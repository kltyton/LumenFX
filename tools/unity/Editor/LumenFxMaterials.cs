#if UNITY_EDITOR
using System;
using System.Collections.Generic;
using System.Globalization;
using System.Security.Cryptography;
using System.Text.RegularExpressions;
using UnityEditor;
using UnityEngine;
using UnityEngine.Experimental.Rendering;
using UnityEngine.Rendering;

namespace LumenFx.UnityExport
{
    internal static class LumenFxMaterialBuilder
    {
        internal static bool SupportsForwardLitBlend(string blend) =>
            blend == "alpha" || blend == "add" || blend == "additive" || blend == "premultiplied" || blend == "modulate";

        private static readonly HashSet<string> SupportedShaders = new HashSet<string>(StringComparer.Ordinal)
        {
            "Particles/Standard Unlit",
            "Particles/Standard Surface",
            "Particles/Additive",
            "Particles/Additive (Soft)",
            "Particles/Alpha Blended",
            "Particles/Alpha Blended Premultiply",
            "Particles/Multiply",
            "Legacy Shaders/Particles/Additive",
            "Legacy Shaders/Particles/Additive (Soft)",
            "Legacy Shaders/Particles/Alpha Blended",
            "Legacy Shaders/Particles/Alpha Blended Premultiply",
            "Legacy Shaders/Particles/Multiply",
            "Mobile/Particles/Additive",
            "Mobile/Particles/Alpha Blended",
            "Mobile/Particles/Multiply",
            "Universal Render Pipeline/Particles/Unlit",
            "Universal Render Pipeline/Particles/Lit",
            "Universal Render Pipeline/Unlit",
            "Universal Render Pipeline/Lit",
            "HDRP/Particles/Lit",
            "HDRP/Unlit",
            "HDRP/Lit",
            "Standard",
            "Sprites/Default",
            "Unlit/Texture",
            "Unlit/Transparent",
            "Unlit/Transparent Cutout",
            "Unlit/Color"
        };

