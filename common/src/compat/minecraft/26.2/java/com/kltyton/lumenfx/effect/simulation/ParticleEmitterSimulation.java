package com.kltyton.lumenfx.effect.simulation;

import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.UnityParticleCollisionEvent.Collider;
import com.kltyton.lumenfx.api.client.UnityParticleCollisionEvent.ForceImpact;
import com.kltyton.lumenfx.api.client.UnityParticleCollisionEvent.Hit;
import com.kltyton.lumenfx.api.client.UnityParticleCollisionEvent.Kind;
import com.kltyton.lumenfx.api.client.UnityParticleCollisionEvent;
import com.kltyton.lumenfx.api.client.UnityParticleTriggerEvent.Type;
import com.kltyton.lumenfx.api.client.UnityParticleTriggerEvent;
import com.kltyton.lumenfx.api.client.parameter.UnityCollisionLayers;
import com.kltyton.lumenfx.effect.render.scene.ScenePrimitiveIds;
import com.kltyton.lumenfx.effect.simulation.unity.UnityForceFields;
import com.kltyton.lumenfx.effect.simulation.unity.UnityNoiseMotion;
import com.kltyton.lumenfx.effect.simulation.unity.UnityBirthRandom;
import com.kltyton.lumenfx.effect.simulation.unity.UnityParticleContacts.Impact;
import com.kltyton.lumenfx.effect.simulation.unity.UnitySubEmitterEvent;
import com.kltyton.lumenfx.effect.simulation.unity.UnityTrailHistory;
import com.kltyton.lumenfx.effect.simulation.unity.UnityTriggerOverlaps;
import com.kltyton.lumenfx.effect.EmitterDefinition;
import com.kltyton.lumenfx.effect.curve.ColorFunction.Color;
import com.kltyton.lumenfx.effect.curve.ScalarFunction;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry;
import com.kltyton.lumenfx.effect.unity.UnityBillboard;
import com.kltyton.lumenfx.effect.unity.UnityConeEmission;
import com.kltyton.lumenfx.effect.unity.UnityEmitterTransform;
import com.kltyton.lumenfx.effect.unity.UnityExternalForces.FieldState;
import com.kltyton.lumenfx.effect.unity.UnityInheritVelocity;
import com.kltyton.lumenfx.effect.unity.UnityLifetimeByEmitterSpeed;
import com.kltyton.lumenfx.effect.unity.UnityMeshEmission;
import com.kltyton.lumenfx.effect.unity.UnityMeshMotion;
import com.kltyton.lumenfx.effect.unity.UnityParticleLights;
import com.kltyton.lumenfx.effect.unity.UnityParticleTrail;
import com.kltyton.lumenfx.effect.unity.UnityAtlasAnimation;
import com.kltyton.lumenfx.effect.unity.UnityRendererBinding.AtlasRowSelection;
import com.kltyton.lumenfx.effect.unity.UnityRendererBinding.LifetimeLoop;
import com.kltyton.lumenfx.effect.unity.UnityRendererBinding;
import com.kltyton.lumenfx.effect.unity.UnityShapeSettings;
import com.kltyton.lumenfx.effect.unity.UnitySubEmitter.Event;
import com.kltyton.lumenfx.effect.unity.UnitySubEmitter;
import com.kltyton.lumenfx.effect.unity.UnityTrigger;
import com.lowdragmc.photon.client.gameobject.emitter.data.ContinuousEmission;
import com.lowdragmc.photon.client.gameobject.emitter.data.EmissionBurstTiming;
import com.lowdragmc.photon.client.gameobject.emitter.data.InheritVelocitySetting.Mode;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.ShapeDistribution;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.TreeSet;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/** CPU particle state and scheduling shared by client presentation and authoritative world simulation. */
public class ParticleEmitterSimulation {
    private static final double PREWARM_STEP = 1.0 / 60;
    private static final Comparator<ParticleState> RING_ORDER = Comparator.comparingDouble(ParticleState::born)
            .thenComparingLong(ParticleState::ringOrder);
    protected final EmitterDefinition definition;
    private final long seed;
    private final SplittableRandom random;
    private final @Nullable UnityBirthRandom unityBirthRandom;
    private final double emitterRandom;
    private final ImprovedNoise noise;
    private final List<ParticleState> particleRows = new ArrayList<>();
    private final List<ParticleState> particleView = Collections.unmodifiableList(particleRows);
    private final Collection<ParticleState> particles;
    private final @Nullable NavigableSet<ParticleState> ring;
    private final @Nullable LifetimeLoop lifetimeLoop;
    // A displaced particle leaves the ring budget while its release phase is still rendered.
    private final @Nullable NavigableSet<ParticleState> loopingSlots;
    private final List<ScheduledBirth> stepBirths = new ArrayList<>();
    private boolean drainRing;
    private long nextRingOrder;

    record ScheduledBirth(double time, double age, int index, int count, int order) { }
    public record StepPlan(double now, Pose pose, boolean simulated) { }
    private final MotionProfile motion;
    private final @Nullable Quaterniond unityShapeRotation;
    private final @Nullable UnityMeshEmission authoredEmissionMesh;
    private @Nullable UnityMeshEmission currentEmissionMesh;
    private @Nullable StaticMeshGeometry lastEmissionGeometry;
    private final @Nullable UnityEmitterTransform emissionTransform;
    private final @Nullable Quaterniond unityEmissionRotation;
    private final @Nullable Vec3 unityBirthPositionScale;
    private final @Nullable SplittableRandom unityShapeRandom;
    private final ContinuousEmission emission;
    private final boolean unityMixedEmission;
    private final int instanceSeed;
    protected final @Nullable UnityTrailHistory trail;
    private final double trailMaximumStep;
    private int trailStepCount;
    private int nextTrailStep;
    private final @Nullable UnityInheritVelocity inheritVelocity;
    private final @Nullable UnityLifetimeByEmitterSpeed lifetimeByEmitterSpeed;
    private final long lifetimeSeed;
    private final long rotationFlipSeed;
    private final long billboardFlipSeed;
    private final long meshFlipSeed;
    private final double flipRotation;
    private final @Nullable UnityBillboard billboard;
    private double emitterSpeedForLifetime;
    private final @Nullable UnityMeshMotion meshMotion;
    private final @Nullable ScalarFunction startDepth;
    private final boolean depthAlongY;
    private final int meshSlots;
    private final int fixedAtlasRow;
    private final AtlasRowSelection atlasRowSelection;
    private final boolean prewarm;
    private boolean prewarmPending;
    private int nextParticleId;
    private final double simulationSpeed;
    private double parentEmissionSpeed = Double.NaN;
    private final double startDelay;
    private double clockOrigin = Double.NaN, hostTime;
    private double started = Double.NaN, previousTime;
    private EffectHandle.Pose previousPose;
    private long ribbon;
    private boolean wasEmitting;
    private boolean hadEmissionInput;
    private boolean emittedInitial;
    private final List<UnitySubEmitter> subEmitterLinks;
    private final List<UnitySubEmitterEvent> subEmitterEvents = new ArrayList<>();
    private final List<UnitySubEmitterEvent> pendingRingEvents = new ArrayList<>();
    private boolean updatingNow;
    private final List<UnityParticleTriggerEvent> triggerEvents = new ArrayList<>();
    private final LinkedHashMap<Collider, List<Hit>> collisionHits = new LinkedHashMap<>();
    private final List<ForceImpact> colliderForces = new ArrayList<>();
    private boolean collectAllEntityContacts;
    private final boolean collisionSubEmitters;
    private final @Nullable UnityTrigger trigger;
    private final @Nullable UnityTriggerOverlaps triggerOverlaps;
    protected final @Nullable UnityParticleLights lightSettings;
    protected final long lightSeed;
    private double lightInterval;
    private final SplittableRandom subEmitterRandom;
    private final @Nullable UnitySubEmitterEvent inherited;
    private final boolean burstOnly;
    private double hostEmissionEnd = Double.POSITIVE_INFINITY;
    private final @Nullable UnityEmitterCapacity sharedCapacity;
    protected final double[] eventScratch = new double[3];
    private EffectHandle.@Nullable Pose updatingPose;
    private double updatingTime;
    private EffectHandle.@Nullable Pose sampleStartPose;
    private double sampleStartTime;

    public ParticleEmitterSimulation(EmitterDefinition definition, long seed) {
        this(definition, seed, null, null);
    }

    protected ParticleEmitterSimulation(EmitterDefinition definition, long seed, @Nullable UnityRendererBinding trailRenderer,
                    @Nullable UnityRendererBinding binding) {
        this(definition, seed, trailRenderer, binding, null, null);
    }

