#if UNITY_EDITOR
using System.Collections.Generic;
using UnityEngine;

namespace LumenFx.UnityExport
{
    internal static class LumenFxSpeedModules
    {
        public static Dictionary<string, object> Build(ParticleSystem system, string context, List<ExportIssue> issues)
        {
            var modules = new Dictionary<string, object>();
            var size = system.sizeBySpeed;
            if (size.enabled)
            {
                var label = context + " Size by Speed";
                var settings = Range(size.range, label, issues);
                if (size.separateAxes)
                    settings["axes"] = new[] { Curve(size.x, label + " X", issues), Curve(size.y, label + " Y", issues),
                        Curve(size.z, label + " Z", issues) };
                else settings["value"] = Curve(size.size, label, issues);
                modules["size"] = settings;
            }
            var color = system.colorBySpeed;
            if (color.enabled)
            {
                var label = context + " Color by Speed";
                var settings = Range(color.range, label, issues);
                settings["value"] = LumenFxCurves.FromMinMaxGradient(color.color, label, issues).ToJson();
                modules["color"] = settings;
            }
            var rotation = system.rotationBySpeed;
            if (rotation.enabled)
            {
                var label = context + " Rotation by Speed";
                var settings = Range(rotation.range, label, issues);
                var z = LumenFxCurves.FromMinMaxCurve(rotation.z, -1, label + " Z", issues).ToJson();
                if (rotation.separateAxes)
                    settings["axes"] = new[] { Curve(rotation.x, label + " X", issues),
                        Curve(rotation.y, label + " Y", issues), z };
                else settings["value"] = z;
                modules["rotation"] = settings;
            }
            return modules;
        }

        public static Dictionary<string, object> Lifetime(ParticleSystem.LifetimeByEmitterSpeedModule module,
            ParticleSystemEmitterVelocityMode source, string context, List<ExportIssue> issues)
        {
            var label = context + " Lifetime by Emitter Speed";
            var settings = Range(module.range, label, issues);
            settings["source"] = LumenFxExporter.EmitterVelocitySource(source, label, issues);
            settings["value"] = Curve(module.curve, label, issues);
            return settings;
        }

        private static object Curve(ParticleSystem.MinMaxCurve source, string label, List<ExportIssue> issues)
        {
            return LumenFxCurves.FromMinMaxCurve(source, 1, label, issues).ToJson();
        }

        private static Dictionary<string, object> Range(Vector2 range, string label, List<ExportIssue> issues)
        {
            if (float.IsNaN(range.x) || float.IsInfinity(range.x) || float.IsNaN(range.y)
                || float.IsInfinity(range.y) || range.x < 0 || range.y < range.x)
                issues.Add(new ExportIssue(label, "Speed range must have finite, ordered non-negative endpoints"));
            return new Dictionary<string, object> { { "min", range.x }, { "max", range.y } };
        }
    }
}
#endif