        public static MaterialPlan Build(Material material, string id, string context,
            string outputNamespace, string effectName, HashSet<string> usedTexturePaths, List<ExportIssue> issues,
            bool supportsTextureTransform = false, Texture2D spriteSheet = null, bool forwardToUp = false, bool meshGpuInstancing = false)
        {
            if (material == null)
            {
                issues.Add(new ExportIssue(context, "No material is assigned to the ParticleSystemRenderer"));
                return null;
            }

            Shader shader = material.shader;
            if (shader == null)
            {
                issues.Add(new ExportIssue(context, "The material has no shader"));
                return null;
            }

            string shaderName = shader.name ?? "";
            string lower = shaderName.ToLowerInvariant();
            string fragment = material.GetTag("LumenFxFragment", false, "");
            bool mappedShader = !string.IsNullOrEmpty(fragment);
            if (mappedShader && !Regex.IsMatch(fragment, @"\A[a-z0-9_.-]+:[a-z0-9_./-]+\z"))
            {
                issues.Add(new ExportIssue(context, "Invalid LumenFxFragment identifier '" + fragment + "'"));
                return null;
            }
            if (!mappedShader && (LumenFxShaderGraphCompiler.IsShaderGraph(material)
                    || shaderName.StartsWith("Shader Graphs/", StringComparison.Ordinal)))
            {
                string shaderFileId = Sanitize(string.IsNullOrEmpty(id) ? material.name : id);
                if (string.IsNullOrEmpty(shaderFileId)) shaderFileId = "shader_graph";
                return LumenFxShaderGraphCompiler.TryBuild(
                    material, id, context, outputNamespace, effectName, usedTexturePaths, issues, shaderFileId, forwardToUp, meshGpuInstancing);
            }
            if (!mappedShader && !IsSupportedShader(shaderName))
            {
                issues.Add(new ExportIssue(context,
                    "Unsupported shader '" + shaderName + "'. Supported shaders: "
                    + string.Join(", ", new List<string>(SupportedShaders).ToArray())
                    + ", Shader Graph (.shadergraph), or materials tagged with LumenFxFragment"));
                return null;
            }

            MaterialPlan plan = new MaterialPlan { Id = id, RenderQueue = material.renderQueue };
            plan.Blend = ReadBlend(material, lower, context, issues);
            bool litSurface = IsNonZero(material, "_LightingEnabled")
                              || lower.Contains("standard surface")
                              || lower.EndsWith("/lit", StringComparison.Ordinal)
                              || lower == "standard";
            bool builtInStandardUnlit = !mappedShader && lower == "particles/standard unlit";
            bool gammaOpaque = !mappedShader && plan.Blend == "opaque" && !litSurface
                               && QualitySettings.activeColorSpace == ColorSpace.Gamma;
            plan.GammaBlend = builtInStandardUnlit && !litSurface && plan.Blend != "opaque"
                              && QualitySettings.activeColorSpace == ColorSpace.Gamma;

            bool urpParticle = lower.StartsWith("universal render pipeline/particles/", StringComparison.Ordinal);
            Color tint = Color.white;
            if (urpParticle && !mappedShader)
            {
                // URP keeps _Color as a legacy alias. Particles sample _BaseColor once.
                if (material.HasProperty("_BaseColor"))
                {
                    tint = material.GetColor("_BaseColor");
                    if (QualitySettings.activeColorSpace == ColorSpace.Linear) tint = tint.linear;
                }
            }
            else
            {
                if (material.HasProperty("_Color")) tint *= material.GetColor("_Color");
                if (material.HasProperty("_TintColor")) tint *= material.GetColor("_TintColor");
                if (material.HasProperty("_BaseColor")) tint *= material.GetColor("_BaseColor");
            }
            plan.Tint = tint;

            string[] textureProperties = material.GetTexturePropertyNames();
            List<string> assigned = new List<string>();
            foreach (string property in textureProperties)
                if (material.GetTexture(property) != null) assigned.Add(property);

            string mainProperty = urpParticle && !mappedShader
                ? (assigned.Contains("_BaseMap") ? "_BaseMap" : null)
                : ChooseMainTextureProperty(assigned);
            if (!string.IsNullOrEmpty(mainProperty) || spriteSheet != null)
            {
                Vector2 scale = mainProperty == null ? Vector2.one : material.GetTextureScale(mainProperty);
                Vector2 offset = mainProperty == null ? Vector2.zero : material.GetTextureOffset(mainProperty);
                if (!IsFinite(scale.x) || !IsFinite(scale.y) || !IsFinite(offset.x) || !IsFinite(offset.y))
                {
                    issues.Add(new ExportIssue(context,
                        "Texture tiling/offset on '" + (mainProperty ?? "sprite sheet") + "' must be finite"));
                }
                plan.TextureScale = scale;
                plan.TextureOffset = offset;

                Texture texture = spriteSheet != null ? spriteSheet : material.GetTexture(mainProperty);
                Texture2D texture2D = texture as Texture2D;
                if (texture2D == null)
                {
                    issues.Add(new ExportIssue(context, "Texture '" + (mainProperty ?? "sprite sheet") + "' is not a Texture2D"));
                }
                else
                {
                    plan.SourceTexture = texture2D;
                    if (RegisterExportedTexture(plan, material, "Sampler0", texture2D, outputNamespace, effectName,
                            usedTexturePaths, context, issues, isPrimary: true, sampleInGamma: gammaOpaque || plan.GammaBlend))
                        plan.FragmentShader = "lumenfx:core/effect";
                }
            }

            if (string.IsNullOrEmpty(plan.FragmentShader))
            {
                plan.FragmentShader = "lumenfx:core/color";
            }

            plan.BlendOp = ReadBlendOp(material, context, issues);
            plan.DepthWrite = material.HasProperty("_ZWrite") ? material.GetFloat("_ZWrite") > 0.5f : plan.Blend == "opaque";
            plan.DepthCompare = ReadDepthCompare(material, context, issues);
            plan.DepthTest = plan.DepthCompare != "disabled" && plan.DepthCompare != "always";
            bool cullFront;
            plan.Cull = ReadCull(material, context, issues, out cullFront);

            if (mappedShader)
            {
                plan.FragmentShader = fragment;
                return plan;
            }

            int colorMode = ReadColorMode(material, context, issues);
            bool alphaClip = material.IsKeywordEnabled("_ALPHATEST_ON")
                             || IsNonZero(material, "_AlphaClip")
                             || lower.Contains("cutout");
            float alphaCutoff = alphaClip
                ? Mathf.Clamp01(material.HasProperty("_Cutoff") ? material.GetFloat("_Cutoff") : 0.5f)
                : -1f;
            bool emissionEnabled = material.IsKeywordEnabled("_EMISSION") || (!urpParticle && (
                                      IsNonZero(material, "_EmissionEnabled")
                                      || (material.HasProperty("_EmissionColor")
                                          && !IsBlack(material.GetColor("_EmissionColor")))));
            Color emissionColor = Color.black;
            bool hasEmissionMap = false;
            if (emissionEnabled)
            {
                emissionColor = material.HasProperty("_EmissionColor")
                    ? material.GetColor("_EmissionColor")
                    : Color.white;
                if (!IsFinite(emissionColor.r) || !IsFinite(emissionColor.g) || !IsFinite(emissionColor.b))
                {
                    issues.Add(new ExportIssue(context, "Material emission color must be finite"));
                    emissionColor = Color.black;
                }
                if (material.HasProperty("_EmissionMap"))
                {
                    Texture emissionTexture = material.GetTexture("_EmissionMap");
                    if (emissionTexture != null)
                    {
                        Texture2D emission2D = emissionTexture as Texture2D;
                        if (emission2D == null)
                        {
                            issues.Add(new ExportIssue(context, "Emission texture '_EmissionMap' is not a Texture2D"));
                        }
                        else
                        {
                            if (RegisterExportedTexture(plan, material, "Sampler1", emission2D, outputNamespace, effectName,
                                    usedTexturePaths, context + " (_EmissionMap)", issues, nameSuffix: "_emission",
                                    sampleInGamma: gammaOpaque || plan.GammaBlend))
                                hasEmissionMap = true;
                        }
                    }
                }
            }

            bool softParticles = material.IsKeywordEnabled("SOFTPARTICLES_ON")
                                 || material.IsKeywordEnabled("_SOFTPARTICLES_ON")
                                 || IsNonZero(material, "_SoftParticlesEnabled");
            float softNear = 0f, softInvRange = 0f;
            if (softParticles)
            {
                ReadFadeRange(material, "_SoftParticlesNearFadeDistance", "_SoftParticlesFarFadeDistance",
                    "_SoftParticleFadeParams", "_InvFade", context, issues, out softNear, out softInvRange);
            }

            bool cameraFading = material.IsKeywordEnabled("_FADING_ON")
                                || IsNonZero(material, "_CameraFadingEnabled");
            float camNear = 0f, camInvRange = 0f;
            if (cameraFading)
            {
                ReadFadeRange(material, "_CameraNearFadeDistance", "_CameraFarFadeDistance",
                    "_CameraFadeParams", null, context, issues, out camNear, out camInvRange);
            }

            bool hasNormalMap = false;
            bool usesPackedNormal = false;
            bool urpUnlitParticle = lower == "universal render pipeline/particles/unlit";
            float bumpScale = material.HasProperty("_BumpScale") ? material.GetFloat("_BumpScale") : 1f;
            if (!IsFinite(bumpScale)) bumpScale = 1f;
            string bumpProp = assigned.Contains("_BumpMap") ? "_BumpMap"
                : (assigned.Contains("_NormalMap") ? "_NormalMap" : null);
            bool normalRequested = material.IsKeywordEnabled("_NORMALMAP") || bumpProp != null;
            if (normalRequested && bumpProp != null)
            {
                Texture2D bump2D = material.GetTexture(bumpProp) as Texture2D;
                if (bump2D != null && RegisterExportedTexture(plan, material, "Sampler2", bump2D, outputNamespace, effectName,
                        usedTexturePaths, context + " (" + bumpProp + ")", issues, nameSuffix: "_normal"))
                {
                    hasNormalMap = true;
                    usesPackedNormal = urpParticle && (bump2D.format == TextureFormat.DXT5 || bump2D.format == TextureFormat.BC5);
                }
            }

            bool hasDistortion = IsNonZero(material, "_DistortionEnabled")
                                 || material.IsKeywordEnabled("_DISTORTION_ON")
                                 || material.IsKeywordEnabled("DISTORTION")
                                 || material.IsKeywordEnabled("EFFECT_BUMP");
            float distortionStrength = material.HasProperty("_DistortionStrength")
                ? material.GetFloat("_DistortionStrength")
                : (material.HasProperty("_BumpAmt") ? material.GetFloat("_BumpAmt") * 0.01f : 0.1f);
            if (urpParticle)
            {
                hasDistortion = material.IsKeywordEnabled("_DISTORTION_ON");
                if (hasDistortion)
                {
                    distortionStrength = material.HasProperty("_DistortionStrengthScaled")
                        ? material.GetFloat("_DistortionStrengthScaled") : 0.1f;
                    float distortionBlend = material.HasProperty("_DistortionBlend")
                        ? material.GetFloat("_DistortionBlend") : .5f;
                    if (!IsFinite(distortionStrength) || !IsFinite(distortionBlend))
                        issues.Add(new ExportIssue(context, "Particle distortion strength and blend must be finite"));
                    plan.Parameters[2] = distortionBlend;
                }
            }
            if (!IsFinite(distortionStrength)) distortionStrength = 0.1f;

            if (litSurface && plan.Blend == "opaque")
                plan.DefaultLitSurface = true;
            bool urpLitParticle = lower == "universal render pipeline/particles/lit";
            plan.ForwardLitSurface = urpLitParticle && SupportsForwardLitBlend(plan.Blend);
            bool premultiplyLitDiffuse = plan.ForwardLitSurface && material.IsKeywordEnabled("_ALPHAPREMULTIPLY_ON");
            if (urpLitParticle && (plan.Blend == "opaque" || plan.ForwardLitSurface))
            {
                plan.VertexMaterial = "lumenfx:core/unity_particle_lit";
                plan.VertexBasis = true;
                int metallicProperty = material.shader.FindPropertyIndex("_Metallic");
                if (metallicProperty >= 0)
                {
                    float metallic = material.GetFloat("_Metallic");
                    var propertyFlags = material.shader.GetPropertyFlags(metallicProperty);
                    if (QualitySettings.activeColorSpace == ColorSpace.Linear && (propertyFlags & ShaderPropertyFlags.Gamma) != 0)
                        metallic = Mathf.GammaToLinearSpace(metallic);
                    plan.Parameters[0] = metallic;
                }
                plan.Parameters[1] = material.HasProperty("_Smoothness") ? material.GetFloat("_Smoothness") : .5f;
                if (!IsFinite(plan.Parameters[0]) || !IsFinite(plan.Parameters[1]))
                    issues.Add(new ExportIssue(context, "Particle metallic and smoothness must be finite"));
                if (material.IsKeywordEnabled("_METALLICSPECGLOSSMAP") && material.HasProperty("_MetallicGlossMap"))
                {
                    Texture metallicGloss = material.GetTexture("_MetallicGlossMap");
                    if (metallicGloss is Texture2D metallic2D)
                        RegisterExportedTexture(plan, material, "Sampler3", metallic2D, outputNamespace, effectName,
                            usedTexturePaths, context + " (_MetallicGlossMap)", issues, nameSuffix: "_metallic");
                    else if (metallicGloss == null)
                        plan.Parameters[0] = 1f;
                    else
                        issues.Add(new ExportIssue(context, "Metallic map '_MetallicGlossMap' is not a Texture2D"));
                }
            }

            bool flipbookBlend = material.IsKeywordEnabled("FLIPBOOKBLENDING_ON")
                                 || material.IsKeywordEnabled("_FLIPBOOKBLENDING_ON")
                                 || IsNonZero(material, "_FlipbookBlending")
                                 || IsNonZero(material, "_FlipbookMode");
            bool hasUvTransform = !supportsTextureTransform && plan.SourceTexture != null
                                  && (!Approximately(plan.TextureScale.x, 1) || !Approximately(plan.TextureScale.y, 1)
                                      || !Approximately(plan.TextureOffset.x, 0) || !Approximately(plan.TextureOffset.y, 0));
            // Standard Unlit passes RGB through to One/OneMinusSrcAlpha. The lit and legacy variants own their alpha handling.
            bool premultiplyOutput = (plan.Blend == "premultiplied" && !builtInStandardUnlit)
                                     || (plan.Blend == "soft_additive" && lower.Contains("additive (soft)"));
            bool alphaModulate = material.IsKeywordEnabled("_ALPHAMODULATE_ON")
                                 || (plan.Blend == "modulate"
                                     && (lower.Contains("multiply") || lower.Contains("modulate")));
            bool fadeRgbWithAlpha = (plan.Blend == "add" || (plan.Blend == "soft_additive" && !premultiplyOutput))
                                    && (softParticles || cameraFading);
            if (urpUnlitParticle)
            {
                premultiplyOutput = material.IsKeywordEnabled("_ALPHAPREMULTIPLY_ON");
                alphaModulate = material.IsKeywordEnabled("_ALPHAMODULATE_ON");
                fadeRgbWithAlpha = premultiplyOutput;
            }

            bool needsExtended = plan.Blend == "soft_additive"
                                 || plan.Blend == "modulate"
                                 || hasUvTransform
                                 || colorMode != 0
                                 || alphaClip
                                 || emissionEnabled
                                 || softParticles
                                 || cameraFading
                                 || flipbookBlend
                                 || hasNormalMap
                                 || hasDistortion
                                 || litSurface
                                 || gammaOpaque
                                 || plan.GammaBlend
                                 || cullFront;

            if (needsExtended)
            {
                plan.FragmentShader = "lumenfx:core/unity_particle";
                plan.DepthProjection = softParticles || cameraFading || hasDistortion;
                plan.SceneDepth = softParticles;
                plan.SceneColor = hasDistortion;
                int flags = (plan.SourceTexture != null ? 1 : 0)
                            | (flipbookBlend ? 2 : 0)
                            | (hasEmissionMap ? 4 : 0)
                            | (cullFront ? 8 : 0)
                            | (fadeRgbWithAlpha ? 16 : 0)
                            | (premultiplyOutput ? 32 : 0)
                            | (alphaModulate ? 64 : 0)
                            | (hasNormalMap ? 128 : 0)
                            | (hasDistortion ? 256 : 0)
                            | (litSurface ? 512 : 0)
                            | (usesPackedNormal ? 1024 : 0)
                            | (urpParticle && hasDistortion ? 2048 : 0)
                            | (urpUnlitParticle ? 4096 : 0)
                            | (premultiplyLitDiffuse ? 8192 : 0)
                            | (gammaOpaque ? 16384 : 0);
                float[] uvSt = !supportsTextureTransform && plan.SourceTexture != null
                    ? new[] { plan.TextureScale.x, plan.TextureScale.y, plan.TextureOffset.x, 1f - plan.TextureScale.y - plan.TextureOffset.y }
                    : new[] { 1f, 1f, 0f, 0f };
                plan.RuntimeParameters.Add(VectorParameter("uvST", uvSt));
                plan.RuntimeParameters.Add(VectorParameter("emissionAndCutoff",
                    new[] { emissionColor.r, emissionColor.g, emissionColor.b, alphaCutoff }));
                plan.RuntimeParameters.Add(VectorParameter("fadeParams",
                    new[] { softNear, softInvRange, camNear, camInvRange }));
                plan.RuntimeParameters.Add(VectorParameter("config",
                    new float[] { colorMode, flags, bumpScale, distortionStrength }));
            }
            else if (plan.Blend == "premultiplied" && !builtInStandardUnlit)
            {
                plan.FragmentShader = plan.SourceTexture == null ? "lumenfx:core/color_premultiplied" : "lumenfx:core/effect_premultiplied";
            }

            return plan;
        }