    protected ParticleEmitterSimulation(EmitterDefinition definition, long seed, @Nullable UnityRendererBinding trailRenderer,
                    @Nullable UnityRendererBinding binding, @Nullable UnitySubEmitterEvent inherited,
                    @Nullable UnityEmitterCapacity sharedCapacity) {
        this.definition = definition;
        this.seed = seed;
        this.inherited = inherited;
        this.sharedCapacity = sharedCapacity;
        ring = binding != null && binding.ringBuffered() ? new TreeSet<>(RING_ORDER) : null;
        lifetimeLoop = binding == null ? null : binding.lifetimeLoop();
        loopingSlots = lifetimeLoop == null ? null : new TreeSet<>(RING_ORDER);
        particles = ring == null ? particleRows : ring;
        burstOnly = inherited != null && inherited.link().event() != Event.BIRTH;
        simulationSpeed = binding == null ? 1 : binding.simulationSpeed();
        var delayMaximum = binding == null ? null : binding.startDelayMaximum();
        double minimumDelay = definition.delay();
        startDelay = delayMaximum == null ? minimumDelay
                : Mth.lerp(particleSample(seed ^ 0xa3b8a4877c98f5dfL, 0), minimumDelay, delayMaximum);
        subEmitterLinks = binding == null ? List.of() : binding.subEmitters();
        trigger = binding == null ? null : binding.trigger();
        triggerOverlaps = trigger == null ? null : new UnityTriggerOverlaps(trigger);
        lightSettings = binding == null ? null : binding.lights();
        lightSeed = seed ^ 0x1bbf3fce259cced4L;
        subEmitterRandom = new SplittableRandom(seed ^ 0x6b81337b35318293L);
        random = new SplittableRandom(seed);
        unityBirthRandom = binding == null ? null : new UnityBirthRandom(seed);
        var shape = definition.shape().unity();
        authoredEmissionMesh = shape == null ? null : shape.mesh();
        currentEmissionMesh = authoredEmissionMesh;
        unityShapeRotation = shape == null ? null : new Quaterniond(shape.x(), shape.y(), shape.z(), shape.w()).normalize();
        var transform = binding == null ? null : binding.transform();
        emissionTransform = binding == null ? null : binding.emissionTransform();
        unityEmissionRotation = emissionTransform == null ? null
                : new Quaterniond(emissionTransform.x(), emissionTransform.y(), emissionTransform.z(), emissionTransform.w()).normalize();
        unityBirthPositionScale = emissionTransform == null && transform != null && transform.shapeOnlyScale() ? transform.scale() : null;
        unityShapeRandom = shape == null || shape.randomDirection() == 0 && shape.randomPosition() == 0
                ? null : new SplittableRandom(seed ^ 0x498be41a8eb05bd1L);
        instanceSeed = (int)seed;
        var trailSettings = trailRenderer == null ? null : trailRenderer.particleTrail();
        boolean perParticleTrail = trailSettings != null && trailSettings.mode() == UnityParticleTrail.Mode.PER_PARTICLE;
        meshMotion = binding == null ? null : binding.meshMotion();
        var depth = binding == null ? null : binding.startDepth();
        boolean consumesDepth = binding != null && (binding.trigger() != null
                || subEmitterLinks.stream().anyMatch(link -> link.inherits(UnitySubEmitter.SIZE)));
        startDepth = meshMotion != null ? meshMotion.depth() : depth != null && consumesDepth ? depth.size() : null;
        depthAlongY = meshMotion != null ? meshMotion.forwardToUp() : depth != null && depth.forwardToUp();
        meshSlots = meshMotion == null ? 0 : Objects.requireNonNull(definition.meshSettings()).entries().size();
        if (meshMotion != null && !meshMotion.weights().isEmpty() && meshMotion.weights().size() != meshSlots)
            throw new IllegalArgumentException("Unity mesh weight count must match the mesh slots for " + definition.name());
        atlasRowSelection = binding == null ? AtlasRowSelection.DEFAULT : binding.atlasRowSelection();
        fixedAtlasRow = binding != null && definition.atlasRow() >= 0
                ? UnityAtlasAnimation.fixedRow(definition.atlasRow(), definition.atlasColumns(), definition.atlasRows())
                : -1;
        trail = !perParticleTrail ? null : new UnityTrailHistory(trailSettings, new SplittableRandom(seed).split().nextLong());
        trailMaximumStep = perParticleTrail ? trailSettings.maximumParticleDeltaTime() : Double.POSITIVE_INFINITY;
        noise = new ImprovedNoise(RandomSource.create(seed));
        inheritVelocity = binding == null ? null : binding.inheritVelocity();
        lifetimeByEmitterSpeed = binding == null ? null : binding.lifetimeByEmitterSpeed();
        lifetimeSeed = seed ^ 0xb88a750299c033a7L;
        rotationFlipSeed = seed ^ 0x5c7e401caba2dc19L;
        billboardFlipSeed = seed ^ 0xda639c50fb826b41L;
        meshFlipSeed = seed ^ 0xa0d2e145cb37e061L;
        flipRotation = binding == null ? 0 : binding.flipRotation();
        billboard = binding == null ? null : binding.billboard();
        prewarm = inherited == null && binding != null && binding.prewarm();
        var unityNoise = binding == null || binding.noise() == null ? null
                : new UnityNoiseMotion(binding.noise(), definition.duration(), loops(),
                new SplittableRandom(seed ^ 0xaf7dfa127b35e185L).nextDouble());
        var externalForces = binding == null || binding.externalForces() == null ? null
                : new UnityForceFields(binding.externalForces(), definition.localSpace());
        motion = new MotionProfile(definition, inheritVelocity != null && inheritVelocity.mode() == Mode.CURRENT
                ? inheritVelocity.multiplier() : null, binding == null ? null : binding.velocityLimit(),
                binding == null ? null : binding.spaceMotion(), binding == null ? null : binding.collision(),
                binding == null ? List.of() : binding.sizeOverLifetimeAxes(), binding == null ? null : binding.speedModules(),
                unityNoise, externalForces, binding == null ? null : binding.maximumParticleDeltaTime());
        emitterRandom = random.nextDouble();
        collisionSubEmitters = hasEvent(Event.COLLISION);
        var collision = binding == null ? null : binding.collision();
        boolean hasColliderForce = collision != null && collision.world() != null && collision.world().colliderForce() != 0.0;
        if (collisionSubEmitters || hasColliderForce || collision != null && collision.sendCollisionMessages())
            motion.collisionImpact = this::collisionEvent;
        double constantRate = definition.rate().timeInvariant() ? definition.rate().sample(0, emitterRandom) : Double.NaN;
        unityMixedEmission = binding != null && constantRate != 0.0
                && (definition.distanceSpacing() > 0 || definition.rateOverDistance() != null);
        emission = new ContinuousEmission(definition.duration(), loops(),
                (from, to) -> definition.rate().integral(from, to, emitterRandom), constantRate,
                unityMixedEmission);
    }

    boolean usesWorldCollision() { return motion.contacts != null && motion.contacts.usesWorld(); }

    ParticleEmitterSimulation collectAllEntityContacts() {
        collectAllEntityContacts = true;
        if (motion.contacts != null) motion.contacts.collectAllEntityContacts();
        return this;
    }

    public List<UnitySubEmitterEvent> subEmitterEvents() { return subEmitterEvents; }
    public List<UnityParticleTriggerEvent> triggerEvents() { return triggerEvents; }

    public void appendCollisionEvents(List<UnityParticleCollisionEvent> events) {
        collisionHits.forEach((collider, hits) -> events.add(new UnityParticleCollisionEvent(definition.name(), collider, hits)));
    }

    public void appendColliderForces(List<ForceImpact> forces) {
        forces.addAll(colliderForces);
    }

    void triggerManual(int count) {
        for (int request = 0; request < count; request++)
            for (var particle : particles) particleEvent(Event.MANUAL, particle, previousTime);
    }

    void endEmissionAt(double time) { hostEmissionEnd = Math.min(hostEmissionEnd, time); }
    void parentEmissionSpeed(double speed) { parentEmissionSpeed = speed; }
    double simulationSpeed() { return simulationSpeed; }

    private double simulationTime(double time) {
        if (simulationSpeed == 1 || !Double.isFinite(time)) return time;
        return clockOrigin + (time - clockOrigin) * simulationSpeed;
    }

    double clockOrigin() { return clockOrigin; }

    double hostEventTime(double time) {
        if (simulationSpeed == 1) return time;
        if (simulationSpeed == 0) return hostTime;
        return Math.min(hostTime, clockOrigin + (time - clockOrigin) / simulationSpeed);
    }

    double particleHostDeathTime(ParticleState particle) {
        double end = ring != null && !drainRing ? particle.ringEndTime() : particle.deathTime();
        if (simulationSpeed == 0) return Double.POSITIVE_INFINITY;
        if (simulationSpeed == 1) return end;
        return clockOrigin + (end - clockOrigin) / simulationSpeed;
    }

    private boolean loops() { return !burstOnly && definition.looping(); }

    private boolean hasEvent(Event event) {
        for (var link : subEmitterLinks) if (link.event() == event && link.probability() > 0) return true;
        return false;
    }

    protected StepPlan simulate(double nextHostTime, Pose input, boolean emitting, boolean sampled,
                    @Nullable Vec3 physicsVelocity, @Nullable Level level, @Nullable Pose geometryPose,
                    UnityCollisionLayers collisionLayers, Map<Identifier, StaticMeshGeometry> emissionGeometry,
                    Map<String, List<UnityTrigger.Collider>> triggerColliders) {
        return simulate(nextHostTime, input, emitting, sampled, physicsVelocity, level, geometryPose,
                collisionLayers, emissionGeometry, triggerColliders, Map.of());
    }

    protected StepPlan simulate(double nextHostTime, Pose input, boolean emitting, boolean sampled,
                    @Nullable Vec3 physicsVelocity, @Nullable Level level, @Nullable Pose geometryPose,
                    UnityCollisionLayers collisionLayers, Map<Identifier, StaticMeshGeometry> emissionGeometry,
                    Map<String, List<UnityTrigger.Collider>> triggerColliders, Map<String, FieldState> forceFields) {
        var plan = prepareStep(nextHostTime, input, emitting, sampled, physicsVelocity, level, geometryPose,
                collisionLayers, emissionGeometry, triggerColliders, forceFields);
        if (plan.simulated() && !stepBirths.isEmpty()) {
            double lastAdvanced = Double.NEGATIVE_INFINITY;
            for (var birth : stepBirths) {
                if (birth.time() > lastAdvanced) {
                    advanceActiveThrough(birth.time(), plan.now(), plan.pose(), false);
                    lastAdvanced = birth.time();
                }
                spawnPreparedBirth(birth, plan);
            }
        }
        finishSimulationStep(plan);
        return plan;
    }

