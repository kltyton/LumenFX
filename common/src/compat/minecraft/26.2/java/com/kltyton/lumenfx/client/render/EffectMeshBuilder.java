package com.kltyton.lumenfx.client.render;

import com.kltyton.lumenfx.client.component.mesh.StaticMeshComponentDraw;
import com.kltyton.lumenfx.client.render.light.shadow.geometry.ParticleShadowGeometry;
import com.kltyton.lumenfx.client.render.light.shadow.geometry.ParticleShadowGeometry.CpuSource;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraCpuSpriteCompute;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuMeshSorting;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSpriteSorting;
import com.kltyton.lumenfx.client.render.niagara.gpu.splat.NiagaraCpuSplatCollector;
import com.kltyton.lumenfx.client.render.volume.NiagaraVolumeInstance;
import com.kltyton.lumenfx.client.render.unity.UnityBillboardInstances;
import com.kltyton.lumenfx.client.render.unity.UnityMeshInstances;

import com.kltyton.lumenfx.client.simulation.EmitterInstance;
import com.kltyton.lumenfx.effect.simulation.ParticleState;
import com.kltyton.lumenfx.effect.EffectDefinition;
import com.kltyton.lumenfx.effect.EmitterDefinition;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.render.RibbonSettings;
import com.kltyton.lumenfx.effect.render.ParticleMotionSettings;
import com.kltyton.lumenfx.effect.render.SpriteCoverage;
import com.kltyton.lumenfx.client.render.ribbon.RibbonTessellation;
import com.kltyton.lumenfx.client.render.ribbon.ShurikenRibbonJoins;
import com.kltyton.lumenfx.client.render.ribbon.NiagaraCpuRibbonBuilder;
import com.kltyton.lumenfx.client.render.ribbon.NiagaraCpuRibbonCompute;
import com.kltyton.lumenfx.effect.niagara.component.NiagaraTranslucencySort;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraRibbonScalability;
import com.kltyton.lumenfx.effect.render.RibbonShapeGeometry;
import com.kltyton.lumenfx.effect.unity.UnityTrailTexture;
import com.kltyton.lumenfx.effect.unity.UnityParticleTrail;
import com.kltyton.lumenfx.effect.unity.UnityBillboard;
import com.kltyton.lumenfx.effect.unity.UnityCustomData;
import com.kltyton.lumenfx.effect.unity.UnityMeshMotion;
import com.kltyton.lumenfx.effect.unity.UnityAtlasAnimation;
import com.kltyton.lumenfx.effect.unity.UnityRendererBinding;
import com.kltyton.lumenfx.effect.render.mesh.MeshBatchGeometry;
import com.kltyton.lumenfx.effect.render.mesh.MeshRendererSettings;
import com.kltyton.lumenfx.client.render.mesh.MeshDrawSelection;
import com.kltyton.lumenfx.client.render.mesh.MeshInstanceBuilder;
import com.kltyton.lumenfx.client.render.mesh.CpuMeshSceneInput;
import com.kltyton.lumenfx.client.render.mesh.MeshView;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.client.render.niagara.sort.NiagaraIndexSort;
import com.lowdragmc.photon.client.gameobject.emitter.data.ParticleAtlas;
import com.lowdragmc.photon.client.gameobject.particle.renderer.TrailSpline;
import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.ints.IntComparator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/** One worker owns its scratch arrays and streams for the complete update/pack/upload batch. */
public final class EffectMeshBuilder {
    private static final int STRIDE = 64;
    private static final int X = 0, Y = 1, Z = 2, WIDTH = 3, HEIGHT = 4, ROTATION = 5;
    private static final int COLOR = 6, AGE = 10, RANDOM = 11, LIFE = 12, BORN = 13, CUSTOM = 14;
    private static final int DISTANCE = 18, SIDE = 19;
    private static final int TANGENT = 22, SEGMENT_FRACTION = 25;
    private static final int EXTRA = 26;
    private static final int RIBBON_UV_DISTANCE = 38, RIBBON_U0 = 39, RIBBON_V0 = 40, RIBBON_U1 = 42, RIBBON_V1 = 43;
    private static final int RIBBON_FACING = 45, RIBBON_TWIST = 48;
    private static final int RIBBON_UP = 49;
    private static final int RIBBON_VELOCITY = 52;
    private static final int RIBBON_DIRECTION = 55, RIBBON_RIGHT = 58, RIBBON_NORMAL_FACING = 61;

    private static final class Points {
        double[] values = new double[STRIDE * 256];
        long[] ribbons = new long[256];
        int count;
        void reserve(int capacity) {
            if (capacity <= ribbons.length) return;
            int next = Math.max(capacity, Math.multiplyExact(ribbons.length, 2));
            values = Arrays.copyOf(values, Math.multiplyExact(next, STRIDE));
            ribbons = Arrays.copyOf(ribbons, next);
        }
    }

    private final Map<EffectMesh.Key, EffectMesh> streams = new LinkedHashMap<>();
    private final Map<RibbonSettings.Shape, RibbonShapeGeometry> ribbonShapes = new HashMap<>();
    private final List<EffectMesh> output = new ArrayList<>();
    private final List<com.kltyton.lumenfx.effect.render.light.ParticleLight> lights = new ArrayList<>();
    private final List<com.kltyton.lumenfx.effect.render.decal.ParticleDecal> decals = new ArrayList<>();
    private final List<NiagaraVolumeInstance> volumes = new ArrayList<>();
    private final List<NiagaraCpuSplatCollector.Source> splats = new ArrayList<>();
    private final Points raw = new Points(), dense = new Points();
    private record PendingMesh(EffectDefinition effect, EmitterInstance.Snapshot snapshot, long primitive) { }
    private final List<PendingMesh> pendingMeshes = new ArrayList<>();
    private final List<CpuSource> shadowSources = new ArrayList<>();
    private final List<CpuMeshSceneInput> sceneMeshes = new ArrayList<>();
    private final List<NiagaraCpuRibbonCompute.Source> computeRibbons = new ArrayList<>();
    private boolean computeRibbonsAvailable;
    private final List<NiagaraCpuSpriteCompute.Source> computeSprites = new ArrayList<>();
    private boolean computeSortingAvailable;
    private final MeshInstanceBuilder meshInstances = new MeshInstanceBuilder();
    private final com.kltyton.lumenfx.client.render.niagara.NiagaraComponentGeometry spriteComponent =
            new com.kltyton.lumenfx.client.render.niagara.NiagaraComponentGeometry();
    private final com.kltyton.lumenfx.client.render.ribbon.RibbonMotionBuilder ribbonMotion =
            new com.kltyton.lumenfx.client.render.ribbon.RibbonMotionBuilder();
    private final ShurikenRibbonJoins shurikenRibbonJoins = new ShurikenRibbonJoins();
    private final NiagaraCpuRibbonBuilder nativeRibbons = new NiagaraCpuRibbonBuilder();
    private MeshView meshView;
    private final int slot;
    private int nextPrimitive;
    private long primitive;
    private static final long SHARED_OPAQUE_PRIMITIVE = Long.MIN_VALUE;
    private final double[] position = new double[3];
    private final Vector4f atlasUv = new Vector4f();
    private final Vector3f tmpAxisX = new Vector3f(), tmpAxisY = new Vector3f(), tmpAxisZ = new Vector3f();
    private final org.joml.Vector3d tmpLocalCamera = new org.joml.Vector3d();
    private final org.joml.Matrix4d tmpOwnerMatrix = new org.joml.Matrix4d();
    private final double[] sampledVelocity = new double[3];
    private final float[] ribbonVelocity = new float[3];
    private int[] order = new int[256];
    private double[] nativeLinkOrder = new double[256];
    private int[] nativeSortKeys = new int[256];
    private boolean nativeLinkIsFloat;
    private boolean nativeOrdering;
    private int dynamicMask;
    private int extraSlots;
    private @Nullable UnityCustomData unityCustomData;
    private boolean ribbonParticleData;
    private boolean ribbonMaterialBasis;
    private boolean ribbonHasDistance, ribbonHasU0, ribbonHasU1, ribbonHasV0, ribbonHasV1, ribbonHasFacing, ribbonHasTwist;
    private record RibbonUv(double scale, double offset, double distributionScale, boolean override) { }
    private record RibbonStrip(int first, int last, double length, double countedLength,
                               double weightedLength, double weightedAngle, double weightedTwist, double weightedWidth,
                               double minX, double minY, double minZ, double maxX, double maxY, double maxZ) { }
    private final List<RibbonStrip> sourceStrips = new ArrayList<>();
    private RibbonUv sourceUv0, sourceUv1;
    private SpriteCoverage coverage = SpriteCoverage.DISABLED;
    private double cameraX, cameraY, cameraZ;
    private Vec3 cameraPosition;
    private Vec3 cameraVelocity = Vec3.ZERO;
    private Vec3 sourceViewDirection;
    private double rightX, rightY, rightZ, upX, upY, upZ;
    private double forwardX, forwardY, forwardZ;
    private float ribbonLodDistanceFactor = 1;
    private float sourceFrameDelta;
    private boolean sourceFrameDeltaKnown;
    private boolean sortByViewDepth;
    private final IntComparator depthOrder = (a, b) -> Double.compare(distanceSquared(b), distanceSquared(a));
    private final IntComparator viewPlaneOrder = (a, b) -> Double.compare(viewPlaneDepth(b), viewPlaneDepth(a));
    private final IntComparator reverseDistanceOrder = (a, b) -> depthOrder.compare(b, a);
    private final IntComparator reverseViewPlaneOrder = (a, b) -> viewPlaneOrder.compare(b, a);
    private final IntComparator oldestFirst = (a, b) -> {
        int comparison = Double.compare(raw.values[a * STRIDE + BORN], raw.values[b * STRIDE + BORN]);
        return comparison == 0 ? Integer.compare(a, b) : comparison;
    };
    private final IntComparator youngestFirst = (a, b) -> oldestFirst.compare(b, a);
    private final UnityBillboardInstances unityBillboards = new UnityBillboardInstances();
    private final UnityMeshInstances unityMeshInstances = new UnityMeshInstances();
    private final float[] unityMeshData = new float[EffectMesh.Kind.MESH.floatsPerInstance()];
    private final float[] unityMeshExtra = new float[40];
    private EffectMesh[] unityMeshSections = new EffectMesh[0];
    private static final ParticleMotionSettings UNITY_MESH_MOTION = new ParticleMotionSettings(ParticleMotionSettings.Mode.APPROXIMATE, false);
    private final NiagaraIndexSort niagaraSort = new NiagaraIndexSort();
    private final IntComparator birthOrder = (a, b) -> {
        int group = Long.compare(raw.ribbons[a], raw.ribbons[b]);
        if (group != 0) return group;
        if (nativeOrdering) return nativeLinkIsFloat ? Double.compare(nativeLinkOrder[a], nativeLinkOrder[b])
                : Double.compare(nativeLinkOrder[b], nativeLinkOrder[a]);
        int birth = Double.compare(raw.values[a * STRIDE + BORN], raw.values[b * STRIDE + BORN]);
        return birth != 0 ? birth : Integer.compare(a, b);
    };
    private int[] unityRibbonBirthOrder = new int[0];

    public EffectMeshBuilder(int slot) { this.slot = slot; }

    public void begin(Vec3 camera, Quaternionf orientation) {
        meshView = null;
        ribbonLodDistanceFactor = 1;
        sourceFrameDeltaKnown = false;
        cameraX = camera.x; cameraY = camera.y; cameraZ = camera.z;
        cameraPosition = camera;
        cameraVelocity = Vec3.ZERO;
        Vector3f right = orientation.transform(tmpAxisX.set(1, 0, 0));
        Vector3f up = orientation.transform(tmpAxisY.set(0, 1, 0));
        Vector3f forward = orientation.transform(tmpAxisZ.set(0, 0, -1));
        rightX = right.x; rightY = right.y; rightZ = right.z;
        upX = up.x; upY = up.y; upZ = up.z;
        forwardX = forward.x; forwardY = forward.y; forwardZ = forward.z;
        shurikenRibbonJoins.view(camera, right, up, forward);
        sourceViewDirection = new Vec3(forward.x, forward.y, forward.z);
        streams.values().forEach(EffectMesh::reset);
        output.clear();
        lights.clear();
        decals.clear();
        volumes.clear();
        splats.clear();
        pendingMeshes.clear();
        shadowSources.clear();
        sceneMeshes.clear();
        computeRibbons.clear();
        computeRibbonsAvailable = false;
        computeSprites.clear();
        computeSortingAvailable = false;
        nextPrimitive = 0;
    }

    public void begin(Vec3 camera, Quaternionf orientation, MeshView view) {
        begin(camera, orientation);
        meshView = view;
    }

    public void begin(Vec3 camera, Quaternionf orientation, @Nullable MeshView view,
                      float ribbonLodDistanceFactor) {
        begin(camera, orientation);
        meshView = view;
        this.ribbonLodDistanceFactor = ribbonLodDistanceFactor;
    }

    public void begin(Vec3 camera, Quaternionf orientation, @Nullable MeshView view,
                      float ribbonLodDistanceFactor, Matrix4fc modelView) {
        begin(camera, orientation, view, ribbonLodDistanceFactor);
        sourceViewDirection = new Vec3(-modelView.m02(), -modelView.m12(), -modelView.m22());
    }

