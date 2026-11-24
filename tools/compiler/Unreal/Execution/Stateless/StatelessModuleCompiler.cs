using System.Buffers.Binary;
using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution.Stateless;

/** Output presence controls native callback registration and the distributions inserted into shared data. */
internal sealed class StatelessModuleCompiler(JArray attributes, StatelessDistributionCompiler distributions,
    StatelessParameterCompiler parameters, int sourceVersion, StatelessDataBuilder data)
{
    private readonly StatelessPhysicsCompiler physics = new(distributions);

    internal JObject? Compile(string kind, JObject source)
    {
        if (kind == "NiagaraStatelessModule_InitializeParticle") {
            JObject result = Initialize(source);
            physics.Initialize(source);
            return result;
        }
        if (physics.Accept(kind, source)) return null;
        if (kind == "NiagaraStatelessModule_SolveVelocitiesAndForces") return physics.Build(this);
        if (kind == "NiagaraStatelessModule_InitialMeshOrientation")
            return new StatelessOrientationCompiler(this, distributions, sourceVersion).Compile(kind, source);
        if (kind == "NiagaraStatelessModule_CalculateAccurateVelocity")
            return new StatelessAttributeModuleCompiler(this, distributions).Compile(kind, source);
        if (source.Value<bool?>("bModuleEnabled") == false) return null;
        return kind switch {
            "NiagaraStatelessModule_ScaleColor" => Scale(source, "Color", null, new[] { 1f, 1f, 1f, 1f }, true),
            "NiagaraStatelessModule_ScaleMeshSize" => Scale(source, "Scale", "Previous.Scale", new[] { 1f, 1f, 1f }),
            "NiagaraStatelessModule_ScaleSpriteSize" => Scale(source, "SpriteSize", "Previous.SpriteSize", new[] { 1f, 1f }),
            "NiagaraStatelessModule_ScaleRibbonWidth" => Scale(source, "RibbonWidth", "Previous.RibbonWidth", new[] { 1f }),
            "NiagaraStatelessModule_CameraOffset" => Camera(source),
            "NiagaraStatelessModule_RotateAroundPoint" => StatelessOrbitCompiler.Compile(source, this, distributions),
            "NiagaraStatelessModule_ShapeLocation" => StatelessShapeCompiler.Compile(source, this, distributions),
            "NiagaraStatelessModule_MeshIndex" => StatelessMeshIndexCompiler.Compile(source, this, distributions, data),
            "NiagaraStatelessModule_MeshRotationRate" or "NiagaraStatelessModule_DecalAttributes" =>
                new StatelessOrientationCompiler(this, distributions, sourceVersion).Compile(kind, source),
            _ => new StatelessAttributeModuleCompiler(this, distributions).Compile(kind, source)
        };
    }

    private JObject Initialize(JObject source)
    {
        bool Uniform(JToken? value, string fallback) => StatelessDistributionCompiler.EnumValue(value?["Mode"], fallback)
            is "UniformConstant" or "UniformRange" or "3" or "5";
        JObject Range(string name, params float[] defaults) => distributions.Range(source[name], defaults, defaults);
        JArray position = StatelessDistributionCompiler.Packed(distributions.Distribution(source["InitialPositionDistribution"], new[] { 0f, 0f, 0f }));
        JArray color = StatelessDistributionCompiler.Packed(distributions.Distribution(source["ColorDistribution"], new[] { 1f, 1f, 1f, 1f }, color: true));
        JObject mass = Range("MassDistribution", 1f), sprite = Range("SpriteSizeDistribution", 10f, 10f),
            rotation = Range("SpriteRotationDistribution", 0f), mesh = Range("MeshScaleDistribution", 1f, 1f, 1f),
            ribbon = Range("RibbonWidthDistribution", 10f);
        return new JObject {
            ["position"] = position, ["color"] = color, ["ribbon_width"] = ribbon, ["sprite_size"] = sprite,
            ["rotation"] = rotation, ["mesh_scale"] = mesh, ["mass"] = mass,
            ["uniform_sprite"] = Uniform(source["SpriteSizeDistribution"], "NonUniformConstant"),
            ["uniform_mesh"] = Uniform(source["MeshScaleDistribution"], "NonUniformConstant"),
            ["channels"] = new JArray(new[] { "Position", "Color", "RibbonWidth", "SpriteSize", "SpriteRotation", "Scale",
                    "Previous.Position", "Previous.RibbonWidth", "Previous.SpriteSize", "Previous.SpriteRotation", "Previous.Scale" }
                .Select(name => Offset(name, 0)))
        };
    }

    private JObject? Scale(JObject source, string attribute, string? previous, float[] defaults, bool color = false)
    {
        int currentOffset = Offset(attribute, 0), previousOffset = previous is null ? -1 : Offset(previous, 0);
        if (currentOffset < 0 && previousOffset < 0) return null;
        JObject result = new() {
            ["current"] = currentOffset, ["previous"] = previousOffset,
            ["distribution"] = StatelessDistributionCompiler.Packed(distributions.Distribution(source["ScaleDistribution"], defaults, color))
        };
        if (!color) {
            int offset = -1;
            float[] scaleDefaults = (float[])defaults.Clone();
            string mode = StatelessDistributionCompiler.EnumValue(source["ScaleDistribution"]?["Mode"], "UniformConstant");
            if (mode is "UniformCurve" or "NonUniformCurve" or "7" or "8") {
                JObject binding = source["ScaleCurveRange"] as JObject ?? new();
                offset = parameters.Binding(binding["ResolvedParameter"]);
                if (binding["DefaultValue"] is { Type: not JTokenType.Null } raw) {
                    byte[] bytes = StatelessParameterCompiler.Bytes(raw);
                    if (bytes.Length != defaults.Length * sizeof(float))
                        throw new ConversionException($"Invalid stateless curve-scale default for {attribute}");
                    for (int index = 0; index < defaults.Length; index++)
                        scaleDefaults[index] = BitConverter.Int32BitsToSingle(BinaryPrimitives.ReadInt32LittleEndian(bytes.AsSpan(index * sizeof(float))));
                }
            }
            result["curve_scale_offset"] = offset;
            result["curve_scale_default"] = new JArray(StatelessDistributionCompiler.Words(scaleDefaults));
        }
        return result;
    }

    private JObject? Camera(JObject source)
    {
        int current = Offset("CameraOffset", 0), previous = Offset("Previous.CameraOffset", 0);
        if (current < 0 && previous < 0) return null;
        return new JObject { ["current"] = current, ["previous"] = previous,
            ["distribution"] = StatelessDistributionCompiler.Packed(distributions.Distribution(source["CameraOffsetDistribution"], new[] { 0f })) };
    }

    internal int Offset(string name, int type)
    {
        JObject? attribute = attributes.OfType<JObject>().FirstOrDefault(value => value.Value<int>("type") == type
            && (SourceNameOrder.Instance.Equals(value.Value<string>("name"), name) || SourceNameOrder.Instance.Equals(value.Value<string>("name"), "Particles." + name)));
        return attribute?.Value<int>("component") ?? -1;
    }
}
