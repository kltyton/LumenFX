#if UNITY_EDITOR
using System.Collections.Generic;
using UnityEngine;

namespace LumenFx.UnityExport
{
    internal static class LumenFxSpriteFrames
    {
        internal static List<float[]> Read(ParticleSystem.TextureSheetAnimationModule sheet, string context,
            List<ExportIssue> issues, out Texture2D texture)
        {
            texture = null;
            if (sheet.spriteCount < 1 || sheet.spriteCount > 65536)
            {
                issues.Add(new ExportIssue(context, "Sprite Animation needs 1 to 65536 sprite frames"));
                return null;
            }
            var sprites = new List<Sprite>(sheet.spriteCount);
            bool needsAtlasRepack = false;
            Texture2D sharedSource = null;
            for (int index = 0; index < sheet.spriteCount; index++)
            {
                Sprite sprite = sheet.GetSprite(index);
                if (sprite == null || sprite.texture == null)
                {
                    issues.Add(new ExportIssue(context, "Sprite frame " + index + " is missing a Sprite or Texture"));
                    return null;
                }
                if (sprite.packed && (sprite.packingMode == SpritePackingMode.Tight
                        || sprite.packingRotation != SpritePackingRotation.None))
                    needsAtlasRepack = true;
                if (sharedSource == null) sharedSource = sprite.texture;
                else if (sharedSource != sprite.texture) needsAtlasRepack = true;
                sprites.Add(sprite);
            }

            if (!needsAtlasRepack)
            {
                texture = sharedSource;
                var frames = new List<float[]>(sprites.Count);
                for (int index = 0; index < sprites.Count; index++)
                {
                    Sprite sprite = sprites[index];
                    Rect region = sprite.textureRect;
                    if (region.width <= 0 || region.height <= 0 || region.xMin < 0 || region.yMin < 0
                        || region.xMax > texture.width || region.yMax > texture.height)
                    {
                        issues.Add(new ExportIssue(context, "Invalid texture rectangle for particle sprite '" + sprite.name + "'"));
                        return null;
                    }
                    frames.Add(new[]
                    {
                        region.xMin / texture.width, 1 - region.yMax / texture.height,
                        region.xMax / texture.width, 1 - region.yMin / texture.height
                    });
                }
                return frames;
            }

            return PackRepackedAtlas(sprites, sharedSource, context, issues, out texture);
        }

        private static List<float[]> PackRepackedAtlas(List<Sprite> sprites, Texture2D sampleSource, string context,
            List<ExportIssue> issues, out Texture2D atlasTexture)
        {
            atlasTexture = null;
            int count = sprites.Count;
            var cropped = new List<Texture2D>(count);
            try
            {
                int maxW = 1, maxH = 1;
                var cachedReadable = new Dictionary<Texture2D, Texture2D>();
                try
                {
                    for (int i = 0; i < count; i++)
                    {
                        Sprite s = sprites[i];
                        Texture2D src = s.texture;
                        Texture2D readable;
                        if (!cachedReadable.TryGetValue(src, out readable))
                        {
                            readable = EnsureReadable(src);
                            cachedReadable[src] = readable;
                        }
                        int w = Mathf.Max(1, Mathf.CeilToInt(s.rect.width));
                        int h = Mathf.Max(1, Mathf.CeilToInt(s.rect.height));
                        maxW = Mathf.Max(maxW, w);
                        maxH = Mathf.Max(maxH, h);
                        Texture2D cell = RenderSpriteCell(s, readable, w, h);
                        cropped.Add(cell);
                    }
                }
                finally
                {
                    foreach (var kv in cachedReadable)
                        if (kv.Value != kv.Key) UnityEngine.Object.DestroyImmediate(kv.Value);
                }

                int cols = Mathf.CeilToInt(Mathf.Sqrt(count));
                int rows = Mathf.CeilToInt((float)count / cols);
                int pad = 2;
                int cellW = maxW + pad * 2;
                int cellH = maxH + pad * 2;
                int atlasW = Mathf.Max(1, cols * cellW);
                int atlasH = Mathf.Max(1, rows * cellH);
                if (atlasW > 8192 || atlasH > 8192)
                {
                    issues.Add(new ExportIssue(context, "Repacked sprite frame atlas exceeds 8192x8192 limit"));
                    return null;
                }

                atlasTexture = new Texture2D(atlasW, atlasH, TextureFormat.RGBA32, false);
                atlasTexture.hideFlags = HideFlags.HideAndDontSave;
                atlasTexture.name = "lfx_sprite_atlas";
                atlasTexture.filterMode = sampleSource != null ? sampleSource.filterMode : FilterMode.Bilinear;
                atlasTexture.wrapModeU = sampleSource != null ? sampleSource.wrapModeU : TextureWrapMode.Clamp;
                atlasTexture.wrapModeV = sampleSource != null ? sampleSource.wrapModeV : TextureWrapMode.Clamp;
                atlasTexture.anisoLevel = sampleSource != null ? sampleSource.anisoLevel : 1;

                Color32[] clear = new Color32[atlasW * atlasH];
                atlasTexture.SetPixels32(clear);
                var frames = new List<float[]>(count);
                for (int i = 0; i < count; i++)
                {
                    int col = i % cols;
                    int row = rows - 1 - (i / cols);
                    int ox = col * cellW + pad;
                    int oy = row * cellH + pad;
                    Texture2D cell = cropped[i];
                    atlasTexture.SetPixels32(ox, oy, cell.width, cell.height, cell.GetPixels32());
                    frames.Add(new[]
                    {
                        (float)ox / atlasW,
                        1f - (float)(oy + cell.height) / atlasH,
                        (float)(ox + cell.width) / atlasW,
                        1f - (float)oy / atlasH
                    });
                }
                atlasTexture.Apply();
                return frames;
            }
            finally
            {
                for (int i = 0; i < cropped.Count; i++)
                    if (cropped[i] != null) UnityEngine.Object.DestroyImmediate(cropped[i]);
            }
        }

