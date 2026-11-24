#if UNITY_EDITOR
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text;
using UnityEditor;
using UnityEngine;
using UnityEngine.Rendering;

namespace LumenFx.UnityExport
{
    internal static partial class LumenFxShaderGraphCompiler
    {
        private readonly struct Expr
        {
            public readonly string Code;
            public readonly int Width;
            public readonly Gradient Ramp;
            public Expr(string code, int width) { Code = code; Width = width; Ramp = null; }
            public Expr(Gradient ramp) { Code = null; Width = 0; Ramp = ramp; }
        }

        private enum UrpLitWorkflow { None = -1, Specular = 0, Metallic = 1 }

        private readonly struct LitSurface
        {
            public readonly Expr Reflectance;
            public readonly Expr Smoothness;
            public readonly Expr Occlusion;
            public readonly Expr WorldNormal;
            public readonly Expr CoatMask;
            public readonly Expr CoatSmoothness;

            public LitSurface(Expr reflectance, Expr smoothness, Expr occlusion, Expr worldNormal, Expr coatMask, Expr coatSmoothness)
            {
                Reflectance = reflectance;
                Smoothness = smoothness;
                Occlusion = occlusion;
                WorldNormal = worldNormal;
                CoatMask = coatMask;
                CoatSmoothness = coatSmoothness;
            }
        }

        private sealed class CompileContext
        {
            public readonly Dictionary<string, Dictionary<string, object>> ObjectsById =
                new Dictionary<string, Dictionary<string, object>>(StringComparer.Ordinal);
            public readonly Dictionary<string, Dictionary<string, object>> PropertiesById =
                new Dictionary<string, Dictionary<string, object>>(StringComparer.Ordinal);
            public readonly Dictionary<(string NodeId, int SlotId), (string NodeId, int SlotId)> IncomingEdges =
                new Dictionary<(string, int), (string, int)>();
            public readonly Dictionary<(string NodeId, int SlotId, bool Vertex), Expr> CompiledSlots =
                new Dictionary<(string, int, bool), Expr>();
            public readonly HashSet<(string NodeId, int SlotId, bool Vertex)> Visiting =
                new HashSet<(string, int, bool)>();
            public readonly Dictionary<string, string> TextureSlotBySamplerKey =
                new Dictionary<string, string>(StringComparer.Ordinal);
            public readonly Dictionary<string, Texture2D> TextureAssetsByPropId =
                new Dictionary<string, Texture2D>(StringComparer.Ordinal);
            public readonly Dictionary<string, string> TextureResourceByPropId =
                new Dictionary<string, string>(StringComparer.Ordinal);
            public readonly Dictionary<string, Vector2> TextureTexelSizes =
                new Dictionary<string, Vector2>(StringComparer.Ordinal);
            public readonly List<string> SamplerDeclarations = new List<string>();
            public readonly HashSet<string> FragmentHelpers = new HashSet<string>(StringComparer.Ordinal);
            public readonly HashSet<string> VertexHelpers = new HashSet<string>(StringComparer.Ordinal);
            public readonly List<string> FragmentGradientFunctions = new List<string>();
            public readonly List<string> VertexGradientFunctions = new List<string>();
            public void AddHelper(string name) => (InVertexStage ? VertexHelpers : FragmentHelpers).Add(name);
            public readonly Material Material;
            public readonly MaterialPlan Plan;
            public readonly string Namespace;
            public readonly string EffectName;
            public readonly HashSet<string> UsedTexturePaths;
            public readonly List<ExportIssue> Issues;
            public readonly string ContextLabel;
            public readonly bool ForwardToUp;
            public readonly bool MeshGpuInstancing;
            public bool InVertexStage;
            public bool CullFront;
            public UrpLitWorkflow LitWorkflow = UrpLitWorkflow.None;
            public bool PremultiplyLitDiffuse;
            public bool AlphaModulate;
            public bool NeedsVertexBasis;
            public bool NeedsFragmentNormal;
            public bool NeedsFragmentBasis;
            public bool NeedsFragmentPosOS;
            public bool NeedsFragmentPosVS;
            public bool NeedsFragmentObjBasis;
            public bool FragmentNeedsModelView;
            public int MaterialView;
            public int FragmentUv4Mask;
            public int VertexUv4Mask;
            public int SamplerCounter;

            public CompileContext(Material material, MaterialPlan plan, string outputNamespace, string effectName,
                HashSet<string> usedTexturePaths, List<ExportIssue> issues, string contextLabel, bool forwardToUp, bool meshGpuInstancing)
            {
                Material = material;
                Plan = plan;
                Namespace = outputNamespace;
                EffectName = effectName;
                UsedTexturePaths = usedTexturePaths;
                Issues = issues;
                ContextLabel = contextLabel;
                ForwardToUp = forwardToUp;
                MeshGpuInstancing = meshGpuInstancing;
            }

            public bool NeedsVertexProgram(bool hasVertexOffset, bool hasVertexNormal, bool hasVertexTangent)
            {
                return hasVertexOffset || hasVertexNormal || hasVertexTangent
                    || NeedsVertexBasis || NeedsFragmentNormal || NeedsFragmentBasis
                    || NeedsFragmentPosOS || NeedsFragmentPosVS || NeedsFragmentObjBasis
                    || VertexUv4Mask != 0;
            }
        }

        public static bool IsShaderGraph(Material material)
        {
            if (material == null || material.shader == null) return false;
            string path = AssetDatabase.GetAssetPath(material.shader);
            return !string.IsNullOrEmpty(path) && path.EndsWith(".shadergraph", StringComparison.OrdinalIgnoreCase);
        }

        public static MaterialPlan TryBuild(Material material, string id, string context,
            string outputNamespace, string effectName, HashSet<string> usedTexturePaths, List<ExportIssue> issues,
            string shaderFileId, bool forwardToUp = false, bool meshGpuInstancing = false)
        {
            string assetPath = AssetDatabase.GetAssetPath(material.shader);
            int errorCountBefore = CountErrors(issues);
            if (string.IsNullOrEmpty(assetPath) || !File.Exists(assetPath))
            {
                issues.Add(new ExportIssue(context, "Shader Graph source asset cannot be found for '" + material.shader.name + "'"));
                return null;
            }

            List<Dictionary<string, object>> docs;
            try
            {
                docs = LumenFxJson.ReadDocuments(File.ReadAllText(assetPath, Encoding.UTF8));
            }
            catch (Exception error)
            {
                issues.Add(new ExportIssue(context, "Failed to parse Shader Graph '" + assetPath + "': " + error.Message));
                return null;
            }

            var ctx = new CompileContext(material, new MaterialPlan { Id = id, RenderQueue = material.renderQueue }, outputNamespace, effectName,
                usedTexturePaths, issues, context, forwardToUp, meshGpuInstancing);

            Dictionary<string, object> graphData = null;
            foreach (Dictionary<string, object> doc in docs)
            {
                string objId = GetString(doc, "m_ObjectId");
                if (!string.IsNullOrEmpty(objId)) ctx.ObjectsById[objId] = doc;
                string type = GetString(doc, "m_Type");
                if (type == "UnityEditor.ShaderGraph.GraphData") graphData = doc;
            }
            if (graphData == null)
            {
                issues.Add(new ExportIssue(context, "Shader Graph '" + assetPath + "' has no GraphData root"));
                return null;
            }

            foreach (Dictionary<string, object> prop in ResolveRefList(ctx, graphData, "m_Properties"))
            {
                string propId = GetString(prop, "m_ObjectId");
                if (!string.IsNullOrEmpty(propId)) ctx.PropertiesById[propId] = prop;
            }

            var edges = graphData.ContainsKey("m_Edges") ? graphData["m_Edges"] as List<object> : null;
            if (edges != null)
            {
                foreach (object item in edges)
                {
                    var edge = item as Dictionary<string, object>;
                    if (edge == null) continue;
                    var outSlot = edge.ContainsKey("m_OutputSlot") ? edge["m_OutputSlot"] as Dictionary<string, object> : null;
                    var inSlot = edge.ContainsKey("m_InputSlot") ? edge["m_InputSlot"] as Dictionary<string, object> : null;
                    if (outSlot == null || inSlot == null) continue;
                    string srcNode = RefId(outSlot, "m_Node");
                    string dstNode = RefId(inSlot, "m_Node");
                    int srcId = GetInt(outSlot, "m_SlotId");
                    int dstId = GetInt(inSlot, "m_SlotId");
                    if (!string.IsNullOrEmpty(srcNode) && !string.IsNullOrEmpty(dstNode))
                        ctx.IncomingEdges[(dstNode, dstId)] = (srcNode, srcId);
                }
            }

            string surfaceType, alphaMode;
            bool alphaClip, twoSided;
            if (!ResolveActiveTarget(ctx, graphData, out var target, out surfaceType, out alphaMode, out alphaClip, out twoSided))
                return null;

            ApplyRenderState(ctx, target, surfaceType, alphaMode, twoSided);
            if (ctx.Plan.DefaultLitSurface) ctx.Plan.DefaultLitSurface = ctx.Plan.Blend == "opaque";
            bool hasLitWorkflow = ctx.LitWorkflow != UrpLitWorkflow.None;
            if (hasLitWorkflow && ctx.Plan.Blend == "opaque") ctx.Plan.DefaultLitSurface = true;
            ctx.Plan.ForwardLitSurface = hasLitWorkflow && LumenFxMaterialBuilder.SupportsForwardLitBlend(ctx.Plan.Blend);

            Dictionary<string, Dictionary<string, object>> blocks = new Dictionary<string, Dictionary<string, object>>(StringComparer.Ordinal);
            foreach (Dictionary<string, object> node in ResolveRefList(ctx, graphData, "m_Nodes"))
            {
                if (GetString(node, "m_Type") != "UnityEditor.ShaderGraph.BlockNode") continue;
                string descriptor = GetString(node, "m_SerializedDescriptor");
                if (!string.IsNullOrEmpty(descriptor)) blocks[descriptor] = node;
            }

            foreach (KeyValuePair<string, Dictionary<string, object>> kv in blocks)
            {
                if (kv.Key == "SurfaceDescription.BaseColor" || kv.Key == "SurfaceDescription.Emission"
                    || kv.Key == "SurfaceDescription.Alpha" || kv.Key == "SurfaceDescription.AlphaClipThreshold"
                    || kv.Key == "SurfaceDescription.NormalTS" || kv.Key == "SurfaceDescription.NormalOS"
                    || kv.Key == "SurfaceDescription.NormalWS" || kv.Key == "SurfaceDescription.Smoothness"
                    || kv.Key == "SurfaceDescription.Metallic" || kv.Key == "SurfaceDescription.Occlusion"
                    || kv.Key == "SurfaceDescription.Specular"
                    || (hasLitWorkflow && (kv.Key == "SurfaceDescription.CoatMask" || kv.Key == "SurfaceDescription.CoatSmoothness"))
                    || kv.Key == "VertexDescription.Position"
                    || kv.Key == "VertexDescription.Normal"
                    || kv.Key == "VertexDescription.Tangent")
                    continue;
                List<Dictionary<string, object>> slots = ResolveRefList(ctx, kv.Value, "m_Slots");
                if (slots.Count > 0 && ctx.IncomingEdges.ContainsKey((GetString(kv.Value, "m_ObjectId"), GetInt(slots[0], "m_Id"))))
                {
                    issues.Add(new ExportIssue(context, "Unsupported connected Shader Graph Master Stack block '" + kv.Key + "'"));
                    return null;
                }
            }

            Expr baseColor = CompileBlockOrDefault(ctx, blocks, "SurfaceDescription.BaseColor", DefaultSrgbColor(0.5f, 0.5f, 0.5f), false);
            Expr emission = CompileBlockOrDefault(ctx, blocks, "SurfaceDescription.Emission", new Expr("vec3(0.0)", 3), false);
            Expr alpha = CompileBlockOrDefault(ctx, blocks, "SurfaceDescription.Alpha", new Expr("1.0", 1), false);
            Expr clipThreshold = alphaClip
                ? CompileBlockOrDefault(ctx, blocks, "SurfaceDescription.AlphaClipThreshold", new Expr("0.5", 1), false)
                : new Expr("-1.0", 1);
            LitSurface? litSurface = null;
            if (hasLitWorkflow && (ctx.Plan.Blend == "opaque" || ctx.Plan.ForwardLitSurface))
            {
                litSurface = CompileLitSurface(ctx, blocks);
            }
            else if (blocks.ContainsKey("SurfaceDescription.NormalTS")
                && HasConnectedSlot(ctx, blocks["SurfaceDescription.NormalTS"]))
            {
                ctx.NeedsVertexBasis = true;
                ctx.NeedsFragmentNormal = true;
                ctx.NeedsFragmentBasis = true;
                Expr normalTs = CompileBlockOrDefault(ctx, blocks, "SurfaceDescription.NormalTS", new Expr("vec3(0.0, 0.0, 1.0)", 3), false);
                Expr metallic = CompileBlockOrDefault(ctx, blocks, "SurfaceDescription.Metallic", new Expr("0.0", 1), false);
                Expr occlusion = CompileBlockOrDefault(ctx, blocks, "SurfaceDescription.Occlusion", new Expr("1.0", 1), false);
                string litFactor = $"(0.45 + 0.55 * clamp(dot(normalize(lfx_sg_frag_world_mc_tangent() * ({normalTs.Code}).x + lfx_sg_frag_world_mc_bitangent() * ({normalTs.Code}).y + lfx_sg_frag_world_mc_normal() * ({normalTs.Code}).z), normalize(vec3(0.35, 0.85, 0.35))), 0.0, 1.0)) * ({occlusion.Code}) * (1.0 - 0.35 * ({metallic.Code}))";
                baseColor = new Expr($"(({baseColor.Code}) * ({litFactor}))", 3);
            }
            else if (ctx.Plan.DefaultLitSurface
                     || (blocks.ContainsKey("SurfaceDescription.Smoothness") && HasConnectedSlot(ctx, blocks["SurfaceDescription.Smoothness"]))
                     || (blocks.ContainsKey("SurfaceDescription.Metallic") && HasConnectedSlot(ctx, blocks["SurfaceDescription.Metallic"])))
            {
                ctx.NeedsVertexBasis = true;
                ctx.NeedsFragmentNormal = true;
                Expr metallic = CompileBlockOrDefault(ctx, blocks, "SurfaceDescription.Metallic", new Expr("0.0", 1), false);
                Expr occlusion = CompileBlockOrDefault(ctx, blocks, "SurfaceDescription.Occlusion", new Expr("1.0", 1), false);
                string litFactor = $"(0.45 + 0.55 * clamp(dot(lfx_sg_frag_world_mc_normal(), normalize(vec3(0.35, 0.85, 0.35))), 0.0, 1.0)) * ({occlusion.Code}) * (1.0 - 0.35 * ({metallic.Code}))";
                baseColor = new Expr($"(({baseColor.Code}) * ({litFactor}))", 3);
            }

            bool hasVertexOffset = false;
            Expr vertexPosition = new Expr("vec3(0.0)", 3);
            if (TryCompileVertexBlock(ctx, blocks, "VertexDescription.Position", out Expr posExpr))
            {
                vertexPosition = posExpr;
                hasVertexOffset = true;
                ctx.NeedsVertexBasis = true;
            }
            bool hasVertexNormal = false;
            Expr vertexNormal = new Expr("vec3(0.0, 0.0, 1.0)", 3);
            if (TryCompileVertexBlock(ctx, blocks, "VertexDescription.Normal", out Expr normExpr))
            {
                vertexNormal = normExpr;
                hasVertexNormal = true;
                ctx.NeedsVertexBasis = true;
                ctx.NeedsFragmentNormal = true;
            }
            bool hasVertexTangent = false;
            Expr vertexTangent = new Expr("vec3(1.0, 0.0, 0.0)", 3);
            if (TryCompileVertexBlock(ctx, blocks, "VertexDescription.Tangent", out Expr tanExpr))
            {
                vertexTangent = tanExpr;
                hasVertexTangent = true;
                ctx.NeedsVertexBasis = true;
                ctx.NeedsFragmentBasis = true;
            }

            if (CountErrors(issues) > errorCountBefore) return null;

            ctx.Plan.MaterialView = ctx.MaterialView;
            ctx.Plan.SgUv4Mask = ctx.VertexUv4Mask | ctx.FragmentUv4Mask;

            string fsh = EmitFragmentShader(ctx, baseColor, emission, alpha, clipThreshold, alphaClip, litSurface);
            string relFsh = "assets/" + outputNamespace + "/shaders/effects/" + effectName + "/" + shaderFileId + ".fsh";
            ctx.Plan.ExtraTextPayloads[relFsh] = fsh;
            ctx.Plan.FragmentShader = outputNamespace + ":effects/" + effectName + "/" + shaderFileId;

            if (ctx.NeedsVertexProgram(hasVertexOffset, hasVertexNormal, hasVertexTangent))
            {
                string vsh = EmitVertexShader(ctx,
                    hasVertexOffset ? vertexPosition : null,
                    hasVertexNormal ? vertexNormal : null,
                    hasVertexTangent ? vertexTangent : null);
                string relVsh = "assets/" + outputNamespace + "/shaders/effects/" + effectName + "/" + shaderFileId + ".vsh";
                ctx.Plan.ExtraTextPayloads[relVsh] = vsh;
                ctx.Plan.VertexMaterial = outputNamespace + ":effects/" + effectName + "/" + shaderFileId;
                ctx.Plan.WorldPositionOffset = hasVertexOffset;
                ctx.Plan.VertexBasis = ctx.NeedsVertexBasis;
                ctx.Plan.MaximumVertexDisplacement = 0f;
            }

            return ctx.Plan;
        }

