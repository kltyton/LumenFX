package com.kltyton.lumenfx.effect.simulation;

import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.api.client.parameter.UnityCollisionLayers;
import com.kltyton.lumenfx.effect.simulation.unity.UnitySubEmitterEvent;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry;
import com.kltyton.lumenfx.effect.unity.UnityEmitterTransform;
import com.kltyton.lumenfx.effect.unity.UnityExternalForces.FieldState;
import com.kltyton.lumenfx.effect.unity.UnityTrigger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/** Owns one authored emitter's shared capacity, parent events, and linked child timelines. */
public final class UnitySubEmitterGroup<S extends ParticleEmitterSimulation> {
    public record Frame(double time, Pose root, boolean emitting, boolean stopping, boolean attachmentSampled,
                 @Nullable Vec3 physicsVelocity, @Nullable Level level, UnityCollisionLayers layers,
                 Map<Identifier, StaticMeshGeometry> emissionGeometry, Map<String, List<UnityTrigger.Collider>> triggerColliders,
                 Map<String, Integer> manualRequests, Map<String, FieldState> forceFields) {
        public Frame {
            forceFields = Map.copyOf(forceFields);
        }

        public Frame(double time, Pose root, boolean emitting, boolean stopping, boolean attachmentSampled,
                     @Nullable Vec3 physicsVelocity, @Nullable Level level, UnityCollisionLayers layers,
                     Map<Identifier, StaticMeshGeometry> emissionGeometry, Map<String, List<UnityTrigger.Collider>> triggerColliders,
                     Map<String, Integer> manualRequests) {
            this(time, root, emitting, stopping, attachmentSampled, physicsVelocity, level, layers, emissionGeometry,
                    triggerColliders, manualRequests, Map.of());
        }
    }

    public interface ChildFactory<S extends ParticleEmitterSimulation> {
        S create(long seed, UnitySubEmitterEvent start, UnityEmitterCapacity capacity);
    }

    public interface StepSink<S extends ParticleEmitterSimulation> {
        void publish(S simulation, ParticleEmitterSimulation.StepPlan step);
    }

    private final S emitter;
    private final @Nullable UnityEmitterTransform authoredTransform;
    private final SplittableRandom childSeeds;
    private final ChildFactory<S> childFactory;
    private final List<UnitySubEmitterGroup<?>> parents = new ArrayList<>();
    private final List<EventSource> eventSources = new ArrayList<>();
    private final List<Child<S>> children = new ArrayList<>();
    private final double[] childPoseScratch = new double[3];
    private @Nullable UnityEmitterCapacity childCapacity;
    private int dependencyGroup = -1;

    public UnitySubEmitterGroup(S emitter, @Nullable UnityEmitterTransform authoredTransform, long childSeed, ChildFactory<S> childFactory) {
        this.emitter = emitter;
        this.authoredTransform = authoredTransform;
        this.childSeeds = new SplittableRandom(childSeed);
        this.childFactory = childFactory;
    }

    public void addParent(UnitySubEmitterGroup<?> source) {
        if (!parents.contains(source)) parents.add(source);
    }

    public int group() {
        if (dependencyGroup < 0) {
            dependencyGroup = 0;
            for (var parent : parents) dependencyGroup = Math.max(dependencyGroup, parent.group() + 1);
        }
        return dependencyGroup;
    }

    public boolean usesWorldCollision() { return emitter.usesWorldCollision(); }

    public boolean finished(double time, boolean stopping) {
        return parents.isEmpty() ? emitter.finished(time, stopping) : children.isEmpty();
    }

