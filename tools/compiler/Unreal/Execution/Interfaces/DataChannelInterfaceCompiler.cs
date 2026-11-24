using LumenFx.Compiler.Unreal.Parameters;
using LumenFx.Compiler.Unreal.Types;
using System.Text.RegularExpressions;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class DataChannelInterfaceCompiler
{
    internal const string Reader = "NiagaraDataInterfaceDataChannelRead";
    internal const string Writer = "NiagaraDataInterfaceDataChannelWrite";

    internal static JObject Definition(SourceAsset owner, int reference, string type, AssetLibrary? library)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, type, library);
        var properties = source?.Object?.Properties;
        var asset = source?.Asset ?? owner;
        var result = new JObject {
            ["writer"] = type == Writer,
            ["read_current"] = properties?.Value<bool?>("bReadCurrentFrame") ?? false,
            ["refresh"] = type == Writer ? properties?.Value<bool?>("bUpdateDestinationDataEveryTick") ?? true
                : properties?.Value<bool?>("bUpdateSourceDataEveryTick") ?? true,
            ["only_final_subtick"] = properties?.Value<bool?>("bOnlyWriteOnceOnSubticks") ?? true,
            ["publish_game"] = properties?.Value<bool?>("bPublishToGame") ?? false,
            ["publish_cpu"] = properties?.Value<bool?>("bPublishToCPU") ?? false,
            ["publish_gpu"] = properties?.Value<bool?>("bPublishToGPU") ?? false
        };
        result["spawn_groups"] = properties?.Value<bool?>("bOverrideSpawnGroupToDataChannelIndex") ?? false;
        result["spawn_once"] = properties?.Value<bool?>("bOnlySpawnOnceOnSubticks") ?? true;
        result["auto_link"] = type == Reader && (properties?.Value<bool?>("bAutoLinkToSpawningNDC") ?? true);
        if (CompileAccessContext(asset, properties?["AccessContext"], library) is { } accessContext)
            result["access_context"] = accessContext;
        var compiled = properties?["CompiledData"] as JObject;
        result["gpu_spawning_reader"] = type == Reader && compiled?.Value<bool?>("bUsedByGPU") == true
            && compiled.Value<bool?>("bSpawnsParticles") == true;
        int channel = properties?.Value<int?>("Channel") ?? 0;
        if (channel != 0) result["channel"] = Channel(asset, channel, library);
        if (type == Writer) {
            var mode = properties?["AllocationMode"] switch {
                null => false,
                { Type: JTokenType.Integer } ordinal when ordinal.Value<int>() is 0 or 1 => ordinal.Value<int>() == 1,
                { Type: JTokenType.String } name when name.Value<string>()?.Split("::").Last() == "Static" => false,
                { Type: JTokenType.String } name when name.Value<string>()?.Split("::").Last() == "Dynamic" => true,
                _ => throw new ConversionException("Unknown Niagara data channel allocation mode")
            };
            result["allocation_count"] = mode ? 0 : properties?.Value<uint?>("AllocationCount") ?? 0;
        } else result["allocation_count"] = 0;
        return result;
    }

    private static JObject Channel(SourceAsset owner, int reference, AssetLibrary? library)
    {
        string path = owner.ObjectPath(reference);
        var asset = reference > 0 ? owner : (library ?? owner.Library)?.Load(path)
            ?? throw new ConversionException($"Data channel requires its source asset: {path}");
        var entry = reference > 0 ? owner.Object(reference) : asset.ResolveObject(path, value => value.ClassName == "NiagaraDataChannelAsset");
        int dataReference = entry.Properties.Value<int>("DataChannel");
        if (dataReference <= 0) throw new ConversionException($"Data channel has no local data object: {path}");
        var channel = asset.Object(dataReference);
        if (channel.ClassName is not ("NiagaraDataChannel_Global" or "NiagaraDataChannel_Islands" or "NiagaraDataChannel_GameplayBurst"))
            throw new ConversionException($"Data channel requires its native access context: {channel.ClassName} ({path})");
        var fields = channel.Properties["ChannelVariables"] ?? channel.Properties["Variables"];
        var result = new JObject {
            ["path"] = path,
            ["fields"] = Variables(asset, fields),
            ["keep_previous"] = channel.Properties.Value<bool?>("bKeepPreviousFrameData") ?? true,
            ["enforce_tick_order"] = channel.Properties.Value<bool?>("bEnforceTickGroupReadWriteOrder") ?? false,
            ["final_write_group"] = channel.Properties["FinalWriteTickGroup"]?.DeepClone() ?? new JValue("TG_EndPhysics")
        };
        if (channel.ClassName == "NiagaraDataChannel_Islands") {
            var source = channel.Properties;
            string mode = source["Mode"] switch {
                null => "AlignedStatic",
                { Type: JTokenType.Integer } ordinal when ordinal.Value<int>() is 0 or 1 => ordinal.Value<int>() == 0 ? "AlignedStatic" : "Dynamic",
                { Type: JTokenType.String } name when name.Value<string>()?.Split("::").Last() is "AlignedStatic" or "Dynamic" =>
                    name.Value<string>()!.Split("::").Last(),
                _ => throw new ConversionException($"Unknown Niagara data channel island mode: {path}")
            };
            var systems = new List<string>();
            ExpandSystemOrCollection(asset, source["DefaultSystemToSpawn"], library, systems);
            ExpandSystemOrCollection(asset, source["SystemToSpawn"], library, systems);
            if (source["Systems"] is JArray islandSystems) {
                foreach (var islandSys in islandSystems) ExpandSystemOrCollection(asset, islandSys, library, systems);
            }
            result["islands"] = new JObject {
                ["mode"] = mode,
                ["initial_extents"] = Extents(source["InitialExtents"], 1000),
                ["maximum_extents"] = Extents(source["MaxExtents"], 5000),
                ["element_extents"] = Extents(source["PerElementExtents"], 250),
                ["pool_size"] = source.Value<int?>("IslandPoolSize") ?? 4,
                ["system_sources"] = new JArray(systems)
            };
        } else if (channel.ClassName == "NiagaraDataChannel_GameplayBurst") {
            var source = channel.Properties;
            var systemList = new List<string>();
            JToken? defaultSystemToken = source["DefaultSystemToSpawn"] ?? source["SystemToSpawn"];
            string defaultSelectedKey = SelectedSystemKey(asset, defaultSystemToken, library);
            ExpandSystemOrCollection(asset, source["DefaultSystemToSpawn"], library, systemList);
            ExpandSystemOrCollection(asset, source["SystemToSpawn"], library, systemList);
            if ((source["Systems"] as JArray ?? source["SystemsToSpawn"] as JArray) is { } systemArray) {
                foreach (JToken item in systemArray) ExpandSystemOrCollection(asset, item, library, systemList);
            }
            JArray cellSize = Vector3(source["CellSize"], 2500.0);
            JArray boundsPadding = Vector3(source["SystemBoundsPadding"], 250.0);
            var attach = source["AttachmentSettings"] as JObject;
            double speedThreshold = attach?.Value<double?>("SpeedThreshold") ?? -1.0;
            var tags = new List<string>();
            if (attach?["GameplayTags"]?["GameplayTags"] is JArray tagEntries) {
                foreach (var tagItem in tagEntries) {
                    string? tagName = (tagItem as JObject)?["TagName"]?.Value<string>() ?? tagItem.Value<string>();
                    if (!string.IsNullOrEmpty(tagName) && tagName != "None") tags.Add(tagName);
                }
            }
            var componentTypes = new List<string>();
            if (attach?["ComponentTypes"] is JArray compEntries) {
                foreach (var compItem in compEntries) {
                    string comp = ObjectPropertyReaderCompiler.ObjectPath(asset, compItem);
                    if (!string.IsNullOrEmpty(comp) && comp != "None") componentTypes.Add(comp);
                }
            }
            var burstObj = new JObject {
                ["cell_size"] = cellSize,
                ["bounds_padding"] = boundsPadding,
                ["default_selected_system"] = SourceNameOrder.Canonical(defaultSelectedKey),
                ["attachment"] = new JObject {
                    ["speed_threshold"] = speedThreshold,
                    ["gameplay_tags"] = new JArray(tags),
                    ["component_types"] = new JArray(componentTypes)
                }
            };
            if (systemList.Count > 0) burstObj["system_sources"] = new JArray(systemList);
            else burstObj["systems"] = new JArray();
            result["gameplay_burst"] = burstObj;
        }
        return result;
    }

    private static void ExpandSystemOrCollection(SourceAsset ownerAsset, JToken? token, AssetLibrary? library,
        List<string> systemList, HashSet<string>? visitedCollections = null)
    {
        if (token is null || token.Type == JTokenType.Null) return;
        string rawPath = ObjectPropertyReaderCompiler.ObjectPath(ownerAsset, token);
        if (string.IsNullOrEmpty(rawPath) || rawPath == "None") return;

        var resolvedAsset = rawPath.StartsWith(ownerAsset.Name + ".", StringComparison.OrdinalIgnoreCase)
                || !rawPath.Contains('.')
                || SourceNameOrder.Equal(rawPath, ownerAsset.Name)
            ? ownerAsset
            : (library ?? ownerAsset.Library)?.Load(rawPath);

        if (resolvedAsset is not null) {
            string canonical = rawPath.Replace(':', '.');
            SourceObject? targetObj = resolvedAsset.Objects.FirstOrDefault(o =>
                SourceNameOrder.Equal(resolvedAsset.ObjectPath(o.Index), canonical))
                ?? (SourceNameOrder.Equal(rawPath, resolvedAsset.Name)
                    ? resolvedAsset.Objects.FirstOrDefault(o => o.ClassName is "NiagaraSystemCollection" or "NiagaraSystem")
                    : null);
            if (targetObj?.ClassName == "NiagaraSystemCollection") {
                visitedCollections ??= new HashSet<string>(SourceNameOrder.Instance);
                string colId = resolvedAsset.ObjectPath(targetObj.Index);
                if (!visitedCollections.Add(colId)) return;
                // UE 5.9 UNiagaraSystemCollection stores FNiagaraSystemCollectionData SystemCollection { TArray<TSoftObjectPtr<UNiagaraSystem>> Systems }.
                // Use active recursion tracking (remove on return) so valid DAG reuse & duplicate system slots match PostLoadSystems.
                try {
                    JArray? colSystems = (targetObj.Properties["SystemCollection"] as JObject)?["Systems"] as JArray
                        ?? targetObj.Properties["Systems"] as JArray;
                    if (colSystems is not null) {
                        foreach (JToken entry in colSystems)
                            ExpandSystemOrCollection(resolvedAsset, entry, library, systemList, visitedCollections);
                    }
                } finally {
                    visitedCollections.Remove(colId);
                }
                return;
            }
            if (targetObj is not null) {
                systemList.Add(resolvedAsset.ObjectPath(targetObj.Index));
                return;
            }
        }
        systemList.Add(rawPath);
    }

    private static string SelectedSystemKey(SourceAsset ownerAsset, JToken? token, AssetLibrary? library)
    {
        if (token is null || token.Type == JTokenType.Null) return "";
        string rawPath = ObjectPropertyReaderCompiler.ObjectPath(ownerAsset, token);
        if (string.IsNullOrEmpty(rawPath) || rawPath == "None") return "";
        var resolvedAsset = rawPath.StartsWith(ownerAsset.Name + ".", StringComparison.OrdinalIgnoreCase)
                || !rawPath.Contains('.')
                || SourceNameOrder.Equal(rawPath, ownerAsset.Name)
            ? ownerAsset
            : (library ?? ownerAsset.Library)?.Load(rawPath);
        if (resolvedAsset is not null) {
            string canonical = rawPath.Replace(':', '.');
            SourceObject? targetObj = resolvedAsset.Objects.FirstOrDefault(o =>
                SourceNameOrder.Equal(resolvedAsset.ObjectPath(o.Index), canonical))
                ?? (SourceNameOrder.Equal(rawPath, resolvedAsset.Name)
                    ? resolvedAsset.Objects.FirstOrDefault(o => o.ClassName is "NiagaraSystemCollection" or "NiagaraSystem")
                    : null);
            if (targetObj is not null) return resolvedAsset.ObjectPath(targetObj.Index);
        }
        return rawPath.Replace(':', '.');
    }

    private static JObject? CompileAccessContext(SourceAsset asset, JToken? rawToken, AssetLibrary? library)
    {
        if (rawToken is null || rawToken.Type == JTokenType.Null) return null;
        JObject? container = rawToken as JObject;
        if (container?["AccessContext"] is JObject inner) container = inner;
        string? structType = null;
        JObject? ctx = container;
        if (container?["unreal_struct"] is JObject unrealStruct) {
            if (unrealStruct["type"] is { Type: JTokenType.Integer } typeRef) {
                int refId = typeRef.Value<int>();
                if (refId == 0) return null;
                structType = asset.Reference(refId);
            } else if (unrealStruct["type"]?.Value<string>() is { Length: > 0 } typeStr) {
                structType = typeStr;
            }
            ctx = unrealStruct["value"] as JObject;
        } else if (container?["StructType"]?.Value<string>() is { Length: > 0 } explicitType) {
            structType = explicitType;
            ctx = container["Value"] as JObject ?? container;
        } else if (container?["Value"] is JObject innerVal) {
            ctx = innerVal;
        }
        if (ctx is null || ctx.Count == 0) return null;

        bool isLegacy = structType is not null && structType.EndsWith("NDCAccessContextLegacy", StringComparison.OrdinalIgnoreCase);
        bool supportsBurstFields = structType is null
            || structType.EndsWith("NDCAccessContext_GameplayBurst", StringComparison.OrdinalIgnoreCase);

        bool overrideLoc = ctx.Value<bool?>("bOverrideLocation") ?? false;
        JArray location = Vector3(ctx["Location"], 0.0);
        bool overrideSys = !isLegacy && (ctx.Value<bool?>("bOverrideSystemToSpawn") ?? false);
        var sysSources = new List<string>();
        string selectedSystem = "";
        if (overrideSys) {
            selectedSystem = SourceNameOrder.Canonical(SelectedSystemKey(asset, ctx["SystemToSpawn"], library));
            ExpandSystemOrCollection(asset, ctx["SystemToSpawn"], library, sysSources);
        }
        bool forceAttach = supportsBurstFields && (ctx.Value<bool?>("bForceAttachToOwningComponent") ?? false);
        bool overrideCell = supportsBurstFields && (ctx.Value<bool?>("bOverrideCellSize") ?? false);
        JArray cellSize = Vector3(supportsBurstFields ? ctx["CellSizeOverride"] : null, 2500.0);
        bool overridePadding = supportsBurstFields && (ctx.Value<bool?>("bOverrideBoundsPadding") ?? false);
        JArray padding = Vector3(supportsBurstFields ? ctx["SystemBoundsPadding"] : null, 250.0);
        string tag = supportsBurstFields
            ? ((ctx["GameplayTag"] as JObject)?["TagName"]?.Value<string>() ?? ctx["GameplayTag"]?.Value<string>()) ?? ""
            : "";
        if (tag == "None") tag = "";

        var accessJson = new JObject {
            ["override_location"] = overrideLoc,
            ["location"] = location,
            ["override_system"] = overrideSys,
            ["selected_system"] = selectedSystem,
            ["force_attach"] = forceAttach,
            ["override_cell_size"] = overrideCell,
            ["cell_size_override"] = cellSize,
            ["override_bounds_padding"] = overridePadding,
            ["bounds_padding_override"] = padding,
            ["gameplay_tag"] = tag
        };
        if (sysSources.Count > 0) accessJson["system_sources"] = new JArray(sysSources);
        else accessJson["systems"] = new JArray();
        return accessJson;
    }

    private static JArray Vector3(JToken? source, double fallback)
    {
        if (source is null) return new JArray(fallback, fallback, fallback);
        if (source.Type is JTokenType.Float or JTokenType.Integer) {
            double v = source.Value<double>();
            return new JArray(v, v, v);
        }
        if (source is not JObject vector) throw new ConversionException("Invalid Niagara GameplayBurst vector");
        return new JArray(new[] { "X", "Y", "Z" }.Select(axis => SourceAsset.Number(
            vector.GetValue(axis, StringComparison.OrdinalIgnoreCase) ?? new JValue(fallback))));
    }

    private static JArray Extents(JToken? source, double fallback)
    {
        if (source is null) return new JArray(fallback, fallback, fallback);
        if (source is not JObject vector) throw new ConversionException("Invalid Niagara data channel island extents");
        return new JArray(new[] { "X", "Y", "Z" }.Select(axis => SourceAsset.Number(
            vector.GetValue(axis, StringComparison.OrdinalIgnoreCase) ?? throw new ConversionException($"Missing Niagara island extent {axis}"))));
    }

    internal static JObject CpuFunction(SourceAsset owner, JObject function, string type, int inputs, int outputs)
    {
        string name = function.Value<string>("Name") ?? "";
        JArray variadic = Variables(owner, function[type == Writer || name == "SpawnConditional" ? "VariadicInputs" : "VariadicOutputs"]);
        int words = variadic.OfType<JObject>().Sum(value => ((JArray)value["registers"]!).Count);
        bool valid = (type, name) switch {
            (_, "Num") => inputs == 0 && outputs == 1 && words == 0,
            (Reader, "Read") => inputs == 1 && outputs == 1 + words,
            (Reader, "Consume") => inputs == 1 && outputs == 2 + words,
            (Reader, "GetNDCSpawnData") => inputs == 2 && outputs == 3 && words == 0,
            (Reader, "SpawnConditional") => inputs == 6 + words && outputs == 0,
            (Reader, "SpawnDirect" or "ScaleSpawnCount") => inputs == 7 && outputs == 0 && words == 0,
            (Writer, "Write") => inputs == 2 + words && outputs == 1,
            (Writer, "Append") => inputs == 1 + words && outputs == 1,
            _ => false
        };
        if (!valid) throw new ConversionException($"Unsupported Niagara data channel function: {type}.{name} ({inputs}, {outputs})");
        var result = new JObject { ["variables"] = variadic };
        if (name is "SpawnDirect" or "ScaleSpawnCount") {
            var specifiers = (function["FunctionSpecifiers"] as JArray ?? new()).OfType<JObject>()
                .ToDictionary(value => value.Value<string>("Key") ?? "", value => value.Value<string>("Value") ?? "", SourceNameOrder.Instance);
            if (!specifiers.TryGetValue("VarName", out string? variable) || variable.Length == 0 || !specifiers.TryGetValue("VarType", out string? sourceType))
                throw new ConversionException($"Niagara data channel {name} has no saved typed variable specifier");
            var match = Regex.Match(sourceType, @"/Script/[\p{L}\p{N}_.]+");
            if (!match.Success) throw new ConversionException($"Cannot read Niagara data channel {name} variable type: {sourceType}");
            string runtimeType = NiagaraTypeSource.KnownSwc(match.Value) ?? match.Value;
            bool supported = SourceNameOrder.Equal(runtimeType, "/Script/Niagara.NiagaraInt32") || name == "ScaleSpawnCount" &&
                new[] { "/Script/Niagara.NiagaraFloat", "/Script/CoreUObject.Vector2f", "/Script/CoreUObject.Vector3f",
                    "/Script/CoreUObject.Vector4f", "/Script/Niagara.NiagaraPosition" }.Any(path => SourceNameOrder.Equal(runtimeType, path));
            if (!supported) throw new ConversionException($"Invalid Niagara data channel {name} variable type: {runtimeType}");
            result["selected_name"] = variable;
            result["selected_type"] = runtimeType;
        }
        return result;
    }

    internal static JArray GpuParameters(SourceAsset owner, JObject shaderBinding)
    {
        var parameters = new List<JObject>();
        foreach (var function in (shaderBinding["GeneratedFunctions"] as JArray ?? new()).OfType<JObject>()) {
            foreach (string field in new[] { "VariadicInputs", "VariadicOutputs" }) {
                foreach (JObject parameter in Variables(owner, function[field])) {
                    string name = parameter.Value<string>("name")!, type = parameter.Value<string>("type")!;
                    if (!parameters.Any(prior => SourceNameOrder.Equal(name, prior.Value<string>("name"))
                            && SourceNameOrder.Equal(type, prior.Value<string>("type"))))
                        parameters.Add(parameter);
                }
            }
        }
        return new JArray(parameters.OrderBy(value => value.Value<string>("name")!, SourceNameOrder.Instance)
            .ThenBy(value => value.Value<string>("type")!.Split('.').Last(), SourceNameOrder.Instance));
    }

    private static JArray Variables(SourceAsset asset, JToken? variables) {
        if (variables is null) return new JArray();
        if (variables is not JArray array) throw new ConversionException("Invalid Niagara data channel variable list");
        return new JArray(array.Select(item => Variable(asset, (JObject)item)));
    }

    private static JObject Variable(SourceAsset asset, JObject value)
    {
        string name;
        NiagaraTypeSource.Definition type;
        if (value["Type"] is { Type: JTokenType.Integer } reference) {
            name = value.Value<string>("Name") ?? throw new ConversionException("Niagara data channel variable reference has no name");
            type = NiagaraTypeSource.ReadCommonReference(asset, reference.Value<int>());
        } else {
            var source = ExecutionNumericParameterSource.Read(asset, value);
            name = source.Name;
            type = source.Type;
        }
        var registers = new JArray();
        if (type.UnderlyingType == 3) registers.Add("INT");
        else {
            if (!BuiltinStructLayouts.TryGet(type.Path, out var layout))
                layout = StructLayoutSource.Find(asset, type.SourceReference);
            if (layout is null) throw new ConversionException($"Missing Niagara data channel variable layout: {type.Path}");
            AddRegisters(layout, registers);
        }
        if (registers.Count == 0) throw new ConversionException($"Niagara data channel variable has no VM registers: {name}");
        return new JObject { ["name"] = name, ["type"] = type.Path, ["registers"] = registers };
    }

    private static void AddRegisters(StructLayout layout, JArray result)
    {
        foreach (var field in layout.Fields) for (int element = 0; element < field.ArrayDim; element++) {
            if (field.Kind == StructFieldKind.Struct && field.Structure is not null) AddRegisters(field.Structure, result);
            else result.Add(field.Kind switch {
                StructFieldKind.Float or StructFieldKind.Double => "FLOAT",
                StructFieldKind.Bool => "BOOL",
                StructFieldKind.Int32 or StructFieldKind.Enum => "INT",
                StructFieldKind.UInt16 => "HALF",
                _ => throw new ConversionException($"Unsupported Niagara data channel field: {layout.Path}.{field.Name}")
            });
        }
    }
}
