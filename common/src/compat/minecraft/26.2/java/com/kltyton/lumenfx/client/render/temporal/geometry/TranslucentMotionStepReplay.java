package com.kltyton.lumenfx.client.render.temporal.geometry;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.render.motion.shader.MotionShaderLayout;
import com.kltyton.lumenfx.effect.render.MaterialDepth;
import com.lowdragmc.photon.client.render.ParticleRasterPass;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

/**
 * Pure OpenGL 3.3 / baseline-Vulkan exact per-primitive translucent depth & velocity replay engine.
 *
 * <p>Requires only GLSL {@code #version 330}, zero SSBOs, zero {@code gl_PrimitiveID}, and zero
 * {@code geometryShader} capability, with O(1) screen-sized scratch targets that cannot overflow.
 * Used automatically on OpenGL 3.3–4.2 contexts, Vulkan devices lacking {@code fragmentStoresAndAtomics}
 * or {@code geometryShader}, or as the extreme {@code 1x1} overflow fallback of {@link TranslucentMotionReplayLog}.
 */
public final class TranslucentMotionStepReplay implements AutoCloseable {
    private static final String STEP_MERGE_UNIFORM = "LfxMotionStepMerge";
    private static final int UNIFORM_BYTES = 16;
    private static final Vector4f CLEAR_ZERO = new Vector4f(0.0f, 0.0f, 0.0f, 0.0f);
    private static final Vector4f CLEAR_EMPTY_COVERAGE = new Vector4f(0.0f, -1.0f, 0.0f, 0.0f);

    private @Nullable GpuTexture colorReplayTexture;
    private @Nullable GpuTextureView colorReplayView;
    private @Nullable GpuTexture stepPreColorTexture;
    private @Nullable GpuTextureView stepPreColorView;
    private @Nullable GpuTexture stepPreviousNdcTexture;
    private @Nullable GpuTextureView stepPreviousNdcView;
    private @Nullable GpuTexture stepDepthCoverageTexture;
    private @Nullable GpuTextureView stepDepthCoverageView;
    private @Nullable GpuBuffer stepBaseUniformBuffer;
    private @Nullable GpuBuffer stepMergeUniformBuffer;
    private @Nullable RenderPipeline stepMergePipeline;

    /** Seeds the writable color-replay depth target from the pre-color depth snapshot before a group. */
    public void beginGroup(CommandEncoder encoder, GpuTexture preColorTexture, int width, int height) {
        RenderSystem.assertOnRenderThread();
        ensureTargets(preColorTexture, width, height);
        encoder.copyTextureToTexture(preColorTexture, Objects.requireNonNull(colorReplayTexture),
                0, 0, 0, 0, 0, width, height);
    }

    /**
     * Executes one non-self-overlapping primitive step against {@code colorReplayDepth} and merges the exact
     * per-fragment transition into {@code (targets.previousNdc, targets.depthCoverage, motionDepth)}.
     */
    public void runPrimitiveStep(CommandEncoder encoder, RenderTarget scene,
                                 GeometryMotionRenderer.CoverageTargets targets,
                                 GpuTextureView motionDepth,
                                 java.util.function.Supplier<@Nullable GpuTextureView> snapshotExistingMotionDepth,
                                 MaterialDepth.Compare compare, boolean writesColorDepth,
                                 int instanceBase, int indirectOffsetUints, boolean indirectGuard,
                                 @Nullable ScissorState scissor,
                                 BiConsumer<RenderPass, GpuBufferSlice> drawPrimitive) {
        RenderSystem.assertOnRenderThread();
        int width = targets.width();
        int height = targets.height();
        encoder.copyTextureToTexture(Objects.requireNonNull(colorReplayTexture),
                Objects.requireNonNull(stepPreColorTexture), 0, 0, 0, 0, 0, width, height);
        encoder.clearColorTexture(Objects.requireNonNull(stepPreviousNdcTexture), CLEAR_ZERO);
        encoder.clearColorTexture(Objects.requireNonNull(stepDepthCoverageTexture), CLEAR_EMPTY_COVERAGE);

        try (var stack = MemoryStack.stackPush()) {
            ByteBuffer baseData = stack.malloc(UNIFORM_BYTES).order(ByteOrder.nativeOrder());
            baseData.putInt(0, instanceBase);
            baseData.putInt(4, indirectOffsetUints);
            baseData.putInt(8, indirectGuard ? 1 : 0);
            baseData.putInt(12, 0);
            encoder.writeToBuffer(Objects.requireNonNull(stepBaseUniformBuffer).slice(0, UNIFORM_BYTES), baseData);

            ByteBuffer mergeData = stack.malloc(UNIFORM_BYTES).order(ByteOrder.nativeOrder());
            mergeData.putInt(0, TranslucentMotionLogLayout.compareCode(compare));
            mergeData.putInt(4, writesColorDepth ? 1 : 0);
            mergeData.putInt(8, 0);
            mergeData.putInt(12, 0);
            encoder.writeToBuffer(Objects.requireNonNull(stepMergeUniformBuffer).slice(0, UNIFORM_BYTES), mergeData);
        }

        var stepDescriptor = RenderPassDescriptor.create(() -> "LumenFX translucent step capture")
                .withColorAttachment(Objects.requireNonNull(stepPreviousNdcView))
                .withColorAttachment(Objects.requireNonNull(stepDepthCoverageView))
                .withDepthAttachment(Objects.requireNonNull(colorReplayView))
                .withRenderArea(new RenderPass.RenderArea(0, 0, width, height));
        try (var pass = openPass(encoder, stepDescriptor, scissor)) {
            drawPrimitive.accept(pass, stepBaseUniformBuffer.slice(0, UNIFORM_BYTES));
        }

        GpuTextureView existingMotionView = snapshotExistingMotionDepth.get();
        if (existingMotionView == null) return;
        var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        var mergeDescriptor = RenderPassDescriptor.create(() -> "LumenFX translucent step merge")
                .withColorAttachment(targets.previousNdc())
                .withColorAttachment(targets.depthCoverage())
                .withDepthAttachment(motionDepth)
                .withRenderArea(new RenderPass.RenderArea(0, 0, width, height));
        try (var pass = openPass(encoder, mergeDescriptor, scissor)) {
            pass.setPipeline(stepMergePipeline());
            pass.setUniform(STEP_MERGE_UNIFORM, stepMergeUniformBuffer.slice(0, UNIFORM_BYTES));
            pass.bindTexture("StepPreColorDepthSampler", Objects.requireNonNull(stepPreColorView), sampler);
            pass.bindTexture("ColorReplayDepthSampler", Objects.requireNonNull(colorReplayView), sampler);
            pass.bindTexture("ExistingMotionDepthSampler", existingMotionView, sampler);
            pass.bindTexture("StepPreviousNdcSampler", Objects.requireNonNull(stepPreviousNdcView), sampler);
            pass.bindTexture("StepDepthCoverageSampler", Objects.requireNonNull(stepDepthCoverageView), sampler);
            pass.draw(3, 1, 0, 0);
        }
    }

