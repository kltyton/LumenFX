package com.kltyton.lumenfx.client.simulation.niagara.interfaces.physics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSkeletalMeshInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSkeletalMeshInputs.Component;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSkeletalMeshInputs.PhysicsSource;
import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs.Transform;
import com.kltyton.lumenfx.client.render.math.NativeSceneMath;
import com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices;
import com.kltyton.lumenfx.client.transform.SceneComponentTransform;
import com.kltyton.lumenfx.effect.niagara.interfaces.physics.NiagaraPhysicsAsset;
import com.kltyton.lumenfx.effect.niagara.interfaces.physics.NiagaraPhysicsAsset.Kind;
import com.kltyton.lumenfx.effect.niagara.interfaces.physics.NiagaraPhysicsAsset.Primitive;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.LocalTransform;
import com.mojang.logging.LogUtils;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/** Builds the source DI's current, previous and reference shape transforms before GPU simulation. */
public final class NiagaraPhysicsAssetReader {
    public static final int MAX_ELEMENTS = 100;
    public static final int WORDS_PER_MATRIX = NiagaraCollisionTransforms.WORDS;
    public static final int WORDS_PER_POSE = MAX_ELEMENTS * WORDS_PER_MATRIX;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Vec3 EMPTY_EXTENT = new Vec3(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);
    private static final Vector3d ONE = new Vector3d(1);

    public enum Buffer { WORLD, INVERSE, EXTENT, TYPE }

    public record Definition(String actor, String parameter, @Nullable NiagaraPhysicsAsset fallback) {
        public static Definition read(JsonObject source, Map<Identifier, SkeletalMeshAsset> meshes) {
            var value = source.get("default_source");
            NiagaraPhysicsAsset asset = null;
            if (value != null && value.isJsonObject()) {
                var data = value.getAsJsonObject();
                var elements = new ArrayList<Primitive>();
                for (var item : data.getAsJsonArray("elements")) {
                    var element = item.getAsJsonObject();
                    elements.add(new Primitive(element.get("bone").getAsString(),
                            Kind.valueOf(element.get("kind").getAsString().toUpperCase(Locale.ROOT)),
                            vector(element.getAsJsonArray("center")), vector(element.getAsJsonArray("rotation")),
                            vector(element.getAsJsonArray("extent")), element.get("query_and_physics").getAsBoolean()));
                }
                var preview = data.get("skeletal_mesh_parameter");
                SkeletalMeshAsset mesh = null;
                if (preview != null && preview.isJsonObject()) {
                    Identifier id = Identifier.parse(preview.getAsJsonObject().get("asset").getAsString());
                    mesh = Objects.requireNonNull(meshes.get(id), "Missing Physics Asset preview mesh " + id);
                }
                asset = new NiagaraPhysicsAsset(elements, mesh);
            }
            return new Definition(source.get("source_actor").getAsString(), source.get("mesh_parameter").getAsString(), asset);
        }
        private static Vec3 vector(JsonArray data) {
            return new Vec3(data.get(0).getAsDouble(), data.get(1).getAsDouble(), data.get(2).getAsDouble());
        }
    }

    public static final class Frame {
        private final int boxes, spheres, total;
        private final Vec3 origin, extent;
        private final float[][] world, inverse;
        private final float[] elements;
        private final int[] types;

        private Frame(int boxes, int spheres, int total, Vec3 origin, Vec3 extent, float[][] world,
                      float[][] inverse, float[] elements, int[] types) {
            this.boxes = boxes; this.spheres = spheres; this.total = total;
            this.origin = origin; this.extent = extent;
            this.world = world; this.inverse = inverse; this.elements = elements; this.types = types;
        }
        public int boxes() { return boxes; }
        public int capsuleOffset() { return boxes + spheres; }
        public int size() { return total; }
        public Vec3 origin() { return origin; }
        public Vec3 extent() { return extent; }

