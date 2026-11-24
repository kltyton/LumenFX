package com.kltyton.lumenfx.client.render.niagara.gpu.scene;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/** Composes the source page-table and narrow-band atlas from immutable scene geometry. */
final class NiagaraVoxelDistanceFieldAtlas {
    private static final int PAGE_VOXELS = 7;
    private static final int STORED_PAGE_VOXELS = 8;
    private static final int ATLAS_PAGES = 128;
    private static final int PAGES_PER_LAYER = ATLAS_PAGES * ATLAS_PAGES;
    static final int ATLAS_SIZE = ATLAS_PAGES * STORED_PAGE_VOXELS;
    static final int INFLUENCE_VOXELS = 4;
    private static final int INVALID_PAGE = -1;
    private static final int BOXES_PER_LEAF = 8;

    record Clipmap(double x, double y, double z, double extent, int pages) {
        double minX() { return x - extent; }
        double minY() { return y - extent; }
        double minZ() { return z - extent; }
        double pageSize() { return 2 * extent / pages; }
    }

    record Layout(int resolution, int pages, int mipFactor, List<Clipmap> clips) {
        Layout { clips = List.copyOf(clips); }
        int mipResolution() { return Math.ceilDiv(resolution, mipFactor); }
    }

    record Atlas(Layout layout, int depth, byte[] distance, int[] pageTable) { }
    private record Page(Clipmap clip, double x, double y, double z) { }

    private NiagaraVoxelDistanceFieldAtlas() { }

    static Layout layout(double x, double y, double z, double viewDistance, int resolution, int clipmaps, float exponent, int mipFactor) {
        if (resolution <= 0 || clipmaps < 1 || clipmaps > 6 || !Float.isFinite(exponent) || exponent <= 0
                || !Double.isFinite(viewDistance) || viewDistance <= 0 || mipFactor < 1 || mipFactor > 8)
            throw new IllegalArgumentException("Invalid source global distance field settings");
        int snappedResolution = Math.multiplyExact(Math.ceilDiv(resolution, 4 * PAGE_VOXELS), 4 * PAGE_VOXELS);
        int pages = snappedResolution / PAGE_VOXELS;
        double extent = viewDistance / Math.pow(exponent, 3);
        var clips = new ArrayList<Clipmap>(clipmaps);
        for (int level = 0; level < clipmaps; level++) {
            double step = 2 * extent / pages;
            if (!Double.isFinite(step) || step <= 0) throw new IllegalArgumentException("Invalid source global distance field extent");
            clips.add(new Clipmap(snap(x, step), snap(y, step), snap(z, step), extent, pages));
            extent *= exponent;
        }
        return new Layout(snappedResolution, pages, mipFactor, clips);
    }

    private static double snap(double position, double step) { return Math.floor(position / step + .5) * step; }

