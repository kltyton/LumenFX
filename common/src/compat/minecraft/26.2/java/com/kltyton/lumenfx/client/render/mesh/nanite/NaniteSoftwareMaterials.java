package com.kltyton.lumenfx.client.render.mesh.nanite;

import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeKernel;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.Identifier;

/** Evaluates each active software bin's authored mask and depth offset against its covered samples. */
final class NaniteSoftwareMaterials implements AutoCloseable {
    private final ComputeRuntime runtime;
    private final Map<Identifier, ShaderProgram> materials;
    private final Map<ShaderProgram, ComputeKernel> kernels = new IdentityHashMap<>();

    NaniteSoftwareMaterials(ComputeRuntime runtime, Map<Identifier, ShaderProgram> materials) {
        this.runtime = runtime;
        this.materials = materials;
    }

    boolean hasProgram(MaterialDefinition material) {
        return materials.containsKey(material.fragmentShader());
    }

    void dispatch(CommandEncoder encoder, NaniteWorldRenderer.Surface surface, NaniteInstanceCollector.Prepared source,
                  NaniteTriangleRaster.Software output, NaniteRasterBins.Output bins,
                  NaniteShadingGeometry.Output geometry, NaniteViewRasterization.Interpolants attributes,
                  NaniteViewProjection current, NaniteViewProjection previous, int slot, int frameFlags,
                  NaniteMaterialFrameBindings frame, NaniteWorldRenderer.RasterResolve resources,
                  int width, int rows, boolean identity) {
        var material = surface.material();
        var shader = Objects.requireNonNull(materials.get(material.fragmentShader()),
                "Missing Nanite software raster program for " + material.fragmentShader());
        var builder = ComputeBindings.builder();
        for (var binding : shader.bindings()) {
            String name = binding.name();
            if (binding instanceof ShaderBinding.StorageBuffer storage) {
                var buffer = switch (name) {
                    case "NaniteBinTriangles" -> bins.triangles();
                    case "NaniteRasterCommands" -> bins.commands();
                    case "NaniteShadingPrimitives" -> geometry.primitives();
                    case "NaniteShadingVertices" -> geometry.vertices();
                    case "NaniteVertexInterpolants" -> attributes.slice();
                    case "NaniteShadingInstanceFloats" -> source.scene().customFloats();
                    case "NaniteShadingTransforms" -> source.scene().instances().transforms();
                    case "NaniteSoftwareDepth" -> output.depth();
                    case "NaniteSoftwarePrimitives" -> output.primitives();
                    default -> throw new IllegalArgumentException("Unknown Nanite software raster buffer " + name);
                };
                builder.storage(name, buffer, storage.access());
            } else if (binding instanceof ShaderBinding.UniformBuffer) {
                var value = switch (name) {
                    case "NaniteSoftwareDispatch" -> dispatchUniform(encoder, width, !material.cull(), identity);
                    case "NaniteShadingView" -> NaniteMaterialGeometry.writeCompute(
                            encoder, geometry, source.scene(), current, previous, slot, frameFlags);
                    case "NaniteInterpolantLayout" -> NaniteMaterialGeometry.writeComputeInterpolants(encoder, attributes);
                    default -> frame.uniform(encoder, name, material, source, current, previous);
                };
                builder.uniform(name, Objects.requireNonNull(value, "Missing Nanite software raster uniform " + name));
            } else if (binding instanceof ShaderBinding.SampledTexture texture) {
                if (!resources.bindSampled(builder, texture) && !frame.texture(builder, texture, material))
                    throw new IllegalArgumentException("Missing Nanite software raster texture " + name);
            } else throw new IllegalArgumentException("Unexpected Nanite software raster resource " + name);
        }
        kernels.computeIfAbsent(shader, runtime::createKernel).dispatch(builder.build(), width, rows);
    }

    private static GpuBufferSlice dispatchUniform(CommandEncoder encoder, int width, boolean twoSided, boolean identity) {
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        try (var mapped = encoder.transientMemory().allocateGpuMapped(16, alignment, GpuBuffer.USAGE_UNIFORM)) {
            mapped.data().order(ByteOrder.nativeOrder()).putInt(width).putInt(twoSided ? 1 : 0)
                    .putInt(identity ? 1 : 0).putInt(0);
            return mapped.slice();
        }
    }

    @Override public void close() {
        try { kernels.values().forEach(ComputeKernel::close); }
        finally { kernels.clear(); }
    }
}
