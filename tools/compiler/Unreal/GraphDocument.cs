using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

/// <summary>Exports decoded source nodes and native pin links for module adapters.</summary>
public static class GraphDocument
{
    public static JObject Create(SourceAsset asset)
    {
        JArray nodes = new();
        foreach (var node in asset.Objects.Where(value => value.ClassName.StartsWith("NiagaraNode", StringComparison.Ordinal)))
        {
            var pins = GraphPins.Read(asset, node);
            nodes.Add(new JObject
            {
                ["index"] = node.Index, ["class"] = node.ClassName, ["name"] = node.Name, ["outer"] = node.Outer,
                ["properties"] = node.Properties,
                ["pins"] = JArray.FromObject(pins)
            });
        }
        return new JObject
        {
            ["source"] = asset.Name,
            ["scripts"] = new JArray(asset.Objects.Where(value => value.ClassName == "NiagaraScript")
                .Select(value => new JObject { ["index"] = value.Index, ["properties"] = value.Properties })),
            ["nodes"] = nodes
        };
    }
}
