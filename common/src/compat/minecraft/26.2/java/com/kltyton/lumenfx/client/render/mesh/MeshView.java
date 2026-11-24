package com.kltyton.lumenfx.client.render.mesh;

import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.effect.niagara.component.NiagaraTranslucencySort;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment.TranslucentSortPolicy;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import com.kltyton.lumenfx.effect.render.mesh.MeshLodPolicy;

/** Camera data captured before workers run; distances use source centimetres. */
public final class MeshView {
    private final float screenMultiple, perspective, lodDistanceFactor;
    private final float[][] planes;
    private final String platform;
    private final float forwardX, forwardY, forwardZ;
    private final MeshLodPolicy lodPolicy;
    private final boolean shadowPass;
    private final TranslucentSortPolicy translucentSortPolicy;
    private final Vec3 translucentSortAxis;
    public record ShadowBounds(Vec3 centre, double radius) { }
    private final @org.jspecify.annotations.Nullable ShadowBounds shadowBounds;

    public MeshView(Matrix4fc projection, Matrix4fc modelView, boolean zeroToOne, float lodDistanceFactor, String platform) {
        this(projection, projection, modelView, zeroToOne, lodDistanceFactor, platform, false, null, 0);
    }

    public static MeshView pointShadow(Matrix4fc projection, Matrix4fc modelView, boolean zeroToOne,
                                       float lodDistanceFactor, String platform, Vec3 centre, double radius) {
        return new MeshView(projection, projection, modelView, zeroToOne, lodDistanceFactor, platform, true, centre, radius);
    }

    public static MeshView directionalShadow(Matrix4fc projection, Matrix4fc cullingProjection, Matrix4fc modelView, boolean zeroToOne,
                                             float lodDistanceFactor, String platform) {
        return new MeshView(projection, cullingProjection, modelView, zeroToOne, lodDistanceFactor, platform, true, null, 0);
    }

    private MeshView(Matrix4fc projection, Matrix4fc cullingProjection, Matrix4fc modelView, boolean zeroToOne, float lodDistanceFactor,
                     String platform, boolean shadowPass, @org.jspecify.annotations.Nullable Vec3 shadowCentre, double radius) {
        planes = new float[6][4];
        lodPolicy = MeshLodPolicy.DEFAULT;
        this.shadowPass = shadowPass;
        shadowBounds = shadowCentre == null ? null : new ShadowBounds(shadowCentre, radius);
        this.screenMultiple = Math.max(projection.m00(), projection.m11()) * .5f;
        this.perspective = projection.m33() >= 1 ? 0 : 1;
        this.lodDistanceFactor = lodDistanceFactor;
        this.platform = platform;
        forwardX = -modelView.m02(); forwardY = -modelView.m12(); forwardZ = -modelView.m22();
        var sourcePlatform = NiagaraPlatforms.snapshot();
        translucentSortPolicy = sourcePlatform.translucentSortPolicy();
        Vec3 axis = sourcePlatform.translucencySortAxis();
        translucentSortAxis = new Vec3(axis.x, axis.z, axis.y);
        if (shadowCentre != null) {
            plane(0, 1, 0, 0, (float)(radius - shadowCentre.x));
            plane(1, -1, 0, 0, (float)(radius + shadowCentre.x));
            plane(2, 0, 1, 0, (float)(radius - shadowCentre.y));
            plane(3, 0, -1, 0, (float)(radius + shadowCentre.y));
            plane(4, 0, 0, 1, (float)(radius - shadowCentre.z));
            plane(5, 0, 0, -1, (float)(radius + shadowCentre.z));
            return;
        }
        Matrix4f matrix = new Matrix4f(cullingProjection).mul(modelView);
        plane(0, matrix.m03() + matrix.m00(), matrix.m13() + matrix.m10(), matrix.m23() + matrix.m20(), matrix.m33() + matrix.m30());
        plane(1, matrix.m03() - matrix.m00(), matrix.m13() - matrix.m10(), matrix.m23() - matrix.m20(), matrix.m33() - matrix.m30());
        plane(2, matrix.m03() + matrix.m01(), matrix.m13() + matrix.m11(), matrix.m23() + matrix.m21(), matrix.m33() + matrix.m31());
        plane(3, matrix.m03() - matrix.m01(), matrix.m13() - matrix.m11(), matrix.m23() - matrix.m21(), matrix.m33() - matrix.m31());
        if (zeroToOne) plane(4, matrix.m02(), matrix.m12(), matrix.m22(), matrix.m32());
        else plane(4, matrix.m03() + matrix.m02(), matrix.m13() + matrix.m12(), matrix.m23() + matrix.m22(), matrix.m33() + matrix.m32());
        plane(5, matrix.m03() - matrix.m02(), matrix.m13() - matrix.m12(), matrix.m23() - matrix.m22(), matrix.m33() - matrix.m32());
    }

