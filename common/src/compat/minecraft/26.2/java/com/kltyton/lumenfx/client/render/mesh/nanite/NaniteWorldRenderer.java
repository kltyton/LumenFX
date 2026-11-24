package com.kltyton.lumenfx.client.render.mesh.nanite;

import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.client.VfxEngine;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.depth.HierarchicalSceneDepth;
import com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture;
import com.kltyton.lumenfx.client.render.light.shadow.PointShadowMatrices;
import com.kltyton.lumenfx.client.render.light.shadow.ShadowRasterViews;
import com.kltyton.lumenfx.client.render.light.shadow.directional.DirectionalShadowMatrices;
import com.kltyton.lumenfx.client.render.light.shadow.geometry.ParticleShadowGeometry;
import com.kltyton.lumenfx.client.render.light.shadow.geometry.PointShadowParticleView;
import com.kltyton.lumenfx.client.render.mesh.MeshDrawSelection;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuView;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuMeshCollector;
import com.kltyton.lumenfx.client.render.mesh.nanite.transcode.NanitePageTranscoder;
import com.kltyton.lumenfx.client.render.scene.material.MaterialPrimitiveFrame;
import com.kltyton.lumenfx.client.render.scene.atmosphere.AtmosphereMaterialLighting;
import com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionView;
import com.kltyton.lumenfx.client.render.texture.MaterialTextureBindings;
import com.kltyton.lumenfx.client.render.texture.ShadowDepthTarget;
import com.kltyton.lumenfx.client.resource.shader.PreparedNaniteMaterials;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraNaniteShaders;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.effect.render.mesh.nanite.NaniteMeshAsset;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;
import org.joml.Matrix4fc;

/** Runs Nanite visibility and material resolve in the owning world's opaque scene pass. */
public final class NaniteWorldRenderer implements AutoCloseable {
    private static final int TRIANGLES_PER_CLUSTER = 128;

    public record Surface(int materialSlot, MaterialDefinition material, @Nullable MaterialPrimitiveFrame primitive,
                          boolean firstPerson, boolean receivesDecals, boolean readsPartialDepth) {
        boolean programmableCoverage() { return material.naniteSource() != null && material.naniteSource().programmableCoverage(); }

        public boolean drawsInMainPass() { return MaterialPrimitiveFrame.drawsInMainPass(primitive); }

        public boolean writesCustomDepth() {
            return primitive != null && primitive.customDepthStencil().enabled() && material.writesCustomDepth();
        }
    }

    private record RasterSettings(float pixelsPerEdge, float hardwarePixelsPerEdge, boolean hardwareOnly,
                                  int maxVisibleClusters, int maxNodes, boolean programmable, boolean masked,
                                  boolean hzb, boolean twoPass, boolean frustum) {
        static RasterSettings capture(NiagaraPlatformEnvironment source) {
            return new RasterSettings(Float.intBitsToFloat(bits(source, "r.Nanite.MaxPixelsPerEdge")),
                    Float.intBitsToFloat(bits(source, "r.Nanite.MinPixelsPerEdgeHW")),
                    bits(source, "r.Nanite.ComputeRasterization") == 0,
                    bits(source, "r.Nanite.MaxVisibleClusters"), bits(source, "r.Nanite.MaxNodes"),
                    bits(source, "r.Nanite.ProgrammableRaster") != 0, bits(source, "r.Nanite.AllowMaskedMaterials") != 0,
                    bits(source, "r.Nanite.Culling.HZB") != 0, bits(source, "r.Nanite.Culling.TwoPass") != 0,
                    bits(source, "r.Nanite.Culling.Frustum") != 0);
        }

        private static int bits(NiagaraPlatformEnvironment source, String name) {
            return Objects.requireNonNull(source.console(name)).bits();
        }

        private static float number(NiagaraPlatformEnvironment source, String name) {
            return Float.intBitsToFloat(bits(source, name));
        }

        NaniteViewProjection project(NiagaraGpuView.Frame view, boolean zeroToOne, boolean topDown, float lodScale) {
            return NaniteViewProjection.capture(view, zeroToOne, topDown, pixelsPerEdge / lodScale,
                    hardwarePixelsPerEdge, hardwareOnly, frustum);
        }
    }

    private record Instance(NaniteInstanceCollector.Prepared source, List<Surface> surfaces) { }
    private record ShadowCaster(Instance instance, BitSet slots) {
        boolean accepts(Surface surface) { return slots.get(surface.materialSlot()); }
    }
    private record Deferred(NaniteViewRasterization.Mesh mesh, Instance instance,
                            NaniteHierarchyCulling.MainPass pass, NaniteViewRasterization.Limits limits) { }
    private record ViewDraw(NaniteViewRasterization.Mesh mesh, Instance instance, NaniteViewRasterization.Limits limits) { }
    private record ShadowDraw(NaniteViewRasterization.Mesh mesh, ShadowCaster caster, NaniteViewRasterization.Limits limits) { }
    private record DeferredShadow(ShadowDraw draw, NaniteHierarchyCulling.MainPass pass) { }

