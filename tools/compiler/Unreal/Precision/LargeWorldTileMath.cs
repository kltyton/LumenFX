using System.Text;

namespace LumenFx.Compiler.Unreal;

internal static class LargeWorldTileMath
{
    internal static void Append(StringBuilder shader)
    {
        shader.AppendLine("const float LFX_LWC_TILE_SIZE = 2097152.0; const float LFX_LWC_TILE_RCP = 0.000000476837158203125;");
        for (int width = 1; width <= 4; width++) {
            string f = width == 1 ? "float" : "vec" + width, l = "LfxLWC" + width;
            string boolean = width == 1 ? "bool" : "bvec" + width;
            shader.AppendLine($"struct {l} {{ {f} Tile; {f} Offset; }};");
            shader.AppendLine($"{l} lfx_lwc_promote({f} v) {{ return {l}({f}(0.0), v); }}");
            shader.AppendLine($"{f} lfx_lwc_demote({l} v) {{ precise {f} tileOffset = v.Tile * LFX_LWC_TILE_SIZE; return tileOffset + v.Offset; }}");
            shader.AppendLine($"{l} lfx_lwc_negate({l} v) {{ return {l}(-v.Tile, -v.Offset); }}");
            shader.AppendLine($"{l} lfx_lwc_add({l} a, {l} b) {{ return {l}(a.Tile + b.Tile, a.Offset + b.Offset); }}");
            shader.AppendLine($"{l} lfx_lwc_add({l} a, {f} b) {{ return {l}(a.Tile, a.Offset + b); }}");
            shader.AppendLine($"{l} lfx_lwc_add({f} a, {l} b) {{ return {l}(b.Tile, a + b.Offset); }}");
            shader.AppendLine($"{l} lfx_lwc_subtract({l} a, {l} b) {{ return {l}(a.Tile - b.Tile, a.Offset - b.Offset); }}");
            shader.AppendLine($"{l} lfx_lwc_subtract({l} a, {f} b) {{ return {l}(a.Tile, a.Offset - b); }}");
            shader.AppendLine($"{l} lfx_lwc_subtract({f} a, {l} b) {{ return {l}(-b.Tile, a - b.Offset); }}");
            shader.AppendLine($"{l} lfx_lwc_multiply({l} a, {l} b) {{ precise {f} t = a.Tile * (b.Tile * LFX_LWC_TILE_SIZE + b.Offset) + b.Tile * a.Offset; return {l}(t, a.Offset * b.Offset); }}");
            shader.AppendLine($"{l} lfx_lwc_multiply({l} a, {f} b) {{ return {l}(a.Tile * b, a.Offset * b); }}");
            shader.AppendLine($"{l} lfx_lwc_multiply({f} a, {l} b) {{ return {l}(b.Tile * a, a * b.Offset); }}");
            shader.AppendLine($"{f} lfx_lwc_rcp({l} v) {{ precise {f} unscaled = v.Offset * LFX_LWC_TILE_RCP + v.Tile; return ({f}(1.0) / unscaled) * LFX_LWC_TILE_RCP; }}");
            shader.AppendLine($"{l} lfx_lwc_divide({l} a, {l} b) {{ return lfx_lwc_multiply(a, lfx_lwc_rcp(b)); }}");
            shader.AppendLine($"{l} lfx_lwc_divide({l} a, {f} b) {{ return lfx_lwc_multiply(a, {f}(1.0) / b); }}");
            shader.AppendLine($"{l} lfx_lwc_divide({f} a, {l} b) {{ return {l}({f}(0.0), a * lfx_lwc_rcp(b)); }}");
            shader.AppendLine($"{l} lfx_lwc_lerp({l} a, {l} b, {f} t) {{ return {l}(mix(a.Tile, b.Tile, t), mix(a.Offset, b.Offset, t)); }}");
            if (width > 1) shader.AppendLine($"{l} lfx_lwc_lerp({l} a, {l} b, float t) {{ return lfx_lwc_lerp(a, b, {f}(t)); }}");
            foreach (var (name, operation) in new[] { ("less", "<"), ("greater", ">"), ("greater_equal", ">=") }) {
                string comparison = string.Join(", ", "xyzw"[..width].Select(component => {
                    string suffix = width == 1 ? "" : "." + component;
                    return $"((a.Tile{suffix} - b.Tile{suffix}) * LFX_LWC_TILE_SIZE {operation} b.Offset{suffix} - a.Offset{suffix})";
                }));
                shader.AppendLine($"{boolean} lfx_lwc_{name}({l} a, {l} b) {{ return {boolean}({comparison}); }}");
            }
            shader.AppendLine($"{l} lfx_lwc_select({boolean} s, {l} a, {l} b) {{ return {l}(mix(b.Tile, a.Tile, s), mix(b.Offset, a.Offset, s)); }}");
            if (width > 1) shader.AppendLine($"{l} lfx_lwc_select(bool s, {l} a, {l} b) {{ return s ? a : b; }}");
            shader.AppendLine($"{f} lfx_lwc_step({l} a, {l} b) {{ return mix({f}(0.0), {f}(1.0), lfx_lwc_greater_equal(b, a)); }}");
            shader.AppendLine($"{l} lfx_lwc_min({l} a, {l} b) {{ return lfx_lwc_select(lfx_lwc_less(a, b), a, b); }}");
            shader.AppendLine($"{l} lfx_lwc_max({l} a, {l} b) {{ return lfx_lwc_select(lfx_lwc_greater(a, b), a, b); }}");
            shader.AppendLine($"{l} lfx_lwc_abs({l} v) {{ return lfx_lwc_select(lfx_lwc_less(v, lfx_lwc_promote({f}(0.0))), lfx_lwc_negate(v), v); }}");
            shader.AppendLine($"{l} lfx_lwc_int_tile({l} v) {{ {f} t = floor(v.Tile); return {l}(t, (v.Tile - t) * LFX_LWC_TILE_SIZE + v.Offset); }}");
            foreach (string operation in new[] { "floor", "ceil", "round", "trunc" })
                shader.AppendLine($"{l} lfx_lwc_{operation}({l} v) {{ v = lfx_lwc_int_tile(v); return {l}(v.Tile, {(operation == "round" ? "roundEven" : operation)}(v.Offset)); }}");
            shader.AppendLine($"{f} lfx_lwc_frac_demote({l} v) {{ return fract(fract(v.Tile * LFX_LWC_TILE_SIZE) + v.Offset); }}");
            shader.AppendLine($"{f} lfx_lwc_sign({l} v) {{ return sign(lfx_lwc_demote(v)); }}");
            shader.AppendLine($"{f} lfx_lwc_saturate({l} v) {{ return clamp(lfx_lwc_demote(v), 0.0, 1.0); }}");
            shader.AppendLine($"{f} lfx_lwc_sqrt({l} v) {{ precise {f} unscaled = v.Offset * LFX_LWC_TILE_RCP + v.Tile; return sqrt(unscaled) * 1448.1546630859375; }}");
            shader.AppendLine($"{f} lfx_lwc_rsqrt({l} v) {{ precise {f} unscaled = v.Offset * LFX_LWC_TILE_RCP + v.Tile; return inversesqrt(unscaled) * 0.0006905339660024879; }}");
            shader.AppendLine($"{f} lfx_lwc_fmod({l} a, {f} b) {{ return lfx_lwc_demote(lfx_lwc_subtract(a, lfx_lwc_multiply(lfx_lwc_trunc(lfx_lwc_divide(a, b)), b))); }}");
            shader.AppendLine($"{f} lfx_lwc_fmod_floor({l} a, {f} b) {{ return lfx_lwc_demote(lfx_lwc_subtract(a, lfx_lwc_multiply(lfx_lwc_floor(lfx_lwc_divide(a, b)), b))); }}");
            shader.AppendLine($"{f} lfx_lwc_sin({l} v) {{ return sin(lfx_lwc_fmod_floor(v, {f}(6.2831854820251465))); }}");
            shader.AppendLine($"{f} lfx_lwc_cos({l} v) {{ return cos(lfx_lwc_fmod_floor(v, {f}(6.2831854820251465))); }}");
            shader.AppendLine($"{f} lfx_lwc_tan({l} v) {{ return tan(lfx_lwc_fmod_floor(v, {f}(3.1415927410125732))); }}");
            shader.AppendLine($"{f} lfx_lwc_asin({l} v) {{ return asin(clamp(lfx_lwc_demote(v), -1.0, 1.0)); }}");
            shader.AppendLine($"{f} lfx_lwc_acos({l} v) {{ return acos(clamp(lfx_lwc_demote(v), -1.0, 1.0)); }}");
            shader.AppendLine($"{f} lfx_lwc_atan({l} v) {{ return atan(clamp(lfx_lwc_demote(v), -1.5707963705062866, 1.5707963705062866)); }}");
            shader.AppendLine($"{f} lfx_lwc_smoothstep({l} a, {l} b, {l} v) {{ {f} t = lfx_lwc_saturate(lfx_lwc_divide(lfx_lwc_subtract(v, a), lfx_lwc_subtract(b, a))); return t * t * (3.0 - 2.0 * t); }}");
            shader.AppendLine($"{l} lfx_lwc_square_scaled({l} v) {{ {f} o = v.Offset * LFX_LWC_TILE_RCP; return {l}(v.Tile * (v.Tile + o * 2.0), v.Offset * o); }}");
            string Component(string expression, char component) => width == 1 ? expression : expression + "." + component;
            string Sum(string expression) {
                string result = $"LfxLWC1({Component(expression + ".Tile", 'x')}, {Component(expression + ".Offset", 'x')})";
                foreach (char component in "yzw"[..(width - 1)])
                    result = $"lfx_lwc_add({result}, LfxLWC1({Component(expression + ".Tile", component)}, {Component(expression + ".Offset", component)}))";
                return result;
            }
            shader.AppendLine($"LfxLWC1 lfx_lwc_sum({l} v) {{ return {Sum("v")}; }}");
            shader.AppendLine($"LfxLWC1 lfx_lwc_length_squared_scaled({l} v) {{ {l} square = lfx_lwc_square_scaled(v); return lfx_lwc_sum(square); }}");
            shader.AppendLine($"LfxLWC1 lfx_lwc_length({l} v) {{ LfxLWC1 s = lfx_lwc_length_squared_scaled(v); return LfxLWC1(sqrt(s.Offset * LFX_LWC_TILE_RCP + s.Tile), 0.0); }}");
            shader.AppendLine($"{f} lfx_lwc_normalize({l} v) {{ {(width == 1 ? "return 1.0;" : $"LfxLWC1 s = lfx_lwc_length_squared_scaled(v); float r = inversesqrt(s.Offset * LFX_LWC_TILE_RCP + s.Tile) * LFX_LWC_TILE_RCP; return lfx_lwc_demote(lfx_lwc_multiply(v, {f}(r)));")} }}");
            shader.AppendLine($"LfxLWC1 lfx_lwc_dot({l} a, {l} b) {{ {l} product = lfx_lwc_multiply(a, b); return lfx_lwc_sum(product); }}");
            shader.AppendLine($"LfxLWC1 lfx_lwc_dot({l} a, {f} b) {{ {l} product = lfx_lwc_multiply(a, b); return lfx_lwc_sum(product); }}");
            shader.AppendLine($"LfxLWC1 lfx_lwc_dot({f} a, {l} b) {{ return lfx_lwc_dot(b, a); }}");
        }
    }
}
