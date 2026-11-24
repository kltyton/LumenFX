package com.kltyton.lumenfx.client.material.collection;

import com.kltyton.lumenfx.effect.material.collection.MaterialCollectionDefinition;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.WeakHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

/** Each client world owns its lazily created MPC instances; frame readers receive immutable snapshots. */
public final class MaterialCollectionWorlds {
    private static final Map<ClientLevel, Map<String, MaterialCollectionInstance>> WORLDS = new WeakHashMap<>();
    private MaterialCollectionWorlds() { }

    public static synchronized MaterialCollectionInstance instance(ClientLevel world, MaterialCollectionDefinition definition) {
        Objects.requireNonNull(world); Objects.requireNonNull(definition);
        Map<String, MaterialCollectionInstance> instances = WORLDS.computeIfAbsent(world,
                ignored -> new TreeMap<>(SourceNames.ASCII_CASE_INSENSITIVE));
        MaterialCollectionInstance instance = instances.get(definition.path());
        if (instance == null) {
            instance = new MaterialCollectionInstance(definition, world);
            instances.put(definition.path(), instance);
        } else instance.definition(definition);
        return instance;
    }

    public static synchronized Map<String, MaterialCollectionInstance.Snapshot> snapshot(@Nullable ClientLevel world,
                                                                                         Iterable<MaterialCollectionDefinition> definitions) {
        Map<String, MaterialCollectionInstance.Snapshot> values = new TreeMap<>(SourceNames.ASCII_CASE_INSENSITIVE);
        if (world != null) for (MaterialCollectionDefinition definition : definitions)
            values.put(definition.path(), instance(world, definition).snapshot());
        return Collections.unmodifiableMap(values);
    }
    public static synchronized void clearWorld(ClientLevel world) {
        Map<String, MaterialCollectionInstance> instances = WORLDS.remove(world);
        if (instances != null) instances.values().forEach(MaterialCollectionInstance::close);
    }
    public static synchronized void clearWorlds() {
        WORLDS.values().forEach(instances -> instances.values().forEach(MaterialCollectionInstance::close));
        WORLDS.clear();
    }
}
