package com.kltyton.lumenfx.api.client.parameter;

import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/** Host-owned terrain sources in Unreal's centimetres and Z-up axes. */
public record NiagaraLandscapeInputs(List<Landscape> landscapes) {
    public static final NiagaraLandscapeInputs EMPTY = new NiagaraLandscapeInputs(List.of());

    public NiagaraLandscapeInputs {
        landscapes = List.copyOf(landscapes);
    }

    public @Nullable Landscape find(String identity) {
        if (identity.isEmpty()) return null;
        for (var landscape : landscapes) if (SourceNames.equal(landscape.identity(), identity)) return landscape;
        return null;
    }

    public record Bounds(double minX, double minY, double maxX, double maxY) {
        public Bounds {
            if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(maxX) || !Double.isFinite(maxY)
                    || minX > maxX || minY > maxY) throw new IllegalArgumentException("Invalid Niagara landscape bounds");
        }
        public boolean overlaps(Bounds other) {
            return maxX >= other.minX && minX <= other.maxX && maxY >= other.minY && minY <= other.maxY;
        }
        public @Nullable Bounds overlap(Bounds other) {
            return overlaps(other) ? new Bounds(Math.max(minX, other.minX), Math.max(minY, other.minY),
                    Math.min(maxX, other.maxX), Math.min(maxY, other.maxY)) : null;
        }
    }

    public record RuntimeTexture(NiagaraRuntimeVirtualTexture.Material material, NiagaraRuntimeVirtualTexture texture) {
        public RuntimeTexture { Objects.requireNonNull(material); Objects.requireNonNull(texture); }
    }

    public record Landscape(String identity, Bounds bounds, List<RuntimeTexture> virtualTextures, CollisionGrid collision) {
        public Landscape {
            Objects.requireNonNull(identity);
            Objects.requireNonNull(bounds);
            Objects.requireNonNull(collision);
            virtualTextures = List.copyOf(virtualTextures);
        }
    }

    @FunctionalInterface
    public interface CollisionGrid {
        /** Capture or reuse the actual collision vertices covering the requested XY region. Called on the host thread. */
        @Nullable Grid capture(Bounds region, List<String> physicalMaterials, boolean height, boolean material);
    }

    /** Y-major rows, tile index in LWC units, and world transforms relative to that tile's centimetre origin.
     * Material byte 255 marks an unmapped physical material. */
    public static final class Grid {
        private final int width, height;
        private final Vec3 tile;
        private final Matrix4f toGrid, toWorld;
        private final float scaleX, scaleY, biasX, biasY, spacingX, spacingY;
        private final float @Nullable [] heights;
        private final byte @Nullable [] materials;

        public Grid(int width, int height, Vec3 tile, Matrix4fc toGrid, Matrix4fc toWorld,
                    float scaleX, float scaleY, float biasX, float biasY, float spacingX, float spacingY,
                    float @Nullable [] heights, byte @Nullable [] materials) {
            if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid Niagara collision grid dimensions");
            int cells = Math.multiplyExact(width, height);
            if (heights == null && materials == null || heights != null && heights.length != cells
                    || materials != null && materials.length != cells)
                throw new IllegalArgumentException("Invalid Niagara collision grid contents");
            this.width = width;
            this.height = height;
            this.tile = Objects.requireNonNull(tile);
            this.toGrid = new Matrix4f(toGrid);
            this.toWorld = new Matrix4f(toWorld);
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.biasX = biasX;
            this.biasY = biasY;
            this.spacingX = spacingX;
            this.spacingY = spacingY;
            this.heights = heights == null ? null : heights.clone();
            this.materials = materials == null ? null : materials.clone();
        }

        public int width() { return width; }
        public int height() { return height; }
        public Vec3 tile() { return tile; }
        public Matrix4f toGrid(Matrix4f target) { return target.set(toGrid); }
        public Matrix4f toWorld(Matrix4f target) { return target.set(toWorld); }
        public float scaleX() { return scaleX; }
        public float scaleY() { return scaleY; }
        public float biasX() { return biasX; }
        public float biasY() { return biasY; }
        public float spacingX() { return spacingX; }
        public float spacingY() { return spacingY; }
        public boolean hasHeight() { return heights != null; }
        public boolean hasMaterial() { return materials != null; }
        public void writeHeights(ByteBuffer destination) { destination.asFloatBuffer().put(Objects.requireNonNull(heights)); }
        public void writeMaterials(ByteBuffer destination) { destination.put(Objects.requireNonNull(materials)); }
    }
}