    private static RenderPass openPass(CommandEncoder encoder, RenderPassDescriptor descriptor,
                                       @Nullable ScissorState scissor) {
        return scissor != null
                ? ParticleRasterPass.open(encoder, descriptor, scissor)
                : encoder.createRenderPass(descriptor);
    }

    private void ensureTargets(GpuTexture preColorTexture, int width, int height) {
        var device = RenderSystem.getDevice();
        var depthFormat = preColorTexture.getFormat();
        if (colorReplayTexture == null
                || colorReplayTexture.getWidth(0) != width
                || colorReplayTexture.getHeight(0) != height
                || colorReplayTexture.getFormat() != depthFormat) {
            closeDepthTargets();
            colorReplayTexture = device.createTexture("LumenFX translucent step color-replay depth",
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC
                            | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                    depthFormat, width, height, 1, 1);
            try {
                colorReplayView = device.createTextureView(colorReplayTexture);
                stepPreColorTexture = device.createTexture("LumenFX translucent step pre-color depth",
                        GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                        depthFormat, width, height, 1, 1);
                stepPreColorView = device.createTextureView(stepPreColorTexture);
            } catch (RuntimeException | Error error) {
                closeDepthTargets();
                throw error;
            }
        }
        if (stepPreviousNdcTexture == null
                || stepPreviousNdcTexture.getWidth(0) != width
                || stepPreviousNdcTexture.getHeight(0) != height) {
            closeColorTargets();
            int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
            stepPreviousNdcTexture = device.createTexture("LumenFX translucent step previous NDC",
                    usage, MotionShaderLayout.MOTION_CLIP_FORMAT, width, height, 1, 1);
            try {
                stepPreviousNdcView = device.createTextureView(stepPreviousNdcTexture);
                stepDepthCoverageTexture = device.createTexture("LumenFX translucent step depth coverage",
                        usage, MotionShaderLayout.MOTION_DEPTH_FORMAT, width, height, 1, 1);
                stepDepthCoverageView = device.createTextureView(stepDepthCoverageTexture);
            } catch (RuntimeException | Error error) {
                closeColorTargets();
                throw error;
            }
        }
        if (stepBaseUniformBuffer == null) {
            stepBaseUniformBuffer = device.createBuffer(() -> "LumenFX translucent step instance base",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UNIFORM_BYTES);
        }
        if (stepMergeUniformBuffer == null) {
            stepMergeUniformBuffer = device.createBuffer(() -> "LumenFX translucent step merge info",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UNIFORM_BYTES);
        }
    }

