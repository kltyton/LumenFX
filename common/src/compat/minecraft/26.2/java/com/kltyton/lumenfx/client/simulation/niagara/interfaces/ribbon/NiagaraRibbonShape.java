package com.kltyton.lumenfx.client.simulation.niagara.interfaces.ribbon;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.effect.render.RibbonSettings;
import com.kltyton.lumenfx.effect.render.RibbonSettings.ShapeVertex;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToDoubleFunction;

/** One Ribbon Interface publication. The renderer consumes only shapes with at least two points. */
public record NiagaraRibbonShape(List<ShapeVertex> vertices, boolean closed, boolean cull, boolean accurate) {
    public NiagaraRibbonShape { vertices = List.copyOf(vertices); }

    public static NiagaraRibbonShape read(JsonObject properties, boolean full, int inputs, IntToDoubleFunction read) {
        boolean closed = flag(properties, "bRibbonShapeClosed", true);
        boolean cull = flag(properties, "bRibbonUseMaterialBackfaceCulling", false);
        boolean accurate = flag(properties, "bRibbonEnableAccurateGeometry", false);
        int width = full ? 5 : 2;
        int count = (inputs - 1) / width;
        if (count < 2) return new NiagaraRibbonShape(List.of(), closed, cull, accurate);
        List<ShapeVertex> vertices = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int start = 1 + index * width;
            vertices.add(new ShapeVertex((float) read.applyAsDouble(start), (float) read.applyAsDouble(start + 1),
                    full ? (float) read.applyAsDouble(start + 2) : 0, full ? (float) read.applyAsDouble(start + 3) : 0,
                    full ? (float) read.applyAsDouble(start + 4) : 0));
        }
        if (!full) computeNormalsAndV(vertices, closed);
        return new NiagaraRibbonShape(vertices, closed, cull, accurate);
    }

    public RibbonSettings.Shape apply(RibbonSettings.Shape source) {
        return new RibbonSettings.Shape(RibbonSettings.ShapeMode.CUSTOM, source.facing(), source.widthSegments(),
                source.planeCount(), source.tubeSubdivisions(), accurate, cull, vertices, source.geometryNormals(), closed, true);
    }

    private static void computeNormalsAndV(List<ShapeVertex> vertices, boolean closed) {
        int count = vertices.size();
        int edges = closed ? count : count - 1;
        float[] distances = new float[count];
        float total = 0;
        for (int index = 0; index < edges; index++) {
            var first = vertices.get(index);
            var next = vertices.get((index + 1) % count);
            distances[index] = total;
            total += (float) Math.hypot(next.x() - first.x(), next.y() - first.y());
        }
        for (int index = 0; index < count; index++) {
            var point = vertices.get(index);
            float nx = 0, ny = 0;
            if (closed || index > 0) {
                var previous = vertices.get((index + count - 1) % count);
                nx -= point.y() - previous.y();
                ny += point.x() - previous.x();
            }
            if (closed || index < count - 1) {
                var next = vertices.get((index + 1) % count);
                nx -= next.y() - point.y();
                ny += next.x() - point.x();
            }
            float length = (float) Math.hypot(nx, ny);
            float v = total > 1.e-4f ? distances[index] / total : 0;
            vertices.set(index, new ShapeVertex(point.x(), point.y(), length > 1.e-4f ? nx / length : 0,
                    length > 1.e-4f ? ny / length : 1, v));
        }
    }

    private static boolean flag(JsonObject properties, String name, boolean fallback) {
        var value = properties.get(name);
        return value == null || value.isJsonNull() ? fallback : value.getAsBoolean();
    }
}