    public void begin(Vec3 camera, Quaternionf orientation, @Nullable MeshView view,
                      float ribbonLodDistanceFactor, Matrix4fc modelView, float sourceFrameDelta) {
        begin(camera, orientation, view, ribbonLodDistanceFactor, modelView);
        this.sourceFrameDelta = sourceFrameDelta;
        sourceFrameDeltaKnown = true;
    }

    public void begin(Vec3 camera, Quaternionf orientation, @Nullable MeshView view, float ribbonLodDistanceFactor,
                      Matrix4fc modelView, float sourceFrameDelta, Vec3 cameraVelocity) {
        begin(camera, orientation, view, ribbonLodDistanceFactor, modelView, sourceFrameDelta);
        this.cameraVelocity = cameraVelocity;
    }

    public void clear() { streams.clear(); output.clear(); lights.clear(); decals.clear(); volumes.clear(); splats.clear(); ribbonShapes.clear(); sourceStrips.clear(); nativeRibbons.clear(); pendingMeshes.clear(); sceneMeshes.clear(); shadowSources.clear(); computeRibbons.clear(); computeSprites.clear(); meshView = null; }
    public List<CpuSource> shadowSources() { return shadowSources; }
    public void computeRibbonsAvailable(boolean available) { computeRibbonsAvailable = available; }
    public List<NiagaraCpuRibbonCompute.Source> computeRibbons() { return computeRibbons; }
    public void computeSortingAvailable(boolean available) { computeSortingAvailable = available; }
    public List<NiagaraCpuSpriteCompute.Source> computeSprites() { return computeSprites; }
    public List<CpuMeshSceneInput> sceneMeshes() { return sceneMeshes; }
    public List<com.kltyton.lumenfx.effect.render.light.ParticleLight> lights() { return lights; }
    public List<com.kltyton.lumenfx.effect.render.decal.ParticleDecal> decals() { return decals; }
    public List<NiagaraVolumeInstance> volumes() { return volumes; }
    public List<NiagaraCpuSplatCollector.Source> splats() { return splats; }

    public void add(EffectDefinition effect, List<EmitterInstance.Snapshot> emitters) {
        for (var snapshot : emitters) add(effect, snapshot);
    }

    public void add(StaticMeshComponentDraw component) {
        var view = Objects.requireNonNull(meshView, "Component meshes require a captured projection view");
        primitive = ((long)slot << 32) | (nextPrimitive++ & 0xffffffffL);
        component.append(view, cameraPosition, (material, geometry, depth) -> {
            var key = new EffectMesh.Key(material, EffectMesh.Kind.MESH, 1, 0, primitive, SpriteCoverage.DISABLED,
                    false, null, geometry, false, true, component.receivesDecals());
            var stream = streams.computeIfAbsent(key, EffectMesh::new);
            stream.visit();
            stream.depth(depth);
            return stream;
        });
    }

    public void add(EffectDefinition effect, EmitterInstance.Snapshot snapshot) {
            if (snapshot.nativeParticles() != null && !snapshot.nativeParticles().hostVisible()) return;
            boolean shadowView = meshView != null && meshView.shadowPass();
            primitive = ((long)slot << 32) | (nextPrimitive++ & 0xffffffffL);
            var definition = snapshot.definition();
            var niagara = snapshot.nativeParticles();
            boolean computeRibbon = false;
            if (niagara != null) {
                var platform = NiagaraPlatforms.snapshot();
                if (!platform.renders(definition.renderer())) return;
                if (definition.renderer() == EmitterDefinition.Renderer.RIBBON) {
                    var settings = definition.ribbonSettings();
                    computeRibbon = computeRibbonsAvailable && !shadowView && settings != null
                            && NiagaraRibbonScalability.useGpuForCpuRibbon(settings, platform);
                }
            }
            boolean sprite = definition.renderer() == EmitterDefinition.Renderer.SPRITE;
            if (!sprite && ParticleShadowGeometry.casts(definition, snapshot.input()) && !shadowView && !computeRibbon)
                shadowSources.add(new CpuSource(effect, snapshot));
            if (definition.renderer() == EmitterDefinition.Renderer.VOLUME) {
                snapshot.nativeParticles().appendVolumes(effect, definition, volumes);
                return;
            }
            if (definition.renderer() == EmitterDefinition.Renderer.LIGHT) {
                snapshot.nativeParticles().appendLights(lights);
                return;
            }
            if (definition.renderer() == EmitterDefinition.Renderer.SPLAT) {
                if (!shadowView && snapshot.nativeParticles() != null) {
                    var source = snapshot.nativeParticles().splatSource();
                    if (source != null) splats.add(source);
                }
                return;
            }
            var sourceMaterial = effect.materials().get(definition.material());
            var material = snapshot.nativeParticles() != null && sourceMaterial != null
                    ? snapshot.nativeParticles().material(sourceMaterial) : sourceMaterial;
            if (shadowView && definition.renderer() != EmitterDefinition.Renderer.MESH && !ParticleShadowGeometry.casts(material)) return;
            if (definition.renderer() == EmitterDefinition.Renderer.DECAL) {
                snapshot.nativeParticles().appendDecals(effect, material, decals);
                return;
            }
            extraSlots = material.dynamicSlots() & 14;
            var unity = effect.unityRenderers().get(definition.name());
            unityCustomData = unity == null ? null : unity.customData();
            ribbonParticleData = material.ribbonParticleData() && definition.renderer() != EmitterDefinition.Renderer.SPRITE;
            ribbonMaterialBasis = material.usesVertexBasis();
            coverage = snapshot.nativeParticles() == null ? SpriteCoverage.DISABLED : snapshot.nativeParticles().coverage();
            switch (definition.renderer()) {
                case SPRITE -> {
                    if (!shadowView && computeSortingAvailable && niagara != null && !niagara.emitterSource()
                            && niagara.count() != 0) {
                        var data = niagara.renderData();
                        if (NiagaraGpuSpriteSorting.dispatchCpu(data, definition, material, NiagaraPlatforms.snapshot())) {
                            computeSprites.add(new NiagaraCpuSpriteCompute.Source(snapshot, data, material));
                            return;
                        }
                    }
                    if (!shadowView && ParticleShadowGeometry.casts(definition, snapshot.input()))
                        shadowSources.add(new CpuSource(effect, snapshot));
                    if (niagara != null && !niagara.emitterDistanceVisible()) return;
                    var primitiveFrame = primitiveFrame(snapshot, material);
                    long streamPrimitive = canShareOpaqueBatch(material, snapshot, primitiveFrame, false)
                            ? SHARED_OPAQUE_PRIMITIVE : primitive;
                    var key = new EffectMesh.Key(material, EffectMesh.Kind.SPRITE, 1, definition.sortOrder(), streamPrimitive,
                            coverage, false, definition.spriteCutout(), null, snapshot.nativeParticles() != null,
                            snapshot.nativeParticles() == null || snapshot.nativeParticles().componentVelocity(), snapshot.input().receivesDecals());
                    var spriteStream = streams.computeIfAbsent(key, EffectMesh::new);
                    spriteStream.visit();
                    spriteStream.niagaraRenderer(snapshot.nativeParticles() != null);
                    spriteStream.unityRenderer(unity);
                    spriteStream.unityInstanceId(snapshot.unityInstanceId());
                    spriteStream.materialPrimitive(primitiveFrame);
                    spriteStream.firstPerson(snapshot.input().firstPerson());
                    if (streamPrimitive != SHARED_OPAQUE_PRIMITIVE) {
                        spriteStream.macroUV(snapshot.input().actorPosition(), definition.macroUVRadius());
                    }
                    sprites(spriteStream, snapshot, material, unity);
                }
                case RIBBON -> {
                    var settings = definition.ribbonSettings();
                    if (settings != null && snapshot.nativeParticles() != null)
                        settings = snapshot.nativeParticles().ribbonSettings(settings);
                    if (computeRibbon) {
                        if (niagara.count() >= 2 && niagara.canRenderRibbon())
                            computeRibbons.add(new NiagaraCpuRibbonCompute.Source(snapshot, niagara.renderData(), material,
                                    Objects.requireNonNull(settings)));
                        return;
                    }
                    var geometry = settings == null ? null : ribbonShapes.computeIfAbsent(settings.shape(), RibbonShapeGeometry::from);
                    var primitiveFrame = primitiveFrame(snapshot, material);
                    boolean directionalBasis = snapshot.nativeParticles() != null && settings != null
                            && shadowView && !Objects.requireNonNull(meshView).pointShadow();
                    long streamPrimitive = canShareOpaqueBatch(material, snapshot, primitiveFrame, directionalBasis)
                            ? SHARED_OPAQUE_PRIMITIVE : primitive;
                    var key = new EffectMesh.Key(material, EffectMesh.Kind.RIBBON, geometry == null ? definition.widthSegments() : 1,
                            definition.sortOrder(), streamPrimitive, coverage, geometry != null && geometry.disableBackfaceCulling(), null, null,
                            snapshot.nativeParticles() != null && settings != null,
                            snapshot.nativeParticles() == null || snapshot.nativeParticles().componentVelocity(), snapshot.input().receivesDecals());
                    var ribbonStream = streams.computeIfAbsent(key, EffectMesh::new);
                    ribbonStream.visit();
                    ribbonStream.niagaraRenderer(snapshot.nativeParticles() != null);
                    ribbonStream.unityRenderer(unity);
                    ribbonStream.unityInstanceId(snapshot.unityInstanceId());
                    ribbonStream.materialPrimitive(primitiveFrame);
                    ribbonStream.firstPerson(snapshot.input().firstPerson());
                    if (snapshot.nativeParticles() != null && settings != null) {
                        ribbonStream.directionalRibbonBasis(directionalBasis);
                        ribbonStream.depth(com.kltyton.lumenfx.client.render.mesh.MeshDrawSelection.depth(snapshot.input().pose(),
                                snapshot.nativeParticles().componentBounds(), cameraPosition, meshView));
                        if (shadowView) nativeRibbons.appendPointShadow(ribbonStream, snapshot, cameraPosition, ribbonLodDistanceFactor,
                                Objects.requireNonNull(geometry), sourceViewDirection, sourceFrameDelta);
                        else if (sourceFrameDeltaKnown) nativeRibbons.append(ribbonStream, snapshot, cameraPosition, ribbonLodDistanceFactor,
                                Objects.requireNonNull(geometry), sourceViewDirection, sourceFrameDelta);
                        else nativeRibbons.append(ribbonStream, snapshot, cameraPosition, ribbonLodDistanceFactor,
                                Objects.requireNonNull(geometry), sourceViewDirection);
                    } else {
                        if (unity != null && unity.particleTrail() != null
                                && unity.particleTrail().mode() == UnityParticleTrail.Mode.PER_PARTICLE)
                            unityTrails(ribbonStream, snapshot, unity.trailTexture(), unity);
                        else if (unity != null && (unity.ribbonCount() != null || unity.trailTexture() != null
                                || unity.particleTrail() != null))
                            unityRibbons(ribbonStream, snapshot, unity.ribbonCount() == null ? 1 : unity.ribbonCount(),
                                    unity.trailTexture(), unity.particleTrail(), unity);
                        else {
                            ribbon(ribbonStream, snapshot);
                            if (unity != null) setUnityDepth(ribbonStream, snapshot, unity, raw.count);
                        }
                    }
                }
                case BEAM -> {
                    var beamStream = stream(material, EffectMesh.Kind.RIBBON, definition.widthSegments(), definition.sortOrder(), snapshot.input().receivesDecals());
                    beamStream.materialPrimitive(primitiveFrame(snapshot, material));
                    beamStream.firstPerson(snapshot.input().firstPerson());
                    beam(beamStream, snapshot);
                }
                case MESH -> {
                    if (unity != null && unity.meshMotion() != null) unityMeshes(effect, snapshot, unity, shadowView);
                    else if (definition.meshSettings().sceneTransforms() != MeshRendererSettings.SceneTransforms.LEGACY
                            || computeSortingAvailable && niagara != null && meshView != null
                            && NiagaraGpuMeshSorting.dispatchCpu(effect, snapshot, meshView, NiagaraPlatforms.snapshot()))
                        sceneMeshes.add(new CpuMeshSceneInput(effect, snapshot));
                    else pendingMeshes.add(new PendingMesh(effect, snapshot, primitive));
                }
            }
    }

    private static boolean canShareOpaqueBatch(MaterialDefinition material, EmitterInstance.Snapshot snapshot,
                                               com.kltyton.lumenfx.client.render.scene.material.@Nullable MaterialPrimitiveFrame primitiveFrame,
                                               boolean directionalBasis) {
        return primitiveFrame == null
                && !directionalBasis
                && material.blend() == MaterialDefinition.Blend.OPAQUE
                && !material.macroUV()
                && !snapshot.input().firstPerson()
                && !material.usesForwardTranslucency()
                && material.translucencyVolume() != MaterialDefinition.TranslucencyVolume.SURFACE_VOLUME;
    }

    private EffectMesh stream(MaterialDefinition material, EffectMesh.Kind kind, int segments, int sortOrder, boolean receivesDecals) {
        var key = new EffectMesh.Key(material, kind, segments, sortOrder, primitive, coverage, false, null, null, false, true, receivesDecals);
        var stream = streams.computeIfAbsent(key, EffectMesh::new);
        stream.visit();
        return stream;
    }

