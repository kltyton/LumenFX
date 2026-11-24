package com.kltyton.lumenfx.client.render.mesh.scene;

import static com.kltyton.lumenfx.client.render.math.UnrealRendererMath.roundToInt;

import java.nio.ByteBuffer;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/** CPU instance-upload encoding; its SSE integer rounding differs from the shader writer. */
final class CpuSceneTransformCodec {
    private CpuSceneTransformCodec() { }

    static void writeRotationScale(Matrix4fc matrix, ByteBuffer data) {
        Vector3f xAxis = new Vector3f(matrix.m00(), matrix.m01(), matrix.m02());
        Vector3f yAxis = new Vector3f(matrix.m10(), matrix.m11(), matrix.m12());
        Vector3f zAxis = new Vector3f(matrix.m20(), matrix.m21(), matrix.m22());
        float sx = length(xAxis), sy = length(yAxis), sz = length(zAxis);
        xAxis.div(sx != 0 ? sx : 1);
        yAxis.div(sy != 0 ? sy : 1);
        zAxis.div(sz != 0 ? sz : 1);
        if (zAxis.z < 0) { zAxis.negate(); sz = -sz; }
        float sum = Math.abs(zAxis.x) + Math.abs(zAxis.y) + Math.abs(zAxis.z);
        float octX = (zAxis.x + zAxis.y) / sum;
        float octY = (zAxis.x - zAxis.y) / sum;
        float a = 1f / (1f + zAxis.z);
        float b = -zAxis.x * zAxis.y * a;
        Vector3f basisX = new Vector3f(1f - zAxis.x * zAxis.x * a, b, -zAxis.x);
        Vector3f basisY = new Vector3f(b, 1f - zAxis.y * zAxis.y * a, -zAxis.y);
        float x = dot(xAxis, basisX), y = dot(xAxis, basisY);
        boolean spinIsX = Math.abs(x) < Math.abs(y);
        float spin0 = spinIsX ? x : y;
        float spin1 = spinIsX ? y : x;
        float sign1 = spin1 < 0 ? -1 : 1;
        sx *= sign1;
        spin0 *= sign1;
        Vector3f generatedY = new Vector3f(zAxis.y * xAxis.z - zAxis.z * xAxis.y,
                zAxis.z * xAxis.x - zAxis.x * xAxis.z, zAxis.x * xAxis.y - zAxis.y * xAxis.x);
        sy *= dot(yAxis, generatedY) < 0 ? -sign1 : sign1;

        int rotationX = (roundToInt(octX * 32767f) + 32768) & 0xffff;
        int rotationY = (roundToInt(octY * 32767f) + 32768) & 0xffff;
        int spin = ((roundToInt(spin0 * 16383f * 1.41421356f) + 16384) & 0x7fff)
                | (spinIsX ? 1 << 15 : 0);
        float maximum = Math.max(Math.abs(sx), Math.max(Math.abs(sy), Math.abs(sz)));
        int sharedExponent = ((Float.floatToRawIntBits(maximum) >>> 23) & 0xff) + 1;
        float exponentScale = Float.intBitsToFloat(((127 + 127 + 15 - sharedExponent) & 0xff) << 23);
        if (roundToInt(maximum * exponentScale) == 1 << 15) {
            sharedExponent++;
            exponentScale *= .5f;
        }
        int scaleX = (roundToInt(sx * exponentScale) + (1 << 15)) & 0xffff;
        int scaleY = (roundToInt(sy * exponentScale) + (1 << 15)) & 0xffff;
        int scaleZ = (roundToInt(sz * exponentScale) + (1 << 15)) & 0xffff;
        data.putInt(rotationX | rotationY << 16).putInt(spin)
                .putInt(scaleX | scaleY << 16).putInt(scaleZ | sharedExponent << 16);
    }

    private static float dot(Vector3f left, Vector3f right) {
        return left.x * right.x + left.y * right.y + left.z * right.z;
    }

    private static float length(Vector3f vector) { return (float)Math.sqrt(dot(vector, vector)); }

}
