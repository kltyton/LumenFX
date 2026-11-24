package com.kltyton.lumenfx.runtime.simulation;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.math.SourceRandomStream;
import com.kltyton.lumenfx.runtime.model.PhotonFxModel;
import com.kltyton.lumenfx.runtime.model.PhotonFxModel.FxObjectSpec;
import com.kltyton.lumenfx.runtime.model.PhotonFxModel.ObjectType;
import com.kltyton.lumenfx.runtime.model.PhotonFxModel.RenderMode;
import com.kltyton.lumenfx.runtime.model.PhotonFxModel.TransformSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Runtime state for a Photon 26.2 {@code .fx} instance resolving parent/child transforms and authored models. */
public final class PhotonFxRuntime {
    public static final class ObjectRuntime {
        public final FxObjectSpec spec;
        public final Matrix4f localToEffectMatrix;
        public final List<ParticleInstance> particles = new ArrayList<>(32);
        private final SourceRandomStream rng;
        private float elapsed;
        private float spawnCarry;
        private boolean burstFired;

        ObjectRuntime(FxObjectSpec spec, Matrix4f localToEffectMatrix, int seed) {
            this.spec = spec;
            this.localToEffectMatrix = localToEffectMatrix;
            this.rng = new SourceRandomStream(seed);
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

    public PhotonFxRuntime(PhotonFxModel model, long seed) {
        this.model = model;
        Map<String, TransformSpec> byId = new LinkedHashMap<>();
        for (FxObjectSpec obj : model.objects()) {
            if (!obj.transform().id().isBlank()) byId.put(obj.transform().id(), obj.transform());
        }
        int baseSeed = (int) (seed ^ (seed >>> 32));
        this.objects = new ArrayList<>(model.objects().size());
        for (int i = 0; i < model.objects().size(); i++) {
            FxObjectSpec spec = model.objects().get(i);
            Matrix4f worldMat = resolveHierarchyMatrix(spec.transform(), byId, 0);
            this.objects.add(new ObjectRuntime(spec, worldMat, baseSeed + i * 0x9E3779B9));
        }
    }

    public PhotonFxModel model() { return model; }
    public List<ObjectRuntime> objects() { return objects; }

    public int totalParticles() {
        int sum = 0;
        for (ObjectRuntime o : objects) sum += o.particles.size();
        return sum;
    }

    public boolean tick(EffectHandle.Snapshot snap, float dt, boolean stopping) {
        boolean anyActive = false;
        EffectHandle.Pose pose = snap.pose();
        for (ObjectRuntime rt : objects) {
            if (rt.spec.type() == ObjectType.EMPTY) continue;
            stepObject(rt, pose, snap, dt, stopping);
            if (!rt.isDone(stopping)) anyActive = true;
        }
        return anyActive;
    }

    private static void stepObject(ObjectRuntime rt, EffectHandle.Pose pose, EffectHandle.Snapshot snap,
                                   float dt, boolean stopping) {
        FxObjectSpec spec = rt.spec;
        for (int i = rt.particles.size() - 1; i >= 0; i--) {
            ParticleInstance p = rt.particles.get(i);
            p.prevX = p.x; p.prevY = p.y; p.prevZ = p.z;
            p.age += dt;
            if (p.age >= p.lifetime) {
                int last = rt.particles.size() - 1;
                if (i != last) rt.particles.set(i, rt.particles.get(last));
                rt.particles.remove(last);
                continue;
            }
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.z += p.vz * dt;
            if (spec.renderMode() == RenderMode.TRAIL || spec.renderMode() == RenderMode.BEAM) {
                p.trailSampleAccum += dt;
                if (p.trailSampleAccum >= 0.025f) {
                    p.trailSampleAccum = 0f;
                    p.pushTrailPoint(p.x, p.y, p.z);
                }
            }
        }

        rt.elapsed += dt;
        if (stopping) return;
        float active = rt.elapsed - spec.delaySeconds();
        if (active < 0f) return;
        if (!spec.looping() && active > spec.durationSeconds()) return;

        int toSpawn = 0;
        if (!rt.burstFired && spec.burstCount() > 0) {
            rt.burstFired = true;
            toSpawn += spec.burstCount();
        }
        if (spec.renderMode() == RenderMode.MODEL && rt.particles.isEmpty()) {
            // Authored Photon Model objects maintain at least one resident model instance
            toSpawn = Math.max(1, toSpawn);
        } else if (spec.emissionRate() > 0f) {
            rt.spawnCarry += spec.emissionRate() * dt;
            int c = (int) Math.floor(rt.spawnCarry);
            if (c > 0) { rt.spawnCarry -= c; toSpawn += c; }
        }

        Vector3f localOrigin = rt.localToEffectMatrix.getTranslation(new Vector3f());
        Vec3 worldOrigin = pose.transformPosition(new Vec3(localOrigin.x, localOrigin.y, localOrigin.z));

        int spawnCount = Math.min(toSpawn, Math.max(0, 512 - rt.particles.size()));
        for (int s = 0; s < spawnCount; s++) {
            ParticleInstance p = new ParticleInstance();
            p.x = p.prevX = worldOrigin.x;
            p.y = p.prevY = worldOrigin.y;
            p.z = p.prevZ = worldOrigin.z;
            if (spec.renderMode() != RenderMode.MODEL) {
                float theta = rt.rng.fraction() * (float) (Math.PI * 2.0);
                p.vx = (float) Math.cos(theta) * spec.startSpeed() * 0.25f;
                p.vy = spec.startSpeed();
                p.vz = (float) Math.sin(theta) * spec.startSpeed() * 0.25f;
            }
            p.age = 0f;
            p.lifetime = Math.max(0.1f, spec.startLifetimeSeconds());
            p.startWidth = p.width = spec.startSize() * pose.scaleX();
            p.startHeight = p.height = spec.startSize() * pose.scaleX();
            p.startR = p.r = spec.r() * snap.red();
            p.startG = p.g = spec.g() * snap.green();
            p.startB = p.b = spec.b() * snap.blue();
            p.startA = p.a = spec.a() * snap.alpha();
            if (spec.renderMode() == RenderMode.TRAIL || spec.renderMode() == RenderMode.BEAM) {
                p.resetTrail(p.x, p.y, p.z);
            }
            rt.particles.add(p);
        }
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