        private static bool HasConnectedSlot(CompileContext ctx, Dictionary<string, object> block)
        {
            if (block == null) return false;
            List<Dictionary<string, object>> slots = ResolveRefList(ctx, block, "m_Slots");
            if (slots.Count == 0) return false;
            return ctx.IncomingEdges.ContainsKey((GetString(block, "m_ObjectId"), GetInt(slots[0], "m_Id")));
        }

        private static LitSurface CompileLitSurface(CompileContext ctx,
            Dictionary<string, Dictionary<string, object>> blocks)
        {
            ctx.NeedsVertexBasis = true;
            ctx.NeedsFragmentNormal = true;
            var vertexNormal = new Expr("lfx_sg_frag_world_mc_normal()", 3);
            Expr worldNormal = vertexNormal;
            if (blocks.TryGetValue("SurfaceDescription.NormalTS", out var tangentBlock) && HasConnectedSlot(ctx, tangentBlock))
            {
                ctx.NeedsFragmentBasis = true;
                Expr normal = CompileBlockOrDefault(ctx, blocks, "SurfaceDescription.NormalTS", new Expr("vec3(0, 0, 1)", 3), false);
                worldNormal = new Expr($"lfx_sg_frag_tangent_normal({normal.Code})", 3);
            }
            else if (blocks.TryGetValue("SurfaceDescription.NormalOS", out var objectBlock) && HasConnectedSlot(ctx, objectBlock))
            {
                ctx.NeedsFragmentObjBasis = true;
                Expr normal = CompileBlockOrDefault(ctx, blocks, "SurfaceDescription.NormalOS", new Expr("vec3(0, 0, 1)", 3), false);
                worldNormal = new Expr($"lfx_sg_frag_object_normal({normal.Code})", 3);
            }
            else if (blocks.TryGetValue("SurfaceDescription.NormalWS", out var worldBlock) && HasConnectedSlot(ctx, worldBlock))
            {
                Expr normal = CompileBlockOrDefault(ctx, blocks, "SurfaceDescription.NormalWS", new Expr("vec3(0, 1, 0)", 3), false);
                worldNormal = new Expr($"lfx_sg_safe_norm(lfx_sg_mc_to_unity_dir({normal.Code}), {vertexNormal.Code})", 3);
            }
            Expr reflectance = ctx.LitWorkflow == UrpLitWorkflow.Specular
                ? CompileBlockOrDefault(ctx, blocks, "SurfaceDescription.Specular", DefaultSrgbColor(0.5f, 0.5f, 0.5f), false)
                : CompileBlockOrDefault(ctx, blocks, "SurfaceDescription.Metallic", new Expr("0.0", 1), false);
            return new LitSurface(
                reflectance,
                CompileBlockOrDefault(ctx, blocks, "SurfaceDescription.Smoothness", new Expr("0.5", 1), false),
                CompileBlockOrDefault(ctx, blocks, "SurfaceDescription.Occlusion", new Expr("1.0", 1), false),
                worldNormal,
                ctx.Plan.ClearCoatSurface ? CompileBlockOrDefault(ctx, blocks, "SurfaceDescription.CoatMask", new Expr("0.0", 1), false) : default,
                ctx.Plan.ClearCoatSurface ? CompileBlockOrDefault(ctx, blocks, "SurfaceDescription.CoatSmoothness", new Expr("1.0", 1), false) : default);
        }

        private static bool TryCompileVertexBlock(CompileContext ctx, Dictionary<string, Dictionary<string, object>> blocks,
            string descriptor, out Expr value)
        {
            value = default(Expr);
            Dictionary<string, object> block;
            if (!blocks.TryGetValue(descriptor, out block)) return false;
            List<Dictionary<string, object>> slots = ResolveRefList(ctx, block, "m_Slots");
            if (slots.Count == 0) return false;
            int slotId = GetInt(slots[0], "m_Id");
            if (!ctx.IncomingEdges.ContainsKey((GetString(block, "m_ObjectId"), slotId))) return false;
            ctx.InVertexStage = true;
            value = Cast(CompileNodeSlot(ctx, block, slotId), 3);
            ctx.InVertexStage = false;
            return true;
        }

        private static bool ResolveActiveTarget(CompileContext ctx, Dictionary<string, object> graphData,
            out Dictionary<string, object> target, out string surfaceType, out string alphaMode, out bool alphaClip, out bool twoSided)
        {
            surfaceType = "Transparent";
            alphaMode = "Alpha";
            alphaClip = ctx.Material.IsKeywordEnabled("_ALPHATEST_ON") || (ctx.Material.HasProperty("_AlphaClip") && ctx.Material.GetFloat("_AlphaClip") > 0.5f);
            twoSided = ctx.Material.HasProperty("_Cull") && Mathf.RoundToInt(ctx.Material.GetFloat("_Cull")) == 0;

            List<Dictionary<string, object>> targets = ResolveRefList(ctx, graphData, "m_ActiveTargets");
            target = targets.Count == 0 ? null : targets[0];
            if (targets.Count > 1)
            {
                var pipeline = GraphicsSettings.currentRenderPipeline;
#if UNITY_2022_3_OR_NEWER
                string pipelineTag = pipeline == null ? "" : pipeline.renderPipelineShaderTag;
#else
                string pipelineTag = pipeline == null ? "" : ctx.Material.GetTag("RenderPipeline", false, "");
#endif
                string targetType = pipelineTag switch
                {
                    "UniversalPipeline" => "UnityEditor.Rendering.Universal.ShaderGraph.UniversalTarget",
                    "UniversalRenderPipeline" => "UnityEditor.Rendering.Universal.ShaderGraph.UniversalTarget",
                    "HDRenderPipeline" => "UnityEditor.Rendering.HighDefinition.ShaderGraph.HDTarget",
                    "" when pipeline == null => "UnityEditor.Rendering.BuiltIn.ShaderGraph.BuiltInTarget",
                    _ => null
                };
                target = targetType == null ? null : targets.Find(candidate => GetString(candidate, "m_Type") == targetType);
                if (target == null)
                {
                    string pipelineName = pipeline == null ? "Built-in" : pipeline.name;
                    ctx.Issues.Add(new ExportIssue(ctx.ContextLabel,
                        "Shader Graph has no target for the active render pipeline '" + pipelineName + "'"));
                    return false;
                }
            }
            if (target == null) return true;

            string subTargetId = RefId(target, "m_ActiveSubTarget");
            Dictionary<string, object> subTarget = !string.IsNullOrEmpty(subTargetId) && ctx.ObjectsById.ContainsKey(subTargetId)
                ? ctx.ObjectsById[subTargetId] : null;
            string subType = subTarget != null ? GetString(subTarget, "m_Type") : "";
            if (!string.IsNullOrEmpty(subType)
                && !subType.Contains("Unlit", StringComparison.OrdinalIgnoreCase)
                && !subType.Contains("Lit", StringComparison.OrdinalIgnoreCase))
            {
                ctx.Issues.Add(new ExportIssue(ctx.ContextLabel, "Shader Graph active sub-target '" + subType + "' is not Unlit or Lit"));
                return false;
            }

            bool universal = IsUniversalTarget(target);
            bool materialOverride = !universal || GetBool(target, "m_AllowMaterialOverride");
            if (universal && subType == "UnityEditor.Rendering.Universal.ShaderGraph.UniversalLitSubTarget")
            {
                int mode = subTarget.ContainsKey("m_WorkflowMode") ? GetInt(subTarget, "m_WorkflowMode") : (int)UrpLitWorkflow.Metallic;
                if (materialOverride && ctx.Material.HasProperty("_WorkflowMode"))
                    mode = Mathf.RoundToInt(ctx.Material.GetFloat("_WorkflowMode"));
                if (mode == (int)UrpLitWorkflow.Specular || mode == (int)UrpLitWorkflow.Metallic)
                {
                    ctx.LitWorkflow = (UrpLitWorkflow)mode;
                    ctx.Plan.ClearCoatSurface = GetBool(subTarget, "m_ClearCoat");
                }
            }
            if (universal || target.ContainsKey("m_SurfaceType"))
                surfaceType = GetInt(target, "m_SurfaceType") == 0 ? "Opaque" : "Transparent";
            if (!string.IsNullOrEmpty(subType) && subType.Contains("Lit", StringComparison.OrdinalIgnoreCase)
                && !subType.Contains("Unlit", StringComparison.OrdinalIgnoreCase))
            {
                ctx.Plan.DefaultLitSurface = surfaceType == "Opaque";
            }
            if (target.ContainsKey("m_AlphaMode"))
            {
                int mode = GetInt(target, "m_AlphaMode");
                alphaMode = mode == 1 ? "Premultiply" : mode == 2 ? "Additive" : mode == 3 ? "Multiply" : "Alpha";
            }
            ctx.PremultiplyLitDiffuse = ctx.LitWorkflow != UrpLitWorkflow.None && (materialOverride
                ? ctx.Material.IsKeywordEnabled("_ALPHAPREMULTIPLY_ON")
                : (alphaMode == "Alpha" || alphaMode == "Additive") && GetBool(subTarget, "m_BlendModePreserveSpecular", true));
            ctx.AlphaModulate = universal && (materialOverride ? ctx.Material.IsKeywordEnabled("_ALPHAMODULATE_ON") : alphaMode == "Multiply");
            if (!materialOverride || target.ContainsKey("m_AlphaClip") && !ctx.Material.HasProperty("_AlphaClip"))
                alphaClip = GetBool(target, "m_AlphaClip");
            if (!materialOverride || target.ContainsKey("m_TwoSided") && !ctx.Material.HasProperty("_Cull"))
                twoSided = GetBool(target, "m_TwoSided");
            return true;
        }

        private static bool IsUniversalTarget(Dictionary<string, object> target)
        {
            return target != null && GetString(target, "m_Type") == "UnityEditor.Rendering.Universal.ShaderGraph.UniversalTarget";
        }

        private static void ApplyRenderState(CompileContext ctx, Dictionary<string, object> target,
            string surfaceType, string alphaMode, bool twoSided)
        {
            Material material = ctx.Material;
            MaterialPlan plan = ctx.Plan;
            bool universal = IsUniversalTarget(target);
            bool materialOverride = !universal || GetBool(target, "m_AllowMaterialOverride");
            if (materialOverride && material.HasProperty("_Surface"))
                surfaceType = material.GetFloat("_Surface") < 0.5f ? "Opaque" : "Transparent";
            if (surfaceType == "Opaque")
            {
                plan.Blend = "opaque";
            }
            else
            {
                if (materialOverride && material.HasProperty("_SrcBlend") && material.HasProperty("_DstBlend"))
                {
                    int src = Mathf.RoundToInt(material.GetFloat("_SrcBlend"));
                    int dst = Mathf.RoundToInt(material.GetFloat("_DstBlend"));
                    plan.Blend = src == 2 && dst == 0 ? "modulate"
                        : src == 1 && dst == 1 ? "add"
                        : src == 5 && dst == 1 ? "additive"
                        : src == 1 && dst == 10 ? "premultiplied"
                        : "alpha";
                }
                else
                {
                    plan.Blend = alphaMode == "Premultiply" || alphaMode == "Alpha" && ctx.PremultiplyLitDiffuse ? "premultiplied"
                        : alphaMode == "Additive" ? (ctx.PremultiplyLitDiffuse ? "add" : "additive")
                        : alphaMode == "Multiply" ? "modulate"
                        : "alpha";
                }
            }

            int cull = twoSided ? 0 : 2;
            if (universal && target.ContainsKey("m_RenderFace")) cull = GetInt(target, "m_RenderFace");
            if (materialOverride && material.HasProperty("_Cull")) cull = Mathf.RoundToInt(material.GetFloat("_Cull"));
            if (cull < 0 || cull > 2) ctx.Issues.Add(new ExportIssue(ctx.ContextLabel, "Unknown cull mode: " + cull));
            plan.Cull = cull == 2;
            ctx.CullFront = cull == 1;

            int zWrite = universal ? GetInt(target, "m_ZWriteControl") : 0;
            if (materialOverride && material.HasProperty("_ZWrite"))
                plan.DepthWrite = material.GetFloat("_ZWrite") > 0.5f;
            else
            {
                if (zWrite < 0 || zWrite > 2)
                    ctx.Issues.Add(new ExportIssue(ctx.ContextLabel, "Unknown Shader Graph depth write control: " + zWrite));
                plan.DepthWrite = zWrite == 1 || zWrite == 0 && surfaceType == "Opaque";
            }

            int zTest = universal && target.ContainsKey("m_ZTestMode") ? GetInt(target, "m_ZTestMode") : 4;
            if (materialOverride && material.HasProperty("_ZTest")) zTest = Mathf.RoundToInt(material.GetFloat("_ZTest"));
            plan.DepthCompare = LumenFxMaterialBuilder.DepthCompare(zTest, ctx.ContextLabel, ctx.Issues);
            plan.DepthTest = plan.DepthCompare != "disabled" && plan.DepthCompare != "always";
        }

