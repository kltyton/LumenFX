using LumenFx.Compiler.Unreal.Execution;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Parameters;

internal static class NiagaraActorReference
{
    internal static string Read(SourceAsset asset, JObject properties)
    {
        string actor = ObjectPropertyReaderCompiler.ObjectPath(asset, properties["SourceActorRef"]);
        if (actor.Length != 0) return actor;

        JToken? legacy = properties["SourceActor_DEPRECATED"] ?? properties["SourceActor"];
        if (legacy is JObject wrapper && wrapper["Guid"] is JValue id) legacy = id;
        if (legacy is JValue { Type: JTokenType.String } value && Guid.TryParse(value.Value<string>(), out Guid guid))
            return guid == Guid.Empty ? "" : guid.ToString("D");
        return ObjectPropertyReaderCompiler.ObjectPath(asset, legacy);
    }
}
