using System.Text;
using LumenFx.Compiler.Unreal.Parameters;
using LumenFx.Compiler.Unreal.TextureSource;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed record CompiledVirtualTextureRead(string Name, string Source, string? Material, int Layers,
    bool Packed, bool Adaptive, string? Parameter, string Association, int Index,
    CompiledMaterialBinding? RendererBinding = null, string? RendererBase = null);

public sealed partial class MaterialCompiler
{
    private readonly Dictionary<CompiledVirtualTextureRead, CompiledVirtualTextureRead> virtualTextureReads = new();
    private readonly Dictionary<(int Context, string Page), int> virtualTextureFeedbackStacks = new();
    private static readonly string[] RuntimeVirtualTextureMaterials = [
        "BaseColor", "Mask4", "BaseColor_Normal_Roughness", "BaseColor_Normal_Specular",
        "BaseColor_Normal_Specular_YCoCg", "BaseColor_Normal_Specular_Mask_YCoCg", "WorldHeight", "Displacement"
    ];

    private Expr RuntimeVirtualTextureSample(Context context, SourceObject node, int output)
    {
        if (output is < 0 or > 7) throw new ConversionException("Invalid runtime virtual texture sample output");
        JObject properties = node.Properties;
        Expr fallback = output switch {
            0 => Vector([0, 0, 0]), 1 or 2 => Constant(.5), 3 => Vector([0, 0, 1]),
            5 => Constant(1), 7 => Vector([0, 0, 0, 0]), _ => Constant(0)
        };
        string? parameter = node.ClassName == "MaterialExpressionRuntimeVirtualTextureSampleParameter"
                ? properties.Value<string>("ParameterName") : null;
        if (string.Equals(parameter, "None", StringComparison.OrdinalIgnoreCase) || parameter?.Length == 0) parameter = null;
        int texture = properties.Value<int?>("VirtualTexture") ?? 0;
        if (texture == 0) {
            if (parameter is not null) throw new ConversionException($"Missing virtual texture for parameter {parameter}");
            return fallback;
        }
        string source = context.Asset.ObjectPath(texture);
        string layout = (properties.Value<string>("MaterialType") ?? "BaseColor").Split("::").Last();
        if (int.TryParse(layout, out int ordinal) && ordinal >= 0 && ordinal < RuntimeVirtualTextureMaterials.Length)
            layout = RuntimeVirtualTextureMaterials[ordinal];
        int material = Array.IndexOf(RuntimeVirtualTextureMaterials, layout);
        if (material < 0) throw new ConversionException($"Unknown runtime virtual texture material {layout}: {source}");
        bool packed = properties.Value<bool?>("bSinglePhysicalSpace") ?? true;
        bool adaptive = properties.Value<bool?>("bAdaptive") ?? false;
        ValidateAsset(source);
        if (parameter is not null && ParameterValue(context, InstanceParameterType.RuntimeVirtualTexture, parameter)?.Value<string>() is { } replacement) {
            ValidateAsset(replacement);
            source = replacement;
        }
        void ValidateAsset(string path) {
            var asset = library.Load(path).ResolveObject(path, value => value.ClassName == "RuntimeVirtualTexture");
            JObject description = RuntimeVirtualTextureSource.Compile(asset, path);
            if (description.Value<string>("material") != layout || description.Value<bool>("packed_table") != packed
                    || description.Value<bool>("adaptive") != adaptive)
                throw new ConversionException($"Runtime virtual texture material, packed table or adaptive layout differs from {path}");
        }

        bool available = output switch {
            0 => material is 0 or >= 2 and <= 5, 1 => material is >= 3 and <= 5,
            2 or 3 => material is >= 2 and <= 5, 4 => material == 6, 5 => material == 5,
            6 => material == 7, 7 => material == 1, _ => false
        };
        if (!available) return fallback;
        int layerCount = material switch { 2 or 3 => 2, 4 or 5 => 3, _ => 1 };
        var binding = new CompiledVirtualTextureRead("", source, layout, layerCount, packed, adaptive, parameter,
                context.ParameterAssociation, context.ParameterIndex);
        var slot = VirtualTextureSlot(binding);
        string name = slot.Name;
        bool hasUv = Connected(properties["Coordinates"]);
        if (hasUv && Connected(properties["WorldPosition"]))
            throw new ConversionException("Runtime virtual texture sample accepts either Coordinates or WorldPosition");
        Expr WorldUv(string position) => new($"vec2(dot({position}, {name}_U.xyz), dot({position}, {name}_V.xyz))", 2);
        Expr uv;
        if (hasUv) uv = Cast(RequiredInput(context, properties, "Coordinates"), 2);
        else {
            string origin = (properties.Value<string>("WorldPositionOriginType") ?? "Absolute").Split("::").Last();
            bool relative = origin is "CameraRelative" or "1";
            if (!relative && origin is not ("Absolute" or "0")) throw new ConversionException($"Unknown RVT position origin {origin}");
            Expr position = Cast(Input(context, properties["WorldPosition"], () => WorldPosition(new JObject {
                ["WorldPositionShaderOffset"] = relative ? "WPT_CameraRelative" : "WPT_Default"
            })), 3);
            if (relative) position = AbsolutePosition(position.Code) with {
                DerivativeX = position.DerivativeX, DerivativeY = position.DerivativeY
            };
            materialViewUsed |= 8;
            Expr offset = Shared(new Expr($"lfx_lwc_demote(lfx_lwc_subtract({Promote(position)}, " +
                    $"LfxLWC3({name}_OriginTile.xyz, {name}_OriginOffset.xyz)))", 3,
                    DerivativeX: position.DerivativeX, DerivativeY: position.DerivativeY));
            uv = WorldUv(offset.Code) with {
                DerivativeX = offset.DerivativeX is null ? null : WorldUv(offset.DerivativeX).Code,
                DerivativeY = offset.DerivativeY is null ? null : WorldUv(offset.DerivativeY).Code
            };
        }
        uv = Shared(uv);
        string mode = (properties.Value<string>("MipValueMode") ?? "RVTMVM_None").Split("::").Last();
        bool level = mode is "RVTMVM_MipLevel" or "1";
        bool bias = mode is "RVTMVM_MipBias" or "2";
        bool noDerivatives = compilingVertex || volumeCoordinates;
        Expr mip = level || bias ? Cast(Input(context, properties["MipValue"], () => Constant(0)), 1) : Constant(0);
        (Expr dx, Expr dy) = mode switch {
            "RVTMVM_DerivativeUV" or "4" => (Cast(RequiredInput(context, properties, "DDX"), 2), Cast(RequiredInput(context, properties, "DDY"), 2)),
            "RVTMVM_DerivativeWorld" or "5" => (WorldUv(Cast(RequiredInput(context, properties, "DDX"), 3).Code),
                    WorldUv(Cast(RequiredInput(context, properties, "DDY"), 3).Code)),
            "RVTMVM_RecalculateDerivatives" or "3" when !noDerivatives => WorldGradients(),
            "RVTMVM_None" or "0" or "RVTMVM_MipBias" or "2" when !noDerivatives =>
                    (new Expr(naniteCoordinates && uv.DerivativeX is not null
                        ? $"lfx_material_ddx({uv.Code}, {uv.DerivativeX})" : $"dFdx({uv.Code})", 2),
                     new Expr(naniteCoordinates && uv.DerivativeY is not null
                        ? $"lfx_material_ddy({uv.Code}, {uv.DerivativeY})" : $"dFdy({uv.Code})", 2)),
            "RVTMVM_MipLevel" or "1" or "RVTMVM_None" or "0" or "RVTMVM_MipBias" or "2" or "RVTMVM_RecalculateDerivatives" or "3"
                    => (Vector([0, 0]), Vector([0, 0])),
            _ => throw new ConversionException($"Unknown runtime virtual texture mip mode {mode}")
        };
        (Expr, Expr) WorldGradients() {
            Expr world = Shared(WorldPosition(new JObject { ["WorldPositionShaderOffset"] = "WPT_CameraRelative" }));
            string dx = naniteCoordinates && world.DerivativeX is not null
                ? $"lfx_material_ddx({world.Code}, {world.DerivativeX})" : $"dFdx({world.Code})";
            string dy = naniteCoordinates && world.DerivativeY is not null
                ? $"lfx_material_ddy({world.Code}, {world.DerivativeY})" : $"dFdy({world.Code})";
            return (WorldUv(dx), WorldUv(dy));
        }
        string address = (properties.Value<string>("TextureAddressMode") ?? "RVTTA_Clamp").Split("::").Last();
        int addressMode = address switch {
            "RVTTA_Clamp" or "0" => 0, "RVTTA_Wrap" or "1" => 1,
            _ => throw new ConversionException($"Unknown runtime virtual texture address mode {address}")
        };
        string suffix = (level || noDerivatives ? "true" : "false") + ", " + mip.Code;
        string noise = noDerivatives || level ? "0.0" :
                $"fract(52.9829189 * fract(dot(gl_FragCoord.xy + mod({name}_Options.z, 8.0) * 5.588238, vec2(0.06711056, 0.00583715))))";
        string page = $"lfx_vt_page({name}_Table0, {name}_Table1, {uv.Code}, {dx.Code}, {dy.Code}, " +
                $"ivec2({addressMode}), {suffix}, {name}_Options.y, {noise}, {(adaptive ? "true" : "false")})";
        if (!noDerivatives && (!level || properties.Value<bool?>("bEnableFeedback") != false))
            page = VirtualTextureFeedbackCode(context, name, page);
        if (adaptive) page = $"lfx_vt_adaptive({page}, {name}_Indirection)";
        string explicitLevel = level || noDerivatives ? "true" : "false";
        string Sample(int layer) => adaptive
                ? $"lfx_vt_adaptive_physical({name}_Pages{layer}, {name}_Fine, {name}_Physical{layer}, {name}_Table1, " +
                        $"{name}_Layer[{layer}], {page}, {explicitLevel}, {name}_Height.z != 0.0)"
                : $"lfx_vt_physical({name}_Pages{layer}, {name}_Physical{layer}, {name}_Table1, " +
                        $"{name}_Layer[{layer}], {page}, 0, {explicitLevel}, {name}_Height.z != 0.0)";
        string Read(int layer, string swizzle) => $"({Sample(layer)}).{swizzle}";
        string result = output switch {
            0 when material >= 4 => $"lfx_rvt_ycocg_to_rgb({Sample(0)})",
            0 when material == 2 => $"srgbToLinear({Read(0, "rgb")})",
            0 => Read(0, "rgb"),
            1 => Read(material == 3 ? 1 : 2, "r"),
            2 => Read(material <= 3 ? 1 : 2, "g"),
            3 when material == 2 => $"lfx_rvt_normal({Read(1, "rb")}, 1.0)",
            3 when material == 3 => $"lfx_rvt_normal(vec2({Read(0, "a")}, {Read(1, "a")}), {Read(1, "b")})",
            3 => $"lfx_rvt_normal({Read(1, "rg")}, {Read(2, "b")})",
            4 => $"({Read(0, "r")} * {name}_Height.x + {name}_Height.y)",
            5 => Read(2, "a"),
            6 => Read(0, "r"),
            _ => Sample(0)
        };
        return new Expr($"({name}_Options.x != 0.0 ? {result} : {fallback.Code})", fallback.Width);
    }

