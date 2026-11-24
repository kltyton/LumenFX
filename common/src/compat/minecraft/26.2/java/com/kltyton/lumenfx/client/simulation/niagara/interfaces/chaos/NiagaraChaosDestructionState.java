package com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos;

import com.kltyton.lumenfx.api.client.physics.NiagaraChaosScene;
import com.kltyton.lumenfx.api.client.physics.NiagaraChaosScene.Batch;
import com.kltyton.lumenfx.api.client.physics.NiagaraChaosScene.Body;
import com.kltyton.lumenfx.api.client.physics.NiagaraChaosScene.Bounds;
import com.kltyton.lumenfx.api.client.physics.NiagaraChaosScene.Breaking;
import com.kltyton.lumenfx.api.client.physics.NiagaraChaosScene.Collision;
import com.kltyton.lumenfx.api.client.physics.NiagaraChaosScene.EventDemand;
import com.kltyton.lumenfx.api.client.physics.NiagaraChaosScene.EventKind;
import com.kltyton.lumenfx.api.client.physics.NiagaraChaosScene.Solver;
import com.kltyton.lumenfx.api.client.physics.NiagaraChaosScene.Trailing;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.ChaosDestructionFrame.Column;
import com.kltyton.lumenfx.effect.niagara.physics.ChaosDestructionDefinition;
import com.kltyton.lumenfx.effect.niagara.physics.ChaosDestructionDefinition.Source;
import com.kltyton.lumenfx.math.SourceIntroSort;
import com.kltyton.lumenfx.math.WindowsSourceRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import static com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.ChaosDestructionFrame.Column.ANGULAR_FIRST;
import static com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.ChaosDestructionFrame.Column.ANGULAR_SECOND;
import static com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.ChaosDestructionFrame.Column.BOUNDS;
import static com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.ChaosDestructionFrame.Column.COLOR;
import static com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.ChaosDestructionFrame.Column.DENSITY;
import static com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.ChaosDestructionFrame.Column.EXTENT_MAX;
import static com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.ChaosDestructionFrame.Column.EXTENT_MIN;
import static com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.ChaosDestructionFrame.Column.FRICTION;
import static com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.ChaosDestructionFrame.Column.IMPULSE;
import static com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.ChaosDestructionFrame.Column.LOCATION;
import static com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.ChaosDestructionFrame.Column.MASS_FIRST;
import static com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.ChaosDestructionFrame.Column.MASS_SECOND;
import static com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.ChaosDestructionFrame.Column.NORMAL;
import static com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.ChaosDestructionFrame.Column.POSITION;
import static com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.ChaosDestructionFrame.Column.RESTITUTION;
import static com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.ChaosDestructionFrame.Column.ROTATION;
import static com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.ChaosDestructionFrame.Column.SCALE;
import static com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.ChaosDestructionFrame.Column.SOLVER_ID;
import static com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.ChaosDestructionFrame.Column.SURFACE;
import static com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.ChaosDestructionFrame.Column.TIME;
import static com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.ChaosDestructionFrame.Column.TRANSLATION;
import static com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.ChaosDestructionFrame.Column.VELOCITY;
import static com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.ChaosDestructionFrame.Column.VELOCITY_FIRST;
import static com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.ChaosDestructionFrame.Column.VELOCITY_SECOND;
import static com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos.ChaosDestructionFrame.Column.VOLUME;

