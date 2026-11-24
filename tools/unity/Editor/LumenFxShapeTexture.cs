#if UNITY_EDITOR
using System.Collections.Generic;
using System.IO;
using UnityEngine;

namespace LumenFx.UnityExport
{
    internal static class LumenFxShapeTexture
    {
        internal static Dictionary<string, object> Build(ParticleSystem.ShapeModule shape, ExportSettings export,
            LumenFxPlan plan, string assetPath, string context, List<ExportIssue> issues)
        {
            var texture = shape.texture;
            if (texture == null) return null;
            if (!texture.isReadable)
            {
                issues.Add(new ExportIssue(context, "Emission texture must be readable to preserve CPU sample values"));
                return null;
            }
            try
            {
                var pixels = texture.GetPixels(0);
                using (var bytes = new MemoryStream())
                using (var writer = new BinaryWriter(bytes))
                {
                    writer.Write(1);
                    writer.Write(texture.width);
                    writer.Write(texture.height);
                    foreach (var pixel in pixels)
                    {
                        writer.Write(pixel.r);
                        writer.Write(pixel.g);
                        writer.Write(pixel.b);
                        writer.Write(pixel.a);
                    }
                    string relative = "lumenfx/shape_textures/" + assetPath + ".lfrgba";
                    plan.ShapeTextures.Add("assets/" + export.Namespace + "/" + relative, bytes.ToArray());
                    return new Dictionary<string, object>
                    {
                        { "asset", export.Namespace + ":" + relative },
                        { "channel", (int)shape.textureClipChannel },
                        { "threshold", shape.textureClipThreshold },
                        { "color", shape.textureColorAffectsParticles },
                        { "alpha", shape.textureAlphaAffectsParticles },
                        { "bilinear", shape.textureBilinearFiltering },
                        { "wrap_u", texture.wrapModeU.ToString() },
                        { "wrap_v", texture.wrapModeV.ToString() }
                    };
                }
            }
            catch (UnityException failure)
            {
                issues.Add(new ExportIssue(context, "Cannot read particle emission texture '" + texture.name + "': " + failure.Message));
                return null;
            }
        }
    }
}
#endif
