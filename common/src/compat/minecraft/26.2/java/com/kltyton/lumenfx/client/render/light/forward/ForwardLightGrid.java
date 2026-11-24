package com.kltyton.lumenfx.client.render.light.forward;

import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/** Screen tiles and logarithmic depth cells used to index forward light records. */
record ForwardLightGrid(int width, int height, int columns, int rows, Settings settings, Depth depth) {
    private static final float DISTRIBUTION = 4.05f;
    private static final float LAST_DEPTH = 2_000_000f;
    private static final double DEFAULT_FAR_LIMIT = 1_048_576.0 / 5.0;
    private static final float MINIMUM_FAR = 1_000f;

    record Settings(int tilePixels, int slices, int entriesPerCell, boolean sharedCapacity, float maxDistanceKm,
                    boolean refineRectBounds) {
        static Settings read(NiagaraPlatformEnvironment environment) {
            int pixels = bits(environment, "r.Forward.LightGridPixelSize");
            int slices = bits(environment, "r.Forward.LightGridSizeZ");
            int entries = bits(environment, "r.Forward.MaxCulledLightsPerCell");
            if (Integer.bitCount(pixels) != 1 || pixels < 1 || slices < 2 || entries < 1)
                throw new IllegalArgumentException("Invalid forward light grid dimensions or capacity");
            return new Settings(pixels, slices, entries, bits(environment, "r.Forward.LightLinkedListCulling") != 0,
                    Float.intBitsToFloat(bits(environment, "r.LightCulling.MaxDistanceOverrideKilometers")),
                    bits(environment, "r.Forward.LightGridDebug.RectLightBounds") != 0);
        }

        private static int bits(NiagaraPlatformEnvironment environment, String name) {
            return Objects.requireNonNull(environment.console(name), name).bits();
        }
    }

    record Depth(float near, float scale, float offset) {
        float edge(int slice, int slices) {
            if (slice == 0) return near;
            if (slice == slices) return LAST_DEPTH;
            return ((float)Math.pow(2.0, slice / DISTRIBUTION) - offset) / scale;
        }
    }

    record Plane(Vec3 normal, double offset) {
        boolean excludes(double x, double y, double z, double ex, double ey, double ez) {
            return normal.x * x + normal.y * y + normal.z * z + offset
                    > Math.abs(normal.x) * ex + Math.abs(normal.y) * ey + Math.abs(normal.z) * ez;
        }
    }

    record Cone(Vec3 axis, float tangent) { }

    record Bounds(float x, float y, float depth, float radius, @Nullable Cone cone, List<Plane> rectPlanes) {
        Bounds(float x, float y, float depth, float radius) { this(x, y, depth, radius, null, List.of()); }

        boolean intersects(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            float dx = Math.max(0f, Math.max(minX - x, x - maxX));
            float dy = Math.max(0f, Math.max(minY - y, y - maxY));
            float dz = Math.max(0f, Math.max(minZ - depth, depth - maxZ));
            if (dx * dx + dy * dy + dz * dz >= radius * radius) return false;
            if (cone == null && rectPlanes.isEmpty()) return true;
            double cx = (minX + maxX) * .5, cy = (minY + maxY) * .5, cz = (minZ + maxZ) * .5;
            double ex = maxX - cx, ey = maxY - cy, ez = maxZ - cz;
            if (cone != null) {
                var fromVertex = new Vec3(cx - x, cy - y, cz - depth);
                var axis = cone.axis();
                var radial = fromVertex.subtract(axis.scale(fromVertex.dot(axis))).normalize();
                var plane = new Plane(radial.subtract(axis.scale(cone.tangent())), 0);
                if (plane.excludes(fromVertex.x, fromVertex.y, fromVertex.z, ex, ey, ez)) return false;
            }
            for (var plane : rectPlanes) if (plane.excludes(cx, cy, cz, ex, ey, ez)) return false;
            return true;
        }
    }

    record Cells(int[] headers, int[] indices, int indexCount, boolean overflowed) { }

    static ForwardLightGrid create(int width, int height, float near, float furthestComponentOrReflection, Settings settings) {
        if (width < 1 || height < 1 || !Float.isFinite(near) || near <= 0)
            throw new IllegalArgumentException("Forward light grid requires the current viewport and near clip");
        double start = near + 9.5;
        double cap = settings.maxDistanceKm() > 0 ? settings.maxDistanceKm() * 100_000.0 : DEFAULT_FAR_LIMIT;
        double far = Math.max(start + 1, Math.min(cap, Math.max(MINIMUM_FAR, furthestComponentOrReflection)) + 10.0);
        double offset = (far - start * Math.pow(2, (settings.slices() - 1) / (double)DISTRIBUTION)) / (far - start);
        return new ForwardLightGrid(width, height, Math.ceilDiv(width, settings.tilePixels()), Math.ceilDiv(height, settings.tilePixels()),
                settings, new Depth(near, (float)((1 - offset) / start), (float)offset));
    }

