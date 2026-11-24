package com.kltyton.lumenfx.client.simulation.niagara.interfaces.physics;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRigidMeshInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRigidMeshInputs.Actor;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRigidMeshInputs.Body;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRigidMeshInputs.Collision;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRigidMeshInputs.Component;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRigidMeshInputs.DistanceField;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRigidMeshInputs.Overlap;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRigidMeshInputs.Shape;
import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs.Transform;
import com.kltyton.lumenfx.client.render.math.NativeSceneMath;
import com.kltyton.lumenfx.client.transform.SceneComponentTransform;
import com.kltyton.lumenfx.effect.niagara.interfaces.physics.NiagaraPhysicsAsset.Kind;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.logging.LogUtils;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4dc;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/** Per-system actor discovery and the rigid collision proxy published after CPU simulation. */
public final class NiagaraRigidMeshCollision {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Vector3d ONE = new Vector3d(1);
    private static final Comparator<Component> COMPONENT_ORDER = Comparator.comparing(Component::kind).thenComparingLong(Component::order);
    private static final int WORDS = NiagaraCollisionTransforms.WORDS;

    public record Definition(List<String> actors, List<String> actorTags, List<String> componentTags, boolean movableOnly,
                             boolean complex, boolean byObjectType, boolean globalSearch, boolean forceGlobal,
                             boolean globalIfUnscripted, int capacity) {
        public Definition {
            actors = List.copyOf(actors); actorTags = List.copyOf(actorTags); componentTags = List.copyOf(componentTags);
            if (capacity < 0 || capacity > Integer.MAX_VALUE / (2 * WORDS * Float.BYTES))
                throw new IllegalArgumentException("Invalid Niagara Rigid Mesh primitive capacity");
        }
        public static Definition read(JsonObject source) {
            return new Definition(names(source, "actors"), names(source, "actor_tags"), names(source, "component_tags"),
                    source.get("movable_only").getAsBoolean(), source.get("complex").getAsBoolean(),
                    source.get("object_type").getAsBoolean(), source.get("global_search").getAsBoolean(),
                    source.get("force_global_search").getAsBoolean(), source.get("global_if_unscripted").getAsBoolean(),
                    source.get("max_primitives").getAsInt());
        }
        private static List<String> names(JsonObject source, String member) {
            var values = new ArrayList<String>();
            for (var value : source.getAsJsonArray(member)) values.add(value.getAsString());
            return values;
        }
    }

    public enum Buffer { WORLD, INVERSE, EXTENT, SCALE, TYPE, DISTANCE_FIELD }

    public static final class Frame {
        private final int capacity, boxes, capsulesAt, count;
        private final Vec3 minimum, maximum;
        private final float[] world, inverse, extents, scales;
        private final int[] types;
        private final @Nullable DistanceField[] distanceFields;
        private final Map<ElementKey, Integer> indices;

        private Frame(int capacity, int boxes, int capsulesAt, int count, Vec3 minimum, Vec3 maximum,
                      float[] world, float[] inverse, float[] extents, float[] scales, int[] types,
                      @Nullable DistanceField[] distanceFields, Map<ElementKey, Integer> indices) {
            this.capacity = capacity; this.boxes = boxes; this.capsulesAt = capsulesAt; this.count = count;
            this.minimum = minimum; this.maximum = maximum; this.world = world; this.inverse = inverse;
            this.extents = extents; this.scales = scales; this.types = types; this.distanceFields = distanceFields;
            this.indices = Map.copyOf(indices);
        }
        public int capacity() { return capacity; }
        public int boxes() { return boxes; }
        public int capsulesAt() { return capsulesAt; }
        public int count() { return count; }
        public Vec3 minimum() { return minimum; }
        public Vec3 maximum() { return maximum; }
        public int bytes(Buffer kind) {
            return Integer.BYTES * capacity * switch (kind) {
                case WORLD, INVERSE -> 2 * WORDS;
                case EXTENT, SCALE -> 4;
                case TYPE, DISTANCE_FIELD -> 1;
            };
        }
        public void write(Buffer kind, ByteBuffer output) {
            switch (kind) {
                case WORLD -> output.asFloatBuffer().put(world);
                case INVERSE -> output.asFloatBuffer().put(inverse);
                case EXTENT -> output.asFloatBuffer().put(extents);
                case SCALE -> output.asFloatBuffer().put(scales);
                case TYPE -> output.asIntBuffer().put(types);
                case DISTANCE_FIELD -> throw new IllegalArgumentException("Rigid mesh DF indices depend on the render scene");
            }
            output.position(output.position() + bytes(kind));
        }
        public @Nullable DistanceField distanceField(int index) { return distanceFields[index]; }
    }