        internal static bool RegisterExportedTexture(MaterialPlan plan, Material material, string samplerSlot, Texture2D texture,
            string outputNamespace, string effectName, HashSet<string> usedTexturePaths, string context, List<ExportIssue> issues,
            bool isPrimary = false, string nameSuffix = "", bool sampleInGamma = false)
        {
            return RegisterExportedTexture(plan, material.name, samplerSlot, texture, outputNamespace, effectName,
                usedTexturePaths, context, issues, isPrimary, nameSuffix, sampleInGamma);
        }

        internal static bool RegisterExportedTexture(MaterialPlan plan, string materialName, string samplerSlot, Texture2D texture,
            string outputNamespace, string effectName, HashSet<string> usedTexturePaths, string context, List<ExportIssue> issues,
            bool isPrimary = false, string nameSuffix = "", bool sampleInGamma = false)
        {
            byte[] png = EncodeTexture(texture, context, issues);
            if (png == null) return false;
            string fileName = UniqueTextureFile(materialName + nameSuffix, texture.name, usedTexturePaths);
            string baseRelPath = "assets/" + outputNamespace + "/textures/effects/" + effectName + "/" + fileName;
            string pngRelPath = baseRelPath + ".png";
            string metaRelPath = baseRelPath + ".lftex.json";
            plan.Textures[samplerSlot] = outputNamespace + ":textures/effects/" + effectName + "/" + fileName + ".png";
            if (isPrimary)
            {
                plan.TextureRelativePath = pngRelPath;
                plan.PngData = png;
            }
            else
            {
                plan.ExtraTexturePayloads[pngRelPath] = png;
            }

            Dictionary<string, object> sampler = BuildSamplerSettings(texture);
            plan.TextureSamplers[samplerSlot] = sampler;
            plan.ExtraTextPayloads[metaRelPath] = BuildTextureMetadataJson(texture, png, sampler,
                !sampleInGamma && IsTextureSrgb(texture));
            return true;
        }

