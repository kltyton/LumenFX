using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution.Stateless;

/** Force modules update transient build state; each solver captures the state at its actual module position. */
internal sealed class StatelessPhysicsCompiler
{
    private readonly StatelessDistributionCompiler distributions;
    private readonly JObject state;

    internal StatelessPhysicsCompiler(StatelessDistributionCompiler distributions)
    {
        this.distributions = distributions;
        JObject Literal(params float[] values) => distributions.LiteralRange(null, values);
        state = new JObject {
            ["mass"] = Literal(1f), ["drag"] = Literal(0f), ["linear_velocity"] = Literal(0f, 0f, 0f),
            ["linear_scale"] = Literal(1f), ["linear_space"] = 2,
            ["wind"] = Literal(0f, 0f, 0f), ["wind_space"] = 2,
            ["acceleration"] = Literal(0f, 0f, 0f), ["acceleration_space"] = 2,
            ["gravity"] = Literal(0f, 0f, 0f), ["cone_enabled"] = false,
            ["cone_space"] = 2, ["cone_use_rotator"] = true, ["cone_rotator"] = Literal(0f, 0f, 0f),
            ["cone_direction"] = Literal(1f, 0f, 0f), ["cone_velocity"] = Literal(0f), ["cone_scale"] = Literal(1f),
            ["cone_outer_angle"] = 0f, ["cone_inner_angle"] = 0f, ["cone_falloff"] = 0f,
            ["point_enabled"] = false, ["point_space"] = 2, ["point_velocity"] = Literal(0f), ["point_scale"] = Literal(1f),
            ["point_origin"] = new JArray(0f, 0f, 0f), ["noise_enabled"] = false,
            ["noise_strength"] = 0f, ["noise_frequency"] = 0f, ["noise_offset"] = Literal(0f, 0f, 0f)
        };
    }

    internal void Initialize(JObject source) => state["mass"] = distributions.LiteralRange(source["MassDistribution"], 1f);

    internal bool Accept(string kind, JObject source)
    {
        if (kind is not ("NiagaraStatelessModule_AddVelocity" or "NiagaraStatelessModule_AccelerationForce" or
            "NiagaraStatelessModule_Drag" or "NiagaraStatelessModule_GravityForce" or "NiagaraStatelessModule_CurlNoiseForce")) return false;
        if (source.Value<bool?>("bModuleEnabled") == false) return true;
        switch (kind) {
            case "NiagaraStatelessModule_AddVelocity": AddVelocity(source); break;
            case "NiagaraStatelessModule_AccelerationForce":
                state["acceleration_space"] = Space(source["CoordinateSpace"]);
                AddLiteral((JObject)state["acceleration"]!, distributions.LiteralRange(source["AccelerationDistribution"], 0f, 0f, 0f));
                break;
            case "NiagaraStatelessModule_Drag": state["drag"] = distributions.LiteralRange(source["DragDistribution"], 1f); break;
            case "NiagaraStatelessModule_GravityForce": state["gravity"] = distributions.LiteralRange(source["GravityDistribution"], 0f, 0f, -980f); break;
            case "NiagaraStatelessModule_CurlNoiseForce":
                state["noise_enabled"] = true;
                state["noise_strength"] = (float)SourceAsset.Number(source["NoiseStrength"], 10f);
                state["noise_frequency"] = (float)SourceAsset.Number(source["NoiseFrequency"], 50f);
                state["noise_offset"] = distributions.LiteralRange(source.Value<bool?>("bBiasNoiseFieldEnabled") == true ? source["BiasNoiseField"] : null, 0f, 0f, 0f);
                break;
        }
        return true;
    }

    private void AddVelocity(JObject source)
    {
        string type = StatelessDistributionCompiler.EnumValue(source["VelocityType"], "Linear");
        int space = Space(source["CoordinateSpace"]);
        switch (type) {
            case "Linear": case "0":
                // The native first conversion is discarded but still inserts any expression binding into the store.
                _ = distributions.Range(source["LinearVelocityDistribution"], new[] { 0f, 0f, 100f }, new[] { 0f, 0f, 0f });
                state["linear_space"] = space;
                state["linear_velocity"] = distributions.Range(source["LinearVelocityDistribution"], new[] { 0f, 0f, 100f }, new[] { 0f, 0f, 0f });
                state["linear_scale"] = distributions.Range(source["LinearVelocityScale"], new[] { 1f }, new[] { 1f });
                break;
            case "FromPoint": case "1":
                state["point_enabled"] = true; state["point_space"] = space;
                state["point_velocity"] = distributions.Range(source["PointVelocityDistribution"], new[] { 100f }, new[] { 0f });
                state["point_scale"] = distributions.Range(source["PointVelocityScale"], new[] { 1f }, new[] { 1f });
                state["point_origin"] = new JArray(StatelessParameterCompiler.Components(source["PointOrigin"], new[] { 0f, 0f, 0f }));
                break;
            case "InCone": case "2":
                bool rotator = StatelessDistributionCompiler.EnumValue(source["ConeRotationType"], "Rotation") is "Rotation" or "0";
                state["cone_enabled"] = true; state["cone_space"] = space; state["cone_use_rotator"] = rotator;
                state["cone_rotator"] = distributions.Range(rotator ? source["ConeRotation"] : null, new[] { 0f, 0f, 0f }, new[] { 0f, 0f, 0f }, rotator: true);
                state["cone_direction"] = distributions.Range(rotator ? null : source["ConeDirection"], new[] { 0f, 0f, 1f }, new[] { 0f, 0f, 1f });
                state["cone_velocity"] = distributions.Range(source["ConeVelocityDistribution"], new[] { 500f }, new[] { 0f });
                state["cone_scale"] = distributions.Range(source["ConeVelocityScale"], new[] { 1f }, new[] { 1f });
                state["cone_outer_angle"] = (float)SourceAsset.Number(source["ConeAngle"], 45f);
                state["cone_inner_angle"] = (float)SourceAsset.Number(source["InnerCone"], 0f);
                float falloff = (float)SourceAsset.Number(source["SpeedFalloffFromConeAxis"]);
                state["cone_falloff"] = source.Value<bool?>("bSpeedFalloffFromConeAxisEnabled") == true ? Math.Clamp(falloff, 0f, 1f) : 0f;
                break;
            default: throw new ConversionException($"Unknown stateless velocity type {type}");
        }
    }

    internal JObject Build(StatelessModuleCompiler layout)
    {
        int[] channels = new[] { "Position", "Velocity", "Previous.Position", "Previous.Velocity" }
            .Select(name => layout.Offset(name, 0)).ToArray();
        return new JObject { ["physics"] = state.DeepClone(), ["channels"] = new JArray(channels), ["callback"] = channels.Any(value => value >= 0) };
    }

    internal static int Space(JToken? source, string fallback = "Local") => StatelessDistributionCompiler.EnumValue(source, fallback) switch {
        "Simulation" or "0" => 0, "World" or "1" => 1, "Local" or "2" => 2,
        var value => throw new ConversionException($"Unknown Niagara coordinate space {value}")
    };

    private static void AddLiteral(JObject destination, JObject source)
    {
        foreach (string field in new[] { "minimum", "maximum" }) {
            JArray values = (JArray)destination[field]!, added = (JArray)source[field]!;
            for (int index = 0; index < values.Count; index++) {
                float a = BitConverter.Int32BitsToSingle(unchecked((int)values[index].Value<uint>()));
                float b = BitConverter.Int32BitsToSingle(unchecked((int)added[index].Value<uint>()));
                values[index] = unchecked((uint)BitConverter.SingleToInt32Bits(a + b));
            }
        }
    }
}
