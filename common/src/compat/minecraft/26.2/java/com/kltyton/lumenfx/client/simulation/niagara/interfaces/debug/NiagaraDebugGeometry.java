package com.kltyton.lumenfx.client.simulation.niagara.interfaces.debug;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/** CPU debug shapes in Niagara's source coordinate order. */
final class NiagaraDebugGeometry {
    interface Lines {
        void line(Vector3f first, Vector3f second, int rgba);
        boolean full();
    }

    private static final float TWO_PI = (float)(2.0 * Math.PI);
    private static final Quaternionf IDENTITY_ROTATION = new Quaternionf();
    private static final Vector3f UNIT_SCALE = new Vector3f(1);
    private static final int[][] BOX_EDGES = {
            {0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}
    };
    private NiagaraDebugGeometry() { }

    static void draw(String function, int[] words, Lines lines) {
        Vector3f location = vector(words, 1);
        switch (function) {
            case "DrawLine" -> lines.line(location, vector(words, 4), color(words, 7));
            case "DrawRectangle" -> rectangle(lines, location, vector(words, 4), vector(words, 7),
                    number(words, 10), number(words, 11), words[12], words[13], color(words, 14), words[18] != 0);
            case "DrawCircle" -> circle(lines, location, vector(words, 4), vector(words, 7), number(words, 10),
                    Math.clamp(words[11], 4, 16), color(words, 12));
            case "DrawBox" -> box(lines, location, quaternion(words, 4), vector(words, 8), color(words, 11));
            case "DrawSphere" -> sphere(lines, location, number(words, 4), Math.clamp(words[5], 4, 16), color(words, 6));
            case "DrawCylinder" -> cylinder(lines, location, alignUp(vector(words, 4), true), number(words, 7),
                    number(words, 8), words[9], words[10], color(words, 11));
            case "DrawCone" -> cone(lines, location, alignUp(vector(words, 4), false), number(words, 7),
                    number(words, 8), number(words, 9), Math.clamp(words[10], 2, 32), Math.clamp(words[11], 3, 32), color(words, 12));
            case "DrawTorus" -> torus(lines, location, normalized(vector(words, 4)), number(words, 7),
                    number(words, 8), Math.clamp(words[9], 3, 32), Math.clamp(words[10], 3, 32), color(words, 11));
            case "DrawCoordinateSystem" -> coordinates(lines, location, quaternion(words, 4), number(words, 8));
            case "DrawGrid2D" -> grid(lines, location, quaternion(words, 4), new Vector3f(number(words, 8), number(words, 9), 0),
                    words[10], words[11], 0, color(words, 12), false);
            case "DrawGrid3D" -> grid(lines, location, quaternion(words, 4), vector(words, 8), words[11], words[12],
                    words[13], color(words, 14), true);
            default -> throw new IllegalArgumentException("Unknown Niagara Debug Draw function: " + function);
        }
    }

    private static void box(Lines lines, Vector3f center, Quaternionf rotation, Vector3f extent, int rgba) {
        box(lines, center, rotation, extent, IDENTITY_ROTATION, UNIT_SCALE, rgba);
    }

    static void box(Lines lines, Vector3f center, Quaternionf rotation, Vector3f extent,
                    Quaternionf worldRotation, Vector3f worldScale, int rgba) {
        Vector3f zero = new Vector3f();
        Vector3f[] corners = {
                point(zero, rotation, extent.x, extent.y, extent.z), point(zero, rotation, -extent.x, extent.y, extent.z),
                point(zero, rotation, -extent.x, -extent.y, extent.z), point(zero, rotation, extent.x, -extent.y, extent.z),
                point(zero, rotation, extent.x, extent.y, -extent.z), point(zero, rotation, -extent.x, extent.y, -extent.z),
                point(zero, rotation, -extent.x, -extent.y, -extent.z), point(zero, rotation, extent.x, -extent.y, -extent.z)
        };
        for (Vector3f corner : corners) worldRotation.transform(corner.mul(worldScale)).add(center);
        for (int[] edge : BOX_EDGES) lines.line(corners[edge[0]], corners[edge[1]], rgba);
    }

