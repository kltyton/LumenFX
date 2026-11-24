using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Parameters;

/** Package-local type indices are compared only after resolving their owning source assets. */
internal static class NiagaraVariableSource
{
    internal static bool Same(SourceAsset leftAsset, JObject left, SourceAsset rightAsset, JObject right)
    {
        if (!SourceNameOrder.Equal(left.Value<string>("name"), right.Value<string>("name"))
            || left["type"] is not JObject a || right["type"] is not JObject b) return false;
        var leftType = NiagaraTypeSource.Read(leftAsset, a);
        var rightType = NiagaraTypeSource.Read(rightAsset, b);
        return leftType.UnderlyingType == rightType.UnderlyingType && leftType.Flags == rightType.Flags
            && SourceNameOrder.Equal(leftType.Path, rightType.Path);
    }
}
