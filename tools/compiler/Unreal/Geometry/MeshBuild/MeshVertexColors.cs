using System.Globalization;
using System.Text.RegularExpressions;
using LumenFx.Compiler.Unreal.Geometry.MeshDescription;

namespace LumenFx.Compiler.Unreal.Geometry.MeshBuild;

internal sealed partial class MeshVertexColors
{
    private const int MinimumBits = (127 - 13) << 23;
    private static readonly float Minimum = BitConverter.Int32BitsToSingle(MinimumBits);
    private static readonly float Maximum = BitConverter.Int32BitsToSingle(0x3f7fffff);
    private readonly uint[] table;

    [GeneratedRegex("0[xX][0-9a-fA-F]+")]
    private static partial Regex HexValues();

    internal MeshVertexColors(string unrealSourceRoot)
    {
        string path = Path.Combine(unrealSourceRoot, "Engine/Source/Runtime/Core/Private/Math/Color.cpp");
        string source = File.ReadAllText(path);
        const string declaration = "stb_fp32_to_srgb8_tab4[104]";
        int start = source.IndexOf(declaration, StringComparison.Ordinal);
        if (start < 0) throw new ConversionException($"Missing source sRGB table: {path}");
        int open = source.IndexOf('{', start + declaration.Length);
        int close = open < 0 ? -1 : source.IndexOf("};", open, StringComparison.Ordinal);
        if (close < 0) throw new ConversionException($"Unterminated source sRGB table: {path}");
        table = HexValues().Matches(source[(open + 1)..close]).Select(match =>
            uint.Parse(match.Value.AsSpan(2), NumberStyles.HexNumber, CultureInfo.InvariantCulture)).ToArray();
        if (table.Length != 104) throw new ConversionException($"Unexpected source sRGB table length: {path}");
    }

    internal StaticMeshGeometry Pack(StaticMeshGeometry source)
    {
        float[] result = new float[source.Colors.Length];
        for (int offset = 0; offset < result.Length; offset += 4)
        {
            for (int channel = 0; channel < 3; channel++) result[offset + channel] = Srgb(source.Colors[offset + channel]) / 255.0f;
            float alpha = source.Colors[offset + 3];
            float clamped = alpha > 1 ? 1 : alpha >= 0 ? alpha : 0;
            result[offset + 3] = (int)(0.5f + clamped * 255.0f) / 255.0f;
        }
        return source with { Colors = result };
    }

    private uint Srgb(float linear)
    {
        if (!(linear > Minimum)) linear = Minimum;
        if (linear > Maximum) linear = Maximum;
        uint bits = BitConverter.SingleToUInt32Bits(linear);
        uint entry = table[(bits - MinimumBits) >> 20];
        uint bias = (entry >> 16) << 9, scale = entry & 0xffff, t = (bits >> 12) & 0xff;
        return (bias + scale * t) >> 16;
    }
}
