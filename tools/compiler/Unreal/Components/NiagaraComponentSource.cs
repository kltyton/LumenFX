using LumenFx.Compiler.Unreal.Parameters;
using LumenFx.Compiler.Unreal.Components.Sequence;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Components;

/** Resolves serialized component defaults through the actual UObject archetype chain. */
internal sealed class NiagaraComponentSource
{
    private readonly record struct Export(SourceAsset Asset, SourceObject Object, JObject Properties);
    private readonly List<Export> hierarchy = new();
    private readonly JObject? lifeCycleTrack;
    private readonly JObject? cacheTrack;

    internal NiagaraComponentSource(AssetLibrary library, string path, string? lifeCycleTrackPath = null, string? cacheTrackPath = null)
    {
        SourceAsset asset = library.Load(path);
        SourceObject component = asset.ResolveObject(path, IsComponent);
        var visited = new HashSet<string>(SourceNameOrder.Instance);
        while (true) {
            string identity = asset.ObjectPath(component.Index);
            if (!visited.Add(identity)) throw new ConversionException($"Cyclic NiagaraComponent archetype: {identity}");
            hierarchy.Add(new Export(asset, component, component.Properties));
            int template = component.Raw.Value<int?>("TemplateIndex") ?? 0;
            if (template == 0) break;
            if (template > 0) {
                component = asset.Object(template);
            } else {
                string templatePath = asset.ObjectPath(template);
                if (SourceNameOrder.Equal(templatePath, "/Script/Niagara.Default__NiagaraComponent")) break;
                asset = library.Load(templatePath);
                component = asset.ResolveObject(templatePath, IsComponent);
            }
            if (!IsComponent(component))
                throw new ConversionException($"Invalid NiagaraComponent archetype: {asset.ObjectPath(component.Index)}");
        }
        var binding = Find("Asset");
        int reference = binding?.Token.Value<int>() ?? 0;
        if (reference == 0) throw new ConversionException($"NiagaraComponent has no NiagaraSystem asset: {Identity}");
        SystemPath = binding!.Value.Owner.Asset.ObjectPath(reference);
        if (lifeCycleTrackPath is not null) lifeCycleTrack = NiagaraLifeCycleTrackSource.Compile(library, lifeCycleTrackPath);
        if (cacheTrackPath is not null) cacheTrack = NiagaraCacheTrackSource.Compile(library, cacheTrackPath);
    }

    private static bool IsComponent(SourceObject value) => value.ClassName == "NiagaraComponent";

    internal string Identity => hierarchy[0].Asset.ObjectPath(hierarchy[0].Object.Index);
    internal string SystemPath { get; }
    internal JToken? Property(string name) => Find(name)?.Token;

    private (Export Owner, JToken Token)? Find(string name, int start = 0)
    {
        for (int index = start; index < hierarchy.Count; index++) {
            var source = hierarchy[index];
            if (source.Properties[name] is { } value) return (source, value);
        }
        return null;
    }

    internal JObject Settings() => new() {
        ["source"] = Identity,
        ["random_seed_offset"] = Find("RandomSeedOffset")?.Token.Value<int>() ?? 0,
        ["rendering_enabled"] = Find("bRenderingEnabled")?.Token.Value<bool>() ?? true,
        ["render_in_main_pass"] = Property("bRenderInMainPass") is { } mainPass ? SceneFlag(mainPass) : true,
        ["draw_in_game"] = (Property("bVisible") is { } visible ? SceneFlag(visible) : true)
            && !SceneFlag(Property("bHiddenInGame")),
        ["cast_hidden_shadow"] = SceneFlag(Property("bCastHiddenShadow")),
        ["only_owner_see"] = SceneFlag(Property("bOnlyOwnerSee")),
        ["owner_no_see"] = SceneFlag(Property("bOwnerNoSee")),
        ["translucency_sort_priority"] = Property("TranslucencySortPriority")?.Value<int>() ?? 0,
        ["translucency_sort_distance_offset"] = Property("TranslucencySortDistanceOffset")?.Value<float>() ?? 0.0f,
        ["auto_activate"] = Find("bAutoActivate")?.Token.Value<bool>() ?? true,
        ["auto_destroy"] = Find("bAutoDestroy")?.Token.Value<bool>() ?? false,
        ["life_cycle_track"] = lifeCycleTrack?.DeepClone() ?? JValue.CreateNull(),
        ["cache_track"] = cacheTrack?.DeepClone() ?? JValue.CreateNull(),
        ["allow_scalability"] = Find("bAllowScalability")?.Token.Value<bool>() ?? true,
        ["occlusion_query_mode"] = OcclusionMode(Property("OcclusionQueryMode")),
        ["tick_behavior"] = SourceEnum(Property("TickBehavior"), "UsePrereqs", "UseComponentTickGroup", "ForceTickFirst", "ForceTickLast"),
        ["component_tick_group"] = SourceEnum(Property("PrimaryComponentTick")?["TickGroup"], "TG_PrePhysics",
            "TG_StartPhysics", "TG_DuringPhysics", "TG_EndPhysics", "TG_PostPhysics", "TG_PostUpdateWork",
            "TG_LastDemotable", "TG_NewlySpawned", "TG_MAX"),
        ["bounds_scale"] = Find("BoundsScale")?.Token.Value<float>() ?? 1.0f,
        ["auto_attachment"] = AutoAttachmentSettings(),
        ["custom_primitive_data"] = Property("CustomPrimitiveData") is JObject primitive && primitive["Data"] is JArray values
            ? new JArray(values.Take(36).Select(value => SourceAsset.Number(value))) : new JArray(),
        ["warmup"] = Find("bOverrideWarmupSettings")?.Token.Value<bool>() == true ? new JObject {
            ["ticks"] = Find("WarmupTickCount")?.Token.Value<int>() ?? 0,
            ["delta"] = SourceAsset.Number(Find("WarmupTickDelta")?.Token, 1.0f / 15.0f)
        } : JValue.CreateNull()
    };

