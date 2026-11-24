package com.kltyton.lumenfx.api.client.parameter;

import com.kltyton.lumenfx.client.resource.texture.EffectTextures;
import com.kltyton.lumenfx.client.render.compute.ComputeTexture;
import com.kltyton.lumenfx.client.render.compute.ComputeVolume;
import com.kltyton.lumenfx.client.render.compute.format.SampledTextureFormats;
import com.kltyton.lumenfx.client.render.texture.cube.CubeFaceView;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Resolves a borrowed texture on the render thread before its simulation or material consumer. */
@FunctionalInterface
public interface TextureParameter {
    enum Dimension { D2, ARRAY, CUBE, VOLUME, CUBE_ARRAY }
    /** The producer keeps the returned image and sampler alive until the frame finishes. */
    Snapshot resolve();

    /** Extends the lifetime of this exact publication, or returns null for a frame-only borrow. */
    default @Nullable Retention retain(Snapshot snapshot) { return null; }

    /** True when every bound publication from this provider can be retained across render frames. */
    default boolean retainsAcrossFrames() { return false; }

    final class Retention implements AutoCloseable {
        private @Nullable Runnable release;
        private final @Nullable Snapshot retainedSnapshot;

        public Retention(Runnable release) { this(release, null); }
        public Retention(Runnable release, @Nullable Snapshot retainedSnapshot) {
            this.release = Objects.requireNonNull(release);
            this.retainedSnapshot = retainedSnapshot;
        }

        public Snapshot snapshot(Snapshot borrowed) { return retainedSnapshot == null ? borrowed : retainedSnapshot; }

        @Override public void close() {
            RenderSystem.assertOnRenderThread();
            Runnable action = release;
            release = null;
            if (action != null) action.run();
        }
    }

    record Snapshot(@Nullable GpuTextureView view, @Nullable GpuSampler sampler, int width, int height, int mipLevels,
                    @Nullable ComputeTexture arrayTexture, boolean cube, @Nullable ComputeVolume volume) {
        public static final Snapshot UNBOUND = new Snapshot(null, null, 0, 0, 0);
        public Snapshot(@Nullable GpuTextureView view, @Nullable GpuSampler sampler, int width, int height, int mipLevels) {
            this(view, sampler, width, height, mipLevels, null, false);
        }
        public Snapshot(@Nullable GpuTextureView view, @Nullable GpuSampler sampler, int width, int height, int mipLevels,
                        @Nullable ComputeTexture arrayTexture) {
            this(view, sampler, width, height, mipLevels, arrayTexture, false);
        }
        public Snapshot(@Nullable GpuTextureView view, @Nullable GpuSampler sampler, int width, int height, int mipLevels,
                        @Nullable ComputeTexture arrayTexture, boolean cube) {
            this(view, sampler, width, height, mipLevels, arrayTexture, cube, null);
        }

        public Snapshot {
            if (volume != null) {
                if (cube || arrayTexture != null || view != null || sampler != null || volume.isClosed()
                        || width != volume.width() || height != volume.height() || mipLevels != volume.mipLevels())
                    throw new IllegalArgumentException("Niagara volume requires its real 3D sampled image");
            } else if (arrayTexture != null) {
                if (cube || arrayTexture.isClosed() || !arrayTexture.isArray() || view != null || sampler == null
                        || width != arrayTexture.width() || height != arrayTexture.height() || mipLevels != arrayTexture.mips()
                        || !SampledTextureFormats.floatingColor(arrayTexture.texture()))
                    throw new IllegalArgumentException("Niagara array texture requires a live floating color array and its real dimensions");
            } else if (view == null ? cube || sampler != null || width != 0 || height != 0 || mipLevels != 0
                    : sampler == null || width <= 0 || height <= 0 || mipLevels <= 0 || mipLevels != view.mipLevels())
                throw new IllegalArgumentException("Invalid Niagara texture snapshot");
            if (view != null) {
                GpuTexture texture = view.texture();
                if (view.isClosed() || texture.isClosed()) throw new IllegalStateException("Niagara texture has been released by its producer");
                boolean cubeTexture = (texture.usage() & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0;
                boolean cubeArray = isCubeArray(texture);
                int layers = texture.getDepthOrLayers();
                if ((texture.usage() & GpuTexture.USAGE_TEXTURE_BINDING) == 0 || cubeTexture != cube
                        || (cubeArray ? layers < 6 || layers % 6 != 0 : layers != (cube ? 6 : 1))
                        || cube && (width != height || view instanceof CubeFaceView face && face.cubeFaceLayer() >= 0)
                        || !com.kltyton.lumenfx.client.render.compute.format.SampledTextureFormats.floatingColor(texture)
                        || width != Math.max(1, view.getWidth(0)) || height != Math.max(1, view.getHeight(0)))
                    throw new IllegalArgumentException("Niagara Texture requires a matching floating color image view");
            }
        }
        public boolean bound() { return view != null || arrayTexture != null || volume != null; }
        public Dimension dimension() {
            if (volume != null) return Dimension.VOLUME;
            if (cube) return isCubeArray(Objects.requireNonNull(view).texture()) ? Dimension.CUBE_ARRAY : Dimension.CUBE;
            if (arrayTexture != null) return Dimension.ARRAY;
            return Dimension.D2;
        }
        public int depth() { return volume == null ? 0 : volume.depth(); }
        public int layers() {
            if (arrayTexture != null) return arrayTexture.layers();
            if (cube) return Objects.requireNonNull(view).texture().getDepthOrLayers();
            return view == null ? 0 : 1;
        }
    }

    /** Selects a texture asset already prepared by the effect resource reload. */
    static TextureParameter resource(Identifier id) {
        return new Resource(id);
    }

    record Resource(Identifier id) implements TextureParameter {
        public Resource { Objects.requireNonNull(id, "texture"); }
        @Override public Snapshot resolve() {
            RenderSystem.assertOnRenderThread();
            return EffectTextures.snapshot(id);
        }
        @Override public @Nullable Retention retain(Snapshot snapshot) {
            return EffectTextures.retain(id, snapshot);
        }
        @Override public boolean retainsAcrossFrames() { return true; }
    }

    enum Unbound implements TextureParameter {
        INSTANCE;
        @Override public Snapshot resolve() { return Snapshot.UNBOUND; }
        @Override public boolean retainsAcrossFrames() { return true; }
    }

    static TextureParameter unbound() { return Unbound.INSTANCE; }

    /** Wraps the producer's current image; a provider may call this after its target has resized. */
    static Snapshot snapshot(GpuTextureView view, GpuSampler sampler) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(sampler, "sampler");
        return new Snapshot(view, sampler, Math.max(1, view.getWidth(0)), Math.max(1, view.getHeight(0)), view.mipLevels());
    }

