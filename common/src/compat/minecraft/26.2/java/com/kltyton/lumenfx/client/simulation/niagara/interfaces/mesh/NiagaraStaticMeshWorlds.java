package com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh;

import com.kltyton.lumenfx.api.client.parameter.NiagaraStaticMeshInputs.Source;
import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs.Transform;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshDistanceField;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

/** Live shared mesh IDs belong to a world, and hold a weak reference to the source component. */
public final class NiagaraStaticMeshWorlds {
    public record Field(int index, int acquireTag, Transform transform, StaticMeshDistanceField field) { }
    public record SceneField(List<Transform> instances, StaticMeshDistanceField field) {
        public SceneField { instances = List.copyOf(instances); }
    }
    public record Snapshot(int slots, List<Field> fields) {
        public static final Snapshot EMPTY = new Snapshot(0, List.of());
    }

    private static final WeakHashMap<ClientLevel, World> WORLDS = new WeakHashMap<>();
    private static final AtomicInteger NEXT_TAG = new AtomicInteger();
    private NiagaraStaticMeshWorlds() { }

    public static Lease acquire(ClientLevel world, Object component, Source source) {
        World registry;
        synchronized (WORLDS) { registry = WORLDS.computeIfAbsent(world, ignored -> new World()); }
        return registry.acquire(component, source);
    }

    static NiagaraMeshUvTree uvMapping(ClientLevel world, StaticMeshGeometry geometry, int channel) {
        World registry;
        synchronized (WORLDS) { registry = WORLDS.computeIfAbsent(world, ignored -> new World()); }
        return registry.uvMapping(geometry, channel);
    }

    public static Snapshot capture(ClientLevel world) {
        World registry;
        synchronized (WORLDS) { registry = WORLDS.get(world); }
        return registry == null ? Snapshot.EMPTY : registry.capture();
    }

    public static List<SceneField> captureScene(ClientLevel world) {
        World registry;
        synchronized (WORLDS) { registry = WORLDS.get(world); }
        return registry == null ? List.of() : registry.captureScene();
    }

    public static void clear(ClientLevel world) {
        World registry;
        synchronized (WORLDS) { registry = WORLDS.remove(world); }
        if (registry != null) registry.clear();
    }

    private static final class Entry {
        final WeakReference<Object> component;
        final int index, tag;
        int references;
        @Nullable Field field;
        @Nullable SceneField sceneField;

        Entry(Object component, int index) {
            this.component = new WeakReference<>(component);
            this.index = index;
            tag = NEXT_TAG.incrementAndGet();
        }

        void publish(Source source) {
            var mesh = source.mesh();
            var field = mesh != null && source.sceneDistanceField() ? mesh.sampling().distanceField() : null;
            var transforms = source.instances();
            var componentTransform = source.component();
            if (transforms.isEmpty() && componentTransform != null) transforms = List.of(componentTransform);
            this.field = field == null || transforms.isEmpty() ? null : new Field(index, tag, transforms.getFirst(), field);
            sceneField = field == null || transforms.isEmpty() ? null : new SceneField(transforms, field);
        }
    }

    private static final class World {
        final ArrayList<@Nullable Entry> entries = new ArrayList<>();
        final ArrayDeque<Integer> free = new ArrayDeque<>();
        final Map<StaticMeshGeometry, Map<Integer, WeakReference<NiagaraMeshUvTree>>> uvMappings = new WeakHashMap<>();
        boolean closed;

        synchronized NiagaraMeshUvTree uvMapping(StaticMeshGeometry geometry, int channel) {
            if (closed) throw new IllegalStateException("The Niagara static mesh world is closed");
            var channels = uvMappings.computeIfAbsent(geometry, ignored -> new HashMap<>());
            var reference = channels.get(channel);
            var mapping = reference == null ? null : reference.get();
            if (mapping == null) {
                mapping = new NiagaraMeshUvTree(geometry, channel);
                channels.put(channel, new WeakReference<>(mapping));
            }
            return mapping;
        }

        synchronized Lease acquire(Object component, Source source) {
            if (closed) throw new IllegalStateException("The Niagara static mesh world is closed");
            for (var entry : entries) if (entry != null && entry.component.get() == component) {
                entry.publish(source);
                return retain(entry);
            }
            int index = free.isEmpty() ? entries.size() : free.removeLast();
            var entry = new Entry(component, index);
            entry.publish(source);
            if (index == entries.size()) entries.add(entry);
            else entries.set(index, entry);
            return retain(entry);
        }

        synchronized boolean live(Entry entry) {
            return !closed && entry.index < entries.size() && entries.get(entry.index) == entry && entry.component.get() != null;
        }

        synchronized Lease retain(Entry entry) {
            if (!live(entry)) throw new IllegalStateException("The Niagara static mesh reference has expired");
            entry.references = Math.incrementExact(entry.references);
            return new Lease(this, entry);
        }

        synchronized void update(Entry entry, Source source) {
            if (!live(entry)) throw new IllegalStateException("The Niagara static mesh reference has expired");
            entry.publish(source);
        }

        synchronized void release(Entry entry) {
            if (entry.index >= entries.size() || entries.get(entry.index) != entry) return;
            if (--entry.references == 0) retire(entry);
        }

        synchronized Snapshot capture() {
            List<Field> fields = new ArrayList<>();
            int slots = 0;
            for (var entry : entries) if (entry != null) {
                if (entry.component.get() == null) {
                    retire(entry);
                    continue;
                }
                slots = entry.index + 1;
                if (entry.field != null) fields.add(entry.field);
            }
            return slots == 0 ? Snapshot.EMPTY : new Snapshot(slots, List.copyOf(fields));
        }

        synchronized List<SceneField> captureScene() {
            var fields = new ArrayList<SceneField>();
            for (var entry : entries) if (entry != null) {
                if (entry.component.get() == null) retire(entry);
                else if (entry.sceneField != null) fields.add(entry.sceneField);
            }
            return List.copyOf(fields);
        }

        private void retire(Entry entry) {
            entries.set(entry.index, null);
            entry.field = null;
            entry.sceneField = null;
            free.addLast(entry.index);
        }

        synchronized void clear() {
            closed = true;
            entries.forEach(entry -> {
                if (entry != null) {
                    entry.field = null;
                    entry.sceneField = null;
                }
            });
            entries.clear();
            free.clear();
            uvMappings.clear();
        }
    }

    public static final class Lease implements AutoCloseable {
        private final World world;
        private final Entry entry;
        private boolean closed;

        private Lease(World world, Entry entry) { this.world = world; this.entry = entry; }

        public synchronized boolean alive() { return !closed && world.live(entry); }
        public int index() { return entry.index; }
        public int acquireTag() { return entry.tag; }
        public synchronized Lease retain() {
            if (closed) throw new IllegalStateException("The Niagara static mesh reference is closed");
            return world.retain(entry);
        }
        public synchronized void update(Source source) {
            if (closed) throw new IllegalStateException("The Niagara static mesh reference is closed");
            world.update(entry, source);
        }
        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            world.release(entry);
        }
    }
}