    @FunctionalInterface
    public interface Resolve {
        void draw(Surface surface, SurfaceBindings bindings);
    }

    public interface RasterResolve {
        void draw(Surface surface, RenderPassDescriptor target, SurfaceBindings bindings, GpuBufferSlice command, boolean topDown);
        boolean bindSampled(ComputeBindings.Builder bindings, ShaderBinding.SampledTexture binding);
    }

    @FunctionalInterface
    public interface SurfaceBindings {
        void bind(RenderPass pass);
    }

    public final class Frame {
        private final VfxEngine.FrameInput input;
        private final NiagaraGpuView.Frame view;
        private final NiagaraGpuView.@Nullable Frame previousView;
        private final ParticleMotionView.@Nullable Snapshot motion;
        private final List<Instance> instances;
        private final List<Surface> surfaces;
        private final List<ShadowCaster> shadows;
        private final long sequence;
        private final boolean mainView;
        private final @Nullable AtmosphereMaterialLighting atmosphere;

        private Frame(VfxEngine.FrameInput input, NiagaraGpuView.Frame view, NiagaraGpuView.@Nullable Frame previousView,
                      ParticleMotionView.@Nullable Snapshot motion, List<Instance> instances, long sequence, boolean mainView,
                      @Nullable AtmosphereMaterialLighting atmosphere) {
            this.input = input;
            this.view = view;
            this.previousView = previousView;
            this.motion = motion;
            this.atmosphere = atmosphere;
            this.instances = List.copyOf(instances);
            surfaces = this.instances.stream().flatMap(instance -> instance.surfaces().stream()).toList();
            var shadows = new ArrayList<ShadowCaster>();
            for (var instance : this.instances) {
                var caster = shadowCaster(instance);
                if (caster != null) shadows.add(caster);
            }
            this.shadows = List.copyOf(shadows);
            this.sequence = sequence;
            this.mainView = mainView;
        }

        public List<Surface> surfaces() { return surfaces; }
        public boolean motionValid() { return previousView != null && motion != null && motion.historyValid(); }

        public boolean hasShadows() {
            return RasterSettings.bits(NiagaraPlatforms.snapshot(), "r.Shadow.Nanite") != 0
                    && shadows.stream().anyMatch(caster -> !caster.slots().isEmpty());
        }

        public void replaceShadowFallbacks(List<NiagaraGpuMeshCollector.Task> tasks) {
            tasks.removeIf(task -> shadows.stream().anyMatch(caster -> matches(task, caster.instance().source())));
        }

        public void drawShadows(CommandEncoder encoder, PointShadowParticleView shadow, ShadowRasterViews matrices,
                                ShadowDepthTarget target, int first, int end, RasterResolve resolve) {
            if (hasShadows()) NaniteWorldRenderer.this.drawShadows(this, encoder, shadow, matrices, target, first, end, resolve);
        }

        public void draw(CommandEncoder encoder, GpuTextureView sceneDepth, Predicate<Surface> accepts,
                         boolean commitHistory, RasterResolve rasterResolve, Resolve resolve) {
            NaniteWorldRenderer.this.draw(this, encoder, sceneDepth, view, mainView, mainView && commitHistory,
                    true, false, accepts, rasterResolve, resolve);
        }

        public void drawDepth(CommandEncoder encoder, GpuTextureView depth, @Nullable Matrix4fc projection,
                              boolean ignoreOpaqueDepth, Predicate<Surface> accepts, RasterResolve rasterResolve, Resolve resolve) {
            var passView = projection == null ? view : new NiagaraGpuView.Frame(view.camera(), view.modelView(),
                    projection, view.unjitteredProjection(), projection, view.width(), view.height(),
                    view.gameTime(), view.realTime(), view.deltaTime(), view.realDeltaTime(), view.resolutionFraction(), view.temporalAA());
            NaniteWorldRenderer.this.draw(this, encoder, depth, passView, false, false, false,
                    ignoreOpaqueDepth, accepts, rasterResolve, resolve);
        }
    }

    private final ComputeRuntime runtime;
    private final NanitePhysicalPagePool pagePool;
    private final NaniteViewRasterization raster;
    private final NaniteVertexMaterials vertices;
    private final NaniteSoftwareMaterials softwareMaterials;
    private final HierarchicalSceneDepth historyDepth = new HierarchicalSceneDepth();
    private final NanitePrimedHzb primedHzb = new NanitePrimedHzb();
    private final NaniteShadowHistory shadowHistory = new NaniteShadowHistory();
    private final NaniteStreamingQuality streamingQuality = new NaniteStreamingQuality();
    private final NaniteStreamingBudget streamingBudget = new NaniteStreamingBudget();
    private final NaniteStreamingRequests authoredRequests = new NaniteStreamingRequests();
    private final Map<NaniteMeshAsset, NaniteMeshStreaming> streaming = new LinkedHashMap<>();
    private @Nullable ClientLevel streamingWorld;
    private NaniteHierarchyCulling.@Nullable OcclusionView history;
    private NiagaraGpuView.@Nullable Frame historyView;
    private long lastStreamingFrame = Long.MIN_VALUE;

