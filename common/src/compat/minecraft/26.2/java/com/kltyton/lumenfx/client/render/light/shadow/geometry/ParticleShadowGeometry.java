package com.kltyton.lumenfx.client.render.light.shadow.geometry;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuMeshCollector;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuRibbonFrame;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSpriteCollector;
import com.kltyton.lumenfx.client.simulation.EmitterInstance;
import com.kltyton.lumenfx.effect.EffectDefinition;
import com.kltyton.lumenfx.effect.EmitterDefinition;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry;
import com.mojang.blaze3d.systems.CommandEncoder;
import java.util.ArrayList;
import java.util.List;

/** Eligibility for the particle portion of the native point shadow pass. */
public final class ParticleShadowGeometry {
    private ParticleShadowGeometry() { }

    public record CpuSource(EffectDefinition effect, EmitterInstance.Snapshot snapshot) { }

    @FunctionalInterface
    public interface GpuSource {
        void append(CommandEncoder encoder, PointShadowParticleView view, GpuDraws output);
    }

    public record GpuDraws(List<NiagaraGpuSpriteCollector.Task> sprites, List<NiagaraGpuRibbonFrame> ribbons,
                           List<NiagaraGpuMeshCollector.Task> meshes) {
        public GpuDraws() { this(new ArrayList<>(), new ArrayList<>(), new ArrayList<>()); }
    }

    public static boolean casts(EmitterDefinition definition, EffectHandle.Input input) {
        return definition.castsShadows() && input.primitiveLighting().castsDynamicShadow() && !input.firstPerson()
                && switch (definition.renderer()) {
                    case SPRITE, RIBBON, MESH -> true;
                    default -> false;
                };
    }

    public static boolean casts(MaterialDefinition material) {
        return material.pointShadowCoverage() != MaterialDefinition.PointShadowCoverage.NONE;
    }

    public static boolean casts(StaticMeshGeometry.Section section) {
        return section.info() == null || section.info().castShadow();
    }
}
