package com.kltyton.lumenfx.runtime.simulation;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.math.SourceRandomStream;
import com.kltyton.lumenfx.runtime.bridge.McResourceBridge;
import com.kltyton.lumenfx.runtime.model.PhotonFxModel;
import com.kltyton.lumenfx.runtime.model.PhotonFxModel.BurstSpec;
import com.kltyton.lumenfx.runtime.model.PhotonFxModel.CollisionSpec;
import com.kltyton.lumenfx.runtime.model.PhotonFxModel.ForceFieldSpec;
import com.kltyton.lumenfx.runtime.model.PhotonFxModel.FxObjectSpec;
import com.kltyton.lumenfx.runtime.model.PhotonFxModel.ObjectType;
import com.kltyton.lumenfx.runtime.model.PhotonFxModel.PhysicsExtras;
import com.kltyton.lumenfx.runtime.model.PhotonFxModel.RenderMode;
import com.kltyton.lumenfx.runtime.model.PhotonFxModel.ShapeSpec;
import com.kltyton.lumenfx.runtime.model.PhotonFxModel.SubEmitterSpec;
import com.kltyton.lumenfx.runtime.model.PhotonFxModel.TransformSpec;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Full-parity runtime state for a Photon {@code .fx} instance: hierarchy transforms, multi-burst
 * timeline, emitter shapes, gravity/drag/noise/forcefields, size/color/rotation curves, axis-aware
 * world block collision, sub-emitter event triggers (BIRTH/COLLISION/DEATH), segmented beams,
 * trails, and authored models.
 */
public final class PhotonFxRuntime {
    public static final class ObjectRuntime {
        public final FxObjectSpec spec;
        public final Matrix4f localToEffectMatrix;
        public final List<ParticleInstance> particles = new ArrayList<>(32);
        private final ArrayDeque<ParticleInstance> pool = new ArrayDeque<>(64);
        private final SourceRandomStream rng;
        private final int[] burstFiredCycles;
        private float elapsed;
        private float spawnCarry;
        private boolean fallbackBurstFired;
        private @Nullable Vec3 previousWorldOrigin;

        ObjectRuntime(FxObjectSpec spec, Matrix4f localToEffectMatrix, int seed) {
            this.spec = spec;
            this.localToEffectMatrix = localToEffectMatrix;
            this.rng = new SourceRandomStream(seed);
            PhysicsExtras ex = spec.extras() != null ? spec.extras() : PhysicsExtras.DEFAULT;
            this.burstFiredCycles = new int[ex.bursts().size()];
        }

        ParticleInstance borrow() {
            ParticleInstance p = pool.pollFirst();
            return p != null ? p : new ParticleInstance();
        }

        void recycle(ParticleInstance p) {
            if (pool.size() < 2048) pool.addFirst(p);
        }

        public boolean isDone(boolean stopping) {
            if (spec.type() == ObjectType.EMPTY) return true;
            if (!particles.isEmpty()) return false;
            if (stopping) return true;
            if (spec.looping()) return false;
            return elapsed >= spec.delaySeconds() + spec.durationSeconds();
        }
    }

    private final PhotonFxModel model;
    private final List<ObjectRuntime> objects;
    private final Map<String, ObjectRuntime> objectsByName;
    private final boolean hasCollision;

    public PhotonFxRuntime(PhotonFxModel model, long seed) {
        this.model = model;
        Map<String, TransformSpec> byId = new LinkedHashMap<>();
        for (FxObjectSpec obj : model.objects()) {
            if (!obj.transform().id().isBlank()) byId.put(obj.transform().id(), obj.transform());
        }
        int baseSeed = (int) (seed ^ (seed >>> 32));
        this.objects = new ArrayList<>(model.objects().size());
        this.objectsByName = new LinkedHashMap<>();
        boolean col = false;
        for (int i = 0; i < model.objects().size(); i++) {
            FxObjectSpec spec = model.objects().get(i);
            Matrix4f worldMat = resolveHierarchyMatrix(spec.transform(), byId, 0);
            ObjectRuntime rt = new ObjectRuntime(spec, worldMat, baseSeed + i * 0x9E3779B9);
            this.objects.add(rt);
            this.objectsByName.put(spec.name(), rt);
            PhysicsExtras ex = spec.extras();
            if (ex != null && ex.collision().enabled()) col = true;
        }
        this.hasCollision = col;
    }

