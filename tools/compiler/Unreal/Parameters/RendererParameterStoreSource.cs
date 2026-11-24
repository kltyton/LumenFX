using Newtonsoft.Json.Linq;
using LumenFx.Compiler.Unreal.Execution;

namespace LumenFx.Compiler.Unreal.Parameters;

/** Editor rebuild and cooked storage retain a separate numeric renderer store. */
internal static class RendererParameterStoreSource
{
    internal static JObject Compile(SourceAsset asset, SourceObject system, JObject emitter, string emitterNamespace, string? emitterHandle)
    {
        var required = RendererRequiredParameters.Read(asset, system, emitter, emitterNamespace);
        var targets = RendererScriptTargets.Read(asset, system, emitter, emitterHandle).ToArray();
        var scripts = targets.Select(target => target.Script).ToArray();
        if ((asset.Raw.Value<string>("PackageFlags") ?? "").Contains("PKG_Cooked", StringComparison.Ordinal)) {
            JObject saved = Saved(asset, emitter);
            saved["required"] = Required(asset, emitter, required, scripts);
            saved["external_objects"] = RendererExternalObjectBindings.Compile(asset, system, emitter, emitterHandle, (JArray)saved["compiled_layout"]!);
            return saved;
        }
        List<byte> data = new();
        List<RendererFieldBindings.Variable> emitted = new();
        JArray variables = new(), declarations = new();
        foreach (var request in required.OrderBy(value => value.Name, SourceNameOrder.Instance)
                .ThenBy(value => value.StoreTypeName, SourceNameOrder.Instance)) {
            if (Storage(request) == "object") {
                if (targets.Any(target => RendererExternalObjectBindings.Declares(target, variable => request.Matches(asset, variable)))) {
                    declarations.Add(Declaration(request, 0));
                    emitted.Add(request);
                }
                continue;
            }
            JObject? value = null;
            foreach (SourceObject script in scripts) {
                value = (script.Properties["CachedScriptVM"]?["StaticVariablesWritten"] as JArray ?? new()).OfType<JObject>()
                    .FirstOrDefault(variable => request.Matches(asset, variable, true));
                if (value is not null) break;
            }
            if (value is null) continue;
            var actual = RendererFieldBindings.Variable.From(asset, value)
                ?? throw new ConversionException("Renderer static variable has no valid type");
            if (emitted.Any(variable => variable.SameStoreSlot(actual))) continue;
            if (actual.UnderlyingType == 1) {
                declarations.Add(Declaration(actual, 0)); emitted.Add(actual);
                continue;
            }
            byte[] bytes = NumericParameterStoreSource.Bytes(value["data"]);
            if (bytes.Length == 0) bytes = new byte[EmptyValueSize(asset, emitter, value, actual)];
            variables.Add(new JObject { ["name"] = actual.Name, ["source_type"] = actual.Type,
                ["flags"] = actual.Flags, ["underlying"] = actual.UnderlyingType, ["lwc"] = actual.Lwc,
                ["bytes"] = bytes.Length, ["offset"] = data.Count });
            data.AddRange(bytes); emitted.Add(actual);
            declarations.Add(Declaration(actual, bytes.Length));
        }
        return new JObject { ["data"] = Convert.ToBase64String(data.ToArray()), ["variables"] = variables,
            ["compiled_layout"] = declarations, ["required"] = Required(asset, emitter, required, scripts),
            ["external_objects"] = RendererExternalObjectBindings.Compile(asset, system, emitter, emitterHandle, declarations) };
    }

    private static string Storage(RendererFieldBindings.Variable variable) => variable.UnderlyingType != 1 ? "numeric"
        : variable.Type.Contains(".NiagaraDataInterface", StringComparison.Ordinal) ? "interface" : "object";

    private static JObject Declaration(RendererFieldBindings.Variable variable, int bytes) => new() {
        ["name"] = variable.Name, ["source_type"] = variable.Type, ["underlying"] = variable.UnderlyingType,
        ["flags"] = variable.Flags, ["lwc"] = variable.Lwc, ["storage"] = Storage(variable), ["bytes"] = bytes
    };

    private static JArray Required(SourceAsset asset, JObject emitter, IReadOnlyList<RendererFieldBindings.Variable> required, SourceObject[] scripts)
    {
        JArray result = new();
        foreach (var variable in required) {
            string storage = Storage(variable);
            int? bytes = variable.Bytes;
            if (storage != "numeric") bytes = 0;
            if (bytes is null) foreach (SourceObject script in scripts) {
                foreach (JObject candidate in ScriptDataInterfaceBindings.ParameterVariables(script)
                        .Concat((script.Properties["CachedScriptVM"]?["StaticVariablesWritten"] as JArray ?? new()).OfType<JObject>())) {
                    if (!variable.MatchesType(asset, candidate, true)) continue;
                    int length = NumericParameterStoreSource.Bytes(candidate["data"]).Length;
                    if (length > 0) { bytes = length; break; }
                }
                if (bytes.HasValue) break;
            }
            if (bytes is null && emitter["RendererBindings"] is JObject saved) {
                var numeric = (saved["SortedParameterOffsets"] as JArray ?? new()).OfType<JObject>()
                    .Where(value => value["type"] is JObject type && NiagaraTypeSource.Read(asset, type).UnderlyingType != 1).ToArray();
                JObject? entry = numeric.FirstOrDefault(value => variable.MatchesType(asset, value, true));
                if (entry is not null) {
                    int offset = entry.Value<int>("offset");
                    int end = numeric.Select(value => value.Value<int>("offset")).Where(value => value > offset)
                        .DefaultIfEmpty(NumericParameterStoreSource.Bytes(saved["ParameterData"]).Length).Min();
                    if (end >= offset) bytes = end - offset;
                }
            }
            if (bytes is null)
                throw new ConversionException($"Required renderer variable needs its source size: {variable.Name} ({variable.Type})");
            result.Add(new JObject { ["name"] = variable.Name, ["source_type"] = variable.Type,
                ["underlying"] = variable.UnderlyingType, ["flags"] = variable.Flags, ["lwc"] = variable.Lwc,
                ["storage"] = storage, ["bytes"] = bytes.Value });
        }
        return result;
    }

