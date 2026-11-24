using LumenFx.Compiler.Unreal.Execution;
using LumenFx.Compiler.Unreal.Audio;
using LumenFx.Compiler.Unreal.Geometry;
using LumenFx.Compiler.Unreal.Geometry.MeshDescription;
using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Components;

internal static class ComponentRendererCompiler
{
    internal static JObject Compile(SourceAsset asset, JObject source, string sourceEmitter, AssetLibrary library,
                                     Func<string, string> compileLightFunction, Func<string, string> compileDecal,
                                     Func<string, StaticMeshSourceData> resolveMesh, Func<string, string> compileMeshMaterial)
    {
        int templateIndex = source.Value<int>("TemplateComponent");
        SourceObject template = asset.Object(templateIndex);
        JObject properties = template.Properties;
        JObject serializedTemplate = asset.NativeProperties(template, out _, properties.Properties().Select(value => value.Name).ToArray());
        string sourceClass = asset.ObjectPath(template.Raw.Value<int>("ClassIndex"));
        if (sourceClass == "/Script/Engine.StaticMeshComponent")
        {
            int mesh = properties.Value<int?>("StaticMesh") ?? 0;
            var overrides = properties["OverrideMaterials"] as JArray ?? new JArray();
            serializedTemplate["lumenfx_static_mesh"] = MeshRendererCompiler.Compile(asset, new JObject {
                ["Meshes"] = new JArray(new JObject {
                    ["Mesh"] = mesh,
                    ["LODMode"] = "ComponentOrigin"
                }),
                ["bOverrideMaterials"] = overrides.Count != 0,
                ["OverrideMaterials"] = overrides.DeepClone()
            }, resolveMesh, compileMeshMaterial);
            serializedTemplate["lumenfx_static_mesh"]!["scene_transforms"] = "legacy";
            serializedTemplate["lumenfx_static_mesh_fallback"] = mesh == 0 ? null
                : compileMeshMaterial(MeshRendererCompiler.DefaultSurfaceMaterial);
        }
        if (sourceClass == "/Script/Engine.DecalComponent")
        {
            int materialReference = properties.Value<int?>("DecalMaterial") ?? 0;
            serializedTemplate["lumenfx_decal_material"] = compileDecal(materialReference == 0
                ? Decals.DecalRendererCompiler.DefaultMaterial : asset.ObjectPath(materialReference));
        }
        if (sourceClass == "/Script/Engine.RectLightComponent")
        {
            string texturePath = ObjectPropertyReaderCompiler.ObjectPath(asset, properties["SourceTexture"]);
            if (texturePath.Length != 0)
            {
                var textureAsset = library.Load(texturePath);
                var texture = textureAsset.ResolveObject(texturePath, value => value.ClassName == "Texture2D");
                if (texture.Properties.Value<bool?>("VirtualTextureStreaming") == true)
                    throw new ConversionException($"Rect light source does not support virtual textures: {texturePath}");
                serializedTemplate["lumenfx_rect_source"] = new JObject { ["source_texture"] = texturePath };
            }
        }
        if (sourceClass == "/Script/Engine.AudioComponent") {
            var audio = new JObject();
            string sound = ObjectPropertyReaderCompiler.ObjectPath(asset, properties["Sound"]);
            if (sound.Length != 0) audio["sound_source"] = sound;
            var defaultWaveSources = new JObject();
            foreach (var parameter in (properties["DefaultParameters"] as JArray ?? new JArray()).OfType<JObject>()) {
                string? name = parameter.Value<string>("ParamName");
                int reference = parameter.Value<int?>("ObjectParam") ?? 0;
                string type = parameter.Value<string>("ParamType")?.Split("::").Last() ?? "None";
                if (!string.IsNullOrEmpty(name) && reference != 0 && (type is "None" or "0" or "Object" or "5")
                        && asset.ReferencedClass(reference) == "SoundWave")
                    defaultWaveSources[name] = asset.ObjectPath(reference);
            }
            if (defaultWaveSources.Count > 0) audio["default_wave_sources"] = defaultWaveSources;
            if (properties.Value<bool?>("bOverrideAttenuation") == true)
                audio["attenuation"] = AudioAcousticsCompiler.Attenuation(asset,
                    properties["AttenuationOverrides"] as JObject ?? new JObject(), library);
            else {
                string attenuation = ObjectPropertyReaderCompiler.ObjectPath(asset, properties["AttenuationSettings"]);
                if (attenuation.Length != 0) audio["attenuation_source"] = attenuation;
            }
            var concurrency = (properties["ConcurrencySet"] as JArray ?? new JArray())
                .Select(value => ObjectPropertyReaderCompiler.ObjectPath(asset, value)).Where(value => value.Length != 0).ToList();
            string legacy = ObjectPropertyReaderCompiler.ObjectPath(asset, properties["ConcurrencySettings_DEPRECATED"]);
            if (legacy.Length != 0) concurrency.Add(legacy);
            audio["component_concurrency_sources"] = new JArray(concurrency.Distinct(SourceNameOrder.Instance));
            serializedTemplate["audio_component"] = audio;
        }
        int lightFunctionReference = properties.Value<int?>("LightFunctionMaterial") ?? 0;
        if (lightFunctionReference != 0)
            serializedTemplate["lumenfx_light_function"] = compileLightFunction(asset.ObjectPath(lightFunctionReference));
        int iesReference = properties.Value<int?>("IESTexture") ?? 0;
        if (iesReference != 0)
        {
            string path = asset.ObjectPath(iesReference);
            SourceAsset owner = iesReference > 0 ? asset : library.Load(path);
            SourceObject profile = iesReference > 0 ? asset.Object(iesReference)
                : owner.ResolveObject(path, value => value.ClassName == "TextureLightProfile");
            if (profile.ClassName != "TextureLightProfile") throw new ConversionException($"Invalid IES profile class: {path}");
            JObject profileProperties = profile.Properties;
            serializedTemplate["lumenfx_ies"] = new JObject {
                ["source_texture"] = path,
                ["brightness"] = SourceAsset.Number(profileProperties["Brightness"], -1),
                ["texture_multiplier"] = SourceAsset.Number(profileProperties["TextureMultiplier"])
            };
        }
        var attributes = new RendererAttributeBindings(source, sourceEmitter, asset);
        JArray bindings = new();
        foreach (JObject binding in (source["PropertyBindings"] as JArray ?? new JArray()).OfType<JObject>())
        {
            JObject attribute = binding["AttributeBinding"] as JObject
                ?? throw new ConversionException("Component property binding has no attribute definition");
            JObject? variableType = attribute["ParamMapVariable"]?["type"] as JObject;
            string? sourceType = Type(asset, variableType, true);
            // Unreadable bindings still determine the first setter mapping for their property.
            if (sourceType is not ("float" or "int" or "bool" or "float2" or "float3" or "float4"
                    or "linear_color" or "quat_float" or "position")) sourceType = null;
            var attributeNames = new RendererAttributeBindings(new JObject { ["AttributeBinding"] = attribute.DeepClone() }, sourceEmitter, asset);
            string name = attributeNames.Name("AttributeBinding", "None");
            bindings.Add(new JObject {
                ["attribute"] = name, ["source_type"] = sourceType,
                ["property"] = binding.Value<string>("PropertyName")
                    ?? throw new ConversionException("Component property binding has no target name"),
                ["property_type"] = Type(asset, binding["PropertyType"] as JObject),
                ["metadata_setter"] = binding.Value<string>("MetadataSetterName") is { } setter && setter != "None" ? setter : null,
                ["setter_defaults"] = SetterDefaults(binding["PropertySetterParameterDefaults"])
            });
        }
        return new JObject {
            ["source_class"] = sourceClass,
            ["template_key"] = asset.ObjectPath(templateIndex),
            ["template"] = serializedTemplate,
            ["count_limit"] = source.Value<uint?>("ComponentCountLimit") ?? 15,
            ["assign_by_particle_id"] = source.Value<bool?>("bAssignComponentsOnParticleID") ?? true,
            ["first_particle_frame_only"] = source.Value<bool?>("bCreateComponentFirstParticleFrame") ?? false,
            ["activate_only_new"] = source.Value<bool?>("bOnlyActivateNewlyAquiredComponents") ?? true,
            ["inherit_owner_visibility"] = source.Value<bool?>("bInheritOwnerVisibility") ?? true,
            ["enabled_attribute"] = attributes.Name("EnabledBinding", "Particles.ComponentsEnabled"),
            ["visibility_attribute"] = attributes.Name("RendererVisibilityTagBinding", "Particles.VisibilityTag"),
            ["visibility_tag"] = source.Value<int?>("RendererVisibility") ?? 0,
            ["bindings"] = bindings
        };
    }