    public PhotonFxModel model() { return model; }
    public List<ObjectRuntime> objects() { return objects; }
    public boolean requiresOwnerThread() { return hasCollision; }

    public int totalParticles() {
        int sum = 0;
        for (ObjectRuntime o : objects) sum += o.particles.size();
        return sum;
    }

    public boolean tick(EffectHandle.Snapshot snap, float dt, boolean stopping) {
        return tick(snap, dt, stopping, null, null);
    }

    public boolean tick(EffectHandle.Snapshot snap, float dt, boolean stopping,
                        @Nullable Object level, @Nullable McResourceBridge bridge) {
        boolean anyActive = false;
        EffectHandle.Pose pose = snap.pose();
        for (ObjectRuntime rt : objects) {
            if (rt.spec.type() == ObjectType.EMPTY) continue;
            stepObject(rt, pose, snap, dt, stopping, level, bridge);
            if (!rt.isDone(stopping)) anyActive = true;
        }
        return anyActive;
    }

    private void stepObject(ObjectRuntime rt, EffectHandle.Pose pose, EffectHandle.Snapshot snap,
                            float dt, boolean stopping,
                            @Nullable Object level, @Nullable McResourceBridge bridge) {
        FxObjectSpec spec = rt.spec;
        PhysicsExtras ex = spec.extras() != null ? spec.extras() : PhysicsExtras.DEFAULT;
        CollisionSpec col = ex.collision();
        float[] vol = ex.velocityOverLife();
        float[] fol = ex.forceOverLife();

        Vector3f localOrigin = rt.localToEffectMatrix.getTranslation(new Vector3f());
        Vec3 worldOrigin = pose.transformPosition(new Vec3(localOrigin.x, localOrigin.y, localOrigin.z));
        Vec3 displacement = spec.localSpace() && rt.previousWorldOrigin != null
                ? worldOrigin.subtract(rt.previousWorldOrigin) : Vec3.ZERO;
        rt.previousWorldOrigin = worldOrigin;

        for (int i = rt.particles.size() - 1; i >= 0; i--) {
            ParticleInstance p = rt.particles.get(i);
            p.prevX = p.x; p.prevY = p.y; p.prevZ = p.z;
            p.x += displacement.x;
            p.y += displacement.y;
            p.z += displacement.z;
            p.age += dt;
            if (p.age >= p.lifetime) {
                triggerSubEmitters(ex, "DEATH", p.x, p.y, p.z, snap, pose);
                removeParticle(rt, i);
                continue;
            }
            float t = p.normalizedAge();

            if (spec.renderMode() == RenderMode.BEAM) {
                updateBeamGeometry(rt, p, worldOrigin, pose, ex);
                applyCurves(p, ex, snap, t, dt);
                continue;
            }

            // Gravity + force-over-lifetime + turbulence noise + forcefields
            float ax = fol[0];
            float ay = fol[1] - 9.81f * ex.gravityModifier();
            float az = fol[2];
            if (ex.noiseStrength() > 0f) {
                float f = ex.noiseFrequency();
                double phase = (rt.elapsed + p.age) * f;
                ax += (float) Math.sin(p.y * f + phase) * ex.noiseStrength();
                ay += (float) Math.cos(p.z * f + phase * 1.17) * ex.noiseStrength();
                az += (float) Math.sin(p.x * f - phase * 0.89) * ex.noiseStrength();
            }
            for (ForceFieldSpec ff : ex.forceFields()) {
                double fxPos = worldOrigin.x + ff.x();
                double fyPos = worldOrigin.y + ff.y();
                double fzPos = worldOrigin.z + ff.z();
                double dx = fxPos - p.x;
                double dy = fyPos - p.y;
                double dz = fzPos - p.z;
                double distSq = dx * dx + dy * dy + dz * dz;
                double maxR = ff.radius();
                if (distSq > maxR * maxR) continue;
                double dist = Math.sqrt(Math.max(1e-6, distSq));
                float atten = (float) (1.0 - dist / maxR);
                if ("directional".equals(ff.kind())) {
                    ax += ff.x() * ff.strength();
                    ay += ff.y() * ff.strength();
                    az += ff.z() * ff.strength();
                } else if ("vortex".equals(ff.kind())) {
                    // Tangential swirl around Y axis + gentle inward pull
                    double tx = -dz / dist;
                    double tz = dx / dist;
                    ax += (float) (tx * ff.strength() * atten + (dx / dist) * ff.strength() * 0.25 * atten);
                    az += (float) (tz * ff.strength() * atten + (dz / dist) * ff.strength() * 0.25 * atten);
                } else {
                    // Point attractor (positive strength) or repulsor (negative strength)
                    ax += (float) ((dx / dist) * ff.strength() * atten);
                    ay += (float) ((dy / dist) * ff.strength() * atten);
                    az += (float) ((dz / dist) * ff.strength() * atten);
                }
            }

            p.vx += ax * dt;
            p.vy += ay * dt;
            p.vz += az * dt;
            if (ex.drag() > 0f) {
                float factor = Math.max(0f, 1f - ex.drag() * dt);
                p.vx *= factor; p.vy *= factor; p.vz *= factor;
            }

            double nx = p.x + (p.vx + vol[0]) * dt;
            double ny = p.y + (p.vy + vol[1]) * dt;
            double nz = p.z + (p.vz + vol[2]) * dt;

            if (col.enabled() && bridge != null && level != null && bridge.isSolidBlock(level, nx, ny, nz)) {
                boolean hitX = bridge.isSolidBlock(level, nx, p.y, p.z);
                boolean hitY = bridge.isSolidBlock(level, p.x, ny, p.z);
                boolean hitZ = bridge.isSolidBlock(level, p.x, p.y, nz);
                if (hitX) p.vx = -p.vx * col.bounce();
                if (hitY) p.vy = -p.vy * col.bounce();
                if (hitZ) p.vz = -p.vz * col.bounce();
                if (!hitX && !hitY && !hitZ) {
                    p.vx = -p.vx * col.bounce();
                    p.vy = -p.vy * col.bounce();
                    p.vz = -p.vz * col.bounce();
                }
                float damp = Math.max(0f, 1f - col.dampen());
                p.vx *= damp; p.vy *= damp; p.vz *= damp;
                p.age += p.lifetime * col.lifetimeLoss();
                nx = p.x; ny = p.y; nz = p.z;
                triggerSubEmitters(ex, "COLLISION", p.x, p.y, p.z, snap, pose);
                if (col.dieOnCollision() || p.age >= p.lifetime) {
                    triggerSubEmitters(ex, "DEATH", p.x, p.y, p.z, snap, pose);
                    removeParticle(rt, i);
                    continue;
                }
            }

            p.x = nx; p.y = ny; p.z = nz;
            applyCurves(p, ex, snap, t, dt);

            if (spec.renderMode() == RenderMode.TRAIL) {
                p.trailSampleAccum += dt;
                if (p.trailSampleAccum >= 0.02f) {
                    p.trailSampleAccum = 0f;
                    p.pushTrailPoint(p.x, p.y, p.z);
                }
            }
        }

        float prevElapsed = rt.elapsed;
        rt.elapsed += dt;
        if (stopping) return;
        float active = rt.elapsed - spec.delaySeconds();
        if (active < 0f) return;
        if (!spec.looping() && (prevElapsed - spec.delaySeconds()) > spec.durationSeconds()) return;

        float dur = Math.max(0.05f, spec.durationSeconds());
        float cycleTime = spec.looping() ? (active % dur) : Math.min(active, dur);
        if (spec.looping() && Math.floor(active / dur) > Math.floor(Math.max(0f, prevElapsed - spec.delaySeconds()) / dur)) {
            Arrays.fill(rt.burstFiredCycles, 0);
            rt.fallbackBurstFired = false;
        }

        int toSpawn = 0;
        List<BurstSpec> bursts = ex.bursts();
        if (!bursts.isEmpty()) {
            for (int b = 0; b < bursts.size(); b++) {
                BurstSpec burst = bursts.get(b);
                while (rt.burstFiredCycles[b] < burst.cycles()) {
                    float trigger = burst.timeSeconds() + rt.burstFiredCycles[b] * burst.intervalSeconds();
                    if (cycleTime >= trigger) {
                        rt.burstFiredCycles[b]++;
                        if (burst.probability() >= 1f || rt.rng.fraction() <= burst.probability()) {
                            toSpawn += burst.count();
                        }
                    } else {
                        break;
                    }
                }
            }
        } else if (!rt.fallbackBurstFired && spec.burstCount() > 0) {
            rt.fallbackBurstFired = true;
            toSpawn += spec.burstCount();
        }

        if (spec.renderMode() == RenderMode.BEAM && rt.particles.isEmpty()) {
            toSpawn = Math.max(1, toSpawn);
        } else if (spec.emissionRate() > 0f) {
            rt.spawnCarry += spec.emissionRate() * dt;
            int c = (int) Math.floor(rt.spawnCarry);
            if (c > 0) { rt.spawnCarry -= c; toSpawn += c; }
        }

        int cap = Math.max(1, ex.maxParticles());
        int spawnCount = Math.min(toSpawn, Math.max(0, cap - rt.particles.size()));
        for (int s = 0; s < spawnCount; s++) {
            spawnParticle(rt, worldOrigin.x, worldOrigin.y, worldOrigin.z, pose, snap, ex);
        }
    }

