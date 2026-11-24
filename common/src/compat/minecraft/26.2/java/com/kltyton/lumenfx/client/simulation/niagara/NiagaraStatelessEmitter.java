package com.kltyton.lumenfx.client.simulation.niagara;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.client.simulation.EmitterInstance;
import com.kltyton.lumenfx.client.simulation.niagara.cache.NiagaraCachePlayer;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraRendererParameterStores;
import com.kltyton.lumenfx.client.simulation.niagara.render.NiagaraEmitterRenderState;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessCpuProgram;
import com.kltyton.lumenfx.effect.EffectDefinition;
import com.kltyton.lumenfx.effect.EmitterDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Sample;
import com.kltyton.lumenfx.effect.niagara.NiagaraParticleReadTypes;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.effect.render.decal.ParticleDecal;
import com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight;
import com.kltyton.lumenfx.effect.render.light.scene.SceneDirectionalLight;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Analytic CPU emitters share renderer ownership while keeping spawn schedules and random state independent. */
final class NiagaraStatelessEmitter implements NiagaraCpuEmitter, NiagaraParticleReadSource {
    private static final int[] NO_IDS = new int[0];
    private final NiagaraDefinition.Emitter source;
    private final VectorDataSet particles;
    private final StatelessCpuProgram program;
    private final NiagaraEmitterRenderState renderState;
    private final NiagaraEmitterBounds bounds;
    private final byte[] spawnParameters, updateParameters, runtime = new byte[640];
    private final ByteBuffer runtimeWords = ByteBuffer.wrap(runtime).order(ByteOrder.LITTLE_ENDIAN);
    private final int parentSeedOffset;
    private @Nullable NiagaraRendererParameterStores rendererParameters;
    private @Nullable VectorDataSet cachedParticles;
    private float cacheAge;

    void bindRendererParameters(NiagaraRendererParameterStores renderer) { rendererParameters = renderer; }

    NiagaraStatelessEmitter(NiagaraDefinition definition, NiagaraDefinition.Emitter source, List<EmitterDefinition> renderers,
                           NiagaraInterfaces interfaces, byte[] spawnParameters, byte[] updateParameters, int parentSeedOffset) {
        this(definition, source, renderers, interfaces, spawnParameters, updateParameters, parentSeedOffset, NiagaraPlatforms.snapshot());
    }
    NiagaraStatelessEmitter(NiagaraDefinition definition, NiagaraDefinition.Emitter source, List<EmitterDefinition> renderers,
                           NiagaraInterfaces interfaces, byte[] spawnParameters, byte[] updateParameters, int parentSeedOffset,
                           NiagaraPlatformEnvironment platform) {
        this.source = source; this.spawnParameters = spawnParameters; this.updateParameters = updateParameters;
        this.parentSeedOffset = parentSeedOffset;
        runtimeWords.putFloat(616, 1f);
        int floats = 0, integers = 0;
        for (var attribute : source.attributes()) {
            if (attribute.type() == 0) floats = Math.max(floats, attribute.component() + attribute.count());
            else if (attribute.type() == 1) integers = Math.max(integers, attribute.component() + attribute.count());
            else throw new IllegalArgumentException("Invalid stateless particle storage type " + attribute.type());
        }
        particles = new VectorDataSet(floats, integers, 0);
        program = new StatelessCpuProgram(definition, source, particles, instanceSeed(), platform);
        bounds = Objects.requireNonNull(interfaces.emitterBounds(source.sourceName()));
        renderState = new NiagaraEmitterRenderState(source, renderers, true, interfaces.materialTextures());
        interfaces.registerParticleEmitter(source.sourceName(), this);
    }

    private int instanceSeed() {
        int seed = NiagaraSeed.offset(source.randomSeed(), parentSeedOffset);
        return source.deterministic() ? seed : seed ^ NiagaraSeed.next();
    }
    @Override public byte[] prepareRuntime(NiagaraFrame frame) {
        System.arraycopy(frame.current, 0, runtime, 0, 608);
        // The native system's AsStateful gather excludes analytic emitters and retains these struct defaults.
        runtimeWords.putInt(608, 0);
        runtimeWords.putInt(612, 0);
        return runtime;
    }
    @Override public List<EmitterInstance.Snapshot> simulate(NiagaraFrame frame, VectorDataSet system,
            Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input, double now, boolean execute, boolean pack) {
        if (execute && program.shouldTick()) {
            program.tick(frame.delta, frame.systemExecutionState, spawnParameters, updateParameters, runtime,
                    input, frame.originX(), frame.originY(), frame.originZ(), frame.collections, frame.attachmentBirthsAllowed);
            bounds.postTick(null);
        }
        if (!pack || cachedParticles == null && program.complete()) return List.of();
        return renderState.snapshots(renderParticles(), 0, 0, 0, system, variables, input, now, renderAge(),
                rendererParameters == null ? null : rendererParameters.renderer(), frame.engineTime);
    }

