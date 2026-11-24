package com.kltyton.lumenfx.client.render.niagara.splat;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.client.render.compute.ComputeAccess;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ComputeTexture;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraCameraFrame;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuComputeStage;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuRadixSort;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuView;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraSceneInputs;
import com.kltyton.lumenfx.client.resource.texture.EffectTexture;
import com.kltyton.lumenfx.client.resource.texture.splat.GaussianSplatLutTextures;
import com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.client.resource.texture.lighting.BlueNoiseTextures;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.BlendOp;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector4d;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Renders CPU/GPU Niagara Gaussian Splat proxies using UE5's shading, radix sort,
 * and hardware or tiled compute rasterization paths with inverse tone-curve blending.
 */
public final class NiagaraGaussianSplatRenderer implements AutoCloseable {
    private static final int DEPTH_OFF = 0;
    private static final int DEPTH_HARDWARE = 1;
    private static final int DEPTH_STOCHASTIC = 2;
    private static final int DEPTH_EXPECTED = 3;

    private static final int THREADS_1D = 64;
    private static final int WRAPPED_STRIDE = 128;
    private static final int SHADED_SPLAT_BYTES = 24;
    private static final int DRAW_INDIRECT_BYTES = 16;
    private static final int RASTER_VIEW_BYTES = 96;
    private static final int POST_BLEND_BYTES = 32;
    private static final int EXPECTED_DEPTH_BYTES = 32;
    private static final int DOWNSAMPLE_BYTES = 16;
    private static final int UPSAMPLE_BYTES = 16;
    private static final float GAUSSIAN_RADIUS_SQUARED = 4.0f;

    private static final BlendFunction HW_OVER_BLEND = new BlendFunction(
            BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA,
            BlendFactor.ZERO, BlendFactor.ONE_MINUS_SRC_ALPHA);
    private static final BlendFunction HW_UNDER_BLEND = new BlendFunction(
            BlendFactor.DST_ALPHA, BlendFactor.ONE,
            BlendFactor.ZERO, BlendFactor.ONE_MINUS_SRC_ALPHA);
    private static final BlendFunction STOCHASTIC_MAX_BLEND = new BlendFunction(
            BlendFactor.ONE, BlendFactor.ONE, BlendOp.MAX);
    private static final BlendFunction POST_BLEND = new BlendFunction(
            BlendFactor.ONE, BlendFactor.SRC_ALPHA,
            BlendFactor.ZERO, BlendFactor.SRC_ALPHA);

    private static final AtomicInteger PIPELINE_SEQ = new AtomicInteger();

    private record SplatConfig(
            boolean enabled,
            int numSplatsCap,
            int tileSize,
            int maxSplatOverlap,
            boolean hwRaster,
            boolean blueNoise,
            int depthMode,
            GpuFormat stochasticDepthFormat,
            float hwOpacityThreshold,
            float shadingOpacityThreshold,
            boolean frustumCull,
            float expectedDepthScale,
            int sortQuality,
            int falloff,
            int downsampleFactor,
            int gatherShadedSplats
    ) {
        static SplatConfig read() {
            var env = NiagaraPlatforms.snapshot();
            int depth = Math.clamp(consoleInt(env, "r.GaussianSplat.Depth", 0), DEPTH_OFF, DEPTH_EXPECTED);
            GpuFormat stochasticFormat = switch (Math.clamp(consoleInt(env, "r.GaussianSplat.StochasticDepthFormat", 1), 0, 3)) {
                case 0 -> GpuFormat.R8_UNORM;
                case 2 -> GpuFormat.R16_FLOAT;
                case 3 -> GpuFormat.R32_FLOAT;
                default -> GpuFormat.R16_UNORM;
            };
            return new SplatConfig(
                    env.gaussianSplatEnabled(),
                    consoleInt(env, "r.GaussianSplat.NumSplats", -1),
                    Math.max(1, consoleInt(env, "r.GaussianSplat.TileSize", 8)),
                    Math.max(1, consoleInt(env, "r.GaussianSplat.MaxSplatOverlap", 16)),
                    consoleInt(env, "r.GaussianSplat.HardwareRasterization", 1) != 0,
                    consoleInt(env, "r.GaussianSplat.BlueNoise", 1) != 0,
                    depth,
                    stochasticFormat,
                    Math.max(0.0f, consoleFloat(env, "r.GaussianSplat.HardwareRasterization.OpacityThreshold", 0.001f)),
                    Math.max(0.0f, consoleFloat(env, "r.GaussianSplat.Shading.OpacityThreshold", 0.0f)),
                    consoleInt(env, "r.GaussianSplat.Debug.FrustumCull", 1) != 0,
                    Math.max(1.0e-4f, consoleFloat(env, "r.GaussianSplat.ExpectedDepthScale", 100.0f)),
                    Math.clamp(consoleInt(env, "r.GaussianSplat.SortQuality", 3), 0, 3),
                    Math.clamp(consoleInt(env, "r.GaussianSplat.Falloff", 1), 0, 4),
                    Math.clamp(consoleInt(env, "r.GaussianSplat.DownsampleFactor", 1), 1, 8),
                    consoleInt(env, "r.GaussianSplat.GatherShadedSplats", -1)
            );
        }
    }

    private record RasterPipelineKey(
            GpuFormat colorFormat,
            @Nullable GpuFormat secondaryFormat,
            int depthMode,
            boolean writeDepth,
            boolean topDown,
            boolean sorted,
            boolean blueNoise,
            boolean underBlend
    ) { }

    private record PostBlendKey(GpuFormat targetFormat, boolean inverseToneCurve) { }

    private record DepthPassKey(int stage, GpuFormat colorFormat) { }

    private record Quantization(
            float[] positionScale,
            float[] positionBias,
            float[] scaleScale,
            float[] scaleBias,
            float[] colorScale,
            float[] colorBias,
            float[] shScale,
            float[] shBias
    ) {
        static final Quantization UNQUANTIZED = new Quantization(
                new float[]{1, 1, 1}, new float[]{0, 0, 0},
                new float[]{1, 1, 1}, new float[]{0, 0, 0},
                new float[]{1, 1, 1, 1}, new float[]{0, 0, 0, 0},
                new float[]{1, 1, 1}, new float[]{0, 0, 0}
        );

        static Quantization from(NiagaraGaussianSplatDraw draw) {
            if (!draw.quantized() || draw.quantizationParameters() == null) return UNQUANTIZED;
            ByteBuffer src = draw.quantizationParameters().duplicate().order(ByteOrder.LITTLE_ENDIAN);
            if (src.remaining() < 26 * Float.BYTES) return UNQUANTIZED;
            return new Quantization(
                    new float[]{src.getFloat(), src.getFloat(), src.getFloat()},
                    new float[]{src.getFloat(), src.getFloat(), src.getFloat()},
                    new float[]{src.getFloat(), src.getFloat(), src.getFloat()},
                    new float[]{src.getFloat(), src.getFloat(), src.getFloat()},
                    new float[]{src.getFloat(), src.getFloat(), src.getFloat(), src.getFloat()},
                    new float[]{src.getFloat(), src.getFloat(), src.getFloat(), src.getFloat()},
                    new float[]{src.getFloat(), src.getFloat(), src.getFloat()},
                    new float[]{src.getFloat(), src.getFloat(), src.getFloat()}
            );
        }
    }

    private record PreparedProxy(
            NiagaraGaussianSplatDraw draw,
            int count,
            float[] localToWorld,
            float[] worldToLocal,
            double viewDistanceSquared
    ) { }

    private static final class PooledBuffer implements AutoCloseable {
        private final String label;
        private final int usage;
        private @Nullable GpuBuffer buffer;
        private long capacity;

        PooledBuffer(String label, int usage) {
            this.label = label;
            this.usage = usage;
        }

