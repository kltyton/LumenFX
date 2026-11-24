package com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh;

import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSkeletalMeshInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSkeletalMeshInputs.Component;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSkeletalMeshInputs.DeformedGeometry;
import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs.Transform;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraExternalRandom;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices;
import com.kltyton.lumenfx.client.transform.SceneComponentTransform;
import com.kltyton.lumenfx.effect.niagara.NiagaraTickBehavior.ComponentTickGroup;
import com.kltyton.lumenfx.effect.niagara.mesh.NiagaraSkeletalMeshDefinition;
import com.kltyton.lumenfx.effect.niagara.mesh.NiagaraSkeletalMeshDefinition.Skinning;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.LocalTransform;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.Lod;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.Region;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.SkinWeightBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/** Resolves source components and retains a consistent pair of poses for a skeletal DI. */
public final class NiagaraSkeletalMeshReader {
    public static final class InvalidSamplingRegion extends RuntimeException {
        InvalidSamplingRegion(String identity, String reason) { super(identity + ": " + reason); }
    }

    private static final Vec3 UNIT = new Vec3(1, 1, 1);
    private static final LocalTransform IDENTITY = new LocalTransform(Vec3.ZERO, 0, 0, 0, 1, UNIT);
    private static final LocalTransform MISSING_BONE = new LocalTransform(Vec3.ZERO, 0, 0, 0, 1, Vec3.ZERO);
    record BoneFilter(List<Integer> selected, List<Integer> remainder, int excluded) { }
    record Frame(@Nullable SkeletalMeshAsset mesh, @Nullable Lod lod, List<Region> regions, NiagaraSkeletalMeshSampling sampling,
                         @Nullable DeformedGeometry deformation, @Nullable SkinWeightBuffer skinWeights,
                         @Nullable NiagaraSkeletalPose current, @Nullable NiagaraSkeletalPose previous,
                         List<LocalTransform> sockets, List<LocalTransform> previousSockets, BoneFilter filter,
                         Matrix4f world, Matrix4f previousWorld, Quaternionf rotation, Quaternionf previousRotation, float inverseDelta) { }

    private final String identity;
    private final @Nullable String sourcePath;
    private final NiagaraSkeletalMeshDefinition definition;
    private boolean captured;
    private @Nullable Object lastUserObject;
    private boolean lastUserOverride;
    private @Nullable Component poseSource, capturedSource;
    private double poseFrameTime = Double.NaN;
    private @Nullable Transform lastWorld;
    private @Nullable NiagaraSkeletalGpuSnapshot lastGpu;
    private volatile Frame frame = new Frame(null, null, List.of(), new NiagaraSkeletalMeshSampling(null, List.of(), 0),
            null, null, null, null, List.of(), List.of(),
            new BoneFilter(List.of(), List.of(), -1), new Matrix4f(), new Matrix4f(), new Quaternionf(), new Quaternionf(), 0);

    public NiagaraSkeletalMeshReader(String identity, NiagaraSkeletalMeshDefinition definition) {
        this(identity, null, definition);
    }

    public NiagaraSkeletalMeshReader(String identity, @Nullable String sourcePath, NiagaraSkeletalMeshDefinition definition) {
        this.identity = identity;
        this.sourcePath = sourcePath;
        this.definition = definition;
    }
    public NiagaraSkeletalMeshDefinition definition() { return definition; }
    public NiagaraSkeletalGpuSnapshot gpuSnapshot() {
        var snapshot = new NiagaraSkeletalGpuSnapshot(frame, lastGpu);
        lastGpu = snapshot;
        return snapshot;
    }

    public ComponentTickGroup requiredTickGroup(NiagaraSkeletalMeshInputs inputs, @Nullable NiagaraParameterStore parameters,
                                                 boolean explicitOverride) {
        if (!definition.requireCurrent()) return ComponentTickGroup.TG_PrePhysics;
        var source = NiagaraSkeletalMeshSource.component(identity, sourcePath, definition, inputs,
                userParameter(parameters), explicitOverride);
        return source == null || source.transform() == null ? ComponentTickGroup.TG_PrePhysics : source.tickWindow().afterSkeletalPose();
    }

    private @Nullable Object userParameter(@Nullable NiagaraParameterStore parameters) {
        String name = definition.meshParameter();
        return parameters == null || name.isBlank() || SourceNames.equal(name, "None")
                ? null : parameters.objectBinding(name).value();
    }

