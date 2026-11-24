package com.kltyton.lumenfx.client.simulation.niagara.stateless;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.client.resource.niagara.NiagaraScalabilityJson;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraCollectionSnapshot;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.math.SourceRandomStream;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Analytic lifecycle and parameter ownership are shared by CPU evaluation and render-thread demand generation. */
public final class StatelessEmitterState {
    private final StatelessParameterBuffer parameters;
    private final StatelessSpawnController spawns;
    private final StatelessExecutionState state;
    private final int features;
    private final boolean canEverExecute;
    private int seed;
    private float delta;
    private StatelessEmitterFrame.@Nullable Bindings renderBindings;
    private List<StatelessSpawnSchedule.Info> renderSpawns = List.of();
    private boolean bindingsDirty = true, spawnsPublished;

    public StatelessEmitterState(NiagaraDefinition system, NiagaraDefinition.Emitter source, int seed,
                                 NiagaraPlatformEnvironment platform) {
        this.seed = seed;
        var definition = (NiagaraDefinition.StatelessSimulation)source.simulation();
        JsonObject metadata = definition.metadata();
        features = metadata.get("allowed_features").getAsInt() & platform.statelessFeatures();
        var scalability = NiagaraScalabilityJson.emitter(metadata.getAsJsonObject("scalability"));
        parameters = new StatelessParameterBuffer(metadata.getAsJsonObject("parameters"), system, source.sourceName());
        JsonObject settings = metadata.getAsJsonObject("state");
        SourceRandomStream random = new SourceRandomStream(seed);
        StatelessLoopState loop = new StatelessLoopState(new StatelessLoopState.Settings(behavior(settings.get("loop_behavior").getAsString()),
                durationMode(settings.get("duration_mode").getAsString()), settings.get("loop_count").getAsInt(),
                floating(settings.getAsJsonObject("duration")), floating(settings.getAsJsonObject("delay")),
                settings.get("delay_enabled").getAsBoolean(), settings.get("recalculate_duration").getAsBoolean(),
                settings.get("first_delay_only").getAsBoolean(), settings.get("recalculate_delay").getAsBoolean()), random);
        StatelessRange lifetime = StatelessRange.decode(metadata.getAsJsonObject("lifetime"));
        List<StatelessSpawnController.Definition> spawnDefinitions = metadata.getAsJsonArray("spawns").asList().stream().map(item -> {
            JsonObject value = item.getAsJsonObject();
            return new StatelessSpawnController.Definition(value.get("type").getAsString().equals("Rate") ? StatelessSpawnSchedule.Type.RATE : StatelessSpawnSchedule.Type.BURST,
                    true, value.get("spawn_time").getAsFloat(), integer(value.getAsJsonObject("amount")), floating(value.getAsJsonObject("rate")),
                    value.get("probability_enabled").getAsBoolean(), floating(value.getAsJsonObject("probability")),
                    value.get("loop_limit_enabled").getAsBoolean(), integer(value.getAsJsonObject("loop_limit")));
        }).toList();
        spawns = new StatelessSpawnController(spawnDefinitions,
                new StatelessSpawnController.Lifetime(lifetime.minimum(0), lifetime.maximum(0), lifetime.parameterOffset()), random,
                metadata.has("scalability") ? scalability.spawnScale(platform)
                        : metadata.has("spawn_count_scale") ? metadata.get("spawn_count_scale").getAsFloat() : 1f);
        state = new StatelessExecutionState(new StatelessExecutionState.Settings(
                List.of("Kill", "1").contains(settings.get("inactive_response").getAsString()), settings.get("reset_age_on_awaken").getAsBoolean(),
                settings.get("visibility_culling").getAsBoolean(), settings.get("visibility_delay").getAsFloat(), reaction(settings.get("visibility_reaction").getAsString()),
                settings.get("distance_culling").getAsBoolean(), settings.get("minimum_distance").getAsFloat(), settings.get("maximum_distance").getAsFloat(),
                reaction(settings.get("minimum_reaction").getAsString()), reaction(settings.get("maximum_reaction").getAsString())), random, loop, spawns);
        canEverExecute = scalability.platforms().active(platform) && !(features == 0
                || definition.target().equals("CPUSim") && (features & 2) == 0 || spawnDefinitions.isEmpty()
                || metadata.get("renderer_count").getAsInt() == 0 || source.attributes().isEmpty()
                || metadata.get("template").getAsString().equals("None")
                || metadata.get("has_initializer").getAsBoolean() && lifetime.parameterOffset() < 0
                    && lifetime.minimum(0) <= 0f && lifetime.maximum(0) <= 0f);
        if (!canEverExecute) state.disableInitialization();
    }

