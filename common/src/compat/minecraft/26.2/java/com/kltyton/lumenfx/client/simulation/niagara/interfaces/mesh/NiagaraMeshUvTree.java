package com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh;

import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** One static mesh LOD's source UV quadtree and its word-addressed GPU representation. */
final class NiagaraMeshUvTree {
    private static final int CAPACITY = 8, MAX_DEPTH = 8;
    private static final double DUPLICATE_THRESHOLD = .25;
    private static final int[] VISIT_ORDER = {2, 3, 0, 1};

    record Hit(int triangle, double a, double b, double c) { }
    private record Point(double x, double y) {
        boolean finite() { return Double.isFinite(x) && Double.isFinite(y); }
    }
    private record Box(double minX, double minY, double maxX, double maxY) {
        double x() { return (minX + maxX) * .5; }
        double y() { return (minY + maxY) * .5; }
        double area() { return (maxX - minX) * (maxY - minY); }
        double overlapArea(Box other) {
            return Math.max(0, Math.min(maxX, other.maxX) - Math.max(minX, other.minX))
                    * Math.max(0, Math.min(maxY, other.maxY) - Math.max(minY, other.minY));
        }
        boolean overlaps(Box other) {
            return minX <= other.maxX && maxX >= other.minX && minY <= other.maxY && maxY >= other.minY;
        }
    }
    private record Triangle(int index, Point a, Point b, Point c, Box bounds, double determinant) {
        Hit coordinates(double x, double y) {
            double bx = b.x - a.x, by = b.y - a.y, cx = c.x - a.x, cy = c.y - a.y;
            double px = x - a.x, py = y - a.y;
            double second = (px * cy - py * cx) / determinant;
            double third = (bx * py - by * px) / determinant;
            return new Hit(index, 1 - second - third, second, third);
        }

        boolean intersects(Box box) {
            return !separates(a, b, box) && !separates(b, c, box) && !separates(c, a, box);
        }

        private boolean separates(Point first, Point second, Box box) {
            double nx = first.y - second.y, ny = second.x - first.x;
            double p0 = nx * a.x + ny * a.y, p1 = nx * b.x + ny * b.y, p2 = nx * c.x + ny * c.y;
            double min = nx * (nx < 0 ? box.maxX : box.minX) + ny * (ny < 0 ? box.maxY : box.minY);
            double max = nx * (nx < 0 ? box.minX : box.maxX) + ny * (ny < 0 ? box.minY : box.maxY);
            return min > Math.max(p0, Math.max(p1, p2)) || max < Math.min(p0, Math.min(p1, p2));
        }
    }

    private static final class Node {
        private final Box area;
        private List<Triangle> triangles = new ArrayList<>();
        private Node[] children;

        Node(Box area) { this.area = area; }

        void insert(Triangle triangle, int depth) {
            if (children == null) {
                if (triangles.size() < CAPACITY || depth >= MAX_DEPTH) {
                    triangles.add(triangle);
                    return;
                }
                split();
            }
            int touched = quadrants(triangle.bounds());
            if (Integer.bitCount(touched) > 1 && triangle.bounds().area() / area.area() > DUPLICATE_THRESHOLD) {
                triangles.add(triangle);
                return;
            }
            for (int child : VISIT_ORDER) if ((touched & 1 << child) != 0) children[child].insert(triangle, depth + 1);
        }

        private void split() {
            double x = area.x(), y = area.y();
            children = new Node[]{
                    new Node(new Box(area.minX, y, x, area.maxY)),
                    new Node(new Box(x, y, area.maxX, area.maxY)),
                    new Node(new Box(area.minX, area.minY, x, y)),
                    new Node(new Box(x, area.minY, area.maxX, y))
            };
            var keep = new ArrayList<Triangle>();
            for (var triangle : triangles) {
                int touched = quadrants(triangle.bounds());
                if (Integer.bitCount(touched) > 1 && area.overlapArea(triangle.bounds()) / area.area() > DUPLICATE_THRESHOLD)
                    keep.add(triangle);
                else for (int child : VISIT_ORDER) if ((touched & 1 << child) != 0) children[child].triangles.add(triangle);
            }
            triangles = keep;
        }

