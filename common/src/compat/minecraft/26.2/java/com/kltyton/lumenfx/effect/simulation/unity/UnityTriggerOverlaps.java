package com.kltyton.lumenfx.effect.simulation.unity;

import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry;
import com.kltyton.lumenfx.effect.unity.UnityTrigger;
import com.lowdragmc.photon.client.collision.ColliderTarget;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Per-emitter immutable collider snapshot with authoring-order indices. */
public final class UnityTriggerOverlaps {
    @FunctionalInterface
    private interface SphereOverlap { boolean contains(double x, double y, double z, double radius); }

    private final UnityTrigger settings;
    private final @Nullable SphereOverlap[] shapes;
    private @Nullable Pose pose;
    private Map<String, List<UnityTrigger.Collider>> hostColliders = Map.of();

    public UnityTriggerOverlaps(UnityTrigger settings) {
        this.settings = settings;
        shapes = new SphereOverlap[settings.colliders().size()];
    }

    public void pose(Pose next) {
        pose(next, Map.of());
    }

    public void pose(Pose next, Map<String, List<UnityTrigger.Collider>> nextHostColliders) {
        boolean samePose = next.equals(pose);
        boolean sameHost = hostColliders == nextHostColliders || hostColliders.equals(nextHostColliders);
        if (samePose && sameHost) return;
        pose = next;
        hostColliders = nextHostColliders;
        for (int index = 0; index < shapes.length; index++) {
            var source = settings.colliders().get(index);
            if (source.shape() == UnityTrigger.Shape.HOST) {
                if (sameHost && shapes[index] != null) continue;
                shapes[index] = buildHostOverlap(index, source, nextHostColliders);
            } else {
                if (samePose && shapes[index] != null) continue;
                shapes[index] = buildOverlap(index, source, next);
            }
        }
    }

    private static @Nullable SphereOverlap buildHostOverlap(
            int index,
            UnityTrigger.Collider source,
            Map<String, List<UnityTrigger.Collider>> nextHostColliders
    ) {
        if (nextHostColliders.isEmpty()) return null;
        var dynamic = nextHostColliders.get(source.name());
        if (dynamic == null || dynamic.isEmpty()) dynamic = nextHostColliders.get(source.hostKey());
        if (dynamic == null || dynamic.isEmpty()) return null;
        Pose worldPose = Pose.at(Vec3.ZERO);
        if (dynamic.size() == 1) {
            return buildOverlap(index, dynamic.get(0), worldPose);
        }
        var compiled = new ArrayList<SphereOverlap>(dynamic.size());
        for (var col : dynamic) {
            var shape = buildOverlap(index, col, worldPose);
            if (shape != null) compiled.add(shape);
        }
        if (compiled.isEmpty()) return null;
        return (x, y, z, radius) -> {
            for (var shape : compiled) {
                if (shape.contains(x, y, z, radius)) return true;
            }
            return false;
        };
    }

    private static @Nullable SphereOverlap buildOverlap(int index, UnityTrigger.Collider source, Pose framePose) {
        var center = framePose.transformPosition(source.center());
        var x = framePose.transformDirection(source.xAxis());
        var y = framePose.transformDirection(source.yAxis());
        var z = framePose.transformDirection(source.zAxis());
        var size = source.size();
        double width = x.length() * size.x, height = y.length() * size.y, depth = z.length() * size.z;
        return switch (source.shape()) {
            case SPHERE -> {
                double sphereRadius = Math.max(width, Math.max(height, depth));
                yield (px, py, pz, pr) -> {
                    double dx = px - center.x, dy = py - center.y, dz = pz - center.z;
                    double reach = sphereRadius + pr;
                    return dx * dx + dy * dy + dz * dz <= reach * reach;
                };
            }
            case BOX -> {
                var box = ColliderTarget.box(index, null, true, center.x, center.y, center.z,
                        new double[]{ x.x, x.y, x.z, y.x, y.y, y.z, z.x, z.y, z.z }, width, height, depth);
                yield box == null ? null : box::overlapsSphere;
            }
            case CAPSULE -> switch (source.direction()) {
                case 0 -> Capsule.fromExtent(center, x, width, Math.max(height, depth));
                case 1 -> Capsule.fromExtent(center, y, height, Math.max(width, depth));
                default -> Capsule.fromExtent(center, z, depth, Math.max(width, height));
            };
            case MESH -> MeshOverlap.from(Objects.requireNonNull(source.mesh()), source.convex(), center,
                    x.scale(size.x * 0.01), y.scale(size.y * 0.01), z.scale(size.z * 0.01));
            case HOST -> null;
        };
    }

