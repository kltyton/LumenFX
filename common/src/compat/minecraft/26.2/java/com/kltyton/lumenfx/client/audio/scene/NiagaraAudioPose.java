package com.kltyton.lumenfx.client.audio.scene;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniondc;

/** Converts a source-space orientation to Unreal pitch, yaw and roll. */
public final class NiagaraAudioPose {
    private NiagaraAudioPose() { }

    public static Vec3 rotator(Quaterniondc source) {
        double x = source.x(), y = source.y(), z = source.z(), w = source.w();
        double pitch = -Math.asin(Math.clamp(2 * (w * y - z * x), -1, 1));
        double yaw = Math.atan2(2 * (w * z + x * y), 1 - 2 * (y * y + z * z));
        double roll = -Math.atan2(2 * (w * x + y * z), 1 - 2 * (x * x + y * y));
        return new Vec3(Math.toDegrees(pitch), Math.toDegrees(yaw), Math.toDegrees(roll));
    }
}