    public NaniteWorldRenderer(ComputeRuntime runtime, PreparedNiagaraNaniteShaders shaders,
                               PreparedNaniteMaterials materials) {
        this.runtime = runtime;
        pagePool = new NanitePhysicalPagePool(Math.min(runtime.maxStorageBufferBytes(),
                RenderSystem.getDevice().getDeviceInfo().limits().maxMemoryAllocationSize()));
        raster = new NaniteViewRasterization(runtime, shaders);
        softwareMaterials = new NaniteSoftwareMaterials(runtime, materials.raster());
        try {
            vertices = new NaniteVertexMaterials(runtime, shaders, materials.vertex()::get);
        } catch (RuntimeException | Error failure) {
            raster.close();
            throw failure;
        }
    }

    public List<NaniteInstanceCollector.Prepared> select(CommandEncoder encoder, ClientLevel world, long sequence,
                                                        List<NaniteInstanceCollector.Prepared> sources) {
        RenderSystem.assertOnRenderThread();
        selectWorld(world);
        shadowHistory.advance(sequence);
        var platform = NiagaraPlatforms.snapshot();
        var settings = RasterSettings.capture(platform);
        var eligible = sources.stream().filter(source -> eligible(source, settings)).toList();
        if (eligible.isEmpty() && streaming.isEmpty() && !authoredRequests.hasPrefetches()) return List.of();
        long requestedBytes = (long) RasterSettings.bits(platform, "r.Nanite.Streaming.StreamingPoolSize") << 20;
        int physicalBudget = pagePool.configure(requestedBytes,
                RasterSettings.bits(platform, "r.Nanite.Streaming.NumInitialRootPages"),
                RasterSettings.bits(platform, "r.Nanite.Streaming.DynamicallyGrowAllocations") != 0,
                RasterSettings.bits(platform, "r.Nanite.Streaming.Debug.StreamingPoolResize") != 0);
        if (physicalBudget != pagePool.streamingSlots()) {
            raster.resetStreamingFeedback();
            streamingBudget.reset();
            for (var resource : streaming.values()) resource.resetStreaming(encoder);
            pagePool.resetStreaming(encoder, physicalBudget);
            if (physicalBudget != 0)
                runtime.clearBuffer(pagePool.buffer().slice(0, (long) physicalBudget * NanitePageTranscoder.STREAM_PAGE_BYTES), 0);
        }
        for (var source : eligible)
            streaming.computeIfAbsent(Objects.requireNonNull(source.asset().nanite()), this::streamingResource);
        authoredRequests.registerPrefetches(asset -> streaming.computeIfAbsent(asset, this::streamingResource));
        long requestedPages = raster.advanceStreamingFeedback(encoder, sequence,
                RasterSettings.bits(platform, "r.Nanite.Streaming.GPURequestsBufferMinSize"),
                RasterSettings.bits(platform, "r.Nanite.Streaming.GPURequestsBufferMaxSize"), streaming.values(), authoredRequests,
                RasterSettings.bits(platform, "r.Nanite.Streaming.Debug.GPURequests") != 0);
        streamingQuality.update(requestedPages, physicalBudget,
                RasterSettings.number(platform, "r.Nanite.Streaming.QualityScale.MinPoolPercentage"),
                RasterSettings.number(platform, "r.Nanite.Streaming.QualityScale.MaxPoolPercentage"),
                RasterSettings.number(platform, "r.Nanite.Streaming.QualityScale.MinQuality"));
        if (!streaming.isEmpty() && lastStreamingFrame != sequence) {
            int pending = RasterSettings.bits(platform, "r.Nanite.Streaming.MaxPendingPages");
            int installs = RasterSettings.bits(platform, "r.Nanite.Streaming.MaxPageInstallsPerFrame");
            streamingBudget.advance(encoder, streaming.values(), sequence, requestedPages >= 0, physicalBudget, pending,
                    Math.min(pending, installs));
            lastStreamingFrame = sequence;
        }
        return eligible.stream().filter(source -> streaming.get(source.asset().nanite()).current() != null).toList();
    }

    public void requestPages(ClientLevel world, int[] packedStream) {
        RenderSystem.assertOnRenderThread();
        selectWorld(world);
        if (RasterSettings.bits(NiagaraPlatforms.snapshot(), "r.Nanite.Streaming.Debug.ExplicitRequests") != 0)
            authoredRequests.enqueue(packedStream);
    }

    public void prefetch(ClientLevel world, NaniteMeshAsset asset, int framesUntilRender) {
        RenderSystem.assertOnRenderThread();
        selectWorld(world);
        if (RasterSettings.bits(NiagaraPlatforms.snapshot(), "r.Nanite.Streaming.Debug.Prefetch") != 0)
            authoredRequests.prefetch(asset, framesUntilRender);
    }

    private void selectWorld(ClientLevel world) {
        if (streamingWorld == world) return;
        releaseScene();
        streamingWorld = world;
    }

