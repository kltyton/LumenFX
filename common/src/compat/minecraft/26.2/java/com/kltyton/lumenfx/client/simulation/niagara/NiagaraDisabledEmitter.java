package com.kltyton.lumenfx.client.simulation.niagara;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.client.simulation.EmitterInstance;
import com.kltyton.lumenfx.client.simulation.niagara.render.NiagaraEmitterRenderState;
import com.kltyton.lumenfx.effect.EffectDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.render.decal.ParticleDecal;
import com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight;
import com.kltyton.lumenfx.effect.render.light.scene.SceneDirectionalLight;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

/** A disabled source handle retains its native instance slot and query data, without execution or renderer state. */
final class NiagaraDisabledEmitter implements NiagaraCpuEmitter, NiagaraParticleReadSource {
    private static final int[] NO_IDS = new int[0];
    private final NiagaraDefinition.Emitter source;
    private final VectorDataSet particles = new VectorDataSet(0, 0, 0);
    private final byte[] runtime = new byte[640];

    NiagaraDisabledEmitter(NiagaraDefinition.Emitter source, NiagaraInterfaces interfaces) {
        this.source = source;
        var disabled = (NiagaraDefinition.DisabledSimulation)source.simulation();
        ByteBuffer.wrap(runtime).order(ByteOrder.LITTLE_ENDIAN).putFloat(616, 1f);
        if (disabled.hasEmitter()) interfaces.registerParticleEmitter(source.sourceName(), this);
    }

    @Override public byte[] prepareRuntime(NiagaraFrame frame) {
        System.arraycopy(frame.current, 0, runtime, 0, 608);
        return runtime;
    }
    @Override public List<EmitterInstance.Snapshot> simulate(NiagaraFrame frame, VectorDataSet system,
            Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input, double now, boolean execute, boolean pack) {
        return List.of();
    }
    @Override public List<EmitterInstance.Snapshot> packCullProxy(NiagaraEmitterRenderState renderer, VectorDataSet system,
            Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input, double now) {
        return List.of();
    }
    @Override public int count() { return 0; }
    @Override public boolean complete() { return true; }
    @Override public boolean shouldTick() { return false; }
    @Override public boolean done() { return true; }
    @Override public void complete(int state) { }
    @Override public void reset() { }
    @Override public void postSystemTick(ClientLevel level, NiagaraFrame frame, EffectHandle.Input input, VectorDataSet system,
            Map<String, NiagaraDefinition.Variable> variables, double realTime) { }
    @Override public void closeComponents() { }
    @Override public void destroyComponentRenderState() { }
    @Override public void updateComponentAttachments(EffectHandle.Pose pose) { }
    @Override public void collectPointLights(EffectDefinition effect, List<ScenePointLight> output) { }
    @Override public void collectDirectionalLights(EffectDefinition effect, List<SceneDirectionalLight> output) { }
    @Override public void collectDecals(EffectDefinition effect, List<ParticleDecal> output) { }
    @Override public void finishComponentFrame(boolean paused) { }
    @Override public VectorDataSet particleReadData(boolean previous) { return particles; }
    @Override public int[] particleReadIndex(boolean previous) { return NO_IDS; }
    @Override public int[] eventParticleReadIndex() { return NO_IDS; }
    @Override public int spawnedParticleCount() { return 0; }
    @Override public int spawnedParticleId(int index) { throw new IndexOutOfBoundsException(index); }
    @Override public int spawnedParticleTag() { return 0; }
    @Override public int frameSpawned(boolean previous) { return 0; }
    @Override public boolean particleReadLocalSpace() { return source.localSpace(); }
    @Override public String particleReadName() { return source.sourceName(); }
    @Override public NiagaraDefinition.@Nullable Attribute particleReadAttribute(String name, String type) { return null; }
    @Override public int particleIndex(VectorDataSet data, int[] table, int id, int acquireTag) { return -1; }
}
