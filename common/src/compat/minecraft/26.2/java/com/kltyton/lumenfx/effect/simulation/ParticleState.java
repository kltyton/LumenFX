package com.kltyton.lumenfx.effect.simulation;

import com.kltyton.lumenfx.effect.EmitterDefinition;
import com.kltyton.lumenfx.effect.curve.ColorFunction;
import com.kltyton.lumenfx.effect.curve.ScalarFunction;
import com.kltyton.lumenfx.effect.unity.UnityRendererBinding.LifetimeLoop;
import com.kltyton.lumenfx.effect.unity.UnitySpaceTransform;
import com.kltyton.lumenfx.effect.unity.UnitySpeedModules;
import com.lowdragmc.photon.client.gameobject.particle.ParticleLifetime;
import java.util.Objects;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/**
 * Worker-owned mutable particle. Birth position/velocity/color stay immutable; only the
 * integrated motion numbers change. Snapshots expose this object only for the owning frame.
 */
public final class ParticleState {
    long unityTriggerMask;
    @Nullable Vec3 unityTriggerPrevPos;
    int unityLightId = -1;
    long unitySceneLightIdentity;
    @Nullable Quaterniond unityLightBirthRotation;

    public int unityLightId() { return unityLightId; }
    public long unitySceneLightIdentity() { return unitySceneLightIdentity; }
    public @Nullable Quaterniondc unityLightBirthRotation() { return unityLightBirthRotation; }
    private int unityParticleId;
    private int unityCollisionSeq;
    private static final double MOTION_STEP = 1.0 / 60;
    public record MeshBirth(double depth, double rotationX, double rotationY, int meshSlot, int flipMask) { }
    public record Motion(double time, Vec3 position, Vec3 velocity) { }

    private final Vec3 position;
    private final Vec3 velocity;
    private final double born;
    private final double lifetime;
    private final double mass;
    private final double dragRandom;
    private final double colorRedRandom, colorGreenRandom, colorBlueRandom, colorAlphaRandom;
    private final double width;
    private final double height;
    private final double rotation;
    private final double random;
    private final long ribbon;
    private final ColorFunction.Color startColor;
    private double motionTime;
    private double motionX;
    private double motionY;
    private double motionZ;
    private double motionVelocityX;
    private double motionVelocityY;
    private double motionVelocityZ;
    private double resolvedVelocityX, resolvedVelocityY, resolvedVelocityZ;
    private double integratedRotationX, integratedRotationY, integratedRotationZ;
    private boolean reversedSpin;
    private boolean billboardFlipX, billboardFlipY;
    private double noiseSizeScale = 1;
    private double collisionAgeOffset;
    private double deathTime;
    private boolean retainedInRing;
    private long ringOrder = -1;
    private double ringEndTime = Double.POSITIVE_INFINITY;
    private @Nullable LifetimeLoop lifetimeLoop;
    private double loopReleaseAge = Double.POSITIVE_INFINITY;
    private MotionProfile cachedProfile;
    private EmitterDefinition cachedDefinition;
    private @Nullable Quaterniond birthOrientation;
    private @Nullable Vec3 unityBirthDirection;
    private @Nullable MeshBirth meshBirth;
    private int atlasRow = -1;

    public ParticleState(Vec3 position, Vec3 velocity, double born, double lifetime, double width, double height,
                         double rotation, double random, long ribbon, ColorFunction.Color startColor) {
        this(position, velocity, born, lifetime, width, height, rotation, random, ribbon, startColor,
                new Motion(born, position, velocity));
    }

    public ParticleState(Vec3 position, Vec3 velocity, double born, double lifetime, double width, double height,
                         double rotation, double random, long ribbon, ColorFunction.Color startColor, Motion motion) {
        this(position, velocity, born, lifetime, width, height, rotation, random, ribbon, startColor, 1, random,
                random, random, random, random, motion);
    }

    ParticleState(Vec3 position, Vec3 velocity, double born, double lifetime, double width, double height,
                  double rotation, double random, long ribbon, ColorFunction.Color startColor, double mass, double dragRandom,
                  double redRandom, double greenRandom, double blueRandom, double alphaRandom) {
        this(position, velocity, born, lifetime, width, height, rotation, random, ribbon, startColor, mass, dragRandom,
                redRandom, greenRandom, blueRandom, alphaRandom, new Motion(born, position, velocity));
    }