    private void spawnParticle(ObjectRuntime rt, double ox, double oy, double oz,
                               EffectHandle.Pose pose, EffectHandle.Snapshot snap, PhysicsExtras ex) {
        FxObjectSpec spec = rt.spec;
        ParticleInstance p = rt.borrow();
        Vec3 shapeOffset = sampleShapeOffset(ex.shape(), rt.rng);
        Vec3 worldOff = transformEmitterDirection(rt, pose, shapeOffset);
        p.x = p.prevX = ox + worldOff.x;
        p.y = p.prevY = oy + worldOff.y;
        p.z = p.prevZ = oz + worldOff.z;

        if (spec.renderMode() != RenderMode.BEAM) {
            Vec3 dir = shapeDirection(ex.shape(), shapeOffset, rt.rng);
            Vec3 worldDir = transformEmitterDirection(rt, pose, dir);
            double dl = worldDir.length();
            if (dl > 1e-6) worldDir = worldDir.scale(1.0 / dl);
            p.vx = (float) (worldDir.x * spec.startSpeed());
            p.vy = (float) (worldDir.y * spec.startSpeed());
            p.vz = (float) (worldDir.z * spec.startSpeed());
        } else {
            p.vx = p.vy = p.vz = 0f;
        }
        p.age = 0f;
        p.lifetime = Math.max(0.05f, spec.startLifetimeSeconds());
        p.startWidth = p.width = spec.startSize() * Math.abs(pose.scaleX()) * ex.sizeStartMult();
        p.startHeight = p.height = p.width;
        p.rotationDeg = ex.startRotationDeg();
        p.angularVelocityDeg = ex.rotationSpeedDeg();
        p.startR = p.r = spec.r() * snap.red();
        p.startG = p.g = spec.g() * snap.green();
        p.startB = p.b = spec.b() * snap.blue();
        p.startA = p.a = spec.a() * snap.alpha();
        if (spec.renderMode() == RenderMode.BEAM) {
            updateBeamGeometry(rt, p, new Vec3(ox, oy, oz), pose, ex);
        } else if (spec.renderMode() == RenderMode.TRAIL) {
            p.resetTrail(p.x, p.y, p.z);
        }
        rt.particles.add(p);
        triggerSubEmitters(ex, "BIRTH", p.x, p.y, p.z, snap, pose);
    }

