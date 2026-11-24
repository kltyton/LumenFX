using System.Globalization;
using System.Text;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private bool vertexSceneViewportUsed, vertexSceneDepthUsed, vertexPixelDepthUsed;
    private int vertexCustomSceneTextures, vertexDeferredSceneTextures;

    private static int ParseSceneTextureId(JObject properties)
    {
        string raw = (properties.Value<string>("SceneTextureId") ?? "PPI_SceneColor").Split("::").Last();
        if (int.TryParse(raw, NumberStyles.Integer, CultureInfo.InvariantCulture, out int numeric)
            && numeric is >= 0 and <= 39)
            return numeric;

        return raw switch {
            "PPI_SceneColor" => 0,
            "PPI_SceneDepth" => 1,
            "PPI_DiffuseColor" => 2,
            "PPI_SpecularColor" => 3,
            "PPI_SubsurfaceColor" => 4,
            "PPI_BaseColor" => 5,
            "PPI_Specular" => 6,
            "PPI_Metallic" => 7,
            "PPI_WorldNormal" => 8,
            "PPI_SeparateTranslucency" => 9,
            "PPI_Opacity" => 10,
            "PPI_Roughness" => 11,
            "PPI_MaterialAO" => 12,
            "PPI_CustomDepth" => 13,
            "PPI_PostProcessInput0" => 14,
            "PPI_PostProcessInput1" => 15,
            "PPI_PostProcessInput2" => 16,
            "PPI_PostProcessInput3" => 17,
            "PPI_PostProcessInput4" => 18,
            "PPI_PostProcessInput5" => 19,
            "PPI_PostProcessInput6" => 20,
            "PPI_DecalMask" => 21,
            "PPI_ShadingModelColor" => 22,
            "PPI_ShadingModelID" => 23,
            "PPI_AmbientOcclusion" => 24,
            "PPI_CustomStencil" => 25,
            "PPI_StoredBaseColor" => 26,
            "PPI_StoredSpecular" => 27,
            "PPI_Velocity" => 28,
            "PPI_WorldTangent" => 29,
            "PPI_Anisotropy" => 30,
            "PPI_IsFirstPerson" => 31,
            "PPI_ToonShadingId" => 32,
            "PPI_UserSceneTexture0" => 33,
            "PPI_UserSceneTexture1" => 34,
            "PPI_UserSceneTexture2" => 35,
            "PPI_UserSceneTexture3" => 36,
            "PPI_UserSceneTexture4" => 37,
            "PPI_UserSceneTexture5" => 38,
            "PPI_UserSceneTexture6" => 39,
            _ => throw new ConversionException($"Unknown Niagara material SceneTextureId {raw}")
        };
    }

    private Expr SurfaceSceneTexture(Context context, JObject properties, int output)
        => SurfaceSceneTextureAt(context, properties, output, null);

    private Expr SurfaceSceneTextureAt(Context context, JObject properties, int output, string? explicitUvGlsl)
    {
        if (volumeCoordinates || lightFunctionCoordinates || runtimeVirtualTextureWriter
                || (compiledMaterialProperties.Value<string>("MaterialDomain") ?? "MD_Surface").Split("::").Last() != "MD_Surface")
            throw new ConversionException("SceneTexture requires a supported raster material domain and stage");
        int texture = ParseSceneTextureId(properties);
        bool dimensions = output is 1 or 2;
        if (UsesMirTranslation || !dimensions) ValidateParticleSceneTextureLookup(texture, false);
        if (output == 3) return new Expr("vec4(0.0)", 4, ZeroConstant: true);
        if (output is not (0 or 1 or 2)) throw new ConversionException($"Invalid SceneTexture output {output}");
        viewUniformUsed = true;
        if (output == 1) return new Expr("(1.0 / vec2(Geometry.w, CameraUp.w))", 2);
        if (output == 2) return new Expr("vec2(Geometry.w, CameraUp.w)", 2);

        var (deferredMask, deferredSample) = texture switch {
            24 => (16, "vec4(lfx_deferred_scene_ao(UV))"),
            4 => (66 | 512, "lfx_gbuffer_subsurface(UV)"),
            10 => (66 | 512, "vec4(lfx_gbuffer_opacity(UV))"),
            8 => (1, "vec4(lfx_gbuffer_normal(UV), 0.0)"),
            6 => (2, "vec4(lfx_gbuffer_b(UV).g)"),
            7 => (2, "vec4(lfx_gbuffer_b(UV).r)"),
            11 => (2, "vec4(lfx_gbuffer_b(UV).b)"),
            23 => (2 | 512, "vec4(lfx_gbuffer_shading_model(UV), 0.0, 0.0, 0.0)"),
            22 => (2 | 512, "vec4(lfx_gbuffer_shading_model_color(UV), 1.0)"),
            31 => (2, "vec4(lfx_gbuffer_first_person(UV))"),
            32 => (66, "vec4(lfx_gbuffer_toon_shading_id(UV))"),
            29 => (35, "vec4(lfx_gbuffer_tangent(UV), 0.0)"),
            30 => (34, "vec4(lfx_gbuffer_anisotropy(UV))"),
            27 => (2, "vec4(vec3(lfx_gbuffer_b(UV).g), 0.0)"),
            5 or 26 => (4, "vec4(lfx_gbuffer_c(UV).rgb, 0.0)"),
            12 => (4, "vec4(lfx_gbuffer_c(UV).a)"),
            2 => (6 | 128, "vec4(lfx_gbuffer_diffuse(UV), 0.0)"),
            3 => (6 | 256, "vec4(lfx_gbuffer_specular_color(UV), 0.0)"),
            _ => (0, "")
        };
        int slot = texture switch {
            13 => 1,
            25 => 2,
            1 => 0,
            9 => 3,
            _ when deferredMask != 0 => 4,
            _ => throw new ConversionException($"Surface SceneTexture {texture} is not implemented")
        };
        if (slot == 3) return new Expr("vec4(1.0)", 4);
        string pixelUv = "(lfx_material_fragment_xy() * vec2(Geometry.w, CameraUp.w))";
        string? uvExpr = explicitUvGlsl is not null
            ? $"({explicitUvGlsl})"
            : Connected(properties["Coordinates"]) ? Cast(RequiredInput(context, properties, "Coordinates"), 2).Code : null;
        string? clamped = uvExpr is null ? null
            : $"clamp({uvExpr}, vec2(Geometry.w, CameraUp.w) * 0.5, 1.0 - vec2(Geometry.w, CameraUp.w) * 0.5)";
        if (compilingVertex && clamped == null) {
            vertexSceneViewportUsed = true;
            clamped = "lfx_vertex_scene_viewport_uv()";
        }
        if (slot == 0) {
            sceneDepthUsed = depthProjectionUsed = true;
            vertexSceneDepthUsed |= compilingVertex;
            return new Expr(clamped is null ? "vec4(lfx_scene_depth())" : $"vec4(lfx_scene_depth_at({clamped}))", 4);
        }
        if (slot == 4) {
            deferredSceneTextures |= deferredMask;
            if (compilingVertex) vertexDeferredSceneTextures |= deferredMask;
            string deferredUv = clamped is null ? pixelUv : $"lfx_deferred_scene_host_uv({clamped})";
            return new Expr(deferredSample.Replace("UV", deferredUv), 4);
        }
        customSceneTextures |= slot;
        if (compilingVertex) vertexCustomSceneTextures |= slot;
        depthProjectionUsed |= slot == 1;
        string uv = clamped is null ? pixelUv : $"lfx_custom_scene_uv({clamped})";
        return new Expr(slot == 1 ? $"vec4(lfx_material_custom_depth({uv}))" : $"vec4(lfx_material_custom_stencil({uv}))", 4);
    }

    private void ValidateParticleSceneTextureLookup(int texture, bool decal)
    {
        if (texture == 21) throw new ConversionException("UE SceneTexture no longer exposes Decal Mask");
        if (texture == 28) throw new ConversionException("Velocity SceneTexture requires a post-process material");
        bool water = compiledShadingModel == "MSM_SingleLayerWater";
        if (water && texture is not (13 or 25))
            throw new ConversionException("Single Layer Water SceneTexture can sample only CustomDepth or CustomStencil");
        if (decal) {
            if (texture is not (1 or 8 or 13 or 25))
                throw new ConversionException($"Deferred decal SceneTexture {texture} is unavailable in UE");
            return;
        }
        if (!water && compiledBlendMode is "BLEND_Opaque" or "BLEND_Masked")
            throw new ConversionException($"Surface SceneTexture {texture} cannot be sampled from {compiledBlendMode}");
        if (texture is 0 or >= 14 and <= 20 or >= 33 and <= 39)
            throw new ConversionException("SceneTexture SceneColor and post-process inputs are unavailable to a surface material");
    }

    private void AppendVertexSceneTextures(StringBuilder shader)
    {
        if (vertexSceneViewportUsed || vertexPixelDepthUsed) {
            shader.AppendLine("vec4 lfx_vertex_scene_clip() {");
            shader.AppendLine("#if defined(LFX_NANITE_VERTEX_MATERIAL)");
            shader.AppendLine("    return lfxNaniteSceneViewProjection * vec4(lfxVertexMaterialPosition, 1.0);");
            shader.AppendLine("#else");
            shader.AppendLine("    return ProjMat * ModelViewMat * vec4(lfxVertexMaterialPosition, 1.0);");
            shader.AppendLine("#endif");
            shader.AppendLine("}");
        }
        if (vertexSceneViewportUsed)
            shader.AppendLine("vec2 lfx_vertex_scene_viewport_uv() { vec4 clip = lfx_vertex_scene_clip(); return clip.xy / clip.w * vec2(0.5, -0.5) + 0.5; }");
        if (vertexPixelDepthUsed) {
            shader.AppendLine("float lfx_vertex_material_pixel_depth() {");
            shader.AppendLine("    vec4 clip = lfx_vertex_scene_clip();");
            shader.AppendLine("    return ProjMat[3][3] >= 1.0 ? -100.0 * (clip.z - ProjMat[3][2]) / ProjMat[2][2] : 100.0 * clip.w;");
            shader.AppendLine("}");
        }
        if (vertexSceneDepthUsed || (vertexCustomSceneTextures != 0 && (customSceneTextures & 1) != 0))
            AppendMaterialLinearDepth(shader);
        if (vertexSceneDepthUsed) {
            shader.AppendLine("uniform sampler2D SceneDepthSampler;");
            shader.AppendLine("float lfx_scene_depth_at(vec2 uv) { vec2 hostUV = vec2(uv.x, CameraRight.w > 0.5 ? 1.0 - uv.y : uv.y); return lfx_linear_depth(textureLod(SceneDepthSampler, hostUV, 0.0).r); }");
        }
        if (vertexCustomSceneTextures != 0) shader.AppendLine("#moj_import <lumenfx:depth/custom_scene_textures.glsl>");
        if (vertexDeferredSceneTextures != 0) shader.AppendLine("#moj_import <lumenfx:scene/material_deferred_textures.glsl>");
    }

    private static void AppendMaterialLinearDepth(StringBuilder shader) =>
        shader.AppendLine("float lfx_linear_depth(float d) { return d * DepthTransform.x + DepthTransform.y + 1.0 / (d * DepthTransform.z - DepthTransform.w); }");
}
