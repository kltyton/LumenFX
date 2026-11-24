package com.kltyton.lumenfx.effect.unity;

import com.kltyton.lumenfx.effect.curve.ColorFunction;
import com.kltyton.lumenfx.effect.curve.ScalarFunction;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Two Shuriken streams, each carrying a four-component vector or a sampled color. */
public record UnityCustomData(@Nullable Stream custom1, @Nullable Stream custom2) {
    public record Stream(List<ScalarFunction> components, @Nullable ColorFunction color, int width) {
        public Stream {
            components = List.copyOf(components);
            if (width < 1 || width > 4 || (color == null ? components.size() != 4 : !components.isEmpty()))
                throw new IllegalArgumentException("Invalid Unity custom stream");
        }

        public double sample(int component, double age, double random) {
            if (component >= width) return 0;
            return color == null ? components.get(component).sample(age, random)
                    : color.sampleChannel(component, age, random, random);
        }
    }
}
