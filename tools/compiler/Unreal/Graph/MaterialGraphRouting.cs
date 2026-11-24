using System.Globalization;
using System.Text;
using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    [Flags]
    private enum MaterialPassSwitchFlags
    {
        None = 0,
        NaniteReplace = 1,
        ShadowReplace = 2
    }

    private MaterialPassSwitchFlags pixelPassSwitchFlags, vertexPassSwitchFlags;

    private void MarkPassSwitchFlag(MaterialPassSwitchFlags flag)
    {
        if (compilingVertex) vertexPassSwitchFlags |= flag;
        else pixelPassSwitchFlags |= flag;
    }

    private void AppendMaterialPassSwitchHelpers(StringBuilder shader, bool vertex)
    {
        MaterialPassSwitchFlags flags = vertex ? vertexPassSwitchFlags : pixelPassSwitchFlags;
        if ((flags & MaterialPassSwitchFlags.NaniteReplace) != 0) {
            shader.AppendLine("bool lfx_nanite_replace_state() {");
            shader.AppendLine("#if (defined(LFX_NANITE_MATERIAL) && LFX_NANITE_MATERIAL) || (defined(LFX_NANITE_VERTEX_MATERIAL) && LFX_NANITE_VERTEX_MATERIAL)");
            shader.AppendLine("    return true;");
            shader.AppendLine("#else");
            shader.AppendLine("    return false;");
            shader.AppendLine("#endif");
            shader.AppendLine("}");
        }
        if ((flags & MaterialPassSwitchFlags.ShadowReplace) != 0) {
            shader.AppendLine("bool lfx_shadow_replace_state() {");
            shader.AppendLine("#if (defined(LFX_PARTICLE_POINT_SHADOW) && LFX_PARTICLE_POINT_SHADOW) || (defined(LFX_SHADOW_DEPTH_PASS) && LFX_SHADOW_DEPTH_PASS)");
            shader.AppendLine("    return true;");
            shader.AppendLine("#elif (defined(LFX_NANITE_MATERIAL) && LFX_NANITE_MATERIAL) || (defined(LFX_NANITE_VERTEX_MATERIAL) && LFX_NANITE_VERTEX_MATERIAL)");
            shader.AppendLine("    return lfx_nanite_shadow_depth();");
            shader.AppendLine("#else");
            shader.AppendLine("    return false;");
            shader.AppendLine("#endif");
            shader.AppendLine("}");
        }
    }

    private bool TrySelectStaticSwitchInput(Context context, SourceObject node, JObject properties, out JToken? selected)
    {
        selected = null;
        switch (node.ClassName) {
            case "MaterialExpressionShaderStageSwitch":
            case "MaterialExpressionRayTracingQualitySwitch":
                selected = StageSwitchInput(context, properties, node.ClassName);
                return true;
            case "MaterialExpressionQualitySwitch":
            case "MaterialExpressionFeatureLevelSwitch":
            case "MaterialExpressionShadingPathSwitch":
            case "MaterialExpressionDataDrivenShaderPlatformInfoSwitch":
                selected = PlatformSwitchInput(context, node, properties);
                return true;
            case "MaterialExpressionRuntimeVirtualTextureReplace":
                selected = properties[VirtualTextureReplaceInput(properties)];
                return true;
            case "MaterialExpressionPreviousFrameSwitch":
                selected = properties[compilingPreviousFrame ? "PreviousFrame" : "CurrentFrame"];
                return true;
            case "MaterialExpressionVirtualTextureFeatureSwitch":
                RequireExpressionConnection(context, properties["Yes"], "Yes");
                RequireExpressionConnection(context, properties["No"], "No");
                selected = properties["Yes"];
                return true;
            case "MaterialExpressionDistanceFieldsRenderingSwitch": {
                RequireExpressionConnection(context, properties["Yes"], "Yes");
                RequireExpressionConnection(context, properties["No"], "No");
                var platform = library.MaterialPermutation.ShaderPlatform;
                selected = properties[!platform.IsMobile && platform.SupportsDistanceFields ? "Yes" : "No"];
                return true;
            }
            case "MaterialExpressionMaterialProxyReplace":
                RequireExpressionConnection(context, properties["Realtime"], "Realtime");
                RequireExpressionConnection(context, properties["MaterialProxy"], "MaterialProxy");
                selected = properties["Realtime"];
                return true;
            case "MaterialExpressionMeshPaintTextureReplace":
                RequireExpressionConnection(context, properties["Default"], "Default");
                RequireExpressionConnection(context, properties["MeshPaintTexture"], "MeshPaintTexture");
                selected = properties["Default"];
                return true;
            case "MaterialExpressionBindlessSwitch":
                RequireExpressionConnection(context, properties["Default"], "Default");
                RequireExpressionConnection(context, properties["Bindless"], "Bindless");
                selected = properties["Default"];
                return true;
            case "MaterialExpressionRequiredSamplersSwitch": {
                RequireExpressionConnection(context, properties["InputTrue"], "InputTrue");
                RequireExpressionConnection(context, properties["InputFalse"], "InputFalse");
                int required = properties.Value<int?>("RequiredSamplers") ?? 16;
                selected = properties[required <= library.MaterialPermutation.ShaderPlatform.MaxSamplers ? "InputTrue" : "InputFalse"];
                return true;
            }
            case "MaterialExpressionLightmassReplace":
                RequireExpressionConnection(context, properties["Realtime"], "Realtime");
                RequireExpressionConnection(context, properties["Lightmass"], "Lightmass");
                selected = properties["Realtime"];
                return true;
            case "MaterialExpressionReflectionCapturePassSwitch":
                RequireExpressionConnection(context, properties["Default"], "Default");
                RequireExpressionConnection(context, properties["Reflection"], "Reflection");
                selected = properties["Default"];
                return true;
            case "MaterialExpressionGIReplace":
                RequireExpressionConnection(context, properties["Default"], "Default");
                selected = properties["Default"];
                return true;
            case "MaterialExpressionPathTracingQualitySwitch":
                RequireExpressionConnection(context, properties["Normal"], "Normal");
                RequireExpressionConnection(context, properties["PathTraced"], "PathTraced");
                selected = properties["Normal"];
                return true;
            case "MaterialExpressionPathTracingRayTypeSwitch":
                RequireExpressionConnection(context, properties["Main"], "Main");
                selected = properties["Main"];
                return true;
            default:
                return false;
        }
    }

    private Expr SelectBooleanCondition(string condition, Expr whenTrue, Expr whenFalse)
    {
        if (whenTrue == whenFalse) return whenTrue;
        if (UsesMirTranslation && (whenTrue.IsInteger || whenFalse.IsInteger || IsMirBoolean(whenTrue) || IsMirBoolean(whenFalse)))
            return MirTernary("Select", new Expr(condition, 1, ShaderBoolean: true), whenTrue, whenFalse);
        int width = ArithmeticWidth(whenTrue, whenFalse);
        bool largeWorld = whenTrue.LargeWorld || whenFalse.LargeWorld;
        Expr left = ArithmeticResult(whenTrue, width, largeWorld);
        Expr right = ArithmeticResult(whenFalse, width, largeWorld);
        string code = largeWorld
                ? $"lfx_lwc_select({condition}, {left.RawCode}, {right.RawCode})"
                : $"({condition} ? {left.Code} : {right.Code})";
        Expr result = new(code, width, LargeWorld: largeWorld, Uniform: whenTrue.Uniform && whenFalse.Uniform);
        if (!AnalyticDerivatives) return result;
        string zero = ZeroDerivative(width);
        string? dx = (left.DerivativeX, right.DerivativeX) switch {
            (null, null) => null,
            (string l, string r) when l == zero && r == zero => zero,
            (string l, null) => $"({condition} ? {l} : {zero})",
            (null, string r) => $"({condition} ? {zero} : {r})",
            (string l, string r) => $"({condition} ? {l} : {r})"
        };
        string? dy = (left.DerivativeY, right.DerivativeY) switch {
            (null, null) => null,
            (string l, string r) when l == zero && r == zero => zero,
            (string l, null) => $"({condition} ? {l} : {zero})",
            (null, string r) => $"({condition} ? {zero} : {r})",
            (string l, string r) => $"({condition} ? {l} : {r})"
        };
        return result with { DerivativeX = dx, DerivativeY = dy };
    }

    private Expr NaniteReplace(Context context, JObject properties)
    {
        Expr fallback = RequiredInput(context, properties, "Default");
        Expr nanite = RequiredInput(context, properties, "Nanite");
        if (fallback == nanite) return fallback;
        MarkPassSwitchFlag(MaterialPassSwitchFlags.NaniteReplace);
        return SelectBooleanCondition("lfx_nanite_replace_state()", nanite, fallback);
    }

    private Expr ShadowReplace(Context context, JObject properties)
    {
        Expr fallback = RequiredInput(context, properties, "Default");
        Expr shadow = RequiredInput(context, properties, "Shadow");
        if (fallback == shadow) return fallback;
        MarkPassSwitchFlag(MaterialPassSwitchFlags.ShadowReplace);
        return SelectBooleanCondition("lfx_shadow_replace_state()", shadow, fallback);
    }

    private Expr CompileSwitchNode(Context context, SourceObject node, JObject properties)
    {
        var entries = (properties["Inputs"] as JArray)?.OfType<JObject>().ToArray() ?? Array.Empty<JObject>();
        if (entries.Length == 0)
            throw new ConversionException($"Material Switch expression has no inputs in {context.Asset.Name}:{node.Name}");
        for (int index = 0; index < entries.Length; index++) {
            string pinName = entries[index].Value<string>("InputName") ?? $"Input {index}";
            RequireExpressionConnection(context, entries[index]["Input"], pinName);
        }
        Expr selector = Input(context, properties["SwitchValue"], () => Constant(SourceAsset.Number(properties["ConstSwitchValue"])));
        selector = Cast(DemoteLargeWorld(selector), 1);
        JToken? defaultToken = properties["Default"];
        if (double.TryParse(selector.Code, NumberStyles.Float, CultureInfo.InvariantCulture, out double constantSelector)) {
            for (int index = 0; index < entries.Length; index++) {
                if (constantSelector >= index && constantSelector < index + 1)
                    return RequiredInput(context, entries[index], "Input");
            }
            return Input(context, defaultToken, () => Constant(SourceAsset.Number(properties["ConstDefault"])));
        }
        Expr selectorShared = Shared(selector);
        Expr acc = Input(context, defaultToken, () => Constant(SourceAsset.Number(properties["ConstDefault"])));
        var compiledInputs = new Expr[entries.Length];
        for (int index = 0; index < entries.Length; index++)
            compiledInputs[index] = RequiredInput(context, entries[index], "Input");
        for (int index = compiledInputs.Length - 1; index >= 0; index--) {
            string condition = $"({selectorShared.Code} >= {Number(index)} && {selectorShared.Code} < {Number(index + 1)})";
            acc = SelectBooleanCondition(condition, compiledInputs[index], acc);
        }
        return Shared(acc);
    }

    private static JToken? RerouteInput(Context context, SourceObject node)
    {
        if (node.ClassName != "MaterialExpressionNamedRerouteUsage") return node.Properties["Input"];
        int reference = node.Properties.Value<int?>("Declaration") ?? 0;
        if (reference <= 0)
            throw new ConversionException($"Invalid named reroute declaration in {context.Asset.Name}:{node.Name}");
        var declaration = context.Asset.Object(reference);
        if (declaration.ClassName != "MaterialExpressionNamedRerouteDeclaration")
            throw new ConversionException($"Invalid named reroute declaration in {context.Asset.Name}:{node.Name}");
        return declaration.Properties["Input"];
    }

    private JToken? StageSwitchInput(Context context, JObject properties, string type)
    {
        bool rayTracing = type == "MaterialExpressionRayTracingQualitySwitch";
        string pixel = rayTracing ? "Normal" : "PixelShader";
        string other = rayTracing ? "RayTraced" : "VertexShader";
        RequireExpressionConnection(context, properties[pixel], pixel);
        RequireExpressionConnection(context, properties[other], other);
        return properties[compilingVertex && !rayTracing ? other : pixel];
    }

    private JToken? PlatformSwitchInput(Context context, SourceObject node, JObject properties)
    {
        if (node.ClassName == "MaterialExpressionDataDrivenShaderPlatformInfoSwitch")
            return ShaderPlatformPropertyInput(context, properties);
        var defaultInput = properties["Default"];
        var permutation = library.MaterialPermutation;
        int selected = node.ClassName switch {
            "MaterialExpressionQualitySwitch" => permutation.Quality == MaterialQualityInput.Default ? -1 : (int)permutation.Quality,
            "MaterialExpressionFeatureLevelSwitch" => (int)permutation.EffectiveFeatureLevel,
            "MaterialExpressionShadingPathSwitch" => (int)permutation.ShadingPath,
            _ => throw new ConversionException($"Unknown material platform switch {node.ClassName}")
        };
        if (selected < 0)
        {
            RequireExpressionConnection(context, defaultInput, "Default");
            return defaultInput;
        }
        var inputs = properties["Inputs"] as JArray;
        JToken? overrideInput = selected < (inputs?.Count ?? 0) ? inputs![selected] : null;
        if (overrideInput is null)
        {
            var source = (node.Raw["Data"] as JArray)?.OfType<JObject>().FirstOrDefault(property =>
                property.Value<string>("Name") == "Inputs" && property.Value<int>("ArrayIndex") == selected);
            overrideInput = source is null ? null : SourceAsset.Value(source);
        }
        if (HasExpressionConnection(context, overrideInput, "Inputs")) return overrideInput;
        RequireExpressionConnection(context, defaultInput, "Default");
        return defaultInput;
    }

    private JToken? ShaderPlatformPropertyInput(Context context, JObject properties)
    {
        var conditions = properties["DDSPIPropertyNames"] as JArray;
        bool hasProperty = false, matches = true;
        foreach (var condition in (conditions ?? new JArray()).OfType<JObject>()) {
            string name = condition.Value<string>("InputName") ?? "None";
            if (SourceNameOrder.Equal(name, "None")) continue;
            bool expected = (condition.Value<string>("PropertyCondition") ?? "COND_True").Split("::").Last() switch {
                "0" or "COND_True" => true,
                "1" or "COND_False" => false,
                var value => throw new ConversionException($"Invalid shader platform condition {value} in {context.Asset.Name}")
            };
            hasProperty = true;
            matches &= library.MaterialPermutation.ShaderPlatform.ReadCondition(name) == expected;
        }
        if (!hasProperty) throw new ConversionException($"Empty DataDrivenShaderPlatformInfo switch in {context.Asset.Name}");
        string selected = matches ? "InputTrue" : "InputFalse";
        RequireExpressionConnection(context, properties[selected], selected);
        return properties[selected];
    }

    private static void RequireExpressionConnection(Context context, JToken? connection, string name)
    {
        if (!HasExpressionConnection(context, connection, name))
            throw new ConversionException($"Missing material input {name} in {context.Asset.Name}");
    }

    private static bool HasExpressionConnection(Context context, JToken? connection, string name)
    {
        var traced = new HashSet<int>();
        while (connection is JObject input && input.Value<int?>("expression") is int index && index != 0) {
            if (!traced.Add(index))
                throw new ConversionException($"Cyclic material reroute in {context.Asset.Name}:{name}");
            var node = context.Asset.Object(index);
            if (node.ClassName is not ("MaterialExpressionReroute" or "MaterialExpressionNamedRerouteDeclaration"
                    or "MaterialExpressionNamedRerouteUsage")) return true;
            connection = RerouteInput(context, node);
        }
        return false;
    }
}