    int cells() { return Math.multiplyExact(Math.multiplyExact(columns, rows), settings.slices()); }

    Cells cull(Matrix4fc projection, List<Bounds> lights, int capacity) {
        int[] headers = new int[Math.multiplyExact(cells(), 2)];
        int[] indices = new int[Math.min(capacity, Math.max(1, lights.size()))];
        if (lights.isEmpty()) return new Cells(headers, indices, 0, false);

        int indexCount = 0;
        boolean overflowed = false;
        int[] candidates = new int[lights.size()];
        float[] nearCorners = new float[Math.multiplyExact(Math.multiplyExact(columns + 1, rows + 1), 2)];
        float[] farCorners = new float[nearCorners.length];
        projectCorners(projection, nearCorners, depth.near());
        for (int z = 0; z < settings.slices(); z++) {
            float minZ = depth.edge(z, settings.slices()), maxZ = depth.edge(z + 1, settings.slices());
            projectCorners(projection, farCorners, maxZ);
            int candidateCount = 0;
            for (int light = 0; light < lights.size(); light++) {
                var bounds = lights.get(light);
                if (bounds.depth() + bounds.radius() > minZ && bounds.depth() - bounds.radius() < maxZ)
                    candidates[candidateCount++] = light;
            }
            if (candidateCount != 0) {
                for (int y = 0; y < rows; y++) {
                    for (int x = 0; x < columns; x++) {
                        int firstCorner = (y * (columns + 1) + x) * 2;
                        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
                        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
                        for (int corner = 0; corner < 4; corner++) {
                            int i = firstCorner + ((corner & 1) + (corner >>> 1) * (columns + 1)) * 2;
                            minX = Math.min(minX, Math.min(nearCorners[i], farCorners[i]));
                            minY = Math.min(minY, Math.min(nearCorners[i + 1], farCorners[i + 1]));
                            maxX = Math.max(maxX, Math.max(nearCorners[i], farCorners[i]));
                            maxY = Math.max(maxY, Math.max(nearCorners[i + 1], farCorners[i + 1]));
                        }
                        int start = indexCount;
                        for (int index = 0; index < candidateCount; index++) {
                            int light = candidates[index];
                            if (!lights.get(light).intersects(minX, minY, minZ, maxX, maxY, maxZ)) continue;
                            if (indexCount == capacity || !settings.sharedCapacity() && indexCount - start == settings.entriesPerCell()) {
                                overflowed = true;
                                break;
                            }
                            if (indexCount == indices.length) {
                                int grown = (int)Math.min(capacity, Math.max((long)indexCount + 1, indexCount * 2L));
                                indices = Arrays.copyOf(indices, grown);
                            }
                            indices[indexCount++] = light;
                        }
                        int header = ((z * rows + y) * columns + x) * 2;
                        headers[header] = start;
                        headers[header + 1] = indexCount - start;
                    }
                }
            }
            float[] previous = nearCorners;
            nearCorners = farCorners;
            farCorners = previous;
        }
        return new Cells(headers, indices, indexCount, overflowed);
    }

    private void projectCorners(Matrix4fc projection, float[] corners, float depth) {
        int index = 0;
        float clipW = projection.m33() - projection.m23() * depth;
        float clipX = projection.m30() - projection.m20() * depth;
        float clipY = projection.m31() - projection.m21() * depth;
        for (int y = 0; y <= rows; y++) {
            float ndcY = 1f - 2f * y * settings.tilePixels() / height;
            for (int x = 0; x <= columns; x++) {
                float ndcX = 2f * x * settings.tilePixels() / width - 1f;
                float a = projection.m00() - ndcX * projection.m03();
                float b = projection.m10() - ndcX * projection.m13();
                float c = projection.m01() - ndcY * projection.m03();
                float d = projection.m11() - ndcY * projection.m13();
                float determinant = a * d - b * c;
                if (!Float.isFinite(determinant) || determinant == 0)
                    throw new IllegalArgumentException("Forward light cell cannot be unprojected");
                float rhsX = ndcX * clipW - clipX, rhsY = ndcY * clipW - clipY;
                corners[index++] = (d * rhsX - b * rhsY) / determinant;
                corners[index++] = (a * rhsY - c * rhsX) / determinant;
            }
        }
    }

    void write(ByteBuffer data, boolean glViewport, Matrix4fc view) {
        data.putInt(columns).putInt(rows).putInt(settings.slices()).putInt(Integer.numberOfTrailingZeros(settings.tilePixels()));
        data.putFloat(depth.scale()).putFloat(depth.offset()).putFloat(DISTRIBUTION).putFloat(glViewport ? height : 0f);
        view.get(data.position(), data);
    }
}
