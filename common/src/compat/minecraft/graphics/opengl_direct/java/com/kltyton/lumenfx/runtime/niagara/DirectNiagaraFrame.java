package com.kltyton.lumenfx.runtime.niagara;

import com.kltyton.lumenfx.api.client.EffectHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.joml.Quaternionf;

/**
 * Exact 608-byte Niagara owner/global constant block matching 26.2 {@code NiagaraFrame}.
 * Converts Minecraft {@code (X, Y, Z)} blocks to Unreal {@code (X, Z, Y)} centimetres so
 * {@code SpawnPerUnit}, {@code Engine.Owner.Position}, and {@code Engine.Owner.Velocity} evaluate identically.
 */
public final class DirectNiagaraFrame {
    public static final double TILE_SIZE = 2097152.0;

    public final byte[] current = new byte[608];
    public final byte[] previous = new byte[608];
    private final ByteBuffer words = ByteBuffer.wrap(current).order(ByteOrder.LITTLE_ENDIAN);
    private final Matrix4d mat = new Matrix4d();
    private final Matrix4d inv = new Matrix4d();
    private EffectHandle.Pose previousPose;
    private Vec3 ownerVelocity = Vec3.ZERO;
    private float age;
    private int tick;

    public void prepare(float dt, EffectHandle.Pose pose, boolean emitting,
                        int emitterCount, int aliveEmitters, int totalParticles, int systemSeed) {
        System.arraycopy(current, 0, previous, 0, current.length);
        if (previousPose == null || dt <= 0f) {
            ownerVelocity = Vec3.ZERO;
        } else {
            ownerVelocity = pose.position().subtract(previousPose.position()).scale(100.0 / dt);
        }

        words.putFloat(0, dt);
        words.putFloat(4, dt);
        words.putFloat(8, dt > 0f ? 1f / dt : 0f);
        words.putFloat(12, age);
        words.putFloat(16, age);
        words.putInt(20, 3); // high quality level
        words.putFloat(32, 0f);
        words.putFloat(36, 0f);
        words.putFloat(40, 0f);
        words.putFloat(44, age);
        words.putInt(48, emitting ? 0 : 1);
        words.putInt(52, tick);
        words.putInt(56, emitterCount);
        words.putInt(60, aliveEmitters);
        words.putInt(64, 0);
        words.putInt(68, systemSeed);
        words.putInt(72, 0);
        words.putInt(76, 1);
        words.putFloat(80, 1f);
        words.putInt(84, totalParticles);

        writeOwner(words, pose, ownerVelocity);
        if (previousPose == null) {
            System.arraycopy(current, 0, previous, 0, current.length);
        }
        previousPose = pose;
    }

    public void finish(float dt) {
        age += dt;
        tick++;
    }

    public float age() { return age; }
    public int tick() { return tick; }
    public Vec3 ownerVelocityUe() { return ownerVelocity; }

    private void writeOwner(ByteBuffer target, EffectHandle.Pose pose, Vec3 velMcCmPerSec) {
        float px = (float) (pose.position().x * 100.0);
        float py = (float) (pose.position().z * 100.0);
        float pz = (float) (pose.position().y * 100.0);
        Quaternionf rot = new Quaternionf(-pose.x(), -pose.z(), -pose.y(), pose.w()).normalize();

        // Scale-included matrix (offset 96)
        buildSourceMatrix(px, py, pz, rot, pose.scaleX(), pose.scaleZ(), pose.scaleY(), mat);
        writeMatrix(target, 96, mat);
        mat.invert(inv);
        writeMatrix(target, 160, inv);
        mat.transpose(); writeMatrix(target, 224, mat); mat.transpose();
        inv.transpose(); writeMatrix(target, 288, inv);

        // Unscaled rotation+translation matrix (offset 352)
        buildSourceMatrix(px, py, pz, rot, 1f, 1f, 1f, mat);
        writeMatrix(target, 352, mat);
        mat.invert(inv);
        writeMatrix(target, 416, inv);

        target.putFloat(480, rot.x);
        target.putFloat(484, rot.y);
        target.putFloat(488, rot.z);
        target.putFloat(492, rot.w);
        target.putFloat(496, px);
        target.putFloat(500, py);
        target.putFloat(504, pz);
        target.putFloat(512, (float) velMcCmPerSec.x);
        target.putFloat(516, (float) velMcCmPerSec.z);
        target.putFloat(520, (float) velMcCmPerSec.y);
        for (int axis = 0; axis < 3; axis++) {
            for (int comp = 0; comp < 3; comp++) {
                target.putFloat(528 + axis * 16 + comp * 4, (float) mat.get(axis, comp));
            }
        }
        target.putFloat(576, pose.scaleX());
        target.putFloat(580, pose.scaleZ());
        target.putFloat(584, pose.scaleY());
        target.putFloat(592, 0f);
        target.putFloat(596, 0f);
        target.putFloat(600, 0f);
        target.putFloat(604, (float) TILE_SIZE);
    }

    private static void buildSourceMatrix(float tx, float ty, float tz, Quaternionf q,
                                          float sx, float sy, float sz, Matrix4d out) {
        out.identity()
                .translate(tx, ty, tz)
                .rotate(new Quaterniond(q.x, q.y, q.z, q.w))
                .scale(sx, sy, sz);
    }

    private static void writeMatrix(ByteBuffer target, int offset, Matrix4d m) {
        for (int i = 0; i < 16; i++) {
            target.putFloat(offset + i * 4, (float) m.get(i / 4, i % 4));
        }
    }
}
