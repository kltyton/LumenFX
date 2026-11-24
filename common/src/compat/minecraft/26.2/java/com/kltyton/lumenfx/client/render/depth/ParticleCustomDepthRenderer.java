package com.kltyton.lumenfx.client.render.depth;

import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.client.render.EffectFrame;
import com.kltyton.lumenfx.client.render.EffectGpuRenderer;
import com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture;
import com.kltyton.lumenfx.client.render.mesh.nanite.NaniteWorldRenderer;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraSceneInputs;
import com.mojang.blaze3d.pipeline.RenderTarget;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/** Adds the prepared Niagara primitives to the current custom depth and stencil surfaces. */
public final class ParticleCustomDepthRenderer implements AutoCloseable {
    public record Settings(int mode, boolean beforeBasePass, boolean temporalJitter) {
        public static Settings capture() {
            var platform = NiagaraPlatforms.snapshot();
            int mode = Objects.requireNonNull(platform.console("r.CustomDepth")).bits();
            int order = Objects.requireNonNull(platform.console("r.CustomDepth.Order")).bits();
            boolean jitter = Objects.requireNonNull(platform.console("r.CustomDepthTemporalAAJitter")).bits() != 0;
            return new Settings(mode, order == 0 || order == 2, jitter);
        }
    }

    private final ParticleCustomDepthTargets targets = new ParticleCustomDepthTargets();
    private final EffectGpuRenderer raster = new EffectGpuRenderer(EffectGpuRenderer.Pass.CUSTOM_DEPTH);

    public NiagaraSceneInputs prepare(EffectFrame frame, Matrix4fc modelView, Quaternionf orientation,
                                      Vec3 camera, RenderTarget scene, NiagaraSceneInputs inputs, Settings settings,
                                      NaniteWorldRenderer.@Nullable Frame nanite) {
        int mode = settings.mode();
        if (mode < 1 || mode > 3) {
            targets.close();
            return inputs;
        }
        var geometry = frame.customDepthGeometry();
        boolean nativeDepth = nanite != null && nanite.surfaces().stream().anyMatch(NaniteWorldRenderer.Surface::writesCustomDepth);
        if (!geometry.hasGeometry() && !nativeDepth) {
            if (mode == 1) targets.close();
            return inputs;
        }
        var projection = new Matrix4f();
        if (settings.temporalJitter()) WorldHdrCapture.INSTANCE.copyProjection(projection);
        else WorldHdrCapture.INSTANCE.copyUnjitteredProjection(projection);
        raster.drawCustomDepth(geometry, modelView, orientation, camera, scene, projection, targets, inputs, mode == 3,
                nativeDepth ? nanite : null);
        return inputs.withCustomDepth(targets.depth(), targets.stencil(), settings.beforeBasePass());
    }

    @Override public void close() {
        raster.close();
        targets.close();
    }
}
