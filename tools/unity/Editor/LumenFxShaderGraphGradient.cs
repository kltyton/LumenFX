#if UNITY_EDITOR
using System;
using System.Collections.Generic;
using System.Globalization;
using System.Text;
using UnityEditor;
using UnityEngine;

namespace LumenFx.UnityExport
{
    internal static partial class LumenFxShaderGraphCompiler
    {
        [Serializable]
        private sealed class SerializedGradient
        {
            public Gradient m_Value;
        }

        private static Gradient ReadGradientValue(CompileContext ctx, Dictionary<string, object> owner)
        {
            if (owner.TryGetValue("m_Value", out object value) && value is Dictionary<string, object>)
            {
                // Let Unity decode its native Gradient layout, including the serialized key times.
                var wrapper = new Dictionary<string, object> { { "m_Value", value } };
                Gradient gradient = JsonUtility.FromJson<SerializedGradient>(LumenFxJson.Write(wrapper))?.m_Value;
                if (gradient != null) return gradient;
            }
            ctx.Issues.Add(new ExportIssue(ctx.ContextLabel, "Shader Graph gradient has no serialized value"));
            return null;
        }

        private static Gradient ReadGradientNode(CompileContext ctx, Dictionary<string, object> node)
        {
            node.TryGetValue("m_SerializableColorKeys", out object rawColors);
            node.TryGetValue("m_SerializableAlphaKeys", out object rawAlphas);
            if (!(rawColors is List<object> colors) || !(rawAlphas is List<object> alphas))
            {
                ctx.Issues.Add(new ExportIssue(ctx.ContextLabel, "Shader Graph Gradient node has no saved color or alpha keys"));
                return null;
            }
            var colorKeys = new GradientColorKey[colors.Count];
            var alphaKeys = new GradientAlphaKey[alphas.Count];
            for (int i = 0; i < colors.Count; ++i)
            {
                if (!(colors[i] is Dictionary<string, object> key))
                {
                    ctx.Issues.Add(new ExportIssue(ctx.ContextLabel, "Invalid Shader Graph gradient color key"));
                    return null;
                }
                colorKeys[i] = new GradientColorKey(
                    new Color(GetFloat(key, "x"), GetFloat(key, "y"), GetFloat(key, "z")), GetFloat(key, "w"));
            }
            for (int i = 0; i < alphas.Count; ++i)
            {
                if (!(alphas[i] is Dictionary<string, object> key))
                {
                    ctx.Issues.Add(new ExportIssue(ctx.ContextLabel, "Invalid Shader Graph gradient alpha key"));
                    return null;
                }
                alphaKeys[i] = new GradientAlphaKey(GetFloat(key, "x"), GetFloat(key, "y"));
            }
            var gradient = new Gradient();
            gradient.SetKeys(colorKeys, alphaKeys);
            gradient.mode = (GradientMode)GetInt(node, "m_SerializableMode");
            return gradient;
        }

        private static Expr CompileGradientSample(CompileContext ctx, Dictionary<string, object> node)
        {
            Gradient ramp = Input(ctx, node, 0).Ramp;
            if (ramp == null)
            {
                ctx.Issues.Add(new ExportIssue(ctx.ContextLabel, "Sample Gradient requires a Gradient value on its Gradient input"));
                return new Expr("vec4(0.0)", 4);
            }

            int mode = (int)ramp.mode;
            GradientColorKey[] colors = ramp.colorKeys;
            GradientAlphaKey[] alphas = ramp.alphaKeys;
            if (mode < 0 || mode > 2 || colors.Length == 0 || alphas.Length == 0)
            {
                ctx.Issues.Add(new ExportIssue(ctx.ContextLabel, "Invalid Shader Graph gradient keys or interpolation mode"));
                return new Expr("vec4(0.0)", 4);
            }

            Expr time = InputWidth(ctx, node, 1, 1);
            bool legacy = GetInt(node, "m_SGVersion") == 0;
            bool gamma = PlayerSettings.colorSpace == ColorSpace.Gamma;
            bool perceptual = !legacy && mode == 2;
            int stepWeight = legacy ? mode : mode % 2;
            if (perceptual) ctx.AddHelper("gradient_oklab");
            if (legacy != gamma) ctx.AddHelper("gradient_transfer");

            List<string> functions = ctx.InVertexStage ? ctx.VertexGradientFunctions : ctx.FragmentGradientFunctions;
            string name = "lfx_sg_sample_gradient_" + functions.Count.ToString(CultureInfo.InvariantCulture);
            var shader = new StringBuilder();
            shader.AppendLine($"vec4 {name}(float time) {{");
            shader.AppendLine($"    vec3 color = {GradientColor(colors[0].color, perceptual)};");
            for (int i = 1; i < colors.Length; ++i)
            {
                string weight = GradientWeight(colors[i - 1].time, colors[i].time, stepWeight);
                shader.AppendLine($"    color = mix(color, {GradientColor(colors[i].color, perceptual)}, {weight});");
            }
            if (perceptual) shader.AppendLine("    color = lfx_sg_oklab_to_linear(color);");
            if (legacy && !gamma) shader.AppendLine("    color = lfx_sg_srgb_to_linear(color);");
            if (!legacy && gamma) shader.AppendLine("    color = lfx_sg_linear_to_srgb(color, 12.9232102);");

            shader.AppendLine($"    float alpha = {GradientNumber(alphas[0].alpha)};");
            for (int i = 1; i < alphas.Length; ++i)
            {
                string weight = GradientWeight(alphas[i - 1].time, alphas[i].time, stepWeight);
                shader.AppendLine($"    alpha = mix(alpha, {GradientNumber(alphas[i].alpha)}, {weight});");
            }
            shader.AppendLine("    return vec4(color, alpha);");
            shader.AppendLine("}");
            functions.Add(shader.ToString());
            return new Expr($"{name}({time.Code})", 4);
        }

