package com.kltyton.lumenfx.client.render.compute;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.kltyton.lumenfx.client.render.texture.cube.CubeColorTarget;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Immutable per-dispatch resource set. Build once and reuse across frames. */
public final class ComputeBindings {
    private final Map<String, Uniform> uniforms;
    private final Map<String, Sampled> sampled;
    private final Map<String, Image> images;
    private final Map<String, VolumeImage> volumeImages;
    private final Map<String, CubeImage> cubeImages;
    private final Map<String, Storage> storage;

    private ComputeBindings(final Builder builder) {
        this.uniforms = Map.copyOf(builder.uniforms);
        this.sampled = Map.copyOf(builder.sampled);
        this.images = Map.copyOf(builder.images);
        this.volumeImages = Map.copyOf(builder.volumeImages);
        this.cubeImages = Map.copyOf(builder.cubeImages);
        this.storage = Map.copyOf(builder.storage);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Map<String, Uniform> uniforms() {
        return this.uniforms;
    }

    public Map<String, Sampled> sampledBindings() {
        return this.sampled;
    }

    public Map<String, Image> images() {
        return this.images;
    }

    public Map<String, Storage> storageBindings() {
        return this.storage;
    }

    public Uniform uniform(final String name) {
        return this.uniforms.get(name);
    }

    public Sampled sampled(final String name) {
        return this.sampled.get(name);
    }

    public Image image(final String name) {
        return this.images.get(name);
    }
    public VolumeImage volumeImage(String name) { return volumeImages.get(name); }
    public CubeImage cubeImage(String name) { return cubeImages.get(name); }

    public Storage storage(final String name) {
        return this.storage.get(name);
    }

    public record Uniform(String name, GpuBufferSlice slice) {
        public Uniform {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(slice, "slice");
        }
    }

    public sealed interface Sampled permits Texture, NativeTexture, SampledImage, NativeSampledImage, VolumeTexture {
        String name();

        @Nullable GpuSampler sampler();
    }

    public record VolumeTexture(String name, ComputeVolume volume, int tilingAxes, @Nullable GpuSampler sampler) implements Sampled {
        public VolumeTexture(String name, ComputeVolume volume, int tilingAxes) {
            this(name, volume, tilingAxes, null);
        }

        public VolumeTexture {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(volume, "volume");
            if (tilingAxes < 0 || tilingAxes > 7) throw new IllegalArgumentException("Invalid volume tiling axes");
        }
    }

    /** Host view binding for 2D, cube and marked 3D textures. */
    public record Texture(String name, GpuTextureView view, GpuSampler sampler) implements Sampled {
        public Texture {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(view, "view");
            Objects.requireNonNull(sampler, "sampler");
        }
    }

    /** Compute-native binding with an exact SRV subresource. */
    public record NativeTexture(
            String name,
            ComputeTexture texture,
            GpuSampler sampler,
            int mipLevel,
            int baseLayer,
            int layerCount,
            boolean arrayView,
            boolean fullMipChain
    ) implements Sampled {
        public NativeTexture(String name, ComputeTexture texture, GpuSampler sampler, int mipLevel,
                             int baseLayer, int layerCount, boolean arrayView) {
            this(name, texture, sampler, mipLevel, baseLayer, layerCount, arrayView, false);
        }

        public NativeTexture {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(texture, "texture");
            Objects.requireNonNull(sampler, "sampler");
            if (mipLevel < 0 || baseLayer < 0 || layerCount < 1) {
                throw new IllegalArgumentException("Invalid sampled image subresource for " + name);
            }
            if (fullMipChain) requireFullArray(name, texture, mipLevel, baseLayer, layerCount, arrayView);
        }

        public ComputeTexture.SampledView sampledView() {
            return fullMipChain ? texture.fullArraySampledView() : texture.cachedSampledView(mipLevel, baseLayer, layerCount, arrayView);
        }
    }

    /** Texture.Load/texelFetch-only host binding: no Vulkan sampler descriptor is created. */
    public record SampledImage(String name, GpuTextureView view) implements Sampled {
        public SampledImage {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(view, "view");
        }

        @Override
        public @Nullable GpuSampler sampler() {
            return null;
        }
    }

    /** Texture.Load/texelFetch-only native binding with an exact SRV subresource. */
    public record NativeSampledImage(
            String name,
            ComputeTexture texture,
            int mipLevel,
            int baseLayer,
            int layerCount,
            boolean arrayView,
            boolean fullMipChain
    ) implements Sampled {
        public NativeSampledImage(String name, ComputeTexture texture, int mipLevel, int baseLayer,
                                  int layerCount, boolean arrayView) {
            this(name, texture, mipLevel, baseLayer, layerCount, arrayView, false);
        }

        public NativeSampledImage {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(texture, "texture");
            if (mipLevel < 0 || baseLayer < 0 || layerCount < 1) {
                throw new IllegalArgumentException("Invalid sampled image subresource for " + name);
            }
            if (fullMipChain) requireFullArray(name, texture, mipLevel, baseLayer, layerCount, arrayView);
        }

        public ComputeTexture.SampledView sampledView() {
            return fullMipChain ? texture.fullArraySampledView() : texture.cachedSampledView(mipLevel, baseLayer, layerCount, arrayView);
        }

        @Override
        public @Nullable GpuSampler sampler() {
            return null;
        }
    }

    private static void requireFullArray(String name, ComputeTexture texture, int mipLevel, int baseLayer,
                                         int layerCount, boolean arrayView) {
        if (!texture.isArray() || !arrayView || mipLevel != 0 || baseLayer != 0 || layerCount != texture.layers())
            throw new IllegalArgumentException("A full sampled array must bind all layers and mip levels: " + name);
    }

    public record Image(
            String name,
            ComputeTexture texture,
            ComputeAccess access,
            int mipLevel,
            int baseLayer,
            int layerCount
    ) {
        public Image {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(texture, "texture");
            Objects.requireNonNull(access, "access");
            if (mipLevel < 0 || baseLayer < 0 || layerCount < 1) {
                throw new IllegalArgumentException("Invalid storage image subresource for " + name);
            }
            if ((texture.texture().usage() & ComputeUsage.STORAGE_IMAGE) == 0)
                throw new IllegalArgumentException("Texture was allocated without storage image access: " + name);
        }
    }

    public record VolumeImage(String name, ComputeVolume volume, ComputeAccess access) {
        public VolumeImage {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(volume, "volume");
            Objects.requireNonNull(access, "access");
            if (volume.isClosed() || !volume.writable()) throw new IllegalArgumentException("Volume is not writable: " + name);
        }
    }
    public record CubeImage(String name, CubeColorTarget target, ComputeAccess access) {
        public CubeImage {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(access, "access");
            if (!target.writable()) throw new IllegalArgumentException("Cube target has no writable array view: " + name);
        }
    }

    public record Storage(String name, GpuBufferSlice slice, ComputeAccess access) {
        public Storage {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(slice, "slice");
            Objects.requireNonNull(access, "access");
        }
    }

    public static final class Builder {
        private final Map<String, Uniform> uniforms = new LinkedHashMap<>();
        private final Map<String, Sampled> sampled = new LinkedHashMap<>();
        private final Map<String, Image> images = new LinkedHashMap<>();
        private final Map<String, VolumeImage> volumeImages = new LinkedHashMap<>();
        private final Map<String, CubeImage> cubeImages = new LinkedHashMap<>();
        private final Map<String, Storage> storage = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder uniform(final String name, final GpuBufferSlice slice) {
            final String checked = claim(name);
            this.uniforms.put(checked, new Uniform(checked, slice));
            return this;
        }

        public Builder texture(final String name, final GpuTextureView view, final GpuSampler sampler) {
            final String checked = claim(name);
            this.sampled.put(checked, new Texture(checked, view, sampler));
            return this;
        }

        public Builder texture(
                final String name,
                final ComputeTexture texture,
                final GpuSampler sampler
        ) {
            return this.texture(
                    name, texture, 0, 0,
                    texture.isArray() ? texture.layers() : 1,
                    texture.isArray(),
                    sampler
            );
        }

        public Builder texture(
                final String name,
                final ComputeTexture texture,
                final int mipLevel,
                final int baseLayer,
                final int layerCount,
                final boolean arrayView,
                final GpuSampler sampler
        ) {
            final String checked = claim(name);
            this.sampled.put(
                    checked,
                    new NativeTexture(checked, texture, sampler, mipLevel, baseLayer, layerCount, arrayView)
            );
            return this;
        }

        public Builder nativeTexture(final String name, final ComputeTexture texture, final GpuSampler sampler) {
            return this.texture(name, texture, sampler);
        }

        public Builder fullArrayTexture(String name, ComputeTexture texture, GpuSampler sampler) {
            String checked = claim(name);
            sampled.put(checked, new NativeTexture(checked, texture, sampler, 0, 0, texture.layers(), true, true));
            return this;
        }

        public Builder volume(final String name, final ComputeVolume volume, final int tilingAxes) {
            return volume(name, volume, tilingAxes, null);
        }

        public Builder volume(final String name, final ComputeVolume volume, final int tilingAxes, @Nullable GpuSampler sampler) {
            final String checked = claim(name);
            this.sampled.put(checked, new VolumeTexture(checked, volume, tilingAxes, sampler));
            return this;
        }

        public Builder sampledImage(final String name, final GpuTextureView view) {
            final String checked = claim(name);
            this.sampled.put(checked, new SampledImage(checked, view));
            return this;
        }

        public Builder sampledImage(
                final String name,
                final ComputeTexture texture,
                final int mipLevel,
                final int baseLayer,
                final int layerCount,
                final boolean arrayView
        ) {
            final String checked = claim(name);
            this.sampled.put(
                    checked,
                    new NativeSampledImage(checked, texture, mipLevel, baseLayer, layerCount, arrayView)
            );
            return this;
        }

        public Builder sampledImage(final String name, final ComputeTexture texture) {
            return this.sampledImage(
                    name, texture, 0, 0,
                    texture.isArray() ? texture.layers() : 1,
                    texture.isArray()
            );
        }

        public Builder nativeSampledImage(final String name, final ComputeTexture texture) {
            return this.sampledImage(name, texture);
        }

        public Builder fullArraySampledImage(String name, ComputeTexture texture) {
            String checked = claim(name);
            sampled.put(checked, new NativeSampledImage(checked, texture, 0, 0, texture.layers(), true, true));
            return this;
        }

        public Builder image(final String name, final ComputeTexture texture, final ComputeAccess access) {
            return this.image(name, texture, access, 0, 0, texture.layers());
        }

        public Builder volumeImage(String name, ComputeVolume volume, ComputeAccess access) {
            String checked = claim(name);
            volumeImages.put(checked, new VolumeImage(checked, volume, access));
            return this;
        }
        public Builder cubeImage(String name, CubeColorTarget target, ComputeAccess access) {
            String checked = claim(name);
            cubeImages.put(checked, new CubeImage(checked, target, access));
            return this;
        }

        public Builder image(
                final String name,
                final ComputeTexture texture,
                final ComputeAccess access,
                final int mipLevel,
                final int baseLayer,
                final int layerCount
        ) {
            final String checked = claim(name);
            this.images.put(checked, new Image(checked, texture, access, mipLevel, baseLayer, layerCount));
            return this;
        }

        public Builder storage(final String name, final GpuBufferSlice slice, final ComputeAccess access) {
            final String checked = claim(name);
            this.storage.put(checked, new Storage(checked, slice, access));
            return this;
        }

        public ComputeBindings build() {
            return new ComputeBindings(this);
        }

        private String claim(final String name) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Compute binding name must not be blank");
            }
            if (this.uniforms.containsKey(name)
                    || this.sampled.containsKey(name)
                    || this.images.containsKey(name)
                    || this.volumeImages.containsKey(name)
                    || this.cubeImages.containsKey(name)
                    || this.storage.containsKey(name)) {
                throw new IllegalArgumentException("Duplicate compute binding " + name);
            }
            return name;
        }
    }
}
