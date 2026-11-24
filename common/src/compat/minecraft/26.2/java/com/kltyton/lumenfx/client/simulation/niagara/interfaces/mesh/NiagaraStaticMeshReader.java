package com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh;

import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.api.client.parameter.NiagaraStaticMeshInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraStaticMeshInputs.CpuBuffers;
import com.kltyton.lumenfx.api.client.parameter.NiagaraStaticMeshInputs.GpuBuffers;
import com.kltyton.lumenfx.api.client.parameter.NiagaraStaticMeshInputs.Source;
import com.kltyton.lumenfx.api.client.parameter.NiagaraStaticMeshInputs.VertexColors;
import com.kltyton.lumenfx.client.render.math.SourceColorMath;
import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs.Transform;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.NiagaraStaticMeshSampling.Selection;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraExternalRandom;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.NiagaraStaticMeshSampling.TangentBasis;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStoreSources;
import com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices;
import com.kltyton.lumenfx.effect.niagara.mesh.NiagaraStaticMeshDefinition;
import com.kltyton.lumenfx.effect.niagara.mesh.NiagaraStaticMeshDefinition.SourceMode;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.parameter.store.ScriptParameterStoreDefinition.ObjectValue;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshDistanceField;
import com.mojang.logging.LogUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/** Owns source selection, LOD and transform history for one mesh data-interface instance. */
public final class NiagaraStaticMeshReader {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Transform IDENTITY = new Transform(0, 0, 0, 0, 0, 0, 1, 1, 1, 1);
    private static final Set<String> FUNCTIONS_WITHOUT_CPU_BUFFERS = Set.of(
            "IsValid", "GetPreSkinnedLocalBounds", "GetMeshBounds", "GetMeshBoundsWS",
            "GetLocalToWorld", "GetLocalToWorldInverseTransposed", "GetWorldVelocity", "SetInstanceIndex",
            "GetSocketCount", "GetFilteredSocketCount", "GetUnfilteredSocketCount",
            "RandomSocket", "RandomFilteredSocket", "RandomUnfilteredSocket",
            "GetSocketTransform", "GetSocketTransformWS", "GetSocketTransformWSInterpolated",
            "GetFilteredSocketTransform", "GetFilteredSocketTransformWS", "GetFilteredSocketTransformWSInterpolated",
            "GetUnfilteredSocketTransform", "GetUnfilteredSocketTransformWS", "GetUnfilteredSocketTransformWSInterpolated",
            "GetFilteredSocket", "GetUnfilteredSocket");

    private record Frame(NiagaraStaticMeshSampling sampling, Matrix4f transform, Matrix4f inverseTransposed,
                         Matrix4f previous, Matrix4f previousInverseTransposed, Quaternionf rotation, Quaternionf previousRotation,
                         Vec3 ownerOffset, Vec3 physicsVelocity,
                         float inverseDelta, int instance, @Nullable NiagaraMeshUvTree uvMapping,
                         @Nullable StaticMeshDistanceField distanceField, @Nullable VertexColors vertexColors,
                         CpuBuffers cpuBuffers, GpuBuffers gpuBuffers) {
        int colorVertexCount() {
            if (vertexColors != null) return vertexColors.cpuAccess() ? vertexColors.size() : 0;
            return sampling.colorVertexCount(cpuBuffers);
        }

        float color(int vertex, int component) {
            if (vertexColors == null) return sampling.color(vertex, component, cpuBuffers);
            if (colorVertexCount() == 0) return 1;
            int value = vertexColors.channel(Math.clamp(vertex, 0, vertexColors.size() - 1), component);
            return component < 3 ? SourceColorMath.linear(value) : value / 255f;
        }
    }
    private final String identity;
    private final NiagaraStaticMeshDefinition definition;
    private @Nullable StaticMeshAsset asset;
    private int lod = -1, instance;
    private volatile int requestedUvChannel = -1;
    private @Nullable NiagaraMeshUvTree uvMapping;
    private @Nullable StaticMeshDistanceField distanceField;
    private @Nullable Source selectedSource;
    private @Nullable ObjectValue savedObject;
    private @Nullable Source savedSource;
    private StaticMeshAsset.@Nullable CpuBufferAccess lodCpuBufferAccess;
    private StaticMeshAsset.@Nullable ShaderBuffers lodGpuBufferPolicy;
    private boolean hasLodGpuBufferPolicy;
    private Transform owner = IDENTITY, current = IDENTITY, previous = IDENTITY;
    private Vec3 ownerOffset = Vec3.ZERO;
    private double originX, originY, originZ;
    private float inverseDelta;
    private boolean initialized, requiresCpuAccess, gpuOnly, defaultMeshBufferViews, previousFallbackUnavailable;
    private NiagaraStaticMeshSampling sampling;
    private volatile Frame frame;

    public NiagaraStaticMeshReader(String identity, NiagaraStaticMeshDefinition definition) {
        this.identity = identity;
        this.definition = definition;
        instance = definition.instance();
        sampling = new NiagaraStaticMeshSampling(null, -1, null, definition);
        frame = snapshot();
    }

    public NiagaraStaticMeshDefinition definition() { return definition; }

    public static boolean functionNeedsCpuAccess(String function) { return !FUNCTIONS_WITHOUT_CPU_BUFFERS.contains(function); }

    public void setUsage(boolean requiresCpuAccess, boolean gpuOnly) {
        this.requiresCpuAccess = requiresCpuAccess;
        this.gpuOnly = gpuOnly;
    }

    public boolean capture(NiagaraStaticMeshInputs inputs, @Nullable NiagaraParameterStore parameters,
                           Map<Identifier, StaticMeshAsset> assets, NiagaraPlatformEnvironment platform,
                           Pose pose, float delta, double x, double y, double z) {
        return capture(inputs, parameters, assets, platform, pose, delta, x, y, z, null);
    }

