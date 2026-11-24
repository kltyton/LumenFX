package com.kltyton.lumenfx.client.render.niagara;

import com.lowdragmc.photon.client.render.PhotonTextureCopy;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.joml.Matrix4fc;

/** A sampleable snapshot of the scene depth preceding a particle draw. */
public final class ParticleSceneDepth implements AutoCloseable {
    private final PhotonTextureCopy snapshot = new PhotonTextureCopy("Niagara.SceneDepth", 0);

    public void capture(CommandEncoder encoder, RenderTarget source) {
        GpuTextureView depth = source.getDepthTextureView();
        if (depth == null) throw new IllegalStateException("Particle material requires scene depth");
        if (snapshot.copyOf(encoder, depth) == null) {
            throw new IllegalStateException("Particle material cannot sample the current scene depth");
        }
    }

    public GpuTextureView view() {
        var view = snapshot.view();
        if (view == null) throw new IllegalStateException("Particle scene depth has not been captured");
        return view;
    }

    public static float[] depthTransform(Matrix4fc projection, boolean zZeroToOne) {
        if (projection.m33() < 1f)
            return NiagaraViewMath.inverseDeviceZToWorldZTransform(projection, 100f, zZeroToOne);
        float alpha = zZeroToOne ? 1f : .5f, beta = zZeroToOne ? 0f : .5f;
        float scale = alpha * projection.m22(), bias = beta + alpha * projection.m32();
        if (scale == 0f) throw new IllegalArgumentException("Orthographic projection has no depth scale");
        return new float[] {-100f / scale, 100f * bias / scale + 1f, 0f, 1f};
    }

    @Override public void close() {
        snapshot.close();
    }
}
