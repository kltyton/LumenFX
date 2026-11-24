package com.kltyton.lumenfx.client.render.motion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Immutable published frame: owner/submission streams plus finalized per-Draw run metadata. */
public final class MotionSnapshot {
    private static final MotionSnapshot EMPTY =
            new MotionSnapshot(-1L, null, null, null, List.of(), List.of());

    private final long frameId;
    private final @Nullable Object worldIdentity;
    private final @Nullable Vec3 cameraWorld;
    private final @Nullable Vec3 previousCameraWorld;
    private final List<OwnerMotion> owners;
    private final Map<OwnerKey, OwnerMotion> byOwner;
    private final List<DrawMotion> draws;
    private final Map<StagedVertexBuffer.Draw, DrawMotion> byDraw;

    MotionSnapshot(
            long frameId,
            @Nullable Object worldIdentity,
            @Nullable Vec3 cameraWorld,
            @Nullable Vec3 previousCameraWorld,
            List<OwnerMotion> owners,
            List<DrawMotion> draws) {
        this.frameId = frameId;
        this.worldIdentity = worldIdentity;
        this.cameraWorld = cameraWorld;
        this.previousCameraWorld = previousCameraWorld;
        this.owners = owners;
        Map<OwnerKey, OwnerMotion> ownerLookup = new LinkedHashMap<>(owners.size());
        for (OwnerMotion owner : owners) {
            ownerLookup.put(owner.owner(), owner);
        }
        this.byOwner = Map.copyOf(ownerLookup);
        this.draws = draws;
        Map<StagedVertexBuffer.Draw, DrawMotion> drawLookup = new LinkedHashMap<>(draws.size());
        for (DrawMotion draw : draws) {
            drawLookup.put(draw.draw(), draw);
        }
        this.byDraw = Map.copyOf(drawLookup);
    }

    static MotionSnapshot empty() {
        return EMPTY;
    }

    public long frameId() {
        return this.frameId;
    }

    public @Nullable Object worldIdentity() {
        return this.worldIdentity;
    }

    public @Nullable Vec3 cameraWorld() {
        return this.cameraWorld;
    }

    public boolean hasCamera() {
        return this.cameraWorld != null;
    }

    public @Nullable Vec3 previousCameraWorld() {
        return this.previousCameraWorld;
    }

    public boolean hasPreviousCamera() {
        return this.previousCameraWorld != null;
    }

    public List<OwnerMotion> owners() {
        return this.owners;
    }

    public @Nullable OwnerMotion owner(OwnerKey key) {
        return this.byOwner.get(key);
    }

    public List<DrawMotion> draws() {
        return this.draws;
    }

    /** Per-Draw lookup for the original index buffers / ExecuteInfo payload plan. */
    public @Nullable DrawMotion draw(StagedVertexBuffer.Draw draw) {
        return this.byDraw.get(draw);
    }

    public boolean isValid() {
        return MotionHistory.INSTANCE.isPublished(this);
    }
}
