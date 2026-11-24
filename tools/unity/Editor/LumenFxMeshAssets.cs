#if UNITY_EDITOR
using System;
using System.Collections.Generic;
using LumenFx.Geometry;
using Unity.Collections;
using UnityEditor;
using UnityEngine;
using UnityEngine.Rendering;

namespace LumenFx.UnityExport
{
    internal static class LumenFxMeshAssets
    {
        private const string ParticleMaterial = "particle";

        internal static bool Build(ExportSettings settings, LumenFxPlan plan, string name,
            ParticleSystemRenderer renderer, MaterialPlan material, AxisMapper mapper, string context,
            List<ExportIssue> issues, out Dictionary<string, object> meshRenderer)
        {
            meshRenderer = null;
            int meshCount = renderer.meshCount;
            var meshes = new Mesh[meshCount];
            if (meshCount == 0 || renderer.GetMeshes(meshes) != meshCount || !LumenFxExporter.Finite(renderer.pivot))
            {
                issues.Add(new ExportIssue(context, "Mesh particles require a mesh list and a finite pivot"));
                return false;
            }
            var entries = new Dictionary<string, object>[meshCount];
            var assetIds = new Dictionary<Mesh, string>();
            var lodZero = new Dictionary<string, object> { { "value", 0 } };
            for (int index = 0; index < meshes.Length; index++)
            {
                Mesh mesh = meshes[index];
                string meshContext = context + " / Mesh " + (index + 1);
                if (mesh == null || mesh.vertexCount == 0)
                {
                    issues.Add(new ExportIssue(meshContext, "Mesh particles require a nonempty mesh"));
                    return false;
                }
                if (!assetIds.TryGetValue(mesh, out string assetId))
                {
                    string path = settings.EffectName + "/" + (index == 0 ? name : name + "/slot_" + index);
                    if (!WriteMesh(settings, plan, path, mesh, material, mapper, meshContext, issues, out assetId)) return false;
                    assetIds.Add(mesh, assetId);
                }
                entries[index] = new Dictionary<string, object>
                {
                    { "asset", assetId },
                    { "materials", new object[0] },
                    { "scale", new object[] { 1, 1, 1 } },
                    { "rotation", new object[] { 0, 0, 0, 1 } },
                    { "pivot", SourceVector(mapper, renderer.pivot * -100) },
                    { "pivot_space", "mesh" },
                    { "lod_mode", "lod_level" },
                    { "lod_level", lodZero },
                    { "lod_bias", lodZero },
                    { "lod_distance_factor", 1 },
                    { "use_lod_range", false },
                    { "lod_range_min", 0 },
                    { "lod_range_max", 0 }
                };
            }
            meshRenderer = new Dictionary<string, object>
            {
                { "entries", entries },
                { "facing_mode", "default" },
                { "locked_axis_enabled", false },
                { "locked_axis", new object[] { 0, 0, 1 } },
                { "locked_axis_space", "simulation" },
                { "bounds_scale", new object[] { 1, 1, 1 } },
                { "frustum_culling", false },
                { "cast_shadows", renderer.shadowCastingMode != UnityEngine.Rendering.ShadowCastingMode.Off }
            };
            return true;
        }

