using System.Text;
using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Audio;

internal sealed class NativeSoundRouting(NativeAssetTools tools)
{
    private readonly Dictionary<string, Task<JObject>> sounds = new(SourceNameOrder.Instance);
    private readonly Dictionary<string, Task<JObject>> classes = new(SourceNameOrder.Instance);

    internal Task<JObject> SoundAsync(string path) => Describe(sounds, path, "sound");

    internal Task<JObject> SoundClassAsync(string path) => Describe(classes, path, "sound_class");

    private Task<JObject> Describe(Dictionary<string, Task<JObject>> cache, string path, string operation)
    {
        if (!cache.TryGetValue(path, out var result)) {
            result = NativeAssetExchange.ExportAsync(path, tools, operation, ".json",
                bytes => JObject.Parse(Encoding.UTF8.GetString(bytes)), operation: operation);
            cache.Add(path, result);
        }
        return result;
    }
}
