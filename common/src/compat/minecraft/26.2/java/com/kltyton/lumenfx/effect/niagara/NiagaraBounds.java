package com.kltyton.lumenfx.effect.niagara;

/** Source-space Niagara box in centimetres. */
public record NiagaraBounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    public static final NiagaraBounds DEFAULT = new NiagaraBounds(-100, -100, -100, 100, 100, 100);
    public NiagaraBounds {
        if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
                || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)
                || maxX < minX || maxY < minY || maxZ < minZ)
            throw new IllegalArgumentException("Invalid Niagara bounds");
    }

    public NiagaraBounds union(NiagaraBounds other) {
        return new NiagaraBounds(Math.min(minX, other.minX), Math.min(minY, other.minY), Math.min(minZ, other.minZ),
                Math.max(maxX, other.maxX), Math.max(maxY, other.maxY), Math.max(maxZ, other.maxZ));
    }

    public NiagaraBounds scaleExtent(double factor) {
        double cx = (minX + maxX) * .5, cy = (minY + maxY) * .5, cz = (minZ + maxZ) * .5;
        double x = (maxX - minX) * .5 * factor, y = (maxY - minY) * .5 * factor, z = (maxZ - minZ) * .5 * factor;
        return new NiagaraBounds(cx - x, cy - y, cz - z, cx + x, cy + y, cz + z);
    }
}