    private record ElementKey(Object component, Object geometry, int body, int shape, Kind kind) { }
    private record Element(Component component, Body body, Shape shape, ElementKey key) { }
    private final String identity;
    private final Definition definition;
    private NiagaraRigidMeshInputs scene = NiagaraRigidMeshInputs.EMPTY;
    private Vec3 tileOrigin = Vec3.ZERO;
    private List<String> found = List.of();
    private @Nullable Frame frame;
    private boolean scripted, exceeded;

    public NiagaraRigidMeshCollision(String identity, Definition definition) { this.identity = identity; this.definition = definition; }
    public void scripted() { scripted = true; }
    public void begin(NiagaraRigidMeshInputs inputs, Vec3 origin) { scene = inputs; tileOrigin = origin; }
    public @Nullable Frame frame() { return frame; }

    public synchronized boolean find(float x, float y, float z, float qx, float qy, float qz, float qw,
                                     float width, float height, float depth, int channel) {
        var query = new Overlap(tileOrigin.add(x, y, z), qx, qy, qz, qw, new Vec3(width * .5, height * .5, depth * .5),
                channel, definition.byObjectType(), definition.complex());
        List<String> previous = found;
        found = selected(scene.overlap().actors(query));
        return !found.equals(previous);
    }

    public boolean contains(float x, float y, float z) {
        var captured = frame;
        if (captured == null) return false;
        Vec3 world = tileOrigin.add(x, y, z);
        return world.x >= captured.minimum.x && world.y >= captured.minimum.y && world.z >= captured.minimum.z
                && world.x <= captured.maximum.x && world.y <= captured.maximum.y && world.z <= captured.maximum.z;
    }

    public void postSimulate() {
        if (definition.globalSearch() && (definition.forceGlobal() || definition.globalIfUnscripted() && !scripted))
            found = selected(scene.actors().keySet());
        else found = found.stream().filter(scene.actors()::containsKey).toList();
        var actors = new TreeSet<String>(SourceNames.ASCII_CASE_INSENSITIVE);
        actors.addAll(scene.sourceActors().getOrDefault(identity, definition.actors()));
        actors.addAll(found);
        actors.retainAll(scene.actors().keySet());
        if (actors.isEmpty()) { frame = null; exceeded = false; return; }

        var components = new ArrayList<Component>();
        var seen = new HashSet<Object>();
        for (String name : actors) for (var component : scene.actors().get(name).components())
            if (!component.bodies().isEmpty() && tags(component.tags(), definition.componentTags()) && seen.add(component.identity()))
                components.add(component);
        components.sort(COMPONENT_ORDER);
        var elements = new ArrayList<Element>();
        int boxes = 0, capsulesAt = 0;
        for (Kind kind : Kind.values()) {
            for (Component component : components) for (int bodyIndex = 0; bodyIndex < component.bodies().size(); bodyIndex++) {
                Body body = component.bodies().get(bodyIndex);
                if (body.shapes().isEmpty()) {
                    if (kind == Kind.BOX) {
                        var bounds = component.localBounds();
                        var shape = new Shape(Kind.BOX, bounds.origin(), Vec3.ZERO, bounds.extent().scale(2), Collision.QUERY_AND_PHYSICS);
                        elements.add(element(component, body, bodyIndex, shape, 0));
                    }
                    continue;
                }
                for (int shapeIndex = 0; shapeIndex < body.shapes().size(); shapeIndex++) {
                    var shape = body.shapes().get(shapeIndex);
                    if (shape.kind() == kind && shape.collision() != Collision.NONE)
                        elements.add(element(component, body, bodyIndex, shape, shapeIndex));
                }
            }
            if (kind == Kind.BOX) boxes = elements.size();
            if (kind == Kind.SPHERE) capsulesAt = elements.size();
        }
        if (elements.size() >= definition.capacity()) {
            if (!exceeded && elements.size() > definition.capacity())
                LOGGER.warn("Niagara Rigid Mesh Collision {} found {} primitives for a limit of {}", identity, elements.size(), definition.capacity());
            exceeded = true;
            return;
        }
        exceeded = false;
        frame = pack(components, elements, boxes, capsulesAt);
    }

    private List<String> selected(Iterable<String> candidates) {
        var names = new TreeSet<String>(SourceNames.ASCII_CASE_INSENSITIVE);
        for (String name : candidates) {
            Actor actor = scene.actors().get(name);
            if (actor != null && (!definition.movableOnly() || actor.movable()) && tags(actor.tags(), definition.actorTags())) names.add(name);
        }
        return names.stream().map(SourceNames::canonical).sorted(Comparator
                .comparingLong((String name) -> scene.actors().get(name).order()).thenComparing(SourceNames.ASCII_CASE_INSENSITIVE)).toList();
    }

