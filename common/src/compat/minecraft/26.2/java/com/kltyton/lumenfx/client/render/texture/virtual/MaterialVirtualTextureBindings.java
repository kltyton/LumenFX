package com.kltyton.lumenfx.client.render.texture.virtual;

import com.kltyton.lumenfx.api.client.parameter.DynamicMaterialValues;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture.Layer;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture.AdaptivePages;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture.Uint4;
import com.kltyton.lumenfx.api.client.parameter.NiagaraStreamingVirtualTexture;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.niagara.gpu.terrain.NiagaraGpuTerrainImages;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.render.material.VirtualTextureRead;
import com.kltyton.lumenfx.effect.render.material.VirtualTextureRead.StreamedValue;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Leases the page publications consumed by material draws and their GPU command encoders. */
public final class MaterialVirtualTextureBindings implements AutoCloseable {
    private static final int UNIFORM_BYTES = 192;
    private static final double TILE_CM = 2097152;
    private static final Uint4 ZERO = new Uint4(0, 0, 0, 0);
    private final NiagaraGpuTerrainImages defaults = new NiagaraGpuTerrainImages();
    private final MaterialVirtualTextureFeedback feedback = new MaterialVirtualTextureFeedback();
    private final Map<MaterialDefinition, List<Prepared>> draws = new IdentityHashMap<>();
    private final Map<DynamicMaterialValues, DynamicMaterialValues.Snapshot> instances = new IdentityHashMap<>();
    private final Map<NiagaraRuntimeVirtualTexture, NiagaraRuntimeVirtualTexture.Lease> leases = new IdentityHashMap<>();
    private final Map<NiagaraStreamingVirtualTexture, NiagaraStreamingVirtualTexture.Lease> streamedLeases = new IdentityHashMap<>();
    private @Nullable Function<String, NiagaraRuntimeVirtualTexture> resolver;
    private @Nullable Function<Identifier, NiagaraStreamingVirtualTexture> streamedResolver;
    private int temporalIndex;
    private boolean anisotropicFiltering;

    private record Publication(Uint4 table0, Uint4 table1, Map<Integer, Layer> layers, GpuSampler sampler,
                               Vec3 origin, Vec3 u, Vec3 v, float heightScale, float heightBias, int width, int height,
                               int addressU, int addressV, int producerId, @Nullable AdaptivePages adaptive) { }
    private record Prepared(VirtualTextureRead source, GpuBufferSlice view, @Nullable Publication snapshot) { }

    public void begin(Function<String, NiagaraRuntimeVirtualTexture> resolver,
                      Function<Identifier, NiagaraStreamingVirtualTexture> streamedResolver, int temporalIndex, int width, int height) {
        if (this.resolver != null) throw new IllegalStateException("Virtual texture material draws already have a scene owner");
        this.resolver = resolver;
        this.streamedResolver = streamedResolver;
        this.temporalIndex = temporalIndex;
        anisotropicFiltering = Objects.requireNonNull(NiagaraPlatforms.snapshot().console("r.VT.AnisotropicFiltering")).bits() != 0;
        feedback.begin(width, height);
    }

    public static void layout(BindGroupLayout.Builder layout, List<VirtualTextureRead> sources, boolean feedback) {
        for (var source : sources) {
            layout.withUniform(source.uniform(), UniformType.UNIFORM_BUFFER);
            for (int layer = 0; layer < source.layers(); layer++)
                layout.withSampler(source.pages(layer)).withSampler(source.physical(layer));
            if (source.adaptive()) layout.withSampler(source.indirection()).withSampler(source.fine());
        }
        if (feedback) VirtualTextureFeedbackLayout.add(layout);
    }

    public void prepare(CommandEncoder encoder, MaterialDefinition material) {
        if (material.virtualTextureReads().isEmpty() || draws.containsKey(material)) return;
        var placed = Objects.requireNonNull(resolver, "Material virtual texture draw requires a live scene");
        var resources = Objects.requireNonNull(streamedResolver);
        if (material.virtualTextureFeedback()) feedback.prepare(encoder);
        var values = material.instanceParameters().dynamicValues();
        var overrides = values == null ? null : instances.computeIfAbsent(values, DynamicMaterialValues::snapshot);
        var rendererTextures = material.instanceParameters().publication().streamedTextures();
        var bindings = new ArrayList<Prepared>(material.virtualTextureReads().size());
        for (var read : material.virtualTextureReads()) {
            Publication snapshot;
            var parameter = read.parameter();
            if (read.streamed() == null) {
                var producer = overrides == null || parameter == null ? null : overrides.runtimeTextures().get(parameter);
                if (producer == null) producer = placed.apply(read.source());
                snapshot = runtime(read, producer, material.virtualTextureFeedback());
            } else {
                var rendererValue = rendererTextures.get(read.name());
                if (rendererValue != null) {
                    snapshot = rendererTexture(Objects.requireNonNull(read.rendererBinding()), rendererValue, resources, material.virtualTextureFeedback());
                } else {
                    var producer = overrides == null || parameter == null ? null : overrides.streamedTextures().get(parameter);
                    if (producer == null) producer = Objects.requireNonNull(resources.apply(read.streamed()), "Missing streamed material virtual texture");
                    snapshot = streamed(producer, material.virtualTextureFeedback());
                }
            }
            if (snapshot == null) {
                defaults.zeroColor();
                defaults.zeroInteger(GpuFormat.RGBA32_UINT);
            }
            if (read.adaptive() && (snapshot == null || snapshot.adaptive() == null)) defaults.zeroInteger(GpuFormat.R32_UINT);
            bindings.add(new Prepared(read, upload(encoder, snapshot), snapshot));
        }
        draws.put(material, List.copyOf(bindings));
    }

