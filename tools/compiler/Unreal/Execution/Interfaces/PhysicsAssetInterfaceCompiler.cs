using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class PhysicsAssetInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfacePhysicsAsset";

    internal static JObject Definition(SourceAsset owner, int reference, AssetLibrary? library)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, ClassName, library);
        var asset = source?.Asset ?? owner;
        var properties = source?.Object?.Properties;
        int physics = properties?.Value<int?>("DefaultSource") ?? 0;
        return new JObject {
            ["source_actor"] = ObjectPropertyReaderCompiler.ObjectPath(asset, properties?["SoftSourceActor"] ?? properties?["SourceActor_DEPRECATED"]),
            ["mesh_parameter"] = properties?["MeshUserParameter"]?["Parameter"]?.Value<string>("name") ?? "",
            ["default_source"] = physics == 0 ? null : ReadAsset(asset, physics, library ?? owner.Library)
        };
    }

    private static JObject ReadAsset(SourceAsset owner, int reference, AssetLibrary? library)
    {
        string path = owner.ObjectPath(reference);
        SourceAsset asset = reference > 0 ? owner : library?.Load(path)
            ?? throw new ConversionException($"Physics Asset source requires its package: {path}");
        var physics = reference > 0 ? asset.Object(reference) : asset.ResolveObject(path, value => value.ClassName == "PhysicsAsset");
        if (physics.ClassName != "PhysicsAsset") throw new ConversionException($"Invalid Niagara Physics Asset source: {path}");
        var data = physics.Properties;
        var elements = new JArray();
        foreach (int bodyReference in (data["SkeletalBodySetups"] as JArray ?? new()).Values<int>()) {
            if (bodyReference <= 0) throw new ConversionException($"Physics Asset body is outside its package: {path}");
            var body = asset.Object(bodyReference).Properties;
            string bone = body.Value<string>("BoneName") ?? "None";
            if (body["AggGeom"] is not JObject geometry) continue;
            Add(geometry, bone, "BoxElems", "box");
            Add(geometry, bone, "SphereElems", "sphere");
            Add(geometry, bone, "SphylElems", "capsule");
        }
        string preview = ObjectPropertyReaderCompiler.ObjectPath(asset, data["PreviewSkeletalMesh"]);
        return new JObject {
            ["source"] = path, ["elements"] = elements,
            ["skeletal_mesh_parameter"] = preview.Length == 0 ? null : new JObject { ["asset_source"] = preview }
        };

        void Add(JObject geometry, string bone, string field, string kind)
        {
            foreach (var element in (geometry[field] as JArray ?? new()).OfType<JObject>()) {
                int mode = Collision(element["CollisionEnabled"]);
                if (mode is not (2 or 3)) continue;
                var center = element["Center"];
                var rotation = element["Rotation"];
                JArray extent = kind switch {
                    "box" => new(SourceAsset.Number(element["X"], 1), SourceAsset.Number(element["Y"], 1), SourceAsset.Number(element["Z"], 1)),
                    "sphere" => new(SourceAsset.Number(element["Radius"], 1), 0, 0),
                    _ => new(SourceAsset.Number(element["Radius"], 1), SourceAsset.Number(element["Length"], 1), 0)
                };
                elements.Add(new JObject {
                    ["bone"] = bone, ["kind"] = kind, ["query_and_physics"] = mode == 3, ["extent"] = extent,
                    ["center"] = new JArray(SourceAsset.Number(center?["X"]), SourceAsset.Number(center?["Y"]), SourceAsset.Number(center?["Z"])),
                    ["rotation"] = new JArray(SourceAsset.Number(rotation?["Pitch"]), SourceAsset.Number(rotation?["Yaw"]), SourceAsset.Number(rotation?["Roll"]))
                });
            }
        }
    }

    private static int Collision(JToken? value) => value?.Type switch {
        null => 3,
        JTokenType.Integer when value.Value<int>() is >= 0 and <= 5 => value.Value<int>(),
        JTokenType.String => value.Value<string>()?.Split("::").Last() switch {
            "NoCollision" => 0, "QueryOnly" => 1, "PhysicsOnly" => 2, "QueryAndPhysics" => 3,
            "ProbeOnly" => 4, "QueryAndProbe" => 5,
            _ => throw new ConversionException($"Unknown Physics Asset collision mode: {value}")
        },
        _ => throw new ConversionException($"Invalid Physics Asset collision mode: {value}")
    };
}
