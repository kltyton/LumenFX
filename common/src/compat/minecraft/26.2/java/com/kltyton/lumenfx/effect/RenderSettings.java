package com.kltyton.lumenfx.effect;

import com.kltyton.lumenfx.effect.render.fog.FogSettings;
import com.kltyton.lumenfx.effect.render.fog.VolumetricFog;
import com.kltyton.lumenfx.effect.render.light.scene.SceneIndirectLighting;
import com.kltyton.lumenfx.effect.render.mesh.MeshLodPolicy;

/** Inputs requested by particle materials and mesh renderers. */
public record RenderSettings(boolean hdr, FogSettings fog, MeshLodPolicy meshLod, SceneIndirectLighting indirectLighting,
                             BlendColorSpace blendColorSpace) {
    public enum BlendColorSpace { LINEAR, UNITY_GAMMA }

    public static final RenderSettings STANDARD = new RenderSettings(false, FogSettings.HOST,
            MeshLodPolicy.DEFAULT, SceneIndirectLighting.NONE);

    public RenderSettings(boolean hdr, FogSettings fog, MeshLodPolicy meshLod, SceneIndirectLighting indirectLighting) {
        this(hdr, fog, meshLod, indirectLighting, BlendColorSpace.LINEAR);
    }

    public RenderSettings {
        if (fog == null || meshLod == null || indirectLighting == null || blendColorSpace == null)
            throw new IllegalArgumentException("Invalid VFX render settings");
        if (fog instanceof VolumetricFog && !hdr)
            throw new IllegalArgumentException("Source volume fog requires the linear scene capture");
    }
}
