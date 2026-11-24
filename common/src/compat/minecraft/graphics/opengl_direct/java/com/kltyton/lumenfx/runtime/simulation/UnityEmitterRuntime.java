package com.kltyton.lumenfx.runtime.simulation;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.math.SourceRandomStream;
import com.kltyton.lumenfx.runtime.bridge.McResourceBridge;
import com.kltyton.lumenfx.runtime.model.UnityEmitterModel;
import com.kltyton.lumenfx.runtime.model.UnityEmitterModel.BurstSpec;
import com.kltyton.lumenfx.runtime.model.UnityEmitterModel.CollisionSpec;
import com.kltyton.lumenfx.runtime.model.UnityEmitterModel.ShapeSpec;
import com.kltyton.lumenfx.runtime.model.UnityEmitterModel.ShapeType;
import com.kltyton.lumenfx.runtime.model.UnityEmitterModel.UnityShapeSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import javax.annotation.Nullable;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Exact 26.2-parity Unity Shuriken emitter simulator (ported from {@code ParticleEmitterSimulation}):
 * supports exact {@code unityBoxPerimeter} ({@code box_shell_thick}/{@code box_edge_thick}),
 * {@code loop}, {@code rate}, {@code bursts}, dual-curve {@code lifetime}, trails, noise, and collision.
 */
public final class UnityEmitterRuntime {
    public final UnityEmitterModel spec;
    public final List<ParticleInstance> particles = new ArrayList<>(64);
    private final SourceRandomStream rng;
    private final SplittableRandom shapeRng;
    private final int[] burstFiredCycles;
    private final @Nullable Quaterniond unityShapeRotation;
    private double elapsed;
    private double spawnCarry;
    private double lastOriginX, lastOriginY, lastOriginZ;
    private boolean hasLastOrigin;
    private double distanceCarry;
    private final float[] tmpColor = new float[4];

    public UnityEmitterRuntime(UnityEmitterModel spec, long seed) {
        this.spec = spec;
        long effectiveSeed = spec.randomSeed() != null ? spec.randomSeed() : seed;
        this.rng = new SourceRandomStream((int) (effectiveSeed ^ (effectiveSeed >>> 32)));
        this.shapeRng = new SplittableRandom(effectiveSeed ^ 0x9E3779B97F4A7C15L);
        this.burstFiredCycles = new int[spec.bursts().size()];
        UnityShapeSpec us = spec.shape().unity();
        this.unityShapeRotation = us != null
                ? new Quaterniond(us.rotX(), us.rotY(), us.rotZ(), us.rotW()).normalize() : null;
    }

    public void prewarm(EffectHandle.Snapshot snap) {
        if (!spec.prewarm() || !spec.loop()) return;
        int steps = Math.min(60, Math.max(1, (int) Math.round(spec.duration() * 20.0)));
        float dt = (float) (spec.duration() / steps);
        for (int i = 0; i < steps; i++) {
            step(snap, dt, false, null, null);
        }
    }

    public boolean isDone(boolean stopping) {
        if (!particles.isEmpty()) return false;
        if (stopping) return true;
        if (spec.loop()) return false;
        return elapsed >= spec.delay() + spec.duration();
    }