    private MeshView(MeshView source, MeshLodPolicy policy) {
        screenMultiple = source.screenMultiple; perspective = source.perspective; lodDistanceFactor = source.lodDistanceFactor;
        planes = source.planes; platform = source.platform; forwardX = source.forwardX; forwardY = source.forwardY; forwardZ = source.forwardZ;
        shadowBounds = source.shadowBounds;
        shadowPass = source.shadowPass;
        translucentSortPolicy = source.translucentSortPolicy;
        translucentSortAxis = source.translucentSortAxis;
        lodPolicy = policy;
    }

    public MeshView withLodPolicy(MeshLodPolicy policy) { return lodPolicy.equals(policy) ? this : new MeshView(this, policy); }
    public MeshLodPolicy lodPolicy() { return lodPolicy; }
    public boolean pointShadow() { return shadowBounds != null; }
    public boolean shadowPass() { return shadowPass; }
    public @org.jspecify.annotations.Nullable ShadowBounds shadowBounds() { return shadowBounds; }

    private void plane(int index, float x, float y, float z, float w) {
        float length = (float)Math.sqrt(x * x + y * y + z * z);
        if (length == 0) { planes[index][3] = w; return; }
        planes[index][0] = x / length; planes[index][1] = y / length;
        planes[index][2] = z / length; planes[index][3] = w / length;
    }

    public boolean visible(Vec3 cameraRelative, double radius) {
        for (float[] plane : planes)
            if (plane[0] * cameraRelative.x + plane[1] * cameraRelative.y + plane[2] * cameraRelative.z + plane[3] < -radius)
                return false;
        return true;
    }
    public float screenMultiple() { return screenMultiple; }
    public float perspective() { return perspective; }
    public float lodDistanceFactor() { return lodDistanceFactor; }
    public String platform() { return platform; }
    public double depth(Vec3 relative) { return relative.x * forwardX + relative.y * forwardY + relative.z * forwardZ; }

    public double translucentSortKey(Vec3 relative) {
        return switch (translucentSortPolicy) {
            case DISTANCE -> relative.lengthSqr();
            case PROJECTED_Z -> NiagaraTranslucencySort.distanceKey(depth(relative));
            case ALONG_AXIS -> NiagaraTranslucencySort.distanceKey(relative.dot(translucentSortAxis));
        };
    }

    public float[] sourcePlanes(com.kltyton.lumenfx.api.client.EffectHandle.Pose pose, boolean localSpace,
                                Vec3 camera, double originX, double originY, double originZ) {
        float[] result = new float[planes.length * 4];
        for (int index = 0; index < planes.length; index++) {
            float[] plane = planes[index];
            var normal = new org.joml.Vector3f(plane[0], plane[2], plane[1]);
            double distance = plane[3] * 100;
            if (localSpace) {
                distance += (pose.position().x - camera.x) * 100 * normal.x
                        + (pose.position().z - camera.z) * 100 * normal.y + (pose.position().y - camera.y) * 100 * normal.z;
                new org.joml.Quaternionf(-pose.x(), -pose.z(), -pose.y(), pose.w()).conjugate().transform(normal)
                        .mul(pose.scaleX(), pose.scaleZ(), pose.scaleY());
            } else distance += (originX - camera.x * 100) * normal.x
                    + (originY - camera.z * 100) * normal.y + (originZ - camera.y * 100) * normal.z;
            float length = normal.length();
            int offset = index * 4;
            if (length == 0) { result[offset + 3] = (float)distance; continue; }
            result[offset] = -normal.x / length; result[offset + 1] = -normal.y / length; result[offset + 2] = -normal.z / length;
            result[offset + 3] = (float)(distance / length);
        }
        return result;
    }
}
