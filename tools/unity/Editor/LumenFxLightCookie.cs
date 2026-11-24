#if UNITY_EDITOR
using System;
using System.Collections.Generic;
using System.Text;
using UnityEngine;
using UnityEngine.Experimental.Rendering;
using UnityEngine.Rendering;

namespace LumenFx.UnityExport
{
    internal static class LumenFxLightCookie
    {
        private static readonly CubemapFace[] Faces = {
            CubemapFace.PositiveX, CubemapFace.NegativeX, CubemapFace.PositiveY,
            CubemapFace.NegativeY, CubemapFace.PositiveZ, CubemapFace.NegativeZ
        };

        internal static MaterialPlan Build(Light light, AxisMapper mapper, ExportSettings settings, string id,
            HashSet<string> usedTexturePaths, string context, List<ExportIssue> issues)
        {
            bool spot = light.type == LightType.Spot;
            Texture texture = light.cookie;
            if ((spot && !(texture is Texture2D)) || (!spot && !(texture is Cubemap)))
            {
                issues.Add(new ExportIssue(context,
                    "Spot light cookies need a Texture2D and Point light cookies need a Cubemap."));
                return null;
            }
            string shader = "effects/" + settings.EffectName + "/" + id;
            var plan = new MaterialPlan {
                Id = id, FragmentShader = settings.Namespace + ":" + shader, Blend = "opaque",
                DepthTest = false, LightFunction = true
            };
            try
            {
                if (spot)
                {
                    if (!LumenFxMaterialBuilder.RegisterExportedTexture(plan, id, "SamplerCookie0", (Texture2D)texture,
                        settings.Namespace, settings.EffectName, usedTexturePaths, context, issues))
                        return null;
                }
                else if (!WriteFaces(plan, (Cubemap)texture, settings, usedTexturePaths, context, issues))
                    return null;
            }
            catch (Exception error)
            {
                issues.Add(new ExportIssue(context, "Unable to export cookie '" + texture.name + "': " + error.Message));
                return null;
            }

            var format = texture.graphicsFormat;
            string color = GraphicsFormatUtility.IsAlphaOnlyFormat(format)
                ? "value.aaa"
                : GraphicsFormatUtility.GetComponentCount(format) == 1 ? "value.rrr" : "value.rgb";
            var source = new StringBuilder();
            source.AppendLine("#version 330");
            source.AppendLine("layout(std140) uniform EffectFrame { vec4 EffectClock; vec4 MaterialParameters; vec4 EffectTimeData; };");
            source.AppendLine("layout(std140) uniform EffectView { vec4 CameraRight; vec4 CameraUp; vec4 Geometry; vec4 DepthTransform; };");
            source.AppendLine("#moj_import <lumenfx:light_function_material.glsl>");
            foreach (string sampler in plan.Textures.Keys) source.AppendLine("uniform sampler2D " + sampler + ";");
            if (!spot) source.AppendLine("#moj_import <lumenfx:lighting/unity_cookie_cube.glsl>");
            source.AppendLine("void main() {");
            source.AppendLine("    lfx_initialize_light_function();");
            if (spot)
            {
                source.AppendLine("    vec2 uv;");
                source.AppendLine("#ifdef LFX_LIGHT_FUNCTION_ATLAS");
                source.AppendLine("    uv = effectUV.yx;");
                source.AppendLine("#else");
                source.AppendLine("    uv = clamp(effectUV.yx, 0.0, 1.0);");
                source.AppendLine("#endif");
                if (mapper.ConvertsForwardToUp) source.AppendLine("    uv.x = 1.0 - uv.x;");
                source.AppendLine("    vec4 value = texture(SamplerCookie0, vec2(uv.x, 1.0 - uv.y));");
            }
            else
            {
                source.AppendLine("    vec3 direction = lfxLightFunctionVector.yxz;");
                if (mapper.ConvertsForwardToUp) source.AppendLine("    direction.x = -direction.x;");
                source.AppendLine("    vec4 value = lfx_unity_cookie_cube(direction);");
            }
            source.AppendLine("    lfx_write_light_function(" + color + ");");
            source.AppendLine("}");
            plan.ExtraTextPayloads["assets/" + settings.Namespace + "/shaders/" + shader + ".fsh"] = source.ToString();
            return plan;
        }

        private static bool WriteFaces(MaterialPlan plan, Cubemap cube, ExportSettings settings,
            HashSet<string> usedTexturePaths, string context, List<ExportIssue> issues)
        {
            bool srgb = GraphicsFormatUtility.IsSRGBFormat(cube.graphicsFormat);
            AsyncGPUReadbackRequest readback = default(AsyncGPUReadbackRequest);
            if (!cube.isReadable)
            {
                readback = AsyncGPUReadback.Request(cube, 0,
                    srgb ? GraphicsFormat.R8G8B8A8_SRGB : GraphicsFormat.R8G8B8A8_UNorm);
                readback.WaitForCompletion();
                if (readback.hasError || readback.layerCount != Faces.Length)
                    throw new InvalidOperationException("the imported cubemap's six faces could not be read from the GPU.");
            }
            for (int i = 0; i < Faces.Length; i++)
            {
                var side = new Texture2D(cube.width, cube.height, TextureFormat.RGBA32, false, !srgb) {
                    name = cube.name + "_" + Faces[i], wrapMode = TextureWrapMode.Clamp, filterMode = cube.filterMode
                };
                try
                {
                    if (cube.isReadable) side.SetPixels(cube.GetPixels(Faces[i], 0));
                    else side.SetPixelData(readback.GetData<Color32>(i), 0);
                    side.Apply();
                    if (!LumenFxMaterialBuilder.RegisterExportedTexture(plan, plan.Id, "SamplerCookie" + i, side,
                        settings.Namespace, settings.EffectName, usedTexturePaths, context, issues))
                        return false;
                }
                finally { UnityEngine.Object.DestroyImmediate(side); }
            }
            return true;
        }
    }
}
#endif