    public void capture(NiagaraSkeletalMeshInputs inputs, Map<Identifier, SkeletalMeshAsset> assets,
                        Pose owner, float delta, double originX, double originY, double originZ) {
        capture(inputs, assets, null, false, owner, delta, originX, originY, originZ);
    }

    public boolean capture(NiagaraSkeletalMeshInputs inputs, Map<Identifier, SkeletalMeshAsset> assets,
                           @Nullable NiagaraParameterStore parameters, boolean explicitOverride,
                           Pose owner, float delta, double originX, double originY, double originZ) {
        return capture(inputs, assets, parameters, explicitOverride, owner, delta, originX, originY, originZ, Double.NaN, null);
    }

    public boolean capture(NiagaraSkeletalMeshInputs inputs, Map<Identifier, SkeletalMeshAsset> assets,
                           @Nullable NiagaraParameterStore parameters, boolean explicitOverride,
                           Pose owner, float delta, double originX, double originY, double originZ,
                           double engineTime, @Nullable ClientLevel level) {
        Object userObject = userParameter(parameters);
        boolean hadFrame = captured;
        boolean changedUserObject = hadFrame && (lastUserObject != userObject || lastUserOverride != explicitOverride);
        var component = NiagaraSkeletalMeshSource.component(identity, sourcePath, definition, inputs, userObject, explicitOverride);
        Component source = NiagaraSkeletalMeshSource.resolve(component, definition, assets);
        SkeletalMeshAsset requestedMesh = source == null ? null : source.mesh();
        var resident = requestedMesh == null ? null : requestedMesh.resident();
        var selection = requestedMesh == null ? NiagaraSkeletalMeshSource.Sampling.NO_RESIDENT_LOD
                : NiagaraSkeletalMeshSource.sampling(identity, requestedMesh, Objects.requireNonNull(source), definition,
                        Objects.requireNonNull(resident));
        Lod lod = selection.lod() < 0 || resident == null ? null : resident.get(Objects.requireNonNull(requestedMesh), selection.lod());
        SkeletalMeshAsset mesh = lod == null ? null : requestedMesh;
        SkinWeightBuffer skinWeights = lod == null || source == null ? null : source.activeSkinWeights(selection.lod(), lod);
        List<Region> regions = lod == null ? List.of() : selection.regions();
        var old = frame;
        boolean layoutChanged = old.mesh() != mesh || old.lod() != lod || !old.regions().equals(regions);
        boolean reset = changedUserObject || layoutChanged || !sameComponent(capturedSource, source);
        boolean newPoseFrame = !hadFrame || engineTime != poseFrameTime;
        capturedSource = source;
        var worldData = NiagaraSkeletalMeshWorlds.world(level);
        var sampling = layoutChanged
                ? new NiagaraSkeletalMeshSampling(lod, regions, definition.uvSet(), worldData) : old.sampling();
        var deformation = !definition.readDeformed() || source == null ? null : source.deformation();
        if (deformation != null && (deformation.lod() != selection.lod()
                || lod == null || deformation.vertices() != lod.vertexCount())) deformation = null;
        var current = old.current();
        var previous = newPoseFrame ? current : old.previous();
        var sockets = old.sockets();
        var previousSockets = sockets;
        var componentPose = source == null ? List.<LocalTransform>of() : source.componentPose();
        var lastPose = poseSource == null ? List.<LocalTransform>of() : poseSource.componentPose();
        if (reset || !Objects.equals(lastPose, componentPose)) {
            current = mesh == null ? null : NiagaraSkeletalMeshWorlds.pose(worldData, source, mesh, lod, skinWeights, definition.skinning(), engineTime);
            sockets = mesh == null ? List.of() : sockets(mesh, component != null, componentPose);
            previous = reset || old.current() == null ? current : old.current();
            previousSockets = reset ? sockets : old.sockets();
            poseSource = source;
        } else if (current != null && old.skinWeights() != skinWeights) {
            previous = current;
            current = NiagaraSkeletalMeshWorlds.pose(worldData, source, Objects.requireNonNull(mesh), lod, skinWeights,
                    definition.skinning(), engineTime);
        }
        // The on-the-fly VM binds the active weight buffer to both bone matrix samples.
        if (previous != null && definition.skinning() == Skinning.SKIN_ON_THE_FLY)
            previous = previous.withWeights(skinWeights);
        Transform world = source == null || source.transform() == null ? owner(owner) : source.transform();
        Transform prior = reset || lastWorld == null ? world : lastWorld;
        lastWorld = world;
        frame = new Frame(mesh, lod, regions, sampling, deformation, skinWeights, current, previous, sockets, previousSockets,
                reset ? filter(mesh) : old.filter(), world(world, originX, originY, originZ), world(prior, originX, originY, originZ),
                quaternion(world), quaternion(prior), Math.abs(delta) <= 1.e-8f ? 0 : 1f / delta);
        lastUserObject = userObject;
        lastUserOverride = explicitOverride;
        poseFrameTime = engineTime;
        captured = true;
        return hadFrame && reset;
    }