    public List<EffectMesh> finish() {
        for (var pending : pendingMeshes) {
            primitive = pending.primitive();
            meshInstances.append(pending.effect(), pending.snapshot(), cameraPosition, meshView,
                    (material, geometry, depth) -> {
                        var key = new EffectMesh.Key(material, EffectMesh.Kind.MESH, 1, pending.snapshot().definition().sortOrder(),
                                 pending.primitive(), SpriteCoverage.DISABLED, false, null, geometry, false,
                                  pending.snapshot().nativeParticles().componentVelocity(), pending.snapshot().input().receivesDecals());
                        var stream = streams.computeIfAbsent(key, EffectMesh::new);
                        stream.visit(); stream.depth(depth);
                        stream.niagaraRenderer(true);
                        stream.materialPrimitive(primitiveFrame(pending.snapshot(), material));
                        stream.firstPerson(pending.snapshot().input().firstPerson());
                        return stream;
                    });
        }
        pendingMeshes.clear();
        streams.values().removeIf(stream -> !stream.visited());
        for (var stream : streams.values()) if (stream.key().drawRun() == 0 && stream.instanceCount() != 0) output.add(stream);
        return output;
    }

    private void unityMeshes(EffectDefinition effect, EmitterInstance.Snapshot snapshot, UnityRendererBinding binding,
                             boolean shadowView) {
        var unity = Objects.requireNonNull(binding.meshMotion());
        int count = samples(snapshot);
        if (count == 0) return;
        sortUnity(unity.order(), count);
        var definition = snapshot.definition();
        var settings = Objects.requireNonNull(definition.meshSettings());
        var pose = snapshot.input().pose();
        unityMeshInstances.prepare(pose, new Vec3(forwardX, forwardY, forwardZ), upX, upY, upZ, unity.forwardToUp());
        var data = unityMeshData;
        var extra = unityMeshExtra;
        Arrays.fill(data, 0);
        Arrays.fill(extra, 0);
        extra[0] = unity.scaleU();
        extra[1] = unity.scaleV();
        extra[2] = unity.offsetU();
        extra[3] = 1 - unity.scaleV() - unity.offsetV();
        data[26] = 15;
        data[14] = 1;
        data[27] = extra[28] = (float) ((pose.position().x - cameraX) * 100);
        data[28] = extra[29] = (float) ((pose.position().z - cameraZ) * 100);
        data[29] = extra[30] = (float) ((pose.position().y - cameraY) * 100);
        data[30] = extra[31] = pose.scaleX();
        data[31] = extra[32] = -pose.x();
        data[32] = extra[33] = -pose.z();
        data[33] = extra[34] = -pose.y();
        data[34] = extra[35] = pose.w();
        extra[36] = extra[38] = pose.scaleZ();
        extra[37] = extra[39] = pose.scaleY();
        double[] values = raw.values;
        var spriteFrames = binding.spriteFrames();
        int columns = spriteFrames.isEmpty() ? definition.atlasColumns() : 1;
        double depth = unityDepth(snapshot, binding, count);
        double ownerDeterminant = definition.localSpace() ? (double) pose.scaleX() * pose.scaleY() * pose.scaleZ() : 1;
        MeshRendererSettings.Entry previousEntry = null;
        boolean previousReverse = false;
        int run = 0, sectionCount = 0;
        try {
            for (int i = 0; i < count; i++) {
                int row = order[i], p = row * STRIDE;
                var particle = snapshot.particles().get(row);
                int slot = Objects.requireNonNull(particle.meshBirth()).meshSlot();
                var entry = settings.entries().get(slot);
                double x = values[p + X] - cameraX, y = values[p + Y] - cameraY, z = values[p + Z] - cameraZ;
                if (definition.localSpace()) {
                    particle.samplePosition(snapshot.time(), definition, snapshot.noise(), position, 0);
                } else {
                    position[0] = x;
                    position[1] = y;
                    position[2] = z;
                }
                data[0] = (float) (position[0] * 100);
                data[1] = (float) (position[2] * 100);
                data[2] = (float) (position[1] * 100);
                unityMeshInstances.pack(unity, snapshot, row, values[p + AGE], values[p + ROTATION], x, y, z, data);
                if (data[7] == 0 && data[8] == 0 && data[9] == 0) continue;
                var scale = entry.scale();
                // Shuriken assets retain clockwise faces after the exporter's axis conversion.
                // A mirrored particle already reverses those faces in the world.
                boolean reverse = (double) data[7] * data[8] * data[9] * scale.x * scale.y * scale.z * ownerDeterminant >= 0;
                if (!entry.equals(previousEntry) || reverse != previousReverse) {
                    Arrays.fill(unityMeshSections, 0, sectionCount, null);
                    sectionCount = unityMeshRun(effect, snapshot, settings, entry, columns, binding, shadowView, reverse, depth, ++run);
                    previousEntry = entry;
                    previousReverse = reverse;
                }
                for (int channel = 0; channel < 4; channel++) {
                    data[15 + channel] = (float) values[p + COLOR + channel];
                    data[19 + channel] = (float) values[p + CUSTOM + channel];
                    extra[4 + channel] = (float) values[p + EXTRA + channel];
                }
                data[23] = (float) values[p + AGE];
                data[24] = (float) values[p + RANDOM];
                double phase = sampledAtlasPhase(snapshot, particle, values[p + AGE], binding);
                int frame = (int) Math.floor(phase);
                float fraction = (float) (phase - frame);
                int frameIndex = (int) ParticleAtlas.wrappedIndex(frame, definition.atlasColumns(), definition.atlasRows(), -1);
                data[25] = frameIndex + fraction;
                if (!spriteFrames.isEmpty()) {
                    var rect = spriteFrames.get(frameIndex);
                    data[25] = 0;
                    extra[0] = (rect.u1() - rect.u0()) * unity.scaleU();
                    extra[1] = (rect.v1() - rect.v0()) * unity.scaleV();
                    extra[2] = rect.u0() * unity.scaleU() + unity.offsetU();
                    extra[3] = rect.v0() * unity.scaleV() + 1 - unity.scaleV() - unity.offsetV();
                }
                for (int section = 0; section < sectionCount; section++)
                    unityMeshSections[section].appendInstance(data, extra);
            }
        } finally {
            Arrays.fill(unityMeshSections, null);
        }
    }

    private int unityMeshRun(EffectDefinition effect, EmitterInstance.Snapshot snapshot, MeshRendererSettings settings,
                             MeshRendererSettings.Entry entry, int columns, UnityRendererBinding binding, boolean shadowView, boolean reverse,
                             double depth, int run) {
        var definition = snapshot.definition();
        var asset = Objects.requireNonNull(entry.asset());
        var geometry = Objects.requireNonNull(asset.lods().getFirst().geometry());
        int sections = geometry.sections().size();
        if (unityMeshSections.length < sections) unityMeshSections = new EffectMesh[sections];
        int count = 0;
        for (int section = 0; section < sections; section++) {
            var source = geometry.sections().get(section);
            if (source.indexCount() == 0) continue;
            var material = MeshDrawSelection.material(effect, definition, asset, entry, source, snapshot.input());
            if (shadowView && (!ParticleShadowGeometry.casts(material) || !ParticleShadowGeometry.casts(source))) continue;
            var draw = new MeshBatchGeometry(geometry, settings, entry, section, definition.localSpace(),
                    columns, definition.atlasRows(), false, UNITY_MESH_MOTION, null, reverse, true,
                    binding.uvChannelMask());
            // Separate only consecutive incompatible transparent draws; sorting by renderer depth keeps those runs together.
            int orderedRun = !shadowView && material.blend().translucent() ? run : 0;
            var key = new EffectMesh.Key(material, EffectMesh.Kind.MESH, 1, definition.sortOrder(), primitive,
                    SpriteCoverage.DISABLED, false, null, draw, false, true, snapshot.input().receivesDecals(), orderedRun);
            var stream = streams.computeIfAbsent(key, EffectMesh::new);
            if (!stream.visited()) {
                stream.visit();
                stream.depth(depth);
                stream.unityRenderer(binding);
                stream.unityInstanceId(snapshot.unityInstanceId());
                stream.materialPrimitive(primitiveFrame(snapshot, material));
                stream.firstPerson(snapshot.input().firstPerson());
                if (orderedRun != 0) output.add(stream);
            }
            unityMeshSections[count++] = stream;
        }
        return count;
    }

    private static com.kltyton.lumenfx.client.render.scene.material.@org.jspecify.annotations.Nullable MaterialPrimitiveFrame primitiveFrame(
            EmitterInstance.Snapshot snapshot, MaterialDefinition material) {
        var particles = snapshot.nativeParticles();
        var previous = particles == null ? snapshot.input().pose() : particles.previousOwnerPose();
        return com.kltyton.lumenfx.client.render.scene.material.MaterialPrimitiveFrame.forMaterial(material, snapshot.input(), previous,
                particles == null ? null : particles::componentBounds);
    }


    private void sample(EmitterInstance.Snapshot snapshot, ParticleState particle, int index) {
        var definition = snapshot.definition();
        double age = particle.normalizedAge(snapshot.time());
        particle.samplePosition(snapshot.time(), snapshot.simulationDefinition(), snapshot.noise(), position, 0);
        if (definition.localSpace()) snapshot.input().pose().transformPosition(position, 0);
        fill(snapshot, particle, index, age, particle.born(), position[0], position[1], position[2]);
    }

    private void fill(EmitterInstance.Snapshot snapshot, ParticleState particle, int index,
                      double age, double born, double x, double y, double z) {
        int p = index * STRIDE;
        var definition = snapshot.simulationDefinition();
        var input = snapshot.input();
        var values = raw.values;
        double random = particle.random();
        double speed = particle.hasSpeedAppearance(definition) ? particle.sampleSpeed(snapshot.time(), definition) : 0;
        var speedColor = particle.speedColor(speed, definition);
        double widthScale = definition.localSpace() ? Math.abs(input.pose().scaleX()) : 1;
        double heightScale = definition.localSpace() ? Math.abs(input.pose().scaleY()) : 1;
        values[p + X] = x; values[p + Y] = y; values[p + Z] = z;
        values[p + WIDTH] = particle.width() * particle.sizeScale(0, age, speed, definition) * widthScale;
        values[p + HEIGHT] = particle.height() * particle.sizeScale(1, age, speed, definition) * heightScale;
        values[p + ROTATION] = particle.rotationZ(age, snapshot.time(), definition);
        var color = definition.colorOverLife();
        var start = particle.startColor();
        values[p + COLOR] = color.sampleChannel(0, age, particle.colorRandom(0), random) * start.red() * input.red();
        values[p + COLOR + 1] = color.sampleChannel(1, age, particle.colorRandom(1), random) * start.green() * input.green();
        values[p + COLOR + 2] = color.sampleChannel(2, age, particle.colorRandom(2), random) * start.blue() * input.blue();
        values[p + COLOR + 3] = color.sampleChannel(3, age, particle.colorRandom(3), random) * start.alpha() * input.alpha();
        if (speedColor != null) {
            values[p + COLOR] *= speedColor.red();
            values[p + COLOR + 1] *= speedColor.green();
            values[p + COLOR + 2] *= speedColor.blue();
            values[p + COLOR + 3] *= speedColor.alpha();
        }
        values[p + AGE] = age; values[p + RANDOM] = random; values[p + LIFE] = particle.lifetime(); values[p + BORN] = born;
        Arrays.fill(values, p + RIBBON_UV_DISTANCE, p + STRIDE, 0);
        if (ribbonParticleData) {
            particle.sampleVelocity(snapshot.time(), definition, sampledVelocity, 0);
            spriteVector(ribbonVelocity, 0, (float)(sampledVelocity[0] * 100), (float)(sampledVelocity[2] * 100),
                    (float)(sampledVelocity[1] * 100), definition.localSpace(), input.pose(), false, false);
            for (int axis = 0; axis < 3; axis++) values[p + RIBBON_VELOCITY + axis] = ribbonVelocity[axis];
        }
        var custom1 = unityCustomData == null ? null : unityCustomData.custom1();
        for (int i = 0; i < 4; i++)
            values[p + CUSTOM + i] = custom1 == null ? definition.customData().get(i).sample(age, random)
                    : custom1.sample(i, age, random);
        if (extraSlots != 0) {
            Arrays.fill(values, p + EXTRA, p + EXTRA + 12, 0);
            var custom2 = unityCustomData == null ? null : unityCustomData.custom2();
            if ((extraSlots & 2) != 0 && custom2 != null)
                for (int i = 0; i < 4; i++) values[p + EXTRA + i] = custom2.sample(i, age, random);
        }
        raw.ribbons[index] = particle.ribbon();
    }

    private int samples(EmitterInstance.Snapshot snapshot) {
        nativeOrdering = snapshot.nativeParticles() != null;
        ribbonHasDistance = ribbonHasU0 = ribbonHasU1 = ribbonHasV0 = ribbonHasV1 = ribbonHasFacing = ribbonHasTwist = false;
        dynamicMask = nativeOrdering ? snapshot.nativeParticles().dynamicParameterMask() : 15;
        if (snapshot.nativeParticles() != null) return nativeSamples(snapshot);
        int count = snapshot.particles().size();
        raw.reserve(count + 1);
        if (order.length < count + 1) order = Arrays.copyOf(order, Math.max(count + 1, order.length * 2));
        for (int i = 0; i < count; i++) { sample(snapshot, snapshot.particles().get(i), i); order[i] = i; }
        raw.count = count;
        return count;
    }

