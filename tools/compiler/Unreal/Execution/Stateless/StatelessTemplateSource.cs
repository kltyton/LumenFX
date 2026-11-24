using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution.Stateless;

/** Restores editor template order and cooked CDO slots before assigning module seeds. */
internal sealed record StatelessTemplateSource(string Path, bool Cooked, bool DefaultShader, IReadOnlyList<string> Classes,
    IReadOnlyList<StatelessTemplateSource.Module> Modules)
{
    internal sealed record Module(string ClassName, JObject Properties, bool ClassDefault)
    {
        internal bool Enabled => !ClassDefault && Properties.Value<bool?>("bModuleEnabled") != false;
    }

    private const string Prefix = "NiagaraStatelessModule_";
    private static readonly string[] DefaultModules = {
        "InitializeParticle", "InitialMeshOrientation", "ShapeLocation", "AddVelocity", "AccelerationForce",
        "CurlNoiseForce", "Drag", "GravityForce", "ApplyOwnerScaleToAttributes", "SolveVelocitiesAndForces",
        "CameraOffset", "DecalAttributes", "DynamicMaterialParameters", "LightAttributes", "MeshIndex",
        "MeshRotationRate", "ScaleColor", "ScaleRibbonWidth", "ScaleSpriteSize", "ScaleSpriteSizeBySpeed",
        "ScaleMeshSize", "ScaleMeshSizeBySpeed", "SpriteFacingAndAlignment", "SpriteRotationRate", "SubUVAnimation"
    };

    internal static StatelessTemplateSource Resolve(SourceAsset asset, JObject source, AssetLibrary? library)
    {
        bool cooked = (asset.Raw.Value<string>("PackageFlags") ?? "").Contains("PKG_Cooked", StringComparison.Ordinal);
        int reference = source.Value<int?>("EmitterTemplate") ?? 0;
        int legacy = source.Value<int?>("EmitterTemplateClass_DEPRECATED") ?? source.Value<int?>("EmitterTemplateClass") ?? 0;
        if (!cooked && legacy != 0) reference = legacy;
        string path = asset.ObjectPath(reference);
        if (reference == 0) return new(path, cooked, false, Array.Empty<string>(), Array.Empty<Module>());

        IReadOnlyList<string> classes;
        bool defaultShader = path is "/Script/Niagara.Default__NiagaraStatelessEmitterDefault" or "/Script/Niagara.NiagaraStatelessEmitterDefault"
            || reference > 0 && asset.Object(reference).ClassName == "NiagaraStatelessEmitterDefault";
        if (defaultShader) {
            classes = DefaultModules.Select(name => Prefix + name).ToArray();
        } else if (path is "/Script/Niagara.Default__NiagaraStatelessEmitterTemplate" or "/Script/Niagara.NiagaraStatelessEmitterTemplate") {
            classes = Array.Empty<string>();
        } else {
            SourceAsset templateAsset = reference > 0 ? asset : library?.Load(path)
                ?? throw new ConversionException($"A source library is required for stateless template {path}");
            SourceObject template = reference > 0 ? asset.Object(reference)
                : templateAsset.ResolveObject(path, value => value.ClassName == "NiagaraStatelessEmitterTemplate");
            classes = (template.Properties["Modules"] as JArray ?? new JArray()).Values<int>()
                .Where(value => value != 0).Select(value => templateAsset.Reference(value).Split('.').Last()).ToArray();
        }

        int[] references = (source["Modules"] as JArray ?? new JArray()).Values<int>().ToArray();
        List<Module> modules = new();
        if (cooked) {
            if (references.Length > classes.Count)
                throw new ConversionException($"Stateless modules exceed template slots: {asset.Name}");
            for (int index = 0; index < references.Length; index++)
                modules.Add(references[index] == 0 ? Default(classes[index], true) : Saved(asset.Object(references[index])));
        } else {
            List<SourceObject> remaining = references.Where(value => value != 0).Select(asset.Object).ToList();
            foreach (string moduleClass in classes) {
                int index = remaining.FindIndex(value => value.ClassName == moduleClass);
                if (index < 0) modules.Add(Default(moduleClass, false));
                else {
                    modules.Add(Saved(remaining[index]));
                    remaining[index] = remaining[^1];
                    remaining.RemoveAt(remaining.Count - 1);
                }
            }
        }
        return new(path, cooked, defaultShader, classes, modules);
    }

    private static Module Saved(SourceObject source)
    {
        bool classDefault = (source.Raw.Value<string>("ObjectFlags") ?? "").Contains("RF_ClassDefaultObject", StringComparison.Ordinal);
        JObject properties = source.Properties;
        if (classDefault) properties["bModuleEnabled"] = false;
        return new(source.ClassName, properties, classDefault);
    }

    private static Module Default(string moduleClass, bool classDefault)
    {
        bool mandatory = moduleClass is "NiagaraStatelessModule_InitializeParticle" or "NiagaraStatelessModule_SolveVelocitiesAndForces" or "NiagaraStatelessModule_CalculateAccurateVelocity";
        return new(moduleClass, new JObject { ["bModuleEnabled"] = !classDefault && mandatory }, classDefault);
    }
}