    private ParticleState(Vec3 position, Vec3 velocity, double born, double lifetime, double width, double height,
                          double rotation, double random, long ribbon, ColorFunction.Color startColor,
                          double mass, double dragRandom, double redRandom, double greenRandom, double blueRandom, double alphaRandom, Motion motion) {
        this.position = position;
        this.velocity = velocity;
        this.born = born;
        this.lifetime = lifetime;
        deathTime = born + lifetime;
        this.mass = mass;
        this.dragRandom = dragRandom;
        colorRedRandom = redRandom; colorGreenRandom = greenRandom; colorBlueRandom = blueRandom; colorAlphaRandom = alphaRandom;
        this.width = width;
        this.height = height;
        this.rotation = rotation;
        this.random = random;
        this.ribbon = ribbon;
        this.startColor = startColor;
        motionTime = motion.time();
        Vec3 point = motion.position();
        motionX = point.x;
        motionY = point.y;
        motionZ = point.z;
        Vec3 speed = motion.velocity();
        motionVelocityX = speed.x;
        motionVelocityY = speed.y;
        motionVelocityZ = speed.z;
    }

    public Vec3 position() { return position; }
    public Vec3 velocity() { return velocity; }
    public double born() { return born; }
    public double lifetime() { return lifetime; }
    public double deathTime() { return deathTime; }
    public void expireAt(double time) {
        deathTime = retainedInRing ? time : Math.min(deathTime, time);
        retainedInRing = false;
        ringEndTime = Math.min(ringEndTime, deathTime);
    }
    public void expireInRing(double time) {
        if (retainedInRing) {
            collisionAgeOffset = Math.max(collisionAgeOffset, lifetime - (time - born));
            lifetimeLoop = null;
            deathTime = time;
            ringEndTime = Math.min(ringEndTime, time);
        } else {
            expireAt(time);
        }
    }
    long ringOrder() { return ringOrder; }
    public double ringEndTime() { return ringEndTime; }
    boolean ringRetained() { return retainedInRing; }
    public void retainInRing(long order, @Nullable LifetimeLoop loop) {
        retainedInRing = true;
        ringOrder = order;
        lifetimeLoop = loop;
        if (loop != null) deathTime = Double.POSITIVE_INFINITY;
    }
    void releaseFromLoop(double time) {
        var loop = lifetimeLoop;
        if (loop == null || !retainedInRing) return;
        loopReleaseAge = unwrappedAge(time);
        deathTime = time + (1 - loop.age(loopReleaseAge)) * lifetime;
        ringEndTime = deathTime;
        retainedInRing = false;
    }
    public void recycleFromRing(double time) {
        if (!retainedInRing && deathTime <= time) return;
        retainedInRing = false;
        deathTime = Math.min(deathTime, time);
        ringEndTime = time;
    }
    private double appearanceTime(double time) { return ringOrder < 0 ? time : Math.min(time, deathTime); }
    private double motionEndTime() {
        if (retainedInRing) return Double.POSITIVE_INFINITY;
        return ringOrder >= 0 && lifetimeLoop == null ? ringEndTime : deathTime;
    }

    private double motionAge(double time) {
        return Math.max(0, (ringOrder < 0 ? time : Math.min(time, motionEndTime())) - born);
    }

    boolean acceptsTriggerAt(double time) { return retainedInRing || deathTime > time; }
    private double unwrappedAge(double time) { return (appearanceTime(time) - born + collisionAgeOffset) / lifetime; }
    private double loopAge(LifetimeLoop loop, double age) {
        if (age < loopReleaseAge) return loop.age(age);
        return loop.age(loopReleaseAge) + (age - loopReleaseAge);
    }
    public double normalizedAge(double time) {
        var loop = lifetimeLoop;
        return loop == null ? ParticleLifetime.once(appearanceTime(time) - born + collisionAgeOffset, lifetime)
                : Math.clamp(loopAge(loop, unwrappedAge(time)), 0, 1);
    }
    public double elapsedLifetime(double time) {
        return lifetimeLoop == null ? Math.clamp(appearanceTime(time) - born + collisionAgeOffset, 0, lifetime)
                : normalizedAge(time) * lifetime;
    }