    private @Nullable Publication rendererTexture(VirtualTextureRead.StreamedBinding binding, StreamedValue value,
            Function<Identifier, NiagaraStreamingVirtualTexture> resources, boolean requests) {
        if (value instanceof StreamedValue.Virtual virtual) {
            var result = streamed(virtual.source(), requests);
            if (result != null) return result;
        } else if (value == StreamedValue.Default.UNPAGED
                || value instanceof StreamedValue.Ordinary ordinary && ordinary.source().resolve().bound()) {
            // A valid UTexture without a virtual resource leaves this virtual sampler's stack unallocated.
            return null;
        }
        return binding.base() == null ? null : streamed(resources.apply(binding.base()), requests);
    }

    private @Nullable Publication runtime(VirtualTextureRead read, @Nullable NiagaraRuntimeVirtualTexture producer, boolean requests) {
        if (producer == null) return null;
        var lease = leases.computeIfAbsent(producer, NiagaraRuntimeVirtualTexture::acquire);
        if (lease == null) return null;
        var snapshot = lease.snapshot();
        if (snapshot.material() != read.material() || snapshot.layers().size() != read.layers()
                || !read.adaptive() && (snapshot.adaptive() != null || ((snapshot.table1().z() >> 4) & 15) != 0))
            throw new IllegalStateException("Runtime virtual texture material publication differs from " + read.source());
        int bias = read.adaptive() ? (snapshot.table1().z() >> 4) & 15 : 0;
        int id = requests ? feedback.register(producer, producer::request, snapshot.table0(), bias) : 0;
        return new Publication(snapshot.table0(), snapshot.table1(), snapshot.layers(), snapshot.sampler(), snapshot.origin(), snapshot.u(),
                snapshot.v(), snapshot.heightScale(), snapshot.heightBias(), 0, 0, 0, 0, id, snapshot.adaptive());
    }

    private @Nullable Publication streamed(NiagaraStreamingVirtualTexture producer, boolean requests) {
        var lease = streamedLeases.computeIfAbsent(producer, NiagaraStreamingVirtualTexture::acquire);
        if (lease == null) return null;
        var snapshot = lease.snapshot();
        int feedbackId = requests ? feedback.register(producer, producer::request, snapshot.table0(), 0) : 0;
        return new Publication(snapshot.table0(), snapshot.table1(), Map.of(0, snapshot.layer()), snapshot.sampler(),
                Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, 0, 0, snapshot.width(), snapshot.height(), snapshot.addressU(), snapshot.addressV(), feedbackId, null);
    }

    public void bind(RenderPass pass, MaterialDefinition material) {
        if (material.virtualTextureReads().isEmpty()) return;
        if (material.virtualTextureFeedback()) feedback.bind(pass);
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        for (var ready : prepared(material)) {
            var source = ready.source();
            var snapshot = ready.snapshot();
            pass.setUniform(source.uniform(), ready.view());
            for (int layer = 0; layer < source.layers(); layer++) {
                Layer publication = snapshot == null ? null : snapshot.layers().get(layer);
                pass.bindTexture(source.pages(layer), page(publication), nearest);
                pass.bindTexture(source.physical(layer), publication == null ? defaults.zeroColor() : publication.physical(),
                        snapshot == null ? nearest : snapshot.sampler());
            }
            if (source.adaptive()) {
                pass.bindTexture(source.indirection(), adaptive(snapshot, true), nearest);
                pass.bindTexture(source.fine(), adaptive(snapshot, false), nearest);
            }
        }
    }

    public @Nullable GpuBufferSlice uniform(MaterialDefinition material, String name) {
        if (material.virtualTextureReads().isEmpty()) return null;
        for (var ready : prepared(material)) if (ready.source().uniform().equals(name)) return ready.view();
        return null;
    }

