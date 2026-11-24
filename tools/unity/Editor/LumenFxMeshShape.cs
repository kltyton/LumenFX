#if UNITY_EDITOR
using System;
using System.Collections.Generic;
using UnityEngine;
using UnityEngine.Rendering;

namespace LumenFx.UnityExport
{
    internal static class LumenFxMeshShape
    {
        internal static Dictionary<string, object> Build(ParticleSystem system, AxisMapper mapper,
            ExportSettings export, LumenFxPlan plan, string path, string context, List<ExportIssue> issues)
        {
            var shape = system.shape;
            if (shape.shapeType == ParticleSystemShapeType.Mesh)
                return BuildGeometry(shape, shape.mesh, false, mapper, export, plan, path, context, issues);
            if (shape.shapeType == ParticleSystemShapeType.MeshRenderer)
            {
                var renderer = shape.meshRenderer;
                var mesh = renderer == null ? null : renderer.GetComponent<MeshFilter>()?.sharedMesh;
                if (mesh == null)
                {
                    issues.Add(new ExportIssue(context, "MeshRenderer emission requires a MeshFilter.sharedMesh"));
                    return null;
                }
                return BuildGeometry(shape, mesh, false, mapper, export, plan, path, context, issues,
                    liveGeometry: true, sectionTints: MaterialTints(shape, renderer, mesh),
                    geometryTransform: RendererToShape(system, renderer.transform));
            }
            if (shape.shapeType == ParticleSystemShapeType.SkinnedMeshRenderer)
            {
                var renderer = shape.skinnedMeshRenderer;
                if (renderer == null || renderer.sharedMesh == null)
                {
                    issues.Add(new ExportIssue(context, "SkinnedMeshRenderer emission requires a skinned mesh"));
                    return null;
                }
                var snapshot = new Mesh { hideFlags = HideFlags.HideAndDontSave };
                try
                {
                    renderer.BakeMesh(snapshot, false);
                    TransformGeometry(snapshot, RendererToShape(system, renderer.transform));
                    return BuildGeometry(shape, snapshot, false, mapper, export, plan, path, context, issues,
                        liveGeometry: true, sectionTints: MaterialTints(shape, renderer, snapshot));
                }
                finally { UnityEngine.Object.DestroyImmediate(snapshot); }
            }
            var spriteRenderer = shape.shapeType == ParticleSystemShapeType.SpriteRenderer ? shape.spriteRenderer : null;
            Sprite sprite = shape.shapeType == ParticleSystemShapeType.SpriteRenderer ? spriteRenderer?.sprite : shape.sprite;
            if (sprite == null)
            {
                issues.Add(new ExportIssue(context, "Sprite emission requires a Sprite"));
                return null;
            }
            var geometry = new Mesh { hideFlags = HideFlags.HideAndDontSave };
            try
            {
                float signX = spriteRenderer != null && spriteRenderer.flipX ? -1 : 1;
                float signY = spriteRenderer != null && spriteRenderer.flipY ? -1 : 1;
                geometry.vertices = Array.ConvertAll(sprite.vertices, vertex => new Vector3(signX * vertex.x, signY * vertex.y, 0));
                geometry.uv = sprite.uv;
                geometry.triangles = Array.ConvertAll(sprite.triangles, index => (int)index);
                var normals = new Vector3[geometry.vertexCount];
                for (int i = 0; i < normals.Length; i++) normals[i] = Vector3.forward;
                geometry.normals = normals;
                geometry.RecalculateBounds();
                if (spriteRenderer != null) TransformGeometry(geometry, RendererToShape(system, spriteRenderer.transform));
                return BuildGeometry(shape, geometry, true, mapper, export, plan, path, context, issues,
                    liveGeometry: spriteRenderer != null);
            }
            finally
            {
                UnityEngine.Object.DestroyImmediate(geometry);
            }
        }

        private static Matrix4x4 RendererToShape(ParticleSystem system, Transform renderer)
        {
            var transform = system.transform;
            var emitter = system.main.scalingMode == ParticleSystemScalingMode.Local
                ? Matrix4x4.TRS(transform.position, transform.rotation, transform.localScale) : transform.localToWorldMatrix;
            return emitter.inverse * renderer.localToWorldMatrix;
        }

        private static void TransformGeometry(Mesh mesh, Matrix4x4 relative)
        {
            var vertices = mesh.vertices;
            for (int i = 0; i < vertices.Length; i++) vertices[i] = relative.MultiplyPoint3x4(vertices[i]);
            mesh.vertices = vertices;
            var normals = mesh.normals;
            var normalTransform = relative.inverse.transpose;
            for (int i = 0; i < normals.Length; i++) normals[i] = normalTransform.MultiplyVector(normals[i]).normalized;
            mesh.normals = normals;
            mesh.RecalculateBounds();
        }

