#if UNITY_EDITOR
using System.Collections.Generic;
using UnityEngine;

namespace LumenFx.UnityExport
{
    internal static class LumenFxLights
    {
        internal static Dictionary<string, object> Build(ParticleSystem.LightsModule source, AxisMapper mapper,
            ExportSettings settings, LumenFxPlan plan, string cookieId, HashSet<string> usedTexturePaths, string context,
            List<ExportIssue> issues)
        {
            var light = source.light;
            if (light == null || source.ratio == 0 || source.maxLights == 0) return null;
            if (light.type != LightType.Point && light.type != LightType.Spot)
            {
                issues.Add(new ExportIssue(context, "The Particle System Lights module requires a Point or Spot Light prefab."));
                return null;
            }
            if (light.cullingMask != -1)
            {
                issues.Add(new ExportIssue(context,
                    "Particle light '" + light.name + "' uses a custom cullingMask that requires a matching host layer mapping.",
                    IssueSeverity.Warning));
            }
            Color color = QualitySettings.activeColorSpace == ColorSpace.Linear ? light.color.linear : light.color;
            if (light.useColorTemperature) color *= Mathf.CorrelatedColorTemperatureToRGB(light.colorTemperature);
            var forward = mapper.Direction(light.transform.localRotation * Vector3.forward);
            var up = mapper.Direction(light.transform.localRotation * Vector3.up);
            var profile = new Dictionary<string, object>
            {
                { "type", light.type == LightType.Spot ? "spot" : "point" },
                { "forward_to_up", mapper.ConvertsForwardToUp },
                { "forward", new[] { forward.x, forward.y, forward.z } },
                { "up", new[] { up.x, up.y, up.z } },
                { "inner_angle", light.innerSpotAngle }, { "outer_angle", light.spotAngle },
                { "shadows", light.shadows.ToString().ToLowerInvariant() },
                { "shadow_strength", light.shadowStrength }, { "shadow_bias", light.shadowBias },
                { "shadow_normal_bias", light.shadowNormalBias }, { "shadow_near", light.shadowNearPlane },
                { "shadow_resolution", light.shadowCustomResolution }
            };
            if (light.cookie != null)
            {
                var cookie = LumenFxLightCookie.Build(light, mapper, settings, cookieId, usedTexturePaths, context, issues);
                if (cookie != null)
                {
                    plan.Materials.Add(cookie.Id, cookie);
                    profile["cookie_material"] = cookie.Id;
                }
            }
            return new Dictionary<string, object>
            {
                { "ratio", source.ratio }, { "maximum", source.maxLights }, { "random_distribution", source.useRandomDistribution },
                { "color", new[] { color.r, color.g, color.b } }, { "radius", light.range }, { "intensity", light.intensity },
                { "profile", profile },
                { "range", LumenFxCurves.FromMinMaxCurve(source.range, 1, context + " light range", issues).ToJson() },
                { "intensity_over_life", LumenFxCurves.FromMinMaxCurve(source.intensity, 1, context + " light intensity", issues).ToJson() },
                { "use_particle_color", source.useParticleColor }, { "size_affects_range", source.sizeAffectsRange },
                { "alpha_affects_intensity", source.alphaAffectsIntensity }
            };
        }
    }
}
#endif