    private boolean eligible(NaniteInstanceCollector.Prepared source, RasterSettings settings) {
        var audit = NaniteMaterialAudit.audit(source, settings.masked(), true, false, false);
        if (audit.verdict() != NaniteMaterialAudit.Verdict.ELIGIBLE) return false;
        if (!settings.programmable()) return true;
        int count = Math.max(1, Math.max(source.asset().materials().size(), source.entry().materials().size()));
        for (int slot = 0; slot < count; slot++) {
            var material = material(source, slot);
            var metadata = material.naniteSource();
            if (metadata == null || metadata.pixelDepthOffset() == null && material.depthProjection()) return false;
            if (metadata.programmableCoverage() && (metadata.pixelDepthOffset() == null
                    || !settings.hardwareOnly() && !softwareMaterials.hasProgram(material))) return false;
        }
        return true;
    }

    private static MaterialDefinition material(NaniteInstanceCollector.Prepared source, int slot) {
        var selected = MeshDrawSelection.materialAtSlot(source.effect(), source.renderer(), source.asset(),
                source.entry(), slot, source.input());
        return source.materialInstances().apply(selected);
    }

    public static void selectFallbacks(List<NiagaraGpuMeshCollector.Task> tasks, List<NaniteInstanceCollector.Prepared> nativeInstances) {
        if (nativeInstances.isEmpty()) return;
        tasks.removeIf(task -> !task.view().shadowPass() && nativeInstances.stream().anyMatch(source -> matches(task, source)));
    }

    private static boolean matches(NiagaraGpuMeshCollector.Task task, NaniteInstanceCollector.Prepared source) {
        return source.renderer() == task.definition() && source.input() == task.input()
                && source.slot() == task.meshIndex() && source.asset() == task.geometry().mesh().asset();
    }

    private static @Nullable ShadowCaster shadowCaster(Instance instance) {
        var source = instance.source();
        if (!ParticleShadowGeometry.casts(source.renderer(), source.input())) return null;
        var configured = new BitSet(instance.surfaces().size());
        var casts = new BitSet(instance.surfaces().size());
        // The native scene proxy takes material slots and cast flags from LOD 0 even while another LOD is resident.
        var sections = source.asset().lods().getFirst().sections();
        if (sections.isEmpty()) return null;
        for (var section : sections) {
            var info = section.info();
            if (info == null) return null;
            int slot = info.materialIndex();
            if (slot < 0 || slot >= instance.surfaces().size()) return null;
            if (configured.get(slot) && casts.get(slot) != info.castShadow()) return null;
            configured.set(slot);
            casts.set(slot, info.castShadow());
        }
        for (var surface : instance.surfaces())
            if (!ParticleShadowGeometry.casts(surface.material())) casts.clear(surface.materialSlot());
        return new ShadowCaster(instance, casts);
    }

    public @Nullable Frame prepare(VfxEngine.FrameInput input, NiagaraGpuView.Frame view,
                                   NiagaraGpuView.@Nullable Frame previousView, ParticleMotionView.@Nullable Snapshot motion,
                                   List<NaniteInstanceCollector.Prepared> sources, long sequence, boolean mainView) {
        return prepare(input, view, previousView, motion, sources, sequence, mainView, null);
    }

    public @Nullable Frame prepare(VfxEngine.FrameInput input, NiagaraGpuView.Frame view,
                                   NiagaraGpuView.@Nullable Frame previousView, ParticleMotionView.@Nullable Snapshot motion,
                                   List<NaniteInstanceCollector.Prepared> sources, long sequence, boolean mainView,
                                   @Nullable AtmosphereMaterialLighting atmosphere) {
        RenderSystem.assertOnRenderThread();
        var instances = new ArrayList<Instance>();
        for (var source : sources) {
            int count = Math.max(1, Math.max(source.asset().materials().size(), source.entry().materials().size()));
            var surfaces = new ArrayList<Surface>(count);
            for (int slot = 0; slot < count; slot++) {
                var material = material(source, slot);
                surfaces.add(new Surface(slot, material, MaterialPrimitiveFrame.forMaterial(material, source.input(), source.previousPose(), source::bounds),
                        source.input().firstPerson(), source.input().receivesDecals(), source.readsPartialDepth()));
            }
            instances.add(new Instance(source, List.copyOf(surfaces)));
        }
        return instances.isEmpty() ? null : new Frame(input, view, previousView, motion, instances, sequence, mainView, atmosphere);
    }

