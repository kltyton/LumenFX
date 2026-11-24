package com.lowdragmc.lowdraglib2.registry;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public abstract class LDLRegistry<K, V> implements Iterable<V> {
    public static final Map<Identifier, LDLRegistry<?, ?>> REGISTERED = new ConcurrentHashMap<>();

    protected final Identifier registryName;
    protected final Map<K, V> registry = new LinkedHashMap<>();
    protected final Map<V, K> inverse = new LinkedHashMap<>();
    protected boolean frozen = false;
    @Nullable
    protected K missingKey = null;

    public LDLRegistry(Identifier registryName) {
        this.registryName = registryName;
        REGISTERED.put(registryName, this);
    }

    public boolean containKey(K key) { return registry.containsKey(key); }
    public boolean containValue(V value) { return inverse.containsKey(value); }
    public void freeze() { frozen = true; }
    public void unfreeze() { frozen = false; }
    public boolean isFrozen() { return frozen; }
    public Identifier getRegistryName() { return registryName; }
    public @Nullable K getMissingKey() { return missingKey; }
    public LDLRegistry<K, V> setMissingKey(K key) { this.missingKey = key; return this; }

    public void register(K key, V value) {
        if (frozen) throw new IllegalStateException("Registry " + registryName + " is frozen");
        registry.put(key, value);
        inverse.put(value, key);
    }

    public V replace(K key, V value) {
        return registerOrOverride(key, value);
    }

    public V registerOrOverride(K key, V value) {
        V old = registry.put(key, value);
        if (old != null) inverse.remove(old);
        inverse.put(value, key);
        return old;
    }

    public boolean remove(K key) {
        V old = registry.remove(key);
        if (old != null) { inverse.remove(old); return true; }
        return false;
    }

    @Override
    public Iterator<V> iterator() { return registry.values().iterator(); }
    public Set<V> values() { return new LinkedHashSet<>(registry.values()); }
    public Set<K> keys() { return registry.keySet(); }
    public Set<Map.Entry<K, V>> entries() { return registry.entrySet(); }
    public Map<K, V> registry() { return registry; }

    public @Nullable V get(K key) {
        V v = registry.get(key);
        return v != null ? v : (missingKey != null ? registry.get(missingKey) : null);
    }

    public Optional<V> getOptional(K key) {
        return Optional.ofNullable(registry.get(key));
    }

    public V getOrDefault(K key, V defaultValue) {
        return registry.getOrDefault(key, defaultValue);
    }

    public @Nullable K getKey(V value) { return inverse.get(value); }
    public K getOrDefaultKey(V value, K defaultKey) { return inverse.getOrDefault(value, defaultKey); }

    public abstract void writeBuf(V value, RegistryFriendlyByteBuf buf);
    public abstract V readBuf(RegistryFriendlyByteBuf buf);
    public abstract Tag saveToNBT(V value);
    public abstract V loadFromNBT(Tag tag);
    public abstract Codec<V> codec();
    public abstract Codec<Optional<V>> optionalCodec();
    public abstract StreamCodec<RegistryFriendlyByteBuf, V> streamCodec();

    public static class String<V> extends LDLRegistry<java.lang.String, V> {
        public String(Identifier registryName) {
            super(registryName);
        }

        @Override
        public void writeBuf(V value, RegistryFriendlyByteBuf buf) {
            var key = getKey(value);
            buf.writeUtf(key != null ? key : "");
        }

        @Override
        public V readBuf(RegistryFriendlyByteBuf buf) {
            return get(buf.readUtf());
        }

        @Override
        public Tag saveToNBT(V value) {
            var key = getKey(value);
            return StringTag.valueOf(key != null ? key : "");
        }

        @Override
        public V loadFromNBT(Tag tag) {
            return tag instanceof StringTag st ? get(st.value()) : null;
        }

        @Override
        public Codec<V> codec() {
            return Codec.STRING.comapFlatMap(
                    key -> {
                        V v = get(key);
                        return v != null ? DataResult.success(v) : DataResult.error(() -> "Unknown key " + key + " in " + registryName);
                    },
                    v -> {
                        var k = getKey(v);
                        return k != null ? k : "";
                    }
            );
        }

        @Override
        public Codec<Optional<V>> optionalCodec() {
            return Codec.STRING.xmap(
                    key -> Optional.ofNullable(get(key)),
                    opt -> opt.map(this::getKey).orElse("")
            );
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, V> streamCodec() {
            return StreamCodec.of((buf, v) -> writeBuf(v, buf), this::readBuf);
        }
    }
}