        private int quadrants(Box box) {
            double x = area.x(), y = area.y();
            boolean left = box.minX <= x, right = box.maxX >= x, bottom = box.minY <= y, top = box.maxY >= y;
            return (left && top ? 1 : 0) | (right && top ? 2 : 0) | (left && bottom ? 4 : 0) | (right && bottom ? 8 : 0);
        }

        @Nullable Hit find(Box box, boolean point, float tolerance) {
            for (var triangle : triangles) {
                if (!triangle.bounds().overlaps(box)) continue;
                if (!point && !triangle.intersects(box)) continue;
                Hit hit = triangle.coordinates(box.x(), box.y());
                if (!point || Math.min(hit.a(), Math.min(hit.b(), hit.c())) > -tolerance
                        && Math.max(hit.a(), Math.max(hit.b(), hit.c())) < 1f + tolerance) return hit;
            }
            if (children != null) {
                int touched = quadrants(box);
                for (int child : VISIT_ORDER) if ((touched & 1 << child) != 0) {
                    Hit hit = children[child].find(box, point, tolerance);
                    if (hit != null) return hit;
                }
            }
            return null;
        }

        int words() {
            int words = Math.addExact(5, triangles.size());
            if (children != null) for (Node child : children) words = Math.addExact(words, child.words());
            return words;
        }

        int freeze(int[] output, int at) {
            for (int index = 0; index < 4; index++) output[at + index] = -1;
            output[at + 4] = triangles.size();
            int next = at + 5;
            for (var triangle : triangles) output[next++] = triangle.index();
            if (children != null) for (int index = 0; index < 4; index++) {
                output[at + index] = next;
                next = children[index].freeze(output, next);
            }
            return next;
        }
    }

    private final Node root = new Node(new Box(0, 0, 1, 1));
    private final int channel;
    private final int[] gpuWords;

    NiagaraMeshUvTree(StaticMeshGeometry geometry, int channel) {
        this.channel = channel;
        for (int offset = 0; offset < geometry.indexCount(); offset += 3) {
            Point a = uv(geometry, geometry.index(offset), channel);
            Point b = uv(geometry, geometry.index(offset + 1), channel);
            Point c = uv(geometry, geometry.index(offset + 2), channel);
            if (!a.finite() || !b.finite() || !c.finite()) continue;
            double det = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
            if (Math.abs(det) < 1.e-8) continue;
            root.insert(new Triangle(offset / 3, a, b, c, new Box(Math.min(a.x, Math.min(b.x, c.x)),
                    Math.min(a.y, Math.min(b.y, c.y)), Math.max(a.x, Math.max(b.x, c.x)),
                    Math.max(a.y, Math.max(b.y, c.y))), det), 0);
        }
        gpuWords = new int[root.words()];
        root.freeze(gpuWords, 0);
    }

    private static Point uv(StaticMeshGeometry geometry, int vertex, int channel) {
        return new Point(geometry.textureCoordinate(channel, vertex, 0), geometry.textureCoordinate(channel, vertex, 1));
    }
    @Nullable Hit point(float u, float v, float tolerance) { return root.find(new Box(u, v, u, v), true, tolerance); }
    @Nullable Hit box(float minU, float minV, float maxU, float maxV) {
        return minU <= maxU && minV <= maxV ? root.find(new Box(minU, minV, maxU, maxV), false, 0) : null;
    }
    int channel() { return channel; }
    int byteSize() { return Math.multiplyExact(gpuWords.length, Integer.BYTES); }
    void write(ByteBuffer data) { for (int word : gpuWords) data.putInt(word); }
}