    public boolean capture(NiagaraStaticMeshInputs inputs, @Nullable NiagaraParameterStore parameters,
                           Map<Identifier, StaticMeshAsset> assets, NiagaraPlatformEnvironment platform,
                           Pose pose, float delta, double x, double y, double z, @Nullable ClientLevel world) {
        return capture(inputs, parameters, assets, platform, pose, delta, x, y, z, world, false);
    }

    public boolean capture(NiagaraStaticMeshInputs inputs, @Nullable NiagaraParameterStore parameters,
                           Map<Identifier, StaticMeshAsset> assets, NiagaraPlatformEnvironment platform,
                           Pose pose, float delta, double x, double y, double z, @Nullable ClientLevel world,
                           boolean explicitOverride) {
        owner = new Transform(pose.position().x * 100, pose.position().z * 100, pose.position().y * 100,
                -pose.x(), -pose.z(), -pose.y(), pose.w(), pose.scaleX(), pose.scaleZ(), pose.scaleY());
        originX = x; originY = y; originZ = z;
        inverseDelta = Math.abs(delta) <= 1.e-8f ? 0 : 1f / delta;
        var source = resolve(inputs, parameters, assets, explicitOverride);
        var resolvedMesh = source == null ? null : source.mesh();
        var previousMesh = selectedSource == null ? null : selectedSource.mesh();
        var shaderPlatforms = platform.shaderPlatforms();
        boolean fallbackUnavailable = resolvedMesh != null && !resolvedMesh.sampling().fallbackAvailable(shaderPlatforms.featureLevel());
        if (fallbackUnavailable && (previousMesh != resolvedMesh || !previousFallbackUnavailable))
            LOGGER.warn("Niagara static mesh reader {} has no fallback mesh on source shader format {}", identity, shaderPlatforms.featureLevel());
        previousFallbackUnavailable = fallbackUnavailable;
        defaultMeshBufferViews = resolvedMesh != null && resolvedMesh.sampling().defaultMeshBufferViews(shaderPlatforms.maximum());
        int requestedLod = resolvedMesh == null ? -1 : requestedLod(source, parameters, platform);
        var resident = resolvedMesh == null ? null : resolvedMesh.resident();
        int selectedLod = resident == null || requestedLod < 0 ? -1 : lod(source, platform, resident, requestedLod);
        var lodBuffers = source == null || selectedLod < 0 ? null : source.lodBuffers().get(selectedLod);
        var lodMetadata = resolvedMesh == null || selectedLod < 0 ? null : resolvedMesh.lods().get(selectedLod);
        lodCpuBufferAccess = lodMetadata == null ? null : lodMetadata.cpuBufferAccess();
        hasLodGpuBufferPolicy = lodMetadata != null && lodMetadata.gpuBufferPolicy() != null;
        lodGpuBufferPolicy = hasLodGpuBufferPolicy
                ? lodMetadata.gpuBufferPolicy().get(shaderPlatforms.maximum()) : null;
        boolean publishedGpuViews = lodBuffers != null && lodBuffers.gpu().validMesh();
        boolean cpuBuffersAvailable = lodBuffers != null ? anyCpuBufferAvailable(lodBuffers.cpu())
                : lodCpuBufferAccess != null ? anyCpuBufferAvailable(lodCpuBufferAccess)
                : resolvedMesh != null && resolvedMesh.sampling().cpuAccess();
        boolean gpuBuffersAvailable = lodBuffers != null ? publishedGpuViews
                : hasLodGpuBufferPolicy ? lodGpuBufferPolicy != null && lodGpuBufferPolicy.validMesh()
                : resolvedMesh != null && (resolvedMesh.sampling().cpuAccess() || defaultMeshBufferViews);
        var mesh = resolvedMesh;
        if (mesh != null && (fallbackUnavailable || requiresCpuAccess && !cpuBuffersAvailable || gpuOnly && !gpuBuffersAvailable)) mesh = null;
        if (mesh == null) { resident = null; selectedLod = -1; lodBuffers = null; }
        else if (requestedLod >= 0 && resolvedMesh.streaming() != null)
            resolvedMesh.streaming().request(requestedLod, false);
        var geometry = mesh == null || resident == null ? null : mesh.residentGeometry(selectedLod, resident);
        if (geometry != null && geometry.vertexCount() == 0) geometry = null;
        distanceField = mesh != null && selectedLod >= 0 && source.sceneDistanceField() && platform.staticMeshDistanceFields()
                ? mesh.sampling().distanceField() : null;
        boolean geometryChanged = mesh != asset || selectedLod != lod || geometry != sampling.geometry();
        boolean reinitialize = initialized && (geometryChanged || previousMesh != resolvedMesh || !sameComponent(selectedSource, source));
        if (geometryChanged) {
            asset = mesh;
            lod = selectedLod;
            sampling = new NiagaraStaticMeshSampling(mesh, lod, geometry, definition);
        }
        if (reinitialize) {
            instance = definition.instance();
            requestedUvChannel = -1;
            uvMapping = null;
            inverseDelta = 0;
        }
        var buffers = lodBuffers;
        boolean cpuUvAvailable = buffers != null ? buffers.cpu().indices() && buffers.cpu().textureCoordinates()
                : lodCpuBufferAccess != null ? lodCpuBufferAccess.indices() && lodCpuBufferAccess.textureCoordinates()
                : mesh != null && mesh.sampling().cpuAccess();
        boolean canBuildUv = mesh != null && cpuUvAvailable && geometry != null;
        if (!canBuildUv) uvMapping = null;
        int uvChannel = requestedUvChannel;
        if (uvMapping == null && canBuildUv && geometry != null
                && uvChannel >= 0 && uvChannel < geometry.textureCoordinateCount())
            uvMapping = world == null ? new NiagaraMeshUvTree(geometry, uvChannel)
                    : NiagaraStaticMeshWorlds.uvMapping(world, geometry, uvChannel);
        if (reinitialize || !initialized) {
            current = previous = selectTransform(source, false);
            ownerOffset = ownerOffset(current, source);
        } else if (definition.captureTransforms()) {
            previous = current;
            current = selectTransform(source, false);
            ownerOffset = ownerOffset(current, source);
        } else previous = current;
        selectedSource = source;
        frame = snapshot();
        initialized = true;
        return reinitialize;
    }

