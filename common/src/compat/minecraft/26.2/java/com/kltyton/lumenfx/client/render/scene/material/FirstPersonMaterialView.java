package com.kltyton.lumenfx.client.render.scene.material;

import java.nio.ByteBuffer;
import org.joml.Matrix4fc;
import org.joml.Matrix4f;
import org.joml.Matrix4d;

/** Source first-person view parameters, independent of the component's first-person flag. */
public record FirstPersonMaterialView(float tanHalfX, float tanHalfY, float correction, float scale, boolean perspective,
                                     Matrix4fc transform) {
    public static final int BYTES = 80;

    public FirstPersonMaterialView { transform = new Matrix4f(transform); }

    public static FirstPersonMaterialView fromProjections(Matrix4fc scene, Matrix4fc firstPerson, Matrix4fc cameraView,
                                                         float scale, boolean enabled) {
        boolean perspective = scene.m33() < 1;
        float sceneX = perspective ? 1 / Math.abs(scene.m00()) : 1;
        float sceneY = perspective ? 1 / Math.abs(scene.m11()) : 1;
        float correction = enabled && perspective ? Math.abs(firstPerson.m00() / scene.m00()) : 1;
        Matrix4d rotation = new Matrix4d(MaterialCoordinateUniforms.sourceView(cameraView))
                .m30(0).m31(0).m32(0).m03(0).m13(0).m23(0).m33(1);
        Matrix4d transform = enabled ? new Matrix4d(rotation).transpose()
                .scale((double)correction * scale, (double)correction * scale, scale).mul(rotation) : new Matrix4d();
        return new FirstPersonMaterialView(sceneX / correction, sceneY / correction, correction, enabled ? scale : 1,
                perspective, new Matrix4f().set(transform));
    }

    public void write(ByteBuffer destination) {
        destination.putFloat(perspective ? 2 * (float)Math.atan(tanHalfX) : 0)
                .putFloat(perspective ? 2 * (float)Math.atan(tanHalfY) : 0)
                .putFloat(tanHalfX).putFloat(tanHalfY);
        destination.putFloat(correction * scale).putFloat(correction * scale).putFloat(scale).putFloat(0);
        destination.putFloat(transform.m00()).putFloat(transform.m01()).putFloat(transform.m02()).putFloat(0);
        destination.putFloat(transform.m10()).putFloat(transform.m11()).putFloat(transform.m12()).putFloat(0);
        destination.putFloat(transform.m20()).putFloat(transform.m21()).putFloat(transform.m22()).putFloat(0);
    }
}
