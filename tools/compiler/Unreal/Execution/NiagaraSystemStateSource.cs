using System.Buffers.Binary;
using System.Globalization;
using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class NiagaraSystemStateSource
{
    private const string SystemStateModule = "/Niagara/Modules/System/SystemState.SystemState";
    private const string Bool = "/Script/Niagara.NiagaraBool";
    private const string Int = "/Script/Niagara.NiagaraInt32";
    private const string Float = "/Script/Niagara.NiagaraFloat";

    internal static JObject? Read(SourceAsset asset, JObject properties, SourceObject spawnScript,
        SourceObject updateScript, AssetLibrary? library)
    {
        JObject? state = ReadSaved(asset, properties) ?? ResolveEditor(asset, properties, spawnScript, updateScript, library);
        if (state is null) return null;
        return new JObject {
            ["run_spawn"] = state.Value<bool?>("bRunSpawnScript") ?? true,
            ["run_update"] = state.Value<bool?>("bRunUpdateScript") ?? true,
            ["ignore_state"] = state.Value<bool?>("bIgnoreSystemState") ?? true,
            ["resample_duration"] = state.Value<bool?>("bRecalculateDurationEachLoop") ?? false,
            ["delay_enabled"] = state.Value<bool?>("bLoopDelayEnabled") ?? false,
            ["first_delay_only"] = state.Value<bool?>("bDelayFirstLoopOnly") ?? false,
            ["resample_delay"] = state.Value<bool?>("bRecalculateDelayEachLoop") ?? false,
            ["inactive_response"] = EnumName(state["InactiveResponse"], "Complete", ["Complete", "Kill"]),
            ["loop_behavior"] = EnumName(state["LoopBehavior"], "Once", ["Infinite", "Multiple", "Once"]),
            ["loop_count"] = state.Value<int?>("LoopCount") ?? 1,
            ["duration"] = Range(state["LoopDuration"]),
            ["delay"] = Range(state["LoopDelay"])
        };
    }

    private static JObject? ReadSaved(SourceAsset asset, JObject properties)
    {
        JToken? encoded = properties["SystemStateDataStruct"];
        if (encoded is null || encoded.Type == JTokenType.Null) return null;
        var source = encoded["unreal_struct"] as JObject
            ?? throw new ConversionException("Niagara system state has no instanced struct declaration");
        int type = source.Value<int>("type");
        if (type == 0) return null;
        if (!SourceNameOrder.Equal(asset.Reference(type), "/Script/Niagara.NiagaraSystemStateData"))
            throw new ConversionException("Unexpected Niagara system state struct");
        return source["value"] as JObject
            ?? throw new ConversionException("Niagara system state struct has no fields");
    }

    private static JObject? ResolveEditor(SourceAsset asset, JObject properties, SourceObject spawnScript,
        SourceObject updateScript, AssetLibrary? library)
    {
        if (properties.Value<bool?>("bAllowSystemStateFastPath") == false
            || properties["EmitterHandles"] is not JArray handles
            || handles.OfType<JObject>().Any(handle => handle.Value<bool?>("bIsEnabled") != false
                && EmitterHandleSource.IsStandard(handle))) return null;

        NiagaraScriptGraphSource? graph = NiagaraScriptGraphSource.Read(asset, spawnScript);
        if (graph?.Modules("SystemUpdateScript") is not { } update || update.Count > 1) return null;
        JObject? state = update.Count == 0 ? new() : ResolveModule(graph, update[0], updateScript, library);
        if (state is null) return null;
        state["bRunUpdateScript"] = false;
        state["bRunSpawnScript"] = graph.Modules("SystemSpawnScript")?.Count != 0;
        return state;
    }

    private static JObject? ResolveModule(NiagaraScriptGraphSource graph, SourceObject module, SourceObject updateScript,
        AssetLibrary? library)
    {
        if (module.ClassName != "NiagaraNodeFunctionCall") return null;
        JObject call = module.Properties;
        int reference = call.Value<int?>("FunctionScript") ?? 0;
        if (!SourceNameOrder.Equal(graph.Asset.ObjectPath(reference), SystemStateModule)) return null;
        SourceAsset? owner = reference > 0 ? graph.Asset : (library ?? graph.Asset.Library)?.TryLoad(SystemStateModule);
        if (owner is null) return null;
        SourceObject script = reference > 0 ? owner.Object(reference)
            : owner.ResolveObject(SystemStateModule, value => value.ClassName == "NiagaraScript");
        NiagaraScriptGraphSource? called = NiagaraScriptGraphSource.Read(owner, script, call.Value<string>("SelectedScriptVersion"));
        string? alias = call.Value<string>("FunctionDisplayName");
        if (called is null || string.IsNullOrEmpty(alias)) return null;

        IReadOnlyList<GraphPin> callPins = graph.Pins(module);
        int? behavior = ReadSwitch(called, graph, callPins, "Loop Behavior", new[] { "Infinite", "Once", "Multiple" });
        int? inactive = ReadSwitch(called, graph, callPins, "Inactive Response", new[] { "Complete", "Kill" });
        int? delayEnabled = ReadSwitch(called, graph, callPins, "UseLoopDelay", null);
        if (behavior is not (0 or 1 or 2) || inactive is not (0 or 1) || delayEnabled is null) return null;

        SourceObject? preceding = graph.PreviousInParameterMap(module);
        IReadOnlyList<GraphPin> overrides = preceding?.ClassName == "NiagaraNodeParameterMapSet"
            ? graph.Pins(preceding) : Array.Empty<GraphPin>();
        JObject iteration = updateScript.Properties["RapidIterationParameters"] as JObject ?? new();
        int? Constant(string name, string type) =>
            ReadConstant(graph, called, overrides, iteration, alias, name, type);

        int? duration = Constant("Loop Duration", Float);
        int? count = behavior == 2 ? Constant("Loop Count", Int) : 1;
        int? recalculate = behavior != 1 ? Constant("Recalculate Duration Each Loop", Bool) : 0;
        int? firstDelay = delayEnabled != 0 ? Constant("Delay First Loop Only", Bool) : 0;
        int? delay = delayEnabled != 0 ? Constant("Loop Delay", Float) : 0;
        if (duration is null || count is null || recalculate is null || firstDelay is null || delay is null) return null;
        float durationSeconds = BitConverter.Int32BitsToSingle(duration.Value);
        float delaySeconds = BitConverter.Int32BitsToSingle(delay.Value);
        if (!float.IsFinite(durationSeconds) || !float.IsFinite(delaySeconds)) return null;

        return new JObject {
            ["InactiveResponse"] = inactive == 0 ? "Complete" : "Kill",
            ["LoopBehavior"] = behavior switch { 0 => "Infinite", 1 => "Once", _ => "Multiple" },
            ["LoopCount"] = count.Value, ["bRecalculateDurationEachLoop"] = recalculate != 0,
            ["bLoopDelayEnabled"] = delayEnabled != 0, ["bDelayFirstLoopOnly"] = firstDelay != 0,
            ["LoopDuration"] = new JObject { ["Min"] = durationSeconds, ["Max"] = durationSeconds },
            ["LoopDelay"] = new JObject { ["Min"] = delaySeconds, ["Max"] = delaySeconds }
        };
    }

    private static int? ReadSwitch(NiagaraScriptGraphSource called, NiagaraScriptGraphSource graph,
        IReadOnlyList<GraphPin> callPins, string name, IReadOnlyList<string>? members)
    {
        JObject? declaration = called.Nodes.Where(node => node.ClassName == "NiagaraNodeStaticSwitch")
            .Select(node => node.Properties).FirstOrDefault(node => SourceNameOrder.Equal(node.Value<string>("InputParameterName"), name));
        if (declaration is null) return null;
        JObject switchType = declaration["SwitchTypeData"] as JObject ?? new();
        if (switchType.Value<bool?>("bExposeAsPin") == true
            || switchType.Value<string>("SwitchConstant") is not (null or "None")) return null;
        GraphPin? pin = callPins.FirstOrDefault(pin => pin.IsInput && SourceNameOrder.Equal(pin.Name, name));
        if (pin is null || pin.Links.Count != 0) return null;
        if (members is null) return graph.HasType(pin, Bool) ? (pin.DefaultValue == "true" ? 1 : 0) : null;
        int enumReference = switchType.Value<int?>("Enum") ?? 0;
        string enumPath = called.Asset.ObjectPath(enumReference);
        if (enumReference == 0 || !graph.HasType(pin, enumPath)) return null;
        string? value = NiagaraScriptGraphSource.EnumMember(pin.DefaultValue);
        SourceObject? enumObject = enumReference > 0 ? called.Asset.Object(enumReference) : null;
        if (enumReference < 0 && (enumPath.StartsWith("/Niagara/", StringComparison.Ordinal)
            || enumPath.StartsWith("/Game/", StringComparison.Ordinal) || enumPath.StartsWith("/Engine/", StringComparison.Ordinal))
            && called.Asset.Library?.TryLoad(enumPath) is { } enumAsset)
            enumObject = enumAsset.ResolveObject(enumPath, item => item.Raw["Enum"] is JObject);
        if (enumObject?.Raw["Enum"]?["Names"] is JArray names) {
            for (int index = 0; index < names.Count; index++)
                if (SourceNameOrder.Equal(value, NiagaraScriptGraphSource.EnumMember(names[index].Value<string>("Item1"))))
                    return index;
            return null;
        }
        for (int index = 0; index < members.Count; index++)
            if (SourceNameOrder.Equal(value, members[index])) return index;
        return null;
    }

    private static int? ReadConstant(NiagaraScriptGraphSource graph, NiagaraScriptGraphSource called,
        IReadOnlyList<GraphPin> overrides, JObject iteration, string alias, string name, string type)
    {
        string aliasName = alias + "." + name;
        GraphPin? local = overrides.FirstOrDefault(pin => pin.IsInput && SourceNameOrder.Equal(pin.Name, aliasName));
        if (local is not null && (local.Links.Count != 0 || !graph.HasType(local, type))) return null;

        List<GraphPin> defaults = new();
        foreach (SourceObject node in called.Nodes.Where(node => node.ClassName == "NiagaraNodeParameterMapGet")) {
            IReadOnlyList<GraphPin> pins = called.Pins(node);
            GraphPin? output = pins.FirstOrDefault(pin => !pin.IsInput && called.HasType(pin, type)
                && SourceNameOrder.Equal(pin.Name, "Module." + name));
            if (output is null) continue;
            Guid id = ReadDefaultPinId(node.Properties["PinOutputToPinDefaultPersistentId"], output.PersistentId);
            if (id == Guid.Empty) return null;
            GraphPin? defaultPin = pins.FirstOrDefault(pin => pin.IsInput && pin.PersistentId == id);
            if (defaultPin is null || !called.HasType(defaultPin, type)) return null;
            defaults.Add(defaultPin);
        }
        if (defaults.Count == 0) return null;
        if (NumericParameterStoreSource.Read(graph.Asset, iteration, "Constants." + aliasName, type, 1) is { } saved)
            return unchecked((int)saved.Words[0]);
        if (local is not null) return ReadLiteral(local, type);

        int? value = ReadLiteral(defaults[0], type);
        return value is not null && defaults.All(pin => ReadLiteral(pin, type) == value) ? value : null;
    }

    private static Guid ReadDefaultPinId(JToken? mapping, Guid outputId)
    {
        if (outputId == Guid.Empty || mapping is not JArray pairs) return Guid.Empty;
        foreach (JToken entry in pairs) {
            (JToken? key, JToken? value) = entry switch {
                JArray { Count: 2 } pair => (pair[0], pair[1]),
                JObject pair => (pair["Key"], pair["Value"]),
                _ => (null, null)
            };
            if (ReadMapGuid(key) == outputId) return ReadMapGuid(value);
        }
        return Guid.Empty;
    }

    private static Guid ReadMapGuid(JToken? token)
    {
        if (token?.Type != JTokenType.String || token.Value<string>() is not { } text
            || !Guid.TryParse(text, out Guid parsed)) return Guid.Empty;
        if (!text.StartsWith('{')) return parsed;

        // UAssetAPI prints the four FGuid words in value order. Pin links store those words little-endian.
        Span<char> digits = stackalloc char[32];
        parsed.TryFormat(digits, out _, "N");
        Span<byte> bytes = stackalloc byte[16];
        for (int word = 0; word < 4; word++) {
            uint value = uint.Parse(digits.Slice(word * 8, 8), NumberStyles.AllowHexSpecifier, CultureInfo.InvariantCulture);
            BinaryPrimitives.WriteUInt32LittleEndian(bytes[(word * sizeof(uint))..], value);
        }
        return new Guid(bytes);
    }

    private static int? ReadLiteral(GraphPin pin, string type)
    {
        if (pin.Links.Count != 0 || pin.DefaultObject != 0) return null;
        if (pin.IgnoresDefault || pin.DefaultValue.Length == 0) return 0;
        return type switch {
            Bool => pin.DefaultValue.Equals("true", StringComparison.OrdinalIgnoreCase) ? -1 : 0,
            Int when int.TryParse(pin.DefaultValue, NumberStyles.Integer, CultureInfo.InvariantCulture, out int integer) => integer,
            Float when float.TryParse(pin.DefaultValue, NumberStyles.Float, CultureInfo.InvariantCulture, out float scalar) =>
                BitConverter.SingleToInt32Bits(scalar),
            _ => null
        };
    }

    private static string EnumName(JToken? value, string fallback, IReadOnlyList<string> nativeOrder)
    {
        string name = value?.Value<string>() ?? fallback;
        int separator = name.LastIndexOf(':');
        name = name[(separator + 1)..];
        if (int.TryParse(name, NumberStyles.Integer, CultureInfo.InvariantCulture, out int ordinal))
            return ordinal >= 0 && ordinal < nativeOrder.Count ? nativeOrder[ordinal]
                : throw new ConversionException($"Unknown Niagara system-state enum value {ordinal}");
        return name;
    }

    private static JArray Range(JToken? value) =>
        new(SourceAsset.Number(value?["Min"], 0), SourceAsset.Number(value?["Max"], 0));
}