    private static boolean sameComponent(@Nullable Source first, @Nullable Source second) {
        boolean firstBound = first != null && first.component() != null;
        boolean secondBound = second != null && second.component() != null;
        if (!firstBound || !secondBound) return firstBound == secondBound;
        return Objects.equals(Objects.requireNonNull(first).componentIdentity(), Objects.requireNonNull(second).componentIdentity());
    }

    private @Nullable Source resolve(NiagaraStaticMeshInputs inputs, @Nullable NiagaraParameterStore parameters,
                                    Map<Identifier, StaticMeshAsset> assets, boolean explicitOverride) {
        SourceMode mode = definition.mode();
        Source source = null;
        if (mode == SourceMode.DEFAULT || mode == SourceMode.SOURCE) {
            var direct = inputs.components().get(identity);
            if (direct != null) return direct;
            source = inputs.actors().get(definition.sourceActor());
        }
        if (source == null) {
            if (mode == SourceMode.DEFAULT || mode == SourceMode.MESH_PARAMETER_BINDING) {
                if (!explicitOverride) source = inputs.parameters().get(definition.meshParameter());
                if (!present(source)) source = boundMesh(inputs, parameters, assets);
            }
            if (!present(source) && (mode == SourceMode.DEFAULT || mode == SourceMode.ATTACH_PARENT)) source = inputs.attachment();
        }
        if (present(source)) return source;
        Identifier fallback = definition.defaultMesh();
        return fallback != null && (mode == SourceMode.DEFAULT || mode == SourceMode.DEFAULT_MESH_ONLY)
                ? Source.asset(Objects.requireNonNull(assets.get(fallback), "Missing prepared Niagara mesh " + fallback)) : null;
    }

    private @Nullable Source boundMesh(NiagaraStaticMeshInputs inputs, @Nullable NiagaraParameterStore parameters,
                                      Map<Identifier, StaticMeshAsset> assets) {
        if (parameters == null || definition.meshParameter().isEmpty() || SourceNames.equal(definition.meshParameter(), "None")) return null;
        Object value = parameters.objectBinding(definition.meshParameter()).value();
        String path = NiagaraParameterStoreSources.sourceObjectPath(value);
        if (!path.isEmpty()) {
            var component = inputs.components().get(path);
            if (present(component)) return component;
            var actor = inputs.actors().get(path);
            if (present(actor)) return actor;
        }
        if (!(value instanceof ObjectValue object)) return null;
        if (savedObject != object) {
            var binding = object.source().getAsJsonObject("static_mesh_parameter");
            savedObject = object;
            savedSource = binding == null || !binding.has("asset") ? null
                    : Source.asset(Objects.requireNonNull(assets.get(Identifier.parse(binding.get("asset").getAsString())),
                            "Missing prepared mesh bound to " + definition.meshParameter()));
        }
        return savedSource;
    }

    private int lod(@Nullable Source source, NiagaraPlatformEnvironment platform, StaticMeshAsset.ResidentLods resident,
                    int requested) {
        if (source == null || source.mesh() == null) return -1;
        var mesh = source.mesh();
        int count = mesh.lods().size();
        if (source.firstResidentLod() >= count) return -1;
        int firstResident = Math.max(mesh.sampling().firstAvailableLod(), source.firstResidentLod());
        int minimum = mesh.firstResidentLod(Math.max(minimumLod(source, platform), firstResident), resident);
        if (minimum < 0) return -1;
        int selected = Math.clamp(requested, minimum, count - 1);
        if (mesh.streaming() == null || selected >= mesh.sampling().firstInlineLod()
                || !mesh.lods().get(selected).hasRenderBuffers() || mesh.residentGeometry(selected, resident) != null)
            return selected;

        // The host can publish a sparse set while the requested bulk geometry is still loading.
        int available = mesh.firstResidentLod(selected, resident);
        return available >= 0 ? available : minimum;
    }

    private int requestedLod(@Nullable Source source, @Nullable NiagaraParameterStore parameters,
                             NiagaraPlatformEnvironment platform) {
        if (source == null || source.mesh() == null) return -1;
        var mesh = source.mesh();
        int count = mesh.lods().size();
        int minimum = Math.max(minimumLod(source, platform), mesh.sampling().firstAvailableLod());
        if (minimum >= count) return -1;
        int requested = definition.lod();
        var key = definition.lodKey();
        if (parameters != null && key != null && parameters.numericBytes(key) == Integer.BYTES)
            requested = parameters.numericWord(parameters.numericOffset(key));
        return Math.clamp(requested < 0 ? count + requested : requested, minimum, count - 1);
    }

    private int minimumLod(Source source, NiagaraPlatformEnvironment platform) {
        var setting = source.mesh().minimumLod();
        int minimum = setting.value();
        for (String profile : platform.profileHierarchy()) {
            Integer override = setting.overrides().get(profile);
            if (override != null) { minimum = override; break; }
        }
        int inline = platform.staticMeshInlineLodMode();
        if (inline == 1 || inline == 2 && !definition.streamingLods()) minimum = source.firstInlineLod();
        return minimum;
    }

    private static boolean present(@Nullable Source source) { return source != null && (source.mesh() != null || source.component() != null); }

    private Transform selectTransform(@Nullable Source source, boolean indexChanged) {
        if (source != null && instance >= 0 && instance < source.instances().size()) return source.instances().get(instance);
        return !indexChanged && source != null && source.component() != null ? source.component() : owner;
    }
    private Vec3 ownerOffset(Transform value, @Nullable Source source) {
        return source == null || source.component() == null ? Vec3.ZERO
                : new Vec3(value.x() - owner.x(), value.y() - owner.y(), value.z() - owner.z());
    }