    private static boolean sameComponent(@Nullable Component first, @Nullable Component second) {
        if (first == second) return true;
        return first != null && second != null && (first.transform() == null) == (second.transform() == null)
                && Objects.equals(first.componentIdentity(), second.componentIdentity());
    }

    private BoneFilter filter(@Nullable SkeletalMeshAsset mesh) {
        if (mesh == null) return new BoneFilter(List.of(), List.of(), -1);
        int excluded = definition.excludeBone() ? mesh.bone(definition.excludedBone()) : -1;
        var filtered = new ArrayList<Integer>();
        for (String name : definition.bones()) {
            int bone = mesh.bone(name);
            if (bone >= 0) filtered.add(bone);
        }
        var unfiltered = new ArrayList<Integer>();
        for (int bone = 0; bone < mesh.bones().size(); bone++) {
            if (definition.bones().isEmpty() || bone != excluded && !filtered.contains(bone)) unfiltered.add(bone);
        }
        return new BoneFilter(List.copyOf(filtered), List.copyOf(unfiltered), excluded);
    }

    private List<LocalTransform> sockets(SkeletalMeshAsset mesh, boolean liveComponent, List<LocalTransform> pose) {
        var result = new ArrayList<LocalTransform>();
        var parent = new SceneComponentTransform();
        var composed = new SceneComponentTransform();
        var referenceSocketMatrix = new Matrix4d();
        for (String name : definition.sockets()) {
            // The asset-only path uses GetComposedRefPoseMatrix, which resolves a bone before a socket.
            int namedBone = liveComponent ? -1 : mesh.bone(name);
            if (namedBone >= 0) {
                result.add(mesh.bones().get(namedBone).componentReference());
                continue;
            }
            SkeletalMeshAsset.Socket match = null;
            for (var socket : mesh.sockets()) if (SourceNames.equal(socket.name(), name)) { match = socket; break; }
            if (match == null) {
                result.add(IDENTITY);
                continue;
            }
            int bone = mesh.bone(match.bone());
            var relative = match.relative();
            if (liveComponent && (bone < 0 || pose.isEmpty())) {
                result.add(relative);
                continue;
            }
            if (bone < 0) {
                result.add(IDENTITY);
                continue;
            }
            // Filtered sockets read the actual component pose even when vertex skinning is disabled.
            var boneTransform = liveComponent ? pose.get(bone) : mesh.bones().get(bone).componentReference();
            parent.source(vectorD(boneTransform.location()), quaternionD(boneTransform), vectorD(boneTransform.scale()));
            if (liveComponent) {
                composed.relative(vectorD(relative.location()), quaternionD(relative), vectorD(relative.scale()), parent);
            } else {
                composed.source(vectorD(relative.location()), quaternionD(relative), vectorD(UNIT));
                composed.source(referenceSocketMatrix.set(parent.matrix()).mul(composed.matrix()));
            }
            var matrix = composed.matrix(); var rotation = composed.rotation(new Quaterniond()); var scale = composed.scale(new Vector3d());
            result.add(new LocalTransform(new Vec3(matrix.m30(), matrix.m31(), matrix.m32()), (float)rotation.x, (float)rotation.y,
                    (float)rotation.z, (float)rotation.w, new Vec3(scale.x, scale.y, scale.z)));
        }
        return List.copyOf(result);
    }