        private static string GradientWeight(float left, float right, int stepWeight)
            => $"lfx_sg_gradient_weight(time, {GradientNumber(left)}, {GradientNumber(right)}, {stepWeight}.0)";

        private static string GradientColor(Color color, bool perceptual)
        {
            string value = $"vec3({GradientNumber(color.r)}, {GradientNumber(color.g)}, {GradientNumber(color.b)})";
            return perceptual ? $"lfx_sg_linear_to_oklab({value})" : value;
        }

        private static string GradientNumber(float value)
        {
            string result = value.ToString("R", CultureInfo.InvariantCulture);
            return result.IndexOf('.') >= 0 || result.IndexOf('E') >= 0 ? result : result + ".0";
        }

        private static void AppendGradientFunctions(StringBuilder shader, List<string> functions)
        {
            if (functions.Count == 0) return;
            shader.AppendLine("float lfx_sg_gradient_weight(float time, float left, float right, float stepWeight) {");
            shader.AppendLine("    float f = (time - left) / (right - left);");
            shader.AppendLine("    f = isnan(f) ? 0.0 : clamp(f, 0.0, 1.0);");
            shader.AppendLine("    return mix(f, step(0.01, f), stepWeight);");
            shader.AppendLine("}");
            foreach (string function in functions) shader.Append(function);
        }

        private static void AppendGradientOklabHelpers(StringBuilder shader)
        {
            // OKLab matrices for linear sRGB. Shader Graph uses a 0.333333 exponent for its LMS root.
            shader.AppendLine("vec3 lfx_sg_linear_to_oklab(vec3 c) {");
            shader.AppendLine("    vec3 lms = vec3(dot(c, vec3(0.4122214708, 0.5363325363, 0.0514459929)),");
            shader.AppendLine("                    dot(c, vec3(0.2119034982, 0.6806995451, 0.1073969566)),");
            shader.AppendLine("                    dot(c, vec3(0.0883024619, 0.2817188376, 0.6299787005)));");
            shader.AppendLine("    vec3 root = pow(lms, vec3(0.333333));");
            shader.AppendLine("    return vec3(dot(root, vec3(0.2104542553, 0.7936177850, -0.0040720468)),");
            shader.AppendLine("                dot(root, vec3(1.9779984951, -2.4285922050, 0.4505937099)),");
            shader.AppendLine("                dot(root, vec3(0.0259040371, 0.7827717662, -0.8086757660)));");
            shader.AppendLine("}");
            shader.AppendLine("vec3 lfx_sg_oklab_to_linear(vec3 c) {");
            shader.AppendLine("    vec3 root = c.xxx + vec3(dot(c.yz, vec2(0.3963377774, 0.2158037573)),");
            shader.AppendLine("                            -dot(c.yz, vec2(0.1055613458, 0.0638541728)),");
            shader.AppendLine("                            -dot(c.yz, vec2(0.0894841775, 1.2914855480)));");
            shader.AppendLine("    vec3 lms = root * root * root;");
            shader.AppendLine("    return vec3(dot(lms, vec3(4.0767416621, -3.3077115913, 0.2309699292)),");
            shader.AppendLine("                dot(lms, vec3(-1.2684380046, 2.6097574011, -0.3413193965)),");
            shader.AppendLine("                dot(lms, vec3(-0.0041960863, -0.7034186147, 1.7076147010)));");
            shader.AppendLine("}");
        }
    }
}
#endif
