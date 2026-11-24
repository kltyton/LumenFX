package com.kltyton.lumenfx.client.render.mesh.nanite;

import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeKernel;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.mesh.MeshDrawSelection;
import com.kltyton.lumenfx.client.resource.shader.PreparedNaniteVertexMaterial;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraNaniteShaders;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Executes each material's authored vertex graph before visibility rasterization. */
final class NaniteVertexMaterials implements AutoCloseable {
    interface MaterialBindings {
        @Nullable GpuBufferSlice uniform(CommandEncoder encoder, String name, MaterialDefinition material,
                                         NaniteInstanceCollector.Prepared source, NaniteViewProjection current,
                                         NaniteViewProjection previous);
        boolean texture(ComputeBindings.Builder bindings, ShaderBinding.SampledTexture binding, MaterialDefinition material);
    }

    private final ComputeRuntime runtime;
    private final Function<Identifier, PreparedNaniteVertexMaterial> materials;
    private final NaniteComputeProgram passthrough;
    private final Map<PreparedNaniteVertexMaterial, ComputeKernel> kernels = new IdentityHashMap<>();

    NaniteVertexMaterials(ComputeRuntime runtime, PreparedNiagaraNaniteShaders shaders,
                          Function<Identifier, PreparedNaniteVertexMaterial> materials) {
        this.runtime = Objects.requireNonNull(runtime);
        this.materials = Objects.requireNonNull(materials);
        this.passthrough = new NaniteComputeProgram(runtime, shaders.vertexPassthrough(), 64);
    }

