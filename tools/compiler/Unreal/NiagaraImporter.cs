using LumenFx.Compiler.Unreal.Execution;
using LumenFx.Compiler.Unreal.Execution.Stateless;
using LumenFx.Compiler.Unreal.Geometry;
using LumenFx.Compiler.Unreal.Geometry.MeshDescription;
using LumenFx.Compiler.Unreal.Geometry.MeshBuild;
using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;
using LumenFx.Compiler.Output;
using LumenFx.Compiler.Unreal.Volumes;
using LumenFx.Compiler.Unreal.Components;

namespace LumenFx.Compiler.Unreal;

public sealed record ImportedEffect(JObject Definition, IReadOnlyDictionary<string, CompiledMaterial> Materials)
{
    internal IReadOnlyDictionary<string, StaticMeshSourceData> SourceMeshes { get; init; } = new Dictionary<string, StaticMeshSourceData>();
}

public sealed class NiagaraImporter(AssetLibrary library, VectorProgramCompiler programs,
    IReadOnlyDictionary<string, string>? textureAliases = null, string? oodleDataLibrary = null, MeshBuildTools? meshTools = null,
    MaterialPrecisionSettings? materialPrecision = null, Func<string, IReadOnlyList<string>>? cacheMaterials = null,
    IReadOnlyDictionary<string, string>? shaderDirectories = null)
{
    public ImportedEffect Convert(string package) => Convert(package, null);

    internal ImportedEffect Convert(string package, NiagaraComponentSource? component)
    {
        SourceAsset asset = library.Load(package);
        var system = asset.ResolveObject(package, value => value.ClassName == "NiagaraSystem");
        var handles = system.Properties["EmitterHandles"] as JArray ?? throw new ConversionException("Missing Niagara emitter handles");
        JObject execution = programs.Compile(asset, system, library, component);
        var componentSettings = component?.Settings();
        if (system.Properties.Value<bool?>("bOverrideTranslucencySortPriority") == true)
            (componentSettings ??= new JObject())["translucency_sort_priority"] =
                system.Properties.Value<int?>("TranslucencySortPriority") ?? 0;
        if (system.Properties.Value<bool?>("bOverrideTranslucencySortDistanceOffset") == true)
            (componentSettings ??= new JObject())["translucency_sort_distance_offset"] =
                system.Properties.Value<float?>("TranslucencySortDistanceOffset") ?? 0.0f;
        if (componentSettings is not null) execution["component"] = componentSettings;
        JObject exposedObjects = (JObject)execution["instance_stores"]!["override"]!;
        bool runtimeDynamicMaterials = execution.Descendants().OfType<JObject>().Any(value => value["material_instance_dynamic"] is JObject);
        execution["source_system"] = asset.ObjectPath(system.Index);
        var dynamicMeshMaterials = DynamicMeshInterfaceCompiler.Materials(execution).Distinct().ToList();
        var meshArrays = MeshArrayInterfaceCompiler.TakeSourceEntries(execution);
        if (dynamicMeshMaterials.Count != 0 && cacheMaterials is not null)
            foreach (string source in ResourcePackage.SimulationCacheSources(execution))
                dynamicMeshMaterials.AddRange(cacheMaterials(source).Select(material => (material, "")));
        Dictionary<string, CompiledMaterial> materials = new(StringComparer.Ordinal);
        Dictionary<(string Path, string Kind, bool TwoSided, bool Nanite, string Constants, string Bindings), string> materialNames = new();
        Dictionary<string, List<JObject>> emitterRenderers = new(StringComparer.Ordinal);
        Dictionary<string, JObject> emitterVersions = new(StringComparer.Ordinal);
        Dictionary<string, IReadOnlyList<StatelessTemplateSource.Module>> statelessModules = new(StringComparer.Ordinal);
        Dictionary<string, StaticMeshSourceData> sourceMeshes = new(StringComparer.Ordinal);
        StaticMeshSourceData ResolveMesh(string path) {
            if (!sourceMeshes.TryGetValue(path, out var mesh)) {
                mesh = StaticMeshSourceCompiler.Compile(library.Load(path), path, oodleDataLibrary);
                if (meshTools?.MeshDescriptionExecutable is not null)
                    mesh = NativeMeshRender.BuildAsync(mesh, meshTools).GetAwaiter().GetResult();
                else if (NativeMeshRender.Required(mesh))
                    throw new ConversionException($"Mesh {path} requires --mesh-native-tools to build its Nanite or distance field data");
                sourceMeshes.Add(path, mesh);
            }
            return mesh;
        }
        HashSet<string> rendererNames = handles.Cast<JObject>()
            .Select(value => value.Value<string>("Name")!.ToLowerInvariant()).ToHashSet(StringComparer.Ordinal);
        JArray emitters = new();
        JObject rendererObjects = new();
        RendererStaticVariables staticVariables = new(asset, system);
        foreach (JObject handle in handles)
        {
            if (handle.Value<bool?>("bIsEnabled") == false) continue;
            EmitterHandleSource.Resolved resolved = EmitterHandleSource.Resolve(asset, handle);
            SourceObject emitter = resolved.Source;
            JObject version = resolved.Settings;
            string target = resolved.Target;
            bool gpuTarget = target.EndsWith("GPUComputeSim", StringComparison.Ordinal);
            string emitterName = handle.Value<string>("Name")!.ToLowerInvariant();
            emitterVersions.Add(emitterName, version);
            if (resolved.Stateless) statelessModules.Add(emitterName, StatelessTemplateSource.Resolve(asset, version, library).Modules);
            List<JObject> rendererList = new();
            emitterRenderers.Add(emitterName, rendererList);
            JToken? rendererProperties = version["RendererProperties"];
            if (rendererProperties is null || rendererProperties.Type == JTokenType.Null) continue;
            if (rendererProperties is not JArray rendererReferences)
                throw new ConversionException($"Invalid Niagara renderer array: {emitter.Name}");
            foreach (int index in rendererReferences.Values<int>())
            {
                if (index == 0) continue;
                var renderer = asset.Object(index);
                JObject sourceRenderer = RendererAttributeBindings.LoadedProperties(asset, renderer);
                if (sourceRenderer.Value<bool?>("bIsEnabled") == false) continue;
                bool nativeNanite = renderer.ClassName == NaniteRendererSource.ClassName;
                string kind = renderer.ClassName switch {
                    "NiagaraRibbonRendererProperties" => "ribbon", "NiagaraSpriteRendererProperties" => "sprite",
                    "NiagaraMeshRendererProperties" or NaniteRendererSource.ClassName => "mesh",
                    "NiagaraLightRendererProperties" => "light",
                    "NiagaraDecalRendererProperties" => "decal",
                    "NiagaraVolumeRendererProperties" => "volume",
                    "NiagaraSplatRendererProperties" => "splat",
                    "NiagaraComponentRendererProperties" => "component",
                    var name => throw new ConversionException($"Unsupported Niagara renderer {name}: {emitter.Name}") };
                // Component rendering requires a stateful CPU emitter. A stateless renderer may still affect target selection.
                if (kind == "component" && (gpuTarget || resolved.Stateless
                        || sourceRenderer.Value<int>("TemplateComponent") == 0)) continue;
                if (gpuTarget && kind is ("light" or "decal")
                        && RendererAttributeBindings.SourceMode(sourceRenderer) != "Emitter") continue;
                string CompileMaterial(string path) => CompileMaterialKind(path, kind);
                string CompileLightFunction(string path) => CompileMaterialKind(path, "light_function");
                string CompileDecal(string path) => CompileMaterialKind(path, "decal");
                string CompileDefaultDecal() => CompileMaterialKind(Decals.DecalRendererCompiler.DefaultMaterial, "decal", false);
                string materialVariant = kind + "_" + renderer.Index;
                RendererMaterialConstants materialConstants = RendererMaterialConstants.Read(asset, sourceRenderer, staticVariables,
                    resolved.Namespace);
                RendererMaterialBindings materialBindings = RendererMaterialBindings.Read(asset, sourceRenderer,
                    resolved.Namespace, system, handle.Value<string>("Id"), library, resolved.Stateless ? version : null,
                    resolved.Stateless ? null : version);
                string CompileMaterialKind(string path, string materialKind, bool useRendererConstants = true) {
                    bool twoSided = materialKind == "ribbon" && sourceRenderer.Value<bool?>("bUseMaterialBackfaceCulling") != true;
                    var constants = useRendererConstants ? materialConstants : RendererMaterialConstants.Empty;
                    var bindings = useRendererConstants ? materialBindings : RendererMaterialBindings.Empty;
                    var materialKey = (path, materialKind, twoSided, nativeNanite && materialKind == "mesh", constants.Signature, bindings.Signature);
                    if (materialNames.TryGetValue(materialKey, out string? existing)) return existing;
                    string result = "material_" + materialNames.Count;
                    materialNames.Add(materialKey, result);
                    var factory = materialKind switch { "sprite" => MaterialVertexFactory.Sprite, "mesh" when nativeNanite => MaterialVertexFactory.Nanite,
                        "mesh" => MaterialVertexFactory.Mesh,
                        "decal" => MaterialVertexFactory.Decal, "light_function" => MaterialVertexFactory.LightFunction,
                        "volume" => MaterialVertexFactory.Volume, _ => MaterialVertexFactory.Ribbon };
                    var material = new MaterialCompiler(library, textureAliases, factory, materialPrecision, constants, bindings,
                        runtimeDynamicMaterials).ConfigureShaderSources(programs.EngineSourceRoot, shaderDirectories).Compile(path);
                    if (twoSided) material = material with { Cull = false };
                    materials.Add(result, material);
                    return result;
                }
                JObject? mesh = kind == "mesh"
                    ? MeshRendererCompiler.Compile(asset, sourceRenderer, ResolveMesh, CompileMaterial, nativeNanite) : null;
                if (mesh is not null) {
                    mesh["material_variant"] = materialVariant;
                    if (nativeNanite) mesh["native_nanite"] = NaniteRendererSource.Settings(asset, sourceRenderer, resolved.Namespace);
                    else MeshArrayInterfaceCompiler.BindRenderer(asset, sourceRenderer, resolved.Namespace, mesh, meshArrays, ResolveMesh, CompileMaterial);
                    foreach (JObject binding in (JArray)mesh["override_materials"]!) binding["material_variant"] = materialVariant;
                    RendererObjectDefaults.Merge(rendererObjects, RendererObjectDefaults.Compile(exposedObjects, mesh, ResolveMesh, CompileMaterial));
                    if (dynamicMeshMaterials.Count != 0
                        && ((JArray)mesh["entries"]!).OfType<JObject>().Any(entry => entry.Value<string>("asset_binding") is not null)) {
                        var dynamic = new JObject { [""] = CompileMaterial(MeshRendererCompiler.DefaultSurfaceMaterial) };
                        foreach (var (source, parameter) in dynamicMeshMaterials) {
                            if (!string.IsNullOrEmpty(source) && source != "None") dynamic[source] = CompileMaterial(source);
                            if (!string.IsNullOrEmpty(parameter) && parameter != "None")
                                RendererObjectDefaults.Merge(rendererObjects, RendererObjectDefaults.CompileMaterials(exposedObjects,
                                    parameter, CompileMaterial, materialVariant));
                        }
                        mesh["dynamic_materials"] = dynamic;
                    }
                }
                if (mesh is not null && mesh["meshes_binding"]!.Type == JTokenType.Null
                        && ((JArray)mesh["entries"]!).OfType<JObject>().All(value => value["asset_source"]!.Type == JTokenType.Null
                            && value["asset_binding"]!.Type == JTokenType.Null)) continue;
                JObject? volume = kind == "volume" ? VolumeRendererCompiler.Compile(sourceRenderer, materialVariant) : null;
                string? boundVolumeMaterial = null;
                if (volume is not null) {
                    RendererObjectDefaults.Merge(rendererObjects, RendererObjectDefaults.CompileMaterials(exposedObjects,
                        volume.Value<string>("material_binding"), path => {
                            string compiled = CompileMaterial(path);
                            boundVolumeMaterial ??= compiled;
                            return compiled;
                        }, materialVariant));
                }
                string? surfaceMaterialParameter = kind is "sprite" or "ribbon"
                    ? sourceRenderer["MaterialUserParamBinding"]?["Parameter"]?.Value<string>("name") : null;
                if (!string.IsNullOrEmpty(surfaceMaterialParameter) && surfaceMaterialParameter != "None") {
                    RendererObjectDefaults.Merge(rendererObjects, RendererObjectDefaults.CompileMaterials(exposedObjects,
                        surfaceMaterialParameter, CompileMaterial, materialVariant));
                } else surfaceMaterialParameter = null;
                string materialName = kind is "light" or "component" or "splat" ? "" : mesh is null
                    ? kind == "decal" && sourceRenderer.Value<int>("Material") == 0 ? CompileDefaultDecal()
                        : kind == "volume" && sourceRenderer.Value<int>("Material") == 0
                            ? boundVolumeMaterial ?? throw new ConversionException($"Niagara volume renderer has no saved material: {renderer.Name}")
                        : (kind is "sprite" or "ribbon") && sourceRenderer.Value<int>("Material") == 0
                            ? CompileMaterial(MeshRendererCompiler.DefaultSurfaceMaterial)
                            : CompileMaterial(asset.ObjectPath(sourceRenderer.Value<int>("Material")))
                    : ((JArray)mesh["entries"]!).OfType<JObject>().SelectMany(value => (JArray)value["materials"]!)
                        .OfType<JObject>().Select(value => value.Value<string>("material") ?? value.Value<string>("default_material")).FirstOrDefault(value => value is not null)
                        ?? CompileMaterial(MeshRendererCompiler.DefaultSurfaceMaterial);
                string rendererName = emitterName;
                if (rendererList.Count != 0)
                {
                    int suffix = rendererList.Count;
                    do rendererName = emitterName + "_renderer_" + suffix++; while (!rendererNames.Add(rendererName));
                }
                var converted = RendererDefinition(asset, rendererName, version, sourceRenderer, kind, materialName,
                        resolved.Namespace, renderer.ClassName, nativeNanite);
                if (kind is "sprite" or "ribbon" or "mesh")
                    converted["cast_shadows"] = sourceRenderer.Value<bool?>("bCastShadows") ?? true;
                if (surfaceMaterialParameter is not null)
                    converted["surface_material_binding"] = new JObject { ["parameter"] = surfaceMaterialParameter, ["variant"] = materialVariant };
                if (mesh is not null) converted["mesh"] = mesh;
                if (volume is not null) converted["volume"] = volume;
                if (kind == "splat") converted["splat"] = Splats.SplatRendererCompiler.Compile(sourceRenderer);
                if (kind == "light") converted["light"] = LumenFx.Compiler.Unreal.Lighting.LightRendererCompiler.Compile(sourceRenderer);
                if (kind == "decal") {
                    JObject decal = Decals.DecalRendererCompiler.Compile(sourceRenderer, CompileDefaultDecal);
                    decal["material_variant"] = materialVariant;
                    converted["decal"] = decal;
                    RendererObjectDefaults.Merge(rendererObjects, RendererObjectDefaults.CompileMaterials(exposedObjects,
                        decal.Value<string>("material_binding"), CompileDecal, materialVariant));
                }
                if (kind == "component") converted["component"] = Components.ComponentRendererCompiler.Compile(asset, sourceRenderer,
                        resolved.Namespace, library, CompileLightFunction, CompileDecal, ResolveMesh,
                        path => CompileMaterialKind(path, "mesh", false));
                if (kind == "sprite") converted["sprite_cutout"] = SpriteCutoutCompiler.Compile(library, asset, sourceRenderer, oodleDataLibrary);
                if (kind is not ("light" or "component" or "splat")) converted["custom_data"] = new JArray(materials[materialName].DynamicDefaults);
                converted["pixel_coverage"] = kind == "sprite"
                    ? NiagaraRenderer.SpriteCoverage(sourceRenderer, materials[materialName].AutomaticSpriteCoverageChannels,
                        programs.DefaultPixelCoverage)
                    : new JObject { ["channels"] = 0, ["blend"] = 0 };
                emitters.Add(converted);
                rendererList.Add(converted);
            }
        }
        foreach (JObject emitter in (JArray)execution["emitters"]!)
        {
            List<JObject> rendererList = emitter["disabled"] is JObject ? new() : emitterRenderers[emitter.Value<string>("name")!];
            if (rendererList.Count == 0)
            {
                emitter["pixel_coverage"] = new JObject { ["channels"] = 0, ["blend"] = 0 };
                emitter["sub_image_columns"] = 1; emitter["sub_image_rows"] = 1;
                emitter["sub_image_blend"] = false; emitter["sub_image_binding"] = null;
                emitter["sub_image_default"] = 0; emitter["sub_image_particle_binding"] = true;
                emitter["renderers"] = new JArray();
                continue;
            }
            JObject renderer = rendererList[0];
            emitter["pixel_coverage"] = renderer["pixel_coverage"]!.DeepClone();
            emitter["sub_image_columns"] = renderer["sub_image_columns"]!.DeepClone();
            emitter["sub_image_rows"] = renderer["sub_image_rows"]!.DeepClone();
            emitter["sub_image_blend"] = renderer["sub_image_blend"]!.DeepClone();
            bool hasSubImage = !renderer.Value<bool>("sub_image_particle_binding")
                || HasParticleAttribute(emitter, renderer.Value<string>("sub_image_binding"));
            emitter["sub_image_binding"] = hasSubImage ? renderer["sub_image_binding"]!.DeepClone() : null;
            emitter["sub_image_default"] = hasSubImage ? renderer["sub_image_default"]!.DeepClone() : 0;
            emitter["sub_image_particle_binding"] = renderer["sub_image_particle_binding"]!.DeepClone();
            JArray renderers = new();
            foreach (JObject item in rendererList)
            {
                bool present = !item.Value<bool>("sub_image_particle_binding")
                    || HasParticleAttribute(emitter, item.Value<string>("sub_image_binding"));
                bool uvPresent = !item.Value<bool>("uv_scale_particle_binding")
                    || HasParticleAttribute(emitter, item.Value<string>("uv_scale_binding"));
                bool dynamicPresent = !item.Value<bool>("dynamic_parameter_particle_binding")
                    || HasParticleAttribute(emitter, item.Value<string>("dynamic_parameter_binding"));
                JObject attributeBindings = (JObject)item["attribute_bindings"]!.DeepClone();
                foreach (string key in new[] { "DynamicMaterial1", "DynamicMaterial2", "DynamicMaterial3" })
                    if (attributeBindings[key] is JObject binding && binding.Value<bool>("particle"))
                        binding["channel_mask"] = DynamicChannelMask(asset, emitterVersions[emitter.Value<string>("name")!], binding.Value<string>("name")!, statelessModules.GetValueOrDefault(emitter.Value<string>("name")!));
                renderers.Add(new JObject {
                    ["name"] = item["name"]!.DeepClone(), ["pixel_coverage"] = item["pixel_coverage"]!.DeepClone(),
                    ["sub_image_columns"] = item["sub_image_columns"]!.DeepClone(), ["sub_image_rows"] = item["sub_image_rows"]!.DeepClone(),
                    ["sub_image_blend"] = item["sub_image_blend"]!.DeepClone(),
                    ["sub_image_binding"] = present ? item["sub_image_binding"]!.DeepClone() : null,
                    ["sub_image_default"] = present ? item["sub_image_default"]!.DeepClone() : 0,
                    ["sub_image_particle_binding"] = item["sub_image_particle_binding"]!.DeepClone(),
                    ["uv_scale_binding"] = uvPresent ? item["uv_scale_binding"]!.DeepClone() : null,
                    ["uv_scale_particle_binding"] = item["uv_scale_particle_binding"]!.DeepClone(),
                    ["dynamic_parameter_binding"] = dynamicPresent ? item["dynamic_parameter_binding"]!.DeepClone() : null,
                    ["dynamic_parameter_particle_binding"] = item["dynamic_parameter_particle_binding"]!.DeepClone(),
                    ["dynamic_parameter_channel_mask"] = dynamicPresent && item.Value<bool>("dynamic_parameter_particle_binding")
                        ? DynamicChannelMask(asset, emitterVersions[emitter.Value<string>("name")!], item.Value<string>("dynamic_parameter_binding")!, statelessModules.GetValueOrDefault(emitter.Value<string>("name")!)) : 15,
                    ["sort_only_when_translucent"] = item["sort_only_when_translucent"]!.DeepClone(),
                    ["custom_sort_binding"] = item["custom_sort_binding"]!.DeepClone(),
                    ["custom_sort_particle_binding"] = item["custom_sort_particle_binding"]!.DeepClone(),
                    ["sort_precision"] = item["sort_precision"]!.DeepClone(),
                    ["gpu_translucent_latency"] = item["gpu_translucent_latency"]!.DeepClone(),
                    ["distance_culling"] = item["distance_culling"]!.DeepClone(),
                    ["min_camera_distance"] = item["min_camera_distance"]!.DeepClone(),
                    ["max_camera_distance"] = item["max_camera_distance"]!.DeepClone(),
                    ["renderer_visibility"] = item["renderer_visibility"]!.DeepClone(),
                    ["allow_in_cull_proxies"] = item["allow_in_cull_proxies"]!.DeepClone(),
                    ["visibility_binding"] = item["visibility_binding"]!.DeepClone(),
                    ["visibility_particle_binding"] = item["visibility_particle_binding"]!.DeepClone(),
                    ["enabled_binding"] = item["enabled_binding"]!.DeepClone(),
                    ["source_mode"] = item["source_mode"]!.DeepClone(),
                    ["motion_vector_setting"] = item["motion_vector_setting"]!.DeepClone(),
                    ["motion_vector_default"] = item["motion_vector_default"]!.DeepClone(),
                    ["component"] = item["component"]?.DeepClone(),
                    ["surface_material_binding"] = item["surface_material_binding"]?.DeepClone(),
                    ["attribute_bindings"] = attributeBindings,
                    ["alignment"] = item["alignment"]!.DeepClone(),
                    ["facing_mode"] = item["facing_mode"]!.DeepClone(),
                    ["pivot_x"] = item["pivot_x"]!.DeepClone(), ["pivot_y"] = item["pivot_y"]!.DeepClone(),
                    ["facing_blend_min"] = item["facing_blend_min"]!.DeepClone(),
                    ["facing_blend_max"] = item["facing_blend_max"]!.DeepClone(),
                    ["ribbon_link_binding"] = item["ribbon_link_binding"]!.DeepClone(),
                    ["ribbon_id_binding"] = item["ribbon_id_binding"]!.DeepClone(),
                    ["ribbon_unique_fallback"] = item["ribbon_unique_fallback"]!.DeepClone() });
            }
            emitter["renderers"] = renderers;
        }
        foreach (JObject renderer in emitters.OfType<JObject>()) renderer.Remove("component");
        JObject properties = system.Properties;
        bool castShadow = properties.Value<bool?>("bOverrideCastShadow") == true
            ? properties.Value<bool?>("bCastShadow") == true : component?.Property("CastShadow")?.Value<bool>() == true;
        bool receivesDecals = properties.Value<bool?>("bOverrideReceivesDecals") == true
            ? properties.Value<bool?>("bReceivesDecals") == true : component?.Property("bReceivesDecals")?.Value<bool>() == true;
        bool renderCustomDepth = properties.Value<bool?>("bOverrideRenderCustomDepth") == true
            ? properties.Value<bool?>("bRenderCustomDepth") == true : component?.Property("bRenderCustomDepth")?.Value<bool>() == true;
        bool castsDynamicShadow = castShadow && (component?.Property("bCastDynamicShadow")?.Value<bool>() ?? true);
        bool castsContactShadow = castShadow && (component?.Property("bCastContactShadow")?.Value<bool>() ?? true);
        JToken? lightingChannels = component?.Property("LightingChannels");
        int lightingMask = ((lightingChannels?["bChannel0"]?.Value<bool>() ?? true) ? 1 : 0)
            | (lightingChannels?["bChannel1"]?.Value<bool>() == true ? 2 : 0)
            | (lightingChannels?["bChannel2"]?.Value<bool>() == true ? 4 : 0);
        // FNiagaraSceneProxy consumes the two override bits in the component's source application order.
        int customStencil = properties.Value<bool?>("bOverrideCustomDepthStencilWriteMask") == true
            ? properties.Value<int?>("CustomDepthStencilValue") ?? 0 : component?.Property("CustomDepthStencilValue")?.Value<int>() ?? 0;
        string sourceStencilMask = ((properties.Value<bool?>("bOverrideCustomDepthStencilValue") == true
            ? properties.Value<string>("CustomDepthStencilWriteMask") : component?.Property("CustomDepthStencilWriteMask")?.Value<string>())
            ?? "ERSM_Default").Split("::").Last();
        string stencilMask = sourceStencilMask switch {
            "ERSM_Default" => "default", "ERSM_255" => "all",
            "ERSM_1" or "ERSM_2" or "ERSM_4" or "ERSM_8" or "ERSM_16" or "ERSM_32" or "ERSM_64" or "ERSM_128"
                => $"bit_{sourceStencilMask[5..]}",
            _ => throw new ConversionException($"Unknown custom depth stencil mask {sourceStencilMask}")
        };
        return new ImportedEffect(new JObject { ["format_version"] = 1, ["materials"] = new JObject(),
            ["emitters"] = emitters, ["niagara"] = execution, ["renderer_objects"] = rendererObjects,
            ["receives_decals"] = receivesDecals,
            ["custom_depth"] = new JObject {
                ["enabled"] = renderCustomDepth, ["stencil"] = Math.Clamp(customStencil, 0, 255), ["stencil_mask"] = stencilMask
            },
            ["primitive_lighting"] = new JObject {
                ["channels"] = lightingMask, ["casts_dynamic_shadow"] = castsDynamicShadow, ["casts_contact_shadow"] = castsContactShadow,
                ["ray_tracing_far_field"] = component?.Property("bRayTracingFarField")?.Value<bool>() == true
            }
        }, materials) { SourceMeshes = sourceMeshes };
    }

    private static bool HasParticleAttribute(JObject emitter, string? name)
    {
        if (name is null or "None") return false;
        string bare = name.StartsWith("Particles.", StringComparison.OrdinalIgnoreCase) ? name[10..] : name;
        return ((JArray)emitter["attributes"]!).OfType<JObject>().Any(attribute => {
            string? attributeName = attribute.Value<string>("name");
            return SourceNameOrder.Instance.Equals(attributeName, name) || SourceNameOrder.Instance.Equals(attributeName, bare)
                || SourceNameOrder.Instance.Equals(attributeName, "Particles." + bare);
        });
    }

    private static JObject RendererDefinition(SourceAsset asset, string name, JObject version, JObject renderer,
            string kind, string material, string sourceEmitter, string rendererClass, bool nativeNanite)
    {
        string sourceMode = RendererAttributeBindings.SourceMode(renderer, RendererFieldBindings.DefaultSourceMode(rendererClass));
        var bindings = new RendererAttributeBindings(renderer, sourceEmitter, asset, sourceMode);
        return new JObject {
        ["name"] = name.ToLowerInvariant(), ["renderer"] = kind, ["material"] = material,
        ["integrator"] = "niagara",
        ["macro_uv_radius"] = kind == "sprite" ? SourceAsset.Number(renderer["MacroUVRadius"]) : 0,
        ["local_space"] = version.Value<bool>("bLocalSpace"), ["max_particles"] = 1000000,
        ["attach_head"] = false, ["scale_size_with_owner"] = false,
        ["width_segments"] = renderer.Value<int?>("WidthSegmentationCount") ?? 1,
        ["sub_image_columns"] = kind is "sprite" or "mesh" ? Math.Max(1, SourceAsset.Number(renderer["SubImageSize"]?["X"], 1)) : 1,
        ["sub_image_rows"] = kind is "sprite" or "mesh" ? Math.Max(1, SourceAsset.Number(renderer["SubImageSize"]?["Y"], 1)) : 1,
        ["sub_image_blend"] = kind is "sprite" or "mesh" && NiagaraSpriteRendererSource.SubImageBlend(NiagaraVersion(asset), renderer),
        ["sub_image_binding"] = kind is "sprite" or "mesh" ? bindings.Name("SubImageIndexBinding", "Particles.SubImageIndex") : null,
        ["sub_image_particle_binding"] = kind is not ("sprite" or "mesh") || bindings.Particle("SubImageIndexBinding"),
        ["uv_scale_binding"] = kind == "sprite" ? bindings.Name("UVScaleBinding", "Particles.UVScale") : null,
        ["uv_scale_particle_binding"] = kind != "sprite" || bindings.Particle("UVScaleBinding"),
        ["dynamic_parameter_binding"] = bindings.Name("DynamicMaterialBinding", "Particles.DynamicMaterialParameter"),
        ["dynamic_parameter_particle_binding"] = bindings.Particle("DynamicMaterialBinding"),
        ["sort_only_when_translucent"] = renderer.Value<bool?>("bSortOnlyWhenTranslucent") ?? true,
        ["custom_sort_binding"] = bindings.Name("CustomSortingBinding",
            NiagaraVersion(asset) < 87 ? "Particles.NormalizedAge" : "Particles.Age"),
        ["custom_sort_particle_binding"] = bindings.Particle("CustomSortingBinding"),
        ["sort_precision"] = NiagaraRenderer.SourceEnum(renderer, "SortPrecision", "Default", "Default", "Low", "High"),
        ["gpu_translucent_latency"] = (renderer.Value<string>("GpuTranslucentLatency") ?? "ProjectDefault").Split("::").Last() switch {
            "0" => "ProjectDefault", "1" => "Immediate", "2" => "Latent", var latency => latency
        },
        ["distance_culling"] = kind is "sprite" or "mesh" && (renderer.Value<bool?>("bEnableCameraDistanceCulling") ?? false),
        ["min_camera_distance"] = kind is "sprite" or "mesh" ? SourceAsset.Number(renderer["MinCameraDistance"], 0) : 0,
        ["max_camera_distance"] = kind is "sprite" or "mesh" ? SourceAsset.Number(renderer["MaxCameraDistance"], 1000) : 1000,
        ["renderer_visibility"] = renderer.Value<long?>("RendererVisibility") ?? 0,
        ["allow_in_cull_proxies"] = kind is not ("sprite" or "ribbon" or "mesh" or "light")
            || (renderer.Value<bool?>("bAllowInCullProxies") ?? true),
        ["visibility_binding"] = kind is "sprite" or "mesh" or "light" or "decal" or "volume" or "splat" ? bindings.Name("RendererVisibilityTagBinding", "Particles.VisibilityTag") : null,
        ["visibility_particle_binding"] = kind is not ("sprite" or "mesh" or "light" or "decal" or "volume" or "splat") || bindings.Particle("RendererVisibilityTagBinding"),
        ["enabled_binding"] = renderer["RendererEnabledBinding"] is null
            ? null : bindings.Name("RendererEnabledBinding", "None"),
        ["source_mode"] = sourceMode,
        ["motion_vector_setting"] = NiagaraRenderer.MotionVectorSetting(renderer),
        ["motion_vector_default"] = "Precise",
        ["attribute_bindings"] = RendererBindings(asset, renderer, sourceEmitter, sourceMode, bindings, kind, rendererClass, nativeNanite),
        ["alignment"] = kind == "sprite" ? NiagaraSpriteRendererSource.Alignment(MainVersion(asset), renderer) : "Unaligned",
        ["facing_mode"] = kind == "sprite" ? NiagaraSpriteRendererSource.Facing(MainVersion(asset), renderer) : "FaceCamera",
        ["pivot_x"] = SourceAsset.Number(renderer["PivotInUVSpace"]?["X"], .5),
        ["pivot_y"] = SourceAsset.Number(renderer["PivotInUVSpace"]?["Y"], .5),
        ["facing_blend_min"] = SourceAsset.Number(renderer["MinFacingCameraBlendDistance"], 0),
        ["facing_blend_max"] = SourceAsset.Number(renderer["MaxFacingCameraBlendDistance"], 0),
        ["ribbon_link_binding"] = kind == "ribbon" ? bindings.Name("RibbonLinkOrderBinding", "Particles.RibbonLinkOrder") : null,
        ["ribbon_id_binding"] = kind == "ribbon" ? bindings.Name("RibbonIdBinding", "Particles.RibbonID") : null,
        ["ribbon_unique_fallback"] = kind == "ribbon" && NiagaraVersion(asset) >= 79 && (renderer.Value<bool?>("bLinkOrderUseUniqueID") ?? true),
        ["sub_image_default"] = kind is "sprite" or "mesh" ? SubImageDefault(renderer) : 0,
        ["sort_order"] = renderer.Value<int?>("SortOrderHint") ?? 0,
        ["sort_mode"] = NiagaraRenderer.SourceEnum(renderer, "SortMode",
            kind is "sprite" or "mesh" && NiagaraVersion(asset) < 52 ? "ViewDistance" : "None",
            "None", "ViewDepth", "ViewDistance", "CustomAscending", "CustomDecending") switch {
            "None" => "none", "ViewDepth" => "view_depth", "ViewDistance" => "distance",
            "CustomAscending" => "custom_ascending", "CustomDecending" => "custom_descending",
            var sorting => throw new ConversionException($"Unknown Niagara renderer sorting mode: {sorting}") },
        ["ribbon"] = kind == "ribbon" ? NiagaraRenderer.Ribbon(asset, renderer, sourceEmitter, NiagaraVersion(asset)) : null
        };
    }

    private static JObject RendererBindings(SourceAsset asset, JObject renderer, string emitter, string mode,
            RendererAttributeBindings bindings, string kind, string rendererClass, bool nativeNanite)
    {
        JObject result = new();
        JObject Read(string field, string fallback) => new() {
            ["name"] = bindings.Name(field, fallback), ["particle"] = bindings.Particle(field)
        };
        if (kind is "volume" or "splat") {
            foreach (var field in RendererFieldBindings.Read(rendererClass)) {
                var variable = RendererFieldBindings.Resolve(asset, renderer, emitter, field, mode);
                string semantic = field.Attribute == "VisibilityTag" ? "RendererVisibilityTag" : field.Attribute;
                result[semantic] = new JObject {
                    ["name"] = variable?.Name ?? "None", ["particle"] = bindings.Particle(field.Name),
                    ["source_type"] = variable?.Type
                };
            }
            return result;
        }
        if (nativeNanite) {
            foreach (var (key, field, fallback, precise) in new[] {
                         ("Position", "PositionBinding", "Particles.Position", true),
                         ("Scale", "ScaleBinding", "Particles.Scale", true),
                         ("MeshOrientation", "RotationBinding", "Particles.MeshOrientation", true),
                         ("MeshIndex", "MeshIndexBinding", "Particles.MeshIndex", false),
                         ("RendererVisibilityTag", "RendererVisibilityTagBinding", "Particles.VisibilityTag", false) }) {
                bool particle = bindings.Particle(field);
                result[key] = new JObject { ["name"] = bindings.Name(field, fallback), ["particle"] = particle };
                if (precise) result["Previous" + key] = new JObject {
                    ["name"] = bindings.PreviousName(field, fallback), ["particle"] = particle };
            }
            return result;
        }
        if (kind == "decal") {
            foreach (var (key, field, fallback) in new[] {
                         ("Position", "PositionBinding", "Particles.Position"), ("Color", "DecalColorBinding", "Particles.Color"),
                         ("DecalOrientation", "DecalOrientationBinding", "Particles.DecalOrientation"),
                         ("DecalSize", "DecalSizeBinding", "Particles.DecalSize"), ("DecalFade", "DecalFadeBinding", "Particles.DecalFade"),
                         ("DecalSortOrder", "DecalSortOrderBinding", "Particles.UniqueID"),
                         ("DecalVisible", "DecalVisibleBinding", "Particles.DecalVisible"),
                         ("RendererVisibilityTag", "RendererVisibilityTagBinding", "Particles.VisibilityTag") })
                result[key] = new JObject { ["name"] = bindings.Name(field, fallback), ["particle"] = bindings.Particle(field) };
            return result;
        }
        if (kind == "light") {
            foreach (var (key, field, fallback) in new[] {
                         ("Position", "PositionBinding", "Particles.Position"), ("Color", "ColorBinding", "Particles.Color"),
                         ("Radius", "RadiusBinding", "Particles.LightRadius"), ("LightExponent", "LightExponentBinding", "Particles.LightExponent"),
                         ("VolumetricScattering", "VolumetricScatteringBinding", "Particles.LightVolumetricScattering"),
                         ("LightRenderingEnabled", "LightRenderingEnabledBinding", "Particles.LightEnabled"),
                         ("RendererVisibilityTag", "RendererVisibilityTagBinding", "Particles.VisibilityTag"),
                         ("SpecularScale", "SpecularScaleBinding", "Particles.LightSpecularScale"),
                         ("DiffuseScale", "DiffuseScaleBinding", "Particles.LightDiffuseScale") })
                result[key] = new JObject { ["name"] = bindings.Name(field, fallback), ["particle"] = bindings.Particle(field) };
            return result;
        }
        foreach (var (key, field, fallback) in new[] {
                     ("Position", "PositionBinding", "Particles.Position"),
                     ("Color", "ColorBinding", "Particles.Color"),
                     ("Velocity", "VelocityBinding", "Particles.Velocity"),
                     ("SpriteRotation", "SpriteRotationBinding", "Particles.SpriteRotation"),
                     ("SpriteSize", "SpriteSizeBinding", "Particles.SpriteSize"),
                     ("SpriteFacing", "SpriteFacingBinding", "Particles.SpriteFacing"),
                     ("SpriteAlignment", "SpriteAlignmentBinding", "Particles.SpriteAlignment"),
                     ("CameraOffset", "CameraOffsetBinding", "Particles.CameraOffset"),
                     ("PivotOffset", "PivotOffsetBinding", "Particles.PivotOffset"),
                     ("MaterialRandom", "MaterialRandomBinding", "Particles.MaterialRandom"),
                     ("NormalizedAge", "NormalizedAgeBinding", "Particles.NormalizedAge"),
                     ("DynamicMaterial1", "DynamicMaterial1Binding", "Particles.DynamicMaterialParameter1"),
                     ("DynamicMaterial2", "DynamicMaterial2Binding", "Particles.DynamicMaterialParameter2"),
                     ("DynamicMaterial3", "DynamicMaterial3Binding", "Particles.DynamicMaterialParameter3") })
            result[key] = new JObject { ["name"] = bindings.Name(field, fallback), ["particle"] = bindings.Particle(field) };
        if (kind == "ribbon")
        {
            var ribbonId = RendererFieldBindings.Resolve(asset, renderer, emitter, new("RibbonIdBinding", "RibbonID"), mode);
            result["RibbonID"] = new JObject {
                ["name"] = ribbonId?.Name ?? "None", ["particle"] = bindings.Particle("RibbonIdBinding"),
                ["source_type"] = ribbonId?.Type
            };
            foreach (var (key, field, fallback) in new[] {
                         ("RibbonWidth", "RibbonWidthBinding", "Particles.RibbonWidth"),
                         ("RibbonTwist", "RibbonTwistBinding", "Particles.RibbonTwist"),
                         ("RibbonFacing", "RibbonFacingBinding", "Particles.RibbonFacing"),
                         ("RibbonUVDistance", "RibbonUVDistance", "Particles.RibbonUVDistance"),
                         ("RibbonU0Override", "U0OverrideBinding", "Particles.RibbonU0Override"),
                         ("RibbonV0RangeOverride", "V0RangeOverrideBinding", "Particles.RibbonV0RangeOverride"),
                         ("RibbonU1Override", "U1OverrideBinding", "Particles.RibbonU1Override"),
                         ("RibbonV1RangeOverride", "V1RangeOverrideBinding", "Particles.RibbonV1RangeOverride") })
                result[key] = new JObject { ["name"] = bindings.Name(field, fallback), ["particle"] = bindings.Particle(field) };
        }
        if (kind == "mesh")
            foreach (var (key, field, fallback) in new[] { ("Scale", "ScaleBinding", "Particles.Scale"),
                         ("MeshOrientation", "MeshOrientationBinding", "Particles.MeshOrientation"), ("MeshIndex", "MeshIndexBinding", "Particles.MeshIndex") })
                result[key] = new JObject { ["name"] = bindings.Name(field, fallback), ["particle"] = bindings.Particle(field) };
        if (kind is "sprite" or "mesh")
            result["RendererVisibilityTag"] = Read("RendererVisibilityTagBinding", "Particles.VisibilityTag");
        string[] previous = kind == "mesh" ? new[] { "Position", "Scale", "MeshOrientation", "CameraOffset", "Velocity" } : kind == "ribbon"
            ? new[] { "Position", "RibbonWidth", "RibbonFacing", "RibbonTwist" }
            : new[] { "Position", "Velocity", "SpriteRotation", "SpriteSize", "SpriteFacing", "SpriteAlignment", "CameraOffset", "PivotOffset" };
        foreach (string key in previous)
        {
            JObject source = (JObject)result[key]!;
            string[] parts = source.Value<string>("name")!.Split('.');
            string name = parts.Any(part => part.Equals("Previous", StringComparison.OrdinalIgnoreCase))
                ? string.Join('.', parts)
                : parts.Length > 1 ? parts[0] + ".Previous." + string.Join('.', parts.Skip(1)) : "Previous." + parts[0];
            result["Previous" + key] = new JObject { ["name"] = name, ["particle"] = source.Value<bool>("particle") };
        }
        return result;
    }

    internal static int NiagaraVersion(SourceAsset asset)
    {
        const string niagaraVersion = "{FCF57AFA-5076-4283-B9A9-E658FFA02D32}";
        return (asset.Raw["CustomVersionContainer"] as JArray)?.OfType<JObject>()
            .SingleOrDefault(value => string.Equals(value.Value<string>("Key"), niagaraVersion, StringComparison.OrdinalIgnoreCase))
            ?.Value<int>("Version") ?? -1;
    }

    private static int MainVersion(SourceAsset asset)
    {
        const string mainVersion = "{697DD581-E64F-41AB-AA4A-51ECBEB7B628}";
        return (asset.Raw["CustomVersionContainer"] as JArray)?.OfType<JObject>()
            .SingleOrDefault(value => string.Equals(value.Value<string>("Key"), mainVersion, StringComparison.OrdinalIgnoreCase))
            ?.Value<int>("Version") ?? -1;
    }

    private static int DynamicChannelMask(SourceAsset asset, JObject version, string binding, IReadOnlyList<StatelessTemplateSource.Module>? modules)
    {
        if (modules is not null) {
            string name = binding.StartsWith("Particles.", StringComparison.OrdinalIgnoreCase) ? binding[10..] : binding;
            int parameter = Enumerable.Range(0, 4).FirstOrDefault(index => SourceNameOrder.Instance.Equals(name,
                "DynamicMaterialParameter" + (index == 0 ? "" : index.ToString())), -1);
            if (parameter < 0) return 15;
            foreach (StatelessTemplateSource.Module module in modules) {
                if (module.ClassName != "NiagaraStatelessModule_DynamicMaterialParameters") continue;
                JObject properties = module.Properties;
                if (!module.Enabled
                        || !(properties.Value<bool?>("bParameter" + parameter + "Enabled") ?? parameter == 0)) return 0;
                JObject values = properties["Parameter" + parameter] as JObject ?? new();
                int channels = 0;
                string[] names = { "X", "Y", "Z", "W" };
                for (int index = 0; index < names.Length; index++)
                    if (values.Value<bool?>("b" + names[index] + "ChannelEnabled") ?? true) channels |= 1 << index;
                return channels;
            }
            return 0;
        }
        int separator = binding.IndexOf('.');
        if (separator < 0) return 15;
        string prefix = binding[..(separator + 1)], suffix = binding[separator..] + "ChannelMask";
        int mask = 0;
        bool found = false;
        List<int> scripts = new();
        foreach (string property in new[] { "SpawnScriptProps", "UpdateScriptProps" })
            scripts.Add(version[property]?.Value<int>("Script") ?? 0);
        scripts.Add(version.Value<int?>("GPUComputeScript") ?? 0);
        if (version["EventHandlerScriptProps"] is JArray events)
            scripts.AddRange(events.OfType<JObject>().Select(value => value.Value<int?>("Script") ?? 0));
        foreach (int scriptIndex in scripts)
        {
            if (scriptIndex == 0) continue;
            JArray variables = asset.Object(scriptIndex).Properties["CachedScriptVM"]?["StaticVariablesWritten"] as JArray ?? new JArray();
            foreach (JObject variable in variables)
            {
                string name = variable.Value<string>("name") ?? "";
                if (!name.StartsWith(prefix, StringComparison.Ordinal) || !name.EndsWith(suffix, StringComparison.Ordinal)
                    || asset.Reference(variable["type"]!.Value<int>("ClassStructOrEnum")) != "/Script/Niagara.NiagaraInt32") continue;
                byte[] bytes = System.Convert.FromBase64String(variable.Value<string>("data")!);
                if (bytes.Length != 4) throw new ConversionException($"Invalid dynamic parameter channel mask {name}");
                mask |= System.Buffers.Binary.BinaryPrimitives.ReadInt32LittleEndian(bytes);
                found = true;
            }
        }
        return found ? mask & 15 : 15;
    }

    private static double SubImageDefault(JObject renderer)
    {
        JToken? value = renderer["SubImageIndexBinding"]?["DefaultValue"];
        if (value is null) return 0;
        if (value.Type == JTokenType.Float || value.Type == JTokenType.Integer) return SourceAsset.Number(value);
        string? data = value["data"]?.Value<string>() ?? value["VarData"]?.Value<string>();
        if (data is null) return 0;
        byte[] bytes = System.Convert.FromBase64String(data);
        if (bytes.Length < sizeof(float)) throw new ConversionException("Truncated SubImageIndexBinding default value");
        return BitConverter.Int32BitsToSingle(BitConverter.ToInt32(bytes, 0));
    }
}
