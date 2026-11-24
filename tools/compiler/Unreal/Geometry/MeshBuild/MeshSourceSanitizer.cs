using LumenFx.Compiler.Unreal.Geometry.MeshDescription;

namespace LumenFx.Compiler.Unreal.Geometry.MeshBuild;

[Flags]
internal enum InvalidMeshChannels { None = 0, Positions = 1, TangentSpace = 2, TextureCoordinates = 4, Colors = 8 }
internal sealed record MeshSourceRepair(StaticMeshGeometry Geometry, InvalidMeshChannels Repaired);

internal static class MeshSourceSanitizer
{
    internal static MeshSourceRepair Repair(StaticMeshGeometry source)
    {
        InvalidMeshChannels repaired = InvalidMeshChannels.None;
        float[] positions = Vectors(source.Positions, 3, 0, out bool positionsChanged);
        float[] normals = Vectors(source.Normals, 3, 0, out bool normalsChanged);
        float[] tangents = Vectors(source.Tangents, 3, 0, out bool tangentsChanged);
        float[] colors = Vectors(source.Colors, 4, 1, out bool colorsChanged);
        float[] signs = source.BinormalSigns;
        bool signsChanged = false;
        for (int i = 0; i < signs.Length; i++)
        {
            // The source check is IsNaN for this scalar, rather than vector ContainsNaN.
            if (!float.IsNaN(signs[i])) continue;
            if (!signsChanged) { signs = (float[])signs.Clone(); signsChanged = true; }
            signs[i] = 0;
        }
        float[][] coordinates = new float[source.TextureCoordinates.Count][];
        bool uvChanged = false;
        for (int channel = 0; channel < coordinates.Length; channel++)
        {
            coordinates[channel] = Vectors(source.TextureCoordinates[channel], 2, 0, out bool changed);
            uvChanged |= changed;
        }
        if (positionsChanged) repaired |= InvalidMeshChannels.Positions;
        if (normalsChanged || tangentsChanged || signsChanged) repaired |= InvalidMeshChannels.TangentSpace;
        if (colorsChanged) repaired |= InvalidMeshChannels.Colors;
        if (uvChanged) repaired |= InvalidMeshChannels.TextureCoordinates;
        return new MeshSourceRepair(repaired == InvalidMeshChannels.None ? source : source with {
            Positions = positions, Normals = normals, Tangents = tangents, BinormalSigns = signs,
            Colors = colors, TextureCoordinates = coordinates }, repaired);
    }

    private static float[] Vectors(float[] source, int components, float replacement, out bool changed)
    {
        float[] result = source;
        changed = false;
        for (int offset = 0; offset < source.Length; offset += components)
        {
            bool valid = true;
            for (int component = 0; component < components; component++)
                valid &= float.IsFinite(source[offset + component]);
            if (valid) continue;
            if (!changed) { result = (float[])source.Clone(); changed = true; }
            for (int component = 0; component < components; component++) result[offset + component] = replacement;
        }
        return result;
    }
}