    Map<Integer, NaniteViewRasterization.Interpolants> apply(CommandEncoder encoder, NaniteViewRasterization.Mesh mesh,
            NaniteRasterBins.Output bins, NaniteShadingGeometry.Output geometry,
            NaniteViewProjection current, NaniteViewProjection previous, int frameFlags, MaterialBindings materialBindings,
            NaniteWorldRenderer.RasterResolve sceneTextures) {
        RenderSystem.assertOnRenderThread();
        var source = mesh.source();
        int slots = Math.max(1, Math.max(source.asset().materials().size(), source.entry().materials().size()));
        if (slots > NaniteRasterBins.MATERIAL_SLOTS) throw new IllegalArgumentException("Nanite material slot count exceeds raster bins");
        int vertices = Math.multiplyExact(bins.triangleCapacity(), 3);
        int groups = Math.ceilDiv(vertices, 64);
        int columns = Math.min(groups, runtime.maxWorkGroups(0));
        int rows = Math.ceilDiv(groups, columns);
        if (rows > runtime.maxWorkGroups(1)) throw new IllegalArgumentException("Nanite material dispatch exceeds device limits");
        int width = Math.multiplyExact(columns, 64);
        var result = new LinkedHashMap<Integer, NaniteViewRasterization.Interpolants>();
        for (int slot = 0; slot < slots; slot++) {
            var selected = MeshDrawSelection.materialAtSlot(source.effect(), source.renderer(), source.asset(), source.entry(), slot, source.input());
            MaterialDefinition material = source.materialInstances().apply(selected);
            var settings = material.vertexMaterial();
            if (settings == null || settings.naniteShader() == null) {
                GpuBufferSlice output = allocate(encoder, vertices, 5);
                var buffers = Map.of("NaniteVertexPrimitives", geometry.primitives(), "NaniteRasterCommands", bins.commands(),
                        "NaniteVertexGeometry", geometry.vertices(), "NaniteVertexInterpolants", output);
                var layout = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder())
                        .putInt(bins.triangleCapacity()).putInt(slot).putInt(width).putInt(0).array();
                passthrough.dispatch(encoder, buffers, Map.of("NaniteVertexLayout", layout), width, rows);
                result.put(slot, new NaniteViewRasterization.Interpolants(output, 5));
                continue;
            }
            Identifier shaderId = Objects.requireNonNull(settings.naniteShader(), "Nanite material has no authored compute program");
            var prepared = Objects.requireNonNull(materials.apply(shaderId), "Nanite material program was not prepared: " + shaderId);
            if (!prepared.interpolatorWidths().equals(settings.naniteInterpolatorWidths()))
                throw new IllegalArgumentException("Nanite vertex interpolator layout differs from its material: " + shaderId);
            GpuBufferSlice output = allocate(encoder, vertices, prepared.wordsPerVertex());
            GpuBufferSlice dispatch = dispatch(encoder, bins.triangleCapacity(), slot, width, mesh.bounds().maximumWpoExtent());
            GpuBufferSlice view = NaniteMaterialGeometry.write(encoder, geometry, mesh.scene(), current, previous, slot, frameFlags);
            var builder = ComputeBindings.builder();
            for (var binding : prepared.shader().bindings()) {
                String name = binding.name();
                if (binding instanceof ShaderBinding.StorageBuffer storage) {
                    GpuBufferSlice buffer = switch (name) {
                        case "NaniteVertexPrimitives" -> geometry.primitives();
                        case "NaniteRasterCommands" -> bins.commands();
                        case "NaniteVertexGeometry" -> geometry.vertices();
                        case "NaniteVertexInterpolants" -> output;
                        case "NaniteShadingInstanceFloats" -> mesh.scene().customFloats();
                        case "NaniteShadingTransforms" -> mesh.instances().transforms();
                        default -> throw new IllegalArgumentException("Unknown Nanite material storage " + name);
                    };
                    builder.storage(name, buffer, storage.access());
                } else if (binding instanceof ShaderBinding.UniformBuffer) {
                    GpuBufferSlice value = switch (name) {
                        case "NaniteVertexDispatch" -> dispatch;
                        case "NaniteShadingView" -> view;
                        default -> materialBindings.uniform(encoder, name, material, source, current, previous);
                    };
                    builder.uniform(name, Objects.requireNonNull(value, "Missing Nanite vertex material uniform " + name));
                } else if (binding instanceof ShaderBinding.SampledTexture texture) {
                    if (!materialBindings.texture(builder, texture, material) && !sceneTextures.bindSampled(builder, texture))
                        throw new IllegalArgumentException("Missing Nanite vertex material texture " + name);
                } else throw new IllegalArgumentException("Unexpected Nanite vertex material resource " + name);
            }
            kernels.computeIfAbsent(prepared, value -> runtime.createKernel(value.shader())).dispatch(builder.build(), width, rows);
            result.put(slot, new NaniteViewRasterization.Interpolants(output, prepared.wordsPerVertex()));
        }
        return result;
    }

    private GpuBufferSlice allocate(CommandEncoder encoder, int vertices, int wordsPerVertex) {
        long bytes = Math.multiplyExact(Math.multiplyExact((long) vertices, wordsPerVertex), 16L);
        if (bytes > runtime.maxStorageBufferBytes() || bytes / 16 > runtime.maxTexelBufferElements())
            throw new IllegalArgumentException("Nanite vertex interpolants exceed device limits");
        return encoder.transientMemory().allocateGpu(bytes, Math.max(16, runtime.storageBufferAlignment()),
                ComputeUsage.storageBuffer(GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER));
    }

    private static GpuBufferSlice dispatch(CommandEncoder encoder, int capacity, int slot, int width, float maximumWpoExtent) {
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        try (var mapped = encoder.transientMemory().allocateGpuMapped(16, alignment, GpuBuffer.USAGE_UNIFORM)) {
            mapped.data().order(ByteOrder.nativeOrder()).putInt(capacity).putInt(slot).putInt(width).putFloat(maximumWpoExtent);
            return mapped.slice();
        }
    }

    @Override public void close() {
        try { kernels.values().forEach(ComputeKernel::close); }
        finally { kernels.clear(); passthrough.close(); }
    }
}