        private static Expr CompileBlockOrDefault(CompileContext ctx, Dictionary<string, Dictionary<string, object>> blocks,
            string descriptor, Expr fallback, bool vertex)
        {
            Dictionary<string, object> block;
            if (!blocks.TryGetValue(descriptor, out block)) return fallback;
            List<Dictionary<string, object>> slots = ResolveRefList(ctx, block, "m_Slots");
            if (slots.Count == 0) return fallback;
            int slotId = GetInt(slots[0], "m_Id");
            ctx.InVertexStage = vertex;
            if (!ctx.IncomingEdges.ContainsKey((GetString(block, "m_ObjectId"), slotId)))
                return Cast(ReadSlotDefault(ctx, slots[0]), fallback.Width);
            return Cast(CompileNodeSlot(ctx, block, slotId), fallback.Width);
        }

        private static Expr Input(CompileContext ctx, Dictionary<string, object> node, int slotId, Expr fallback = default(Expr))
        {
            string nodeId = GetString(node, "m_ObjectId");
            if (ctx.IncomingEdges.TryGetValue((nodeId, slotId), out var edge))
            {
                Dictionary<string, object> targetNode;
                if (!ctx.ObjectsById.TryGetValue(edge.NodeId, out targetNode))
                {
                    ctx.Issues.Add(new ExportIssue(ctx.ContextLabel, "Missing Shader Graph node '" + edge.NodeId + "'"));
                    return new Expr("0.0", 1);
                }
                return CompileNodeSlot(ctx, targetNode, edge.SlotId);
            }
            Dictionary<string, object> slot = FindSlot(ctx, node, slotId);
            if (slot != null) return ReadSlotDefault(ctx, slot);
            return fallback.Code != null ? fallback : new Expr("0.0", 1);
        }

        private static int ResolveInputSlotId(CompileContext ctx, Dictionary<string, object> node, string slotName, int fallback)
        {
            foreach (Dictionary<string, object> s in ResolveRefList(ctx, node, "m_Slots"))
            {
                if (GetInt(s, "m_SlotType") != 0) continue;
                string shaderOut = GetString(s, "m_ShaderOutputName");
                string display = GetString(s, "m_DisplayName");
                if (string.Equals(shaderOut, slotName, StringComparison.OrdinalIgnoreCase)
                    || string.Equals(display, slotName, StringComparison.OrdinalIgnoreCase))
                {
                    return GetInt(s, "m_Id");
                }
            }
            return fallback;
        }

        private static Expr InputByName(CompileContext ctx, Dictionary<string, object> node, string slotName, int fallback, int width)
            => Cast(Input(ctx, node, ResolveInputSlotId(ctx, node, slotName, fallback)), width);

        private static Expr VectorInputLane(CompileContext ctx, Dictionary<string, object> node, int slotId, string comp)
        {
            var edgeKey = (GetString(node, "m_ObjectId"), slotId);
            if (ctx.IncomingEdges.TryGetValue(edgeKey, out var edge))
            {
                if (ctx.ObjectsById.TryGetValue(edge.NodeId, out var targetNode))
                    return Cast(CompileNodeSlot(ctx, targetNode, edge.SlotId), 1);
            }
            Dictionary<string, object> slot = FindSlot(ctx, node, slotId);
            if (slot != null && slot.ContainsKey("m_Value"))
                return new Expr(Num(GetFloat(slot, "m_Value", VecComponent(node, "m_Value", comp))), 1);
            return new Expr(Num(VecComponent(node, "m_Value", comp)), 1);
        }

        private static Expr InputWidth(CompileContext ctx, Dictionary<string, object> node, int slotId, int width)
            => Cast(Input(ctx, node, slotId), width);

        private static Expr CompileNodeSlot(CompileContext ctx, Dictionary<string, object> node, int slotId)
        {
            string nodeId = GetString(node, "m_ObjectId");
            var key = (nodeId, slotId, ctx.InVertexStage);
            Expr cached;
            if (ctx.CompiledSlots.TryGetValue(key, out cached)) return cached;
            if (!ctx.Visiting.Add(key))
            {
                ctx.Issues.Add(new ExportIssue(ctx.ContextLabel, "Cyclic Shader Graph connection at node '" + GetString(node, "m_Name") + "'"));
                return new Expr("0.0", 1);
            }
            try
            {
                Expr compiled = EvaluateNode(ctx, node, slotId);
                ctx.CompiledSlots[key] = compiled;
                return compiled;
            }
            finally
            {
                ctx.Visiting.Remove(key);
            }
        }

