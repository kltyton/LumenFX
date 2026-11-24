package com.kltyton.lumenfx.client.simulation.niagara.interfaces.splat;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.effect.niagara.interfaces.splat.NiagaraGaussianSplatAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class NiagaraGaussianSplatReader {
    public static final String TYPE = "NiagaraDataInterfaceGaussianSplatReader";
    public enum Function { GetNumSplats, GetBounds, GetBaseData, GetSHDegree, GetSHCoefficient, CalculateColor }

    public record Definition(@Nullable Identifier asset, int maxShDegree) {
        public Definition {
            maxShDegree = Math.clamp(maxShDegree, 0, NiagaraGaussianSplatAsset.MAX_SH_DEGREE);
        }

        public static Definition read(JsonObject source) {
            var asset = source.get("asset");
            return new Definition(asset == null || asset.isJsonNull() ? null : Identifier.parse(asset.getAsString()),
                    source.has("max_sh_degree") ? source.get("max_sh_degree").getAsInt() : NiagaraGaussianSplatAsset.MAX_SH_DEGREE);
        }
    }

    @FunctionalInterface public interface Input { int word(int component, int lane); }
    @FunctionalInterface public interface Output { void word(int component, int lane, int value); }

    private final NiagaraGaussianSplatAsset asset;
    private final int degree;

    public NiagaraGaussianSplatReader(NiagaraGaussianSplatAsset asset, int maxShDegree) {
        this.asset = asset;
        this.degree = Math.min(asset.degree(), maxShDegree);
    }

    public NiagaraGaussianSplatAsset asset() { return asset; }
    public int degree() { return degree; }

    public void execute(Function function, int lanes, Input input, Output output) {
        int dimensions = NiagaraGaussianSplatAsset.coefficientCount(degree);
        float[] basis = function == Function.CalculateColor && asset.count() != 0 ? new float[dimensions] : null;
        for (int lane = 0; lane < lanes; lane++) {
            switch (function) {
                case GetNumSplats -> output.word(0, lane, asset.count());
                case GetSHDegree -> output.word(0, lane, degree);
                case GetBounds -> {
                    writeVector(output, 0, lane, asset.boundsMin());
                    writeVector(output, 3, lane, asset.boundsMax());
                }
                case GetBaseData -> writeBase(input.word(0, lane), output, lane);
                case GetSHCoefficient -> {
                    int coefficient = input.word(1, lane);
                    if (asset.count() == 0 || coefficient < 0 || coefficient >= dimensions) {
                        for (int channel = 0; channel < 3; channel++) output.word(channel, lane, 0);
                    } else {
                        int row = asset.clampedOffset(input.word(0, lane));
                        int offset = row + NiagaraGaussianSplatAsset.coefficientOffset(coefficient);
                        for (int channel = 0; channel < 3; channel++) output.word(channel, lane, asset.word(offset + channel * 4));
                    }
                }
                case CalculateColor -> {
                    if (basis == null) {
                        for (int channel = 0; channel < 3; channel++) output.word(channel, lane, 0);
                    } else {
                        int row = asset.clampedOffset(input.word(0, lane));
                        float x = Float.intBitsToFloat(input.word(1, lane));
                        float y = Float.intBitsToFloat(input.word(2, lane));
                        float z = Float.intBitsToFloat(input.word(3, lane));
                        switch (asset.coordinateSpace()) {
                            case 1 -> evaluateBasis(basis, y, z, -x);
                            case 2 -> evaluateBasis(basis, y, -z, x);
                            default -> evaluateBasis(basis, x, y, z);
                        }
                        for (int channel = 0; channel < 3; channel++) {
                            float color = 0;
                            for (int coefficient = 0; coefficient < dimensions; coefficient++) {
                                int offset = NiagaraGaussianSplatAsset.coefficientOffset(coefficient) + channel * 4;
                                color += basis[coefficient] * asset.component(row + offset);
                            }
                            writeFloat(output, channel, lane, linearColor(color));
                        }
                    }
                }
            }
        }
    }

    private void writeBase(int index, Output output, int lane) {
        if (asset.count() == 0) {
            for (int i = 0; i < 11; i++) output.word(i, lane, i == 6 ? Float.floatToRawIntBits(1f) : 0);
            return;
        }
        int row = asset.clampedOffset(index);
        for (int i = 0; i < 3; i++) output.word(i, lane, asset.word(row + i * 4));
        for (int i = 0; i < 4; i++) output.word(3 + i, lane, asset.word(row + 24 + i * 4));
        for (int i = 0; i < 3; i++) output.word(7 + i, lane, asset.word(row + 12 + i * 4));
        output.word(10, lane, asset.word(row + 52));
    }

    private static void evaluateBasis(float[] basis, float x, float y, float z) {
        basis[0] = 0.28209479177387814f;
        if (basis.length == 1) return;
        basis[1] = -0.4886025119029199f * y;
        basis[2] = 0.4886025119029199f * z;
        basis[3] = -0.4886025119029199f * x;
        if (basis.length == 4) return;
        float xx = x * x, yy = y * y, zz = z * z;
        float xy = x * y, xz = x * z, yz = y * z;
        basis[4] = 1.0925484305920792f * xy;
        basis[5] = -1.0925484305920792f * yz;
        basis[6] = 0.31539156525252005f * (2 * zz - xx - yy);
        basis[7] = -1.0925484305920792f * xz;
        basis[8] = 0.5462742152960396f * (xx - yy);
        if (basis.length == 9) return;
        basis[9] = -0.5900435899266435f * y * (3 * xx - yy);
        basis[10] = 2.890611442640554f * xy * z;
        basis[11] = -0.4570457994644658f * y * (4 * zz - xx - yy);
        basis[12] = 0.3731763325901154f * z * (2 * zz - 3 * xx - 3 * yy);
        basis[13] = -0.4570457994644658f * x * (4 * zz - xx - yy);
        basis[14] = 1.445305721320277f * z * (xx - yy);
        basis[15] = -0.5900435899266435f * x * (xx - 3 * yy);
    }

    private static float linearColor(float coefficient) {
        float srgb = Math.max(coefficient + 0.5f, 6.10352e-5f);
        return srgb > 0.04045f ? (float)Math.pow(srgb * (1f / 1.055f) + 0.0521327f, 2.4f) : srgb * (1f / 12.92f);
    }

    private static void writeVector(Output output, int index, int lane, Vec3 vector) {
        writeFloat(output, index, lane, (float)vector.x);
        writeFloat(output, index + 1, lane, (float)vector.y);
        writeFloat(output, index + 2, lane, (float)vector.z);
    }

    private static void writeFloat(Output output, int component, int lane, float value) {
        output.word(component, lane, Float.floatToRawIntBits(value));
    }
}
