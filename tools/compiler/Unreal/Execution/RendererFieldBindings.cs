using LumenFx.Compiler.Unreal.Geometry;
using LumenFx.Compiler.Unreal.Parameters;
using LumenFx.Compiler.Unreal.Types;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

/** Renderer attribute pointers retain their native order independently of the selected draw layout. */
internal static class RendererFieldBindings
{
    internal sealed record Field(string Name, string Attribute, bool Previous = false);
    internal static string? DefaultSourceMode(string rendererClass)
        => rendererClass == "NiagaraVolumeRendererProperties" ? "Emitter" : null;

    internal sealed record Variable(string Name, string Type, int UnderlyingType = 2, int Flags = 0, int? Bytes = null, bool? Lwc = false)
    {
        internal string StoreTypeName => Type[(Math.Max(Type.LastIndexOf('.'), Math.Max(Type.LastIndexOf('/'), Type.LastIndexOf(':'))) + 1)..];
        internal bool SameStoreSlot(Variable other)
            => SourceNameOrder.Equal(Name, other.Name) && SourceNameOrder.Equal(StoreTypeName, other.StoreTypeName);

        internal static Variable? From(SourceAsset asset, JObject variable, string? emitterNamespace = null)
        {
            string name = variable.Value<string>("name") ?? "None";
            if (SourceNameOrder.Equal(name, "None") || variable["type"] is not JObject type) return null;
            var loaded = NiagaraTypeSource.Read(asset, type);
            if (loaded.SourceReference == 0) return null;
            if (emitterNamespace is not null && name.Length > 7 && name[7] == '.' && SourceNameOrder.Equal(name[..7], "Emitter"))
                name = emitterNamespace + name[7..];
            return new(name, loaded.Path, loaded.UnderlyingType, loaded.Flags,
                loaded.UnderlyingType == 1 ? 0 : loaded.UnderlyingType == 3 ? 4 : NiagaraTypeSource.Size(asset, type),
                NiagaraTypeSource.IsLwc(asset, type));
        }

        internal bool Matches(SourceAsset asset, JObject variable, bool sameBase = false)
            => SourceNameOrder.Equal(Name, variable.Value<string>("name")) && MatchesType(asset, variable, sameBase);

        internal bool MatchesType(SourceAsset asset, JObject variable, bool sameBase = false)
        {
            if (variable["type"] is not JObject type) return false;
            var loaded = NiagaraTypeSource.Read(asset, type);
            return UnderlyingType == loaded.UnderlyingType && (sameBase || Flags == loaded.Flags)
                && SourceNameOrder.Equal(Type, loaded.Path);
        }
    }

