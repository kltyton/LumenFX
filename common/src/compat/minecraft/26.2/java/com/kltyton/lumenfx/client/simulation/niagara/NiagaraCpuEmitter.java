package com.kltyton.lumenfx.client.simulation.niagara;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.client.component.mesh.StaticMeshComponentDraw;
import org.jspecify.annotations.Nullable;
import com.kltyton.lumenfx.client.simulation.EmitterInstance;
import com.kltyton.lumenfx.client.simulation.niagara.render.NiagaraEmitterRenderState;
import com.kltyton.lumenfx.effect.EffectDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.render.decal.ParticleDecal;
import com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight;
import com.kltyton.lumenfx.effect.render.light.scene.SceneDirectionalLight;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.multiplayer.ClientLevel;

/** The system schedules CPU simulation and renderer lifetime without assuming a VM implementation. */
interface NiagaraCpuEmitter {
    byte[] prepareRuntime(NiagaraFrame frame);
    List<EmitterInstance.Snapshot> simulate(NiagaraFrame frame, VectorDataSet system,
            Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input, double now, boolean execute, boolean pack);
    List<EmitterInstance.Snapshot> packCullProxy(NiagaraEmitterRenderState renderer, VectorDataSet system,
            Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input, double now);
    int count();
    void complete(int state);
    boolean complete();
    boolean shouldTick();
    boolean done();
    void reset();
    void postSystemTick(ClientLevel level, NiagaraFrame frame, EffectHandle.Input input, VectorDataSet system,
                        Map<String, NiagaraDefinition.Variable> variables, double realTime);
    void closeComponents();
    void destroyComponentRenderState();
    void updateComponentAttachments(EffectHandle.Pose pose);
    void collectPointLights(EffectDefinition effect, List<ScenePointLight> output);
    void collectDirectionalLights(EffectDefinition effect, List<SceneDirectionalLight> output);
    void collectDecals(EffectDefinition effect, List<ParticleDecal> output);
    default void collectComponentMeshes(EffectDefinition effect, @Nullable UUID viewActor,
                                        List<StaticMeshComponentDraw> output) { }
    void finishComponentFrame(boolean paused);
}