        internal static Dictionary<string, object> BuildSamplerSettings(Texture2D texture)
        {
            string addressX = MapWrapMode(texture.wrapModeU);
            string addressY = MapWrapMode(texture.wrapModeV);
            int maxAnisotropy = Mathf.Max(1, texture.anisoLevel);
            string minMag;
            string mip;
            switch (texture.filterMode)
            {
                case FilterMode.Point:
                    minMag = "point";
                    mip = "point";
                    maxAnisotropy = 1;
                    break;
                case FilterMode.Trilinear:
                    minMag = maxAnisotropy > 1 ? "aniso" : "linear";
                    mip = "linear";
                    break;
                default:
                    minMag = maxAnisotropy > 1 ? "aniso" : "linear";
                    mip = "point";
                    break;
            }
            return new Dictionary<string, object>
            {
                { "address_x", addressX },
                { "address_y", addressY },
                { "min_mag_filter", minMag },
                { "mip_filter", mip },
                { "max_anisotropy", maxAnisotropy }
            };
        }

        private static string MapWrapMode(TextureWrapMode mode)
        {
            switch (mode)
            {
                case TextureWrapMode.Clamp: return "clamp";
                case TextureWrapMode.Mirror: return "mirror";
                case TextureWrapMode.MirrorOnce: return "mirror_once";
                default: return "wrap";
            }
        }

