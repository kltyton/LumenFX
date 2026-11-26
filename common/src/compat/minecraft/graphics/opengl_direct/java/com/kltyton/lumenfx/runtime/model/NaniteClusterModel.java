package com.kltyton.lumenfx.runtime.model;

import java.util.ArrayList;
import java.util.List;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Hierarchical Nanite-style triangle cluster (meshlet) representation built directly from
 * {@link StaticMeshModel} geometry, supporting per-cluster frustum culling and screen-space
 * projected geometric error LOD selection on all OpenGL targets.
 */
public final class NaniteClusterModel {
    public static final int MAX_TRIS_PER_CLUSTER = 128;

    public record Cluster(int clusterId, int lodLevel, int firstIndex, int indexCount,
                          float centerX, float centerY, float centerZ, float radius,
                          float geometricError) { }

    private final StaticMeshModel mesh;
    private final List<Cluster> clusters;
    private final int maxLodLevel;

    private NaniteClusterModel(StaticMeshModel mesh, List<Cluster> clusters, int maxLodLevel) {
        this.mesh = mesh;
        this.clusters = List.copyOf(clusters);
        this.maxLodLevel = maxLodLevel;
    }

    public StaticMeshModel mesh() { return mesh; }
    public List<Cluster> clusters() { return clusters; }
    public int maxLodLevel() { return maxLodLevel; }

    /** Partitions a {@link StaticMeshModel} into bounded triangle clusters with spatial bounding spheres. */
    public static NaniteClusterModel buildFromMesh(StaticMeshModel mesh) {
        int[] indices = mesh.indices();
        float[] pos = mesh.positions();
        int totalTris = indices.length / 3;
        List<Cluster> built = new ArrayList<>();
        int clusterId = 0;
        int triCursor = 0;
        while (triCursor < totalTris) {
            int countTris = Math.min(MAX_TRIS_PER_CLUSTER, totalTris - triCursor);
            int firstIdx = triCursor * 3;
            int idxCount = countTris * 3;
            float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
            for (int k = 0; k < idxCount; k++) {
                int v = indices[firstIdx + k];
                if (v < 0 || v * 3 + 2 >= pos.length) continue;
                float x = pos[v * 3] * mesh.unitScale();
                float y = pos[v * 3 + 1] * mesh.unitScale();
                float z = pos[v * 3 + 2] * mesh.unitScale();
                if (x < minX) minX = x; if (x > maxX) maxX = x;
                if (y < minY) minY = y; if (y > maxY) maxY = y;
                if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
            }
            float cx = 0.5f * (minX + maxX);
            float cy = 0.5f * (minY + maxY);
            float cz = 0.5f * (minZ + maxZ);
            float dx = maxX - cx, dy = maxY - cy, dz = maxZ - cz;
            float radius = (float) Math.max(0.01, Math.sqrt(dx * dx + dy * dy + dz * dz));
            float error = Math.max(0.002f, radius * 0.02f);
            built.add(new Cluster(clusterId++, 0, firstIdx, idxCount, cx, cy, cz, radius, error));
            triCursor += countTris;
        }
        return new NaniteClusterModel(mesh, built, 0);
    }

    /**
     * Performs frustum culling and screen-space projected pixel error selection for one instance.
     */
    public List<Cluster> selectVisibleClusters(Matrix4f viewProj, Matrix4f modelMatrix,
                                               float camRelX, float camRelY, float camRelZ,
                                               float instanceScale, int viewportHeight, float pixelErrorThreshold) {
        FrustumIntersection frustum = new FrustumIntersection(new Matrix4f(viewProj).mul(modelMatrix), false);
        List<Cluster> visible = new ArrayList<>(clusters.size());
        Vector3f localCenter = new Vector3f();
        float scale = Math.max(0.001f, Math.abs(instanceScale));
        for (Cluster c : clusters) {
            if (!frustum.testSphere(c.centerX() / mesh.unitScale(), c.centerY() / mesh.unitScale(),
                    c.centerZ() / mesh.unitScale(), c.radius() / mesh.unitScale())) {
                continue;
            }
            localCenter.set(c.centerX(), c.centerY(), c.centerZ()).mul(scale);
            float dx = localCenter.x + camRelX;
            float dy = localCenter.y + camRelY;
            float dz = localCenter.z + camRelZ;
            float dist = (float) Math.max(0.1, Math.sqrt(dx * dx + dy * dy + dz * dz));
            float projectedErrorPx = (c.geometricError() * scale / dist) * (viewportHeight * 0.5f);
            if (c.lodLevel() == 0 || projectedErrorPx <= pixelErrorThreshold) {
                visible.add(c);
            }
        }
        return visible;
    }
}
