package com.kltyton.lumenfx.client.graphics;

import com.lowdragmc.photon.client.render.PhotonShaderSources;
import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

/**
 * Central MC 26.3 RenderPearl compatibility helper shared by NeoForge 26.3 and Fabric 26.3.
 * Bridges explicit {@link CompiledRenderPipeline} handles, custom {@link ShaderSource} compilation,
 * {@link RenderPassDescriptor} creation, and MC 26.3 {@link TextureTarget}/{@link ShaderManager} APIs.
 */
public final class RenderPearlCompat {
    public static final BindGroupLayout MATRICES_PROJECTION = BindGroupLayout.builder()
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", UniformType.UNIFORM_BUFFER)
            .build();
    public record CompiledResult(@Nullable CompiledRenderPipeline pipeline) {
        public boolean isValid() {
            return pipeline != null && !pipeline.isClosed();
        }
    }

    private static final Map<RenderPipeline, CompiledRenderPipeline> CUSTOM_PIPELINES = new IdentityHashMap<>();
    private static final Map<CompiledRenderPipeline, RenderPipeline> COMPILED_TO_SOURCE =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<BackendRenderPipeline.CreateInfo, RenderPipeline> CREATE_INFO_PIPELINES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private RenderPearlCompat() { }

    public static void rememberSource(BackendRenderPipeline.CreateInfo info, RenderPipeline pipeline) {
        CREATE_INFO_PIPELINES.put(info, pipeline);
    }

    public static @Nullable RenderPipeline getSource(BackendRenderPipeline.CreateInfo info) {
        return CREATE_INFO_PIPELINES.get(info);
    }

    public static CompiledResult precompilePipeline(GpuDevice device, RenderPipeline pipeline) {
        RenderSystem.assertOnRenderThread();
        CompiledRenderPipeline custom = CUSTOM_PIPELINES.get(pipeline);
        if (custom != null && !custom.isClosed()) {
            return new CompiledResult(custom);
        }
        try {
            CompiledRenderPipeline compiled = RenderSystem.getCompiledPipelineNullable(pipeline);
            if (compiled != null && !compiled.isClosed()) {
                COMPILED_TO_SOURCE.put(compiled, pipeline);
                return new CompiledResult(compiled);
            }
        } catch (RuntimeException ignored) {
            // Fall through to explicit compile below if cache lookup failed before pack reload finished.
        }
        ShaderSource active = activeShaderSource();
        if (active == null) {
            return new CompiledResult(null);
        }
        return precompilePipeline(device, pipeline, active);
    }

    public static CompiledResult precompilePipeline(GpuDevice device, RenderPipeline pipeline, LumenShaderSource source) {
        return precompilePipeline(device, pipeline, (ShaderSource) source);
    }

    public static CompiledResult precompilePipeline(GpuDevice device, RenderPipeline pipeline, ShaderSource source) {
        RenderSystem.assertOnRenderThread();
        CompiledRenderPipeline existing = CUSTOM_PIPELINES.get(pipeline);
        if (existing != null && !existing.isClosed()) {
            return new CompiledResult(existing);
        }
        try {
            var pending = device.compilePipeline(pipeline, source, Runnable::run).join();
            if (pending == null || pending == CompiledRenderPipeline.Pending.NULL) {
                return new CompiledResult(null);
            }
            CompiledRenderPipeline compiled = pending.finishCompile();
            if (compiled == null || compiled.isClosed()) {
                return new CompiledResult(null);
            }
            CUSTOM_PIPELINES.put(pipeline, compiled);
            COMPILED_TO_SOURCE.put(compiled, pipeline);
            return new CompiledResult(compiled);
        } catch (RuntimeException error) {
            return new CompiledResult(null);
        }
    }

    public static CompiledRenderPipeline resolvePipeline(RenderPipeline pipeline) {
        CompiledRenderPipeline custom = CUSTOM_PIPELINES.get(pipeline);
        if (custom != null && !custom.isClosed()) {
            return custom;
        }
        CompiledRenderPipeline compiled = RenderSystem.getCompiledPipeline(pipeline);
        COMPILED_TO_SOURCE.put(compiled, pipeline);
        return compiled;
    }