    private int nativeSamples(EmitterInstance.Snapshot snapshot) {
        var particles = snapshot.nativeParticles();
        ribbonHasDistance = particles.hasRibbonUvDistance();
        ribbonHasU0 = particles.hasRibbonU(0); ribbonHasU1 = particles.hasRibbonU(1);
        ribbonHasV0 = particles.hasRibbonV(0); ribbonHasV1 = particles.hasRibbonV(1);
        ribbonHasFacing = particles.hasRibbonFacing();
        ribbonHasTwist = particles.hasRibbonTwistAndWidth();
        int count = particles.count();
        if (snapshot.definition().renderer() == EmitterDefinition.Renderer.RIBBON && !particles.canRenderRibbon()) {
            raw.count = 0;
            return 0;
        }
        nativeLinkIsFloat = particles.ribbonLinkIsFloat();
        raw.reserve(count + 1);
        if (order.length < count + 1) order = Arrays.copyOf(order, Math.max(count + 1, order.length * 2));
        if (nativeLinkOrder.length < count + 1) nativeLinkOrder = Arrays.copyOf(nativeLinkOrder, order.length);
        if (nativeSortKeys.length < count + 1) nativeSortKeys = Arrays.copyOf(nativeSortKeys, order.length);
        var input = snapshot.input();
        for (int row = 0; row < count; row++) {
            int p = row * STRIDE;
            boolean sprite = snapshot.definition().renderer() == EmitterDefinition.Renderer.SPRITE;
            double x, y, z;
            if (sprite) {
                spriteComponent.worldPosition(raw.values, p, particles.simulationPosition(row, 0), particles.simulationPosition(row, 1),
                        particles.simulationPosition(row, 2), snapshot.definition().localSpace(), false);
                x = raw.values[p]; y = raw.values[p + 1]; z = raw.values[p + 2];
            } else { x = particles.x(row); y = particles.y(row); z = particles.z(row); }
            if (snapshot.definition().localSpace() && !sprite) {
                var pose = input.pose();
                x *= pose.scaleX(); y *= pose.scaleY(); z *= pose.scaleZ();
                double tx = 2 * (pose.y() * z - pose.z() * y), ty = 2 * (pose.z() * x - pose.x() * z), tz = 2 * (pose.x() * y - pose.y() * x);
                double nextX = x + pose.w() * tx + pose.y() * tz - pose.z() * ty + pose.position().x;
                double nextY = y + pose.w() * ty + pose.z() * tx - pose.x() * tz + pose.position().y;
                z = z + pose.w() * tz + pose.x() * ty - pose.y() * tx + pose.position().z;
                x = nextX; y = nextY;
            }
            raw.values[p + X] = x; raw.values[p + Y] = y; raw.values[p + Z] = z;
            raw.values[p + WIDTH] = particles.width(row); raw.values[p + HEIGHT] = particles.height(row);
            // Source sprites rotate in UV coordinates, whose vertical axis points down.
            raw.values[p + ROTATION] = -particles.rotation(row);
            raw.values[p + COLOR] = particles.color(row, 0) * input.red();
            raw.values[p + COLOR + 1] = particles.color(row, 1) * input.green();
            raw.values[p + COLOR + 2] = particles.color(row, 2) * input.blue();
            raw.values[p + COLOR + 3] = particles.color(row, 3) * input.alpha();
            raw.values[p + AGE] = particles.normalizedAge(row); raw.values[p + LIFE] = particles.lifetime(row);
            raw.values[p + RANDOM] = particles.random(row); raw.values[p + BORN] = snapshot.time() - particles.age(row);
            raw.values[p + RIBBON_UV_DISTANCE] = particles.ribbonUvDistance(row);
            raw.values[p + RIBBON_U0] = particles.ribbonU(row, 0); raw.values[p + RIBBON_U1] = particles.ribbonU(row, 1);
            for (int component = 0; component < 2; component++) {
                raw.values[p + RIBBON_V0 + component] = particles.ribbonV(row, 0, component);
                raw.values[p + RIBBON_V1 + component] = particles.ribbonV(row, 1, component);
            }
            double fx = particles.ribbonFacing(row, 0), fy = particles.ribbonFacing(row, 2), fz = particles.ribbonFacing(row, 1);
            if (snapshot.definition().localSpace()) {
                var pose = input.pose();
                fx *= pose.scaleX() * com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices.inverseAxisScale(pose, 0, false);
                fy *= pose.scaleY() * com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices.inverseAxisScale(pose, 1, false);
                fz *= pose.scaleZ() * com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices.inverseAxisScale(pose, 2, false);
                double tx = 2 * (pose.y() * fz - pose.z() * fy), ty = 2 * (pose.z() * fx - pose.x() * fz), tz = 2 * (pose.x() * fy - pose.y() * fx);
                double nextX = fx + pose.w() * tx + pose.y() * tz - pose.z() * ty;
                double nextY = fy + pose.w() * ty + pose.z() * tx - pose.x() * tz;
                fz = fz + pose.w() * tz + pose.x() * ty - pose.y() * tx;
                fx = nextX; fy = nextY;
            }
            raw.values[p + RIBBON_FACING] = fx; raw.values[p + RIBBON_FACING + 1] = fy;
            raw.values[p + RIBBON_FACING + 2] = fz; raw.values[p + RIBBON_TWIST] = particles.ribbonTwist(row);
            if (ribbonParticleData) {
                spriteVector(ribbonVelocity, 0, particles.ribbonVelocity(row, 0), particles.ribbonVelocity(row, 1), particles.ribbonVelocity(row, 2),
                        snapshot.definition().localSpace(), input.pose());
                for (int axis = 0; axis < 3; axis++) raw.values[p + RIBBON_VELOCITY + axis] = ribbonVelocity[axis];
            }
            for (int channel = 0; channel < 4; channel++) raw.values[p + CUSTOM + channel] = particles.dynamicParameter(row, channel,
                    (float)snapshot.definition().customData().get(channel).sample(particles.normalizedAge(row), particles.random(row)));
            if (extraSlots != 0) {
                for (int slot = 1; slot <= 3; slot++) for (int channel = 0; channel < 4; channel++)
                    raw.values[p + EXTRA + (slot - 1) * 4 + channel] = (extraSlots & (1 << slot)) != 0
                            ? particles.dynamicParameter(row, slot, channel) : 0;
            }
            raw.ribbons[row] = particles.ribbonId(row); order[row] = row;
            nativeLinkOrder[row] = snapshot.definition().renderer() == EmitterDefinition.Renderer.RIBBON ? particles.ribbonLink(row) : 0;
            var sortMode = snapshot.definition().sortMode();
            if ((sortMode == EmitterDefinition.SortMode.CUSTOM_ASCENDING || sortMode == EmitterDefinition.SortMode.CUSTOM_DESCENDING)
                    && particles.supportsSort(sortMode))
                nativeSortKeys[row] = particles.customSortKey(row, sortMode == EmitterDefinition.SortMode.CUSTOM_ASCENDING);
        }
        raw.count = count;
        return count;
    }

    private double distanceSquared(int index) {
        int p = index * STRIDE;
        double x = raw.values[p] - cameraX, y = raw.values[p + 1] - cameraY, z = raw.values[p + 2] - cameraZ;
        return sortByViewDepth ? x * forwardX + y * forwardY + z * forwardZ : x * x + y * y + z * z;
    }

    private double viewPlaneDepth(int index) {
        int p = index * STRIDE;
        return (raw.values[p + X] - cameraX) * sourceViewDirection.x
                + (raw.values[p + Y] - cameraY) * sourceViewDirection.y
                + (raw.values[p + Z] - cameraZ) * sourceViewDirection.z;
    }

    private double sampledAtlasPhase(EmitterInstance.Snapshot snapshot, ParticleState particle,
                                     double normalizedAge, @Nullable UnityRendererBinding binding) {
        var definition = snapshot.definition();
        double phase = definition.atlasFrame().sample(normalizedAge, particle.random());
        if (binding == null) return phase;
        int row = particle.atlasRow(definition.atlasRow());
        int columns = definition.atlasColumns(), rows = definition.atlasRows();
        int span = row < 0 ? columns * rows : columns;
        if (binding.atlasFps() != null || binding.atlasSpeed() != null) {
            double advance;
            var speed = binding.atlasSpeed();
            if (speed != null) {
                double length = particle.sampleSpeed(snapshot.time(), snapshot.simulationDefinition());
                // A saturated speed selects the final frame of the last cycle.
                advance = Math.min(speed.progress(length) * span * speed.cycles(),
                        Math.nextDown((double) span * speed.cycles()));
            } else advance = particle.elapsedLifetime(snapshot.time()) * Objects.requireNonNull(binding.atlasFps());
            // Reduce the unbounded frame counter before converting to a finite cycle position.
            double wholePhase = Math.floor(phase);
            phase = wholePhase % span + ((phase - wholePhase) + advance) % span;
        }
        if (row < 0) return phase;
        double wrapped = phase % columns;
        float columnPhase = (float) ((wrapped < 0 ? wrapped + columns : wrapped) / columns);
        return UnityAtlasAnimation.sampleRow(row, columns, rows, columnPhase);
    }

