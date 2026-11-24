using System.Text;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private const int ClosureAnisotropy = 1 << 4;
    private const int ClosureEdge = 1 << 5;
    private const int ClosureHaze = 1 << 6;
    private const int ClosureFuzz = 1 << 7;
    private const int ClosureWrap = 1 << 8;
    private const int ClosureVolume = 1 << 9;
    private const int ClosureSpecularProfile = 1 << 10;
    private const int ClosureAbove = 1 << 11;

    private sealed record SlabPayload(SlabLayer Layer, int Length, int Features, bool Diffusion);
    private sealed record SlabClosurePayload(LayeredSlabs Source, IReadOnlyList<SlabPayload> Closures,
        int Words, int LightingFeatures, bool Anisotropy, bool VolumeLookup, bool Diffusion, bool Thin, string NormalSign);

    private SlabClosurePayload CompileSlabClosurePayload(LayeredSlabs graph)
    {
        var payload = new List<SlabPayload>(graph.Layers.Count);
        bool diffusion = graph.Layers.Any(layer => layer.Slab.RequiresDeferredMedium);
        int offset = diffusion ? 5 : 3;
        int lightingFeatures = 1;
        bool anisotropy = false, volumeLookup = false;
        foreach (var layer in graph.Layers) {
            SlabSubstrate slab = layer.Slab;
            int features = (slab.WorldTangent is null ? 0 : ClosureAnisotropy)
                | (slab.AuthoredF90 ? ClosureEdge : 0)
                | (slab.SecondWeight.ZeroConstant && slab.SimpleCoat.ZeroConstant && slab.DiffusionProfile is null ? 0 : ClosureHaze)
                | (slab.FuzzAmount.ZeroConstant ? 0 : ClosureFuzz)
                | (slab.Wrap is null ? 0 : ClosureWrap)
                | (layer.VolumeMfp is null ? 0 : ClosureVolume)
                | (slab.SpecularProfile is null ? 0 : ClosureSpecularProfile)
                | (layer.CoverageAbove.ZeroConstant ? 0 : ClosureAbove);
            int words = 5 + System.Numerics.BitOperations.PopCount((uint)(features & ~ClosureAnisotropy))
                + (slab.RequiresDeferredMedium ? 1 : 0);
            payload.Add(new SlabPayload(layer, words, features, slab.RequiresDeferredMedium));
            offset += words;
            if ((features & ClosureAnisotropy) != 0) anisotropy = true;
            if ((features & ClosureFuzz) != 0) lightingFeatures |= 2;
            if ((features & ClosureWrap) != 0) lightingFeatures |= 128;
            if ((features & ClosureSpecularProfile) != 0) lightingFeatures |= 256;
            if ((features & ClosureVolume) != 0) {
                lightingFeatures |= 512;
                volumeLookup = true;
            }
            volumeLookup |= slab.UsesVolumeLookup;
        }
        string normalSign = rasterTwoSided && compiledMaterialProperties.Value<bool?>("bTangentSpaceNormal") != false
            ? RasterTwoSidedSign().Code : "1.0";
        if (diffusion) lightingFeatures |= 1024;
        bool thin = compiledMaterialProperties.Value<bool?>("bIsThinSurface") == true;
        return new SlabClosurePayload(graph, payload, offset, lightingFeatures, anisotropy, volumeLookup, diffusion, thin, normalSign);
    }

    private static void AppendSlabClosurePayload(StringBuilder shader, SlabClosurePayload payload, int decalResponse)
    {
        shader.AppendLine("#if LFX_SUBSTRATE_ADAPTIVE_REPLAY");
        shader.AppendLine($"    int lfxSlabOffset = {(payload.Diffusion ? "LFX_ADAPTIVE_FIRST_DIFFUSION_CLOSURE" : "LFX_ADAPTIVE_FIRST_CLOSURE")};");
        shader.AppendLine("    uint lfxVisibleSlabs = 0u;");
        shader.AppendLine("    vec3 lfxTopNormal = vec3(0.0);");
        shader.AppendLine("    vec2 lfxTopRoughnessWeight = vec2(0.0);");
        shader.AppendLine("    LfxSlabDiffusionPixel lfxDiffusion = lfx_slab_diffusion_pixel();");
        foreach (var data in payload.Closures) {
            var layer = data.Layer;
            var slab = layer.Slab;
            shader.AppendLine($"    if (any(greaterThan({layer.Luminance.Code}, vec3(0.0)))) {{");
            shader.AppendLine($"        lfx_write_adaptive_slab(lfxSlabOffset, {data.Length | data.Features}u, {decalResponse},");
            shader.AppendLine($"            ({slab.WorldNormal.Code} * {payload.NormalSign}).xzy, ({slab.WorldTangent?.Code ?? "vec3(0.0)"}).xzy, {slab.Anisotropy.Code},");
            shader.AppendLine($"            {slab.Diffuse.Code}, {slab.F0.Code}, {slab.F90.Code}, {slab.Roughness.Code},");
            shader.AppendLine($"            {slab.SecondRoughness.Code}, {slab.SecondWeight.Code}, {slab.SimpleCoat.Code}, {slab.CoatNormalOct?.Code ?? "vec2(0.0)"},");
            shader.AppendLine($"            {slab.FuzzColor.Code}, {slab.FuzzAmount.Code}, {slab.FuzzRoughness.Code},");
            shader.AppendLine($"            {layer.Luminance.Code}, {layer.CoverageAbove.Code}, {layer.TransmittanceAbove.Code},");
            shader.AppendLine($"            {slab.Wrap?.MeanFreePath.Code ?? layer.VolumeMfp?.Code ?? "vec3(0.0)"},");
            shader.AppendLine($"            {slab.Wrap?.PhaseAnisotropy.Code ?? slab.VolumePhase?.Code ?? "0.0"},"
                + $" int({slab.Wrap?.Type.Code ?? "0.0"}), {slab.SpecularProfile?.Code ?? "0.0"},");
            string phase = slab.DiffusionPhase?.Code ?? "0.0";
            string diffuseMfp = data.Diffusion ? slab.MeanFreePath.Code : "vec3(0.0)";
            string profile = slab.DiffusionProfile?.Index.Code ?? "-1.0";
            string radius = slab.DiffusionProfile?.RadiusScale.Code ?? "0.0";
            shader.AppendLine($"            {layer.TopWeight.Code}, lfxTopNormal, lfxTopRoughnessWeight,");
            shader.AppendLine($"            {(data.Diffusion ? "true" : "false")}, {diffuseMfp}, {phase}, int({profile}), {radius},"
                + $" {slab.Thickness.Code}, {(payload.Thin ? "true" : "false")}, {layer.Coverage.Code}, lfxDiffusion);");
            shader.AppendLine($"        lfxSlabOffset += {data.Length};");
            shader.AppendLine("        lfxVisibleSlabs++;");
            shader.AppendLine("    }");
        }
        shader.AppendLine($"    lfx_write_adaptive_header(lfxVisibleSlabs, lfxTopNormal, lfxTopRoughnessWeight,"
            + $" {(payload.Diffusion ? "true" : "false")}, lfxDiffusion);");
        shader.AppendLine("#endif");
    }

    private void AppendFirstVisibleSlabSurface(StringBuilder shader, SlabClosurePayload payload, SlabSubstrate sceneSurface)
    {
        shader.AppendLine("#if LFX_PARTICLE_SURFACE && !LFX_PARTICLE_MOTION");
        string branch = "if";
        foreach (var data in payload.Closures) {
            var layer = data.Layer;
            var slab = layer.Slab with {
                WorldNormal = new Expr($"({layer.Slab.WorldNormal.Code} * {payload.NormalSign})", 3),
                Coverage = payload.Source.Coverage, AmbientOcclusion = sceneSurface.AmbientOcclusion,
                DecalResponse = sceneSurface.DecalResponse
            };
            shader.AppendLine($"    {branch} (any(greaterThan({layer.Luminance.Code}, vec3(0.0)))) {{");
            AppendSlabSubstrateSurface(shader, slab);
            shader.AppendLine("    }");
            branch = "else if";
        }
        shader.AppendLine("    else {");
        shader.AppendLine("#if LFX_MATERIAL_WRITES_DEPTH");
        shader.AppendLine("        lfx_write_unlit_particle_surface(lfxMaterialDeviceZ);");
        shader.AppendLine("#elif LFX_NANITE_MATERIAL");
        shader.AppendLine("        lfx_write_unlit_particle_surface(lfxNaniteGeometry.deviceDepth);");
        shader.AppendLine("#else");
        shader.AppendLine("        lfx_write_unlit_particle_surface(gl_FragCoord.z);");
        shader.AppendLine("#endif");
        shader.AppendLine("    }");
        shader.AppendLine("#endif");
    }
}