        public static int bytes(Buffer buffer) {
            return Integer.BYTES * switch (buffer) {
                case WORLD, INVERSE -> WORDS_PER_POSE * 3;
                case EXTENT -> MAX_ELEMENTS * 4;
                case TYPE -> MAX_ELEMENTS;
            };
        }
        public void write(Buffer buffer, ByteBuffer destination) {
            switch (buffer) {
                case WORLD, INVERSE -> {
                    var output = destination.asFloatBuffer();
                    for (float[] pose : buffer == Buffer.WORLD ? world : inverse) output.put(pose);
                }
                case EXTENT -> destination.asFloatBuffer().put(elements);
                case TYPE -> destination.asIntBuffer().put(types);
            }
            destination.position(destination.position() + bytes(buffer));
        }
    }

    private record Source(@Nullable Component component, SkeletalMeshAsset mesh, NiagaraPhysicsAsset asset) { }
    private record Shape(Source source, Primitive primitive, int bone) { }
    private final String identity;
    private final Definition definition;
    private @Nullable Frame frame;
    private boolean overflowReported;

    public NiagaraPhysicsAssetReader(String identity, Definition definition) {
        this.identity = identity;
        this.definition = definition;
    }
    public @Nullable Frame frame() { return frame; }

    public void capture(NiagaraSkeletalMeshInputs inputs, Pose owner) {
        var collector = inputs.physicsCollectors().get(identity);
        var preferred = collector == null ? definition.fallback() : collector.primary();
        var sources = new ArrayList<Source>();
        if (collector != null) for (PhysicsSource source : collector.sources()) add(sources, source.component(), source.asset());
        var component = component(inputs);
        add(sources, component, preferred != null ? preferred : component == null ? null : component.physicsAsset());

        var previous = frame;
        if (sources.isEmpty() && previous != null) return;
        var shapes = new ArrayList<Shape>();
        int boxes = 0, spheres = 0;
        for (Kind kind : Kind.values()) for (Source source : sources) for (Primitive primitive : source.asset().elements()) {
            if (primitive.kind() != kind) continue;
            int bone = source.mesh().bone(primitive.bone());
            if (bone < 0) continue;
            shapes.add(new Shape(source, primitive, bone));
            if (kind == Kind.BOX) boxes++;
            if (kind == Kind.SPHERE) spheres++;
        }
        if (shapes.size() >= MAX_ELEMENTS) {
            if (!overflowReported) LOGGER.warn("Niagara Physics Asset {} reached the source primitive limit {}", identity, MAX_ELEMENTS);
            overflowReported = true;
            if (previous != null) return;
            shapes.clear();
            boxes = spheres = 0;
        } else overflowReported = false;

        boolean sameLayout = previous != null && previous.boxes == boxes && previous.spheres == spheres && previous.total == shapes.size();
        float[] current = new float[WORDS_PER_POSE], currentInverse = new float[WORDS_PER_POSE];
        float[] rest = sameLayout ? previous.world[2] : new float[WORDS_PER_POSE];
        float[] restInverse = sameLayout ? previous.inverse[2] : new float[WORDS_PER_POSE];
        float[] extents = sameLayout ? previous.elements : new float[MAX_ELEMENTS * 4];
        int[] types = sameLayout ? previous.types : new int[MAX_ELEMENTS];
        var relative = new SceneComponentTransform();
        var inverseParent = new Matrix4d();
        if (collector != null && collector.frame() != null)
            NiagaraComponentMatrices.inverse(transform(collector.frame(), new Matrix4d()), inverseParent);
        var componentWorld = new SceneComponentTransform();
        var bonePose = new SceneComponentTransform();
        var elementPose = new SceneComponentTransform();
        var relativeMatrix = new Matrix4d();
        var rotation = new double[4];
        for (int index = 0; index < shapes.size(); index++) {
            var shape = shapes.get(index);
            var primitive = shape.primitive();
            var source = shape.source();
            LocalTransform reference = source.mesh().bones().get(shape.bone()).componentReference();
            var active = source.component();
            var componentTransform = active == null ? null : active.transform();
            if (componentTransform == null) componentWorld.owner(owner);
            else componentWorld.source(new Vector3d(componentTransform.x(), componentTransform.y(), componentTransform.z()),
                    new Quaterniond(componentTransform.qx(), componentTransform.qy(), componentTransform.qz(), componentTransform.qw()),
                    new Vector3d(componentTransform.scaleX(), componentTransform.scaleY(), componentTransform.scaleZ()));
            if (componentTransform != null) {
                LocalTransform animated = active.componentPose().isEmpty() ? reference : active.componentPose().get(shape.bone());
                composeBone(bonePose, animated, componentWorld);
                relative.source(relativeMatrix.set(inverseParent).mul(bonePose.matrix()));
            } else {
                relative.source(relativeMatrix.set(inverseParent).mul(componentWorld.matrix()));
                composeBone(bonePose, reference, relative);
            }
            var angles = primitive.pitchYawRoll();
            NativeSceneMath.rotatorQuaternion(angles.x, angles.y, angles.z, rotation);
            var orientation = new Quaterniond(rotation[0], rotation[1], rotation[2], rotation[3]);
            elementPose.relative(vector(primitive.center()), orientation, ONE, componentTransform == null ? bonePose : relative);
            NiagaraCollisionTransforms.write(elementPose.matrix(), index * WORDS_PER_MATRIX, current, currentInverse);
            if (!sameLayout) {
                bonePose.source(vector(reference.location()), quaternion(reference), vector(reference.scale()));
                elementPose.relative(vector(primitive.center()), orientation, ONE, bonePose);
                NiagaraCollisionTransforms.write(elementPose.matrix(), index * WORDS_PER_MATRIX, rest, restInverse);
                int offset = index * 4;
                var extent = primitive.extent();
                extents[offset] = (float)extent.x; extents[offset + 1] = (float)extent.y; extents[offset + 2] = (float)extent.z;
                types[index] = primitive.queryAndPhysics() ? 1 : 0;
            }
        }

        Vec3 origin, extent;
        if (previous != null) {
            origin = previous.origin; extent = previous.extent;
        } else if (sources.isEmpty()) {
            origin = Vec3.ZERO; extent = EMPTY_EXTENT;
        } else {
            var minimum = new Vector3d(Double.POSITIVE_INFINITY);
            var maximum = new Vector3d(Double.NEGATIVE_INFINITY);
            for (var source : sources) {
                var bounds = source.mesh().bounds();
                minimum.min(vector(bounds.origin()).sub(vector(bounds.extent())));
                maximum.max(vector(bounds.origin()).add(vector(bounds.extent())));
            }
            origin = new Vec3((minimum.x + maximum.x) * .5, (minimum.y + maximum.y) * .5, (minimum.z + maximum.z) * .5);
            extent = new Vec3(maximum.x - minimum.x, maximum.y - minimum.y, maximum.z - minimum.z);
        }
        frame = new Frame(boxes, spheres, shapes.size(), origin, extent,
                new float[][] {current, sameLayout ? previous.world[0] : current, rest},
                new float[][] {currentInverse, sameLayout ? previous.inverse[0] : currentInverse, restInverse}, extents, types);
    }

