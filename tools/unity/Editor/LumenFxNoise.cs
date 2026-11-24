#if UNITY_EDITOR
using System.Collections.Generic;
using UnityEngine;

namespace LumenFx.UnityExport
{
    internal static class LumenFxNoise
    {
        internal static Dictionary<string, object> Build(ParticleSystem.NoiseModule source, AxisMapper mapper,
            string context, List<ExportIssue> issues)
        {
            var strength = ReadAxes(source.strength, source.strengthY, source.strengthZ, source.separateAxes,
                mapper, context + " Strength", issues);
            var result = new Dictionary<string, object>
            {
                { "strength", strength },
                { "frequency", source.frequency },
                { "damping", source.damping },
                { "octaves", source.octaveCount },
                { "octave_multiplier", source.octaveMultiplier },
                { "octave_scale", source.octaveScale },
                { "quality", (int)source.quality + 1 },
                { "scroll_speed", Read(source.scrollSpeed, 1, context + " Scroll Speed", issues) },
                { "position", Read(source.positionAmount, 1, context + " Position Amount", issues) },
                { "rotation", Read(source.rotationAmount, Mathf.Deg2Rad, context + " Rotation Amount", issues) },
                { "size", Read(source.sizeAmount, 1, context + " Size Amount", issues) },
                { "forward_to_up", mapper.ConvertsForwardToUp }
            };
            if (source.remapEnabled)
                result["remap"] = ReadAxes(source.remap, source.remapY, source.remapZ, source.separateAxes,
                    mapper, context + " Remap", issues);
            return result;
        }

        private static object[] ReadAxes(ParticleSystem.MinMaxCurve x, ParticleSystem.MinMaxCurve y,
            ParticleSystem.MinMaxCurve z, bool separate, AxisMapper mapper, string context, List<ExportIssue> issues)
        {
            var first = LumenFxCurves.FromMinMaxCurve(x, 1, context + " X", issues);
            var second = separate ? LumenFxCurves.FromMinMaxCurve(y, 1, context + " Y", issues) : first;
            var third = separate ? LumenFxCurves.FromMinMaxCurve(z, 1, context + " Z", issues) : first;
            var axes = mapper.MapScalars(first, second, third, false);
            return new[] { axes[0].ToJson(), axes[1].ToJson(), axes[2].ToJson() };
        }

        private static object Read(ParticleSystem.MinMaxCurve curve, double scale, string context,
            List<ExportIssue> issues)
        {
            return LumenFxCurves.FromMinMaxCurve(curve, scale, context, issues).ToJson();
        }
    }
}
#endif