    public long overlap(Vec3 center, double particleRadius) {
        double radius = particleRadius * settings.radiusScale();
        long mask = 0;
        for (int index = 0; index < shapes.length; index++) {
            var shape = shapes[index];
            if (shape != null && shape.contains(center.x, center.y, center.z, radius)) mask |= 1L << index;
        }
        return mask;
    }

    public List<String> names(long mask) {
        if (mask == 0 || settings.query() == UnityTrigger.Query.DISABLED) return List.of();
        var colliders = settings.colliders();
        if (settings.query() == UnityTrigger.Query.ONE) return List.of(colliders.get(Long.numberOfTrailingZeros(mask)).name());
        var names = new ArrayList<String>();
        while (mask != 0) {
            names.add(colliders.get(Long.numberOfTrailingZeros(mask)).name());
            mask &= mask - 1;
        }
        return List.copyOf(names);
    }

    private record Capsule(Vec3 center, Vec3 axis, double halfSegment, double radius) implements SphereOverlap {
        static Capsule fromExtent(Vec3 center, Vec3 scaledAxis, double halfHeight, double radius) {
            double length = scaledAxis.length();
            Vec3 axis = length > 0 ? scaledAxis.scale(1 / length) : Vec3.ZERO;
            return new Capsule(center, axis, Math.max(0, halfHeight - radius), radius);
        }

        @Override public boolean contains(double x, double y, double z, double particleRadius) {
            double dx = x - center.x, dy = y - center.y, dz = z - center.z;
            double along = Math.clamp(dx * axis.x + dy * axis.y + dz * axis.z, -halfSegment, halfSegment);
            dx -= axis.x * along;
            dy -= axis.y * along;
            dz -= axis.z * along;
            double reach = radius + particleRadius;
            return dx * dx + dy * dy + dz * dz <= reach * reach;
        }
    }

    private record MeshOverlap(double[] triCoords, double @Nullable [] hullPlanes, boolean convex,
                               double minX, double minY, double minZ,
                               double maxX, double maxY, double maxZ) implements SphereOverlap {
        static @Nullable MeshOverlap from(StaticMeshGeometry mesh, boolean convex,
                                          Vec3 center, Vec3 axisX, Vec3 axisY, Vec3 axisZ) {
            int triCount = mesh.indexCount() / 3;
            if (triCount <= 0) return null;
            int vCount = mesh.vertexCount();
            double[] wx = new double[vCount], wy = new double[vCount], wz = new double[vCount];
            double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
            for (int v = 0; v < vCount; v++) {
                double lx = mesh.position(v, 0), ly = mesh.position(v, 2), lz = mesh.position(v, 1);
                double px = center.x + lx * axisX.x + ly * axisY.x + lz * axisZ.x;
                double py = center.y + lx * axisX.y + ly * axisY.y + lz * axisZ.y;
                double pz = center.z + lx * axisX.z + ly * axisY.z + lz * axisZ.z;
                wx[v] = px; wy[v] = py; wz[v] = pz;
                if (px < minX) minX = px; if (px > maxX) maxX = px;
                if (py < minY) minY = py; if (py > maxY) maxY = py;
                if (pz < minZ) minZ = pz; if (pz > maxZ) maxZ = pz;
            }
            if (convex) {
                var hull = buildConvexHull(wx, wy, wz);
                if (hull != null) {
                    return new MeshOverlap(hull.triCoords, hull.planes, true, minX, minY, minZ, maxX, maxY, maxZ);
                }
            }
            double[] tris = new double[triCount * 9];
            for (int t = 0; t < triCount; t++) {
                int a = mesh.index(t * 3), b = mesh.index(t * 3 + 1), c = mesh.index(t * 3 + 2);
                int dst = t * 9;
                tris[dst] = wx[a]; tris[dst + 1] = wy[a]; tris[dst + 2] = wz[a];
                tris[dst + 3] = wx[b]; tris[dst + 4] = wy[b]; tris[dst + 5] = wz[b];
                tris[dst + 6] = wx[c]; tris[dst + 7] = wy[c]; tris[dst + 8] = wz[c];
            }
            return new MeshOverlap(tris, null, convex, minX, minY, minZ, maxX, maxY, maxZ);
        }

        private record HullData(double[] triCoords, double[] planes) { }

        private record HullFace(int a, int b, int c, double nx, double ny, double nz, double d) {
            static @Nullable HullFace of(int a, int b, int c, double[] x, double[] y, double[] z,
                                         double cx, double cy, double cz) {
                double abx = x[b] - x[a], aby = y[b] - y[a], abz = z[b] - z[a];
                double acx = x[c] - x[a], acy = y[c] - y[a], acz = z[c] - z[a];
                double nx = aby * acz - abz * acy;
                double ny = abz * acx - abx * acz;
                double nz = abx * acy - aby * acx;
                double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len <= 1e-12) return null;
                nx /= len; ny /= len; nz /= len;
                if (nx * (x[a] - cx) + ny * (y[a] - cy) + nz * (z[a] - cz) < 0.0) {
                    int tmp = b; b = c; c = tmp;
                    nx = -nx; ny = -ny; nz = -nz;
                }
                double d = nx * x[a] + ny * y[a] + nz * z[a];
                return new HullFace(a, b, c, nx, ny, nz, d);
            }
        }

