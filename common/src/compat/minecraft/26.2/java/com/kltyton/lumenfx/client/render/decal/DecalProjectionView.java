package com.kltyton.lumenfx.client.render.decal;

import com.kltyton.lumenfx.effect.render.decal.ParticleDecal;
import com.kltyton.lumenfx.client.render.precision.WorldOriginUniforms;
import com.kltyton.lumenfx.client.render.scene.view.ViewFrustum;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaterniond;

/** A render view owns its projection inverses and per-decal matrix upload scratch. */
public final class DecalProjectionView {
    public static final int UNIFORM_BYTES = 400 + WorldOriginUniforms.BYTES;
    public record Shading(float screenOpacity, float lifetimeOpacity, float preExposure, boolean inside) { }
    private static final Matrix4dc AXES = new Matrix4d(
            1, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 1);
    private final Matrix4d pixelToView, pixelToWorld, viewToSourceWorld, worldToClip;
    private final Matrix4d source = new Matrix4d(), sourceInverse = new Matrix4d(), render = new Matrix4d(), scratch = new Matrix4d();
    private final Matrix4f upload = new Matrix4f();
    private final Quaterniond rotation = new Quaterniond();
    private final Vec3 camera;
    private final int width, height;
    private final boolean openGl, perspective;
    private final float projectionScaleX;
    private final ViewFrustum frustum;

    public DecalProjectionView(Matrix4fc projection, Matrix4fc modelView, Vec3 camera, RenderTarget target) {
        var device = RenderSystem.getDevice();
        boolean zeroToOne = device.getDeviceInfo().isZZeroToOne();
        openGl = device.backend instanceof com.mojang.blaze3d.opengl.GlDevice;
        width = target.width; height = target.height; this.camera = camera;
        perspective = projection.m33() < 1;
        projectionScaleX = projection.m00();
        var pixelToClip = new Matrix4d().m00(2.0 / width).m11(2.0 / height).m22(zeroToOne ? 1 : 2)
                .m30(-1).m31(-1).m32(zeroToOne ? 0 : -1);
        pixelToView = new Matrix4d(projection).invert().mul(pixelToClip);
        var inverseView = new Matrix4d(modelView).invert();
        pixelToWorld = new Matrix4d(inverseView).mul(pixelToView);
        viewToSourceWorld = new Matrix4d().translation(camera.x * 100, camera.z * 100, camera.y * 100)
                .scale(100).mul(AXES).mul(inverseView);
        worldToClip = new Matrix4d(projection).mul(new Matrix4d(modelView));
        frustum = new ViewFrustum(worldToClip, zeroToOne);
    }

    public void write(ParticleDecal decal, Shading shading, ByteBuffer data) {
        prepareSource(decal);
        inverseSource();
        put(pixelToView, data);
        put(pixelToWorld, data);
        put(scratch.set(sourceInverse).mul(viewToSourceWorld).mul(pixelToView), data);
        Vec3 half = decal.halfSizeCentimeters(), relative = decal.position().subtract(camera);
        rotation.set(decal.rotationX(), decal.rotationY(), decal.rotationZ(), decal.rotationW());
        render.translation(relative.x, relative.y, relative.z).rotate(rotation)
                .scale(half.x * .01, half.z * .01, half.y * .01).mul(AXES);
        put(render, data);
        put(scratch.set(worldToClip).mul(render), data);
        data.putFloat(1f / width).putFloat(1f / height).putFloat(1).putFloat(openGl ? -1 : 1);
        data.putFloat(decal.red()).putFloat(decal.green()).putFloat(decal.blue()).putFloat(decal.alpha());
        data.putFloat(shading.screenOpacity()).putFloat(shading.lifetimeOpacity()).putFloat(shading.preExposure())
                .putFloat(half.x * half.y * half.z < 0 ? 1 : -1);
        data.putFloat(reciprocal(axisLength(0))).putFloat(reciprocal(axisLength(1))).putFloat(reciprocal(axisLength(2))).putFloat(0);
        // Source ObjectOrientation uses the unscaled X axis, independently of mirrored dimensions.
        scratch.rotation(rotation);
        data.putFloat((float)scratch.m00()).putFloat((float)scratch.m01()).putFloat((float)scratch.m02()).putFloat(shading.inside() ? 1 : 0);
        var position = decal.position();
        WorldOriginUniforms.writeSourcePosition(data, position.x * 100, position.z * 100, position.y * 100);
    }

    public float screenOpacity(ParticleDecal decal, float multiplier) {
        if (!perspective || decal.screenSizeFade() == 0) return 1;
        prepareSource(decal);
        float radius = (float)Math.max(axisLength(0), Math.max(axisLength(1), axisLength(2)));
        float distance = (float)(decal.position().distanceTo(camera) * 100);
        float current = radius / distance * multiplier;
        float fieldOfView = 2f / projectionScaleX / width * 600f;
        float coefficient = decal.screenSizeFade() * fieldOfView;
        return com.kltyton.lumenfx.client.render.math.UnrealRendererMath.clamp((current - coefficient) / (coefficient * .5f), 0, 1);
    }

    public float conservativeRadiusCentimeters(ParticleDecal decal) {
        prepareSource(decal);
        return (float)Math.sqrt(axisSquared(0) + axisSquared(1) + axisSquared(2));
    }

    public boolean inside(ParticleDecal decal, float nearClipCentimeters) {
        float radius = conservativeRadiusCentimeters(decal);
        float margin = radius * 1.05f + nearClipCentimeters * 2f;
        return decal.position().distanceToSqr(camera) * 10000 < margin * margin;
    }

    public boolean visible(ParticleDecal decal, float radiusCentimeters) {
        double x = decal.position().x - camera.x, y = decal.position().y - camera.y, z = decal.position().z - camera.z;
        double radius = radiusCentimeters * .01;
        return frustum.intersectsSphere(x, y, z, radius);
    }

    private void prepareSource(ParticleDecal decal) {
        Vec3 half = decal.halfSizeCentimeters(), position = decal.position();
        rotation.set(-decal.rotationX(), -decal.rotationZ(), -decal.rotationY(), decal.rotationW());
        source.translation(position.x * 100, position.z * 100, position.y * 100).rotate(rotation).scale(half.x, half.y, half.z);
    }

    private void inverseSource() {
        double determinant = source.determinant();
        // TMatrix::Inverse returns identity for an all-small basis or a non-invertible matrix.
        boolean allSmall = true;
        for (int column = 0; column < 3; column++) for (int row = 0; row < 3; row++)
            allSmall &= Math.abs(source.get(column, row)) <= 1e-8f;
        if (allSmall || determinant == 0 || !Double.isFinite(determinant)) sourceInverse.identity();
        else source.invert(sourceInverse);
    }

    private double axisSquared(int axis) {
        double x = source.get(axis, 0), y = source.get(axis, 1), z = source.get(axis, 2);
        return x * x + y * y + z * z;
    }
    private double axisLength(int axis) { return Math.sqrt(axisSquared(axis)); }
    private static float reciprocal(double value) { return value == 0 ? 3.4e38f : (float)(1 / value); }
    private void put(Matrix4dc matrix, ByteBuffer data) {
        int offset = data.position();
        upload.set(matrix).get(offset, data);
        data.position(offset + 64);
    }
}
