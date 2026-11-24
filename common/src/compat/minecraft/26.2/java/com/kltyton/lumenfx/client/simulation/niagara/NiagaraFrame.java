package com.kltyton.lumenfx.client.simulation.niagara;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.component.PrimitiveOwnerVisibility;
import com.kltyton.lumenfx.api.client.parameter.ActorComponentInputs.Velocity;
import com.kltyton.lumenfx.effect.niagara.NiagaraLwcSettings;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraCollectionSnapshot;
import com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/** Numeric owner/global/system constant snapshots, prepared before emitter workers start. */
final class NiagaraFrame {
    static final double TILE_SIZE = com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraPositionParameters.TILE_SIZE;
    final byte[] current = new byte[608], previous = new byte[608];
    private final ByteBuffer words = ByteBuffer.wrap(current).order(ByteOrder.LITTLE_ENDIAN);
    private final ByteBuffer oldWords = ByteBuffer.wrap(previous).order(ByteOrder.LITTLE_ENDIAN);
    private final Matrix4d matrix = new Matrix4d(), inverse = new Matrix4d();
    private final boolean initialOwnerVelocityFromActor;
    private final NiagaraLwcSettings lwc;
    private EffectHandle.Pose previousPose;
    private Vec3 ownerVelocity = Vec3.ZERO;
    private @Nullable Velocity actorVelocity;
    private long attachmentSequence = Long.MIN_VALUE;
    private double attachmentSampleTime = Double.NaN;
    private @Nullable Vec3 attachmentSamplePosition;
    private boolean attachmentMissing;
    boolean attachmentBirthsAllowed = true;
    PrimitiveOwnerVisibility ownerVisibility = PrimitiveOwnerVisibility.UNRESTRICTED;
    @Nullable EffectHandle componentHost;
    private double tileX, tileY, tileZ;
    private float age;
    private int tick;
    private float maximumLodDistance = Float.MAX_VALUE;
    private float scalabilityLodDistance = Float.NaN;
    private int significanceIndex = -1;
    private double lastRenderTime = Double.NaN;
    int systemExecutionState;
    double engineTime = Double.NaN;
    float delta;
    NiagaraCollectionSnapshot collections = NiagaraCollectionSnapshot.EMPTY;
    NiagaraPlatformEnvironment platform = NiagaraPlatformEnvironment.WINDOWS_DEFAULT;
    long platformSequence;
    double shiftX, shiftY, shiftZ;
    List<Integer> gpuRebaseEmitters = List.of();

    NiagaraFrame(boolean initialOwnerVelocityFromActor, NiagaraLwcSettings lwc) {
        this.initialOwnerVelocityFromActor = initialOwnerVelocityFromActor;
        this.lwc = lwc;
    }

    void prepare(double now, double realTime, float delta, EffectHandle.Input input, Vec3 camera, boolean emitting,
                 int emitters, int aliveEmitters, int particles, int systemSeed, boolean canRecacheTile) {
        this.delta = delta;
        engineTime = now;
        System.arraycopy(current, 0, previous, 0, current.length);
        Vec3 position = input.pose().position();
        double nextX = tileX, nextY = tileY, nextZ = tileZ;
        if (lwc.enabled() && (previousPose == null || canRecacheTile)) {
            double actorX = tile(position.x * 100), actorY = tile(position.z * 100), actorZ = tile(position.y * 100);
            if (previousPose == null || recachesAt(tileDistance(actorX, actorY, actorZ))) {
                nextX = actorX; nextY = actorY; nextZ = actorZ;
            }
        }
        shiftX = previousPose == null ? 0 : (tileX - nextX) * TILE_SIZE;
        shiftY = previousPose == null ? 0 : (tileY - nextY) * TILE_SIZE;
        shiftZ = previousPose == null ? 0 : (tileZ - nextZ) * TILE_SIZE;
        tileX = nextX; tileY = nextY; tileZ = nextZ;
        sampleOwnerVelocity(input, now, delta);
        words.putFloat(0, delta); words.putFloat(4, delta); words.putFloat(8, delta > 0 ? 1f / delta : 0);
        words.putFloat(12, (float)now); words.putFloat(16, (float)realTime); words.putInt(20, platform.quality());
        float distance = Float.isNaN(scalabilityLodDistance)
                ? (float)(position.distanceTo(camera) * 100) : scalabilityLodDistance;
        if (Double.isNaN(lastRenderTime)) lastRenderTime = now;
        words.putFloat(32, timeSinceRendered(now)); words.putFloat(36, distance); words.putFloat(40, distance / maximumLodDistance);
        words.putFloat(44, age); words.putInt(48, emitting ? 0 : 1); words.putInt(52, tick);
        words.putInt(56, emitters); words.putInt(60, aliveEmitters); words.putInt(64, significanceIndex); words.putInt(68, systemSeed);
        words.putInt(72, 0); words.putInt(76, 1); words.putFloat(80, 1); words.putInt(84, particles);
        owner(words, input.pose(), ownerVelocity);
        if (previousPose == null) System.arraycopy(current, 0, previous, 0, current.length);
        else if (input.renderTracked() && attachmentMissing && attachmentBirthsAllowed) {
            // A skipped display pose leaves no authored path to interpolate. Resume at the new sample.
            owner(oldWords, input.pose(), ownerVelocity);
        } else if (shiftX != 0 || shiftY != 0 || shiftZ != 0) {
            Vec3 oldVelocity = new Vec3(oldWords.getFloat(512), oldWords.getFloat(520), oldWords.getFloat(516));
            owner(oldWords, previousPose, oldVelocity);
        }
        attachmentMissing = input.renderTracked() && !attachmentBirthsAllowed;
        previousPose = input.pose();
    }

