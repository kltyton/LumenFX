package com.lowdragmc.lowdraglib2.utils;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigSetter;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.syncdata.AccessorRegistries;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib2.syncdata.annotation.ReadOnlyManaged;
import com.lowdragmc.photon.utils.PhotonValueSerializable;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.EndTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Self-contained reflection-based NBT/ValueIO serializer matching LDLib2's PersistedParser wire format. */
public final class PersistedParser {
    private PersistedParser() { }

    @Nullable
    public static String persistedKey(Field field) {
        if (field.isAnnotationPresent(Configurable.class)) {
            var c = field.getAnnotation(Configurable.class);
            if (!c.persisted()) return null;
            return c.key().isEmpty() ? field.getName() : c.key();
        }
        if (field.isAnnotationPresent(Persisted.class)) {
            var p = field.getAnnotation(Persisted.class);
            return p.key().isEmpty() ? field.getName() : p.key();
        }
        return null;
    }

    public static boolean isPersistedFlat(Field field) {
        if (field.isAnnotationPresent(Configurable.class)) {
            var c = field.getAnnotation(Configurable.class);
            return c.subConfigurable() && c.subFlattenPersisted();
        }
        if (field.isAnnotationPresent(Persisted.class)) {
            var p = field.getAnnotation(Persisted.class);
            return p.subPersisted() && p.subFlattenPersisted();
        }
        return false;
    }

    private static boolean isSubPersisted(Field field) {
        if (field.isAnnotationPresent(Configurable.class)) {
            return field.getAnnotation(Configurable.class).subConfigurable();
        }
        if (field.isAnnotationPresent(Persisted.class)) {
            return field.getAnnotation(Persisted.class).subPersisted();
        }
        return false;
    }

    public static <T> Codec<T> createCodec(Supplier<T> creator) {
        return CompoundTag.CODEC.xmap(
                tag -> {
                    T instance = creator.get();
                    deserializeNBT(tag, instance, Platform.getFrozenRegistry());
                    return instance;
                },
                value -> serializeNBT(value, Platform.getFrozenRegistry())
        );
    }

    public static <T> MapCodec<T> createMapCodec(Supplier<T> creator) {
        return createCodec(creator).fieldOf("value");
    }

    public static <T> StreamCodec<ByteBuf, T> createStreamCodec(Supplier<T> creator) {
        return StreamCodec.of(
                (buf, val) -> writeBuff(buf, val),
                buf -> {
                    T inst = creator.get();
                    readBuff(buf, inst);
                    return inst;
                }
        );
    }

    public static void serialize(Object object, ValueOutput output) {
        CompoundTag tag = serializeNBT(object, Platform.getFrozenRegistry());
        for (String key : tag.keySet()) {
            Tag child = tag.get(key);
            if (child != null) output.store(key, ExtraCodecs.NBT, child);
        }
    }

    public static void deserialize(Object object, ValueInput input) {
        CompoundTag tag = new CompoundTag();
        for (Field field : allPersistedFields(object.getClass())) {
            String key = persistedKey(field);
            if (key != null) {
                input.read(key, ExtraCodecs.NBT).ifPresent(t -> tag.put(key, t));
            }
            if (isPersistedFlat(field)) {
                try {
                    field.setAccessible(true);
                    Object sub = field.get(object);
                    if (sub != null) {
                        for (Field sf : allPersistedFields(sub.getClass())) {
                            String sk = persistedKey(sf);
                            if (sk != null && !tag.contains(sk)) {
                                input.read(sk, ExtraCodecs.NBT).ifPresent(t -> tag.put(sk, t));
                            }
                        }
                    }
                } catch (IllegalAccessException ignored) { }
            }
        }
        input.read("_additional", ExtraCodecs.NBT).ifPresent(t -> tag.put("_additional", t));
        deserializeNBT(tag, object, Platform.getFrozenRegistry());
    }

    public static CompoundTag serializeNBT(Object object, HolderLookup.Provider provider) {
        CompoundTag root = new CompoundTag();
        serializeObject(true, root, object.getClass(), object, provider);
        return root;
    }

    public static void deserializeNBT(CompoundTag tag, Object object, HolderLookup.Provider provider) {
        deserializeObject(true, tag, new HashMap<>(), object.getClass(), object, provider);
    }

    public static <T> DataResult<T> serialize(DynamicOps<T> ops, Object object, HolderLookup.Provider provider) {
        CompoundTag tag = serializeNBT(object, provider);
        return DataResult.success(NbtOps.INSTANCE.convertTo(ops, tag));
    }

    public static <T> void deserialize(DynamicOps<T> ops, T data, Object object, HolderLookup.Provider provider) {
        Tag converted = ops.convertTo(NbtOps.INSTANCE, data);
        if (converted instanceof CompoundTag compound) {
            deserializeNBT(compound, object, provider);
        }
    }

