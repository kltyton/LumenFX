package com.kltyton.lumenfx.client.render.mesh;

import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

/** Retains static assets while their definitions live and releases replaced dynamic geometry. */
public final class MeshGeometryCache implements AutoCloseable {
    private static final class Entry extends WeakReference<StaticMeshGeometry> {
        final StaticMeshBuffers buffers;
        Entry(StaticMeshGeometry geometry, StaticMeshBuffers buffers, ReferenceQueue<StaticMeshGeometry> queue) {
            super(geometry, queue); this.buffers = buffers;
        }
    }
    private final WeakHashMap<StaticMeshGeometry, Entry> entries = new WeakHashMap<>();
    private final Set<Entry> retained = new HashSet<>();
    private final ReferenceQueue<StaticMeshGeometry> released = new ReferenceQueue<>();

    public StaticMeshBuffers get(CommandEncoder encoder, StaticMeshGeometry geometry, long maximumTexels) {
        RenderSystem.assertOnRenderThread();
        reap();
        Entry entry = entries.get(geometry);
        if (entry == null) {
            entry = new Entry(geometry, new StaticMeshBuffers(encoder, geometry, maximumTexels), released);
            entries.put(geometry, entry); retained.add(entry);
        }
        return entry.buffers;
    }

    public void reap() {
        RenderSystem.assertOnRenderThread();
        for (Entry entry; (entry = (Entry)released.poll()) != null;)
            if (retained.remove(entry)) entry.buffers.close();
    }

    public void prepare(CommandEncoder encoder, Collection<StaticMeshGeometry> lods, long maximumTexels) {
        RenderSystem.assertOnRenderThread();
        reap();
        var created = new ArrayList<StaticMeshGeometry>();
        try {
            for (var geometry : lods) {
                if (entries.containsKey(geometry)) continue;
                get(encoder, geometry, maximumTexels);
                created.add(geometry);
            }
        } catch (RuntimeException | Error failure) {
            for (var geometry : created) {
                var entry = entries.remove(geometry);
                retained.remove(entry);
                entry.buffers.close();
            }
            throw failure;
        }
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        retained.forEach(entry -> entry.buffers.close());
        retained.clear(); entries.clear();
        while (released.poll() != null) { }
    }
}