    public void read(String name, int[] input, int[] output) {
        var current = frame;
        int bones = current.mesh() == null ? 0 : current.mesh().bones().size();
        int sockets = current.sockets().size();
        var filtered = current.filter().selected();
        var unfiltered = current.filter().remainder();
        int vertices = current.current() == null ? 0 : current.current().vertices();
        switch (name) {
            case "GetPreSkinnedLocalBounds" -> {
                var bounds = current.mesh() == null ? null : current.mesh().bounds();
                var center = bounds == null ? Vec3.ZERO : bounds.origin();
                var halfExtent = bounds == null ? Vec3.ZERO : bounds.extent();
                put(output, 0, center);
                put(output, 3, center.subtract(halfExtent));
                put(output, 6, center.add(halfExtent));
                put(output, 9, halfExtent.scale(2));
                put(output, 12, halfExtent);
            }
            case "GetBoneCount" -> output[0] = bones;
            case "IsValidBone" -> output[0] = valid(input[0], bones) ? -1 : 0;
            case "GetParentBone" -> output[0] = valid(input[0], bones) ? current.mesh().bones().get(input[0]).parent() : -1;
            case "GetFilteredBoneCount" -> output[0] = filtered.size();
            case "GetUnfilteredBoneCount" -> output[0] = unfiltered.size();
            case "GetFilteredBone" -> output[0] = at(filtered, input[0]);
            case "GetUnfilteredBone" -> output[0] = filtered.isEmpty() && !unfiltered.isEmpty()
                    ? Math.clamp(input[0], 0, unfiltered.size() - 1) : at(unfiltered, input[0]);
            case "GetFilteredSocketCount" -> output[0] = sockets;
            case "GetFilteredSocket" -> output[0] = sockets == 0 ? -1 : bones + Math.clamp(input[0], 0, sockets - 1);
            case "GetFilteredSocketOrBoneCount" -> output[0] = sockets + filtered.size();
            case "GetFilteredSocketOrBone" -> output[0] = filteredIndex(current, input[0], bones);
            case "GetFilteredSocketTransform" -> readSocket(current, input, output);
            case "GetSkinnedBoneData", "GetSkinnedBoneDataWS", "GetSkinnedBoneDataInterpolated", "GetSkinnedBoneDataWSInterpolated" ->
                    readBone(current, name, input, output, bones);
            case "GetVertexCount" -> output[0] = vertices;
            case "IsValidVertex" -> output[0] = input[0] < vertices ? -1 : 0;
            case "GetFilteredVertexCount" -> output[0] = current.sampling().count(false);
            case "IsValidFilteredVertex" -> output[0] = input[0] < vertices ? -1 : 0;
            case "GetFilteredVertex" -> output[0] = current.sampling().element(input[0], false);
            case "GetVertexColor", "GetVertexUV" -> readVertexAttribute(current, name, input, output, vertices);
            case "GetVertexData", "GetSkinnedVertexData", "GetSkinnedVertexDataWS", "GetSkinnedVertexDataInterpolated",
                    "GetSkinnedVertexDataInterpolatedWS" -> readVertex(current, name, input, output, vertices);
            default -> NiagaraSkeletalTriangleReader.read(current, name, input, output);
        }
    }

    public void random(String name, int[] output, NiagaraExternalRandom random) {
        var current = frame;
        switch (name) {
            case "RandomTriCoord", "RandomTriangle", "RandomFilteredTriangle" -> {
                current.sampling().randomTriangle(name, output, random);
                return;
            }
            default -> { }
        }
        int bones = current.mesh() == null ? 0 : current.mesh().bones().size();
        int sockets = current.sockets().size();
        var filter = current.filter();
        int filtered = filter.selected().size(), unfiltered = filter.remainder().size();
        int vertices = current.current() == null ? 0 : current.current().vertices();
        output[0] = switch (name) {
            case "RandomBone" -> randomBone(random, current.mesh() == null ? 1 : bones, filter.excluded());
            case "RandomFilteredBone" -> filtered == 0 ? -1 : filter.selected().get(choice(random, filtered));
            case "RandomUnfilteredBone" -> filtered == 0 ? randomBone(random, unfiltered, filter.excluded())
                    : unfiltered == 0 ? -1 : filter.remainder().get(choice(random, unfiltered));
            case "RandomFilteredSocket" -> sockets == 0 ? -1 : bones + choice(random, sockets);
            case "RandomFilteredSocketOrBone" -> sockets + filtered == 0 ? -1 : filteredIndex(current, choice(random, sockets + filtered), bones);
            case "RandomVertex" -> vertices == 0 ? -1 : choice(random, vertices);
            case "RandomFilteredVertex" -> current.sampling().randomVertex(random);
            default -> throw new IllegalArgumentException("Unknown Niagara skeletal mesh random function: " + name);
        };
    }