    StepPlan prepareStep(double nextHostTime, Pose input, boolean emitting, boolean sampled,
                         @Nullable Vec3 physicsVelocity, @Nullable Level level, @Nullable Pose geometryPose,
                         UnityCollisionLayers collisionLayers, Map<Identifier, StaticMeshGeometry> emissionGeometry) {
        return prepareStep(nextHostTime, input, emitting, sampled, physicsVelocity, level, geometryPose,
                collisionLayers, emissionGeometry, Map.of());
    }

    StepPlan prepareStep(double nextHostTime, Pose input, boolean emitting, boolean sampled,
                         @Nullable Vec3 physicsVelocity, @Nullable Level level, @Nullable Pose geometryPose,
                         UnityCollisionLayers collisionLayers, Map<Identifier, StaticMeshGeometry> emissionGeometry,
                         Map<String, List<UnityTrigger.Collider>> triggerColliders) {
        return prepareStep(nextHostTime, input, emitting, sampled, physicsVelocity, level, geometryPose,
                collisionLayers, emissionGeometry, triggerColliders, Map.of());
    }

    StepPlan prepareStep(double nextHostTime, Pose input, boolean emitting, boolean sampled,
                         @Nullable Vec3 physicsVelocity, @Nullable Level level, @Nullable Pose geometryPose,
                         UnityCollisionLayers collisionLayers, Map<Identifier, StaticMeshGeometry> emissionGeometry,
                         Map<String, List<UnityTrigger.Collider>> triggerColliders, Map<String, FieldState> forceFields) {
        captureEmissionMesh(emissionGeometry);
        updatingNow = true;
        subEmitterEvents.clear();
        if (!pendingRingEvents.isEmpty()) {
            subEmitterEvents.addAll(pendingRingEvents);
            pendingRingEvents.clear();
        }
        triggerEvents.clear();
        collisionHits.clear();
        colliderForces.clear();
        boolean reset = Double.isNaN(clockOrigin) || nextHostTime < hostTime;
        hostTime = nextHostTime;
        if (reset) {
            if (sharedCapacity != null && ring != null) sharedCapacity.drainOwner(this, previousTime);
            clearParticles();
            clockOrigin = inherited == null ? nextHostTime : inherited.time();
            started = clockOrigin;
            previousTime = started;
            if (motion.shurikenClock != null) motion.shurikenClock.reset(started, previousTime);
            previousPose = inherited == null ? input : inherited.pose();
            emission.reset();
            wasEmitting = false;
            hadEmissionInput = false;
            emittedInitial = false;
            nextParticleId = 0;
            if (unityBirthRandom != null) unityBirthRandom.reset();
            nextRingOrder = 0;
            lightInterval = 0;
            prewarmPending = prewarm;
        }
        double now = simulationTime(nextHostTime);
        motion.pose(input);
        if (motion.externalForces != null) motion.externalForces.pose(input, geometryPose, forceFields);
        if (prewarmPending && emitting) {
            prewarmPending = false;
            prewarm(now, input, level, geometryPose, collisionLayers, triggerColliders);
        }
        stepBirths.clear();
        if (simulationSpeed != 0 || reset || emitting && !emittedInitial) {
            prepareAdvance(now, input, emitting, sampled, physicsVelocity, level, nextHostTime, false, geometryPose,
                    collisionLayers, triggerColliders);
            return new StepPlan(now, input, true);
        }
        updatingPose = input;
        updatingTime = now;
        previousPose = input;
        wasEmitting = false;
        return new StepPlan(now, input, false);
    }

    List<ScheduledBirth> scheduledBirths() { return stepBirths; }

    void spawnPreparedBirth(ScheduledBirth birth, StepPlan plan) {
        if (plan.simulated() && hasCapacity()) {
            spawn(birth.time(), plan.now(), plan.pose(), birth.age(), birth.index(), birth.count());
        }
    }

    void finishSimulationStep(StepPlan plan) {
        stepBirths.clear();
        if (plan.simulated()) finishAdvance(plan.now(), plan.pose());
        updatingNow = false;
        syncRingSnapshot();
        if (plan.simulated() && trail != null) trail.advance(plan.now());
    }

    protected List<ParticleState> particlesView() { return particleView; }
    protected ImprovedNoise noise() { return noise; }
    protected long ribbonGeneration() { return ribbon; }
    protected boolean emitting() { return wasEmitting; }
    protected double elapsedEmitterTime(double now) {
        double elapsed = now - started - startDelay;
        return Math.max(0, loops() ? elapsed % definition.duration() : elapsed);
    }

    public void syncRingSnapshot() {
        if (ring != null) {
            if (!ring.isEmpty() && updatingPose != null) {
                removeExpiredFrom(ring, updatingTime);
            }
            particleRows.clear();
            particleRows.addAll(ring);
        }
    }

    public void flushPendingRingEvents() {
        if (!pendingRingEvents.isEmpty()) {
            subEmitterEvents.addAll(pendingRingEvents);
            pendingRingEvents.clear();
        }
    }

    private void captureEmissionMesh(Map<Identifier, StaticMeshGeometry> shapes) {
        if (authoredEmissionMesh == null || authoredEmissionMesh.sourceId() == null) return;
        var frame = shapes.get(authoredEmissionMesh.sourceId());
        if (lastEmissionGeometry == frame) return;
        currentEmissionMesh = frame == null ? authoredEmissionMesh : authoredEmissionMesh.withGeometry(frame);
        lastEmissionGeometry = frame;
    }

    void clearFrozenParticlesOnStop(boolean stopping) {
        if (simulationSpeed == 0 && stopping) clearParticles();
    }

    public void drainRingOnStop(boolean stopping) {
        if (stopping) drainRing = true;
    }

    private void clearParticles() {
        if (sharedCapacity != null) {
            if (ring != null) sharedCapacity.drainOwner(this, previousTime);
            else sharedCapacity.release(particles.size());
        }
        particles.clear();
        particleRows.clear();
        if (ring != null) ring.clear();
        if (loopingSlots != null) loopingSlots.clear();
        if (trail != null) trail.clear();
    }

    private void prewarm(double now, Pose input, @Nullable Level level, @Nullable Pose geometryPose,
                         UnityCollisionLayers collisionLayers, Map<String, List<UnityTrigger.Collider>> triggerColliders) {
        double duration = definition.duration();
        double longestLife = definition.lifetime().magnitudeBound();
        if (lifetimeByEmitterSpeed != null) {
            double stationaryMultiplier = Math.max(lifetimeByEmitterSpeed.multiplier(0, 0),
                    lifetimeByEmitterSpeed.multiplier(0, 1));
            longestLife *= Math.max(0, stationaryMultiplier);
        }
        // Preserve the loop phase and only warm the interval that can contribute live particles.
        double phaseDuration = Math.max(1, Math.ceil(longestLife / duration)) * duration;
        if (!Double.isFinite(phaseDuration))
            throw new IllegalStateException("Unity prewarm interval is not finite for " + definition.name());
        started = now - phaseDuration;
        previousTime = now - longestLife;
        if (motion.shurikenClock != null) motion.shurikenClock.reset(started, previousTime);
        previousPose = input;
        while (previousTime < now) {
            double next = Math.min(now, previousTime + PREWARM_STEP);
            if (next <= previousTime) next = now;
            advance(next, input, true, false, null, level, hostTime, true, geometryPose, collisionLayers, triggerColliders);
        }
    }

    private void advance(double now, Pose input, boolean emitting, boolean sampled,
                         @Nullable Vec3 physicsVelocity, @Nullable Level level, double worldTime, boolean frozenWorld,
                         @Nullable Pose geometryPose, UnityCollisionLayers collisionLayers,
                         Map<String, List<UnityTrigger.Collider>> triggerColliders) {
        prepareAdvance(now, input, emitting, sampled, physicsVelocity, level, worldTime, frozenWorld, geometryPose,
                collisionLayers, triggerColliders);
        if (!stepBirths.isEmpty()) {
            double lastAdvanced = Double.NEGATIVE_INFINITY;
            for (var birth : stepBirths) {
                if (birth.time() > lastAdvanced) {
                    advanceActiveThrough(birth.time(), now, input, false);
                    lastAdvanced = birth.time();
                }
                if (!hasCapacity()) continue;
                spawn(birth.time(), now, input, birth.age(), birth.index(), birth.count());
            }
            stepBirths.clear();
        }
        finishAdvance(now, input);
    }

    void advanceActiveToHostTime(double hostTarget, StepPlan plan) {
        if (!plan.simulated()) return;
        advanceActiveThrough(simulationTime(hostTarget), plan.now(), plan.pose(), false);
    }