        GpuBufferSlice acquire(long requiredBytes) {
            long bytes = Math.max(16L, (requiredBytes + 63L) & ~63L);
            if (buffer == null || buffer.isClosed() || capacity < bytes) {
                if (buffer != null) buffer.close();
                capacity = Math.max(bytes, capacity * 2L);
                buffer = RenderSystem.getDevice().createBuffer(() -> label, usage, capacity);
            }
            return new GpuBufferSlice(buffer, 0L, requiredBytes);
        }

        @Override
        public void close() {
            if (buffer != null) {
                buffer.close();
                buffer = null;
            }
            capacity = 0L;
        }
    }

    private final NiagaraGpuPrograms programs;
    private final NiagaraGpuRadixSort radixSort;
    private final Map<RasterPipelineKey, RenderPipeline> rasterPipelines = new HashMap<>();
    private final Map<PostBlendKey, RenderPipeline> postBlendPipelines = new HashMap<>();
    private final Map<DepthPassKey, RenderPipeline> depthPipelines = new HashMap<>();

    private final PooledBuffer shadedBuffer = new PooledBuffer(
            "LumenFX.GaussianSplat.Shaded",
            ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER));
    private final PooledBuffer gatheredBuffer = new PooledBuffer(
            "LumenFX.GaussianSplat.Gathered",
            ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER));
    private final PooledBuffer splatCountBuffer = new PooledBuffer(
            "LumenFX.GaussianSplat.Count",
            ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST));
    private final PooledBuffer drawIndirectArgs = new PooledBuffer(
            "LumenFX.GaussianSplat.DrawIndirectArgs",
            ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_INDIRECT_PARAMETERS));
    private final PooledBuffer dummySortBuffer = new PooledBuffer(
            "LumenFX.GaussianSplat.DummySort",
            ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST));
    private final PooledBuffer tileCountBuffer = new PooledBuffer(
            "LumenFX.GaussianSplat.TileCount",
            ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST));
    private final PooledBuffer prefixSumBuffer = new PooledBuffer(
            "LumenFX.GaussianSplat.PrefixSum",
            ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST));
    private final PooledBuffer numSplatsPerTileBuffer = new PooledBuffer(
            "LumenFX.GaussianSplat.NumSplatsPerTile",
            ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST));
    private final PooledBuffer minDepthPerTileBuffer = new PooledBuffer(
            "LumenFX.GaussianSplat.MinDepthPerTile",
            ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST));
    private final PooledBuffer maxDepthPerTileBuffer = new PooledBuffer(
            "LumenFX.GaussianSplat.MaxDepthPerTile",
            ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST));

    private @Nullable ComputeTexture scratchColor;
    private @Nullable TextureTarget scratchSecondary;
    private @Nullable GpuFormat scratchSecondaryFormat;
    private @Nullable TextureTarget downsampledSceneDepth;
    private @Nullable TextureTarget downsampledSplatDepth;

    public NiagaraGaussianSplatRenderer(NiagaraGpuPrograms programs) {
        this.programs = Objects.requireNonNull(programs, "programs");
        this.radixSort = new NiagaraGpuRadixSort("GaussianSplat", programs);
    }

    public void draw(CommandEncoder encoder, List<NiagaraGaussianSplatDraw> draws, NiagaraGpuView gpuView,
                     RenderTarget target, NiagaraSceneInputs inputs, int frameNumber) {
        if (draws.isEmpty() || target.getColorTextureView() == null) return;
        SplatConfig config = SplatConfig.read();
        if (!config.enabled() || config.numSplatsCap() == 0) return;

        List<PreparedProxy> proxies = prepareDraws(draws, gpuView, config);
        if (proxies.isEmpty()) return;

        var device = RenderSystem.getDevice();
        boolean topDown = !(device.backend instanceof GlDevice);
        boolean zeroToOne = device.getDeviceInfo().isZZeroToOne();
        int fullWidth = target.width;
        int fullHeight = target.height;
        int dsFactor = config.downsampleFactor();
        int rasterWidth = Math.max(1, Math.ceilDiv(fullWidth, dsFactor));
        int rasterHeight = Math.max(1, Math.ceilDiv(fullHeight, dsFactor));

        float[] viewToClip22_23_32_33 = readViewToClip22_23_32_33(gpuView);
        float[] invDeviceZ = readFloat4(gpuView.value("InvDeviceZToWorldZTransform"));
        float[] falloff = computeFalloffParameters(config.falloff());
        GpuTextureView fullDepthView = target.getDepthTextureView();
        boolean hwRaster = config.hwRaster();
        int effectiveDepthMode = hwRaster && fullDepthView != null ? config.depthMode() : DEPTH_OFF;
        if (dsFactor > 1 && hwRaster && fullDepthView != null) {
            downsampledSceneDepth = ensureDownsampledDepth(downsampledSceneDepth,
                    "LumenFX.GaussianSplat.DownsampledSceneDepth", rasterWidth, rasterHeight);
            downsampledSplatDepth = ensureDownsampledDepth(downsampledSplatDepth,
                    "LumenFX.GaussianSplat.DownsampledSplatDepth", rasterWidth, rasterHeight);
            prepareDownsampledDepth(encoder, downsampledSceneDepth, fullDepthView,
                    fullWidth, fullHeight, rasterWidth, rasterHeight, dsFactor);
            if (effectiveDepthMode != DEPTH_OFF) {
                prepareDownsampledDepth(encoder, downsampledSplatDepth, fullDepthView,
                        fullWidth, fullHeight, rasterWidth, rasterHeight, dsFactor);
            }
        }

        for (PreparedProxy proxy : proxies) {
            drawProxy(encoder, proxy, gpuView, target, inputs, config, frameNumber,
                    fullWidth, fullHeight, rasterWidth, rasterHeight, dsFactor,
                    effectiveDepthMode, topDown, zeroToOne,
                    viewToClip22_23_32_33, invDeviceZ, falloff, fullDepthView);
        }
    }

    private void drawProxy(
            CommandEncoder encoder, PreparedProxy proxy, NiagaraGpuView gpuView, RenderTarget target,
            NiagaraSceneInputs inputs, SplatConfig config, int frameNumber,
            int fullWidth, int fullHeight, int rasterWidth, int rasterHeight, int dsFactor,
            int effectiveDepthMode, boolean topDown, boolean zeroToOne,
            float[] viewToClip22_23_32_33, float[] invDeviceZ, float[] falloff, @Nullable GpuTextureView fullDepthView
    ) {
        NiagaraGaussianSplatDraw draw = proxy.draw();
        int splatCount = proxy.count();
        boolean hwRaster = config.hwRaster();
        boolean gather = hwRaster && config.gatherShadedSplats() > 0;
        EffectTexture lut = draw.invertToneCurve() && draw.inverseToneCurveStrength() > 0.0f
                ? GaussianSplatLutTextures.find(draw.invertExpandGamut()) : null;
        boolean applyLut = lut != null;
        boolean usesScratch = !hwRaster || dsFactor > 1 || applyLut
                || effectiveDepthMode == DEPTH_STOCHASTIC || effectiveDepthMode == DEPTH_EXPECTED;

        var runtime = programs.runtime();
        GpuBufferSlice shadedSlice = shadedBuffer.acquire((long) splatCount * SHADED_SPLAT_BYTES);
        GpuBufferSlice countSlice = splatCountBuffer.acquire(4L);
        runtime.clearBuffer(countSlice, 0);

        int sortQuality = config.sortQuality();
        int sortBits = hwRaster ? switch (sortQuality) {
            case 1 -> 16;
            case 2 -> 20;
            case 3 -> 24;
            default -> 32;
        } : 32;
        int sortMask = sortBits >= 32 ? -1 : ((1 << sortBits) - 1);

        GpuBufferSlice sortKeySlice;
        GpuBufferSlice sortValueSlice;
        if (hwRaster) {
            radixSort.prepare(splatCount);
            sortKeySlice = new GpuBufferSlice(radixSort.inputKeys(), 0L, splatCount * 4L);
            sortValueSlice = new GpuBufferSlice(radixSort.inputIndices(), 0L, splatCount * 4L);
            runtime.clearBuffer(sortKeySlice, -1);
        } else {
            GpuBufferSlice dummy = dummySortBuffer.acquire(4L);
            sortKeySlice = dummy;
            sortValueSlice = dummy;
        }

        String shVariant = "sh" + Math.clamp(draw.degree(), 0, 3);
        NiagaraGpuComputeStage shadingStage = programs.gaussianSplat("shading", shVariant);
        ComputeBindings.Builder shadingBindings = ComputeBindings.builder()
                .storage("GaussianSplatBuffer", draw.splats(), ComputeAccess.READ_ONLY)
                .storage("ClusterBuffer", new GpuBufferSlice(programs.dummyBuffer(), 0L, 4L), ComputeAccess.READ_ONLY)
                .storage("ClusterIndexBuffer", new GpuBufferSlice(programs.dummyBuffer(), 0L, 4L), ComputeAccess.READ_ONLY)
                .storage("ClusterCountBuffer", new GpuBufferSlice(programs.dummyBuffer(), 0L, 4L), ComputeAccess.READ_ONLY)
                .storage("RWShadedGaussianSplatBuffer", shadedSlice, ComputeAccess.READ_WRITE)
                .storage("RWSplatCount", countSlice, ComputeAccess.READ_WRITE)
                .storage("RWSortKeys", sortKeySlice, ComputeAccess.READ_WRITE)
                .storage("RWSortValues", sortValueSlice, ComputeAccess.READ_WRITE);

        Quantization quant = Quantization.from(draw);
        float covarianceScale = hwRaster ? 1.0f / (dsFactor * dsFactor) : 1.0f;
        int sortFrontToBack = effectiveDepthMode == DEPTH_EXPECTED ? 1 : 0;

        dispatchWrapped1D(shadingStage, encoder, shadingBindings, name -> switch (leaf(name)) {
            case "DebugGpuDrivenPipeline" -> ints(1);
            case "DebugClustering" -> ints(0);
            case "DebugFrustumCull" -> ints(config.frustumCull() ? 1 : 0);
            case "DebugWriteSortKeys" -> ints(hwRaster ? 1 : 0);
            case "LocalToWorld" -> floats(proxy.localToWorld());
            case "WorldToLocal" -> floats(proxy.worldToLocal());
            case "NumSplats" -> ints(splatCount);
            case "BufferFormat" -> ints(draw.quantized() ? 1 : 0);
            case "BufferStride" -> ints(draw.stride());
            case "PositionScale" -> floats(quant.positionScale());
            case "PositionBias" -> floats(quant.positionBias());
            case "ScaleScale" -> floats(quant.scaleScale());
            case "ScaleBias" -> floats(quant.scaleBias());
            case "ColorScale" -> floats(quant.colorScale());
            case "ColorBias" -> floats(quant.colorBias());
            case "SHScale" -> floats(quant.shScale());
            case "SHBias" -> floats(quant.shBias());
            case "SHCoordinateSpace" -> ints(draw.coordinateSpace());
            case "PointScaleFactor" -> floats(draw.pointScaleFactor());
            case "OpacityScale" -> floats(draw.opacityScale());
            case "OpacityThreshold" -> floats(config.shadingOpacityThreshold());
            case "CovarianceScale" -> floats(covarianceScale);
            case "SortKeyFrontToBack" -> ints(sortFrontToBack);
            case "SortKeyBits" -> ints(sortBits);
            case "StatsMessageId", "StatsFrameNumber", "StatsNumClustersSubmitted",
                    "StatsNumSplatsSubmitted", "StatsHasClusterCount" -> ints(0);
            default -> viewUniform(gpuView, name, rasterWidth, rasterHeight, dsFactor, null);
        }, splatCount);

        GpuTextureView rasterColorView;
        if (usesScratch) {
            ComputeTexture colorScratch = ensureScratchColor(rasterWidth, rasterHeight);
            encoder.clearColorTexture(colorScratch.texture(), new Vector4f(0.0f, 0.0f, 0.0f, 1.0f));
            rasterColorView = colorScratch.sampledView();
        } else {
            rasterColorView = Objects.requireNonNull(target.getColorTextureView());
        }

        if (hwRaster) {
            GpuBuffer sortedBuffer = radixSort.sort(encoder, sortMask);
            GpuBufferSlice sortedSlice = new GpuBufferSlice(sortedBuffer, 0L, splatCount * 4L);

            GpuBufferSlice indirectSlice = drawIndirectArgs.acquire(DRAW_INDIRECT_BYTES);
            NiagaraGpuComputeStage indirectStage = programs.gaussianSplat("draw_indirect_args", "default");
            ComputeBindings.Builder indirectBindings = ComputeBindings.builder()
                    .storage("SplatCountBuffer", countSlice, ComputeAccess.READ_ONLY)
                    .storage("StatsClusterCountBuffer", new GpuBufferSlice(programs.dummyBuffer(), 0L, 4L), ComputeAccess.READ_ONLY)
                    .storage("RWDrawIndirectArgs", indirectSlice, ComputeAccess.READ_WRITE);
            indirectStage.dispatch(encoder, indirectBindings, name -> ints(0), 1, 1, 1);

            GpuBufferSlice vertexShadedSlice = shadedSlice;
            if (gather) {
                GpuBufferSlice gatheredSlice = gatheredBuffer.acquire((long) splatCount * SHADED_SPLAT_BYTES);
                NiagaraGpuComputeStage gatherStage = programs.gaussianSplat("gather_shaded_splats", "gpu_1");
                ComputeBindings.Builder gatherBindings = ComputeBindings.builder()
                        .storage("ShadedGaussianSplatBuffer", shadedSlice, ComputeAccess.READ_ONLY)
                        .storage("SortedIndexBuffer", sortedSlice, ComputeAccess.READ_ONLY)
                        .storage("SplatCountBuffer", countSlice, ComputeAccess.READ_ONLY)
                        .storage("RWGatheredShadedGaussianSplatVertexBuffer", new GpuBufferSlice(programs.dummyBuffer(), 0L, 4L), ComputeAccess.READ_WRITE)
                        .storage("RWGatheredShadedGaussianSplatBuffer", gatheredSlice, ComputeAccess.READ_WRITE);
                dispatchWrapped1D(gatherStage, encoder, gatherBindings, name -> switch (leaf(name)) {
                    case "NumSplats" -> ints(splatCount);
                    default -> viewUniform(gpuView, name, rasterWidth, rasterHeight, dsFactor, null);
                }, splatCount);
                vertexShadedSlice = gatheredSlice;
            }

            TextureTarget secondaryTarget = null;
            if (effectiveDepthMode == DEPTH_STOCHASTIC) {
                secondaryTarget = ensureSecondaryTarget(rasterWidth, rasterHeight, config.stochasticDepthFormat());
                encoder.clearColorTexture(Objects.requireNonNull(secondaryTarget.getColorTexture()), new Vector4f(0.0f));
            } else if (effectiveDepthMode == DEPTH_EXPECTED) {
                secondaryTarget = ensureSecondaryTarget(rasterWidth, rasterHeight, GpuFormat.RGBA16_FLOAT);
                encoder.clearColorTexture(Objects.requireNonNull(secondaryTarget.getColorTexture()), new Vector4f(0.0f, 0.0f, 0.0f, 1.0f));
            }

            TextureTarget activeDsTarget = effectiveDepthMode == DEPTH_OFF ? downsampledSceneDepth : downsampledSplatDepth;
            GpuTextureView activeDepth = dsFactor > 1 && activeDsTarget != null
                    ? activeDsTarget.getDepthTextureView() : fullDepthView;
            GpuFormat colorFormat = usesScratch ? GpuFormat.RGBA16_FLOAT : target.getColorTexture().getFormat();
            GpuFormat secondaryFormat = secondaryTarget != null ? secondaryTarget.getColorTexture().getFormat() : null;
            boolean writeHwDepth = effectiveDepthMode == DEPTH_HARDWARE && activeDepth != null;
            GpuBufferSlice rasterUniform = writeRasterViewUniform(encoder, viewToClip22_23_32_33, invDeviceZ,
                    falloff, config, rasterWidth, rasterHeight, usesScratch ? 1.0f : inputs.preExposure(),
                    frameNumber, zeroToOne);
            boolean stochastic = effectiveDepthMode == DEPTH_STOCHASTIC;
            boolean expected = effectiveDepthMode == DEPTH_EXPECTED;
            var secondaryView = secondaryTarget == null ? null : secondaryTarget.getColorTextureView();
            var colorKey = new RasterPipelineKey(colorFormat, expected ? secondaryFormat : null,
                    stochastic ? DEPTH_OFF : effectiveDepthMode, writeHwDepth, topDown, !gather, false, expected);
            rasterize(encoder, colorKey, rasterColorView, expected ? secondaryView : null, activeDepth,
                    rasterUniform, vertexShadedSlice, sortedSlice, indirectSlice, rasterWidth, rasterHeight);
            if (stochastic) {
                // Blaze3D has one blend function per pass. The depth reduction uses MAX over the same fragments.
                var depthKey = new RasterPipelineKey(Objects.requireNonNull(secondaryFormat), null,
                        DEPTH_STOCHASTIC, false, topDown, !gather, config.blueNoise(), false);
                rasterize(encoder, depthKey, Objects.requireNonNull(secondaryView), null, activeDepth,
                        rasterUniform, vertexShadedSlice, sortedSlice, indirectSlice, rasterWidth, rasterHeight);
            }

            if (effectiveDepthMode == DEPTH_STOCHASTIC && secondaryTarget != null && activeDepth != null) {
                GpuTextureView resolveColor = dsFactor > 1 && activeDsTarget != null
                        ? activeDsTarget.getColorTextureView() : target.getColorTextureView();
                resolveStochasticDepth(encoder, resolveColor, activeDepth, secondaryTarget.getColorTextureView(),
                        rasterWidth, rasterHeight);
            } else if (effectiveDepthMode == DEPTH_EXPECTED && secondaryTarget != null && activeDepth != null) {
                GpuTextureView resolveColor = dsFactor > 1 && activeDsTarget != null
                        ? activeDsTarget.getColorTextureView() : target.getColorTextureView();
                resolveExpectedDepth(encoder, resolveColor, activeDepth, secondaryTarget.getColorTextureView(),
                        viewToClip22_23_32_33, config.expectedDepthScale(), zeroToOne, rasterWidth, rasterHeight);
            }
            if (dsFactor > 1 && effectiveDepthMode != DEPTH_OFF && downsampledSplatDepth != null
                    && downsampledSceneDepth != null && fullDepthView != null) {
                upsampleDepth(encoder, target.getColorTextureView(), fullDepthView,
                        downsampledSplatDepth.getDepthTextureView(), downsampledSceneDepth.getDepthTextureView(),
                        fullWidth, fullHeight, rasterWidth, rasterHeight);
            }
        } else {
            runTiledComputeRasterize(encoder, splatCount, gpuView, config, rasterWidth, rasterHeight,
                    dsFactor, shadedSlice, falloff, Objects.requireNonNull(scratchColor));
        }

        if (usesScratch) {
            boolean flipScratchV = !hwRaster && !topDown;
            blendScratchToTarget(encoder, target, Objects.requireNonNull(scratchColor).sampledView(),
                    lut, draw.inverseToneCurveStrength(), inputs.preExposure(), flipScratchV);
        }
    }

    private void rasterize(CommandEncoder encoder, RasterPipelineKey key, GpuTextureView color,
                           @Nullable GpuTextureView secondary, @Nullable GpuTextureView depth,
                           GpuBufferSlice view, GpuBufferSlice shaded, GpuBufferSlice sorted, GpuBufferSlice indirect,
                           int width, int height) {
        RenderPipeline pipeline = rasterPipelines.computeIfAbsent(key, NiagaraGaussianSplatRenderer::createRasterPipeline);
        var descriptor = RenderPassDescriptor.create(() -> "LumenFX GaussianSplat Hardware Rasterize")
                .withColorAttachment(color)
                .withRenderArea(new RenderPass.RenderArea(0, 0, width, height));
        if (secondary != null) descriptor.withColorAttachment(secondary);
        if (depth != null) descriptor.withDepthAttachment(depth);
        try (var pass = encoder.createRenderPass(descriptor)) {
            pass.setPipeline(pipeline);
            pass.setUniform("GaussianSplatRasterView", view);
            pass.setUniform("ShadedGaussianSplatBuffer", shaded);
            if (key.sorted()) pass.setUniform("SortedIndexBuffer", sorted);
            if (key.blueNoise()) {
                pass.bindTexture("BlueNoiseScalarTexture", BlueNoiseTextures.scalarActive(),
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            }
            pass.drawIndirect(indirect, 1);
        }
    }

    private void runTiledComputeRasterize(
            CommandEncoder encoder, int splatCount, NiagaraGpuView gpuView, SplatConfig config,
            int rasterWidth, int rasterHeight, int dsFactor, GpuBufferSlice shadedSlice,
            float[] falloff, ComputeTexture colorTarget
    ) {
        int tileSize = config.tileSize();
        int tilesX = Math.ceilDiv(rasterWidth, tileSize);
        int tilesY = Math.ceilDiv(rasterHeight, tileSize);
        int numTiles = Math.max(1, Math.multiplyExact(tilesX, tilesY));
        long tileBytes = (long) numTiles * 4L;
        int maxOverlapCount = Math.multiplyExact(splatCount, config.maxSplatOverlap());
        long indexBytes = (long) maxOverlapCount * 4L;

        var runtime = programs.runtime();
        GpuBufferSlice tileCountSlice = tileCountBuffer.acquire(tileBytes);
        GpuBufferSlice minDepthSlice = minDepthPerTileBuffer.acquire(tileBytes);
        GpuBufferSlice maxDepthSlice = maxDepthPerTileBuffer.acquire(tileBytes);
        GpuBufferSlice prefixSlice = prefixSumBuffer.acquire(tileBytes);
        GpuBufferSlice numPerTileSlice = numSplatsPerTileBuffer.acquire(tileBytes);

        runtime.clearBuffer(tileCountSlice, 0);
        runtime.clearBuffer(minDepthSlice, Float.floatToRawIntBits(Float.MAX_VALUE));
        runtime.clearBuffer(maxDepthSlice, 0);
        runtime.clearBuffer(numPerTileSlice, 0);

        radixSort.prepare(maxOverlapCount);
        GpuBufferSlice sortKeySlice = new GpuBufferSlice(radixSort.inputKeys(), 0L, indexBytes);
        GpuBufferSlice unsortedIndexSlice = new GpuBufferSlice(radixSort.inputIndices(), 0L, indexBytes);
        runtime.clearBuffer(sortKeySlice, -1);
        runtime.clearBuffer(unsortedIndexSlice, -1);

        NiagaraGpuComputeStage tileCountStage = programs.gaussianSplat("tile_count", "default");
        ComputeBindings.Builder tileCountBindings = ComputeBindings.builder()
                .storage("ShadedGaussianSplatBuffer", shadedSlice, ComputeAccess.READ_ONLY)
                .storage("RWTileCountBuffer", tileCountSlice, ComputeAccess.READ_WRITE)
                .storage("RWMinDepthPerTileBuffer", minDepthSlice, ComputeAccess.READ_WRITE)
                .storage("RWMaxDepthPerTileBuffer", maxDepthSlice, ComputeAccess.READ_WRITE);
        dispatchWrapped1D(tileCountStage, encoder, tileCountBindings, name -> switch (leaf(name)) {
            case "NumSplats" -> ints(splatCount);
            case "TileSize" -> ints(tileSize, tileSize);
            case "TileDimension" -> ints(tilesX, tilesY);
            default -> viewUniform(gpuView, name, rasterWidth, rasterHeight, dsFactor, null);
        }, splatCount);

        NiagaraGpuComputeStage prefixStage = programs.gaussianSplat("prefix_sum", "default");
        ComputeBindings.Builder prefixBindings = ComputeBindings.builder()
                .storage("ItemBuffer", tileCountSlice, ComputeAccess.READ_ONLY)
                .storage("RWPrefixSumBuffer", prefixSlice, ComputeAccess.READ_WRITE);
        prefixStage.dispatch(encoder, prefixBindings, name -> switch (leaf(name)) {
            case "NumItems" -> ints(numTiles);
            default -> ints(0);
        }, 1, 1, 1);

        NiagaraGpuComputeStage fillStage = programs.gaussianSplat("fill_splat_index_buffer", "default");
        ComputeBindings.Builder fillBindings = ComputeBindings.builder()
                .storage("ShadedGaussianSplatBuffer", shadedSlice, ComputeAccess.READ_ONLY)
                .storage("SplatOffsetPerTileBuffer", prefixSlice, ComputeAccess.READ_ONLY)
                .storage("MinDepthPerTileBuffer", minDepthSlice, ComputeAccess.READ_ONLY)
                .storage("MaxDepthPerTileBuffer", maxDepthSlice, ComputeAccess.READ_ONLY)
                .storage("RWNumSplatsPerTileBuffer", numPerTileSlice, ComputeAccess.READ_WRITE)
                .storage("RWSortKeyBuffer", sortKeySlice, ComputeAccess.READ_WRITE)
                .storage("RWSplatIndexBuffer", unsortedIndexSlice, ComputeAccess.READ_WRITE);
        dispatchWrapped1D(fillStage, encoder, fillBindings, name -> switch (leaf(name)) {
            case "NumSplats" -> ints(splatCount);
            case "TileSize" -> ints(tileSize, tileSize);
            case "TileDimension" -> ints(tilesX, tilesY);
            default -> viewUniform(gpuView, name, rasterWidth, rasterHeight, dsFactor, null);
        }, splatCount);

        GpuBuffer sortedIndexBuffer = radixSort.sort(encoder, -1);
        GpuBufferSlice sortedIndexSlice = new GpuBufferSlice(sortedIndexBuffer, 0L, indexBytes);

        NiagaraGpuComputeStage tileRasterStage = programs.gaussianSplat("tile_rasterize", "default");
        ComputeBindings.Builder rasterBindings = ComputeBindings.builder()
                .storage("ShadedGaussianSplatBuffer", shadedSlice, ComputeAccess.READ_ONLY)
                .storage("NumSplatsPerTileBuffer", numPerTileSlice, ComputeAccess.READ_ONLY)
                .storage("SplatOffsetPerTileBuffer", prefixSlice, ComputeAccess.READ_ONLY)
                .storage("SplatIndexBuffer", sortedIndexSlice, ComputeAccess.READ_ONLY)
                .image("RWColorTexture", colorTarget, storageImageAccess(tileRasterStage, "RWColorTexture"), 0, 0, 1);
        tileRasterStage.dispatch(encoder, rasterBindings, name -> switch (leaf(name)) {
            case "NumSplats" -> ints(splatCount);
            case "TileSize" -> ints(tileSize, tileSize);
            case "TileDimension" -> ints(tilesX, tilesY);
            case "FalloffParameters" -> floats(falloff[0], falloff[1], falloff[2]);
            default -> viewUniform(gpuView, name, rasterWidth, rasterHeight, dsFactor, 1.0f);
        }, rasterWidth, rasterHeight, 1);
    }

    private static ComputeAccess storageImageAccess(NiagaraGpuComputeStage stage, String name) {
        for (ShaderBinding binding : stage.shader().bindings()) {
            if (binding instanceof ShaderBinding.StorageImage image && image.name().equals(name)) {
                return image.access();
            }
        }
        return ComputeAccess.READ_WRITE;
    }

    private void blendScratchToTarget(
            CommandEncoder encoder, RenderTarget target, GpuTextureView scratchView,
            @Nullable EffectTexture lut, float inverseToneCurveStrength, float preExposure, boolean flipScratchV
    ) {
        GpuFormat targetFormat = target.getColorTexture().getFormat();
        boolean applyLut = lut != null;
        RenderPipeline pipeline = postBlendPipelines.computeIfAbsent(
                new PostBlendKey(targetFormat, applyLut),
                NiagaraGaussianSplatRenderer::createPostBlendPipeline);

        float lutEdge = lut != null ? lut.rasterView().texture().getWidth(0) : 32.0f;
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        GpuBufferSlice uniform;
        try (var mapped = encoder.transientMemory().allocateGpuMapped(POST_BLEND_BYTES, alignment, GpuBuffer.USAGE_UNIFORM)) {
            ByteBuffer data = mapped.data().order(ByteOrder.nativeOrder());
            data.putFloat(applyLut ? inverseToneCurveStrength : 0.0f)
                    .putFloat(lutEdge).putFloat(preExposure).putFloat(0.0f);
            data.putFloat(1.0f).putFloat(flipScratchV ? -1.0f : 1.0f)
                    .putFloat(0.0f).putFloat(flipScratchV ? 1.0f : 0.0f);
            uniform = mapped.slice();
        }

        var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        var descriptor = RenderPassDescriptor.create(() -> "LumenFX GaussianSplat PostBlend")
                .withColorAttachment(target.getColorTextureView())
                .withRenderArea(new RenderPass.RenderArea(0, 0, target.width, target.height));
        try (var pass = encoder.createRenderPass(descriptor)) {
            pass.setPipeline(pipeline);
            pass.setUniform("GaussianSplatPostBlend", uniform);
            pass.bindTexture("SplatColor", scratchView, linear);
            if (lut != null) pass.bindTexture("InverseToneCurveLUT", lut.rasterView(), lut.sampler());
            pass.draw(3, 1, 0, 0);
        }
    }

    private void prepareDownsampledDepth(
            CommandEncoder encoder, TextureTarget dsTarget, GpuTextureView fullDepth,
            int fullW, int fullH, int dsW, int dsH, int factor
    ) {
        encoder.clearDepthTexture(Objects.requireNonNull(dsTarget.getDepthTexture()), 0.0);
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        GpuBufferSlice uniform;
        try (var mapped = encoder.transientMemory().allocateGpuMapped(DOWNSAMPLE_BYTES, alignment, GpuBuffer.USAGE_UNIFORM)) {
            mapped.data().order(ByteOrder.nativeOrder())
                    .putInt(factor).putInt(fullW - 1).putInt(fullH - 1).putInt(0);
            uniform = mapped.slice();
        }
        GpuTextureView colorView = Objects.requireNonNull(dsTarget.getColorTextureView());
        RenderPipeline pipeline = depthPipelines.computeIfAbsent(
                new DepthPassKey(0, GpuFormat.R8_UNORM),
                NiagaraGaussianSplatRenderer::createDepthPipeline);
        var descriptor = RenderPassDescriptor.create(() -> "LumenFX GaussianSplat DownsampleDepth")
                .withColorAttachment(colorView)
                .withDepthAttachment(Objects.requireNonNull(dsTarget.getDepthTextureView()))
                .withRenderArea(new RenderPass.RenderArea(0, 0, dsW, dsH));
        try (var pass = encoder.createRenderPass(descriptor)) {
            pass.setPipeline(pipeline);
            pass.setUniform("GaussianSplatDownsample", uniform);
            pass.bindTexture("SceneDepthTexture", fullDepth,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(3, 1, 0, 0);
        }
    }

    private void resolveStochasticDepth(
            CommandEncoder encoder, GpuTextureView colorDummy, GpuTextureView depthTarget,
            @Nullable GpuTextureView stochasticTexture, int width, int height
    ) {
        if (stochasticTexture == null) return;
        RenderPipeline pipeline = depthPipelines.computeIfAbsent(
                new DepthPassKey(1, colorDummy.texture().getFormat()),
                NiagaraGaussianSplatRenderer::createDepthPipeline);
        var descriptor = RenderPassDescriptor.create(() -> "LumenFX GaussianSplat ResolveDepth")
                .withColorAttachment(colorDummy)
                .withDepthAttachment(depthTarget)
                .withRenderArea(new RenderPass.RenderArea(0, 0, width, height));
        try (var pass = encoder.createRenderPass(descriptor)) {
            pass.setPipeline(pipeline);
            pass.bindTexture("StochasticDepthTexture", stochasticTexture,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(3, 1, 0, 0);
        }
    }

    private void resolveExpectedDepth(
            CommandEncoder encoder, GpuTextureView colorDummy, GpuTextureView depthTarget,
            @Nullable GpuTextureView momentsTexture, float[] viewToClip22_23_32_33,
            float expectedDepthScale, boolean zeroToOne, int width, int height
    ) {
        if (momentsTexture == null) return;
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        GpuBufferSlice uniform;
        try (var mapped = encoder.transientMemory().allocateGpuMapped(EXPECTED_DEPTH_BYTES, alignment, GpuBuffer.USAGE_UNIFORM)) {
            ByteBuffer data = mapped.data().order(ByteOrder.nativeOrder());
            data.putFloat(viewToClip22_23_32_33[0]).putFloat(viewToClip22_23_32_33[1])
                    .putFloat(viewToClip22_23_32_33[2]).putFloat(viewToClip22_23_32_33[3]);
            data.putFloat(expectedDepthScale).putFloat(zeroToOne ? 1.0f : 0.0f).putFloat(0.0f).putFloat(0.0f);
            uniform = mapped.slice();
        }
        RenderPipeline pipeline = depthPipelines.computeIfAbsent(
                new DepthPassKey(2, colorDummy.texture().getFormat()),
                NiagaraGaussianSplatRenderer::createDepthPipeline);
        var descriptor = RenderPassDescriptor.create(() -> "LumenFX GaussianSplat ExpectedDepth")
                .withColorAttachment(colorDummy)
                .withDepthAttachment(depthTarget)
                .withRenderArea(new RenderPass.RenderArea(0, 0, width, height));
        try (var pass = encoder.createRenderPass(descriptor)) {
            pass.setPipeline(pipeline);
            pass.setUniform("GaussianSplatExpectedDepth", uniform);
            pass.bindTexture("DepthMomentsTexture", momentsTexture,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(3, 1, 0, 0);
        }
    }

    private void upsampleDepth(
            CommandEncoder encoder, GpuTextureView colorDummy, GpuTextureView fullDepth,
            @Nullable GpuTextureView dsSplatDepth, @Nullable GpuTextureView dsSceneDepth,
            int fullW, int fullH, int dsW, int dsH
    ) {
        if (dsSplatDepth == null || dsSceneDepth == null) return;
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        GpuBufferSlice uniform;
        try (var mapped = encoder.transientMemory().allocateGpuMapped(UPSAMPLE_BYTES, alignment, GpuBuffer.USAGE_UNIFORM)) {
            mapped.data().order(ByteOrder.nativeOrder())
                    .putFloat((float) dsW / fullW).putFloat((float) dsH / fullH)
                    .putFloat((float) (dsW - 1)).putFloat((float) (dsH - 1));
            uniform = mapped.slice();
        }
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        RenderPipeline pipeline = depthPipelines.computeIfAbsent(
                new DepthPassKey(3, colorDummy.texture().getFormat()),
                NiagaraGaussianSplatRenderer::createDepthPipeline);
        var descriptor = RenderPassDescriptor.create(() -> "LumenFX GaussianSplat UpsampleDepth")
                .withColorAttachment(colorDummy)
                .withDepthAttachment(fullDepth)
                .withRenderArea(new RenderPass.RenderArea(0, 0, fullW, fullH));
        try (var pass = encoder.createRenderPass(descriptor)) {
            pass.setPipeline(pipeline);
            pass.setUniform("GaussianSplatUpsample", uniform);
            pass.bindTexture("SplatDepthTexture", dsSplatDepth, nearest);
            pass.bindTexture("DownsampledSceneDepthTexture", dsSceneDepth, nearest);
            pass.draw(3, 1, 0, 0);
        }
    }

    private static GpuBufferSlice writeRasterViewUniform(
            CommandEncoder encoder, float[] viewToClip22_23_32_33, float[] invDeviceZ,
            float[] falloff, SplatConfig config, int width, int height, float preExposure,
            int frameNumber, boolean zeroToOne
    ) {
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        try (var mapped = encoder.transientMemory().allocateGpuMapped(RASTER_VIEW_BYTES, alignment, GpuBuffer.USAGE_UNIFORM)) {
            ByteBuffer data = mapped.data().order(ByteOrder.nativeOrder());
            data.putFloat(viewToClip22_23_32_33[0]).putFloat(viewToClip22_23_32_33[1])
                    .putFloat(viewToClip22_23_32_33[2]).putFloat(viewToClip22_23_32_33[3]);
            data.putFloat(1.0f / width).putFloat(1.0f / height).putFloat(width).putFloat(height);
            data.putFloat(invDeviceZ[0]).putFloat(invDeviceZ[1]).putFloat(invDeviceZ[2]).putFloat(invDeviceZ[3]);
            data.putFloat(falloff[0]).putFloat(falloff[1]).putFloat(falloff[2]).putFloat(config.hwOpacityThreshold());
            data.putFloat(config.expectedDepthScale()).putFloat(preExposure).putFloat(0.0f).putFloat(0.0f);
            data.putInt(0).putInt(frameNumber).putInt(1).putInt(zeroToOne ? 1 : 0);
            return mapped.slice();
        }
    }

    private List<PreparedProxy> prepareDraws(List<NiagaraGaussianSplatDraw> draws, NiagaraGpuView gpuView, SplatConfig config) {
        NiagaraCameraFrame camera = gpuView.camera(false);
        Vector3d viewTile = gpuView.tileOriginCm();
        Matrix4f translatedWorldToClip = readMatrix4f(gpuView.value("TranslatedWorldToClip"));
        boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        List<PreparedProxy> prepared = new ArrayList<>(draws.size());
        Matrix4d localToWorld = new Matrix4d();
        Matrix4d worldToLocal = new Matrix4d();
        Vector3d center = new Vector3d();
        float[] l2w = new float[16];
        float[] w2l = new float[16];

        for (NiagaraGaussianSplatDraw draw : draws) {
            int count = config.numSplatsCap() >= 0 ? Math.min(draw.count(), config.numSplatsCap()) : draw.count();
            if (count <= 0) continue;

            if (draw.localSpace()) {
                NiagaraComponentMatrices.source(draw.pose(), camera.x(), camera.y(), camera.z(), true, localToWorld);
            } else {
                Vec3 tile = draw.tileOrigin();
                localToWorld.identity()
                        .m30(tile.x * 100.0 - camera.x())
                        .m31(tile.z * 100.0 - camera.y())
                        .m32(tile.y * 100.0 - camera.z());
            }

            NiagaraBounds bounds = draw.localBounds();
            if (bounds != null && config.frustumCull() && isBoundsCulled(bounds, localToWorld, translatedWorldToClip, zeroToOne)) {
                continue;
            }

            if (bounds != null) {
                center.set((bounds.minX() + bounds.maxX()) * 0.5,
                        (bounds.minY() + bounds.maxY()) * 0.5,
                        (bounds.minZ() + bounds.maxZ()) * 0.5);
                transformRowPoint(center, localToWorld, center);
            } else {
                center.set(localToWorld.m30(), localToWorld.m31(), localToWorld.m32());
            }

            double distSq = center.lengthSquared();
            // Culling above needs camera-relative data. The Niagara shader takes view-tile-relative positions,
            // then applies the camera translation itself, including for splats from another simulation tile.
            localToWorld.m30(localToWorld.m30() + camera.x() - viewTile.x)
                    .m31(localToWorld.m31() + camera.y() - viewTile.y)
                    .m32(localToWorld.m32() + camera.z() - viewTile.z);
            NiagaraComponentMatrices.inverse(localToWorld, worldToLocal);
            new Matrix4f(localToWorld).get(l2w);
            new Matrix4f(worldToLocal).get(w2l);
            prepared.add(new PreparedProxy(draw, count, l2w.clone(), w2l.clone(), distSq));
        }

        if (prepared.size() > 1) {
            Comparator<PreparedProxy> order = config.depthMode() == DEPTH_EXPECTED
                    ? Comparator.comparingDouble(PreparedProxy::viewDistanceSquared)
                    : Comparator.comparingDouble(PreparedProxy::viewDistanceSquared).reversed();
            prepared.sort(order);
        }
        return prepared;
    }

    private static boolean isBoundsCulled(
            NiagaraBounds bounds, Matrix4d localToTranslatedWorld, Matrix4f translatedWorldToClip, boolean zeroToOne
    ) {
        Vector3d local = new Vector3d();
        Vector4d world = new Vector4d();
        Vector4f clip = new Vector4f();
        int allLeft = 1, allRight = 1, allBottom = 1, allTop = 1, allFar = 1, allNear = 1;
        for (int corner = 0; corner < 8; corner++) {
            local.set((corner & 1) == 0 ? bounds.minX() : bounds.maxX(),
                    (corner & 2) == 0 ? bounds.minY() : bounds.maxY(),
                    (corner & 4) == 0 ? bounds.minZ() : bounds.maxZ());
            transformRowPoint(local, localToTranslatedWorld, local);
            world.set(local.x, local.y, local.z, 1.0);
            mulRowVector(world, translatedWorldToClip, clip);
            float w = clip.w;
            if (clip.x >= -w) allLeft = 0;
            if (clip.x <= w) allRight = 0;
            if (clip.y >= -w) allBottom = 0;
            if (clip.y <= w) allTop = 0;
            if (clip.z >= (zeroToOne ? 0.0f : -w)) allFar = 0;
            if (clip.z <= w) allNear = 0;
        }
        return (allLeft | allRight | allBottom | allTop | allFar | allNear) != 0;
    }

    private static void transformRowPoint(Vector3d src, Matrix4d rowMatrix, Vector3d dst) {
        double x = src.x, y = src.y, z = src.z;
        dst.set(x * rowMatrix.m00() + y * rowMatrix.m10() + z * rowMatrix.m20() + rowMatrix.m30(),
                x * rowMatrix.m01() + y * rowMatrix.m11() + z * rowMatrix.m21() + rowMatrix.m31(),
                x * rowMatrix.m02() + y * rowMatrix.m12() + z * rowMatrix.m22() + rowMatrix.m32());
    }

    private static void mulRowVector(Vector4d v, Matrix4f m, Vector4f out) {
        float x = (float) v.x, y = (float) v.y, z = (float) v.z, w = (float) v.w;
        out.set(x * m.m00() + y * m.m01() + z * m.m02() + w * m.m03(),
                x * m.m10() + y * m.m11() + z * m.m12() + w * m.m13(),
                x * m.m20() + y * m.m21() + z * m.m22() + w * m.m23(),
                x * m.m30() + y * m.m31() + z * m.m32() + w * m.m33());
    }

    private void dispatchWrapped1D(
            NiagaraGpuComputeStage stage, CommandEncoder encoder, ComputeBindings.Builder bindings,
            java.util.function.Function<String, byte[]> values, int count
    ) {
        var runtime = programs.runtime();
        int groupsX = Math.ceilDiv(Math.max(1, count), THREADS_1D);
        int groupsY = 1, groupsZ = 1;
        if (groupsX > runtime.maxWorkGroups(0)) {
            groupsY = Math.ceilDiv(groupsX, WRAPPED_STRIDE);
            groupsX = WRAPPED_STRIDE;
        }
        if (groupsY > runtime.maxWorkGroups(1)) {
            groupsZ = Math.ceilDiv(groupsY, WRAPPED_STRIDE);
            groupsY = WRAPPED_STRIDE;
        }
        if (groupsZ > runtime.maxWorkGroups(2)) {
            throw new IllegalArgumentException("GaussianSplat compute dispatch exceeds device limit");
        }
        stage.dispatch(encoder, bindings, values, Math.multiplyExact(groupsX, THREADS_1D), groupsY, groupsZ);
    }

    private ComputeTexture ensureScratchColor(int width, int height) {
        if (scratchColor != null && !scratchColor.isClosed()
                && scratchColor.width() == width && scratchColor.height() == height) {
            return scratchColor;
        }
        if (scratchColor != null) scratchColor.close();
        scratchColor = programs.runtime().createColorTarget2D(
                "LumenFX.GaussianSplat.ScratchColor", GpuFormat.RGBA16_FLOAT, width, height, 1, false);
        return scratchColor;
    }

    private TextureTarget ensureSecondaryTarget(int width, int height, GpuFormat format) {
        if (scratchSecondary != null && scratchSecondary.width == width && scratchSecondary.height == height
                && scratchSecondaryFormat == format) {
            return scratchSecondary;
        }
        if (scratchSecondary != null) scratchSecondary.destroyBuffers();
        scratchSecondary = new TextureTarget("LumenFX.GaussianSplat.Secondary", width, height, false, format);
        scratchSecondaryFormat = format;
        return scratchSecondary;
    }

    private static TextureTarget ensureDownsampledDepth(
            @Nullable TextureTarget current, String label, int width, int height
    ) {
        if (current != null && current.width == width && current.height == height) {
            return current;
        }
        if (current != null) current.destroyBuffers();
        return new TextureTarget(label, width, height, true, GpuFormat.R8_UNORM);
    }

    private static RenderPipeline createRasterPipeline(RasterPipelineKey key) {
        var layout = BindGroupLayout.builder()
                .withUniform("GaussianSplatRasterView", UniformType.UNIFORM_BUFFER)
                .withUniform("ShadedGaussianSplatBuffer", UniformType.TEXEL_BUFFER, GpuFormat.R32_UINT);
        if (key.sorted()) layout.withUniform("SortedIndexBuffer", UniformType.TEXEL_BUFFER, GpuFormat.R32_UINT);
        if (key.blueNoise()) layout.withSampler("BlueNoiseScalarTexture");

        BlendFunction colorBlend = key.depthMode() == DEPTH_STOCHASTIC ? STOCHASTIC_MAX_BLEND
                : key.underBlend() ? HW_UNDER_BLEND : HW_OVER_BLEND;
        var builder = RenderPipeline.builder()
                .withLocation(LumenFx.id("pipeline/niagara/splat/hw_" + PIPELINE_SEQ.getAndIncrement()))
                .withVertexShader(LumenFx.id("core/niagara/splat/hardware_rasterize"))
                .withFragmentShader(LumenFx.id("core/niagara/splat/hardware_rasterize"))
                .withBindGroupLayout(layout.build())
                .withColorTargetState(0, new ColorTargetState(Optional.of(colorBlend), key.colorFormat(), ColorTargetState.WRITE_ALL))
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, key.writeDepth()))
                .withCull(false)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLE_STRIP)
                .withShaderDefine("LFX_SPLAT_SORTED", key.sorted() ? 1 : 0)
                .withShaderDefine("LFX_SPLAT_TEXTURE_Y_DOWN", key.topDown() ? 1 : 0)
                .withShaderDefine("LFX_SUPERSAMPLE", 0)
                .withShaderDefine("LFX_WRITE_STOCHASTIC_DEPTH", key.depthMode() == DEPTH_STOCHASTIC ? 1 : 0)
                .withShaderDefine("LFX_WRITE_EXPECTED_DEPTH", key.depthMode() == DEPTH_EXPECTED ? 1 : 0)
                .withShaderDefine("LFX_USE_BLUE_NOISE", key.blueNoise() ? 1 : 0);

        if (key.secondaryFormat() != null) {
            builder.withColorTargetState(1, new ColorTargetState(
                    Optional.of(colorBlend), key.secondaryFormat(), ColorTargetState.WRITE_ALL));
        }
        return builder.build();
    }

    private static RenderPipeline createPostBlendPipeline(PostBlendKey key) {
        var layout = BindGroupLayout.builder()
                .withUniform("GaussianSplatPostBlend", UniformType.UNIFORM_BUFFER)
                .withSampler("SplatColor");
        if (key.inverseToneCurve()) layout.withSampler("InverseToneCurveLUT");
        return RenderPipeline.builder()
                .withLocation(LumenFx.id("pipeline/niagara/splat/post_blend_" + PIPELINE_SEQ.getAndIncrement()))
                .withVertexShader(LumenFx.id("post/fullscreen"))
                .withFragmentShader(LumenFx.id("post/niagara/splat/post_blend"))
                .withBindGroupLayout(layout.build())
                .withColorTargetState(new ColorTargetState(Optional.of(POST_BLEND), key.targetFormat(), ColorTargetState.WRITE_ALL))
                .withDepthStencilState(Optional.empty())
                .withCull(false)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withShaderDefine("LFX_APPLY_INVERSE_TONECURVE", key.inverseToneCurve() ? 1 : 0)
                .build();
    }

    private static RenderPipeline createDepthPipeline(DepthPassKey key) {
        var layout = BindGroupLayout.builder();
        String fragment;
        CompareOp compare = CompareOp.GREATER_THAN_OR_EQUAL;
        switch (key.stage()) {
            case 0 -> {
                fragment = "post/niagara/splat/downsample_depth";
                compare = CompareOp.ALWAYS_PASS;
                layout.withUniform("GaussianSplatDownsample", UniformType.UNIFORM_BUFFER)
                        .withSampler("SceneDepthTexture");
            }
            case 1 -> {
                fragment = "post/niagara/splat/resolve_depth";
                layout.withSampler("StochasticDepthTexture");
            }
            case 2 -> {
                fragment = "post/niagara/splat/expected_depth";
                layout.withUniform("GaussianSplatExpectedDepth", UniformType.UNIFORM_BUFFER)
                        .withSampler("DepthMomentsTexture");
            }
            default -> {
                fragment = "post/niagara/splat/upsample_depth";
                layout.withUniform("GaussianSplatUpsample", UniformType.UNIFORM_BUFFER)
                        .withSampler("SplatDepthTexture")
                        .withSampler("DownsampledSceneDepthTexture");
            }
        }
        return RenderPipeline.builder()
                .withLocation(LumenFx.id("pipeline/niagara/splat/depth_" + PIPELINE_SEQ.getAndIncrement()))
                .withVertexShader(LumenFx.id("post/fullscreen"))
                .withFragmentShader(LumenFx.id(fragment))
                .withBindGroupLayout(layout.build())
                .withColorTargetState(new ColorTargetState(Optional.empty(), key.colorFormat(), 0))
                .withDepthStencilState(new DepthStencilState(compare, true))
                .withCull(false)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .build();
    }

    private static byte[] viewUniform(
            NiagaraGpuView gpuView, String qualified, int width, int height, int dsFactor, @Nullable Float preExposureOverride
    ) {
        String field = leaf(qualified);
        if (field.startsWith("View_")) field = field.substring("View_".length());
        if (preExposureOverride != null) {
            if ("PreExposure".equals(field)) return floats(preExposureOverride);
            if ("OneOverPreExposure".equals(field)) return floats(1.0f / preExposureOverride);
        }
        if (dsFactor > 1) {
            if ("ViewSizeAndInvSize".equals(field) || "BufferSizeAndInvSize".equals(field)) {
                return floats(width, height, 1.0f / width, 1.0f / height);
            }
            if ("ViewRectMinAndSize".equals(field)) return ints(0, 0, width, height);
            if ("BufferBilinearUVMinMax".equals(field)) {
                return floats(0.5f / width, 0.5f / height, 1.0f - 0.5f / width, 1.0f - 0.5f / height);
            }
        }
        switch (field) {
            case "TranslatedWorldToClip", "TranslatedWorldToView", "ViewToClip", "ClipToView" -> {
                // DXC lowers the UE row-vector multiplication to matrix times column vector.
                return floats(readMatrix4f(gpuView.value(qualified)).transpose().get(new float[16]));
            }
            default -> { }
        }
        return gpuView.value(qualified);
    }

    private static float[] computeFalloffParameters(int falloff) {
        float r2 = GAUSSIAN_RADIUS_SQUARED;
        float e = (float) Math.exp(-r2);
        return switch (falloff) {
            case 1 -> {
                float scale = 1.0f / (1.0f - e);
                yield new float[]{scale, 0.0f, -e * scale};
            }
            case 2 -> {
                float scale = 1.0f / (1.0f - e * (1.0f + r2));
                yield new float[]{scale, 0.0f, -e * scale};
            }
            case 3 -> {
                float scale = 1.0f / (1.0f - e * (1.0f + r2));
                yield new float[]{scale, e * scale, -e * (1.0f + r2) * scale};
            }
            case 4 -> {
                float integral = (1.0f - e) + 0.5f * e * r2 * r2 - e * (1.0f + r2) * r2;
                float scale = 1.0f / integral;
                yield new float[]{scale, e * scale, -e * (1.0f + r2) * scale};
            }
            default -> new float[]{1.0f, 0.0f, 0.0f};
        };
    }

    private static float[] readViewToClip22_23_32_33(NiagaraGpuView gpuView) {
        ByteBuffer buf = ByteBuffer.wrap(Objects.requireNonNull(gpuView.value("ViewToClip")))
                .order(ByteOrder.LITTLE_ENDIAN);
        float[] words = new float[16];
        for (int i = 0; i < 16; i++) words[i] = buf.getFloat();
        return new float[]{words[10], words[14], words[11], words[15]};
    }

    private static Matrix4f readMatrix4f(@Nullable byte[] bytes) {
        if (bytes == null || bytes.length < 64) return new Matrix4f();
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] words = new float[16];
        for (int i = 0; i < 16; i++) words[i] = buf.getFloat();
        return new Matrix4f().set(words);
    }

    private static float[] readFloat4(@Nullable byte[] bytes) {
        if (bytes == null || bytes.length < 16) return new float[4];
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        return new float[]{buf.getFloat(), buf.getFloat(), buf.getFloat(), buf.getFloat()};
    }

    private static String leaf(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot >= 0 ? qualified.substring(dot + 1) : qualified;
    }

    private static int consoleInt(NiagaraPlatformEnvironment env, String name, int fallback) {
        var value = env.console(name);
        return value == null ? fallback : value.bits();
    }

    private static float consoleFloat(NiagaraPlatformEnvironment env, String name, float fallback) {
        var value = env.console(name);
        return value == null ? fallback : Float.intBitsToFloat(value.bits());
    }

    private static byte[] ints(int... values) {
        ByteBuffer buf = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : values) buf.putInt(v);
        return buf.array();
    }

    private static byte[] floats(float... values) {
        ByteBuffer buf = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : values) buf.putFloat(v);
        return buf.array();
    }

    @Override
    public void close() {
        rasterPipelines.clear();
        postBlendPipelines.clear();
        depthPipelines.clear();
        if (scratchColor != null) {
            scratchColor.close();
            scratchColor = null;
        }
        if (scratchSecondary != null) {
            scratchSecondary.destroyBuffers();
            scratchSecondary = null;
            scratchSecondaryFormat = null;
        }
        if (downsampledSceneDepth != null) {
            downsampledSceneDepth.destroyBuffers();
            downsampledSceneDepth = null;
        }
        if (downsampledSplatDepth != null) {
            downsampledSplatDepth.destroyBuffers();
            downsampledSplatDepth = null;
        }
        shadedBuffer.close();
        gatheredBuffer.close();
        splatCountBuffer.close();
        drawIndirectArgs.close();
        dummySortBuffer.close();
        tileCountBuffer.close();
        prefixSumBuffer.close();
        numSplatsPerTileBuffer.close();
        minDepthPerTileBuffer.close();
        maxDepthPerTileBuffer.close();
        radixSort.close();
    }
}
