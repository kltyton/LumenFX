package com.kltyton.lumenfx.client.render.light.shadow.directional;

import com.kltyton.lumenfx.client.render.light.shadow.ShadowRasterViews;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderPass;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** The camera split sphere defines a stable light-space square and the atlas tile defines its UV transform. */
public final class DirectionalShadowMatrices implements ShadowRasterViews {
    private static final double MIN_SUBJECT_DEPTH_METRES = 50;
    private static final double HALF_SOURCE_WORLD_METRES = 10485.76;
    private static final Matrix4d SOURCE_TO_HOST = new Matrix4d().m11(0).m22(0).m12(1).m21(1).scale(.01);

    record Cascade(Matrix4fc sampling, Matrix4fc raster, Matrix4fc tiledProjection, Matrix4f casterProjection,
                   Matrix4f particleProjection, Matrix4fc particleCullProjection, Vector4f range, Vector4f uvBounds,
                   Vector3f casterBias) { }
    public record ViewSphere(Vector3f center, double radius) { }

    private final List<Cascade> cascades;
    private final Matrix4f lightView;
    private final Vec3 camera;
    private final boolean zeroToOne;
    private final int tileSize, tileColumns, lightingChannels, cascadeLimit;
    private final long lightIdentity;

    DirectionalShadowMatrices(Vec3 camera, Vec3 sourceDirection, Matrix4fc cameraProjection, Matrix4fc cameraView,
                              double[] splits, float transition, float sourceBias, float sourceSlope, float maxSlope,
                              float biasDistribution, boolean zeroToOne, DirectionalShadowAtlas atlas,
                              int lightingChannels, long lightIdentity, int cascadeLimit) {
        this.camera = camera;
        this.zeroToOne = zeroToOne;
        tileSize = atlas.size();
        tileColumns = atlas.width() / tileSize;
        this.lightingChannels = lightingChannels;
        this.lightIdentity = lightIdentity;
        this.cascadeLimit = cascadeLimit;
        var rotation = viewRotation(sourceDirection);
        lightView = new Matrix4f(rotation);
        var cameraToWorld = new Matrix4f(cameraView).invert();
        var worldCameraLight = rotation.transformDirection(new Vector3d(camera.x, camera.y, camera.z));
        int count = atlas.viewCount();
        var prepared = new ArrayList<Cascade>(count);
        for (int index = 0; index < count; index++) {
            double splitNear = splits[index], splitFar = splits[index + 1];
            double end = index + 2 < splits.length ? splitFar + (splitFar - splitNear) * transition : splitFar;
            double nearFade = index == 0 ? 0 : (splitNear - splits[index - 1]) * transition;
            prepared.add(cascade(rotation, cameraToWorld, worldCameraLight, cameraProjection, splitNear, end,
                    nearFade, sourceBias, sourceSlope, maxSlope, biasDistribution, zeroToOne, atlas, index));
        }
        cascades = List.copyOf(prepared);
    }

    private static Cascade cascade(Matrix4d light, Matrix4fc cameraToWorld, Vector3d worldCameraLight, Matrix4fc projection,
                                   double near, double far, double nearFade, float sourceBias, float sourceSlope,
                                   float maxSlope, float biasDistribution,
                                   boolean zeroToOne, DirectionalShadowAtlas atlas, int index) {
        var bounds = viewSphere(projection, cameraToWorld, near, far);
        double radius = Math.ceil(bounds.radius() * 100) * .01;
        var center = light.transformDirection(new Vector3d(bounds.center()));
        double pitch = 8 * radius / atlas.innerSize();
        double x = center.x - (worldCameraLight.x + center.x) % pitch;
        double y = center.y - (worldCameraLight.y + center.y) % pitch;
        double depth = Math.max(MIN_SUBJECT_DEPTH_METRES, radius);
        var normalized = new Matrix4d().m00(1 / radius).m11(1 / radius).m22(.5 / depth)
                .m30(-x / radius).m31(-y / radius).m32(.5 - center.z * .5 / depth);
        double originX = atlas.x(index) + DirectionalShadowAtlas.BORDER;
        double originY = atlas.y(index) + DirectionalShadowAtlas.BORDER;
        double endX = originX + atlas.innerSize(), endY = originY + atlas.innerSize();
        var uv = new Matrix4d().m00(.5 * atlas.innerSize() / atlas.width()).m11(.5 * atlas.innerSize() / atlas.height())
                .m30((originX + endX) * .5 / atlas.width()).m31((originY + endY) * .5 / atlas.height());
        var tiled = uv.mul(normalized, new Matrix4d());
        var clip = new Matrix4d().m00(2).m11(2).m22(zeroToOne ? 1 : 2).m30(-1).m31(-1).m32(zeroToOne ? 0 : -1);
        var hostProjection = clip.mul(tiled, new Matrix4d());
        var sourceProjection = new Matrix4d(hostProjection).mul(light).mul(SOURCE_TO_HOST);
        var fullClip = new Matrix4d().m22(zeroToOne ? 1 : 2).m32(zeroToOne ? 0 : -1).mul(normalized);
        // FrustumIntersection expects the homogeneous -1..1 depth interval on both backends.
        double casterDepth = depth + Math.max(radius, HALF_SOURCE_WORLD_METRES);
        var casterBase = new Matrix4d(normalized).m22(1 / casterDepth).m32((depth - center.z) / casterDepth);
        var caster = new Matrix4d().m22(2).m32(-1).mul(casterBase);
        var particleCull = new Matrix4d().m22(zeroToOne ? 1 : 2).m32(zeroToOne ? 0 : -1).mul(casterBase);
        float bias = (float) (sourceBias / (200 * depth) * (1 + (radius * 100 / atlas.innerSize() - 1) * biasDistribution));
        var casterBias = new Vector3f(Math.max(0, bias), Math.max(0, bias * sourceSlope), maxSlope);
        return new Cascade(new Matrix4f(tiled.mul(light)), new Matrix4f(sourceProjection), new Matrix4f(hostProjection),
                new Matrix4f(caster), new Matrix4f(fullClip), new Matrix4f(particleCull),
                new Vector4f((float) near, (float) far, (float) nearFade, casterBias.x), new Vector4f((float) (originX / atlas.width()),
                (float) (originY / atlas.height()), (float) (endX / atlas.width()), (float) (endY / atlas.height())), casterBias);
    }

