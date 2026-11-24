using LumenFx.Compiler.Unreal;

namespace LumenFx.Compiler.Output;

public static partial class ResourcePackage
{
    public static void WriteBlueNoiseTextures(string sourceDirectory, string resourcesDirectory, string? oodleDataLibrary)
    {
        var source = new AssetLibrary(sourceDirectory);
        Dictionary<string, byte[]> files = new(StringComparer.Ordinal);
        Write("FastBlueNoise_scalar_128x128x64", "scalar");
        Write("FastBlueNoise_vec2_128x128x64", "vector");
        WriteNamespaceFiles(resourcesDirectory, "lumenfx", files);

        void Write(string name, string resource)
        {
            string path = "/Engine/EngineMaterials/" + name + "." + name;
            SourceAsset asset = source.Load(path);
            SourceObject texture = asset.ResolveObject(path, value => value.ClassName == "Texture2D");
            WriteNativeSourceTexture(asset, texture, path, "lumenfx", "blue_noise", files, oodleDataLibrary,
                NativeSourceTextureKind.EngineLookup, "textures/lumenfx/blue_noise/" + resource);
        }
    }
}