    public StatelessParameterBuffer parameters() { return parameters; }
    public int features() { return features; }
    public boolean canEverExecute() { return canEverExecute; }
    public boolean updateParameters(byte[] spawn, byte[] update, byte[] runtime, EffectHandle.Input input,
                                    double originX, double originY, double originZ, NiagaraCollectionSnapshot collections) {
        boolean dirty = canEverExecute && parameters.update(spawn, update, runtime, input, originX, originY, originZ, collections);
        bindingsDirty |= dirty;
        return dirty;
    }
    public void tick(float delta, int parentState, float timeSinceRendered, float lodDistance) {
        tick(delta, parentState, timeSinceRendered, lodDistance, true);
    }
    public void tick(float delta, int parentState, float timeSinceRendered, float lodDistance, boolean attachmentBirthsAllowed) {
        if (!canEverExecute) return;
        this.delta = delta;
        state.tick(delta, parentState, timeSinceRendered, lodDistance, parameters.words(), attachmentBirthsAllowed);
    }
    public StatelessSpawnSchedule.Plan plan() { return spawns.plan(state.age()); }
    public StatelessEmitterFrame capture(byte[] runtime) {
        StatelessEmitterFrame.Bindings bindings = renderBindings;
        if (bindingsDirty || bindings == null) {
            bindings = new StatelessEmitterFrame.Bindings(parameters.words());
            renderBindings = bindings;
            bindingsDirty = false;
        }
        boolean spawnsDirty = spawns.consumeDirty();
        if (!spawnsPublished || spawnsDirty) {
            renderSpawns = spawns.snapshot();
            spawnsPublished = true;
        }
        return new StatelessEmitterFrame(seed, state.age(), delta, state.executionState(), features, canEverExecute,
                bindings, renderSpawns, runtime);
    }
    public long countEstimate() { return canEverExecute ? spawns.count(state.age()) : 0; }
    public float age() { return state.age(); }
    public float delta() { return delta; }
    public int seed() { return seed; }
    public boolean shouldTick() { return state.shouldTick(); }
    public boolean complete() { return state.complete(); }
    public int executionState() { return state.executionState(); }
    public void spawningAllowed(boolean allowed) { state.enabled(allowed); }
    public void complete(int executionState) { if (executionState == 4) state.disableInitialization(); else state.forceComplete(); }
    public void reset(int nextSeed) {
        reset(true, nextSeed);
    }

    public void reset(boolean killExisting, int nextSeed) {
        if (!canEverExecute) return;
        seed = nextSeed;
        delta = 0f;
        state.reset(killExisting, seed);
    }

    private static StatelessScalarRange.Floating floating(JsonObject value) {
        return new StatelessScalarRange.Floating(value.get("minimum").getAsFloat(), value.get("maximum").getAsFloat(), value.get("bound").getAsBoolean(), value.get("parameter_offset").getAsInt());
    }
    private static StatelessScalarRange.Integer integer(JsonObject value) {
        return new StatelessScalarRange.Integer(value.get("minimum").getAsInt(), value.get("maximum").getAsInt(), value.get("bound").getAsBoolean(), value.get("parameter_offset").getAsInt());
    }
    private static StatelessLoopState.Behavior behavior(String value) { return switch (value) {
        case "Infinite", "0" -> StatelessLoopState.Behavior.INFINITE; case "Multiple", "1" -> StatelessLoopState.Behavior.MULTIPLE;
        case "Once", "2" -> StatelessLoopState.Behavior.ONCE; default -> throw new IllegalArgumentException("Invalid stateless loop behavior " + value);
    }; }
    private static StatelessLoopState.DurationMode durationMode(String value) { return switch (value) {
        case "Fixed", "0" -> StatelessLoopState.DurationMode.FIXED; case "Infinite", "1" -> StatelessLoopState.DurationMode.INFINITE;
        default -> throw new IllegalArgumentException("Invalid stateless duration mode " + value);
    }; }
    private static StatelessExecutionState.Reaction reaction(String value) { return switch (value) {
        case "Awaken", "0" -> StatelessExecutionState.Reaction.AWAKEN;
        case "SleepAndLetParticlesFinish", "1" -> StatelessExecutionState.Reaction.SLEEP_AND_FINISH;
        case "SleepAndClearParticles", "2" -> StatelessExecutionState.Reaction.SLEEP_AND_CLEAR;
        case "KillImmediately", "3" -> StatelessExecutionState.Reaction.KILL_IMMEDIATELY;
        case "KillAfterParticlesFinish", "4" -> StatelessExecutionState.Reaction.KILL_AFTER_FINISH;
        default -> throw new IllegalArgumentException("Invalid stateless scalability reaction " + value);
    }; }
}