    private static Vec3 transformEmitterDirection(ObjectRuntime runtime, EffectHandle.Pose pose, Vec3 direction) {
        var local = runtime.localToEffectMatrix.transformDirection(
                new Vector3f((float) direction.x, (float) direction.y, (float) direction.z));
        return pose.transformDirection(new Vec3(local.x, local.y, local.z));
    }

    private void triggerSubEmitters(PhysicsExtras ex, String trigger, double wx, double wy, double wz,
                                    EffectHandle.Snapshot snap, EffectHandle.Pose pose) {
        if (ex.subEmitters().isEmpty()) return;
        for (SubEmitterSpec sub : ex.subEmitters()) {
            if (!trigger.equals(sub.trigger())) continue;
            ObjectRuntime target = objectsByName.get(sub.targetName());
            if (target == null) continue;
            if (sub.probability() < 1f && target.rng.fraction() > sub.probability()) continue;
            PhysicsExtras targetEx = target.spec.extras() != null ? target.spec.extras() : PhysicsExtras.DEFAULT;
            int room = Math.max(0, targetEx.maxParticles() - target.particles.size());
            int count = Math.min(sub.spawnCount(), room);
            for (int c = 0; c < count; c++) {
                spawnParticle(target, wx, wy, wz, pose, snap, targetEx);
            }
        }
    }