        private static Expr EvaluateNode(CompileContext ctx, Dictionary<string, object> node, int slotId)
        {
            string fullType = GetString(node, "m_Type");
            string shortType = fullType.Contains('.') ? fullType.Substring(fullType.LastIndexOf('.') + 1) : fullType;
            switch (shortType)
            {
                case "BlockNode":
                case "PreviewNode":
                case "RedirectNodeData":
                    return Input(ctx, node, 0, new Expr("0.0", 1));
                case "PropertyNode":
                    return EvaluateProperty(ctx, node);
                case "ProviderNode":
                    return CompileParticleProvider(ctx, node, slotId);
                case "GradientNode":
                    return new Expr(ReadGradientNode(ctx, node));
                case "SampleGradient":
                    return CompileGradientSample(ctx, node);
                case "FloatNode":
                case "Vector1Node":
                    return new Expr(Num(GetFloat(node, "m_Value")), 1);
                case "SliderNode":
                    return new Expr(Num(VecComponent(node, "m_Value", "x")), 1);
                case "IntegerNode":
                    return new Expr(Num(GetFloat(node, "m_Value")), 1);
                case "BooleanNode":
                    return new Expr(GetBool(node, "m_Value") ? "1.0" : "0.0", 1);
                case "Vector2Node":
                    return new Expr($"vec2({VectorInputLane(ctx, node, 1, "x").Code}, {VectorInputLane(ctx, node, 2, "y").Code})", 2);
                case "Vector3Node":
                    return new Expr($"vec3({VectorInputLane(ctx, node, 1, "x").Code}, {VectorInputLane(ctx, node, 2, "y").Code}, {VectorInputLane(ctx, node, 3, "z").Code})", 3);
                case "Vector4Node":
                    return new Expr($"vec4({VectorInputLane(ctx, node, 1, "x").Code}, {VectorInputLane(ctx, node, 2, "y").Code}, {VectorInputLane(ctx, node, 3, "z").Code}, {VectorInputLane(ctx, node, 4, "w").Code})", 4);
                case "ColorNode":
                {
                    var colorObj = node.ContainsKey("m_Color") ? node["m_Color"] as Dictionary<string, object> : null;
                    var col = colorObj != null && colorObj.ContainsKey("color") ? colorObj["color"] as Dictionary<string, object> : null;
                    return col == null ? new Expr("vec4(0.0)", 4)
                        : VecLiteral(GetFloat(col, "r"), GetFloat(col, "g"), GetFloat(col, "b"), GetFloat(col, "a", 1f));
                }
                case "TimeNode":
                    return slotId switch
                    {
                        1 => new Expr("sin(EffectClock.x)", 1),
                        2 => new Expr("cos(EffectClock.x)", 1),
                        3 => new Expr("EffectTimeData.x", 1),
                        4 => new Expr("(1.0 / max(EffectTimeData.x, 1e-5))", 1),
                        _ => new Expr("EffectClock.x", 1)
                    };
                case "UVNode":
                    return EvaluateUV(ctx, GetInt(node, "m_OutputChannel"));
                case "VertexColorNode":
                    return new Expr("effectColor", 4);
                case "PositionNode":
                    return EvaluatePosition(ctx, GetInt(node, "m_Space"));
                case "ScreenPositionNode":
                    return EvaluateScreenPosition(ctx);
                case "NormalVectorNode":
                case "TangentVectorNode":
                case "BitangentVectorNode":
                {
                    int kind = shortType == "TangentVectorNode" ? 0 : shortType == "BitangentVectorNode" ? 1 : 2;
                    return EvaluateNormalKind(ctx, kind, GetInt(node, "m_Space"));
                }
                case "ViewDirectionNode":
                    return EvaluateViewDirection(ctx, GetInt(node, "m_Space"));
                case "SampleTexture2DNode":
                    return EvaluateSampleTexture(ctx, node, slotId, explicitLod: false);
                case "SampleTexture2DLODNode":
                    return EvaluateSampleTexture(ctx, node, slotId, explicitLod: true);
                case "Texture2DPropertiesNode":
                case "TextureSizeNode":
                case "TexelSizeNode":
                {
                    int texInputSlot = FindTextureInputSlotId(ctx, node, shortType == "TextureSizeNode" ? 0 : 1);
                    string propId = PropertyIdOfInput(ctx, node, texInputSlot);
                    Vector2 dims = Vector2.one;
                    if (!string.IsNullOrEmpty(propId))
                    {
                        if (!ctx.TextureTexelSizes.TryGetValue(propId, out dims))
                        {
                            Texture2D tex2d = ResolveMaterialTextureAsset(ctx, propId, GetPropertyRefName(ctx, propId));
                            if (tex2d != null) dims = new Vector2(tex2d.width, tex2d.height);
                        }
                    }
                    Dictionary<string, object> outSlot = FindSlot(ctx, node, slotId);
                    string outName = outSlot != null
                        ? (GetString(outSlot, "m_ShaderOutputName") + " " + GetString(outSlot, "m_DisplayName"))
                        : "";
                    if (!string.IsNullOrEmpty(outName))
                    {
                        bool texel = outName.IndexOf("Texel", StringComparison.OrdinalIgnoreCase) >= 0;
                        bool height = outName.IndexOf("Height", StringComparison.OrdinalIgnoreCase) >= 0;
                        if (texel)
                            return new Expr(Num(height ? (dims.y > 0 ? 1f / dims.y : 0f) : (dims.x > 0 ? 1f / dims.x : 0f)), 1);
                        if (height) return new Expr(Num(dims.y), 1);
                        if (outName.IndexOf("Width", StringComparison.OrdinalIgnoreCase) >= 0) return new Expr(Num(dims.x), 1);
                    }
                    if (shortType == "Texture2DPropertiesNode" || shortType == "TextureSizeNode")
                    {
                        return slotId switch
                        {
                            0 => new Expr(Num(dims.x), 1),
                            1 => new Expr(Num(shortType == "TextureSizeNode" ? dims.x : dims.y), 1),
                            2 => new Expr(Num(dims.y), 1),
                            _ => VecLiteral(dims.x, dims.y)
                        };
                    }
                    return slotId switch
                    {
                        0 => new Expr(Num(dims.x), 1),
                        1 => new Expr(Num(dims.y), 1),
                        2 => new Expr(Num(dims.x > 0 ? 1f / dims.x : 0f), 1),
                        3 => new Expr(Num(dims.y > 0 ? 1f / dims.y : 0f), 1),
                        _ => VecLiteral(dims.x, dims.y)
                    };
                }
                case "TilingAndOffsetNode":
                {
                    Expr uv = InputByName(ctx, node, "UV", 0, 2);
                    Expr tiling = InputByName(ctx, node, "Tiling", 1, 2);
                    Expr offset = InputByName(ctx, node, "Offset", 2, 2);
                    return new Expr($"(({uv.Code}) * ({tiling.Code}) + ({offset.Code}))", 2);
                }
                case "RotateNode":
                {
                    ctx.AddHelper("rotate");
                    Expr uv = InputByName(ctx, node, "UV", 0, 2);
                    Expr center = InputByName(ctx, node, "Center", 1, 2);
                    Expr rotation = InputByName(ctx, node, "Rotation", 2, 1);
                    bool degrees = GetInt(node, "m_Unit") == 1;
                    string rad = degrees ? $"radians({rotation.Code})" : rotation.Code;
                    return new Expr($"lfx_sg_rotate({uv.Code}, {center.Code}, {rad})", 2);
                }
                case "FlipbookNode":
                {
                    ctx.AddHelper("flipbook");
                    Expr uv = InputByName(ctx, node, "UV", 0, 2);
                    Expr width = InputByName(ctx, node, "Width", 1, 1);
                    Expr height = InputByName(ctx, node, "Height", 2, 1);
                    Expr tile = InputByName(ctx, node, "Tile", 3, 1);
                    string invX = GetBool(node, "m_InvertX", false) ? "true" : "false";
                    string invY = GetBool(node, "m_InvertY", true) ? "true" : "false";
                    return new Expr($"lfx_sg_flipbook({uv.Code}, {width.Code}, {height.Code}, {tile.Code}, {invX}, {invY})", 2);
                }
                case "PolarCoordinatesNode":
                {
                    ctx.AddHelper("polar");
                    return new Expr($"lfx_sg_polar({InputByName(ctx, node, "UV", 0, 2).Code}, {InputByName(ctx, node, "Center", 1, 2).Code}, {InputByName(ctx, node, "RadialScale", 2, 1).Code}, {InputByName(ctx, node, "LengthScale", 3, 1).Code})", 2);
                }
                case "TwirlNode":
                {
                    ctx.AddHelper("twirl");
                    return new Expr($"lfx_sg_twirl({InputByName(ctx, node, "UV", 0, 2).Code}, {InputByName(ctx, node, "Center", 1, 2).Code}, {InputByName(ctx, node, "Strength", 2, 1).Code}, {InputByName(ctx, node, "Offset", 3, 2).Code})", 2);
                }
                case "RadialShearNode":
                {
                    ctx.AddHelper("radial_shear");
                    return new Expr($"lfx_sg_radial_shear({InputByName(ctx, node, "UV", 0, 2).Code}, {InputByName(ctx, node, "Center", 1, 2).Code}, {InputByName(ctx, node, "Strength", 2, 2).Code}, {InputByName(ctx, node, "Offset", 3, 2).Code})", 2);
                }
                case "SpherizeNode":
                {
                    ctx.AddHelper("spherize");
                    return new Expr($"lfx_sg_spherize({InputByName(ctx, node, "UV", 0, 2).Code}, {InputByName(ctx, node, "Center", 1, 2).Code}, {InputByName(ctx, node, "Strength", 2, 1).Code}, {InputByName(ctx, node, "Offset", 3, 2).Code})", 2);
                }
                case "SplitNode":
                {
                    Expr vec = Input(ctx, node, 0);
                    string lane = slotId switch { 1 => "x", 2 => "y", 3 => "z", _ => "w" };
                    int index = slotId - 1;
                    return index < vec.Width ? new Expr($"({vec.Code}).{lane}", 1) : new Expr("0.0", 1);
                }
                case "CombineNode":
                {
                    string r = InputWidth(ctx, node, 0, 1).Code;
                    string g = InputWidth(ctx, node, 1, 1).Code;
                    string b = InputWidth(ctx, node, 2, 1).Code;
                    string a = InputWidth(ctx, node, 3, 1).Code;
                    return slotId switch
                    {
                        5 => new Expr($"vec3({r}, {g}, {b})", 3),
                        6 => new Expr($"vec2({r}, {g})", 2),
                        _ => new Expr($"vec4({r}, {g}, {b}, {a})", 4)
                    };
                }
                case "SwizzleNode":
                {
                    bool legacy = node.ContainsKey("m_Mask") && !node.ContainsKey("_maskInput");
                    int slot = ResolveInputSlotId(ctx, node, "In", legacy ? 1 : 0);
                    Expr src = Cast(Input(ctx, node, slot), 4);
                    string mask = GetString(node, legacy ? "m_Mask" : "_maskInput")
                        .ToLowerInvariant().Replace('r', 'x').Replace('g', 'y').Replace('b', 'z').Replace('a', 'w');
                    if (mask.Length < 1 || mask.Length > 4 || mask.Trim('x', 'y', 'z', 'w').Length != 0)
                    {
                        ctx.Issues.Add(new ExportIssue(ctx.ContextLabel, "Invalid Shader Graph Swizzle mask '" + mask + "'"));
                        return new Expr("0.0", 1);
                    }
                    return new Expr($"({src.Code}).{mask}", mask.Length);
                }
                case "ChannelMaskNode":
                {
                    Expr src = Input(ctx, node, 1);
                    int mask = GetInt(node, "m_ChannelMask");
                    var parts = new List<string>();
                    if ((mask & 1) != 0) parts.Add("x");
                    if ((mask & 2) != 0) parts.Add("y");
                    if ((mask & 4) != 0) parts.Add("z");
                    if ((mask & 8) != 0) parts.Add("w");
                    if (parts.Count == 0) parts.Add("x");
                    Expr four = Cast(src, 4);
                    return new Expr($"({four.Code}).{string.Concat(parts)}", parts.Count);
                }
                case "AppendNode":
                {
                    Expr a = Input(ctx, node, 1);
                    Expr b = Input(ctx, node, 2);
                    int w = Math.Min(4, a.Width + b.Width);
                    return new Expr($"vec{w}({a.Code}, {b.Code})", w);
                }
                case "AddNode": return Binary(ctx, node, 0, 1, "+");
                case "SubtractNode": return Binary(ctx, node, 0, 1, "-");
                case "MultiplyNode": return Binary(ctx, node, 0, 1, "*");
                case "DivideNode": return Binary(ctx, node, 0, 1, "/");
                case "PowerNode": return BinaryFn(ctx, node, 0, 1, "pow");
                case "ModuloNode":
                    ctx.AddHelper("fmod");
                    return BinaryFn(ctx, node, 0, 1, "lfx_sg_fmod");
                case "MinimumNode": return BinaryFn(ctx, node, 0, 1, "min");
                case "MaximumNode": return BinaryFn(ctx, node, 0, 1, "max");
                case "StepNode": return BinaryFn(ctx, node, 0, 1, "step");
                case "NegateNode": return Unary(ctx, node, 0, "-");
                case "SquareRootNode": return UnaryFn(ctx, node, 0, "sqrt");
                case "AbsoluteNode": return UnaryFn(ctx, node, 0, "abs");
                case "FloorNode": return UnaryFn(ctx, node, 0, "floor");
                case "CeilingNode": return UnaryFn(ctx, node, 0, "ceil");
                case "TruncateNode": return UnaryFn(ctx, node, 0, "trunc");
                case "RoundNode": return UnaryFn(ctx, node, 0, "round");
                case "FractionNode": return UnaryFn(ctx, node, 0, "fract");
                case "SignNode": return UnaryFn(ctx, node, 0, "sign");
                case "SineNode": return UnaryFn(ctx, node, 0, "sin");
                case "CosineNode": return UnaryFn(ctx, node, 0, "cos");
                case "TangentNode": return UnaryFn(ctx, node, 0, "tan");
                case "ArcsineNode": return UnaryFn(ctx, node, 0, "asin");
                case "ArccosineNode": return UnaryFn(ctx, node, 0, "acos");
                case "ArctangentNode": return UnaryFn(ctx, node, 0, "atan");
                case "ExponentialNode": return UnaryFn(ctx, node, 0, GetInt(node, "m_Base") == 1 ? "exp2" : "exp");
                case "LogNode": return UnaryFn(ctx, node, 0, GetInt(node, "m_Base") == 1 ? "log2" : "log");
                case "OneMinusNode":
                {
                    Expr v = Input(ctx, node, 0);
                    return new Expr($"(1.0 - ({v.Code}))", v.Width);
                }
                case "ReciprocalNode":
                {
                    Expr v = Input(ctx, node, 0);
                    return new Expr($"(1.0 / ({v.Code}))", v.Width);
                }
                case "SaturateNode":
                {
                    Expr v = Input(ctx, node, 0);
                    return new Expr($"clamp({v.Code}, 0.0, 1.0)", v.Width);
                }
                case "NormalizeNode":
                {
                    Expr v = Input(ctx, node, 0);
                    return v.Width == 1 ? new Expr($"sign({v.Code})", 1) : new Expr($"normalize({v.Code})", v.Width);
                }
                case "LengthNode":
                    return new Expr($"length({Input(ctx, node, 0).Code})", 1);
                case "DistanceNode":
                {
                    Expr a = Input(ctx, node, 0);
                    Expr b = Input(ctx, node, 1);
                    int w = Math.Max(a.Width, b.Width);
                    return new Expr($"distance({Cast(a, w).Code}, {Cast(b, w).Code})", 1);
                }
                case "DotProductNode":
                {
                    Expr a = Input(ctx, node, 0);
                    Expr b = Input(ctx, node, 1);
                    int w = Math.Max(a.Width, b.Width);
                    return new Expr($"dot({Cast(a, w).Code}, {Cast(b, w).Code})", 1);
                }
                case "CrossProductNode":
                    return new Expr($"cross({InputWidth(ctx, node, 0, 3).Code}, {InputWidth(ctx, node, 1, 3).Code})", 3);
                case "ClampNode":
                {
                    Expr v = Input(ctx, node, 0);
                    Expr min = Cast(Input(ctx, node, 1), v.Width);
                    Expr max = Cast(Input(ctx, node, 2), v.Width);
                    return new Expr($"clamp({v.Code}, {min.Code}, {max.Code})", v.Width);
                }
                case "LerpNode":
                {
                    Expr a = Input(ctx, node, 0);
                    Expr b = Input(ctx, node, 1);
                    Expr t = Input(ctx, node, 2);
                    int w = Math.Max(a.Width, Math.Max(b.Width, t.Width));
                    return new Expr($"mix({Cast(a, w).Code}, {Cast(b, w).Code}, {Cast(t, w).Code})", w);
                }
                case "InverseLerpNode":
                {
                    Expr a = Input(ctx, node, 0);
                    Expr b = Input(ctx, node, 1);
                    Expr v = Input(ctx, node, 2);
                    int w = Math.Max(a.Width, Math.Max(b.Width, v.Width));
                    return new Expr($"(({Cast(v, w).Code} - {Cast(a, w).Code}) / ({Cast(b, w).Code} - {Cast(a, w).Code}))", w);
                }
                case "SmoothstepNode":
                {
                    Expr a = Input(ctx, node, 0);
                    Expr b = Input(ctx, node, 1);
                    Expr x = Input(ctx, node, 2);
                    int w = Math.Max(a.Width, Math.Max(b.Width, x.Width));
                    return new Expr($"smoothstep({Cast(a, w).Code}, {Cast(b, w).Code}, {Cast(x, w).Code})", w);
                }
                case "RemapNode":
                {
                    Expr v = Input(ctx, node, 0);
                    Expr inMinMax = InputWidth(ctx, node, 1, 2);
                    Expr outMinMax = InputWidth(ctx, node, 2, 2);
                    return new Expr($"(({outMinMax.Code}).x + (({v.Code}) - ({inMinMax.Code}).x) * (({outMinMax.Code}).y - ({outMinMax.Code}).x) / (({inMinMax.Code}).y - ({inMinMax.Code}).x))", v.Width);
                }
                case "FresnelNode":
                {
                    Expr normal = InputWidth(ctx, node, 0, 3);
                    Expr view = InputWidth(ctx, node, 1, 3);
                    Expr power = InputWidth(ctx, node, 2, 1);
                    return new Expr($"pow(clamp(1.0 - max(dot(normalize({normal.Code}), normalize({view.Code})), 0.0), 0.0, 1.0), {power.Code})", 1);
                }
                case "NoiseNode":
                case "SimpleNoiseNode":
                    ctx.AddHelper("simple_noise");
                    return new Expr($"lfx_sg_simple_noise({InputByName(ctx, node, "UV", 0, 2).Code}, {InputByName(ctx, node, "Scale", 1, 1).Code})", 1);
                case "GradientNoiseNode":
                    ctx.AddHelper("gradient_noise");
                    return new Expr($"lfx_sg_gradient_noise({InputByName(ctx, node, "UV", 0, 2).Code}, {InputByName(ctx, node, "Scale", 1, 1).Code})", 1);
                case "VoronoiNode":
                {
                    ctx.AddHelper("voronoi");
                    bool cellsOut = slotId == 4 || string.Equals(GetString(FindSlot(ctx, node, slotId) ?? new Dictionary<string, object>(), "m_ShaderOutputName"), "Cells", StringComparison.OrdinalIgnoreCase);
                    return cellsOut
                        ? new Expr($"lfx_sg_voronoi_cells({InputByName(ctx, node, "UV", 0, 2).Code}, {InputByName(ctx, node, "AngleOffset", 1, 1).Code}, {InputByName(ctx, node, "CellDensity", 2, 1).Code})", 1)
                        : new Expr($"lfx_sg_voronoi({InputByName(ctx, node, "UV", 0, 2).Code}, {InputByName(ctx, node, "AngleOffset", 1, 1).Code}, {InputByName(ctx, node, "CellDensity", 2, 1).Code})", 1);
                }
                case "ColorspaceConversionNode":
                {
                    ctx.AddHelper("colorspace");
                    int fromMode = GetConversionMode(node, "from", "m_From");
                    int toMode = GetConversionMode(node, "to", "m_To");
                    return new Expr($"lfx_sg_convert_color({InputByName(ctx, node, "In", 1, 3).Code}, {fromMode}, {toMode})", 3);
                }
                case "SaturationNode":
                {
                    Expr col = InputWidth(ctx, node, 1, 3);
                    Expr sat = InputWidth(ctx, node, 2, 1);
                    return new Expr($"mix(vec3(dot({col.Code}, vec3(0.2126729, 0.7151522, 0.0721750))), {col.Code}, {sat.Code})", 3);
                }
                case "ContrastNode":
                {
                    Expr col = InputWidth(ctx, node, 1, 3);
                    Expr contrast = InputWidth(ctx, node, 2, 1);
                    return new Expr($"((({col.Code}) - exp2(-2.226681)) * ({contrast.Code}) + exp2(-2.226681))", 3);
                }
                case "HueNode":
                    ctx.AddHelper("colorspace");
                    return new Expr($"lfx_sg_hue({InputWidth(ctx, node, 1, 3).Code}, {InputWidth(ctx, node, 2, 1).Code}, {(GetInt(node, "m_HueOffsetType") == 0 ? "1.0 / 360.0" : "1.0")})", 3);
                case "InvertColorsNode":
                {
                    Expr v = Input(ctx, node, 1);
                    bool r = GetBool(node, "m_RedChannel");
                    bool g = GetBool(node, "m_GreenChannel");
                    bool b = GetBool(node, "m_BlueChannel");
                    bool a = GetBool(node, "m_AlphaChannel");
                    if (v.Width == 1)
                        return r ? new Expr($"(1.0 - ({v.Code}))", 1) : v;
                    string mask = v.Width switch
                    {
                        2 => $"vec2({(r ? "1.0" : "0.0")}, {(g ? "1.0" : "0.0")})",
                        3 => $"vec3({(r ? "1.0" : "0.0")}, {(g ? "1.0" : "0.0")}, {(b ? "1.0" : "0.0")})",
                        _ => $"vec4({(r ? "1.0" : "0.0")}, {(g ? "1.0" : "0.0")}, {(b ? "1.0" : "0.0")}, {(a ? "1.0" : "0.0")})"
                    };
                    return new Expr($"mix(({v.Code}), vec{v.Width}(1.0) - ({v.Code}), {mask})", v.Width);
                }
                case "ComparisonNode":
                {
                    Expr a = InputWidth(ctx, node, 0, 1);
                    Expr b = InputWidth(ctx, node, 1, 1);
                    string op = GetInt(node, "m_ComparisonType") switch { 1 => "!=", 2 => "<", 3 => "<=", 4 => ">", 5 => ">=", _ => "==" };
                    return new Expr($"(({a.Code}) {op} ({b.Code}) ? 1.0 : 0.0)", 1);
                }
                case "BranchNode":
                {
                    Expr pred = InputWidth(ctx, node, 0, 1);
                    Expr t = Input(ctx, node, 1);
                    Expr f = Input(ctx, node, 2);
                    int w = Math.Max(t.Width, f.Width);
                    return new Expr($"(({pred.Code}) != 0.0 ? {Cast(t, w).Code} : {Cast(f, w).Code})", w);
                }
                default:
                    ctx.Issues.Add(new ExportIssue(ctx.ContextLabel, "Unsupported Shader Graph node type '" + shortType + "'"));
                    return new Expr("0.0", 1);
            }
        }

        private static Expr EvaluateProperty(CompileContext ctx, Dictionary<string, object> node)
        {
            string propId = RefId(node, "m_Property");
            Dictionary<string, object> prop;
            if (string.IsNullOrEmpty(propId) || !ctx.PropertiesById.TryGetValue(propId, out prop))
            {
                ctx.Issues.Add(new ExportIssue(ctx.ContextLabel, "Missing Shader Graph property reference"));
                return new Expr("0.0", 1);
            }
            string propType = GetString(prop, "m_Type");
            string shortType = propType.Contains('.') ? propType.Substring(propType.LastIndexOf('.') + 1) : propType;
            string refName = GetString(prop, "m_OverrideReferenceName");
            if (string.IsNullOrEmpty(refName)) refName = GetString(prop, "m_DefaultReferenceName");
            bool hasMat = !string.IsNullOrEmpty(refName) && ctx.Material.HasProperty(refName);

            switch (shortType)
            {
                case "GradientShaderProperty":
                    return new Expr(ReadGradientValue(ctx, prop));
                case "Vector1ShaderProperty":
                case "BooleanShaderProperty":
                {
                    float v = hasMat ? ctx.Material.GetFloat(refName) : GetFloat(prop, "m_Value");
                    return new Expr(Num(v), 1);
                }
                case "Vector2ShaderProperty":
                {
                    Vector4 v = hasMat ? ctx.Material.GetVector(refName) : new Vector4(VecComponent(prop, "m_Value", "x"), VecComponent(prop, "m_Value", "y"), 0, 0);
                    return VecLiteral(v.x, v.y);
                }
                case "Vector3ShaderProperty":
                {
                    Vector4 v = hasMat ? ctx.Material.GetVector(refName) : new Vector4(VecComponent(prop, "m_Value", "x"), VecComponent(prop, "m_Value", "y"), VecComponent(prop, "m_Value", "z"), 0);
                    return VecLiteral(v.x, v.y, v.z);
                }
                case "Vector4ShaderProperty":
                {
                    Vector4 v = hasMat ? ctx.Material.GetVector(refName) : new Vector4(VecComponent(prop, "m_Value", "x"), VecComponent(prop, "m_Value", "y"), VecComponent(prop, "m_Value", "z"), VecComponent(prop, "m_Value", "w"));
                    return VecLiteral(v.x, v.y, v.z, v.w);
                }
                case "ColorShaderProperty":
                {
                    Color c = hasMat ? ctx.Material.GetColor(refName) : new Color(VecComponent(prop, "m_Value", "r"), VecComponent(prop, "m_Value", "g"), VecComponent(prop, "m_Value", "b"), VecComponent(prop, "m_Value", "a", 1f));
                    return VecLiteral(c.r, c.g, c.b, c.a);
                }
                case "Texture2DShaderProperty":
                {
                    string sampler = EnsureMaterialTextureSampler(ctx, propId, refName, samplerOverride: null);
                    return new Expr(sampler, 4);
                }
                default:
                    ctx.Issues.Add(new ExportIssue(ctx.ContextLabel, "Unsupported Shader Graph property type '" + shortType + "'"));
                    return new Expr("0.0", 1);
            }
        }

