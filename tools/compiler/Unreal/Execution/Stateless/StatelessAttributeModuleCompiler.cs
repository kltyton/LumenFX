using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution.Stateless;

internal sealed class StatelessAttributeModuleCompiler(StatelessModuleCompiler layout, StatelessDistributionCompiler distributions)
{
    internal JObject? Compile(string kind, JObject source) => kind switch {
        "NiagaraStatelessModule_DynamicMaterialParameters" => Dynamic(source),
        "NiagaraStatelessModule_LightAttributes" => Light(source),
        "NiagaraStatelessModule_SpriteRotationRate" => Rotation(source),
        "NiagaraStatelessModule_ScaleMeshSizeBySpeed" => Speed(source, "Scale", 3),
        "NiagaraStatelessModule_ScaleSpriteSizeBySpeed" => Speed(source, "SpriteSize", 2),
        "NiagaraStatelessModule_CalculateAccurateVelocity" => AccurateVelocity(),
        "NiagaraStatelessModule_SpriteFacingAndAlignment" => Facing(source),
        "NiagaraStatelessModule_SubUVAnimation" => SubUv(source),
        _ => null
    };

    private JObject? Dynamic(JObject source)
    {
        JArray entries = new();
        for (int parameter = 0; parameter < 4; parameter++) {
            if (!(source.Value<bool?>("bParameter" + parameter + "Enabled") ?? parameter == 0)) continue;
            JObject channels = source["Parameter" + parameter] as JObject ?? new();
            string[] names = { "X", "Y", "Z", "W" };
            int mask = 0;
            for (int channel = 0; channel < 4; channel++)
                if (channels.Value<bool?>("b" + names[channel] + "ChannelEnabled") ?? true) mask |= 1 << channel;
            int offset = layout.Offset("DynamicMaterialParameter" + (parameter == 0 ? "" : parameter.ToString()), 0);
            if (mask == 0 || offset < 0) continue;
            JArray values = new();
            for (int channel = 0; channel < 4; channel++)
                values.Add(StatelessDistributionCompiler.Packed(distributions.Distribution(
                    (mask & 1 << channel) != 0 ? channels[names[channel] + "ChannelDistribution"] : null, new[] { 0f })));
            entries.Add(new JObject { ["parameter"] = parameter, ["mask"] = mask, ["component"] = offset, ["distributions"] = values });
        }
        return entries.Count == 0 ? null : new JObject { ["entries"] = entries };
    }

    private JObject? Light(JObject source)
    {
        var fields = new[] { ("Radius", "LightRadius", 100f), ("FalloffExponent", "LightExponent", 1f),
            ("DiffuseScale", "LightDiffuseScale", 1f), ("SpecularScale", "LightSpecularScale", 1f),
            ("VolumetricScattering", "LightVolumetricScattering", 0f) };
        JArray entries = new();
        for (int index = 0; index < fields.Length; index++) {
            var (field, attribute, fallback) = fields[index];
            if (source.Value<bool?>("bApply" + field) != true) continue;
            int offset = layout.Offset(attribute, 0);
            if (offset < 0) continue;
            entries.Add(new JObject { ["component"] = offset, ["radius"] = index == 0,
                ["distribution"] = StatelessDistributionCompiler.Packed(distributions.Distribution(source[field], new[] { fallback })) });
        }
        return entries.Count == 0 ? null : new JObject { ["entries"] = entries };
    }

    private JObject? Rotation(JObject source)
    {
        int current = layout.Offset("SpriteRotation", 0), previous = layout.Offset("Previous.SpriteRotation", 0);
        if (current < 0 && previous < 0) return null;
        JObject rate = distributions.Range(source["RotationRateDistribution"], new[] { 0f }, new[] { 0f });
        return new JObject { ["current"] = current, ["previous"] = previous, ["rate"] = rate,
            ["curve"] = StatelessDistributionCompiler.Packed(distributions.Distribution(
                Curve(source.Value<bool?>("bUseRateScale") == true ? source["RateScaleDistribution"] : null),
                new[] { 1f }, asCurve: true, fallback: new[] { 1f })) };
    }