    private double lifetimeIntegral(ScalarFunction curve, double from, double to) {
        var loop = lifetimeLoop;
        if (loop == null) {
            if (ringOrder >= 0 && to > 1) {
                double held = to - Math.max(1, from);
                return (curve.integral(Math.min(from, 1), 1, random) + held * curve.sample(1, random)) * lifetime;
            }
            return curve.integral(from, to, random) * lifetime;
        }
        return (loopIntegral(loop, curve, to) - loopIntegral(loop, curve, from)) * lifetime;
    }

    private double loopIntegral(LifetimeLoop loop, ScalarFunction curve, double age) {
        if (age <= loopReleaseAge) return loop.integral(curve, age, random);
        double release = loop.age(loopReleaseAge);
        return loop.integral(curve, loopReleaseAge, random)
                + curve.integral(release, Math.min(1, release + (age - loopReleaseAge)), random);
    }
    public double width() { return width; }
    public double height() { return height; }
    public double rotation() { return rotation; }
    public double integratedRotationX() { return integratedRotationX; }
    public double integratedRotationY() { return integratedRotationY; }
    void reverseSpin() { reversedSpin = true; }
    void billboardFlip(boolean x, boolean y) { billboardFlipX = x; billboardFlipY = y; }
    public boolean billboardFlipX() { return billboardFlipX; }
    public boolean billboardFlipY() { return billboardFlipY; }
    public double spinOverLife(ScalarFunction angularVelocity, double age, double time) {
        double offset = lifetimeIntegral(angularVelocity, 0, lifetimeLoop == null ? age : unwrappedAge(time));
        return reversedSpin ? -offset : offset;
    }
    public double rotationZ(double age, double time, EmitterDefinition definition) {
        return (reversedSpin ? -rotation : rotation) + integratedRotationZ + spinOverLife(definition.angularVelocity(), age, time);
    }
    public double random() { return random; }
    public double colorRandom(int channel) {
        return switch (channel) { case 0 -> colorRedRandom; case 1 -> colorGreenRandom; case 2 -> colorBlueRandom; case 3 -> colorAlphaRandom;
            default -> throw new IllegalArgumentException("Invalid color channel"); };
    }
    public long ribbon() { return ribbon; }
    public ColorFunction.Color startColor() { return startColor; }
    public @Nullable MeshBirth meshBirth() { return meshBirth; }
    public @Nullable Vec3 unityBirthDirection() { return unityBirthDirection; }
    public int atlasRow(int fallback) { return atlasRow < 0 ? fallback : atlasRow; }
    void unityParticleId(int id) { unityParticleId = id; }
    public int unityParticleId() { return unityParticleId; }
    public int nextUnityCollisionSeq() { return unityCollisionSeq++; }
    void atlasRowAtBirth(int row) { atlasRow = row; }
    void meshBirth(double depth, double rotationX, double rotationY, int meshSlot, int flipMask) {
        meshBirth = new MeshBirth(depth, rotationX, rotationY, meshSlot, flipMask);
    }
    void birthOrientation(Quaterniond orientation) { birthOrientation = orientation; }
    void unityBirthDirection(Vec3 direction) { unityBirthDirection = direction; }

    private Vector3d spaceVector(MotionProfile profile, @Nullable UnitySpaceTransform transform,
                                 double x, double y, double z) {
        var value = profile.spaceVector.set(x, y, z);
        if (transform != null) {
            if (transform.fixed()) {
                transform.rotation(profile.resolvedSpaceRotation).transform(value);
                return value;
            }
            var base = profile.simulationLocal ? profile.worldToLocal : birthOrientation;
            if (base != null) base.mul(transform.rotation(), profile.resolvedSpaceRotation).transform(value);
            else transform.rotation(profile.resolvedSpaceRotation).transform(value);
            return value;
        }
        var rotation = profile.simulationLocal ? profile.worldToLocal : birthOrientation;
        if (rotation != null) rotation.transform(value);
        return value;
    }

    private double forceRandom(double midpoint, int axis) {
        long z = Double.doubleToRawLongBits(random) * 0x9e3779b97f4a7c15L
                ^ Double.doubleToRawLongBits(born) * 0xbf58476d1ce4e5b9L
                ^ Double.doubleToRawLongBits(midpoint) * 0x94d049bb133111ebL
                ^ (axis + 1L) * 0x369dea0f31a53f85L;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53;
    }

    public Motion motion() {
        return new Motion(motionTime, new Vec3(motionX, motionY, motionZ),
                new Vec3(motionVelocityX, motionVelocityY, motionVelocityZ));
    }