    static Snapshot snapshotArray(ComputeTexture texture, GpuSampler sampler) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(texture, "texture array");
        return new Snapshot(null, sampler, texture.width(), texture.height(), texture.mips(), texture);
    }

    static Snapshot snapshotCube(GpuTextureView view, GpuSampler sampler) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(view, "texture cube");
        if (isCubeArray(view.texture())) throw new IllegalArgumentException("Expected a single cubemap");
        return new Snapshot(view, sampler, view.getWidth(0), view.getHeight(0), view.mipLevels(), null, true);
    }

    static Snapshot snapshotCubeArray(GpuTextureView view, GpuSampler sampler) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(view, "texture cube array");
        if (!isCubeArray(view.texture())) throw new IllegalArgumentException("Expected a native cubemap array view");
        return new Snapshot(view, sampler, view.getWidth(0), view.getHeight(0), view.mipLevels(), null, true);
    }

    private static boolean isCubeArray(GpuTexture texture) {
        return com.kltyton.lumenfx.client.resource.texture.storage.SampledTextureStorage.isCubeArray(texture);
    }

    static Snapshot snapshotVolume(ComputeVolume image) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(image, "texture volume");
        return new Snapshot(null, null, image.width(), image.height(), image.mipLevels(), null, false, image);
    }

    record Volume(ComputeVolume image) implements TextureParameter {
        public Volume { Objects.requireNonNull(image); }
        @Override public Snapshot resolve() { return image.isOwnerReleased() ? Snapshot.UNBOUND : snapshotVolume(image); }
        @Override public @Nullable Retention retain(Snapshot snapshot) {
            if (snapshot.volume() != image) return null;
            var lease = image.retain();
            return new Retention(lease::close);
        }
        @Override public boolean retainsAcrossFrames() { return true; }
    }
}
