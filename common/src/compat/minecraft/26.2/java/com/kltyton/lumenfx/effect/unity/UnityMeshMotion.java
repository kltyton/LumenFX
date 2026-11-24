package com.kltyton.lumenfx.effect.unity;

import com.kltyton.lumenfx.effect.curve.ScalarFunction;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;

/** Shuriken mesh attributes sampled on the same particles as any secondary trail renderer. */
public record UnityMeshMotion(ScalarFunction depth, List<ScalarFunction> startRotation,
                              List<ScalarFunction> angularVelocity,
                              UnityBillboard.Alignment alignment, UnityBillboard.Order order, boolean forwardToUp,
                              float scaleU, float scaleV, float offsetU, float offsetV, Vec3 flip, List<Float> weights) {
    public UnityMeshMotion(ScalarFunction depth, List<ScalarFunction> startRotation, List<ScalarFunction> angularVelocity,
                           UnityBillboard.Alignment alignment, UnityBillboard.Order order, boolean forwardToUp,
                           float scaleU, float scaleV, float offsetU, float offsetV, Vec3 flip) {
        this(depth, startRotation, angularVelocity, alignment, order, forwardToUp, scaleU, scaleV, offsetU, offsetV, flip, List.of());
    }

    public UnityMeshMotion(ScalarFunction depth, List<ScalarFunction> startRotation, List<ScalarFunction> angularVelocity,
                           UnityBillboard.Alignment alignment, UnityBillboard.Order order, boolean forwardToUp,
                           float scaleU, float scaleV, float offsetU, float offsetV) {
        this(depth, startRotation, angularVelocity, alignment, order, forwardToUp, scaleU, scaleV, offsetU, offsetV, Vec3.ZERO);
    }

    public UnityMeshMotion {
        Objects.requireNonNull(depth);
        Objects.requireNonNull(alignment);
        Objects.requireNonNull(order);
        startRotation = components(startRotation, 2);
        angularVelocity = components(angularVelocity, 2);
        weights = List.copyOf(weights);
        float totalWeight = 0;
        for (float weight : weights) {
            if (!Float.isFinite(weight) || weight < 0) throw new IllegalArgumentException("Invalid Unity mesh weight");
            totalWeight += weight;
        }
        if (!Float.isFinite(totalWeight)) throw new IllegalArgumentException("Unity mesh weight total exceeds float range");
        if (!Float.isFinite(scaleU) || !Float.isFinite(scaleV) || !Float.isFinite(offsetU) || !Float.isFinite(offsetV))
            throw new IllegalArgumentException("Invalid Unity mesh UV transform");
        if (!(flip.x >= 0 && flip.x <= 1 && flip.y >= 0 && flip.y <= 1 && flip.z >= 0 && flip.z <= 1))
            throw new IllegalArgumentException("Invalid Unity mesh flip probabilities");
    }

    private static List<ScalarFunction> components(List<ScalarFunction> values, int count) {
        if (!values.isEmpty() && values.size() != count) throw new IllegalArgumentException("Invalid Unity mesh curve count");
        return List.copyOf(values);
    }
}