    public void step(EffectHandle.Snapshot snap, float rawDt, boolean stopping,
                     @Nullable Object level, @Nullable McResourceBridge bridge) {
        float dt = (float) (rawDt * spec.simulationSpeed());
        if (dt <= 0f) return;
        EffectHandle.Pose pose = snap.pose();
        Vec3 origin = pose.position();

        // 1. Advance existing particles
        for (int i = particles.size() - 1; i >= 0; i--) {
            ParticleInstance p = particles.get(i);
            p.prevX = p.x;
            p.prevY = p.y;
            p.prevZ = p.z;
            p.age += dt;
            if (p.age >= p.lifetime) {
                int last = particles.size() - 1;
                if (i != last) particles.set(i, particles.get(last));
                particles.remove(last);
                continue;
            }
            float t = p.normalizedAge();
            double[] acc = spec.acceleration();
            float fx = (float) acc[0] + spec.forceOverLife()[0].evaluate(t, p.curveRandom);
            float fy = (float) acc[1] + spec.forceOverLife()[1].evaluate(t, p.curveRandom);
            float fz = (float) acc[2] + spec.forceOverLife()[2].evaluate(t, p.curveRandom);
            double[] ns = spec.noiseStrength();
            if (ns[0] != 0.0 || ns[1] != 0.0 || ns[2] != 0.0) {
                double nf = spec.noiseFrequency();
                double phase = (elapsed + p.age) * nf;
                fx += (float) (Math.sin(p.y * nf + phase) * ns[0]);
                fy += (float) (Math.cos(p.z * nf + phase * 1.13) * ns[1]);
                fz += (float) (Math.sin(p.x * nf - phase * 0.91) * ns[2]);
            }
            p.vx += fx * dt;
            p.vy += fy * dt;
            p.vz += fz * dt;
            float dragVal = spec.drag().evaluate(t, p.curveRandom);
            if (dragVal > 0f) {
                float factor = Math.max(0f, 1f - dragVal * dt);
                p.vx *= factor;
                p.vy *= factor;
                p.vz *= factor;
            }
            float addVx = spec.velocityOverLife()[0].evaluate(t, p.curveRandom);
            float addVy = spec.velocityOverLife()[1].evaluate(t, p.curveRandom);
            float addVz = spec.velocityOverLife()[2].evaluate(t, p.curveRandom);
            double nx = p.x + (p.vx + addVx) * dt;
            double ny = p.y + (p.vy + addVy) * dt;
            double nz = p.z + (p.vz + addVz) * dt;

            CollisionSpec col = spec.collision();
            if (col.enabled() && bridge != null && level != null && bridge.isSolidBlock(level, nx, ny, nz)) {
                p.vy = -p.vy * col.bounce();
                float damp = Math.max(0f, 1f - col.dampen());
                p.vx *= damp;
                p.vz *= damp;
                p.age += p.lifetime * col.lifetimeLoss();
                nx = p.x; ny = p.y; nz = p.z;
            }
            p.x = nx; p.y = ny; p.z = nz;

            float sizeMult = spec.sizeOverLife().evaluate(t, p.curveRandom);
            p.width = Math.max(0.001f, p.startWidth * sizeMult);
            p.height = Math.max(0.001f, p.startHeight * sizeMult);
            p.rotationDeg += p.angularVelocityDeg * dt;
            spec.colorOverLife().evaluate(t, p.curveRandom, tmpColor);
            float[] tint = spec.tint();
            p.r = p.startR * tmpColor[0] * tint[0] * snap.red();
            p.g = p.startG * tmpColor[1] * tint[1] * snap.green();
            p.b = p.startB * tmpColor[2] * tint[2] * snap.blue();
            p.a = p.startA * tmpColor[3] * tint[3] * snap.alpha();

            if (bridge != null && level != null) {
                p.packedLight = bridge.samplePackedLight(level, p.x, p.y, p.z);
            }
            if (spec.trail().enabled() || spec.renderer() == UnityEmitterModel.RendererKind.RIBBON) {
                p.trailSampleAccum += dt;
                if (p.trailSampleAccum >= 0.025f) {
                    p.trailSampleAccum = 0f;
                    p.pushTrailPoint(p.x, p.y, p.z);
                }
            }
        }

        // 2. Emit new particles
        double prevElapsed = elapsed;
        elapsed += dt;
        if (stopping) return;
        double activeTime = elapsed - spec.delay();
        if (activeTime < 0.0) return;
        if (!spec.loop() && (prevElapsed - spec.delay()) >= spec.duration()) return;

        double cycleTime = spec.loop() ? (activeTime % spec.duration()) : Math.min(activeTime, spec.duration());
        float normalizedCycle = (float) Math.max(0.0, Math.min(1.0, cycleTime / spec.duration()));

        if (spec.loop() && Math.floor(activeTime / spec.duration()) > Math.floor(Math.max(0.0, prevElapsed - spec.delay()) / spec.duration())) {
            for (int b = 0; b < burstFiredCycles.length; b++) burstFiredCycles[b] = 0;
        }

        int toSpawn = 0;
        float rate = Math.max(0f, spec.rate().evaluate(normalizedCycle, rng.fraction()));
        if (rate > 0f) {
            spawnCarry += rate * dt;
            int count = (int) Math.floor(spawnCarry);
            if (count > 0) {
                spawnCarry -= count;
                toSpawn += count;
            }
        }

        if (hasLastOrigin) {
            double dx = origin.x - lastOriginX;
            double dy = origin.y - lastOriginY;
            double dz = origin.z - lastOriginZ;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > 1e-4) {
                if (spec.rateOverDistance() != null) {
                    float dr = Math.max(0f, spec.rateOverDistance().evaluate(normalizedCycle, rng.fraction()));
                    if (dr > 0f) {
                        distanceCarry += dist * dr;
                        int dc = (int) Math.floor(distanceCarry);
                        if (dc > 0) { distanceCarry -= dc; toSpawn += dc; }
                    }
                } else if (spec.distanceSpacing() > 0.0) {
                    distanceCarry += dist / spec.distanceSpacing();
                    int dc = (int) Math.floor(distanceCarry);
                    if (dc > 0) { distanceCarry -= dc; toSpawn += dc; }
                }
            }
        }
        lastOriginX = origin.x; lastOriginY = origin.y; lastOriginZ = origin.z;
        hasLastOrigin = true;