    internal static IReadOnlyList<Field> Read(string rendererClass)
    {
        List<Field> result = new();
        void Fields(params (string Field, string Attribute)[] fields) {
            foreach (var field in fields) result.Add(new(field.Field, field.Attribute));
        }
        void Previous(params (string Field, string Attribute)[] fields) {
            foreach (var field in fields) result.Add(new("Prev" + field.Field, field.Attribute, true));
        }
        void Dynamics() => Fields(("DynamicMaterialBinding", "DynamicMaterialParameter"), ("DynamicMaterial1Binding", "DynamicMaterialParameter1"),
            ("DynamicMaterial2Binding", "DynamicMaterialParameter2"), ("DynamicMaterial3Binding", "DynamicMaterialParameter3"));
        switch (rendererClass) {
            case "NiagaraSpriteRendererProperties":
                Fields(("PositionBinding", "Position"), ("ColorBinding", "Color"), ("VelocityBinding", "Velocity"),
                    ("SpriteRotationBinding", "SpriteRotation"), ("SpriteSizeBinding", "SpriteSize"),
                    ("SpriteFacingBinding", "SpriteFacing"), ("SpriteAlignmentBinding", "SpriteAlignment"), ("SubImageIndexBinding", "SubImageIndex"));
                Dynamics();
                Fields(("CameraOffsetBinding", "CameraOffset"), ("UVScaleBinding", "UVScale"), ("PivotOffsetBinding", "PivotOffset"),
                    ("MaterialRandomBinding", "MaterialRandom"), ("CustomSortingBinding", "Age"), ("NormalizedAgeBinding", "NormalizedAge"));
                Previous(("PositionBinding", "Position"), ("VelocityBinding", "Velocity"), ("SpriteRotationBinding", "SpriteRotation"),
                    ("SpriteSizeBinding", "SpriteSize"), ("SpriteFacingBinding", "SpriteFacing"), ("SpriteAlignmentBinding", "SpriteAlignment"),
                    ("CameraOffsetBinding", "CameraOffset"), ("PivotOffsetBinding", "PivotOffset"));
                Fields(("RendererVisibilityTagBinding", "VisibilityTag"));
                break;
            case "NiagaraRibbonRendererProperties":
                Fields(("PositionBinding", "Position"), ("ColorBinding", "Color"), ("VelocityBinding", "Velocity"),
                    ("NormalizedAgeBinding", "NormalizedAge"), ("RibbonTwistBinding", "RibbonTwist"), ("RibbonWidthBinding", "RibbonWidth"),
                    ("RibbonFacingBinding", "RibbonFacing"), ("RibbonIdBinding", "RibbonID"), ("RibbonLinkOrderBinding", "RibbonLinkOrder"),
                    ("MaterialRandomBinding", "MaterialRandom"));
                Dynamics();
                Fields(("RibbonUVDistance", "RibbonUVDistance"), ("U0OverrideBinding", "RibbonU0Override"), ("V0RangeOverrideBinding", "RibbonV0RangeOverride"),
                    ("U1OverrideBinding", "RibbonU1Override"), ("V1RangeOverrideBinding", "RibbonV1RangeOverride"));
                Previous(("PositionBinding", "Position"), ("RibbonWidthBinding", "RibbonWidth"), ("RibbonFacingBinding", "RibbonFacing"), ("RibbonTwistBinding", "RibbonTwist"));
                break;
            case "NiagaraMeshRendererProperties":
                Fields(("PositionBinding", "Position"), ("VelocityBinding", "Velocity"), ("ColorBinding", "Color"), ("ScaleBinding", "Scale"),
                    ("MeshOrientationBinding", "MeshOrientation"), ("MaterialRandomBinding", "MaterialRandom"), ("NormalizedAgeBinding", "NormalizedAge"),
                    ("CustomSortingBinding", "Age"), ("SubImageIndexBinding", "SubImageIndex"));
                Dynamics(); Fields(("CameraOffsetBinding", "CameraOffset"));
                Previous(("PositionBinding", "Position"), ("ScaleBinding", "Scale"), ("MeshOrientationBinding", "MeshOrientation"),
                    ("CameraOffsetBinding", "CameraOffset"), ("VelocityBinding", "Velocity"));
                Fields(("RendererVisibilityTagBinding", "VisibilityTag"), ("MeshIndexBinding", "MeshIndex"));
                break;
            case NaniteRendererSource.ClassName:
                Fields(("PositionBinding", "Position"), ("RotationBinding", "MeshOrientation"), ("ScaleBinding", "Scale"));
                Previous(("PositionBinding", "Position"), ("RotationBinding", "MeshOrientation"), ("ScaleBinding", "Scale"));
                Fields(("RendererVisibilityTagBinding", "VisibilityTag"), ("MeshIndexBinding", "MeshIndex"));
                break;
            case "NiagaraLightRendererProperties":
                Fields(("LightRenderingEnabledBinding", "LightEnabled"), ("LightExponentBinding", "LightExponent"), ("PositionBinding", "Position"),
                    ("ColorBinding", "Color"), ("RadiusBinding", "LightRadius"), ("VolumetricScatteringBinding", "LightVolumetricScattering"),
                    ("RendererVisibilityTagBinding", "VisibilityTag"), ("SpecularScaleBinding", "LightSpecularScale"), ("DiffuseScaleBinding", "LightDiffuseScale"));
                break;
            case "NiagaraDecalRendererProperties":
                Fields(("PositionBinding", "Position"), ("DecalOrientationBinding", "DecalOrientation"), ("DecalSizeBinding", "DecalSize"),
                    ("DecalFadeBinding", "DecalFade"), ("DecalSortOrderBinding", "UniqueID"), ("DecalColorBinding", "Color"),
                    ("DecalVisibleBinding", "DecalVisible"), ("RendererVisibilityTagBinding", "VisibilityTag"));
                break;
            case "NiagaraVolumeRendererProperties":
                Fields(("PositionBinding", "Position"), ("RotationBinding", "VolumeRotation"), ("ScaleBinding", "VolumeScale"),
                    ("RendererVisibilityTagBinding", "VisibilityTag"), ("VolumeResolutionMaxAxisBinding", "VolumeResolutionMaxAxis"),
                    ("VolumeWorldSpaceSizeBinding", "VolumeWorldSpaceSize"));
                break;
            case "NiagaraSplatRendererProperties":
                Fields(("PositionBinding", "Position"), ("ScaleBinding", "Scale"), ("RotationBinding", "MeshOrientation"),
                    ("ColorBinding", "Color"), ("RendererVisibilityTagBinding", "VisibilityTag"));
                break;
            case "NiagaraComponentRendererProperties":
                Fields(("EnabledBinding", "ComponentsEnabled"), ("RendererVisibilityTagBinding", "VisibilityTag"));
                break;
        }
        return result;
    }