        private static Texture2D EnsureReadable(Texture2D src)
        {
            if (src.isReadable) return src;
            RenderTexture rt = RenderTexture.GetTemporary(src.width, src.height, 0, RenderTextureFormat.ARGB32, RenderTextureReadWrite.Default);
            RenderTexture prev = RenderTexture.active;
            try
            {
                Graphics.Blit(src, rt);
                RenderTexture.active = rt;
                Texture2D copy = new Texture2D(src.width, src.height, TextureFormat.RGBA32, false);
                copy.ReadPixels(new Rect(0, 0, src.width, src.height), 0, 0, false);
                copy.Apply();
                return copy;
            }
            finally
            {
                RenderTexture.active = prev;
                RenderTexture.ReleaseTemporary(rt);
            }
        }

        private static Texture2D RenderSpriteCell(Sprite sprite, Texture2D readableSrc, int width, int height)
        {
            Texture2D dst = new Texture2D(width, height, TextureFormat.RGBA32, false);
            Color32[] outPixels = new Color32[width * height];
            Vector2[] verts = sprite.vertices;
            Vector2[] uvs = sprite.uv;
            ushort[] tris = sprite.triangles;
            float ppu = sprite.pixelsPerUnit > 0f ? sprite.pixelsPerUnit : 100f;
            Vector2 pivotPx = sprite.pivot;
            int texW = readableSrc.width;
            int texH = readableSrc.height;
            Color32[] srcPixels = readableSrc.GetPixels32();

            for (int t = 0; t + 2 < tris.Length; t += 3)
            {
                Vector2 p0 = verts[tris[t]] * ppu + pivotPx;
                Vector2 p1 = verts[tris[t + 1]] * ppu + pivotPx;
                Vector2 p2 = verts[tris[t + 2]] * ppu + pivotPx;
                Vector2 uv0 = uvs[tris[t]];
                Vector2 uv1 = uvs[tris[t + 1]];
                Vector2 uv2 = uvs[tris[t + 2]];

                int minX = Mathf.Clamp(Mathf.FloorToInt(Mathf.Min(p0.x, Mathf.Min(p1.x, p2.x))), 0, width - 1);
                int maxX = Mathf.Clamp(Mathf.CeilToInt(Mathf.Max(p0.x, Mathf.Max(p1.x, p2.x))), 0, width - 1);
                int minY = Mathf.Clamp(Mathf.FloorToInt(Mathf.Min(p0.y, Mathf.Min(p1.y, p2.y))), 0, height - 1);
                int maxY = Mathf.Clamp(Mathf.CeilToInt(Mathf.Max(p0.y, Mathf.Max(p1.y, p2.y))), 0, height - 1);

                float area = (p1.x - p0.x) * (p2.y - p0.y) - (p2.x - p0.x) * (p1.y - p0.y);
                if (Mathf.Abs(area) < 1e-6f) continue;

                for (int py = minY; py <= maxY; py++)
                {
                    for (int px = minX; px <= maxX; px++)
                    {
                        float cx = px + 0.5f;
                        float cy = py + 0.5f;
                        float w0 = ((p1.x - cx) * (p2.y - cy) - (p2.x - cx) * (p1.y - cy)) / area;
                        float w1 = ((p2.x - cx) * (p0.y - cy) - (p0.x - cx) * (p2.y - cy)) / area;
                        float w2 = 1f - w0 - w1;
                        if (w0 < -1e-4f || w1 < -1e-4f || w2 < -1e-4f) continue;
                        Vector2 uv = uv0 * w0 + uv1 * w1 + uv2 * w2;
                        int sx = Mathf.Clamp(Mathf.RoundToInt(uv.x * (texW - 1)), 0, texW - 1);
                        int sy = Mathf.Clamp(Mathf.RoundToInt(uv.y * (texH - 1)), 0, texH - 1);
                        outPixels[py * width + px] = srcPixels[sy * texW + sx];
                    }
                }
            }
            dst.SetPixels32(outPixels);
            dst.Apply();
            return dst;
        }
    }
}
#endif
