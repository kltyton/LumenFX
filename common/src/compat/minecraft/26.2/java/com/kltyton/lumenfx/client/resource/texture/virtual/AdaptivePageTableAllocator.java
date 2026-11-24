package com.kltyton.lumenfx.client.resource.texture.virtual;

/** Allocates aligned power-of-two squares in an adaptive RVT's private page-table space. */
final class AdaptivePageTableAllocator {
    static final class Block {
        final int x;
        final int y;
        final int level;
        final Block parent;
        Block[] children;
        boolean allocated;
        int largestFreeLevel;

        Block(int x, int y, int level, Block parent) {
            this.x = x;
            this.y = y;
            this.level = level;
            this.parent = parent;
            largestFreeLevel = level;
        }

        int side() { return 1 << level; }
        int area() { return 1 << (level * 2); }

        Block allocate(int target) {
            if (target > largestFreeLevel) return null;
            if (target == level) {
                allocated = true;
                largestFreeLevel = -1;
                return this;
            }
            if (children == null) {
                int step = side() >> 1;
                children = new Block[] { new Block(x, y, level - 1, this), new Block(x + step, y, level - 1, this),
                        new Block(x, y + step, level - 1, this), new Block(x + step, y + step, level - 1, this) };
            }
            for (var child : children) {
                var block = child.allocate(target);
                if (block != null) {
                    updateFreeLevel();
                    return block;
                }
            }
            return null;
        }

        void updateFreeLevel() {
            int largest = -1;
            boolean unused = true;
            for (var child : children) {
                largest = Math.max(largest, child.largestFreeLevel);
                unused &= child.largestFreeLevel == child.level;
            }
            if (unused) children = null;
            largestFreeLevel = unused ? level : largest;
        }
    }

    private final Block root;
    private int area;

    AdaptivePageTableAllocator(int level) { root = new Block(0, 0, level, null); }

    Block allocate(int level) {
        var block = root.allocate(level);
        if (block != null) area += block.area();
        return block;
    }

    void free(Block block) {
        if (!block.allocated) throw new IllegalArgumentException("Adaptive virtual texture table block is already free");
        block.allocated = false;
        block.largestFreeLevel = block.level;
        area -= block.area();
        for (Block parent = block.parent; parent != null; parent = parent.parent) parent.updateFreeLevel();
    }

    int area() { return area; }
    int capacity() { return root.area(); }
}
