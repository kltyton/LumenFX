using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using LumenFx.Compiler.Unreal;
using LumenFx.Compiler.Unreal.Geometry.MeshBuild;
using LumenFx.Compiler.Unreal.Geometry.MeshDescription;
using LumenFx.Compiler.Unreal.Geometry.SkinnedMesh;
using LumenFx.Compiler.Unreal.Audio;
using LumenFx.Compiler.Unreal.SimulationCache;
using LumenFx.Compiler.Unreal.Execution;
using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Output;

public static partial class ResourcePackage
{
    [GeneratedRegex("^[a-z0-9_]+:[a-z0-9_/.-]+$")]
    private static partial Regex IdentifierPattern();

    public static void Write(ImportedEffect effect, string identifier, string texturesDirectory, string resourcesDirectory, AssetLibrary? vectorFields = null)
    {
        if (MeshEntries(effect.Definition).Any(value => value.Value<string>("asset_source") is not null))
            throw new ConversionException("Source mesh assets require the Niagara mesh build tool inputs");
        if (NiagaraMeshEntries(effect.Definition, "skeletal_mesh_reader", "skeletal_mesh_parameter")
                .Any(value => value.Value<string>("asset_source") is not null))
            throw new ConversionException("Source skeletal mesh assets require native mesh export inputs");
        if (MeshRendererInfoCompiler.PendingSources(effect.Definition).Any())
            throw new ConversionException("Source mesh renderer information requires the offline mesh build inputs");
        if (IslandDefinitions(effect.Definition).Any())
            throw new ConversionException("Data channel handler systems require the Niagara dependency package");
        if (NiagaraMeshEntries(effect.Definition, "audio_player", "audio_component").Any(value => value.Value<string>("sound_source") is not null))
            throw new ConversionException("Source audio requires the native Unreal wave exporter");
        if (NiagaraMeshEntries(effect.Definition, "sim_cache_reader", "sim_cache_asset").Any(value => value.Value<string>("asset_source") is { Length: > 0 }))
            throw new ConversionException("Source simulation caches require the native Unreal simulation cache exporter");
        if (NiagaraMeshEntries(effect.Definition, "sparse_volume_texture", "sparse_volume_asset").Any(value => value.Value<string>("asset_source") is { Length: > 0 })
                || effect.Materials.Values.Any(material => material.SparseVolumeTextures.Count != 0))
            throw new ConversionException("Source sparse volumes require the native Unreal sparse volume exporter");
        if (NiagaraMeshEntries(effect.Definition, "volume_cache").Any(value => value.Value<string>("asset_source") is { Length: > 0 }))
            throw new ConversionException("Source Volume Cache assets require the native Unreal OpenVDB exporter");
        if (GroomSources(effect.Definition).Any())
            throw new ConversionException("Source grooms require the native Unreal Groom exporter");
        if (NiagaraMeshEntries(effect.Definition, "gaussian_splat_reader")
                .Any(value => value.Value<string>("asset_source") is { Length: > 0 } || value.Value<string>("asset") is { Length: > 0 }))
            throw new ConversionException("Source Gaussian Splats require the native Unreal mesh description exporter");
        WriteCore(effect, identifier, texturesDirectory, resourcesDirectory, vectorFields, new Dictionary<string, byte[]>(StringComparer.Ordinal));
    }

