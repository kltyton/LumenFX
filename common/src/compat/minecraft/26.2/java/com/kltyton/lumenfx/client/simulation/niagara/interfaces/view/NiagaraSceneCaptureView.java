package com.kltyton.lumenfx.client.simulation.niagara.interfaces.view;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/** Immutable projection state shared by the Scene Capture VM calls and the GPU publication. */
public final class NiagaraSceneCaptureView {
    private static final Matrix4fc UNREAL_AXIS = new Matrix4f(
            0, 0, 1, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1);
    private final Matrix4f view, projection, deprojection;
    private final boolean perspective;
    private final int width, height, mips;

    public NiagaraSceneCaptureView() {
        view = new Matrix4f();
        projection = new Matrix4f();
        deprojection = new Matrix4f();
        perspective = true;
        width = height = mips = 0;
    }

    public NiagaraSceneCaptureView(Vec3 position, Quaternionfc rotation, Vec3 tile, Matrix4fc cameraProjection,
                                   boolean perspective, int width, int height, int mips) {
        this.perspective = perspective;
        this.width = width;
        this.height = height;
        this.mips = mips;
        var cameraTransform = new Matrix4f().translation(
                (float)(position.x - tile.x), (float)(position.y - tile.y), (float)(position.z - tile.z))
                .rotate(new Quaternionf(rotation));
        view = new Matrix4f(UNREAL_AXIS).mul(cameraTransform.invert());
        projection = new Matrix4f(cameraProjection).mul(view);
        var screenToClip = new Matrix4f(
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, cameraProjection.m22(), perspective ? 1 : 0,
                0, 0, cameraProjection.m32(), perspective ? 0 : 1);
        deprojection = new Matrix4f(projection).invert().mul(screenToClip);
    }

    public int width() { return width; }
    public int height() { return height; }
    public int mips() { return mips; }

    public Vector3f project(float x, float y, float z) {
        var clip = projection.transform(new Vector4f(x, y, z, 1));
        float reciprocal = clip.w > 0 ? 1f / clip.w : 1f;
        return new Vector3f(clip.x * reciprocal * .5f + .5f, clip.y * reciprocal * -.5f + .5f, clip.z * reciprocal);
    }

    public Vector3f deproject(float u, float v, float depth) {
        float x = (u - .5f) * 2f;
        float y = (v - .5f) * -2f;
        if (perspective) {
            x *= depth;
            y *= depth;
        }
        var world = deprojection.transform(new Vector4f(x, y, depth, 1));
        return new Vector3f(world.x, world.y, world.z);
    }

    public @Nullable byte[] shaderValue(String name) {
        return switch (name) {
            case "TextureSize" -> ints(width, height, mips);
            case "TextureViewMatrix" -> matrix(view);
            case "TextureProjMatrix" -> matrix(projection);
            case "InvTextureProjMatrix" -> matrix(deprojection);
            case "IsPerspective" -> ints(perspective ? 1 : 0);
            default -> null;
        };
    }

    private static byte[] ints(int... values) {
        var data = ByteBuffer.allocate(values.length * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) data.putInt(value);
        return data.array();
    }

    private static byte[] matrix(Matrix4fc value) {
        var data = ByteBuffer.allocate(16 * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        // The source shader multiplies row vectors. Its reflected matrices use the corresponding transposed representation.
        for (float word : new Matrix4f(value).transpose().get(new float[16])) data.putFloat(word);
        return data.array();
    }
}