        private static Texture2D ResolveMaterialTextureAsset(CompileContext ctx, string propId, string refName)
        {
            Texture2D cached;
            if (ctx.TextureAssetsByPropId.TryGetValue(propId, out cached)) return cached;
            if (string.IsNullOrEmpty(refName) || !ctx.Material.HasProperty(refName))
            {
                ctx.Issues.Add(new ExportIssue(ctx.ContextLabel, "Missing texture property '" + refName + "' on Shader Graph material"));
                return null;
            }
            Texture2D tex2d = ctx.Material.GetTexture(refName) as Texture2D;
            if (tex2d == null)
            {
                ctx.Issues.Add(new ExportIssue(ctx.ContextLabel, "Shader Graph texture property '" + refName + "' has no assigned Texture2D"));
                return null;
            }
            ctx.TextureAssetsByPropId[propId] = tex2d;
            ctx.TextureTexelSizes[propId] = new Vector2(tex2d.width, tex2d.height);
            return tex2d;
        }

        private static string EnsureMaterialTextureSampler(CompileContext ctx, string propId, string refName,
            Dictionary<string, object> samplerOverride)
        {
            Texture2D tex2d = ResolveMaterialTextureAsset(ctx, propId, refName);
            if (tex2d == null) return "Sampler0";

            Dictionary<string, object> sampler = samplerOverride ?? LumenFxMaterialBuilder.BuildSamplerSettings(tex2d);
            string samplerKey = propId + "|" + sampler["address_x"] + "|" + sampler["address_y"] + "|"
                + sampler["min_mag_filter"] + "|" + sampler["mip_filter"] + "|" + sampler["max_anisotropy"];
            string existingSlot;
            if (ctx.TextureSlotBySamplerKey.TryGetValue(samplerKey, out existingSlot)) return existingSlot;

            string slot = "Sampler" + ctx.SamplerCounter++;
            ctx.TextureSlotBySamplerKey[samplerKey] = slot;
            ctx.SamplerDeclarations.Add("uniform sampler2D " + slot + ";");

            string existingResource;
            if (ctx.TextureResourceByPropId.TryGetValue(propId, out existingResource))
            {
                ctx.Plan.Textures[slot] = existingResource;
                ctx.Plan.TextureSamplers[slot] = sampler;
            }
            else
            {
                bool isFirstSlot = ctx.SamplerCounter == 1;
                if (LumenFxMaterialBuilder.RegisterExportedTexture(
                    ctx.Plan, ctx.Material, slot, tex2d,
                    ctx.Namespace, ctx.EffectName, ctx.UsedTexturePaths, ctx.ContextLabel + " (" + refName + ")", ctx.Issues,
                    isPrimary: isFirstSlot, nameSuffix: isFirstSlot ? "" : "_" + slot))
                {
                    ctx.TextureResourceByPropId[propId] = ctx.Plan.Textures[slot];
                    ctx.Plan.TextureSamplers[slot] = sampler;
                }
            }
            return slot;
        }

        private static int FindTextureInputSlotId(CompileContext ctx, Dictionary<string, object> node, int fallback)
        {
            foreach (Dictionary<string, object> s in ResolveRefList(ctx, node, "m_Slots"))
            {
                if (GetInt(s, "m_SlotType") == 0 && GetString(s, "m_Type").Contains("Texture2D"))
                    return GetInt(s, "m_Id");
            }
            return fallback;
        }

        private static Expr EvaluateSampleTexture(CompileContext ctx, Dictionary<string, object> node, int slotId, bool explicitLod)
        {
            string propId = PropertyIdOfInput(ctx, node, 1);
            string samplerName;
            Texture2D tex2d = null;
            if (string.IsNullOrEmpty(propId))
            {
                ctx.Issues.Add(new ExportIssue(ctx.ContextLabel, "SampleTexture2D requires a connected Texture2D property"));
                samplerName = "Sampler0";
            }
            else
            {
                string refName = GetPropertyRefName(ctx, propId);
                tex2d = ResolveMaterialTextureAsset(ctx, propId, refName);
                Dictionary<string, object> samplerOverride = ReadSamplerStateOverride(ctx, node, 3, tex2d);
                samplerName = EnsureMaterialTextureSampler(ctx, propId, refName, samplerOverride);
            }
            Expr uv = InputWidth(ctx, node, 2, 2);
            string sampleCall;
            if (explicitLod)
            {
                Expr lod = InputWidth(ctx, node, 4, 1);
                sampleCall = $"textureLod({samplerName}, {uv.Code}, {lod.Code})";
            }
            else
            {
                int mipMode = GetInt(node, "m_MipSamplingMode");
                if (mipMode == 1)
                {
                    Expr lod = InputWidth(ctx, node, 9, 1);
                    sampleCall = $"textureLod({samplerName}, {uv.Code}, {lod.Code})";
                }
                else if (mipMode == 3)
                {
                    Expr ddx = InputWidth(ctx, node, 10, 2);
                    Expr ddy = InputWidth(ctx, node, 11, 2);
                    sampleCall = $"textureGrad({samplerName}, {uv.Code}, {ddx.Code}, {ddy.Code})";
                }
                else if (mipMode == 2)
                {
                    Expr bias = InputWidth(ctx, node, 8, 1);
                    sampleCall = ctx.InVertexStage
                        ? $"textureLod({samplerName}, {uv.Code}, {bias.Code})"
                        : $"texture({samplerName}, {uv.Code}, {bias.Code})";
                }
                else
                {
                    sampleCall = ctx.InVertexStage
                        ? $"textureLod({samplerName}, {uv.Code}, 0.0)"
                        : $"texture({samplerName}, {uv.Code})";
                }
            }

            if (GetInt(node, "m_TextureType") == 1)
            {
                int normSpace = GetInt(node, "m_NormalMapSpace");
                if (normSpace == 0 && tex2d != null && tex2d.format == TextureFormat.DXT5)
                {
                    ctx.AddHelper("unpack_normal_dxt5nm");
                    sampleCall = $"lfx_sg_unpack_normal_dxt5nm({sampleCall})";
                }
                else if (normSpace == 0 && tex2d != null && tex2d.format == TextureFormat.BC5)
                {
                    ctx.AddHelper("unpack_normal_bc5");
                    sampleCall = $"lfx_sg_unpack_normal_bc5({sampleCall})";
                }
                else
                {
                    ctx.AddHelper("unpack_normal_rgb");
                    sampleCall = $"lfx_sg_unpack_normal_rgb({sampleCall})";
                }
            }

            if (explicitLod)
            {
                return slotId switch
                {
                    5 => new Expr($"({sampleCall}).r", 1),
                    6 => new Expr($"({sampleCall}).g", 1),
                    7 => new Expr($"({sampleCall}).b", 1),
                    8 => new Expr($"({sampleCall}).a", 1),
                    _ => new Expr(sampleCall, 4)
                };
            }
            return slotId switch
            {
                4 => new Expr($"({sampleCall}).r", 1),
                5 => new Expr($"({sampleCall}).g", 1),
                6 => new Expr($"({sampleCall}).b", 1),
                7 => new Expr($"({sampleCall}).a", 1),
                _ => new Expr(sampleCall, 4)
            };
        }

        private static Dictionary<string, object> ReadSamplerStateOverride(CompileContext ctx, Dictionary<string, object> node,
            int samplerSlotId, Texture2D tex2d)
        {
            (string SrcNode, int SrcSlot) edge;
            if (!ctx.IncomingEdges.TryGetValue((GetString(node, "m_ObjectId"), samplerSlotId), out edge)) return null;
            Dictionary<string, object> samplerNode;
            if (!ctx.ObjectsById.TryGetValue(edge.SrcNode, out samplerNode)) return null;
            string nodeType = GetString(samplerNode, "m_Type");
            if (!nodeType.EndsWith(".SamplerStateNode", StringComparison.Ordinal)) return null;
            Dictionary<string, object> stateObj = samplerNode;
            if (samplerNode.TryGetValue("m_TextureSamplerState", out object rawState) && rawState is Dictionary<string, object> tsDict)
                stateObj = tsDict;
            else if (samplerNode.TryGetValue("m_SamplerState", out object rawState2) && rawState2 is Dictionary<string, object> ssDict)
                stateObj = ssDict;
            int filterMode = stateObj.ContainsKey("m_filter") ? GetInt(stateObj, "m_filter") : GetInt(samplerNode, "m_filter");
            int wrapMode = stateObj.ContainsKey("m_wrap") ? GetInt(stateObj, "m_wrap") : GetInt(samplerNode, "m_wrap");
            int rawAniso = stateObj.ContainsKey("m_aniso") ? GetInt(stateObj, "m_aniso")
                : samplerNode.ContainsKey("m_aniso") ? GetInt(samplerNode, "m_aniso") : 1;
            // Standalone SamplerStateNode must not inherit tex2d.anisoLevel.
            int maxAnisotropy = filterMode == 1 ? 1 : Mathf.Max(1, rawAniso);
            string filter = filterMode == 1 ? "point" : (maxAnisotropy > 1 ? "aniso" : "linear");
            string mip = filterMode == 2 ? "linear" : "point";
            string wrap = wrapMode switch { 1 => "clamp", 2 => "mirror", 3 => "mirror_once", _ => "wrap" };
            return new Dictionary<string, object>
            {
                { "address_x", wrap },
                { "address_y", wrap },
                { "min_mag_filter", filter },
                { "mip_filter", mip },
                { "max_anisotropy", maxAnisotropy }
            };
        }

        private static string PropertyIdOfInput(CompileContext ctx, Dictionary<string, object> node, int slotId)
        {
            (string SrcNode, int SrcSlot) edge;
            if (!ctx.IncomingEdges.TryGetValue((GetString(node, "m_ObjectId"), slotId), out edge)) return "";
            Dictionary<string, object> src;
            if (!ctx.ObjectsById.TryGetValue(edge.SrcNode, out src)) return "";
            if (!GetString(src, "m_Type").EndsWith(".PropertyNode", StringComparison.Ordinal)) return "";
            return RefId(src, "m_Property");
        }

        private static string GetPropertyRefName(CompileContext ctx, string propId)
        {
            Dictionary<string, object> prop;
            if (!ctx.PropertiesById.TryGetValue(propId, out prop)) return "";
            string r = GetString(prop, "m_OverrideReferenceName");
            return !string.IsNullOrEmpty(r) ? r : GetString(prop, "m_DefaultReferenceName");
        }