    private void prepareAdvance(double now, Pose input, boolean emitting, boolean sampled,
                                @Nullable Vec3 physicsVelocity, @Nullable Level level, double worldTime, boolean frozenWorld,
                                @Nullable Pose geometryPose, UnityCollisionLayers collisionLayers,
                                Map<String, List<UnityTrigger.Collider>> triggerColliders) {
        if (motion.unityNoise != null) motion.unityNoise.phaseStart(started + startDelay);
        if (inheritVelocity != null || lifetimeByEmitterSpeed != null) {
            Vec3 transformVelocity = Vec3.ZERO;
            if (sampled && now > previousTime) {
                var displacement = emitterPosition(input).subtract(emitterPosition(previousPose));
                if (displacement.length() <= definition.movementThreshold())
                    transformVelocity = displacement.scale(1 / (now - previousTime));
            }
            Vec3 physics = simulationSpeed == 1 ? physicsVelocity
                    : physicsVelocity == null || simulationSpeed == 0 ? null : physicsVelocity.scale(1 / simulationSpeed);
            if (inherited != null) transformVelocity = physics != null ? physics
                    : simulationSpeed == 0 ? Vec3.ZERO : inherited.velocity().scale(1 / simulationSpeed);
            if (inheritVelocity != null)
                motion.emitterVelocity = emitterVelocity(inheritVelocity.source(), transformVelocity, physics);
            if (lifetimeByEmitterSpeed != null)
                emitterSpeedForLifetime = emitterVelocity(lifetimeByEmitterSpeed.source(), transformVelocity, physics).length();
        }
        if (motion.contacts != null)
            motion.contacts.prepare(level, previousPose, input, previousTime, now, worldTime,
                    simulationSpeed, frozenWorld, geometryPose, collisionLayers);
        sampleStartPose = previousPose;
        sampleStartTime = previousTime;
        updatingPose = input;
        updatingTime = now;
        trailStepCount = motion.shurikenClock == null
                ? (int)Math.ceil((float)(now - previousTime) / (float)trailMaximumStep)
                : motion.shurikenClock.beginFrame(previousTime, now);
        nextTrailStep = 1;
        if (triggerOverlaps != null) triggerOverlaps.pose(geometryPose == null ? input : geometryPose, triggerColliders);
        if (drainRing) {
            if (sharedCapacity != null && ring != null) sharedCapacity.drainOwner(this, previousTime);
            if (loopingSlots != null) {
                for (var particle : loopingSlots) particle.releaseFromLoop(previousTime);
                loopingSlots.clear();
            }
        }
        if (ring == null || drainRing || trigger != null || motion.contacts != null || lifetimeLoop != null)
            removeExpired(previousTime);
        double emissionThrough = Math.min(now, simulationTime(hostEmissionEnd));
        double elapsed = emissionThrough - started - startDelay;
        double before = previousTime - started - startDelay;
        boolean active = emitting && hostTime <= hostEmissionEnd && elapsed >= 0 && (loops() || elapsed < definition.duration());
        if (active && !wasEmitting) {
            ribbon++;
            emission.reset();
            if (!hadEmissionInput && inherited == null) previousPose = input;
        }
        if (emitting && elapsed >= 0) {
            stepBirths.clear();
            // Shuriken spends the distance contribution first, then applies time emission to the same remainder.
            if (!burstOnly && unityMixedEmission) emitDistance(emissionThrough, now, input);
            if (!burstOnly) emitTime(Math.max(before, 0), elapsed, now, input);
            emitBursts(before, elapsed, now, input);
            if (!burstOnly && !unityMixedEmission) emitDistance(emissionThrough, now, input);
            if (!stepBirths.isEmpty()) {
                stepBirths.sort(Comparator.comparingDouble(ScheduledBirth::time)
                        .thenComparingInt(ScheduledBirth::order)
                        .thenComparingInt(ScheduledBirth::index));
            }
            emittedInitial = true;
        }
        wasEmitting = active;
        hadEmissionInput = emitting;
    }

    private void finishAdvance(double now, Pose input) {
        advanceActiveThrough(now, now, input, true);
        previousTime = now;
        previousPose = input;
    }

    private void advanceActiveThrough(double localTarget, double finalNow, Pose input, boolean finalSlice) {
        double target = Math.min(finalNow, localTarget);
        // Source substeps belong to the frame. Interleaved emitter births do not reset their sampling phase.
        while (nextTrailStep < trailStepCount) {
            double sampleTime = trailStepEnd(nextTrailStep, finalNow);
            if (sampleTime > target) break;
            if (motion.shurikenClock != null) motion.shurikenClock.select(nextTrailStep);
            advanceActiveSlice(sampleTime, input, true, false);
            nextTrailStep++;
            if (sampleTime == target) return;
        }
        if (motion.shurikenClock != null && trailStepCount > 0) motion.shurikenClock.select(nextTrailStep);
        advanceActiveSlice(target, input, finalSlice, finalSlice);
    }

    private double trailStepEnd(int step, double frameEnd) {
        return previousTime + (frameEnd - previousTime) * step / trailStepCount;
    }

    private void startTrail(ParticleState particle, double frameEnd, Pose framePose) {
        if (trail == null) return;
        double firstStepEnd = trailStepCount == 0 ? Double.NaN
                : nextTrailStep < trailStepCount ? trailStepEnd(nextTrailStep, frameEnd) : frameEnd;
        trail.birth(particle, firstStepEnd, framePose, definition, noise);
    }

    private void advanceActiveSlice(double target, Pose input, boolean sampleTrail, boolean frameEnd) {
        Collection<ParticleState> activeSet = ring != null ? ring : particles;
        if (activeSet.isEmpty()) return;
        if (!sampleTrail && motion.shurikenClock != null && ring == null
                && motion.contacts == null && trigger == null) {
            // A birth inside the source step can free capacity without advancing its neighbors twice.
            removeExpiredFrom(activeSet, target);
            return;
        }
        if (ring != null && !drainRing && motion.contacts == null && trigger == null && lifetimeLoop == null && sampleTrail) {
            for (var particle : activeSet) {
                particle.advance(target, definition, noise, motion);
                if (trail != null) trail.update(particle, target, input, definition, noise);
            }
            return;
        }
        activeSet.removeIf(particle -> {
            double motionTarget = drainRing && lifetimeLoop == null && particle.ringRetained()
                    ? Math.min(target, Math.max(particle.deathTime(), previousTime)) : target;
            particle.advance(motionTarget, definition, noise, motion);
            if (trigger != null && frameEnd && motionTarget == target && particle.acceptsTriggerAt(target))
                updateTrigger(particle, target);
            if (ring != null && !drainRing && particle.ringRetained()) {
                if (sampleTrail && trail != null) trail.update(particle, target, input, definition, noise);
                return false;
            }
            double death = particle.deathTime();
            if (drainRing && particle.ringRetained()) {
                death = Math.max(death, Math.min(target, previousTime));
                particle.recycleFromRing(death);
            }
            boolean died = death <= target;
            if (!died && !sampleTrail) return false;
            if (trail != null) {
                boolean atDeathBoundary = sampleTrail && Double.isFinite(death)
                        && (float)(target - particle.born()) == (float)(death - particle.born());
                // The Per Particle strip freezes at the authored Float32 lifetime. The owner
                // can still supply the zero-lifetime particle to the renderer for this frame.
                boolean freezesTrail = died || motion.shurikenClock != null && atDeathBoundary;
                if (!died || atDeathBoundary) trail.update(particle, target, input, definition, noise);
                if (freezesTrail) trail.end(particle, target, atDeathBoundary);
            }
            if (died) {
                particleEvent(Event.DEATH, particle, death);
                if (loopingSlots != null) {
                    loopingSlots.remove(particle);
                    if (sharedCapacity != null) sharedCapacity.removeLoopingParticle(particle);
                } else if (ring != null && sharedCapacity != null) {
                    sharedCapacity.removePauseParticle(particle);
                }
                if (sharedCapacity != null && ring == null) sharedCapacity.release(1);
            }
            return died;
        });
    }

    private static Vec3 emitterVelocity(UnityInheritVelocity.Source source, Vec3 transform,
                                        @Nullable Vec3 physics) {
        return source == UnityInheritVelocity.Source.RIGIDBODY && physics != null ? physics : transform;
    }

    private void removeExpired(double now) {
        removeExpiredFrom(particles, now);
        if (ring != null && !ring.isEmpty()) removeExpiredFrom(ring, now);
    }

    private void removeExpiredFrom(Collection<ParticleState> source, double now) {
        source.removeIf(particle -> {
            if (ring != null && !drainRing && particle.ringRetained()) return false;
            boolean releasedFromRing = particle.ringRetained();
            double naturalDeath = particle.deathTime();
            double death = releasedFromRing ? Math.max(naturalDeath, previousTime) : naturalDeath;
            if (now < death) return false;
            if (releasedFromRing) particle.recycleFromRing(death);
            if (trail != null || hasEvent(Event.DEATH) || hasEvent(Event.COLLISION)) {
                particle.advance(death, definition, noise, motion);
                death = (!releasedFromRing || particle.deathTime() < naturalDeath)
                        ? particle.deathTime()
                        : particle.ringEndTime();
                if (trail != null) trail.end(particle, death, false);
                particleEvent(Event.DEATH, particle, death);
            }
            if (sharedCapacity != null && ring == null) sharedCapacity.release(1);
            if (loopingSlots != null) {
                loopingSlots.remove(particle);
                if (sharedCapacity != null) sharedCapacity.removeLoopingParticle(particle);
            } else if (ring != null && sharedCapacity != null) {
                sharedCapacity.removePauseParticle(particle);
            }
            return true;
        });
    }

    private void emitTime(double start, double end, double now, EffectHandle.Pose pose) {
        if (definition.renderer() == EmitterDefinition.Renderer.BEAM) return;
        if (inherited != null && Double.isFinite(parentEmissionSpeed) && parentEmissionSpeed > 0
                && simulationSpeed > 0 && parentEmissionSpeed != simulationSpeed) {
            double ratio = parentEmissionSpeed / simulationSpeed;
            emission.emitTime(0, start * ratio, end * ratio, this::hasAnyCapacity, scaledBorn -> {
                double born = started + startDelay + scaledBorn / ratio;
                if (Double.isFinite(hostEmissionEnd) && hostEventTime(born) >= hostEmissionEnd - 1e-9) return;
                scheduleBirth(born, emitterAge(born), 0, 1, 1);
            });
            return;
        }
        emission.emitTime(started + startDelay, start, end, this::hasAnyCapacity,
                born -> scheduleBirth(born, emitterAge(born), 0, 1, 1));
    }

