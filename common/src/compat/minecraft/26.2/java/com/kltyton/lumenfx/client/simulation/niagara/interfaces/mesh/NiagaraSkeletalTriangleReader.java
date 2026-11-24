package com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh;

import com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.NiagaraSkeletalMeshReader.Frame;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.NiagaraSkeletalPose.Vertex;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry;
import java.util.Arrays;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/** Reads reference and deformed skeletal triangle attributes from the captured frame. */
final class NiagaraSkeletalTriangleReader {
    private NiagaraSkeletalTriangleReader() { }

    static void read(Frame frame, String name, int[] input, int[] output) {
        var sampling = frame.sampling();
        var lod = frame.lod();
        boolean skinningVertexData = name.startsWith("GetSkinnedTriangleVertexData");
        var geometry = lod == null || !skinningVertexData && !sampling.accessible() ? null : lod.geometry();
        int count = skinningVertexData ? geometry == null ? 0 : geometry.indexCount() / 3 : sampling.triangles();
        int index = count == 0 || input.length == 0 ? -1 : Math.clamp(input[0], 0, count - 1) * 3;
        switch (name) {
            case "GetTriangleCount" -> output[0] = count;
            case "GetFilteredTriangleCount" -> output[0] = sampling.count(true);
            case "IsValidTriCoord" -> output[0] = sampling.accessible() && count * 3 > input[0] * 3 + 2 ? -1 : 0;
            case "GetFilteredTriangle" -> {
                output[0] = count == 0 ? -1 : Math.clamp(sampling.element(input[0], true), 0, count - 1);
                System.arraycopy(input, 1, output, 1, 3);
            }
            case "GetTriangleCoordAtUV", "GetTriangleCoordInAabb" -> sampling.findUv(name.equals("GetTriangleCoordAtUV"), input, output);
            case "GetTriCoordVertices", "GetTriangleIndices" -> {
                for (int corner = 0; corner < 3; corner++) output[corner] = geometry == null || index < 0 ? 0 : geometry.index(index + corner);
            }
            case "GetTriColor" -> {
                var colorGeometry = lod == null || !lod.cpuColors() ? null : lod.geometry();
                int colorTriangles = colorGeometry == null ? 0 : colorGeometry.indexCount() / 3;
                int colorIndex = colorTriangles == 0 ? -1 : Math.clamp(input[0], 0, colorTriangles - 1) * 3;
                attribute(true, colorGeometry, colorIndex, input, output);
            }
            case "GetTriUV" -> attribute(false, geometry, index, input, output);
            case "GetTriangleData" -> {
                var vertex = geometry == null || index < 0 ? NiagaraSkeletalPose.DEFAULT
                        : triangle(geometry, index, barycentric(input), null);
                put(output, 0, vertex.position());
                // The native bind-pose CPU accessor writes X to the Normal port and Z to Tangent.
                put(output, 3, index < 0 ? vertex.normal() : vertex.tangent());
                put(output, 6, vertex.bitangent());
                put(output, 9, index < 0 ? vertex.tangent() : vertex.normal());
            }
            case "GetSkinnedTriangleData", "GetSkinnedTriangleDataWS", "GetSkinnedTriangleDataInterpolated",
                    "GetSkinnedTriangleDataWSInterpolated" -> skinnedTriangle(frame, name, geometry, count, input, output);
            case "GetSkinnedTriangleVertexData", "GetSkinnedTriangleVertexDataWS", "GetSkinnedTriangleVertexDataInterpolated",
                    "GetSkinnedTriangleVertexDataWSInterpolated" -> skinnedVertices(frame, name, geometry, index, input, output);
            default -> throw new IllegalArgumentException("Unknown Niagara skeletal mesh CPU function: " + name);
        }
    }

    private static void attribute(boolean color, @Nullable StaticMeshGeometry geometry, int index, int[] input, int[] output) {
        if (geometry == null || index < 0 || !color && geometry.textureCoordinateCount() == 0) {
            Arrays.fill(output, color ? Float.floatToRawIntBits(1f) : 0);
            return;
        }
        int uv = color ? 0 : Math.clamp(input[4], 0, geometry.textureCoordinateCount() - 1);
        int a = geometry.index(index), b = geometry.index(index + 1), c = geometry.index(index + 2);
        float wa = Float.intBitsToFloat(input[1]), wb = Float.intBitsToFloat(input[2]), wc = Float.intBitsToFloat(input[3]);
        for (int channel = 0; channel < output.length; channel++) {
            float value = color ? wa * geometry.color(a, channel) + wb * geometry.color(b, channel) + wc * geometry.color(c, channel)
                    : wa * geometry.textureCoordinate(uv, a, channel) + wb * geometry.textureCoordinate(uv, b, channel)
                    + wc * geometry.textureCoordinate(uv, c, channel);
            output[channel] = Float.floatToRawIntBits(value);
        }
    }