    private JObject? Speed(JObject source, string attribute, int width)
    {
        int current = layout.Offset(attribute, 0), previous = layout.Offset("Previous." + attribute, 0);
        if (current < 0 && previous < 0) return null;
        int position = layout.Offset("Position", 0), previousPosition = layout.Offset("Previous.Position", 0), velocity = layout.Offset("Velocity", 0);
        float[] minimum = Enumerable.Repeat(1f, width).ToArray(), maximum = Enumerable.Repeat(2f, width).ToArray();
        JObject threshold = distributions.Range(source["VelocityThreshold"], new[] { 1000f }, new[] { 1000f });
        JObject minScale = distributions.Range(source["MinScaleFactor"], minimum, minimum);
        JObject maxScale = distributions.Range(source["MaxScaleFactor"], maximum, minimum);
        JArray curve = StatelessDistributionCompiler.Packed(distributions.Distribution(
            Curve(source.Value<bool?>("bSampleScaleFactorByCurve") == true ? source["SampleFactorCurve"] : null), new[] { 0f }));
        return new JObject { ["current"] = current, ["previous"] = previous, ["width"] = width,
            ["position"] = position, ["previous_position"] = previousPosition, ["velocity"] = velocity,
            ["threshold"] = threshold, ["minimum_scale"] = minScale, ["maximum_scale"] = maxScale,
            ["distribution"] = curve, ["callback"] = previousPosition >= 0 || velocity >= 0 };
    }

    private JObject? AccurateVelocity()
    {
        int velocity = layout.Offset("Velocity", 0), previousVelocity = layout.Offset("Previous.Velocity", 0);
        return velocity < 0 && previousVelocity < 0 ? null : new JObject {
            ["position"] = layout.Offset("Position", 0), ["previous_position"] = layout.Offset("Previous.Position", 0),
            ["velocity"] = velocity, ["previous_velocity"] = previousVelocity };
    }

    private JObject? Facing(JObject source)
    {
        bool facing = source.Value<bool?>("bSpriteFacingEnabled") ?? true;
        bool alignment = source.Value<bool?>("bSpriteAlignmentEnabled") ?? false;
        int currentFacing = facing ? layout.Offset("SpriteFacing", 0) : -1, previousFacing = facing ? layout.Offset("Previous.SpriteFacing", 0) : -1;
        int currentAlignment = alignment ? layout.Offset("SpriteAlignment", 0) : -1, previousAlignment = alignment ? layout.Offset("Previous.SpriteAlignment", 0) : -1;
        if (currentFacing < 0 && previousFacing < 0 && currentAlignment < 0 && previousAlignment < 0) return null;
        JArray entries = new();
        if (facing) entries.Add(new JObject { ["current"] = currentFacing, ["previous"] = previousFacing,
            ["value"] = distributions.Range(source["SpriteFacing"], new[] { 1f, 0f, 0f }, new[] { 0f, 0f, 0f }) });
        if (alignment) entries.Add(new JObject { ["current"] = currentAlignment, ["previous"] = previousAlignment,
            ["value"] = distributions.Range(source["SpriteAlignment"], new[] { 0f, 1f, 0f }, new[] { 0f, 0f, 0f }) });
        return new JObject { ["entries"] = entries };
    }

    private JObject? SubUv(JObject source)
    {
        int component = layout.Offset("SubImageIndex", 0), frames = source.Value<int?>("NumFrames") ?? 16;
        if (component < 0 || frames <= 1) return null;
        int start = source.Value<bool?>("bStartFrameRangeOverride_Enabled") == true
            ? Math.Clamp(source.Value<int?>("StartFrameRangeOverride") ?? 0, 0, frames - 1) : 0;
        int end = source.Value<bool?>("bEndFrameRangeOverride_Enabled") == true
            ? Math.Clamp(source.Value<int?>("EndFrameRangeOverride") ?? 0, 0, frames - 1) : frames - 1;
        int mode = StatelessDistributionCompiler.EnumValue(source["AnimationMode"], "Linear") switch {
            "DirectSet" or "0" => 0, "InfiniteLoop" or "1" => 1, "Linear" or "2" => 2, "Random" or "3" => 3,
            var value => throw new ConversionException($"Unknown stateless subimage animation mode {value}")
        };
        JObject initial = mode == 0 ? distributions.IntegerRange(source["FrameIndex"], 0)
            : distributions.IntegerRange(null, 0);
        if (mode == 3) { initial["minimum"] = start; initial["maximum"] = end; }
        float frameValue = frames;
        int builtFrames = frameValue >= 2147483648f ? int.MinValue : (int)frameValue;
        float interval = (float)SourceAsset.Number(source["RandomChangeInterval"], .1f);
        return new JObject { ["component"] = component, ["mode"] = mode, ["frames"] = builtFrames, ["initial"] = initial,
            ["seed_rate"] = mode == 3 && interval > 0f ? 1f / interval : 0f,
            ["frame_start"] = mode is 1 or 2 ? start : 0, ["frame_range"] = mode is 1 or 2 ? end - start : 0,
            ["rate_scale"] = mode == 1 ? (float)SourceAsset.Number(source["LoopsPerSecond"], 1f) : mode == 2 ? 1f : 0f };
    }

    private static JObject Curve(JToken? source)
        => StatelessDistributionCompiler.CurveDefaults(source, 1);
}
