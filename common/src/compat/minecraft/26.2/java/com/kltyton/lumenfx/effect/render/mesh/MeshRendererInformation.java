package com.kltyton.lumenfx.effect.render.mesh;

/** Immutable source-space mesh bounds and SubUV settings queried by Niagara. */
public final class MeshRendererInformation {
    private final int meshCount;
    private final boolean subImageBlend;
    private final float columns, rows;
    private final float[] bounds;

    public MeshRendererInformation(int meshCount, boolean subImageBlend, float columns, float rows, float[] bounds) {
        if (meshCount < 0 || (long)meshCount * 6 != bounds.length)
            throw new IllegalArgumentException("Mesh renderer information requires six bounds components per mesh");
        this.meshCount = meshCount;
        this.subImageBlend = subImageBlend;
        this.columns = columns; this.rows = rows;
        this.bounds = bounds.clone();
    }

    public int meshCount() { return meshCount; }
    public boolean subImageBlend() { return subImageBlend; }
    public float columns() { return columns; }
    public float rows() { return rows; }
    public float[] bounds() { return bounds.clone(); }

    public float minimum(int mesh, int axis) {
        return meshCount == 0 ? 0 : bounds[Math.max(0, Math.min(meshCount - 1, mesh)) * 6 + axis];
    }

    public float maximum(int mesh, int axis) {
        return meshCount == 0 ? 0 : bounds[Math.max(0, Math.min(meshCount - 1, mesh)) * 6 + 3 + axis];
    }
}