    private static void skinnedTriangle(Frame frame, String name, @Nullable StaticMeshGeometry geometry,
                                        int count, int[] input, int[] output) {
        boolean available = geometry != null && count > 0;
        boolean valid = input[0] >= 0 && input[0] < count;
        Vec3 weights = valid ? barycentric(input) : new Vec3(1, 0, 0);
        int first = valid ? input[0] * 3 : 0;
        Vertex current = available ? triangle(geometry, first, weights, frame.current()) : NiagaraSkeletalPose.DEFAULT;
        Vertex previous = available ? triangle(geometry, first, weights, frame.previous()) : NiagaraSkeletalPose.DEFAULT;
        boolean world = name.contains("WS");
        boolean interpolate = name.endsWith("Interpolated");
        float alpha = interpolate ? Float.intBitsToFloat(input[4]) : 1f;
        Vec3 now = position(current.position(), frame.world(), world), before = position(previous.position(), frame.previousWorld(), world);
        Vec3 position = interpolate ? before.lerp(now, alpha) : now;
        put(output, 0, position);
        put(output, 3, position.subtract(before).scale(frame.inverseDelta()));
        Vec3 normal = interpolate ? previous.normal().lerp(current.normal(), alpha) : current.normal();
        Vec3 binormal = interpolate ? previous.bitangent().lerp(current.bitangent(), alpha) : current.bitangent();
        Vec3 tangent = interpolate ? previous.tangent().lerp(current.tangent(), alpha) : current.tangent();
        put(output, 6, direction(normal, frame.world(), world && available).normalize());
        put(output, 9, direction(binormal, frame.world(), world && available).normalize());
        put(output, 12, direction(tangent, frame.world(), world && available).normalize());
    }

    private static void skinnedVertices(Frame frame, String name, @Nullable StaticMeshGeometry geometry,
                                        int index, int[] input, int[] output) {
        boolean world = name.contains("WS");
        boolean interpolate = name.endsWith("Interpolated");
        float alpha = interpolate ? Float.intBitsToFloat(input[1]) : 1f;
        for (int corner = 0; corner < 3; corner++) {
            int vertex = geometry == null || index < 0 ? -1 : geometry.index(index + corner);
            var current = vertex < 0 || frame.current() == null ? NiagaraSkeletalPose.DEFAULT
                    : frame.current().vertexIgnoringCpuAccess(vertex);
            var previous = vertex < 0 || frame.previous() == null ? NiagaraSkeletalPose.DEFAULT
                    : frame.previous().vertexIgnoringCpuAccess(vertex);
            Vec3 now = position(current.position(), frame.world(), world), before = position(previous.position(), frame.previousWorld(), world);
            int offset = corner * 15;
            put(output, offset, interpolate ? before.lerp(now, alpha) : now);
            put(output, offset + 3, now.subtract(before).scale(Math.max(0, frame.inverseDelta())));
            boolean transformBasis = world && vertex >= 0;
            put(output, offset + 6, direction(current.normal(), frame.world(), transformBasis));
            put(output, offset + 9, direction(current.bitangent(), frame.world(), transformBasis));
            put(output, offset + 12, direction(current.tangent(), frame.world(), transformBasis));
        }
    }

    private static Vertex triangle(StaticMeshGeometry geometry, int first, Vec3 weights, @Nullable NiagaraSkeletalPose pose) {
        int a = geometry.index(first), b = geometry.index(first + 1), c = geometry.index(first + 2);
        var va = pose == null ? NiagaraSkeletalPose.referenceVertex(geometry, a) : pose.vertex(a);
        var vb = pose == null ? NiagaraSkeletalPose.referenceVertex(geometry, b) : pose.vertex(b);
        var vc = pose == null ? NiagaraSkeletalPose.referenceVertex(geometry, c) : pose.vertex(c);
        return new Vertex(blend(weights, va.position(), vb.position(), vc.position()), blend(weights, va.normal(), vb.normal(), vc.normal()),
                blend(weights, va.bitangent(), vb.bitangent(), vc.bitangent()), blend(weights, va.tangent(), vb.tangent(), vc.tangent()));
    }

    private static Vec3 barycentric(int[] input) {
        return new Vec3(Float.intBitsToFloat(input[1]), Float.intBitsToFloat(input[2]), Float.intBitsToFloat(input[3]));
    }
    private static Vec3 blend(Vec3 weights, Vec3 a, Vec3 b, Vec3 c) {
        return a.scale(weights.x).add(b.scale(weights.y)).add(c.scale(weights.z));
    }
    private static Vec3 position(Vec3 value, Matrix4f matrix, boolean world) {
        if (!world) return value;
        var result = matrix.transformPosition(new Vector3f((float)value.x, (float)value.y, (float)value.z));
        return new Vec3(result.x, result.y, result.z);
    }
    private static Vec3 direction(Vec3 value, Matrix4f matrix, boolean world) {
        if (!world) return value;
        var result = matrix.transformDirection(new Vector3f((float)value.x, (float)value.y, (float)value.z));
        return new Vec3(result.x, result.y, result.z).normalize();
    }
    private static void put(int[] output, int offset, Vec3 value) {
        output[offset] = Float.floatToRawIntBits((float)value.x);
        output[offset + 1] = Float.floatToRawIntBits((float)value.y);
        output[offset + 2] = Float.floatToRawIntBits((float)value.z);
    }
}
