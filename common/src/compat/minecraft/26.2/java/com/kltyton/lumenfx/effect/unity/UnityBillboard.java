package com.kltyton.lumenfx.effect.unity;

import com.kltyton.lumenfx.effect.curve.ScalarFunction;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleRendererSetting.Mode;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Authored facing and particle draw order for a Shuriken billboard renderer. */
public record UnityBillboard(Mode mode, Alignment alignment, Order order, float pivotX, float pivotY,
                             float lengthScale, float velocityScale, boolean freeformStretching, boolean rotateWithStretch,
                             double flipX, double flipY, float cameraVelocityScale,
                             List<ScalarFunction> startRotation, List<ScalarFunction> angularVelocity, boolean allowRoll,
                             @Nullable ViewportSize sizeLimits) {
    public enum Alignment { VIEW, WORLD, LOCAL, FACING, VELOCITY }
    public enum Order {
        NONE, DISTANCE, OLDEST_IN_FRONT, YOUNGEST_IN_FRONT, DEPTH, DISTANCE_REVERSE, DEPTH_REVERSE;

        public boolean usesCameraDistance() { return this == DISTANCE || this == DISTANCE_REVERSE; }
    }

    /** Both limits are fractions of the viewport. */
    public record ViewportSize(float minimum, float maximum) {
        public ViewportSize {
            if (!(minimum >= 0 && maximum >= 0) || !Float.isFinite(minimum) || !Float.isFinite(maximum)) {
                throw new IllegalArgumentException("Invalid Unity billboard viewport size limits");
            }
        }
    }

    public UnityBillboard(Mode mode, Alignment alignment, Order order, float pivotX, float pivotY, float lengthScale,
                          float velocityScale, boolean freeformStretching, boolean rotateWithStretch,
                          double flipX, double flipY, float cameraVelocityScale,
                          List<ScalarFunction> startRotation, List<ScalarFunction> angularVelocity) {
        this(mode, alignment, order, pivotX, pivotY, lengthScale, velocityScale, freeformStretching, rotateWithStretch,
                flipX, flipY, cameraVelocityScale, startRotation, angularVelocity, true, null);
    }

    public UnityBillboard(Mode mode, Alignment alignment, Order order, float pivotX, float pivotY, float lengthScale,
                          float velocityScale, boolean freeformStretching, boolean rotateWithStretch) {
        this(mode, alignment, order, pivotX, pivotY, lengthScale, velocityScale, freeformStretching, rotateWithStretch,
                0, 0, 0, List.of(), List.of());
    }

    public UnityBillboard(Mode mode, Alignment alignment, Order order, float pivotX, float pivotY, float lengthScale,
                          float velocityScale, boolean freeformStretching, boolean rotateWithStretch,
                          double flipX, double flipY, float cameraVelocityScale) {
        this(mode, alignment, order, pivotX, pivotY, lengthScale, velocityScale, freeformStretching, rotateWithStretch,
                flipX, flipY, cameraVelocityScale, List.of(), List.of());
    }

    public UnityBillboard {
        startRotation = List.copyOf(startRotation);
        angularVelocity = List.copyOf(angularVelocity);
        if (!startRotation.isEmpty() && startRotation.size() != 2
                || !angularVelocity.isEmpty() && angularVelocity.size() != 2)
            throw new IllegalArgumentException("Expected two X/Y rotation curves for Unity billboard");
        if (mode != Mode.Billboard && mode != Mode.Horizontal && mode != Mode.VerticalBillboard && mode != Mode.StretchedBillboard)
            throw new IllegalArgumentException("Invalid Unity billboard mode");
        Objects.requireNonNull(alignment);
        Objects.requireNonNull(order);
        if (!Float.isFinite(pivotX) || !Float.isFinite(pivotY)) throw new IllegalArgumentException("Invalid Unity billboard pivot");
        if (!Float.isFinite(lengthScale) || lengthScale < 0 || !Float.isFinite(velocityScale) || velocityScale < 0
                || !Float.isFinite(cameraVelocityScale))
            throw new IllegalArgumentException("Invalid Unity billboard stretch");
        if (!(flipX >= 0 && flipX <= 1 && flipY >= 0 && flipY <= 1))
            throw new IllegalArgumentException("Invalid Unity billboard flip probabilities");
    }

    public boolean usesCameraVelocity() {
        return mode == Mode.StretchedBillboard && cameraVelocityScale != 0;
    }
}
