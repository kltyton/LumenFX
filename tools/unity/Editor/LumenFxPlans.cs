#if UNITY_EDITOR
using System.Collections.Generic;
using UnityEngine;

namespace LumenFx.UnityExport
{
    internal sealed class ExportSettings
    {
        public GameObject Root;
        public string Namespace = "lumenfx";
        public string EffectName = "effect";
        public string OutputFolder;
    }

    internal enum IssueSeverity
    {
        Error,
        Warning
    }

    internal sealed class ExportIssue
    {
        public readonly string Context;
        public readonly string Message;
        public readonly IssueSeverity Severity;

        public ExportIssue(string context, string message) : this(context, message, IssueSeverity.Error)
        {
        }

        public ExportIssue(string context, string message, IssueSeverity severity)
        {
            Context = context;
            Message = message;
            Severity = severity;
        }

        public override string ToString()
        {
            string body = string.IsNullOrEmpty(Context) ? Message : Context + ": " + Message;
            return Severity == IssueSeverity.Warning ? "[Warning] " + body : body;
        }
    }

    internal sealed class MaterialPlan
    {
        public string Id;
        public int RenderQueue;
        public string FragmentShader;
        public string VertexMaterial;
        public bool WorldPositionOffset;
        public bool VertexBasis;
        public float MaximumVertexDisplacement;
        public int MaterialView;
        public int SgUv4Mask;
        public readonly Dictionary<string, string> Textures = new Dictionary<string, string>();
        public readonly Dictionary<string, Dictionary<string, object>> TextureSamplers = new Dictionary<string, Dictionary<string, object>>();
        public string Blend;
        public string BlendOp = "add";
        public bool DepthTest = true;
        public string DepthCompare = "less_equal";
        public bool DepthWrite;
        public bool Cull;
        public bool DepthProjection;
        public bool SceneDepth;
        public bool SceneColor;
        public bool LightFunction;
        public bool DefaultLitSurface;
        public bool GammaBlend;
        public bool ForwardLitSurface;
        public bool ClearCoatSurface;
        public int DynamicSlots;
        public readonly float[] Parameters = new float[4];
        public Color Tint = Color.white;
        public Vector2 TextureScale = Vector2.one;
        public Vector2 TextureOffset = Vector2.zero;
        public Texture2D SourceTexture;
        public string TextureRelativePath;
        public byte[] PngData;
        public readonly Dictionary<string, byte[]> ExtraTexturePayloads = new Dictionary<string, byte[]>();
        public readonly Dictionary<string, string> ExtraTextPayloads = new Dictionary<string, string>();
        public readonly List<Dictionary<string, object>> RuntimeParameters = new List<Dictionary<string, object>>();
    }

    internal sealed class LumenFxPlan
    {
        public int EmitterShapes;
        public readonly Dictionary<string, MaterialPlan> Materials = new Dictionary<string, MaterialPlan>();
        public readonly List<Dictionary<string, object>> Emitters = new List<Dictionary<string, object>>();
        public readonly Dictionary<string, byte[]> MeshGeometry = new Dictionary<string, byte[]>();
        public readonly Dictionary<string, byte[]> ShapeTextures = new Dictionary<string, byte[]>();
        public readonly Dictionary<string, Dictionary<string, object>> MeshAssets =
            new Dictionary<string, Dictionary<string, object>>();
    }

    internal sealed class LumenFxBuildResult
    {
        public readonly LumenFxPlan Plan;
        public readonly List<ExportIssue> Issues;

        public LumenFxBuildResult(LumenFxPlan plan, List<ExportIssue> issues)
        {
            Plan = plan;
            Issues = issues;
        }

        public bool Success
        {
            get
            {
                for (int i = 0; i < Issues.Count; i++)
                    if (Issues[i].Severity == IssueSeverity.Error) return false;
                return true;
            }
        }
    }
}
#endif