    private Frame snapshot() {
        var publishedVelocity = definition.physicsVelocity() && selectedSource != null ? selectedSource.physicsVelocity() : null;
        Vec3 physicsVelocity = publishedVelocity == null ? Vec3.ZERO
                : new Vec3(publishedVelocity.x(), publishedVelocity.y(), publishedVelocity.z());
        var buffers = selectedSource == null ? null : selectedSource.lodBuffers().get(lod);
        CpuBuffers cpuBuffers = CpuBuffers.NONE;
        if (asset != null && sampling.valid()) {
            if (buffers != null) cpuBuffers = buffers.cpu();
            else if (lodCpuBufferAccess != null) cpuBuffers = cpuBuffers(lodCpuBufferAccess);
            else if (asset.sampling().cpuAccess()) cpuBuffers = CpuBuffers.ALL;
        }
        GpuBuffers gpuBuffers = GpuBuffers.NONE;
        if (asset != null && sampling.valid()) {
            var sourceSampling = asset.sampling();
            if (buffers != null) gpuBuffers = buffers.gpu();
            else if (hasLodGpuBufferPolicy) {
                var policy = lodGpuBufferPolicy;
                if (policy != null) gpuBuffers = new GpuBuffers(policy.indices(), policy.positions(), policy.tangents(),
                        policy.textureCoordinates(), policy.colors(), sourceSampling.gpuAreaWeighted() && policy.indices());
            }
            else if (sourceSampling.cpuAccess() || defaultMeshBufferViews) gpuBuffers = GpuBuffers.ALL;
        }
        return new Frame(sampling, matrix(current), inverseTransposed(current), matrix(previous), inverseTransposed(previous),
                rotation(current), rotation(previous),
                ownerOffset, physicsVelocity, inverseDelta, instance, uvMapping, distanceField,
                selectedSource == null || !sampling.valid() ? null : selectedSource.vertexColors().get(lod), cpuBuffers, gpuBuffers);
    }

    private static boolean anyCpuBufferAvailable(CpuBuffers buffers) {
        return buffers.indices() || buffers.positions() || buffers.tangents()
                || buffers.textureCoordinates() || buffers.colors();
    }

    private static boolean anyCpuBufferAvailable(StaticMeshAsset.CpuBufferAccess buffers) {
        return buffers.indices() || buffers.positions() || buffers.tangents()
                || buffers.textureCoordinates() || buffers.colors();
    }

    private static CpuBuffers cpuBuffers(StaticMeshAsset.CpuBufferAccess buffers) {
        return new CpuBuffers(buffers.indices(), buffers.positions(), buffers.tangents(),
                buffers.textureCoordinates(), buffers.colors());
    }

    private Matrix4f matrix(Transform value) {
        return new Matrix4f(NiagaraComponentMatrices.srt(value.qx(), value.qy(), value.qz(), value.qw(), value.scaleX(),
                value.scaleY(), value.scaleZ(), value.x() - originX, value.y() - originY, value.z() - originZ, new Matrix4d()));
    }

    private Matrix4f inverseTransposed(Transform value) {
        double x = reciprocal(value.scaleX()), y = reciprocal(value.scaleY()), z = reciprocal(value.scaleZ());
        var inverse = new Quaterniond(-value.qx(), -value.qy(), -value.qz(), value.qw());
        var translation = inverse.transform(new Vector3d((originX - value.x()) * x, (originY - value.y()) * y, (originZ - value.z()) * z));
        return new Matrix4f(NiagaraComponentMatrices.srt(inverse.x, inverse.y, inverse.z, inverse.w, x, y, z,
                translation.x, translation.y, translation.z, new Matrix4d()).transpose());
    }

    private static double reciprocal(double value) { return Math.abs(value) <= 1.e-8 ? 0 : 1 / value; }
    private static Quaternionf rotation(Transform value) {
        return new Quaternionf((float)value.qx(), (float)value.qy(), (float)value.qz(), (float)value.qw());
    }

    public int instance() { return frame.instance(); }
    public void buildUvMapping(int channel) { requestedUvChannel = channel; }
    public void setInstance(int index) {
        instance = index;
        current = previous = selectTransform(selectedSource, true);
        ownerOffset = ownerOffset(current, selectedSource);
        frame = snapshot();
    }

    public GpuSnapshot captureGpu() { return new GpuSnapshot(frame); }

    /** Current and previous simulation values are retained together for delayed GPU dispatch. */
    public static final class GpuSnapshot {
        private final Frame frame;
        private final NiagaraStaticMeshGpuGeometry geometry;

        private GpuSnapshot(Frame frame) {
            this.frame = frame;
            geometry = new NiagaraStaticMeshGpuGeometry(frame.sampling(), frame.uvMapping(), frame.vertexColors(), frame.gpuBuffers());
        }

        public NiagaraStaticMeshGpuGeometry geometry() { return geometry; }
        public @Nullable StaticMeshDistanceField distanceField() {
            if (frame.distanceField() == null) return null;
            float determinant = frame.transform().determinant();
            return Float.isFinite(determinant) && determinant != 0 ? frame.distanceField() : null;
        }

