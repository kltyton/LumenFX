using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Scene;

/** Imports the saved construction commands separately from their runtime dispatch. */
internal static class PhysicsFieldComponentSource
{
    private static readonly string[] TargetNames = [
        "Node", "DynamicState", "LinearForce", "ExternalClusterStrain", "Kill", "LinearVelocity", "AngularVelocity",
        "AngularTorque", "InternalClusterStrain", "DisableThreshold", "SleepingThreshold", "PositionStatic",
        "PositionAnimated", "PositionTarget", "DynamicConstraint", "CollisionGroup", "ActivateDisabled",
        "InitialLinearVelocity", "InitialAngularVelocity", "LinearImpulse"
    ];

    internal static JObject Compile(AssetLibrary library, IEnumerable<string> paths)
    {
        var result = new JObject();
        foreach (string path in paths) {
            SourceAsset owner = library.Load(path);
            SourceObject component = owner.ResolveObject(path, value => value.ClassName == "FieldSystemComponent");
            JObject source = component.Properties;
            JObject saved = source["ConstructionCommands"] as JObject ?? new JObject();
            JArray targets = saved["TargetNames"] as JArray ?? new JArray();
            JArray nodes = saved["RootNodes"] as JArray ?? new JArray();
            JArray metadata = saved["MetaDatas"] as JArray ?? new JArray();
            if (targets.Count != nodes.Count || targets.Count != metadata.Count)
                throw new ConversionException($"Saved field command arrays have different lengths: {path}");
            var commands = new JArray();
            for (int index = 0; index < targets.Count; index++) {
                int reference = nodes[index].Value<int>();
                if (reference == 0) continue;
                string name = targets[index].Value<string>() ?? throw new ConversionException($"Missing Chaos field target: {path}");
                int target = Array.FindIndex(TargetNames, candidate => SourceNameOrder.Equal(candidate, name));
                if (target <= 0) throw new ConversionException($"Unknown Chaos field target {name}: {path}");
                int meta = metadata[index].Value<int>();
                commands.Add(new JObject {
                    ["target"] = target, ["node"] = Node(library, owner, reference, new HashSet<string>(SourceNameOrder.Instance)),
                    ["metadata"] = meta == 0 ? null : owner.ObjectPath(meta) });
            }
            result.Add(owner.ObjectPath(component.Index), new JObject {
                ["chaos"] = source.Value<bool?>("bIsChaosField") ?? true,
                ["world"] = source.Value<bool?>("bIsWorldField") ?? false,
                ["commands"] = commands });
        }
        return result;
    }

    private static JObject Node(AssetLibrary library, SourceAsset owner, int reference, HashSet<string> visited)
    {
        string path = owner.ObjectPath(reference);
        if (!visited.Add(path)) throw new ConversionException($"Chaos field graph reuses a node in one command: {path}");
        SourceAsset asset = reference > 0 ? owner : library.Load(path);
        SourceObject node = reference > 0 ? owner.Object(reference)
            : asset.ResolveObject(path, value => NodeProperties(value.ClassName) is not null);
        var children = NodeProperties(node.ClassName)
            ?? throw new ConversionException($"Unsupported Chaos field node {node.ClassName}: {path}");
        var properties = (JObject)node.Properties.DeepClone();
        foreach (string child in children) {
            int childReference = properties.Value<int?>(child) ?? 0;
            properties[child] = childReference == 0 ? null : Node(library, asset, childReference, visited);
        }
        return new JObject { ["type"] = node.ClassName, ["properties"] = properties };
    }

    private static string[]? NodeProperties(string type) => type switch {
        "UniformInteger" or "RadialIntMask" or "UniformScalar" or "WaveScalar" or "RadialFalloff" or "PlaneFalloff"
            or "BoxFalloff" or "NoiseField" or "UniformVector" or "RadialVector" or "RotatedRadialVector"
            or "RandomVector" or "ReturnResultsTerminal" => [],
        "OperatorField" => ["RightField", "LeftField"],
        "ToIntegerField" => ["FloatField"],
        "ToFloatField" => ["IntField"],
        "CullingField" => ["Culling", "Field"],
        _ => null
    };
}