    private static void applyCurves(ParticleInstance p, PhysicsExtras ex, EffectHandle.Snapshot snap, float t, float dt) {
        float sizeMult = ex.sizeStartMult() + (ex.sizeEndMult() - ex.sizeStartMult()) * t;
        p.width = Math.max(0.005f, p.startWidth * (ex.sizeStartMult() > 1e-4f ? sizeMult / ex.sizeStartMult() : sizeMult));
        p.height = p.width;
        p.rotationDeg += p.angularVelocityDeg * dt;
        if (ex.hasEndColor()) {
            p.r = (p.startR + (ex.endR() * snap.red() - p.startR) * t);
            p.g = (p.startG + (ex.endG() * snap.green() - p.startG) * t);
            p.b = (p.startB + (ex.endB() * snap.blue() - p.startB) * t);
            p.a = (p.startA + (ex.endA() * snap.alpha() - p.startA) * t);
        }
    }

    private static void updateBeamGeometry(ObjectRuntime rt, ParticleInstance p, Vec3 start,
                                           EffectHandle.Pose pose, PhysicsExtras ex) {
        Vec3 forward = pose.transformDirection(new Vec3(0.0, 0.0, 1.0));
        double fl = forward.length();
        forward = fl > 1e-5 ? forward.scale(1.0 / fl) : new Vec3(0.0, 0.0, 1.0);
        Vec3 end = start.add(forward.scale(ex.beamLength()));
        int segs = Math.max(2, Math.min(ParticleInstance.MAX_TRAIL_POINTS - 1, ex.beamSegments()));
        p.trailCount = segs + 1;
        for (int i = 0; i <= segs; i++) {
            float u = (float) i / segs;
            double bx = start.x + (end.x - start.x) * u;
            double by = start.y + (end.y - start.y) * u;
            double bz = start.z + (end.z - start.z) * u;
            if (i > 0 && i < segs && ex.beamNoise() > 0f) {
                float env = (float) Math.sin(u * Math.PI) * ex.beamNoise();
                double phase = rt.elapsed * 18.0 + i * 2.13;
                bx += Math.sin(phase) * env;
                by += Math.cos(phase * 1.31) * env;
                bz += Math.sin(phase * 0.77 + 1.0) * env;
            }
            p.trailX[i] = bx;
            p.trailY[i] = by;
            p.trailZ[i] = bz;
        }
    }

