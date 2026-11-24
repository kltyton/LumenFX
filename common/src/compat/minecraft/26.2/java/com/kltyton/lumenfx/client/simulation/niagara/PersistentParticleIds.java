package com.kltyton.lumenfx.client.simulation.niagara;

import java.util.Arrays;

/** Per-emitter allocation state and the current frame's ID-to-particle mapping. */
final class PersistentParticleIds {
    private int[] free = new int[0];
    private int[] index = new int[0];
    private int[] spawned = new int[0];
    private int freeCount, spawnedCount, spawnedCursor, maxUsed = -1, tag;

    void begin(int requested, int acquireTag) {
        int oldSize = index.length;
        int required = Math.max(requested, Math.max(oldSize, maxUsed + 1));
        if (required > oldSize) {
            free = Arrays.copyOf(free, required);
            index = Arrays.copyOf(index, required);
            for (int id = required - 1; id >= oldSize; id--) free[freeCount++] = id;
        }
        Arrays.fill(index, -1);
        maxUsed = -1;
        tag = acquireTag;
    }

    void beginSpawning() {
        spawnedCount = 0;
        spawnedCursor = spawned.length;
    }

    void beginAcquireBatch(int count) {
        if (count < 0 || count > freeCount) throw new IllegalStateException("Niagara persistent ID capacity exhausted");
        int needed = Math.addExact(spawnedCount, count);
        if (needed > spawned.length) {
            int[] replacement = new int[Math.max(needed, Math.max(16, spawned.length * 2))];
            System.arraycopy(spawned, spawned.length - spawnedCount, replacement, replacement.length - spawnedCount, spawnedCount);
            spawned = replacement;
        }
        spawnedCount = needed;
        spawnedCursor = spawned.length - spawnedCount;
    }

    int acquire() {
        if (freeCount == 0) throw new IllegalStateException("Niagara persistent ID capacity exhausted");
        int id = free[--freeCount];
        spawned[spawnedCursor++] = id;
        return id;
    }

    int tag() { return tag; }
    int[] copyIndex(int[] target) {
        if (target.length != index.length) target = new int[index.length];
        System.arraycopy(index, 0, target, 0, index.length);
        return target;
    }
    int[] indexTable() { return index; }
    int spawnedCount() { return spawnedCount; }
    int spawnedAt(int index) { return spawned[spawned.length - spawnedCount + index]; }

    void alive(int id, int row) {
        if (id >= 0 && id < index.length) {
            index[id] = row;
            maxUsed = Math.max(maxUsed, id);
        }
    }

    void dead(int id) {
        if (id >= 0 && id < index.length) index[id] = -1;
        free[freeCount++] = id;
    }

    void resetIdTable() {
        Arrays.fill(index, -1);
        maxUsed = -1;
    }

    void reset() {
        free = new int[0]; index = new int[0]; spawned = new int[0];
        freeCount = 0; spawnedCount = 0; spawnedCursor = 0; maxUsed = -1; tag = 0;
    }

    void readCache(int acquireTag, int[] idToRow) {
        int len = idToRow.length;
        index = Arrays.copyOf(idToRow, len);
        free = new int[len];
        freeCount = 0;
        maxUsed = -1;
        for (int id = len - 1; id >= 0; id--) {
            if (index[id] >= 0) maxUsed = Math.max(maxUsed, id);
            else free[freeCount++] = id;
        }
        tag = acquireTag;
        spawnedCount = 0;
    }
}
