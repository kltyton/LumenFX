package com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh;

import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;

/** Native skeletal adjacency groups coincident source positions and keeps the largest eight triangles. */
final class NiagaraSkeletalConnectivity {
    static final int MAX_ADJACENT = 8;
    private final int[] indices;

    private record Point(float x, float y, float z) {
        Point(StaticMeshGeometry geometry, int vertex) {
            this(zero(geometry.position(vertex, 0)), zero(geometry.position(vertex, 1)), zero(geometry.position(vertex, 2)));
        }
        private static float zero(float value) { return value == 0f ? 0f : value; }
    }

    NiagaraSkeletalConnectivity(StaticMeshGeometry geometry) {
        int vertexCount = geometry.vertexCount();
        int triangleCount = geometry.indexCount() / 3;
        indices = new int[Math.multiplyExact(vertexCount, MAX_ADJACENT)];
        Arrays.fill(indices, -1);
        var firstVertices = new HashMap<Point, Integer>();
        var redirect = new int[vertexCount];
        Arrays.setAll(redirect, vertex -> vertex);
        var sizes = new float[triangleCount];
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            int a = geometry.index(triangle * 3), b = geometry.index(triangle * 3 + 1), c = geometry.index(triangle * 3 + 2);
            var first = new Point(geometry, a);
            var second = new Point(geometry, b);
            var third = new Point(geometry, c);
            sizes[triangle] = area(first, second, third);
            insert(firstVertices, redirect, first, a, triangle, sizes);
            insert(firstVertices, redirect, second, b, triangle, sizes);
            insert(firstVertices, redirect, third, c, triangle, sizes);
        }
        for (int vertex = 0; vertex < vertexCount; vertex++) if (redirect[vertex] != vertex)
            System.arraycopy(indices, redirect[vertex] * MAX_ADJACENT, indices, vertex * MAX_ADJACENT, MAX_ADJACENT);
    }

    private void insert(HashMap<Point, Integer> firstVertices, int[] redirect, Point point, int vertex, int triangle, float[] sizes) {
        int canonical = firstVertices.computeIfAbsent(point, ignored -> vertex);
        redirect[vertex] = canonical;
        int offset = canonical * MAX_ADJACENT;
        for (int slot = 0; slot < MAX_ADJACENT; slot++) {
            int previous = indices[offset + slot];
            if (previous == -1 || sizes[triangle] > sizes[previous]) {
                if (previous != -1) System.arraycopy(indices, offset + slot, indices, offset + slot + 1, MAX_ADJACENT - slot - 1);
                indices[offset + slot] = triangle;
                return;
            }
        }
    }

    private static float area(Point a, Point b, Point c) {
        float bx = b.x - a.x, by = b.y - a.y, bz = b.z - a.z;
        float cx = c.x - a.x, cy = c.y - a.y, cz = c.z - a.z;
        float x = cy * bz - cz * by, y = cz * bx - cx * bz, z = cx * by - cy * bx;
        return .5f * (float)Math.sqrt(x * x + y * y + z * z);
    }

    void write(ByteBuffer bytes, boolean half) {
        if (half) {
            for (int triangle : indices) bytes.putShort((short)triangle);
        } else {
            for (int triangle : indices) bytes.putInt(triangle);
        }
    }
}
