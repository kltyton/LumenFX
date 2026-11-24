package com.kltyton.lumenfx.client.compat.kbr;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Narrow MethodHandle bridge to KBR's optional {@code BillboardQuadDimensions} state so Sodium's fast
 * {@code QuadParticleRenderState} intrinsic emitter preserves rectangular and oriented Bedrock billboards
 * when Sodium and KBR are co-installed without hard-linking KBR into standalone LumenFX.
 *
 * <p>AT/Mixin against KBR is intentionally avoided because KBR is an optional co-install mod outside
 * LumenFX's compile classpath. Handles are resolved once at class load (never on the render hot path)
 * and linkage failures are logged once instead of being silently hidden.
 */
public final class KbrSodiumBillboardBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger(KbrSodiumBillboardBridge.class);
    private static final String DIMENSIONS_CLASS =
            "com.kltyton.bedrockruntime.client.particle.runtime.render.BillboardQuadDimensions";

    private static final Binding DEFAULT = resolveDefault();

    private KbrSodiumBillboardBridge() {
    }

    public static boolean isAvailable() {
        return DEFAULT.available();
    }

    public static boolean isActive() {
        return DEFAULT.isActive();
    }

    public static float selectNext(float width) {
        return DEFAULT.selectNext(width);
    }

    public static float scaleY(float y, float width) {
        return DEFAULT.scaleY(y, width);
    }

    public static Vector3f applyBasis(Vector3f point, Quaternionfc rotation,
                                      float horizontal, float vertical, float width) {
        return DEFAULT.applyBasis(point, rotation, horizontal, vertical, width);
    }

    /** Computes one billboard corner position using KBR's active height scale and custom basis, if any. */
    public static Vector3f transformCorner(Binding binding, Vector3f scratch, Quaternionfc rotation,
                                           float centerX, float centerY, float centerZ,
                                           float nx, float ny, float width) {
        float scaledY = binding.scaleY(ny, width);
        scratch.set(nx, scaledY, 0.0F).rotate(rotation).mul(width);
        Vector3f point = binding.applyBasis(scratch, rotation, nx, ny, width);
        return point.add(centerX, centerY, centerZ);
    }

    /** Default-binding overload used by the Sodium + KBR billboard mixin on the render hot path. */
    public static Vector3f transformCorner(Vector3f scratch, Quaternionfc rotation,
                                           float centerX, float centerY, float centerZ,
                                           float nx, float ny, float width) {
        return transformCorner(DEFAULT, scratch, rotation, centerX, centerY, centerZ, nx, ny, width);
    }

    /** Binds against a specific {@code BillboardQuadDimensions}-compatible class (used at init and in tests). */
    public static Binding bind(Class<?> dimensionsClass) {
        try {
            var lookup = MethodHandles.publicLookup();
            MethodHandle isActive = lookup.findStatic(dimensionsClass, "isActive",
                    MethodType.methodType(boolean.class));
            MethodHandle selectNext = lookup.findStatic(dimensionsClass, "selectNext",
                    MethodType.methodType(float.class, float.class));
            MethodHandle scaleY = lookup.findStatic(dimensionsClass, "scaleY",
                    MethodType.methodType(float.class, float.class, float.class));
            MethodHandle applyBasis = lookup.findStatic(dimensionsClass, "applyBasis",
                    MethodType.methodType(Vector3f.class, Vector3f.class, Quaternionfc.class,
                            float.class, float.class, float.class));
            return new Binding(isActive, selectNext, scaleY, applyBasis);
        } catch (ReflectiveOperationException error) {
            LOGGER.warn("KBR BillboardQuadDimensions contract mismatch on {}; Sodium billboard bridge disabled",
                    dimensionsClass.getName(), error);
            return Binding.UNAVAILABLE;
        }
    }

    private static Binding resolveDefault() {
        if (!KbrCompat.isPresent()) {
            return Binding.UNAVAILABLE;
        }
        try {
            Class<?> dimensions = Class.forName(DIMENSIONS_CLASS, false,
                    KbrSodiumBillboardBridge.class.getClassLoader());
            return bind(dimensions);
        } catch (ClassNotFoundException | LinkageError error) {
            LOGGER.warn("KBR mod is present but {} could not be linked; Sodium billboard bridge disabled",
                    DIMENSIONS_CLASS, error);
            return Binding.UNAVAILABLE;
        }
    }

    public static final class Binding {
        static final Binding UNAVAILABLE = new Binding(null, null, null, null);

        private final @Nullable MethodHandle isActive;
        private final @Nullable MethodHandle selectNext;
        private final @Nullable MethodHandle scaleY;
        private final @Nullable MethodHandle applyBasis;

        private Binding(@Nullable MethodHandle isActive,
                        @Nullable MethodHandle selectNext,
                        @Nullable MethodHandle scaleY,
                        @Nullable MethodHandle applyBasis) {
            this.isActive = isActive;
            this.selectNext = selectNext;
            this.scaleY = scaleY;
            this.applyBasis = applyBasis;
        }

        public boolean available() {
            return isActive != null;
        }

        public boolean isActive() {
            if (isActive == null) return false;
            try {
                return (boolean) isActive.invokeExact();
            } catch (RuntimeException | Error error) {
                throw error;
            } catch (Throwable throwable) {
                throw new IllegalStateException("KBR BillboardQuadDimensions.isActive failed", throwable);
            }
        }

        public float selectNext(float width) {
            if (selectNext == null) return width;
            try {
                return (float) selectNext.invokeExact(width);
            } catch (RuntimeException | Error error) {
                throw error;
            } catch (Throwable throwable) {
                throw new IllegalStateException("KBR BillboardQuadDimensions.selectNext failed", throwable);
            }
        }

        public float scaleY(float y, float width) {
            if (scaleY == null) return y;
            try {
                return (float) scaleY.invokeExact(y, width);
            } catch (RuntimeException | Error error) {
                throw error;
            } catch (Throwable throwable) {
                throw new IllegalStateException("KBR BillboardQuadDimensions.scaleY failed", throwable);
            }
        }

        public Vector3f applyBasis(Vector3f point, Quaternionfc rotation,
                                   float horizontal, float vertical, float width) {
            if (applyBasis == null) return point;
            try {
                return (Vector3f) applyBasis.invokeExact(point, rotation, horizontal, vertical, width);
            } catch (RuntimeException | Error error) {
                throw error;
            } catch (Throwable throwable) {
                throw new IllegalStateException("KBR BillboardQuadDimensions.applyBasis failed", throwable);
            }
        }
    }
}
