using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.TextureSource;

internal static class TextureSourceCommand
{
    internal static void Export(string sourceDirectory, string package, string destination, string oodleLibrary)
    {
        var asset = new AssetLibrary(Path.GetFullPath(sourceDirectory)).Load(package);
        using var codec = oodleLibrary == "-" ? null : new OodleDataCodec(oodleLibrary);
        var image = TextureSourceReader.Read(asset, codec);
        string output = Path.GetFullPath(destination);
        JObject description = new() {
            ["format_version"] = 1,
            ["source"] = asset.Name,
            ["pixel_data"] = Path.GetFileName(output),
            ["levels"] = new JArray(image.Levels.Select(level => new JObject {
                ["block"] = level.Block, ["layer"] = level.Layer, ["mip"] = level.Mip,
                ["offset"] = level.Surface.Offset, ["width"] = level.Surface.Width,
                ["height"] = level.Surface.Height, ["slices"] = level.Surface.Slices,
                ["format"] = level.Surface.Format })) };
        Directory.CreateDirectory(Path.GetDirectoryName(output)!);
        File.WriteAllBytes(output, image.Pixels);
        File.WriteAllText(output + ".json", description.ToString(Formatting.Indented) + "\n");
    }
}