    private void draw(Frame frame, CommandEncoder encoder, GpuTextureView depth, NiagaraGpuView.Frame passView,
                      boolean useHistory, boolean commitHistory, boolean requestPages, boolean ignoreOpaqueDepth, Predicate<Surface> accepts,
                      RasterResolve rasterResolve, Resolve resolve) {
        RenderSystem.assertOnRenderThread();
        var device = RenderSystem.getDevice();
        boolean zeroToOne = device.getDeviceInfo().isZZeroToOne();
        boolean topDown = !(device.backend instanceof GlDevice);
        var platform = NiagaraPlatforms.snapshot();
        var settings = RasterSettings.capture(platform);
        float currentScale = streamingQuality.scale();
        var current = settings.project(passView, zeroToOne, topDown, currentScale);
        var previous = frame.previousView == null ? current : settings.project(frame.previousView, zeroToOne, topDown, currentScale);
        var previousHzb = useHistory && settings.hzb() && settings.twoPass()
                && frame.previousView != null && historyView == frame.previousView
                && frame.previousView.width() == frame.view.width() && frame.previousView.height() == frame.view.height()
                ? history : null;
        var bindings = new NaniteMaterialFrameBindings(encoder, frame.input, passView, frame.motion, frame.atmosphere);
        int frameFlags = ((int) (WorldHdrCapture.INSTANCE.renderFrameSequence() - 1) & 63) << 1
                | (frame.motionValid() ? 1 : 0) | (settings.programmable() ? 0 : NaniteMaterialGeometry.FIXED_RASTER);
        NaniteViewRasterization.VertexMaterials vertexMaterials = (commands, mesh, bins, geometry, view, prior) ->
                vertices.apply(commands, mesh, bins, geometry, view, prior, frameFlags, bindings, rasterResolve);
        var draws = new ArrayList<ViewDraw>();
        var preparedMaterials = new IdentityHashMap<MaterialDefinition, Boolean>();
        for (var instance : frame.instances) {
            if (instance.surfaces().stream().noneMatch(accepts)) continue;
            var asset = Objects.requireNonNull(instance.source().asset().nanite());
            var resource = streaming.get(asset);
            var pages = resource.current();
            if (pages == null) continue;
            for (var surface : instance.surfaces())
                if (preparedMaterials.put(surface.material(), Boolean.TRUE) == null)
                    MaterialTextureBindings.prepare(encoder, surface.material());
            var mesh = new NaniteViewRasterization.Mesh(resource, pages, instance.source(),
                    rasterFlags(encoder, instance, accepts, settings.programmable()), primitiveBounds(instance, frame.motion));
            var limits = limits(asset, mesh.instances().capacity(), instance, settings);
            draws.add(new ViewDraw(mesh, instance, limits));
        }
        int prime = RasterSettings.bits(platform, "r.Nanite.PrimeHZB");
        if (useHistory && settings.hzb() && settings.twoPass() && (prime == 2 || prime != 0 && previousHzb == null))
            previousHzb = prime(encoder, frame, passView, depth, platform, settings, zeroToOne, topDown,
                    currentScale, frameFlags & ~1, draws, accepts, rasterResolve);
        var deferred = new ArrayList<Deferred>();
        for (var job : draws) {
            var mesh = job.mesh();
            var instance = job.instance();
            var limits = job.limits();
            var programmable = programmableRaster(encoder, instance, current, previous, bindings, frameFlags, accepts, rasterResolve);
            var main = raster.main(encoder, mesh, current, previous, depth, previousHzb, limits, frame.sequence, vertexMaterials,
                    programmable, ignoreOpaqueDepth, requestPages);
            shade(encoder, main.draw(), current, previous, instance, bindings, frameFlags, accepts, resolve);
            if (main.deferred() != null) deferred.add(new Deferred(mesh, instance, main.deferred(), limits));
        }
        if (!deferred.isEmpty()) {
            var currentHzb = raster.buildPostHzb(encoder, current, depth);
            for (var job : deferred) {
                var programmable = programmableRaster(encoder, job.instance(), current, previous, bindings, frameFlags, accepts, rasterResolve);
                var post = raster.post(encoder, job.mesh(), current, previous, depth, currentHzb,
                        job.pass(), job.limits(), frame.sequence, vertexMaterials, programmable);
                shade(encoder, post, current, previous, job.instance(), bindings, frameFlags, accepts, resolve);
            }
        }
        if (commitHistory) {
            history = current.occlusion(historyDepth.buildFurthest(encoder, depth));
            historyView = frame.view;
        }
    }

    private NaniteHierarchyCulling.OcclusionView prime(CommandEncoder encoder, Frame frame, NiagaraGpuView.Frame view,
            GpuTextureView sceneDepth, NiagaraPlatformEnvironment platform, RasterSettings settings,
            boolean zeroToOne, boolean topDown, float lodScale, int frameFlags, List<ViewDraw> draws,
            Predicate<Surface> accepts, RasterResolve resolve) {
        int sizeBias = 1 + Math.clamp(RasterSettings.bits(platform, "r.Nanite.PrimeHZB.RenderSizeBias"), 0, 29);
        int width = Math.max(1, view.width() >> sizeBias), height = Math.max(1, view.height() >> sizeBias);
        var coarseView = new NiagaraGpuView.Frame(view.camera(), view.modelView(), view.projection(), view.unjitteredProjection(),
                view.baseProjection(), width, height, view.gameTime(), view.realTime(), view.deltaTime(),
                view.realDeltaTime(), view.resolutionFraction(), view.temporalAA());
        float multiplier = RasterSettings.number(platform, "r.Nanite.PrimeHZB.MaxPixelsPerEdgeMultiplier");
        var projection = settings.project(coarseView, zeroToOne, topDown, lodScale / multiplier);
        var depth = primedHzb.begin(encoder, width, height);
        var bindings = new NaniteMaterialFrameBindings(encoder, frame.input, coarseView, frame.motion, frame.atmosphere);
        NaniteViewRasterization.VertexMaterials materials = (commands, mesh, bins, geometry, current, previous) ->
                vertices.apply(commands, mesh, bins, geometry, current, previous, frameFlags, bindings, resolve);
        boolean farField = RasterSettings.bits(platform, "r.Nanite.PrimeHZB.DrawOnlyRTFarField") != 0;
        for (var job : draws) {
            var instance = job.instance();
            if (farField && !instance.source().input().primitiveLighting().rayTracingFarField()) continue;
            var programmable = programmableRaster(encoder, instance, projection, projection, bindings, frameFlags, accepts, resolve);
            var covered = raster.primeDepth(encoder, job.mesh(), projection, depth, job.limits(), materials, programmable);
            primedHzb.accumulate(encoder, covered);
        }
        var hzb = primedHzb.finish(encoder, sceneDepth, coarseView.projection(), zeroToOne, topDown,
                RasterSettings.bits(platform, "r.Nanite.PrimeHZB.SampleNonNanite") != 0,
                RasterSettings.number(platform, "r.Nanite.PrimeHZB.SceneDepthBias"));
        return new NaniteHierarchyCulling.OcclusionView(hzb, projection.raster().clip(), width * 2, height * 2, topDown, false);
    }

