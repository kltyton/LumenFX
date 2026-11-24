package com.lowdragmc.lowdraglib2.utils;

import net.minecraft.core.BlockPos;
import org.joml.Vector3f;

public final class Vector3fHelper {
    private Vector3fHelper() { }

    public static float min(Vector3f v) {
        return Math.min(v.x, Math.min(v.y, v.z));
    }

    public static float max(Vector3f v) {
        return Math.max(v.x, Math.max(v.y, v.z));
    }

    public static Vector3f rotateYXY(Vector3f vec, Vector3f rotation) {
        return new Vector3f(vec).rotateY(rotation.y).rotateX(rotation.x).rotateY(rotation.z);
    }

    public static boolean isZero(Vector3f v) {
        return v.x == 0f && v.y == 0f && v.z == 0f;
    }

    public static BlockPos toBlockPos(Vector3f v) {
        return BlockPos.containing(v.x, v.y, v.z);
    }

    public static Vector3f project(Vector3f a, Vector3f b) {
        float lenSq = b.lengthSquared();
        if (lenSq == 0f) return new Vector3f();
        return new Vector3f(b).mul(a.dot(b) / lenSq);
    }

    public static Vector3f closestPointOnLine(Vector3f point, Vector3f a, Vector3f b, Vector3f out) {
        Vector3f ab = new Vector3f(b).sub(a);
        float lenSq = ab.lengthSquared();
        if (lenSq == 0f) return out.set(a);
        float t = Math.max(0f, Math.min(1f, new Vector3f(point).sub(a).dot(ab) / lenSq));
        return out.set(a).fma(t, ab);
    }

    public static float signedAngle(Vector3f from, Vector3f to, Vector3f axis) {
        float angle = from.angle(to);
        Vector3f cross = new Vector3f(from).cross(to);
        return cross.dot(axis) < 0f ? -angle : angle;
    }
}
