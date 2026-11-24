package com.kltyton.lumenfx.client.render.mesh.nanite;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/** Selects and replaces registered pages in the world's shared streaming pool. */
final class NaniteStreamingBudget {
    private record Page(NaniteMeshStreaming mesh, int index) { }
    private record Batch(List<NaniteMeshStreaming> resources, int installs) { }

    private static final Comparator<Page> LRU = Comparator.<Page>comparingLong(page -> page.mesh().lastReferenced(page.index()))
            .thenComparingInt(page -> page.mesh().resourceId()).thenComparingInt(Page::index);

    private final ArrayDeque<Batch> pending = new ArrayDeque<>();

    void advance(CommandEncoder encoder, Collection<NaniteMeshStreaming> meshes, long frame, boolean newFeedback,
                 int poolPages, int maxPendingPages, int maxInstalls) {
        RenderSystem.assertOnRenderThread();
        if (poolPages < 0 || maxPendingPages < 0 || maxInstalls < 0)
            throw new IllegalArgumentException("Invalid Nanite streaming budget");
        installReady(encoder, maxInstalls);
        for (var mesh : meshes) {
            if (mesh.current() != null) continue;
            if (mesh.ready()) {
                var roots = mesh.takeReady();
                if (roots != null) mesh.install(encoder, roots);
            } else if (!mesh.loading()) {
                mesh.schedule(Set.of(), frame);
            }
        }
        if (newFeedback && poolPages > 0) select(meshes, frame, poolPages, maxPendingPages, maxInstalls);
    }

    private void select(Collection<NaniteMeshStreaming> meshes, long frame, int poolPages, int maxPending, int maxInstalls) {
        var target = new LinkedHashMap<NaniteMeshStreaming, Set<Integer>>();
        var requested = new ArrayList<NaniteMeshStreaming.Request>();
        int free = poolPages;
        int available = maxPending;
        for (var mesh : meshes) {
            free -= mesh.plannedPages().size();
            available -= mesh.pendingPages();
            target.put(mesh, new LinkedHashSet<>(mesh.plannedPages()));
            mesh.collectRequests(requested);
        }
        int limit = Math.min(available, maxInstalls);
        if (limit <= 0 || requested.isEmpty()) return;

        var referenced = new HashSet<Page>();
        for (var request : requested) referenced.add(new Page(request.mesh(), request.page()));
        requested.sort(Comparator.comparingLong(NaniteMeshStreaming.Request::priority).reversed()
                .thenComparingInt(request -> request.mesh().resourceId()).thenComparingInt(NaniteMeshStreaming.Request::page));

        var dependents = new HashMap<Page, Integer>();
        var victims = new PriorityQueue<>(LRU);
        if (free < limit) {
            target.forEach((mesh, pages) -> {
                for (int page : pages)
                    mesh.forEachParent(page, parent -> dependents.merge(new Page(mesh, parent), 1, Integer::sum));
            });
            target.forEach((mesh, pages) -> {
                if (mesh.loading()) return;
                for (int index : pages) {
                    var page = new Page(mesh, index);
                    if (!referenced.contains(page) && !dependents.containsKey(page)) victims.add(page);
                }
            });
        }

        int installs = 0;
        var changed = new LinkedHashSet<NaniteMeshStreaming>();
        for (var request : requested) {
            if (installs >= limit) break;
            var mesh = request.mesh();
            if (mesh.current() == null || mesh.loading()) continue;
            var pages = target.get(mesh);
            if (pages.contains(request.page()) || mesh.dependencies(request.page(), pages).size() != 1) continue;

            if (free == 0) {
                var victim = victims.poll();
                if (victim == null) break;
                target.get(victim.mesh()).remove(victim.index());
                changed.add(victim.mesh());
                victim.mesh().forEachParent(victim.index(), parent -> {
                    var candidate = new Page(victim.mesh(), parent);
                    if (dependents.computeIfPresent(candidate, (key, count) -> count == 1 ? null : count - 1) == null
                            && !referenced.contains(candidate)) victims.add(candidate);
                });
            } else {
                free--;
            }
            pages.add(request.page());
            changed.add(mesh);
            installs++;
        }
        if (installs == 0) return;

        var scheduled = new ArrayList<NaniteMeshStreaming>();
        try {
            for (var mesh : changed) {
                if (!mesh.schedule(Set.copyOf(target.get(mesh)), frame)) return;
                scheduled.add(mesh);
            }
            pending.addLast(new Batch(List.copyOf(scheduled), installs));
            scheduled.clear();
        } finally {
            scheduled.forEach(NaniteMeshStreaming::cancelRead);
        }
    }

    private void installReady(CommandEncoder encoder, int maxInstalls) {
        int installed = 0;
        while (!pending.isEmpty()) {
            var batch = pending.getFirst();
            if (installed > 0 && batch.installs() > maxInstalls - installed) return;
            if (batch.resources().stream().anyMatch(mesh -> !mesh.ready())) return;
            pending.removeFirst();
            var ready = new LinkedHashMap<NaniteMeshStreaming, NaniteMeshStreaming.Installation>();
            for (var mesh : batch.resources()) {
                var installation = mesh.takeReady();
                if (installation != null) ready.put(mesh, installation);
            }
            if (ready.size() != batch.resources().size()) {
                ready.keySet().forEach(NaniteMeshStreaming::discardUninstalledReferences);
                continue;
            }
            var ordered = new ArrayList<>(ready.entrySet());
            ordered.sort(Comparator.comparingInt(entry -> entry.getKey().installDelta(entry.getValue())));
            for (var entry : ordered) entry.getKey().install(encoder, entry.getValue());
            installed += batch.installs();
        }
    }

    void reset() { pending.clear(); }
}