        private static float[][] MaterialTints(ParticleSystem.ShapeModule shape, Renderer renderer, Mesh mesh)
        {
            if (!shape.useMeshColors || mesh.HasVertexAttribute(VertexAttribute.Color)) return null;
            var materials = renderer.sharedMaterials;
            var colors = new float[mesh.subMeshCount][];
            for (int section = 0; section < colors.Length; section++)
            {
                var material = section < materials.Length ? materials[section] : null;
                var color = Color.white;
                if (material != null)
                {
                    if (material.HasProperty("_Color")) color = material.GetColor("_Color");
                    else if (material.HasProperty("_TintColor")) color = material.GetColor("_TintColor");
                }
                colors[section] = new[] { color.r, color.g, color.b, color.a };
            }
            return colors;
        }

        private static Dictionary<string, object> BuildGeometry(ParticleSystem.ShapeModule shape, Mesh mesh, bool sprite,
            AxisMapper mapper, ExportSettings export, LumenFxPlan plan, string path, string context, List<ExportIssue> issues,
            bool liveGeometry = false, float[][] sectionTints = null, Matrix4x4? geometryTransform = null)
        {
            if (mesh == null || mesh.vertexCount == 0)
            {
                issues.Add(new ExportIssue(context, "Mesh emission requires a nonempty mesh"));
                return null;
            }
            bool filterSection = !sprite && shape.useMeshMaterialIndex;
            int section = filterSection ? shape.meshMaterialIndex : -1;
            if (filterSection && (section < 0 || section >= mesh.subMeshCount || mesh.GetIndexCount(section) == 0))
            {
                issues.Add(new ExportIssue(context, "Mesh emission submesh is missing or empty"));
                return null;
            }
            var vertexMode = !sprite && shape.meshShapeType == ParticleSystemMeshShapeType.Vertex
                ? shape.meshSpawnMode : ParticleSystemShapeMultiModeValue.Random;
            if (vertexMode == ParticleSystemShapeMultiModeValue.BurstSpread)
                issues.Add(new ExportIssue(context, "Mesh Vertex emission uses Random, Loop or Ping-Pong"));
            Dictionary<string, object> edgeTraversal = null;
            if (!sprite && shape.meshShapeType == ParticleSystemMeshShapeType.Edge)
            {
                var mode = shape.meshSpawnMode;
                float spread = shape.meshSpawnSpread;
                if (mode == ParticleSystemShapeMultiModeValue.BurstSpread || spread < 0 || spread > 1 || float.IsNaN(spread))
                {
                    issues.Add(new ExportIssue(context, "Invalid mesh edge traversal mode or spread"));
                    return null;
                }
                var speed = mode == ParticleSystemShapeMultiModeValue.Loop || mode == ParticleSystemShapeMultiModeValue.PingPong
                    ? LumenFxCurves.FromMinMaxCurve(shape.meshSpawnSpeed, 1, context + " shape.meshSpawnSpeed", issues)
                    : ScalarPlan.Constant(1);
                edgeTraversal = LumenFxShape.BuildTraversal(mode, spread, speed);
            }
            if (!LumenFxExporter.Finite(new Vector3(shape.normalOffset, 0, 0)))
            {
                issues.Add(new ExportIssue(context, "Mesh emission normal offset must be finite"));
                return null;
            }
            string assetPath = export.EffectName + "/" + path;
            int uvChannel = !sprite && shape.texture != null ? shape.textureUVChannel : 0;
            if (uvChannel < 0 || uvChannel > 7)
            {
                issues.Add(new ExportIssue(context, "Invalid emission texture UV channel"));
                return null;
            }
            if (shape.texture != null && !mesh.HasVertexAttribute(VertexAttribute.TexCoord0 + uvChannel))
            {
                issues.Add(new ExportIssue(context, "Emission texture needs UV data for every vertex"));
                return null;
            }
            if (!LumenFxMeshAssets.WriteMesh(export, plan, assetPath, mesh, null, mapper,
                    context + " / Emission mesh", issues, out string id, true, uvChannel, geometryTransform)) return null;
            var result = new Dictionary<string, object>
            {
                { "asset", id },
                { "placement", shape.meshShapeType.ToString() },
                { "vertex_mode", vertexMode.ToString() },
                { "section", section },
                { "normal_offset", shape.normalOffset },
                { "use_vertex_color", !sprite && shape.useMeshColors && mesh.HasVertexAttribute(VertexAttribute.Color) }
            };
            if (edgeTraversal != null) result["edge_traversal"] = edgeTraversal;
            if (liveGeometry) result["live_geometry"] = true;
            if (sectionTints != null) result["section_tints"] = sectionTints;
            if (shape.texture != null)
            {
                var texture = LumenFxShapeTexture.Build(shape, export, plan, assetPath, context, issues);
                if (texture == null) return null;
                result["texture"] = texture;
            }
            return result;
        }

    }
}
#endif
