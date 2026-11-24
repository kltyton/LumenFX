using LumenFx.Compiler.Unreal.Parameters;

namespace LumenFx.Compiler.Unreal.Types;

/** Native scalar, vector and quaternion layouts for the fixed Windows source profile. */
internal static class BuiltinStructLayouts
{
    private static readonly Dictionary<string, StructLayout> Values = Build();
    internal static bool TryGet(string path, out StructLayout? layout) => Values.TryGetValue(path, out layout);

    private static Dictionary<string, StructLayout> Build()
    {
        Dictionary<string, StructLayout> result = new(SourceNameOrder.Instance);
        void Scalars(string path, StructFieldKind kind, int width, int count, int alignment, params string[] names) {
            var fields = Enumerable.Range(0, count).Select(index => new StructField(
                names.Length >= count ? names[index] : "Value" + index, kind, index * width, width, 1, width)).ToArray();
            result.Add(path, new(path, path[(path.LastIndexOf('.') + 1)..], width * count, alignment, Array.AsReadOnly(fields)));
        }
        Scalars("/Script/Niagara.NiagaraFloat", StructFieldKind.Float, 4, 1, 4, "Value");
        Scalars("/Script/Niagara.NiagaraDouble", StructFieldKind.Double, 8, 1, 8, "Value");
        Scalars("/Script/Niagara.NiagaraInt32", StructFieldKind.Int32, 4, 1, 4, "Value");
        Scalars("/Script/Niagara.NiagaraBool", StructFieldKind.Bool, 4, 1, 4, "Value");
        Scalars("/Script/CoreUObject.Vector2f", StructFieldKind.Float, 4, 2, 4, "X", "Y");
        Scalars("/Script/CoreUObject.Vector3f", StructFieldKind.Float, 4, 3, 4, "X", "Y", "Z");
        Scalars("/Script/CoreUObject.Vector4f", StructFieldKind.Float, 4, 4, 16, "X", "Y", "Z", "W");
        Scalars("/Script/CoreUObject.Quat4f", StructFieldKind.Float, 4, 4, 16, "X", "Y", "Z", "W");
        Scalars("/Script/CoreUObject.LinearColor", StructFieldKind.Float, 4, 4, 4, "R", "G", "B", "A");
        Scalars("/Script/Niagara.NiagaraPosition", StructFieldKind.Float, 4, 3, 4, "X", "Y", "Z");
        Scalars("/Script/Niagara.NiagaraID", StructFieldKind.Int32, 4, 2, 4, "Index", "AcquireTag");
        const string spawnInfo = "/Script/Niagara.NiagaraSpawnInfo";
        result.Add(spawnInfo, new(spawnInfo, "NiagaraSpawnInfo", 16, 4, [
            new("Count", StructFieldKind.Int32, 0, 4, 1, 4),
            new("InterpStartDt", StructFieldKind.Float, 4, 4, 1, 4),
            new("IntervalDt", StructFieldKind.Float, 8, 4, 1, 4),
            new("SpawnGroup", StructFieldKind.Int32, 12, 4, 1, 4)
        ]));
        Scalars("/Script/Niagara.NiagaraSharedDataID_StaticMesh", StructFieldKind.Int32, 4, 2, 4, "Index", "AcquireTag");
        Scalars("/Script/Niagara.NiagaraEmitterID", StructFieldKind.Int32, 4, 1, 4, "ID");
        Scalars("/Script/Niagara.NiagaraRandInfo", StructFieldKind.Int32, 4, 3, 4, "Seed1", "Seed2", "Seed3");
        const string meshTriCoordinate = "/Script/Niagara.MeshTriCoordinate";
        result.Add(meshTriCoordinate, new(meshTriCoordinate, "MeshTriCoordinate", 16, 4, [
            new("Tri", StructFieldKind.Int32, 0, 4, 1, 4),
            new("BaryCoord", StructFieldKind.Struct, 4, 12, 1, 4, result["/Script/CoreUObject.Vector3f"])
        ]));
        Scalars("/Script/Niagara.NiagaraMatrix", StructFieldKind.Float, 4, 16, 16);
        Scalars("/Script/Niagara.NiagaraHalf", StructFieldKind.UInt16, 2, 1, 2, "Value");
        Scalars("/Script/Niagara.NiagaraHalfVector2", StructFieldKind.UInt16, 2, 2, 2, "x", "y");
        Scalars("/Script/Niagara.NiagaraHalfVector3", StructFieldKind.UInt16, 2, 3, 2, "x", "y", "z");
        Scalars("/Script/Niagara.NiagaraHalfVector4", StructFieldKind.UInt16, 2, 4, 2, "x", "y", "z", "w");
        foreach (var entry in new[] { ("Vector2D", 2, 8), ("Vector", 3, 8), ("Vector3d", 3, 8), ("Vector4", 4, 16), ("Vector4d", 4, 16), ("Quat", 4, 16), ("Quat4d", 4, 16) })
            Scalars("/Script/CoreUObject." + entry.Item1, StructFieldKind.Double, 8, entry.Item2, entry.Item3, "X", "Y", "Z", "W");
        return result;
    }
}