    private void drawShadows(Frame frame, CommandEncoder encoder, PointShadowParticleView shadow,
                             ShadowRasterViews matrices, ShadowDepthTarget target, int first, int end, RasterResolve resolve) {
        RenderSystem.assertOnRenderThread();
        var device = RenderSystem.getDevice();
        boolean zeroToOne = device.getDeviceInfo().isZZeroToOne();
        var platform = NiagaraPlatforms.snapshot();
        var settings = RasterSettings.capture(platform);
        float lodBias = RasterSettings.number(platform, "r.Shadow.NaniteLODBias");
        float edge = settings.pixelsPerEdge() * (float) Math.pow(2.0, lodBias) / streamingQuality.scale();
        boolean requestPages = RasterSettings.bits(platform, "r.Shadow.NaniteUpdateStreaming") != 0;
        boolean useHistory = settings.hzb() && settings.twoPass() && RasterSettings.bits(platform, "r.Shadow.NaniteUseHZB") != 0;
        int lightingChannels = switch (matrices) {
            case PointShadowMatrices point -> point.lightingChannels();
            case DirectionalShadowMatrices directional -> directional.lightingChannels();
            default -> throw new IllegalArgumentException("Unsupported Nanite shadow projection " + matrices);
        };
        var passView = new NiagaraGpuView.Frame(frame.view.camera(), shadow.modelView(),
                shadow.projection(), shadow.projection(), shadow.projection(), target.size(), target.size(),
                frame.view.gameTime(), frame.view.realTime(), frame.view.deltaTime(), frame.view.realDeltaTime(), 1f);
        var bindings = new NaniteMaterialFrameBindings(encoder, frame.input, passView, frame.motion, frame.atmosphere, shadow);
        int frameFlags = ((int) (WorldHdrCapture.INSTANCE.renderFrameSequence() - 1) & 63) << 1
                | (settings.programmable() ? 0 : NaniteMaterialGeometry.FIXED_RASTER) | NaniteMaterialGeometry.SHADOW_DEPTH;
        NaniteViewRasterization.VertexMaterials vertexMaterials = (commands, mesh, bins, geometry, view, prior) ->
                vertices.apply(commands, mesh, bins, geometry, view, prior, frameFlags, bindings, resolve);
        var preparedMaterials = new IdentityHashMap<MaterialDefinition, Boolean>();
        var draws = new ArrayList<ShadowDraw>();
        for (var caster : frame.shadows) {
            if (caster.slots().isEmpty()) continue;
            var instance = caster.instance();
            if (lightingChannels >= 0 && (instance.source().input().primitiveLighting().channels() & lightingChannels) == 0) continue;
            var asset = Objects.requireNonNull(instance.source().asset().nanite());
            var resource = streaming.get(asset);
            var pages = Objects.requireNonNull(resource.current(), "Nanite shadow caster lost its prepared resident pages");
            for (var surface : instance.surfaces())
                if (caster.accepts(surface) && preparedMaterials.put(surface.material(), Boolean.TRUE) == null)
                    MaterialTextureBindings.prepare(encoder, surface.material());
            var mesh = new NaniteViewRasterization.Mesh(resource, pages, instance.source(),
                    rasterFlags(encoder, instance, caster::accepts, settings.programmable()), primitiveBounds(instance, frame.motion));
            draws.add(new ShadowDraw(mesh, caster, limits(asset, mesh.instances().capacity(), instance, settings)));
        }
        if (draws.isEmpty()) return;
        for (int face = first; face < end; face++) {
            var current = switch (matrices) {
                case PointShadowMatrices point -> NaniteViewProjection.localShadow(shadow, point, face, target.size(), zeroToOne,
                        edge, settings.hardwarePixelsPerEdge(), settings.hardwareOnly(), settings.frustum());
                case DirectionalShadowMatrices directional -> NaniteViewProjection.directionalShadow(directional, face, zeroToOne,
                        edge, settings.hardwarePixelsPerEdge(), settings.hardwareOnly(), settings.frustum());
                default -> throw new IllegalArgumentException("Unsupported Nanite shadow projection " + matrices);
            };
            var key = useHistory ? NaniteShadowHistory.key(matrices, face) : null;
            var raw = key == null ? null : shadowHistory.beginDepth(encoder, target.size());
            var previousHzb = key == null || frame.previousView == null ? null : shadowHistory.previous(key, current);
            var sceneDepth = target.face(face);
            var deferred = new ArrayList<DeferredShadow>();
            for (var job : draws) {
                var caster = job.caster();
                var programmable = programmableRaster(encoder, caster.instance(), current, current,
                        bindings, frameFlags, caster::accepts, resolve);
                var main = raster.shadow(encoder, job.mesh(), current, sceneDepth, previousHzb, job.limits(),
                        frame.sequence, vertexMaterials, programmable, requestPages);
                var visible = main.draw().visibility().depth();
                if (raw == null) NaniteShadowDepth.resolve(encoder, target, matrices, face, visible);
                else NaniteShadowDepth.accumulate(encoder, raw, visible);
                if (main.deferred() != null) deferred.add(new DeferredShadow(job, main.deferred()));
            }
            if (raw == null || key == null) continue;
            var rawDepth = raw.face(0);
            if (!deferred.isEmpty()) {
                var postHzb = shadowHistory.post(encoder, current, rawDepth);
                for (var pending : deferred) {
                    var job = pending.draw();
                    var caster = job.caster();
                    var programmable = programmableRaster(encoder, caster.instance(), current, current,
                            bindings, frameFlags, caster::accepts, resolve);
                    var post = raster.shadowPost(encoder, job.mesh(), current, sceneDepth, postHzb, pending.pass(),
                            job.limits(), frame.sequence, vertexMaterials, programmable, requestPages);
                    NaniteShadowDepth.accumulate(encoder, raw, post.visibility().depth());
                }
            }
            shadowHistory.capture(encoder, key, current, rawDepth);
            NaniteShadowDepth.resolve(encoder, target, matrices, face, rawDepth);
        }
    }

