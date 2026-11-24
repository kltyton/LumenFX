using LumenFx.Compiler.Unreal.Execution;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Parameters;

/** Transcribes the cooker's category-specific source and destination offsets without rematching names. */
internal static class CookedRapidBindings
{
    internal static JArray Read(SourceAsset asset, JObject runtime, JObject target, JToken source)
    {
        JArray bindings = new();
        int sourceSize = NumericParameterStoreSource.Bytes(source["data"]).Length;
        int targetSize = NumericParameterStoreSource.Bytes(target["data"]).Length;
        JObject[] savedSlots = ((runtime[ScriptRuntimeSource.ExecutionStore] as JObject)?["SortedParameterOffsets"] as JArray
            ?? new()).Cast<JObject>().ToArray();
        JArray compiledSlots = (JArray)target["variables"]!;
        foreach (JObject binding in runtime[ScriptRuntimeSource.RapidBindings] as JArray ?? new()) {
            JObject variable = binding["Parameter"] as JObject ?? throw new ConversionException("Cooked rapid binding has no variable");
            // UPROPERTY integer offsets omitted from tagged data retain their zero initializer.
            int from = binding.Value<int>("SrcOffset"), to = binding.Value<int>("DestOffset");
            string name = variable.Value<string>("name") ?? throw new ConversionException("Cooked rapid binding has no name");
            int slotIndex = Array.FindIndex(savedSlots, slot => slot.Value<int>("offset") == to
                && ScriptDataInterfaceBindings.Same(asset, slot, variable));
            if (slotIndex < 0) throw new ConversionException($"Cooked rapid binding has no destination slot: {name}");
            JObject targetSlot = (JObject)compiledSlots[slotIndex];
            string storage = targetSlot.Value<string>("storage")!;
            int size = targetSlot.Value<int>("bytes");
            int sourceLimit = storage == "numeric" ? sourceSize + 1 - size
                : ((JArray)source[storage == "interface" ? "interface_values" : "object_values"]!).Count;
            int targetLimit = storage == "numeric" ? targetSize + 1 - size
                : ((JArray)target[storage == "interface" ? "interface_values" : "object_values"]!).Count;
            if (from < 0 || from >= sourceLimit || to < 0 || to >= targetLimit)
                throw new ConversionException($"Cooked rapid binding exceeds its {storage} store: {name}");
            bindings.Add(new JObject { ["source"] = from, ["target"] = to, ["storage"] = storage, ["bytes"] = size });
        }
        return bindings;
    }
}