    public ParticleState advance(double now, EmitterDefinition definition, ImprovedNoise noise) {
        return advance(now, definition, noise, profile(definition));
    }

    ParticleState advance(double now, EmitterDefinition definition, ImprovedNoise noise, MotionProfile profile) {
        cachedProfile = profile;
        cachedDefinition = definition;
        var speedModules = profile.speedModules;
        var spin = speedModules == null ? null : speedModules.rotation();
        if (!profile.usesMotion) {
            if (spin != null) {
                double end = Math.min(now, motionEndTime());
                while (motionTime < end) {
                    double next = Math.min(motionTime + MOTION_STEP, end);
                    if (next == motionTime) break;
                    advanceSpin(spin, sampleSpeed((motionTime + next) * .5, definition), next - motionTime);
                    motionTime = next;
                }
            }
            return this;
        }
        double pointX = motionX, pointY = motionY, pointZ = motionZ;
        double speedX = motionVelocityX, speedY = motionVelocityY, speedZ = motionVelocityZ;
        double time = motionTime;
        boolean combined = profile.velocityLimiter != null || profile.speedModifier != null;
        var unityNoise = profile.unityNoise;
        var shurikenClock = profile.shurikenClock;
        boolean shuriken = shurikenClock != null;
        boolean needsResolvedVelocity = shuriken || combined || profile.contacts != null || spin != null
                || profile.orbitalMotion != null || unityNoise != null;
        while (time < now && time < motionEndTime()) {
            double target = Math.min(now, motionEndTime());
            double nextTime = profile.niagara || shuriken ? target : Math.min(time + MOTION_STEP, target);
            if (nextTime == time) break;
            double step = shuriken ? shurikenClock.duration(time, nextTime, born) : nextTime - time;
            if (step <= 0) {
                time = nextTime;
                continue;
            }
            double forceTime = shuriken ? time : profile.niagara ? nextTime : (time + nextTime) * .5;
            double age = normalizedAge(forceTime);
            double fxRandom = profile.randomizedForce ? forceRandom(forceTime, 0) : random;
            double fyRandom = profile.randomizedForce ? forceRandom(forceTime, 1) : random;
            double fzRandom = profile.randomizedForce ? forceRandom(forceTime, 2) : random;
            double accelerationX = profile.acceleration.x + profile.forceOverLife.get(0).sample(age, fxRandom);
            double accelerationY = profile.acceleration.y + profile.forceOverLife.get(1).sample(age, fyRandom);
            double accelerationZ = profile.acceleration.z + profile.forceOverLife.get(2).sample(age, fzRandom);
            if (!profile.spaceForce.isEmpty()) {
                var force = profile.spaceForce;
                var projected = spaceVector(profile, profile.forceTransform,
                        force.get(0).sample(age, fxRandom), force.get(1).sample(age, fyRandom),
                        force.get(2).sample(age, fzRandom));
                accelerationX += projected.x;
                accelerationY += projected.y;
                accelerationZ += projected.z;
            }
            if (profile.vectorField) {
                double frequency = profile.noise.frequency();
                double scale = profile.field.ownerScale() ? profile.ownerScale : 1;
                double coordinateX = (pointX / scale + profile.fieldOffset.x) * frequency;
                double coordinateY = (pointY / scale + profile.fieldOffset.y) * frequency;
                double coordinateZ = (pointZ / scale + profile.fieldOffset.z) * frequency;
                profile.field.volume().sample(coordinateX, coordinateY, coordinateZ,
                        profile.field.tileX(), profile.field.tileY(), profile.field.tileZ(), profile.fieldOutput, 0);
                Vec3 strength = profile.noise.strength();
                accelerationX += profile.fieldOutput[0] * strength.x;
                accelerationY += profile.fieldOutput[1] * strength.y;
                accelerationZ += profile.fieldOutput[2] * strength.z;
            }
            double drag = Math.max(0, profile.drag.sample(age, profile.niagara ? dragRandom : random));
            double nextSpeedX, nextSpeedY, nextSpeedZ;
            if (profile.niagara) {
                double denominator = 1 + drag * step;
                double massStep = step / Math.max(mass, 1e-6);
                nextSpeedX = (speedX + accelerationX * massStep) / denominator;
                nextSpeedY = (speedY + accelerationY * massStep) / denominator;
                nextSpeedZ = (speedZ + accelerationZ * massStep) / denominator;
            } else if (shuriken && drag == 0) {
                nextSpeedX = (float)speedX + (float)accelerationX * (float)step;
                nextSpeedY = (float)speedY + (float)accelerationY * (float)step;
                nextSpeedZ = (float)speedZ + (float)accelerationZ * (float)step;
            } else {
                double decay = Math.exp(-drag * step);
                double dragFactor = drag == 0 ? step : -Math.expm1(-drag * step) / drag;
                nextSpeedX = speedX * decay + accelerationX * dragFactor;
                nextSpeedY = speedY * decay + accelerationY * dragFactor;
                nextSpeedZ = speedZ * decay + accelerationZ * dragFactor;
            }
            if (profile.externalForces != null) {
                double speed = length(nextSpeedX, nextSpeedY, nextSpeedZ);
                double size = Math.max(Math.abs(width * sizeScale(0, age, speed, profile)),
                        Math.abs(height * sizeScale(1, age, speed, profile)));
                if (meshBirth != null) size = Math.max(size, Math.abs(meshBirth.depth() * sizeScale(2, age, speed, profile)));
                if (profile.externalForces.apply(pointX, pointY, pointZ, nextSpeedX, nextSpeedY, nextSpeedZ,
                        age, random, (float)size, (float)step, time)) {
                    var fieldVelocity = profile.externalForces.velocity();
                    nextSpeedX = fieldVelocity.x;
                    nextSpeedY = fieldVelocity.y;
                    nextSpeedZ = fieldVelocity.z;
                }
            }
            double elapsed = time - born + collisionAgeOffset;
            double from = elapsed / lifetime, to = (elapsed + step) / lifetime;
            double moduleX = velocityDisplacement(profile.velocityOverLife.get(0), from, to, age, step, shuriken);
            double moduleY = velocityDisplacement(profile.velocityOverLife.get(1), from, to, age, step, shuriken);
            double moduleZ = velocityDisplacement(profile.velocityOverLife.get(2), from, to, age, step, shuriken);
            if (!profile.spaceVelocity.isEmpty()) {
                var velocity = profile.spaceVelocity;
                var projected = spaceVector(profile, profile.velocityTransform,
                        velocityDisplacement(velocity.get(0), from, to, age, step, shuriken),
                        velocityDisplacement(velocity.get(1), from, to, age, step, shuriken), velocityDisplacement(velocity.get(2), from, to, age, step, shuriken));
                moduleX += projected.x;
                moduleY += projected.y;
                moduleZ += projected.z;
            }
            if (profile.currentInheritedVelocity != null) {
                double seconds = velocityDisplacement(profile.currentInheritedVelocity, from, to, age, step, shuriken);
                moduleX += profile.emitterVelocity.x * seconds;
                moduleY += profile.emitterVelocity.y * seconds;
                moduleZ += profile.emitterVelocity.z * seconds;
            }
            double dx, dy, dz;
            double multiplier = profile.speedModifier == null ? 1 : profile.speedModifier.sample(age, random);
            if (profile.orbitalMotion != null && multiplier != 0) {
                var orbital = profile.orbitalMotion.velocity(pointX, pointY, pointZ, age, random, step * multiplier);
                moduleX += orbital.x * step;
                moduleY += orbital.y * step;
                moduleZ += orbital.z * step;
            }
            if (unityNoise != null) {
                unityNoise.sample(noise, pointX, pointY, pointZ, time + step * .5, age, random);
                moduleX += unityNoise.x * step;
                moduleY += unityNoise.y * step;
                moduleZ += unityNoise.z * step;
                noiseSizeScale = unityNoise.sizeScale;
            }
            double extraX = needsResolvedVelocity ? moduleX / step : 0;
            double extraY = needsResolvedVelocity ? moduleY / step : 0;
            double extraZ = needsResolvedVelocity ? moduleZ / step : 0;
            double totalX = nextSpeedX + extraX, totalY = nextSpeedY + extraY, totalZ = nextSpeedZ + extraZ;
            if (combined) {
                double moveX = totalX * multiplier, moveY = totalY * multiplier, moveZ = totalZ * multiplier;
                if (profile.velocityLimiter != null) {
                    double speed = length(moveX, moveY, moveZ);
                    double size = Math.max(0, width * sizeScale(0, age, speed, profile) * (definition.localSpace() ? profile.ownerScale : 1));
                    var limited = profile.velocityLimiter.apply(nextSpeedX * multiplier, nextSpeedY * multiplier, nextSpeedZ * multiplier,
                            extraX * multiplier, extraY * multiplier, extraZ * multiplier, age, size, random, step);
                    if (multiplier != 0) {
                        nextSpeedX += (limited.x - moveX) / multiplier;
                        nextSpeedY += (limited.y - moveY) / multiplier;
                        nextSpeedZ += (limited.z - moveZ) / multiplier;
                        totalX = limited.x / multiplier;
                        totalY = limited.y / multiplier;
                        totalZ = limited.z / multiplier;
                    }
                    moveX = limited.x;
                    moveY = limited.y;
                    moveZ = limited.z;
                }
                dx = moveX * step;
                dy = moveY * step;
                dz = moveZ * step;
            } else if (shuriken) {
                dx = (float)totalX * (float)step;
                dy = (float)totalY * (float)step;
                dz = (float)totalZ * (float)step;
            } else {
                dx = profile.niagara ? nextSpeedX * step : (speedX + nextSpeedX) * (step * .5);
                dy = profile.niagara ? nextSpeedY * step : (speedY + nextSpeedY) * (step * .5);
                dz = profile.niagara ? nextSpeedZ * step : (speedZ + nextSpeedZ) * (step * .5);
            }
            boolean moduleDisplacementIncluded = shuriken || combined;
            if (profile.contacts != null) {
                double speed = length(totalX, totalY, totalZ);
                double size = Math.max(Math.abs(width * sizeScale(0, age, speed, profile)),
                        Math.abs(height * sizeScale(1, age, speed, profile)));
                if (meshBirth != null) size = Math.max(size, Math.abs(meshBirth.depth() * sizeScale(2, age, speed, profile)));
                var contact = profile.contacts.move(pointX, pointY, pointZ,
                        moduleDisplacementIncluded ? dx : dx + moduleX, moduleDisplacementIncluded ? dy : dy + moduleY,
                        moduleDisplacementIncluded ? dz : dz + moduleZ,
                        totalX, totalY, totalZ, time, step, size * .5, age,
                        lifetimeLoop != null && retainedInRing ? (1 - normalizedAge(time)) * lifetime : deathTime - time,
                        lifetime, random, retainedInRing && lifetimeLoop == null,
                        profile.collisionImpact == null ? null : impact -> profile.collisionImpact.accept(this, impact));
                dx = contact.moveX;
                dy = contact.moveY;
                dz = contact.moveZ;
                if (contact.touched) {
                    if (multiplier != 0) {
                        nextSpeedX = contact.svx / multiplier - extraX;
                        nextSpeedY = contact.svy / multiplier - extraY;
                        nextSpeedZ = contact.svz / multiplier - extraZ;
                    }
                    totalX = contact.svx;
                    totalY = contact.svy;
                    totalZ = contact.svz;
                    collisionAgeOffset += contact.lifetimeLost * lifetime;
                    if (contact.killed) expireAt(time + contact.elapsedOnDeath);
                    else if (lifetimeLoop == null)
                        deathTime = Math.max(time + step, born + lifetime - collisionAgeOffset);
                    else if (!retainedInRing)
                        ringEndTime = deathTime = Math.max(time + step, deathTime - contact.lifetimeLost * lifetime);
                }
                pointX += dx;
                pointY += dy;
                pointZ += dz;
            } else if (shuriken) {
                pointX = addShurikenDisplacement(pointX, dx, profile.simulationLocal ? 0 : position.x);
                pointY = addShurikenDisplacement(pointY, dy, profile.simulationLocal ? 0 : position.y);
                pointZ = addShurikenDisplacement(pointZ, dz, profile.simulationLocal ? 0 : position.z);
            } else if (combined) {
                pointX += dx;
                pointY += dy;
                pointZ += dz;
            } else {
                pointX = pointX + dx + moduleX;
                pointY = pointY + dy + moduleY;
                pointZ = pointZ + dz + moduleZ;
            }
            if (needsResolvedVelocity) {
                resolvedVelocityX = totalX;
                resolvedVelocityY = totalY;
                resolvedVelocityZ = totalZ;
            }
            double end = Math.min(nextTime, motionEndTime());
            if (spin != null) advanceSpin(spin, length(totalX, totalY, totalZ), end - time);
            if (unityNoise != null) {
                double stepElapsed = end - time;
                integratedRotationX += unityNoise.rotationX * stepElapsed;
                integratedRotationY += unityNoise.rotationY * stepElapsed;
                integratedRotationZ += unityNoise.rotationZ * stepElapsed;
            }
            speedX = nextSpeedX;
            speedY = nextSpeedY;
            speedZ = nextSpeedZ;
            time = end;
        }
        motionTime = time;
        motionX = pointX;
        motionY = pointY;
        motionZ = pointZ;
        motionVelocityX = speedX;
        motionVelocityY = speedY;
        motionVelocityZ = speedZ;
        return this;
    }

