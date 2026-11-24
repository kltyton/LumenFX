package com.kltyton.lumenfx.client.simulation.niagara;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraTickBehavior.ComponentTickGroup;
import com.kltyton.lumenfx.effect.niagara.VectorProgram;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** World-owned system script batches, isolated by asset identity, host phase and tick group. */
public final class NiagaraWorldSystemSimulation {
    public enum Phase {
        BeforeCamera, AfterCamera, Render;

        public static Phase forTickGroup(ComponentTickGroup group) {
            return switch (group) {
                case TG_PrePhysics, TG_StartPhysics, TG_DuringPhysics -> BeforeCamera;
                case TG_EndPhysics, TG_PostPhysics, TG_PostUpdateWork -> AfterCamera;
                case TG_LastDemotable, TG_NewlySpawned, TG_MAX -> Render;
            };
        }
    }

    record SharedTickInfo(float engineDelta, int steps, float fraction) { }
    private static final SharedTickInfo NO_TICK = new SharedTickInfo(0, 0, 0);

    private enum State { PendingSpawn, PendingSpawnPaused, Spawning, Running, Paused }

    private record TickDomain(Phase phase, ComponentTickGroup group) { }

    private final Map<TickDomain, IdentityHashMap<NiagaraDefinition, Simulation>> phases = new HashMap<>();
    private final EnumMap<Phase, SharedTickInfo> phaseTickInfo = new EnumMap<>(Phase.class);
    private final IdentityHashMap<NiagaraSystem, Membership> members = new IdentityHashMap<>();
    private final IdentityHashMap<Membership, ComponentTickGroup> promotions = new IdentityHashMap<>();
    private final List<Simulation> pending = new ArrayList<>();

    public void track(Phase phase, NiagaraSystem instance, boolean paused) {
        var group = switch (phase) {
            case BeforeCamera -> ComponentTickGroup.TG_PrePhysics;
            case AfterCamera -> ComponentTickGroup.TG_EndPhysics;
            case Render -> ComponentTickGroup.TG_LastDemotable;
        };
        track(phase, group, instance, paused);
    }

    public void track(Phase phase, ComponentTickGroup group, NiagaraSystem instance, boolean paused) {
        track(phase, group, group, instance, paused);
    }

    public void track(Phase phase, ComponentTickGroup group, ComponentTickGroup requested, NiagaraSystem instance,
                      boolean paused) {
        if (instance.completed() || instance.requiresSoloSystemSimulation()) {
            detach(instance);
            return;
        }
        var member = members.get(instance);
        if (member != null && (member.ready || member.state == State.Spawning))
            throw new IllegalStateException("Cannot move a Niagara instance during a system step");
        var simulation = simulation(phase, group, instance.systemDefinition());
        if (member == null) {
            member = new Membership(instance);
            instance.joinWorldSimulation(this);
            members.put(instance, member);
        }
        var previous = member.simulation;
        // The system row belongs to the instance, so a tick phase transfer also retains its current data.
        member.move(simulation, instance.initialized()
                ? (paused ? State.Paused : State.Running)
                : (paused ? State.PendingSpawnPaused : State.PendingSpawn));
        if (member.state == State.Running && requested.ordinal() < group.ordinal())
            promotions.put(member, requested);
        else promotions.remove(member);
        if (previous != null && previous != simulation) releaseIfIdle(previous);
    }

    private Simulation simulation(Phase phase, ComponentTickGroup group, NiagaraDefinition definition) {
        var domain = new TickDomain(phase, group);
        var simulations = phases.computeIfAbsent(domain, ignored -> new IdentityHashMap<>());
        return simulations.computeIfAbsent(definition, key -> new Simulation(domain, key));
    }

    ComponentTickGroup scheduleGroup(NiagaraSystem instance, ComponentTickGroup requested) {
        var member = members.get(instance);
        if (member != null && member.state == State.Running && !instance.requiresSoloSystemSimulation())
            return ComponentTickGroup.latest(requested, member.simulation.domain.group());
        return requested;
    }

    /** An earlier shared tick group takes effect after the instance's current host frame. */
    public void advanceFrame() {
        if (promotions.isEmpty()) return;
        var ordered = new ArrayList<>(promotions.entrySet());
        // Native group promotion pops the most recently visited running row first.
        ordered.sort(Comparator.comparingInt((Map.Entry<Membership, ComponentTickGroup> entry) ->
                entry.getKey().simulation.domain.group().ordinal()).thenComparingInt(entry -> -entry.getKey().index));
        for (var entry : ordered) {
            var member = entry.getKey();
            var group = entry.getValue();
            var previous = Objects.requireNonNull(member.simulation);
            member.move(simulation(Phase.forTickGroup(group), group, previous.definition), State.Running);
            releaseIfIdle(previous);
        }
        promotions.clear();
    }