    private NaniteMeshStreaming streamingResource(NaniteMeshAsset asset) {
        return new NaniteMeshStreaming(asset, pagePool, Util.backgroundExecutor().forName("LumenFX Nanite pages"));
    }

    private static NaniteHierarchyCulling.PrimitiveBounds primitiveBounds(Instance instance,
            ParticleMotionView.@Nullable Snapshot motion) {
        float maximum = 0;
        boolean firstPerson = false;
        for (var surface : instance.surfaces()) {
            var vertex = surface.material().vertexMaterial();
            if (vertex == null || vertex.naniteShader() == null) continue;
            if (vertex.worldPositionOffset()) maximum = Math.max(maximum, vertex.maximumDisplacement());
            firstPerson |= surface.firstPerson() && (vertex.firstPersonInputs() & 128) != 0;
        }
        var current = firstPerson ? WorldHdrCapture.INSTANCE.firstPersonMaterialView() : null;
        var previous = firstPerson && motion != null ? motion.previous().firstPerson() : null;
        return new NaniteHierarchyCulling.PrimitiveBounds(maximum, current == null ? null : current.transform(),
                previous == null ? null : previous.transform());
    }

    private GpuBufferSlice rasterFlags(CommandEncoder encoder, Instance instance, Predicate<Surface> accepts, boolean programmable) {
        if (instance.surfaces().size() > NaniteRasterBins.MATERIAL_SLOTS)
            throw new IllegalArgumentException("Nanite material slot count exceeds raster bins");
        try (var flags = encoder.transientMemory().allocateGpuMapped(
                NaniteRasterBins.MATERIAL_SLOTS * Integer.BYTES, runtime.storageBufferAlignment(), ComputeUsage.storageBuffer(0))) {
            var words = flags.data().order(ByteOrder.nativeOrder());
            for (int i = 0; i < NaniteRasterBins.MATERIAL_SLOTS; i++) {
                var surface = i < instance.surfaces().size() ? instance.surfaces().get(i) : null;
                int entryFlags = surface == null || !accepts.test(surface) ? 2
                        : (surface.material().cull() ? 0 : 1) | (programmable && surface.programmableCoverage() ? 4 : 0);
                words.putInt(entryFlags);
            }
            return flags.slice();
        }
    }

