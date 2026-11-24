package com.lowdragmc.photon.client.render;

import java.util.Comparator;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

/** Orders batches within a render phase by authored priority, then draws farthest first. */
public final class ParticleDrawOrder {
    private ParticleDrawOrder() {
    }

    public static <T> Comparator<T> backToFront(ToIntFunction<T> priority, ToDoubleFunction<T> distance) {
        return Comparator.comparingInt(priority).thenComparing(Comparator.comparingDouble(distance).reversed());
    }
}