    private void sprites(EffectMesh mesh, EmitterInstance.Snapshot snapshot, MaterialDefinition material,
                         @Nullable UnityRendererBinding binding) {
        UnityBillboard unity = binding == null ? null : binding.billboard();
        var nativeParticles = snapshot.nativeParticles();
        if (nativeParticles != null && nativeParticles.count() != 0) spriteComponent.prepare(nativeParticles, cameraPosition);
        int count = samples(snapshot);
        if (nativeParticles == null) setUnityDepth(mesh, snapshot, binding, count);
        else mesh.depth(com.kltyton.lumenfx.client.render.mesh.MeshDrawSelection.depth(snapshot.input().pose(),
                nativeParticles.componentBounds(), cameraPosition, meshView));
        var definition = snapshot.definition();
        sortByViewDepth = definition.sortMode() == EmitterDefinition.SortMode.VIEW_DEPTH;
        if (nativeParticles != null) {
            int visible = 0;
            for (int row = 0; row < count; row++) if (nativeParticles.visible(row)) order[visible++] = row;
            count = visible;
        }
        boolean custom = definition.sortMode() == EmitterDefinition.SortMode.CUSTOM_ASCENDING
                || definition.sortMode() == EmitterDefinition.SortMode.CUSTOM_DESCENDING;
        if (custom && nativeParticles == null) throw new IllegalArgumentException("Custom particle sorting requires Niagara attributes");
        boolean translucent = nativeParticles == null ? material.blend().translucent()
                : NiagaraGpuSpriteSorting.usesTranslucentSort(material);
        boolean needsSort = definition.sortMode() != EmitterDefinition.SortMode.NONE
                && (translucent || nativeParticles != null && !nativeParticles.sortOnlyWhenTranslucent())
                && (nativeParticles == null || nativeParticles.supportsSort(definition.sortMode()));
        if (needsSort && nativeParticles != null) {
            if (!custom) {
                var localCamera = tmpLocalCamera.set(cameraX, cameraY, cameraZ);
                if (definition.localSpace()) {
                    var owner = com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices.minecraft(snapshot.input().pose(), true, tmpOwnerMatrix);
                    com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices.inverse(owner, owner).transformPosition(localCamera);
                }
                for (int i = 0; i < count; i++) {
                    int row = order[i];
                    double value = distanceSquared(row);
                    if (!sortByViewDepth && definition.localSpace()) {
                        double dx = nativeParticles.x(row) - localCamera.x, dy = nativeParticles.y(row) - localCamera.y;
                        double dz = nativeParticles.z(row) - localCamera.z;
                        value = dx * dx + dy * dy + dz * dz;
                    }
                    float sourceValue = (float)(sortByViewDepth ? value * 100 : value * 10000);
                    nativeSortKeys[row] = NiagaraIndexSort.viewKey(sourceValue);
                }
            }
            niagaraSort.sortParticles(order, count, nativeSortKeys, NiagaraPlatforms.snapshot().radixSortThreshold());
        } else if (unity != null) {
            sortUnity(unity.order(), count);
            unityBillboards.prepare(rightX, rightY, rightZ, upX, upY, upZ, snapshot.input().pose(), cameraVelocity,
                    unity.allowRoll());
        } else if (needsSort) IntArrays.quickSort(order, 0, count, depthOrder);
        for (int i = 0; i < count; i++) {
            int p = order[i] * STRIDE, start = mesh.appendInstance();
            float[] data = mesh.values();
            if (extraSlots != 0) {
                float[] extra = mesh.extraValues();
                int extraOffset = start / mesh.kind().floatsPerInstance() * mesh.extraFloatsPerInstance();
                for (int channel = 0; channel < 12; channel++) extra[extraOffset + channel] = (float) raw.values[p + EXTRA + channel];
            }
            data[start] = (float)(raw.values[p] - cameraX); data[start + 1] = (float)(raw.values[p + 1] - cameraY); data[start + 2] = (float)(raw.values[p + 2] - cameraZ);
            data[start + 3] = (float)raw.values[p + WIDTH]; data[start + 4] = (float)raw.values[p + HEIGHT]; data[start + 5] = (float)raw.values[p + ROTATION];
            if (nativeParticles == null) {
                var particle = snapshot.particles().get(order[i]);
                double phase = sampledAtlasPhase(snapshot, particle, raw.values[p + AGE], binding);
                int frame = (int) Math.floor(phase);
                float fraction = (float) (phase - frame);
                if (binding != null && !binding.spriteFrames().isEmpty()) {
                    int index0 = (int) ParticleAtlas.wrappedIndex(frame, definition.atlasColumns(), 1, -1);
                    var rect = binding.spriteFrames().get(index0);
                    data[start + 6] = rect.u0(); data[start + 7] = rect.v0();
                    data[start + 8] = rect.u1(); data[start + 9] = rect.v1();
                    attributes(raw.values, p, data, start + 10);
                    int index1 = (int) ParticleAtlas.wrappedIndex(frame + 1, definition.atlasColumns(), 1, -1);
                    var nextRect = binding.spriteFrames().get(index1);
                    data[start + 22] = nextRect.u0(); data[start + 23] = nextRect.v0();
                    data[start + 24] = nextRect.u1(); data[start + 25] = nextRect.v1();
                } else {
                    int selectedRow = particle.atlasRow(definition.atlasRow());
                    int row = binding == null ? selectedRow : -1;
                    ParticleAtlas.wrappedFrame(frame, definition.atlasColumns(), definition.atlasRows(), row, atlasUv);
                    data[start + 6] = atlasUv.x; data[start + 7] = atlasUv.y;
                    data[start + 8] = atlasUv.z; data[start + 9] = atlasUv.w;
                    attributes(raw.values, p, data, start + 10);
                    int nextFrame = frame + 1;
                    if (binding != null && selectedRow >= 0 && nextFrame == (selectedRow + 1) * definition.atlasColumns())
                        nextFrame = selectedRow * definition.atlasColumns();
                    ParticleAtlas.wrappedFrame(nextFrame, definition.atlasColumns(), definition.atlasRows(), row, atlasUv);
                    data[start + 22] = atlasUv.x; data[start + 23] = atlasUv.y;
                    data[start + 24] = atlasUv.z; data[start + 25] = atlasUv.w;
                }
                data[start + 26] = fraction;
                data[start + 27] = data[start + 28] = data[start + 29] = 0;
            } else {
                attributes(raw.values, p, data, start + 10);
                Arrays.fill(data, start + 22, start + 30, 0);
            }
            data[start + 30] = dynamicMask;
            Arrays.fill(data, start + 31, start + EffectMesh.Kind.SPRITE.floatsPerInstance(), 0);
            data[start + 43] = .5f;
            data[start + 44] = .5f;
            if (unity != null) {
                var particle = snapshot.particles().get(order[i]);
                data[start + 28] = particle.billboardFlipX() ? -1 : 0;
                data[start + 29] = particle.billboardFlipY() ? -1 : 0;
                unityBillboards.pack(unity, snapshot, order[i], data, start);
            }
            if (snapshot.nativeParticles() != null) {
                var particles = snapshot.nativeParticles();
                int row = order[i];
                spriteComponent.position(data, start, particles.simulationPosition(row, 0), particles.simulationPosition(row, 1),
                        particles.simulationPosition(row, 2), definition.localSpace(), false);
                spriteComponent.direction(data, start + 31, particles.velocity(row, 0), particles.velocity(row, 1), particles.velocity(row, 2),
                        definition.localSpace(), false);
                spriteComponent.direction(data, start + 34, particles.facing(row, 0), particles.facing(row, 1), particles.facing(row, 2),
                        definition.localSpace(), false);
                spriteComponent.direction(data, start + 37, particles.alignment(row, 0), particles.alignment(row, 1), particles.alignment(row, 2),
                        definition.localSpace(), false);
                data[start + 40] = particles.facingMode(); data[start + 41] = particles.alignmentMode();
                data[start + 42] = particles.cameraOffset(row) * .01f;
                data[start + 43] = particles.pivot(row, 0); data[start + 44] = particles.pivot(row, 1);
                float minimum = particles.facingBlendMin(), maximum = particles.facingBlendMax();
                float inverseRange = 1f / Math.max(maximum * maximum - minimum * minimum, 1f);
                data[start + 45] = inverseRange; data[start + 46] = minimum * minimum * inverseRange;
                int sub = start + 26;
                float image = particles.subImage(row);
                data[sub] = ParticleAtlas.sourceSubImages(image, particles.subImageColumns(),
                        particles.subImageRows(), particles.subImageBlend(), data, start + 6, start + 22);
                data[sub + 1] = 1;
                data[sub + 2] = particles.uvScale(row, 0); data[sub + 3] = particles.uvScale(row, 1);
                data[start + 47] = image;
                int extraOffset = start / mesh.kind().floatsPerInstance() * mesh.extraFloatsPerInstance();
                com.kltyton.lumenfx.client.render.niagara.SpriteMotionData.write(mesh.extraValues(), extraOffset + 12,
                        particles, row, definition.localSpace(), spriteComponent);
            }
        }
    }

    private void sortUnity(UnityBillboard.Order mode, int count) {
        switch (mode) {
            case DISTANCE, DEPTH, DISTANCE_REVERSE, DEPTH_REVERSE -> {
                boolean viewPlane = !mode.usesCameraDistance() || meshView != null && meshView.perspective() == 0;
                boolean reverse = mode == UnityBillboard.Order.DISTANCE_REVERSE || mode == UnityBillboard.Order.DEPTH_REVERSE;
                sortByViewDepth = false;
                IntComparator comparator = viewPlane ? (reverse ? reverseViewPlaneOrder : viewPlaneOrder)
                        : (reverse ? reverseDistanceOrder : depthOrder);
                IntArrays.quickSort(order, 0, count, comparator);
            }
            case OLDEST_IN_FRONT -> IntArrays.quickSort(order, 0, count, youngestFirst);
            case YOUNGEST_IN_FRONT -> IntArrays.quickSort(order, 0, count, oldestFirst);
            case NONE -> { }
        }
    }

    private static void spriteVector(float[] target, int offset, float sx, float sy, float sz,
                                     boolean local, com.kltyton.lumenfx.api.client.EffectHandle.Pose pose) {
        spriteVector(target, offset, sx, sy, sz, local, pose, false);
    }

    private static void spriteVector(float[] target, int offset, float sx, float sy, float sz,
                                      boolean local, com.kltyton.lumenfx.api.client.EffectHandle.Pose pose, boolean includeScale) {
        spriteVector(target, offset, sx, sy, sz, local, pose, includeScale, true);
    }

    private static void spriteVector(float[] target, int offset, float sx, float sy, float sz,
                                      boolean local, com.kltyton.lumenfx.api.client.EffectHandle.Pose pose,
                                      boolean includeScale, boolean componentScale) {
        double x = sx, y = sz, z = sy;
        if (local) {
            if (componentScale) {
                x *= pose.scaleX() * (includeScale ? 1 : com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices.inverseAxisScale(pose, 0, false));
                y *= pose.scaleY() * (includeScale ? 1 : com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices.inverseAxisScale(pose, 1, false));
                z *= pose.scaleZ() * (includeScale ? 1 : com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices.inverseAxisScale(pose, 2, false));
            }
            double tx = 2 * (pose.y() * z - pose.z() * y), ty = 2 * (pose.z() * x - pose.x() * z), tz = 2 * (pose.x() * y - pose.y() * x);
            double resultX = x + pose.w() * tx + pose.y() * tz - pose.z() * ty;
            double resultY = y + pose.w() * ty + pose.z() * tx - pose.x() * tz;
            z = z + pose.w() * tz + pose.x() * ty - pose.y() * tx;
            x = resultX; y = resultY;
        }
        target[offset] = (float) x; target[offset + 1] = (float) y; target[offset + 2] = (float) z;
    }

    private void ribbon(EffectMesh mesh, EmitterInstance.Snapshot snapshot) {
        int count = samples(snapshot);
        if (snapshot.nativeParticles() == null) setDepth(mesh, count);
        else mesh.depth(com.kltyton.lumenfx.client.render.mesh.MeshDrawSelection.depth(snapshot.input().pose(),
                snapshot.nativeParticles().componentBounds(), cameraPosition, meshView));
        if (mesh.key().particleMotion()) ribbonMotion.begin(snapshot, cameraX, cameraY, cameraZ);
        IntArrays.quickSort(order, 0, count, birthOrder);
        if (count != 0 && snapshot.definition().attachHead()) {
            int newest = order[count - 1];
            var particle = snapshot.particles().get(newest);
            var head = snapshot.input().pose().position();
            fill(snapshot, particle, count, 0, snapshot.time(), head.x, head.y, head.z);
            if (distance(raw.values, newest * STRIDE, count * STRIDE) != 0) { order[count] = count; count++; }
        }
        int first = 0;
        sourceStrips.clear();
        while (first < count) {
            int last = first + 1;
            long ribbon = raw.ribbons[order[first]];
            while (last < count && raw.ribbons[order[last]] == ribbon) last++;
            if (snapshot.definition().ribbonSettings() == null) strip(mesh, first, last, snapshot.definition());
            else {
                RibbonStrip strip = prepareSourceStrip(first, last, snapshot.definition().ribbonSettings());
                if (strip != null) sourceStrips.add(strip);
            }
            first = last;
        }
        if (snapshot.definition().ribbonSettings() != null) {
            double total = 0, weightedLength = 0, weightedAngle = 0, weightedTwist = 0, weightedWidth = 0;
            double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
            double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
            for (RibbonStrip strip : sourceStrips) {
                total += strip.countedLength(); weightedLength += strip.weightedLength(); weightedAngle += strip.weightedAngle();
                weightedTwist += strip.weightedTwist(); weightedWidth += strip.weightedWidth();
                minX = Math.min(minX, strip.minX()); minY = Math.min(minY, strip.minY()); minZ = Math.min(minZ, strip.minZ());
                maxX = Math.max(maxX, strip.maxX()); maxY = Math.max(maxY, strip.maxY()); maxZ = Math.max(maxZ, strip.maxZ());
            }
            double dx = sourceStrips.isEmpty() ? 0 : Math.max(0, Math.max(minX - cameraX, cameraX - maxX));
            double dy = sourceStrips.isEmpty() ? 0 : Math.max(0, Math.max(minY - cameraY, cameraY - maxY));
            double dz = sourceStrips.isEmpty() ? 0 : Math.max(0, Math.max(minZ - cameraZ, cameraZ - maxZ));
            double viewDistance = snapshot.nativeParticles() == null ? Math.sqrt(dx * dx + dy * dy + dz * dz)
                    : Math.sqrt(com.kltyton.lumenfx.client.transform.NiagaraComponentBounds.distanceSquared(snapshot.input().pose(),
                    snapshot.nativeParticles().componentBounds(), cameraPosition)) * .01;
            var settings = snapshot.definition().ribbonSettings();
            int subdivisions = meshView != null && meshView.shadowPass()
                    ? snapshot.tessellation().subdivisions(settings, viewDistance, ribbonLodDistanceFactor)
                    : snapshot.tessellation().update(settings, total, weightedLength, weightedAngle,
                            weightedTwist, weightedWidth, ribbonHasTwist, viewDistance, ribbonLodDistanceFactor);
            for (RibbonStrip strip : sourceStrips) emitSourceStrip(mesh, strip, snapshot.definition(), subdivisions);
        }
    }

    private void unityRibbons(EffectMesh mesh, EmitterInstance.Snapshot snapshot, int ribbonCount,
                              @Nullable UnityTrailTexture texture, @Nullable UnityParticleTrail trail,
                              UnityRendererBinding binding) {
        int count = trail == null ? samples(snapshot) : unityRibbonSamples(snapshot, trail);
        IntArrays.quickSort(order, 0, count, birthOrder);
        if (unityRibbonBirthOrder.length < count)
            unityRibbonBirthOrder = new int[Math.max(count, unityRibbonBirthOrder.length * 2)];
        System.arraycopy(order, 0, unityRibbonBirthOrder, 0, count);
        var definition = snapshot.definition();
        var head = snapshot.input().pose().position();
        boolean attached = false;
        int first = 0;
        while (first < count) {
            int end = first + 1;
            long generation = raw.ribbons[unityRibbonBirthOrder[first]];
            while (end < count && raw.ribbons[unityRibbonBirthOrder[end]] == generation) end++;
            int groups = Math.min(ribbonCount, end - first);
            boolean attach = definition.attachHead() && generation == snapshot.ribbonGeneration();
            for (int group = 0; group < groups; group++) {
                int newest = end - 1 - group;
                int vertices = 0;
                int headIndex = unityRibbonBirthOrder[newest];
                var headParticle = snapshot.particles().get(headIndex);
                if (attach) {
                    fill(snapshot, headParticle, count, 0, snapshot.time(), head.x, head.y, head.z);
                    if (trail != null)
                        applyRibbonParticleOverrides(snapshot, headParticle, count, 0,
                                EmitterInstance.ribbonRandom(headParticle.random(), headParticle.born()), trail);
                    if (distance(raw.values, headIndex * STRIDE, count * STRIDE) != 0) {
                        order[vertices++] = count;
                        attached = true;
                    }
                }
                for (int row = newest; row >= first; row -= groups) order[vertices++] = unityRibbonBirthOrder[row];
                if (trail != null) {
                    if (vertices >= 2) {
                        double strandRandom = EmitterInstance.ribbonRandom(headParticle.random(), headParticle.born());
                        double total = 0;
                        for (int k = 1; k < vertices; k++)
                            total += distance(raw.values, order[k - 1] * STRIDE, order[k] * STRIDE);
                        double covered = 0;
                        for (int k = 0; k < vertices; k++) {
                            if (k > 0) covered += distance(raw.values, order[k - 1] * STRIDE, order[k] * STRIDE);
                            double along = total > 0 ? Math.clamp(covered / total, 0, 1) : 0;
                            int vp = order[k] * STRIDE;
                            raw.values[vp + WIDTH] = Math.max(0, raw.values[vp + WIDTH] * trail.widthOverTrail().sample(along, strandRandom));
                            raw.values[vp + COLOR] *= trail.colorOverTrail().sampleChannel(0, along, strandRandom, strandRandom);
                            raw.values[vp + COLOR + 1] *= trail.colorOverTrail().sampleChannel(1, along, strandRandom, strandRandom);
                            raw.values[vp + COLOR + 2] *= trail.colorOverTrail().sampleChannel(2, along, strandRandom, strandRandom);
                            raw.values[vp + COLOR + 3] *= trail.colorOverTrail().sampleChannel(3, along, strandRandom, strandRandom);
                        }
                    }
                }
                unityStrip(mesh, 0, vertices, definition, texture, trail != null);
            }
            first = end;
        }
        setUnityDepth(mesh, snapshot, binding, count + (attached ? 1 : 0));
    }

