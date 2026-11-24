package com.kltyton.lumenfx.client.simulation.niagara.interfaces;

import com.kltyton.lumenfx.api.client.parameter.NiagaraCollisionWorld;
import com.kltyton.lumenfx.api.client.parameter.NiagaraCollisionWorld.Hit;
import java.util.ArrayList;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Per-instance collision batch. Async requests submitted in one step are visible in the next. */
public final class NiagaraCollisionState {
    public static final String SYNC = "PerformCollisionQuerySyncCPU";
    public static final String ASYNC = "PerformCollisionQueryAsyncCPU";
    private static final double MINIMUM_ASYNC_LENGTH_SQUARED = 1e-8 / 10_000;
    private static final Hit[] NO_RESULTS = new Hit[0];

    private record Trace(Vec3 start, Vec3 end, int channel, boolean complex) { }

    private final ArrayList<Trace> requests = new ArrayList<>();
    private @Nullable Hit[] visible = NO_RESULTS;
    private @Nullable Hit[] pending = NO_RESULTS;
    private @Nullable NiagaraCollisionWorld world;
    private @Nullable Thread owner;
    private boolean enabled;
    private double originX, originY, originZ;

    public void preTick(@Nullable NiagaraCollisionWorld world, boolean enabled,
                        double originX, double originY, double originZ) {
        this.world = world;
        this.enabled = enabled;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        owner = Thread.currentThread();
        requests.clear();
        visible = pending;
        pending = NO_RESULTS;
    }

    public void postSimulate() {
        requireOwner();
        if (requests.isEmpty()) return;
        var resolved = new Hit[requests.size()];
        try {
            if (world != null) for (int i = 0; i < resolved.length; i++) {
                Trace request = requests.get(i);
                resolved[i] = world.trace(request.start(), request.end(), request.channel(), request.complex());
            }
            pending = resolved;
        } finally {
            requests.clear();
        }
    }

    public int submit(Vec3 start, Vec3 end, int channel, boolean complex) {
        if (!enabled || start.distanceToSqr(end) <= MINIMUM_ASYNC_LENGTH_SQUARED) return 0;
        requests.add(new Trace(start, end, channel, complex));
        return requests.size();
    }

    public @Nullable Hit result(int query) {
        return query > 0 && query <= visible.length ? visible[query - 1] : null;
    }

    public @Nullable Hit trace(Vec3 start, Vec3 end, int channel, boolean complex) {
        requireOwner();
        return enabled && world != null ? world.trace(start, end, channel, complex) : null;
    }

    public Vec3 worldPosition(float x, float y, float z) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
            throw new IllegalArgumentException("Niagara collision trace requires finite positions");
        return new Vec3((originX + x) / 100, (originZ + z) / 100, (originY + y) / 100);
    }

    public double sourceX(Vec3 position) { return position.x * 100 - originX; }
    public double sourceY(Vec3 position) { return position.z * 100 - originY; }
    public double sourceZ(Vec3 position) { return position.y * 100 - originZ; }

    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Niagara collision world accessed outside its client thread");
    }
}