    private static JObject SetterDefaults(JToken? source)
    {
        JObject result = new();
        if (source is null) return result;
        if (source is not JArray pairs) throw new ConversionException("Component setter defaults must be a native key/value map");
        foreach (JToken pair in pairs)
        {
            if (pair is not JArray { Count: 2 } entry || entry[0].Type != JTokenType.String || entry[1].Type != JTokenType.String)
                throw new ConversionException("Component setter default map contains an invalid string pair");
            result.Add(entry[0].Value<string>()!, entry[1].DeepClone());
        }
        return result;
    }

    private static string? Type(SourceAsset asset, JObject? definition, bool particleSource = false)
    {
        if (definition is null) return null;
        var loaded = NiagaraTypeSource.Read(asset, definition);
        if (loaded.SourceReference == 0) return null;
        string path = loaded.Path;
        return path switch {
            "/Script/Niagara.NiagaraFloat" => "float", "/Script/Niagara.NiagaraInt32" => "int",
            "/Script/Niagara.NiagaraBool" => "bool", "/Script/Niagara.NiagaraPosition" => "position",
            "/Script/Niagara.NiagaraDouble" => "double",
            "/Script/CoreUObject.Vector2f" => "float2", "/Script/CoreUObject.Vector3f" => "float3",
            "/Script/CoreUObject.Vector4f" => "float4", "/Script/CoreUObject.Quat4f" => "quat_float",
            "/Script/CoreUObject.LinearColor" => "linear_color", "/Script/CoreUObject.Color" => "byte_color",
            "/Script/CoreUObject.Rotator" or "/Script/CoreUObject.Rotator3d" => "rotator",
            "/Script/CoreUObject.Vector2D" or "/Script/CoreUObject.Vector2d" => "double2",
            "/Script/CoreUObject.Vector" or "/Script/CoreUObject.Vector3d" => "double3",
            "/Script/CoreUObject.Vector4" or "/Script/CoreUObject.Vector4d" => "double4",
            "/Script/CoreUObject.Quat" or "/Script/CoreUObject.Quat4d" => "quat_double",
            _ => particleSource ? null : throw new ConversionException($"Unknown component binding type {path}")
        };
    }
}