    private static int EmptyValueSize(SourceAsset asset, JObject emitter, JObject value, RendererFieldBindings.Variable variable)
    {
        if (variable.UnderlyingType == 3) return sizeof(int);
        if (value["type"] is JObject type && NiagaraTypeSource.Size(asset, type) is int structureSize) return structureSize;
        int size = variable.Type switch {
            "/Script/Niagara.NiagaraBool" or "/Script/Niagara.NiagaraInt32" or "/Script/Niagara.NiagaraFloat" => 4,
            "/Script/CoreUObject.Vector2f" or "/Script/Niagara.NiagaraID" => 8,
            "/Script/CoreUObject.Vector3f" or "/Script/Niagara.NiagaraPosition" => 12,
            "/Script/CoreUObject.Vector4f" or "/Script/CoreUObject.LinearColor" or "/Script/CoreUObject.Quat4f" => 16,
            "/Script/Niagara.NiagaraHalf" => 2, "/Script/Niagara.NiagaraHalfVector2" => 4,
            "/Script/Niagara.NiagaraHalfVector3" => 6, "/Script/Niagara.NiagaraHalfVector4" => 8,
            "/Script/Niagara.NiagaraMatrix" => 64,
            _ => 0
        };
        if (size != 0) return size;
        JObject saved = emitter["RendererBindings"] as JObject ?? new();
        var numeric = (saved["SortedParameterOffsets"] as JArray ?? new()).OfType<JObject>()
            .Where(entry => entry["type"] is JObject type && NiagaraTypeSource.Read(asset, type).UnderlyingType != 1).ToArray();
        JObject? entry = numeric.FirstOrDefault(candidate => NiagaraVariableSource.Same(asset, value, asset, candidate));
        if (entry is not null) {
            int offset = entry.Value<int>("offset");
            int end = numeric.Select(candidate => candidate.Value<int>("offset")).Where(candidate => candidate > offset)
                .DefaultIfEmpty(NumericParameterStoreSource.Bytes(saved["ParameterData"]).Length).Min();
            if (end > offset) return end - offset;
        }
        throw new ConversionException($"Unallocated renderer static value needs its struct-size metadata: {variable.Name} ({variable.Type})");
    }

    private static JObject Saved(SourceAsset asset, JObject emitter)
    {
        JObject store = emitter["RendererBindings"] as JObject ?? new();
        byte[] data = NumericParameterStoreSource.Bytes(store["ParameterData"]);
        JArray variables = new(), declarations = new();
        foreach (JObject variable in (store["SortedParameterOffsets"] as JArray ?? new()).OfType<JObject>()) {
            JObject type = variable["type"] as JObject
                ?? throw new ConversionException("Renderer parameter has no source type");
            var loaded = NiagaraTypeSource.Read(asset, type);
            if (loaded.UnderlyingType == 1) {
                declarations.Add(Declaration(RendererFieldBindings.Variable.From(asset, variable)
                    ?? throw new ConversionException("Renderer object parameter has no valid type"), 0));
                continue;
            }
            int offset = variable.Value<int?>("offset")
                ?? throw new ConversionException("Renderer parameter has no byte offset");
            if (offset < 0 || offset > data.Length)
                throw new ConversionException("Renderer parameter offset is outside its compiled store");
            variables.Add(new JObject {
                ["name"] = variable.Value<string>("name")
                    ?? throw new ConversionException("Renderer parameter has no name"),
                ["source_type"] = loaded.Path,
                ["lwc"] = NiagaraTypeSource.IsLwc(asset, type),
                ["offset"] = offset,
                ["flags"] = loaded.Flags
            });
        }
        foreach (JObject variable in variables) {
            int offset = variable.Value<int>("offset");
            int end = variables.OfType<JObject>().Select(value => value.Value<int>("offset")).Where(value => value > offset)
                .DefaultIfEmpty(data.Length).Min();
            JObject source = (store["SortedParameterOffsets"] as JArray ?? new()).OfType<JObject>()
                .First(value => value.Value<int>("offset") == offset && value["type"] is JObject type
                    && NiagaraTypeSource.Read(asset, type).UnderlyingType != 1);
            variable["underlying"] = NiagaraTypeSource.Read(asset, (JObject)source["type"]!).UnderlyingType;
            variable["bytes"] = end - offset;
            var actual = RendererFieldBindings.Variable.From(asset, source)
                ?? throw new ConversionException("Renderer numeric parameter has no valid type");
            declarations.Add(Declaration(actual, end - offset));
        }
        return new JObject { ["data"] = Convert.ToBase64String(data), ["variables"] = variables, ["compiled_layout"] = declarations };
    }
}