        private static string BuildTextureMetadataJson(Texture2D texture, byte[] png, Dictionary<string, object> sampler, bool srgb)
        {
            string sha256;
            using (var alg = SHA256.Create())
            {
                byte[] hash = alg.ComputeHash(png);
                var sb = new System.Text.StringBuilder(64);
                for (int i = 0; i < hash.Length; i++) sb.Append(hash[i].ToString("x2", CultureInfo.InvariantCulture));
                sha256 = sb.ToString();
            }
            var metadata = new Dictionary<string, object>
            {
                { "format_version", 1 },
                { "source", "unity-texture2d-png" },
                { "width", texture.width },
                { "height", texture.height },
                { "srgb", srgb },
                { "mip_gen", texture.mipmapCount > 1 ? "simple_average" : "no_mipmaps" },
                { "sharpen", 0f },
                { "kernel_size", 2 },
                { "downsample_with_average", false },
                { "sharpen_without_color_shift", false },
                { "use_new_mip_filter", false },
                { "address_x", sampler["address_x"] },
                { "address_y", sampler["address_y"] },
                { "min_mag_filter", sampler["min_mag_filter"] },
                { "mip_filter", sampler["mip_filter"] },
                { "max_anisotropy", sampler["max_anisotropy"] },
                { "lod_group", "TEXTUREGROUP_Effects" },
                { "compression", "TC_Default" },
                { "cooked_format", "RGBA8" },
                { "png_sha256", sha256 },
                { "mip_data", "" },
                { "legacy_gamma", false }
            };
            return LumenFxJson.Write(metadata);
        }