        List<BurstSpec> bursts = spec.bursts();
        for (int b = 0; b < bursts.size(); b++) {
            BurstSpec burst = bursts.get(b);
            while (burstFiredCycles[b] < burst.cycles()) {
                double trigger = burst.time() + burstFiredCycles[b] * Math.max(0.0, burst.interval());
                if (cycleTime >= trigger) {
                    burstFiredCycles[b]++;
                    if (burst.probability() >= 1.0 || rng.fraction() <= burst.probability()) {
                        int count;
                        if (burst.countCurve() != null) {
                            count = Math.max(0, Math.round(burst.countCurve().sample(normalizedCycle, rng)));
                        } else if (burst.maxCount() <= burst.minCount()) {
                            count = Math.max(0, burst.minCount());
                        } else {
                            count = rng.rangeInt(burst.minCount(), burst.maxCount());
                        }
                        toSpawn += count;
                    }
                } else {
                    break;
                }
            }
        }

        int room = spec.maxParticles() - particles.size();
        int spawnCount = Math.min(toSpawn, Math.max(0, room));
        for (int s = 0; s < spawnCount; s++) {
            spawnParticle(snap, normalizedCycle, level, bridge);
        }
    }

    private void spawnParticle(EffectHandle.Snapshot snap, float normalizedCycle,
                               @Nullable Object level, @Nullable McResourceBridge bridge) {
        EffectHandle.Pose pose = snap.pose();
        Vec3 localOffset = sampleShapeOffset();
        Vec3 localDir = sampleDirection(localOffset);

        UnityShapeSpec us = spec.shape().unity();
        if (us != null) {
            if (unityShapeRotation != null) {
                Vector3d rPos = unityShapeRotation.transform(localOffset.x, localOffset.y, localOffset.z, new Vector3d());
                localOffset = new Vec3(rPos.x, rPos.y, rPos.z);
                Vector3d rDir = unityShapeRotation.transform(localDir.x, localDir.y, localDir.z, new Vector3d());
                localDir = new Vec3(rDir.x, rDir.y, rDir.z);
            }
            localOffset = localOffset.add(us.posX(), us.posY(), us.posZ());
        }

        Vec3 worldPos = pose.transformPosition(localOffset);
        Vec3 worldDir = pose.transformDirection(localDir);
        double dLen = worldDir.length();
        worldDir = dLen > 1e-6 ? worldDir.scale(1.0 / dLen) : new Vec3(0.0, 1.0, 0.0);

        float lifetime = Math.max(0.02f, spec.lifetime().sample(normalizedCycle, rng));
        float speed = spec.startSpeed().sample(normalizedCycle, rng);
        float w = Math.max(0.005f, spec.width().sample(normalizedCycle, rng) * pose.scaleX());
        float h = Math.max(0.005f, spec.height().sample(normalizedCycle, rng) * pose.scaleX());
        float rot = (float) Math.toDegrees(spec.rotation().sample(normalizedCycle, rng));
        float angVel = (float) Math.toDegrees(spec.angularVelocity().sample(normalizedCycle, rng));
        float curveRand = rng.fraction();
        spec.startColor().evaluate(normalizedCycle, rng.fraction(), tmpColor);

        double[] vMin = spec.velocityMin();
        double[] vMax = spec.velocityMax();
        double randVx = vMin[0] + (vMax[0] - vMin[0]) * rng.fraction();
        double randVy = vMin[1] + (vMax[1] - vMin[1]) * rng.fraction();
        double randVz = vMin[2] + (vMax[2] - vMin[2]) * rng.fraction();

        ParticleInstance p = new ParticleInstance();
        p.x = p.prevX = worldPos.x;
        p.y = p.prevY = worldPos.y;
        p.z = p.prevZ = worldPos.z;
        p.vx = (float) (worldDir.x * speed + randVx);
        p.vy = (float) (worldDir.y * speed + randVy);
        p.vz = (float) (worldDir.z * speed + randVz);
        p.age = 0f;
        p.lifetime = lifetime;
        p.startWidth = p.width = w;
        p.startHeight = p.height = h;
        p.rotationDeg = rot;
        p.angularVelocityDeg = angVel;
        p.curveRandom = curveRand;
        p.startR = tmpColor[0];
        p.startG = tmpColor[1];
        p.startB = tmpColor[2];
        p.startA = tmpColor[3];
        float[] tint = spec.tint();
        p.r = p.startR * tint[0] * snap.red();
        p.g = p.startG * tint[1] * snap.green();
        p.b = p.startB * tint[2] * snap.blue();
        p.a = p.startA * tint[3] * snap.alpha();
        if (bridge != null && level != null) {
            p.packedLight = bridge.samplePackedLight(level, p.x, p.y, p.z);
        }
        if (spec.trail().enabled() || spec.renderer() == UnityEmitterModel.RendererKind.RIBBON) {
            p.resetTrail(p.x, p.y, p.z);
        }
        particles.add(p);
    }

    /** Exact 26.2 {@code ParticleEmitterSimulation.shape} + {@code unityBoxPerimeter} port. */
    private Vec3 sampleShapeOffset() {
        ShapeSpec sh = spec.shape();
        UnityShapeSpec unity = sh.unity();
        double inner = unity == null ? 0.0 : 1.0 - unity.radiusThickness();
        double az = shapeRng.nextDouble() * (unity != null ? unity.arcRadians() : Math.PI * 2.0);
        double rad = sh.surface() ? 1.0 : Math.sqrt(inner * inner + shapeRng.nextDouble() * (1.0 - inner * inner));
        return switch (sh.type()) {
            case POINT, MESH -> Vec3.ZERO;
            case BOX, BOX_EDGE -> {
                double x = between(-1.0, 1.0) * sh.sizeX();
                double y = between(-1.0, 1.0) * sh.sizeY();
                double z = between(-1.0, 1.0) * sh.sizeZ();
                if (unity != null) {
                    if (sh.type() == ShapeType.BOX_EDGE) {
                        int axis = shapeRng.nextInt(3);
                        if (axis != 0) x = unityBoxPerimeter(sh.sizeX(), unity.boxThickX(), -1.0);
                        if (axis != 1) y = unityBoxPerimeter(sh.sizeY(), unity.boxThickY(), -1.0);
                        if (axis != 2) z = unityBoxPerimeter(sh.sizeZ(), unity.boxThickZ(), 1.0);
                    } else if (sh.surface()) {
                        int axis = shapeRng.nextInt(3);
                        if (axis == 0) x = unityBoxPerimeter(sh.sizeX(), unity.boxThickX(), -1.0);
                        else if (axis == 1) y = unityBoxPerimeter(sh.sizeY(), unity.boxThickY(), -1.0);
                        else z = unityBoxPerimeter(sh.sizeZ(), unity.boxThickZ(), 1.0);
                    }
                } else if (sh.surface()) {
                    int axis = shapeRng.nextInt(3);
                    if (axis == 0) x = (shapeRng.nextBoolean() ? 1.0 : -1.0) * sh.sizeX();
                    else if (axis == 1) y = (shapeRng.nextBoolean() ? 1.0 : -1.0) * sh.sizeY();
                    else z = (shapeRng.nextBoolean() ? 1.0 : -1.0) * sh.sizeZ();
                }
                yield new Vec3(x, y, z);
            }
            case SPHERE, HEMISPHERE -> {
                double y = between(sh.type() == ShapeType.HEMISPHERE ? 0.0 : -1.0, 1.0);
                double c = Math.sqrt(Math.max(0.0, 1.0 - y * y));
                double r = sh.surface() ? 1.0 : Math.cbrt(inner * inner * inner + shapeRng.nextDouble() * (1.0 - inner * inner * inner));
                yield new Vec3(c * Math.cos(az) * sh.sizeX() * r, y * sh.sizeY() * r, c * Math.sin(az) * sh.sizeZ() * r);
            }
            case EDGE -> new Vec3(between(-1.0, 1.0) * sh.sizeX(), 0.0, 0.0);
            case CIRCLE -> new Vec3(rad * Math.cos(az) * sh.sizeX(), 0.0, rad * Math.sin(az) * sh.sizeZ());
            case CYLINDER -> new Vec3(rad * Math.cos(az) * sh.sizeX(), between(-1.0, 1.0) * sh.sizeY(), rad * Math.sin(az) * sh.sizeZ());
            case DONUT -> {
                double ringR = unity != null ? unity.donutRing() : 0.5;
                double tubeR = (unity != null ? unity.donutTube() : 0.1) * rad;
                double cs = shapeRng.nextDouble() * Math.PI * 2.0;
                double r = ringR + tubeR * Math.cos(cs);
                yield new Vec3(r * Math.cos(az) * sh.sizeX(), tubeR * Math.sin(cs) * sh.sizeY(), r * Math.sin(az) * sh.sizeZ());
            }
            case CONE -> {
                double slope = Math.tan(sh.angle());
                double bottom = sh.sizeX();
                double y = shapeRng.nextDouble() * sh.sizeY();
                double r = rad * (bottom + y * slope);
                yield new Vec3(r * Math.cos(az), y, r * Math.sin(az));
            }
        };
    }

    private Vec3 sampleDirection(Vec3 offset) {
        double[] sd = spec.startDirection();
        if (sd[0] != 0.0 || sd[1] != 0.0 || sd[2] != 0.0) {
            return new Vec3(sd[0], sd[1], sd[2]).normalize();
        }
        if (offset.lengthSqr() > 1e-8) return offset.normalize();
        return new Vec3(0.0, 1.0, 0.0);
    }

    /** Exact Unity Shuriken BoxShell/BoxEdge asymmetric outer-plane perimeter sampler from 26.2. */
    private double unityBoxPerimeter(double extent, double thickness, double outerSign) {
        if (thickness == 0.0) return (shapeRng.nextBoolean() ? 1.0 : -1.0) * extent;
        return shapeRng.nextBoolean()
                ? outerSign * extent
                : -outerSign * extent * (1.0 - 2.0 * thickness * shapeRng.nextDouble());
    }

    private double between(double lo, double hi) {
        return lo + shapeRng.nextDouble() * (hi - lo);
    }
}