    static void circle(Lines lines, Vector3f center, Vector3f x, Vector3f y, float radius, int segments, int rgba) {
        x.mul(radius); y.mul(radius);
        float step = TWO_PI / segments, angle = 0;
        Vector3f previous = new Vector3f(center).add(x);
        for (int segment = 0; segment < segments && !lines.full(); segment++) {
            angle += step;
            Vector3f next = new Vector3f(center).fma(cos(angle), x).fma(sin(angle), y);
            lines.line(previous, next, rgba);
            previous = next;
        }
    }

    static void rectangle(Lines lines, Vector3f center, Vector3f x, Vector3f y, float halfX, float halfY,
                                  int segmentsX, int segmentsY, int rgba, boolean unbounded) {
        int start = unbounded ? 1 : 0;
        segmentsX = Math.clamp(segmentsX, unbounded ? 2 : 1, 16);
        segmentsY = Math.clamp(segmentsY, unbounded ? 2 : 1, 16);
        Vector3f lengthX = normalized(x).mul(halfX * 2), lengthY = normalized(y).mul(halfY * 2);
        Vector3f corner = new Vector3f(center).fma(-.5f, lengthX).fma(-.5f, lengthY);
        for (int index = start; index <= segmentsX - start && !lines.full(); index++)
            along(lines, corner, lengthX, (float)index / segmentsX, lengthY, rgba);
        for (int index = start; index <= segmentsY - start && !lines.full(); index++)
            along(lines, corner, lengthY, (float)index / segmentsY, lengthX, rgba);
    }

    private static void sphere(Lines lines, Vector3f center, float radius, int segments, int rgba) {
        sphere(lines, center, new Vector3f(radius), IDENTITY_ROTATION, IDENTITY_ROTATION, UNIT_SCALE,
                segments, true, true, true, rgba);
    }

    static void sphere(Lines lines, Vector3f center, Vector3f radii, Quaternionf rotation,
                       Quaternionf worldRotation, Vector3f worldScale, int segments,
                       boolean wholeX, boolean wholeY, boolean wholeZ, int rgba) {
        float step = TWO_PI / segments, longitude = 0;
        for (int x = 0; x < segments && !lines.full(); x++) {
            float nextLongitude = longitude + step, latitude = 0;
            for (int y = 0; y < segments && !lines.full(); y++) {
                float nextLatitude = latitude + step;
                Vector3f origin = spherePoint(center, radii, rotation, worldRotation, worldScale,
                        longitude, latitude, wholeX, wholeY, wholeZ);
                lines.line(origin, spherePoint(center, radii, rotation, worldRotation, worldScale,
                        nextLongitude, latitude, wholeX, wholeY, wholeZ), rgba);
                lines.line(origin, spherePoint(center, radii, rotation, worldRotation, worldScale,
                        longitude, nextLatitude, wholeX, wholeY, wholeZ), rgba);
                latitude = nextLatitude;
            }
            longitude = nextLongitude;
        }
    }

    private static Vector3f spherePoint(Vector3f center, Vector3f radii, Quaternionf rotation,
                                        Quaternionf worldRotation, Vector3f worldScale, float x, float y,
                                        boolean wholeX, boolean wholeY, boolean wholeZ) {
        float latitude = cos(y);
        Vector3f vertex = new Vector3f(cos(x) * latitude, sin(y), sin(x) * latitude);
        if (!wholeX) vertex.x = Math.abs(vertex.x);
        if (!wholeY) vertex.y = Math.abs(vertex.y);
        if (!wholeZ) vertex.z = Math.abs(vertex.z);
        return worldRotation.transform(rotation.transform(vertex.mul(radii)).mul(worldScale)).add(center);
    }

    private static void cylinder(Lines lines, Vector3f center, Quaternionf rotation, float height, float radius,
                                 int heightSegments, int radialSegments, int rgba) {
        cylinder(lines, center, rotation, height, radius, heightSegments, radialSegments, rgba, false, false, UNIT_SCALE);
    }