    public void beginTick(Phase phase, float delta) {
        var info = new SharedTickInfo(delta, 1, 1);
        phaseTickInfo.put(phase, info);
        phases.forEach((domain, simulations) -> {
            if (domain.phase() == phase) simulations.values().forEach(simulation -> simulation.tickInfo = info);
        });
    }

    public void prepareStep(NiagaraSystem instance, double now, double engineTime, double realTime, float delta,
                            EffectHandle.Input input, Vec3 camera, boolean emitting,
                            @Nullable NiagaraCameraFrame cameraFrame, boolean postActorSpawn) {
        if (instance.completed()) return;
        var member = Objects.requireNonNull(members.get(instance), "Niagara system step has no shared simulation");
        if (member.ready) throw new IllegalStateException("Niagara system step is already staged");
        var simulation = Objects.requireNonNull(member.simulation);
        var sharedTiming = simulation.tickInfo;
        var spawnTiming = postActorSpawn
                ? new SharedTickInfo(sharedTiming.engineDelta(), sharedTiming.steps(), sharedTiming.fraction()) : null;
        if (!instance.prepareSharedStep(now, engineTime, realTime, delta, input, camera, emitting, cameraFrame, spawnTiming)) return;
        if (!postActorSpawn) simulation.tickInfo = instance.sharedTickInfo(delta);
        member.move(simulation, instance.initialized() ? State.Running : State.PendingSpawn);
        if (simulation.ready.isEmpty()) pending.add(simulation);
        simulation.ready.add(member);
        member.ready = true;
    }

    void detach(NiagaraSystem instance) {
        var member = members.remove(instance);
        if (member == null) return;
        promotions.remove(member);
        var simulation = Objects.requireNonNull(member.simulation);
        if (member.ready) {
            simulation.ready.remove(member);
            if (simulation.ready.isEmpty()) pending.remove(simulation);
        }
        member.remove();
        member.ready = false;
        instance.leaveWorldSimulation(this);
        releaseIfIdle(simulation);
    }

    private void releaseIfIdle(Simulation simulation) {
        if (simulation.inFlight != null || simulation.states.values().stream().anyMatch(rows -> !rows.isEmpty())) return;
        var simulations = phases.get(simulation.domain);
        if (simulations != null && simulations.remove(simulation.definition, simulation) && simulations.isEmpty())
            phases.remove(simulation.domain);
    }

    /**
     * Freezes ready system rows on the host thread. Each returned step may execute on a task executor
     * only when {@link PreparedStep#canRunOnTaskExecutor()} is true; finish it on the staging thread.
     */
    public List<PreparedStep> stagePendingSystemSteps() {
        var steps = new ArrayList<PreparedStep>(pending.size());
        var staging = List.copyOf(pending);
        pending.clear();
        try {
            for (var simulation : staging) {
                var step = simulation.stage();
                if (step == null) {
                    if (simulation.hasReadyMembers()) pending.add(simulation);
                } else steps.add(step);
            }
        } catch (RuntimeException | Error failure) {
            steps.forEach(PreparedStep::discard);
            for (var simulation : staging) if (!pending.contains(simulation)) simulation.discardPending();
            discardPending();
            throw failure;
        }
        return List.copyOf(steps);
    }

    public boolean hasPendingSystemSteps() { return !pending.isEmpty(); }

    public void execute() {
        if (pending.stream().anyMatch(simulation -> simulation.inFlight != null))
            throw new IllegalStateException("A Niagara system step is still in flight; resume it through the staged execution path");
        var steps = stagePendingSystemSteps();
        try {
            for (var step : steps) {
                step.execute();
                step.finish();
            }
        } catch (RuntimeException | Error failure) {
            steps.forEach(PreparedStep::discard);
            throw failure;
        } finally {
            discardPending();
        }
    }

    public void discardPending() {
        pending.forEach(Simulation::discardPending);
        pending.clear();
    }

    public void clear() {
        discardPending();
        for (var member : members.values()) {
            member.ready = false;
            member.remove();
            member.instance.leaveWorldSimulation(this);
        }
        members.clear();
        promotions.clear();
        phases.clear();
        phaseTickInfo.clear();
    }

