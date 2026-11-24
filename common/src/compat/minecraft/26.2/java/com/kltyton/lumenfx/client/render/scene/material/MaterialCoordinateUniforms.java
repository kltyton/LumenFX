package com.kltyton.lumenfx.client.render.scene.material;

import java.nio.ByteBuffer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.jspecify.annotations.Nullable;

/** Source view axes and centimeters, with distinct draw-view and camera transforms. */
public final class MaterialCoordinateUniforms {
    public static final int BYTES = 256;

    private MaterialCoordinateUniforms() { }

    public static void write(ByteBuffer destination, @Nullable Matrix4fc modelView, Quaternionfc camera) {
        if (modelView == null) {
            for (int component = 0; component < 32; component++) destination.putFloat(0);
        } else writePair(destination, sourceView(modelView), false);
        writePair(destination, sourceView(new Matrix4f().rotation(new Quaternionf(camera).conjugate())), true);
    }

    private static void writePair(ByteBuffer destination, Matrix4f transform, boolean cameraRotation) {
        transform.get(destination.position(), destination);
        destination.position(destination.position() + 64);
        if (cameraRotation) transform.transpose(); else transform.invert();
        transform.get(destination.position(), destination);
        destination.position(destination.position() + 64);
    }

    static Matrix4f sourceView(Matrix4fc view) {
        // The source world uses X/Z/Y; source view Z points forward and view Y follows ViewUp.
        return new Matrix4f().set(
                view.m00(), view.m01(), -view.m02(), view.m03() * .01f,
                view.m20(), view.m21(), -view.m22(), view.m23() * .01f,
                view.m10(), view.m11(), -view.m12(), view.m13() * .01f,
                view.m30() * 100, view.m31() * 100, -view.m32() * 100, view.m33());
    }
}
