package com.kltyton.lumenfx.client.render.translucency;

import java.nio.ByteBuffer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;

/** World-aligned translucency lighting cascades in a slice atlas. */
record TranslucencyVolumeGrid(int dimension, int columns, int rows, Cascade inner, Cascade outer) {
    record Cascade(Vec3 minimum, float voxelSize) {
        void write(ByteBuffer output) {
            output.putFloat((float)minimum.x).putFloat((float)minimum.y).putFloat((float)minimum.z).putFloat(voxelSize);
        }
    }

    static TranslucencyVolumeGrid create(Matrix4fc view, Matrix4fc projection, Vec3 camera,
                                          int requestedDimension, int maximumAtlasExtent, double innerDistance,
                                          double outerDistance, boolean adaptToPerspective,
                                          float minimumFovDegrees, float snapFovDegrees) {
        int dimension = Math.clamp(requestedDimension, 4, 2048);
        int maximumTiles = maximumAtlasExtent / dimension;
        int columns = (int)Math.ceil(Math.sqrt(dimension));
        int rows = (dimension + columns - 1) / columns;
        if (columns > maximumTiles || rows > maximumTiles)
            throw new IllegalArgumentException("Translucency lighting cascades exceed the texture extent of this device");
        double fov = Math.PI / 4;
        double aspect = 1;
        if (adaptToPerspective && projection.m33() < 1f) {
            fov = Math.max(Math.atan(1.0 / projection.m00()), Math.toRadians(minimumFovDegrees));
            double snap = Math.toRadians(snapFovDegrees);
            if (snap > 0) fov += snap - fov % snap;
            aspect = projection.m11() / projection.m00();
        }
        double inner = Math.max(0, innerDistance), outer = Math.max(0, outerDistance);
        double tangent = Math.tan(fov);
        double maximumHalfWidth = Math.sqrt(Math.max(1, 3 * outer * outer));
        Vec3 origin = camera.scale(100);
        Vec3 right = new Vec3(view.m00(), view.m10(), view.m20());
        Vec3 up = new Vec3(view.m01(), view.m11(), view.m21());
        Vec3 forward = new Vec3(-view.m02(), -view.m12(), -view.m22());
        return new TranslucencyVolumeGrid(dimension, columns, rows,
                cascade(origin, right, up, forward, 0, inner, tangent, aspect, maximumHalfWidth, dimension),
                cascade(origin, right, up, forward, inner, outer, tangent, aspect, maximumHalfWidth, dimension));
    }

    private static Cascade cascade(Vec3 origin, Vec3 right, Vec3 up, Vec3 forward, double start, double end,
                                   double tangent, double aspect, double maximumHalfWidth, int dimension) {
        Vec3[] corners = new Vec3[8];
        Vec3 centre = Vec3.ZERO;
        for (int plane = 0; plane < 2; plane++) {
            double depth = plane == 0 ? start : end;
            double width = Math.min(maximumHalfWidth, depth * tangent);
            Vec3 horizontal = right.scale(width), vertical = up.scale(width / aspect);
            Vec3 planeCentre = origin.add(forward.scale(depth));
            double weight = plane == 0 ? 1.0 / 44 : 1.0 / 4.4;
            for (int corner = 0; corner < 4; corner++) {
                Vec3 point = planeCentre.add((corner & 1) == 0 ? horizontal : horizontal.scale(-1))
                        .add((corner & 2) == 0 ? vertical : vertical.scale(-1));
                corners[plane * 4 + corner] = point;
                centre = centre.add(point.scale(weight));
            }
        }
        double radiusSquared = 0;
        for (Vec3 point : corners) radiusSquared = Math.max(radiusSquared, point.distanceToSqr(centre));
        double radius = Math.sqrt(radiusSquared);
        double voxel = 2 * radius / dimension;
        if (!(voxel > 0)) return new Cascade(centre.subtract(origin), 0);
        Vec3 minimum = new Vec3(centre.x - centre.x % voxel - radius, centre.y - centre.y % voxel - radius,
                centre.z - centre.z % voxel - radius);
        return new Cascade(minimum.subtract(origin), (float)voxel);
    }

    int width() { return columns * dimension; }
    int height() { return rows * dimension; }
}