    private void emitDistance(double emissionThrough, double now, EffectHandle.Pose pose) {
        if (!hadEmissionInput || definition.distanceSpacing() <= 0 && definition.rateOverDistance() == null) return;
        double start = Math.max(previousTime, started + startDelay);
        double end = loops() ? emissionThrough : Math.min(emissionThrough, started + startDelay + definition.duration());
        if (end <= start) return;
        double segmentDistance = emitterPosition(previousPose).distanceTo(emitterPosition(pose));
        if (segmentDistance > definition.movementThreshold()) {
            ribbon++;
            emission.resetDistance();
            return;
        }
        double span = now - previousTime;
        if (!(span > 0)) return;
        double distance = segmentDistance * ((end - start) / span);
        var distanceRate = definition.rateOverDistance();
        if (distanceRate != null) {
            double elapsedStart = Math.max(0, start - started - startDelay);
            double elapsedEnd = Math.max(elapsedStart, end - started - startDelay);
            emission.emitDistance(distance, elapsedStart, elapsedEnd,
                    (from, to) -> distanceRate.integral(from, to, emitterRandom),
                    this::hasAnyCapacity, fraction -> {
                        double born = Mth.lerp(fraction, start, end);
                        scheduleBirth(born, emitterAge(born), 0, 1, 1);
                    });
        } else if (definition.distanceSpacing() > 0) {
            emission.emitDistance(distance, definition.distanceSpacing(), this::hasAnyCapacity, fraction -> {
                double born = Mth.lerp(fraction, start, end);
                scheduleBirth(born, emitterAge(born), 0, 1, 1);
            });
        }
    }

    private void emitBursts(double before, double elapsed, double now, EffectHandle.Pose pose) {
        if (definition.bursts().isEmpty()) return;
        long first = Math.max(0, (long)Math.floor(before / definition.duration()));
        long last = Math.max(0, (long)Math.floor(elapsed / definition.duration()));
        if (!loops()) last = 0;
        for (long cycle = first; cycle <= last; cycle++) for (var burst : definition.bursts()) {
            if (burst.probability() == 0) continue;
            double start = cycle * definition.duration() + burst.time();
            long repeats = burst.cycles() == 0 ? Long.MAX_VALUE : burst.cycles();
            long firstRepeat = !emittedInitial && start == 0 ? 0
                    : EmissionBurstTiming.firstRepeat(start, burst.interval(), repeats, before, false);
            double cycleEnd = (cycle + 1) * definition.duration();
            for (long repeat = firstRepeat; repeat < repeats; repeat++) {
                double time = EmissionBurstTiming.time(start, burst.interval(), repeat);
                if (time > elapsed || burst.cycles() == 0 && time >= cycleEnd) break;
                if (time <= before && (emittedInitial || time != 0)) continue;
                if (burst.probability() < 1 && random.nextDouble() >= burst.probability()) continue;
                double born = started + startDelay + time;
                double age = emitterAge(born);
                int count = burstCount(burst, age);
                int limit = ring != null ? count : Math.min(count, Math.max(0,
                        sharedCapacity == null ? definition.maximumParticles() : sharedCapacity.maximum()));
                for (int i = 0; i < limit; i++)
                    scheduleBirth(born, age, i, count, 0);
            }
        }
    }

    private int burstCount(EmitterDefinition.Burst burst, double age) {
        var count = burst.countCurve();
        if (count != null) return (int) Math.clamp(Math.round(count.sample(age, random.nextDouble())), 0, Integer.MAX_VALUE);
        if (burst.maximum() == Integer.MAX_VALUE && burst.minimum() == 0)
            return (int) random.nextLong((long) Integer.MAX_VALUE + 1);
        return burst.minimum() + random.nextInt(burst.maximum() - burst.minimum() + 1);
    }

    private boolean hasAnyCapacity() {
        return (sharedCapacity == null ? definition.maximumParticles() : sharedCapacity.maximum()) > 0;
    }

    private boolean hasCapacity() {
        if (ring != null) {
            return (sharedCapacity == null ? definition.maximumParticles() : sharedCapacity.maximum()) > 0;
        }
        return sharedCapacity == null ? particles.size() < definition.maximumParticles() : sharedCapacity.hasRoom();
    }

    private void scheduleBirth(double born, double age, int index, int count, int order) {
        stepBirths.add(new ScheduledBirth(born, age, index, count, order));
    }

    private EffectHandle.Pose interpolatePose(EffectHandle.Pose next, double time, double now) {
        return interpolatePose(previousPose, previousTime, next, time, now);
    }

    private static EffectHandle.Pose interpolatePose(EffectHandle.Pose previous, double before,
                                                     EffectHandle.Pose next, double time, double now) {
        double fraction = now == before ? 1 : Math.clamp((time - before) / (now - before), 0, 1);
        if (fraction == 0) return previous;
        if (fraction == 1 || previous.equals(next)) return next;
        float alpha = (float) fraction;
        var rotation = new Quaternionf(previous.x(), previous.y(), previous.z(), previous.w())
                .slerp(new Quaternionf(next.x(), next.y(), next.z(), next.w()), alpha);
        return new EffectHandle.Pose(previous.position().lerp(next.position(), fraction),
                rotation.x(), rotation.y(), rotation.z(), rotation.w(),
                Mth.lerp(alpha, previous.scaleX(), next.scaleX()),
                Mth.lerp(alpha, previous.scaleY(), next.scaleY()),
                Mth.lerp(alpha, previous.scaleZ(), next.scaleZ()));
    }