        private static bool IsTextureSrgb(Texture2D texture)
        {
            string assetPath = AssetDatabase.GetAssetPath(texture);
            if (!string.IsNullOrEmpty(assetPath))
            {
                var importer = AssetImporter.GetAtPath(assetPath) as TextureImporter;
                if (importer != null) return importer.sRGBTexture;
            }
            return GraphicsFormatUtility.IsSRGBFormat(texture.graphicsFormat);
        }

        private static bool IsSupportedShader(string shaderName)
        {
            return SupportedShaders.Contains(shaderName);
        }

        private static Dictionary<string, object> VectorParameter(string name, float[] values)
        {
            return new Dictionary<string, object>
            {
                { "name", name },
                { "kind", "vector" },
                { "type", "constant" },
                { "default", new object[] { values[0], values[1], values[2], values[3] } }
            };
        }

        private static int ReadColorMode(Material material, string context, List<ExportIssue> issues)
        {
            int mode = 0;
            if (material.HasProperty("_ColorMode"))
            {
                mode = Mathf.RoundToInt(material.GetFloat("_ColorMode"));
                if (mode < 0 || mode > 5)
                {
                    issues.Add(new ExportIssue(context, "Unsupported particle _ColorMode value: " + mode));
                    return 0;
                }
            }
            if (mode == 0)
            {
                if (material.IsKeywordEnabled("_COLOROVERLAY_ON")) return 3;
                if (material.IsKeywordEnabled("_COLORCOLOR_ON")) return 4;
                if (material.IsKeywordEnabled("_COLORADDSUBDIFF_ON"))
                {
                    if (material.HasProperty("_ColorAddSubDiff"))
                    {
                        Vector4 vec = material.GetVector("_ColorAddSubDiff");
                        if (vec.z > 0.5f) return 5;
                        if (vec.y < -0.5f) return 2;
                    }
                    return 1;
                }
            }
            return mode;
        }

        private static void ReadFadeRange(Material material, string nearProperty, string farProperty,
            string packedProperty, string invFadeProperty, string context, List<ExportIssue> issues,
            out float near, out float invRange)
        {
            near = 0f;
            invRange = 0f;
            if (!string.IsNullOrEmpty(nearProperty) && !string.IsNullOrEmpty(farProperty)
                && material.HasProperty(nearProperty) && material.HasProperty(farProperty))
            {
                near = material.GetFloat(nearProperty);
                float far = material.GetFloat(farProperty);
                if (!IsFinite(near) || !IsFinite(far))
                {
                    issues.Add(new ExportIssue(context, "Particle fade distances must be finite"));
                    near = 0f;
                    return;
                }
                invRange = far > near + 1e-5f ? 1f / (far - near) : 0f;
                return;
            }
            if (!string.IsNullOrEmpty(packedProperty) && material.HasProperty(packedProperty))
            {
                Vector4 packed = material.GetVector(packedProperty);
                near = packed.x;
                float far = packed.y;
                invRange = packed.z > 1e-5f ? packed.z : (far > near + 1e-5f ? 1f / (far - near) : 0f);
                if (!IsFinite(near) || !IsFinite(invRange))
                {
                    issues.Add(new ExportIssue(context, "Particle fade vector must be finite"));
                    near = 0f;
                    invRange = 0f;
                }
                return;
            }
            if (!string.IsNullOrEmpty(invFadeProperty) && material.HasProperty(invFadeProperty))
            {
                float inv = material.GetFloat(invFadeProperty);
                if (!IsFinite(inv))
                {
                    issues.Add(new ExportIssue(context, "Particle _InvFade must be finite"));
                    return;
                }
                near = 0f;
                invRange = Mathf.Max(0f, inv);
            }
        }

