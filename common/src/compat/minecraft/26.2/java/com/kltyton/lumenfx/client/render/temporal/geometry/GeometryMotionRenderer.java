package com.kltyton.lumenfx.client.render.temporal.geometry;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.compat.iris.IrisParticleRenderScope;
import com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture;
import com.kltyton.lumenfx.client.render.motion.DrawMotion;
import com.mojang.blaze3d.opengl.GlStateManager;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import com.kltyton.lumenfx.client.render.motion.MotionHistory;
import com.kltyton.lumenfx.client.render.motion.shader.MotionShaderAdapter;
import com.kltyton.lumenfx.client.render.motion.shader.MotionShaderContractException;
import com.kltyton.lumenfx.client.render.motion.shader.MotionShaderLayout;
import com.kltyton.lumenfx.client.render.motion.shader.MotionShaderSet;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

public final class GeometryMotionRenderer implements AutoCloseable {
    private static final BindGroupLayout POINT_BINDINGS = BindGroupLayout.builder()
            .withUniform(MotionShaderLayout.ATLAS_BLOCK, UniformType.UNIFORM_BUFFER)
            .build();
    private static final BindGroupLayout COVERAGE_BINDINGS = BindGroupLayout.builder()
            .withUniform(MotionShaderLayout.ATLAS_BLOCK, UniformType.UNIFORM_BUFFER)
            .withUniform(MotionShaderLayout.VERTEX_MAP, UniformType.TEXEL_BUFFER,
                    MotionShaderLayout.VERTEX_MAP_FORMAT)
            .withSampler(MotionShaderLayout.PREVIOUS_CLIP_ATLAS)
            .build();
    private static final int MAP_SLOTS = 3;
    private static final int MIN_MAP_CAPACITY = 4096;
    private static final Vector4f CLEAR = new Vector4f();

    private static volatile GeometryMotionRenderer active;
    private static int pipelineSequence;

    private final Map<RenderPipeline, Variant> variants = new IdentityHashMap<>();
    private final Map<StagedVertexBuffer.Draw, CapturePlan> plans = new IdentityHashMap<>();
    private final MapSlot[] mapSlots = new MapSlot[MAP_SLOTS];
    private final ParticleMotionDepth particleDepth = new ParticleMotionDepth();

    private @Nullable MotionShaderAdapter shaderAdapter;
    private @Nullable VertexClipAtlas atlas;
    private @Nullable TextureTarget previousNdcTarget;
    private @Nullable TextureTarget coverageDepthTarget;
    private @Nullable TextureTarget motionOffsetTarget;
    private @Nullable TextureTarget emptyClipTarget;
    private @Nullable GpuTextureView framePreviousNdc;
    private @Nullable GpuTextureView frameCoverageDepth;
    private @Nullable GpuTextureView frameMotionOffset;
    private @Nullable GpuTextureView atlasCurrent;
    private @Nullable GpuTextureView atlasPrevious;
    private VertexClipAtlas.@Nullable Layout atlasCurrentLayout;
    private VertexClipAtlas.@Nullable Layout atlasPreviousLayout;
    private @Nullable GpuTextureView sceneDepth;
    private @Nullable GpuBuffer vertexMap;
    private int sceneWidth;
    private int sceneHeight;
    private int mapSlot;
    private @Nullable Object world;
    private @Nullable Object cameraOwner;
    private long cutVersion;
    private boolean initialized;
    private boolean frameOpen;
    private boolean historyValid;
    private boolean particleHistoryValid;

    public GeometryMotionRenderer() {
        for (int index = 0; index < this.mapSlots.length; index++) {
            this.mapSlots[index] = new MapSlot();
        }
    }

    public static @Nullable GeometryMotionRenderer active() {
        return active;
    }

    public void activate() {
        active = this;
    }

    public void deactivate() {
        if (active == this) {
            active = null;
        }
    }

    public void prepare(
            List<DrawMotion> draws,
            RenderTarget scene,
            Object world,
            Object cameraOwner,
            long cutVersion) {
        Objects.requireNonNull(scene, "scene");
        GpuTextureView depth = scene.getDepthTextureView();
        if (depth == null) {
            throw new IllegalArgumentException("Geometry motion coverage requires the real scene depth texture");
        }
        prepare(draws, depth, scene.width, scene.height, world, cameraOwner, cutVersion);
    }

