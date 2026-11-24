using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

/// <summary>Rebuilds attribute names from the root and source mode, without trusting serialized cache flags.</summary>
internal sealed class RendererAttributeBindings(JObject renderer, string sourceEmitter, SourceAsset? asset = null, string? sourceMode = null)
{
    private sealed class ValidatedStatelessMode;
    private readonly bool emitter = SourceMode(renderer, sourceMode) == "Emitter";

    internal static JObject LoadedProperties(SourceAsset asset, SourceObject renderer)
    {
        JObject properties = renderer.Properties;
        if (renderer.ClassName is not ("NiagaraSpriteRendererProperties" or "NiagaraMeshRendererProperties"
                or "NiagaraLightRendererProperties" or "NiagaraDecalRendererProperties") || SourceMode(properties) != "Emitter")
            return properties;

        bool cooked = asset.Raw["PackageFlags"] switch {
            JValue { Type: JTokenType.Integer } flags => (flags.Value<uint>() & 0x80000200u) != 0,
            JValue flags => (flags.Value<string>() ?? "").Split(',', StringSplitOptions.TrimEntries)
                .Any(flag => flag is "PKG_Cooked" or "PKG_FilterEditorOnly"),
            _ => false
        };
        if (cooked) return properties;

        // ValidateSourceMode runs on editor load and requires a non-Stateless typed outer for Emitter mode.
        HashSet<int> visited = new();
        for (int outer = renderer.Outer; outer > 0;) {
            if (!visited.Add(outer))
                throw new ConversionException($"Cyclic renderer ownership for {renderer.Name} in {asset.Name}");
            SourceObject owner = asset.Object(outer);
            if (owner.ClassName == "NiagaraStatelessEmitter") {
                properties["SourceMode"] = "Particles";
                properties.AddAnnotation(new ValidatedStatelessMode());
                break;
            }
            outer = owner.Outer;
        }
        return properties;
    }

    internal static string? SavedDatasetBinding(SourceAsset asset, JObject renderer, string field)
    {
        if (renderer.Annotation<ValidatedStatelessMode>() is null || renderer[field] is not JObject binding)
            return null;
        if (binding["BoundVariable"] is JObject legacy && legacy["type"] is JObject type
                && !SourceNameOrder.Equal(legacy.Value<string>("name"), "None")
                && NiagaraTypeSource.Read(asset, type).SourceReference != 0)
            return null;
        // Stateless builds the data set before CacheFromCompiledData recaches the current renderer bindings.
        return binding["ParamMapVariable"]?.Value<string>("name");
    }

    internal static string SourceMode(JObject renderer, string? fallback = null)
        => (renderer.Value<string>("SourceMode") ?? fallback ?? "Particles").Split("::").Last() switch {
            "0" => "Particles", "1" => "Emitter", var mode => mode
        };

    internal string Name(string field, string fallback)
    {
        var (root, mode) = Root(field, fallback);
        return ResolvedName(root, mode, field);
    }

    private string ResolvedName(string root, string mode, string field)
    {
        if (root.Length == 0 || SourceNameOrder.Instance.Equals(root, "None")) return "None";
        if (mode == "ImplicitFromSource" || mode is "ExplicitEmitter" or "ExplicitParticles")
        {
            bool particle = mode == "ExplicitParticles" || mode == "ImplicitFromSource" && !emitter;
            root = Strip(root, sourceEmitter);
            return particle ? "Particles." + root : sourceEmitter + "." + root;
        }
        return mode switch {
            "ExplicitSystem" or "ExplicitUser" => root,
            _ => throw new ConversionException($"Unknown Niagara attribute binding source {mode}: {field}")
        };
    }

    internal bool Particle(string field)
    {
        string mode = Root(field, "None").Mode;
        return mode == "ExplicitParticles" || mode == "ImplicitFromSource" && !emitter;
    }

    internal string Source(string field) => Root(field, "None").Mode;

    internal string PreviousName(string field, string fallback)
    {
        var previousRoot = SetValue(PreviousRoot(Root(field, fallback).Name), "ImplicitFromSource", emitter);
        return ResolvedName(previousRoot.Name, previousRoot.Mode, field);
    }

    internal string MigratedPositionName(string cachedName, bool previous)
    {
        var binding = SetValue(cachedName, "ImplicitFromSource", false);
        if (previous) binding = SetValue(PreviousRoot(binding.Name), "ImplicitFromSource", emitter);
        return ResolvedName(binding.Name, binding.Mode, "PositionBinding");
    }

    private static string PreviousRoot(string root)
    {
        List<string> parts = root.Split('.', StringSplitOptions.RemoveEmptyEntries).ToList();
        if (!parts.Any(value => SourceNameOrder.Instance.Equals(value, "Previous"))) parts.Insert(parts.Count > 1 ? 1 : 0, "Previous");
        return string.Join('.', parts);
    }

    private (string Name, string Mode) Root(string field, string fallback)
    {
        JObject? binding = renderer[field] as JObject;
        string mode = Mode(binding);
        string root = binding?.Value<string>("RootName") ?? StripFallback(fallback);
        string? oldRoot = (binding?["RootVariable_DEPRECATED"] ?? binding?["RootVariable"])?.Value<string>("name");
        if (oldRoot is not null && !SourceNameOrder.Instance.Equals(oldRoot, "None")) root = oldRoot;
        JToken? bound = binding?["BoundVariable"];
        string? boundName = bound?.Value<string>("name");
        if (boundName is not null && !SourceNameOrder.Instance.Equals(boundName, "None")
            && bound?["type"] is JObject type && (asset is null
                ? (type.Value<int?>("ClassStructOrEnum") ?? 0) != 0 : NiagaraTypeSource.Read(asset, type).SourceReference != 0))
            return SetValue(boundName, mode, emitter);
        return (root, mode);
    }

    // PostLoad and SetAsPreviousValue call SetValue with an empty emitter context.
    private static (string Name, string Mode) SetValue(string root, string mode, bool sourceEmitter)
    {
        string first = root.Split('.')[0];
        bool In(string space) => root.Length > first.Length && SourceNameOrder.Instance.Equals(first, space);
        if (In("Particles") || In("Emitter") || In("StackContext")) {
            bool particle = In("Particles") || In("StackContext") && !sourceEmitter;
            mode = particle == !sourceEmitter ? "ImplicitFromSource" : particle ? "ExplicitParticles" : "ExplicitEmitter";
            root = root[(first.Length + 1)..];
        } else if (In("System")) mode = "ExplicitSystem";
        else if (In("User")) mode = "ExplicitUser";
        return (root, mode);
    }

    private static string Mode(JObject? binding)
        => (binding?.Value<string>("BindingSourceMode") ?? "ImplicitFromSource").Split("::").Last() switch {
            "0" => "ImplicitFromSource", "1" => "ExplicitParticles", "2" => "ExplicitEmitter",
            "3" => "ExplicitSystem", "4" => "ExplicitUser", var value => value
        };

    private static string Strip(string value, string space)
        => value.Length > space.Length && value[space.Length] == '.' && SourceNameOrder.Instance.Equals(value[..space.Length], space)
            ? value[(space.Length + 1)..] : value;

    private string StripFallback(string value)
    {
        foreach (string space in new[] { sourceEmitter, "Particles", "Emitter" })
            if (value.StartsWith(space + ".", StringComparison.Ordinal)) return value[(space.Length + 1)..];
        return value;
    }
}