    static Atlas compose(Layout layout, List<AABB> sourceBoxes, List<NiagaraMeshDistanceVolume> meshFields,
                         BooleanSupplier cancelled) {
        var geometry = new Geometry(sourceBoxes, meshFields, cancelled);
        var pages = new ArrayList<Page>();
        int side = layout.pages();
        int clipTableSize = Math.multiplyExact(Math.multiplyExact(side, side), side);
        var pageTable = new int[Math.multiplyExact(clipTableSize, layout.clips().size())];
        Arrays.fill(pageTable, INVALID_PAGE);
        for (int clipIndex = 0; clipIndex < layout.clips().size(); clipIndex++) {
            Clipmap clip = layout.clips().get(clipIndex);
            double pageSize = clip.pageSize();
            double influence = pageSize / PAGE_VOXELS * INFLUENCE_VOXELS;
            for (int z = 0; z < side; z++) {
                checkCancellation(cancelled);
                double pageZ = clip.minZ() + z * pageSize;
                for (int y = 0; y < side; y++) {
                    double pageY = clip.minY() + y * pageSize;
                    for (int x = 0; x < side; x++) {
                        double pageX = clip.minX() + x * pageSize;
                        if (!geometry.overlaps(pageX - influence, pageY - influence, pageZ - influence,
                                pageX + pageSize + influence, pageY + pageSize + influence, pageZ + pageSize + influence)) continue;
                        int tableIndex = clipIndex * clipTableSize + (z * side + y) * side + x;
                        pageTable[tableIndex] = pages.size();
                        pages.add(new Page(clip, pageX, pageY, pageZ));
                    }
                }
            }
        }
        int depth = Math.multiplyExact(STORED_PAGE_VOXELS, Math.max(1, Math.ceilDiv(pages.size(), PAGES_PER_LAYER)));
        var atlas = new byte[Math.multiplyExact(ATLAS_SIZE * ATLAS_SIZE, depth)];
        for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
            checkCancellation(cancelled);
            writePage(atlas, pages.get(pageIndex), pageIndex, geometry);
        }
        return new Atlas(layout, depth, atlas, pageTable);
    }

    private static void writePage(byte[] atlas, Page page, int index, Geometry geometry) {
        int atlasX = (index % ATLAS_PAGES) * STORED_PAGE_VOXELS;
        int atlasY = (index / ATLAS_PAGES % ATLAS_PAGES) * STORED_PAGE_VOXELS;
        int atlasZ = (index / PAGES_PER_LAYER) * STORED_PAGE_VOXELS;
        double step = page.clip().pageSize() / PAGE_VOXELS;
        double influence = step * INFLUENCE_VOXELS;
        for (int z = 0; z < STORED_PAGE_VOXELS; z++) {
            double worldZ = page.z() + z * step;
            for (int y = 0; y < STORED_PAGE_VOXELS; y++) {
                double worldY = page.y() + y * step;
                int start = ((atlasZ + z) * ATLAS_SIZE + atlasY + y) * ATLAS_SIZE + atlasX;
                for (int x = 0; x < STORED_PAGE_VOXELS; x++) {
                    double distance = geometry.distance(page.x() + x * step, worldY, worldZ, influence);
                    atlas[start + x] = (byte)Math.round(Math.clamp(distance / (2 * influence) + .5, 0, 1) * 255);
                }
            }
        }
    }

    static float samplePage(Atlas source, int clip, float u, float v, float w) {
        int pages = source.layout().pages();
        float x = u * pages, y = v * pages, z = w * pages;
        int pageX = (int)x, pageY = (int)y, pageZ = (int)z;
        int page = source.pageTable()[((clip * pages + pageZ) * pages + pageY) * pages + pageX];
        if (page == INVALID_PAGE) return 1f;
        x = (x - pageX) * PAGE_VOXELS;
        y = (y - pageY) * PAGE_VOXELS;
        z = (z - pageZ) * PAGE_VOXELS;
        int tileX = (page % ATLAS_PAGES) * STORED_PAGE_VOXELS;
        int tileY = (page / ATLAS_PAGES % ATLAS_PAGES) * STORED_PAGE_VOXELS;
        int tileZ = (page / PAGES_PER_LAYER) * STORED_PAGE_VOXELS;
        int x0 = tileX + (int)x, y0 = tileY + (int)y, z0 = tileZ + (int)z;
        int x1 = Math.min(x0 + 1, tileX + PAGE_VOXELS), y1 = Math.min(y0 + 1, tileY + PAGE_VOXELS);
        int z1 = Math.min(z0 + 1, tileZ + PAGE_VOXELS);
        float fx = x - (int)x, fy = y - (int)y, fz = z - (int)z;
        float front = lerp(fy, lerp(fx, texel(source, x0, y0, z0), texel(source, x1, y0, z0)),
                lerp(fx, texel(source, x0, y1, z0), texel(source, x1, y1, z0)));
        float back = lerp(fy, lerp(fx, texel(source, x0, y0, z1), texel(source, x1, y0, z1)),
                lerp(fx, texel(source, x0, y1, z1), texel(source, x1, y1, z1)));
        return lerp(fz, front, back);
    }

    private static float texel(Atlas source, int x, int y, int z) {
        return Byte.toUnsignedInt(source.distance()[(z * ATLAS_SIZE + y) * ATLAS_SIZE + x]) * (1f / 255);
    }
    private static float lerp(float alpha, float start, float end) { return start + (end - start) * alpha; }

    static void checkCancellation(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new CancellationException("Niagara world distance field source changed");
    }

    private static final class Geometry {
        private record Primitive(AABB bounds, @Nullable NiagaraMeshDistanceVolume mesh) {
            double distance(double x, double y, double z, double maximum) {
                if (mesh != null) return mesh.distance(x, y, z, Math.max(0, maximum));
                double dx = gap(x, bounds.minX, bounds.maxX), dy = gap(y, bounds.minY, bounds.maxY);
                double dz = gap(z, bounds.minZ, bounds.maxZ);
                double outsideX = Math.max(dx, 0), outsideY = Math.max(dy, 0), outsideZ = Math.max(dz, 0);
                return Math.sqrt(outsideX * outsideX + outsideY * outsideY + outsideZ * outsideZ)
                        + Math.min(Math.max(dx, Math.max(dy, dz)), 0);
            }
        }

        private record Node(AABB bounds, int first, int end, @Nullable Node left, @Nullable Node right) { }
        private final Primitive[] primitives;
        private final @Nullable Node root;
        private final BooleanSupplier cancelled;

        Geometry(List<AABB> boxes, List<NiagaraMeshDistanceVolume> meshes, BooleanSupplier cancelled) {
            this.cancelled = cancelled;
            primitives = new Primitive[Math.addExact(boxes.size(), meshes.size())];
            int index = 0;
            for (AABB box : boxes) primitives[index++] = new Primitive(box, null);
            for (var mesh : meshes) primitives[index++] = new Primitive(mesh.bounds(), mesh);
            root = primitives.length == 0 ? null : partition(0, primitives.length);
        }

        private Node partition(int first, int end) {
            checkCancellation(cancelled);
            AABB bounds = primitives[first].bounds();
            for (int i = first + 1; i < end; i++) bounds = bounds.minmax(primitives[i].bounds());
            if (end - first <= BOXES_PER_LEAF) return new Node(bounds, first, end, null, null);
            double x = bounds.maxX - bounds.minX, y = bounds.maxY - bounds.minY, z = bounds.maxZ - bounds.minZ;
            Comparator<Primitive> order = x >= y && x >= z
                    ? Comparator.comparingDouble(primitive -> primitive.bounds().minX + primitive.bounds().maxX)
                    : y >= z ? Comparator.comparingDouble(primitive -> primitive.bounds().minY + primitive.bounds().maxY)
                    : Comparator.comparingDouble(primitive -> primitive.bounds().minZ + primitive.bounds().maxZ);
            Arrays.sort(primitives, first, end, order);
            int middle = (first + end) >>> 1;
            return new Node(bounds, first, end, partition(first, middle), partition(middle, end));
        }

        boolean overlaps(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            return root != null && overlaps(root, minX, minY, minZ, maxX, maxY, maxZ);
        }

        private boolean overlaps(Node node, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            if (!node.bounds().intersects(minX, minY, minZ, maxX, maxY, maxZ)) return false;
            if (node.left() != null)
                return overlaps(node.left(), minX, minY, minZ, maxX, maxY, maxZ)
                        || overlaps(node.right(), minX, minY, minZ, maxX, maxY, maxZ);
            for (int i = node.first(); i < node.end(); i++)
                if (primitives[i].bounds().intersects(minX, minY, minZ, maxX, maxY, maxZ)) return true;
            return false;
        }

        double distance(double x, double y, double z, double limit) {
            return root == null ? limit : distance(root, x, y, z, limit);
        }

        private double distance(Node node, double x, double y, double z, double best) {
            double outside = Math.max(0, best);
            if (outsideSquared(node.bounds(), x, y, z) > outside * outside) return best;
            if (node.left() != null) {
                Node left = node.left(), right = node.right();
                if (outsideSquared(left.bounds(), x, y, z) > outsideSquared(right.bounds(), x, y, z)) {
                    Node swap = left;
                    left = right;
                    right = swap;
                }
                return distance(right, x, y, z, distance(left, x, y, z, best));
            }
            for (int i = node.first(); i < node.end(); i++)
                best = Math.min(best, primitives[i].distance(x, y, z, best));
            return best;
        }

        private static double outsideSquared(AABB box, double x, double y, double z) {
            double dx = Math.max(0, gap(x, box.minX, box.maxX));
            double dy = Math.max(0, gap(y, box.minY, box.maxY));
            double dz = Math.max(0, gap(z, box.minZ, box.maxZ));
            return dx * dx + dy * dy + dz * dz;
        }

        private static double gap(double position, double min, double max) { return Math.max(min - position, position - max); }
    }
}