    private void sampleOwnerVelocity(EffectHandle.Input input, double sampleTime, float delta) {
        if (input.renderTracked()) {
            if (!attachmentBirthsAllowed || attachmentSequence == input.sequence() && attachmentSampleTime != sampleTime) {
                ownerVelocity = Vec3.ZERO;
                return;
            }
            // Warmup and ordinary step batches in one host frame can both begin at substep zero.
            if (attachmentSequence == input.sequence()) return;
            Vec3 position = input.pose().position();
            double elapsed = sampleTime - attachmentSampleTime;
            if (attachmentSamplePosition == null) sampleInitialVelocity();
            else ownerVelocity = elapsed > 0 ? position.subtract(attachmentSamplePosition).scale(100 / elapsed) : Vec3.ZERO;
            attachmentSequence = input.sequence();
            attachmentSampleTime = sampleTime;
            attachmentSamplePosition = position;
        } else {
            attachmentSequence = Long.MIN_VALUE;
            attachmentSampleTime = Double.NaN;
            attachmentSamplePosition = null;
            if (delta <= 0) return;
            if (previousPose == null || !initialOwnerVelocityFromActor && Math.abs(age) <= 1.0e-8f) sampleInitialVelocity();
            else ownerVelocity = input.pose().position().subtract(previousPose.position()).scale(100.0 / delta);
        }
    }

    private void sampleInitialVelocity() {
        if (Math.abs(age) <= 1.0e-8f && !initialOwnerVelocityFromActor) ownerVelocity = Vec3.ZERO;
        else if (actorVelocity != null) ownerVelocity = new Vec3(actorVelocity.x(), actorVelocity.z(), actorVelocity.y());
        else ownerVelocity = Vec3.ZERO;
    }