    private static boolean tags(List<String> present, List<String> wanted) {
        if (wanted.isEmpty()) return true;
        for (String tag : wanted)
            if (tag.isEmpty() || SourceNames.equal(tag, "None") || present.stream().anyMatch(value -> SourceNames.equal(tag, value))) return true;
        return false;
    }

    private Frame pack(List<Component> components, List<Element> elements, int boxes, int capsulesAt) {
        int capacity = definition.capacity();
        var world = new float[capacity * 2 * WORDS];
        var inverse = new float[world.length];
        var extents = new float[capacity * 4];
        var scales = new float[capacity * 4];
        var types = new int[capacity];
        var fields = new DistanceField[capacity];
        var indices = new HashMap<ElementKey, Integer>();
        var bodyPose = new SceneComponentTransform();
        var elementPose = new SceneComponentTransform();
        var rotation = new double[4];
        var previous = frame;
        int previousOffset = capacity * WORDS;
        for (int index = 0; index < elements.size(); index++) {
            var element = elements.get(index);
            var shape = element.shape();
            Transform transform = element.body().world();
            source(bodyPose, transform, tileOrigin);
            var angles = shape.pitchYawRoll();
            NativeSceneMath.rotatorQuaternion(angles.x, angles.y, angles.z, rotation);
            var orientation = new Quaterniond(rotation[0], rotation[1], rotation[2], rotation[3]);
            elementPose.relative(vector(shape.center()), orientation, ONE, bodyPose);
            int offset = index * WORDS;
            NiagaraCollisionTransforms.write(elementPose.matrix(), offset, world, inverse);
            Integer oldIndex = previous == null ? null : previous.indices.get(element.key());
            if (oldIndex == null) {
                System.arraycopy(world, offset, world, previousOffset + offset, WORDS);
                System.arraycopy(inverse, offset, inverse, previousOffset + offset, WORDS);
            } else {
                System.arraycopy(previous.world, oldIndex * WORDS, world, previousOffset + offset, WORDS);
                System.arraycopy(previous.inverse, oldIndex * WORDS, inverse, previousOffset + offset, WORDS);
            }
            var extent = shape.extent();
            int at = index * 4;
            extents[at] = (float)extent.x; extents[at + 1] = (float)extent.y; extents[at + 2] = (float)extent.z;
            scales[at] = (float)transform.scaleX(); scales[at + 1] = (float)transform.scaleY(); scales[at + 2] = (float)transform.scaleZ();
            types[index] = shape.collision() == Collision.QUERY_AND_PHYSICS ? 1 : 0;
            fields[index] = element.component().distanceField();
            indices.put(element.key(), index);
        }

        var min = new Vector3d(Float.MAX_VALUE);
        var max = new Vector3d(-Float.MAX_VALUE);
        for (Component component : components) {
            source(bodyPose, component.world(), tileOrigin);
            Matrix4dc matrix = bodyPose.matrix();
            var bounds = component.localBounds();
            var center = matrix.transformPosition(vector(bounds.origin()));
            Vec3 e = bounds.extent();
            var extent = new Vector3d(Math.abs(matrix.m00()) * e.x + Math.abs(matrix.m10()) * e.y + Math.abs(matrix.m20()) * e.z,
                    Math.abs(matrix.m01()) * e.x + Math.abs(matrix.m11()) * e.y + Math.abs(matrix.m21()) * e.z,
                    Math.abs(matrix.m02()) * e.x + Math.abs(matrix.m12()) * e.y + Math.abs(matrix.m22()) * e.z);
            min.min(new Vector3d(center).sub(extent));
            max.max(center.add(extent));
        }
        return new Frame(capacity, boxes, capsulesAt, elements.size(), new Vec3(min.x, min.y, min.z), new Vec3(max.x, max.y, max.z),
                world, inverse, extents, scales, types, fields, indices);
    }

    private static Element element(Component component, Body body, int bodyIndex, Shape shape, int shapeIndex) {
        return new Element(component, body, shape, new ElementKey(component.identity(), component.geometryIdentity(), bodyIndex, shapeIndex, shape.kind()));
    }
    private static void source(SceneComponentTransform output, Transform pose, Vec3 origin) {
        output.source(new Vector3d(pose.x() - origin.x, pose.y() - origin.y, pose.z() - origin.z),
                new Quaterniond(pose.qx(), pose.qy(), pose.qz(), pose.qw()), new Vector3d(pose.scaleX(), pose.scaleY(), pose.scaleZ()));
    }
    private static Vector3d vector(Vec3 value) { return new Vector3d(value.x, value.y, value.z); }
}
