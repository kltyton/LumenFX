package com.kltyton.lumenfx.effect.simulation.unity;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.effect.simulation.ParticleState;
import com.kltyton.lumenfx.effect.EmitterDefinition;
import com.kltyton.lumenfx.effect.curve.ColorFunction.Color;
import com.kltyton.lumenfx.effect.unity.UnityParticleTrail;
import com.lowdragmc.photon.client.gameobject.particle.TrailSampling;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SplittableRandom;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Owns trail vertices for one renderer of a Shuriken particle simulation. */
public final class UnityTrailHistory {
    public record Vertex(Vec3 position, double time, long strip, double width, Color color) { }

    private record Point(Vec3 position, double time, double expiry) { }

    private static final class Trail {
        final long strip;
        final double random;
        final List<Point> points = new ArrayList<>();
        @Nullable Point head;
        double firstStepEnd;
        double clearAfter = Double.POSITIVE_INFINITY;
        boolean detached;

        Trail(long strip, double random) {
            this.strip = strip;
            this.random = random;
        }
    }

    private final UnityParticleTrail settings;
    private final LinkedHashMap<ParticleState, Trail> trails = new LinkedHashMap<>();
    private final List<Vertex> vertices = new ArrayList<>();
    private final List<Vertex> frame = java.util.Collections.unmodifiableList(vertices);
    private final List<Vec3> worldPoints = new ArrayList<>();
    private final double[] coordinates = new double[3];
    private final SplittableRandom random;
    private long nextStrip;

    public UnityTrailHistory(UnityParticleTrail settings, long seed) {
        this.settings = settings;
        this.random = new SplittableRandom(seed);
    }

    public void clear() {
        trails.clear();
        vertices.clear();
    }

    public boolean isEmpty() { return trails.isEmpty(); }

    public void birth(ParticleState particle, double stepEnd, EffectHandle.Pose pose, EmitterDefinition source,
                      ImprovedNoise noise) {
        if (random.nextDouble() >= settings.ratio()) return;
        var trail = new Trail(nextStrip++, random.nextDouble());
        trails.put(particle, trail);
        trail.firstStepEnd = stepEnd;
        double born = particle.born();
        var origin = sample(trail, particle, born, stepEnd, pose, source, noise);
        if (origin != null) trail.points.add(origin);
    }

    public void update(ParticleState particle, double time, EffectHandle.Pose pose, EmitterDefinition source,
                       ImprovedNoise noise) {
        var trail = trails.get(particle);
        if (trail == null || trail.detached) return;
        if (Double.isNaN(trail.firstStepEnd)) {
            if (time <= particle.born()) return;
            // A zero-delta start can schedule the birth before the first actual particle step.
            trail.firstStepEnd = time;
            if (!trail.points.isEmpty()) {
                var origin = trail.points.getFirst();
                Vec3 position = source.localSpace() && settings.worldSpace()
                        ? pose.transformPosition(origin.position()) : origin.position();
                trail.points.set(0, new Point(position, time, origin.expiry() + (time - origin.time())));
            }
        }
        trail.head = sample(trail, particle, time, time, pose, source, noise);
        if (trail.head == null || time <= trail.firstStepEnd) return;
        if (trail.points.isEmpty() || beyondMinimumDistance(trail.points.getLast(), trail.head)) {
            // The live head is also emitted by the source even if this step saved a coincident knot.
            // Its ordinal is part of DistributePerSegment and RepeatPerSegment UVs.
            trail.points.add(trail.head);
        }
    }

    public void end(ParticleState particle, double sampleTime, boolean onSampleBoundary) {
        var trail = trails.get(particle);
        if (trail == null || trail.detached) return;
        if (settings.dieWithParticles()) {
            if (onSampleBoundary) trail.clearAfter = sampleTime;
            else trails.remove(particle);
            return;
        }
        var newestKnot = trail.points.isEmpty() ? null : trail.points.getLast();
        if (onSampleBoundary && newestKnot == trail.head) {
            trail.head = null;
        } else if (!onSampleBoundary && settings.worldSpace()) {
            // Once the particle expires, a world-space strip draws from its last saved world point.
            trail.head = newestKnot;
        }
        trail.detached = true;
    }

    private @Nullable Point sample(Trail trail, ParticleState particle, double time, double stamp,
                                   EffectHandle.Pose pose, EmitterDefinition source, ImprovedNoise noise) {
        particle.samplePosition(time, source, noise, coordinates, 0);
        if (!Double.isNaN(stamp) && source.localSpace() && settings.worldSpace()) pose.transformPosition(coordinates, 0);
        var position = new Vec3(coordinates[0], coordinates[1], coordinates[2]);
        double age = particle.normalizedAge(time);
        double lifetime = particle.lifetime() * settings.lifetime().sample(age, trail.random);
        if (settings.sizeAffectsLifetime()) {
            double speed = particle.hasSpeedAppearance(source) ? particle.sampleSpeed(time, source) : 0;
            lifetime *= particleSize(particle, source, age, speed, pose);
        }
        double pointTime = Double.isNaN(stamp) ? time : stamp;
        return lifetime > 0 && Double.isFinite(lifetime) ? new Point(position, pointTime, pointTime + lifetime) : null;
    }

    private boolean beyondMinimumDistance(Point previous, Point current) {
        var from = previous.position();
        var to = current.position();
        float dx = (float)(to.x - from.x);
        float dy = (float)(to.y - from.y);
        float dz = (float)(to.z - from.z);
        float threshold = (float)settings.minimumVertexDistance();
        return dx * dx + dy * dy + dz * dz > threshold * threshold;
    }

