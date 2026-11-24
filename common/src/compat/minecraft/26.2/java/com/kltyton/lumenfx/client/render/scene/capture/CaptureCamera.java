package com.kltyton.lumenfx.client.render.scene.capture;

import com.kltyton.lumenfx.api.client.parameter.NiagaraSceneCaptureInputs.ManagedSettings;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSceneCaptureInputs.Pose;
import com.kltyton.lumenfx.client.render.math.NativeSceneMath;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/** Source capture projection and its corresponding native world camera. */
final class CaptureCamera {
    private static final float NEAR = 10f;
    private static final float ORTHO_FAR = 2097152f;
    private static final Matrix4fc HOST_TO_SOURCE = new Matrix4f(
            1, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 1);
    private static final Matrix4fc SOURCE_TO_VIEW = new Matrix4f(
            0, 0, -1, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1);

    private CaptureCamera() { }

    static Matrix4f projection(ManagedSettings settings) {
        float aspect = (float)settings.width() / settings.height();
        if (settings.perspective()) {
            float angle = Math.max(.001f, settings.fov()) * (float)Math.PI / 360f;
            float tangent = (float)NativeSceneMath.tangent(angle);
            return new Matrix4f().zero().m00(1f / tangent).m11(aspect / tangent).m23(1).m32(NEAR);
        }
        float half = settings.orthoWidth() * .5f;
        float height = half / aspect;
        return new Matrix4f().m00(half == 0 ? 1 : 1f / half).m11(height == 0 ? 1 : 1f / height)
                .m22(-1f / ORTHO_FAR).m32(1);
    }

    static CameraRenderState host(Pose pose, Matrix4fc projection, float far, boolean zeroToOne) {
        var state = new CameraRenderState();
        var position = pose.position();
        state.pos = new Vec3(position.x * .01, position.z * .01, position.y * .01);
        state.blockPos = BlockPos.containing(state.pos);
        state.initialized = true;
        state.depthFar = far;
        state.viewRotationMatrix.set(SOURCE_TO_VIEW)
                .mul(new Matrix4f().rotation(pose.rotation()).transpose()).mul(HOST_TO_SOURCE);
        var viewToSource = new Matrix4f().scaling(100, 100, -100);
        var reverseZ = new Matrix4f(projection).mul(viewToSource);
        return complete(state, reverseZ, zeroToOne);
    }

    private static CameraRenderState complete(CameraRenderState state, Matrix4fc reverseZ, boolean zeroToOne) {
        new Matrix4f(state.viewRotationMatrix).transpose().getNormalizedRotation(state.orientation);
        var direction = state.orientation.transform(new Vector3f(0, 0, -1));
        state.yRot = (float)Math.toDegrees(Math.atan2(-direction.x, direction.z));
        state.xRot = (float)Math.toDegrees(Math.asin(-Math.clamp(direction.y, -1f, 1f)));
        var fullClip = new Matrix4f().m22(2).m32(-1).mul(reverseZ);
        state.projectionMatrix.set(zeroToOne ? reverseZ : fullClip);
        state.cullFrustum = new Frustum(state.viewRotationMatrix, fullClip);
        state.cullFrustum.prepare(state.pos.x, state.pos.y, state.pos.z);
        return state;
    }
}
