package com.kltyton.lumenfx.effect.render.mesh.nanite;

import java.util.function.IntPredicate;

/** A source range is a span of pages or a span of ordinary ranges in the resource's lookup table. */
public record NanitePageRange(int key) {
    private static final int COUNT_BITS = 5;
    private static final int COUNT_MASK = (1 << COUNT_BITS) - 1;
    private static final int START_MASK = (1 << 25) - 1;
    private static final int HAS_STREAMING = 1 << 30;
    private static final int MULTI_RANGE = 1 << 31;

    private int count() { return key & COUNT_MASK; }
    private int start() { return (key >>> COUNT_BITS) & START_MASK; }
    private boolean multiRange() { return (key & MULTI_RANGE) != 0; }

    public boolean allStreamingPages(NaniteMeshAsset asset, IntPredicate visitor) {
        if ((key & HAS_STREAMING) == 0 || count() == 0) return true;
        if (!multiRange()) return visitRange(asset, visitor);
        var lookup = asset.pageRangeLookup();
        int last = start() + count();
        if (last > lookup.remaining() / Integer.BYTES) throw new IllegalArgumentException("Nanite page range index out of bounds");
        for (int index = start(); index < last; index++) {
            var range = new NanitePageRange(lookup.getInt(index * Integer.BYTES));
            if (range.multiRange() || range.count() == 0) throw new IllegalArgumentException("Invalid nested Nanite page range");
            if (!range.allStreamingPages(asset, visitor)) return false;
        }
        return true;
    }

    private boolean visitRange(NaniteMeshAsset asset, IntPredicate visitor) {
        int last = start() + count();
        if (last > asset.pages().size()) throw new IllegalArgumentException("Nanite page range exceeds the asset");
        for (int page = Math.max(start(), asset.header().rootPages()); page < last; page++)
            if (!visitor.test(page)) return false;
        return true;
    }
}
