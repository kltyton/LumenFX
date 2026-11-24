package com.kltyton.lumenfx.client.render.light.shadow.geometry;

import com.kltyton.lumenfx.client.component.mesh.StaticMeshComponentDraw;
import com.kltyton.lumenfx.api.client.render.VfxRenderViews;
import com.kltyton.lumenfx.client.render.EffectFrame;
import com.kltyton.lumenfx.client.render.EffectGpuRenderer;
import com.kltyton.lumenfx.client.render.EffectMeshBuilder;
import com.kltyton.lumenfx.client.render.light.shadow.ShadowRasterViews;
import com.kltyton.lumenfx.client.render.light.shadow.geometry.ParticleShadowGeometry.CpuSource;
import com.kltyton.lumenfx.client.render.light.shadow.geometry.ParticleShadowGeometry.GpuSource;
import com.kltyton.lumenfx.client.render.mesh.CpuMeshSceneCollector;
import com.kltyton.lumenfx.client.render.mesh.nanite.NaniteWorldRenderer;
import com.kltyton.lumenfx.client.render.mesh.scene.MeshScenePrograms;
import com.kltyton.lumenfx.client.render.mesh.scene.MeshSceneView;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuMeshCollector;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuMeshFrame;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSpriteCollector;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSpriteFrame;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraSceneInputs;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraSceneInputs.Atmosphere;
import com.kltyton.lumenfx.client.render.texture.ShadowDepthTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Repacks the prepared CPU and GPU particle datasets without advancing simulation or replacing display draw buffers. */
public final class ParticlePointShadowRenderer implements AutoCloseable {
    private final EffectMeshBuilder cpu = new EffectMeshBuilder(0);
    private final EffectGpuRenderer raster = new EffectGpuRenderer(EffectGpuRenderer.Pass.POINT_SHADOW);
    private @Nullable NiagaraGpuSpriteCollector sprites;
    private @Nullable NiagaraGpuMeshCollector meshes;
    private @Nullable CpuMeshSceneCollector cpuMeshes;

    public void draw(EffectFrame frame, List<CpuSource> cpuSources, List<StaticMeshComponentDraw> componentMeshes,
                     List<GpuSource> gpuSources, PointShadowParticleView view,
                     RenderTarget scene, ShadowDepthTarget target, ShadowRasterViews matrices, int first, int end,
                     @Nullable NiagaraGpuPrograms programs, @Nullable MeshScenePrograms meshScene,
                     NaniteWorldRenderer.@Nullable Frame nanite, @Nullable Atmosphere atmosphere, Vec3 sceneCamera) {
        RenderSystem.assertOnRenderThread();
        try (var scope = VfxRenderViews.shadow()) {
            Vec3 cameraVelocity = frame.motionView() == null ? Vec3.ZERO : frame.motionView().cameraVelocity();
            cpu.begin(view.camera(), view.orientation(), view.meshView(), view.ribbonLodDistanceFactor(), view.modelView(),
                    frame.deltaTime(), cameraVelocity);
            cpu.computeSortingAvailable(programs != null);
            for (var source : cpuSources) cpu.add(source.effect(), source.snapshot());
            for (var component : componentMeshes) cpu.add(component);
            var cpuDraws = cpu.finish();
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            var draws = new ParticleShadowGeometry.GpuDraws();
            for (var source : gpuSources) source.append(encoder, view, draws);
            if (!cpu.sceneMeshes().isEmpty() && cpuMeshes == null)
                cpuMeshes = new CpuMeshSceneCollector(Objects.requireNonNull(programs, "Point shadow CPU scene meshes require Niagara programs"));
            if (cpuMeshes != null)
                cpuMeshes.append(encoder, cpu.sceneMeshes(), view.camera(), view.orientation(), view.meshView(), draws.meshes());
            if (nanite != null) nanite.replaceShadowFallbacks(draws.meshes());
            List<NiagaraGpuSpriteFrame> spriteFrames = List.of();
            if (!draws.sprites().isEmpty()) {
                if (sprites == null) sprites = new NiagaraGpuSpriteCollector(Objects.requireNonNull(programs));
                spriteFrames = sprites.prepare(encoder, draws.sprites());
            }
            List<NiagaraGpuMeshFrame> meshFrames = List.of();
            if (!draws.meshes().isEmpty()) {
                if (meshes == null) meshes = new NiagaraGpuMeshCollector(Objects.requireNonNull(programs));
                meshFrames = meshes.prepare(encoder, draws.meshes(), meshScene,
                        new MeshSceneView(view.orientation(), view.camera(), null, frame.deltaTime()), null);
            }
            if (!cpuDraws.isEmpty() || !spriteFrames.isEmpty() || !draws.ribbons().isEmpty() || !meshFrames.isEmpty()) {
                var casters = new EffectFrame(frame.generation(), frame.time(), frame.engineTime(), frame.realTime(),
                        cpuDraws, spriteFrames, draws.ribbons(), frame.deltaTime(), meshFrames);
                var inputs = NiagaraSceneInputs.EMPTY;
                if (atmosphere != null) inputs = inputs.withAtmosphere(atmosphere.forViewOrigin(sceneCamera, view.camera()));
                raster.drawParticleShadow(casters, view, scene, target, matrices, first, end, inputs);
            }
            if (nanite != null && nanite.hasShadows())
                raster.drawNaniteShadows(nanite, view, scene, target, matrices, first, end, atmosphere);
        }
    }

    @Override public void close() {
        cpu.clear();
        raster.close();
        if (sprites != null) { sprites.close(); sprites = null; }
        if (meshes != null) { meshes.close(); meshes = null; }
        if (cpuMeshes != null) { cpuMeshes.close(); cpuMeshes = null; }
    }
}