    private static double addShurikenDisplacement(double position, double displacement, double origin) {
        // Local Float32 accumulation matches the native vertex spacing while world anchors can be large in Minecraft.
        float local = (float)(position - origin);
        return origin + (local + (float)displacement);
    }

    private double velocityDisplacement(ScalarFunction curve, double from, double to, double age, double delta,
                                        boolean shuriken) {
        return shuriken ? (float)curve.sample(age, random) * (float)delta : lifetimeIntegral(curve, from, to);
    }

    public void samplePosition(double time, EmitterDefinition definition, ImprovedNoise noise, double[] output, int offset) {
        MotionProfile profile = profile(definition);
        double age = motionAge(time);
        double x, y, z;
        if (profile.usesMotion) {
            x = motionX;
            y = motionY;
            z = motionZ;
        } else {
            double drag = Math.max(0, profile.drag.sample(0, random));
            double accelerationX = profile.acceleration.x + profile.forceOverLife.get(0).sample(0, random);
            double accelerationY = profile.acceleration.y + profile.forceOverLife.get(1).sample(0, random);
            double accelerationZ = profile.acceleration.z + profile.forceOverLife.get(2).sample(0, random);
            if (drag == 0) {
                double halfAgeSquared = age * age * .5;
                x = position.x + velocity.x * age + accelerationX * halfAgeSquared;
                y = position.y + velocity.y * age + accelerationY * halfAgeSquared;
                z = position.z + velocity.z * age + accelerationZ * halfAgeSquared;
            } else {
                double response = -Math.expm1(-drag * age) / drag;
                double factor = (age - response) / drag;
                x = position.x + velocity.x * response + accelerationX * factor;
                y = position.y + velocity.y * response + accelerationY * factor;
                z = position.z + velocity.z * response + accelerationZ * factor;
            }
            double normalizedAge = age / lifetime;
            x += lifetimeIntegral(profile.velocityOverLife.get(0), 0, normalizedAge);
            y += lifetimeIntegral(profile.velocityOverLife.get(1), 0, normalizedAge);
            z += lifetimeIntegral(profile.velocityOverLife.get(2), 0, normalizedAge);
        }
        if (profile.displacement) {
            Vec3 scroll = profile.noise.scroll();
            double frequency = profile.noise.frequency();
            double potentialX = position.x * frequency + scroll.x * age;
            double potentialY = position.y * frequency + scroll.y * age;
            double potentialZ = position.z * frequency + scroll.z * age;
            Vec3 strength = profile.noise.strength();
            x += strength.x * noise.noise(potentialX + random * 31, potentialY, potentialZ);
            y += strength.y * noise.noise(potentialX, potentialY + random * 47, potentialZ);
            z += strength.z * noise.noise(potentialX, potentialY, potentialZ + random * 61);
        }
        output[offset] = x;
        output[offset + 1] = y;
        output[offset + 2] = z;
    }