        private static string ChooseMainTextureProperty(List<string> assigned)
        {
            string[] preferred = { "_MainTex", "_BaseMap", "_BaseColorMap", "_MainTexture" };
            foreach (string name in preferred)
                if (assigned.Contains(name)) return name;
            return null;
        }

        private static bool IsNonZero(Material material, string property)
        {
            return material.HasProperty(property) && Mathf.Abs(material.GetFloat(property)) > 1e-5f;
        }

        private static bool IsBlack(Color color)
        {
            return Mathf.Abs(color.r) <= 1e-5f && Mathf.Abs(color.g) <= 1e-5f && Mathf.Abs(color.b) <= 1e-5f;
        }

        private static string ReadBlend(Material material, string shaderNameLower, string context,
            List<ExportIssue> issues)
        {
            bool hasSource = material.HasProperty("_SrcBlend");
            bool hasDestination = material.HasProperty("_DstBlend");
            if (hasSource && hasDestination)
            {
                int source = Mathf.RoundToInt(material.GetFloat("_SrcBlend"));
                int destination = Mathf.RoundToInt(material.GetFloat("_DstBlend"));
                if (source == 2 && destination == 0) return "modulate";
                // Built-in Standard Unlit Modulate has the same effective RGB transfer as the
                // legacy multiply shader: destination * lerp(1, albedo, alpha).
                if (source == 2 && destination == 10 && shaderNameLower == "particles/standard unlit"
                    && material.IsKeywordEnabled("_ALPHAMODULATE_ON")
                    && material.HasProperty("_BlendOp")
                    && Mathf.RoundToInt(material.GetFloat("_BlendOp")) == (int)UnityEngine.Rendering.BlendOp.Add)
                    return "modulate";
                if (destination == 0)
                {
                    if (source == 1) return "opaque";
                    issues.Add(new ExportIssue(context,
                        "Unsupported blend factors Src=" + source + ", Dst=" + destination));
                    return "opaque";
                }
                if (source == 1 && destination == 1) return "add";
                if (source == 5 && destination == 1) return "additive";
                if (source == 1 && destination == 6) return "soft_additive";
                if (source == 5 && destination == 10) return "alpha";
                if (source == 1 && destination == 10) return "premultiplied";
                issues.Add(new ExportIssue(context,
                    "Unsupported blend factors Src=" + source + ", Dst=" + destination));
                return "alpha";
            }

            if (material.HasProperty("_Surface") && material.GetFloat("_Surface") < 0.5f) return "opaque";
            if (shaderNameLower.Contains("premultiply")) return "premultiplied";
            if (shaderNameLower.Contains("additive (soft)")) return "soft_additive";
            if (shaderNameLower.Contains("additive")) return "additive";
            if (shaderNameLower.Contains("multiply") || shaderNameLower.Contains("modulate")) return "modulate";
            if (shaderNameLower.Contains("cutout")
                || shaderNameLower == "unlit/texture"
                || shaderNameLower == "unlit/color") return "opaque";
            if (shaderNameLower.Contains("alpha blended")
                || shaderNameLower.Contains("alphablended")
                || shaderNameLower.Contains("transparent")
                || shaderNameLower.Contains("unlit")
                || shaderNameLower == "sprites/default"
                || shaderNameLower.StartsWith("shader graphs/", StringComparison.Ordinal))
                return "alpha";

            issues.Add(new ExportIssue(context,
                "Cannot determine blend mode; assign a material with _SrcBlend/_DstBlend or a known unlit particle shader"));
            return "alpha";
        }

        private static string ReadBlendOp(Material material, string context, List<ExportIssue> issues)
        {
            if (!material.HasProperty("_BlendOp")) return "add";
            int value = Mathf.RoundToInt(material.GetFloat("_BlendOp"));
            switch ((UnityEngine.Rendering.BlendOp)value)
            {
                case UnityEngine.Rendering.BlendOp.Add: return "add";
                case UnityEngine.Rendering.BlendOp.Subtract: return "subtract";
                case UnityEngine.Rendering.BlendOp.ReverseSubtract: return "reverse_subtract";
                case UnityEngine.Rendering.BlendOp.Min: return "min";
                case UnityEngine.Rendering.BlendOp.Max: return "max";
                default:
                    issues.Add(new ExportIssue(context, "Unsupported blend operation: " + value));
                    return "add";
            }
        }

        private static string ReadDepthCompare(Material material, string context, List<ExportIssue> issues)
        {
            if (!material.HasProperty("_ZTest")) return "less_equal";
            return DepthCompare(Mathf.RoundToInt(material.GetFloat("_ZTest")), context, issues);
        }