    private void spawn(double born, double now, EffectHandle.Pose currentPose, double age, int batchIndex, int batchCount) {
        int particleId = nextParticleId++;
        if (inherited != null && inherited.link().inherits(UnitySubEmitter.DURATION)) age = inherited.systemAge();
        // Keep the auxiliary stream aligned for shape and motion sampling.
        var birthRandom = unityBirthRandom == null ? null : unityBirthRandom.next(born);
        double lifetimeRandom = random.nextDouble();
        double life = definition.lifetime().sample(age, birthRandom == null ? lifetimeRandom : birthRandom.lifetime());
        if (inherited != null && inherited.link().inherits(UnitySubEmitter.LIFETIME)) {
            double parentRemaining = inherited.particle() != null && Double.isFinite(hostEmissionEnd)
                    ? Math.max(0.0, hostEmissionEnd - hostEventTime(born))
                    : inherited.remainingLifetime();
            life *= parentRemaining;
        }
        if (lifetimeByEmitterSpeed != null) {
            double factor = particleSample(lifetimeSeed, particleId);
            life *= lifetimeByEmitterSpeed.multiplier(emitterSpeedForLifetime, factor);
        }
        if (life <= 0) return;
        boolean lit = selectLight(particleId);
        var pose = interpolatePose(currentPose, born, now);
        var shape = definition.shape().unity();
        double azimuthFraction = shapeAzimuth(shape, born, age, batchIndex, batchCount);
        var mesh = currentEmissionMesh;
        var edgeTraversal = mesh == null ? null : mesh.edgeTraversal();
        if (edgeTraversal != null)
            azimuthFraction = shapeTraversal(edgeTraversal, 1, born, age, batchIndex, batchCount, azimuthFraction, false);
        var meshSample = mesh == null ? null : mesh.sample(azimuthFraction, particleId, random, definition.startDirection());
        if (mesh != null && meshSample == null) return;
        double azimuth = azimuthFraction * (2 * Math.PI);
        var cone = shape == null ? null : shape.cone();
        var coneSample = cone == null ? null : UnityConeEmission.sample(cone, definition.shape().size(), definition.shape().angle(),
                shape.radiusThickness(), azimuth, random);
        Vec3 position;
        Vec3 direction = null;
        if (meshSample != null) {
            position = meshSample.position();
            direction = meshSample.direction();
        } else if (coneSample != null) {
            position = coneSample.position();
            direction = coneSample.direction();
        } else position = shape(azimuthFraction);
        Color shapeTint = meshSample == null ? null : meshSample.color();
        var shapeTexture = shape == null ? null : shape.texture();
        if (shapeTexture != null) {
            shapeTint = shapeTexture.sample(definition.shape(), position, azimuth, coneSample == null ? 0 : coneSample.outerRadius());
            if (shapeTint == null) return;
        }
        Vec3 velocity = new Vec3(between(definition.velocityMinimum().x, definition.velocityMaximum().x),
                between(definition.velocityMinimum().y, definition.velocityMaximum().y), between(definition.velocityMinimum().z, definition.velocityMaximum().z));
        if (direction == null) direction = initialDirection(position, azimuth);
        if (shape != null) {
            var rotation = Objects.requireNonNull(unityShapeRotation);
            position = rotateShape(position, rotation).add(shape.offset());
            direction = rotateShape(direction, rotation);
            var shapeRandom = unityShapeRandom;
            if (shape.randomPosition() != 0) {
                var jitter = Objects.requireNonNull(shapeRandom);
                double radius = shape.randomPosition() * ShapeDistribution.shellRadius(0, 1, jitter.nextDouble());
                position = position.add(randomUnit(jitter).scale(radius));
            }
            if (shape.randomDirection() != 0)
                direction = direction.lerp(randomUnit(Objects.requireNonNull(shapeRandom)), shape.randomDirection()).normalize();
            if (shape.sphericalDirection() != 0 && position.lengthSqr() > 0)
                direction = direction.lerp(position.normalize(), shape.sphericalDirection()).normalize();
        }
        if (unityBirthPositionScale != null) position = position.multiply(unityBirthPositionScale);
        double speedRandom = random.nextDouble();
        if (birthRandom != null) speedRandom = birthRandom.speed();
        velocity = velocity.add(direction.scale(definition.startSpeed().sample(age, speedRandom)));
        if (emissionTransform != null) {
            var es = emissionTransform.scale();
            position = position.multiply(es);
            if (!emissionTransform.shapeOnlyScale()) velocity = velocity.multiply(es);
            if (unityEmissionRotation != null) {
                position = rotateShape(position, unityEmissionRotation);
                velocity = rotateShape(velocity, unityEmissionRotation);
                direction = rotateShape(direction, unityEmissionRotation);
            }
            position = position.add(emissionTransform.position());
        }
        if (!definition.localSpace()) { position = pose.transformPosition(position); velocity = pose.transformDirection(velocity); }
        boolean scaleSize = !definition.localSpace() && definition.scaleSizeWithOwner();
        boolean mappedMesh = meshMotion != null && meshMotion.forwardToUp();
        double widthScale = scaleSize ? Math.abs(pose.scaleX()) : 1;
        double heightScale = scaleSize ? Math.abs(mappedMesh ? pose.scaleZ() : pose.scaleY()) : 1;
        if (emissionTransform != null && !emissionTransform.shapeOnlyScale()) {
            var es = emissionTransform.scale();
            widthScale *= Math.abs(es.x);
            heightScale *= Math.abs(mappedMesh ? es.z : es.y);
        }
        double sizeRandom = random.nextDouble();
        if (birthRandom != null) sizeRandom = birthRandom.size();
        double particleRandom = random.nextDouble();
        if (inheritVelocity != null && inheritVelocity.mode() == Mode.INITIAL)
            velocity = velocity.add(motion.emitterVelocity.scale(inheritVelocity.multiplier().sample(0, particleRandom)));
        var sampling = definition.randomization();
        double r = particleRandom, g = particleRandom, b = particleRandom, a = particleRandom;
        if (sampling.independentColor()) {
            int context = instanceSeed << 16;
            r = NiagaraRandom.unit(particleId, sampling.colorSeed(), context | sampling.colorContext());
            g = b = a = r;
        }
        double rotationRandom = random.nextDouble();
        if (birthRandom != null) rotationRandom = birthRandom.rotation();
        Color color = definition.startColor().sample(age, birthRandom == null ? particleRandom : birthRandom.color());
        if (shapeTint != null) color = multiply(color, shapeTint);
        Vec3 inheritedRotation = inherited != null && inherited.link().inherits(UnitySubEmitter.ROTATION)
                ? inherited.rotation() : Vec3.ZERO;
        double startRotation = definition.rotation().sample(age, rotationRandom) + inheritedRotation.z;
        double width = definition.width().sample(age, sizeRandom) * widthScale;
        double height = definition.height().sample(age, sizeRandom) * heightScale;
        if (inherited != null) {
            if (inherited.link().inherits(UnitySubEmitter.COLOR)) color = multiply(color, inherited.color());
            if (inherited.link().inherits(UnitySubEmitter.SIZE)) {
                width *= inherited.size().x;
                height *= inherited.size().y;
            }
        }
        var particle = new ParticleState(position, velocity, born, life,
                width, height, startRotation, particleRandom, ribbon, color,
                definition.mass().sample(age, particleRandom), definition.integration() == EmitterDefinition.Integration.NIAGARA
                        ? NiagaraRandom.unit(particleId, sampling.dragSeed(), (instanceSeed << 16) | sampling.dragContext()) : particleRandom,
                r, g, b, a);
        particle.unityParticleId(particleId);
        boolean flippedSpin = flipRotation == 1 || flipRotation > 0 && particleSample(rotationFlipSeed, particleId) < flipRotation;
        if (flippedSpin) particle.reverseSpin();
        if (billboard != null && (billboard.flipX() > 0 || billboard.flipY() > 0))
            particle.billboardFlip(particleSample(billboardFlipSeed, particleId) < billboard.flipX(),
                    particleSample(billboardFlipSeed ^ 0x724f58491575c8bdL, particleId) < billboard.flipY());
        if (shape != null && shape.alignToDirection()) {
            Vec3 birthDirection = velocity;
            if (birthDirection.lengthSqr() <= 1e-12)
                birthDirection = definition.localSpace() ? direction : pose.transformDirection(direction);
            particle.unityBirthDirection(birthDirection);
        }
        if (lit) {
            particle.unityLightId = particleId;
            if (Objects.requireNonNull(lightSettings).profile() != null) {
                particle.unitySceneLightIdentity = ScenePrimitiveIds.next();
                if (!definition.localSpace())
                    particle.unityLightBirthRotation = new Quaterniond(pose.x(), pose.y(), pose.z(), pose.w());
            }
        }
        int meshSlot = 0;
        if (startDepth != null || (billboard != null && !billboard.startRotation().isEmpty())
                || inheritedRotation.x != 0 || inheritedRotation.y != 0) {
            List<ScalarFunction> rotation = meshMotion != null ? meshMotion.startRotation()
                    : billboard == null ? List.of() : billboard.startRotation();
            double depth = width;
            int flipMask = 0;
            if (startDepth != null) {
                double depthScale = scaleSize ? Math.abs(depthAlongY ? pose.scaleY() : pose.scaleZ()) : 1;
                if (emissionTransform != null && !emissionTransform.shapeOnlyScale()) {
                    var es = emissionTransform.scale();
                    depthScale *= Math.abs(depthAlongY ? es.y : es.z);
                }
                depth = startDepth.sample(age, sizeRandom) * depthScale;
                if (inherited != null && inherited.link().inherits(UnitySubEmitter.SIZE)) depth *= inherited.size().z;
            }
            if (meshMotion != null) {
                meshSlot = meshSlots < 2 ? 0 : Objects.requireNonNull(birthRandom).meshIndex(meshSlots, meshMotion.weights());
                var flip = meshMotion.flip();
                if (flip.x > 0 && particleSample(meshFlipSeed, particleId) < flip.x) flipMask |= 1;
                if (flip.y > 0 && particleSample(meshFlipSeed ^ 0xd5f0f809b8486589L, particleId) < flip.y) flipMask |= 2;
                if (flip.z > 0 && particleSample(meshFlipSeed ^ 0x49d732c270ef5b21L, particleId) < flip.z) flipMask |= 4;
            }
            double x = (rotation.isEmpty() ? 0 : rotation.get(0).sample(age, rotationRandom)) + inheritedRotation.x;
            double y = (rotation.isEmpty() ? 0 : rotation.get(1).sample(age, rotationRandom)) + inheritedRotation.y;
            if (flippedSpin) { x = -x; y = -y; }
            particle.meshBirth(depth, x, y, meshSlot, flipMask);
        }
        switch (atlasRowSelection) {
            case RANDOM -> particle.atlasRowAtBirth(Objects.requireNonNull(birthRandom).randomAtlasRow(definition.atlasRows()));
            case MESH_INDEX -> particle.atlasRowAtBirth(Objects.requireNonNull(birthRandom).meshAtlasRow(meshSlots, definition.atlasRows()));
            case DEFAULT -> { if (fixedAtlasRow >= 0) particle.atlasRowAtBirth(fixedAtlasRow); }
        }
        if (!definition.localSpace() && (!motion.spaceVelocity.isEmpty() || !motion.spaceForce.isEmpty()))
            particle.birthOrientation(new Quaterniond(pose.x(), pose.y(), pose.z(), pose.w()).normalize());
        particle.advance(born, definition, noise, motion);
        if (loopingSlots != null) {
            particle.retainInRing(nextRingOrder++, lifetimeLoop);
            Objects.requireNonNull(ring).add(particle);
            loopingSlots.add(particle);
            particleEvent(Event.BIRTH, particle, born);
            startTrail(particle, now, currentPose);
            if (sharedCapacity != null) {
                sharedCapacity.addLoopingSlot(
                        new UnityEmitterCapacity.RingSlot(this, particle, hostEventTime(born), sharedCapacity.nextOrder()),
                        hostEventTime(born));
            } else if (loopingSlots.size() > definition.maximumParticles()) {
                var replaced = Objects.requireNonNull(loopingSlots.pollFirst());
                replaced.advance(born, definition, noise, motion);
                replaced.releaseFromLoop(born);
            }
            return;
        } else if (ring != null) {
            particle.retainInRing(nextRingOrder++, null);
            ring.add(particle);
            particleEvent(Event.BIRTH, particle, born);
            startTrail(particle, now, currentPose);
            if (sharedCapacity != null) {
                sharedCapacity.addPauseSlot(
                        new UnityEmitterCapacity.RingSlot(this, particle, hostEventTime(born), sharedCapacity.nextOrder()),
                        hostEventTime(born));
            } else if (ring.size() > definition.maximumParticles()) {
                var replaced = Objects.requireNonNull(ring.pollFirst());
                double replacement = Math.max(born, replaced.born());
                recycleRingParticle(replaced, replacement);
            }
            return;
        }
        particleEvent(Event.BIRTH, particle, born);
        startTrail(particle, now, currentPose);
        particles.add(particle);
        if (sharedCapacity != null) sharedCapacity.occupy();
    }

    void evictSharedRingSlot(ParticleState particle, double hostEvictTime) {
        if (ring != null) ring.remove(particle);
        double localEvict = Math.max(particle.born(), simulationTime(hostEvictTime));
        recycleRingParticle(particle, localEvict);
    }

    void releaseSharedLoopingSlot(ParticleState particle, double hostReleaseTime) {
        if (loopingSlots != null) loopingSlots.remove(particle);
        double localRelease = Math.max(particle.born(), simulationTime(hostReleaseTime));
        particle.advance(localRelease, definition, noise, motion);
        if (!particle.ringRetained() && particle.deathTime() < localRelease) {
            double actualDeath = particle.deathTime();
            if (trail != null) trail.end(particle, actualDeath, false);
            particleEvent(Event.DEATH, particle, actualDeath);
            if (ring != null) ring.remove(particle);
            return;
        }
        particle.releaseFromLoop(localRelease);
    }

    private void recycleRingParticle(ParticleState particle, double time) {
        particle.advance(time, definition, noise, motion);
        particle.recycleFromRing(time);
        double actualEnd = Math.min(time, particle.ringEndTime());
        if (trail != null) trail.end(particle, actualEnd, false);
        particleEvent(Event.DEATH, particle, actualEnd);
    }

    private double emitterAge(double time) {
        double age = Math.max(0, (time - started - startDelay) / definition.duration());
        return loops() ? age % 1 : Math.min(1, age);
    }