        internal static bool WriteMesh(ExportSettings settings, LumenFxPlan plan, string path, Mesh mesh,
            MaterialPlan material, AxisMapper mapper, string context, List<ExportIssue> issues, out string assetId,
            bool retainSections = false, int uvChannel = 0, Matrix4x4? transform = null)
        {
            assetId = settings.Namespace + ":" + path;
            using var snapshot = MeshUtility.AcquireReadOnlyMeshData(mesh);
            var geometryData = snapshot[0];
            int vertexCount = geometryData.vertexCount;
            Vector3[] vertices = ReadArray<Vector3>(vertexCount, geometryData.GetVertices);
            Vector3[] normals = geometryData.HasVertexAttribute(VertexAttribute.Normal)
                ? ReadArray<Vector3>(vertexCount, geometryData.GetNormals) : Array.Empty<Vector3>();
            Vector4[] tangents = geometryData.HasVertexAttribute(VertexAttribute.Tangent)
                ? ReadArray<Vector4>(vertexCount, geometryData.GetTangents) : Array.Empty<Vector4>();
            Color[] colors = geometryData.HasVertexAttribute(VertexAttribute.Color)
                ? ReadArray<Color>(vertexCount, geometryData.GetColors) : Array.Empty<Color>();
            Bounds bounds = mesh.bounds;
            if (transform is Matrix4x4 relative)
            {
                for (int i = 0; i < vertices.Length; i++) vertices[i] = relative.MultiplyPoint3x4(vertices[i]);
                var normalTransform = relative.inverse.transpose;
                for (int i = 0; i < normals.Length; i++) normals[i] = normalTransform.MultiplyVector(normals[i]).normalized;
                bounds = new Bounds(vertices[0], Vector3.zero);
                for (int i = 1; i < vertices.Length; i++) bounds.Encapsulate(vertices[i]);
            }
            var uvChannels = new List<Vector4[]>();
            var uvWidths = new List<int>();
            int highestUvChannel = -1;
            if (uvChannel > 0)
            {
                if (geometryData.HasVertexAttribute(VertexAttribute.TexCoord0 + uvChannel))
                {
                    var coordinates = ReadArray<Vector4>(vertexCount, values => geometryData.GetUVs(uvChannel, values));
                    uvChannels.Add(coordinates);
                    uvWidths.Add(HasWideUv(coordinates) ? 4 : 2);
                    highestUvChannel = 0;
                }
            }
            else
            {
                for (int channel = 0; channel < 8; channel++)
                {
                    if (!geometryData.HasVertexAttribute(VertexAttribute.TexCoord0 + channel)) continue;
                    Vector4[] coordinates = ReadArray<Vector4>(vertexCount, values => geometryData.GetUVs(channel, values));
                    while (uvChannels.Count < channel)
                    {
                        uvChannels.Add(new Vector4[vertexCount]);
                        uvWidths.Add(2);
                    }
                    uvChannels.Add(coordinates);
                    uvWidths.Add(HasWideUv(coordinates) ? 4 : 2);
                    highestUvChannel = channel;
                }
            }
            if (highestUvChannel < 0 && material != null && material.SourceTexture != null)
            {
                issues.Add(new ExportIssue(context, "Textured mesh particles require at least one UV channel per vertex"));
                return false;
            }
            var indices = new List<int>();
            var sections = new List<(int group, int first, int count)>();
            for (int section = 0; section < geometryData.subMeshCount; section++)
            {
                var submesh = geometryData.GetSubMesh(section);
                if (submesh.topology != MeshTopology.Triangles)
                {
                    issues.Add(new ExportIssue(context, "Mesh particle submeshes must use triangle topology"));
                    return false;
                }
                if (submesh.indexCount == 0) continue;
                int[] triangles = ReadArray<int>(submesh.indexCount, values => geometryData.GetIndices(values, section, true));
                sections.Add((section, indices.Count, triangles.Length));
                indices.AddRange(triangles);
            }
            if (indices.Count == 0)
            {
                issues.Add(new ExportIssue(context, "Mesh particles require at least one triangle"));
                return false;
            }
            if (!retainSections)
            {
                sections.Clear();
                sections.Add((0, 0, indices.Count));
            }
            if (!LumenFxExporter.Finite(bounds.center) || !LumenFxExporter.Finite(bounds.extents))
            {
                issues.Add(new ExportIssue(context, "Mesh particle bounds must be finite"));
                return false;
            }
            // Unity triangles are clockwise. The usual Unity-to-Minecraft conversion reflects Z.
            if (mapper.ConvertsForwardToUp)
                for (int i = 0; i < indices.Count; i += 3)
                {
                    int swap = indices[i + 1];
                    indices[i + 1] = indices[i + 2];
                    indices[i + 2] = swap;
                }
            string geometry = "lumenfx/meshes/" + path + ".lfmesh";
            string assets = "assets/" + settings.Namespace + "/";
            var ids = new int[vertices.Length];
            for (int i = 0; i < ids.Length; i++) ids[i] = i;
            // Both the final shader's XZY conversion and PNG's flipped V affect tangent handedness.
            float tangentSign = mapper.ConvertsForwardToUp ? -1 : 1;
            plan.MeshGeometry.Add(assets + geometry, StaticMeshPayloadWriter.Write(1, ids, indices.ToArray(),
                uvChannels.Count, sections.Count,
                writer =>
                {
                    foreach (Vector3 value in vertices) WriteVector(writer, mapper, value * 100);
                    for (int i = 0; i < vertices.Length; i++)
                        WriteVector(writer, mapper, normals.Length == 0 ? Vector3.zero : normals[i]);
                    for (int i = 0; i < vertices.Length; i++)
                        WriteVector(writer, mapper, tangents.Length == 0 ? Vector3.zero : (Vector3)tangents[i]);
                    for (int i = 0; i < vertices.Length; i++)
                        writer.Write(tangents.Length == 0 ? 1 : tangentSign * tangents[i].w);
                    for (int i = 0; i < vertices.Length; i++)
                    {
                        Color color = colors.Length == 0 ? Color.white : colors[i];
                        writer.Write(color.r);
                        writer.Write(color.g);
                        writer.Write(color.b);
                        writer.Write(color.a);
                    }
                    for (int ch = 0; ch < uvChannels.Count; ch++)
                    {
                        bool wide = uvWidths[ch] > 2;
                        foreach (Vector4 coordinate in uvChannels[ch])
                        {
                            writer.Write(coordinate.x);
                            writer.Write(1 - coordinate.y);
                            if (wide)
                            {
                                writer.Write(coordinate.z);
                                writer.Write(coordinate.w);
                            }
                        }
                    }
                }, writer =>
                {
                    foreach (var section in sections)
                        StaticMeshPayloadWriter.WriteSection(writer, section.group, ParticleMaterial, section.first, section.count);
                }, uvWidths.ToArray()));
            Vector3 extent = mapper.Extent(bounds.extents) * 100;
            plan.MeshAssets.Add(assets + "lumenfx/meshes/" + path + ".json", new Dictionary<string, object>
            {
                { "format_version", 1 },
                { "geometry_stage", "render" },
                { "settings", new Dictionary<string, object>() },
                { "materials", new[] { new Dictionary<string, object>
                    { { "slot", ParticleMaterial }, { "imported_slot", ParticleMaterial } } } },
                { "lods", new[] { new Dictionary<string, object>
                    {
                        { "geometry", settings.Namespace + ":" + geometry },
                        { "settings", new Dictionary<string, object>() },
                        { "has_vertex_colors", colors.Length != 0 },
                        { "bounds", new Dictionary<string, object>
                            {
                                { "origin", SourceVector(mapper, bounds.center * 100) },
                                { "extent", new object[] { extent.x, extent.z, extent.y } },
                                { "radius", extent.magnitude }
                            } }
                    } } }
            });
            return true;
        }

        private static object[] SourceVector(AxisMapper mapper, Vector3 value)
        {
            value = mapper.Vector(value);
            return new object[] { value.x, value.z, value.y };
        }

        private static T[] ReadArray<T>(int count, Action<NativeArray<T>> copy) where T : struct
        {
            using var values = new NativeArray<T>(count, Allocator.Temp, NativeArrayOptions.UninitializedMemory);
            copy(values);
            return values.ToArray();
        }

        private static bool HasWideUv(Vector4[] uvs)
        {
            for (int i = 0; i < uvs.Length; i++)
                if (uvs[i].z != 0f || uvs[i].w != 0f) return true;
            return false;
        }

        private static void WriteVector(System.IO.BinaryWriter writer, AxisMapper mapper, Vector3 value)
        {
            value = mapper.Vector(value);
            writer.Write(value.x);
            writer.Write(value.z);
            writer.Write(value.y);
        }
    }
}
#endif
