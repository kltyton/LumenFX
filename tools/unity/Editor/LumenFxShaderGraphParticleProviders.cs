#if UNITY_EDITOR
using System.Collections.Generic;
using UnityEditor;

namespace LumenFx.UnityExport
{
    internal static partial class LumenFxShaderGraphCompiler
    {
        private static Expr CompileParticleProvider(CompileContext ctx, Dictionary<string, object> node, int outputSlot)
        {
            var provider = ParticleProviderData(node);
            string key = provider != null ? GetString(provider, "m_providerKey") : "";
            string asset = provider != null ? AssetDatabase.GUIDToAssetPath(GetString(provider, "m_sourceAssetId")) : "";
            string nodeId = GetString(node, "m_ObjectId");
            const string shaderLibrary = "Packages/com.unity.render-pipelines.core/ShaderLibrary/";
            if (asset == shaderLibrary + "Particles.hlsl")
            {
                if (key == "Core.Particles.GetParticleColor" && outputSlot == 0
                    && (!ctx.MeshGpuInstancing || !ctx.IncomingEdges.ContainsKey((nodeId, 1))))
                    return InputWidth(ctx, node, 1, 4);
                if (key == "Core.Particles.GetParticleTexcoords" && outputSlot == 1
                    && (!ctx.MeshGpuInstancing || !ctx.IncomingEdges.ContainsKey((nodeId, 2))))
                    return InputWidth(ctx, node, 2, 2);
            }
            else if (asset == shaderLibrary + "ParticlesInstancing.hlsl" && key == "Core.Particles.Transforms")
            {
                if ((outputSlot == 2 || outputSlot == 4 || outputSlot == 6)
                    && (!ctx.MeshGpuInstancing || IsDirectParticleTransform(ctx, nodeId, outputSlot)))
                    return InputWidth(ctx, node, outputSlot - 1, 3);
            }
            ctx.Issues.Add(new ExportIssue(ctx.ContextLabel, "Unsupported Shader Graph particle provider or stream configuration '" +
                (string.IsNullOrEmpty(key) ? GetString(node, "m_Name") : key) + "'"));
            return new Expr("0.0", 1);
        }

        private static Dictionary<string, object> ParticleProviderData(Dictionary<string, object> node)
        {
            if (!node.TryGetValue("m_provider", out object rawProvider) || !(rawProvider is Dictionary<string, object> provider)
                || !provider.ContainsKey("rid") || !node.TryGetValue("references", out object rawReferences)
                || !(rawReferences is Dictionary<string, object> references)
                || !references.TryGetValue("RefIds", out object rawIds) || !(rawIds is List<object> ids))
                return null;

            string id = GetString(provider, "rid");
            foreach (object entry in ids)
            {
                if (!(entry is Dictionary<string, object> reference) || GetString(reference, "rid") != id
                    || !reference.TryGetValue("type", out object rawType) || !(rawType is Dictionary<string, object> type)
                    || GetString(type, "class") != "ReflectedFunctionProvider"
                    || GetString(type, "ns") != "Unity.GraphAuthoring.Editor.ProviderSystem")
                    continue;
                return reference.TryGetValue("data", out object data) ? data as Dictionary<string, object> : null;
            }
            return null;
        }

        private static bool IsDirectParticleTransform(CompileContext ctx, string nodeId, int outputSlot)
        {
            // The host vertex program applies the particle transform after evaluating the object-space blocks.
            if (!ctx.InVertexStage) return false;
            string expectedBlock = outputSlot == 2 ? "VertexDescription.Position" :
                outputSlot == 4 ? "VertexDescription.Normal" : "VertexDescription.Tangent";
            foreach (var edge in ctx.IncomingEdges)
            {
                if (edge.Value != (nodeId, outputSlot)) continue;
                if (!ctx.ObjectsById.TryGetValue(edge.Key.NodeId, out var target)
                    || GetString(target, "m_SerializedDescriptor") != expectedBlock) return false;
            }
            return true;
        }
    }
}
#endif
