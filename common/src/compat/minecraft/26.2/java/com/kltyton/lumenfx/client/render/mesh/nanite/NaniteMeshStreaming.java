package com.kltyton.lumenfx.client.render.mesh.nanite;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.effect.render.mesh.nanite.NaniteMeshAsset;
import com.kltyton.lumenfx.effect.render.mesh.nanite.NanitePageRange;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;
import org.jspecify.annotations.Nullable;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/** Owns requests and page residency for one registered mesh resource. */
final class NaniteMeshStreaming implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    record Request(NaniteMeshStreaming mesh, int page, long priority) { }
    record Installation(Set<Integer> pages, long frame, NaniteResidentPages.Decoded decoded) { }
    private record Read(Set<Integer> requested, int newPages, long frame, CompletableFuture<NaniteResidentPages.Decoded> result) { }
    private record Dependency(int page, int next) { }

    private final NaniteMeshAsset asset;
    private final NanitePhysicalPagePool pool;
    private final int rootBase;
    private final Executor reader;
    private final Map<Integer, Long> wanted = new HashMap<>();
    private final Map<Integer, Long> lastReferenced = new HashMap<>();
    private final Set<Integer> failedPages = new HashSet<>();
    private long requestFrame = Long.MIN_VALUE;
    private @Nullable Read pending;
    private @Nullable NaniteResidentPages resident;
    private @Nullable NaniteGpuPages gpu;
    private Set<Integer> requested = Set.of();
    private @Nullable Set<Integer> failed;
    private boolean failedInitial, closed;

    NaniteMeshStreaming(NaniteMeshAsset asset, NanitePhysicalPagePool pool, Executor reader) {
        if (asset.pages().size() < asset.header().rootPages())
            throw new IllegalArgumentException("Invalid Nanite streaming allocation");
        this.asset = asset;
        this.pool = pool;
        rootBase = pool.registerRoots(asset.header().rootPages());
        this.reader = reader;
    }

    int resourceId() { return rootBase; }
    NaniteMeshAsset asset() { return asset; }

    void beginRequests(long frame) {
        if (frame <= requestFrame) throw new IllegalStateException("Nanite streaming requests must advance by frame");
        requestFrame = frame;
        wanted.clear();
    }

    void request(int pageRange, long priority) {
        if (closed || failedInitial || priority == 0 || priority > 0xffffffe0L) return;
        new NanitePageRange(pageRange).allStreamingPages(asset, page -> {
            requestPage(page, priority);
            return true;
        });
    }

    void requestPage(int page, long priority) {
        if (!closed && !failedInitial && !asset.root(page) && asset.canLoad(page))
            wanted.merge(page, priority, Math::max);
    }

    int includeParentRequests() {
        var ordered = new LinkedHashSet<Integer>();
        for (int page : wanted.keySet()) ordered.addAll(dependencies(page, ordered));
        var childFirst = new ArrayList<>(ordered);
        for (int page : childFirst.reversed()) {
            long parentPriority = wanted.get(page) + 1;
            if (parentPriority > 0xffffffffL) throw new IllegalArgumentException("Nanite streaming dependency priority overflow");
            forEachParent(page, parent -> wanted.merge(parent, parentPriority, Math::max));
        }
        for (int page : plannedPages()) if (wanted.containsKey(page)) lastReferenced.put(page, requestFrame);
        return wanted.size();
    }

    @Nullable NaniteGpuPages current() { return gpu; }
    boolean failedToLoadRoots() { return failedInitial; }

    void collectRequests(Collection<Request> result) {
        if (!failedInitial) wanted.forEach((page, value) -> {
            if (!failedPages.contains(page)) result.add(new Request(this, page, value));
        });
    }

    boolean loading() { return pending != null; }
    boolean ready() { return pending != null && pending.result().isDone(); }
    int pendingPages() { return pending == null ? 0 : pending.newPages(); }
    Set<Integer> plannedPages() { return pending == null ? requested : pending.requested(); }
    long lastReferenced(int page) { return lastReferenced.getOrDefault(page, Long.MIN_VALUE); }
    int installDelta(Installation next) { return next.pages().size() - requested.size(); }

    boolean schedule(Set<Integer> desired, long frame) {
        RenderSystem.assertOnRenderThread();
        if (closed || pending != null || failedInitial || desired.equals(failed) || resident != null && requested.equals(desired))
            return false;
        var old = resident;
        var destination = new CompletableFuture<NaniteResidentPages.Decoded>();
        int newPages = (int) desired.stream().filter(page -> !requested.contains(page)).count();
        pending = new Read(desired, newPages, frame, destination);
        try {
            reader.execute(() -> {
                if (destination.isCancelled()) return;
                try { destination.complete(NaniteResidentPages.prepare(asset, old, desired)); }
                catch (IOException | RuntimeException | Error error) { destination.completeExceptionally(error); }
            });
        } catch (RuntimeException failure) {
            destination.completeExceptionally(failure);
        }
        return true;
    }

    @Nullable Installation takeReady() {
        if (!ready()) throw new IllegalStateException("Nanite page installation is not ready");
        var read = Objects.requireNonNull(pending);
        pending = null;
        try {
            var decoded = read.result().join();
            return new Installation(read.requested(), read.frame(), decoded);
        } catch (CompletionException error) {
            failed = read.requested();
            failedInitial = resident == null && failed.isEmpty();
            if (error.getCause() instanceof NaniteResidentPages.PageLoadFailure page) failedPages.add(page.page());
            discardUninstalledReferences();
            LOGGER.error("Cannot stream Nanite mesh pages {}", failed, error.getCause());
            return null;
        }
    }

    void install(CommandEncoder encoder, Installation next) {
        publish(encoder, next.decoded());
        for (int page : next.pages()) lastReferenced.putIfAbsent(page, next.frame());
        lastReferenced.keySet().retainAll(next.pages());
        requested = next.pages();
        failed = null;
    }

    void cancelRead() {
        var read = pending;
        pending = null;
        if (read != null) read.result().cancel(false);
        discardUninstalledReferences();
    }

    void discardUninstalledReferences() { lastReferenced.keySet().retainAll(requested); }

    void resetStreaming(CommandEncoder encoder) {
        cancelRead();
        wanted.clear();
        requestFrame = Long.MIN_VALUE;
        lastReferenced.clear();
        failedPages.clear();
        if (resident != null && !requested.isEmpty()) publish(encoder, resident.rootsOnly());
        requested = Set.of();
        failed = null;
        failedInitial = false;
    }

    private void publish(CommandEncoder encoder, NaniteResidentPages.Decoded decoded) {
        int rootCount = asset.header().rootPages();
        var placement = pool.placement(rootBase, rootCount, decoded.sourcePages(),
                resident == null ? Map.of() : resident.slots());
        var ready = decoded.install(placement.pages());
        pool.ensureBuffer(encoder);
        var next = NaniteGpuPages.upload(encoder, pool, ready, resident, rootCount);
        pool.commit(placement);
        var old = gpu;
        gpu = next;
        resident = ready;
        if (old != null) old.close();
    }

    Set<Integer> dependencies(int requestedPage, Set<Integer> selected) {
        if (selected.contains(requestedPage)) return Set.of();
        var needed = new LinkedHashSet<Integer>();
        var active = new HashSet<Integer>();
        var stack = new ArrayDeque<Dependency>();
        active.add(requestedPage);
        stack.addLast(new Dependency(requestedPage, 0));
        while (!stack.isEmpty()) {
            var item = stack.removeLast();
            var state = asset.pages().get(item.page());
            if (item.next() < state.dependencyCount()) {
                stack.addLast(new Dependency(item.page(), item.next() + 1));
                int dependency = asset.dependency(state, item.next());
                if (asset.root(dependency) || selected.contains(dependency) || needed.contains(dependency)) continue;
                if (!active.add(dependency)) throw new IllegalArgumentException("Nanite streaming dependency cycle");
                stack.addLast(new Dependency(dependency, 0));
            } else {
                needed.add(item.page());
                active.remove(item.page());
            }
        }
        return needed;
    }

    void forEachParent(int page, IntConsumer parent) {
        var state = asset.pages().get(page);
        for (int i = 0; i < state.dependencyCount(); i++) {
            int dependency = asset.dependency(state, i);
            if (!asset.root(dependency)) parent.accept(dependency);
        }
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        cancelRead();
        var published = gpu;
        gpu = null;
        if (resident != null) pool.releaseStreams(resident.slots(), asset.header().rootPages());
        resident = null;
        wanted.clear();
        lastReferenced.clear();
        failedPages.clear();
        if (published != null) published.close();
    }
}