    void finish() { age += delta; tick++; }
    void advanceCacheClock(float age) { this.age = age; tick++; }
    float age() { return age; }
    int tick() { return tick; }
    int requestedExecutionState() { return words.getInt(48); }
    void actorVelocity(@Nullable Velocity value) { actorVelocity = value; }
    void scalabilityLod(float distance, float maximum) {
        scalabilityLodDistance = distance;
        if (!Float.isNaN(distance)) maximumLodDistance = Math.max(1, maximum);
    }
    void cullProxyLod(float maximum) {
        scalabilityLodDistance = maximum * 0.999f;
        maximumLodDistance = maximum;
    }
    void significanceIndex(int value) { significanceIndex = value; }
    void renderedAt(double time) {
        if (Double.isNaN(lastRenderTime) || time > lastRenderTime) lastRenderTime = time;
    }
    boolean renderedSince(double time) { return lastRenderTime > time; }
    void resetRenderTime() { lastRenderTime = Double.NaN; }
    float timeSinceRendered(double time) {
        return Double.isNaN(lastRenderTime) ? 0 : (float)Math.max(0, time - lastRenderTime - platform.lastRenderTimeSafetyBias());
    }
    void resetAge() { age = 0; tick = 0; }
    void timeStep(float engineDelta, int index, int count, float fraction, boolean initializeHistory) {
        words.putFloat(0, engineDelta);
        words.putInt(72, index); words.putInt(76, count); words.putFloat(80, fraction);
        if (initializeHistory) {
            oldWords.putFloat(0, engineDelta);
            oldWords.putInt(72, index); oldWords.putInt(76, count); oldWords.putFloat(80, fraction);
        }
    }
    float timeStepFraction() { return words.getFloat(80); }
    void reset() {
        age = 0;
        tick = 0;
        systemExecutionState = 0;
        previousPose = null;
        ownerVelocity = Vec3.ZERO;
        attachmentSequence = Long.MIN_VALUE;
        attachmentSampleTime = Double.NaN;
        attachmentSamplePosition = null;
        attachmentMissing = false;
    }
    double originX() { return tileX * TILE_SIZE; }
    double originY() { return tileY * TILE_SIZE; }
    double originZ() { return tileZ * TILE_SIZE; }

    boolean requiresTileReset(Vec3 position) {
        if (!lwc.enabled() || previousPose == null || lwc.updateMode() == NiagaraLwcSettings.UpdateMode.REBASE) return false;
        float distance = tileDistance(tile(position.x * 100), tile(position.z * 100), tile(position.y * 100));
        return recachesAt(distance) && lwc.resetsOnRecache(distance);
    }

    private float tileDistance(double x, double y, double z) {
        return (float)Math.max(Math.abs(tileX - x), Math.max(Math.abs(tileY - y), Math.abs(tileZ - z)));
    }

    private boolean recachesAt(float distance) {
        float threshold = platform.lwcTileRecache();
        return threshold > 0 && distance >= threshold;
    }

    private static double tile(double position) { return com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraPositionParameters.tile(position); }

    private void owner(ByteBuffer target, EffectHandle.Pose pose, Vec3 velocity) {
        float px = (float)(pose.position().x * 100 - originX());
        float py = (float)(pose.position().z * 100 - originY());
        float pz = (float)(pose.position().y * 100 - originZ());
        Quaternionf rotation = new Quaternionf(-pose.x(), -pose.z(), -pose.y(), pose.w());
        NiagaraComponentMatrices.source(pose, originX(), originY(), originZ(), true, matrix);
        writeMatrix(target, 96, matrix);
        NiagaraComponentMatrices.inverse(matrix, inverse); writeMatrix(target, 160, inverse);
        matrix.transpose(); writeMatrix(target, 224, matrix); matrix.transpose();
        inverse.transpose(); writeMatrix(target, 288, inverse);
        NiagaraComponentMatrices.source(pose, originX(), originY(), originZ(), false, matrix); writeMatrix(target, 352, matrix);
        NiagaraComponentMatrices.inverse(matrix, inverse); writeMatrix(target, 416, inverse);
        target.putFloat(480, rotation.x); target.putFloat(484, rotation.y); target.putFloat(488, rotation.z); target.putFloat(492, rotation.w);
        target.putFloat(496, px); target.putFloat(500, py); target.putFloat(504, pz);
        target.putFloat(512, (float)velocity.x); target.putFloat(516, (float)velocity.z); target.putFloat(520, (float)velocity.y);
        for (int axis = 0; axis < 3; axis++) for (int component = 0; component < 3; component++)
            target.putFloat(528 + axis * 16 + component * 4, (float)matrix.get(axis, component));
        target.putFloat(576, pose.scaleX()); target.putFloat(580, pose.scaleZ()); target.putFloat(584, pose.scaleY());
        target.putFloat(592, (float)tileX); target.putFloat(596, (float)tileY); target.putFloat(600, (float)tileZ);
        target.putFloat(604, (float)TILE_SIZE);
    }

    private void writeMatrix(ByteBuffer target, int offset, Matrix4d value) {
        for (int i = 0; i < 16; i++) target.putFloat(offset + i * 4, (float)value.get(i / 4, i % 4));
    }
}
