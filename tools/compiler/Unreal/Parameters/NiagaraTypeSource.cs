using Newtonsoft.Json.Linq;
using System.Runtime.CompilerServices;
using LumenFx.Compiler.Unreal.Types;
using LumenFx.Compiler.Unreal.Parameters.Structure;

namespace LumenFx.Compiler.Unreal.Parameters;

/** Restores the built-in struct identities changed by FNiagaraTypeDefinition's archive load. */
internal static class NiagaraTypeSource
{
    private const int MemorySavingVersion = 53;
    internal sealed record Definition(string Path, int UnderlyingType, int Flags, int? Size = null, int SourceReference = 0);
    private static readonly ConditionalWeakTable<StructLayout, NiagaraSwcStructLayout> Projections = new();
    private static readonly HashSet<string> ValueStructs = new(SourceNameOrder.Instance) {
        "/Script/Niagara.NiagaraFloat", "/Script/Niagara.NiagaraInt32", "/Script/Niagara.NiagaraBool",
        "/Script/Niagara.NiagaraPosition", "/Script/Niagara.NiagaraID", "/Script/Niagara.NiagaraMatrix",
        "/Script/Niagara.NiagaraSharedDataID_StaticMesh",
        "/Script/Niagara.NiagaraHalf", "/Script/Niagara.NiagaraHalfVector2",
        "/Script/Niagara.NiagaraHalfVector3", "/Script/Niagara.NiagaraHalfVector4",
        "/Script/CoreUObject.Vector2f", "/Script/CoreUObject.Vector3f", "/Script/CoreUObject.Vector4f",
        "/Script/CoreUObject.LinearColor", "/Script/CoreUObject.Quat4f"
    };

    internal static Definition Read(SourceAsset asset, JObject type)
    {
        int reference = type.Value<int?>("ClassStructOrEnum") ?? 0;
        int underlying = type.Value<int?>("UnderlyingType") ?? 0;
        int flags = type.Value<int?>("Flags") ?? 0;
        if (NiagaraImporter.NiagaraVersion(asset) < MemorySavingVersion) {
            int oldEnum = (type["Enum_DEPRECATED"] ?? type["Enum"])?.Value<int?>() ?? 0;
            int oldStruct = (type["Struct_DEPRECATED"] ?? type["Struct"])?.Value<int?>() ?? 0;
            if (oldEnum != 0) { reference = oldEnum; underlying = 3; }
            else if (oldStruct != 0) { reference = oldStruct; underlying = SourceObjectKinds.IsClass(asset, oldStruct) ? 1 : 2; }
            else { reference = 0; underlying = 0; }
        }
        string path = asset.ObjectPath(reference);
        Definition Result(string loadedPath, int loadedUnderlying, int loadedFlags, int? size = null)
            => new(loadedPath, loadedUnderlying, loadedFlags, size, reference);
        if (reference == 0 || underlying != 2) return Result(path, underlying, flags);
        if ((flags & 2) != 0) {
            string? swc = KnownSwc(path);
            if (swc is not null) return Result(swc, underlying, flags & ~2);
            bool? lwc = StructLayoutSource.IsLwc(asset, reference);
            if (lwc == false) return Result(path, underlying, flags & ~2);
            if (lwc == true) {
                StructLayout source = StructLayoutSource.Find(asset, reference)
                    ?? throw new ConversionException($"Missing source fields for SWC structure {path}");
                var projected = Projection(source);
                return Result(projected.Simulation.Path, underlying, flags & ~2, projected.Simulation.Size);
            }
            // Native script structs need their authoritative reflection layout before their SWC identity can be restored.
            return Result(path, underlying, flags);
        }
        if ((flags & 4) == 0) path = Friendly(path);
        if (ValueStructs.TryGetValue(path, out string? canonical)) path = canonical;
        return Result(path, underlying, flags);
    }

    internal static int? Size(SourceAsset asset, JObject type)
    {
        Definition definition = Read(asset, type);
        if (definition.Size is int size) return size;
        return BuiltinStructLayouts.TryGet(definition.Path, out var structure) ? structure!.Size : null;
    }

    internal static Definition ReadCommonReference(SourceAsset asset, int reference)
    {
        // FNiagaraVariableCommonReference serializes the underlying UObject directly.
        string kind = asset.ReferencedClass(reference);
        bool isEnum = SourceNameOrder.Equal(kind, "Enum") || SourceNameOrder.Equal(kind, "UserDefinedEnum");
        if (!isEnum && !SourceNameOrder.Equal(kind, "ScriptStruct") && !SourceNameOrder.Equal(kind, "UserDefinedStruct"))
            throw new ConversionException($"Niagara variable reference {asset.ObjectPath(reference)} has no numeric type: {kind}");
        return Read(asset, new JObject {
            ["ClassStructOrEnum"] = reference, ["UnderlyingType"] = isEnum ? 3 : 2,
            [isEnum ? "Enum" : "Struct"] = reference
        });
    }

    internal static bool? IsLwc(SourceAsset asset, JObject type)
    {
        Definition definition = Read(asset, type);
        if (definition.UnderlyingType != 2) return false;
        if (BuiltinStructLayouts.TryGet(definition.Path, out var structure)) return StructLayoutSource.HasLwc(structure!.Fields);
        if (definition.Size.HasValue) return false;
        return StructLayoutSource.IsLwc(asset, definition.SourceReference);
    }

    internal static NiagaraSwcStructLayout Projection(StructLayout source)
        => Projections.GetValue(source, NiagaraSwcStructLayout.Build);

    internal static string? KnownSwc(string path)
    {
        if (SourceNameOrder.Equal(path, "/Script/Niagara.NiagaraDouble")) return "/Script/Niagara.NiagaraFloat";
        if (SourceNameOrder.Equal(path, "/Script/CoreUObject.Vector2D")) return "/Script/CoreUObject.Vector2f";
        if (SourceNameOrder.Equal(path, "/Script/CoreUObject.Vector")) return "/Script/CoreUObject.Vector3f";
        if (SourceNameOrder.Equal(path, "/Script/CoreUObject.Vector4")) return "/Script/CoreUObject.Vector4f";
        if (SourceNameOrder.Equal(path, "/Script/CoreUObject.Quat")) return "/Script/CoreUObject.Quat4f";
        return ValueStructs.TryGetValue(path, out string? canonical) ? canonical : null;
    }

    private static string Friendly(string path)
    {
        int separator = Math.Max(path.LastIndexOf('.'), Math.Max(path.LastIndexOf('/'), path.LastIndexOf(':')));
        string name = path[(separator + 1)..];
        if (SourceNameOrder.Equal(name, "NiagaraDouble")) return "/Script/Niagara.NiagaraFloat";
        if (SourceNameOrder.Equal(name, "Vector2D") || SourceNameOrder.Equal(name, "Vector2d")) return "/Script/CoreUObject.Vector2f";
        if (SourceNameOrder.Equal(name, "Vector") || SourceNameOrder.Equal(name, "Vector3d")) return "/Script/CoreUObject.Vector3f";
        if (SourceNameOrder.Equal(name, "Vector4") || SourceNameOrder.Equal(name, "Vector4d")) return "/Script/CoreUObject.Vector4f";
        if (SourceNameOrder.Equal(name, "Quat") || SourceNameOrder.Equal(name, "Quat4d")) return "/Script/CoreUObject.Quat4f";
        if (SourceNameOrder.Equal(name, "NiagaraPosition")) return "/Script/Niagara.NiagaraPosition";
        return path;
    }
}