    private RenderPipeline stepMergePipeline() {
        if (stepMergePipeline != null) return stepMergePipeline;
        var bindings = BindGroupLayout.builder()
                .withUniform(STEP_MERGE_UNIFORM, UniformType.UNIFORM_BUFFER)
                .withSampler("StepPreColorDepthSampler")
                .withSampler("ColorReplayDepthSampler")
                .withSampler("ExistingMotionDepthSampler")
                .withSampler("StepPreviousNdcSampler")
                .withSampler("StepDepthCoverageSampler");
        stepMergePipeline = RenderPipeline.builder()
                .withLocation(LumenFx.id("pipeline/temporal/translucent_motion_step_merge"))
                .withVertexShader(LumenFx.id("post/fullscreen"))
                .withFragmentShader(LumenFx.id("temporal/translucent_motion_step_merge"))
                .withCull(false)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
                .withColorTargetState(0, new ColorTargetState(Optional.empty(),
                        MotionShaderLayout.MOTION_CLIP_FORMAT, ColorTargetState.WRITE_ALL))
                .withColorTargetState(1, new ColorTargetState(Optional.empty(),
                        MotionShaderLayout.MOTION_DEPTH_FORMAT,
                        ColorTargetState.WRITE_RED | ColorTargetState.WRITE_GREEN))
                .withBindGroupLayout(bindings.build())
                .build();
        return stepMergePipeline;
    }

    private void closeDepthTargets() {
        if (colorReplayView != null) { colorReplayView.close(); colorReplayView = null; }
        if (colorReplayTexture != null) { colorReplayTexture.close(); colorReplayTexture = null; }
        if (stepPreColorView != null) { stepPreColorView.close(); stepPreColorView = null; }
        if (stepPreColorTexture != null) { stepPreColorTexture.close(); stepPreColorTexture = null; }
    }

    private void closeColorTargets() {
        if (stepPreviousNdcView != null) { stepPreviousNdcView.close(); stepPreviousNdcView = null; }
        if (stepPreviousNdcTexture != null) { stepPreviousNdcTexture.close(); stepPreviousNdcTexture = null; }
        if (stepDepthCoverageView != null) { stepDepthCoverageView.close(); stepDepthCoverageView = null; }
        if (stepDepthCoverageTexture != null) { stepDepthCoverageTexture.close(); stepDepthCoverageTexture = null; }
    }

    @Override public void close() {
        closeDepthTargets();
        closeColorTargets();
        if (stepBaseUniformBuffer != null) { stepBaseUniformBuffer.close(); stepBaseUniformBuffer = null; }
        if (stepMergeUniformBuffer != null) { stepMergeUniformBuffer.close(); stepMergeUniformBuffer = null; }
        stepMergePipeline = null;
    }

    /**
     * Pure Java reference of one primitive step ({@code colorReplayDepth} hardware test +
     * {@code translucent_motion_step_merge.fsh}), proving bit-for-bit parity with {@link TranslucentMotionReplayLog#resolvePixel}.
     */
    public static TranslucentMotionReplayLog.PixelState applyPrimitiveStep(
            TranslucentMotionReplayLog.PixelState current,
            float fragDepth, MaterialDepth.Compare compare,
            boolean writesColorDepth, boolean velocityEligible,
            float currentClipDepth,
            float prevNdcX, float prevNdcY, float prevNdcZ, float prevNdcW) {
        int compareCode = TranslucentMotionLogLayout.compareCode(compare);
        float oldC = current.colorDepth();
        if (!TranslucentMotionReplayLog.depthTest(compareCode, fragDepth, oldC)) {
            return current;
        }
        float newC = writesColorDepth ? fragDepth : oldC;
        float motionDepth = current.motionDepth();
        if (velocityEligible) {
            if (!TranslucentMotionReplayLog.depthTest(compareCode, fragDepth, motionDepth)) {
                return new TranslucentMotionReplayLog.PixelState(newC, motionDepth,
                        current.prevNdcX(), current.prevNdcY(), current.prevNdcZ(), current.prevNdcW(),
                        current.covClipDepth(), current.covFragDepth());
            }
            return new TranslucentMotionReplayLog.PixelState(newC, fragDepth,
                    prevNdcX, prevNdcY, prevNdcZ, prevNdcW, currentClipDepth, fragDepth);
        }
        if (!writesColorDepth) {
            return current;
        }
        if (newC > oldC) {
            if (newC >= motionDepth && newC != motionDepth) {
                return new TranslucentMotionReplayLog.PixelState(newC, newC,
                        0.0f, 0.0f, 0.0f, 0.0f, 0.0f, -1.0f);
            }
        } else if (newC < oldC) {
            if (motionDepth <= oldC && newC != motionDepth) {
                return new TranslucentMotionReplayLog.PixelState(newC, newC,
                        0.0f, 0.0f, 0.0f, 0.0f, 0.0f, -1.0f);
            }
        }
        return new TranslucentMotionReplayLog.PixelState(newC, motionDepth,
                current.prevNdcX(), current.prevNdcY(), current.prevNdcZ(), current.prevNdcW(),
                current.covClipDepth(), current.covFragDepth());
    }
}