    private static int filteredIndex(Frame frame, int index, int bones) {
        int filtered = frame.filter().selected().size();
        if (index < 0) return -1;
        if (index < filtered) return frame.filter().selected().get(index);
        return filtered == 0 && frame.sockets().isEmpty() ? -1 : bones + index - filtered;
    }

    private static int randomBone(NiagaraExternalRandom random, int count, int excluded) {
        int choices = count - (excluded >= 0 ? 1 : 0);
        if (choices <= 0) return -1;
        int index = choice(random, choices);
        return index == excluded ? index + 1 : index;
    }
    private static int choice(NiagaraExternalRandom random, int count) { return (int)(random.next() * count); }
    private static int at(List<Integer> entries, int index) {
        return entries.isEmpty() ? -1 : entries.get(Math.clamp(index, 0, entries.size() - 1));
    }

    private static void readSocket(Frame frame, int[] input, int[] output) {
        var values = frame.sockets();
        if (values.isEmpty()) { write(output, MISSING_BONE, Vec3.ZERO, false); return; }
        var socket = values.get(Math.clamp(input[0], 0, values.size() - 1));
        var result = input[1] == 0 ? socket : apply(socket, frame.world(), frame.rotation(), true);
        write(output, result, Vec3.ZERO, false);
    }

    private static void readBone(Frame frame, String name, int[] input, int[] output, int boneCount) {
        int index = input[0];
        boolean valid = valid(index, boneCount + frame.sockets().size());
        LocalTransform now = valid ? boneOrSocket(frame.current(), frame.sockets(), index, boneCount) : MISSING_BONE;
        LocalTransform before = valid ? boneOrSocket(frame.previous(), frame.previousSockets(), index, boneCount) : MISSING_BONE;
        boolean world = name.contains("WS");
        if (world) {
            now = apply(now, frame.world(), frame.rotation(), valid);
            before = apply(before, frame.previousWorld(), frame.previousRotation(), valid);
        }
        if (name.endsWith("Interpolated")) now = lerp(before, now, Float.intBitsToFloat(input[1]));
        write(output, now, now.location().subtract(before.location()).scale(frame.inverseDelta()), true);
    }

    private static LocalTransform boneOrSocket(@Nullable NiagaraSkeletalPose pose, List<LocalTransform> sockets, int index, int boneCount) {
        return index >= boneCount ? sockets.get(index - boneCount) : pose == null ? MISSING_BONE : pose.bone(index);
    }

    private static void readVertexAttribute(Frame frame, String name, int[] input, int[] output, int vertices) {
        boolean color = name.equals("GetVertexColor");
        var lod = frame.lod();
        var geometry = lod == null || color && !lod.cpuColors() ? null : lod.geometry();
        int count = geometry == null ? 0 : color ? geometry.vertexCount() : vertices;
        if (geometry == null || count == 0 || !color && geometry.textureCoordinateCount() == 0) {
            for (int channel = 0; channel < (color ? 4 : 2); channel++) output[channel] = bits(color ? 1 : 0);
            return;
        }
        int vertex = Math.clamp(input[0], 0, count - 1);
        if (color) for (int channel = 0; channel < 4; channel++) {
            float value = geometry.color(vertex, channel);
            output[channel] = bits(value);
        } else {
            int uv = Math.clamp(input[1], 0, geometry.textureCoordinateCount() - 1);
            for (int axis = 0; axis < 2; axis++) output[axis] = bits(geometry.textureCoordinate(uv, vertex, axis));
        }
    }

    private static void readVertex(Frame frame, String name, int[] input, int[] output, int vertices) {
        if (name.equals("GetVertexData")) {
            var data = vertices == 0 || frame.lod() == null ? new NiagaraSkeletalPose.Vertex(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO)
                    : NiagaraSkeletalPose.referenceVertex(Objects.requireNonNull(frame.lod().geometry()), Math.clamp(input[0], 0, vertices - 1));
            put(output, 0, data.position()); put(output, 3, data.normal()); put(output, 6, data.bitangent()); put(output, 9, data.tangent());
            return;
        }
        int vertex = vertices == 0 ? -1 : Math.clamp(input[0], 0, vertices - 1);
        boolean world = name.endsWith("WS");
        if (vertex < 0) {
            var fallback = NiagaraSkeletalPose.DEFAULT;
            put(output, 0, world ? vec(frame.world().getTranslation(new Vector3f())) : fallback.position());
            put(output, 3, Vec3.ZERO);
            put(output, 6, fallback.normal());
            put(output, 9, fallback.bitangent());
            put(output, 12, fallback.tangent());
            return;
        }
        var current = frame.current() == null ? NiagaraSkeletalPose.DEFAULT : frame.current().vertex(vertex);
        var previous = frame.previous() == null ? NiagaraSkeletalPose.DEFAULT : frame.previous().vertex(vertex);
        if (world) current = worldVertex(current, frame.world());
        Vec3 before = world ? vec(frame.previousWorld().transformPosition(vector(previous.position()))) : previous.position();
        Vec3 now = current.position();
        Vec3 position = name.contains("Interpolated") ? before.lerp(now, Float.intBitsToFloat(input[1])) : now;
        put(output, 0, position);
        put(output, 3, now.subtract(before).scale(frame.inverseDelta()));
        put(output, 6, current.normal());
        put(output, 9, current.bitangent());
        put(output, 12, current.tangent());
    }