        /** Incremental 3D convex hull producing outward triangular faces and normalized half-spaces. */
        private static @Nullable HullData buildConvexHull(double[] rawX, double[] rawY, double[] rawZ) {
            int nRaw = rawX.length;
            double[] px = new double[nRaw], py = new double[nRaw], pz = new double[nRaw];
            int n = 0;
            for (int i = 0; i < nRaw; i++) {
                boolean dup = false;
                for (int j = 0; j < n; j++) {
                    double dx = rawX[i] - px[j], dy = rawY[i] - py[j], dz = rawZ[i] - pz[j];
                    if (dx * dx + dy * dy + dz * dz <= 1e-16) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    px[n] = rawX[i]; py[n] = rawY[i]; pz[n] = rawZ[i];
                    n++;
                }
            }
            if (n < 4) return null;
            int i0 = 0, i1 = 0;
            for (int i = 1; i < n; i++) {
                if (px[i] < px[i0]) i0 = i;
                if (px[i] > px[i1]) i1 = i;
            }
            if (i0 == i1) i1 = 1;
            double bestDistSq = -1.0;
            for (int i = 0; i < n; i++) {
                double d = distSq(px[i], py[i], pz[i], px[i0], py[i0], pz[i0]);
                if (d > bestDistSq) { bestDistSq = d; i1 = i; }
            }
            if (bestDistSq <= 1e-16) return null;
            double lx = px[i1] - px[i0], ly = py[i1] - py[i0], lz = pz[i1] - pz[i0];
            double lLenSq = lx * lx + ly * ly + lz * lz;
            int i2 = -1;
            double bestAreaSq = 1e-16;
            for (int i = 0; i < n; i++) {
                if (i == i0 || i == i1) continue;
                double vx = px[i] - px[i0], vy = py[i] - py[i0], vz = pz[i] - pz[i0];
                double cx = vy * lz - vz * ly, cy = vz * lx - vx * lz, cz = vx * ly - vy * lx;
                double areaSq = (cx * cx + cy * cy + cz * cz) / lLenSq;
                if (areaSq > bestAreaSq) { bestAreaSq = areaSq; i2 = i; }
            }
            if (i2 < 0) return null;
            double abx = px[i1] - px[i0], aby = py[i1] - py[i0], abz = pz[i1] - pz[i0];
            double acx = px[i2] - px[i0], acy = py[i2] - py[i0], acz = pz[i2] - pz[i0];
            double nx = aby * acz - abz * acy, ny = abz * acx - abx * acz, nz = abx * acy - aby * acx;
            double nLen = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (nLen <= 1e-12) return null;
            nx /= nLen; ny /= nLen; nz /= nLen;
            int i3 = -1;
            double bestVol = 1e-8;
            for (int i = 0; i < n; i++) {
                if (i == i0 || i == i1 || i == i2) continue;
                double d = Math.abs(nx * (px[i] - px[i0]) + ny * (py[i] - py[i0]) + nz * (pz[i] - pz[i0]));
                if (d > bestVol) { bestVol = d; i3 = i; }
            }
            if (i3 < 0) return null; // Coplanar mesh: caller falls back to surface triangles.
            double cx = (px[i0] + px[i1] + px[i2] + px[i3]) * 0.25;
            double cy = (py[i0] + py[i1] + py[i2] + py[i3]) * 0.25;
            double cz = (pz[i0] + pz[i1] + pz[i2] + pz[i3]) * 0.25;
            var faces = new ArrayList<HullFace>(16);
            faces.add(Objects.requireNonNull(HullFace.of(i0, i1, i2, px, py, pz, cx, cy, cz)));
            faces.add(Objects.requireNonNull(HullFace.of(i0, i1, i3, px, py, pz, cx, cy, cz)));
            faces.add(Objects.requireNonNull(HullFace.of(i0, i2, i3, px, py, pz, cx, cy, cz)));
            faces.add(Objects.requireNonNull(HullFace.of(i1, i2, i3, px, py, pz, cx, cy, cz)));
            var nextFaces = new ArrayList<HullFace>(16);
            var horizonA = new ArrayList<Integer>(16);
            var horizonB = new ArrayList<Integer>(16);
            for (int p = 0; p < n; p++) {
                if (p == i0 || p == i1 || p == i2 || p == i3) continue;
                var visible = new ArrayList<HullFace>();
                nextFaces.clear();
                for (var f : faces) {
                    if (f.nx * px[p] + f.ny * py[p] + f.nz * pz[p] - f.d > 1e-8) {
                        visible.add(f);
                    } else {
                        nextFaces.add(f);
                    }
                }
                if (visible.isEmpty()) continue;
                horizonA.clear();
                horizonB.clear();
                for (int vi = 0; vi < visible.size(); vi++) {
                    var vf = visible.get(vi);
                    int[] eStart = { vf.a, vf.b, vf.c };
                    int[] eEnd = { vf.b, vf.c, vf.a };
                    for (int ei = 0; ei < 3; ei++) {
                        int u = eStart[ei], v = eEnd[ei];
                        boolean shared = false;
                        for (int vj = 0; vj < visible.size(); vj++) {
                            if (vj == vi) continue;
                            var of = visible.get(vj);
                            if ((of.a == v && of.b == u) || (of.b == v && of.c == u) || (of.c == v && of.a == u)) {
                                shared = true;
                                break;
                            }
                        }
                        if (!shared) {
                            horizonA.add(u);
                            horizonB.add(v);
                        }
                    }
                }
                for (int hi = 0; hi < horizonA.size(); hi++) {
                    var nf = HullFace.of(horizonA.get(hi), horizonB.get(hi), p, px, py, pz, cx, cy, cz);
                    if (nf != null) nextFaces.add(nf);
                }
                faces = new ArrayList<>(nextFaces);
            }
            if (faces.size() < 4) return null;
            double[] triCoords = new double[faces.size() * 9];
            double[] planes = new double[faces.size() * 4];
            for (int fi = 0; fi < faces.size(); fi++) {
                var f = faces.get(fi);
                int tDst = fi * 9;
                triCoords[tDst] = px[f.a]; triCoords[tDst + 1] = py[f.a]; triCoords[tDst + 2] = pz[f.a];
                triCoords[tDst + 3] = px[f.b]; triCoords[tDst + 4] = py[f.b]; triCoords[tDst + 5] = pz[f.b];
                triCoords[tDst + 6] = px[f.c]; triCoords[tDst + 7] = py[f.c]; triCoords[tDst + 8] = pz[f.c];
                int pDst = fi * 4;
                planes[pDst] = f.nx; planes[pDst + 1] = f.ny; planes[pDst + 2] = f.nz; planes[pDst + 3] = f.d;
            }
            return new HullData(triCoords, planes);
        }

