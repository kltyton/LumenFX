package com.kltyton.lumenfx.effect.unity;

import org.joml.Quaterniond;

/** Fixed or pose-coupled orientation offset mapping a module's coordinate space into the emitter's simulation space. */
public record UnitySpaceTransform(boolean fixed, Quaterniond rotation) {
    public UnitySpaceTransform {
        if (rotation == null) rotation = new Quaterniond();
        else rotation = new Quaterniond(rotation);
    }

    @Override
    public Quaterniond rotation() {
        return new Quaterniond(rotation);
    }

    public Quaterniond rotation(Quaterniond target) {
        return target.set(rotation);
    }
}