    public void advance(double time) {
        var entries = trails.values().iterator();
        while (entries.hasNext()) {
            var trail = entries.next();
            if (time > trail.clearAfter) {
                entries.remove();
                continue;
            }
            if (trail.head != null && !TrailSampling.retained(trail.head.expiry() - time)) {
                trail.head = null;
            }
            // Keep the predecessor of the live tail until the last visible part of its segment expires.
            int firstLive = 0;
            while (firstLive < trail.points.size()
                    && !TrailSampling.retained(trail.points.get(firstLive).expiry() - time)) {
                firstLive++;
            }
            int livePoints = trail.points.size() - firstLive + (trail.head == null ? 0 : 1);
            // The frozen head still occupies a native vertex slot, even when it coincides with a retained knot.
            int keepPredecessor = firstLive > 0 && livePoints >= (trail.detached ? 2 : 1) ? 1 : 0;
            trail.points.subList(0, firstLive - keepPredecessor).clear();
            trail.points.subList(keepPredecessor, trail.points.size())
                    .removeIf(point -> !TrailSampling.retained(point.expiry() - time));
            if (trail.detached && trail.points.isEmpty() && trail.head == null) {
                entries.remove();
            }
        }
    }

    public List<Vertex> pack(double time, EffectHandle.Pose pose, EmitterDefinition source) {
        vertices.clear();
        for (var entry : trails.entrySet()) {
            var trail = entry.getValue();
            int count = trail.points.size() + (trail.head == null ? 0 : 1);
            if (count < 2) continue;
            append(vertices, trail, entry.getKey(), source, time, pose, count);
        }
        return frame;
    }

    private void append(List<Vertex> output, Trail trail, ParticleState particle, EmitterDefinition source, double time,
                        EffectHandle.Pose pose, int count) {
        double age = particle.normalizedAge(time);
        double speed = particle.hasSpeedAppearance(source) ? particle.sampleSpeed(time, source) : 0;
        double width = settings.sizeAffectsWidth() ? particleSize(particle, source, age, speed, pose) : 1;
        var color = settings.colorOverLifetime().sample(age, trail.random);
        if (settings.inheritColor()) {
            var start = particle.startColor();
            var overLife = source.colorOverLife().sample(age, particle.random());
            color = multiply(color, multiply(start, overLife));
            var speedColor = particle.speedColor(speed, source);
            if (speedColor != null) color = multiply(color, speedColor);
        }
        boolean local = source.localSpace() && !settings.worldSpace();
        double total = 0;
        worldPoints.clear();
        var tail = liveTail(trail, time);
        var previous = worldPosition(tail.position(), local, pose);
        worldPoints.add(previous);
        for (int index = 1; index < count; index++) {
            var position = worldPosition(point(trail, index).position(), local, pose);
            worldPoints.add(position);
            total += position.distanceTo(previous);
            previous = position;
        }
        double covered = 0;
        for (int index = 0; index < count; index++) {
            var point = index == 0 ? tail : point(trail, index);
            var position = worldPoints.get(index);
            if (index != 0) covered += position.distanceTo(previous);
            previous = position;
            double along = total > 0 ? Math.clamp(1 - covered / total, 0, 1) : 0;
            double size = Math.max(0, width * settings.widthOverTrail().sample(along, trail.random));
            output.add(new Vertex(position, point.time(), trail.strip, size,
                    multiply(color, settings.colorOverTrail().sample(along, trail.random))));
        }
    }

    private Vec3 worldPosition(Vec3 point, boolean local, EffectHandle.Pose pose) {
        if (!local) return point;
        coordinates[0] = point.x;
        coordinates[1] = point.y;
        coordinates[2] = point.z;
        pose.transformPosition(coordinates, 0);
        return new Vec3(coordinates[0], coordinates[1], coordinates[2]);
    }

    private static Point point(Trail trail, int index) {
        return index < trail.points.size() ? trail.points.get(index) : java.util.Objects.requireNonNull(trail.head);
    }

    private static Point liveTail(Trail trail, double time) {
        var tail = point(trail, 0);
        if (tail.expiry() >= time) return tail;
        var next = point(trail, 1);
        double fraction = (time - tail.expiry()) / (next.expiry() - tail.expiry());
        return new Point(tail.position().lerp(next.position(), fraction),
                Math.fma(next.time() - tail.time(), fraction, tail.time()), time);
    }

    private static double particleSize(ParticleState particle, EmitterDefinition source, double age, double speed, EffectHandle.Pose pose) {
        double width = particle.width() * particle.sizeScale(0, age, speed, source);
        double height = particle.height() * particle.sizeScale(1, age, speed, source);
        if (source.renderer() == EmitterDefinition.Renderer.MESH && particle.meshBirth() != null) {
            double depth = particle.meshBirth().depth() * particle.sizeScale(2, age, speed, source);
            double volume = Math.abs(width * height * depth);
            if (source.localSpace()) volume *= Math.abs((double)pose.scaleX() * pose.scaleY() * pose.scaleZ());
            return Math.cbrt(volume);
        }
        double scale = 1;
        double sx = Math.abs(pose.scaleX()), sy = Math.abs(pose.scaleY()), sz = Math.abs(pose.scaleZ());
        if (source.localSpace()) {
            scale = Math.cbrt(sx * sy * sz);
        } else if (source.scaleSizeWithOwner() && (sx != 1 || sy != 1 || sz != 1) && sx > 0 && sy > 0) {
            width /= sx;
            height /= sy;
            scale = Math.cbrt(sx * sy * sz);
        }
        return Math.sqrt(Math.abs(width * height)) * scale;
    }

    private static Color multiply(Color left, Color right) {
        return new Color(left.red() * right.red(), left.green() * right.green(),
                left.blue() * right.blue(), left.alpha() * right.alpha());
    }
}