public final class NiagaraChaosDestructionState {
    public enum Function {
        GetPosition(POSITION), GetNormal(NORMAL), GetVelocity(VELOCITY), GetAngularVelocity(ANGULAR_FIRST),
        GetExtentMin(EXTENT_MIN), GetExtentMax(EXTENT_MAX), GetVolume(VOLUME), GetParticleIdsToSpawnAtTime,
        GetPointType, GetColor(COLOR), GetSolverTime, GetDensity(DENSITY), GetFriction(FRICTION), GetRestitution(RESTITUTION),
        GetSurfaceType(SURFACE), GetTransform(TRANSLATION, ROTATION, SCALE), GetSize(BOUNDS),
        GetCollisionData(LOCATION, IMPULSE, NORMAL, VELOCITY_FIRST, VELOCITY_SECOND, ANGULAR_FIRST, ANGULAR_SECOND,
                MASS_FIRST, MASS_SECOND, TIME),
        GetBreakingData(LOCATION, VELOCITY_FIRST, ANGULAR_FIRST, MASS_FIRST, TIME),
        GetTrailingData(LOCATION, VELOCITY_FIRST, ANGULAR_FIRST, MASS_FIRST, TIME);

        private final Column[] columns;
        Function(Column... columns) { this.columns = columns; }
    }

    @FunctionalInterface
    public interface Output { void set(int word, int lane, int value); }

    private static final Body ZERO_BODY = new Body(Vec3.ZERO, Vec3.ZERO, 0, 0, null);
    private static final Vec3 ONE = new Vec3(1, 1, 1);
    private static final Bounds FALLBACK_BOUNDS = new Bounds(new Vec3(-100, -100, -100), new Vec3(100, 100, 100));

    private final ChaosDestructionDefinition definition;
    private final List<EventDemand> demand;
    private final @Nullable ChaosSpatialSelection spatial;
    private final List<Event> events = new ArrayList<>();
    private long revision = Long.MIN_VALUE;
    private float lastTime = -1;
    private float time;
    private int lastId = -1;
    private ChaosDestructionFrame frame = new ChaosDestructionFrame.Builder().finish(lastId, time);

    public NiagaraChaosDestructionState(ChaosDestructionDefinition definition) {
        this.definition = definition;
        spatial = definition.spatialHash().enabled() && definition.source() != Source.TRAILING
                ? new ChaosSpatialSelection(definition.spatialHash()) : null;
        var requests = new ArrayList<EventDemand>();
        if (definition.solvers().isEmpty()) request(requests, null);
        else for (var actor : definition.solvers()) if (!actor.isEmpty()) request(requests, actor);
        demand = List.copyOf(requests);
    }

    public List<EventDemand> eventDemand() { return demand; }

    private void request(List<EventDemand> requests, @Nullable String actor) {
        var kind = EventKind.valueOf(definition.source().name());
        if (kind == EventKind.TRAILING) requests.add(new EventDemand(actor, EventKind.BREAKING));
        requests.add(new EventDemand(actor, kind));
    }

    public void step(NiagaraChaosScene scene) {
        List<Solver> solvers = solvers(scene);
        updateEvents(solvers);
        if (!solvers.isEmpty()) time = solvers.getFirst().time();
        boolean shouldSpawn = time != lastTime && time - lastTime >= 1.0 / definition.frequency();
        if (shouldSpawn) {
            lastTime = time;
            lastId += frame.count();
        }

        var result = new ChaosDestructionFrame.Builder();
        if (shouldSpawn && definition.spawn() && definition.maximumEntries() > 0) {
            var random = WindowsSourceRandom.current();
            for (int index = 0; index < solvers.size(); index++) {
                var solver = solvers.get(index);
                if (solver.time() <= 0 || events.isEmpty() || !enabled(solver)) continue;
                events.removeIf(this::rejects);
                orderEvents(random);
                List<Event> selected = spatial == null ? events : spatial.select(events, Event::location);
                for (Event event : ChaosSpatialSelection.cap(selected, definition.maximumEntries())) {
                    spawn(result, event, index, solver.time(), random);
                }
            }
        }
        frame = result.finish(lastId, time);
    }

    public ChaosDestructionFrame frame() { return frame; }

