package com.kltyton.lumenfx.client.render.light.scene;

import com.kltyton.lumenfx.client.render.light.function.LightFunctionFade;
import com.kltyton.lumenfx.client.render.light.function.LightFunctionView;
import com.kltyton.lumenfx.client.render.math.UnrealRendererMath;
import com.kltyton.lumenfx.client.render.scene.view.ViewFrustum;
import com.kltyton.lumenfx.client.render.scene.view.SourceViewLod;
import com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector4d;

/** One view's actual depth convention and source-centimeter transforms serve every component light. */
public final class PointLightProjection {
    private static final Matrix4dc AXES = new Matrix4d(
            1, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 1);
    private final Matrix4d clipToTranslatedWorld, clipToWorld, clipToView, worldToClip;
    private final Matrix4f viewToSourceWorld;
    private final Vec3 cameraOrigin, forward, camera;
    private final int width, height;
    private final float projectedScale;
    private final boolean orthographic;
    private final ViewFrustum frustum;
    private final float lodDistanceFactor;
    private final PointLightVisibility.Controls visibility;

    public PointLightProjection(Matrix4fc projection, Matrix4fc modelView, Vec3 camera,
                                int width, int height, boolean zeroToOne) {
        this(projection, modelView, camera, width, height, zeroToOne, PointLightVisibility.Controls.DEFAULT);
    }
    public PointLightProjection(Matrix4fc projection, Matrix4fc modelView, Vec3 camera,
                                int width, int height, boolean zeroToOne, PointLightVisibility.Controls visibility) {
        this.camera = camera; this.width = width; this.height = height;
        orthographic = projection.m33() >= 1;
        var inverseView = new Matrix4d(modelView).invert();
        var viewToSource = new Matrix4d().scaling(100).mul(AXES).mul(inverseView);
        viewToSourceWorld = new Matrix4f(viewToSource);
        // LightFunctionView uses top-origin pixels. Compose the host depth and pixel-Y conversion once.
        var hostClip = new Matrix4d().m11(-1).m22(zeroToOne ? 1 : 2).m32(zeroToOne ? 0 : -1);
        var clipToHostView = new Matrix4d(projection).invert().mul(hostClip);
        clipToTranslatedWorld = new Matrix4d(viewToSource).mul(clipToHostView);
        clipToWorld = new Matrix4d().translation(camera.x * 100, camera.z * 100, camera.y * 100).mul(clipToTranslatedWorld);
        clipToView = new Matrix4d().scaling(100, 100, -100).mul(clipToHostView);
        worldToClip = new Matrix4d(projection).mul(new Matrix4d(modelView));
        frustum = new ViewFrustum(worldToClip, zeroToOne);
        lodDistanceFactor = SourceViewLod.factor(projection); this.visibility = visibility;
        cameraOrigin = new Vec3(viewToSource.m30(), viewToSource.m31(), viewToSource.m32());
        Vector3d direction = viewToSource.transformDirection(0, 0, -1, new Vector3d()).normalize();
        forward = new Vec3(direction.x, direction.y, direction.z);
        projectedScale = Math.max(width * .5f * Math.abs(projection.m00()), height * .5f * Math.abs(projection.m11()));
    }

    public Matrix4fc viewToSourceWorld() { return viewToSourceWorld; }

    public float shadowScreenRadius(ScenePointLight light) {
        var shader = light.data().shader();
        var cone = shader.cone();
        if (cone != null) {
            float radius = light.data().radius();
            Vec3 center = shader.worldPosition().subtract(shader.direction().scale(radius * cone.cosineOuter()));
            float distance = (float)Math.sqrt(distanceSquared(center));
            return projectedScale * radius * cone.sineOuter() / UnrealRendererMath.max(distance, 1f);
        }
        float distance = (float)Math.sqrt(distanceSquared(light));
        return projectedScale * light.data().radius() / UnrealRendererMath.max(distance, 1f);
    }

    private double distanceSquared(ScenePointLight light) {
        return distanceSquared(light.data().shader().worldPosition());
    }

    private double distanceSquared(Vec3 source) {
        double dx = source.x - camera.x * 100 - cameraOrigin.x;
        double dy = source.y - camera.z * 100 - cameraOrigin.y;
        double dz = source.z - camera.y * 100 - cameraOrigin.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public boolean visible(ScenePointLight light) {
        return visible(light, 1);
    }

    public boolean visible(ScenePointLight light, int receiverChannels) {
        if ((light.data().lightingChannels() & receiverChannels) == 0) return false;
        return visibleBounds(light);
    }

    public boolean visibleBounds(ScenePointLight light) {
        var shader = light.data().shader();
        var cone = shader.cone();
        float radius = light.data().radius();
        Vec3 center = shader.worldPosition();
        double boundsRadius = radius;
        if (cone != null) {
            center = center.subtract(shader.direction().scale(cone.boundsCenterOffset(radius)));
            boundsRadius = cone.boundsRadius(radius);
        }
        if (!frustum.intersectsSphere(center.x * .01 - camera.x, center.z * .01 - camera.y,
                center.y * .01 - camera.z, boundsRadius * .01)) return false;
        return orthographic || PointLightVisibility.inRange(light.data().radius(), (float)distanceSquared(light), lodDistanceFactor,
                light.data().distanceSettings(), visibility);
    }

    public float colorFade(ScenePointLight light) {
        return orthographic ? 1 : PointLightVisibility.colorFade(light.data().radius(), (float)distanceSquared(light),
                lodDistanceFactor, light.data().distanceSettings(), visibility);
    }

    public LightFunctionView function(ScenePointLight light, LightFunctionFade fade) {
        var shader = light.data().shader();
        Vec3 source = shader.worldPosition();
        var cone = shader.cone();
        var rect = shader.rect();
        float tangent = cone != null ? cone.tangentOuter() : rect != null ? rect.lightFunctionTangent() : 0f;
        Vector4d screen = worldToClip.transform(new Vector4d(source.x * .01 - camera.x,
                source.z * .01 - camera.y, source.y * .01 - camera.z, 1));
        float screenW = (float)(orthographic ? screen.w : screen.w * 100);
        float radius = projectedScale * light.data().radius() / UnrealRendererMath.max(screenW, 1f);
        return new LightFunctionView(clipToWorld, clipToTranslatedWorld, clipToView,
                light.data().worldToLight(new Matrix4d()), light.data().lightFunction(), 0, 0,
                width, height, height, false, tangent, fade.alpha(radius),
                tangent > 0f, cone == null && rect == null, false, orthographic,
                cameraOrigin, forward, source,
                new Vec3(camera.x * 100, camera.z * 100, camera.y * 100).add(cameraOrigin));
    }
}