    private int unityRibbonSamples(EmitterInstance.Snapshot snapshot, UnityParticleTrail trail) {
        nativeOrdering = false;
        ribbonHasDistance = ribbonHasU0 = ribbonHasU1 = ribbonHasV0 = ribbonHasV1 = ribbonHasFacing = ribbonHasTwist = false;
        dynamicMask = 15;
        // Ribbon mode joins live particles. Particle trail history settings do not filter those nodes.
        var live = snapshot.particles();
        int capacity = live.size() + 1;
        raw.reserve(capacity);
        if (order.length < capacity) order = Arrays.copyOf(order, Math.max(capacity, order.length * 2));
        for (int i = 0; i < live.size(); i++) {
            var particle = live.get(i);
            double randomHash = EmitterInstance.ribbonRandom(particle.random(), particle.born());
            sample(snapshot, particle, i);
            applyRibbonParticleOverrides(snapshot, particle, i, particle.normalizedAge(snapshot.time()), randomHash, trail);
            order[i] = i;
        }
        raw.count = live.size();
        return raw.count;
    }

    private void applyRibbonParticleOverrides(EmitterInstance.Snapshot snapshot, ParticleState particle, int index,
                                              double age, double randomHash, UnityParticleTrail trail) {
        int p = index * STRIDE;
        if (!trail.sizeAffectsWidth()) raw.values[p + WIDTH] = 1;
        if (!trail.inheritColor()) {
            var input = snapshot.input();
            raw.values[p + COLOR] = input.red();
            raw.values[p + COLOR + 1] = input.green();
            raw.values[p + COLOR + 2] = input.blue();
            raw.values[p + COLOR + 3] = input.alpha();
        }
        var overLife = trail.colorOverLifetime();
        raw.values[p + COLOR] *= overLife.sampleChannel(0, age, randomHash, randomHash);
        raw.values[p + COLOR + 1] *= overLife.sampleChannel(1, age, randomHash, randomHash);
        raw.values[p + COLOR + 2] *= overLife.sampleChannel(2, age, randomHash, randomHash);
        raw.values[p + COLOR + 3] *= overLife.sampleChannel(3, age, randomHash, randomHash);
    }

    private void unityTrails(EffectMesh mesh, EmitterInstance.Snapshot snapshot, @Nullable UnityTrailTexture texture,
                             UnityRendererBinding binding) {
        var vertices = snapshot.trailVertices();
        int count = vertices.size();
        raw.reserve(count);
        if (order.length < count) order = Arrays.copyOf(order, Math.max(count, order.length * 2));
        var input = snapshot.input();
        dynamicMask = 15;
        for (int i = 0; i < count; i++) {
            var vertex = vertices.get(i);
            int p = i * STRIDE;
            Arrays.fill(raw.values, p, p + STRIDE, 0);
            raw.values[p + X] = vertex.position().x;
            raw.values[p + Y] = vertex.position().y;
            raw.values[p + Z] = vertex.position().z;
            raw.values[p + WIDTH] = vertex.width();
            raw.values[p + COLOR] = vertex.color().red() * input.red();
            raw.values[p + COLOR + 1] = vertex.color().green() * input.green();
            raw.values[p + COLOR + 2] = vertex.color().blue() * input.blue();
            raw.values[p + COLOR + 3] = vertex.color().alpha() * input.alpha();
            raw.values[p + BORN] = vertex.time();
            raw.ribbons[i] = vertex.strip();
            order[i] = i;
        }
        raw.count = count;
        setUnityDepth(mesh, snapshot, binding, count);
        int first = 0;
        while (first < count) {
            int last = first + 1;
            while (last < count && raw.ribbons[last] == raw.ribbons[first]) last++;
            unityStrip(mesh, first, last, snapshot.definition(), texture, false);
            first = last;
        }
    }

    private void beam(EffectMesh mesh, EmitterInstance.Snapshot snapshot) {
        dynamicMask = 15;
        if (!snapshot.emitting()) return;
        raw.reserve(2); raw.count = 2; order[0] = 0; order[1] = 1;
        var definition = snapshot.definition(); var input = snapshot.input();
        double age = Math.clamp(snapshot.emitterAge() / definition.duration(), 0, 1);
        for (int i = 0; i < 2; i++) {
            int p = i * STRIDE;
            var point = i == 0 ? input.pose().position() : input.beamEnd();
            raw.values[p] = point.x; raw.values[p + 1] = point.y; raw.values[p + 2] = point.z;
            raw.values[p + WIDTH] = definition.width().sample(age, 0) * input.pose().scale();
            var color = definition.colorOverLife(); var start = definition.startColor();
            raw.values[p + COLOR] = color.sampleChannel(0, age, 0, 0) * start.sampleChannel(0, age, 0, 0) * input.red();
            raw.values[p + COLOR + 1] = color.sampleChannel(1, age, 0, 0) * start.sampleChannel(1, age, 0, 0) * input.green();
            raw.values[p + COLOR + 2] = color.sampleChannel(2, age, 0, 0) * start.sampleChannel(2, age, 0, 0) * input.blue();
            raw.values[p + COLOR + 3] = color.sampleChannel(3, age, 0, 0) * start.sampleChannel(3, age, 0, 0) * input.alpha();
            raw.values[p + AGE] = age; raw.values[p + RANDOM] = 0; raw.values[p + LIFE] = definition.duration(); raw.values[p + BORN] = 0;
            for (int field = 0; field < 4; field++) raw.values[p + CUSTOM + field] = definition.customData().get(field).sample(age, 0);
            if (extraSlots != 0) Arrays.fill(raw.values, p + EXTRA, p + EXTRA + 12, 0);
            if (ribbonParticleData) Arrays.fill(raw.values, p + RIBBON_VELOCITY, p + RIBBON_VELOCITY + 3, 0);
        }
        strip(mesh, 0, 2, definition);
        setDepth(mesh, 2);
    }

    private void setDepth(EffectMesh mesh, int count) {
        mesh.depth(boundsDepth(count));
    }

    private void setUnityDepth(EffectMesh mesh, EmitterInstance.Snapshot snapshot,
                               @Nullable UnityRendererBinding binding, int count) {
        mesh.depth(unityDepth(snapshot, binding, count));
    }

    private double unityDepth(EmitterInstance.Snapshot snapshot, @Nullable UnityRendererBinding binding, int count) {
        var sorting = binding == null ? null : binding.sorting();
        if (sorting != null && sorting.grouped()) {
            Vec3 rootWorld = snapshot.unityRootPose().transformPosition(Objects.requireNonNull(sorting.rootGroup()).position());
            return unitySortDepth(rootWorld.x, rootWorld.y, rootWorld.z);
        }
        if (binding == null) return boundsDepth(count);
        if (count == 0) return 0;
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (int i = 0; i < count; i++) {
            int p = i * STRIDE;
            minX = Math.min(minX, raw.values[p]); maxX = Math.max(maxX, raw.values[p]);
            minY = Math.min(minY, raw.values[p + 1]); maxY = Math.max(maxY, raw.values[p + 1]);
            minZ = Math.min(minZ, raw.values[p + 2]); maxZ = Math.max(maxZ, raw.values[p + 2]);
        }
        return unitySortDepth((minX + maxX) * .5, (minY + maxY) * .5, (minZ + maxZ) * .5);
    }

    private double unitySortDepth(double x, double y, double z) {
        double dx = x - cameraX, dy = y - cameraY, dz = z - cameraZ;
        if (meshView != null && meshView.perspective() == 0) {
            return NiagaraTranslucencySort.distanceKey(
                    dx * sourceViewDirection.x + dy * sourceViewDirection.y + dz * sourceViewDirection.z);
        }
        return dx * dx + dy * dy + dz * dz;
    }

    private double boundsDepth(int count) {
        if (count == 0) return 0;
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (int i = 0; i < count; i++) {
            int p = i * STRIDE;
            minX = Math.min(minX, raw.values[p]); maxX = Math.max(maxX, raw.values[p]);
            minY = Math.min(minY, raw.values[p + 1]); maxY = Math.max(maxY, raw.values[p + 1]);
            minZ = Math.min(minZ, raw.values[p + 2]); maxZ = Math.max(maxZ, raw.values[p + 2]);
        }
        double x = (minX + maxX) * .5 - cameraX, y = (minY + maxY) * .5 - cameraY, z = (minZ + maxZ) * .5 - cameraZ;
        return x * x + y * y + z * z;
    }

    private void strip(EffectMesh mesh, int first, int last, EmitterDefinition definition) {
        strip(mesh, first, last, definition, null, false);
    }

    private void unityStrip(EffectMesh mesh, int first, int last, EmitterDefinition definition,
                            @Nullable UnityTrailTexture texture, boolean shurikenRibbon) {
        if (texture != null) {
            // Shuriken's texture origin is the live head for particles and the oldest vertex for ribbons.
            for (int left = first, right = last - 1; left < right; left++, right--) {
                int vertex = order[left];
                order[left] = order[right];
                order[right] = vertex;
            }
        }
        strip(mesh, first, last, definition, texture, shurikenRibbon);
    }

    private void strip(EffectMesh mesh, int first, int last, EmitterDefinition definition,
                       @Nullable UnityTrailTexture texture, boolean shurikenRibbon) {
        if (last - first < 2) return;
        sourceUv0 = sourceUv1 = null;
        dense.reserve(Math.addExact(Math.multiplyExact(last - first - 1, definition.subdivisions()), 1));
        int count = 0;
        for (int i = first; i + 1 < last; i++) {
            int a = order[i] * STRIDE, b = order[i + 1] * STRIDE;
            if (distance(raw.values, a, b) == 0) continue;
            int before = order[Math.max(first, i - 1)] * STRIDE, after = order[Math.min(last - 1, i + 2)] * STRIDE;
            for (int step = 0; step < definition.subdivisions(); step++) {
                double t = (double)step / definition.subdivisions();
                int p = count++ * STRIDE;
                for (int axis = 0; axis < 3; axis++) {
                    dense.values[p + axis] = TrailSpline.catmullRom(raw.values[before + axis], raw.values[a + axis],
                            raw.values[b + axis], raw.values[after + axis], t);
                }
                for (int field = WIDTH; field < DISTANCE; field++) dense.values[p + field] = raw.values[a + field] + (raw.values[b + field] - raw.values[a + field]) * t;
                if (extraSlots != 0) for (int field = EXTRA; field < EXTRA + 12; field++)
                    dense.values[p + field] = raw.values[a + field] + (raw.values[b + field] - raw.values[a + field]) * t;
                if (ribbonParticleData) for (int field = RIBBON_VELOCITY; field < RIBBON_VELOCITY + 3; field++)
                    dense.values[p + field] = raw.values[a + field] + (raw.values[b + field] - raw.values[a + field]) * t;
                dense.values[p + RANDOM] = raw.values[a + RANDOM];
                dense.values[p + ROTATION] = raw.values[a + ROTATION];
                dense.values[p + SEGMENT_FRACTION] = i - first + t;
            }
        }
        if (count == 0) return;
        System.arraycopy(raw.values, order[last - 1] * STRIDE, dense.values, count * STRIDE, DISTANCE);
        if (extraSlots != 0) System.arraycopy(raw.values, order[last - 1] * STRIDE + EXTRA, dense.values, count * STRIDE + EXTRA, 12);
        if (ribbonParticleData) System.arraycopy(raw.values, order[last - 1] * STRIDE + RIBBON_VELOCITY,
                dense.values, count * STRIDE + RIBBON_VELOCITY, 3);
        dense.values[count * STRIDE + SEGMENT_FRACTION] = last - first - 1;
        count++;
        dense.values[DISTANCE] = 0;
        for (int i = 1; i < count; i++) dense.values[i * STRIDE + DISTANCE] = dense.values[(i - 1) * STRIDE + DISTANCE] + distance(dense.values, (i - 1) * STRIDE, i * STRIDE);
        double length = dense.values[(count - 1) * STRIDE + DISTANCE];
        if (length == 0) return;
        if (!shurikenRibbon || ribbonMaterialBasis) writeStripSides(count);
        if (shurikenRibbon) shurikenRibbonJoins.apply(dense.values, count, STRIDE, WIDTH, SIDE);
        for (int i = 0; i + 1 < count; i++) {
            int a = i * STRIDE, b = (i + 1) * STRIDE;
            int offset = patch(mesh, a, b, length, definition, null, null);
            if (texture == null) continue;
            float[] data = mesh.values();
            int segments = last - first - 1;
            double firstOrdinal = dense.values[a + SEGMENT_FRACTION];
            double endOrdinal = firstOrdinal + 1.0 / definition.subdivisions();
            data[offset + 12] = (float)texture.u(dense.values[a + DISTANCE], length, firstOrdinal, segments);
            data[offset + 13] = (float)texture.u(dense.values[b + DISTANCE], length, endOrdinal, segments);
            data[offset + 16] = (float)texture.scaleV();
            data[offset + 17] = (float)texture.rasterOffsetV();
        }
    }