    public void execute(Function function, int lanes, IntUnaryOperator particleId, Output output) {
        for (int lane = 0; lane < lanes; lane++) {
            switch (function) {
                case GetSolverTime -> output.set(0, lane, Float.floatToRawIntBits(frame.time()));
                case GetPointType -> output.set(0, lane, 0);
                case GetParticleIdsToSpawnAtTime -> {
                    int count = frame.count();
                    output.set(0, lane, count == 0 ? 0 : frame.lastId() + 1);
                    output.set(1, lane, count == 0 ? 0 : frame.lastId() + count);
                    output.set(2, lane, count);
                }
                default -> {
                    int index = particleId.applyAsInt(lane) - (frame.lastId() + 1);
                    int word = 0;
                    for (Column column : function.columns) {
                        int width = column.components();
                        for (int c = 0; c < width; c++) output.set(word++, lane, frame.read(column, index, c));
                    }
                }
            }
        }
    }

    private List<Solver> solvers(NiagaraChaosScene scene) {
        if (definition.solvers().isEmpty()) return scene.worldSolver() == null ? List.of() : List.of(scene.worldSolver());
        var selected = new ArrayList<Solver>();
        for (String actor : definition.solvers()) {
            if (actor.isEmpty()) continue;
            var solver = scene.actorSolvers().get(actor);
            if (solver != null) selected.add(solver);
        }
        return selected;
    }

    private boolean enabled(Solver solver) {
        return switch (definition.source()) {
            case COLLISION -> solver.collisionsEnabled();
            case BREAKING -> solver.breakingsEnabled();
            case TRAILING -> solver.trailingsEnabled();
        };
    }

    private void updateEvents(List<Solver> solvers) {
        Batch<?> latest = null;
        for (Solver solver : solvers) {
            Batch<?> batch = switch (definition.source()) {
                case COLLISION -> solver.collisions();
                case BREAKING -> solver.breakings();
                case TRAILING -> solver.trailings();
            };
            if (batch != null && (latest == null || batch.sequence() > latest.sequence())) latest = batch;
        }
        if (latest == null || latest.sequence() <= revision) return;
        revision = latest.sequence();
        events.clear();
        for (Object data : latest.entries()) {
            switch (data) {
                case Collision collision -> events.add(new Event(collision.location(), collision.impulse(), collision.normal(),
                        collision.first(), collision.second(), 100, 100, 1000000, Vec3.ZERO));
                case Breaking breaking -> {
                    var event = definition.externalBreaking()
                            ? Event.withBounds(breaking.location(), breaking.body(), breaking.bounds())
                            : new Event(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, ZERO_BODY, ZERO_BODY,
                                    100, 100, 1000000, FALLBACK_BOUNDS.maximum().subtract(FALLBACK_BOUNDS.minimum()));
                    events.add(event);
                }
                case Trailing trailing -> {
                    var filter = definition.filters();
                    float minimum = filter.trailSpeed();
                    if (trailing.notifyTrailing() && trailing.body().physicalMaterial() != null
                            && trailing.body().velocity().lengthSqr() >= minimum * minimum
                            && (!filter.filterMaterials() || filter.acceptsMaterial(trailing.body().physicalMaterial()))) {
                        events.add(Event.withBounds(trailing.location(), trailing.body(), trailing.bounds()));
                    }
                }
                default -> throw new IllegalStateException("Unexpected Niagara Chaos event " + data);
            }
        }
    }

    private boolean rejects(Event event) {
        var filter = definition.filters();
        var first = event.first();
        var second = event.second();
        boolean collision = definition.source() == Source.COLLISION;
        boolean testMaterial = definition.source() != Source.TRAILING && filter.filterMaterials();
        return (testMaterial && !filter.acceptsMaterial(first.physicalMaterial())
                    && (!collision || !filter.acceptsMaterial(second.physicalMaterial())))
                || (collision && filter.impulse().rejects((float) event.impulse().lengthSqr(), true))
                || filter.speed().rejects((float) first.velocity().lengthSqr(), true)
                || filter.mass().rejects(first.mass(), false) || filter.extentMin().rejects(event.min(), false)
                || filter.extentMax().rejects(event.max(), false) || filter.volume().rejects(event.volume(), false)
                || filter.time().rejects(time, false) || (filter.surface() != -1 && filter.surface() != first.surfaceType()
                    && (!collision || filter.surface() != second.surfaceType()))
                || filter.rejectsLocation(event.location());
    }