    public Vec3 positionAt(double time, EmitterDefinition definition, ImprovedNoise noise) {
        double[] output = new double[3];
        samplePosition(time, definition, noise, output, 0);
        return new Vec3(output[0], output[1], output[2]);
    }

    public void sampleVelocity(double time, EmitterDefinition definition, double[] output, int offset) {
        MotionProfile profile = profile(definition);
        if (profile.usesMotion && (profile.shurikenClock != null || profile.velocityLimiter != null
                || profile.speedModifier != null || profile.contacts != null
                || profile.orbitalMotion != null || profile.unityNoise != null
                || profile.speedModules != null && profile.speedModules.rotation() != null)
                && motionTime > born) {
            output[offset] = resolvedVelocityX;
            output[offset + 1] = resolvedVelocityY;
            output[offset + 2] = resolvedVelocityZ;
            return;
        }
        double age = motionAge(time);
        double normalizedAge = ringOrder < 0 ? (age + collisionAgeOffset) / lifetime : normalizedAge(time);
        double x, y, z;
        if (profile.usesMotion) {
            x = motionVelocityX; y = motionVelocityY; z = motionVelocityZ;
        } else {
            double drag = Math.max(0, profile.drag.sample(0, random));
            double decay = Math.exp(-drag * age);
            double response = drag == 0 ? age : -Math.expm1(-drag * age) / drag;
            x = velocity.x * decay + (profile.acceleration.x + profile.forceOverLife.get(0).sample(0, random)) * response;
            y = velocity.y * decay + (profile.acceleration.y + profile.forceOverLife.get(1).sample(0, random)) * response;
            z = velocity.z * decay + (profile.acceleration.z + profile.forceOverLife.get(2).sample(0, random)) * response;
        }
        if (profile.currentInheritedVelocity != null) {
            double multiplier = profile.currentInheritedVelocity.sample(normalizedAge, random);
            x += profile.emitterVelocity.x * multiplier;
            y += profile.emitterVelocity.y * multiplier;
            z += profile.emitterVelocity.z * multiplier;
        }
        if (!profile.spaceVelocity.isEmpty()) {
            var velocity = profile.spaceVelocity;
            var projected = spaceVector(profile, profile.velocityTransform,
                    velocity.get(0).sample(normalizedAge, random),
                    velocity.get(1).sample(normalizedAge, random), velocity.get(2).sample(normalizedAge, random));
            x += projected.x;
            y += projected.y;
            z += projected.z;
        }
        if (profile.orbitalMotion != null
                && (profile.speedModifier == null || profile.speedModifier.sample(normalizedAge, random) != 0)) {
            var orbital = profile.orbitalMotion.velocity(motionX, motionY, motionZ, normalizedAge, random, 0);
            x += orbital.x;
            y += orbital.y;
            z += orbital.z;
        }
        output[offset] = x + profile.velocityOverLife.get(0).sample(normalizedAge, random);
        output[offset + 1] = y + profile.velocityOverLife.get(1).sample(normalizedAge, random);
        output[offset + 2] = z + profile.velocityOverLife.get(2).sample(normalizedAge, random);
    }

