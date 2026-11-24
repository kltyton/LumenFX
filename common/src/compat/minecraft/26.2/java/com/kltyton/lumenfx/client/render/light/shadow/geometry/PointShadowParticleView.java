package com.kltyton.lumenfx.client.render.light.shadow.geometry;

import com.kltyton.lumenfx.client.render.light.shadow.ShadowRasterViews;
import com.kltyton.lumenfx.client.render.light.shadow.directional.DirectionalShadowMatrices;
import com.kltyton.lumenfx.client.render.mesh.MeshView;
import com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;

/** Shadow caster material orientation and raster projection are tracked separately. */
public record PointShadowParticleView(Vec3 camera, Vec3 lightOrigin, Matrix4fc modelView, Matrix4fc projection,
                                     Quaternionf orientation, MeshView meshView, float ribbonLodDistanceFactor) {
    private static final Matrix4fc HOST_TO_SOURCE = new Matrix4f().m11(0).m22(0).m12(1).m21(1);
    private static final Matrix4fc LIGHT_TO_HOST_VIEW = new Matrix4f().m00(0).m22(0).m02(-1).m20(-1);

    public static PointShadowParticleView create(ScenePointLight light, Vec3 camera, Matrix4fc projection,
                                                 float lodDistanceFactor, boolean zeroToOne, String platform) {
        var source = light.data().shader().worldPosition();
        var lightOrigin = new Vec3(source.x * .01, source.z * .01, source.y * .01);
        var worldToLight = light.data().worldToLight(new Matrix4d()).setTranslation(0, 0, 0);
        var modelView = new Matrix4f(LIGHT_TO_HOST_VIEW).mul(new Matrix4f(worldToLight)).mul(HOST_TO_SOURCE);
        var orientation = new Matrix4f(modelView).transpose().getNormalizedRotation(new Quaternionf());
        var meshView = MeshView.pointShadow(projection, modelView, zeroToOne, lodDistanceFactor, platform,
                lightOrigin.subtract(camera), light.data().radius() * .01);
        return new PointShadowParticleView(camera, lightOrigin, modelView, projection, orientation, meshView, lodDistanceFactor);
    }

    public static PointShadowParticleView directional(DirectionalShadowMatrices matrices, int cascade,
                                                       float lodDistanceFactor, boolean zeroToOne, String platform) {
        var projection = matrices.particleProjection(cascade);
        var modelView = matrices.hostView(cascade);
        var orientation = new Matrix4f(modelView).transpose().getNormalizedRotation(new Quaternionf());
        var meshView = MeshView.directionalShadow(projection, matrices.particleCullProjection(cascade), modelView,
                zeroToOne, lodDistanceFactor, platform);
        return new PointShadowParticleView(matrices.origin(), matrices.origin(), modelView, projection, orientation, meshView, lodDistanceFactor);
    }

    public Matrix4f clip(ShadowRasterViews matrices, int face) {
        Vec3 offset = camera.subtract(lightOrigin);
        return new Matrix4f(matrices.hostProjection(face))
                .mul(matrices.hostView(face))
                .translate((float)offset.x, (float)offset.y, (float)offset.z);
    }
}
