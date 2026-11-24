package com.kltyton.lumenfx.client.simulation.niagara.stateless;

import com.kltyton.lumenfx.math.SourceRandomStream;
import java.util.Objects;

/** Resolved emitter-state settings drive loops, spawn cropping and scalability independently of system VM outputs. */
public final class StatelessExecutionState {
    public enum Reaction { AWAKEN, SLEEP_AND_FINISH, SLEEP_AND_CLEAR, KILL_IMMEDIATELY, KILL_AFTER_FINISH }
    public record Settings(boolean killOnInactive, boolean resetAgeOnAwaken, boolean visibilityCulling,
                           float visibilityDelay, Reaction visibilityReaction, boolean distanceCulling,
                           float minimumDistance, float maximumDistance, Reaction minimumReaction, Reaction maximumReaction) {
        public Settings {
            Objects.requireNonNull(visibilityReaction); Objects.requireNonNull(minimumReaction); Objects.requireNonNull(maximumReaction);
        }
    }
    private final Settings settings;
    private final SourceRandomStream random;
    private final StatelessLoopState loop;
    private final StatelessSpawnController spawns;
    private Reaction scalability = Reaction.AWAKEN;
    private int internalState, executionState;
    private float age;
    private boolean enabled = true, initialize = true;
    private boolean attachmentWasMissing;

    public StatelessExecutionState(Settings settings, SourceRandomStream random, StatelessLoopState loop, StatelessSpawnController spawns) {
        this.settings = Objects.requireNonNull(settings); this.random = Objects.requireNonNull(random);
        this.loop = Objects.requireNonNull(loop); this.spawns = Objects.requireNonNull(spawns);
    }

    public void tick(float delta, int parentState, float timeSinceRendered, float lodDistance, int[] parameters) {
        tick(delta, parentState, timeSinceRendered, lodDistance, parameters, true);
    }

    void tick(float delta, int parentState, float timeSinceRendered, float lodDistance, int[] parameters,
              boolean attachmentBirthsAllowed) {
        float priorAge = age;
        boolean admitBirths = enabled && attachmentBirthsAllowed;
        if (initialize) {
            initialize = false;
            loop.initialize(parameters);
            spawns.initialize(0f, loop, parameters, admitBirths && scalability == Reaction.AWAKEN);
        }
        if (!attachmentBirthsAllowed) spawns.tickEnabled(priorAge, false, loop, parameters);
        else if (attachmentWasMissing && admitBirths && scalability == Reaction.AWAKEN)
            spawns.tickEnabled(priorAge, true, loop, parameters);
        attachmentWasMissing = !attachmentBirthsAllowed;
        age += delta;
        spawns.tickEnabled(age, admitBirths && scalability == Reaction.AWAKEN, loop, parameters);
        if (parentState > internalState) transition(parentState);
        if (internalState == 1 && spawns.count(age) == 0) transition(3);
        if (internalState != 0) return;

        Reaction requested = Reaction.AWAKEN;
        if (settings.visibilityCulling() && timeSinceRendered > settings.visibilityDelay()) requested = settings.visibilityReaction();
        if (settings.distanceCulling()) {
            if (lodDistance > settings.maximumDistance()) requested = settings.maximumReaction();
            else if (lodDistance < settings.minimumDistance()) requested = settings.minimumReaction();
        }
        if (requested != scalability) {
            executionState = internalState;
            scalability = requested;
            switch (requested) {
                case AWAKEN -> { if (settings.resetAgeOnAwaken()) resetAge(false); }
                case SLEEP_AND_FINISH, KILL_AFTER_FINISH -> { executionState = 1; spawns.crop(age); }
                case SLEEP_AND_CLEAR -> { executionState = 1; spawns.kill(); }
                case KILL_IMMEDIATELY -> { transition(3); return; }
            }
        }
        if (scalability == Reaction.KILL_AFTER_FINISH && spawns.count(age) == 0) { transition(3); return; }
        if (!loop.advance(age, parameters, state -> spawns.beginLoop(state.ageStart(), state, parameters,
                admitBirths && scalability == Reaction.AWAKEN))) transition(1);
    }

    public void reset(boolean killExisting, int seed) {
        random.initialize(seed);
        resetAge(killExisting);
    }
    private void resetAge(boolean killExisting) {
        attachmentWasMissing = false;
        spawns.reset(killExisting, age, enabled);
        random.reset();
        age = 0f; initialize = true; internalState = 0; executionState = 0; scalability = Reaction.AWAKEN;
    }
    private void transition(int requested) {
        if (internalState == requested) return;
        switch (requested) {
            case 1 -> {
                if (settings.killOnInactive()) { spawns.kill(); internalState = 3; }
                else { spawns.crop(age); internalState = spawns.snapshot().isEmpty() ? 3 : 1; }
                executionState = internalState;
            }
            case 2, 3 -> { spawns.kill(); internalState = 3; executionState = 3; }
        }
    }
    public void forceComplete() { internalState = 3; executionState = 3; }
    public void disableInitialization() { internalState = 4; executionState = 4; }
    public void enabled(boolean value) { enabled = value; }
    public boolean shouldTick() { return internalState <= 1; }
    public boolean complete() { return internalState == 3 || internalState == 4; }
    public int executionState() { return executionState; }
    public int internalState() { return internalState; }
    public float age() { return age; }
    public Reaction scalability() { return scalability; }
}