    private void writeStripSides(int count) {
        double previousX = 0, previousY = 0, previousZ = 0;
        for (int i = 0; i < count; i++) {
            int p = i * STRIDE, before = Math.max(0, i - 1) * STRIDE, after = Math.min(count - 1, i + 1) * STRIDE;
            double tx = dense.values[after] - dense.values[before], ty = dense.values[after + 1] - dense.values[before + 1], tz = dense.values[after + 2] - dense.values[before + 2];
            if (tx * tx + ty * ty + tz * tz == 0) { tx = dense.values[after] - dense.values[p]; ty = dense.values[after + 1] - dense.values[p + 1]; tz = dense.values[after + 2] - dense.values[p + 2]; }
            if (tx * tx + ty * ty + tz * tz == 0) { tx = dense.values[p] - dense.values[before]; ty = dense.values[p + 1] - dense.values[before + 1]; tz = dense.values[p + 2] - dense.values[before + 2]; }
            double vx = cameraX - dense.values[p], vy = cameraY - dense.values[p + 1], vz = cameraZ - dense.values[p + 2];
            double sx = ty * vz - tz * vy, sy = tz * vx - tx * vz, sz = tx * vy - ty * vx;
            double square = sx * sx + sy * sy + sz * sz;
            if (square < 1e-12) { sx = ty * upZ - tz * upY; sy = tz * upX - tx * upZ; sz = tx * upY - ty * upX; square = sx * sx + sy * sy + sz * sz; }
            if (square < 1e-12) { sx = ty * rightZ - tz * rightY; sy = tz * rightX - tx * rightZ; sz = tx * rightY - ty * rightX; square = sx * sx + sy * sy + sz * sz; }
            double factor = square == 0 ? 0 : 1 / Math.sqrt(square);
            sx *= factor; sy *= factor; sz *= factor;
            if (sx * previousX + sy * previousY + sz * previousZ < 0) { sx = -sx; sy = -sy; sz = -sz; }
            previousX = sx; previousY = sy; previousZ = sz;
            double width = dense.values[p + WIDTH] * .5;
            dense.values[p + SIDE] = sx * width; dense.values[p + SIDE + 1] = sy * width; dense.values[p + SIDE + 2] = sz * width;
            if (ribbonMaterialBasis) {
                double tangentLength = Math.sqrt(tx * tx + ty * ty + tz * tz);
                double viewLength = Math.sqrt(vx * vx + vy * vy + vz * vz);
                dense.values[p + RIBBON_DIRECTION] = tangentLength > 0 ? tx / tangentLength : 0;
                dense.values[p + RIBBON_DIRECTION + 1] = tangentLength > 0 ? ty / tangentLength : 0;
                dense.values[p + RIBBON_DIRECTION + 2] = tangentLength > 0 ? tz / tangentLength : 0;
                dense.values[p + RIBBON_RIGHT] = sx; dense.values[p + RIBBON_RIGHT + 1] = sy; dense.values[p + RIBBON_RIGHT + 2] = sz;
                dense.values[p + RIBBON_NORMAL_FACING] = viewLength > 0 ? vx / viewLength : 0;
                dense.values[p + RIBBON_NORMAL_FACING + 1] = viewLength > 0 ? vy / viewLength : 0;
                dense.values[p + RIBBON_NORMAL_FACING + 2] = viewLength > 0 ? vz / viewLength : 0;
            }
        }
    }

    private int patch(EffectMesh mesh, int a, int b, double length, EmitterDefinition definition,
                       RibbonShapeGeometry.Vertex firstVertex, RibbonShapeGeometry.Vertex secondVertex) {
        int start = mesh.appendInstance();
        float[] data = mesh.values();
        var settings = definition.ribbonSettings();
        if (mesh.needsExtra()) {
            float[] extra = mesh.extraValues();
            int extraOffset = start / mesh.kind().floatsPerInstance() * mesh.extraFloatsPerInstance();
            if (extraSlots != 0) for (int channel = 0; channel < 12; channel++) {
                extra[extraOffset + channel] = (float) dense.values[a + EXTRA + channel];
                extra[extraOffset + 12 + channel] = (float) dense.values[b + EXTRA + channel];
            }
            if (ribbonParticleData) {
                for (int axis = 0; axis < 3; axis++) {
                    extra[extraOffset + 24 + axis] = (float)dense.values[a + RIBBON_VELOCITY + axis];
                    extra[extraOffset + 28 + axis] = (float)dense.values[b + RIBBON_VELOCITY + axis];
                }
                extra[extraOffset + 27] = (float)(dense.values[a + WIDTH] * 100);
                extra[extraOffset + 31] = (float)(dense.values[b + WIDTH] * 100);
            }
            if (mesh.key().particleMotion()) ribbonMotion.write(extra, extraOffset + 32, a / STRIDE, b / STRIDE, firstVertex, secondVertex);
            if (mesh.needsMaterialBasis()) {
                int basis = extraOffset + mesh.materialBasisOffset();
                writeRibbonBasis(extra, basis, a);
                writeRibbonBasis(extra, basis + 12, b);
                extra[basis + 3] = settings == null || settings.shape().usesGeometryNormals() ? 1 : 0;
                extra[basis + 7] = settings != null && settings.shape().flipNormalToView() ? 1 : 0;
                extra[basis + 24] = firstVertex == null ? 0 : firstVertex.normalX();
                extra[basis + 25] = firstVertex == null ? 1 : firstVertex.normalY();
                extra[basis + 26] = secondVertex == null ? 0 : secondVertex.normalX();
                extra[basis + 27] = secondVertex == null ? 1 : secondVertex.normalY();
            }
        }
        data[start] = (float)(dense.values[a] - cameraX); data[start + 1] = (float)(dense.values[a + 1] - cameraY); data[start + 2] = (float)(dense.values[a + 2] - cameraZ);
        data[start + 3] = (float)(dense.values[b] - cameraX); data[start + 4] = (float)(dense.values[b + 1] - cameraY); data[start + 5] = (float)(dense.values[b + 2] - cameraZ);
        for (int axis = 0; axis < 3; axis++) { data[start + 6 + axis] = (float)dense.values[a + SIDE + axis]; data[start + 9 + axis] = (float)dense.values[b + SIDE + axis]; }
        if (firstVertex != null && secondVertex != null) {
            for (int axis = 0; axis < 3; axis++) {
                double midX = firstVertex.x() + secondVertex.x(), midY = firstVertex.y() + secondVertex.y();
                double deltaX = secondVertex.x() - firstVertex.x(), deltaY = secondVertex.y() - firstVertex.y();
                data[start + axis] += (float)(dense.values[a + SIDE + axis] * midX + dense.values[a + RIBBON_UP + axis] * midY);
                data[start + 3 + axis] += (float)(dense.values[b + SIDE + axis] * midX + dense.values[b + RIBBON_UP + axis] * midY);
                data[start + 6 + axis] = (float)(dense.values[a + SIDE + axis] * deltaX + dense.values[a + RIBBON_UP + axis] * deltaY);
                data[start + 9 + axis] = (float)(dense.values[b + SIDE + axis] * deltaX + dense.values[b + RIBBON_UP + axis] * deltaY);
            }
        }
        double denominator = definition.uvMode() == EmitterDefinition.UvMode.DISTANCE ? definition.uvDistance() : length;
        data[start + 12] = (float)(dense.values[a + DISTANCE] / denominator); data[start + 13] = (float)(dense.values[b + DISTANCE] / denominator);
        data[start + 14] = (float)(dense.values[a + DISTANCE] / length); data[start + 15] = (float)(dense.values[b + DISTANCE] / length);
        if (settings != null) {
            data[start + 12] = (float)ribbonU(settings.uv0(), sourceUv0, a, length, 0);
            data[start + 13] = (float)ribbonU(settings.uv0(), sourceUv0, b, length, 0);
            data[start + 14] = (float)ribbonU(settings.uv1(), sourceUv1, a, length, 1);
            data[start + 15] = (float)ribbonU(settings.uv1(), sourceUv1, b, length, 1);
        }
        data[start + 16] = settings == null ? 1 : (float)settings.uv0().scaleV();
        data[start + 17] = settings == null ? 0 : (float)settings.uv0().offsetV();
        data[start + 18] = settings == null ? 1 : (float)settings.uv1().scaleV();
        data[start + 19] = settings == null ? 0 : (float)settings.uv1().offsetV();
        attributes(dense.values, a, data, start + 20);
        attributes(dense.values, b, data, start + 32);
        data[start + 44] = dynamicMask;
        float firstV = firstVertex == null ? 0 : firstVertex.textureV();
        float secondV = secondVertex == null ? 1 : secondVertex.textureV();
        ribbonV(data, start + 45, a, settings, firstV, secondV);
        ribbonV(data, start + 49, b, settings, firstV, secondV);
        data[start + 53] = (float)(dense.values[a] - cameraX);
        data[start + 54] = (float)(dense.values[a + 1] - cameraY);
        data[start + 55] = (float)(dense.values[a + 2] - cameraZ);
        data[start + 56] = (float)(dense.values[b] - cameraX);
        data[start + 57] = (float)(dense.values[b + 1] - cameraY);
        data[start + 58] = (float)(dense.values[b + 2] - cameraZ);
        return start;
    }

    private RibbonStrip prepareSourceStrip(int first, int last, RibbonSettings settings) {
        if (last - first < 2) return null;
        double minimum = settings.minimumSegmentLength();
        int originalLast = last - 1, next = first + 1;
        while (next < last && distance(raw.values, order[first] * STRIDE, order[next] * STRIDE) <= minimum) next++;
        if (next == last) return null;
        int kept = first + 1;
        order[kept++] = order[next++];
        while (next < last) {
            int candidate = order[next];
            double distance = distance(raw.values, order[kept - 1] * STRIDE, candidate * STRIDE);
            if (distance > minimum || next == originalLast && distance > 0)
                order[kept++] = candidate;
            next++;
        }
        last = kept;
        double length = 0, weightedLength = 0, weightedAngle = 0, weightedTwist = 0, weightedWidth = 0;
        double previousX = 0, previousY = 0, previousZ = 0;
        double minimumX = Double.POSITIVE_INFINITY, minimumY = minimumX, minimumZ = minimumX;
        double maximumX = Double.NEGATIVE_INFINITY, maximumY = maximumX, maximumZ = maximumX;
        for (int i = first; i < last; i++) {
            int p = order[i] * STRIDE;
            minimumX = Math.min(minimumX, raw.values[p]); maximumX = Math.max(maximumX, raw.values[p]);
            minimumY = Math.min(minimumY, raw.values[p + 1]); maximumY = Math.max(maximumY, raw.values[p + 1]);
            minimumZ = Math.min(minimumZ, raw.values[p + 2]); maximumZ = Math.max(maximumZ, raw.values[p + 2]);
            double nx = 0, ny = 0, nz = 0, segmentLength = 0;
            if (i + 1 < last) {
                int after = order[i + 1] * STRIDE;
                segmentLength = distance(raw.values, p, after);
                double factor = 1 / Math.max(minimum, segmentLength);
                nx = (raw.values[after] - raw.values[p]) * factor;
                ny = (raw.values[after + 1] - raw.values[p + 1]) * factor;
                nz = (raw.values[after + 2] - raw.values[p + 2]) * factor;
            }
            raw.values[p + DISTANCE] = length;
            if (i == first) {
                raw.values[p + TANGENT] = nx; raw.values[p + TANGENT + 1] = ny; raw.values[p + TANGENT + 2] = nz;
            } else if (i == last - 1) {
                raw.values[p + TANGENT] = previousX; raw.values[p + TANGENT + 1] = previousY; raw.values[p + TANGENT + 2] = previousZ;
            } else {
                double tx = previousX + nx, ty = previousY + ny, tz = previousZ + nz;
                double square = tx * tx + ty * ty + tz * tz;
                double factor = square < 1e-8 ? 0 : (1 - settings.tension()) / Math.sqrt(square);
                raw.values[p + TANGENT] = tx * factor; raw.values[p + TANGENT + 1] = ty * factor; raw.values[p + TANGENT + 2] = tz * factor;
                weightedLength += segmentLength * segmentLength;
                weightedAngle += segmentLength * RibbonTessellation.acosFast(previousX * nx + previousY * ny + previousZ * nz);
                if (ribbonHasTwist) {
                    int after = order[i + 1] * STRIDE;
                    weightedTwist += segmentLength * Math.abs(raw.values[after + RIBBON_TWIST] - raw.values[p + RIBBON_TWIST]);
                    weightedWidth += segmentLength * raw.values[p + WIDTH];
                }
            }
            previousX = nx; previousY = ny; previousZ = nz;
            length += segmentLength;
        }
        if (last - first > 2) {
            reflectTangent(order[first] * STRIDE, order[first + 1] * STRIDE);
            reflectTangent(order[last - 1] * STRIDE, order[last - 2] * STRIDE);
        }
        double firstLength = distance(raw.values, order[first] * STRIDE, order[first + 1] * STRIDE);
        return new RibbonStrip(first, last, length, length - firstLength, weightedLength, weightedAngle,
                weightedTwist, weightedWidth, minimumX, minimumY, minimumZ, maximumX, maximumY, maximumZ);
    }