        private static string EmitFragmentShader(CompileContext ctx, Expr baseColor, Expr emission, Expr alpha, Expr clipThreshold,
            bool alphaClip, LitSurface? litSurface)
        {
            var sb = new StringBuilder();
            sb.AppendLine("#version 330");
            if (ctx.FragmentNeedsModelView)
                sb.AppendLine("#moj_import <minecraft:dynamictransforms.glsl>");
            sb.AppendLine("#moj_import <lumenfx:material_fog.glsl>");
            if (litSurface.HasValue) sb.AppendLine("#moj_import <lumenfx:lighting/default_lit_surface.glsl>");
            if (ctx.Plan.ForwardLitSurface) sb.AppendLine("#moj_import <lumenfx:lighting/unity_metallic_forward.glsl>");
            if (ctx.MaterialView != 0)
                sb.AppendLine("#moj_import <lumenfx:material_view.glsl>");
            foreach (string decl in ctx.SamplerDeclarations) sb.AppendLine(decl);
            sb.AppendLine("layout(std140) uniform EffectFrame { vec4 EffectClock; vec4 MaterialParameters; vec4 EffectTimeData; };");
            if (ctx.FragmentHelpers.Contains("screen_uv"))
                sb.AppendLine("layout(std140) uniform EffectView { vec4 CameraRight; vec4 CameraUp; vec4 Geometry; };");
            sb.AppendLine("in vec2 effectUV;");
            sb.AppendLine("in vec2 effectUV1;");
            for (int c = 0; c < 8; c++)
                if ((ctx.FragmentUv4Mask & (1 << c)) != 0) sb.AppendLine($"in vec4 lfxSgUV{c};");
            sb.AppendLine("in vec4 effectColor;");
            sb.AppendLine("in vec4 effectParticle;");
            sb.AppendLine("in vec4 effectCustom;");
            bool fragNeedsNormal = ctx.NeedsFragmentNormal || ctx.NeedsFragmentBasis;
            if (fragNeedsNormal)
                sb.AppendLine("in vec3 effectMaterialVertexNormal;");
            if (ctx.NeedsFragmentBasis)
            {
                sb.AppendLine("in vec3 effectMaterialVertexTangent;");
                sb.AppendLine("in float effectMaterialBasisDeterminant;");
            }
            if (ctx.NeedsFragmentPosOS)
                sb.AppendLine("in vec3 lfxSgFragPosOS;");
            if (ctx.NeedsFragmentPosVS)
                sb.AppendLine("in vec3 lfxSgFragPosVS;");
            if (ctx.NeedsFragmentObjBasis)
            {
                sb.AppendLine("in vec3 lfxSgObjXMC;");
                sb.AppendLine("in vec3 lfxSgObjYMC;");
                sb.AppendLine("in vec3 lfxSgObjZMC;");
            }
            AppendCoordinateHelpers(sb, ctx.MaterialView != 0);
            if (fragNeedsNormal)
            {
                sb.AppendLine("vec3 lfx_sg_frag_world_mc_normal() { return lfx_sg_safe_norm(effectMaterialVertexNormal, vec3(0.0, 1.0, 0.0)); }");
            }
            if (ctx.NeedsFragmentBasis)
            {
                sb.AppendLine("vec3 lfx_sg_frag_world_mc_tangent() { return lfx_sg_safe_norm(effectMaterialVertexTangent, vec3(1.0, 0.0, 0.0)); }");
                // Source tangent basis undergoes .xzy reflection into MC space, flipping cross chirality.
                sb.AppendLine("vec3 lfx_sg_frag_world_mc_bitangent() { vec3 n = lfx_sg_frag_world_mc_normal(); vec3 t = lfx_sg_frag_world_mc_tangent(); return lfx_sg_safe_norm(-cross(n, t) * effectMaterialBasisDeterminant, vec3(0.0, 0.0, 1.0)); }");
                sb.AppendLine("vec3 lfx_sg_frag_world_to_tangent(vec3 dirMC) { return vec3(dot(dirMC, lfx_sg_frag_world_mc_tangent()), dot(dirMC, lfx_sg_frag_world_mc_bitangent()), dot(dirMC, lfx_sg_frag_world_mc_normal())); }");
                sb.AppendLine("vec3 lfx_sg_frag_tangent_normal(vec3 n) { vec3 z = lfx_sg_frag_world_mc_normal(); return lfx_sg_safe_norm(lfx_sg_frag_world_mc_tangent() * n.x + lfx_sg_frag_world_mc_bitangent() * n.y + z * n.z, z); }");
            }
            if (ctx.NeedsFragmentObjBasis)
            {
                sb.AppendLine("vec3 lfx_sg_frag_mc_dir_to_object(vec3 dirMC) { mat3 m = mat3(lfxSgObjXMC, lfxSgObjYMC, lfxSgObjZMC); float d = determinant(m); return (abs(d) > 1e-12 ? inverse(m) : transpose(m)) * dirMC; }");
                sb.AppendLine("vec3 lfx_sg_frag_mc_normal_to_object(vec3 normalMC) { return lfx_sg_safe_norm(transpose(mat3(lfxSgObjXMC, lfxSgObjYMC, lfxSgObjZMC)) * normalMC, vec3(0.0, 0.0, 1.0)); }");
                if (litSurface.HasValue)
                    sb.AppendLine("vec3 lfx_sg_frag_object_normal(vec3 n) { mat3 m = mat3(lfxSgObjXMC, lfxSgObjYMC, lfxSgObjZMC); vec3 fallback = lfx_sg_frag_world_mc_normal(); return abs(determinant(m)) > 1e-12 ? lfx_sg_safe_norm(transpose(inverse(m)) * n, fallback) : fallback; }");
            }
            sb.AppendLine("#if LFX_SUBSTRATE_DUAL_SOURCE && !LFX_PARTICLE_MOTION");
            sb.AppendLine("layout(location = 0, index = 0) out vec4 fragColor;");
            sb.AppendLine("layout(location = 0, index = 1) out vec4 lfxDualTransmittance;");
            sb.AppendLine("#else");
            sb.AppendLine("out vec4 fragColor;");
            sb.AppendLine("vec4 lfxDualTransmittance;");
            sb.AppendLine("#endif");
            AppendUsedHelpers(sb, ctx.FragmentHelpers);
            AppendGradientFunctions(sb, ctx.FragmentGradientFunctions);
            sb.AppendLine("void main() {");
            if (ctx.CullFront) sb.AppendLine("    if (gl_FrontFacing) discard;");
            sb.AppendLine($"    float sgAlpha = {alpha.Code};");
            if (alphaClip) sb.AppendLine($"    if (sgAlpha < {clipThreshold.Code}) discard;");
            bool multiply = ctx.Plan.Blend == "modulate";
            string albedo = multiply && ctx.AlphaModulate
                ? $"mix(vec3(1.0), {baseColor.Code}, clamp(sgAlpha, 0.0, 1.0))" : baseColor.Code;
            if (litSurface is { } surface)
            {
                bool specular = ctx.LitWorkflow == UrpLitWorkflow.Specular;
                string metallic = specular ? "0.0" : surface.Reflectance.Code;
                string specularColor = specular ? surface.Reflectance.Code : "vec3(0.0)";
                string specularWorkflow = specular ? "true" : "false";
                string coatMask = ctx.Plan.ClearCoatSurface ? $"clamp({surface.CoatMask.Code}, 0.0, 1.0)" : "0.0";
                string coatRoughness = ctx.Plan.ClearCoatSurface ? $"1.0 - clamp({surface.CoatSmoothness.Code}, 0.0, 1.0)" : "0.0";
                sb.AppendLine("#if LFX_PARTICLE_SURFACE && !LFX_PARTICLE_MOTION");
                if (ctx.Plan.ClearCoatSurface)
                    sb.AppendLine($"    lfx_write_unity_coated_surface({baseColor.Code}, {metallic}, {specularColor}, {specularWorkflow},");
                else
                    sb.AppendLine($"    lfx_write_unity_urp_surface({baseColor.Code}, {metallic}, {specularColor}, {specularWorkflow},");
                string coatArguments = ctx.Plan.ClearCoatSurface ? $", {coatMask}, {coatRoughness}" : "";
                sb.AppendLine($"        1.0 - clamp({surface.Smoothness.Code}, 0.0, 1.0), {surface.Occlusion.Code}, {surface.WorldNormal.Code}, effectPosition{coatArguments});");
                sb.AppendLine($"    fragColor = lfx_apply_material_fog(vec4({emission.Code}, 1.0));");
                sb.AppendLine("    return;");
                if (ctx.Plan.ForwardLitSurface)
                {
                    sb.AppendLine("#elif LFX_FORWARD_TRANSLUCENCY && !LFX_PARTICLE_MOTION");
                    string diffuseAlpha = ctx.PremultiplyLitDiffuse ? "sgAlpha" : "1.0";
                    string light = specular ? "lfx_unity_specular_forward" : "lfx_unity_metallic_forward";
                    sb.AppendLine("    sgAlpha = clamp(sgAlpha, 0.0, 1.0);");
                    if (ctx.Plan.ClearCoatSurface)
                    {
                        sb.AppendLine($"    vec3 sgLighting = lfx_unity_coated_forward(effectPosition, {surface.WorldNormal.Code}, {albedo},");
                        sb.AppendLine($"        {metallic}, {specularColor}, {specularWorkflow}, {surface.Smoothness.Code}, {surface.Occlusion.Code}, {diffuseAlpha}{coatArguments});");
                    }
                    else
                    {
                        sb.AppendLine($"    vec3 sgLighting = {light}(effectPosition, {surface.WorldNormal.Code}, {albedo},");
                        sb.AppendLine($"        {surface.Reflectance.Code}, {surface.Smoothness.Code}, {surface.Occlusion.Code}, {diffuseAlpha});");
                    }
                    sb.AppendLine($"    fragColor = lfx_apply_material_fog(vec4(sgLighting + ({emission.Code}), sgAlpha));");
                    sb.AppendLine("    return;");
                }
                sb.AppendLine("#elif !LFX_PARTICLE_MOTION");
                string outputAlpha = ctx.Plan.Blend == "opaque" ? "1.0" : "sgAlpha";
                sb.AppendLine($"    fragColor = lfx_apply_material_fog(vec4({emission.Code}, {outputAlpha}));");
                sb.AppendLine("    return;");
                sb.AppendLine("#endif");
            }
            sb.AppendLine($"    vec3 sgColor = ({albedo}) + ({emission.Code});");
            if (ctx.Plan.Blend == "premultiplied")
                sb.AppendLine("    vec4 outColor = lfx_apply_material_fog(vec4(sgColor * sgAlpha, sgAlpha));");
            else
                sb.AppendLine("    vec4 outColor = lfx_apply_material_fog(vec4(sgColor, sgAlpha));");
            if (multiply)
                sb.AppendLine("    fragColor = outColor;");
            else
            {
                sb.AppendLine("    vec3 transmittance = clamp(vec3(1.0) - outColor.rgb, vec3(0.0), vec3(1.0));");
                sb.AppendLine("#if LFX_SUBSTRATE_MODULATION_ONLY && !LFX_PARTICLE_MOTION");
                sb.AppendLine("    fragColor = vec4(transmittance, 1.0);");
                sb.AppendLine("#elif LFX_SUBSTRATE_DUAL_SOURCE && !LFX_PARTICLE_MOTION");
                sb.AppendLine("    fragColor = vec4(outColor.rgb, 0.0);");
                sb.AppendLine("    lfxDualTransmittance = vec4(transmittance, 1.0);");
                sb.AppendLine("#else");
                sb.AppendLine("    fragColor = outColor;");
                sb.AppendLine("#endif");
            }
            sb.AppendLine("}");
            return sb.ToString();
        }

        private static string EmitVertexShader(CompileContext ctx, Expr? vertexPosition, Expr? vertexNormal, Expr? vertexTangent)
        {
            var sb = new StringBuilder();
            sb.AppendLine("#version 330");
            sb.AppendLine("#define LFX_PRESKIN_POSITION 1");
            sb.AppendLine("#define LFX_MATERIAL_INSTANCE_TRANSFORMS 1");
            if (ctx.MaterialView != 0)
                sb.AppendLine("#moj_import <lumenfx:material_view.glsl>");
            foreach (string decl in ctx.SamplerDeclarations) sb.AppendLine(decl);
            sb.AppendLine("layout(std140) uniform EffectFrame { vec4 EffectClock; vec4 MaterialParameters; vec4 EffectTimeData; };");
            sb.AppendLine("vec3 lfxVertexMaterialPosition; vec3 lfxVertexMaterialParticleCenter;");
            sb.AppendLine("vec3 lfxVertexPreSkinnedPosition;");
            sb.AppendLine("#ifdef LFX_MESH_PARTICLE");
            sb.AppendLine("mat3 lfxVertexInstanceLinear; vec3 lfxVertexInstanceCenter; vec3 lfxVertexInstanceInverseScale;");
            sb.AppendLine("#endif");
            bool vertexNeedsFragNormal = ctx.NeedsFragmentNormal || ctx.NeedsFragmentBasis;
            if (ctx.NeedsVertexBasis)
                sb.AppendLine("vec3 lfxVertexMaterialNormal; vec3 lfxVertexMaterialTangent; vec3 lfxVertexMaterialBinormal; float lfxVertexMaterialBasisDeterminant;");
            if (vertexNeedsFragNormal)
                sb.AppendLine("out vec3 effectMaterialVertexNormal;");
            if (ctx.NeedsFragmentBasis)
            {
                sb.AppendLine("out vec3 effectMaterialVertexTangent;");
                sb.AppendLine("out float effectMaterialBasisDeterminant;");
            }
            if (ctx.NeedsFragmentPosOS)
                sb.AppendLine("out vec3 lfxSgFragPosOS;");
            if (ctx.NeedsFragmentPosVS)
                sb.AppendLine("out vec3 lfxSgFragPosVS;");
            if (ctx.NeedsFragmentObjBasis)
            {
                sb.AppendLine("out vec3 lfxSgObjXMC;");
                sb.AppendLine("out vec3 lfxSgObjYMC;");
                sb.AppendLine("out vec3 lfxSgObjZMC;");
            }
            AppendCoordinateHelpers(sb, ctx.MaterialView != 0);
            sb.AppendLine("#ifdef LFX_MESH_PARTICLE");
            if (ctx.ForwardToUp)
            {
                sb.AppendLine("vec3 lfx_sg_mesh_src_to_unity(vec3 s) { return vec3(s.x, -s.y, s.z); }");
                sb.AppendLine("vec3 lfx_sg_unity_to_mesh_src(vec3 u) { return vec3(u.x, -u.y, u.z); }");
            }
            else
            {
                sb.AppendLine("vec3 lfx_sg_mesh_src_to_unity(vec3 s) { return vec3(s.x, s.z, -s.y); }");
                sb.AppendLine("vec3 lfx_sg_unity_to_mesh_src(vec3 u) { return vec3(u.x, -u.z, u.y); }");
            }
            sb.AppendLine("vec3 lfx_sg_object_dir_to_mc(vec3 dirOS) { return (lfxVertexInstanceLinear * lfx_sg_unity_to_mesh_src(dirOS)).xzy; }");
            sb.AppendLine("vec3 lfx_sg_object_normal_to_mc(vec3 normalOS) { mat3 m = lfxVertexInstanceLinear; mat3 cof = mat3(cross(m[1], m[2]), cross(m[2], m[0]), cross(m[0], m[1])); float d = dot(m[0], cof[0]); vec3 nSrc = cof * lfx_sg_unity_to_mesh_src(normalOS); if (d < 0.0) nSrc = -nSrc; return lfx_sg_safe_norm(nSrc.xzy, vec3(0.0, 1.0, 0.0)); }");
            sb.AppendLine("vec3 lfx_sg_mc_dir_to_object(vec3 dirMC) { mat3 m = lfxVertexInstanceLinear; float det = determinant(m); mat3 inv = abs(det) > 1e-12 ? inverse(m) : transpose(m); return lfx_sg_mesh_src_to_unity(inv * dirMC.xzy); }");
            sb.AppendLine("vec3 lfx_sg_mc_normal_to_object(vec3 normalMC) { return lfx_sg_safe_norm(lfx_sg_mesh_src_to_unity(transpose(lfxVertexInstanceLinear) * normalMC.xzy), vec3(0.0, 0.0, 1.0)); }");
            sb.AppendLine("vec3 lfx_sg_vertex_pos_os() { return lfx_sg_mesh_src_to_unity(lfxVertexPreSkinnedPosition) * 0.01; }");
            sb.AppendLine("#else");
            if (ctx.NeedsVertexBasis)
            {
                sb.AppendLine("vec3 lfx_sg_object_dir_to_mc(vec3 dirOS) { return lfxVertexMaterialTangent.xzy * dirOS.x - lfxVertexMaterialBinormal.xzy * dirOS.y + lfxVertexMaterialNormal.xzy * dirOS.z; }");
                sb.AppendLine("vec3 lfx_sg_object_normal_to_mc(vec3 normalOS) { return lfx_sg_safe_norm(lfx_sg_object_dir_to_mc(normalOS), lfxVertexMaterialNormal.xzy); }");
                sb.AppendLine("vec3 lfx_sg_mc_dir_to_object(vec3 dirMC) { return vec3(dot(dirMC, lfxVertexMaterialTangent.xzy), -dot(dirMC, lfxVertexMaterialBinormal.xzy), dot(dirMC, lfxVertexMaterialNormal.xzy)); }");
                sb.AppendLine("vec3 lfx_sg_mc_normal_to_object(vec3 normalMC) { return lfx_sg_safe_norm(lfx_sg_mc_dir_to_object(normalMC), vec3(0.0, 0.0, 1.0)); }");
            }
            else
            {
                sb.AppendLine("vec3 lfx_sg_object_dir_to_mc(vec3 dirOS) { return lfx_sg_mc_to_unity_dir(dirOS); }");
                sb.AppendLine("vec3 lfx_sg_object_normal_to_mc(vec3 normalOS) { return lfx_sg_safe_norm(lfx_sg_mc_to_unity_dir(normalOS), vec3(0.0, 1.0, 0.0)); }");
                sb.AppendLine("vec3 lfx_sg_mc_dir_to_object(vec3 dirMC) { return lfx_sg_mc_to_unity_dir(dirMC); }");
                sb.AppendLine("vec3 lfx_sg_mc_normal_to_object(vec3 normalMC) { return lfx_sg_safe_norm(lfx_sg_mc_to_unity_dir(normalMC), vec3(0.0, 0.0, 1.0)); }");
            }
            sb.AppendLine("vec3 lfx_sg_vertex_pos_os() { return lfx_sg_mc_dir_to_object(lfxVertexMaterialPosition - lfxVertexPreSkinnedPosition.xzy * 0.01); }");
            sb.AppendLine("#endif");
            if (ctx.NeedsVertexBasis)
                sb.AppendLine("vec3 lfx_sg_world_to_tangent(vec3 dirMC) { return vec3(dot(dirMC, lfxVertexMaterialTangent.xzy), dot(dirMC, lfxVertexMaterialBinormal.xzy), dot(dirMC, lfxVertexMaterialNormal.xzy)); }");
            AppendUsedHelpers(sb, ctx.VertexHelpers);
            AppendGradientFunctions(sb, ctx.VertexGradientFunctions);
            sb.AppendLine("vec3 lfx_sg_compute_vertex_offset(vec3 position, vec3 particleCenter, out vec3 outPosOS) {");
            sb.AppendLine("    lfxVertexMaterialPosition = position; lfxVertexMaterialParticleCenter = particleCenter;");
            sb.AppendLine("    vec3 origPosOS = lfx_sg_vertex_pos_os();");
            if (vertexPosition.HasValue)
            {
                sb.AppendLine($"    vec3 sgPos = {vertexPosition.Value.Code};");
                sb.AppendLine("    outPosOS = sgPos;");
                sb.AppendLine("    return lfx_sg_object_dir_to_mc(sgPos - origPosOS);");
            }
            else
            {
                sb.AppendLine("    outPosOS = origPosOS;");
                sb.AppendLine("    return vec3(0.0);");
            }
            sb.AppendLine("}");
            sb.AppendLine("vec3 lfx_material_vertex_current(vec3 position, vec3 particleCenter) {");
            sb.AppendLine("    vec3 outPosOS;");
            sb.AppendLine("    vec3 dispMC = lfx_sg_compute_vertex_offset(position, particleCenter, outPosOS);");
            if (ctx.NeedsFragmentPosOS)
                sb.AppendLine("    lfxSgFragPosOS = outPosOS;");
            if (ctx.NeedsFragmentPosVS)
                sb.AppendLine("    lfxSgFragPosVS = (ModelViewMat * vec4(position + dispMC, 1.0)).xyz;");
            if (ctx.NeedsFragmentObjBasis)
            {
                sb.AppendLine("    lfxSgObjXMC = lfx_sg_object_dir_to_mc(vec3(1.0, 0.0, 0.0));");
                sb.AppendLine("    lfxSgObjYMC = lfx_sg_object_dir_to_mc(vec3(0.0, 1.0, 0.0));");
                sb.AppendLine("    lfxSgObjZMC = lfx_sg_object_dir_to_mc(vec3(0.0, 0.0, 1.0));");
            }
            if (vertexNormal.HasValue)
                sb.AppendLine($"    vec3 sgNormalMC = lfx_sg_object_normal_to_mc({vertexNormal.Value.Code});");
            else if (vertexNeedsFragNormal)
                sb.AppendLine("    vec3 sgNormalMC = lfxVertexMaterialNormal.xzy;");
            if (vertexTangent.HasValue)
                sb.AppendLine($"    vec3 sgTangentMC = lfx_sg_safe_norm(lfx_sg_object_dir_to_mc({vertexTangent.Value.Code}), lfxVertexMaterialTangent.xzy);");
            else if (ctx.NeedsFragmentBasis)
                sb.AppendLine("    vec3 sgTangentMC = lfxVertexMaterialTangent.xzy;");
            if (vertexNeedsFragNormal)
                sb.AppendLine("    effectMaterialVertexNormal = sgNormalMC;");
            if (ctx.NeedsFragmentBasis)
            {
                sb.AppendLine("    effectMaterialVertexTangent = sgTangentMC;");
                sb.AppendLine("    effectMaterialBasisDeterminant = lfxVertexMaterialBasisDeterminant;");
            }
            sb.AppendLine("    return dispMC;");
            sb.AppendLine("}");
            sb.AppendLine("#if LFX_PARTICLE_MOTION");
            sb.AppendLine("vec3 lfx_material_vertex_previous(vec3 position, vec3 particleCenter) { vec3 unused; return lfx_sg_compute_vertex_offset(position, particleCenter, unused); }");
            sb.AppendLine("#endif");
            return sb.ToString();
        }

