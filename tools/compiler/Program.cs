using LumenFx.Compiler.Output;
using LumenFx.Compiler.Unreal;
using LumenFx.Compiler.Unreal.Execution;
using LumenFx.Compiler.Unreal.Expressions;
using LumenFx.Compiler.Unreal.TextureEncoding;
using LumenFx.Compiler.Unreal.TextureSource;
using LumenFx.Compiler.Unreal.Geometry.MeshBuild;
using LumenFx.Compiler.Unreal.Geometry.GeometryCollection;
using LumenFx.Compiler.Unreal.Geometry.GaussianSplat;
using LumenFx.Compiler.Unreal.Localization;
using LumenFx.Compiler.Unreal.Audio;
using LumenFx.Compiler.Unreal.SimulationCache;
using LumenFx.Compiler.Unreal.Parameters;
using LumenFx.Compiler.Unreal.Scene;
using LumenFx.Compiler.Unreal.Components;
using Newtonsoft.Json.Linq;
using System.Security.Cryptography;
using System.Text;

try
{
    string? oodleDataLibrary = null;
    string[]? meshBuildPaths = null;
    string? meshTargetPlatform = null;
    string[]? audioBuildPaths = null;
    string[]? simCacheBuildPaths = null;
    string[]? geometryBuildPaths = null;
    string[]? groomBuildPaths = null;
    string[]? textBuildPaths = null;
    string? textLanguage = null, textLocale = null;
    MaterialGraphPermutation materialPermutation = new();
    var localizationResources = new List<string>();
    string? volumeCacheSavedDirectory = null;
    bool nativeMeshBuild = false;
    var virtualTextureWriters = new List<(string Material, string Texture)>();
    var virtualTextureComponents = new List<string>();
    var virtualTexturePrimitives = new List<string>();
    var physicsFieldComponents = new List<string>();
    string? niagaraComponentPath = null;
    string? niagaraGpuSource = null;
    string? niagaraLifeCycleTrackPath = null;
    string? niagaraCacheTrackPath = null;
    string? shaderDirectoriesFile = null;
    string? materialSourceConfig = null;
    string materialIniPlatform = "Windows";
    int triangleOrder = 1;
    Dictionary<string, string> meshContentRoots = new(StringComparer.Ordinal);
    while (args.Length >= 3 && args[0] is "niagara" or "static-mesh-build" or "static-mesh-native") {
        if (args[0] == "niagara" && args[^2] == "--oodle-data-library") {
            oodleDataLibrary = args[^1]; args = args[..^2];
        } else if (args[0] == "niagara" && args[^2] == "--volume-cache-saved-directory") {
            volumeCacheSavedDirectory = Path.GetFullPath(args[^1]); args = args[..^2];
        } else if (args[0] == "niagara" && args[^2] == "--rvt-volume") {
            virtualTextureComponents.Add(args[^1]); args = args[..^2];
        } else if (args[0] == "niagara" && args[^2] == "--rvt-primitive") {
            virtualTexturePrimitives.Add(args[^1]); args = args[..^2];
        } else if (args[0] == "niagara" && args[^2] == "--physics-field-component") {
            physicsFieldComponents.Add(args[^1]); args = args[..^2];
        } else if (args[0] == "niagara" && args[^2] == "--niagara-gpu-source") {
            if (niagaraGpuSource is not null) throw new ArgumentException("Select one matching Niagara editor export root");
            niagaraGpuSource = Path.GetFullPath(args[^1]); args = args[..^2];
        } else if (args[0] == "niagara" && args[^2] == "--niagara-component") {
            if (niagaraComponentPath is not null) throw new ArgumentException("Select one root NiagaraComponent");
            niagaraComponentPath = args[^1]; args = args[..^2];
        } else if (args[0] == "niagara" && args[^2] == "--niagara-lifecycle-track") {
            if (niagaraLifeCycleTrackPath is not null) throw new ArgumentException("Select one Niagara System Life Cycle track");
            niagaraLifeCycleTrackPath = args[^1]; args = args[..^2];
        } else if (args[0] == "niagara" && args[^2] == "--niagara-cache-track") {
            if (niagaraCacheTrackPath is not null) throw new ArgumentException("Select one Niagara Cache track");
            niagaraCacheTrackPath = args[^1]; args = args[..^2];
        } else if (args[0] == "niagara" && args[^2] == "--shader-directories") {
            if (shaderDirectoriesFile is not null) throw new ArgumentException("Select one shader directory mappings file");
            shaderDirectoriesFile = args[^1]; args = args[..^2];
        } else if (args[0] == "niagara" && args[^2] == "--material-source-config") {
            if (materialSourceConfig is not null) throw new ArgumentException("Select one Unreal source Config directory");
            materialSourceConfig = Path.GetFullPath(args[^1]); args = args[..^2];
            if (!Directory.Exists(materialSourceConfig)) throw new DirectoryNotFoundException(materialSourceConfig);
        } else if (args[0] == "niagara" && args[^2] == "--material-ini-platform") {
            materialIniPlatform = args[^1]; args = args[..^2];
            if (materialIniPlatform.Length == 0 || !materialIniPlatform.All(character => char.IsAsciiLetterOrDigit(character) || character == '_'))
                throw new ArgumentException("Invalid Unreal INI platform name");
        } else if (args[0] == "niagara" && args[^2] == "--material-quality") {
            materialPermutation = materialPermutation with { Quality = args[^1].ToLowerInvariant() switch {
                "low" => MaterialQualityInput.Low, "medium" => MaterialQualityInput.Medium,
                "high" => MaterialQualityInput.High, "epic" => MaterialQualityInput.Epic,
                "default" => MaterialQualityInput.Default,
                _ => throw new ArgumentException("Material quality must be low, medium, high, epic or default")
            } };
            args = args[..^2];
        } else if (args[0] == "niagara" && args[^2] == "--material-feature-level") {
            materialPermutation = materialPermutation with { FeatureLevel = args[^1].ToLowerInvariant() switch {
                "es3_1" => MaterialFeatureInput.Es3_1, "sm5" => MaterialFeatureInput.Sm5, "sm6" => MaterialFeatureInput.Sm6,
                _ => throw new ArgumentException("Material feature level must be es3_1, sm5 or sm6")
            } };
            args = args[..^2];
        } else if (args[0] == "niagara" && args[^2] == "--material-shading-path") {
            materialPermutation = materialPermutation with { ShadingPathOverride = args[^1].ToLowerInvariant() switch {
                "deferred" => MaterialShadingInput.Deferred, "forward" => MaterialShadingInput.Forward,
                "mobile" => MaterialShadingInput.Mobile,
                _ => throw new ArgumentException("Material shading path must be deferred, forward or mobile")
            } };
            args = args[..^2];
        } else if (args[0] == "niagara" && args[^2] == "--material-simulate-mobile-for-low-quality") {
            materialPermutation = materialPermutation with { SimulateMobileForLowQuality = bool.Parse(args[^1]) };
            args = args[..^2];
        } else if (args[0] == "niagara" && args.Length >= 4 && args[^3] == "--material-shader-platform-info") {
            materialPermutation = materialPermutation with { PlatformFlags = MaterialShaderPlatformFlags.Read(args[^2], args[^1]) };
            args = args[..^3];
        } else if (args[0] == "niagara" && args.Length >= 4 && args[^3] == "--material-substrate-budget") {
            if (!int.TryParse(args[^2], out int bytes) || bytes is < 20 or > 256
                    || !int.TryParse(args[^1], out int closures) || closures is < 1 or > 8)
                throw new ArgumentException("The resolved Unreal Substrate budget requires 20–256 bytes and 1–8 closures");
            materialPermutation = materialPermutation with { SubstrateByteLimit = bytes, SubstrateClosureLimit = closures };
            args = args[..^3];
        } else if (args[0] == "niagara" && args[^2] == "--text-language") {
            textLanguage = args[^1]; args = args[..^2];
        } else if (args[0] == "niagara" && args[^2] == "--text-locale") {
            textLocale = args[^1]; args = args[..^2];
        } else if (args[0] == "niagara" && args[^2] == "--text-locres") {
            localizationResources.Insert(0, Path.GetFullPath(args[^1])); args = args[..^2];
        } else if (args[^2] == "--mesh-target-platform") {
            meshTargetPlatform = args[^1]; args = args[..^2];
        } else if (args[^2] == "--triangle-order-optimization") {
            if (!int.TryParse(args[^1], out triangleOrder) || triangleOrder is < 0 or > 2)
                throw new ArgumentException("Triangle order optimization must be 0, 1 or 2");
            args = args[..^2];
        } else if (args.Length >= 4 && args[^3] is "--mesh-content-root" or "--audio-content-root"
                or "--sim-cache-content-root" or "--geometry-content-root" or "--groom-content-root" or "--text-content-root") {
            meshContentRoots.Add(args[^2], args[^1]); args = args[..^3];
        } else if (args[0] == "niagara" && args.Length >= 4 && args[^3] == "--audio-native-tools") {
            audioBuildPaths = args[^2..]; args = args[..^3];
        } else if (args[0] == "niagara" && args.Length >= 4 && args[^3] == "--sim-cache-native-tools") {
            simCacheBuildPaths = args[^2..]; args = args[..^3];
        } else if (args[0] == "niagara" && args.Length >= 4 && args[^3] == "--geometry-native-tools") {
            geometryBuildPaths = args[^2..]; args = args[..^3];
        } else if (args[0] == "niagara" && args.Length >= 4 && args[^3] == "--groom-native-tools") {
            groomBuildPaths = args[^2..]; args = args[..^3];
        } else if (args[0] == "niagara" && args.Length >= 4 && args[^3] == "--text-native-tools") {
            textBuildPaths = args[^2..]; args = args[..^3];
        } else if (args[0] == "niagara" && args.Length >= 4 && args[^3] == "--rvt-writer") {
            virtualTextureWriters.Add((args[^2], args[^1])); args = args[..^3];
        } else if (args[0] == "niagara" && args.Length >= 5 && args[^4] == "--mesh-build-tools") {
            meshBuildPaths = args[^3..]; nativeMeshBuild = false; args = args[..^4];
        } else if (args[0] == "niagara" && args.Length >= 5 && args[^4] == "--mesh-native-tools") {
            meshBuildPaths = args[^3..]; nativeMeshBuild = true; args = args[..^4];
        } else break;
    }
    if (args.Length == 5 && args[0] == "texture-source")
    {
        TextureSourceCommand.Export(args[1], args[2], args[3], args[4]);
        return 0;
    }
    if (args.Length == 4 && args[0] == "shading-lookup-textures")
    {
        ResourcePackage.WriteShadingLookupTextures(args[1], args[2], args[3] == "-" ? null : args[3]);
        return 0;
    }
    if (args.Length == 4 && args[0] == "blue-noise-textures")
    {
        ResourcePackage.WriteBlueNoiseTextures(args[1], args[2], args[3] == "-" ? null : args[3]);
        return 0;
    }
    if (args.Length == 4 && args[0] == "audio-scope-filter")
    {
        await ScopeFilterResourcePackage.WriteAsync(args[1], args[2], args[3]);
        return 0;
    }
    if (args.Length == 4 && args[0] == "gaussian-splat-lut-textures")
    {
        ResourcePackage.WriteGaussianSplatLutTextures(args[1], args[2], args[3] == "-" ? null : args[3]);
        return 0;
    }
    if (args.Length == 6 && args[0] == "static-mesh")
    {
        SourceAsset source = new AssetLibrary(Path.GetFullPath(args[1])).Load(args[2]);
        StaticMeshSourcePackage.Write(source, args[3], Path.GetFullPath(args[4]), args[5] == "-" ? null : args[5], args[2]);
        return 0;
    }
    if (args.Length == 10 && args[0] is "static-mesh-build" or "static-mesh-native")
    {
        SourceAsset source = new AssetLibrary(Path.GetFullPath(args[1])).Load(args[2]);
        bool native = args[0] == "static-mesh-native";
        var tools = new MeshBuildTools(args[6] == "-" ? null : args[6], native ? null : args[7], args[8], args[9],
            native ? args[7] : null, triangleOrder, meshContentRoots, meshTargetPlatform);
        await StaticMeshRenderPackage.WriteAsync(source, args[3], Path.GetFullPath(args[4]), args[5] == "-" ? null : args[5], tools, args[2]);
        return 0;
    }
    if (args.Length == 5 && args[0] == "texture-codec")
    {
        TextureBakeCommand.Convert(args[1], args[2], args[3], args[4]);
        return 0;
    }
    if (args.Length is 4 or 5 && args[0] == "graph")
    {
        var source = new AssetLibrary(args.Length == 5 ? [Path.GetFullPath(args[1]), Path.GetFullPath(args[4])]
                : [Path.GetFullPath(args[1])]).Load(args[2]);
        string destination = Path.GetFullPath(args[3]);
        Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
        File.WriteAllText(destination, GraphDocument.Create(source).ToString(Newtonsoft.Json.Formatting.Indented) + "\n");
        Console.WriteLine($"Exported source graph: {args[2]}");
        return 0;
    }
    if (args.Length is not (10 or 11 or 12) || args[0] != "niagara")
    {
        Console.Error.WriteLine("Usage: LumenFx.Compiler niagara <uasset-json-directory> </Game/package.object> <namespace:effect> <original-png-directory> <resources-directory> <texture-aliases.json> <render-profile.json> <vector-field-json-directory> <unreal-source-directory> [engine-function-json-directory] [engine-texture-json-directory]");
        Console.Error.WriteLine("       LumenFx.Compiler graph <uasset-json-directory> <package.object> <output.json> [engine-function-json-directory]");
        Console.Error.WriteLine("       LumenFx.Compiler audio-scope-filter <unreal-source-directory> <unreal-commit> <resources-directory>");
        Console.Error.WriteLine("       LumenFx.Compiler texture-source <uasset-json-directory> <package.object> <output.raw> <absolute-oodle-data-library-or-dash>");
        Console.Error.WriteLine("       LumenFx.Compiler shading-lookup-textures <engine-texture-json-directory> <resources-directory> <absolute-oodle-data-library-or-dash>");
        Console.Error.WriteLine("       LumenFx.Compiler blue-noise-textures <engine-texture-json-directory> <resources-directory> <absolute-oodle-data-library-or-dash>");
        Console.Error.WriteLine("       LumenFx.Compiler gaussian-splat-lut-textures <gaussian-splat-lut-json-directory> <resources-directory> <absolute-oodle-data-library-or-dash>");
        Console.Error.WriteLine("       LumenFx.Compiler static-mesh <uasset-json-directory> <package.object> <namespace:mesh> <resources-directory> <absolute-oodle-data-library-or-dash>");
        Console.Error.WriteLine("       LumenFx.Compiler static-mesh-build <uasset-json-directory> <package.object> <namespace:mesh> <resources-directory> <oodle-library-or-dash> <mesh-source-library> <uv-layout-executable> <work-directory> <unreal-source-directory>");
        Console.Error.WriteLine("       LumenFx.Compiler static-mesh-native <uasset-json-directory> <package.object> <namespace:mesh> <resources-directory> <oodle-library-or-dash> <mesh-index-library-or-dash> <mesh-description-executable> <work-directory> <unreal-source-directory>");
        Console.Error.WriteLine("       Niagara cutout source decoding accepts --oodle-data-library <absolute-library-path> after the positional inputs.");
        Console.Error.WriteLine("       Niagara meshes accept --mesh-build-tools <mesh-source-library> <uv-layout-executable> <work-directory>; the Unreal source directory is shared with the positional input.");
        Console.Error.WriteLine("       Native mesh preparation and generated LODs use --mesh-native-tools <mesh-source-library> <mesh-description-executable> <work-directory>.");
        Console.Error.WriteLine("       Mesh commands accept --triangle-order-optimization <0|1|2>; mode 0 uses the engine's NVTriStrip helper, mode 1 is Forsyth, mode 2 preserves triangle order.");
        Console.Error.WriteLine("       Add project and plugin shader includes with --shader-directories <mappings.json>.");
        Console.Error.WriteLine("       Native asset references accept repeated --mesh-content-root </Root/> <absolute-content-directory> mounts.");
        Console.Error.WriteLine("       Native waves accept --audio-native-tools <wave-export-executable> <work-directory> and repeated --audio-content-root mounts.");
        Console.Error.WriteLine("       Native simulation caches and sparse volumes accept --sim-cache-native-tools <cache-export-executable> <work-directory> and repeated --sim-cache-content-root mounts.");
        Console.Error.WriteLine("       Saved Geometry Collections accept --geometry-native-tools <mesh-description-executable> <work-directory> and repeated --geometry-content-root mounts.");
        Console.Error.WriteLine("       Saved Grooms accept --groom-native-tools <groom-export-executable> <work-directory> and repeated --groom-content-root mounts.");
        Console.Error.WriteLine("       Legacy material layer names and dialogue use --text-native-tools <text-resolver-executable> <work-directory>, optional --text-language and --text-locale, and repeated --text-locres <file> and --text-content-root mounts.");
        Console.Error.WriteLine("       OpenVDB Volume Caches may resolve {SavedDir} using --volume-cache-saved-directory <source-project-saved-directory>.");
        Console.Error.WriteLine("       Runtime virtual texture writers accept repeated --rvt-writer <material.object> <runtime-virtual-texture.object>.");
        Console.Error.WriteLine("       Authored RVT placements accept repeated --rvt-volume <runtime-virtual-texture-component.object>.");
        Console.Error.WriteLine("       Authored RVT primitive settings accept repeated --rvt-primitive <primitive-component.object>.");
        Console.Error.WriteLine("       Saved Chaos graphs accept repeated --physics-field-component <field-system-component.object>.");
        Console.Error.WriteLine("       Filtered GPU packages use --niagara-gpu-source <matching-editor-json-directory> for their original script translation.");
        Console.Error.WriteLine("       Use --niagara-component <component.object> to import a placed or templated component of the root system.");
        Console.Error.WriteLine("       Use --niagara-lifecycle-track <track.object> with --niagara-component to import its MovieScene life cycle section.");
        Console.Error.WriteLine("       Use --niagara-cache-track <track.object> with --niagara-component to import its MovieScene simulation cache sections.");
        Console.Error.WriteLine("       Material graph permutations accept --material-quality <low|medium|high|epic|default>, --material-feature-level <es3_1|sm5|sm6>, --material-shading-path <deferred|forward|mobile> and --material-simulate-mobile-for-low-quality <true|false>.");
        Console.Error.WriteLine("       DataDrivenShaderPlatformInfo switches accept --material-shader-platform-info <DataDrivenPlatformInfo.ini> <shader-platform>.");
        Console.Error.WriteLine("       Material project CVars come from the /Game/ mount's Config directory or --material-source-config <directory> with optional --material-ini-platform <name>.");
        Console.Error.WriteLine("       Adaptive Substrate uses --material-substrate-budget <resolved-bytes-per-pixel> <resolved-closures-per-pixel> for project overrides.");
        return 2;
    }
    var library = new AssetLibrary(new[] { args[1] }.Concat(args.Skip(10)).Select(Path.GetFullPath).ToArray()) {
        NiagaraGpuSource = niagaraGpuSource is null ? null : new AssetLibrary(niagaraGpuSource)
    };
    library.MaterialPermutation = materialPermutation;
    if (textBuildPaths is not null)
        library.Localization = new NativeLocalization(new NativeAssetTools(textBuildPaths[0], textBuildPaths[1], meshContentRoots),
            textLanguage, textLocale, localizationResources);
    var aliases = args.Length >= 7 ? Newtonsoft.Json.JsonConvert.DeserializeObject<Dictionary<string, string>>(File.ReadAllText(args[6])) : null;
    if (meshTargetPlatform is not null && !nativeMeshBuild)
        throw new ArgumentException("--mesh-target-platform requires --mesh-native-tools");
    MeshBuildTools? meshTools = meshBuildPaths is null ? null : new MeshBuildTools(meshBuildPaths[0] == "-" ? null : meshBuildPaths[0],
        nativeMeshBuild ? null : meshBuildPaths[1], meshBuildPaths[2], Path.GetFullPath(args[9]), nativeMeshBuild ? meshBuildPaths[1] : null,
        triangleOrder, meshContentRoots, meshTargetPlatform);
    NativeAssetTools? audioTools = audioBuildPaths is null ? null : new NativeAssetTools(audioBuildPaths[0], audioBuildPaths[1], meshContentRoots);
    NativeAssetTools? simCacheTools = simCacheBuildPaths is null ? null : new NativeAssetTools(simCacheBuildPaths[0], simCacheBuildPaths[1], meshContentRoots);
    if (simCacheTools is not null) {
        var dataTables = new Dictionary<string, JObject>(SourceNameOrder.Instance);
        library.NativeDataTable = path => {
            if (!dataTables.TryGetValue(path, out var table)) {
                table = NativeAssetExchange.ExportAsync(path, simCacheTools, "data_table", ".lfdt.json",
                    bytes => JObject.Parse(System.Text.Encoding.UTF8.GetString(bytes))).GetAwaiter().GetResult();
                dataTables.Add(path, table);
            }
            return (JObject)table.DeepClone();
        };
    }
    if (geometryBuildPaths is not null)
        library.GeometryCollections = new GeometryCollectionAssetSource(new NativeAssetTools(geometryBuildPaths[0], geometryBuildPaths[1], meshContentRoots));
    else if (nativeMeshBuild && meshBuildPaths is not null)
        library.GeometryCollections = new GeometryCollectionAssetSource(new NativeAssetTools(meshBuildPaths[1], meshBuildPaths[2], meshContentRoots));
    if (geometryBuildPaths is not null)
        library.GaussianSplats = new GaussianSplatAssetSource(new NativeAssetTools(geometryBuildPaths[0], geometryBuildPaths[1], meshContentRoots));
    else if (nativeMeshBuild && meshBuildPaths is not null)
        library.GaussianSplats = new GaussianSplatAssetSource(new NativeAssetTools(meshBuildPaths[1], meshBuildPaths[2], meshContentRoots));
    var exportedCaches = new Dictionary<string, Task<NativeSimulationCache>>(SourceNameOrder.Instance);
    var exportedVolumes = new Dictionary<string, Task<byte[]>>(SourceNameOrder.Instance);
    var exportedVolumeCaches = new Dictionary<string, Task<byte[]>>(SourceNameOrder.Instance);
    var exportedGrooms = new Dictionary<string, Task<byte[]>>(SourceNameOrder.Instance);
    NativeAssetTools? groomTools = groomBuildPaths is null ? null
        : new NativeAssetTools(groomBuildPaths[0], groomBuildPaths[1], meshContentRoots);
    Task<byte[]> ExportGroom(string path)
    {
        if (exportedGrooms.TryGetValue(path, out var pending)) return pending;
        if (groomTools is null) throw new ConversionException($"Missing explicit Unreal groom export tools for {path}");
        pending = NativeAssetExchange.ExportAsync(path, groomTools, "groom", ".lfgr", bytes => {
            if (bytes.Length < 12 || !bytes.AsSpan(0, 4).SequenceEqual("LFGR"u8))
                throw new ConversionException($"Invalid native groom archive for {path}");
            return bytes;
        });
        exportedGrooms.Add(path, pending);
        return pending;
    }
    Task<NativeSimulationCache> ExportCache(string path)
    {
        if (!exportedCaches.TryGetValue(path, out var cache)) {
            if (simCacheTools is null) throw new ConversionException($"Missing explicit Unreal simulation cache export tools for {path}");
            cache = NativeSimulationCache.ExportAsync(path, simCacheTools);
            exportedCaches.Add(path, cache);
        }
        return cache;
    }
    Task<byte[]> ExportSparseVolume(string path)
    {
        if (!exportedVolumes.TryGetValue(path, out var volume)) {
            if (simCacheTools is null) throw new ConversionException($"Missing explicit Unreal sparse volume export tools for {path}");
            volume = NativeAssetExchange.ExportAsync(path, simCacheTools, "sparse_volume", ".lfst", bytes => {
                if (bytes.Length < 8 || !bytes.AsSpan(0, 4).SequenceEqual("LFST"u8))
                    throw new ConversionException($"Invalid Unreal sparse volume header: {path}");
                return bytes;
            });
            exportedVolumes.Add(path, volume);
        }
        return volume;
    }
    Task<byte[]> ExportVolumeCache(string path)
    {
        if (!exportedVolumeCaches.TryGetValue(path, out var cache)) {
            if (simCacheTools is null) throw new ConversionException($"Missing explicit Unreal OpenVDB export tools for {path}");
            cache = NativeAssetExchange.ExportAsync(path, simCacheTools, "volume_cache", ".lfvc", bytes => {
                if (bytes.Length < 24 || !bytes.AsSpan(0, 4).SequenceEqual("LFVC"u8))
                    throw new ConversionException($"Invalid Unreal Volume Cache header: {path}");
                return bytes;
            }, volumeCacheSavedDirectory is null ? null : new JObject { ["volume_cache_saved_directory"] = volumeCacheSavedDirectory });
            exportedVolumeCaches.Add(path, cache);
        }
        return cache;
    }
    var profile = args.Length >= 8 ? Newtonsoft.Json.Linq.JObject.Parse(File.ReadAllText(args[7])) : null;
    string engineSource = Path.GetFullPath(args[9]);
    var sourcePrecision = MaterialProjectPrecision.Read(engineSource,
        materialSourceConfig ?? MaterialProjectPrecision.ConfigFromGameMount(meshContentRoots), materialIniPlatform);
    MaterialPrecisionSettings precision = MaterialPrecisionSettings.Read(profile?["material_precision"], sourcePrecision);
    profile?.Remove("material_precision");
    var niagaraProject = NiagaraProjectSettings.Read(profile?["niagara_settings"]);
    profile?.Remove("niagara_settings");
    var skeletalMeshSettings = SkeletalMeshShaderSettings.Read(profile?["skeletal_mesh_gpu"]);
    profile?.Remove("skeletal_mesh_gpu");
    string motionDefault = profile?.Value<string>("default_renderer_motion_vector_setting") ?? "Precise";
    if (motionDefault is not ("Precise" or "Approximate")) throw new ArgumentException("Invalid project renderer motion vector default");
    var shaderDirectories = ShaderDirectoryMappings.Read(shaderDirectoriesFile);
    var importer = new NiagaraImporter(library, new VectorProgramCompiler(Path.GetFullPath(args[9]), motionDefault == "Precise", niagaraProject),
        aliases, oodleDataLibrary, meshTools, precision, path => ExportCache(path).GetAwaiter().GetResult().Materials,
        shaderDirectories);
    var packages = new List<(string Identifier, ImportedEffect Effect)>();
    var systemIds = new Dictionary<string, string>(SourceNameOrder.Instance);
    var importedSystems = new Dictionary<string, ImportedEffect>(SourceNameOrder.Instance);
    string namespaceId = args[3].Split(':')[0];
    string rootIdentity = SystemIdentity(args[2]);
    if ((niagaraLifeCycleTrackPath is not null || niagaraCacheTrackPath is not null) && niagaraComponentPath is null)
        throw new ArgumentException("A Niagara MovieScene track requires --niagara-component");
    var component = niagaraComponentPath is null ? null
        : new NiagaraComponentSource(library, niagaraComponentPath, niagaraLifeCycleTrackPath, niagaraCacheTrackPath);
    if (component is not null && !SourceNameOrder.Equal(rootIdentity, SystemIdentity(component.SystemPath)))
        throw new ConversionException($"NiagaraComponent {component.Identity} uses a different NiagaraSystem: {component.SystemPath}");
    var root = ImportEffect(rootIdentity, component);
    if (virtualTextureWriters.Count != 0)
        root = ResourcePackage.AddRuntimeVirtualTextureWriters(root, library, aliases, virtualTextureWriters,
            engineSource, shaderDirectories, precision);
    if (virtualTextureComponents.Count != 0 || virtualTexturePrimitives.Count != 0)
        root = ResourcePackage.AddRuntimeVirtualTextureScene(root, library, virtualTextureComponents, virtualTexturePrimitives, Path.GetFullPath(args[9]));
    if (physicsFieldComponents.Count != 0)
        root.Definition["physics_field_components"] = PhysicsFieldComponentSource.Compile(library, physicsFieldComponents);
    packages.Add((args[3], root));
    foreach (string handler in ResourcePackage.ChannelSystems(root.Definition)) AddHandler(handler);
    var fields = args.Length >= 9 ? new AssetLibrary(Path.GetFullPath(args[8])) : null;
    foreach (var package in packages)
        await ResourcePackage.WriteAsync(package.Effect, package.Identifier, Path.GetFullPath(args[4]), Path.GetFullPath(args[5]),
            fields, library, meshTools, oodleDataLibrary, systemIds, audioTools, ExportCache, ExportSparseVolume, ExportVolumeCache, ExportGroom);
    return 0;

    string SystemIdentity(string source)
    {
        var asset = library.Load(source);
        return asset.ObjectPath(asset.ResolveObject(source, value => value.ClassName == "NiagaraSystem").Index);
    }

    void AddHandler(string source)
    {
        string identity = SystemIdentity(source);
        if (systemIds.TryGetValue(identity, out string? existing)) {
            systemIds[source] = existing;
            return;
        }
        string identifier = namespaceId + ":niagara/channel_systems/" +
            Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(identity.ToLowerInvariant())))[..24];
        systemIds[identity] = identifier;
        systemIds[source] = identifier;
        var effect = ImportEffect(identity);
        if (identifier != args[3]) packages.Add((identifier, effect));
        foreach (string handler in ResourcePackage.ChannelSystems(effect.Definition)) AddHandler(handler);
    }

    ImportedEffect ImportEffect(string identity, NiagaraComponentSource? componentSource = null)
    {
        if (componentSource is null && importedSystems.TryGetValue(identity, out var imported)) return imported;
        var effect = importer.Convert(identity, componentSource);
        if (skeletalMeshSettings is not null)
            foreach (var emitter in effect.Definition["niagara"]!["emitters"]!.OfType<JObject>())
                if (emitter["gpu_program"] is JObject program) skeletalMeshSettings.Apply(program);
        if (profile is not null) {
            var particleView = (JObject)profile.DeepClone();
            foreach (string field in new[] { "ambient_occlusion", "bloom_strength", "bloom_threshold", "anti_aliasing", "motion_blur", "exposure" })
                particleView.Remove(field);
            effect.Definition["render"] = particleView;
            foreach (var emitter in effect.Definition["niagara"]!["emitters"]!.OfType<JObject>())
                foreach (var renderer in emitter["renderers"]!.OfType<JObject>()) renderer["motion_vector_default"] = motionDefault;
            double lightExposureDefault = SourceAsset.Number(profile["default_light_inverse_exposure_blend"]);
            foreach (var emitter in effect.Definition["emitters"]!.OfType<JObject>())
                if (emitter["light"] is JObject light) light["inverse_exposure_default"] = lightExposureDefault;
        }
        if (componentSource is null) importedSystems.Add(identity, effect);
        return effect;
    }
}
catch (Exception error) when (error is ConversionException or IOException or Newtonsoft.Json.JsonException or InvalidOperationException or ArgumentException or OodleTextureException)
{
    Console.Error.WriteLine(error.ToString());
    return 1;
}