    private NaniteViewRasterization.Limits limits(NaniteMeshAsset asset, int instances, Instance source, RasterSettings settings) {
        long texels = runtime.maxTexelBufferElements();
        long storage = runtime.maxStorageBufferBytes();
        long clusterLimit = Math.min(Math.min(settings.maxVisibleClusters(), (1 << 24) - 1), Math.min(texels, storage / 4) / TRIANGLES_PER_CLUSTER);
        int clusters = Math.toIntExact(Math.max(1, Math.min(clusterLimit, (long) instances * asset.header().clusters())));
        long vectorsPerTriangle = 27;
        for (var surface : source.surfaces()) {
            var vertex = surface.material().vertexMaterial();
            if (vertex != null) {
                long vectors = 5 + vertex.naniteInterpolatorWidths().size();
                vectorsPerTriangle = Math.max(vectorsPerTriangle, 3 * vectors);
            }
        }
        long triangleLimit = Math.min(texels, storage / 16) / vectorsPerTriangle;
        int triangles = Math.toIntExact(Math.max(1, Math.min((long) clusters * TRIANGLES_PER_CLUSTER, triangleLimit)));
        int deferred = Math.toIntExact(Math.max(1, Math.min(Math.min(settings.maxNodes(), storage / 16),
                (long) instances * (asset.hierarchy().remaining() / NaniteMeshAsset.HIERARCHY_NODE_BYTES) * 4)));
        return new NaniteViewRasterization.Limits(clusters, deferred, triangles);
    }

    private NaniteViewRasterization.@Nullable ProgrammableRaster programmableRaster(
            CommandEncoder encoder, Instance instance, NaniteViewProjection current, NaniteViewProjection previous,
            NaniteMaterialFrameBindings bindings, int frameFlags, Predicate<Surface> accepts, RasterResolve resolve) {
        if ((frameFlags & NaniteMaterialGeometry.FIXED_RASTER) != 0
                || instance.surfaces().stream().noneMatch(surface -> surface.programmableCoverage() && accepts.test(surface))) return null;
        return new NaniteViewRasterization.ProgrammableRaster() {
            @Override public void draw(NaniteVisibilityTarget.HardwarePass hardware, NaniteShadingGeometry.Output shading,
                                       Map<Integer, NaniteViewRasterization.Interpolants> interpolants) {
                for (int slot = 0; slot < instance.surfaces().size(); slot++) {
                    var surface = instance.surfaces().get(slot);
                    if (!surface.programmableCoverage() || !accepts.test(surface)) continue;
                    var geometry = NaniteMaterialGeometry.write(encoder, shading, instance.source().scene(), current, previous, slot, frameFlags);
                    var attributes = Objects.requireNonNull(interpolants.get(slot));
                    var layout = NaniteMaterialGeometry.writeInterpolants(encoder, attributes);
                    var material = bindings.prepareSurface(surface.material(), instance.source());
                    resolve.draw(surface, hardware.descriptor(), pass -> {
                        hardware.bind(pass);
                        NaniteMaterialGeometry.bindGeometry(pass, shading, instance.source().scene(), attributes, geometry, layout);
                        material.bind(pass);
                    }, hardware.geometry().bins().command(slot, true), current.raster().textureOriginTopDown());
                }
            }

            @Override public void dispatch(NaniteTriangleRaster.Software output, NaniteRasterBins.Output bins,
                                           NaniteShadingGeometry.Output shading,
                                           Map<Integer, NaniteViewRasterization.Interpolants> interpolants,
                                           int width, int rows, boolean identity) {
                for (int slot = 0; slot < instance.surfaces().size(); slot++) {
                    var surface = instance.surfaces().get(slot);
                    if (!surface.programmableCoverage() || !accepts.test(surface)) continue;
                    softwareMaterials.dispatch(encoder, surface, instance.source(), output, bins, shading,
                            Objects.requireNonNull(interpolants.get(slot)), current, previous, slot, frameFlags,
                            bindings, resolve, width, rows, identity);
                }
            }
        };
    }

    private static void shade(CommandEncoder encoder, NaniteViewRasterization.Draw draw, NaniteViewProjection current,
                              NaniteViewProjection previous, Instance instance, NaniteMaterialFrameBindings bindings,
                              int frameFlags, Predicate<Surface> accepts, Resolve resolve) {
        for (int slot = 0; slot < instance.surfaces().size(); slot++) {
            int index = slot;
            var surface = instance.surfaces().get(slot);
            if (!accepts.test(surface)) continue;
            var geometry = NaniteMaterialGeometry.write(encoder, draw, current, previous, slot, frameFlags);
            var interpolants = NaniteMaterialGeometry.writeInterpolants(encoder, draw, slot);
            var surfaceBindings = bindings.prepareSurface(surface.material(), instance.source());
            resolve.draw(surface, pass -> {
                NaniteMaterialGeometry.bind(pass, draw, geometry, index, interpolants);
                surfaceBindings.bind(pass);
            });
        }
    }

    private void releaseScene() {
        history = null;
        historyView = null;
        lastStreamingFrame = Long.MIN_VALUE;
        raster.resetStreamingFeedback();
        streamingQuality.reset();
        streamingBudget.reset();
        authoredRequests.clear();
        try { historyDepth.close(); }
        finally {
            try { primedHzb.close(); }
            finally {
                try { shadowHistory.close(); }
                finally {
                    try { streaming.values().forEach(NaniteMeshStreaming::close); }
                    finally {
                        streaming.clear();
                        pagePool.close();
                    }
                }
            }
        }
    }

    @Override public void close() {
        try { raster.close(); }
        finally {
            try { vertices.close(); }
            finally {
                try { softwareMaterials.close(); }
                finally { releaseScene(); }
            }
        }
    }
}
