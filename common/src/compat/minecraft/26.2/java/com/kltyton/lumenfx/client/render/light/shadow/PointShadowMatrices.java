package com.kltyton.lumenfx.client.render.light.shadow;

import com.kltyton.lumenfx.client.render.math.NativeSceneMath;
import com.kltyton.lumenfx.effect.render.light.scene.SpotLightCone;
import com.kltyton.lumenfx.effect.render.light.scene.PointShadowLightSettings;
import com.mojang.blaze3d.ProjectionType;
import java.nio.ByteBuffer;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Light-relative shadow views retain the distinct source sampling and host raster conventions. */
public final class PointShadowMatrices implements ShadowRasterViews {
    public static final int BYTES = 6 * 64;
    private static final int[][] DIRECTIONS = {{-1,0,0}, {1,0,0}, {0,-1,0}, {0,1,0}, {0,0,-1}, {0,0,1}};
    private static final int[][] UP = {{0,1,0}, {0,1,0}, {0,0,-1}, {0,0,1}, {0,1,0}, {0,1,0}};
    private final Matrix4f[] sampling = new Matrix4f[6], raster = new Matrix4f[6];
    private final Matrix4f[] hostViews = new Matrix4f[6], hostProjections = new Matrix4f[6];
    private final float radius;
    private final float nearClip;
    private final float depthAdd, depthNumerator;
    private final float spotNear, spotFar, spotFov;
    private final float spotInverseSize, spotInverseScale;
    private final int lightingChannels;
    private final long lightIdentity;
    private final PointShadowProjectionSettings.@Nullable Spot spotSettings;

    public PointShadowMatrices(float radius, boolean zeroToOne) {
        this(radius, zeroToOne, 0L);
    }

    public PointShadowMatrices(float radius, boolean zeroToOne, long lightIdentity) {
        this(radius, zeroToOne, lightIdentity, 0f);
    }

    public PointShadowMatrices(float radius, boolean zeroToOne, long lightIdentity, float nearClipCm) {
        this.radius = radius;
        nearClip = nearClipCm > 0 ? nearClipCm : 1f;
        this.lightIdentity = lightIdentity;
        spotNear = spotFar = spotFov = 0f;
        spotInverseSize = spotInverseScale = 0f;
        lightingChannels = -1;
        spotSettings = null;
        double maximum = radius;
        double cotangent = 1.0 / NativeSceneMath.tangent((double)((float)Math.PI / 4f));
        Matrix4d projection = new Matrix4d().zero().m00(cotangent).m11(cotangent)
                .m22(maximum == nearClip ? 0 : nearClip / (nearClip - maximum)).m23(1)
                .m32(maximum == nearClip ? nearClip : -maximum * nearClip / (nearClip - maximum));
        var hostClip = new Matrix4d().m11(-1).m22(zeroToOne ? 1 : 2).m32(zeroToOne ? 0 : -1);
        depthAdd = (float) projection.m22();
        depthNumerator = (float) projection.m32();
        for (int face = 0; face < 6; face++) {
            int[] d = DIRECTIONS[face], u = UP[face];
            var z = new Vector3d(d[0], d[1], d[2]);
            var x = new Vector3d(u[0], u[1], u[2]).cross(z);
            var y = new Vector3d(z).cross(x);
            var view = new Matrix4d(x.x, -y.x, z.x, 0, x.y, -y.y, z.y, 0,
                    x.z, -y.z, z.z, 0, 0, 0, 0, 1);
            var source = new Matrix4d(projection).mul(view);
            sampling[face] = new Matrix4f(source);
            raster[face] = new Matrix4f(new Matrix4d(hostClip).mul(source));
            var direction = new Vector3d(d[0], d[2], d[1]);
            var hostUp = new Vector3d(u[0], u[2], u[1]);
            var right = new Vector3d(direction).cross(hostUp);
            hostUp.set(right).cross(direction);
            var hostView = new Matrix4d(right.x, hostUp.x, -direction.x, 0,
                    right.y, hostUp.y, -direction.y, 0, right.z, hostUp.z, -direction.z, 0, 0, 0, 0, 1);
            hostViews[face] = new Matrix4f(hostView);
            var hostToSource = new Matrix4d(1, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 1).scale(100);
            hostProjections[face] = new Matrix4f(new Matrix4d(hostClip).mul(source).mul(hostToSource).mul(hostView.transpose()));
        }
    }

    public PointShadowMatrices(float radius, SpotLightCone cone, Matrix4dc sourceWorldToLight, int size,
                               boolean zeroToOne, PointShadowLightSettings settings) {
        this(radius, cone, sourceWorldToLight, size, zeroToOne, settings, 7, 0L);
    }

