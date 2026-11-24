package com.kltyton.lumenfx.effect.render;

import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Source ribbon curve, tessellation and independent texture-coordinate channels. */
public record RibbonSettings(double tension, double minimumSegmentLength, int maximumTessellation,
                             double tessellationAngle, boolean constantTessellation, boolean screenSpace,
                             Uv uv0, Uv uv1, Shape shape, TessellationMode tessellationMode, DrawDirection drawDirection,
                             @Nullable Key shapeInterface, boolean gpuInitialization) {
    public enum ShapeMode { PLANE, MULTI_PLANE, TUBE, CUSTOM }
    public enum Facing { SCREEN, CUSTOM, CUSTOM_SIDE_VECTOR }
    public enum TessellationMode { AUTOMATIC, CUSTOM, DISABLED }
    public enum DrawDirection { FRONT_TO_BACK, BACK_TO_FRONT }
    public RibbonSettings(double tension, double minimumSegmentLength, int maximumTessellation,
                          double tessellationAngle, boolean constantTessellation, boolean screenSpace,
                          Uv uv0, Uv uv1, Shape shape, TessellationMode tessellationMode, DrawDirection drawDirection,
                          @Nullable Key shapeInterface) {
        this(tension, minimumSegmentLength, maximumTessellation, tessellationAngle, constantTessellation, screenSpace,
                uv0, uv1, shape, tessellationMode, drawDirection, shapeInterface, false);
    }
    public RibbonSettings(double tension, double minimumSegmentLength, int maximumTessellation,
                          double tessellationAngle, boolean constantTessellation, boolean screenSpace,
                          Uv uv0, Uv uv1, Shape shape, TessellationMode tessellationMode, DrawDirection drawDirection) {
        this(tension, minimumSegmentLength, maximumTessellation, tessellationAngle, constantTessellation, screenSpace,
                uv0, uv1, shape, tessellationMode, drawDirection, null);
    }
    public RibbonSettings(double tension, double minimumSegmentLength, int maximumTessellation,
                          double tessellationAngle, boolean constantTessellation, boolean screenSpace,
                          Uv uv0, Uv uv1, Shape shape, TessellationMode tessellationMode) {
        this(tension, minimumSegmentLength, maximumTessellation, tessellationAngle, constantTessellation, screenSpace,
                uv0, uv1, shape, tessellationMode, DrawDirection.FRONT_TO_BACK);
    }
    public record ShapeVertex(float x, float y, float normalX, float normalY, float textureV) { }
    public record Shape(ShapeMode mode, Facing facing, int widthSegments, int planeCount, int tubeSubdivisions,
                        boolean accurateGeometry, boolean materialBackfaceCulling, List<ShapeVertex> vertices,
                        boolean geometryNormals, boolean customShapeClosed, boolean interfaceShape) {
        public Shape(ShapeMode mode, Facing facing, int widthSegments, int planeCount, int tubeSubdivisions,
                     boolean accurateGeometry, boolean materialBackfaceCulling, List<ShapeVertex> vertices) {
            this(mode, facing, widthSegments, planeCount, tubeSubdivisions, accurateGeometry, materialBackfaceCulling,
                    vertices, true, true, false);
        }
        public Shape { vertices = List.copyOf(vertices); }
        public ShapeMode resolvedMode() {
            return switch (mode) {
                case CUSTOM -> vertices.size() >= (interfaceShape ? 2 : 3) ? mode : ShapeMode.PLANE;
                case TUBE -> tubeSubdivisions > 2 && tubeSubdivisions <= 16 ? mode : ShapeMode.PLANE;
                case MULTI_PLANE -> planeCount > 1 && planeCount <= 16 ? mode : ShapeMode.PLANE;
                case PLANE -> mode;
            };
        }
        public boolean usesGeometryNormals() { return resolvedMode() != ShapeMode.PLANE || geometryNormals; }
        public boolean flipNormalToView() { return resolvedMode() == ShapeMode.MULTI_PLANE && !accurateGeometry; }
    }
    public RibbonSettings withShape(Shape replacement) {
        return new RibbonSettings(tension, minimumSegmentLength, maximumTessellation, tessellationAngle, constantTessellation,
                screenSpace, uv0, uv1, replacement, tessellationMode, drawDirection, shapeInterface, gpuInitialization);
    }
    public RibbonSettings(double tension, double minimumSegmentLength, int maximumTessellation,
                          double tessellationAngle, boolean constantTessellation, boolean screenSpace, Uv uv0, Uv uv1) {
        this(tension, minimumSegmentLength, maximumTessellation, tessellationAngle, constantTessellation, screenSpace,
                uv0, uv1, new Shape(ShapeMode.PLANE, Facing.SCREEN, 1, 2, 3, false, false, List.of()), TessellationMode.AUTOMATIC);
    }
    public enum Distribution { UNIFORM, LENGTH, DISTANCE, DISTANCE_FROM_START }
    public enum Edge { SMOOTH_TRANSITION, LOCKED }
    public record Uv(Distribution distribution, double tileLength, double scaleU, double scaleV,
                     double offsetU, double offsetV, Edge leadingEdge, Edge trailingEdge,
                     boolean uOverride, boolean vOverride) {
        public Uv(Distribution distribution, double tileLength, double scaleU, double scaleV,
                  double offsetU, double offsetV) {
            this(distribution, tileLength, scaleU, scaleV, offsetU, offsetV, Edge.LOCKED, Edge.LOCKED, false, false);
        }
        public Uv {
            if (distribution == null || !Double.isFinite(tileLength)
                    || (distribution == Distribution.DISTANCE || distribution == Distribution.DISTANCE_FROM_START) && tileLength == 0
                    || !Double.isFinite(scaleU) || !Double.isFinite(scaleV)
                    || !Double.isFinite(offsetU) || !Double.isFinite(offsetV) || leadingEdge == null || trailingEdge == null)
                throw new IllegalArgumentException("Invalid ribbon UV settings");
        }
        public double u(double distance, double length, double segmentFraction) {
            return (switch (distribution) {
                case UNIFORM -> segmentFraction;
                case LENGTH -> distance / length;
                case DISTANCE, DISTANCE_FROM_START -> distance / tileLength;
            }) * scaleU + offsetU;
        }
    }
    public RibbonSettings {
        if (!Double.isFinite(tension) || tension < 0 || tension > 1
                || !Double.isFinite(minimumSegmentLength) || minimumSegmentLength <= 0
                || maximumTessellation < 1 || maximumTessellation > 64
                || !Double.isFinite(tessellationAngle) || tessellationAngle < 0 || uv0 == null || uv1 == null
                || shape == null || tessellationMode == null || drawDirection == null)
            throw new IllegalArgumentException("Invalid source ribbon settings");
    }
}