    public static Matrix4d viewRotation(Vec3 sourceDirection) {
        var direction = new Vector3d(-sourceDirection.x, -sourceDirection.z, -sourceDirection.y).normalize();
        var vertical = Math.abs(direction.y) > .999 ? new Vector3d(0, 0, 1) : new Vector3d(0, 1, 0);
        return new Matrix4d().lookAlong(direction, vertical);
    }

    public static ViewSphere viewSphere(Matrix4fc projection, Matrix4fc cameraToWorld, double near, double far) {
        double halfX = 1.0 / Math.abs(projection.m00()), halfY = 1.0 / Math.abs(projection.m11());
        double diagonal = halfX * halfX + halfY * halfY;
        double length = far - near;
        double offset = (near * near - far * far) * diagonal / (2 * length) + length * .5;
        double centerDepth = Math.clamp(far - offset, near, far);
        double radiusSquared = 0;
        for (double depth : new double[] {near, far}) {
            double x = halfX * depth * (1 + Math.abs(projection.m20()));
            double y = halfY * depth * (1 + Math.abs(projection.m21()));
            double z = depth - centerDepth;
            radiusSquared = Math.max(radiusSquared, x * x + y * y + z * z);
        }
        var centerWorld = cameraToWorld.transformDirection(0, 0, (float) -centerDepth, new Vector3f());
        return new ViewSphere(centerWorld, Math.max(.01, Math.sqrt(radiusSquared)));
    }

    List<Cascade> cascades() { return cascades; }
    boolean zeroToOne() { return zeroToOne; }
    public Vec3 origin() { return camera; }
    public Matrix4fc particleProjection(int index) { return cascades.get(index).particleProjection(); }
    public int lightingChannels() { return lightingChannels; }
    public long lightIdentity() { return lightIdentity; }
    public int cascadeLimit() { return cascadeLimit; }
    public float casterDepthBias(int index) { return cascades.get(index).casterBias().x; }
    public RenderPass.RenderArea outerTile(int index) {
        return new RenderPass.RenderArea(index % tileColumns * tileSize, index / tileColumns * tileSize, tileSize, tileSize);
    }
    public Matrix4f outerProjection(int index) {
        float scale = (tileSize - 2f * DirectionalShadowAtlas.BORDER) / tileSize;
        return new Matrix4f().scaling(scale, scale, 1f).mul(particleProjection(index));
    }
    public Matrix4fc particleCullProjection(int index) { return cascades.get(index).particleCullProjection(); }
    public Frustum frustum(int index) {
        var frustum = new Frustum(lightView, cascades.get(index).casterProjection());
        frustum.prepare(camera.x, camera.y, camera.z);
        return frustum;
    }
    @Override public Matrix4fc raster(int index) { return cascades.get(index).raster(); }
    @Override public Matrix4fc hostView(int index) { return lightView; }
    @Override public Matrix4fc hostProjection(int index) { return cascades.get(index).tiledProjection(); }
    @Override public ProjectionType projectionType() { return ProjectionType.ORTHOGRAPHIC; }
    @Override public CameraRenderState camera(int index, Vec3 origin) {
        var state = new CameraRenderState();
        state.pos = origin;
        state.blockPos = BlockPos.containing(origin);
        state.initialized = true;
        state.yRot = (float) Math.toDegrees(Math.atan2(lightView.m02(), -lightView.m22()));
        state.xRot = (float) Math.toDegrees(Math.asin(Math.clamp(lightView.m12(), -1f, 1f)));
        state.viewRotationMatrix.set(lightView);
        state.projectionMatrix.set(hostProjection(index));
        new Matrix4f(lightView).transpose().getNormalizedRotation(state.orientation);
        state.depthFar = cascades.get(index).range().y;
        state.cullFrustum = frustum(index);
        return state;
    }
}