    public void prepare(
            List<DrawMotion> draws,
            GpuTextureView depth,
            int width,
            int height,
            Object world,
            Object cameraOwner,
            long cutVersion) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(draws, "draws");
        Objects.requireNonNull(depth, "depth");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Geometry motion coverage requires a non-empty scene viewport");
        }
        if (this.frameOpen) {
            this.discardFrame();
        }

        boolean changed = !this.initialized
                || this.world != world
                || this.cameraOwner != cameraOwner
                || this.cutVersion != cutVersion;
        this.world = world;
        this.cameraOwner = cameraOwner;
        this.cutVersion = cutVersion;
        this.initialized = true;
        if (changed) {
            this.invalidateHistory();
        }

        List<CapturePlan> built = new ArrayList<>(draws.size());
        IdentityHashMap<StagedVertexBuffer.Draw, CapturePlan> byDraw = new IdentityHashMap<>();
        int nextAtlasTexel = 0;
        int nextMapTexel = 0;
        long totalLong = 0L;
        for (DrawMotion draw : draws) {
            if (draw == null || !draw.validated() || !draw.countValid()) {
                continue;
            }
            int count = draw.vertexCount();
            if (count <= 0) {
                continue;
            }
            if (totalLong + count > Integer.MAX_VALUE / Integer.BYTES) {
                throw new IllegalArgumentException("Geometry motion vertex map exceeds the addressable buffer size");
            }
            CapturePlan plan = new CapturePlan(draw, nextAtlasTexel, nextMapTexel, count);
            built.add(plan);
            byDraw.put(draw.draw(), plan);
            nextAtlasTexel += count;
            nextMapTexel += count;
            totalLong += count;
        }
        int totalVertices = (int)totalLong;

        this.plans.clear();
        this.plans.putAll(byDraw);
        this.ensureTargets(width, height);
        this.sceneDepth = depth;
        this.sceneWidth = width;
        this.sceneHeight = height;
        this.framePreviousNdc = this.requireColorView(this.previousNdcTarget);
        this.frameCoverageDepth = this.requireColorView(this.coverageDepthTarget);
        this.frameMotionOffset = this.requireColorView(this.motionOffsetTarget);

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.clearColorTexture(this.requireColorTexture(this.previousNdcTarget), CLEAR);
        encoder.clearColorTexture(this.requireColorTexture(this.coverageDepthTarget), CLEAR);
        encoder.clearColorTexture(this.requireColorTexture(this.motionOffsetTarget), CLEAR);

        if (this.atlas == null) {
            this.atlas = new VertexClipAtlas();
        }
        VertexClipAtlas.Frame frame = this.atlas.beginFrame(totalVertices, true);
        this.frameOpen = true;
        this.particleHistoryValid = false;
        this.particleDepth.beginFrame();
        this.atlasCurrent = frame.current();
        this.atlasPrevious = frame.previous();
        this.atlasCurrentLayout = frame.currentLayout();
        this.atlasPreviousLayout = frame.previousLayout();
        this.historyValid = frame.historyValid() && frame.previousLayout().vertexCount() > 0;

        try {
            this.writeVertexMap(encoder, built, totalVertices);
            this.activate();
        } catch (RuntimeException failure) {
            this.discardFrame();
            throw failure;
        }
    }

    public void updateSceneDepth(GpuTextureView depth) {
        RenderSystem.assertOnRenderThread();
        if (!this.frameOpen || depth == null) return;
        if (depth.getWidth(0) == this.sceneWidth && depth.getHeight(0) == this.sceneHeight) {
            this.sceneDepth = depth;
        }
    }

    public void draw(PreparedRenderType prepared, StagedVertexBuffer.ExecuteInfo info) {
        RenderSystem.assertOnRenderThread();
        if (!this.frameOpen || IrisParticleRenderScope.isShadowPass()) {
            return;
        }
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(info, "info");
        StagedVertexBuffer.Draw draw = MotionHistory.INSTANCE.drawFor(info);
        if (draw == null) {
            return;
        }
        CapturePlan plan = this.plans.get(draw);
        if (plan == null) {
            return;
        }

        boolean externalGl = WorldHdrCapture.INSTANCE.externalSceneOwner()
                && RenderSystem.getDevice().backend instanceof com.mojang.blaze3d.opengl.GlDevice;
        int prevReadFbo = externalGl ? GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING) : 0;
        int prevDrawFbo = externalGl ? GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING) : 0;
        IrisParticleRenderScope.withParticlePrograms(() -> {
            Variant variant = this.variant(prepared.pipeline());
            this.drawPointCapture(prepared, info, plan, variant);
            MotionHistory.INSTANCE.recordCapturedDraw(draw, plan.firstAtlasTexel());

            if (variant.coverage() != null && this.vertexMap != null) {
                this.drawCoverage(prepared, info, plan, variant);
            }
        });
        if (externalGl) {
            GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, prevReadFbo);
            GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
        }
    }

    public void commitFrame() {
        RenderSystem.assertOnRenderThread();
        if (this.frameOpen) {
            if (this.atlas != null) {
                this.atlas.commitFrame();
            }
            this.frameOpen = false;
        }
        this.deactivate();
    }

    public void discardFrame() {
        RenderSystem.assertOnRenderThread();
        if (this.frameOpen) {
            if (this.atlas != null) this.atlas.discardFrame();
            this.frameOpen = false;
        }
        this.historyValid = false;
        this.particleHistoryValid = false;
        this.deactivate();
    }

    public void holdFrame() {
        RenderSystem.assertOnRenderThread();
        if (this.frameOpen) {
            if (this.atlas != null) {
                this.atlas.holdFrame();
            }
            this.frameOpen = false;
        }
        this.deactivate();
    }

    public void release() {
        RenderSystem.assertOnRenderThread();
        this.particleDepth.close();
        if (this.frameOpen) {
            this.discardFrame();
        }
        this.deactivate();
        if (this.atlas != null) {
            this.atlas.close();
            this.atlas = null;
        }
        if (this.previousNdcTarget != null) {
            this.previousNdcTarget.destroyBuffers();
            this.previousNdcTarget = null;
        }
        if (this.coverageDepthTarget != null) {
            this.coverageDepthTarget.destroyBuffers();
            this.coverageDepthTarget = null;
        }
        if (this.motionOffsetTarget != null) {
            this.motionOffsetTarget.destroyBuffers();
            this.motionOffsetTarget = null;
        }
        if (this.emptyClipTarget != null) {
            this.emptyClipTarget.destroyBuffers();
            this.emptyClipTarget = null;
        }
        for (MapSlot slot : this.mapSlots) {
            if (slot.buffer != null) {
                slot.buffer.close();
            }
            slot.buffer = null;
            slot.capacity = 0L;
        }
        if (this.shaderAdapter != null) {
            this.shaderAdapter.clear();
            this.shaderAdapter = null;
        }
        this.variants.clear();
        this.plans.clear();
        this.framePreviousNdc = null;
        this.frameCoverageDepth = null;
        this.frameMotionOffset = null;
        this.atlasCurrent = null;
        this.atlasPrevious = null;
        this.atlasCurrentLayout = null;
        this.atlasPreviousLayout = null;
        this.sceneDepth = null;
        this.vertexMap = null;
        this.world = null;
        this.cameraOwner = null;
        this.cutVersion = 0L;
        this.initialized = false;
        this.historyValid = false;
    }

    public void invalidateShaders() {
        RenderSystem.assertOnRenderThread();
        this.variants.clear();
        if (this.shaderAdapter != null) {
            this.shaderAdapter.clear();
        }
    }

    public @Nullable GpuTextureView previousNdc() {
        return this.framePreviousNdc;
    }

    public @Nullable GpuTextureView coverageDepth() {
        return this.frameCoverageDepth;
    }

    public @Nullable GpuTextureView motionOffset() {
        return this.frameMotionOffset;
    }

    public boolean hasFrameHistory() {
        return this.historyValid || this.particleHistoryValid;
    }

    public void recordParticleHistory(boolean valid) {
        if (!frameOpen) throw new IllegalStateException("Particle velocity frame is not open");
        particleHistoryValid |= valid;
    }

    public record CoverageTargets(GpuTextureView previousNdc, GpuTextureView depthCoverage,
                                  @Nullable GpuTextureView motionOffset, int width, int height) {
        public CoverageTargets(GpuTextureView previousNdc, GpuTextureView depthCoverage, int width, int height) {
            this(previousNdc, depthCoverage, null, width, height);
        }
    }
    public GpuTextureView particleDepth(CommandEncoder encoder, RenderTarget scene) {
        if (!frameOpen) throw new IllegalStateException("Particle velocity frame is not open");
        return particleDepth.attachment(encoder, scene);
    }
    public boolean hasParticleDepth() { return frameOpen && particleDepth.isActive(); }
    public void snapshotTranslucentPreColorDepth(CommandEncoder encoder, RenderTarget scene) {
        if (frameOpen && particleDepth.isActive()) particleDepth.snapshotPreColorDepth(encoder, scene);
    }
    public @Nullable GpuTextureView translucentPreColorDepth() {
        return frameOpen && particleDepth.isActive() ? particleDepth.preColorView() : null;
    }
    public void clearTranslucentPreColorDepth() { particleDepth.clearPreColorCapture(); }
    public boolean executeTranslucentMotionLog(
            CommandEncoder encoder, RenderTarget scene, CoverageTargets targets,
            com.mojang.blaze3d.systems.@Nullable ScissorState scissor,
            List<TranslucentMotionReplayLog.DrawSpec> draws, long maxPrimitives,
            java.util.function.BiConsumer<RenderPass, TranslucentMotionReplayLog.DrawCursor> captureDrawer,
            java.util.function.Consumer<com.mojang.blaze3d.systems.ScissorState> singlePixelOverflowFallback) {
        if (!frameOpen) throw new IllegalStateException("Particle velocity frame is not open");
        return particleDepth.executeTranslucentMotionLog(encoder, scene, targets, scissor, draws,
                maxPrimitives, captureDrawer, singlePixelOverflowFallback);
    }
    public void beginTranslucentStepReplay(CommandEncoder encoder, RenderTarget scene) {
        if (!frameOpen) throw new IllegalStateException("Particle velocity frame is not open");
        particleDepth.beginTranslucentStepReplay(encoder, scene);
    }
    public void runTranslucentStepPrimitive(
            CommandEncoder encoder, RenderTarget scene, CoverageTargets targets,
            com.kltyton.lumenfx.effect.render.MaterialDepth.Compare compare, boolean writesColorDepth,
            int instanceBase, int indirectOffsetUints, boolean indirectGuard,
            com.mojang.blaze3d.systems.@Nullable ScissorState scissor,
            java.util.function.BiConsumer<RenderPass, GpuBufferSlice> drawPrimitive) {
        if (!frameOpen) throw new IllegalStateException("Particle velocity frame is not open");
        particleDepth.runTranslucentStepPrimitive(encoder, scene, targets, compare, writesColorDepth,
                instanceBase, indirectOffsetUints, indirectGuard, scissor, drawPrimitive);
    }
    public GpuTextureView velocityDepth(GpuTextureView opaqueDepth) { return particleDepth.resolveDepth(opaqueDepth); }
    public @Nullable CoverageTargets coverageTargets() {
        return framePreviousNdc == null || frameCoverageDepth == null ? null
                : new CoverageTargets(framePreviousNdc, frameCoverageDepth, frameMotionOffset, sceneWidth, sceneHeight);
    }

    @Override
    public void close() {
        this.release();
    }

    private void invalidateHistory() {
        if (this.atlas != null) {
            this.atlas.close();
            this.atlas = null;
        }
        this.historyValid = false;
    }

    private void ensureTargets(int width, int height) {
        if (this.previousNdcTarget == null) {
            this.previousNdcTarget = new TextureTarget(
                    "LumenFX motion previous NDC", width, height, false, MotionShaderLayout.MOTION_CLIP_FORMAT);
        } else if (this.previousNdcTarget.width != width || this.previousNdcTarget.height != height) {
            this.previousNdcTarget.resize(width, height);
        }
        if (this.coverageDepthTarget == null) {
            this.coverageDepthTarget = new TextureTarget(
                    "LumenFX motion coverage depth", width, height, false, MotionShaderLayout.MOTION_DEPTH_FORMAT);
        } else if (this.coverageDepthTarget.width != width || this.coverageDepthTarget.height != height) {
            this.coverageDepthTarget.resize(width, height);
        }
        if (this.motionOffsetTarget == null) {
            this.motionOffsetTarget = new TextureTarget(
                    "LumenFX motion vector world offset", width, height, false, MotionShaderLayout.MOTION_OFFSET_FORMAT);
        } else if (this.motionOffsetTarget.width != width || this.motionOffsetTarget.height != height) {
            this.motionOffsetTarget.resize(width, height);
        }
    }

    private void writeVertexMap(CommandEncoder encoder, List<CapturePlan> capturePlans, int totalVertices) {
        this.vertexMap = null;
        if (totalVertices == 0) {
            return;
        }
        long bytes = (long)totalVertices * Integer.BYTES;
        MapSlot slot = this.mapSlots[this.mapSlot];
        this.mapSlot = (this.mapSlot + 1) % this.mapSlots.length;
        if (slot.buffer == null || slot.capacity < bytes) {
            if (slot.buffer != null) {
                slot.buffer.close();
            }
            long capacity = Math.max(bytes, MIN_MAP_CAPACITY);
            slot.buffer = RenderSystem.getDevice().createBuffer(
                    () -> "LumenFX motion vertex map",
                    GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST,
                    capacity);
            slot.capacity = capacity;
        }

        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        GpuBufferSlice vertexMapUpload;
        try (var upload = encoder.transientMemory().allocateGpuMapped(bytes, alignment, GpuBuffer.USAGE_COPY_SRC)) {
            IntBuffer map = upload.data().order(ByteOrder.nativeOrder()).asIntBuffer();
            if (this.historyValid) {
                for (CapturePlan plan : capturePlans) {
                    IntBuffer slice = map.duplicate();
                    slice.position(plan.firstMapTexel());
                    IntBuffer destination = slice.slice();
                    destination.limit(plan.vertexCount());
                    MotionHistory.INSTANCE.writePreviousVertexMap(plan.draw(), destination);
                }
            } else {
                for (int vertex = 0; vertex < totalVertices; vertex++) map.put(vertex, 0);
            }
            vertexMapUpload = upload.slice();
        }
        encoder.copyToBuffer(vertexMapUpload, slot.buffer.slice(0L, bytes));
        this.vertexMap = slot.buffer;
    }

    private Variant variant(RenderPipeline host) {
        Variant cached = this.variants.get(host);
        if (cached == null) {
            MotionShaderAdapter adapter = this.adapter();
            MotionShaderSet pointSet = adapter.pointCapture(host);
            RenderPipeline point = this.buildPointPipeline(host, pointSet);
            MotionShaderSet coverageSet = null;
            RenderPipeline coverage = null;
            DepthStencilState depth = host.getDepthStencilState();
            if (depth != null && depth.writeDepth()) {
                coverageSet = adapter.motionCoverage(host);
                coverage = this.buildCoveragePipeline(host, coverageSet);
            }
            cached = new Variant(point, coverage, pointSet, coverageSet);
            this.ensureCompiled(cached);
            this.variants.put(host, cached);
        }
        this.ensureCompiled(cached);
        return cached;
    }

    private MotionShaderAdapter adapter() {
        if (this.shaderAdapter == null) {
            this.shaderAdapter = new MotionShaderAdapter(
                    (id, type) -> Minecraft.getInstance().getShaderManager().getShader(id, type));
        }
        return this.shaderAdapter;
    }

    private static void ensureCompiled(Variant variant) {
        GpuDevice device = RenderSystem.getDevice();
        if (!device.precompilePipeline(variant.point(), variant.pointSet().source()).isValid()) {
            throw new MotionShaderContractException(
                    "Point capture pipeline failed to compile: " + variant.point().getLocation());
        }
        if (variant.coverage() != null && variant.coverageSet() != null
                && !device.precompilePipeline(variant.coverage(), variant.coverageSet().source()).isValid()) {
            throw new MotionShaderContractException(
                    "Coverage pipeline failed to compile: " + variant.coverage().getLocation());
        }
    }

    private RenderPipeline buildPointPipeline(RenderPipeline host, MotionShaderSet set) {
        RenderPipeline.Builder builder = builderWithDefines(host.getShaderDefines());
        builder.withLocation(LumenFx.id("pipeline/motion/point/" + pipelineSequence++))
                .withVertexShader(set.vertexShader())
                .withFragmentShader(set.fragmentShader())
                .withPrimitiveTopology(PrimitiveTopology.POINTS)
                .withCull(host.isCull())
                .withPolygonMode(host.getPolygonMode())
                .withColorTargetState(new ColorTargetState(
                        Optional.empty(), MotionShaderLayout.POINT_CLIP_FORMAT, ColorTargetState.WRITE_ALL))
                .withDepthStencilState(Optional.empty());
        copyBindings(builder, host, POINT_BINDINGS);
        copyVertexFormats(builder, host);
        return builder.build();
    }

    private RenderPipeline buildCoveragePipeline(RenderPipeline host, MotionShaderSet set) {
        return com.kltyton.lumenfx.client.render.motion.shader.MotionPipelineFactory.coverage(host, set,
                LumenFx.id("pipeline/motion/coverage/" + pipelineSequence++), COVERAGE_BINDINGS, false);
    }

    private static RenderPipeline.Builder builderWithDefines(ShaderDefines defines) {
        RenderPipeline.Snippet snippet = new RenderPipeline.Snippet(
                Optional.empty(),
                Optional.empty(),
                Optional.of(defines),
                Optional.empty(),
                new ColorTargetState[ColorTargetState.MAX_COLOR_TARGETS],
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                new VertexFormat[16],
                Optional.empty());
        return RenderPipeline.builder(snippet);
    }

    private static void copyBindings(RenderPipeline.Builder builder, RenderPipeline host, BindGroupLayout extra) {
        for (BindGroupLayout layout : host.getBindGroupLayouts()) {
            builder.withBindGroupLayout(layout);
        }
        builder.withBindGroupLayout(extra);
    }

    private static void copyVertexFormats(RenderPipeline.Builder builder, RenderPipeline host) {
        VertexFormat[] formats = host.getVertexFormatBindings();
        for (int binding = 0; binding < formats.length; binding++) {
            VertexFormat format = formats[binding];
            if (format != null) {
                builder.withVertexBinding(binding, format);
            }
        }
    }

    private void drawPointCapture(
            PreparedRenderType prepared,
            StagedVertexBuffer.ExecuteInfo info,
            CapturePlan plan,
            Variant variant) {
        GpuTextureView target = Objects.requireNonNull(this.atlasCurrent, "atlas current texture");
        VertexClipAtlas.Layout layout = Objects.requireNonNull(this.atlasCurrentLayout, "atlas current layout");
        GpuDevice device = RenderSystem.getDevice();
        CommandEncoder encoder = device.createCommandEncoder();
        int alignment = device.getDeviceInfo().limits().minUniformOffsetAlignment();
        GpuBufferSlice packetSlice;
        try (GpuBufferSlice.MappedView packet = encoder.transientMemory()
                .allocateGpuMapped(MotionShaderLayout.ATLAS_PACKET_BYTES, alignment, GpuBuffer.USAGE_UNIFORM)) {
            MotionShaderLayout.writeAtlasPacket(
                    packet.data().order(ByteOrder.nativeOrder()),
                    layout.width(),
                    layout.height(),
                    info.baseVertex(),
                    plan.firstAtlasTexel(),
                    plan.firstMapTexel());
            packetSlice = packet.slice();
        }
        try (RenderPass pass = encoder.createRenderPass(
                () -> "LumenFX motion point capture", target, Optional.empty())) {
            pass.setPipeline(variant.point());
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", prepared.dynamicTransforms());
            pass.setUniform(MotionShaderLayout.ATLAS_BLOCK, packetSlice);
            pass.setVertexBuffer(0, info.vertexBuffer().slice());
            bindTextures(pass, prepared);
            pass.draw(plan.vertexCount(), 1, info.baseVertex(), 0);
        }
    }

    private void drawCoverage(
            PreparedRenderType prepared,
            StagedVertexBuffer.ExecuteInfo info,
            CapturePlan plan,
            Variant variant) {
        GpuTextureView depth = Objects.requireNonNull(this.sceneDepth, "scene depth texture");
        GpuTextureView previousNdc = Objects.requireNonNull(this.framePreviousNdc, "previous NDC target");
        GpuTextureView coverage = Objects.requireNonNull(this.frameCoverageDepth, "coverage depth target");
        if (depth.getWidth(0) != this.sceneWidth || depth.getHeight(0) != this.sceneHeight) {
            throw new IllegalStateException("Scene depth changed after geometry motion prepare");
        }
        VertexClipAtlas.Layout layout = Objects.requireNonNull(this.atlasPreviousLayout, "atlas previous layout");
        GpuTextureView previousClip = this.historyValid ? this.atlasPrevious : null;
        if (previousClip == null || previousClip == this.atlasCurrent) {
            previousClip = this.emptyClip();
        }
        GpuBuffer map = Objects.requireNonNull(this.vertexMap, "vertex map buffer");
        GpuDevice device = RenderSystem.getDevice();
        CommandEncoder encoder = device.createCommandEncoder();
        int alignment = device.getDeviceInfo().limits().minUniformOffsetAlignment();
        GpuBufferSlice packetSlice;
        try (GpuBufferSlice.MappedView packet = encoder.transientMemory()
                .allocateGpuMapped(MotionShaderLayout.ATLAS_PACKET_BYTES, alignment, GpuBuffer.USAGE_UNIFORM)) {
            MotionShaderLayout.writeAtlasPacket(
                    packet.data().order(ByteOrder.nativeOrder()),
                    layout.width(),
                    layout.height(),
                    info.baseVertex(),
                    plan.firstAtlasTexel(),
                    plan.firstMapTexel());
            packetSlice = packet.slice();
        }
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "LumenFX motion coverage")
                .withColorAttachment(previousNdc)
                .withColorAttachment(coverage)
                .withDepthAttachment(depth)
                .withRenderArea(new RenderPass.RenderArea(0, 0, this.sceneWidth, this.sceneHeight));
        try (RenderPass pass = encoder.createRenderPass(descriptor)) {
            pass.setPipeline(variant.coverage());
            if (prepared.scissorState().enabled()) {
                pass.enableScissor(
                        prepared.scissorState().x(),
                        prepared.scissorState().y(),
                        prepared.scissorState().width(),
                        prepared.scissorState().height());
            }
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", prepared.dynamicTransforms());
            pass.setUniform(MotionShaderLayout.ATLAS_BLOCK, packetSlice);
            pass.setUniform(MotionShaderLayout.VERTEX_MAP, map.slice());
            pass.bindTexture(
                    MotionShaderLayout.PREVIOUS_CLIP_ATLAS,
                    previousClip,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.setVertexBuffer(0, info.vertexBuffer().slice());
            bindTextures(pass, prepared);
            pass.setIndexBuffer(info.indexBuffer(), info.indexType());
            pass.drawIndexed(
                    info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
        }
    }

    private static void bindTextures(RenderPass pass, PreparedRenderType prepared) {
        for (PreparedRenderType.Texture texture : prepared.textures()) {
            pass.bindTexture(texture.name(), texture.textureView(), texture.sampler());
        }
    }

    private GpuTextureView emptyClip() {
        if (this.emptyClipTarget == null) {
            this.emptyClipTarget = new TextureTarget(
                    "LumenFX empty previous clip", 1, 1, false, MotionShaderLayout.PREVIOUS_CLIP_ATLAS_FORMAT);
            RenderSystem.getDevice().createCommandEncoder()
                    .clearColorTexture(this.requireColorTexture(this.emptyClipTarget), CLEAR);
        }
        return this.requireColorView(this.emptyClipTarget);
    }

    private com.mojang.blaze3d.textures.GpuTexture requireColorTexture(@Nullable TextureTarget target) {
        TextureTarget actual = Objects.requireNonNull(target, "render target");
        return Objects.requireNonNull(actual.getColorTexture(), "render target color texture");
    }

    private GpuTextureView requireColorView(@Nullable TextureTarget target) {
        TextureTarget actual = Objects.requireNonNull(target, "render target");
        return Objects.requireNonNull(actual.getColorTextureView(), "render target color view");
    }

    private record CapturePlan(
            DrawMotion motion, int firstAtlasTexel, int firstMapTexel, int vertexCount) {
        private StagedVertexBuffer.Draw draw() {
            return this.motion.draw();
        }
    }

    private record Variant(
            RenderPipeline point,
            @Nullable RenderPipeline coverage,
            MotionShaderSet pointSet,
            @Nullable MotionShaderSet coverageSet) {
    }

    private static final class MapSlot {
        private @Nullable GpuBuffer buffer;
        private long capacity;
    }
}