    public static @Nullable RenderPipeline sourceOf(@Nullable CompiledRenderPipeline compiled) {
        if (compiled == null) return null;
        RenderPipeline known = COMPILED_TO_SOURCE.get(compiled);
        if (known != null) return known;
        PipelineCache cache = RenderSystem.currentPipelineCache;
        if (cache != null) {
            for (var entry : cache.cache.entrySet()) {
                if (entry.getValue() == compiled) {
                    COMPILED_TO_SOURCE.put(compiled, entry.getKey());
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    public static void setPipeline(RenderPass pass, RenderPipeline pipeline) {
        pass.setPipeline(resolvePipeline(pipeline));
    }

    public static void setPipeline(RenderPass pass, CompiledRenderPipeline pipeline) {
        pass.setPipeline(pipeline);
    }

    public static void releasePipeline(RenderPipeline pipeline) {
        RenderSystem.assertOnRenderThread();
        CompiledRenderPipeline custom = CUSTOM_PIPELINES.remove(pipeline);
        if (custom != null) {
            COMPILED_TO_SOURCE.remove(custom);
            if (!custom.isClosed()) {
                custom.close();
            }
        }
        PipelineCache cache = RenderSystem.currentPipelineCache;
        if (cache != null) {
            CompiledRenderPipeline cached = cache.cache.remove(pipeline);
            if (cached != null) {
                COMPILED_TO_SOURCE.remove(cached);
                if (!cached.isClosed()) {
                    cached.close();
                }
            }
        }
    }

    public static RenderPass createRenderPass(CommandEncoder encoder, LumenRenderPassDescriptor descriptor) {
        return encoder.createRenderPass(descriptor.toRenderPearl());
    }

    public static RenderPass createRenderPass(CommandEncoder encoder, RenderPassDescriptor descriptor) {
        return encoder.createRenderPass(descriptor);
    }

    public static RenderPass createRenderPass(CommandEncoder encoder, Supplier<String> label,
                                              GpuTextureView color, Optional<Vector4fc> clearColor) {
        return encoder.createRenderPass(label, color, clearColor);
    }

    public static RenderPass createRenderPass(CommandEncoder encoder, Supplier<String> label,
                                              GpuTextureView color, Optional<Vector4fc> clearColor,
                                              @Nullable GpuTextureView depth, OptionalDouble clearDepth) {
        return encoder.createRenderPass(label, color, clearColor, depth, clearDepth);
    }

    public static RenderPass createRenderPass(CommandEncoder encoder, Supplier<String> label,
                                              GpuTextureView color, Optional<Vector4fc> clearColor,
                                              @Nullable GpuTextureView depth, OptionalDouble clearDepth,
                                              RenderPass.RenderArea area) {
        return encoder.createRenderPass(label, color, clearColor, depth, clearDepth, area);
    }

    public static TextureTarget textureTarget(@Nullable String label, int width, int height,
                                              boolean useDepth, GpuFormat colorFormat) {
        return new TextureTarget(label, width, height, colorFormat, useDepth ? GpuFormat.D32_FLOAT : null);
    }

    public static TextureTarget textureTarget(@Nullable String label, int width, int height, boolean useDepth) {
        return new TextureTarget(label, width, height, GpuFormat.RGBA8_UNORM, useDepth ? GpuFormat.D32_FLOAT : null);
    }

    public static @Nullable Identifier vertexShader(RenderPipeline pipeline) {
        return pipeline.getShaders().get(ShaderType.VERTEX);
    }

    public static @Nullable Identifier fragmentShader(RenderPipeline pipeline) {
        return pipeline.getShaders().get(ShaderType.FRAGMENT);
    }

    public static @Nullable ColorTargetState colorTargetState(RenderPipeline pipeline) {
        List<ColorTargetState> states = pipeline.getColorTargetStates();
        return states.isEmpty() ? null : states.getFirst();
    }

    public static ColorTargetState[] colorTargetStatesArray(RenderPipeline pipeline) {
        return pipeline.getColorTargetStates().toArray(ColorTargetState[]::new);
    }

    public static VertexFormat[] vertexFormatBindingsArray(RenderPipeline pipeline) {
        return pipeline.getVertexFormatBindings().toArray(VertexFormat[]::new);
    }

    public static RenderPipeline.Snippet snippet(
            Optional<Identifier> vertexShader,
            Optional<Identifier> fragmentShader,
            Optional<ShaderDefines> shaderDefines,
            Optional<List<BindGroupLayout>> bindGroupLayouts,
            ColorTargetState[] colorTargetStates,
            int activeColorTargetStateCount,
            Optional<DepthStencilState> depthStencilState,
            Optional<PolygonMode> polygonMode,
            Optional<Boolean> cull,
            VertexFormat[] vertexFormatPerBuffer,
            Optional<PrimitiveTopology> primitiveTopology) {
        Map<ShaderType, Identifier> shaders = new EnumMap<>(ShaderType.class);
        vertexShader.ifPresent(id -> shaders.put(ShaderType.VERTEX, id));
        fragmentShader.ifPresent(id -> shaders.put(ShaderType.FRAGMENT, id));
        return new RenderPipeline.Snippet(
                shaders,
                shaderDefines,
                bindGroupLayouts,
                colorTargetStates,
                activeColorTargetStateCount,
                depthStencilState,
                polygonMode,
                cull,
                vertexFormatPerBuffer,
                primitiveTopology,
                0);
    }

    public static GpuTextureView createTextureView(VulkanDevice device, GpuTexture texture) {
        return device.createTextureView(texture, 0, texture.getMipLevels());
    }

    public static <E extends Entity> boolean shouldRender(EntityRenderDispatcher dispatcher, E entity,
                                                          Frustum frustum, double x, double y, double z) {
        float scale = Minecraft.getInstance().options.entityDistanceScaling().get().floatValue();
        return dispatcher.shouldRender(entity, frustum, x, y, z, scale);
    }

    public static float sectionVisibility(SectionRenderDispatcher.RenderSection section, long timeMs) {
        return section.getVisibility(timeMs, 0L);
    }

    public static @Nullable String getShader(ShaderManager manager, Identifier id, ShaderType type) {
        if (PhotonShaderSources.isAdapted(id)) {
            String adapted = PhotonShaderSources.get(id, type);
            if (adapted != null) return adapted;
        }
        ShaderSource configs = activeShaderSource(manager);
        return configs == null ? null : configs.getShader(id, type);
    }

    public static ShaderSource.@Nullable CachedIncludeSource getInclude(Identifier id) {
        ShaderSource configs = activeShaderSource();
        return configs == null ? null : configs.getInclude(id);
    }

    public static @Nullable ShaderSource activeShaderSource() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null ? null : activeShaderSource(mc.getShaderManager());
    }

    public static @Nullable ShaderSource activeShaderSource(@Nullable ShaderManager manager) {
        if (manager == null || manager.postChains == null) return null;
        return manager.postChains.configs;
    }

    public static @Nullable RenderTarget auxiliaryDepthTarget(LevelRenderer renderer, int slot) {
        if (renderer == null || renderer.targets == null) return null;
        ResourceHandle<RenderTarget> handle = switch (slot) {
            case 0 -> renderer.targets.oitTerrainWithWaterPatchDepth;
            case 1 -> renderer.targets.oitCloudDepth;
            default -> null;
        };
        if (handle == null) return null;
        try {
            return handle.get();
        } catch (IllegalStateException unavailable) {
            return null;
        }
    }

    public static RenderPipeline.Builder toBuilder(RenderPipeline pipeline) {
        var builder = RenderPipeline.builder()
                .withLocation(pipeline.getLocation())
                .withPolygonMode(pipeline.getPolygonMode())
                .withCull(pipeline.isCull())
                .withPrimitiveTopology(pipeline.getPrimitiveTopology());
        Identifier vertex = vertexShader(pipeline);
        if (vertex != null) builder.withVertexShader(vertex);
        Identifier fragment = fragmentShader(pipeline);
        if (fragment != null) builder.withFragmentShader(fragment);
        if (pipeline.pushConstantSize() > 0) {
            builder.withPushConstantSize(pipeline.pushConstantSize());
        }
        builder.withDepthStencilState(Optional.ofNullable(pipeline.getDepthStencilState()));
        List<ColorTargetState> targets = pipeline.getColorTargetStates();
        for (int i = 0; i < targets.size(); i++) {
            ColorTargetState state = targets.get(i);
            if (state != null) {
                builder.withColorTargetState(i, state);
            } else {
                builder.withUnusedColorTargetState(i);
            }
        }
        List<VertexFormat> bindings = pipeline.getVertexFormatBindings();
        for (int i = 0; i < bindings.size(); i++) {
            VertexFormat binding = bindings.get(i);
            if (binding != null) {
                builder.withVertexBinding(i, binding);
            }
        }
        for (BindGroupLayout layout : pipeline.getBindGroupLayouts()) {
            builder.withBindGroupLayout(layout);
        }
        var defines = pipeline.getShaderDefines();
        for (String flag : defines.flags()) {
            builder.withShaderDefine(flag);
        }
        for (var entry : defines.values().entrySet()) {
            String key = entry.getKey();
            String raw = entry.getValue();
            try {
                builder.withShaderDefine(key, Integer.parseInt(raw));
            } catch (NumberFormatException notInt) {
                try {
                    builder.withShaderDefine(key, Float.parseFloat(raw));
                } catch (NumberFormatException notFloat) {
                    builder.withShaderDefine(key);
                }
            }
        }
        return builder;
    }

    /**
     * Synthesizes a lightweight cached {@link RenderPipeline} descriptor carrying the exact location
     * and depth-stencil state of a backend {@link BackendRenderPipeline.CreateInfo} for Iris draw hooks.
     */
    public static RenderPipeline pipelineFromCreateInfo(BackendRenderPipeline.CreateInfo info) {
        return CREATE_INFO_PIPELINES.computeIfAbsent(info, key -> {
            Identifier id = Identifier.tryParse(key.name());
            if (id == null) {
                id = Identifier.fromNamespaceAndPath("minecraft", "unknown");
            }
            var builder = RenderPipeline.builder()
                    .withLocation(id)
                    .withVertexShader(id)
                    .withFragmentShader(id)
                    .withPolygonMode(key.polygonMode())
                    .withCull(key.cull())
                    .withPrimitiveTopology(key.primitiveTopology())
                    .withDepthStencilState(Optional.ofNullable(key.depthStencilState()));
            List<ColorTargetState> targets = key.colorTargetStates();
            for (int i = 0; i < targets.size(); i++) {
                ColorTargetState target = targets.get(i);
                if (target != null) {
                    builder.withColorTargetState(i, target);
                } else {
                    builder.withUnusedColorTargetState(i);
                }
            }
            return builder.build();
        });
    }
}
