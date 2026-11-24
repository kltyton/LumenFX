using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private Expr StreamedVirtualTexture(Context context, string source, string? name, MaterialSamplerType samplerType, string? staticSource)
    {
        var renderer = name is not null && context.ParameterAssociation == "GlobalParameter" && context.ParameterIndex == -1
                && rendererConstants.Texture(name) is null ? rendererBindings.Find(name, "texture") : null;
        string? baseSource = null;
        if (renderer is not null) {
            baseSource = staticSource ?? throw new ConversionException($"Virtual texture parameter {name} has no authored default");
        }
        var binding = new CompiledVirtualTextureRead("", source, null, 1, true, false, name,
                context.ParameterAssociation, context.ParameterIndex, renderer, baseSource);
        var slot = VirtualTextureSlot(binding);
        return new Expr(slot.Name, 0, true, samplerType, VirtualTexture: true, StaticTextureSource: staticSource);
    }

    private string SampleStreamedVirtualTexture(Context context, JObject properties, string name, string uv, bool automaticBias,
        string? derivativeX, string? derivativeY)
    {
        string mode = (properties.Value<string>("MipValueMode") ?? "TMVM_None").Split("::").Last();
        bool mipLevel = mode is "TMVM_MipLevel" or "1";
        bool mipBias = mode is "TMVM_MipBias" or "2";
        bool gradients = mode is "TMVM_Derivative" or "3";
        if (!mipLevel && !mipBias && !gradients && mode is not ("TMVM_None" or "0"))
            throw new ConversionException($"Unknown virtual texture mip mode {mode}");
        string mip = mipLevel || mipBias
            ? Cast(Input(context, properties["MipValue"], () => Constant(SourceAsset.Number(properties["ConstMipValue"], -1))), 1).Code : "0.0";
        string? explicitDx = gradients ? Cast(RequiredInput(context, properties, "CoordinatesDX"), 2).Code : null;
        string? explicitDy = gradients ? Cast(RequiredInput(context, properties, "CoordinatesDY"), 2).Code : null;
        string samplerSource = (properties.Value<string>("SamplerSource") ?? "SSM_FromTextureAsset").Split("::").Last();
        return SampleStreamedVirtualTextureAt(context, name, uv, mode, mip, explicitDx, explicitDy, samplerSource, automaticBias, derivativeX, derivativeY);
    }

    private string SampleStreamedVirtualTextureAt(
        Context context,
        string name,
        string uv,
        string mode,
        string mip,
        string? explicitDx,
        string? explicitDy,
        string samplerSource,
        bool automaticBias,
        string? derivativeX = null,
        string? derivativeY = null)
    {
        bool pixel = !compilingVertex && !volumeCoordinates;
        bool mipLevel = mode is "TMVM_MipLevel" or "1";
        bool gradients = mode is "TMVM_Derivative" or "3";
        string dx = "vec2(0.0)", dy = dx;
        if (gradients && explicitDx is not null && explicitDy is not null) {
            dx = explicitDx;
            dy = explicitDy;
        } else if (pixel && !mipLevel) {
            dx = naniteCoordinates && derivativeX is not null ? $"lfx_material_ddx({uv}, {derivativeX})" : $"dFdx({uv})";
            dy = naniteCoordinates && derivativeY is not null ? $"lfx_material_ddy({uv}, {derivativeY})" : $"dFdy({uv})";
        }
        if (!pixel && !mipLevel) mip = "0.0";
        string address = samplerSource switch {
            "SSM_FromTextureAsset" or "0" => $"{name}_SizeAndAddress.zw",
            "SSM_Wrap_WorldGroupSettings" or "1" => "ivec2(1)",
            "SSM_Clamp_WorldGroupSettings" or "SSM_TerrainWeightmapGroupSettings" or "2" or "3" => "ivec2(0)",
            var unknown => throw new ConversionException($"Unknown virtual texture sampler source {unknown}")
        };
        string level = mipLevel || !pixel ? "true" : "false";
        string viewBias = pixel && automaticBias ? $"({name}_Options.y + EffectClock.y)" : $"{name}_Options.y";
        string noise = level == "true" ? "0.0" :
                $"fract(52.9829189 * fract(dot(gl_FragCoord.xy + mod({name}_Options.z, 8.0) * 5.588238, vec2(0.06711056, 0.00583715))))";
        string page = $"lfx_vt_page({name}_Table0, {name}_Table1, {uv}, {dx}, {dy}, {address}, {level}, {mip}, {viewBias}, {noise}, false)";
        if (pixel) page = VirtualTextureFeedbackCode(context, name, page);
        return $"({name}_Options.x != 0.0 ? lfx_vt_physical({name}_Pages0, {name}_Physical0, {name}_Table1, " +
                $"{name}_Layer[0], {page}, 0, {level}, {name}_Height.z != 0.0) : vec4(0.0))";
    }
}