    private static void removeParticle(ObjectRuntime rt, int idx) {
        int last = rt.particles.size() - 1;
        ParticleInstance dead = rt.particles.get(idx);
        if (idx != last) rt.particles.set(idx, rt.particles.get(last));
        rt.particles.remove(last);
        rt.recycle(dead);
    }

    private static Vec3 sampleShapeOffset(ShapeSpec sh, SourceRandomStream rng) {
        String k = sh.shapeType().toLowerCase(Locale.ROOT);
        double az = rng.fraction() * Math.PI * 2.0;
        if (k.contains("sphere") || k.contains("hemi")) {
            double y = k.contains("hemi") ? rng.fraction() : (rng.fraction() * 2.0 - 1.0);
            double c = Math.sqrt(Math.max(0.0, 1.0 - y * y));
            double r = sh.surface() ? sh.radius() : sh.radius() * Math.cbrt(rng.fraction());
            return new Vec3(c * Math.cos(az) * r, y * r, c * Math.sin(az) * r);
        }
        if (k.contains("box")) {
            return new Vec3((rng.fraction() * 2.0 - 1.0) * sh.boxX(),
                    (rng.fraction() * 2.0 - 1.0) * sh.boxY(),
                    (rng.fraction() * 2.0 - 1.0) * sh.boxZ());
        }
        if (k.contains("circle") || k.contains("cone")) {
            double r = sh.surface() ? sh.radius() : sh.radius() * Math.sqrt(rng.fraction());
            return new Vec3(Math.cos(az) * r, 0.0, Math.sin(az) * r);
        }
        if (k.contains("edge")) {
            return new Vec3((rng.fraction() * 2.0 - 1.0) * sh.radius(), 0.0, 0.0);
        }
        return Vec3.ZERO;
    }

    private static Vec3 randomConeDirection(ShapeSpec sh, SourceRandomStream rng) {
        double az = rng.fraction() * Math.PI * 2.0;
        double rad = Math.toRadians(Math.max(5f, Math.min(89f, sh.angleDeg())));
        double spread = Math.tan(rad) * rng.fraction();
        return new Vec3(Math.cos(az) * spread, 1.0, Math.sin(az) * spread).normalize();
    }

    private static Vec3 shapeDirection(ShapeSpec shape, Vec3 offset, SourceRandomStream rng) {
        double distance = offset.length();
        if (shape.shapeType().equalsIgnoreCase("cone")) {
            if (distance <= 1e-6 || shape.radius() <= 0f) return new Vec3(0, 1, 0);
            double tilt = Math.min(1.0, distance / shape.radius()) * Math.toRadians(shape.angleDeg());
            double outward = Math.sin(tilt) / distance;
            return new Vec3(offset.x * outward, Math.cos(tilt), offset.z * outward);
        }
        return distance > 1e-6 ? offset.scale(1.0 / distance) : randomConeDirection(shape, rng);
    }

    private static Matrix4f resolveHierarchyMatrix(TransformSpec t, Map<String, TransformSpec> byId, int depth) {
        Matrix4f local = new Matrix4f()
                .translate(t.posX(), t.posY(), t.posZ())
                .rotate(new Quaternionf(t.rotX(), t.rotY(), t.rotZ(), t.rotW()))
                .scale(t.scaleX(), t.scaleY(), t.scaleZ());
        if (t.parentId() != null && depth < 16) {
            TransformSpec parent = byId.get(t.parentId());
            if (parent != null) {
                return resolveHierarchyMatrix(parent, byId, depth + 1).mul(local);
            }
        }
        return local;
    }
}
