package com.kltyton.lumenfx.client.resource.texture;

import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.client.render.compute.ComputeTexture;
import com.kltyton.lumenfx.client.render.compute.ComputeVolume;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTextureArray;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTextureVolume;
import com.kltyton.lumenfx.client.resource.texture.target.RenderTargetTextureCube;
import com.kltyton.lumenfx.client.resource.texture.target.DimensionalRenderTarget;
import com.kltyton.lumenfx.client.resource.texture.storage.NativeTextureUpload;
import com.kltyton.lumenfx.client.resource.texture.storage.SampledTextureStorage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.resources.Identifier;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Render-thread GPU resources for one texture with its source block and color format. */
public final class EffectTexture implements AutoCloseable {
    private final Identifier id;
    private final @Nullable GpuTexture texture;
    private final @Nullable GpuTextureView view;
    private final @Nullable ComputeTexture array;
    private final @Nullable ComputeVolume volume;
    private final @Nullable GpuSampler sampler;
    private final @Nullable TextureMetadata metadata;
    private final @Nullable DimensionalRenderTarget target;
    private boolean closed;
    private int references = 1;

    public EffectTexture(Identifier id, GpuTexture texture, GpuTextureView view, GpuSampler sampler,
                         @Nullable TextureMetadata metadata) {
        this.id = id;
        this.texture = texture;
        this.view = view;
        array = null;
        volume = null;
        this.sampler = sampler;
        this.metadata = metadata;
        target = null;
    }

    EffectTexture(Identifier id, ComputeTexture array, GpuSampler sampler, @Nullable TextureMetadata metadata) {
        this.id = id;
        this.texture = array.texture();
        this.view = null;
        this.array = array;
        volume = null;
        this.sampler = sampler;
        this.metadata = metadata;
        target = null;
    }

    EffectTexture(Identifier id, DimensionalRenderTarget target) {
        this.id = id;
        this.target = target;
        texture = null;
        view = null;
        array = null;
        volume = null;
        sampler = null;
        metadata = null;
    }

    EffectTexture(Identifier id, ComputeVolume volume, TextureMetadata metadata) {
        this.id = id;
        this.volume = volume;
        this.metadata = metadata;
        texture = null;
        view = null;
        array = null;
        sampler = null;
        target = null;
    }

    public Identifier id() { return id; }

    public GpuTextureView view() {
        return Objects.requireNonNull(target == null ? view : target.resolve().view(), "Texture requires its native sampled view");
    }

    public TextureParameter.Dimension dimension() {
        if (target != null) return target.dimension();
        if (volume != null) return TextureParameter.Dimension.VOLUME;
        if (array != null) return TextureParameter.Dimension.ARRAY;
        if (texture != null && SampledTextureStorage.isCubeArray(texture)) return TextureParameter.Dimension.CUBE_ARRAY;
        return texture != null && (texture.usage() & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0
                ? TextureParameter.Dimension.CUBE : TextureParameter.Dimension.D2;
    }

    public GpuTextureView rasterView() {
        return switch (dimension()) {
            case ARRAY -> arrayView();
            case CUBE, CUBE_ARRAY, D2 -> view();
            case VOLUME -> volumeImage().rasterView();
        };
    }

    public GpuTextureView arrayView() {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Texture has been released: " + id);
        var image = target == null ? array : target.resolve().arrayTexture();
        var selected = Objects.requireNonNull(image, "Texture does not have a prepared array image");
        return selected.rasterArrayView(() -> NativeTextureUpload.createRasterArrayView(RenderSystem.getDevice(), selected));
    }

    TextureParameter.Snapshot snapshot() {
        if (target != null) return target.resolve();
        if (volume != null) return TextureParameter.snapshotVolume(volume);
        if (texture != null && SampledTextureStorage.isCubeArray(texture)) return TextureParameter.snapshotCubeArray(view(), sampler());
        if (texture != null && (texture.usage() & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0)
            return TextureParameter.snapshotCube(view(), sampler());
        return array == null ? TextureParameter.snapshot(view(), sampler()) : TextureParameter.snapshotArray(array, sampler());
    }

    public GpuSampler sampler() {
        if (dimension() == TextureParameter.Dimension.VOLUME) return volumeImage().rasterSampler();
        return Objects.requireNonNull(target == null ? sampler : target.resolve().sampler(), "Texture sampler is unavailable");
    }

    private ComputeVolume volumeImage() {
        return Objects.requireNonNull(target == null ? volume : target.resolve().volume(),
                "Texture does not have a prepared volume image: " + id);
    }
    @Nullable RenderTargetTextureArray targetArray() { return target instanceof RenderTargetTextureArray arrayTarget ? arrayTarget : null; }
    @Nullable RenderTargetTextureVolume targetVolume() { return target instanceof RenderTargetTextureVolume volumeTarget ? volumeTarget : null; }
    @Nullable RenderTargetTextureCube targetCube() { return target instanceof RenderTargetTextureCube cubeTarget ? cubeTarget : null; }

    public TextureMetadata metadata() { return Objects.requireNonNull(metadata, "Render targets use their source render target settings"); }

    public int mipLevels() {
        if (target != null) return target.settings().mipLevels();
        return volume != null ? volume.mipLevels() : Objects.requireNonNull(texture).getMipLevels();
    }

    TextureParameter.@Nullable Retention retain(TextureParameter.Snapshot snapshot) {
        RenderSystem.assertOnRenderThread();
        if (target != null) return closed ? null : target.retain(snapshot);
        if (closed || snapshot.view() != view || snapshot.arrayTexture() != array || snapshot.volume() != volume
                || snapshot.sampler() != sampler) return null;
        var hold = new TextureParameter.Retention(this::release);
        references = Math.incrementExact(references);
        return hold;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (target != null) { target.close(); return; }
        release();
    }

    private void release() {
        if (--references != 0) return;
        if (volume != null) { volume.close(); return; }
        try {
            if (view != null) view.close();
        }
        finally {
            try { sampler().close(); }
            finally { if (array == null) Objects.requireNonNull(texture).close(); else array.close(); }
        }
    }
}