    private JObject AutoAttachmentSettings()
    {
        var root = hierarchy[0];
        var location = ObjectPropertyFieldSource.ComponentProperty(root.Asset, root.Object, root.Asset.Library, "RelativeLocation")?.Value;
        var rotation = ObjectPropertyFieldSource.ComponentProperty(root.Asset, root.Object, root.Asset.Library, "RelativeRotation")?.Value;
        var scale = ObjectPropertyFieldSource.ComponentProperty(root.Asset, root.Object, root.Asset.Library, "RelativeScale3D")?.Value;
        var absoluteLocation = ObjectPropertyFieldSource.ComponentProperty(root.Asset, root.Object, root.Asset.Library, "bAbsoluteLocation")?.Value;
        var absoluteRotation = ObjectPropertyFieldSource.ComponentProperty(root.Asset, root.Object, root.Asset.Library, "bAbsoluteRotation")?.Value;
        var absoluteScale = ObjectPropertyFieldSource.ComponentProperty(root.Asset, root.Object, root.Asset.Library, "bAbsoluteScale")?.Value;
        string? attachedParentIdentity = ComponentIdentity(
                ObjectPropertyFieldSource.ComponentProperty(root.Asset, root.Object, root.Asset.Library, "AttachParent"));
        string? parentIdentity = ComponentIdentity(
                ObjectPropertyFieldSource.ComponentProperty(root.Asset, root.Object, root.Asset.Library, "AutoAttachParent"))
                ?? attachedParentIdentity;
        var socket = Property("AutoAttachSocketName")?.Value<string>();
        if ((string.IsNullOrEmpty(socket) || SourceNameOrder.Equal(socket, "None")) && attachedParentIdentity is not null)
            socket = ObjectPropertyFieldSource.ComponentProperty(root.Asset, root.Object, root.Asset.Library, "AttachSocketName")?.Value.Value<string>();
        return new JObject {
            ["enabled"] = Find("bAutoManageAttachment")?.Token.Value<bool>() ?? false,
            ["parent_identity"] = parentIdentity is null ? JValue.CreateNull() : new JValue(parentIdentity),
            ["socket_name"] = string.IsNullOrEmpty(socket) ? "None" : socket,
            ["location_rule"] = SourceEnum(Property("AutoAttachLocationRule"), "KeepRelative", "KeepWorld", "SnapToTarget"),
            ["rotation_rule"] = SourceEnum(Property("AutoAttachRotationRule"), "KeepRelative", "KeepWorld", "SnapToTarget"),
            ["scale_rule"] = SourceEnum(Property("AutoAttachScaleRule"), "KeepRelative", "KeepWorld", "SnapToTarget"),
            ["weld_simulated_bodies"] = Find("bAutoAttachWeldSimulatedBodies")?.Token.Value<bool>() ?? false,
            ["relative_transform"] = new JObject {
                ["location"] = SceneVector(location, 0, "X", "Y", "Z"),
                ["rotation"] = SceneVector(rotation, 0, "Pitch", "Yaw", "Roll"),
                ["scale"] = SceneVector(scale, 1, "X", "Y", "Z"),
                ["absolute_location"] = SceneFlag(absoluteLocation),
                ["absolute_rotation"] = SceneFlag(absoluteRotation),
                ["absolute_scale"] = SceneFlag(absoluteScale)
            }
        };
    }

