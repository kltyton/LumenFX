package com.kltyton.lumenfx.effect.render;

import java.util.ArrayList;
import java.util.List;

/** Niagara ribbon cross-section geometry shared by CPU and GPU rendering. */
public record RibbonShapeGeometry(List<Vertex> vertices, List<Integer> edges, boolean flipNormalToView,
                                  boolean disableBackfaceCulling) {
    public record Vertex(float x, float y, float normalX, float normalY, float textureV) { }
    public RibbonShapeGeometry { vertices = List.copyOf(vertices); edges = List.copyOf(edges); }

    public static RibbonShapeGeometry from(RibbonSettings.Shape shape) {
        int width = Math.max(1, shape.widthSegments());
        List<Vertex> vertices = new ArrayList<>();
        List<Integer> edges = new ArrayList<>();
        if (shape.resolvedMode() == RibbonSettings.ShapeMode.CUSTOM) {
            var custom = shape.vertices();
            int edgeCount = custom.size() - (shape.customShapeClosed() ? 0 : 1);
            boolean hasUv = custom.stream().anyMatch(v -> Math.abs(v.textureV()) > 1.e-8f);
            for (int i = 0; i <= edgeCount; i++) {
                var v = custom.get(i % custom.size());
                float nx = v.normalX(), ny = v.normalY();
                if (Math.abs(nx) <= 1.e-8f && Math.abs(ny) <= 1.e-8f) {
                    float length = (float) Math.hypot(v.x(), v.y());
                    if (length >= 1.e-4f) { nx = v.x() / length; ny = v.y() / length; }
                    else { nx = 0; ny = 0; }
                }
                vertices.add(new Vertex(v.x(), v.y(), nx, ny, hasUv ? v.textureV() : (float) i / edgeCount));
                if (i < edgeCount) { edges.add(i); edges.add(i + 1); }
            }
            return new RibbonShapeGeometry(vertices, edges, false, !shape.materialBackfaceCulling());
        }
        if (shape.resolvedMode() == RibbonSettings.ShapeMode.TUBE) {
            int count = shape.tubeSubdivisions();
            for (int i = 0; i <= count; i++) {
                float t = (float) i / count;
                double angle = -2 * Math.PI * t;
                float nx = -(float) Math.cos(angle), ny = -(float) Math.sin(angle);
                vertices.add(new Vertex(nx * .5f, ny * .5f, nx, ny, t));
                if (i < count) { edges.add(i); edges.add(i + 1); }
            }
            return new RibbonShapeGeometry(vertices, edges, false, !shape.materialBackfaceCulling());
        }
        boolean multi = shape.resolvedMode() == RibbonSettings.ShapeMode.MULTI_PLANE;
        int planes = multi ? shape.planeCount() : 1;
        boolean accurate = multi && shape.accurateGeometry();
        int faceVertices = Math.multiplyExact(planes, width + 1);
        for (int side = 0; side < (accurate ? 2 : 1); side++) for (int plane = 0; plane < planes; plane++) {
            double angle = Math.PI * plane / planes;
            float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle), normal = side == 0 ? 1 : -1;
            for (int vertex = 0; vertex <= width; vertex++) {
                float t = (float) vertex / width, x = t - .5f;
                vertices.add(new Vertex(x * cos, x * sin, -normal * sin, normal * cos, t));
            }
        }
        for (int plane = 0; plane < planes; plane++) {
            int base = plane * (width + 1);
            for (int vertex = 0; vertex < width; vertex++) { edges.add(base + vertex); edges.add(base + vertex + 1); }
            if (accurate) for (int vertex = 0; vertex < width; vertex++) {
                edges.add(faceVertices + base + vertex + 1); edges.add(faceVertices + base + vertex);
            }
        }
        return new RibbonShapeGeometry(vertices, edges, multi && !accurate,
                !shape.materialBackfaceCulling() && !accurate);
    }
}