    private void orderEvents(WindowsSourceRandom random) {
        switch (definition.order()) {
            case NONE -> { }
            case RANDOM -> SourceIntroSort.sort(events, (left, right) -> random.unit() < 0.5f);
            case HEAVIEST -> SourceIntroSort.sort(events, (left, right) -> left.first().mass() > right.first().mass());
            case LIGHTEST -> SourceIntroSort.sort(events, (left, right) -> left.first().mass() < right.first().mass());
        }
    }

    private void spawn(ChaosDestructionFrame.Builder result, Event event, int solver, float eventTime,
                       WindowsSourceRandom random) {
        // The source callback timestamps the batch with the current solver time, so the latency is zero.
        if (definition.maxLatency() < 0) return;
        var multiplier = definition.multiplier();
        int attempts = random.range((int) multiplier.min(), (int) Math.max(multiplier.min(), multiplier.max()));
        float chance = Math.clamp(definition.chance(), 0, 1);
        boolean collision = definition.source() == Source.COLLISION;
        boolean breaking = definition.source() == Source.BREAKING;
        Vec3 inherited = event.first().velocity().subtract(event.second().velocity());
        for (int i = 0; i < attempts; i++) {
            if (random.unit() > chance) continue;
            result.vector(POSITION, ChaosParticleMotion.position(event.location(), definition.motion().position(), random));
            result.vector(VELOCITY, ChaosParticleMotion.velocity(inherited, event.normal(), collision, definition.motion(), random));
            result.integer(SOLVER_ID, solver);
            result.vector(LOCATION, event.location());
            result.vector(IMPULSE, event.impulse());
            result.vector(NORMAL, event.normal());
            result.vector(VELOCITY_FIRST, event.first().velocity());
            result.vector(VELOCITY_SECOND, event.second().velocity());
            result.vector(ANGULAR_FIRST, event.first().angularVelocity());
            result.vector(ANGULAR_SECOND, event.second().angularVelocity());
            result.scalar(MASS_FIRST, event.first().mass());
            result.scalar(MASS_SECOND, event.second().mass());
            result.scalar(TIME, eventTime);
            result.scalar(EXTENT_MIN, event.min());
            result.scalar(EXTENT_MAX, event.max());
            result.scalar(VOLUME, event.volume());
            if (breaking) result.vector(BOUNDS, event.bounds());
        }

        // Native metadata columns count attempted particles, even when SpawnChance rejected a position.
        for (int i = 0; i < attempts; i++) {
            result.integer(SURFACE, event.first().surfaceType());
            result.vector4(COLOR, 1, 1, 1, 1);
            result.scalar(FRICTION, 0.7f);
            result.scalar(RESTITUTION, 0.3f);
            result.scalar(DENSITY, 1);
            if (breaking) {
                result.vector(TRANSLATION, Vec3.ZERO);
                result.vector4(ROTATION, 0, 0, 0, 1);
                result.vector(SCALE, ONE);
            }
        }
    }

    private record Event(Vec3 location, Vec3 impulse, Vec3 normal, Body first, Body second,
                         float min, float max, float volume, Vec3 bounds) {
        static Event withBounds(Vec3 location, Body body, Bounds box) {
            Vec3 size = box.maximum().subtract(box.minimum());
            float minimum = (float) Math.min(size.x, Math.min(size.y, size.z));
            float maximum = (float) Math.max(size.x, Math.max(size.y, size.z));
            return new Event(location, Vec3.ZERO, Vec3.ZERO, body, ZERO_BODY,
                    minimum, maximum, (float) (size.x * size.y * size.z), size);
        }
    }
}