    private void particleEvent(Event event, ParticleState particle, double time) {
        if (!hasEvent(event)) return;
        PoseData value = particlePose(particle, time, eventScratch);
        publishEvent(event, particle, time, value.pose(), value.velocity(), particle.normalizedAge(time));
    }

    private void collisionEvent(ParticleState particle, Impact impact) {
        var hit = impact.hit();
        if (hit != null) {
            var collider = Objects.requireNonNull(impact.collider());
            double hostEvent = hostEventTime(hit.time());
            if (motion.contacts != null && motion.contacts.sendsCollisionMessages()) {
                collisionHits.computeIfAbsent(collider, ignored -> new ArrayList<>()).add(new Hit(hostEvent,
                        hit.intersection(), hit.normal(), hit.incidentVelocity().scale(simulationSpeed),
                        hit.appliedForce(), hit.particleDiameter()));
            }
            if (collider.kind() == Kind.ENTITY && (collectAllEntityContacts || hit.appliedForce().lengthSqr() > 0.0)) {
                var incident = hit.incidentVelocity();
                double speed = incident.length();
                var dir = speed > 1e-8 ? incident.scale(1.0 / speed) : hit.normal().scale(-1.0);
                colliderForces.add(new ForceImpact(null, 0L, seed, definition.name(), collider.key(),
                        particle.unityParticleId(), particle.nextUnityCollisionSeq(), hostEvent,
                        hit.intersection(), dir, hit.normal(), speed, hit.particleDiameter(), hit.appliedForce()));
            }
        }
        if (collisionSubEmitters) {
            var pose = interpolatePose(Objects.requireNonNull(updatingPose), impact.time(), updatingTime);
            publishEvent(Event.COLLISION, particle, impact.time(), pose.withPosition(impact.position()), impact.velocity(),
                    Math.clamp(particle.normalizedAge(impact.time()) + impact.lifeLost(), 0, 1));
        }
    }

    private void updateTrigger(ParticleState particle, double time) {
        var settings = Objects.requireNonNull(trigger);
        var overlaps = Objects.requireNonNull(triggerOverlaps);
        var sample = particlePose(particle, time, eventScratch);
        double age = particle.normalizedAge(time);
        Vec3 size = currentSize(particle, age, sample.velocity().length());
        double radius = Math.max(Math.abs(size.x), Math.max(Math.abs(size.y), Math.abs(size.z))) * .5;
        if (definition.localSpace()) radius *= Math.max(Math.abs(sample.pose().scaleX()),
                Math.max(Math.abs(sample.pose().scaleY()), Math.abs(sample.pose().scaleZ())));
        Vec3 currPos = sample.pose().position();
        Vec3 prevPos = particle.unityTriggerPrevPos;
        long mask = overlaps.overlap(currPos, radius);
        if (prevPos != null) mask |= overlaps.overlap(prevPos, radius);
        particle.unityTriggerPrevPos = currPos;
        long previous = particle.unityTriggerMask;
        particle.unityTriggerMask = mask;
        boolean spawn = hasEvent(Event.TRIGGER);
        long entered = previous == 0 ? mask : 0L, exited = mask == 0 ? previous : 0L;
        boolean killed = false;
        if (entered != 0) killed |= triggerAction(time, sample, age, Type.ENTER, settings.enter(), entered);
        if (exited != 0) killed |= triggerAction(time, sample, age, Type.EXIT, settings.exit(), 0L);
        double reportAge = killed ? 1.0 : age;
        killed |= triggerAction(time, sample, reportAge, mask == 0 ? Type.OUTSIDE : Type.INSIDE,
                mask == 0 ? settings.outside() : settings.inside(), mask);
        if (spawn && (entered != 0 && settings.enter() != UnityTrigger.Action.IGNORE
                || exited != 0 && settings.exit() != UnityTrigger.Action.IGNORE
                || mask != 0 && settings.inside() != UnityTrigger.Action.IGNORE
                || mask == 0 && settings.outside() != UnityTrigger.Action.IGNORE))
            publishEvent(Event.TRIGGER, particle, time, sample.pose(), sample.velocity(), age);
        if (killed) {
            if (ring != null && !drainRing && particle.ringRetained()) particle.expireInRing(time);
            else particle.expireAt(time);
        }
    }

    private boolean triggerAction(double time, PoseData sample, double age,
                                  Type type, UnityTrigger.Action action, long mask) {
        if (action == UnityTrigger.Action.CALLBACK)
            triggerEvents.add(new UnityParticleTriggerEvent(definition.name(), type, hostEventTime(time),
                    sample.pose().position(), sample.velocity(), age, Objects.requireNonNull(triggerOverlaps).names(mask)));
        return action == UnityTrigger.Action.KILL;
    }

    record PoseData(EffectHandle.Pose pose, Vec3 velocity) { }

    PoseData particlePoseAtHostTime(ParticleState particle, double time, double[] scratch) {
        var sample = particlePose(particle, simulationTime(time), scratch);
        return simulationSpeed == 1 ? sample : new PoseData(sample.pose(), sample.velocity().scale(simulationSpeed));
    }

    private PoseData particlePose(ParticleState particle, double time, double[] scratch) {
        var currentPose = Objects.requireNonNull(updatingPose);
        var pose = sampleStartPose == null ? currentPose
                : interpolatePose(sampleStartPose, sampleStartTime, currentPose, time, updatingTime);
        particle.samplePosition(time, definition, noise, scratch, 0);
        if (definition.localSpace()) pose.transformPosition(scratch, 0);
        var point = new Vec3(scratch[0], scratch[1], scratch[2]);
        particle.sampleVelocity(time, definition, scratch, 0);
        var velocity = new Vec3(scratch[0], scratch[1], scratch[2]);
        if (definition.localSpace()) velocity = pose.transformDirection(velocity);
        return new PoseData(pose.withPosition(point), velocity);
    }

    private void publishEvent(Event event, ParticleState particle, double time, EffectHandle.Pose pose, Vec3 velocity, double age) {
        double speed = velocity.length();
        for (var link : subEmitterLinks) {
            if (link.event() != event || link.probability() <= 0
                    || link.probability() < 1 && subEmitterRandom.nextDouble() >= link.probability()) continue;
            Color color = link.inherits(UnitySubEmitter.COLOR) ? currentColor(particle, age, speed) : null;
            Vec3 size = link.inherits(UnitySubEmitter.SIZE) ? currentSize(particle, age, speed) : null;
            Vec3 rotation = link.inherits(UnitySubEmitter.ROTATION) ? currentRotation(particle, age, time) : null;
            Vec3 hostVelocity = simulationSpeed == 1 ? velocity : velocity.scale(simulationSpeed);
            var out = updatingNow ? subEmitterEvents : pendingRingEvents;
            out.add(new UnitySubEmitterEvent(link, hostEventTime(time), pose, hostVelocity,
                    event == Event.BIRTH ? particle : null, color, size, rotation,
                    event == Event.DEATH ? 0.0 : Math.max(0.0, particle.lifetime() - (time - particle.born())), emitterAge(time)));
        }
    }

    protected Color currentColor(ParticleState particle, double age, double speed) {
        Color color = multiply(particle.startColor(), definition.colorOverLife().sample(age, particle.random()));
        var bySpeed = particle.speedColor(speed, definition);
        return bySpeed == null ? color : multiply(color, bySpeed);
    }

    private boolean selectLight(int id) {
        var light = lightSettings;
        if (light == null || light.maximum() == 0 || light.ratio() == 0) return false;
        if (light.randomDistribution()) {
            double sample = particleSample(lightSeed, id);
            return sample < light.ratio();
        }
        lightInterval += light.ratio();
        if (lightInterval < 1 - 1e-12) return false;
        lightInterval -= 1;
        return true;
    }

    private static Color multiply(Color left, Color right) {
        return new Color(left.red() * right.red(), left.green() * right.green(),
                left.blue() * right.blue(), left.alpha() * right.alpha());
    }

    private Vec3 currentSize(ParticleState particle, double age, double speed) {
        var mesh = particle.meshBirth();
        return new Vec3(particle.width() * particle.sizeScale(0, age, speed, definition),
                particle.height() * particle.sizeScale(1, age, speed, definition),
                (mesh == null ? particle.width() : mesh.depth()) * particle.sizeScale(2, age, speed, definition));
    }

    protected Vec3 currentRotation(ParticleState particle, double age, double time) {
        var mesh = particle.meshBirth();
        double x = (mesh == null ? 0 : mesh.rotationX()) + particle.integratedRotationX();
        double y = (mesh == null ? 0 : mesh.rotationY()) + particle.integratedRotationY();
        List<ScalarFunction> angular = meshMotion != null ? meshMotion.angularVelocity()
                : billboard != null ? billboard.angularVelocity() : List.of();
        if (!angular.isEmpty()) {
            x += particle.spinOverLife(angular.get(0), age, time);
            y += particle.spinOverLife(angular.get(1), age, time);
        }
        return new Vec3(x, y, particle.rotationZ(age, time, definition));
    }

    private static double particleSample(long seed, int id) {
        return (RandomSupport.mixStafford13(seed + Integer.toUnsignedLong(id)) >>> 11) * 0x1.0p-53;
    }

