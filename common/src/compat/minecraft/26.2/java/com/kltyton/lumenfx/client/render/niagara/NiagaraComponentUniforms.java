package com.kltyton.lumenfx.client.render.niagara;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices;
import java.nio.ByteBuffer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;

/** Component matrices cross the double-to-float boundary before vertex-factory consumption. */
public final class NiagaraComponentUniforms {
    public static final int BYTES = 21 * 16;

    private NiagaraComponentUniforms() { }

    public static void write(ByteBuffer target, EffectHandle.Pose current, EffectHandle.Pose previous,
                             double originX, double originY, double originZ, Vec3 camera) {
        Matrix4d matrix = NiagaraComponentMatrices.source(current, 0, 0, 0, true, new Matrix4d());
        Matrix4d old = NiagaraComponentMatrices.source(previous, 0, 0, 0, true, new Matrix4d());
        float highX = (float)matrix.m30(), highY = (float)matrix.m31(), highZ = (float)matrix.m32();
        NiagaraComponentMatrices.relative(matrix, highX, highY, highZ, false, matrix);
        NiagaraComponentMatrices.relative(old, highX, highY, highZ, true, old);
        Matrix4d inverse = NiagaraComponentMatrices.inverse(matrix, new Matrix4d());
        writeMatrix(target, matrix);
        writeMatrix(target, inverse);
        writeMatrix(target, old);
        target.putFloat(highX).putFloat(highY).putFloat(highZ).putFloat(0);
        target.putFloat(inverseScale(matrix, 0)).putFloat(inverseScale(matrix, 1)).putFloat(inverseScale(matrix, 2))
                .putFloat(matrix.determinant3x3() < 0 ? -1 : 1);
        target.putFloat((float)(originX / 2097152)).putFloat((float)(originY / 2097152))
                .putFloat((float)(originZ / 2097152)).putFloat(0);
        double cameraX = camera.x * 100, cameraY = camera.z * 100, cameraZ = camera.y * 100;
        float tileX = (float)Math.floor(cameraX / 2097152 + .5), tileY = (float)Math.floor(cameraY / 2097152 + .5);
        float tileZ = (float)Math.floor(cameraZ / 2097152 + .5);
        target.putFloat(-tileX).putFloat(-tileY).putFloat(-tileZ).putFloat(0);
        target.putFloat((float)(-cameraX + tileX * 2097152.0)).putFloat((float)(-cameraY + tileY * 2097152.0))
                .putFloat((float)(-cameraZ + tileZ * 2097152.0)).putFloat(0);
        writeMatrix(target, new Matrix4d(old).mul(inverse));
    }

    public static void writeMatrix(ByteBuffer target, Matrix4dc matrix) {
        for (int column = 0; column < 4; column++) for (int row = 0; row < 4; row++)
            target.putFloat((float)matrix.get(column, row));
    }

    private static float inverseScale(Matrix4d matrix, int column) {
        float x = (float)matrix.get(column, 0), y = (float)matrix.get(column, 1), z = (float)matrix.get(column, 2);
        float length = (float)Math.sqrt((x * x + y * y) + z * z);
        return length > 0.0001f ? 1f / length : 0;
    }
}
