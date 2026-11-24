package com.kltyton.lumenfx.client.render.niagara.gpu.terrain;

import com.kltyton.lumenfx.api.client.parameter.NiagaraLandscapeInputs.Grid;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/** Owns typed 2D textures used by Niagara's terrain and virtual texture interfaces. */
public final class NiagaraGpuTerrainImages implements AutoCloseable {
    private final Map<GpuFormat, Image> defaults = new EnumMap<>(GpuFormat.class);

    public GpuTextureView zeroColor() {
        return defaults.computeIfAbsent(GpuFormat.RGBA8_UNORM, type ->
                upload("Niagara terrain empty color", type, 1, 1, Integer.BYTES, data -> data.putInt(0))).view;
    }

    public GpuTextureView zeroInteger(GpuFormat format) {
        return defaults.computeIfAbsent(format, type -> {
            int bytes = switch (type) {
                case R8_UINT -> 1;
                case R32_UINT -> Integer.BYTES;
                case RGBA32_UINT -> 4 * Integer.BYTES;
                default -> throw new IllegalArgumentException("Unsupported Niagara terrain integer sampler " + type);
            };
            return upload("Niagara terrain empty " + type, type, 1, 1, bytes, data -> {
                while (data.hasRemaining()) data.put((byte)0);
            });
        }).view;
    }

    void bind(ShaderBinding.SampledTexture binding, ComputeBindings.Builder resources, @Nullable GpuTextureView view,
              @Nullable GpuSampler sampler, Supplier<GpuTextureView> black) {
        if (view == null) view = binding.format() == GpuFormat.R8_UINT || binding.format() == GpuFormat.RGBA32_UINT
                ? zeroInteger(binding.format()) : black.get();
        if (binding.form() == ShaderBinding.DescriptorForm.SAMPLED_IMAGE) resources.sampledImage(binding.name(), view);
        else resources.texture(binding.name(), view, sampler == null ? RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST) : sampler);
    }

    private static Image upload(String name, GpuFormat format, int width, int height, int bytes, Consumer<ByteBuffer> fill) {
        var device = RenderSystem.getDevice();
        var data = MemoryUtil.memAlloc(bytes).order(ByteOrder.nativeOrder());
        try {
            fill.accept(data);
            data.clear();
            var texture = device.createTexture(name, GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                    format, width, height, 1, 1);
            try {
                device.createCommandEncoder().writeToTexture(texture, data, 0, 0, 0, 0, width, height);
                return new Image(texture, device.createTextureView(texture));
            } catch (RuntimeException | Error failure) {
                texture.close();
                throw failure;
            }
        } finally { MemoryUtil.memFree(data); }
    }

    private record Image(GpuTexture texture, GpuTextureView view) implements AutoCloseable {
        @Override public void close() {
            try { view.close(); }
            finally { texture.close(); }
        }
    }

    @Override public void close() {
        defaults.values().forEach(Image::close);
        defaults.clear();
    }

    public static final class CollisionGrids implements AutoCloseable {
        private final Map<String, Grid> selected = new HashMap<>();
        private final Map<Grid, Entry> entries = new IdentityHashMap<>();

        private static final class Entry implements AutoCloseable {
            final @Nullable Image heights;
            final @Nullable Image materials;
            int references;

            Entry(String id, Grid grid) {
                int cells = Math.multiplyExact(grid.width(), grid.height());
                heights = grid.hasHeight() ? upload(id + " height", GpuFormat.R32_FLOAT,
                        grid.width(), grid.height(), Math.multiplyExact(cells, Float.BYTES), grid::writeHeights) : null;
                try {
                    materials = grid.hasMaterial() ? upload(id + " physical materials", GpuFormat.R8_UINT,
                            grid.width(), grid.height(), cells, grid::writeMaterials) : null;
                } catch (RuntimeException | Error failure) {
                    if (heights != null) heights.close();
                    throw failure;
                }
            }

            @Override public void close() {
                try { if (heights != null) heights.close(); }
                finally { if (materials != null) materials.close(); }
            }
        }

        public void use(String id, @Nullable Grid grid) {
            var previous = selected.get(id);
            if (previous == grid) return;
            if (grid == null) selected.remove(id);
            else {
                entries.computeIfAbsent(grid, value -> new Entry(id, value)).references++;
                selected.put(id, grid);
            }
            if (previous != null) {
                var old = entries.get(previous);
                if (--old.references == 0) {
                    entries.remove(previous);
                    old.close();
                }
            }
        }

        public @Nullable GpuTextureView height(String id) {
            var entry = entries.get(selected.get(id));
            return entry == null || entry.heights == null ? null : entry.heights.view;
        }

        public @Nullable GpuTextureView material(String id) {
            var entry = entries.get(selected.get(id));
            return entry == null || entry.materials == null ? null : entry.materials.view;
        }

        @Override public void close() {
            entries.values().forEach(Entry::close);
            entries.clear();
            selected.clear();
        }
    }
}