    internal static Variable? Resolve(SourceAsset asset, JObject source, string emitterNamespace, Field field, string? sourceMode = null)
    {
        RendererAttributeBindings names = new(source, emitterNamespace, asset, sourceMode);
        if (field.Name == "CustomSortingBinding" && NiagaraImporter.NiagaraVersion(asset) < 87 && source[field.Name] is null) {
            string old = new RendererAttributeBindings(new JObject { ["SourceMode"] = source["SourceMode"]?.DeepClone() }, emitterNamespace, asset, sourceMode)
                .Name(field.Name, "Particles.NormalizedAge");
            return new(old, "/Script/Niagara.NiagaraFloat");
        }
        string current = field.Previous ? field.Name[4..] : field.Name;
        string name = field.Previous ? names.PreviousName(current, "Particles." + field.Attribute)
            : names.Name(field.Name, "Particles." + field.Attribute);
        if (SourceNameOrder.Equal(name, "None")) return null;
        JObject? binding = source[current] as JObject;
        JObject? type = binding?["ParamMapVariable"]?["type"] as JObject;
        var loaded = type is null ? new NiagaraTypeSource.Definition(NiagaraAttributeTypes.Type(field.Attribute), 2, 0)
            : NiagaraTypeSource.Read(asset, type);
        if (loaded.SourceReference == 0 && binding?["ParamMapVariable"] is not null) return null;
        string path = loaded.Path;
        int underlying = loaded.UnderlyingType, flags = loaded.Flags;
        if (current == "PositionBinding" && path == "/Script/CoreUObject.Vector3f") {
            name = names.MigratedPositionName(binding?["ParamMapVariable"]?.Value<string>("name") ?? "None", field.Previous);
            path = "/Script/Niagara.NiagaraPosition"; underlying = 2; flags = 0;
        }
        int? bytes = underlying == 1 ? 0 : underlying == 3 ? 4
            : type is null ? (BuiltinSize(path)) : NiagaraTypeSource.Size(asset, type);
        return SourceNameOrder.Equal(name, "None") ? null : new(name, path, underlying, flags, bytes);
    }

    private static int? BuiltinSize(string path)
        => BuiltinStructLayouts.TryGet(path, out var layout) ? layout!.Size : null;
}
