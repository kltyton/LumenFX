package com.kltyton.lumenfx.client.simulation.niagara;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.client.render.niagara.stateless.NiagaraStatelessSimulation;
import com.kltyton.lumenfx.effect.render.scene.ScenePrimitiveIds;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessEmitterFrame;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessEmitterState;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.math.SourceFloatMath;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Simulation ownership publishes analytic state; render ownership applies updates before demand generation. */
final class NiagaraStatelessGpuEmitterState {
    private record Update(@Nullable StatelessEmitterFrame frame, boolean reset, int restartSeed) {
        Update(StatelessEmitterFrame frame, boolean reset) { this(frame, reset, 0); }
    }
    private final NiagaraDefinition.Emitter source;
    private final long renderKey = ScenePrimitiveIds.next();
    private final StatelessEmitterState state;
    private final byte[] runtime = new byte[640];
    private final List<Update> updates = new ArrayList<>();
    private @Nullable StatelessEmitterFrame rendered;
    private @Nullable NiagaraStatelessSimulation simulation;
    private int queuedTicks;
    private volatile int count;
    private boolean reuseAllocations = true;
    private int cpuThreshold;

    NiagaraStatelessGpuEmitterState(NiagaraDefinition definition, NiagaraDefinition.Emitter source,
                                    int parentSeedOffset, NiagaraPlatformEnvironment platform) {
        this.source = source;
        int seed = NiagaraSeed.offset(source.randomSeed(), parentSeedOffset);
        if (!source.deterministic()) seed ^= NiagaraSeed.next();
        state = new StatelessEmitterState(definition, source, seed, platform);
        ByteBuffer.wrap(runtime).order(ByteOrder.LITTLE_ENDIAN).putFloat(616, 1f);
        if (state.canEverExecute()) simulation = new NiagaraStatelessSimulation(source, state);
    }
    byte[] prepareRuntime(NiagaraFrame frame) {
        System.arraycopy(frame.current, 0, runtime, 0, 608);
        count = (int)state.countEstimate();
        reuseAllocations = frame.platform.statelessUseCache();
        cpuThreshold = frame.platform.statelessCpuThreshold();
        return runtime;
    }
    boolean tick(NiagaraFrame frame, int parentState, byte[] spawn, byte[] update, EffectHandle.Input input, int capacity) {
        if (!state.shouldTick()) return false;
        if (queuedTicks >= capacity) throw new IllegalStateException("Niagara analytic updates exceed the declared batch capacity");
        state.updateParameters(spawn, update, runtime, input, frame.originX(), frame.originY(), frame.originZ(), frame.collections);
        ByteBuffer engine = ByteBuffer.wrap(runtime).order(ByteOrder.LITTLE_ENDIAN);
        state.tick(frame.delta, parentState, engine.getFloat(32), engine.getFloat(36), frame.attachmentBirthsAllowed);
        updates.add(new Update(state.capture(runtime), false));
        queuedTicks++;
        count = (int)state.countEstimate();
        return true;
    }
    void beginBatch() {
        if (queuedTicks != 0) throw new IllegalStateException("Previous Niagara analytic updates have not been consumed");
    }
    @Nullable StatelessEmitterFrame renderFrame() {
        for (Update update : updates) {
            StatelessEmitterFrame frame = update.frame();
            if (frame == null) {
                if (rendered != null) rendered = rendered.restartClock(update.restartSeed());
                continue;
            }
            float oldAge = rendered == null ? 0f : rendered.age();
            float delta = update.reset() ? 0f : SourceFloatMath.max(frame.age() - oldAge, 0f);
            rendered = frame.withDelta(delta);
        }
        updates.clear(); queuedTicks = 0;
        return rendered;
    }
    NiagaraStatelessSimulation simulation() {
        if (simulation == null) throw new IllegalStateException("Niagara analytic instance resources have been released");
        return simulation;
    }
    int estimatedCount(int ignoredReadback) { return count; }
    void spawningAllowed(boolean allowed) { state.spawningAllowed(allowed); }
    boolean shouldTick() { return state.shouldTick(); }
    boolean complete() { return state.complete(); }
    boolean reuseAllocations() { return reuseAllocations; }
    long renderKey() { return renderKey; }
    int cpuThreshold() { return cpuThreshold; }
    void complete(int executionState) {
        state.complete(executionState);
        updates.add(new Update(state.capture(runtime), false));
    }
    void reset() {
        if (!state.canEverExecute()) return;
        int seed = source.deterministic() ? state.seed() : state.seed() ^ NiagaraSeed.next();
        resetState(true, seed);
    }
    void resetWithSeedOffset(int offset) {
        if (!state.canEverExecute()) return;
        int seed = NiagaraSeed.offset(source.randomSeed(), offset);
        resetState(true, source.deterministic() ? seed : seed ^ NiagaraSeed.next());
    }
    void restartKeepingSpawns() {
        if (!state.canEverExecute()) return;
        resetState(false, state.seed());
    }
    private void resetState(boolean killExisting, int seed) {
        state.reset(killExisting, seed);
        count = (int)state.countEstimate();
        // UE's render command resets the clock immediately. The next tick publishes the shifted spawn windows.
        updates.add(killExisting ? new Update(state.capture(runtime), true) : new Update(null, true, seed));
        if (simulation == null) simulation = new NiagaraStatelessSimulation(source, state);
    }
    void releaseResources() {
        if (simulation != null) { simulation.close(); simulation = null; }
        rendered = null; updates.clear(); queuedTicks = 0;
    }
}
