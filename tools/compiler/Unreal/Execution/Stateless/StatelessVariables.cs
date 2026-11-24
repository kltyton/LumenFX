using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution.Stateless;

/** Typed output declarations used by both dataset layout and shader component permutations. */
internal static class StatelessVariables
{
    internal sealed record Variable(string Name, string Type)
    {
        internal bool Matches(Variable other) => Type == other.Type && SourceNameOrder.Instance.Equals(Name, other.Name);
        internal int Storage => Type == "/Script/Niagara.NiagaraInt32" ? 1 : 0;
        internal int Count => StatelessParameterCompiler.Width(Type);
    }

    internal static Variable Named(string name)
    {
        return new(name, NiagaraAttributeTypes.Type(name));
    }

    internal static IReadOnlyList<Variable> Outputs(string moduleClass, JObject properties, bool all)
    {
        List<Variable> result = new();
        void Add(params string[] names) { foreach (string name in names) AddUnique(result, Named(name)); }
        void Conditional(string flag, params string[] names) { if (all || properties.Value<bool?>(flag) == true) Add(names); }
        switch (moduleClass) {
            case "NiagaraStatelessModule_InitializeParticle":
                Add("Position", "Color", "SpriteSize", "SpriteRotation", "Scale", "Previous.Position", "Previous.SpriteSize", "Previous.SpriteRotation", "Previous.Scale");
                Conditional("bWriteRibbonWidth", "RibbonWidth", "Previous.RibbonWidth");
                break;
            case "NiagaraStatelessModule_InitialMeshOrientation":
            case "NiagaraStatelessModule_MeshRotationRate": Add("MeshOrientation", "Previous.MeshOrientation"); break;
            case "NiagaraStatelessModule_ShapeLocation":
            case "NiagaraStatelessModule_RotateAroundPoint": Add("Position", "Previous.Position"); break;
            case "NiagaraStatelessModule_SolveVelocitiesAndForces": Add("Position", "Velocity", "Previous.Position", "Previous.Velocity"); break;
            case "NiagaraStatelessModule_CalculateAccurateVelocity": Add("Velocity", "Previous.Velocity"); break;
            case "NiagaraStatelessModule_CameraOffset": Add("CameraOffset", "Previous.CameraOffset"); break;
            case "NiagaraStatelessModule_DecalAttributes":
                Conditional("bApplyOrientation", "DecalOrientation"); Conditional("bApplySize", "DecalSize"); Conditional("bApplyFade", "DecalFade");
                break;
            case "NiagaraStatelessModule_LightAttributes":
                Conditional("bApplyRadius", "LightRadius"); Conditional("bApplyFalloffExponent", "LightExponent");
                Conditional("bApplyDiffuseScale", "LightDiffuseScale"); Conditional("bApplySpecularScale", "LightSpecularScale");
                Conditional("bApplyVolumetricScattering", "LightVolumetricScattering");
                break;
            case "NiagaraStatelessModule_DynamicMaterialParameters":
                for (int index = 0; index < 4; index++) {
                    JObject channels = properties["Parameter" + index] as JObject ?? new();
                    bool any = new[] { "X", "Y", "Z", "W" }.Any(channel => channels.Value<bool?>("b" + channel + "ChannelEnabled") != false);
                    if (all || (properties.Value<bool?>("bParameter" + index + "Enabled") ?? index == 0) && any)
                        Add("DynamicMaterialParameter" + (index == 0 ? "" : index.ToString()));
                }
                break;
            case "NiagaraStatelessModule_MeshIndex": Add("MeshIndex"); break;
            case "NiagaraStatelessModule_ScaleColor": Add("Color"); break;
            case "NiagaraStatelessModule_ScaleRibbonWidth": Add("RibbonWidth", "Previous.RibbonWidth"); break;
            case "NiagaraStatelessModule_ScaleSpriteSize":
            case "NiagaraStatelessModule_ScaleSpriteSizeBySpeed": Add("SpriteSize", "Previous.SpriteSize"); break;
            case "NiagaraStatelessModule_ScaleMeshSize":
            case "NiagaraStatelessModule_ScaleMeshSizeBySpeed": Add("Scale", "Previous.Scale"); break;
            case "NiagaraStatelessModule_SpriteFacingAndAlignment":
                if (all || properties.Value<bool?>("bSpriteFacingEnabled") != false) Add("SpriteFacing", "Previous.SpriteFacing");
                Conditional("bSpriteAlignmentEnabled", "SpriteAlignment", "Previous.SpriteAlignment");
                break;
            case "NiagaraStatelessModule_SpriteRotationRate": Add("SpriteRotation", "Previous.SpriteRotation"); break;
            case "NiagaraStatelessModule_SubUVAnimation": Add("SubImageIndex"); break;
            case "NiagaraStatelessModule_AddVelocity":
            case "NiagaraStatelessModule_AccelerationForce":
            case "NiagaraStatelessModule_CurlNoiseForce":
            case "NiagaraStatelessModule_Drag":
            case "NiagaraStatelessModule_GravityForce":
            case "NiagaraStatelessModule_ApplyOwnerScaleToAttributes": break;
            default: throw new ConversionException($"Missing stateless output declaration for {moduleClass}");
        }
        return result;
    }

    internal static bool GpuOutputs(string moduleClass) => moduleClass is not ("NiagaraStatelessModule_LightAttributes" or "NiagaraStatelessModule_DecalAttributes");

    internal static void AddUnique(List<Variable> variables, Variable value)
    {
        if (!variables.Any(existing => existing.Matches(value))) variables.Add(value);
    }

    internal static JArray Layout(IEnumerable<Variable> variables)
    {
        JArray result = new();
        int[] offsets = new int[2];
        foreach (Variable variable in variables) {
            int storage = variable.Storage, count = variable.Count;
            result.Add(new JObject { ["name"] = variable.Name, ["source_type"] = variable.Type,
                ["type"] = storage, ["component"] = offsets[storage], ["count"] = count });
            offsets[storage] += count;
        }
        return result;
    }
}