    private static NiagaraSkeletalPose.Vertex worldVertex(NiagaraSkeletalPose.Vertex data, Matrix4f matrix) {
        return new NiagaraSkeletalPose.Vertex(vec(matrix.transformPosition(vector(data.position()))),
                unit(matrix, data.normal()), unit(matrix, data.bitangent()), unit(matrix, data.tangent()));
    }
    private static Vec3 unit(Matrix4f matrix, Vec3 value) { return vec(matrix.transformDirection(vector(value))).normalize(); }

    private static Transform owner(Pose pose) {
        return new Transform(pose.position().x * 100, pose.position().z * 100, pose.position().y * 100,
                -pose.x(), -pose.z(), -pose.y(), pose.w(), pose.scaleX(), pose.scaleZ(), pose.scaleY());
    }
    private static Matrix4f world(Transform pose, double x, double y, double z) {
        return new Matrix4f(NiagaraComponentMatrices.srt(pose.qx(), pose.qy(), pose.qz(), pose.qw(), pose.scaleX(), pose.scaleY(),
                pose.scaleZ(), pose.x() - x, pose.y() - y, pose.z() - z, new Matrix4d()));
    }
    private static Quaternionf quaternion(Transform value) {
        return new Quaternionf((float)value.qx(), (float)value.qy(), (float)value.qz(), (float)value.qw());
    }
    private static Quaternionf quaternion(LocalTransform value) { return new Quaternionf(value.qx(), value.qy(), value.qz(), value.qw()); }
    private static Quaterniond quaternionD(LocalTransform value) { return new Quaterniond(value.qx(), value.qy(), value.qz(), value.qw()); }
    private static Vector3d vectorD(Vec3 value) { return new Vector3d(value.x, value.y, value.z); }
    private static Vector3f vector(Vec3 value) { return new Vector3f((float)value.x, (float)value.y, (float)value.z); }
    private static Vec3 vec(Vector3f value) { return new Vec3(value.x, value.y, value.z); }
    private static LocalTransform apply(LocalTransform value, Matrix4f world, Quaternionf orientation, boolean rotate) {
        var position = vec(world.transformPosition(vector(value.location())));
        var rotation = rotate ? new Quaternionf(orientation).mul(quaternion(value)) : quaternion(value);
        return new LocalTransform(position, rotation.x, rotation.y, rotation.z, rotation.w,
                vec(world.transformDirection(vector(value.scale()))));
    }
    private static LocalTransform lerp(LocalTransform start, LocalTransform end, float alpha) {
        var rotation = quaternion(start).slerp(quaternion(end), alpha);
        return new LocalTransform(start.location().lerp(end.location(), alpha), rotation.x, rotation.y, rotation.z, rotation.w,
                start.scale().lerp(end.scale(), alpha));
    }
    private static void write(int[] output, LocalTransform transform, Vec3 velocity, boolean includeVelocity) {
        put(output, 0, transform.location());
        output[3] = bits(transform.qx()); output[4] = bits(transform.qy()); output[5] = bits(transform.qz()); output[6] = bits(transform.qw());
        put(output, 7, transform.scale());
        if (includeVelocity) put(output, 10, velocity);
    }
    private static void put(int[] output, int offset, Vec3 value) {
        output[offset] = bits(value.x); output[offset + 1] = bits(value.y); output[offset + 2] = bits(value.z);
    }
    private static int bits(double value) { return Float.floatToRawIntBits((float)value); }
    private static boolean valid(int index, int count) { return index >= 0 && index < count; }
}
