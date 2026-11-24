/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.render;

import com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture;
import com.lowdragmc.photon.Photon;

import com.lowdragmc.photon.client.light.DynamicLightRenderer;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The draw engine behind {@link PhotonFeatureRenderer}. {@link Frame#prepare()} bakes, sorts, uploads and resolves
 * everything before any pass opens; {@link Frame#execute(PhotonStage)} only records draws.
 * <p>
 * Draws land in Photon's HDR target: {@link PhotonDrawTarget} (seeded from the output and composited back) or
 * {@link PhotonFXLayer} (starts empty, merged later by {@link FXCompositeMode#LATE} or a shader pack).
 */
public final class PhotonWorldRenderState {

    // ---- the job model the emitters bake into ----------------------------------------------------

    /** One sortable draw in Photon's slot: a baked-mesh {@link Job} or an {@link InstancedJob}. */
    public sealed interface DrawJob permits Job, InstancedJob {
        PhotonStage stage();

        int orderInLayer();

        float distanceSq();
    }

    /**
     * A CPU-baked mesh, consumed by {@link Frame#prepare()}. {@code backFacesFirst}: draw the back faces, then the
     * front ones, so a closed transparent mesh sorts itself ({@code MaterialSetting.isBackFacesFirst}); such a job
     * never merges into a shared run, where overlapping shells would interleave. {@code records}: the per-particle
     * records it reads, which also keep it out of a run.
     */
    public record Job(RenderType renderType, MeshData mesh, ByteBufferBuilder buffer,
                      PhotonStage stage, int orderInLayer, float distanceSq,
                      boolean backFacesFirst,
                      @Nullable CpuRecords records) implements DrawJob {
    }

    /**
     * Per-particle records a CPU job carries ({@code CpuParticleRecords}), and the {@code PHOTON_CPU_DATA} pipeline
     * that reads them in place of the material's plain one, indexed from the job's first vertex.
     */
    public record CpuRecords(@Nullable RenderPipeline pipeline, @Nullable GpuBuffer data, @Nullable GpuBuffer custom,
                             int vertsPerParticle) {
        public CpuRecords with(RenderPipeline pipeline) {
            return new CpuRecords(pipeline, data, custom, vertsPerParticle);
        }
    }

    public record InstancedJob(PhotonRenderTypes.PhotonDrawInfo.Programs programs,
                               InstancedGeometry geometry,
                               DrawBindings bindings,
                               Vector3f positionOffset,
                               PhotonStage stage, int orderInLayer, float distanceSq,
                               PhotonPipelines.InstancedGeometryKey geometryKey,
                               boolean backFacesFirst) implements DrawJob {
    }

    /**
     * {@code indices} null = the shared sequential-quad indices. {@code reversedIndices}: the same triangles wound
     * the other way, for a back-faces-first draw over own indices (null draws such a mesh front faces only).
     */
    public record InstancedGeometry(GpuBuffer vertices, int indexCount, @Nullable GpuBuffer indices,
                                    GpuBufferSlice instances, int instanceCount,
                                    @Nullable GpuBuffer points,
                                    @Nullable GpuBuffer data, @Nullable GpuBuffer customData,
                                    @Nullable GpuBuffer vat, @Nullable GpuBufferSlice vatInfo,
                                    @Nullable GpuBuffer reversedIndices) {

        public InstancedGeometry(GpuBuffer vertices, int indexCount, @Nullable GpuBuffer indices,
                                 GpuBufferSlice instances, int instanceCount,
                                 @Nullable GpuBuffer points,
                                 @Nullable GpuBuffer data, @Nullable GpuBuffer customData,
                                 @Nullable GpuBuffer vat, @Nullable GpuBufferSlice vatInfo) {
            this(vertices, indexCount, indices, instances, instanceCount, points, data, customData, vat, vatInfo,
                    null);
        }

        public InstancedGeometry(GpuBuffer vertices, int indexCount, @Nullable GpuBuffer indices,
                                 GpuBufferSlice instances, int instanceCount,
                                 @Nullable GpuBuffer points,
                                 @Nullable GpuBuffer data, @Nullable GpuBuffer customData) {
            this(vertices, indexCount, indices, instances, instanceCount, points, data, customData, null, null);
        }
    }

    /** {@link PhotonRenderTypes.PhotonDrawInfo.Bindings} with the uniform blocks resolved to slices. */
    public record DrawBindings(Map<String, Identifier> textures,
                               GpuBufferSlice materialSlice,
                               @Nullable GpuBufferSlice customSlice,
                               List<String> sceneSamplers,
                               @Nullable PhotonRenderTypes.GraphSource graph,
                               boolean legacyDepth,
                               @Nullable PhotonRenderTypes.MaterialBinder binder) {
        public DrawBindings(Map<String, Identifier> textures, GpuBufferSlice materialSlice,
                            @Nullable GpuBufferSlice customSlice, List<String> sceneSamplers,
                            @Nullable PhotonRenderTypes.GraphSource graph, boolean legacyDepth) {
            this(textures, materialSlice, customSlice, sceneSamplers, graph, legacyDepth, null);
        }
    }

    // ---- shared instanced base meshes -------------------------------------------------------------

    @Nullable
    private static GpuBuffer tileQuad;
    @Nullable
    private static GpuBuffer beamQuad;
    @Nullable
    private static GpuBuffer segmentQuad;
    @Nullable
    private static GpuBuffer araQuad;

    private static GpuBuffer corners(String label, int components, float... values) {
        var bytes = MemoryUtil.memAlloc(values.length * Float.BYTES);
        try {
            for (var v : values) {
                bytes.putFloat(v);
            }
            bytes.flip();
            return RenderSystem.getDevice().createBuffer(() -> label, GpuBuffer.USAGE_VERTEX, bytes);
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }

    /** Billboard corners ±1. */
    public static GpuBuffer tileQuad() {
        if (tileQuad == null) {
            tileQuad = corners("Photon tile quad", 3, 1, -1, 0, 1, 1, 0, -1, 1, 0, -1, -1, 0);
        }
        return tileQuad;
    }

    /** Beam corners: x = start/end, y = side, in the CPU emission order. */
    public static GpuBuffer beamQuad() {
        if (beamQuad == null) {
            beamQuad = corners("Photon beam quad", 2, 0, -1, 0, 1, 1, 1, 1, -1);
        }
        return beamQuad;
    }

    /** Trail segment corners: x = curr/next, y = up/down, wound like 1.21. */
    public static GpuBuffer segmentQuad() {
        if (segmentQuad == null) {
            segmentQuad = corners("Photon segment quad", 2, 0, 1, 0, -1, 1, -1, 1, 1);
        }
        return segmentQuad;
    }

    /** Ara ribbon corners; 1.21 winds these opposite to the trail quad. */
    public static GpuBuffer araQuad() {
        if (araQuad == null) {
            araQuad = corners("Photon ara quad", 2, 1, -1, 0, -1, 0, 1, 1, 1);
        }
        return araQuad;
    }

    // ---- frame bookkeeping ------------------------------------------------------------------------

    private static final Comparator<DrawJob> DRAW_ORDER =
            ParticleDrawOrder.backToFront(DrawJob::orderInLayer, DrawJob::distanceSq);

    private static final Vector4f NO_MODULATION = new Vector4f(1, 1, 1, 1);

    /** Resources whose queued draws must complete before they can be closed. */
    private static final List<AutoCloseable> CLOSE_AT_FRAME_END = new ArrayList<>();

    public static void closeAtFrameEnd(AutoCloseable resource) {
        CLOSE_AT_FRAME_END.add(resource);
    }

    private PhotonWorldRenderState() {
    }

    public static void endFrame() {
        StackedDistortion.endFrame();
        PhotonVatUniforms.endFrame();
        DynamicLightRenderer.endFrame();
        PhotonTexelPool.endFrame();
        PhotonFXLayer.endFrame();
        // a frame that never reached AfterLevel must not composite its layer twice
        PhotonDeferredLayer.discardPending();
        OpaqueDepthCapture.endFrame();
        PhotonDrawTarget.endFrame();
        if (CLOSE_AT_FRAME_END.isEmpty()) return;
        var retired = List.copyOf(CLOSE_AT_FRAME_END);
        CLOSE_AT_FRAME_END.clear();
        RenderSystem.queueFencedTask(() -> {
            for (var resource : retired) {
                try {
                    resource.close();
                } catch (Exception error) {
                    Photon.LOGGER.error("Could not release a particle draw resource", error);
                }
            }
        });
    }

    // ---- resolved draws ---------------------------------------------------------------------------

    private record BoundTexture(String sampler, GpuTextureView view, GpuSampler samplerState) {
    }

    /**
     * A fully resolved draw call. {@code instances} null = CPU geometry on binding 0 alone. {@code backFacesFirst}
     * draws twice, through {@code reversedIndices} (or the reversed sequential indices) first. {@code cpuData}: the
     * records block of a CPU-records draw.
     */
    private record Draw(RenderPipeline pipeline, GpuBufferSlice transforms,
                        @Nullable GpuBufferSlice material, @Nullable GpuBufferSlice custom,
                        List<BoundTexture> textures, @Nullable PhotonRenderTypes.GraphSource graph,
                        @Nullable PhotonRenderTypes.MaterialBinder binder,
                        List<String> sceneSamplers, boolean legacyDepth,
                        GpuBufferSlice vertices, @Nullable GpuBufferSlice instances,
                        @Nullable GpuBuffer points, @Nullable GpuBuffer data, @Nullable GpuBuffer customData,
                        @Nullable GpuBuffer vat, @Nullable GpuBufferSlice vatInfo,
                        @Nullable GpuBufferSlice cpuData,
                        @Nullable GpuBuffer indices, IndexType indexType, PrimitiveTopology sequentialTopology,
                        int indexCount, int instanceCount, int baseVertex,
                        boolean backFacesFirst, @Nullable GpuBuffer reversedIndices) {

        int vertexBindings() {
            return instances == null ? 1 : 2;
        }

        /** Drawn into the stacked-distortion sum rather than the frame. */
        boolean stacked() {
            return StackedDistortion.isStacked(pipeline);
        }

        /** A pass holds draws with the same vertex-binding count and the same colour target. */
        boolean sharesPass(Draw other) {
            return vertexBindings() == other.vertexBindings() && stacked() == other.stacked();
        }
    }

    private static final class Stage {
        final List<Draw> draws = new ArrayList<>();
        final EnumMap<PrimitiveTopology, Integer> sequentialIndexCounts = new EnumMap<>(PrimitiveTopology.class);
        /** The same, wound the other way, for back-faces-first draws. */
        final EnumMap<PrimitiveTopology, Integer> reversedIndexCounts = new EnumMap<>(PrimitiveTopology.class);
        boolean needsSceneColor;
        boolean needsSceneDepth;
        boolean needsLegacyDepth;

        boolean isEmpty() {
            return draws.isEmpty();
        }
    }

    /** Particle draw work for one extracted world view. */
    public static final class Frame {
        private final List<PhotonBakeTask> tasks;
        private final Vec3 camera;
        private final Matrix4f viewRotation;
        private final Matrix4f projection;
        private final Frustum frustum;
        private final float partialTick;
        private final EnumMap<PhotonStage, Stage> stages = new EnumMap<>(PhotonStage.class);
        private boolean prepared;

        Frame(List<PhotonBakeTask> tasks, CameraRenderState camera, float partialTick) {
            this.tasks = tasks;
            this.camera = camera.pos;
            this.viewRotation = new Matrix4f(camera.viewRotationMatrix);
            this.projection = new Matrix4f(camera.projectionMatrix);
            this.frustum = new Frustum(camera.cullFrustum);
            this.partialTick = partialTick;
        }

        public void prepare() {
            if (prepared) return;
            var output = PhotonRenderOutput.color();
            if (output == null) throw new IllegalStateException("Particle view has no color target");
            var baked = new ArrayList<DrawJob>();
            try {
                for (var task : tasks) task.bake(baked);
                var queued = new EnumMap<PhotonStage, List<DrawJob>>(PhotonStage.class);
                for (var job : baked) {
                    queued.computeIfAbsent(job.stage(), stage -> new ArrayList<>()).add(job);
                }
                queued.forEach((stage, jobs) -> stages.put(stage, resolve(jobs)));
                if (stages.containsKey(PhotonStage.DEFERRED)
                        && PhotonRenderOutput.opaqueDepth() == null
                        && !WorldHdrCapture.INSTANCE.externalSceneOwner()) {
                    OpaqueDepthCapture.demand();
                }
                prepared = true;
            } finally {
                PremultipliedBlendPlan.setAccumulating(false);
                for (var job : baked) {
                    if (job instanceof Job mesh) closeMesh(mesh);
                }
            }
        }

        public boolean hasStage(PhotonStage phase) {
            prepare();
            var stage = stages.get(phase);
            return stage != null && !stage.isEmpty();
        }

        public void execute(PhotonStage phase) {
            prepare();
            var stage = stages.remove(phase);
            if (stage != null) drain(stage, false, this);
            if (phase == PhotonStage.AFTER_TRANSLUCENT_PARTICLES) {
                var deferred = stages.remove(PhotonStage.DEFERRED);
                if (deferred != null) drain(deferred, true, this);
            }
        }
    }

    // ---- prepare: resolve jobs into draws -------------------------------------------------------

    /** Sorts the jobs, merges adjacent CPU jobs into runs and uploads their vertices in one transient block. */
    private static Stage resolve(List<DrawJob> jobs) {
        var stage = new Stage();
        if (jobs.isEmpty()) {
            return stage;
        }
        jobs.sort(DRAW_ORDER);

        var blocks = new ArrayList<ByteBuffer>();
        for (var drawJob : jobs) {
            if (drawJob instanceof Job job) {
                blocks.add(job.mesh().vertexBuffer());
            }
        }
        var cpuVertices = blocks.isEmpty() ? null : PhotonUploads.vertices(blocks);

        var textureManager = Minecraft.getInstance().getTextureManager();
        var baseVertex = 0;
        for (int i = 0; i < jobs.size(); i++) {
            if (jobs.get(i) instanceof InstancedJob instanced) {
                resolveInstanced(stage, instanced, textureManager);
                continue;
            }
            var job = (Job) jobs.get(i);
            var info = PhotonRenderTypes.drawInfo(job.renderType());
            var mode = job.mesh().drawState().primitiveTopology();
            var vertexCount = job.mesh().drawState().vertexCount();
            var indexCount = job.mesh().drawState().indexCount();
            // cross-emitter batching; strips and fans cannot be joined, nor jobs with records or two passes
            while (!job.backFacesFirst() && job.records() == null
                    && i + 1 < jobs.size()
                    && jobs.get(i + 1) instanceof Job next
                    && !next.backFacesFirst() && next.records() == null
                    && next.renderType() == job.renderType()
                    && !mode.connectedPrimitives) {
                i++;
                vertexCount += next.mesh().drawState().vertexCount();
                indexCount += next.mesh().drawState().indexCount();
            }
            if (info != null && cpuVertices != null) {
                resolveRun(stage, job, info, cpuVertices, baseVertex, indexCount, mode, textureManager);
            }
            baseVertex += vertexCount;
        }
        return stage;
    }

    private static void closeMesh(Job job) {
        try {
            job.mesh().close();
        } finally {
            job.buffer().close();
        }
    }

    private static void needSequential(Stage stage, PrimitiveTopology topology, int indexCount,
                                       boolean reversed) {
        stage.sequentialIndexCounts.merge(topology, indexCount, Math::max);
        if (reversed) {
            stage.reversedIndexCounts.merge(topology, indexCount, Math::max);
        }
    }

    /** The {@code PhotonCpuData} block of a records draw starting at {@code baseVertex}; MIRRORED IN particle.glsl. */
    private static GpuBufferSlice cpuDataBlock(int vertsPerParticle, int baseVertex) {
        var bytes = MemoryUtil.memCalloc(16);
        try {
            bytes.putInt(0, vertsPerParticle).putInt(4, baseVertex);
            return PhotonUploads.block(bytes);
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }

    private static List<BoundTexture> resolveTextures(Map<String, Identifier> textures,
                                                      TextureManager manager) {
        var bound = new ArrayList<BoundTexture>(textures.size());
        for (var entry : textures.entrySet()) {
            var texture = manager.getTexture(entry.getValue());
            bound.add(new BoundTexture(entry.getKey(), texture.getTextureView(), texture.getSampler()));
        }
        return bound;
    }

    private static void noteSceneSamplers(Stage stage, List<String> sceneSamplers,
                                          boolean legacyDepth) {
        for (var name : sceneSamplers) {
            if (name.contains("Depth")) {
                stage.needsSceneDepth = true;
                stage.needsLegacyDepth |= legacyDepth;
            } else {
                stage.needsSceneColor = true;
            }
        }
    }

    /** Custom-shader materials own no block but still declare it, so they bind the defaults. */
    static GpuBufferSlice materialSlice(RenderType renderType) {
        var slice = PhotonMaterialUniforms.sliceFor(renderType);
        return slice != null ? slice : PhotonMaterialUniforms.sliceFor(PhotonMaterialUniforms.Values.DEFAULT);
    }

    private static void resolveRun(Stage stage, Job job, PhotonRenderTypes.PhotonDrawInfo info,
                                   GpuBufferSlice vertices, int baseVertex, int indexCount, PrimitiveTopology mode,
                                   TextureManager manager) {
        var records = job.records();
        var pipeline = records != null && records.pipeline() != null ? records.pipeline() : info.programs().main();
        var graph = info.bindings().graph();
        if (graph != null) {
            graph.prepareUniforms();
        }
        var binder = info.bindings().binder();
        if (binder != null) {
            binder.prepare();
        }
        var custom = info.bindings().customUniforms();
        var draw = new Draw(pipeline,
                RenderSystem.getDynamicUniforms().writeTransform(PhotonRenderOutput.modelView(),
                        new Vector4f(NO_MODULATION), new Vector3f(), new Matrix4f()),
                materialSlice(job.renderType()),
                custom == null ? null : custom.slice(),
                resolveTextures(info.bindings().textures(), manager), graph, binder,
                info.bindings().sceneSamplers(), info.bindings().legacyDepth(),
                vertices, null, null,
                records == null ? null : records.data(), records == null ? null : records.custom(),
                null, null,
                records == null ? null : cpuDataBlock(records.vertsPerParticle(), baseVertex),
                null, IndexType.INT, mode, indexCount, 1, baseVertex,
                job.backFacesFirst(), null);
        needSequential(stage, mode, indexCount, job.backFacesFirst());
        noteSceneSamplers(stage, info.bindings().sceneSamplers(), info.bindings().legacyDepth());
        stage.draws.add(draw);
    }

    private static void resolveInstanced(Stage stage, InstancedJob job,
                                         TextureManager manager) {
        var pipeline = job.programs().main();
        var geometry = job.geometry();
        var graph = job.bindings().graph();
        if (graph != null) {
            graph.prepareUniforms();
        }
        var binder = job.bindings().binder();
        if (binder != null) {
            binder.prepare();
        }
        // ModelOffset: eye → render-origin delta for eye-relative trail/beam data (zero in-world)
        var transforms = RenderSystem.getDynamicUniforms().writeTransform(PhotonRenderOutput.modelView(),
                new Vector4f(NO_MODULATION), new Vector3f(job.positionOffset()), new Matrix4f());
        var ownIndices = geometry.indices();
        var draw = new Draw(pipeline, transforms, job.bindings().materialSlice(), job.bindings().customSlice(),
                resolveTextures(job.bindings().textures(), manager), graph, binder, job.bindings().sceneSamplers(),
                job.bindings().legacyDepth(),
                geometry.vertices().slice(), geometry.instances(),
                geometry.points(), geometry.data(), geometry.customData(), geometry.vat(), geometry.vatInfo(),
                null, ownIndices, IndexType.INT, PrimitiveTopology.QUADS,
                geometry.indexCount(), geometry.instanceCount(), 0,
                job.backFacesFirst(), geometry.reversedIndices());
        if (ownIndices == null) {
            needSequential(stage, PrimitiveTopology.QUADS, geometry.indexCount(), job.backFacesFirst());
        }
        noteSceneSamplers(stage, job.bindings().sceneSamplers(), job.bindings().legacyDepth());
        stage.draws.add(draw);
    }

    // ---- execute: draw one stage ------------------------------------------------------------------

    private static void drain(Stage stage, boolean deferred, Frame frame) {
        if (stage.isEmpty()) return;
        var output = Objects.requireNonNull(PhotonRenderOutput.color(), "Particle view has no color target");
        var depth = Objects.requireNonNull(PhotonRenderOutput.depth(), "Particle view has no depth target");
        var scissor = PhotonRenderOutput.scissor(output.getWidth(0), output.getHeight(0));
        if (PhotonRenderOutput.isEmpty(scissor)) return;

        var worldProjection = new Matrix4f(frame.projection);
        WorldHdrCapture.INSTANCE.copyProjectionIfAvailable(worldProjection);
        PhotonEngineUniforms.update(worldProjection, frame.viewRotation, frame.camera.toVector3f(),
                output.getWidth(0), output.getHeight(0));
        if (stage.draws.stream().anyMatch(draw -> PhotonPipelines.isLit(draw.pipeline()))) {
            var level = Objects.requireNonNull(Minecraft.getInstance().level, "Lit particle view has no client level");
            DynamicLightRenderer.prepare(level, frame.camera, PhotonRenderOutput.modelView(), worldProjection,
                    frame.frustum, frame.partialTick, output.getWidth(0), output.getHeight(0));
        }
        var opaqueDepth = deferred
                ? (PhotonRenderOutput.opaqueDepth() != null
                        ? PhotonRenderOutput.opaqueDepth()
                        : OpaqueDepthCapture.view(depth))
                : null;
        if (opaqueDepth != null) depth = opaqueDepth;
        var sceneColor = stage.needsSceneColor
                ? Objects.requireNonNull(PhotonSceneCapture.captureColor(output), "Particle scene color is unavailable") : null;
        var sceneDepth = stage.needsSceneDepth || stage.needsLegacyDepth
                ? Objects.requireNonNull(PhotonSceneCapture.captureDepth(depth), "Particle scene depth is unavailable") : null;
        var legacyDepth = stage.needsLegacyDepth ? PhotonSceneCapture.legacyDepth(sceneDepth) : null;

        if (deferred) {
            var layer = PhotonFXLayer.acquire(output.getWidth(0), output.getHeight(0));
            layer.beginFrame();
            recordDraws(stage, layer.view(), depth, scissor, sceneColor, sceneDepth, legacyDepth, StackedDistortion.LAYER);
            PhotonDeferredLayer.park(layer);
        } else {
            var layer = PhotonDrawTarget.acquire(output.getWidth(0), output.getHeight(0));
            layer.copyFrom(output, scissor);
            recordDraws(stage, layer.view(), depth, scissor, sceneColor, sceneDepth, legacyDepth, StackedDistortion.INLINE);
            StackedDistortion.INLINE.bend(layer.view(), depth, scissor);
            layer.compositeTo(output, scissor);
        }
    }

    /** Indices resolved before any pass opens: getting them may grow (and upload) a buffer. */
    private record Indices(Map<PrimitiveTopology, GpuBuffer> sequential, Map<PrimitiveTopology, IndexType> types,
                           Map<PrimitiveTopology, GpuBuffer> reversed) {
        static Indices of(Stage stage) {
            var sequential = new EnumMap<PrimitiveTopology, GpuBuffer>(PrimitiveTopology.class);
            var types = new EnumMap<PrimitiveTopology, IndexType>(PrimitiveTopology.class);
            stage.sequentialIndexCounts.forEach((topology, count) -> {
                var autoIndices = RenderSystem.getSequentialBuffer(topology);
                sequential.put(topology, autoIndices.getBuffer(count));
                types.put(topology, autoIndices.type());
            });
            var reversed = new EnumMap<PrimitiveTopology, GpuBuffer>(PrimitiveTopology.class);
            stage.reversedIndexCounts.forEach((topology, count) -> {
                var buffer = PhotonReversedIndices.sequential(topology, count);
                if (buffer != null) reversed.put(topology, buffer);
            });
            return new Indices(sequential, types, reversed);
        }
    }

    /**
     * One pass per run of draws sharing the vertex-binding count (a pass cannot drop a binding) and the colour
     * target: stacked distortions draw into the sum, against the frame's depth.
     */
    private static void recordDraws(Stage stage, GpuTextureView colorTexture, GpuTextureView depthTexture,
                                    ScissorState scissor, @Nullable GpuTextureView sceneColor,
                                    @Nullable GpuTextureView sceneDepth, @Nullable GpuTextureView legacyDepth,
                                    StackedDistortion.Accumulator distortions) {
        var indices = Indices.of(stage);
        var lightmap = Minecraft.getInstance().gameRenderer.lightmap();
        var lightmapSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        var engine = PhotonEngineUniforms.currentSlice();
        var legacyEngine = PhotonEngineUniforms.legacySlice();
        var captureSampler = PhotonSceneCapture.sampler();

        var index = 0;
        while (index < stage.draws.size()) {
            var first = stage.draws.get(index);
            // the sum is made and cleared before its first pass opens
            var target = first.stacked()
                    ? distortions.target(colorTexture.getWidth(0), colorTexture.getHeight(0)) : colorTexture;
            if (target == null) {
                while (index < stage.draws.size() && stage.draws.get(index).sharesPass(first)) index++;
                continue;
            }
            try (RenderPass pass = ParticleRasterPass.open(RenderSystem.getDevice().createCommandEncoder(),
                    first.stacked() ? "Photon fx (stacked distortion)" : "Photon fx", target, depthTexture, scissor)) {
                RenderSystem.bindDefaultUniforms(pass);
                while (index < stage.draws.size() && stage.draws.get(index).sharesPass(first)) {
                    var draw = stage.draws.get(index++);
                    pass.setPipeline(draw.pipeline());
                    pass.setUniform("DynamicTransforms", draw.transforms());
                    if (draw.material() != null) {
                        pass.setUniform(PhotonMaterialUniforms.UBO_NAME, draw.material());
                    }
                    // legacy shaders get the inverse projection matching their legacy depth
                    var drawEngine = draw.legacyDepth() ? legacyEngine : engine;
                    if (drawEngine != null) {
                        pass.setUniform(PhotonEngineUniforms.UBO_NAME, drawEngine);
                    }
                    if (draw.custom() != null) {
                        pass.setUniform(PhotonCustomUniforms.UBO_NAME, draw.custom());
                    }
                    bindSideBuffers(pass, draw);
                    for (var bound : draw.textures()) {
                        pass.bindTexture(bound.sampler(), bound.view(), bound.samplerState());
                    }
                    pass.bindTexture("Sampler2", lightmap, lightmapSampler);
                    if (draw.graph() != null) {
                        draw.graph().bindCustomUniforms(pass);
                    }
                    if (draw.binder() != null) {
                        draw.binder().bind(pass);
                    }
                    if (PhotonPipelines.isLit(draw.pipeline())) {
                        DynamicLightRenderer.bindLights(pass);
                    }
                    // after bindCustomUniforms, which binds the renamed scene samplers to the missing texture
                    for (var name : draw.sceneSamplers()) {
                        var view = name.contains("Depth")
                                ? (draw.legacyDepth() ? legacyDepth : sceneDepth)
                                : sceneColor;
                        if (view != null) {
                            pass.bindTexture(name, view, captureSampler);
                        }
                    }
                    drawGeometry(pass, draw, indices);
                }
            }
        }
    }

    private static void bindSideBuffers(RenderPass pass, Draw draw) {
        if (draw.cpuData() != null) {
            pass.setUniform(PhotonPipelines.CPU_DATA_BLOCK, draw.cpuData());
        }
        if (draw.points() != null) {
            pass.setUniform("PhotonPoints", draw.points());
        }
        if (draw.data() != null) {
            pass.setUniform("PhotonData", draw.data());
        }
        if (draw.customData() != null) {
            pass.setUniform("PhotonCustomData", draw.customData());
        }
        if (draw.vat() != null && draw.vatInfo() != null) {
            pass.setUniform("PhotonVat", draw.vat());
            pass.setUniform(PhotonVatUniforms.UBO_NAME, draw.vatInfo());
        }
    }

    /**
     * A back-faces-first draw goes twice, through the reversed winding first: a pipeline only says whether to cull,
     * never which face, so the back faces are drawn as front ones of the same triangles wound the other way.
     */
    private static void drawGeometry(RenderPass pass, Draw draw, Indices indices) {
        pass.setVertexBuffer(0, draw.vertices());
        if (draw.instances() != null) {
            pass.setVertexBuffer(1, draw.instances());
        }
        if (draw.backFacesFirst()) {
            var reversed = draw.indices() != null ? draw.reversedIndices()
                    : indices.reversed().get(draw.sequentialTopology());
            if (reversed != null) {
                // same layout, so everything bound stays bound across the switch
                var back = PhotonPipelines.reversedWinding(draw.pipeline());
                if (back != draw.pipeline()) pass.setPipeline(back);
                pass.setIndexBuffer(reversed, IndexType.INT);
                drawIndexed(pass, draw);
                if (back != draw.pipeline()) pass.setPipeline(draw.pipeline());
            }
        }
        if (draw.indices() != null) {
            pass.setIndexBuffer(draw.indices(), draw.indexType());
        } else {
            pass.setIndexBuffer(indices.sequential().get(draw.sequentialTopology()),
                    indices.types().get(draw.sequentialTopology()));
        }
        drawIndexed(pass, draw);
    }

    private static void drawIndexed(RenderPass pass, Draw draw) {
        // firstInstance stays 0: GL's gl_InstanceID ignores it, Vulkan's gl_InstanceIndex includes it
        pass.drawIndexed(draw.indexCount(), draw.instanceCount(), 0, draw.baseVertex(), 0);
    }

}
