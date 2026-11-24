package com.kltyton.lumenfx.client.render.math;

import com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Vector3d;

/** Source working-space chromaticities determine the RGB response of component light temperature. */
public final class SourceColorSpace {
    public static final SourceColorSpace SRGB = new SourceColorSpace(.64, .33, .30, .60, .15, .06, .3127, .3290);
    private final Matrix4d xyzToRgb;
    private final Vec3 luminanceWeights;

    public SourceColorSpace(double rx, double ry, double gx, double gy, double bx, double by, double wx, double wy) {
        Matrix4d rgbToXyz = new Matrix4d().m00(rx).m01(ry).m02(1.0 - rx - ry)
                .m10(gx).m11(gy).m12(1.0 - gx - gy).m20(bx).m21(by).m22(1.0 - bx - by);
        Matrix4d inverse = NiagaraComponentMatrices.inverse(rgbToXyz, new Matrix4d());
        Vector3d scale = inverse.transformDirection(new Vector3d(wx / wy, 1.0, (1.0 - wx - wy) / wy));
        for (int row = 0; row < 3; row++) {
            rgbToXyz.set(0, row, rgbToXyz.get(0, row) * scale.x);
            rgbToXyz.set(1, row, rgbToXyz.get(1, row) * scale.y);
            rgbToXyz.set(2, row, rgbToXyz.get(2, row) * scale.z);
        }
        luminanceWeights = new Vec3(rgbToXyz.m01(), rgbToXyz.m11(), rgbToXyz.m21());
        xyzToRgb = NiagaraComponentMatrices.inverse(rgbToXyz, inverse);
    }

    public double luminance(Vec3 color) { return luminanceWeights.dot(color); }

    public Vec3 temperature(float temperature) {
        float t = temperature < 1000f ? 1000f : temperature < 15000f ? temperature : 15000f;
        float u = (0.860117757f + 1.54118254e-4f * t + 1.28641212e-7f * t * t)
                / (1f + 8.42420235e-4f * t + 7.08145163e-7f * t * t);
        float v = (0.317398726f + 4.22806245e-5f * t + 4.20481691e-8f * t * t)
                / (1f - 2.89741816e-5f * t + 1.61456053e-7f * t * t);
        float x = 3f * u / (2f * u - 8f * v + 4f);
        float y = 2f * v / (2f * u - 8f * v + 4f), z = 1f - x - y;
        double xx = 1.0 / y * x, zz = 1.0 / y * z;
        float red = (float)((xyzToRgb.m00() * xx + xyzToRgb.m10()) + xyzToRgb.m20() * zz);
        float green = (float)((xyzToRgb.m01() * xx + xyzToRgb.m11()) + xyzToRgb.m21() * zz);
        float blue = (float)((xyzToRgb.m02() * xx + xyzToRgb.m12()) + xyzToRgb.m22() * zz);
        return new Vec3(Math.max(0f, red), Math.max(0f, green), Math.max(0f, blue));
    }
}
