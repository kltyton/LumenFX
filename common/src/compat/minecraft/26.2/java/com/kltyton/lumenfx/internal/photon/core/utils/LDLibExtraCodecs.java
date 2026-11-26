package com.kltyton.lumenfx.internal.photon.core.utils;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.PrimitiveCodec;
import net.minecraft.nbt.Tag;
import net.minecraft.util.ExtraCodecs;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3i;

import java.util.List;
import java.util.UUID;

public final class LDLibExtraCodecs {
    public static final String NULL_STRING = "empty";

    public static final Codec<UUID> UUID = Codec.STRING.comapFlatMap(
            s -> {
                try { return DataResult.success(java.util.UUID.fromString(s)); }
                catch (Exception e) { return DataResult.error(() -> "Invalid UUID: " + s); }
            },
            java.util.UUID::toString
    );

    public static final Codec<Tag> TAG = ExtraCodecs.NBT;

    public static final PrimitiveCodec<Character> CHAR = new PrimitiveCodec<>() {
        @Override
        public <T> DataResult<Character> read(DynamicOps<T> ops, T input) {
            return ops.getStringValue(input).flatMap(s -> s.length() == 1
                    ? DataResult.success(s.charAt(0))
                    : DataResult.error(() -> "Expected 1-char string"));
        }

        @Override
        public <T> T write(DynamicOps<T> ops, Character value) {
            return ops.createString(String.valueOf(value));
        }
    };

    public static final Codec<Number> NUMBER = new PrimitiveCodec<>() {
        @Override
        public <T> DataResult<Number> read(DynamicOps<T> ops, T input) {
            return ops.getNumberValue(input);
        }

        @Override
        public <T> T write(DynamicOps<T> ops, Number value) {
            return ops.createNumeric(value);
        }
    };

    public static final Codec<Vector3i> VECTOR3I = Codec.INT.listOf().comapFlatMap(
            list -> list.size() >= 3
                    ? DataResult.success(new Vector3i(list.get(0), list.get(1), list.get(2)))
                    : DataResult.error(() -> "Expected 3 ints"),
            v -> List.of(v.x, v.y, v.z)
    );

    public static final Codec<Vector2f> VECTOR2F = Codec.FLOAT.listOf().comapFlatMap(
            list -> list.size() >= 2
                    ? DataResult.success(new Vector2f(list.get(0), list.get(1)))
                    : DataResult.error(() -> "Expected 2 floats"),
            v -> List.of(v.x, v.y)
    );

    public static final Codec<Vector2i> VECTOR2I = Codec.INT.listOf().comapFlatMap(
            list -> list.size() >= 2
                    ? DataResult.success(new Vector2i(list.get(0), list.get(1)))
                    : DataResult.error(() -> "Expected 2 ints"),
            v -> List.of(v.x, v.y)
    );

    private LDLibExtraCodecs() { }

    @SuppressWarnings("unchecked")
    public static <T> MapCodec<T> errorDecoder() {
        return (MapCodec<T>) MapCodec.unit(() -> { throw new IllegalStateException("Unsupported decode"); });
    }

    public static <T> T createStringNull(DynamicOps<T> ops) {
        return ops.createString(NULL_STRING);
    }

    public static <T> boolean isEmptyOrStringNull(DynamicOps<T> ops, T input) {
        if (input == null) return true;
        var str = ops.getStringValue(input).result();
        return str.isPresent() && (str.get().isEmpty() || NULL_STRING.equals(str.get()));
    }

    @SafeVarargs
    public static <T> Codec<T> compat(Codec<T> primary, Codec<T>... fallbacks) {
        Codec<T> combined = primary;
        for (Codec<T> fallback : fallbacks) {
            combined = Codec.withAlternative(combined, fallback);
        }
        return combined;
    }

    public static <T extends Enum<T>> Codec<T> enumCodec(Class<T> enumClass, T fallback) {
        return Codec.STRING.xmap(
                name -> {
                    for (T constant : enumClass.getEnumConstants()) {
                        if (constant.name().equalsIgnoreCase(name)) return constant;
                    }
                    return fallback;
                },
                Enum::name
        );
    }
}
