package com.kltyton.lumenfx.client.render.light.rect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

record RectLightAtlasLayout<T>(int size, int sourceMip, Map<T, Slot> slots) {
    record Extent(int width, int height) {
        Extent mip(int level) {
            return new Extent(Math.ceilDiv(width, 1 << level), Math.ceilDiv(height, 1 << level));
        }

        int maxMip() {
            return Math.max(0, Integer.SIZE - 2 - Integer.numberOfLeadingZeros(Math.min(width, height)));
        }

        Extent aligned() {
            Extent extent = this;
            int level = extent.maxMip();
            for (;;) {
                Extent coarse = extent.mip(level);
                extent = new Extent(coarse.width << level, coarse.height << level);
                int nextLevel = extent.maxMip();
                if (nextLevel == level) return extent;
                level = nextLevel;
            }
        }
    }

    record Slot(int x, int y, Extent extent) { }
    private record Entry<T>(T source, Extent extent) { }

    static <T> RectLightAtlasLayout<T> create(Map<T, Extent> sources, int maximum) {
        if (sources.isEmpty()) return new RectLightAtlasLayout<>(1, 0, Map.of());
        int maxSize = Integer.highestOneBit(maximum);
        if ((long)maxSize * maxSize < sources.size())
            throw new IllegalArgumentException("Rect light atlas has more sources than available texels");
        int size = Math.min(256, maxSize);
        for (int mip = 0; ; ) {
            List<Entry<T>> entries = new ArrayList<>(sources.size());
            for (var source : sources.entrySet()) entries.add(new Entry<>(source.getKey(), source.getValue().mip(mip).aligned()));
            entries.sort(Comparator.<Entry<T>>comparingInt(entry -> entry.extent().height()).reversed()
                    .thenComparing(Comparator.comparingInt((Entry<T> entry) -> entry.extent().width()).reversed()));
            var slots = pack(entries, size);
            if (slots != null) return new RectLightAtlasLayout<>(size, mip, Map.copyOf(slots));
            if (size < maxSize) size *= 2;
            else mip++;
        }
    }

    private static <T> @Nullable Map<T, Slot> pack(List<Entry<T>> entries, int size) {
        var slots = new HashMap<T, Slot>();
        int nextX = 0, y = 0, nextRow = 0;
        for (var entry : entries) {
            var extent = entry.extent();
            if (extent.width() > size || extent.height() > size) return null;
            int alignment = 1 << extent.maxMip();
            int x = align(nextX, alignment);
            if (x + extent.width() > size || y % alignment != 0) {
                y = align(nextRow, alignment);
                x = 0;
            }
            if (y + extent.height() > size) return null;
            slots.put(entry.source(), new Slot(x, y, extent));
            nextX = x + extent.width();
            nextRow = Math.max(nextRow, y + extent.height());
        }
        return slots;
    }

    private static int align(int value, int alignment) {
        return Math.ceilDiv(value, alignment) * alignment;
    }
}
