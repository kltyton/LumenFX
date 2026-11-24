using LumenFx.Compiler.Unreal.Geometry.MeshDescription;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Geometry.MeshBuild;

internal static class MeshStoragePrecision
{
    internal static StaticMeshGeometry Pack(StaticMeshGeometry source, JObject settings)
    {
        int maximum = settings.Value<bool?>("bUseHighPrecisionTangentBasis") == true ? 32767 : 127;
        float[] normals = SignedNormalized(source.Normals, maximum), tangents = SignedNormalized(source.Tangents, maximum),
            signs = SignedNormalized(source.BinormalSigns, maximum);
        if (settings.Value<bool?>("bUseFullPrecisionUVs") == true)
            return source with { Normals = normals, Tangents = tangents, BinormalSigns = signs };
        bool truncate = settings.Value<bool?>("bUseBackwardsCompatibleF16TruncUVs") == true;
        float[][] coordinates = new float[source.TextureCoordinates.Count][];
        for (int channel = 0; channel < coordinates.Length; channel++)
        {
            float[] original = source.TextureCoordinates[channel];
            float[] packed = new float[original.Length];
            for (int i = 0; i < packed.Length; i++) packed[i] = truncate ? TruncatedHalf(original[i]) : (float)(Half)original[i];
            coordinates[channel] = packed;
        }
        return source with { Normals = normals, Tangents = tangents, BinormalSigns = signs, TextureCoordinates = coordinates };
    }

    private static float[] SignedNormalized(float[] source, int maximum)
    {
        float[] result = new float[source.Length];
        for (int i = 0; i < source.Length; i++)
        {
            float scaled = source[i] * maximum;
            // Windows RoundToInt32 uses cvt(2*x + .5) followed by an arithmetic right shift.
            float sum = scaled + scaled + 0.5f;
            float nearestEven = MathF.Round(sum, MidpointRounding.ToEven);
            int converted = !float.IsFinite(nearestEven) || nearestEven >= 2147483648.0f || nearestEven < -2147483648.0f
                ? int.MinValue : (int)nearestEven;
            int packed = Math.Clamp(converted >> 1, -maximum - 1, maximum);
            result[i] = MathF.Max(packed / (float)maximum, -1);
        }
        return result;
    }

    private static float TruncatedHalf(float value)
    {
        uint bits = BitConverter.SingleToUInt32Bits(value);
        uint encoded = (bits >> 16) & 0x8000;
        int exponent = (int)((bits >> 23) & 0xff);
        uint mantissa = bits & 0x7fffff;
        if (exponent <= 112)
        {
            int shift = 126 - exponent;
            if (shift <= 24)
            {
                mantissa |= 0x800000;
                encoded |= (mantissa >> shift) & 0x3ff;
                if (((mantissa >> (shift - 1)) & 1) != 0) encoded++;
            }
        }
        else if (exponent >= 143) encoded |= 0x7bff;
        else encoded |= (uint)(exponent - 112) << 10 | (mantissa >> 13);
        return (float)BitConverter.UInt16BitsToHalf((ushort)encoded);
    }
}