    public void simulate(Frame frame, StepSink<S> sink) {
        eventSources.clear();
        Pose root = frame.root();
        Pose authored = authoredTransform == null ? null : transform(root, authoredTransform);
        Pose geometryPose = authored == null ? null : root;
        if (parents.isEmpty()) {
            emitter.drainRingOnStop(frame.stopping());
            var step = emitter.simulate(frame.time(), authored == null ? root : authored, frame.emitting(),
                    frame.attachmentSampled(), frame.physicsVelocity(), frame.level(), geometryPose, frame.layers(),
                    frame.emissionGeometry(), frame.triggerColliders(), frame.forceFields());
            publish(frame, sink, emitter, step);
            return;
        }

        if (childCapacity == null) childCapacity = new UnityEmitterCapacity(emitter.definition.maximumParticles());
        for (var parent : parents) for (var source : parent.eventSources) {
            var link = source.event();
            if (!link.link().target().equals(emitter.definition.name())) continue;
            var start = authored == null ? link : link.withPose(authored.withPosition(link.pose().position()));
            var child = childFactory.create(childSeeds.nextLong(), start, childCapacity);
            children.add(new Child<>(child, source));
        }
        record GlobalChildBirth(int childIndex, ParticleEmitterSimulation emitter, ParticleEmitterSimulation.StepPlan plan,
                                ParticleEmitterSimulation.ScheduledBirth birth, double hostBorn, double childOrigin) { }
        List<ParticleEmitterSimulation.StepPlan> childPlans = new ArrayList<>(children.size());
        List<GlobalChildBirth> globalBirths = new ArrayList<>();
        for (int childIdx = 0; childIdx < children.size(); childIdx++) {
            var child = children.get(childIdx);
            var source = child.source();
            var event = source.event();
            var parentParticle = event.particle();
            Pose pose = event.pose();
            Vec3 velocity = event.velocity();
            if (parentParticle != null) {
                double end = source.emitter().particleHostDeathTime(parentParticle);
                child.emitter().endEmissionAt(end);
                child.emitter().parentEmissionSpeed(source.emitter().simulationSpeed());
                if (frame.time() <= end || child.sampledThrough() < end) {
                    double time = Math.min(frame.time(), end);
                    var sample = source.emitter().particlePoseAtHostTime(parentParticle, time, childPoseScratch);
                    child.sample(time, sample.pose(), sample.velocity());
                }
                pose = child.pose();
                velocity = child.velocity();
            }
            if (authored != null) pose = authored.withPosition(pose.position());
            child.emitter().drainRingOnStop(frame.stopping());
            // Linked emitters follow the parent particle, while exported fields and colliders use the effect root.
            var plan = child.emitter().prepareStep(frame.time(), pose, !frame.stopping(),
                    true, velocity, frame.level(), root, frame.layers(),
                    frame.emissionGeometry(), frame.triggerColliders(), frame.forceFields());
            childPlans.add(plan);
            for (var birth : child.emitter().scheduledBirths()) {
                globalBirths.add(new GlobalChildBirth(childIdx, child.emitter(), plan, birth,
                        child.emitter().hostEventTime(birth.time()), child.emitter().clockOrigin()));
            }
        }
        if (!globalBirths.isEmpty()) {
            globalBirths.sort(Comparator.comparingDouble(GlobalChildBirth::hostBorn)
                    .thenComparingDouble(GlobalChildBirth::childOrigin)
                    .thenComparingInt(GlobalChildBirth::childIndex)
                    .thenComparingInt(entry -> entry.birth().order())
                    .thenComparingInt(entry -> entry.birth().index()));
            double lastAdvancedHost = Double.NEGATIVE_INFINITY;
            for (var entry : globalBirths) {
                if (entry.hostBorn() > lastAdvancedHost) {
                    for (int idx = 0; idx < children.size(); idx++) {
                        children.get(idx).emitter().advanceActiveToHostTime(entry.hostBorn(), childPlans.get(idx));
                    }
                    lastAdvancedHost = entry.hostBorn();
                }
                entry.emitter().spawnPreparedBirth(entry.birth(), entry.plan());
            }
        }
        for (int childIdx = 0; childIdx < children.size(); childIdx++)
            children.get(childIdx).emitter().finishSimulationStep(childPlans.get(childIdx));
        int childIdx = 0;
        for (var iterator = children.iterator(); iterator.hasNext(); childIdx++) {
            var child = iterator.next();
            child.emitter().syncRingSnapshot();
            child.emitter().flushPendingRingEvents();
            publish(frame, sink, child.emitter(), childPlans.get(childIdx));
            if (child.emitter().finished(frame.time(), frame.stopping())) iterator.remove();
        }
    }

    private void publish(Frame frame, StepSink<S> sink, S simulation, ParticleEmitterSimulation.StepPlan step) {
        simulation.clearFrozenParticlesOnStop(frame.stopping());
        simulation.triggerManual(frame.manualRequests().getOrDefault(simulation.definition.name(), 0));
        sink.publish(simulation, step);
        for (var event : simulation.subEmitterEvents()) eventSources.add(new EventSource(simulation, event));
    }

    private static Pose transform(Pose root, UnityEmitterTransform transform) {
        var rotation = new Quaternionf(root.x(), root.y(), root.z(), root.w())
                .mul(new Quaternionf(transform.x(), transform.y(), transform.z(), transform.w()));
        if (transform.shapeOnlyScale()) {
            return new Pose(root.transformPosition(transform.position()), rotation.x, rotation.y, rotation.z, rotation.w,
                    root.scaleX(), root.scaleY(), root.scaleZ());
        }
        var scale = transform.scale();
        return new Pose(root.transformPosition(transform.position()), rotation.x, rotation.y, rotation.z, rotation.w,
                root.scaleX() * (float)scale.x, root.scaleY() * (float)scale.y, root.scaleZ() * (float)scale.z);
    }

    private record EventSource(ParticleEmitterSimulation emitter, UnitySubEmitterEvent event) { }
    private static final class Child<S extends ParticleEmitterSimulation> {
        private final S emitter;
        private final EventSource source;
        private double sampledThrough;
        private Pose pose;
        private Vec3 velocity;

        private Child(S emitter, EventSource source) {
            this.emitter = emitter;
            this.source = source;
            sample(source.event().time(), source.event().pose(), source.event().velocity());
        }

        private void sample(double time, Pose pose, Vec3 velocity) {
            sampledThrough = time;
            this.pose = pose;
            this.velocity = velocity;
        }

        private S emitter() { return emitter; }
        private EventSource source() { return source; }
        private double sampledThrough() { return sampledThrough; }
        private Pose pose() { return pose; }
        private Vec3 velocity() { return velocity; }
    }
}
