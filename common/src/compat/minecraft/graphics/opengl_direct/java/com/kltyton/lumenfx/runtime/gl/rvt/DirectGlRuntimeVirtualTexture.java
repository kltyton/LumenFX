package com.kltyton.lumenfx.runtime.gl.rvt;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;

/**
 * Native OpenGL 3.3+ Runtime Virtual Texture (RVT) page table, physical page cache atlas,
 * and LRU feedback streaming runtime for non-26.2 targets.
 */
public final class DirectGlRuntimeVirtualTexture {
    public static final int VIRTUAL_TILES_PER_AXIS = 64;
    public static final int PHYSICAL_PAGES_PER_AXIS = 8; // 64 resident pages in atlas
    public static final int PAGE_PIXELS = 128;
    public static final int ATLAS_PIXELS = PHYSICAL_PAGES_PER_AXIS * PAGE_PIXELS;

    public record VirtualPageKey(int tileX, int tileY, int mip) { }
    public record PhysicalSlot(int physX, int physY, VirtualPageKey residentKey) { }

    private final Map<VirtualPageKey, PhysicalSlot> resident = new LinkedHashMap<>(64, 0.75f, true);
    private final boolean[] slotUsed = new boolean[PHYSICAL_PAGES_PER_AXIS * PHYSICAL_PAGES_PER_AXIS];
    private final float[] pageTableCpu = new float[VIRTUAL_TILES_PER_AXIS * VIRTUAL_TILES_PER_AXIS * 4];
    private int pageTableTex;
    private int physicalAtlasTex;
    private boolean pageTableDirty;

    public int pageTableTextureId() { return pageTableTex; }
    public int physicalAtlasTextureId() { return physicalAtlasTex; }
    public int residentPageCount() { return resident.size(); }

    /**
     * Records a virtual UV page feedback request and guarantees an LRU physical page slot is resident.
     * Can be exercised headlessly (updates CPU page table state) and synced to OpenGL when rendering.
     */
    public PhysicalSlot requestPage(int tileX, int tileY, int mip) {
        int tx = Math.floorMod(tileX, VIRTUAL_TILES_PER_AXIS);
        int ty = Math.floorMod(tileY, VIRTUAL_TILES_PER_AXIS);
        int clampedMip = Math.max(0, Math.min(4, mip));
        VirtualPageKey key = new VirtualPageKey(tx, ty, clampedMip);
        PhysicalSlot existing = resident.get(key);
        if (existing != null) return existing;

        int slotIndex = findFreeOrEvictSlot();
        int physX = slotIndex % PHYSICAL_PAGES_PER_AXIS;
        int physY = slotIndex / PHYSICAL_PAGES_PER_AXIS;
        PhysicalSlot created = new PhysicalSlot(physX, physY, key);
        resident.put(key, created);
        slotUsed[slotIndex] = true;

        int base = (ty * VIRTUAL_TILES_PER_AXIS + tx) * 4;
        pageTableCpu[base] = (float) physX / PHYSICAL_PAGES_PER_AXIS;
        pageTableCpu[base + 1] = (float) physY / PHYSICAL_PAGES_PER_AXIS;
        pageTableCpu[base + 2] = (float) clampedMip;
        pageTableCpu[base + 3] = 1.0f;
        pageTableDirty = true;
        return created;
    }

    /** Uploads raw RGBA8 texels into a resident physical page and synchronizes the GPU indirection table. */
    public void uploadPageRgba(PhysicalSlot slot, byte[] rgba128x128) {
        ensureGlTextures();
        if (rgba128x128 == null || rgba128x128.length < PAGE_PIXELS * PAGE_PIXELS * 4) return;
        ByteBuffer buf = MemoryUtil.memAlloc(PAGE_PIXELS * PAGE_PIXELS * 4);
        try {
            buf.put(rgba128x128, 0, PAGE_PIXELS * PAGE_PIXELS * 4).flip();
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, physicalAtlasTex);
            GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0,
                    slot.physX() * PAGE_PIXELS, slot.physY() * PAGE_PIXELS,
                    PAGE_PIXELS, PAGE_PIXELS, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, buf);
        } finally {
            MemoryUtil.memFree(buf);
        }
        syncPageTableToGpu();
    }

    public void syncPageTableToGpu() {
        if (!pageTableDirty) return;
        ensureGlTextures();
        FloatBuffer fb = MemoryUtil.memAllocFloat(pageTableCpu.length);
        try {
            fb.put(pageTableCpu).flip();
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, pageTableTex);
            GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0,
                    VIRTUAL_TILES_PER_AXIS, VIRTUAL_TILES_PER_AXIS,
                    GL11C.GL_RGBA, GL11C.GL_FLOAT, fb);
        } finally {
            MemoryUtil.memFree(fb);
        }
        pageTableDirty = false;
    }

    private int findFreeOrEvictSlot() {
        for (int i = 0; i < slotUsed.length; i++) {
            if (!slotUsed[i]) return i;
        }
        // Evict least-recently-used resident page
        var it = resident.entrySet().iterator();
        var oldest = it.next();
        PhysicalSlot evicted = oldest.getValue();
        it.remove();
        VirtualPageKey oldKey = evicted.residentKey();
        int oldBase = (oldKey.tileY() * VIRTUAL_TILES_PER_AXIS + oldKey.tileX()) * 4;
        pageTableCpu[oldBase + 3] = 0.0f;
        pageTableDirty = true;
        return evicted.physY() * PHYSICAL_PAGES_PER_AXIS + evicted.physX();
    }

    private void ensureGlTextures() {
        if (pageTableTex != 0 && physicalAtlasTex != 0) return;
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);

        pageTableTex = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, pageTableTex);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_RGBA16F,
                VIRTUAL_TILES_PER_AXIS, VIRTUAL_TILES_PER_AXIS, 0,
                GL11C.GL_RGBA, GL11C.GL_FLOAT, (ByteBuffer) null);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);

        physicalAtlasTex = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, physicalAtlasTex);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8,
                ATLAS_PIXELS, ATLAS_PIXELS, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
    }

    public void clear() {
        resident.clear();
        java.util.Arrays.fill(slotUsed, false);
        java.util.Arrays.fill(pageTableCpu, 0f);
        pageTableDirty = true;
    }

    public void dispose() {
        clear();
        if (pageTableTex != 0) { GL11C.glDeleteTextures(pageTableTex); pageTableTex = 0; }
        if (physicalAtlasTex != 0) { GL11C.glDeleteTextures(physicalAtlasTex); physicalAtlasTex = 0; }
    }
}
