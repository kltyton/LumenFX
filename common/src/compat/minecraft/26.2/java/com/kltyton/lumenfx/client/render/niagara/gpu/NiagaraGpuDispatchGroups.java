package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.render.niagara.gpu.channel.NiagaraGpuDataChannelWorlds;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraSystem;
import com.kltyton.lumenfx.client.simulation.niagara.gpu.NiagaraGpuStageSchedule;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** The render-thread barriers between Niagara GPU dispatch groups. */
final class NiagaraGpuDispatchGroups implements AutoCloseable {
    static final class EmitterTick implements AutoCloseable {
        final int start;
        final List<NiagaraGpuStageSchedule.Invocation> stages;
        private final Supplier<NiagaraSystem.GpuEmitterTick> begin;
        private NiagaraSystem.@Nullable GpuEmitterTick active;

        EmitterTick(int start, List<NiagaraGpuStageSchedule.Invocation> stages,
                    Supplier<NiagaraSystem.GpuEmitterTick> begin) {
            this.start = start;
            this.stages = stages;
            this.begin = begin;
        }

        NiagaraGpuSimulation.Dispatch prepare(NiagaraGpuStageSchedule.Invocation stage, int iteration, boolean last) {
            if (active == null) active = begin.get();
            return active.simulation().prepare(stage, iteration, last);
        }

        void finish() {
            if (active == null) active = begin.get();
            active.finish();
            close();
        }

        @Override public void close() {
            var submission = active;
            active = null;
            if (submission != null) submission.close();
        }
    }

    private static final class SystemTick implements AutoCloseable {
        final List<EmitterTick> emitters;
        final Supplier<NiagaraSystem.GpuTargetStep> targets;
        final Runnable consumeSkipped;
        private NiagaraSystem.@Nullable GpuTargetStep active;

        SystemTick(List<EmitterTick> emitters, Supplier<NiagaraSystem.GpuTargetStep> targets, Runnable consumeSkipped) {
            this.emitters = emitters;
            this.targets = targets;
            this.consumeSkipped = consumeSkipped;
        }

        void begin() {
            consumeSkipped.run();
            active = targets.get();
            for (var emitter : emitters) if (emitter.stages.isEmpty()) emitter.finish();
        }

        void finish() {
            for (var emitter : emitters) if (!emitter.stages.isEmpty()) emitter.finish();
            close();
        }

        @Override public void close() {
            Throwable failure = null;
            for (var emitter : emitters) {
                try { emitter.close(); }
                catch (RuntimeException | Error cleanup) { failure = accumulate(failure, cleanup); }
            }
            var scope = active;
            active = null;
            if (scope != null) {
                try { scope.close(); }
                catch (RuntimeException | Error cleanup) { failure = accumulate(failure, cleanup); }
            }
            throwFailure(failure);
        }
    }

    private record Stage(EmitterTick emitter, NiagaraGpuStageSchedule.Invocation invocation, int iteration, boolean last) {
        NiagaraGpuSimulation.Dispatch prepare() { return emitter.prepare(invocation, iteration, last); }
    }

    private static final class Group {
        final List<SystemTick> begin = new ArrayList<>();
        final List<Stage> stages = new ArrayList<>();
        final List<SystemTick> finish = new ArrayList<>();
        final List<Runnable> after = new ArrayList<>();

        void execute() {
            for (var tick : begin) tick.begin();
            List<NiagaraGpuSimulation.Dispatch> prepared = new ArrayList<>(stages.size());
            var access = new NiagaraGpuDataChannelWorlds.StageAccess();
            for (var stage : stages) {
                var dispatch = stage.prepare();
                dispatch.acquireSharedStageInputs(access);
                prepared.add(dispatch);
            }
            for (var dispatch : prepared) dispatch.finalizePreStage();
            for (var dispatch : prepared) dispatch.dispatch();
            for (var dispatch : prepared) dispatch.postStage();
            for (var dispatch : prepared) dispatch.finalizePostStage();
            for (var tick : finish) tick.finish();
            for (var callback : after) callback.run();
        }
    }

    private final List<Group> groups = new ArrayList<>();
    private final List<SystemTick> ticks = new ArrayList<>();

    int addTick(int start, List<EmitterTick> emitters, Supplier<NiagaraSystem.GpuTargetStep> targets, Runnable consumeSkipped) {
        var tick = new SystemTick(List.copyOf(emitters), targets, consumeSkipped);
        ticks.add(tick);
        int end = start + 1;
        group(start).begin.add(tick);
        for (var emitter : emitters) {
            int offset = emitter.start;
            var stages = emitter.stages;
            for (int index = 0; index < stages.size(); index++) {
                var stage = stages.get(index);
                for (int iteration = 0; iteration < stage.iterations(); iteration++) {
                    boolean last = index == stages.size() - 1 && iteration == stage.iterations() - 1;
                    group(offset++).stages.add(new Stage(emitter, stage, iteration, last));
                }
            }
            end = Math.max(end, offset);
        }
        group(end - 1).finish.add(tick);
        return end;
    }

    void onComplete(int lastGroup, Runnable action) { group(lastGroup).after.add(Objects.requireNonNull(action)); }

    private Group group(int index) {
        while (groups.size() <= index) groups.add(new Group());
        return groups.get(index);
    }

    void execute() { for (var group : groups) group.execute(); }

    @Override public void close() {
        Throwable failure = null;
        for (var tick : ticks) {
            try { tick.close(); }
            catch (RuntimeException | Error cleanup) { failure = accumulate(failure, cleanup); }
        }
        throwFailure(failure);
    }

    private static Throwable accumulate(@Nullable Throwable first, Throwable next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }

    private static void throwFailure(@Nullable Throwable failure) {
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }
}
