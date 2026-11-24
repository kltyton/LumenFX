using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;
using LumenFx.Compiler.Unreal;
using LumenFx.Compiler.Unreal.Execution;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Output;

public static partial class ResourcePackage
{
    private static string WriteOriginalTexture(string source, TextureSettings settings, string ns, string effect,
        OriginalTextureSources textures, Dictionary<string, byte[]> files)
    {
        (string path, string id) = textures.Resolve(source);
        string location = $"textures/lumenfx/{effect}/{id}.png";
        byte[] bytes = File.ReadAllBytes(path);
        if (bytes.Length < 33 || !bytes.AsSpan(0, 8).SequenceEqual(new byte[] { 137, 80, 78, 71, 13, 10, 26, 10 })
            || BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(8, 4)) != 13
            || !bytes.AsSpan(12, 4).SequenceEqual("IHDR"u8))
            throw new ConversionException($"Invalid original PNG: {path}");
        int width = BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(16, 4));
        int height = BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(20, 4));
        if (width != settings.Width || height != settings.Height)
            throw new ConversionException($"Original PNG for {source} is {width}x{height}; expected {settings.Width}x{settings.Height}: {path}");
        if (files.TryGetValue(location, out byte[]? existing) && !existing.AsSpan().SequenceEqual(bytes))
            throw new ConversionException($"Texture output collision: {location}");
        files[location] = bytes;
        string metadataLocation = location[..^4] + ".lftex.json";
        JObject textureMetadata = settings.ToJson(Convert.ToHexStringLower(SHA256.HashData(bytes)));
        textureMetadata["mip_data"] = ns + ":" + location[..^4] + ".lftx";
        byte[] metadata = Encoding.UTF8.GetBytes(textureMetadata.ToString(Formatting.Indented) + "\n");
        if (files.TryGetValue(metadataLocation, out byte[]? existingMetadata) && !existingMetadata.AsSpan().SequenceEqual(metadata))
            throw new ConversionException($"Texture metadata collision: {metadataLocation}");
        files[metadataLocation] = metadata;
        return ns + ":" + location;
    }

    private static void BindInterfaceTextures(JObject definition, AssetLibrary? library, string ns, string effect,
        OriginalTextureSources textures, Dictionary<string, byte[]> files, string? oodleDataLibrary)
    {
        Dictionary<string, string> converted = new(StringComparer.Ordinal);
        foreach (JObject binding in TextureInterfaces(definition))
            foreach (string field in new[] { "texture", "user_texture" }) {
                string sourceField = "source_" + field;
                if (binding[sourceField] is not JToken value) continue;
                if (value.Type == JTokenType.Null) binding[field] = JValue.CreateNull();
                else {
                    string source = value.Value<string>() ?? throw new ConversionException("Texture interface source is not an object path");
                    if (!converted.TryGetValue(source, out string? id)) {
                        id = WriteTextureObject(source, library, ns, effect, textures, files, oodleDataLibrary);
                        converted.Add(source, id);
                    }
                    binding[field] = id;
                }
                if (binding.Value<bool?>("array") == true || binding.Value<bool?>("cube") == true
                        || binding.Value<bool?>("volume") == true)
                    binding[field == "texture" ? "source_object" : "source_user_object"] = value.DeepClone();
                binding.Remove(sourceField);
            }
    }

    private static string WriteTextureObject(string source, AssetLibrary? library, string ns, string effect,
        OriginalTextureSources textures, Dictionary<string, byte[]> files, string? oodleDataLibrary)
    {
        if (library is null) throw new ConversionException($"Texture object requires its source asset library: {source}");
        SourceAsset asset = library.Load(source);
        SourceObject texture = asset.ResolveObject(source, value => value.ClassName is "Texture2D" or "TextureRenderTarget2D"
            or "TextureLightProfile" or "Texture2DArray" or "TextureRenderTarget2DArray" or "TextureCube"
            or "TextureRenderTargetCube" or "TextureCubeArray" or "VolumeTexture" or "TextureRenderTargetVolume" or "CurveLinearColorAtlas");
        if (texture.ClassName is "TextureRenderTarget2D" or "TextureRenderTarget2DArray" or "TextureRenderTargetCube" or "TextureRenderTargetVolume") {
            string suffix = texture.ClassName switch {
                "TextureRenderTarget2DArray" => ".lfart.json", "TextureRenderTargetCube" => ".lfcrt.json",
                "TextureRenderTargetVolume" => ".lfvrt.json", _ => ".lfrt.json"
            };
            string location = "lumenfx/texture_targets/texture_" + Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(source)))[..16] + suffix;
            byte[] bytes = Encoding.UTF8.GetBytes(RenderTargetTextureCompiler.Compile(texture, source).ToString(Formatting.Indented) + "\n");
            if (files.TryGetValue(location, out byte[]? existing) && !existing.AsSpan().SequenceEqual(bytes))
                throw new ConversionException($"Render target resource collision: {location}");
            files[location] = bytes;
            return ns + ":" + location;
        }
        return texture.ClassName switch {
            "TextureLightProfile" => WriteNativeSourceTexture(asset, texture, source, ns, effect, files, oodleDataLibrary),
            "Texture2DArray" => WriteNativeSourceTexture(asset, texture, source, ns, effect, files, oodleDataLibrary, NativeSourceTextureKind.TextureArray),
            "TextureCube" => WriteNativeSourceTexture(asset, texture, source, ns, effect, files, oodleDataLibrary, NativeSourceTextureKind.TextureCube),
            "TextureCubeArray" => WriteNativeSourceTexture(asset, texture, source, ns, effect, files, oodleDataLibrary, NativeSourceTextureKind.TextureCubeArray),
            "VolumeTexture" => WriteNativeSourceTexture(asset, texture, source, ns, effect, files, oodleDataLibrary, NativeSourceTextureKind.TextureVolume),
            "CurveLinearColorAtlas" => WriteNativeSourceTexture(asset, texture, source, ns, effect, files, oodleDataLibrary,
                NativeSourceTextureKind.CurveAtlas),
            _ => WriteOriginalTexture(source, Textures.Read(asset, source), ns, effect, textures, files)
        };
    }

    private static IEnumerable<JObject> TextureInterfaces(JObject definition)
    {
        if (definition["niagara"] is not JObject niagara) yield break;
        foreach (JObject value in niagara.Descendants().OfType<JObject>()) {
            if (value["texture_2d_defaults"] is JObject texture) yield return texture;
            if (value["texture_array_defaults"] is JObject array) yield return array;
            if (value["texture_cube_defaults"] is JObject cube) yield return cube;
            if (value["texture_volume_defaults"] is JObject volume) yield return volume;
            if (value["render_target_2d_defaults"] is JObject target) yield return target;
            if (value["render_target_array_defaults"] is JObject arrayTarget) yield return arrayTarget;
            if (value["render_target_volume_defaults"] is JObject volumeTarget) yield return volumeTarget;
            if (value["render_target_cube_defaults"] is JObject cubeTarget) yield return cubeTarget;
        }
        foreach (JObject instance in niagara["render_target_instances"] as JArray ?? new JArray())
            yield return instance["data"] as JObject ?? throw new ConversionException("Render target instance has no source definition");
        foreach (string usage in new[] { "system_spawn", "system_update" })
            foreach (JObject binding in CpuTextureInterfaces(niagara[usage] as JObject)) yield return binding;
        foreach (JObject emitter in (niagara["emitters"] as JArray ?? new JArray()).OfType<JObject>()) {
            foreach (JObject renderer in (emitter["renderers"] as JArray ?? new JArray()).OfType<JObject>()) {
                var template = (renderer["component"] as JObject)?["template"] as JObject;
                if (template?["lumenfx_ies"] is JObject profile) yield return profile;
                if (template?["lumenfx_rect_source"] is JObject source) yield return source;
            }
            if (emitter["gpu_program"]?["interfaces"] is JArray gpu)
                foreach (JObject binding in gpu.OfType<JObject>())
                    if (binding.Value<string>("class") == "NiagaraDataInterfaceTexture")
                        yield return binding["texture_2d"] as JObject ?? throw new ConversionException("GPU texture has no object binding");
                    else if (binding.Value<string>("class") == TextureDataInterfaceCompiler.ArrayClass)
                        yield return binding["texture_array"] as JObject ?? throw new ConversionException("GPU texture array has no object binding");
                    else if (binding.Value<string>("class") == TextureDataInterfaceCompiler.CubeClass)
                        yield return binding["texture_cube"] as JObject ?? throw new ConversionException("GPU cube texture has no object binding");
                    else if (binding.Value<string>("class") == TextureDataInterfaceCompiler.VolumeClass)
                        yield return binding["texture_volume"] as JObject ?? throw new ConversionException("GPU volume texture has no object binding");
                    else if (binding.Value<string>("class") == "NiagaraDataInterfaceRenderTarget2D")
                        yield return binding["render_target_2d"] as JObject ?? throw new ConversionException("GPU render target has no object binding");
                    else if (binding.Value<string>("class") == RenderTarget2DDataInterfaceCompiler.ArrayClass)
                        yield return binding["render_target_array"] as JObject ?? throw new ConversionException("GPU render target array has no object binding");
                    else if (binding.Value<string>("class") == RenderTarget2DDataInterfaceCompiler.VolumeClass)
                        yield return binding["render_target_volume"] as JObject ?? throw new ConversionException("GPU volume target has no object binding");
                    else if (binding.Value<string>("class") == RenderTarget2DDataInterfaceCompiler.CubeClass)
                        yield return binding["render_target_cube"] as JObject ?? throw new ConversionException("GPU cube target has no object binding");
            foreach (string usage in new[] { "spawn", "update" })
                foreach (JObject binding in CpuTextureInterfaces(emitter[usage] as JObject)) yield return binding;
            foreach (JObject handler in (emitter["event_handlers"] as JArray ?? new JArray()).OfType<JObject>())
                foreach (JObject binding in CpuTextureInterfaces(handler["program"] as JObject)) yield return binding;
        }
    }

    private static IEnumerable<JObject> CpuTextureInterfaces(JObject? program)
    {
        foreach (JObject binding in (program?["external"] as JArray ?? new JArray()).OfType<JObject>())
            if (binding.Value<string>("interface_type") is "texture_2d" or "texture_array" or "texture_cube" or "texture_volume"
                or "render_target_2d" or "render_target_array" or "render_target_volume" or "render_target_cube")
                yield return binding["interface_data"] as JObject ?? throw new ConversionException("CPU texture has no object binding");
    }
}