        private static void AppendCoordinateHelpers(StringBuilder sb, bool hasMaterialView)
        {
            sb.AppendLine("vec3 lfx_sg_safe_norm(vec3 v, vec3 fb) { float l2 = dot(v, v); return l2 > 1e-12 ? v * inversesqrt(l2) : fb; }");
            sb.AppendLine("vec3 lfx_sg_mc_to_unity_pos(vec3 p) { return vec3(p.x, p.y, -p.z); }");
            sb.AppendLine("vec3 lfx_sg_mc_to_unity_dir(vec3 d) { return vec3(d.x, d.y, -d.z); }");
            if (hasMaterialView)
                sb.AppendLine("vec3 lfx_sg_abs_world_cam_mc() { return ((MaterialWorldTile.xyz * 2097152.0 - MaterialPreViewTranslation.xyz).xzy) * 0.01; }");
        }

        private static void AppendUsedHelpers(StringBuilder sb, HashSet<string> used)
        {
            if (used.Contains("unpack_normal_rgb"))
                sb.AppendLine("vec4 lfx_sg_unpack_normal_rgb(vec4 c) { vec3 n = c.rgb * 2.0 - 1.0; float l2 = dot(n, n); return vec4(l2 > 1e-12 ? n * inversesqrt(l2) : vec3(0.0, 0.0, 1.0), c.a); }");
            if (used.Contains("unpack_normal_dxt5nm"))
                sb.AppendLine("vec4 lfx_sg_unpack_normal_dxt5nm(vec4 c) { vec2 xy = c.ag * 2.0 - 1.0; float z = sqrt(max(1.0 - dot(xy, xy), 0.0)); return vec4(xy, z, c.a); }");
            if (used.Contains("unpack_normal_bc5"))
                sb.AppendLine("vec4 lfx_sg_unpack_normal_bc5(vec4 c) { vec2 xy = c.rg * 2.0 - 1.0; float z = sqrt(max(1.0 - dot(xy, xy), 0.0)); return vec4(xy, z, c.a); }");
            if (used.Contains("screen_uv"))
                sb.AppendLine("vec2 lfx_sg_screen_uv() { vec2 uv = gl_FragCoord.xy * vec2(Geometry.w, CameraUp.w); return vec2(uv.x, CameraRight.w > 0.5 ? uv.y : 1.0 - uv.y); }");
            if (used.Contains("rotate"))
                sb.AppendLine("vec2 lfx_sg_rotate(vec2 uv, vec2 c, float r) { uv -= c; float s = sin(r), co = cos(r); return vec2(uv.x * co - uv.y * s, uv.x * s + uv.y * co) + c; }");
            if (used.Contains("flipbook"))
            {
                sb.AppendLine("vec2 lfx_sg_flipbook(vec2 uv, float width, float height, float tile, bool invertX, bool invertY) {");
                sb.AppendLine("    float tileCount = width * height;");
                sb.AppendLine("    float biasedTile = tile + 0.00001;");
                sb.AppendLine("    float frame = floor(biasedTile - tileCount * trunc(biasedTile / tileCount));");
                sb.AppendLine("    vec2 tileSize = 1.0 / vec2(width, height);");
                sb.AppendLine("    float row = floor((frame + 0.5) * tileSize.x);");
                sb.AppendLine("    float column = frame - width * row;");
                sb.AppendLine("    vec2 offset = vec2(invertX ? width - (column + 1.0) : column, invertY ? height - (row + 1.0) : row);");
                sb.AppendLine("    return (uv + offset) * tileSize;");
                sb.AppendLine("}");
            }
            if (used.Contains("polar"))
                sb.AppendLine("vec2 lfx_sg_polar(vec2 uv, vec2 c, float rs, float ls) { vec2 d = uv - c; return vec2(length(d) * 2.0 * rs, atan(d.x, d.y) * (1.0 / 6.28318530718) * ls); }");
            if (used.Contains("twirl"))
                sb.AppendLine("vec2 lfx_sg_twirl(vec2 uv, vec2 c, float s, vec2 o) { vec2 d = uv - c; float a = s * length(d); vec2 p = vec2(d.x * cos(a) - d.y * sin(a), d.x * sin(a) + d.y * cos(a)); return p + c + o; }");
            if (used.Contains("fmod"))
            {
                sb.AppendLine("float lfx_sg_fmod(float a, float b) { return a - b * trunc(a / b); }");
                sb.AppendLine("vec2 lfx_sg_fmod(vec2 a, vec2 b) { return a - b * trunc(a / b); }");
                sb.AppendLine("vec3 lfx_sg_fmod(vec3 a, vec3 b) { return a - b * trunc(a / b); }");
                sb.AppendLine("vec4 lfx_sg_fmod(vec4 a, vec4 b) { return a - b * trunc(a / b); }");
            }
            if (used.Contains("radial_shear"))
                sb.AppendLine("vec2 lfx_sg_radial_shear(vec2 uv, vec2 c, vec2 s, vec2 o) { vec2 d = uv - c; float r2 = dot(d, d); vec2 dd = d * r2; return uv + vec2(dd.y, -dd.x) * s + o; }");
            if (used.Contains("spherize"))
                sb.AppendLine("vec2 lfx_sg_spherize(vec2 uv, vec2 c, float s, vec2 o) { vec2 d = uv - c; float r2 = dot(d, d); float r4 = r2 * r2; return uv + d * (r4 * s) + o; }");
            if (used.Contains("simple_noise"))
            {
                sb.AppendLine("float lfx_sg_sn_rand(vec2 uv) { return fract(sin(dot(uv, vec2(12.9898, 78.233))) * 43758.5453); }");
                sb.AppendLine("float lfx_sg_sn_value(vec2 uv) { vec2 i = floor(uv), f = fract(uv); f = f * f * (3.0 - 2.0 * f); float a = lfx_sg_sn_rand(i), b = lfx_sg_sn_rand(i + vec2(1.0, 0.0)), c = lfx_sg_sn_rand(i + vec2(0.0, 1.0)), d = lfx_sg_sn_rand(i + vec2(1.0, 1.0)); return mix(mix(a, b, f.x), mix(c, d, f.x), f.y); }");
                sb.AppendLine("float lfx_sg_simple_noise(vec2 uv, float scale) { float t = 0.0; t += lfx_sg_sn_value(uv * scale) * 0.125; t += lfx_sg_sn_value(uv * scale * 0.5) * 0.25; t += lfx_sg_sn_value(uv * scale * 0.25) * 0.5; return t; }");
            }
            if (used.Contains("gradient_noise"))
            {
                sb.AppendLine("vec2 lfx_sg_gn_dir(vec2 p) { p = mod(p, 289.0); float x = mod((34.0 * p.x + 1.0) * p.x, 289.0) + p.y; x = mod((34.0 * x + 1.0) * x, 289.0); x = fract(x / 41.0) * 2.0 - 1.0; return normalize(vec2(x - floor(x + 0.5), abs(x) - 0.5)); }");
                sb.AppendLine("float lfx_sg_gradient_noise(vec2 p, float scale) { vec2 uv = p * scale; vec2 ip = floor(uv), fp = fract(uv); float d00 = dot(lfx_sg_gn_dir(ip), fp); float d01 = dot(lfx_sg_gn_dir(ip + vec2(0.0, 1.0)), fp - vec2(0.0, 1.0)); float d10 = dot(lfx_sg_gn_dir(ip + vec2(1.0, 0.0)), fp - vec2(1.0, 0.0)); float d11 = dot(lfx_sg_gn_dir(ip + vec2(1.0, 1.0)), fp - vec2(1.0, 1.0)); fp = fp * fp * fp * (fp * (fp * 6.0 - 15.0) + 10.0); return mix(mix(d00, d01, fp.y), mix(d10, d11, fp.y), fp.x) + 0.5; }");
            }
            if (used.Contains("voronoi"))
            {
                sb.AppendLine("vec2 lfx_sg_vor_rand(vec2 uv, float off) { mat2 m = mat2(15.27, 47.63, 99.41, 89.98); uv = fract(sin(uv * m) * 46839.32); return vec2(sin(uv.y * off) * 0.5 + 0.5, cos(uv.x * off) * 0.5 + 0.5); }");
                sb.AppendLine("float lfx_sg_voronoi(vec2 uv, float off, float dens) { vec2 g = floor(uv * dens), f = fract(uv * dens); float res = 8.0; for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) { vec2 lat = vec2(float(x), float(y)); vec2 pt = lfx_sg_vor_rand(g + lat, off); res = min(res, distance(lat + pt, f)); } return res; }");
                sb.AppendLine("float lfx_sg_voronoi_cells(vec2 uv, float off, float dens) { vec2 g = floor(uv * dens), f = fract(uv * dens); float res = 8.0, cell = 0.0; for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) { vec2 lat = vec2(float(x), float(y)); vec2 pt = lfx_sg_vor_rand(g + lat, off); float d = distance(lat + pt, f); if (d < res) { res = d; cell = pt.x; } } return cell; }");
            }
            if (used.Contains("colorspace") || used.Contains("gradient_transfer"))
            {
                sb.AppendLine("vec3 lfx_sg_srgb_to_linear(vec3 c) { vec3 lo = c / 12.92; vec3 hi = pow(max((c + vec3(0.055)) / 1.055, vec3(0.0)), vec3(2.4)); return mix(hi, lo, lessThanEqual(c, vec3(0.04045))); }");
                sb.AppendLine("vec3 lfx_sg_linear_to_srgb(vec3 c, float lowSlope) { vec3 lo = c * lowSlope; vec3 hi = 1.055 * pow(max(c, vec3(0.0)), vec3(1.0 / 2.4)) - vec3(0.055); return mix(hi, lo, lessThanEqual(c, vec3(0.0031308))); }");
            }
            if (used.Contains("colorspace"))
            {
                sb.AppendLine("vec3 lfx_sg_rgb_to_hsv(vec3 c) { vec4 K = vec4(0.0, -1.0/3.0, 2.0/3.0, -1.0); vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g)); vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r)); float d = q.x - min(q.w, q.y); float e = 1e-10; return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x); }");
                sb.AppendLine("vec3 lfx_sg_hsv_to_rgb(vec3 c) { vec4 K = vec4(1.0, 2.0/3.0, 1.0/3.0, 3.0); vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www); return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y); }");
                sb.AppendLine("vec3 lfx_sg_linear_to_srgb(vec3 c) { return lfx_sg_linear_to_srgb(c, 12.92); }");
                sb.AppendLine("vec3 lfx_sg_convert_color(vec3 c, int fromMode, int toMode) { if (fromMode == toMode) return c; vec3 srgb = fromMode == 1 ? lfx_sg_linear_to_srgb(c) : (fromMode == 2 ? lfx_sg_hsv_to_rgb(c) : c); return toMode == 1 ? lfx_sg_srgb_to_linear(srgb) : (toMode == 2 ? lfx_sg_rgb_to_hsv(srgb) : srgb); }");
                sb.AppendLine("vec3 lfx_sg_hue(vec3 c, float off, float scale) { vec3 hsv = lfx_sg_rgb_to_hsv(c); hsv.x = fract(hsv.x + off * scale); return lfx_sg_hsv_to_rgb(hsv); }");
            }
            if (used.Contains("gradient_oklab")) AppendGradientOklabHelpers(sb);
        }