    private static final class Membership {
        final NiagaraSystem instance;
        @Nullable Simulation simulation;
        @Nullable State state;
        int index;
        boolean ready;

        Membership(NiagaraSystem instance) { this.instance = instance; }

        void move(Simulation destination, State next) {
            if (simulation == destination && state == next) return;
            remove();
            var list = destination.list(next);
            index = list.size();
            list.add(this);
            simulation = destination;
            state = next;
        }

        void remove() {
            if (simulation == null) return;
            var list = simulation.list(state);
            var last = list.removeLast();
            if (last != this) {
                list.set(index, last);
                last.index = index;
            }
            simulation = null;
            state = null;
        }
    }

    public static final class PreparedStep {
        private enum Status { Prepared, Executing, Executed, Finished, Failed, Discarded }

        private final Simulation simulation;
        private final List<Membership> readyMembers;
        private final List<Membership> newMembers;
        private final List<Simulation.SystemRow> rows;
        private final boolean spawnScriptRuns;
        private final boolean updateScriptRuns;
        private final boolean updateNewborns;
        private final boolean canRunOnTaskExecutor;
        private final Thread stagingThread;
        private volatile Status status = Status.Prepared;

        private PreparedStep(Simulation simulation, List<Membership> readyMembers, List<Membership> newMembers,
                             List<Simulation.SystemRow> rows, boolean spawnScriptRuns, boolean updateScriptRuns,
                             boolean updateNewborns, boolean canRunOnTaskExecutor) {
            this.simulation = simulation;
            this.readyMembers = readyMembers;
            this.newMembers = newMembers;
            this.rows = rows;
            this.spawnScriptRuns = spawnScriptRuns;
            this.updateScriptRuns = updateScriptRuns;
            this.updateNewborns = updateNewborns;
            this.canRunOnTaskExecutor = canRunOnTaskExecutor;
            stagingThread = Thread.currentThread();
        }

        public boolean canRunOnTaskExecutor() { return canRunOnTaskExecutor; }

        /** Runs only frozen CPU system-script rows. This step serializes access to its VM scratch state. */
        public void execute() {
            if (!canRunOnTaskExecutor && Thread.currentThread() != stagingThread)
                throw new IllegalStateException("Niagara system scripts with external functions must run on the host thread");
            synchronized (simulation.executionLock) {
                if (status != Status.Prepared) throw new IllegalStateException("Niagara system step is not prepared");
                status = Status.Executing;
                try {
                    if (spawnScriptRuns) simulation.run(rowsFor(true), true);
                    if (updateScriptRuns) {
                        simulation.run(rowsFor(false), false);
                        if (updateNewborns) simulation.run(rowsForNewbornUpdate(), false);
                    }
                    status = Status.Executed;
                } catch (RuntimeException | Error failure) {
                    status = Status.Failed;
                    throw failure;
                }
            }
        }

        /** Publishes row state and emitter transfers on the host thread after execute completes. */
        public void finish() {
            requireStagingThread();
            if (status != Status.Executed) throw new IllegalStateException("Niagara system step has not executed");
            try {
                for (var row : rows) if (row.member.ready && row.member.simulation == simulation)
                    row.instance.acceptSharedSystemRows(row.current, row.destination);
                for (var member : readyMembers) {
                    if (!member.ready || member.simulation != simulation) continue;
                    member.instance.finishSystemStep();
                    member.ready = false;
                }
                for (var member : newMembers)
                    if (member.simulation == simulation && member.state == State.Spawning)
                        member.move(simulation, State.Running);
                status = Status.Finished;
            } catch (RuntimeException | Error failure) {
                status = Status.Failed;
                throw failure;
            } finally {
                if (status != Status.Executing) simulation.finished(this);
            }
        }

        /** Abandons unpublished rows after their computation has stopped. */
        public void discard() {
            requireStagingThread();
            if (status == Status.Executing)
                throw new IllegalStateException("Cannot discard a Niagara system step while its task is executing");
            if (status == Status.Finished || status == Status.Discarded) return;
            simulation.discard(this, readyMembers);
            status = Status.Discarded;
        }

        private List<Simulation.SystemRow> rowsFor(boolean spawning) {
            return rows.stream().filter(row -> row.spawning == spawning).toList();
        }

        private List<Simulation.SystemRow> rowsForNewbornUpdate() {
            return rows.stream().filter(row -> row.spawning && row.updatesOnSpawn).toList();
        }