    static void cylinder(Lines lines, Vector3f center, Quaternionf rotation, float height, float radius,
                         int heightSegments, int radialSegments, int rgba, boolean halfX, boolean halfY, Vector3f scale) {
        if (heightSegments == 0 || radialSegments <= 0) return;
        rings(lines, center, rotation, height, radius, radius, heightSegments, radialSegments, rgba,
                halfX, halfY, scale, height * .5f);
    }

    private static void cone(Lines lines, Vector3f center, Quaternionf rotation, float height, float top, float bottom,
                             int heightSegments, int radialSegments, int rgba) {
        cone(lines, center, rotation, height, top, bottom, heightSegments, radialSegments, rgba, UNIT_SCALE, true);
    }

    static void cone(Lines lines, Vector3f center, Quaternionf rotation, float height, float top, float bottom,
                     int heightSegments, int radialSegments, int rgba, Vector3f scale, boolean centered) {
        rings(lines, center, rotation, height, top, bottom, Math.clamp(heightSegments, 2, 32) - 1,
                Math.clamp(radialSegments, 3, 32), rgba, false, false, scale, centered ? height * .5f : 0);
    }

    private static void rings(Lines lines, Vector3f center, Quaternionf rotation, float height, float top, float bottom,
                              int rings, int radialSegments, int rgba, boolean halfX, boolean halfY,
                              Vector3f scale, float half) {
        float step = TWO_PI / radialSegments;
        Vector3f previous = new Vector3f(0, 1, 0);
        for (int index = 0; index < radialSegments && !lines.full(); index++) {
            float angle = step * (index + 1);
            Vector3f next = new Vector3f(sin(angle), cos(angle), 0);
            if (halfX) next.x = Math.abs(next.x);
            if (halfY) next.y = Math.abs(next.y);
            lines.line(ringPoint(center, rotation, next, bottom, -half, scale),
                    ringPoint(center, rotation, next, top, height - half, scale), rgba);
            for (long ring = 0; ring <= Math.max(0, rings) && !lines.full(); ring++) {
                float alpha = ring == 0 ? 0 : (float)ring / rings;
                float radius = bottom + (top - bottom) * alpha, z = height * alpha - half;
                lines.line(ringPoint(center, rotation, previous, radius, z, scale),
                        ringPoint(center, rotation, next, radius, z, scale), rgba);
            }
            previous = next;
        }
    }

    private static Vector3f ringPoint(Vector3f center, Quaternionf rotation, Vector3f direction, float radius, float z, Vector3f scale) {
        return rotation.transform(new Vector3f(direction.x * radius, direction.y * radius, z).mul(scale)).add(center);
    }

    static void torus(Lines lines, Vector3f center, Vector3f axis, float majorRadius, float minorRadius,
                              int majorSegments, int minorSegments, int rgba) {
        Quaternionf rotation = alignUp(axis, true);
        Vector3f x = rotation.transform(new Vector3f(1, 0, 0)), y = rotation.transform(new Vector3f(0, 1, 0));
        float majorStep = TWO_PI / majorSegments, minorStep = TWO_PI / minorSegments;
        Vector3f previousMajor = x;
        float major = 0;
        for (int majorIndex = 0; majorIndex < majorSegments && !lines.full(); majorIndex++) {
            major += majorStep;
            Vector3f nextMajor = new Vector3f(x).mul(cos(major)).fma(sin(major), y);
            float previousMinor = 0;
            for (int minorIndex = 0; minorIndex < minorSegments && !lines.full(); minorIndex++) {
                float minor = previousMinor + minorStep;
                Vector3f next = torusPoint(center, nextMajor, axis, majorRadius, minorRadius, minor);
                lines.line(torusPoint(center, nextMajor, axis, majorRadius, minorRadius, previousMinor), next, rgba);
                lines.line(torusPoint(center, previousMajor, axis, majorRadius, minorRadius, minor), next, rgba);
                previousMinor = minor;
            }
            previousMajor = nextMajor;
        }
    }