    public boolean bind(ComputeBindings.Builder bindings, MaterialDefinition material, ShaderBinding.SampledTexture binding) {
        if (material.virtualTextureReads().isEmpty()) return false;
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        for (var ready : prepared(material)) {
            var read = ready.source();
            var snapshot = ready.snapshot();
            if (read.adaptive() && (binding.name().equals(read.indirection()) || binding.name().equals(read.fine()))) {
                bindImage(bindings, binding, adaptive(snapshot, binding.name().equals(read.indirection())), nearest);
                return true;
            }
            for (int layer = 0; layer < read.layers(); layer++) {
                Layer publication = snapshot == null ? null : snapshot.layers().get(layer);
                boolean table = binding.name().equals(read.pages(layer));
                if (!table && !binding.name().equals(read.physical(layer))) continue;
                GpuTextureView view = table ? page(publication) : publication == null ? defaults.zeroColor() : publication.physical();
                GpuSampler sampler = table || snapshot == null ? nearest : snapshot.sampler();
                bindImage(bindings, binding, view, sampler);
                return true;
            }
        }
        return false;
    }

    private static void bindImage(ComputeBindings.Builder bindings, ShaderBinding.SampledTexture binding, GpuTextureView view, GpuSampler sampler) {
        if (binding.form() == ShaderBinding.DescriptorForm.SAMPLED_IMAGE) bindings.sampledImage(binding.name(), view);
        else bindings.texture(binding.name(), view, sampler);
    }

    private GpuTextureView adaptive(@Nullable Publication snapshot, boolean indirection) {
        var fine = snapshot == null ? null : snapshot.adaptive();
        return fine == null ? defaults.zeroInteger(GpuFormat.R32_UINT) : indirection ? fine.indirection() : fine.pages();
    }

    private GpuTextureView page(@Nullable Layer layer) {
        return layer == null ? defaults.zeroInteger(GpuFormat.RGBA32_UINT) : layer.pageTable();
    }

    private List<Prepared> prepared(MaterialDefinition material) {
        return Objects.requireNonNull(draws.get(material), "Virtual texture material bindings were not prepared");
    }

    private GpuBufferSlice upload(CommandEncoder encoder, @Nullable Publication snapshot) {
        try (var mapped = encoder.transientMemory().allocateGpuMapped(UNIFORM_BYTES,
                RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            words(data, snapshot == null ? ZERO : snapshot.table0());
            words(data, snapshot == null ? ZERO : snapshot.table1());
            for (int index = 0; index < 3; index++) {
                var layer = snapshot == null ? null : snapshot.layers().get(index);
                words(data, layer == null ? ZERO : layer.uniforms());
            }
            var origin = snapshot == null ? Vec3.ZERO : snapshot.origin();
            var tile = new Vec3(Math.floor(origin.x * 100 / TILE_CM + .5),
                    Math.floor(origin.y * 100 / TILE_CM + .5), Math.floor(origin.z * 100 / TILE_CM + .5));
            vector(data, tile, 1);
            vector(data, origin.scale(100).subtract(tile.scale(TILE_CM)), 1);
            vector(data, snapshot == null ? Vec3.ZERO : snapshot.u(), .01);
            vector(data, snapshot == null ? Vec3.ZERO : snapshot.v(), .01);
            data.putFloat(snapshot == null ? 0 : snapshot.heightScale() * 100f)
                    .putFloat(snapshot == null ? 0 : snapshot.heightBias() * 100f)
                    .putFloat(anisotropicFiltering ? 1f : 0f).putFloat(0);
            data.putFloat(snapshot == null ? 0 : 1).putFloat(0).putFloat(temporalIndex & 0x00ff_ffff)
                    .putFloat(snapshot == null ? 0 : snapshot.producerId());
            data.putInt(snapshot == null ? 0 : snapshot.width()).putInt(snapshot == null ? 0 : snapshot.height())
                    .putInt(snapshot == null ? 0 : snapshot.addressU()).putInt(snapshot == null ? 0 : snapshot.addressV());
            return mapped.slice();
        }
    }

    private static void vector(ByteBuffer data, Vec3 vector, double scale) {
        data.putFloat((float)(vector.x * scale)).putFloat((float)(vector.z * scale)).putFloat((float)(vector.y * scale)).putFloat(0);
    }

    private static void words(ByteBuffer data, Uint4 value) {
        data.putInt(value.x()).putInt(value.y()).putInt(value.z()).putInt(value.w());
    }

    public void end() {
        try { feedback.finish(); }
        finally {
            draws.clear();
            instances.clear();
            for (var lease : leases.values()) lease.close();
            leases.clear();
            for (var lease : streamedLeases.values()) lease.close();
            streamedLeases.clear();
            resolver = null;
            streamedResolver = null;
        }
    }

    public void invalidateWorld() { feedback.invalidateWorld(); }

    @Override public void close() {
        end();
        feedback.close();
        RenderSystem.queueFencedTask(defaults::close);
    }
}
