/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon;

import com.lowdragmc.photon.client.render.FXCompositeMode;
import java.util.Objects;
import java.util.function.Supplier;

/** Client-side particle configuration shared by the Photon runtime modules across all loaders. */
public final class PhotonConfig {
    public static final int MAX_SHADOWED_LIGHTS = 32;
    public static final PhotonConfig INSTANCE = new PhotonConfig();

    public static final class Value<T> implements Supplier<T> {
        private volatile Supplier<T> backing;

        private Value(T defaultValue) {
            this.backing = () -> defaultValue;
        }

        @Override
        public T get() {
            return backing.get();
        }

        public void bind(Supplier<T> supplier) {
            this.backing = Objects.requireNonNull(supplier, "supplier");
        }
    }

    public final Value<FXCompositeMode> fxCompositeMode = new Value<>(FXCompositeMode.LATE);
    public final Value<Boolean> collisionParallel = new Value<>(true);
    public final Value<Integer> collisionSectionCacheLimit = new Value<>(4096);
    public final Value<Integer> lightShadowedLights = new Value<>(8);
    public final Value<Double> lightVoxelBudgetMs = new Value<>(2.0);

    private PhotonConfig() {
    }
}
