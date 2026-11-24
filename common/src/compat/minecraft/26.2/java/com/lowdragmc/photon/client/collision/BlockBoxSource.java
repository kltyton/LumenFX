/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.collision;

import org.jetbrains.annotations.Nullable;

/** Static world geometry a query can gather boxes from. */
public interface BlockBoxSource {
    int BLOCKS = 1;
    int FLUIDS = 2;

    /**
     * Adds every box of the requested kinds that overlaps the region. Returns false when part of the region
     * could not be read on this thread, in which case the caller must not trust a miss.
     */
    boolean collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                    int mask, @Nullable BlockFilter filter, BoxBuffer out);

    /** Unwraps any floating-origin shift to retrieve the underlying {@link CollisionWorld} version, or 0. */
    static int blockVersion(@Nullable BlockBoxSource source) {
        while (source instanceof Shifted shifted) source = shifted.delegate;
        return source instanceof CollisionWorld world ? world.version() : 0;
    }

    /** Returns a view of {@code source} translated by {@code (-ox, -oy, -oz)} into FX-origin space. */
    static BlockBoxSource shifted(BlockBoxSource source, double ox, double oy, double oz) {
        if (ox == 0.0 && oy == 0.0 && oz == 0.0) return source;
        return new Shifted(source, ox, oy, oz);
    }

    final class Shifted implements BlockBoxSource {
        public final BlockBoxSource delegate;
        public final double ox, oy, oz;

        Shifted(BlockBoxSource delegate, double ox, double oy, double oz) {
            this.delegate = delegate;
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
        }

        @Override
        public boolean collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                               int mask, @Nullable BlockFilter filter, BoxBuffer out) {
            int start = out.size();
            boolean ok = delegate.collect(minX + ox, minY + oy, minZ + oz, maxX + ox, maxY + oy, maxZ + oz,
                    mask, filter, out);
            out.shift(start, -ox, -oy, -oz);
            return ok;
        }
    }
}