    public double sampleSpeed(double time, EmitterDefinition definition) {
        var profile = profile(definition);
        var speed = profile.sampledVelocity;
        sampleVelocity(Math.min(time, deathTime), definition, speed, 0);
        return length(speed[0], speed[1], speed[2]);
    }

    public double sizeScale(int axis, double age, double speed, EmitterDefinition definition) {
        return sizeScale(axis, age, speed, profile(definition));
    }

    private double sizeScale(int axis, double age, double speed, MotionProfile profile) {
        double lifetimeScale = (profile.sizeOverLifetimeAxes.isEmpty() ? profile.sizeOverLife
                : profile.sizeOverLifetimeAxes.get(axis)).sample(age, random);
        var modules = profile.speedModules;
        var size = modules == null ? null : modules.size();
        return noiseSizeScale * (size == null ? lifetimeScale : lifetimeScale * size.scale(axis, speed, random));
    }

    public boolean hasSpeedAppearance(EmitterDefinition definition) {
        var modules = profile(definition).speedModules;
        return modules != null && (modules.size() != null || modules.color() != null);
    }

    public ColorFunction.@Nullable Color speedColor(double speed, EmitterDefinition definition) {
        var modules = profile(definition).speedModules;
        var color = modules == null ? null : modules.color();
        return color == null ? null : color.sample(speed, random);
    }

    private void advanceSpin(UnitySpeedModules.Rotation spin, double speed, double elapsed) {
        double progress = spin.range().progress(speed);
        var axes = spin.axes();
        if (reversedSpin) elapsed = -elapsed;
        integratedRotationZ += axes.getLast().sample(progress, random) * elapsed;
        if (axes.size() == 3) {
            integratedRotationX += axes.get(0).sample(progress, random) * elapsed;
            integratedRotationY += axes.get(1).sample(progress, random) * elapsed;
        }
    }

    private static double length(double x, double y, double z) {
        return Math.sqrt(x * x + y * y + z * z);
    }

    private MotionProfile profile(EmitterDefinition definition) {
        MotionProfile profile = cachedProfile;
        if (profile == null || cachedDefinition != definition) {
            profile = new MotionProfile(definition);
            cachedProfile = profile;
            cachedDefinition = definition;
        }
        return profile;
    }
}