    private Vec3 initialDirection(Vec3 position, double shapeAngle) {
        boolean unity = definition.shape().unity() != null;
        return switch (definition.shape().type()) {
            case CIRCLE -> {
                if (position.lengthSqr() != 0) yield position.normalize();
                double angle = unity ? shapeAngle : random.nextDouble() * Math.PI * 2;
                yield new Vec3(Math.cos(angle), 0, Math.sin(angle));
            }
            case SPHERE, HEMISPHERE -> {
                if (position.lengthSqr() != 0) yield position.normalize();
                double y = between(definition.shape().type() == EmitterDefinition.ShapeType.HEMISPHERE ? 0 : -1, 1);
                double angle = unity ? shapeAngle : random.nextDouble() * Math.PI * 2;
                double circle = Math.sqrt(1 - y * y);
                yield new Vec3(circle * Math.cos(angle), y, circle * Math.sin(angle));
            }
            case EDGE -> new Vec3(0, 1, 0);
            case DONUT -> {
                var shape = definition.shape();
                var donut = Objects.requireNonNull(Objects.requireNonNull(shape.unity()).donut());
                double x = Math.cos(shapeAngle) * shape.size().x, z = Math.sin(shapeAngle) * shape.size().z;
                Vec3 outward = position.subtract(x * donut.ringRadius(), 0, z * donut.ringRadius());
                yield outward.lengthSqr() > 1e-12 ? outward.normalize() : new Vec3(x, 0, z).normalize();
            }
            case CONE -> {
                double slope = Math.tan(definition.shape().angle());
                if (slope == 0) yield new Vec3(0, 1, 0);
                double height = definition.shape().size().x / slope;
                Vec3 ray = position.add(0, height, 0);
                yield ray.lengthSqr() == 0 ? new Vec3(0, 1, 0) : ray.normalize();
            }
            default -> definition.startDirection().normalize();
        };
    }

    private double shapeAzimuth(@Nullable UnityShapeSettings settings, double born, double age, int batchIndex, int batchCount) {
        double sample = random.nextDouble();
        var arc = settings == null ? null : settings.arc();
        if (arc != null) {
            double turns = arc.radians() / (2 * Math.PI);
            double arcLength = Math.abs(turns);
            if (arcLength == 0) return 0;
            return turns * shapeTraversal(arc.traversal(), arcLength, born, age, batchIndex, batchCount, sample, arcLength < 1);
        }
        if (definition.shape().type() == EmitterDefinition.ShapeType.EDGE) {
            var edge = settings == null ? null : settings.edge();
            // Unity's radius speed traverses the authored half-extent per cycle.
            double radius = definition.shape().size().x;
            if (edge != null && radius > 0) sample = shapeTraversal(edge, radius, born, age, batchIndex, batchCount, sample, true);
        }
        return sample;
    }

    private Vec3 shape(double azimuthFraction) {
        var shape = definition.shape();
        var unity = shape.unity();
        double angle = azimuthFraction * (2 * Math.PI);
        double inner = unity == null ? 0 : 1 - unity.radiusThickness();
        double radius = shape.surface() ? 1 : ShapeDistribution.annulusRadius(inner, 1, random.nextDouble());
        return switch (shape.type()) {
            case POINT -> Vec3.ZERO;
            case BOX, BOX_EDGE -> {
                double x = between(-1, 1) * shape.size().x, y = between(-1, 1) * shape.size().y, z = between(-1, 1) * shape.size().z;
                if (unity != null) {
                    var thickness = unity.boxThickness();
                    if (shape.type() == EmitterDefinition.ShapeType.BOX_EDGE) {
                        // Unity Shuriken picks the free edge axis uniformly across X/Y/Z.
                        int axis = random.nextInt(3);
                        if (axis != 0) x = unityBoxPerimeter(shape.size().x, thickness.x, -1);
                        if (axis != 1) y = unityBoxPerimeter(shape.size().y, thickness.y, -1);
                        if (axis != 2) z = unityBoxPerimeter(shape.size().z, thickness.z, 1);
                    } else if (shape.surface()) {
                        // Unity Shuriken BoxShell picks the face axis uniformly across X/Y/Z.
                        int axis = random.nextInt(3);
                        if (axis == 0) x = unityBoxPerimeter(shape.size().x, thickness.x, -1);
                        else if (axis == 1) y = unityBoxPerimeter(shape.size().y, thickness.y, -1);
                        else z = unityBoxPerimeter(shape.size().z, thickness.z, 1);
                    }
                } else if (shape.type() == EmitterDefinition.ShapeType.BOX_EDGE) {
                    int axis = ShapeDistribution.boxEdgeAxis(shape.size().x, shape.size().y, shape.size().z, random.nextDouble());
                    if (axis != 0) x = boxPerimeter(shape.size().x, 0);
                    if (axis != 1) y = boxPerimeter(shape.size().y, 0);
                    if (axis != 2) z = boxPerimeter(shape.size().z, 0);
                } else if (shape.surface()) {
                    double yz = shape.size().y * shape.size().z, xz = shape.size().x * shape.size().z, xy = shape.size().x * shape.size().y;
                    double face = random.nextDouble() * (yz + xz + xy);
                    if (face < yz) x = boxPerimeter(shape.size().x, 0);
                    else if (face < yz + xz) y = boxPerimeter(shape.size().y, 0);
                    else z = boxPerimeter(shape.size().z, 0);
                }
                yield new Vec3(x, y, z);
            }
            case SPHERE, HEMISPHERE -> {
                double y = between(shape.type() == EmitterDefinition.ShapeType.HEMISPHERE ? 0 : -1, 1);
                double circle = Math.sqrt(1 - y * y);
                double r = shape.surface() ? 1 : ShapeDistribution.shellRadius(inner, 1, random.nextDouble());
                yield new Vec3(circle * Math.cos(angle) * shape.size().x, y * shape.size().y, circle * Math.sin(angle) * shape.size().z).scale(r);
            }
            case EDGE -> new Vec3((2 * azimuthFraction - 1) * shape.size().x, 0, 0);
            case CYLINDER -> new Vec3(radius * Math.cos(angle) * shape.size().x, between(-1, 1) * shape.size().y, radius * Math.sin(angle) * shape.size().z);
            case CIRCLE -> new Vec3(radius * Math.cos(angle) * shape.size().x, 0, radius * Math.sin(angle) * shape.size().z);
            case DONUT -> {
                var donut = Objects.requireNonNull(Objects.requireNonNull(unity).donut());
                double tube = donut.tubeRadius() * radius;
                double crossSection = random.nextDouble() * (2 * Math.PI);
                double ring = donut.ringRadius() + tube * Math.cos(crossSection);
                yield new Vec3(ring * Math.cos(angle) * shape.size().x, tube * Math.sin(crossSection) * shape.size().y,
                        ring * Math.sin(angle) * shape.size().z);
            }
            case MESH -> throw new IllegalStateException("Mesh birth must use its geometry sampler");
            case CONE -> {
                double slope = Math.tan(shape.angle()), bottom = shape.size().x, top = bottom + shape.size().y * slope;
                double fraction = random.nextDouble(), y;
                if (slope == 0) y = fraction * shape.size().y;
                else {
                    double r = shape.surface() ? ShapeDistribution.annulusRadius(bottom, top, fraction)
                            : ShapeDistribution.shellRadius(bottom, top, fraction);
                    y = (r - bottom) / slope;
                }
                double r = radius * (bottom + y * slope);
                yield new Vec3(r * Math.cos(angle), y, r * Math.sin(angle));
            }
        };
    }

    private double boxPerimeter(double extent, double thickness) {
        double sign = random.nextBoolean() ? 1 : -1;
        return sign * (thickness == 0 ? extent : extent * (1 - random.nextDouble() * thickness));
    }

    /**
     * Unity Shuriken BoxShell/BoxEdge applies boxThickness asymmetrically in Unity local space:
     * half the samples clamp to the exact outer plane (-1 in Unity local, outerSign in LumenFX local
     * where Z is negated) and half span [1 - 2*thickness, 1] on the opposite side.
     */
    private double unityBoxPerimeter(double extent, double thickness, double outerSign) {
        if (thickness == 0) return (random.nextBoolean() ? 1 : -1) * extent;
        return random.nextBoolean()
                ? outerSign * extent
                : -outerSign * extent * (1 - 2 * thickness * random.nextDouble());
    }

    private double shapeTraversal(UnityShapeSettings.Traversal traversal, double unitsPerCycle, double born, double age,
                                  int batchIndex, int batchCount, double sample, boolean includeEndpoint) {
        double phase = 0;
        if (traversal.mode().usesArcSpeed()) {
            double duration = definition.duration();
            double cycles = Math.max(0, Math.floor((born - started - startDelay) / duration));
            phase = duration * (cycles * traversal.speed().integral(0, 1, emitterRandom)
                    + traversal.speed().integral(0, age, emitterRandom)) / unitsPerCycle;
        }
        return traversal.sample((float) phase, batchIndex, batchCount, sample, includeEndpoint);
    }

    private static Vec3 rotateShape(Vec3 value, Quaterniond rotation) {
        var result = rotation.transform(value.x, value.y, value.z, new Vector3d());
        return new Vec3(result.x, result.y, result.z);
    }

    private static Vec3 randomUnit(SplittableRandom random) {
        double y = random.nextDouble(-1, 1);
        double angle = random.nextDouble() * Math.PI * 2;
        double circle = Math.sqrt(1 - y * y);
        return new Vec3(circle * Math.cos(angle), y, circle * Math.sin(angle));
    }

    private Vec3 emitterPosition(Pose pose) {
        return emissionTransform == null ? pose.position() : pose.transformPosition(emissionTransform.position());
    }

    public static double ribbonRandom(double random, double born) {
        long z = Double.doubleToRawLongBits(random) * 0x9e3779b97f4a7c15L
                ^ Double.doubleToRawLongBits(born) * 0xbf58476d1ce4e5b9L;
        return (RandomSupport.mixStafford13(z) >>> 11) * 0x1.0p-53;
    }

    private double between(double minimum, double maximum) { return minimum + random.nextDouble() * (maximum - minimum); }
    public boolean finished(double time, boolean stopping) {
        double now = simulationTime(time);
        return particles.isEmpty() && (trail == null || trail.isEmpty())
                && (stopping || time >= hostEmissionEnd
                    || !loops() && now - started >= startDelay + definition.duration());
    }
}
