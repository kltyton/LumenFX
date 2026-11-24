using LumenFx.Compiler.Unreal.Parameters;

namespace LumenFx.Compiler.Unreal.Types;

/** Identifies class definitions from decoded export payloads and the reference's actual metaclass. */
internal static class SourceObjectKinds
{
    internal static bool IsClass(SourceAsset asset, int reference)
        => IsClass(asset, reference, new HashSet<string>(SourceNameOrder.Instance));

    private static bool IsClass(SourceAsset asset, int reference, HashSet<string> visiting)
    {
        if (reference == 0) return false;
        string kind = asset.ReferencedClass(reference);
        if (SourceNameOrder.Equal(kind, "Class")) return true;
        if (SourceNameOrder.Equal(kind, "ScriptStruct") || SourceNameOrder.Equal(kind, "UserDefinedStruct")
                || SourceNameOrder.Equal(kind, "Function")) return false;
        string path = asset.ObjectPath(reference);
        if (reference > 0) {
            var raw = asset.Object(reference).Raw;
            string? exportType = raw.Value<string>("$type")?.Split(',')[0];
            if (exportType == "UAssetAPI.ExportTypes.ClassExport") return true;
            if (raw["ClassFlags"] is not null && raw["ClassWithin"] is not null && raw["ClassDefaultObject"] is not null) return true;
            if (exportType is "UAssetAPI.ExportTypes.StructExport" or "UAssetAPI.ExportTypes.UserDefinedStructExport"
                    or "UAssetAPI.ExportTypes.FunctionExport") return false;
            throw new ConversionException($"Legacy struct reference requires its decoded class/struct export: {path} ({kind})");
        }
        if (path.StartsWith("/Script/", StringComparison.Ordinal) || asset.Library is null)
            throw new ConversionException($"Legacy struct reference requires its metaclass hierarchy: {path} ({kind})");
        if (!visiting.Add(path)) throw new ConversionException($"Cyclic object definition reference: {path}");
        try {
            SourceAsset external = asset.Library.Load(path);
            SourceObject value = external.ResolveObject(path, _ => true);
            return IsClass(external, value.Index, visiting);
        } finally { visiting.Remove(path); }
    }
}