    private static Vector3f torusPoint(Vector3f center, Vector3f radial, Vector3f axis, float major, float minor, float angle) {
        return new Vector3f(center).fma(major + minor * cos(angle), radial).fma(minor * sin(angle), axis);
    }

    static void coordinates(Lines lines, Vector3f center, Quaternionf rotation, float length) {
        lines.line(center, point(center, rotation, length, 0, 0), 0xff0000ff);
        lines.line(center, point(center, rotation, 0, length, 0), 0x00ff00ff);
        lines.line(center, point(center, rotation, 0, 0, length), 0x0000ffff);
    }

    static void grid(Lines lines, Vector3f center, Quaternionf rotation, Vector3f extent,
                             int cellsX, int cellsY, int cellsZ, int rgba, boolean volume) {
        if (cellsX <= 0 || cellsY <= 0 || volume && cellsZ <= 0) return;
        Vector3f lengthX = rotation.transform(new Vector3f(extent.x * 2, 0, 0));
        Vector3f lengthY = rotation.transform(new Vector3f(0, extent.y * 2, 0));
        Vector3f lengthZ = rotation.transform(new Vector3f(0, 0, extent.z * 2));
        Vector3f corner = new Vector3f(center).sub(rotation.transform(new Vector3f(extent)));
        for (long x = 0; x <= cellsX && !lines.full(); x++) {
            Vector3f alongX = new Vector3f(corner).fma((float)x / cellsX, lengthX);
            for (long y = 0; y <= cellsY && !lines.full(); y++) {
                Vector3f alongY = new Vector3f(corner).fma((float)y / cellsY, lengthY);
                if (!volume) {
                    lines.line(alongX, new Vector3f(alongX).add(lengthY), rgba);
                    lines.line(alongY, new Vector3f(alongY).add(lengthX), rgba);
                    continue;
                }
                for (long z = 0; z <= cellsZ && !lines.full(); z++) {
                    float fraction = (float)z / cellsZ;
                    along(lines, alongX, lengthZ, fraction, lengthY, rgba);
                    along(lines, alongY, lengthZ, fraction, lengthX, rgba);
                    along(lines, alongX, lengthY, (float)y / cellsY, lengthZ, rgba);
                    along(lines, alongX, lengthZ, fraction, lengthY, rgba);
                }
            }
        }
    }

    private static void along(Lines lines, Vector3f corner, Vector3f offset, float fraction, Vector3f direction, int rgba) {
        Vector3f start = new Vector3f(corner).fma(fraction, offset);
        lines.line(start, new Vector3f(start).add(direction), rgba);
    }

    static Quaternionf alignUp(Vector3f axis, boolean unit) {
        float normal = unit ? 1f : axis.length(), w = normal + axis.z;
        return w >= 1.e-6f * normal && w != 0 ? new Quaternionf(-axis.y, axis.x, 0, w).normalize()
                : new Quaternionf(1, 0, 0, 0);
    }

    static Vector3f normalized(Vector3f value) {
        return value.lengthSquared() > 1.e-8f ? value.normalize() : value.zero();
    }

    private static Vector3f point(Vector3f origin, Quaternionf q, float x, float y, float z) {
        return q.transform(new Vector3f(x, y, z)).add(origin);
    }

    private static int color(int[] words, int at) {
        int rgba = 0;
        for (int component = 0; component < 4; component++)
            rgba = rgba << 8 | (int)(Math.clamp(number(words, at + component), 0f, 1f) * 255f);
        return rgba;
    }
    private static Quaternionf quaternion(int[] v, int i) { return new Quaternionf(number(v, i), number(v, i + 1), number(v, i + 2), number(v, i + 3)); }
    private static Vector3f vector(int[] v, int i) { return new Vector3f(number(v, i), number(v, i + 1), number(v, i + 2)); }
    private static float number(int[] words, int at) { return Float.intBitsToFloat(words[at]); }
    private static float sin(float radians) { return (float)Math.sin(radians); }
    private static float cos(float radians) { return (float)Math.cos(radians); }
}