        internal static string DepthCompare(int value, string context, List<ExportIssue> issues)
        {
            switch ((UnityEngine.Rendering.CompareFunction)value)
            {
                case UnityEngine.Rendering.CompareFunction.Disabled: return "disabled";
                case UnityEngine.Rendering.CompareFunction.Never: return "never";
                case UnityEngine.Rendering.CompareFunction.Less: return "less";
                case UnityEngine.Rendering.CompareFunction.Equal: return "equal";
                case UnityEngine.Rendering.CompareFunction.LessEqual: return "less_equal";
                case UnityEngine.Rendering.CompareFunction.Greater: return "greater";
                case UnityEngine.Rendering.CompareFunction.NotEqual: return "not_equal";
                case UnityEngine.Rendering.CompareFunction.GreaterEqual: return "greater_equal";
                case UnityEngine.Rendering.CompareFunction.Always: return "always";
                default:
                    issues.Add(new ExportIssue(context, "Unknown depth comparison: " + value));
                    return "less_equal";
            }
        }

        private static bool ReadCull(Material material, string context, List<ExportIssue> issues, out bool cullFront)
        {
            cullFront = false;
            if (!material.HasProperty("_Cull")) return false;
            int value = Mathf.RoundToInt(material.GetFloat("_Cull"));
            if (value == 1)
            {
                cullFront = true;
                return false;
            }
            if (value == 0) return false;
            if (value == 2) return true;
            issues.Add(new ExportIssue(context, "Unknown cull mode: " + value));
            return false;
        }

        private static bool IsFinite(float value)
        {
            return !float.IsNaN(value) && !float.IsInfinity(value);
        }

        private static byte[] EncodeTexture(Texture2D texture, string context, List<ExportIssue> issues)
        {
            int width = texture.width;
            int height = texture.height;
            if (width <= 0 || height <= 0)
            {
                issues.Add(new ExportIssue(context, "Texture '" + texture.name + "' has invalid dimensions"));
                return null;
            }

            try
            {
                if (texture.isReadable)
                {
                    Color32[] pixels = texture.GetPixels32();
                    Texture2D copy = new Texture2D(width, height, TextureFormat.RGBA32, false);
                    try
                    {
                        copy.SetPixels32(pixels);
                        copy.Apply();
                        return copy.EncodeToPNG();
                    }
                    finally
                    {
                        UnityEngine.Object.DestroyImmediate(copy);
                    }
                }

                bool srgb = IsTextureSrgb(texture);
                RenderTexture renderTexture = RenderTexture.GetTemporary(width, height, 0,
                    RenderTextureFormat.ARGB32, srgb ? RenderTextureReadWrite.sRGB : RenderTextureReadWrite.Linear);
                RenderTexture previous = RenderTexture.active;
                bool previousSrgbWrite = GL.sRGBWrite;
                try
                {
                    GL.sRGBWrite = srgb;
                    Graphics.Blit(texture, renderTexture);
                    RenderTexture.active = renderTexture;
                    Texture2D copy = new Texture2D(width, height, TextureFormat.RGBA32, false, !srgb);
                    try
                    {
                        copy.ReadPixels(new Rect(0, 0, width, height), 0, 0, false);
                        copy.Apply();
                        return copy.EncodeToPNG();
                    }
                    finally
                    {
                        UnityEngine.Object.DestroyImmediate(copy);
                    }
                }
                finally
                {
                    RenderTexture.active = previous;
                    GL.sRGBWrite = previousSrgbWrite;
                    RenderTexture.ReleaseTemporary(renderTexture);
                }
            }
            catch (Exception exception)
            {
                issues.Add(new ExportIssue(context, "Failed to encode texture '" + texture.name + "': " + exception.Message));
                return null;
            }
        }

        private static string UniqueTextureFile(string materialName, string textureName, HashSet<string> used)
        {
            string baseName = Sanitize(materialName) + "_" + Sanitize(textureName);
            if (string.IsNullOrEmpty(baseName) || baseName == "_") baseName = "texture";
            string candidate = baseName;
            int suffix = 2;
            while (!used.Add(candidate)) candidate = baseName + "_" + suffix++;
            return candidate;
        }

        private static string Sanitize(string value)
        {
            if (string.IsNullOrEmpty(value)) return "";
            System.Text.StringBuilder builder = new System.Text.StringBuilder(value.Length);
            foreach (char character in value.ToLowerInvariant())
            {
                if ((character >= 'a' && character <= 'z') || (character >= '0' && character <= '9')
                    || character == '_' || character == '-' || character == '.')
                    builder.Append(character);
                else
                    builder.Append('_');
            }
            return builder.ToString().Trim('_');
        }

        private static bool Approximately(float a, float b)
        {
            return Mathf.Abs(a - b) <= 1e-5f;
        }
    }
}
#endif
