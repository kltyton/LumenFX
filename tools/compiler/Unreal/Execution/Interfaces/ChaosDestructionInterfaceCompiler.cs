using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class ChaosDestructionInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceChaosDestruction";

    internal static JObject Definition(SourceAsset owner, int reference, AssetLibrary? library)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, ClassName, library);
        var data = source?.Object?.Properties ?? new JObject();
        var asset = source?.Asset ?? owner;
        JObject result = new() {
            ["source"] = EnumValue(data, "DataSourceType", ["Collision", "Breaking", "Trailing"]),
            ["solvers"] = Paths(asset, data["ChaosSolverActorSet"]),
            ["frequency"] = data.Value<int?>("DataProcessFrequency") ?? 10,
            ["maximum_entries"] = data.Value<int?>("MaxNumberOfDataEntriesToSpawn") ?? 50,
            ["spawn"] = data.Value<bool?>("DoSpawn") ?? true,
            ["multiplier"] = Range(data, "SpawnMultiplierMinMax", 1, 1),
            ["chance"] = SourceAsset.Number(data["SpawnChance"], 1),
            ["impulse"] = Range(data, "ImpulseToSpawnMinMax"),
            ["speed"] = Range(data, "SpeedToSpawnMinMax"),
            ["mass"] = Range(data, "MassToSpawnMinMax"),
            ["extent_min"] = Range(data, "ExtentMinToSpawnMinMax"),
            ["extent_max"] = Range(data, "ExtentMaxToSpawnMinMax"),
            ["volume"] = Range(data, "VolumeToSpawnMinMax"),
            ["time"] = Range(data, "SolverTimeToSpawnMinMax"),
            ["surface"] = data.Value<int?>("SurfaceTypeToSpawn") ?? -1,
            ["inclusive"] = EnumValue(data, "LocationFilteringMode", ["Inclusive", "Exclusive"]) == "INCLUSIVE",
            ["trail_speed"] = SourceAsset.Number(data["TrailMinSpeedToSpawn"], 50),
            ["filter_materials"] = data.Value<bool>("bApplyMaterialsFilter"),
            ["materials"] = new JArray(Paths(asset, data["ChaosBreakingMaterialSet"]).Values<string>()
                .Where(path => !string.IsNullOrEmpty(path))
                .Select(path => path!.Split('/', '.', ':').Last().ToLowerInvariant())),
            ["order"] = EnumValue(data, "DataSortingType", ["NoSorting", "RandomShuffle", "SortByMassMaxToMin", "SortByMassMinToMax"],
                ["NONE", "RANDOM", "HEAVIEST", "LIGHTEST"]),
            ["external_breaking"] = data.Value<bool?>("bGetExternalBreakingData") ?? true,
            ["spatial"] = data.Value<bool>("DoSpatialHash"),
            ["spatial_min"] = Vector(data, "SpatialHashVolumeMin", -100),
            ["spatial_max"] = Vector(data, "SpatialHashVolumeMax", 100),
            ["cell_size"] = Vector(data, "SpatialHashVolumeCellSize", 10),
            ["per_cell"] = data.Value<int?>("MaxDataPerCell") ?? 1,
            ["position"] = Range(data, "RandomPositionMagnitudeMinMax", 0, 0),
            ["inherited"] = SourceAsset.Number(data["InheritedVelocityMultiplier"], 1),
            ["velocity_model"] = EnumValue(data, "RandomVelocityGenerationType",
                ["RandomDistribution", "RandomDistributionWithStreamers", "CollisionNormalBased"],
                ["RANDOM", "STREAMERS", "COLLISION_CONE"]),
            ["random_velocity"] = Range(data, "RandomVelocityMagnitudeMinMax", 1, 2),
            ["spread"] = SourceAsset.Number(data["SpreadAngleMax"], 30),
            ["offset_min"] = Vector(data, "VelocityOffsetMin", 0),
            ["offset_max"] = Vector(data, "VelocityOffsetMax", 0),
            ["final_velocity"] = Range(data, "FinalVelocityMagnitudeMinMax"),
            ["max_latency"] = SourceAsset.Number(data["MaxLatency"], 1)
        };
        foreach (string axis in new[] { "X", "Y", "Z" }) {
            string field = "Location" + axis + "ToSpawn";
            string key = axis.ToLowerInvariant();
            result[key + "_mode"] = EnumValue(data, field, ["None", "Min", "Max", "MinMax"]);
            result[key + "_range"] = Range(data, field + "MinMax", 0, 0);
        }
        return result;
    }

    private static JArray Paths(SourceAsset owner, JToken? value)
    {
        if (value is null || value.Type == JTokenType.Null) return new JArray();
        if (value is not JArray entries) throw new ConversionException("Expected a saved Niagara Chaos object set");
        return new JArray(entries.Select(item => ObjectPropertyReaderCompiler.ObjectPath(owner, item)));
    }

    private static string EnumValue(JObject data, string name, string[] values, string[]? canonical = null)
    {
        string raw = data.Value<string>(name) ?? values[0];
        int index = int.TryParse(raw, out int numeric) ? numeric : Array.IndexOf(values, raw.Split("::").Last().Split('_').Last());
        if (index < 0 || index >= values.Length) throw new ConversionException($"Unknown Chaos Destruction {name} {raw}");
        return canonical?[index] ?? values[index].ToUpperInvariant();
    }

    private static JArray Range(JObject data, string name, double min = -1, double max = -1)
        => new(SourceAsset.Number(data[name]?["X"], min), SourceAsset.Number(data[name]?["Y"], max));

    private static JArray Vector(JObject data, string name, double fallback)
        => new(SourceAsset.Number(data[name]?["X"], fallback), SourceAsset.Number(data[name]?["Y"], fallback),
            SourceAsset.Number(data[name]?["Z"], fallback));

    internal static void ValidateCpu(string name, int inputs, int outputs)
    {
        var shape = name switch {
            "GetSolverTime" => (0, 1),
            "GetParticleIdsToSpawnAtTime" or "GetPosition" or "GetNormal" or "GetVelocity" or "GetAngularVelocity" or "GetSize" => (1, 3),
            "GetExtentMin" or "GetExtentMax" or "GetVolume" or "GetPointType" or "GetDensity" or "GetFriction"
                or "GetRestitution" or "GetSurfaceType" => (1, 1),
            "GetColor" => (1, 4),
            "GetTransform" => (1, 10),
            "GetCollisionData" => (1, 24),
            "GetBreakingData" or "GetTrailingData" => (1, 11),
            _ => (-1, -1)
        };
        if (shape != (inputs, outputs)) throw new ConversionException($"Invalid Chaos Destruction CPU function {name} ({inputs}, {outputs})");
    }
}
