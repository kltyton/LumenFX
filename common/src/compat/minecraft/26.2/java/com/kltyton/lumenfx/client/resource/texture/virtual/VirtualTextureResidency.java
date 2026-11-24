package com.kltyton.lumenfx.client.resource.texture.virtual;

import com.kltyton.lumenfx.client.resource.texture.virtual.VirtualTexturePages.Page;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Shared physical slot lifetime for streamed tiles and pages generated from the scene. */
final class VirtualTextureResidency {
    private record Slot(int index, long lastUse) { }
    private final int width;
    private final Set<Page> roots;
    private final Set<Page> adaptiveRoots = new HashSet<>();
    private final Map<Page, Slot> pages = new HashMap<>();
    private final ArrayDeque<Integer> free = new ArrayDeque<>();
    private int next = 1;
    private long clock;

    VirtualTextureResidency(int width, Set<Page> roots) {
        this.width = width;
        this.roots = Set.copyOf(roots);
    }

    boolean contains(Page page) { return pages.containsKey(page); }
    int capacity() { return width * width - 1; }
    int size() { return pages.size(); }
    void pin(Page page) { adaptiveRoots.add(page); }
    void unpin(Page page) { adaptiveRoots.remove(page); }
    Set<Page> pages() { return Set.copyOf(pages.keySet()); }

    boolean touch(Page page) {
        Slot slot = pages.get(page);
        if (slot == null) return false;
        pages.put(page, new Slot(slot.index(), ++clock));
        return true;
    }

    int reserve(Set<Page> dirty) {
        if (!free.isEmpty()) return free.removeFirst();
        if (next < width * width) return next++;
        Page candidate = null;
        long oldest = Long.MAX_VALUE;
        for (var entry : pages.entrySet()) {
            if (roots.contains(entry.getKey()) || adaptiveRoots.contains(entry.getKey()) || entry.getValue().lastUse() >= oldest) continue;
            candidate = entry.getKey();
            oldest = entry.getValue().lastUse();
        }
        if (candidate == null) return -1;
        dirty.add(candidate);
        return pages.remove(candidate).index();
    }

    void publish(Page page, int index) {
        var previous = pages.put(page, new Slot(index, ++clock));
        if (previous != null && previous.index() != index) free.add(previous.index());
    }

    void discard(int slot) { free.add(slot); }
    int x(int slot) { return slot % width; }
    int y(int slot) { return slot / width; }

    int pageEntry(Page start, int maxMip) {
        return pageEntry(start, maxMip, 0);
    }

    int pageEntry(Page start, int maxMip, int mipBias) {
        for (Page page = start; page.mip() <= maxMip; page = page.parent()) {
            Slot slot = pages.get(page);
            if (slot != null) return (page.mip() - mipBias) | (x(slot.index()) << 4) | (y(slot.index()) << 12);
        }
        return 0;
    }
}
