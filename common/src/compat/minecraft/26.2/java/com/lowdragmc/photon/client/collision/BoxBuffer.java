/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.collision;

import java.util.Arrays;

/** World-space boxes gathered for a query; thread-confined scratch. */
public final class BoxBuffer {
    public static final byte BLOCK = 0;
    /** A fluid surface: solid only from above, and only its top face. */
    public static final byte FLUID = 1;

    double[] boxes = new double[6 * 64];
    byte[] kinds = new byte[64];
    long[] keys = new long[64];
    Object[] payloads = new Object[64];
    int size;

    /** Sections this thread looked up recently, valid for one world at one version. */
    private static final int SECTION_SLOTS = 64;
    final long[] sectionKeys = new long[SECTION_SLOTS];
    final SectionSnapshot[] sectionValues = new SectionSnapshot[SECTION_SLOTS];
    /** {@link CollisionWorld#id}, not the world: a thread's scratch must not keep a left world alive. */
    private long sectionWorld;
    private int sectionVersion;

    void useSectionCache(CollisionWorld world, int version) {
        if (sectionWorld != world.id || sectionVersion != version) {
            Arrays.fill(sectionValues, null);
            sectionWorld = world.id;
            sectionVersion = version;
        }
    }

    static int sectionSlot(long key) {
        return (int) ((key * 0x9E3779B97F4A7C15L) >>> 58);
    }

    public void clear() {
        size = 0;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int size() { return size; }
    public double minX(int index) { return boxes[index * 6]; }
    public double minY(int index) { return boxes[index * 6 + 1]; }
    public double minZ(int index) { return boxes[index * 6 + 2]; }
    public double maxX(int index) { return boxes[index * 6 + 3]; }
    public double maxY(int index) { return boxes[index * 6 + 4]; }
    public double maxZ(int index) { return boxes[index * 6 + 5]; }
    public byte kind(int index) { return kinds[index]; }
    public long key(int index) { return keys[index]; }
    public Object payload(int index) { return payloads[index]; }

    /** {@code key} identifies the source (a packed block position); {@code payload} is reported with a hit. */
    public void add(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                    byte kind, long key, Object payload) {
        if (size == kinds.length) {
            int capacity = size * 2;
            boxes = Arrays.copyOf(boxes, capacity * 6);
            kinds = Arrays.copyOf(kinds, capacity);
            keys = Arrays.copyOf(keys, capacity);
            payloads = Arrays.copyOf(payloads, capacity);
        }
        int n = size * 6;
        boxes[n] = minX;
        boxes[n + 1] = minY;
        boxes[n + 2] = minZ;
        boxes[n + 3] = maxX;
        boxes[n + 4] = maxY;
        boxes[n + 5] = maxZ;
        kinds[size] = kind;
        keys[size] = key;
        payloads[size] = payload;
        size++;
    }

    /** Shifts boxes from {@code startIndex} onward in double precision (used for floating-origin rebasing). */
    public void shift(int startIndex, double dx, double dy, double dz) {
        for (int i = startIndex; i < size; i++) {
            int n = i * 6;
            boxes[n] += dx;
            boxes[n + 1] += dy;
            boxes[n + 2] += dz;
            boxes[n + 3] += dx;
            boxes[n + 4] += dy;
            boxes[n + 5] += dz;
        }
    }
}