    private static string? ComponentIdentity((SourceAsset Asset, JToken Value)? binding)
    {
        if (binding is not { } value || value.Value.Type == JTokenType.Null) return null;
        if (value.Value.Type == JTokenType.Integer) {
            int reference = value.Value.Value<int>();
            if (reference == 0) return null;
            string path = value.Asset.ObjectPath(reference);
            return SourceNameOrder.Equal(path, "None") ? null : path;
        }
        if (value.Value.Type == JTokenType.String) {
            string? path = value.Value.Value<string>();
            return string.IsNullOrWhiteSpace(path) || SourceNameOrder.Equal(path, "None") ? null : path;
        }
        throw new ConversionException($"Invalid Niagara auto-attachment component reference: {value.Value.Type}");
    }

    private static JArray SceneVector(JToken? value, double fallback, string x, string y, string z)
    {
        if (value is JObject vector)
            return new JArray(SourceAsset.Number(vector[x], fallback), SourceAsset.Number(vector[y], fallback),
                SourceAsset.Number(vector[z], fallback));
        if (value is JArray values && values.Count >= 3)
            return new JArray(SourceAsset.Number(values[0], fallback), SourceAsset.Number(values[1], fallback),
                SourceAsset.Number(values[2], fallback));
        if (value?.Type == JTokenType.Integer && value.Value<int>() == 0)
            return new JArray(0, 0, 0);
        return new JArray(fallback, fallback, fallback);
    }

    private static bool SceneFlag(JToken? value) => value?.Type switch {
        JTokenType.Boolean => value.Value<bool>(),
        JTokenType.Integer => value.Value<int>() != 0,
        _ => false
    };

    private static string OcclusionMode(JToken? value) => (value?.Value<string>() ?? "Default").Split("::").Last() switch {
        "Default" or "0" => "Default",
        "AlwaysEnabled" or "1" => "AlwaysEnabled",
        "AlwaysDisabled" or "2" => "AlwaysDisabled",
        string mode => throw new ConversionException($"Unknown Niagara occlusion query mode: {mode}")
    };

    private static string SourceEnum(JToken? value, params string[] names)
    {
        string? text = value?.Value<string>()?.Split("::").Last();
        if (text is null) return names[0];
        if (int.TryParse(text, out int index) && index >= 0 && index < names.Length) return names[index];
        if (names.Contains(text, StringComparer.Ordinal)) return text;
        throw new ConversionException($"Unknown Niagara component tick value: {text}");
    }

    internal JObject? SavedOverrides(SourceAsset systemAsset, SourceObject system, AssetLibrary library, JObject[] declarations)
    {
        var root = hierarchy[0];
        bool cooked = HasFlag(root.Asset.Raw, "PackageFlags", "PKG_Cooked")
            || HasFlag(root.Asset.Raw, "PackageFlags", "PKG_FilterEditorOnly");
        var saved = Find("OverrideParameters");
        if (cooked) return saved is { Token: JObject store } binding
            ? SavedParameterStoreSource.Compile(binding.Owner.Asset, store, library, system, systemAsset, Identity)
            : null;

        bool template = HasFlag(root.Object.Raw, "ObjectFlags", "RF_ArchetypeObject")
            || HasFlag(root.Object.Raw, "ObjectFlags", "RF_ClassDefaultObject");
        var templateValues = Find("TemplateParameterOverrides", template ? 0 : 1);
        var instanceValues = template ? null : Find("InstanceParameterOverrides");
        var editor = new NiagaraComponentParameterOverrides(systemAsset, system, library, Identity, declarations);
        if (templateValues is { Token: JArray templateMap } templateBinding)
            editor.AddVariants(templateBinding.Owner.Asset, templateMap);
        if (instanceValues is { Token: JArray instanceMap } instanceBinding)
            editor.AddVariants(instanceBinding.Owner.Asset, instanceMap);
        if (Find("EditorOverridesValue_DEPRECATED") is { Token: JArray legacy } && saved is { Token: JObject oldStore } oldBinding)
            editor.AddLegacy(oldBinding.Owner.Asset, oldStore, legacy);
        return editor.Compile();
    }

    private static bool HasFlag(JObject source, string field, string flag)
        => (source.Value<string>(field) ?? "").Split(',', StringSplitOptions.TrimEntries).Contains(flag, StringComparer.Ordinal);
}