    public PointShadowMatrices(float radius, SpotLightCone cone, Matrix4dc sourceWorldToLight, int size,
                               boolean zeroToOne, PointShadowLightSettings settings, int lightingChannels, long lightIdentity) {
        this.radius = radius;
        this.lightIdentity = lightIdentity;
        this.lightingChannels = lightingChannels;
        double boundsRadius = cone.boundsRadius(radius), boundsCenter = cone.boundsCenterOffset(radius);
        double near = settings.nearClipCm() > 0 ? settings.nearClipCm() : Math.max(.1f, boundsCenter - boundsRadius);
        double far = Math.min(radius, boundsCenter + boundsRadius);
        spotNear = (float)near;
        nearClip = spotNear;
        spotFar = (float)far;
        spotFov = (float)Math.toDegrees(2.0 * Math.atan(cone.tangentOuter()));
        float borderScale = (size - 2 * PointShadowResolution.SPOT_BORDER) / (float)size;
        double cotangent = (1f / cone.tangentOuter()) * borderScale;
        spotInverseSize = 1f / size;
        spotInverseScale = (float)(1.0 / cotangent);
        spotSettings = PointShadowProjectionSettings.Spot.prepare(settings, size, spotDepthRange());
        Matrix4d projection = new Matrix4d().zero().m00(cotangent).m11(cotangent)
                .m22(-near / (far - near)).m23(1).m32(far * near / (far - near));
        depthAdd = (float)projection.m22();
        depthNumerator = (float)projection.m32();
        var lightRotation = new Matrix4d(sourceWorldToLight).setTranslation(0, 0, 0);
        var face = new Matrix4d().m00(0).m22(0).m02(1).m20(-1).mul(lightRotation);
        var source = new Matrix4d(projection).mul(face);
        var hostClip = new Matrix4d().m11(-1).m22(zeroToOne ? 1 : 2).m32(zeroToOne ? 0 : -1);
        var hostToSource = new Matrix4d(1, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 1);
        var hostView = new Matrix4d().m22(-1).mul(face).mul(hostToSource);
        sampling[0] = new Matrix4f(source);
        raster[0] = new Matrix4f(new Matrix4d(hostClip).mul(source));
        hostViews[0] = new Matrix4f(hostView);
        hostProjections[0] = new Matrix4f(new Matrix4d(hostClip).mul(source).mul(hostToSource.scale(100)).mul(hostView.transpose()));
    }

    public boolean spot() { return spotNear > 0f; }
    public float nearClipCm() { return nearClip; }
    public float deviceDepthAdd() { return depthAdd; }
    public int lightingChannels() { return lightingChannels; }
    public long lightIdentity() { return lightIdentity; }
    public float spotDepthRange() { return spotFar - spotNear; }
    public PointShadowProjectionSettings.Spot spotSettings() {
        return java.util.Objects.requireNonNull(spotSettings, "Spot shadow settings require a projected light");
    }
    public void writeSpotCaster(ByteBuffer destination) {
        var settings = spotSettings();
        destination.putFloat(depthAdd).putFloat(depthNumerator).putFloat(spotInverseSize).putFloat(spotInverseScale);
        destination.putFloat(settings.casterBias()).putFloat(settings.slopeBias()).putFloat(1f).putFloat(0f);
    }
    @Override public Matrix4fc raster(int face) { return raster[face]; }
    public Matrix4fc sampling(int face) { return sampling[face]; }
    @Override public Matrix4fc hostProjection(int face) { return hostProjections[face]; }
    @Override public Matrix4fc hostView(int face) { return hostViews[face]; }
    @Override public ProjectionType projectionType() { return ProjectionType.PERSPECTIVE; }
    @Override public CameraRenderState camera(int face, Vec3 origin) {
        var state = new CameraRenderState();
        state.pos = origin; state.blockPos = BlockPos.containing(origin); state.initialized = true;
        if (spot()) {
            var view = hostViews[face];
            state.yRot = (float)Math.toDegrees(Math.atan2(view.m02(), -view.m22()));
            state.xRot = (float)Math.toDegrees(Math.asin(Math.clamp(view.m12(), -1f, 1f)));
        } else {
            state.yRot = switch (face) { case 0 -> 90; case 1 -> -90; case 2, 5 -> 180; default -> 0; };
            state.xRot = face == 4 ? 90 : face == 5 ? -90 : 0;
        }
        state.viewRotationMatrix.set(hostViews[face]); state.projectionMatrix.set(hostProjections[face]);
        new Matrix4f(hostViews[face]).transpose().getNormalizedRotation(state.orientation);
        state.depthFar = radius * .01f; state.hudFov = spot() ? spotFov : 90;
        state.cullFrustum = new Frustum(state.viewRotationMatrix, state.projectionMatrix);
        state.cullFrustum.prepare(origin.x, origin.y, origin.z);
        return state;
    }
    public void writeSampling(ByteBuffer destination) {
        for (var matrix : sampling) {
            if (matrix == null) {
                for (int word = 0; word < 16; word++) destination.putFloat(0f);
            } else {
                matrix.get(destination.position(), destination);
                destination.position(destination.position() + 64);
            }
        }
    }
    public void writeLinearDepth(ByteBuffer destination) {
        destination.putFloat(depthAdd).putFloat(depthNumerator);
    }
}