        public @Nullable byte[] value(String name) {
            var sampling = frame.sampling();
            var source = geometry.samplingGeometry();
            var bounds = sampling.bounds();
            return switch (name) {
                case "NumVertices" -> integers(source == null ? 0 : source.vertexCount());
                case "NumUVs" -> integers(geometry.textureCoordinateCount());
                case "HasColors" -> integers(geometry.hasVertexColors() ? 1 : 0);
                case "HasUniformSampling" -> integers(geometry.areaSampling() ? 1 : 0);
                case "NumTriangles" -> source == null ? integers(0, 0, 0) : integers(source.indexCount() / 3,
                        sampling.triangleCount(Selection.FILTERED), sampling.triangleCount(Selection.UNFILTERED));
                case "SectionCounts" -> integers(sampling.sectionCount(Selection.ALL), sampling.sectionCount(Selection.FILTERED),
                        sampling.sectionCount(Selection.UNFILTERED));
                case "SocketCounts" -> integers(sampling.socketCount(Selection.ALL), sampling.socketCount(Selection.FILTERED),
                        sampling.socketCount(Selection.UNFILTERED));
                case "InvDeltaSeconds" -> floats(Math.max(frame.inverseDelta(), 0f));
                case "InstanceTransform" -> matrix(frame.transform());
                case "InstanceTransformInverseTransposed" -> matrix(shaderInverse(frame.transform()));
                case "InstancePreviousTransform" -> matrix(frame.previous());
                case "InstancePreviousTransformInverseTransposed" -> matrix(shaderInverse(frame.previous()));
                case "InstanceRotation" -> rotation(frame.rotation());
                case "InstancePreviousRotation" -> rotation(frame.previousRotation());
                case "InstanceWorldVelocity" -> floats(frame.transform().m30() - frame.previous().m30(),
                        frame.transform().m31() - frame.previous().m31(), frame.transform().m32() - frame.previous().m32());
                case "PreSkinnedLocalBoundsCenter" -> vectorBytes(bounds == null ? Vec3.ZERO : bounds.origin());
                case "PreSkinnedLocalBoundsExtents" -> vectorBytes(bounds == null ? Vec3.ZERO : bounds.extent());
                case "MeshBoundsWSCenter", "MeshBoundsWSExtents" -> worldBounds(name);
                case "OwnerToMeshVector" -> vectorBytes(frame.ownerOffset());
                case "UvMappingBufferLength" -> integers(frame.uvMapping() == null ? 0 : frame.uvMapping().byteSize());
                case "UvMappingSet" -> integers(frame.uvMapping() == null ? 0 : frame.uvMapping().channel());
                case "InstanceDistanceFieldIndex" -> integers(-1);
                case "MeshFieldValid" -> integers(distanceField() == null ? 0 : 1);
                case "MeshFieldTransform", "MeshFieldExtent", "MeshFieldWorldScale" -> meshFieldTransform(name);
                default -> null;
            };
        }

        private byte[] meshFieldTransform(String name) {
            var field = distanceField();
            if (field == null) return name.equals("MeshFieldTransform") ? matrix(new Matrix4f()) : floats(0, 0, 0);
            Vec3 center = field.minimum().add(field.maximum()).scale(.5);
            Vec3 half = field.maximum().subtract(field.minimum()).scale(.5);
            float extent = (float)Math.max(half.x, Math.max(half.y, half.z));
            var transform = frame.transform();
            return switch (name) {
                case "MeshFieldTransform" -> matrix(new Matrix4f().scaling(1f / extent)
                        .translate((float)-center.x, (float)-center.y, (float)-center.z).mul(new Matrix4f(transform).invert()));
                case "MeshFieldExtent" -> floats((float)half.x / extent, (float)half.y / extent, (float)half.z / extent);
                case "MeshFieldWorldScale" -> floats(length(transform.m00(), transform.m01(), transform.m02()) * extent,
                        length(transform.m10(), transform.m11(), transform.m12()) * extent,
                        length(transform.m20(), transform.m21(), transform.m22()) * extent);
                default -> throw new IllegalArgumentException("Unknown static mesh field transform member " + name);
            };
        }
        private static float length(float x, float y, float z) { return (float)Math.sqrt(x * x + y * y + z * z); }

        private byte[] worldBounds(String name) {
            int[] data = new int[18];
            readBounds("GetMeshBoundsWS", data, frame);
            int offset = name.equals("MeshBoundsWSCenter") ? 0 : 12;
            return integers(data[offset], data[offset + 1], data[offset + 2]);
        }