    private @Nullable Component component(NiagaraSkeletalMeshInputs inputs) {
        String parameter = definition.parameter();
        if (!parameter.isEmpty() && !SourceNames.equal(parameter, "None")) return inputs.parameters().get(parameter);
        var actor = inputs.actors().get(definition.actor());
        if (actor != null) return actor.isEmpty() ? null : actor.getFirst();
        for (var component : inputs.attachments()) if (component.mesh() != null) return component;
        return null;
    }
    private static void add(List<Source> sources, @Nullable Component component, @Nullable NiagaraPhysicsAsset asset) {
        if (asset == null) return;
        var mesh = component == null || component.mesh() == null ? asset.previewMesh() : component.mesh();
        if (mesh != null) sources.add(new Source(component, mesh, asset));
    }
    private static void composeBone(SceneComponentTransform result, LocalTransform bone, SceneComponentTransform component) {
        result.relative(vector(bone.location()), quaternion(bone), vector(bone.scale()), component);
    }
    private static Vector3d vector(Vec3 value) { return new Vector3d(value.x, value.y, value.z); }
    private static Quaterniond quaternion(LocalTransform value) { return new Quaterniond(value.qx(), value.qy(), value.qz(), value.qw()); }
    private static Matrix4d transform(Transform value, Matrix4d destination) {
        return NiagaraComponentMatrices.srt(value.qx(), value.qy(), value.qz(), value.qw(),
                value.scaleX(), value.scaleY(), value.scaleZ(), value.x(), value.y(), value.z(), destination);
    }
}
