using System.Text;

namespace LumenFx.Compiler.Unreal;

internal static class MaterialShaderMath
{
    internal static void AppendSphericalOpacity(StringBuilder shader)
    {
        shader.AppendLine("""
float lfx_spherical_opacity(float density, vec3 center, float size, vec3 position) {
    float radius = max(0.000001, size);
    float distanceToCenter = length(position - center);
    if (distanceToCenter >= radius) return 0.0;
    float halfChord = sqrt(radius * radius - distanceToCenter * distanceToCenter);
#if (defined(LFX_PARTICLE_POINT_SHADOW) && LFX_PARTICLE_POINT_SHADOW) || (defined(LFX_SHADOW_DEPTH_PASS) && LFX_SHADOW_DEPTH_PASS)
    float path = halfChord * 2.0;
    return clamp(1.0 - exp2(-(density / radius) * (1.0 - distanceToCenter / radius) * path), 0.0, 1.0);
#else
#if (defined(LFX_NANITE_MATERIAL) && LFX_NANITE_MATERIAL) || (defined(LFX_NANITE_SOFTWARE_RASTER) && LFX_NANITE_SOFTWARE_RASTER)
    if (lfx_nanite_shadow_depth()) {
        float path = halfChord * 2.0;
        return clamp(1.0 - exp2(-(density / radius) * (1.0 - distanceToCenter / radius) * path), 0.0, 1.0);
    }
#endif
    float screenW = ProjMat[3][3] >= 1.0 ? 1.0 : 100.0 / gl_FragCoord.w;
    float nearPlane = 100.0 * abs((ProjMat[3][2] - ProjMat[3][3]) / (ProjMat[2][3] - ProjMat[2][2]));
    float path = min(lfx_scene_depth(), screenW + halfChord) - (screenW - halfChord);
    float opacity = clamp(1.0 - exp2(-(density / radius) * (1.0 - distanceToCenter / radius) * path), 0.0, 1.0);
    return opacity * clamp((screenW - radius - nearPlane) / radius, 0.0, 1.0);
#endif
}
""");
    }

    internal static void Append(StringBuilder shader)
    {
        shader.AppendLine("vec4 lfx_unpack_normal_rg(vec4 sampleValue) { vec2 xy = sampleValue.rg * 2.0 - 1.0; return vec4(xy, sqrt(clamp(1.0 - dot(xy, xy), 0.0, 1.0)), 1.0); }");
        shader.AppendLine("float lfx_positive_pow(float b, float e) { return b <= 2.980233e-8 ? 0.0 : pow(b, e); }");
        shader.AppendLine("float lfx_fmod(float x, float y) { return x - y * trunc(x / y); }");
        shader.AppendLine("float lfx_smoothstep(float a, float b, float x) { float t = clamp((x-a)/(b-a), 0.0, 1.0); return t*t*(3.0-2.0*t); }");
        shader.AppendLine("vec3 lfx_hsv_to_rgb(vec3 hsv) { float h = hsv.r; vec3 rgb = clamp(vec3(abs(h * 6.0 - 3.0) - 1.0, 2.0 - abs(h * 6.0 - 2.0), 2.0 - abs(h * 6.0 - 4.0)), 0.0, 1.0); return ((rgb - 1.0) * hsv.y + 1.0) * hsv.z; }");
        shader.AppendLine("vec4 lfx_hsv_to_rgb(vec4 hsv) { return vec4(lfx_hsv_to_rgb(hsv.rgb), hsv.a); }");
        shader.AppendLine("vec3 lfx_rgb_to_hsv(vec3 rgb) { vec4 p = (rgb.g < rgb.b) ? vec4(rgb.bg, -1.0, 2.0 / 3.0) : vec4(rgb.gb, 0.0, -1.0 / 3.0); vec4 q = (rgb.r < p.x) ? vec4(p.xyw, rgb.r) : vec4(rgb.r, p.yzx); float chroma = q.x - min(q.w, q.y); float hue = abs((q.w - q.y) / (6.0 * chroma + 1e-10) + q.z); vec3 hcv = vec3(hue, chroma, q.x); float s = hcv.y / (hcv.z + 1e-10); return vec3(hcv.x, s, hcv.z); }");
        shader.AppendLine("vec4 lfx_rgb_to_hsv(vec4 rgb) { return vec4(lfx_rgb_to_hsv(rgb.rgb), rgb.a); }");
        foreach (int width in new[] { 2, 3, 4 }) {
            shader.AppendLine($"vec{width} lfx_positive_pow(vec{width} b, vec{width} e) {{ return vec{width}({string.Join(", ", "xyzw"[..width].Select(c => $"lfx_positive_pow(b.{c}, e.{c})"))}); }}");
            shader.AppendLine($"vec{width} lfx_fmod(vec{width} x, vec{width} y) {{ return x - y * trunc(x / y); }}");
            shader.AppendLine($"vec{width} lfx_smoothstep(vec{width} a, vec{width} b, vec{width} x) {{ vec{width} t = clamp((x-a)/(b-a), 0.0, 1.0); return t*t*(3.0-2.0*t); }}");
        }
        AppendFastTrigonometry(shader);
    }

    private static void AppendFastTrigonometry(StringBuilder shader)
    {
        shader.AppendLine("""
            float lfx_fast_acos(float inputValue) {
                float magnitude = abs(inputValue);
                float angle = (1.5707963267948966 - 0.156583 * magnitude) * sqrt(1.0 - magnitude);
                return inputValue >= 0.0 ? angle : 3.141592653589793 - angle;
            }
            float lfx_fast_asin(float inputValue) {
                return 1.5707963267948966 - lfx_fast_acos(inputValue);
            }
            float lfx_fast_atan_polynomial(float ratio) {
                float squared = ratio * ratio;
                return ((0.0872929 * squared - 0.301895) * squared + 1.0) * ratio;
            }
            float lfx_fast_atan(float inputValue) {
                float magnitude = abs(inputValue);
                bool belowOne = magnitude < 1.0;
                float angle = lfx_fast_atan_polynomial(belowOne ? magnitude : 1.0 / magnitude);
                if (!belowOne) angle = 1.5707963267948966 - angle;
                return inputValue < 0.0 ? -angle : angle;
            }
            float lfx_fast_atan2(float y, float x) {
                float absoluteX = abs(x), absoluteY = abs(y);
                float angle = lfx_fast_atan_polynomial(min(absoluteX, absoluteY) / max(absoluteX, absoluteY));
                if (absoluteY > absoluteX) angle = 1.5707963267948966 - angle;
                if (x < 0.0) angle = 3.141592653589793 - angle;
                return y < 0.0 ? -angle : angle;
            }
            """);
        for (int width = 2; width <= 4; width++) {
            string components = "xyzw"[..width];
            foreach (string operation in new[] { "asin", "acos", "atan" }) {
                string lanes = string.Join(", ", components.Select(lane => $"lfx_fast_{operation}(value.{lane})"));
                shader.AppendLine($"vec{width} lfx_fast_{operation}(vec{width} value) {{ return vec{width}({lanes}); }}");
            }
            string atan2 = string.Join(", ", components.Select(lane => $"lfx_fast_atan2(y.{lane}, x.{lane})"));
            shader.AppendLine($"vec{width} lfx_fast_atan2(vec{width} y, vec{width} x) {{ return vec{width}({atan2}); }}");
        }
    }
}