        private void requireStagingThread() {
            if (Thread.currentThread() != stagingThread)
                throw new IllegalStateException("Niagara system rows must be finalized on their staging thread");
        }
    }

    private final class Simulation {
        final TickDomain domain;
        final NiagaraDefinition definition;
        final EnumMap<State, List<Membership>> states = new EnumMap<>(State.class);
        final List<Membership> ready = new ArrayList<>();
        final VectorMachine spawn, update;
        final Object executionLock = new Object();
        @Nullable PreparedStep inFlight;
        SharedTickInfo tickInfo;

        Simulation(TickDomain domain, NiagaraDefinition definition) {
            this.domain = domain;
            this.definition = definition;
            tickInfo = phaseTickInfo.getOrDefault(domain.phase(), NO_TICK);
            spawn = new VectorMachine(definition.systemSpawn());
            update = new VectorMachine(definition.systemUpdate());
        }

        List<Membership> list(State state) {
            return states.computeIfAbsent(state, ignored -> new ArrayList<>());
        }

        @Nullable PreparedStep stage() {
            if (inFlight != null) return null;
            if (!hasReadyMembers()) {
                ready.clear();
                return null;
            }
            var running = list(State.Running);
            var runningMembers = running.stream().filter(member -> member.ready).toList();
            int runningCount = running.size();
            var pendingSpawn = list(State.PendingSpawn);
            var newMembers = new ArrayList<Membership>();
            int index = 0;
            while (index < pendingSpawn.size()) {
                var member = pendingSpawn.get(index);
                if (!member.ready) {
                    index++;
                    continue;
                }
                // Promotion swap-removes from PendingSpawn. The incoming row at this index is next.
                member.move(this, State.Spawning);
                newMembers.add(member);
            }
            var readyMembers = new ArrayList<Membership>(runningMembers.size() + newMembers.size());
            readyMembers.addAll(runningMembers);
            readyMembers.addAll(newMembers);
            var scripts = definition.timing().systemState();
            boolean spawnScriptRuns = !newMembers.isEmpty() && (scripts.runUpdate() || scripts.runSpawn());
            boolean updateScriptRuns = scripts.runUpdate();
            boolean updateNewborns = updateScriptRuns && newMembers.stream().anyMatch(member -> member.instance.updatesOnSpawn());
            for (var member : readyMembers) member.instance.prepareSystemSpawnParameters(runningCount);
            if (spawnScriptRuns) for (var member : newMembers) member.instance.prepareSpawnExecution();
            if (updateScriptRuns) {
                for (var member : runningMembers) member.instance.prepareUpdateExecution(runningCount);
                if (updateNewborns) for (var member : newMembers)
                    if (member.instance.updatesOnSpawn()) member.instance.prepareUpdateExecution(runningCount);
            }
            var rows = new ArrayList<SystemRow>(readyMembers.size());
            for (var member : runningMembers)
                rows.add(SystemRow.capture(member, definition, false, updateScriptRuns));
            for (var member : newMembers)
                rows.add(SystemRow.capture(member, definition, true, updateScriptRuns && member.instance.updatesOnSpawn()));
            boolean updateExecutes = updateScriptRuns && (!runningMembers.isEmpty() || updateNewborns);
            boolean taskSafe = (!spawnScriptRuns || taskExecutorSafe(definition.systemSpawn()))
                    && (!updateExecutes || taskExecutorSafe(definition.systemUpdate()));
            ready.clear();
            inFlight = new PreparedStep(this, readyMembers, newMembers, rows, spawnScriptRuns,
                    updateScriptRuns, updateNewborns, taskSafe);
            return inFlight;
        }

        void discardPending() {
            for (var member : ready) {
                member.ready = false;
                if (member.simulation == this && member.state == State.Spawning)
                    member.move(this, member.instance.initialized() ? State.Running : State.PendingSpawn);
            }
            ready.clear();
        }

        void finished(PreparedStep step) {
            if (inFlight == step) {
                inFlight = null;
                releaseIfIdle(this);
            }
        }

        boolean hasReadyMembers() {
            return ready.stream().anyMatch(member -> member.ready && member.simulation == this);
        }

        void discard(PreparedStep step, List<Membership> stagedMembers) {
            for (var member : stagedMembers) {
                member.ready = false;
                if (member.simulation == this && member.state == State.Spawning)
                    member.move(this, member.instance.initialized() ? State.Running : State.PendingSpawn);
            }
            finished(step);
        }