    internal static async Task WriteAsync(ImportedEffect effect, string identifier, string texturesDirectory,
        string resourcesDirectory, AssetLibrary? vectorFields, AssetLibrary library, MeshBuildTools? meshTools, string? oodleDataLibrary,
        IReadOnlyDictionary<string, string>? channelSystems = null, NativeAssetTools? audioTools = null,
        Func<string, Task<NativeSimulationCache>>? simCacheExporter = null,
        Func<string, Task<byte[]>>? sparseVolumeExporter = null, Func<string, Task<byte[]>>? volumeCacheExporter = null,
        Func<string, Task<byte[]>>? groomExporter = null)
    {
        if (!IdentifierPattern().IsMatch(identifier) || identifier.Contains("..", StringComparison.Ordinal))
            throw new ConversionException($"Invalid effect identifier {identifier}");
        JObject definition = (JObject)effect.Definition.DeepClone();
        foreach (var island in IslandDefinitions(definition)) {
            var systemSources = (JArray)island["system_sources"]!;
            island["systems"] = new JArray(systemSources.Values<string>().Select(path =>
                channelSystems is not null && channelSystems.TryGetValue(path!, out string? system) ? system
                    : throw new ConversionException($"Missing Niagara data channel handler package: {path}")));
            island.Remove("system_sources");
        }
        Dictionary<string, byte[]> files = new(StringComparer.Ordinal);
        Dictionary<string, string> sources = new(StringComparer.Ordinal);
        Dictionary<string, MeshSourceBounds> meshBounds = new(StringComparer.Ordinal);
        string ns = identifier.Split(':')[0];
        foreach (JObject entry in MeshEntries(definition)) {
            if (entry.Value<string>("asset_source") is not { } source) continue;
            if (!sources.TryGetValue(source, out string? meshId)) {
                meshId = MeshIdentifier(ns, source);
                if (sources.Values.Contains(meshId, StringComparer.Ordinal)) throw new ConversionException("Source mesh identifiers collide");
                sources.Add(source, meshId);
                StaticMeshSourceData decoded = effect.SourceMeshes.TryGetValue(source, out var cached)
                    ? cached
                    : StaticMeshSourceCompiler.Compile(library.Load(source), source, oodleDataLibrary);
                IReadOnlyDictionary<string, byte[]> built;
                if (meshTools is not null) {
                    built = await StaticMeshRenderPackage.CollectAsync(decoded, meshId, meshTools);
                } else if (StaticMeshRenderPackage.CanCollectWithoutBuildTools(decoded)) {
                    built = StaticMeshRenderPackage.CollectFromDecoded(decoded, meshId);
                } else {
                    throw new ConversionException($"Missing explicit mesh build tools for {source}");
                }
                meshBounds.Add(source, ExtendedMeshBounds(meshId, built));
                foreach ((string path, byte[] bytes) in built)
                    if (!files.TryAdd(path, bytes)) throw new ConversionException($"Mesh resource output collision: {path}");
            }
            entry["asset"] = meshId;
            entry.Remove("asset_source");
        }
        var skeletalMeshes = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var entry in NiagaraMeshEntries(definition, "skeletal_mesh_reader", "skeletal_mesh_parameter")) {
            if (entry.Value<string>("asset_source") is not { } source) continue;
            if (meshTools is null) throw new ConversionException($"Missing native skeletal mesh export inputs for {source}");
            if (!skeletalMeshes.TryGetValue(source, out string? meshId)) {
                string key = "unreal/skeletal_mesh_" + Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(source)))[..16];
                meshId = ns + ":" + key;
                var package = await NativeSkeletalMesh.CollectAsync(source, meshTools).ConfigureAwait(false);
                if (!files.TryAdd("lumenfx/skinned_meshes/" + key + ".lfsk", package.Header))
                    throw new ConversionException($"Skeletal mesh resource output collision: {source}");
                foreach (var lod in package.StreamedLods) {
                    if (!files.TryAdd($"lumenfx/skinned_meshes/{key}.lod{lod.Key}.lfsl", lod.Value))
                        throw new ConversionException($"Streamed skeletal mesh LOD resource output collision: {source} LOD {lod.Key}");
                }
                skeletalMeshes.Add(source, meshId);
            }
            entry["asset"] = meshId;
            entry.Remove("asset_source");
        }
        await CompleteAudioAsync(definition, ns, files, library, audioTools).ConfigureAwait(false);
        await CompleteSimulationCachesAsync(definition, ns, files, simCacheExporter).ConfigureAwait(false);
        await CompleteSparseVolumesAsync(definition, effect.Materials.Values, ns, files, sparseVolumeExporter).ConfigureAwait(false);
        await CompleteVolumeCachesAsync(definition, ns, files, volumeCacheExporter).ConfigureAwait(false);
        await CompleteGroomsAsync(definition, ns, files, groomExporter).ConfigureAwait(false);
        CompleteGaussianSplats(definition, ns, files, library);
        await CompleteMeshRendererInformationAsync(effect, definition, library, meshTools, oodleDataLibrary, ns, meshBounds);
        WriteCore(effect with { Definition = definition }, identifier, texturesDirectory, resourcesDirectory, vectorFields, files, library, oodleDataLibrary);
    }

    private static async Task CompleteSparseVolumesAsync(JObject definition, IEnumerable<CompiledMaterial> materials,
        string ns, Dictionary<string, byte[]> files, Func<string, Task<byte[]>>? exporter)
    {
        var sources = new HashSet<string>(SourceNameOrder.Instance);
        foreach (var entry in NiagaraMeshEntries(definition, "sparse_volume_texture", "sparse_volume_asset")) {
            string? source = entry.Value<string>("asset_source");
            entry.Remove("asset_source");
            if (string.IsNullOrEmpty(source)) continue;
            sources.Add(source);
            entry["asset"] = ns + ":" + SparseVolumeKey(source);
        }
        foreach (var material in materials)
            sources.UnionWith(material.SparseVolumeTextures.Values);
        foreach (var source in sources) {
            if (exporter is null) throw new ConversionException($"Missing explicit Unreal sparse volume export tools for {source}");
            if (!files.TryAdd("lumenfx/sparse_volumes/" + SparseVolumeKey(source) + ".lfst", await exporter(source).ConfigureAwait(false)))
                throw new ConversionException($"Sparse volume resource output collision: {source}");
        }
    }

    private static string SparseVolumeKey(string source)
        => "unreal/svt_" + Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(source.ToLowerInvariant())))[..24];

    private static async Task CompleteVolumeCachesAsync(JObject definition, string ns, Dictionary<string, byte[]> files,
        Func<string, Task<byte[]>>? exporter)
    {
        var exported = new HashSet<string>(SourceNameOrder.Instance);
        foreach (var entry in NiagaraMeshEntries(definition, "volume_cache")) {
            string? source = entry.Value<string>("asset_source");
            entry.Remove("asset_source");
            if (string.IsNullOrEmpty(source)) continue;
            string key = "unreal/openvdb_" + Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(SourceNameOrder.Canonical(source))))[..24];
            entry["asset"] = ns + ":" + key;
            if (!exported.Add(source)) continue;
            if (exporter is null) throw new ConversionException($"Missing explicit Unreal OpenVDB export tools for {source}");
            if (!files.TryAdd("lumenfx/volume_caches/" + key + ".lfvc", await exporter(source).ConfigureAwait(false)))
                throw new ConversionException($"Volume Cache resource output collision: {source}");
        }
    }

    private static async Task CompleteAudioAsync(JObject definition, string ns, Dictionary<string, byte[]> files,
        AssetLibrary library, NativeAssetTools? tools)
    {
        var waves = new Dictionary<string, SoundCueCompiler.WaveAsset>(SourceNameOrder.Instance);
        var cues = new Dictionary<string, JObject>(SourceNameOrder.Instance);
        var routing = tools is null ? null : new NativeSoundRouting(tools);
        foreach (var entry in NiagaraMeshEntries(definition, "audio_player", "audio_component")) {
            if (entry.Value<string>("sound_source") is not { } source) continue;
            if (routing is null) throw new ConversionException($"Missing explicit Unreal audio export tools for {source}");
            var owner = library.Load(source);
            var sound = owner.ResolveObject(source, value => value.ClassName is "SoundWave" or "SoundCue");
            entry["sound_routing"] = (await routing.SoundAsync(owner.ObjectPath(sound.Index)).ConfigureAwait(false)).DeepClone();
            string? attenuation = entry.Value<string>("attenuation_source");
            if (attenuation is null) attenuation = ObjectPropertyReaderCompiler.ObjectPath(owner, sound.Properties["AttenuationSettings"]);
            if (!entry.ContainsKey("attenuation") && !string.IsNullOrEmpty(attenuation))
                entry["attenuation"] = AudioAcousticsCompiler.AttenuationAsset(attenuation, library);
            if (entry.Value<string>("concurrency_source") is { Length: > 0 } concurrency)
                entry["concurrency"] = AudioAcousticsCompiler.ConcurrencyAsset(concurrency, library);
            entry["source_priority"] = sound.Properties.Value<float?>("Priority") ?? 1f;
            entry["asset_concurrency"] = AudioAcousticsCompiler.ConcurrencySet(owner, sound, library);
            if (entry["component_concurrency_sources"] is JArray component) {
                entry["component_concurrency"] = new JArray(component.Values<string>().OfType<string>()
                    .Distinct(SourceNameOrder.Instance).Select(path => AudioAcousticsCompiler.ConcurrencyAsset(path, library)));
                entry.Remove("component_concurrency_sources");
            }
            entry.Remove("attenuation_source");
            entry.Remove("concurrency_source");
            if (entry["default_wave_sources"] is JObject defaultSources) {
                var defaults = new JObject();
                foreach (var parameter in defaultSources.Properties()) {
                    var wave = await Wave(parameter.Value.Value<string>()!).ConfigureAwait(false);
                    defaults[parameter.Name] = wave.Describe(wave.Data.Looping);
                }
                entry["default_wave_parameters"] = defaults;
                entry.Remove("default_wave_sources");
            }
            if (sound.ClassName == "SoundWave") {
                var wave = await Wave(source).ConfigureAwait(false);
                entry["sound"] = wave.Id;
                entry["looping"] = wave.Data.Looping;
                entry["source_volume"] = wave.Data.Volume;
                entry["source_pitch"] = wave.Data.Pitch;
            } else {
                if (!cues.TryGetValue(source, out var cue)) {
                    cue = await SoundCueCompiler.CompileAsync(owner, sound, Wave, routing).ConfigureAwait(false);
                    cues.Add(source, cue);
                }
                entry["sound"] = ns + ":lumenfx/audio/cues/cue_" + AudioKey(source);
                entry["cue"] = cue.DeepClone();
                entry["source_volume"] = cue.Value<float>("volume");
                entry["source_pitch"] = cue.Value<float>("pitch");
            }
            entry.Remove("sound_source");
        }

        async Task<SoundCueCompiler.WaveAsset> Wave(string source) {
            if (waves.TryGetValue(source, out var existing)) return existing;
            if (tools is null || routing is null) throw new ConversionException($"Missing explicit Unreal audio export tools for {source}");
            string key = "lumenfx/audio/waves/wave_" + AudioKey(source);
            var data = await NativeAudioWave.CollectAsync(source, tools).ConfigureAwait(false);
            var description = await routing.SoundAsync(source).ConfigureAwait(false);
            var wave = new SoundCueCompiler.WaveAsset(ns + ":" + key, data, description.Value<string>("submix"),
                description["submix_graph"] as JObject);
            if (!files.TryAdd(key + ".lfaw", data.Bytes)) throw new ConversionException($"Sound wave output collision: {source}");
            waves.Add(source, wave);
            return wave;
        }

        static string AudioKey(string source) => Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(source.ToLowerInvariant())))[..24];
    }

    internal static IEnumerable<string> SimulationCacheSources(JObject execution) =>
        execution.Descendants().OfType<JProperty>().Where(property => property.Name is "sim_cache_reader" or "sim_cache_asset")
            .Select(property => property.Value).OfType<JObject>().Select(value => value.Value<string>("asset_source"))
            .OfType<string>().Where(path => !string.IsNullOrEmpty(path)).Distinct(SourceNameOrder.Instance);

    private static async Task CompleteSimulationCachesAsync(JObject definition, string ns, Dictionary<string, byte[]> files,
        Func<string, Task<NativeSimulationCache>>? exporter)
    {
        var ids = new Dictionary<string, string>(SourceNameOrder.Instance);
        foreach (var entry in NiagaraMeshEntries(definition, "sim_cache_reader", "sim_cache_asset")) {
            string? source = entry.Value<string>("asset_source");
            bool allowInvalid = entry.Value<bool?>("allow_invalid_cache") ?? false;
            entry.Remove("asset_source");
            entry.Remove("allow_invalid_cache");
            if (string.IsNullOrEmpty(source)) continue;
            if (exporter is null) throw new ConversionException($"Missing explicit Unreal simulation cache export tools for {source}");
            if (!ids.TryGetValue(source, out string? id)) {
                string key = "unreal/cache_" + Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(source.ToLowerInvariant())))[..24];
                id = ns + ":" + key;
                var cache = await exporter(source).ConfigureAwait(false);
                if (!cache.IsValid) {
                    if (!allowInvalid) throw new ConversionException($"Invalid Unreal simulation cache: {source}");
                    continue;
                }
                if (!files.TryAdd("lumenfx/sim_caches/" + key + ".lfsc", cache.Bytes))
                    throw new ConversionException($"Simulation cache resource output collision: {source}");
                ids.Add(source, id);
            }
            entry["asset"] = id;
        }
    }

    private static IEnumerable<string> GroomSources(JObject definition) => NiagaraMeshEntries(definition, "hair_strands", "interface_data")
        .Where(entry => entry.ContainsKey("source_actor") && entry.ContainsKey("default_source"))
        .SelectMany(entry => new[] { entry.Value<string>("default_source"), entry.Value<string>("actor_groom") })
        .OfType<string>().Where(source => source.Length != 0).Distinct(SourceNameOrder.Instance);

    private static async Task CompleteGroomsAsync(JObject definition, string ns, Dictionary<string, byte[]> files,
        Func<string, Task<byte[]>>? exporter)
    {
        var assets = new JObject();
        foreach (string source in GroomSources(definition)) {
            if (exporter is null) throw new ConversionException($"Missing explicit Unreal groom export tools for {source}");
            string key = "unreal/groom_" + Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(source.ToLowerInvariant())))[..24];
            var bytes = await exporter(source).ConfigureAwait(false);
            if (!files.TryAdd("lumenfx/grooms/" + key + ".lfgr", bytes))
                throw new ConversionException($"Groom resource output collision: {source}");
            assets[source] = ns + ":" + key;
        }
        if (assets.Count != 0) definition["groom_assets"] = assets;
    }

    private static void CompleteGaussianSplats(JObject definition, string ns, Dictionary<string, byte[]> files, AssetLibrary library)
    {
        foreach (var entry in NiagaraMeshEntries(definition, "gaussian_splat_reader")) {
            string? source = entry.Value<string>("asset_source");
            entry.Remove("asset_source");
            if (string.IsNullOrEmpty(source)) continue;
            var splats = library.GaussianSplats
                ?? throw new ConversionException($"Missing explicit Unreal Gaussian Splat export tool for {source}");
            string key = splats.Read(source);
            entry["asset"] = ns + ":" + key;
        }
        if (library.GaussianSplats is not { } exporter) return;
        foreach (var (key, bytes) in exporter.ExportedAssets)
            if (!files.TryAdd("lumenfx/gaussian_splats/" + key + ".lfgs", bytes))
                throw new ConversionException($"Gaussian Splat resource output collision: {key}");
    }

    private static IEnumerable<JObject> MeshEntries(JObject definition) => ((JArray?)definition["emitters"] ?? new JArray())
        .OfType<JObject>().Select(emitter => emitter["mesh"]).OfType<JObject>()
        .SelectMany(mesh => (mesh["entries"] as JArray ?? new JArray()).OfType<JObject>()
            .Concat((mesh["mesh_array"]?["values"] as JObject ?? new JObject()).Properties()
                .SelectMany(value => ((JArray)value.Value).OfType<JObject>())))
        .Concat(((JObject?)definition["renderer_objects"] ?? new JObject()).Properties().Select(value => value.Value)
            .OfType<JObject>().Where(value => value.Value<string>("type") == "mesh"))
        .Concat(NiagaraMeshEntries(definition, "static_mesh_reader", "static_mesh_parameter"))
        .Concat(NiagaraMeshEntries(definition, "lumenfx_static_mesh")
            .SelectMany(mesh => (mesh["entries"] as JArray ?? new JArray()).OfType<JObject>()));

    private static IEnumerable<JObject> NiagaraMeshEntries(JObject definition, params string[] names) =>
        (definition["niagara"] as JObject)?.Descendants().OfType<JProperty>()
            .Where(property => names.Contains(property.Name, StringComparer.Ordinal)).Select(property => property.Value).OfType<JObject>()
            ?? Enumerable.Empty<JObject>();

    private static IEnumerable<JObject> IslandDefinitions(JObject definition) =>
        (definition["niagara"] as JObject)?.Descendants().OfType<JProperty>()
            .Where(property => property.Name is "islands" or "gameplay_burst" or "access_context")
            .Select(property => property.Value).OfType<JObject>()
            .Where(container => container["system_sources"] is JArray) ?? Enumerable.Empty<JObject>();

    internal static IEnumerable<string> ChannelSystems(JObject definition) =>
        IslandDefinitions(definition).SelectMany(island => island["system_sources"]!.Values<string>()).OfType<string>();

    private static void WriteCore(ImportedEffect effect, string identifier, string texturesDirectory, string resourcesDirectory,
        AssetLibrary? vectorFields, Dictionary<string, byte[]> files, AssetLibrary? textureAssets = null, string? oodleDataLibrary = null)
    {
        if (!IdentifierPattern().IsMatch(identifier) || identifier.Contains("..", StringComparison.Ordinal))
            throw new ConversionException($"Invalid effect identifier {identifier}");
        string[] parts = identifier.Split(':');
        string ns = parts[0], name = parts[1];
        JObject definition = (JObject)effect.Definition.DeepClone();
        foreach (JObject emitter in (JArray)definition["emitters"]!)
        {
            if (emitter["noise"] is JObject noise) BindField(noise, ns, vectorFields, files);
        }
        if (definition["niagara"] is JObject niagara)
        {
            if (niagara["emitters"] is JArray niagaraEmitters)
            {
                foreach (JObject emitter in niagaraEmitters.OfType<JObject>())
                {
                    if (emitter["gpu_program"] is JObject gpu && gpu["hlsl"] is JValue source)
                    {
                        string emitterName = emitter.Value<string>("name")!;
                        if (!Regex.IsMatch(emitterName, "^[a-z0-9_]+$"))
                            throw new ConversionException($"Invalid GPU emitter identifier {emitterName}");
                        string location = $"lumenfx/gpu/{name}/{emitterName}.ush";
                        byte[] hlslBytes = Encoding.UTF8.GetBytes(source.Value<string>()!);
                        files[location] = hlslBytes;
                        gpu.Remove("hlsl");
                        gpu["hlsl_source"] = ns + ":" + location;
                        gpu["shader_source_sha256"] = Convert.ToHexStringLower(SHA256.HashData(hlslBytes));
                        string programKey = identifier + "/" + emitterName;
                        gpu["shader_pipeline"] = "niagara_" + Convert.ToHexStringLower(
                            SHA256.HashData(Encoding.UTF8.GetBytes(programKey)));
                        if (gpu["interfaces"] is JArray interfaces)
                            foreach (JObject binding in interfaces.OfType<JObject>())
                                BindField(binding, ns, vectorFields, files);
                    }
                    foreach (string usage in new[] { "spawn", "update" })
                    {
                        if (emitter[usage] is not JObject program || program["external"] is not JArray external) continue;
                        foreach (JObject binding in external.OfType<JObject>())
                            BindField(binding, ns, vectorFields, files);
                    }
                    if (emitter["event_handlers"] is JArray handlers)
                        foreach (JObject handler in handlers.OfType<JObject>())
                            if (handler["program"] is JObject program && program["external"] is JArray external)
                                foreach (JObject binding in external.OfType<JObject>())
                                    BindField(binding, ns, vectorFields, files);
                }
            }
            foreach (string usage in new[] { "system_spawn", "system_update" })
            {
                if (niagara[usage] is not JObject program || program["external"] is not JArray external) continue;
                foreach (JObject binding in external.OfType<JObject>())
                    BindField(binding, ns, vectorFields, files);
            }
        }
        var textures = new OriginalTextureSources(texturesDirectory);
        BindInterfaceTextures(definition, textureAssets ?? vectorFields, ns, name, textures, files, oodleDataLibrary);
        BindVirtualTextureAssets(definition, effect.Materials.Values, textureAssets ?? vectorFields, ns, name, files);
        JObject materialDefinitions = new();
        Dictionary<(string Source, bool Native), string> materialTextureObjects = new();
        string MaterialTextureObject(string source, bool native = false) {
            if (!materialTextureObjects.TryGetValue((source, native), out string? value)) {
                if (native) {
                    AssetLibrary library = textureAssets ?? vectorFields ?? throw new ConversionException($"Native texture requires its source library: {source}");
                    SourceAsset owner = library.Load(source);
                    SourceObject texture = owner.ResolveObject(source, value => value.ClassName == "Texture2D");
                    value = WriteNativeSourceTexture(owner, texture, source, ns, name, files, oodleDataLibrary, NativeSourceTextureKind.CurveExposure);
                } else value = WriteTextureObject(source, textureAssets ?? vectorFields, ns, name, textures, files, oodleDataLibrary);
                materialTextureObjects.Add((source, native), value);
            }
            return value;
        }
        void BindStoreObject(JToken? entry) {
            if (entry is not JObject value) return;
            if (value["vector_field_defaults"] is JObject vectorField)
                BindField(vectorField, ns, vectorFields ?? textureAssets, files, true);
            string? type = value.Value<string>("class");
            if (type is "Texture2D" or "TextureRenderTarget2D" or "TextureLightProfile" or "Texture2DArray"
                or "TextureRenderTarget2DArray" or "TextureCube" or "TextureRenderTargetCube" or "TextureCubeArray" or "VolumeTexture" or "TextureRenderTargetVolume")
            {
                string path = value.Value<string>("path")
                    ?? throw new ConversionException("Texture object reference has no source path");
                if (definition["niagara"]?["virtual_texture_assets"]?[path] is null)
                    value["runtime_texture"] = MaterialTextureObject(path);
            }
            else if (type is not null && TextureObjectTypes.IsKnownNonTexture(type)) value["texture_cast"] = false;
        }
        void BindStoreObjects(JObject? store) {
            if (store is null) return;
            foreach (JObject reference in (store["references"] as JArray ?? new()).Cast<JObject>())
                BindStoreObject(reference["value"]);
            foreach (string field in new[] { "interface_values", "object_values" })
                foreach (JToken entry in (store[field] as JArray ?? new())) BindStoreObject(entry);
            foreach (string field in new[] { "initial_sources", "instance_sources" })
                foreach (JObject source in (store[field] as JArray ?? new()).Cast<JObject>()) BindStoreObjects(source["definition"] as JObject);
        }
        if (definition["niagara"] is JObject objectSystem) {
            if (objectSystem["instance_stores"] is JObject instanceStores) {
                BindStoreObjects(instanceStores["instance"] as JObject);
                BindStoreObjects(instanceStores["override"] as JObject);
            }
            foreach (string field in new[] { "system_spawn", "system_update" }) BindStoreObjects(objectSystem[field]?["execution_store"] as JObject);
            foreach (JObject emitter in (objectSystem["emitters"] as JArray ?? new()).Cast<JObject>()) {
                foreach (string field in new[] { "spawn", "update", "gpu_program" }) BindStoreObjects(emitter[field]?["execution_store"] as JObject);
                foreach (JObject context in (emitter["gpu_program"]?["host_contexts"] as JArray ?? new()).Cast<JObject>())
                    BindStoreObjects(context["execution_store"] as JObject);
                foreach (JObject handler in (emitter["event_handlers"] as JArray ?? new()).Cast<JObject>()) BindStoreObjects(handler["program"]?["execution_store"] as JObject);
            }
        }
        JObject? MaterialTextureCollection(CompiledTextureCollection? source) {
            if (source is null) return null;
            return new JObject {
                ["path"] = source.Path, ["name"] = source.Name, ["source_type"] = source.SourceType,
                ["system_override"] = source.SystemOverride, ["base_present"] = source.BasePresent,
                ["base_default"] = source.BaseDefault is { } texture ? MaterialTextureObject(texture) : null
            };
        }
        JObject? MaterialInterfaceDefinition(JObject? sourceDefinition) {
            if (sourceDefinition is null) return null;
            JObject data = (JObject)sourceDefinition.DeepClone();
            foreach (string field in new[] { "texture", "user_texture" }) {
                string source = "source_" + field;
                if (data[source] is not JToken value) continue;
                data[field] = value.Type == JTokenType.Null ? JValue.CreateNull() : MaterialTextureObject(value.Value<string>()!);
                data.Remove(source);
            }
            return data;
        }
        var fontPages = new Dictionary<string, string>(SourceNameOrder.Instance);
        var materialFonts = new Dictionary<string, JObject>(SourceNameOrder.Instance);
        string MaterialFontPage(string source) {
            if (fontPages.TryGetValue(source, out string? existing)) return existing;
            AssetLibrary library = textureAssets ?? vectorFields
                ?? throw new ConversionException($"Font page requires its source library: {source}");
            SourceAsset owner = library.Load(source);
            SourceObject page = owner.ResolveObject(source, value => value.ClassName == "Texture2D");
            string resource = WriteNativeSourceTexture(owner, page, source, ns, name, files, oodleDataLibrary,
                NativeSourceTextureKind.FontPage);
            fontPages.Add(source, resource);
            return resource;
        }
        foreach (var (key, material) in effect.Materials)
        {
            foreach (var (font, pages) in material.FontAssets) {
                if (materialFonts.ContainsKey(font)) continue;
                materialFonts.Add(font, new JObject(pages.Select(page => new JProperty(
                    page.Key.ToString(System.Globalization.CultureInfo.InvariantCulture), MaterialFontPage(page.Value)))));
            }
            JObject bindings = new();
            foreach (var (sampler, source) in material.Textures)
            {
                if (source is MaterialCompiler.NoiseGradientTexture or MaterialCompiler.NoiseVolumeTexture or MaterialCompiler.NoiseScalarBlueTexture) {
                    bindings[sampler] = source;
                    continue;
                }
                if (material.RenderTargetTextures.Contains(source)) {
                    bindings[sampler] = MaterialTextureObject(source);
                    continue;
                }
                if (!material.TextureSettings.TryGetValue(source, out TextureSettings? settings))
                    throw new ConversionException($"Missing texture settings for {source}");
                bindings[sampler] = settings.FontPage ? MaterialFontPage(source)
                    : WriteOriginalTexture(source, settings, ns, name, textures, files);
            }
            if (material.SubstratePunctualVolume)
                bindings["LfxSlabPunctualVolume"] = "lumenfx:" + SubstrateVolumePunctual + ".lftex.json";
            string shader = $"lumenfx/{name}/{key}";
            files[$"shaders/{shader}.{(material.VolumeCompute ? "csh" : "fsh")}"] = Encoding.UTF8.GetBytes(material.Fragment);
            if (material.VolumeCompute) {
                var sparseVolumes = new JObject(material.SparseVolumeTextures.Select(volume =>
                    new JProperty(volume.Key, ns + ":" + SparseVolumeKey(volume.Value))));
                files[$"shaders/{shader}.volume.json"] = Encoding.UTF8.GetBytes(new JObject {
                    ["schema"] = "lumenfx.volume_material_source.v1", ["sparse_volumes"] = sparseVolumes
                }.ToString());
            }
            if (material.NaniteSoftwareRaster is { } raster) {
                string coverage = shader + "_nanite_raster";
                files[$"shaders/{coverage}.csh"] = Encoding.UTF8.GetBytes(raster);
                files[$"shaders/{coverage}.nanite_raster.json"] = Encoding.UTF8.GetBytes(new JObject {
                    ["schema"] = "lumenfx.nanite_raster_source.v1"
                }.ToString());
            }
            string? vertex = null;
            string? naniteVertex = null;
            if (material.VertexMaterial is { } vertexMaterial) {
                vertex = shader + "_vertex_material";
                files[$"shaders/{vertex}.vsh"] = Encoding.UTF8.GetBytes(vertexMaterial.Source);
                if (vertexMaterial.NaniteComputeSource is { } naniteSource) {
                    naniteVertex = shader + "_nanite_vertex";
                    files[$"shaders/{naniteVertex}.csh"] = Encoding.UTF8.GetBytes(naniteSource);
                    files[$"shaders/{naniteVertex}.nanite_vertex.json"] = Encoding.UTF8.GetBytes(new JObject {
                        ["schema"] = "lumenfx.nanite_vertex_source.v1",
                        ["interpolator_widths"] = new JArray(vertexMaterial.NaniteInterpolatorWidths)
                    }.ToString());
                }
            }
            materialDefinitions[key] = new JObject { ["fragment_shader"] = ns + ":" + shader,
                ["vertex_material"] = vertex is null ? null : ns + ":" + vertex,
                ["nanite_vertex_material"] = naniteVertex is null ? null : ns + ":" + naniteVertex,
                ["nanite_interpolator_widths"] = new JArray(material.VertexMaterial?.NaniteInterpolatorWidths ?? Array.Empty<int>()),
                ["nanite_material_source"] = material.NaniteSource is { } nanite ? new JObject {
                    ["domain"] = nanite.Domain, ["blend_mode"] = nanite.BlendMode, ["shading_model"] = nanite.ShadingModel,
                    ["shading_model_field"] = nanite.ShadingModelField, ["used_with_nanite"] = nanite.UsedWithNanite,
                    ["automatically_set_usage"] = nanite.AutomaticallySetUsage, ["sky"] = nanite.Sky,
                    ["two_sided"] = nanite.TwoSided, ["opacity_mask_clip"] = nanite.OpacityMaskClip,
                    ["pixel_depth_offset"] = nanite.PixelDepthOffset, ["dither_opacity_mask"] = nanite.DitherOpacityMask
                } : null,
                ["maximum_vertex_displacement"] = material.VertexMaterial?.MaximumDisplacement ?? 0f,
                ["customized_uv_mask"] = material.VertexMaterial?.CustomizedUVMask ?? 0,
                ["world_position_offset"] = material.VertexMaterial?.WorldPositionOffset ?? false,
                ["vertex_basis"] = material.VertexMaterial?.VertexBasis ?? false,
                ["spherical_particle_normals"] = material.VertexMaterial?.SphericalParticleNormals ?? false,
                ["previous_view"] = material.VertexMaterial?.PreviousView ?? false,
                ["previous_matrices"] = material.VertexMaterial?.PreviousMatrices ?? false,
                ["primitive_matrices"] = material.VertexMaterial?.PrimitiveMatrices ?? false,
                ["previous_first_person"] = material.VertexMaterial?.PreviousFirstPerson ?? false,
                ["first_person_inputs"] = material.VertexMaterial?.FirstPersonInputs ?? 0,
                ["previous_clock"] = material.VertexMaterial?.PreviousClock ?? false,
                ["source_object"] = material.SourceObject,
                ["light_function"] = material.LightFunction,
                ["light_function_atlas_compatible"] = material.LightFunctionAtlasCompatible,
                ["translucency_lighting_volume"] = material.TranslucencyVolume,
                ["high_quality_forward_reflections"] = material.HighQualityForwardReflections,
                ["runtime_virtual_texture_outputs"] = material.RuntimeVirtualTextureOutputs ?? 0,
                ["virtual_texture_feedback"] = material.VirtualTextureFeedback,
                ["virtual_texture_reads"] = new JArray(material.VirtualTextureReads.Select(read => new JObject {
                    ["name"] = read.Name, ["source"] = read.Source, ["material"] = read.Material, ["layers"] = read.Layers,
                    ["streamed"] = read.Material is null ? definition["niagara"]?["virtual_texture_assets"]?[read.Source]?.Value<string>()
                            ?? throw new ConversionException($"Missing cooked streaming virtual texture {read.Source}") : null,
                    ["packed"] = read.Packed, ["adaptive"] = read.Adaptive, ["parameter"] = read.Parameter,
                    ["association"] = read.Association, ["index"] = read.Index,
                    ["renderer_binding"] = read.RendererBinding is { } binding ? new JObject {
                        ["variable"] = binding.Variable, ["source_type"] = binding.SourceType, ["type_flags"] = binding.TypeFlags,
                        ["data_interface"] = binding.DataInterface, ["child"] = binding.Child,
                        ["object_default"] = binding.ObjectDefault, ["interface_id"] = binding.InterfaceId,
                        ["static_exposure"] = binding.StaticExposure,
                        ["base"] = read.RendererBase is { } parent
                            ? definition["niagara"]?["virtual_texture_assets"]?[parent]?.Value<string>() : null,
                        ["collection"] = binding.Collection is { } collection ? new JObject {
                            ["path"] = collection.Path, ["name"] = collection.Name, ["source_type"] = collection.SourceType,
                            ["system_override"] = collection.SystemOverride, ["base_default"] = collection.BaseDefault
                        } : null,
                        ["object_inputs"] = new JArray((binding.ObjectInputs ?? Array.Empty<CompiledTextureObjectInput>()).Select(input => new JObject {
                            ["variable"] = input.Variable, ["default_present"] = input.DefaultPresent, ["default_texture"] = input.DefaultTexture
                        }))
                    } : null
                })),
                ["decal"] = material.Decal is { } decal ? new JObject { ["outputs"] = decal.Outputs, ["blend"] = decal.Blend } : null,
                ["write_translucent_velocity"] = material.WriteTranslucentVelocity, ["velocity_from_depth"] = material.VelocityFromDepth,
                ["textures"] = bindings, ["blend"] = material.Blend, ["depth_test"] = material.DepthTest, ["cull"] = material.Cull,
                ["automatic_sprite_coverage_channels"] = material.AutomaticSpriteCoverageChannels,
                ["texture_sampler_sources"] = material.TextureSamplerSources.Count == 0 ? null
                    : new JObject(material.TextureSamplerSources.Select(binding => new JProperty(binding.Key, binding.Value.ToString()))),
                ["depth_compare"] = material.DepthTest ? "less_equal" : "always",
                ["depth_write"] = material.DepthTest && material.Blend == "opaque",
                ["translucency_pass"] = material.TranslucencyPass,
                ["dynamic_slots"] = material.DynamicSlots,
                ["ribbon_particle_data"] = material.RibbonParticleData,
                ["depth_projection"] = material.DepthProjection,
                ["scene_depth"] = material.SceneDepth,
                ["scene_color"] = material.SceneColor,
                ["default_lit_surface"] = material.DefaultLitSurface,
                ["particle_lighting_features"] = material.ParticleLightingFeatures,
                ["subsurface_profile"] = material.SubsurfaceProfile,
                ["slab_subsurface_profiles"] = new JArray(material.SlabSubsurfaceProfiles),
                ["specular_profiles"] = new JArray(material.SpecularProfiles),
                ["toon_profiles"] = new JArray(material.ToonProfiles),
                ["adaptive_substrate_words"] = material.AdaptiveSubstrateWords,
                ["point_shadow_coverage"] = material.PointShadowCoverage ?? "none",
                ["translucent_custom_depth"] = material.TranslucentCustomDepth,
                ["custom_scene_textures"] = material.CustomSceneTextures,
                ["deferred_scene_textures"] = material.DeferredSceneTextures,
                ["anisotropic_surface"] = material.AnisotropicSurface,
                ["custom_surface_data"] = material.CustomSurfaceData,
                ["material_view"] = material.MaterialView,
                ["material_collections"] = new JArray(material.MaterialCollections.Select(collection => new JObject {
                    ["uniform"] = collection.Uniform, ["stages"] = collection.Stages, ["source"] = collection.Source.Json()
                })),
                ["runtime_parameters"] = new JArray(material.RuntimeParameters.Select(parameter => {
                    var entry = new JObject {
                        ["name"] = parameter.Name, ["kind"] = parameter.Kind, ["variable"] = parameter.Binding?.Variable,
                        ["type"] = parameter.Binding?.Type ?? "constant", ["data_interface"] = parameter.Binding?.DataInterface,
                        ["child"] = parameter.Binding?.Child, ["default"] = new JArray(parameter.Default),
                        ["association"] = parameter.Association, ["parameter_index"] = parameter.ParameterIndex
                    };
                    if (parameter.DefaultProfile is not null) entry["default_profile"] = parameter.DefaultProfile;
                    if (parameter.RendererConstant) entry["renderer_constant"] = true;
                    return entry;
                })),
                ["runtime_textures"] = new JArray(material.RuntimeTextures.Select(texture => new JObject {
                    ["name"] = texture.Binding.Name, ["sampler"] = texture.Sampler, ["variable"] = texture.Binding.Variable,
                    ["source_type"] = texture.Binding.SourceType, ["type_flags"] = texture.Binding.TypeFlags,
                    ["data_interface"] = texture.Binding.DataInterface, ["child"] = texture.Binding.Child,
                    ["static_exposure"] = texture.Binding.StaticExposure,
                    ["interface_id"] = texture.Binding.InterfaceId,
                    ["interface_definition"] = MaterialInterfaceDefinition(texture.Binding.InterfaceDefinition),
                    ["collection"] = MaterialTextureCollection(texture.Binding.Collection),
                    ["object_inputs"] = new JArray((texture.Binding.ObjectInputs ?? Array.Empty<CompiledTextureObjectInput>()).Select(input => new JObject {
                        ["variable"] = input.Variable, ["default_present"] = input.DefaultPresent,
                        ["default_texture"] = input.DefaultTexture is { } inputTextureSource ? MaterialTextureObject(inputTextureSource) : null
                    })),
                    ["base_default"] = MaterialTextureObject(texture.BaseDefault),
                    ["object_default"] = texture.Binding.ObjectDefault is { } source ? MaterialTextureObject(source, texture.Binding.StaticExposure) : null
                })),
                ["font_samplers"] = new JArray(material.FontSamplers.Select(font => new JObject {
                    ["name"] = font.Name, ["association"] = font.Association, ["parameter_index"] = font.Index,
                    ["sampler"] = font.Sampler, ["parent_texture"] = font.ParentTexture is { } parentFont ? MaterialFontPage(parentFont) : null
                })),
                ["texture_parameter_samplers"] = new JArray(material.TextureParameterSamplers.Select(texture => new JObject {
                    ["name"] = texture.Name, ["association"] = texture.Association,
                    ["parameter_index"] = texture.Index, ["sampler"] = texture.Sampler
                })),
                ["dbuffer_textures"] = material.DBufferTextures,
                ["receives_decals"] = material.ReceivesDecals,
                ["macro_uv"] = material.MacroUV,
                ["fog"] = new JObject { ["enabled"] = material.ApplyFogging, ["per_pixel"] = material.FogPerPixel, ["sky"] = material.IsSky } };
        }
        definition["materials"] = materialDefinitions;
        if (materialFonts.Count != 0)
            definition["material_fonts"] = new JObject(materialFonts.Select(font => new JProperty(font.Key, font.Value)));
        files[$"lumenfx/effects/{name}.json"] = Encoding.UTF8.GetBytes(definition.ToString(Formatting.Indented) + "\n");
        WriteNamespaceFiles(resourcesDirectory, ns, files);
        Console.WriteLine($"Wrote {effect.Definition["emitters"]!.Count()} emitters, {effect.Materials.Count} materials and {files.Count} resources: {identifier}");
    }

    private static void WriteNamespaceFiles(string resourcesDirectory, string ns, Dictionary<string, byte[]> files)
    {
        string root = Path.GetFullPath(Path.Combine(resourcesDirectory, "assets", ns));
        foreach (var (relative, bytes) in files)
        {
            string destination = Path.GetFullPath(Path.Combine(root, relative.Replace('/', Path.DirectorySeparatorChar)));
            if (!destination.StartsWith(root + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))
                throw new ConversionException("Output path escapes resource namespace");
            Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
            File.WriteAllBytes(destination, bytes);
        }
    }

    private static void BindField(JObject binding, string ns, AssetLibrary? vectorFields, Dictionary<string, byte[]> files,
        bool objectDefaults = false)
    {
        if (binding.Value<string>("field_source") is not { } source) return;
        if (vectorFields is null) throw new ConversionException($"Missing source vector field library for {source}");
        SourceAsset asset = vectorFields.Load(source);
        bool niagaraField = binding.Value<string>("interface_type") == "vector_field"
                || binding.Value<string>("class") == "NiagaraDataInterfaceVectorField";
        SourceObject fieldObject = asset.ResolveObject(source, value => value.ClassName == "VectorFieldStatic"
                || niagaraField && value.ClassName == "VectorFieldAnimated");
        if (niagaraField) {
            binding["field_kind"] = fieldObject.ClassName == "VectorFieldAnimated" ? "animated" : "static";
            if (fieldObject.ClassName == "VectorFieldAnimated") {
                binding.Remove("field_source"); binding.Remove("field");
                return;
            }
        }
        if (binding.Value<string>("interface_type") == "vector_field") {
            binding.Remove("field_source");
            JObject properties = fieldObject.Properties;
            JObject data = (JObject)binding["interface_data"]!;
            data["cpu_access"] = properties.Value<bool?>("bAllowCPUAccess") ?? false;
            data["dimensions"] = new JArray(properties.Value<int>("SizeX"), properties.Value<int>("SizeY"), properties.Value<int>("SizeZ"));
            JObject bounds = properties["Bounds"] as JObject ?? throw new ConversionException($"Missing vector field bounds: {source}");
            data["min_bounds"] = new JArray(new[] { "X", "Y", "Z" }.Select(axis => SourceAsset.Number(bounds["Min"]?[axis])));
            data["max_bounds"] = new JArray(new[] { "X", "Y", "Z" }.Select(axis => SourceAsset.Number(bounds["Max"]?[axis])));
            if (!objectDefaults && (!data.Value<bool>("cpu_access") || binding.Value<string>("name") is not ("SampleField" or "LoadField"))) return;
        }
        string identity = SourceNameOrder.Canonical(asset.ObjectPath(fieldObject.Index));
        string fieldId = "unreal/vf_" + Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(identity)))[..24];
        string fieldPath = "lumenfx/vector_fields/" + fieldId + ".lfvf";
        byte[] fieldBytes = VectorFieldAsset.Convert(asset, fieldObject);
        if (files.TryGetValue(fieldPath, out byte[]? existingField) && !existingField.AsSpan().SequenceEqual(fieldBytes))
            throw new ConversionException($"Different vector fields collide at {fieldPath}: {source}");
        files[fieldPath] = fieldBytes;
        binding.Remove("field_source");
        binding["field"] = ns + ":" + fieldId;
    }
}