    private void emitSourceStrip(EffectMesh mesh, RibbonStrip strip, EmitterDefinition definition, int subdivisions) {
        int first = strip.first(), last = strip.last();
        double length = strip.length();
        RibbonSettings settings = definition.ribbonSettings();
        dense.reserve((last - first - 1) * subdivisions + 1);
        int count = 0;
        for (int i = first; i + 1 < last; i++) {
            int a = order[i] * STRIDE, b = order[i + 1] * STRIDE;
            double span = raw.values[b + DISTANCE] - raw.values[a + DISTANCE];
            for (int step = 0; step < subdivisions; step++) {
                double t = (double)step / subdivisions;
                int p = count++ * STRIDE;
                for (int axis = 0; axis < 3; axis++) dense.values[p + axis] = TrailSpline.hermite(raw.values[a + axis], raw.values[b + axis],
                        raw.values[a + TANGENT + axis] * span, raw.values[b + TANGENT + axis] * span, t);
                for (int field = WIDTH; field <= DISTANCE; field++) dense.values[p + field] = raw.values[a + field] + (raw.values[b + field] - raw.values[a + field]) * t;
                if (extraSlots != 0) for (int field = EXTRA; field < EXTRA + 12; field++)
                    dense.values[p + field] = raw.values[a + field] + (raw.values[b + field] - raw.values[a + field]) * t;
                for (int field = RIBBON_UV_DISTANCE; field < RIBBON_VELOCITY + 3; field++)
                    dense.values[p + field] = raw.values[a + field] + (raw.values[b + field] - raw.values[a + field]) * t;
                dense.values[p + SEGMENT_FRACTION] = (i - first + t) / (last - first - 1);
                sourceSide(p, a, b, t, settings);
                if (mesh.key().particleMotion()) ribbonMotion.sample(p / STRIDE, a / STRIDE, b / STRIDE, t, span,
                        raw.values, a + TANGENT, b + TANGENT, settings);
            }
        }
        int end = count++ * STRIDE, lastPoint = order[last - 1] * STRIDE;
        System.arraycopy(raw.values, lastPoint, dense.values, end, DISTANCE + 1);
        if (extraSlots != 0) System.arraycopy(raw.values, lastPoint + EXTRA, dense.values, end + EXTRA, 12);
        System.arraycopy(raw.values, lastPoint + RIBBON_UV_DISTANCE, dense.values, end + RIBBON_UV_DISTANCE, RIBBON_VELOCITY + 3 - RIBBON_UV_DISTANCE);
        dense.values[end + SEGMENT_FRACTION] = 1;
        sourceSide(end, lastPoint, lastPoint, 0, settings);
        if (mesh.key().particleMotion()) ribbonMotion.sample(end / STRIDE, lastPoint / STRIDE, lastPoint / STRIDE, 0, 0,
                raw.values, lastPoint + TANGENT, lastPoint + TANGENT, settings);
        sourceUv0 = ribbonUv(settings.uv0(), first, last, length, ribbonHasU0);
        sourceUv1 = ribbonUv(settings.uv1(), first, last, length, ribbonHasU1);
        RibbonShapeGeometry shape = ribbonShapes.computeIfAbsent(settings.shape(), RibbonShapeGeometry::from);
        for (int i = 0; i + 1 < count; i++) for (int edge = 0; edge < shape.edges().size(); edge += 2)
            patch(mesh, i * STRIDE, (i + 1) * STRIDE, length, definition,
                    shape.vertices().get(shape.edges().get(edge)), shape.vertices().get(shape.edges().get(edge + 1)));
    }

    private RibbonUv ribbonUv(RibbonSettings.Uv uv, int first, int last, double length, boolean hasOverride) {
        if (uv.uOverride() && hasOverride) return new RibbonUv(1, 0, 1, true);
        int a = order[first] * STRIDE, b = order[first + 1] * STRIDE;
        int c = order[last - 2] * STRIDE, d = order[last - 1] * STRIDE;
        double firstAge = raw.values[a + AGE], secondAge = raw.values[b + AGE];
        double penultimateAge = raw.values[c + AGE], lastAge = raw.values[d + AGE];
        double startStep = secondAge - firstAge, endStep = lastAge - penultimateAge;
        double leading = uv.leadingEdge() == RibbonSettings.Edge.SMOOTH_TRANSITION && startStep > 0 && firstAge < startStep
                ? (startStep - firstAge) / startStep : 0;
        double trailing = uv.trailingEdge() == RibbonSettings.Edge.SMOOTH_TRANSITION && endStep > 0 && 1 - lastAge < endStep
                ? (endStep - (1 - lastAge)) / endStep : 0;
        double count = last - first - 1, scale, offset, distributionScale;
        switch (uv.distribution()) {
            case UNIFORM -> {
                scale = count / (count - leading - trailing);
                offset = -leading / count * scale;
                distributionScale = 1 / count;
            }
            case LENGTH -> {
                double leadingDistance = raw.values[b + DISTANCE] * leading;
                double trailingDistance = (raw.values[d + DISTANCE] - raw.values[c + DISTANCE]) * trailing;
                scale = length / (length - leadingDistance - trailingDistance);
                offset = -(leadingDistance / length) * scale;
                distributionScale = 1 / length;
            }
            case DISTANCE -> {
                double leadingDistance = raw.values[b + DISTANCE] * leading;
                scale = length / uv.tileLength();
                offset = -leadingDistance / uv.tileLength();
                distributionScale = 1 / length;
            }
            case DISTANCE_FROM_START -> {
                scale = length / uv.tileLength(); offset = 0; distributionScale = 1 / length;
            }
            default -> throw new IllegalStateException("Unknown Niagara ribbon UV distribution");
        }
        return new RibbonUv(scale * uv.scaleU(), offset * uv.scaleU() + uv.offsetU(), distributionScale, false);
    }

    private double ribbonU(RibbonSettings.Uv settings, RibbonUv uv, int point, double length, int channel) {
        if (uv == null) return settings.u(dense.values[point + DISTANCE], length, dense.values[point + SEGMENT_FRACTION]);
        if (uv.override()) return dense.values[point + (channel == 0 ? RIBBON_U0 : RIBBON_U1)];
        double source = switch (settings.distribution()) {
            case UNIFORM -> dense.values[point + SEGMENT_FRACTION];
            case LENGTH, DISTANCE -> dense.values[point + DISTANCE] * uv.distributionScale();
            case DISTANCE_FROM_START -> ribbonHasDistance ? dense.values[point + RIBBON_UV_DISTANCE] * uv.distributionScale() : 0;
        };
        return source * uv.scale() + uv.offset();
    }

    private void ribbonV(float[] target, int offset, int point, RibbonSettings settings, float firstV, float secondV) {
        boolean override0 = settings != null && settings.uv0().vOverride() && ribbonHasV0;
        boolean override1 = settings != null && settings.uv1().vOverride() && ribbonHasV1;
        float scale0 = override0 ? (float)(dense.values[point + RIBBON_V0 + 1] - dense.values[point + RIBBON_V0]) : 1;
        float scale1 = override1 ? (float)(dense.values[point + RIBBON_V1 + 1] - dense.values[point + RIBBON_V1]) : 1;
        target[offset] = scale0 * (secondV - firstV);
        target[offset + 1] = (override0 ? 1 : 0) + scale0 * firstV;
        target[offset + 2] = scale1 * (secondV - firstV);
        target[offset + 3] = (override1 ? 1 : 0) + scale1 * firstV;
    }

    private void reflectTangent(int endpoint, int neighbor) {
        double dot = 0;
        for (int axis = 0; axis < 3; axis++) dot += raw.values[endpoint + TANGENT + axis] * raw.values[neighbor + TANGENT + axis];
        for (int axis = 0; axis < 3; axis++) raw.values[endpoint + TANGENT + axis] = 2 * dot * raw.values[endpoint + TANGENT + axis] - raw.values[neighbor + TANGENT + axis];
    }

    private void sourceSide(int point, int a, int b, double t, RibbonSettings settings) {
        double tx = raw.values[a + TANGENT] + (raw.values[b + TANGENT] - raw.values[a + TANGENT]) * t;
        double ty = raw.values[a + TANGENT + 1] + (raw.values[b + TANGENT + 1] - raw.values[a + TANGENT + 1]) * t;
        double tz = raw.values[a + TANGENT + 2] + (raw.values[b + TANGENT + 2] - raw.values[a + TANGENT + 2]) * t;
        double directionLength = Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (directionLength > 0) { tx /= directionLength; ty /= directionLength; tz /= directionLength; }
        boolean customFacing = settings.shape().facing() != RibbonSettings.Facing.SCREEN && ribbonHasFacing;
        double vx = customFacing ? raw.values[a + RIBBON_FACING] + (raw.values[b + RIBBON_FACING] - raw.values[a + RIBBON_FACING]) * t : cameraX - dense.values[point];
        double vy = customFacing ? raw.values[a + RIBBON_FACING + 1] + (raw.values[b + RIBBON_FACING + 1] - raw.values[a + RIBBON_FACING + 1]) * t : cameraY - dense.values[point + 1];
        double vz = customFacing ? raw.values[a + RIBBON_FACING + 2] + (raw.values[b + RIBBON_FACING + 2] - raw.values[a + RIBBON_FACING + 2]) * t : cameraZ - dense.values[point + 2];
        double facingLength = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (facingLength > 0) { vx /= facingLength; vy /= facingLength; vz /= facingLength; }
        // Swapping UE Y/Z reverses handedness, so the transformed side is direction cross view.
        double sx = ty * vz - tz * vy, sy = tz * vx - tx * vz, sz = tx * vy - ty * vx;
        double square = sx * sx + sy * sy + sz * sz;
        double inverse = square <= 0 ? 0 : 1 / Math.sqrt(square);
        sx *= inverse; sy *= inverse; sz *= inverse;
        if (settings.shape().facing() == RibbonSettings.Facing.CUSTOM_SIDE_VECTOR) {
            double px = sx, py = sy, pz = sz;
            sx = -vx; sy = -vy; sz = -vz; vx = px; vy = py; vz = pz;
        }
        double twist = raw.values[a + RIBBON_TWIST] + (raw.values[b + RIBBON_TWIST] - raw.values[a + RIBBON_TWIST]) * t;
        double cosine = Math.cos(twist), sine = Math.sin(twist), scale = dense.values[point + WIDTH] * .5;
        double rx = cosine * sx + sine * vx, ry = cosine * sy + sine * vy, rz = cosine * sz + sine * vz;
        dense.values[point + SIDE] = rx * scale; dense.values[point + SIDE + 1] = ry * scale; dense.values[point + SIDE + 2] = rz * scale;
        if (ribbonMaterialBasis) {
            dense.values[point + RIBBON_DIRECTION] = tx; dense.values[point + RIBBON_DIRECTION + 1] = ty; dense.values[point + RIBBON_DIRECTION + 2] = tz;
            dense.values[point + RIBBON_RIGHT] = rx; dense.values[point + RIBBON_RIGHT + 1] = ry; dense.values[point + RIBBON_RIGHT + 2] = rz;
            dense.values[point + RIBBON_NORMAL_FACING] = -sine * sx + cosine * vx;
            dense.values[point + RIBBON_NORMAL_FACING + 1] = -sine * sy + cosine * vy;
            dense.values[point + RIBBON_NORMAL_FACING + 2] = -sine * sz + cosine * vz;
        }
        dense.values[point + RIBBON_UP] = -(ty * rz - tz * ry) * scale;
        dense.values[point + RIBBON_UP + 1] = -(tz * rx - tx * rz) * scale;
        dense.values[point + RIBBON_UP + 2] = -(tx * ry - ty * rx) * scale;
    }

    private void writeRibbonBasis(float[] target, int offset, int point) {
        for (int axis = 0; axis < 3; axis++) {
            target[offset + axis] = (float)dense.values[point + RIBBON_DIRECTION + axis];
            target[offset + 4 + axis] = (float)dense.values[point + RIBBON_RIGHT + axis];
            target[offset + 8 + axis] = (float)dense.values[point + RIBBON_NORMAL_FACING + axis];
        }
        target[offset + 3] = target[offset + 7] = target[offset + 11] = 0;
    }

    private static void attributes(double[] source, int point, float[] target, int offset) {
        for (int i = 0; i < 4; i++) target[offset + i] = (float)source[point + COLOR + i];
        target[offset + 4] = (float)source[point + AGE]; target[offset + 5] = (float)source[point + RANDOM];
        target[offset + 6] = (float)source[point + LIFE]; target[offset + 7] = (float)source[point + BORN];
        for (int i = 0; i < 4; i++) target[offset + 8 + i] = (float)source[point + CUSTOM + i];
    }

    private static double distance(double[] values, int a, int b) {
        double x = values[b] - values[a], y = values[b + 1] - values[a + 1], z = values[b + 2] - values[a + 2];
        return Math.sqrt(x * x + y * y + z * z);
    }

}
