package com.kltyton.lumenfx.effect.unity;

import com.kltyton.lumenfx.effect.curve.ColorFunction;
import com.kltyton.lumenfx.effect.curve.ScalarFunction;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Per-emitter Shuriken modules driven by the simulated particle speed. */
public record UnitySpeedModules(@Nullable Size size, @Nullable Color color, @Nullable Rotation rotation) {
    public record Size(UnitySpeedRange range, List<ScalarFunction> axes) {
        public Size {
            Objects.requireNonNull(range);
            axes = curves(axes);
        }

        public double scale(int axis, double speed, double random) {
            return axes.get(axes.size() == 1 ? 0 : axis).sample(range.progress(speed), random);
        }
    }

    public record Color(UnitySpeedRange range, ColorFunction value) {
        public Color {
            Objects.requireNonNull(range);
            Objects.requireNonNull(value);
        }

        public ColorFunction.Color sample(double speed, double random) {
            return value.sample(range.progress(speed), random);
        }
    }

    public record Rotation(UnitySpeedRange range, List<ScalarFunction> axes) {
        public Rotation {
            Objects.requireNonNull(range);
            axes = curves(axes);
        }
    }

    private static List<ScalarFunction> curves(List<ScalarFunction> axes) {
        if (axes.size() != 1 && axes.size() != 3)
            throw new IllegalArgumentException("Expected a single curve or three Unity speed axes");
        return List.copyOf(axes);
    }
}