        private static Expr Binary(CompileContext ctx, Dictionary<string, object> node, int aSlot, int bSlot, string op)
        {
            Expr a = Input(ctx, node, aSlot);
            Expr b = Input(ctx, node, bSlot);
            int w = Math.Max(a.Width, b.Width);
            return new Expr($"({Cast(a, w).Code} {op} {Cast(b, w).Code})", w);
        }

        private static Expr BinaryFn(CompileContext ctx, Dictionary<string, object> node, int aSlot, int bSlot, string fn)
        {
            Expr a = Input(ctx, node, aSlot);
            Expr b = Input(ctx, node, bSlot);
            int w = Math.Max(a.Width, b.Width);
            return new Expr($"{fn}({Cast(a, w).Code}, {Cast(b, w).Code})", w);
        }

        private static Expr Unary(CompileContext ctx, Dictionary<string, object> node, int slot, string op)
        {
            Expr a = Input(ctx, node, slot);
            return new Expr($"({op}({a.Code}))", a.Width);
        }

        private static Expr UnaryFn(CompileContext ctx, Dictionary<string, object> node, int slot, string fn)
        {
            Expr a = Input(ctx, node, slot);
            return new Expr($"{fn}({a.Code})", a.Width);
        }

        private static Expr Cast(Expr v, int width)
        {
            if (v.Width == width) return v;
            if (width == 1) return new Expr($"({v.Code}).x", 1);
            if (v.Width == 1) return new Expr($"vec{width}({v.Code})", width);
            if (v.Width > width) return new Expr($"({v.Code}).{"xyzw".Substring(0, width)}", width);
            return new Expr($"vec{width}({v.Code}" + (width - v.Width == 1 ? ", 0.0" : width - v.Width == 2 ? ", 0.0, 0.0" : ", 0.0, 0.0, 0.0") + ")", width);
        }

        private static Expr VecLiteral(params float[] vals)
        {
            if (vals.Length == 1) return new Expr(Num(vals[0]), 1);
            string[] parts = new string[vals.Length];
            for (int i = 0; i < vals.Length; i++) parts[i] = Num(vals[i]);
            return new Expr($"vec{vals.Length}({string.Join(", ", parts)})", vals.Length);
        }

        private static Expr EvaluateUV(CompileContext ctx, int channel)
        {
            int ch = Mathf.Clamp(channel, 0, 7);
            if (ctx.InVertexStage)
            {
                ctx.VertexUv4Mask |= 1 << ch;
                return new Expr($"lfxSgVertexUV{ch}", 4);
            }
            ctx.FragmentUv4Mask |= 1 << ch;
            return new Expr($"lfxSgUV{ch}", 4);
        }

        private static Expr EvaluatePosition(CompileContext ctx, int space)
        {
            if (space == 4) ctx.MaterialView |= 8;
            if (ctx.InVertexStage)
            {
                switch (space)
                {
                    case 0:
                        ctx.NeedsVertexBasis = true;
                        return new Expr("lfx_sg_vertex_pos_os()", 3);
                    case 1:
                        return new Expr("(ModelViewMat * vec4(lfxVertexMaterialPosition, 1.0)).xyz", 3);
                    case 3:
                        ctx.NeedsVertexBasis = true;
                        return new Expr("lfx_sg_world_to_tangent(lfxVertexMaterialPosition)", 3);
                    case 4:
                        return new Expr("lfx_sg_mc_to_unity_pos(lfxVertexMaterialPosition + lfx_sg_abs_world_cam_mc())", 3);
                    default:
                        return new Expr("lfx_sg_mc_to_unity_pos(lfxVertexMaterialPosition)", 3);
                }
            }
            switch (space)
            {
                case 0:
                    ctx.NeedsVertexBasis = true;
                    ctx.NeedsFragmentPosOS = true;
                    return new Expr("lfxSgFragPosOS", 3);
                case 1:
                    ctx.NeedsFragmentPosVS = true;
                    return new Expr("lfxSgFragPosVS", 3);
                case 3:
                    ctx.NeedsVertexBasis = true;
                    ctx.NeedsFragmentNormal = true;
                    ctx.NeedsFragmentBasis = true;
                    return new Expr("lfx_sg_frag_world_to_tangent(effectPosition)", 3);
                case 4:
                    return new Expr("lfx_sg_mc_to_unity_pos(effectPosition + lfx_sg_abs_world_cam_mc())", 3);
                default:
                    return new Expr("lfx_sg_mc_to_unity_pos(effectPosition)", 3);
            }
        }

        private static Expr EvaluateScreenPosition(CompileContext ctx)
        {
            if (ctx.InVertexStage)
            {
                ctx.Issues.Add(new ExportIssue(ctx.ContextLabel, "ScreenPositionNode is not supported in Shader Graph VertexDescription.Position"));
                return new Expr("vec4(0.0)", 4);
            }
            ctx.AddHelper("screen_uv");
            return new Expr("vec4(lfx_sg_screen_uv(), gl_FragCoord.z, gl_FragCoord.w)", 4);
        }

        private static Expr EvaluateNormalKind(CompileContext ctx, int kind, int space)
        {
            if (space == 3)
            {
                return kind == 0 ? new Expr("vec3(1.0, 0.0, 0.0)", 3)
                    : kind == 1 ? new Expr("vec3(0.0, 1.0, 0.0)", 3)
                    : new Expr("vec3(0.0, 0.0, 1.0)", 3);
            }
            ctx.NeedsVertexBasis = true;
            if (ctx.InVertexStage)
            {
                string mcExpr = kind == 0 ? "lfx_sg_safe_norm(lfxVertexMaterialTangent.xzy, vec3(1.0, 0.0, 0.0))"
                    : kind == 1 ? "lfx_sg_safe_norm(lfxVertexMaterialBinormal.xzy, vec3(0.0, 0.0, 1.0))"
                    : "lfx_sg_safe_norm(lfxVertexMaterialNormal.xzy, vec3(0.0, 1.0, 0.0))";
                return space switch
                {
                    0 => kind == 2 ? new Expr($"lfx_sg_mc_normal_to_object({mcExpr})", 3) : new Expr($"normalize(lfx_sg_mc_dir_to_object({mcExpr}))", 3),
                    1 => new Expr($"normalize(mat3(ModelViewMat) * {mcExpr})", 3),
                    _ => new Expr($"lfx_sg_mc_to_unity_dir({mcExpr})", 3)
                };
            }
            ctx.NeedsFragmentNormal = true;
            if (kind != 2) ctx.NeedsFragmentBasis = true;
            string fragMc = kind == 0 ? "lfx_sg_frag_world_mc_tangent()"
                : kind == 1 ? "lfx_sg_frag_world_mc_bitangent()"
                : "lfx_sg_frag_world_mc_normal()";
            switch (space)
            {
                case 0:
                    ctx.NeedsFragmentObjBasis = true;
                    return kind == 2
                        ? new Expr($"lfx_sg_frag_mc_normal_to_object({fragMc})", 3)
                        : new Expr($"normalize(lfx_sg_frag_mc_dir_to_object({fragMc}))", 3);
                case 1:
                    ctx.FragmentNeedsModelView = true;
                    return new Expr($"normalize(mat3(ModelViewMat) * {fragMc})", 3);
                default:
                    return new Expr($"lfx_sg_mc_to_unity_dir({fragMc})", 3);
            }
        }

        private static Expr EvaluateViewDirection(CompileContext ctx, int space)
        {
            if (ctx.InVertexStage)
            {
                if (space is 0 or 3) ctx.NeedsVertexBasis = true;
                return space switch
                {
                    0 => new Expr("normalize(lfx_sg_mc_dir_to_object(-lfxVertexMaterialPosition))", 3),
                    1 => new Expr("normalize(-(ModelViewMat * vec4(lfxVertexMaterialPosition, 1.0)).xyz)", 3),
                    3 => new Expr("normalize(lfx_sg_world_to_tangent(-lfxVertexMaterialPosition))", 3),
                    _ => new Expr("normalize(lfx_sg_mc_to_unity_dir(-lfxVertexMaterialPosition))", 3)
                };
            }
            switch (space)
            {
                case 0:
                    ctx.NeedsVertexBasis = true;
                    ctx.NeedsFragmentObjBasis = true;
                    return new Expr("normalize(lfx_sg_frag_mc_dir_to_object(-effectPosition))", 3);
                case 1:
                    ctx.NeedsFragmentPosVS = true;
                    return new Expr("normalize(-lfxSgFragPosVS)", 3);
                case 3:
                    ctx.NeedsVertexBasis = true;
                    ctx.NeedsFragmentNormal = true;
                    ctx.NeedsFragmentBasis = true;
                    return new Expr("normalize(lfx_sg_frag_world_to_tangent(-effectPosition))", 3);
                default:
                    return new Expr("normalize(lfx_sg_mc_to_unity_dir(-effectPosition))", 3);
            }
        }

        private static int CountErrors(List<ExportIssue> issues)
        {
            int count = 0;
            for (int i = 0; i < issues.Count; i++)
                if (issues[i].Severity == IssueSeverity.Error) count++;
            return count;
        }

        private static int GetConversionMode(Dictionary<string, object> node, string convKey, string legacyKey)
        {
            if (node.TryGetValue("m_Conversion", out object raw) && raw is Dictionary<string, object> conv && conv.ContainsKey(convKey))
                return GetInt(conv, convKey);
            return GetInt(node, legacyKey);
        }

        private static Expr ReadSlotDefault(CompileContext ctx, Dictionary<string, object> slot)
        {
            string type = GetString(slot, "m_Type");
            if (type.EndsWith(".GradientInputMaterialSlot", StringComparison.Ordinal)) return new Expr(ReadGradientValue(ctx, slot));
            if (type.Contains("UVMaterialSlot")) return EvaluateUV(ctx, GetInt(slot, "m_Channel"));
            if (type.Contains("PositionMaterialSlot")) return EvaluatePosition(ctx, GetInt(slot, "m_Space"));
            if (type.Contains("NormalMaterialSlot")) return EvaluateNormalKind(ctx, 2, GetInt(slot, "m_Space"));
            if (type.Contains("TangentMaterialSlot")) return EvaluateNormalKind(ctx, 0, GetInt(slot, "m_Space"));
            if (type.Contains("BitangentMaterialSlot")) return EvaluateNormalKind(ctx, 1, GetInt(slot, "m_Space"));
            if (type.Contains("ViewDirectionMaterialSlot")) return EvaluateViewDirection(ctx, GetInt(slot, "m_Space"));
            if (type.Contains("ScreenPositionMaterialSlot")) return EvaluateScreenPosition(ctx);
            if (type.Contains("VertexColorMaterialSlot")) return new Expr("effectColor", 4);
            if (type.EndsWith(".ColorRGBMaterialSlot", StringComparison.Ordinal))
            {
                float red = VecComponent(slot, "m_Value", "x");
                float green = VecComponent(slot, "m_Value", "y");
                float blue = VecComponent(slot, "m_Value", "z");
                return GetInt(slot, "m_ColorMode") == 0 ? DefaultSrgbColor(red, green, blue) : VecLiteral(red, green, blue);
            }
            if (type.Contains("Vector1") || type.Contains("Boolean")) return new Expr(Num(GetFloat(slot, "m_Value")), 1);
            if (type.Contains("Vector2")) return VecLiteral(VecComponent(slot, "m_Value", "x"), VecComponent(slot, "m_Value", "y"));
            if (type.Contains("Vector3")) return VecLiteral(VecComponent(slot, "m_Value", "x"), VecComponent(slot, "m_Value", "y"), VecComponent(slot, "m_Value", "z"));
            if (type.Contains("Vector4")) return VecLiteral(VecComponent(slot, "m_Value", "x"), VecComponent(slot, "m_Value", "y"), VecComponent(slot, "m_Value", "z"), VecComponent(slot, "m_Value", "w"));
            if (type.Contains("Color")) return VecLiteral(VecComponent(slot, "m_Value", "r"), VecComponent(slot, "m_Value", "g"), VecComponent(slot, "m_Value", "b"), VecComponent(slot, "m_Value", "a", 1f));
            return new Expr("0.0", 1);
        }

        private static Expr DefaultSrgbColor(float red, float green, float blue)
        {
            if (QualitySettings.activeColorSpace != ColorSpace.Linear) return VecLiteral(red, green, blue);
            Color linear = new Color(red, green, blue).linear;
            return VecLiteral(linear.r, linear.g, linear.b);
        }

        private static Dictionary<string, object> FindSlot(CompileContext ctx, Dictionary<string, object> node, int slotId)
        {
            foreach (Dictionary<string, object> s in ResolveRefList(ctx, node, "m_Slots"))
                if (GetInt(s, "m_Id") == slotId) return s;
            return null;
        }

        private static List<Dictionary<string, object>> ResolveRefList(CompileContext ctx, Dictionary<string, object> owner, string key)
        {
            var list = new List<Dictionary<string, object>>();
            object raw;
            if (!owner.TryGetValue(key, out raw)) return list;
            var arr = raw as List<object>;
            if (arr == null) return list;
            foreach (object item in arr)
            {
                var d = item as Dictionary<string, object>;
                if (d == null) continue;
                string id = GetString(d, "m_Id");
                Dictionary<string, object> resolved;
                if (!string.IsNullOrEmpty(id) && ctx.ObjectsById.TryGetValue(id, out resolved)) list.Add(resolved);
                else list.Add(d);
            }
            return list;
        }

        private static string RefId(Dictionary<string, object> owner, string key)
        {
            object raw;
            if (!owner.TryGetValue(key, out raw)) return "";
            var d = raw as Dictionary<string, object>;
            return d == null ? "" : GetString(d, "m_Id");
        }

        private static string GetString(Dictionary<string, object> dict, string key)
        {
            object v;
            return dict.TryGetValue(key, out v) && v != null ? v as string ?? Convert.ToString(v, CultureInfo.InvariantCulture) : "";
        }

        private static int GetInt(Dictionary<string, object> dict, string key)
        {
            object v;
            return dict.TryGetValue(key, out v) && v is double ? (int)Math.Round((double)v) : 0;
        }

        private static float GetFloat(Dictionary<string, object> dict, string key, float fallback = 0f)
        {
            object v;
            return dict.TryGetValue(key, out v) && v is double ? (float)(double)v : fallback;
        }

        private static bool GetBool(Dictionary<string, object> dict, string key, bool fallback = false)
        {
            object v;
            return dict.TryGetValue(key, out v) && v is bool ? (bool)v : fallback;
        }

        private static float VecComponent(Dictionary<string, object> dict, string vecKey, string comp, float fallback = 0f)
        {
            object v;
            if (!dict.TryGetValue(vecKey, out v)) return fallback;
            var sub = v as Dictionary<string, object>;
            return sub == null ? fallback : GetFloat(sub, comp, fallback);
        }

        private static string Num(float value)
        {
            string s = value.ToString("0.0######", CultureInfo.InvariantCulture);
            return s.IndexOf('.') >= 0 ? s : s + ".0";
        }
    }
}
#endif
