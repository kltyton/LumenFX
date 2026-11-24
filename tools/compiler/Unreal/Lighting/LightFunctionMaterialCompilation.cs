using System.Text;
using LumenFx.Compiler.Unreal.Attributes;
using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private CompiledMaterial CompileLightFunctionMaterial(SourceAsset asset, SourceObject material, string sourceObject)
    {
        string domain = (material.Properties.Value<string>("MaterialDomain") ?? "MD_Surface").Split("::").Last();
        if (domain != "MD_LightFunction") throw new ConversionException($"Light component requires a light-function material: {sourceObject}");
        SourceObject data = material.Properties["EditorOnlyData"] is { } editor ? asset.Object(editor.Value<int>()) : material;
        Context context = new(nextContext++, asset, new Dictionary<int, FunctionInputBinding>());
        Expr emissive;
        if (Connected(data.Properties["FrontMaterial"])) {
            if (CompileSubstrateSurface(context, data.Properties["FrontMaterial"], new()) is not LightFunctionSubstrate function)
                throw new ConversionException($"Front Material must supply a Substrate Light Function: {sourceObject}");
            // GetLightFunctionColor sanitizes and reads the emissive field of the single unlit closure directly.
            emissive = function.Emissive;
        } else {
            emissive = MaterialInput(context, data, MaterialAttribute.EmissiveColor, () => Constant(0)).Value;
        }
        StringBuilder shader = new(MaterialShaderHeader());
        shader.AppendLine("#moj_import <minecraft:projection.glsl>");
        shader.AppendLine("layout(std140) uniform EffectFrame { vec4 EffectClock; vec4 MaterialParameters; vec4 EffectTimeData; };");
        AppendMaterialCollections(shader, false);
        shader.AppendLine("layout(std140) uniform EffectView { vec4 CameraRight; vec4 CameraUp; vec4 Geometry; vec4 DepthTransform; };");
        AppendMaterialView(shader);
        AppendMaterialPassSwitchHelpers(shader, vertex: false);
        AppendVirtualTextureReadDeclarations(shader);
        shader.AppendLine("#moj_import <lumenfx:light_function_material.glsl>");
        AppendTextureSamplers(shader);
        MaterialShaderMath.Append(shader);
        AppendMaterialNoise(shader);
        AppendLargeWorldMath(shader);
        AppendPeriodicWorldMath(shader);
        if (sphericalOpacityUsed) MaterialShaderMath.AppendSphericalOpacity(shader);
        foreach (var sampler in UsedTextureSamplers()) AppendSampler(shader, sampler);
        AppendPixelCoordinates(shader);
        foreach (string function in functions) shader.AppendLine(function);
        shader.AppendLine("void main() {");
        shader.AppendLine("    lfx_initialize_light_function();");
        foreach (string expression in expressions) shader.AppendLine("    " + expression);
        string emission = Cast(emissive, 3).Code;
        if (material.Properties.Value<bool?>("bAllowNegativeEmissiveColor") != true) emission = $"max({emission}, vec3(0.0))";
        shader.AppendLine($"    lfx_write_light_function({emission});");
        AppendVirtualTextureFeedback(shader);
        shader.AppendLine("}");
        return new CompiledMaterial(shader.ToString(), CompiledTextureBindings(),
                new Dictionary<string, TextureSettings>(textureSettings, StringComparer.Ordinal), "opaque", false, false, dynamicDefaults ?? new[] { 1d, 1d, 1d, 1d }, false, false,
                "before_dof", dynamicSlots, ribbonParticleData, true, sceneDepthUsed, false) {
            SourceObject = sourceObject, MaterialView = materialViewUsed, LightFunction = true, ReceivesDecals = false,
            LightFunctionAtlasCompatible = material.Properties.Value<bool?>("bForceCompatibleWithLightFunctionAtlas") == true
                    || (!lightFunctionUsesPositionOrCoordinates && !sceneDepthUsed && deferredSceneTextures == 0),
            RuntimeParameters = runtimeParameters.ToArray(), RuntimeTextures = CompiledRuntimeTextures(),
            TextureSamplerSources = CompiledTextureSamplerSources(),
            MaterialCollections = materialCollections.ToArray(),
            VirtualTextureReads = virtualTextureReads.Values.ToArray(),
            VirtualTextureFeedback = virtualTextureFeedbackStacks.Count != 0,
            FontSamplers = fontSamplers.Values.ToArray(),
            TextureParameterSamplers = CompiledTextureParameterSamplers(),
            FontAssets = new Dictionary<string, IReadOnlyDictionary<int, string>>(fontAssets, SourceNameOrder.Instance),
            RenderTargetTextures = new HashSet<string>(renderTargetTextures, StringComparer.Ordinal)
        };
    }

    private Expr LightVector()
    {
        if (lightFunctionCoordinates) return new Expr("lfxLightFunctionVector", 3);
        if (decalCoordinates) return new Expr("lfxDecalLightVector", 3);
        throw new ConversionException("LightVector is only available in light-function and deferred-decal materials");
    }
}