    @Override
    public List<EmitterInstance.Snapshot> packCullProxy(NiagaraEmitterRenderState renderer, VectorDataSet system,
            Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input, double now) {
        if (!renderer.hasVisuals() || (cachedParticles == null && program.complete())) return List.of();
        return renderer.snapshots(renderParticles(), 0, 0, 0, system, variables, input, now, renderAge(),
                rendererParameters == null ? null : rendererParameters.renderer());
    }
    void readCache(NiagaraCachePlayer playback, int index, Sample sample, NiagaraFrame frame, EffectHandle.Input input, Vec3 tile) {
        if (!program.canEverExecute()) return;
        if (cachedParticles == null) {
            cachedParticles = new VectorDataSet(particles.componentCount(0), particles.componentCount(1), particles.componentCount(2));
        }
        var saved = playback.readEmitter(index, sample, frame.delta, input.pose(), tile, cachedParticles);
        cacheAge = playback.age();
        bounds.readCache(saved.bounds());
    }

    void leaveCache() { cachedParticles = null; }
    private VectorDataSet renderParticles() { return cachedParticles == null ? particles : cachedParticles; }
    private float renderAge() { return cachedParticles == null ? program.age() : cacheAge; }

    void spawningAllowed(boolean allowed) { program.spawningAllowed(allowed); }
    @Override public int count() { return cachedParticles == null ? program.countEstimate() : cachedParticles.count(); }
    @Override public void complete(int state) { program.complete(state); }
    @Override public boolean complete() { return program.complete(); }
    @Override public boolean shouldTick() { return program.shouldTick(); }
    @Override public boolean done() { return program.complete(); }
    @Override public void reset() {
        leaveCache();
        if (!program.canEverExecute()) return;
        program.reset(source.deterministic() ? program.seed() : program.seed() ^ NiagaraSeed.next());
        renderState.reset();
    }
    void resetWithSeedOffset(int offset) {
        leaveCache();
        if (!program.canEverExecute()) return;
        int seed = NiagaraSeed.offset(source.randomSeed(), offset);
        program.reset(source.deterministic() ? seed : seed ^ NiagaraSeed.next());
        renderState.reset();
    }
    void restartKeepingSpawns() {
        if (!program.canEverExecute()) return;
        program.reset(false, program.seed());
    }
    @Override public void postSystemTick(ClientLevel level, NiagaraFrame frame, EffectHandle.Input input, VectorDataSet system,
                                         Map<String, NiagaraDefinition.Variable> variables, double realTime) {
        if (cachedParticles != null || !program.complete())
            renderState.postTick(level, renderParticles(), 0, 0, 0, 0, input, system, variables, realTime,
                    frame.platform.componentPoolInactiveSeconds(), frame.ownerVisibility, frame.componentHost,
                    rendererParameters == null ? null : rendererParameters.renderer());
    }
    @Override public void closeComponents() { renderState.close(); }
    @Override public void destroyComponentRenderState() { renderState.destroyRenderState(); }
    @Override public void updateComponentAttachments(EffectHandle.Pose pose) { renderState.updateAttachment(pose); }
    @Override public void collectPointLights(EffectDefinition effect, List<ScenePointLight> output) { renderState.collectPointLights(effect, output); }
    @Override public void collectDirectionalLights(EffectDefinition effect, List<SceneDirectionalLight> output) { renderState.collectDirectionalLights(effect, output); }
    @Override public void collectDecals(EffectDefinition effect, List<ParticleDecal> output) { renderState.collectDecals(effect, output); }
    @Override public void finishComponentFrame(boolean paused) { renderState.finishFrame(paused); }

    @Override public VectorDataSet particleReadData(boolean previous) { return renderParticles(); }
    // Stateless data has UniqueID, but the native analytic backend never creates a persistent NiagaraID table or spawned-ID list.
    @Override public int[] particleReadIndex(boolean previous) { return NO_IDS; }
    @Override public int[] eventParticleReadIndex() { return NO_IDS; }
    @Override public int spawnedParticleCount() { return 0; }
    @Override public int spawnedParticleId(int index) { throw new IndexOutOfBoundsException(index); }
    @Override public int spawnedParticleTag() { return 0; }
    @Override public int frameSpawned(boolean previous) { return 0; }
    @Override public boolean particleReadLocalSpace() { return true; }
    @Override public String particleReadName() { return source.sourceName(); }
    @Override public NiagaraDefinition.@Nullable Attribute particleReadAttribute(String name, String type) {
        return NiagaraParticleReadTypes.resolve(source.attributes(), name, type);
    }
    @Override public int particleIndex(VectorDataSet data, int[] table, int id, int acquireTag) { return -1; }
}
