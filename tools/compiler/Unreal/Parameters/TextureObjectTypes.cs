namespace LumenFx.Compiler.Unreal.Parameters;

/** Known non-texture UObject defaults produce the same null cast as runtime texture bindings. */
internal static class TextureObjectTypes
{
    internal static bool IsKnownNonTexture(string type) => type is "Object" or "StaticMesh" or "Material"
        or "MaterialInstanceConstant" or "MaterialInstanceDynamic" or "NiagaraSystem" or "Blueprint" or "Actor"
        or "RuntimeVirtualTexture" or "SparseVolumeTexture" or "TextureCollection";
}