        @Override public boolean contains(double x, double y, double z, double particleRadius) {
            if (x + particleRadius < minX || x - particleRadius > maxX
                    || y + particleRadius < minY || y - particleRadius > maxY
                    || z + particleRadius < minZ || z - particleRadius > maxZ) {
                return false;
            }
            if (hullPlanes != null) {
                double maxDist = Double.NEGATIVE_INFINITY;
                for (int i = 0; i < hullPlanes.length; i += 4) {
                    double d = hullPlanes[i] * x + hullPlanes[i + 1] * y + hullPlanes[i + 2] * z - hullPlanes[i + 3];
                    if (d > maxDist) maxDist = d;
                }
                if (maxDist <= 0.0) return true;
                if (maxDist > particleRadius) return false;
            }
            double rSq = particleRadius * particleRadius;
            for (int i = 0; i < triCoords.length; i += 9) {
                double ax = triCoords[i], ay = triCoords[i + 1], az = triCoords[i + 2];
                double bx = triCoords[i + 3], by = triCoords[i + 4], bz = triCoords[i + 5];
                double cx = triCoords[i + 6], cy = triCoords[i + 7], cz = triCoords[i + 8];
                if (pointTriangleDistSq(x, y, z, ax, ay, az, bx, by, bz, cx, cy, cz) < rSq) {
                    return true;
                }
            }
            return false;
        }

