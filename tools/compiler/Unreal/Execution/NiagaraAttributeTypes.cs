namespace LumenFx.Compiler.Unreal.Execution;

/** Built-in attribute types shared by renderer bindings and analytic module outputs. */
internal static class NiagaraAttributeTypes
{
    internal static string Type(string name)
    {
        string root = name.StartsWith("Previous.", StringComparison.Ordinal) ? name[9..] : name;
        return root switch {
            "UniqueID" or "MeshIndex" or "VisibilityTag" or "VolumeResolutionMaxAxis" => "/Script/Niagara.NiagaraInt32",
            "RendererEnabled" or "LightEnabled" or "DecalVisible" or "ComponentsEnabled" => "/Script/Niagara.NiagaraBool",
            "Position" => "/Script/Niagara.NiagaraPosition",
            "Color" => "/Script/CoreUObject.LinearColor",
            "SpriteSize" or "UVScale" or "PivotOffset" or "RibbonV0RangeOverride" or "RibbonV1RangeOverride" => "/Script/CoreUObject.Vector2f",
            "Scale" or "Velocity" or "SpriteFacing" or "SpriteAlignment" or "DecalSize" or "RibbonFacing"
                or "VolumeWorldSpaceSize" or "VolumeScale" => "/Script/CoreUObject.Vector3f",
            "MeshOrientation" or "DecalOrientation" or "VolumeRotation" => "/Script/CoreUObject.Quat4f",
            "DynamicMaterialParameter" or "DynamicMaterialParameter1" or "DynamicMaterialParameter2" or "DynamicMaterialParameter3" => "/Script/CoreUObject.Vector4f",
            "RibbonID" => "/Script/Niagara.NiagaraID",
            _ => "/Script/Niagara.NiagaraFloat"
        };
    }
}