        private static Matrix4f shaderInverse(Matrix4f value) {
            return value.determinant() == 0 ? new Matrix4f() : new Matrix4f(value).invert().transpose();
        }
        private static byte[] matrix(Matrix4f value) { return floats(new Matrix4f(value).transpose().get(new float[16])); }
        private static byte[] rotation(Quaternionf value) { return floats(value.x, value.y, value.z, value.w); }
        private static byte[] vectorBytes(Vec3 value) { return floats((float)value.x, (float)value.y, (float)value.z); }
        private static byte[] floats(float... values) {
            var output = ByteBuffer.allocate(values.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            for (float value : values) output.putFloat(value);
            return output.array();
        }
        private static byte[] integers(int... values) {
            var output = ByteBuffer.allocate(values.length * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            for (int value : values) output.putInt(value);
            return output.array();
        }
    }

    public void read(String function, int[] input, int[] output) {
        var current = frame;
        var mesh = current.sampling();
        var buffers = current.cpuBuffers();
        var selection = function.contains("Unfiltered") ? Selection.UNFILTERED
                : function.contains("Filtered") ? Selection.FILTERED : Selection.ALL;
        switch (function) {
            case "IsValid" -> output[0] = mesh.valid() ? -1 : 0;
            case "IsValidVertex" -> output[0] = mesh.vertexValid(input[0], buffers) ? -1 : 0;
            case "GetVertexCount" -> output[0] = mesh.vertexCount(buffers);
            case "GetVertexColor" -> { for (int channel = 0; channel < 4; channel++) output[channel] = bits(current.color(input[0], channel)); }
            case "GetVertexUV" -> { for (int channel = 0; channel < 2; channel++) output[channel] = bits(mesh.uv(input[0], input[1], channel, buffers)); }
            case "IsValidTriangle", "IsValidFilteredTriangle", "IsValidUnfilteredTriangle" -> output[0] = mesh.triangleValid(input[0], selection, buffers) ? -1 : 0;
            case "GetTriangleCount", "GetFilteredTriangleCount", "GetUnfilteredTriangleCount" -> output[0] = mesh.triangleCount(selection, buffers);
            case "GetFilteredTriangleAt", "GetUnfilteredTriangleAt" -> output[0] = mesh.triangleAt(input[0], selection);
            case "GetTriangleIndices" -> { for (int vertex = 0; vertex < 3; vertex++) output[vertex] = mesh.triangleVertex(input[0], vertex, buffers); }
            case "GetTriangle", "GetTriangleWS", "GetTriangleWSInterpolated" -> readTriangle(function, input, output, current);
            case "GetTriangleColor", "GetTriangleUV" -> readTriangleAttributes(function, input, output, current);
            case "IsValidSection" -> output[0] = mesh.sectionValid(input[0]) ? -1 : 0;
            case "GetSectionCount", "GetFilteredSectionCount", "GetUnfilteredSectionCount" -> output[0] = mesh.sectionCount(selection);
            case "GetFilteredSectionAt", "GetUnfilteredSectionAt" -> output[0] = mesh.sectionAt(input[0], selection);
            case "GetSectionTriangleCount" -> output[0] = mesh.sectionTriangles(input[0]);
            case "GetSectionTriangleAt" -> output[0] = mesh.sectionTriangleAt(input[0], input[1]);
            case "GetSocketCount", "GetFilteredSocketCount", "GetUnfilteredSocketCount" -> output[0] = mesh.socketCount(selection);
            case "GetFilteredSocket", "GetUnfilteredSocket" -> output[0] = mesh.socketAt(input[0], selection);
            case "GetSocketTransform", "GetSocketTransformWS", "GetSocketTransformWSInterpolated",
                    "GetFilteredSocketTransform", "GetFilteredSocketTransformWS", "GetFilteredSocketTransformWSInterpolated",
                    "GetUnfilteredSocketTransform", "GetUnfilteredSocketTransformWS", "GetUnfilteredSocketTransformWSInterpolated" ->
                    readSocket(function, input, output, current, selection);
            case "GetInstanceIndex" -> output[0] = current.instance();
            case "GetWorldVelocity" -> write(output, 0, current.physicsVelocity());
            case "GetLocalToWorld" -> write(output, current.transform());
            case "GetLocalToWorldInverseTransposed" -> write(output, current.inverseTransposed());
            case "GetVertex", "GetVertexWS", "GetVertexWSInterpolated" -> readVertex(function, input, output, current);
            case "GetPreSkinnedLocalBounds", "GetMeshBounds", "GetMeshBoundsWS" -> readBounds(function, output, current);
            case "GetTriangleCoordAtUV", "GetTriangleCoordInAabb" -> readUvMapping(function, input, output, current.uvMapping());
            case "DeprecatedGetVertexPosition" -> write(output, 0, mesh.position(input[0], buffers));
            case "DeprecatedGetVertexPositionWS" -> write(output, 0, current.transform().transformPosition(vector(mesh.position(input[0], buffers))));
            case "DeprecatedGetTriColor" -> readTriangleAttributes("GetTriangleColor", input, output, current);
            case "DeprecatedGetTriUV" -> readTriangleAttributes("GetTriangleUV", input, output, current);
            case "DeprecatedGetTriPosition", "DeprecatedGetTriPositionWS", "DeprecatedGetTriPositionAndVelocityWS",
                    "DeprecatedGetTriNormal", "DeprecatedGetTriNormalWS", "DeprecatedGetTriTangents", "DeprecatedGetTriTangentsWS" ->
                    readLegacyTriangle(function, input, output, current);
            default -> throw new IllegalArgumentException("Unknown Niagara static mesh VM function: " + function);
        }
    }

    private static void readUvMapping(String function, int[] input, int[] output, @Nullable NiagaraMeshUvTree tree) {
        NiagaraMeshUvTree.Hit hit = null;
        if (tree != null && input[0] != 0) {
            float u = Float.intBitsToFloat(input[1]), v = Float.intBitsToFloat(input[2]);
            float fourth = Float.intBitsToFloat(input[3]);
            hit = function.equals("GetTriangleCoordAtUV") ? tree.point(u, v, fourth)
                    : tree.box(u, v, fourth, Float.intBitsToFloat(input[4]));
        }
        output[0] = hit == null ? -1 : hit.triangle();
        output[1] = hit == null ? 0 : bits(hit.a());
        output[2] = hit == null ? 0 : bits(hit.b());
        output[3] = hit == null ? 0 : bits(hit.c());
        output[4] = hit == null ? 0 : -1;
    }

    public void random(String function, int[] input, int[] output, NiagaraExternalRandom random) {
        var current = frame;
        var mesh = current.sampling();
        var buffers = current.cpuBuffers();
        var selection = function.contains("Unfiltered") ? Selection.UNFILTERED
                : function.contains("Filtered") ? Selection.FILTERED : Selection.ALL;
        switch (function) {
            case "RandomVertex" -> output[0] = mesh.randomVertex(random, buffers);
            case "RandomSocket", "RandomFilteredSocket", "RandomUnfilteredSocket" -> output[0] = mesh.randomSocket(random, selection);
            case "RandomSection", "RandomFilteredSection", "RandomUnfilteredSection" -> output[0] = mesh.randomSection(random, selection);
            case "RandomTriangle", "RandomFilteredTriangle", "RandomUnfilteredTriangle" -> {
                output[0] = mesh.randomTriangle(random, selection, buffers);
                random.barycentric(output, 1);
            }
            case "RandomSectionTriangle" -> {
                output[0] = mesh.randomTriangleInSection(random, input[3]);
                random.barycentric(output, 1);
            }
            case "DeprecatedRandomSection" -> output[0] = mesh.randomSection(random, Selection.FILTERED);
            case "DeprecatedRandomTriCoord" -> {
                output[0] = mesh.randomTriangle(random, Selection.ALL, buffers);
                random.barycentric(output, 1);
            }
            case "DeprecatedRandomTriCoordOnSection" -> {
                output[0] = mesh.randomTriangleInSection(random, input[0]);
                random.barycentric(output, 1);
            }
            default -> throw new IllegalArgumentException("Unknown Niagara static mesh random function: " + function);
        }
    }
    private static void readBounds(String function, int[] output, Frame frame) {
        var bounds = frame.sampling().bounds();
        Vec3 center = bounds == null ? Vec3.ZERO : bounds.origin();
        Vec3 half = bounds == null ? Vec3.ZERO : bounds.extent();
        if (function.equals("GetMeshBoundsWS")) {
            var matrix = frame.transform();
            var position = matrix.transformPosition(vector(center));
            center = new Vec3(position.x, position.y, position.z);
            half = new Vec3(Math.abs(matrix.m00()) * half.x + Math.abs(matrix.m10()) * half.y + Math.abs(matrix.m20()) * half.z,
                    Math.abs(matrix.m01()) * half.x + Math.abs(matrix.m11()) * half.y + Math.abs(matrix.m21()) * half.z,
                    Math.abs(matrix.m02()) * half.x + Math.abs(matrix.m12()) * half.y + Math.abs(matrix.m22()) * half.z);
        }
        write(output, 0, center);
        write(output, 3, center.subtract(half));
        write(output, 6, center.add(half));
        write(output, 9, half.scale(2));
        write(output, 12, half);
        if (!function.equals("GetPreSkinnedLocalBounds")) write(output, 15, frame.ownerOffset());
    }

    private record Triangle(int a, int b, int c, float u, float v, float w) {
        Triangle(NiagaraStaticMeshSampling mesh, int[] input, CpuBuffers buffers) {
            this(mesh.triangleVertex(input[0], 0, buffers), mesh.triangleVertex(input[0], 1, buffers), mesh.triangleVertex(input[0], 2, buffers),
                    Float.intBitsToFloat(input[1]), Float.intBitsToFloat(input[2]), Float.intBitsToFloat(input[3]));
        }
        float mix(float x, float y, float z) { return x * u + y * v + z * w; }
        Vec3 mix(Vec3 x, Vec3 y, Vec3 z) {
            return new Vec3(mix((float)x.x, (float)y.x, (float)z.x), mix((float)x.y, (float)y.y, (float)z.y),
                    mix((float)x.z, (float)y.z, (float)z.z));
        }
        Vec3 position(NiagaraStaticMeshSampling mesh, CpuBuffers buffers) {
            return mix(mesh.position(a, buffers), mesh.position(b, buffers), mesh.position(c, buffers));
        }
        TangentBasis basis(NiagaraStaticMeshSampling mesh, CpuBuffers buffers) {
            var first = mesh.rawTangentBasis(a, buffers);
            var second = mesh.rawTangentBasis(b, buffers);
            var third = mesh.rawTangentBasis(c, buffers);
            return new TangentBasis(mix(first.normal(), second.normal(), third.normal()),
                    mix(first.bitangent(), second.bitangent(), third.bitangent()),
                    mix(first.tangent(), second.tangent(), third.tangent()));
        }
    }

    private static void readTriangle(String function, int[] input, int[] output, Frame frame) {
        var mesh = frame.sampling();
        var buffers = frame.cpuBuffers();
        boolean valid = mesh.triangleCount(Selection.ALL, buffers) > 0
                && mesh.vertexCount(buffers) > 0 && mesh.hasTangents(buffers);
        Vec3 local = Vec3.ZERO;
        TangentBasis basis = NiagaraStaticMeshSampling.DEFAULT_BASIS;
        if (valid) {
            var triangle = new Triangle(mesh, input, buffers);
            local = triangle.position(mesh, buffers);
            basis = triangle.basis(mesh, buffers);
        }
        if (function.equals("GetTriangle")) {
            write(output, 0, local); write(output, 3, Vec3.ZERO);
            write(output, 6, basis.normal()); write(output, 9, basis.bitangent()); write(output, 12, basis.tangent());
            return;
        }
        var position = frame.transform().transformPosition(vector(local));
        var previous = frame.previous().transformPosition(vector(local));
        var velocity = valid ? new Vector3f(position).sub(previous).mul(frame.inverseDelta()) : new Vector3f();
        float alpha = valid && function.equals("GetTriangleWSInterpolated") ? Float.intBitsToFloat(input[4]) : 1f;
        if (alpha != 1f) position = previous.lerp(position, alpha);
        write(output, 0, position); write(output, 3, velocity);
        write(output, 6, triangleBasis(frame, basis.normal(), alpha, valid));
        write(output, 9, triangleBasis(frame, basis.bitangent(), alpha, valid));
        write(output, 12, triangleBasis(frame, basis.tangent(), alpha, valid));
    }

    private static void readLegacyTriangle(String function, int[] input, int[] output, Frame frame) {
        var mesh = frame.sampling();
        var buffers = frame.cpuBuffers();
        boolean hasIndices = mesh.triangleCount(Selection.ALL, buffers) > 0;
        boolean world = function.endsWith("WS");
        if (function.contains("Position")) {
            boolean valid = hasIndices && mesh.vertexCount(buffers) > 0;
            Vec3 local = valid ? new Triangle(mesh, input, buffers).position(mesh, buffers) : Vec3.ZERO;
            if (!world) {
                write(output, 0, local);
                return;
            }
            var current = frame.transform().transformPosition(vector(local));
            write(output, 0, current);
            if (function.equals("DeprecatedGetTriPositionAndVelocityWS"))
                write(output, 3, valid ? current.sub(frame.previous().transformPosition(vector(local))).mul(frame.inverseDelta())
                        : new Vector3f());
            return;
        }
        boolean valid = hasIndices && mesh.hasTangents(buffers);
        var basis = valid ? new Triangle(mesh, input, buffers).basis(mesh, buffers) : NiagaraStaticMeshSampling.DEFAULT_BASIS;
        if (function.contains("Tangents")) {
            writeLegacyTriangleDirection(output, 0, basis.tangent(), frame, world, valid);
            writeLegacyTriangleDirection(output, 3, basis.bitangent(), frame, world, valid);
            writeLegacyTriangleDirection(output, 6, basis.normal(), frame, world, valid);
        } else {
            writeLegacyTriangleDirection(output, 0, basis.normal(), frame, world, valid);
        }
    }

    private static void writeLegacyTriangleDirection(int[] output, int offset, Vec3 direction, Frame frame,
                                                       boolean world, boolean valid) {
        if (world) write(output, offset, triangleBasis(frame, direction, 1f, valid));
        else write(output, offset, direction);
    }

    private static Vector3f triangleBasis(Frame frame, Vec3 direction, float alpha, boolean valid) {
        var matrix = valid ? frame.transform() : frame.inverseTransposed();
        var current = unit(matrix.transformDirection(vector(direction)));
        return alpha == 1f ? current : unit(frame.previous().transformDirection(vector(direction))).lerp(current, alpha);
    }

    private static void readTriangleAttributes(String function, int[] input, int[] output, Frame frame) {
        var mesh = frame.sampling();
        var buffers = frame.cpuBuffers();
        boolean color = function.equals("GetTriangleColor");
        int count = color ? 4 : 2;
        if (mesh.triangleCount(Selection.ALL, buffers) == 0 || (color ? frame.colorVertexCount() : mesh.uvVertexCount(buffers)) == 0) {
            for (int component = 0; component < count; component++) output[component] = bits(color ? 1 : 0);
            return;
        }
        var triangle = new Triangle(mesh, input, buffers);
        for (int component = 0; component < count; component++) {
            output[component] = bits(color ? triangle.mix(frame.color(triangle.a(), component), frame.color(triangle.b(), component), frame.color(triangle.c(), component))
                    : triangle.mix(mesh.uv(triangle.a(), input[4], component, buffers), mesh.uv(triangle.b(), input[4], component, buffers),
                    mesh.uv(triangle.c(), input[4], component, buffers)));
        }
    }

    private static void readSocket(String function, int[] input, int[] output, Frame frame, Selection selection) {
        var socket = frame.sampling().socket(input[0], selection);
        var position = vector(socket == null ? Vec3.ZERO : socket.position());
        var rotation = socket == null ? new Quaternionf() : new Quaternionf(socket.rotation());
        var scale = socket == null ? new Vector3f(1f) : vector(socket.scale());
        var velocity = new Vector3f();
        if (function.contains("WS")) {
            var previousPosition = frame.previous().transformPosition(new Vector3f(position));
            position = frame.transform().transformPosition(position);
            if (socket != null) velocity.set(position).sub(previousPosition).mul(frame.inverseDelta());
            var currentRotation = new Quaternionf(frame.rotation()).mul(rotation);
            var currentScale = frame.inverseTransposed().transformDirection(new Vector3f(scale));
            if (socket != null && function.endsWith("Interpolated")) {
                float alpha = Float.intBitsToFloat(input[1]);
                position = previousPosition.lerp(position, alpha);
                rotation = new Quaternionf(frame.previousRotation()).mul(rotation).slerp(currentRotation, alpha);
                scale = frame.previousInverseTransposed().transformDirection(scale).lerp(currentScale, alpha);
            } else {
                rotation = currentRotation;
                scale = currentScale;
            }
        }
        write(output, 0, position);
        output[3] = bits(rotation.x); output[4] = bits(rotation.y); output[5] = bits(rotation.z); output[6] = bits(rotation.w);
        write(output, 7, scale);
        write(output, 10, velocity);
    }

    private static void readVertex(String function, int[] input, int[] output, Frame frame) {
        var mesh = frame.sampling();
        var buffers = frame.cpuBuffers();
        boolean valid = mesh.vertexCount(buffers) > 0 && mesh.hasTangents(buffers);
        var local = valid ? mesh.position(input[0], buffers) : Vec3.ZERO;
        var basis = valid ? mesh.tangentBasis(input[0], buffers) : NiagaraStaticMeshSampling.DEFAULT_BASIS;
        if (function.equals("GetVertex")) {
            write(output, 0, local); write(output, 3, Vec3.ZERO);
            write(output, 6, basis.normal()); write(output, 9, basis.bitangent()); write(output, 12, basis.tangent());
            return;
        }
        var position = frame.transform().transformPosition(vector(local));
        var prior = frame.previous().transformPosition(vector(local));
        var velocity = valid ? new Vector3f(position).sub(prior).mul(frame.inverseDelta()) : new Vector3f();
        boolean interpolated = valid && function.equals("GetVertexWSInterpolated");
        float interpolation = interpolated ? Float.intBitsToFloat(input[1]) : 1;
        if (interpolated) position = prior.lerp(position, interpolation);
        write(output, 0, position);
        write(output, 3, velocity);
        write(output, 6, basis(frame.inverseTransposed(), frame.previousInverseTransposed(), basis.normal(), interpolation, interpolated));
        write(output, 9, basis(frame.inverseTransposed(), frame.previousInverseTransposed(), basis.bitangent(), interpolation, interpolated));
        write(output, 12, basis(frame.inverseTransposed(), frame.previousInverseTransposed(), basis.tangent(), interpolation, interpolated));
    }

    private static Vector3f basis(Matrix4f matrix, Matrix4f previous, Vec3 value, float alpha, boolean interpolated) {
        var current = unit(matrix.transformDirection(vector(value)));
        if (!interpolated) return current;
        return unit(previous.transformDirection(vector(value))).lerp(current, alpha);
    }

    private static Vector3f unit(Vector3f value) { return value.lengthSquared() > 1.e-8f ? value.normalize() : value.zero(); }
    private static Vector3f vector(Vec3 value) { return new Vector3f((float)value.x, (float)value.y, (float)value.z); }
    private static int bits(double value) { return Float.floatToRawIntBits((float)value); }
    private static void write(int[] output, int offset, Vec3 value) {
        output[offset] = bits(value.x); output[offset + 1] = bits(value.y); output[offset + 2] = bits(value.z);
    }
    private static void write(int[] output, int offset, Vector3f value) {
        output[offset] = bits(value.x); output[offset + 1] = bits(value.y); output[offset + 2] = bits(value.z);
    }
    private static void write(int[] output, Matrix4f value) {
        for (int row = 0; row < 4; row++) for (int column = 0; column < 4; column++) output[row * 4 + column] = bits(value.get(row, column));
    }
}