        private static double pointTriangleDistSq(double px, double py, double pz,
                                                  double ax, double ay, double az,
                                                  double bx, double by, double bz,
                                                  double cx, double cy, double cz) {
            double abx = bx - ax, aby = by - ay, abz = bz - az;
            double acx = cx - ax, acy = cy - ay, acz = cz - az;
            double apx = px - ax, apy = py - ay, apz = pz - az;
            double d1 = abx * apx + aby * apy + abz * apz;
            double d2 = acx * apx + acy * apy + acz * apz;
            if (d1 <= 0.0 && d2 <= 0.0) return distSq(px, py, pz, ax, ay, az);
            double bpx = px - bx, bpy = py - by, bpz = pz - bz;
            double d3 = abx * bpx + aby * bpy + abz * bpz;
            double d4 = acx * bpx + acy * bpy + acz * bpz;
            if (d3 >= 0.0 && d4 <= d3) return distSq(px, py, pz, bx, by, bz);
            double vc = d1 * d4 - d3 * d2;
            if (vc <= 0.0 && d1 >= 0.0 && d3 <= 0.0) {
                double v = d1 / (d1 - d3);
                return distSq(px, py, pz, ax + v * abx, ay + v * aby, az + v * abz);
            }
            double cpx = px - cx, cpy = py - cy, cpz = pz - cz;
            double d5 = abx * cpx + aby * cpy + abz * cpz;
            double d6 = acx * cpx + acy * cpy + acz * cpz;
            if (d6 >= 0.0 && d5 <= d6) return distSq(px, py, pz, cx, cy, cz);
            double vb = d5 * d2 - d1 * d6;
            if (vb <= 0.0 && d2 >= 0.0 && d6 <= 0.0) {
                double w = d2 / (d2 - d6);
                return distSq(px, py, pz, ax + w * acx, ay + w * acy, az + w * acz);
            }
            double va = d3 * d6 - d5 * d4;
            if (va <= 0.0 && (d4 - d3) >= 0.0 && (d5 - d6) >= 0.0) {
                double w = (d4 - d3) / ((d4 - d3) + (d5 - d6));
                return distSq(px, py, pz, bx + w * (cx - bx), by + w * (cy - by), bz + w * (cz - bz));
            }
            double denom = 1.0 / (va + vb + vc);
            double v = vb * denom;
            double w = vc * denom;
            return distSq(px, py, pz, ax + abx * v + acx * w, ay + aby * v + acy * w, az + abz * v + acz * w);
        }

        private static double distSq(double ax, double ay, double az, double bx, double by, double bz) {
            double dx = ax - bx, dy = ay - by, dz = az - bz;
            return dx * dx + dy * dy + dz * dz;
        }
    }
}