    private CompiledVirtualTextureRead VirtualTextureSlot(CompiledVirtualTextureRead binding)
    {
        if (!virtualTextureReads.TryGetValue(binding, out var slot)) {
            slot = binding with { Name = "LfxVT" + virtualTextureReads.Count };
            virtualTextureReads.Add(binding, slot);
        }
        return slot;
    }

    private string VirtualTextureFeedbackCode(Context context, string name, string page)
    {
        var key = (context.Id, page);
        if (!virtualTextureFeedbackStacks.TryGetValue(key, out int stack))
            virtualTextureFeedbackStacks.Add(key, stack = virtualTextureFeedbackStacks.Count);
        return $"lfx_vt_feedback({page}, uint({name}_Options.w), uint({name}_Options.z), {stack}u, LFX_VT_FEEDBACK_STACKS, lfxVtSeen{stack})";
    }

    private void AppendVirtualTextureReadDeclarations(StringBuilder shader, bool vertex = false)
    {
        if (virtualTextureReads.Count == 0) return;
        shader.AppendLine("#moj_import <lumenfx:material_virtual_texture.glsl>");
        if (!vertex && virtualTextureFeedbackStacks.Count != 0) {
            shader.AppendLine($"#define LFX_VT_FEEDBACK_STACKS {virtualTextureFeedbackStacks.Count}u");
            shader.AppendLine("#moj_import <lumenfx:material_virtual_texture_feedback.glsl>");
            for (int stack = 0; stack < virtualTextureFeedbackStacks.Count; stack++)
                shader.AppendLine($"bool lfxVtSeen{stack} = false;");
        }
        foreach (var read in virtualTextureReads.Values) {
            string name = read.Name;
            shader.AppendLine($"layout(std140) uniform {name}View {{");
            shader.AppendLine($"    uvec4 {name}_Table0; uvec4 {name}_Table1; uvec4 {name}_Layer[3];");
            shader.AppendLine($"    vec4 {name}_OriginTile; vec4 {name}_OriginOffset; vec4 {name}_U; vec4 {name}_V;");
            shader.AppendLine($"    vec4 {name}_Height; vec4 {name}_Options; ivec4 {name}_SizeAndAddress;");
            shader.AppendLine("};");
            for (int layer = 0; layer < read.Layers; layer++) {
                shader.AppendLine($"uniform usampler2D {name}_Pages{layer};");
                shader.AppendLine($"uniform sampler2D {name}_Physical{layer};");
            }
            if (read.Adaptive) {
                shader.AppendLine($"uniform usampler2D {name}_Indirection;");
                shader.AppendLine($"uniform usampler2D {name}_Fine;");
            }
        }
    }

    private void AppendVirtualTextureFeedback(StringBuilder shader)
    {
        if (virtualTextureFeedbackStacks.Count != 0) shader.AppendLine("    lfx_vt_commit_feedback();");
    }
}
