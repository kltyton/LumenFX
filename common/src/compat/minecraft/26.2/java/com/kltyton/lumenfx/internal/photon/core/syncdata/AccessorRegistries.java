package com.kltyton.lumenfx.internal.photon.core.syncdata;

import com.kltyton.lumenfx.internal.photon.core.editor.resource.IResourcePath;
import com.kltyton.lumenfx.internal.photon.core.math.HDRColor;
import com.kltyton.lumenfx.internal.photon.core.math.Range;
import com.kltyton.lumenfx.internal.photon.core.syncdata.accessor.direct.CustomDirectAccessor;
import com.kltyton.lumenfx.internal.photon.core.utils.LDLibExtraCodecs;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public final class AccessorRegistries {
    private record Entry(int priority, CustomDirectAccessor<?> accessor) { }

    private static final List<Entry> ACCESSORS = new CopyOnWriteArrayList<>();

    static {
        registerCodec(HDRColor.class, HDRColor.CODEC, 500);
        registerCodec(Range.class, Range.CODEC, 500);
        registerCodec(IResourcePath.class, IResourcePath.CODEC, 500);
        registerCodec(Identifier.class, Identifier.CODEC, 500);
        registerCodec(UUID.class, LDLibExtraCodecs.UUID, 500);
        registerCodec(Number.class, LDLibExtraCodecs.NUMBER, 500);
        registerCodec(BlockPos.class, BlockPos.CODEC, 500);
        registerCodec(Vector2f.class, LDLibExtraCodecs.VECTOR2F, 500);
        registerCodec(Vector2i.class, LDLibExtraCodecs.VECTOR2I, 500);
        registerCodec(Vector3f.class, ExtraCodecs.VECTOR3F.xmap(Vector3f::new, v -> v), 500);
        registerCodec(Vector3i.class, LDLibExtraCodecs.VECTOR3I, 500);
        registerCodec(Vector4f.class, ExtraCodecs.VECTOR4F.xmap(Vector4f::new, v -> v), 500);
        registerCodec(Quaternionf.class, ExtraCodecs.QUATERNIONF.xmap(Quaternionf::new, q -> q), 500);
    }

    private AccessorRegistries() { }

    private static <T> void registerCodec(Class<T> type, Codec<T> codec, int priority) {
        registerAccessor(CustomDirectAccessor.builder(type).codec(codec).build(), priority);
    }

    public static void registerAccessor(CustomDirectAccessor<?> accessor, int priority) {
        ACCESSORS.add(new Entry(priority, accessor));
        ACCESSORS.sort(Comparator.comparingInt(Entry::priority).reversed());
    }

    public static void registerAccessor(CustomDirectAccessor<?> accessor) {
        registerAccessor(accessor, 0);
    }

    @SuppressWarnings("unchecked")
    public static <T> @Nullable Codec<T> findCodec(Class<?> type) {
        for (var entry : ACCESSORS) {
            if (entry.accessor.test(type) && entry.accessor.getCodec() != null) {
                return (Codec<T>) entry.accessor.getCodec();
            }
        }
        return null;
    }
}
