package com.kltyton.lumenfx.api.client;

import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Thread-safe effect handle shared across OpenGL direct targets (1.20.1, 1.21.1, 26.1.2).
 * Callers can publish pose, color, beam endpoint, scalar/vector parameters, and lifecycle state.
 */
public final class EffectHandle implements AutoCloseable {
    public record Pose(Vec3 position, float x, float y, float z, float w, float scale, float scaleY, float scaleZ) {
        public Pose(Vec3 position, float x, float y, float z, float w, float scale) {
            this(position, x, y, z, w, positiveScale(scale), scale, scale);
        }

        public Pose {
            float lenSq = x * x + y * y + z * z + w * w;
            if (!finite(position) || !Float.isFinite(lenSq) || lenSq <= 0f
                    || !Float.isFinite(scale) || !Float.isFinite(scaleY) || !Float.isFinite(scaleZ)) {
                throw new IllegalArgumentException("Invalid effect pose");
            }
            float inv = (float) (1.0 / Math.sqrt(lenSq));
            x *= inv; y *= inv; z *= inv; w *= inv;
        }

        public static Pose at(Vec3 position) {
            return new Pose(position, 0f, 0f, 0f, 1f, 1f);
        }

        public Pose withPosition(Vec3 next) {
            return new Pose(next, x, y, z, w, scale, scaleY, scaleZ);
        }

        public float scaleX() { return scale; }

        public Vec3 transformDirection(Vec3 v) {
            Vector3f out = new Quaternionf(x, y, z, w).transform(
                    (float) (v.x * scale), (float) (v.y * scaleY), (float) (v.z * scaleZ), new Vector3f());
            return new Vec3(out.x, out.y, out.z);
        }

        public Vec3 transformPosition(Vec3 v) {
            return transformDirection(v).add(position);
        }

        private static float positiveScale(float s) {
            if (!Float.isFinite(s) || s <= 0f) throw new IllegalArgumentException("Invalid scale");
            return s;
        }
    }

    public record Snapshot(Pose pose, Vec3 beamEnd, long sequence,
                           float red, float green, float blue, float alpha,
                           Map<String, float[]> parameters) { }

    private final AtomicReference<Snapshot> snapshot;
    private final AtomicReference<NiagaraSimulationCache.Playback> niagaraCache = new AtomicReference<>();
    private final AtomicBoolean beamEndpointSet = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private volatile @Nullable String effectId;

    public EffectHandle(Vec3 position) {
        Objects.requireNonNull(position, "position");
        this.snapshot = new AtomicReference<>(new Snapshot(Pose.at(position), position, 0L, 1f, 1f, 1f, 1f, Map.of()));
    }

    public @Nullable String effectId() { return effectId; }

    public void setEffectId(@Nullable String effectId) { this.effectId = effectId; }

    public Snapshot snapshot() { return snapshot.get(); }

    public void setPose(Pose pose) {
        Objects.requireNonNull(pose, "pose");
        snapshot.updateAndGet(old -> new Snapshot(pose, old.beamEnd(), old.sequence() + 1L,
                old.red(), old.green(), old.blue(), old.alpha(), old.parameters()));
    }

    public void moveTo(Vec3 position) {
        Objects.requireNonNull(position, "position");
        snapshot.updateAndGet(old -> new Snapshot(old.pose().withPosition(position), old.beamEnd(),
                old.sequence() + 1L, old.red(), old.green(), old.blue(), old.alpha(), old.parameters()));
    }

    public void setBeamEnd(Vec3 end) {
        if (!finite(end)) throw new IllegalArgumentException("Non-finite beam endpoint");
        snapshot.updateAndGet(old -> new Snapshot(old.pose(), end, old.sequence(),
                old.red(), old.green(), old.blue(), old.alpha(), old.parameters()));
        beamEndpointSet.set(true);
    }

    public @Nullable Vec3 beamEndOverride() {
        return beamEndpointSet.get() ? snapshot.get().beamEnd() : null;
    }

    public void setColor(float red, float green, float blue, float alpha) {
        if (!Float.isFinite(red) || !Float.isFinite(green) || !Float.isFinite(blue) || !Float.isFinite(alpha)) {
            throw new IllegalArgumentException("Non-finite effect color");
        }
        snapshot.updateAndGet(old -> new Snapshot(old.pose(), old.beamEnd(), old.sequence(),
                red, green, blue, alpha, old.parameters()));
    }

    public void setFloatParameter(String name, float... values) {
        Objects.requireNonNull(name, "name");
        float[] copy = values.clone();
        snapshot.updateAndGet(old -> {
            Map<String, float[]> next = new LinkedHashMap<>(old.parameters());
            next.put(name, copy);
            return new Snapshot(old.pose(), old.beamEnd(), old.sequence(),
                    old.red(), old.green(), old.blue(), old.alpha(), Collections.unmodifiableMap(next));
        });
    }

    public @Nullable NiagaraSimulationCache.Playback niagaraCache() {
        return niagaraCache.get();
    }

    /** Plays a saved Niagara simulation cache from start, or evaluates it at an explicit source age in seconds. */
    public void setNiagaraCache(@Nullable NiagaraSimulationCache cache, @Nullable Float age) {
        if (cache == null && age != null) {
            throw new IllegalArgumentException("A playback age requires a Niagara cache");
        }
        niagaraCache.set(cache == null ? null : new NiagaraSimulationCache.Playback(cache, age));
    }

    public void stopEmitting() { stopping.set(true); }

    public boolean isStopping() { return stopping.get(); }

    public boolean isAlive() { return alive.get(); }

    public void markDead() { alive.set(false); }

    @Override
    public void close() {
        stopping.set(true);
        alive.set(false);
    }

    private static boolean finite(Vec3 v) {
        return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }
}
