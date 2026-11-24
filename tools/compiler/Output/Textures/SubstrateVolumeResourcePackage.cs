using LumenFx.Compiler.Unreal;

namespace LumenFx.Compiler.Output;

public static partial class ResourcePackage
{
    private const string SubstrateVolumePunctual = "textures/lumenfx/shading_energy/simple_volume_punctual";
    private const string SubstrateVolumeEnvironment = "textures/lumenfx/shading_energy/simple_volume_environment";
    private const string VolumePackageDirectory = "/Engine/EngineMaterials/Substrate/Volume/";

    public static void WriteShadingLookupTextures(string sourceDirectory, string resourcesDirectory, string? oodleDataLibrary)
    {
        var source = new AssetLibrary(sourceDirectory);
        Dictionary<string, byte[]> files = new(StringComparer.Ordinal);
        WriteVolume("SubstrateSimpleVolumeLUT3D", SubstrateVolumePunctual);
        WriteVolume("SubstrateSimpleVolumeEnvLUT3D", SubstrateVolumeEnvironment);
        const string skin = "/Engine/EngineMaterials/PreintegratedSkinBRDF.PreintegratedSkinBRDF";
        SourceAsset skinAsset = source.Load(skin);
        SourceObject skinTexture = skinAsset.ResolveObject(skin, value => value.ClassName == "Texture2D");
        WriteNativeSourceTexture(skinAsset, skinTexture, skin, "lumenfx", "lighting", files, oodleDataLibrary,
            NativeSourceTextureKind.EngineLookup, "textures/lumenfx/shading_energy/preintegrated_skin");
        WriteNamespaceFiles(resourcesDirectory, "lumenfx", files);

        void WriteVolume(string objectName, string location)
        {
            string path = VolumePackageDirectory + objectName + "." + objectName;
            SourceAsset asset = source.Load(path);
            SourceObject volume = asset.ResolveObject(path, value => value.ClassName == "VolumeTexture");
            WriteNativeSourceTexture(asset, volume, path, "lumenfx", "substrate", files, oodleDataLibrary,
                NativeSourceTextureKind.TextureVolume, location);
        }
    }
}