    public static void writeBuff(ByteBuf buf, Object object) {
        CompoundTag tag = serializeNBT(object, Platform.getFrozenRegistry());
        new FriendlyByteBuf(buf).writeNbt(tag);
    }

    public static void readBuff(ByteBuf buf, Object object) {
        Tag tag = new FriendlyByteBuf(buf).readNbt();
        if (tag instanceof CompoundTag compound) {
            deserializeNBT(compound, object, Platform.getFrozenRegistry());
        }
    }

    private static List<Field> allPersistedFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        collectFields(clazz, fields);
        return fields;
    }

    private static void collectFields(Class<?> clazz, List<Field> out) {
        if (clazz == null || clazz == Object.class) return;
        collectFields(clazz.getSuperclass(), out);
        for (Field f : clazz.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && persistedKey(f) != null) {
                out.add(f);
            }
        }
    }

    private static void serializeObject(boolean root, CompoundTag out, Class<?> clazz, Object object, HolderLookup.Provider provider) {
        if (clazz == null || clazz == Object.class) return;
        if (root && object instanceof IPersistedSerializable s) s.beforeSerialize();
        serializeObject(false, out, clazz.getSuperclass(), object, provider);
        for (Field field : clazz.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            String key = persistedKey(field);
            if (key == null) continue;
            try {
                field.setAccessible(true);
                Object value = field.get(object);
                if (value == null) continue;
                if (isSubPersisted(field)) {
                    boolean flat = isPersistedFlat(field);
                    CompoundTag subTag = value instanceof PhotonValueSerializable vs
                            ? toValueTag(vs, provider)
                            : serializeNBT(value, provider);
                    if (flat) {
                        for (String sk : subTag.keySet()) {
                            Tag sv = subTag.get(sk);
                            if (sv != null) out.put(sk, sv);
                        }
                    } else {
                        out.put(key, subTag);
                    }
                } else {
                    Tag encoded = encodeValue(value, field.getGenericType(), provider);
                    if (encoded != null) out.put(key, encoded);
                }
            } catch (Exception e) {
                LDLib2.LOGGER.warn("Failed to serialize field {}.{}", clazz.getSimpleName(), field.getName(), e);
            }
        }
        if (root && object instanceof IPersistedSerializable s) {
            Tag additional = s.serializeAdditionalNBT(provider);
            if (additional != null && additional != EndTag.INSTANCE) {
                out.put("_additional", additional);
            }
            s.afterSerialize();
        }
    }

    private static void deserializeObject(boolean root, CompoundTag tag, Map<String, Method> setters,
                                          Class<?> clazz, Object object, HolderLookup.Provider provider) {
        if (clazz == null || clazz == Object.class) return;
        if (root && object instanceof IPersistedSerializable s) s.beforeDeserialize();
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.isAnnotationPresent(ConfigSetter.class)) {
                setters.putIfAbsent(m.getAnnotation(ConfigSetter.class).field(), m);
            }
        }
        deserializeObject(false, tag, setters, clazz.getSuperclass(), object, provider);
        for (Field field : clazz.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            String key = persistedKey(field);
            if (key == null) continue;
            try {
                field.setAccessible(true);
                Tag data = tag.get(key);
                if (isSubPersisted(field)) {
                    Object value = field.get(object);
                    if (value != null) {
                        if (data instanceof CompoundTag subCompound) {
                            if (value instanceof PhotonValueSerializable vs) fromValueTag(vs, subCompound, provider);
                            else deserializeObject(true, subCompound, new HashMap<>(), value.getClass(), value, provider);
                        } else if (isPersistedFlat(field)) {
                            if (value instanceof PhotonValueSerializable vs) fromValueTag(vs, tag, provider);
                            else deserializeObject(true, tag, new HashMap<>(), value.getClass(), value, provider);
                        }
                    }
                } else if (data != null) {
                    Object current = field.get(object);
                    Object decoded = decodeFieldValue(object, field, current, data, provider);
                    if (decoded != null && !Modifier.isFinal(field.getModifiers())) {
                        field.set(object, decoded);
                    }
                    Method setter = setters.get(field.getName());
                    if (setter != null) {
                        setter.setAccessible(true);
                        setter.invoke(object, field.get(object));
                    }
                }
            } catch (Exception e) {
                LDLib2.LOGGER.warn("Failed to deserialize field {}.{}", clazz.getSimpleName(), field.getName(), e);
            }
        }
        if (root && object instanceof IPersistedSerializable s) {
            Tag additional = tag.get("_additional");
            if (additional != null) {
                s.deserializeAdditionalNBT(additional, provider);
            }
            s.afterDeserialize();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static @Nullable Tag encodeValue(Object value, Type genericType, HolderLookup.Provider provider) {
        if (value == null) return null;
        Class<?> cls = value.getClass();
        if (value instanceof Tag t) return t.copy();
        if (value instanceof Boolean b) return ByteTag.valueOf(b);
        if (value instanceof Byte b) return ByteTag.valueOf(b);
        if (value instanceof Short s) return ShortTag.valueOf(s);
        if (value instanceof Integer i) return IntTag.valueOf(i);
        if (value instanceof Long l) return LongTag.valueOf(l);
        if (value instanceof Float f) return FloatTag.valueOf(f);
        if (value instanceof Double d) return DoubleTag.valueOf(d);
        if (value instanceof String s) return StringTag.valueOf(s);
        if (value instanceof Enum<?> e) return StringTag.valueOf(e.name());
        Codec codec = AccessorRegistries.findCodec(cls);
        if (codec != null) {
            return (Tag) codec.encodeStart(NbtOps.INSTANCE, value).result().orElse(null);
        }
        if (value instanceof PhotonValueSerializable vs) {
            return toValueTag(vs, provider);
        }
        if (value instanceof Collection<?> col) {
            ListTag list = new ListTag();
            Type elemType = genericType instanceof ParameterizedType pt && pt.getActualTypeArguments().length > 0
                    ? pt.getActualTypeArguments()[0] : Object.class;
            for (Object elem : col) {
                Tag t = encodeValue(elem, elemType, provider);
                if (t != null) list.add(t);
            }
            return list;
        }
        if (cls.isArray()) {
            ListTag list = new ListTag();
            int len = Array.getLength(value);
            Class<?> comp = cls.getComponentType();
            for (int i = 0; i < len; i++) {
                Tag t = encodeValue(Array.get(value, i), comp, provider);
                if (t != null) list.add(t);
            }
            return list;
        }
        if (value instanceof Map<?, ?> map) {
            CompoundTag ct = new CompoundTag();
            Type valType = genericType instanceof ParameterizedType pt && pt.getActualTypeArguments().length > 1
                    ? pt.getActualTypeArguments()[1] : Object.class;
            for (var entry : map.entrySet()) {
                Tag vt = encodeValue(entry.getValue(), valType, provider);
                if (vt != null && entry.getKey() != null) ct.put(entry.getKey().toString(), vt);
            }
            return ct;
        }
        return serializeNBT(value, provider);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static @Nullable Object decodeFieldValue(Object owner, Field field, @Nullable Object current,
                                                     Tag data, HolderLookup.Provider provider) throws Exception {
        ReadOnlyManaged rom = field.getAnnotation(ReadOnlyManaged.class);
        if (rom != null && Collection.class.isAssignableFrom(field.getType())) {
            Tag listData = data;
            Tag uidTag = null;
            if (data instanceof CompoundTag ct && ct.contains("d")) {
                listData = ct.get("d");
                uidTag = ct.get("uid");
            }
            if (listData instanceof ListTag listTag) {
                Collection col = null;
                if (uidTag != null || !rom.deserializeMethod().isEmpty()) {
                    Tag arg = uidTag != null ? uidTag : IntTag.valueOf(listTag.size());
                    Method m = findMethod(owner.getClass(), rom.deserializeMethod(), arg.getClass());
                    if (m != null) {
                        m.setAccessible(true);
                        col = (Collection) m.invoke(owner, arg);
                    }
                }
                if (col == null) {
                    col = current instanceof Collection existing ? existing : new ArrayList();
                    col.clear();
                }
                Type elemType = field.getGenericType() instanceof ParameterizedType pt && pt.getActualTypeArguments().length > 0
                        ? pt.getActualTypeArguments()[0] : Object.class;
                if (col instanceof List list && list.size() == listTag.size()) {
                    for (int i = 0; i < listTag.size(); i++) {
                        Object existing = list.get(i);
                        Object decoded = decodeValue(existing, elemType, listTag.get(i), provider);
                        if (decoded != null) list.set(i, decoded);
                    }
                } else {
                    col.clear();
                    for (int i = 0; i < listTag.size(); i++) {
                        Object decoded = decodeValue(null, elemType, listTag.get(i), provider);
                        if (decoded != null) col.add(decoded);
                    }
                }
                return col;
            }
        }
        return decodeValue(current, field.getGenericType(), data, provider);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static @Nullable Object decodeValue(@Nullable Object current, Type targetType, Tag data,
                                                HolderLookup.Provider provider) {
        if (data == null) return current;
        Class<?> raw = rawClass(targetType);
        if (raw == boolean.class || raw == Boolean.class) return data instanceof NumericTag nt && nt.byteValue() != 0;
        if (raw == byte.class || raw == Byte.class) return data instanceof NumericTag nt ? nt.byteValue() : (byte) 0;
        if (raw == short.class || raw == Short.class) return data instanceof NumericTag nt ? nt.shortValue() : (short) 0;
        if (raw == int.class || raw == Integer.class) return data instanceof NumericTag nt ? nt.intValue() : 0;
        if (raw == long.class || raw == Long.class) return data instanceof NumericTag nt ? nt.longValue() : 0L;
        if (raw == float.class || raw == Float.class) return data instanceof NumericTag nt ? nt.floatValue() : 0f;
        if (raw == double.class || raw == Double.class) return data instanceof NumericTag nt ? nt.doubleValue() : 0d;
        if (raw == String.class) return data instanceof StringTag st ? st.value() : data.toString();
        if (Tag.class.isAssignableFrom(raw)) return data.copy();
        if (raw.isEnum()) {
            Object[] constants = raw.getEnumConstants();
            if (data instanceof StringTag st) {
                for (Object c : constants) if (((Enum<?>) c).name().equalsIgnoreCase(st.value())) return c;
            } else if (data instanceof NumericTag nt) {
                int ord = nt.intValue();
                if (ord >= 0 && ord < constants.length) return constants[ord];
            }
            return current;
        }
        Codec<Object> codec = AccessorRegistries.findCodec(raw);
        if (codec != null) {
            return codec.parse(NbtOps.INSTANCE, data).result().orElse(current);
        }
        if (PhotonValueSerializable.class.isAssignableFrom(raw) && data instanceof CompoundTag ct) {
            Object target = current != null ? current : tryInstantiate(raw);
            if (target instanceof PhotonValueSerializable vs) {
                fromValueTag(vs, ct, provider);
                return target;
            }
        }
        if (Collection.class.isAssignableFrom(raw) && data instanceof ListTag listTag) {
            Collection col = current instanceof Collection c ? c : (Set.class.isAssignableFrom(raw) ? new LinkedHashSet() : new ArrayList());
            col.clear();
            Type elemType = targetType instanceof ParameterizedType pt && pt.getActualTypeArguments().length > 0
                    ? pt.getActualTypeArguments()[0] : Object.class;
            for (int i = 0; i < listTag.size(); i++) {
                Object elem = decodeValue(null, elemType, listTag.get(i), provider);
                if (elem != null) col.add(elem);
            }
            return col;
        }
        if (raw.isArray() && data instanceof ListTag listTag) {
            Class<?> comp = raw.getComponentType();
            Object arr = Array.newInstance(comp, listTag.size());
            for (int i = 0; i < listTag.size(); i++) {
                Object elem = decodeValue(null, comp, listTag.get(i), provider);
                if (elem != null) Array.set(arr, i, elem);
            }
            return arr;
        }
        if (Map.class.isAssignableFrom(raw) && data instanceof CompoundTag ct) {
            Map map = current instanceof Map m ? m : new LinkedHashMap();
            map.clear();
            Type valType = targetType instanceof ParameterizedType pt && pt.getActualTypeArguments().length > 1
                    ? pt.getActualTypeArguments()[1] : Object.class;
            for (String k : ct.keySet()) {
                Object v = decodeValue(null, valType, ct.get(k), provider);
                if (v != null) map.put(k, v);
            }
            return map;
        }
        if (data instanceof CompoundTag ct) {
            Object target = current != null ? current : tryInstantiate(raw);
            if (target != null) {
                deserializeObject(true, ct, new HashMap<>(), target.getClass(), target, provider);
                return target;
            }
        }
        return current;
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> c) return c;
        if (type instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> c) return c;
        return Object.class;
    }

    private static @Nullable Object tryInstantiate(Class<?> raw) {
        if (raw.isInterface() || Modifier.isAbstract(raw.getModifiers())) return null;
        try {
            var ctor = raw.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static @Nullable Method findMethod(Class<?> clazz, String name, Class<?> paramType) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isAssignableFrom(paramType)) {
                    return m;
                }
            }
        }
        return null;
    }

    private static CompoundTag toValueTag(PhotonValueSerializable vs, HolderLookup.Provider provider) {
        try (var reporter = new ProblemReporter.ScopedCollector(LDLib2.LOGGER)) {
            var out = TagValueOutput.createWithContext(reporter, provider);
            vs.serialize(out);
            return out.buildResult();
        }
    }

    private static void fromValueTag(PhotonValueSerializable vs, CompoundTag tag, HolderLookup.Provider provider) {
        try (var reporter = new ProblemReporter.ScopedCollector(LDLib2.LOGGER)) {
            vs.deserialize(TagValueInput.create(reporter, provider, tag));
        }
    }
}
