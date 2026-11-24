namespace LumenFx.Compiler.Unreal.Geometry.MeshDescription;

internal enum MeshAttributeType { Vector4, Vector3, Vector2, Float, Int, Bool, Name }

internal sealed record MeshAttribute(MeshAttributeType Type, int Components, int Extent,
    int Elements, IReadOnlyList<Array> Channels, Array DefaultValue, uint Flags);

internal sealed record MeshElement(int Slots, IReadOnlyList<int> ValidIds,
    IReadOnlyDictionary<string, MeshAttribute> Attributes);

internal sealed record MeshDescriptionData(IReadOnlyDictionary<string, IReadOnlyList<MeshElement>> Elements)
{
    internal byte[]? SourceArchive { get; init; }
}
