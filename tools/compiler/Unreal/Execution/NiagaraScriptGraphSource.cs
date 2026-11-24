using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal sealed class NiagaraScriptGraphSource
{
    private const string ParameterMap = "/Script/Niagara.NiagaraParameterMap";
    private readonly IReadOnlyDictionary<int, SourceObject> nodes;
    private readonly Dictionary<int, IReadOnlyList<GraphPin>> pins = new();
    internal SourceAsset Asset { get; }
    internal IEnumerable<SourceObject> Nodes => nodes.Values;

    private NiagaraScriptGraphSource(SourceAsset asset, SourceObject graph)
    {
        Asset = asset;
        nodes = (graph.Properties["Nodes"] as JArray ?? new()).Values<int>()
            .Where(index => index > 0).Distinct().Select(asset.Object).ToDictionary(node => node.Index);
    }

    internal static NiagaraScriptGraphSource? Read(SourceAsset asset, SourceObject script, string? version = null)
    {
        JObject properties = script.Properties;
        JObject? data = (properties["VersionData"] as JArray)?.OfType<JObject>().FirstOrDefault();
        if (properties.Value<bool?>("bVersioningEnabled") == true) {
            Guid selected = Identity(version);
            if (selected == Guid.Empty) selected = Identity(properties.Value<string>("ExposedVersion"));
            data = (properties["VersionData"] as JArray)?.OfType<JObject>()
                .FirstOrDefault(candidate => Identity(candidate["Version"]?.Value<string>("VersionGuid")) == selected);
            if (data is null) return null;
        }
        int source = data?.Value<int?>("Source") ?? properties.Value<int?>("Source") ?? 0;
        if (source <= 0 || asset.Object(source).ClassName != "NiagaraScriptSource") return null;
        int graph = asset.Object(source).Properties.Value<int?>("NodeGraph") ?? 0;
        return graph > 0 && asset.Object(graph).ClassName == "NiagaraGraph"
            ? new NiagaraScriptGraphSource(asset, asset.Object(graph)) : null;
    }

    internal IReadOnlyList<GraphPin> Pins(SourceObject node)
    {
        if (!pins.TryGetValue(node.Index, out var result)) {
            result = GraphPins.Read(Asset, node).Where(pin => !pin.IsOrphaned).ToArray();
            pins.Add(node.Index, result);
        }
        return result;
    }

    internal SourceObject? PreviousInParameterMap(SourceObject node)
    {
        GraphPin? input = Pins(node).FirstOrDefault(pin => pin.IsInput && HasType(pin, ParameterMap));
        if (input is null || input.Links.Count != 1) return null;
        PinLink link = input.Links[0];
        return nodes.TryGetValue(link.Node, out SourceObject? previous)
            && Pins(previous).Any(pin => !pin.IsInput && pin.Id == link.Pin && HasType(pin, ParameterMap))
            ? previous : null;
    }

    internal bool HasType(GraphPin pin, string type) => pin.Container == GraphPinContainer.None
        && SourceNameOrder.Equal(Asset.ObjectPath(pin.TypeObject), type);

    internal List<SourceObject>? Modules(string usage)
    {
        SourceObject? node = Nodes.FirstOrDefault(node => node.ClassName == "NiagaraNodeOutput"
            && EnumMember(node.Properties.Value<string>("ScriptType")) == usage
            && Identity(node.Properties.Value<string>("ScriptTypeId")) == Guid.Empty);
        if (node is null) return null;
        HashSet<int> visited = new() { node.Index };
        List<SourceObject> modules = new();
        while (PreviousInParameterMap(node) is { } previous) {
            if (!visited.Add(previous.Index))
                throw new ConversionException($"Cyclic Niagara parameter map for {usage} in {Asset.Name}");
            if (previous.ClassName == "NiagaraNodeParameterMapSet") {
                string? alias = node.ClassName == "NiagaraNodeFunctionCall"
                    ? node.Properties.Value<string>("FunctionDisplayName") : null;
                if (alias is null || Pins(previous).Any(pin => pin.IsInput && pin.TypeObject != 0
                    && !HasType(pin, ParameterMap) && !pin.Name.StartsWith(alias + ".", StringComparison.OrdinalIgnoreCase)))
                    return null;
            }
            node = previous;
            switch (node.ClassName) {
                case "NiagaraNodeInput":
                    modules.Reverse();
                    return modules;
                case "NiagaraNodeParameterMapSet":
                    continue;
                case "NiagaraNodeFunctionCall":
                case "NiagaraNodeAssignment":
                    JObject properties = node.Properties;
                    string enabled = EnumMember(properties.Value<string>("EnabledState")) ?? "Enabled";
                    switch (enabled) {
                        case "Enabled":
                        case "0":
                            bool? legacyEnabled = properties.Value<bool?>("bIsNodeEnabled")
                                ?? properties.Value<bool?>("bIsNodeEnabled_DEPRECATED");
                            if (legacyEnabled != false || properties.Value<bool?>("bUserSetEnabledState") == true)
                                modules.Add(node);
                            break;
                        case "Disabled":
                        case "1":
                            break;
                        case "DevelopmentOnly":
                        case "2":
                            // Editor imports follow the compiled editor graph's development mode.
                            modules.Add(node);
                            break;
                        default:
                            return null;
                    }
                    continue;
                default:
                    return null;
            }
        }
        // A disconnected stack does not provide evidence that the compiled script is empty.
        return null;
    }

    internal static string? EnumMember(string? name) => name?[(name.LastIndexOf(':') + 1)..];

    private static Guid Identity(string? value) => string.IsNullOrEmpty(value) ? Guid.Empty : Guid.Parse(value);
}