        private void run(List<SystemRow> rows, boolean spawning) {
            if (rows.isEmpty()) return;
            var script = spawning ? definition.systemSpawn() : definition.systemUpdate();
            var parameters = NiagaraSystem.dataset(spawning ? definition.spawnInputLayout() : definition.updateInputLayout());
            var machine = spawning ? spawn : update;
            var owners = new ArrayList<VectorMachine>(rows.size());
            var constants = new ArrayList<byte[]>(rows.size());
            var inputRows = NiagaraSystem.dataset(definition.systemLayout());
            var outputRows = NiagaraSystem.dataset(definition.systemLayout());
            inputRows.count(rows.size());
            outputRows.count(rows.size());
            parameters.count(rows.size());
            for (int row = 0; row < rows.size(); row++) {
                var state = rows.get(row);
                inputRows.copyRowFrom(state.current, 0, row);
                outputRows.copyRowFrom(state.destination, 0, row);
                parameters.copyRowFrom(spawning ? state.spawnInputs : state.updateInputs, 0, row);
                owners.add(spawning ? state.spawnOwner : state.updateOwner);
                constants.add(spawning ? state.spawnConstants : state.updateConstants);
            }
            int[] results = machine.executeSystemRows(inputRows, parameters, outputRows, owners, constants);
            for (int row = 0; row < rows.size(); row++) rows.get(row).accept(outputRows, results[row], script);
        }

        private static boolean taskExecutorSafe(VectorProgram program) {
            return program.external().isEmpty() && program.userPointers().count() == 0
                    && program.instructions().stream().noneMatch(instruction -> switch (instruction.opcode()) {
                        case EXTERNAL_FUNC_CALL, ACQUIRE_ID, UPDATE_ID, RANDOM, RANDOMI, RANDOM_ADD, RANDOM_2X -> true;
                        default -> false;
                    });
        }

        private static final class SystemRow {
            final Membership member;
            final NiagaraSystem instance;
            VectorDataSet current, destination;
            final VectorDataSet spawnInputs, updateInputs;
            final VectorMachine spawnOwner, updateOwner;
            final byte[] spawnConstants, updateConstants;
            final boolean spawning;
            final boolean updatesOnSpawn;

            private SystemRow(Membership member, NiagaraSystem instance, VectorDataSet current,
                              VectorDataSet destination, VectorDataSet spawnInputs, VectorDataSet updateInputs,
                              VectorMachine spawnOwner, VectorMachine updateOwner,
                              byte[] spawnConstants, byte[] updateConstants, boolean spawning,
                              boolean updatesOnSpawn) {
                this.member = member;
                this.instance = instance;
                this.current = current;
                this.destination = destination;
                this.spawnInputs = spawnInputs;
                this.updateInputs = updateInputs;
                this.spawnOwner = spawnOwner;
                this.updateOwner = updateOwner;
                this.spawnConstants = spawnConstants;
                this.updateConstants = updateConstants;
                this.spawning = spawning;
                this.updatesOnSpawn = updatesOnSpawn;
            }

            static SystemRow capture(Membership member, NiagaraDefinition definition, boolean spawning,
                                     boolean updateScriptPrepared) {
                var instance = member.instance;
                return new SystemRow(member, instance,
                        copyRow(instance.systemRow(), definition.systemLayout()),
                        copyRow(instance.systemDestination(), definition.systemLayout()),
                        copyRow(instance.systemInputs(true), definition.spawnInputLayout()),
                        copyRow(instance.systemInputs(false), definition.updateInputLayout()),
                        instance.systemMachine(true), instance.systemMachine(false),
                        instance.systemConstants(true).clone(), updateScriptPrepared ? instance.systemConstants(false).clone() : new byte[0],
                        spawning, instance.updatesOnSpawn());
            }

            private static VectorDataSet copyRow(VectorDataSet source, List<NiagaraDefinition.Variable> layout) {
                var copy = NiagaraSystem.dataset(layout);
                copy.copyRowFrom(source, 0, 0);
                copy.count(source.count());
                return copy;
            }

            void accept(VectorDataSet source, int sourceRow, VectorProgram script) {
                if (sourceRow >= 0) for (var column : script.outputs()) {
                    if (column.component() != 0xffff)
                        destination.component(column.type(), column.component())[0]
                                = source.component(column.type(), column.component())[sourceRow];
                }
                destination.count(sourceRow < 0 ? 0 : 1);
                VectorDataSet swap = current;
                current = destination;
                destination = swap;
            }
        }
    }
}
