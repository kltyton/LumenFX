package com.kltyton.lumenfx.client.render.light.forward;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Stable row packing shared by the projected and cube-face forward shadow atlases. */
final class ForwardShadowAtlasLayout {
    record Rectangle(int width, int height) {
        Rectangle {
            if (width <= 0 || height <= 0) throw new IllegalArgumentException("Shadow atlas rectangles must be positive");
        }
    }
    record Placement(int x, int y) { }
    record Layout(int width, int height, List<Placement> placements) { }

    private ForwardShadowAtlasLayout() { }

    static Layout pack(List<Rectangle> rectangles, int maximum) {
        if (maximum <= 0) throw new IllegalStateException("Forward shadow atlas needs the active device texture limit");
        if (rectangles.isEmpty()) return new Layout(1, 1, List.of());
        long area = 0;
        int largest = 0;
        for (var rectangle : rectangles) {
            if (rectangle.width() > maximum || rectangle.height() > maximum)
                throw new IllegalArgumentException("Forward shadow rectangle exceeds the active device texture limit");
            area = Math.addExact(area, Math.multiplyExact((long)rectangle.width(), rectangle.height()));
            largest = Math.max(largest, Math.max(rectangle.width(), rectangle.height()));
        }
        int width = nextPowerOfTwo(Math.max(largest, (long)Math.ceil(Math.sqrt(area))), maximum);
        Integer[] order = new Integer[rectangles.size()];
        for (int index = 0; index < order.length; index++) order[index] = index;
        Arrays.sort(order, Comparator.<Integer>comparingInt(index -> Math.max(rectangles.get(index).width(), rectangles.get(index).height())).reversed()
                .thenComparing(Comparator.comparingInt((Integer index) -> rectangles.get(index).height()).reversed())
                .thenComparing(Comparator.comparingInt((Integer index) -> rectangles.get(index).width()).reversed()));

        while (true) {
            var placements = new ArrayList<Placement>(Collections.nCopies(rectangles.size(), null));
            int x = 0, y = 0, rowHeight = 0;
            boolean fits = true;
            for (int index : order) {
                var rectangle = rectangles.get(index);
                if ((long)x + rectangle.width() > width) {
                    y = Math.addExact(y, rowHeight);
                    x = 0;
                    rowHeight = 0;
                }
                if ((long)y + rectangle.height() > maximum) { fits = false; break; }
                placements.set(index, new Placement(x, y));
                x = Math.addExact(x, rectangle.width());
                rowHeight = Math.max(rowHeight, rectangle.height());
            }
            if (fits) return new Layout(width, Math.max(1, Math.addExact(y, rowHeight)), List.copyOf(placements));
            if (width >= maximum) throw new IllegalStateException("Forward shadow maps exceed the active device atlas limit");
            width = (int)Math.min(maximum, (long)width * 2);
        }
    }

    private static int nextPowerOfTwo(long value, int maximum) {
        if (value <= 1) return 1;
        if (value >= maximum) return maximum;
        long rounded = Long.highestOneBit(value - 1) << 1;
        return (int)Math.min(maximum, Math.max(1, rounded));
    }
}
