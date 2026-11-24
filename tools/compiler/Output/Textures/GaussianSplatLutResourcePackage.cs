using LumenFx.Compiler.Unreal;

namespace LumenFx.Compiler.Output;

public static partial class ResourcePackage
{
    private const string GaussianSplatInverseFilmicTc = "textures/lumenfx/gaussian_splat/inverse_filmic_tc";
    private const string GaussianSplatInverseFilmicTcFull = "textures/lumenfx/gaussian_splat/inverse_filmic_tc_full";
    private const string GaussianSplatLutDirectory = "/GaussianSplat/LUTs/";

    public static void WriteGaussianSplatLutTextures(string sourceDirectory, string resourcesDirectory, string? oodleDataLibrary)
    {
        var source = new AssetLibrary(sourceDirectory);
        Dictionary<string, byte[]> files = new(StringComparer.Ordinal);
        WriteVolume("UE_InverseFilmicTC", GaussianSplatInverseFilmicTc);
        WriteVolume("UE_InverseFilmicTC_Full", GaussianSplatInverseFilmicTcFull);
        WriteNamespaceFiles(resourcesDirectory, "lumenfx", files);

        void WriteVolume(string objectName, string location)
        {
            string path = GaussianSplatLutDirectory + objectName + "." + objectName;
            SourceAsset asset = source.Load(path);
            SourceObject volume = asset.ResolveObject(path, value => value.ClassName == "VolumeTexture");
            WriteNativeSourceTexture(asset, volume, path, "lumenfx", "gaussian_splat", files, oodleDataLibrary,
                NativeSourceTextureKind.TextureVolume, location);
        }
    }
}
