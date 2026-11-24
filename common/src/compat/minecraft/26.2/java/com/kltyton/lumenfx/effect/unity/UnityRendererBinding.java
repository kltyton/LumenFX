package com.kltyton.lumenfx.effect.unity;

import com.kltyton.lumenfx.effect.curve.ColorFunction.Color;
import com.kltyton.lumenfx.effect.curve.ScalarFunction;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** One renderer of an exported Shuriken system. A secondary renderer reads its source's live particles. */
public record UnityRendererBinding(@Nullable String source, Color tint, @Nullable UnityParticleTrail particleTrail,
                                   @Nullable Integer ribbonCount, @Nullable UnityTrailTexture trailTexture,
                                   @Nullable UnityInheritVelocity inheritVelocity, @Nullable UnityVelocityLimit velocityLimit,
                                   @Nullable UnitySpaceMotion spaceMotion, @Nullable UnityBillboard billboard,
                                   @Nullable UnityMeshMotion meshMotion, @Nullable UnityCollision collision,
                                   AtlasRowSelection atlasRowSelection, @Nullable Double atlasFps,
                                   @Nullable AtlasSpeed atlasSpeed, List<SpriteFrame> spriteFrames, boolean prewarm,
                                   @Nullable UnitySpeedModules speedModules, @Nullable UnityLifetimeByEmitterSpeed lifetimeByEmitterSpeed,
                                   List<ScalarFunction> sizeOverLifetimeAxes, List<UnitySubEmitter> subEmitters,
                                   @Nullable UnityTrigger trigger, @Nullable UnityParticleLights lights,
                                   @Nullable UnityCustomData customData, @Nullable UnityNoise noise,
                                   @Nullable UnityExternalForces externalForces, @Nullable UnityEmitterTransform transform,
                                   double simulationSpeed, double flipRotation, @Nullable Double startDelayMaximum,
                                   @Nullable Long randomSeed, boolean pauseUntilReplaced, @Nullable LifetimeLoop lifetimeLoop,
                                   @Nullable UnityEmitterTransform emissionTransform, int uvChannelMask,
                                   @Nullable StartDepth startDepth, @Nullable Sorting sorting,
                                   @Nullable Double maximumParticleDeltaTime) {
    public enum AtlasRowSelection { DEFAULT, RANDOM, MESH_INDEX }

    public record StartDepth(ScalarFunction size, boolean forwardToUp) { }

    public record SortingGroup(String id, int layer, int order, Vec3 position, int hierarchyOrder) {
        public SortingGroup {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Unity sorting group requires a non-blank id");
            if (position == null || !Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z))
                throw new IllegalArgumentException("Unity sorting group position must be finite");
        }
    }

    public record Sorting(int layer, int order, int queue, double distanceBias, List<SortingGroup> groups, int hierarchyOrder) {
        public Sorting(int layer, int order, int queue, double distanceBias) {
            this(layer, order, queue, distanceBias, List.of(), 0);
        }

        public Sorting {
            groups = List.copyOf(groups);
            if (!Double.isFinite(distanceBias)) throw new IllegalArgumentException("Unity sorting bias must be finite");
        }

        public boolean grouped() { return !groups.isEmpty(); }

        public @Nullable SortingGroup rootGroup() { return groups.isEmpty() ? null : groups.getFirst(); }

        public double applyDistanceBias(double squaredDistance) {
            if (distanceBias == 0 || !groups.isEmpty()) return squaredDistance;
            double distance = Math.copySign(Math.sqrt(Math.abs(squaredDistance)), squaredDistance) + distanceBias;
            return Math.copySign(distance * distance, distance);
        }
    }

    public record LifetimeLoop(double start, double end) {
        public LifetimeLoop {
            if (!(0 <= start && start <= end && end <= 1))
                throw new IllegalArgumentException("Invalid Unity particle lifetime loop range");
        }

        public double age(double age) {
            if (age <= end + 1e-6) return Math.min(age, end);
            double span = end - start;
            if (span == 0) return start;
            double phase = (age - end) % span;
            return phase <= 1e-6 ? end : start + phase;
        }

        public double integral(ScalarFunction curve, double age, double random) {
            if (age <= end) return curve.integral(0, age, random);
            double span = end - start;
            double head = curve.integral(0, end, random);
            double remainder = age - end;
            if (span == 0) return head + remainder * curve.sample(start, random);
            double phase = remainder % span;
            return head + Math.floor(remainder / span) * curve.integral(start, end, random)
                    + curve.integral(start, start + phase, random);
        }
    }

    public record AtlasSpeed(UnitySpeedRange range, int cycles) {
        public AtlasSpeed {
            if (range.maximum() <= range.minimum() || cycles < 1)
                throw new IllegalArgumentException("Invalid Unity atlas speed range or cycle count");
        }

        public double progress(double speed) { return range.progress(speed); }
    }

    public record SpriteFrame(float u0, float v0, float u1, float v1) {
        public SpriteFrame {
            if (!(u0 >= 0 && v0 >= 0 && u1 <= 1 && v1 <= 1 && u1 > u0 && v1 > v0))
                throw new IllegalArgumentException("Invalid Unity sprite texture rectangle");
        }
    }

    public UnityRendererBinding(@Nullable String source, Color tint, @Nullable UnityParticleTrail particleTrail,
                                @Nullable Integer ribbonCount, @Nullable UnityTrailTexture trailTexture,
                                @Nullable UnityInheritVelocity inheritVelocity, @Nullable UnityVelocityLimit velocityLimit,
                                @Nullable UnitySpaceMotion spaceMotion, @Nullable UnityBillboard billboard,
                                @Nullable UnityMeshMotion meshMotion, @Nullable UnityCollision collision,
                                AtlasRowSelection atlasRowSelection, @Nullable Double atlasFps,
                                @Nullable AtlasSpeed atlasSpeed, List<SpriteFrame> spriteFrames, boolean prewarm,
                                @Nullable UnitySpeedModules speedModules, @Nullable UnityLifetimeByEmitterSpeed lifetimeByEmitterSpeed,
                                List<ScalarFunction> sizeOverLifetimeAxes, List<UnitySubEmitter> subEmitters,
                                @Nullable UnityTrigger trigger, @Nullable UnityParticleLights lights,
                                @Nullable UnityCustomData customData, @Nullable UnityNoise noise,
                                @Nullable UnityExternalForces externalForces, @Nullable UnityEmitterTransform transform,
                                double simulationSpeed, double flipRotation, @Nullable Double startDelayMaximum,
                                @Nullable Long randomSeed, boolean pauseUntilReplaced, @Nullable LifetimeLoop lifetimeLoop,
                                @Nullable UnityEmitterTransform emissionTransform) {
        this(source, tint, particleTrail, ribbonCount, trailTexture, inheritVelocity, velocityLimit, spaceMotion,
                billboard, meshMotion, collision, atlasRowSelection, atlasFps, atlasSpeed, spriteFrames, prewarm,
                speedModules, lifetimeByEmitterSpeed, sizeOverLifetimeAxes, subEmitters, trigger, lights, customData,
                noise, externalForces, transform, simulationSpeed, flipRotation, startDelayMaximum, randomSeed,
                pauseUntilReplaced, lifetimeLoop, emissionTransform, 1, null, null, null);
    }

    public UnityRendererBinding(@Nullable String source, Color tint, @Nullable UnityParticleTrail particleTrail) {
        this(source, tint, particleTrail, null, null, null, null, null, null, null, null, AtlasRowSelection.DEFAULT,
                null, null, List.of(), false, null, null, List.of(), List.of(), null, null, null, null, null, null, 1, 0,
                null, null, false, null, null, 1, null, null, null);
    }

    public UnityRendererBinding {
        spriteFrames = List.copyOf(spriteFrames);
        sizeOverLifetimeAxes = List.copyOf(sizeOverLifetimeAxes);
        subEmitters = List.copyOf(subEmitters);
        if (!sizeOverLifetimeAxes.isEmpty() && sizeOverLifetimeAxes.size() != 3)
            throw new IllegalArgumentException("Expected three Unity size-over-lifetime axes");
        if (source != null && source.isBlank() || !Float.isFinite(tint.red()) || !Float.isFinite(tint.green())
                || !Float.isFinite(tint.blue()) || !Float.isFinite(tint.alpha()))
            throw new IllegalArgumentException("Invalid Unity renderer binding");
        if (ribbonCount != null && (ribbonCount <= 0
                || particleTrail != null && particleTrail.mode() != UnityParticleTrail.Mode.RIBBON))
            throw new IllegalArgumentException("Invalid Unity ribbon count");
        if (atlasFps != null && (!Double.isFinite(atlasFps) || atlasFps < 0 || atlasFps > Float.MAX_VALUE || atlasSpeed != null))
            throw new IllegalArgumentException("Invalid Unity atlas FPS");
        if (!Double.isFinite(simulationSpeed) || simulationSpeed < 0 || simulationSpeed > Float.MAX_VALUE
                || source != null && simulationSpeed != 1)
            throw new IllegalArgumentException("Invalid Unity simulation speed");
        if (maximumParticleDeltaTime != null && (source != null || !Float.isFinite(maximumParticleDeltaTime.floatValue())
                || maximumParticleDeltaTime.floatValue() <= 0))
            throw new IllegalArgumentException("Invalid Unity maximum particle delta time");
        if (!(flipRotation >= 0 && flipRotation <= 1) || source != null && flipRotation != 0)
            throw new IllegalArgumentException("Invalid Unity flip rotation probability");
        if (startDelayMaximum != null && (!Double.isFinite(startDelayMaximum) || startDelayMaximum < 0
                || prewarm || source != null))
            throw new IllegalArgumentException("Invalid Unity start delay maximum");
        if (randomSeed != null && (randomSeed < 0 || randomSeed > 0xffff_ffffL || source != null))
            throw new IllegalArgumentException("Invalid Unity random seed");
        if ((pauseUntilReplaced || lifetimeLoop != null) && source != null)
            throw new IllegalArgumentException("A secondary renderer cannot own a Unity ring buffer");
        if (pauseUntilReplaced && lifetimeLoop != null)
            throw new IllegalArgumentException("A Unity ring buffer cannot pause and loop at the same time");
        if (lifetimeLoop != null && lifetimeLoop.start() == lifetimeLoop.end()) {
            // A zero-width range retains the final lifetime value and uses replacement without a release tail.
            pauseUntilReplaced = true;
            lifetimeLoop = null;
        }
        if (emissionTransform != null && source != null)
            throw new IllegalArgumentException("A secondary renderer cannot own a Unity emission transform");
        if (startDepth != null && (source != null || meshMotion != null))
            throw new IllegalArgumentException("Unity start depth applies to non-mesh particle simulations");
    }

    public boolean ringBuffered() { return pauseUntilReplaced || lifetimeLoop != null; }
}
