#if UNITY_EDITOR
using System.Collections.Generic;
using UnityEngine;

namespace LumenFx.UnityExport
{
    internal static class LumenFxTriggers
    {
        internal static Dictionary<string, object> Build(ParticleSystem.TriggerModule source, Transform root,
            AxisMapper mapper, ExportSettings settings, LumenFxPlan plan, string pathPrefix, string context, List<ExportIssue> issues)
        {
            var shapes = new List<object>();
            Matrix4x4 unscaledRootInverse = LumenFxExporter.UnscaledRootWorld(root).inverse;
            for (int i = 0; i < source.colliderCount; i++)
            {
                Component item = source.GetCollider(i);
                if (item == null) continue;
                if (item.transform != root && !item.transform.IsChildOf(root))
                {
                    issues.Add(new ExportIssue(context,
                        "Trigger collider '" + item.name + "' is outside the exported root; exporting as a host-bound slot (bind dynamically via EffectHandle.setUnityTriggerColliders).",
                        IssueSeverity.Warning));
                    shapes.Add(new Dictionary<string, object>
                    {
                        { "name", item.name + "#" + i },
                        { "shape", "host" },
                        { "host_key", item.name }
                    });
                    continue;
                }
                var basis = item.transform;
                Vector3 localCenter, localSize;
                string shape;
                int capsuleDirection = -1;
                string meshAssetId = null;
                bool convexMesh = false;
                if (item is SphereCollider sphere)
                {
                    if (!sphere.enabled) continue;
                    localCenter = sphere.center;
                    localSize = Vector3.one * sphere.radius;
                    shape = "sphere";
                }
                else if (item is BoxCollider box)
                {
                    if (!box.enabled) continue;
                    localCenter = box.center;
                    localSize = box.size * 0.5f;
                    shape = "box";
                }
                else if (item is CapsuleCollider capsule)
                {
                    if (!capsule.enabled) continue;
                    localCenter = capsule.center;
                    localSize = Vector3.one * capsule.radius;
                    capsuleDirection = capsule.direction;
                    localSize[capsuleDirection] = capsule.height * 0.5f;
                    shape = "capsule";
                }
                else if (item is CharacterController character)
                {
                    if (!character.enabled) continue;
                    localCenter = character.center;
                    localSize = new Vector3(character.radius, character.height * 0.5f, character.radius);
                    capsuleDirection = 1;
                    shape = "capsule";
                }
                else if (item is MeshCollider meshCollider)
                {
                    if (!meshCollider.enabled) continue;
                    Mesh mesh = meshCollider.sharedMesh;
                    if (mesh == null || mesh.vertexCount == 0)
                    {
                        issues.Add(new ExportIssue(context, "MeshCollider '" + item.name + "' requires a nonempty sharedMesh"));
                        continue;
                    }
                    if (!LumenFxMeshAssets.WriteMesh(settings, plan, pathPrefix + "/trigger_" + i, mesh, null,
                            mapper, context + " / Trigger " + item.name, issues, out meshAssetId))
                    {
                        continue;
                    }
                    localCenter = Vector3.zero;
                    localSize = Vector3.one;
                    convexMesh = meshCollider.convex;
                    shape = "mesh";
                }
                else if (item is TerrainCollider terrainCollider)
                {
                    if (!terrainCollider.enabled || terrainCollider.terrainData == null) continue;
                    Mesh terrainMesh = BuildTerrainTriggerMesh(terrainCollider.terrainData);
                    try
                    {
                        if (!LumenFxMeshAssets.WriteMesh(settings, plan, pathPrefix + "/trigger_" + i, terrainMesh, null,
                                mapper, context + " / Trigger " + item.name, issues, out meshAssetId))
                        {
                            continue;
                        }
                    }
                    finally
                    {
                        UnityEngine.Object.DestroyImmediate(terrainMesh);
                    }
                    localCenter = Vector3.zero;
                    localSize = Vector3.one;
                    convexMesh = false;
                    shape = "mesh";
                }
                else if (item is BoxCollider2D box2d)
                {
                    if (!box2d.enabled) continue;
                    localCenter = box2d.offset;
                    localSize = new Vector3(box2d.size.x * 0.5f, box2d.size.y * 0.5f, 0.5f);
                    shape = "box";
                }
                else if (item is CircleCollider2D circle2d)
                {
                    if (!circle2d.enabled) continue;
                    localCenter = circle2d.offset;
                    localSize = Vector3.one * circle2d.radius;
                    shape = "sphere";
                }
                else if (item is CapsuleCollider2D capsule2d)
                {
                    if (!capsule2d.enabled) continue;
                    localCenter = capsule2d.offset;
                    capsuleDirection = capsule2d.direction == CapsuleDirection2D.Horizontal ? 0 : 1;
                    float r = (capsule2d.direction == CapsuleDirection2D.Horizontal ? capsule2d.size.y : capsule2d.size.x) * 0.5f;
                    float h = (capsule2d.direction == CapsuleDirection2D.Horizontal ? capsule2d.size.x : capsule2d.size.y) * 0.5f;
                    localSize = new Vector3(capsuleDirection == 0 ? h : r, capsuleDirection == 1 ? h : r, r);
                    shape = "capsule";
                }
                else
                {
                    issues.Add(new ExportIssue(context, "Unsupported particle trigger collider " + item.GetType().Name + ": " + item.name));
                    continue;
                }

                Matrix4x4 relative = unscaledRootInverse * basis.localToWorldMatrix;
                Vector3 x, y, z;
                if (meshAssetId != null)
                {
                    // .lfmesh vertices already store mapper.Vector(localVertex); conjugate by mapper^-1 here
                    // so UnityTriggerOverlaps does not apply AxisMapper twice to MeshCollider coordinates.
                    Vector3 invX = Vector3.right;
                    Vector3 invY = mapper.ConvertsForwardToUp ? Vector3.forward : Vector3.up;
                    Vector3 invZ = mapper.ConvertsForwardToUp ? Vector3.down : Vector3.back;
                    x = mapper.Direction(relative.MultiplyVector(invX));
                    y = mapper.Direction(relative.MultiplyVector(invY));
                    z = mapper.Direction(relative.MultiplyVector(invZ));
                }
                else
                {
                    x = mapper.Direction(relative.MultiplyVector(Vector3.right));
                    y = mapper.Direction(relative.MultiplyVector(Vector3.up));
                    z = mapper.Direction(relative.MultiplyVector(Vector3.forward));
                }
                Vector3 center = mapper.Vector(relative.MultiplyPoint3x4(localCenter));
                Vector3 size = Vector3.Scale(localSize, new Vector3(x.magnitude, y.magnitude, z.magnitude));
                if (!LumenFxExporter.Finite(center) || !LumenFxExporter.Finite(size)
                    || size.x <= 0 || size.y <= 0 || size.z <= 0)
                {
                    issues.Add(new ExportIssue(context, "Trigger collider must have finite, positive dimensions: " + item.name));
                    continue;
                }
                var collider = new Dictionary<string, object>
                {
                    { "name", item.name + "#" + i }, { "shape", shape }, { "center", Vector(center) },
                    { "size", Vector(size) }, { "x_axis", Vector(x.normalized) },
                    { "y_axis", Vector(y.normalized) }, { "z_axis", Vector(z.normalized) }
                };
                if (capsuleDirection >= 0) collider.Add("direction", capsuleDirection);
                if (meshAssetId != null)
                {
                    collider.Add("mesh", meshAssetId);
                    collider.Add("convex", convexMesh);
                }
                shapes.Add(collider);
            }
            if (shapes.Count > 64) issues.Add(new ExportIssue(context, "At most 64 trigger colliders per ParticleSystem are supported"));
            return new Dictionary<string, object>
            {
                { "colliders", shapes }, { "radius_scale", source.radiusScale },
                { "query", source.colliderQueryMode.ToString().ToLowerInvariant() },
                { "inside", source.inside.ToString().ToLowerInvariant() },
                { "outside", source.outside.ToString().ToLowerInvariant() },
                { "enter", source.enter.ToString().ToLowerInvariant() },
                { "exit", source.exit.ToString().ToLowerInvariant() }
            };
        }

        private static float[] Vector(Vector3 value) { return new[] { value.x, value.y, value.z }; }

        private static Mesh BuildTerrainTriggerMesh(TerrainData data)
        {
            int res = Mathf.Clamp(data.heightmapResolution, 2, 65);
            int step = Mathf.Max(1, (data.heightmapResolution - 1) / (res - 1));
            int cols = (data.heightmapResolution - 1) / step + 1;
            float[,] heights = data.GetHeights(0, 0, data.heightmapResolution, data.heightmapResolution);
            Vector3 terrainSize = data.size;
            var verts = new Vector3[cols * cols];
            var indices = new List<int>((cols - 1) * (cols - 1) * 6);
            for (int z = 0; z < cols; z++)
            {
                int hz = Mathf.Min(z * step, data.heightmapResolution - 1);
                for (int x = 0; x < cols; x++)
                {
                    int hx = Mathf.Min(x * step, data.heightmapResolution - 1);
                    float u = (float)hx / (data.heightmapResolution - 1);
                    float v = (float)hz / (data.heightmapResolution - 1);
                    verts[z * cols + x] = new Vector3(u * terrainSize.x, heights[hz, hx] * terrainSize.y, v * terrainSize.z);
                }
            }
            for (int z = 0; z < cols - 1; z++)
            {
                for (int x = 0; x < cols - 1; x++)
                {
                    int i0 = z * cols + x;
                    int i1 = i0 + 1;
                    int i2 = i0 + cols;
                    int i3 = i2 + 1;
                    indices.Add(i0); indices.Add(i2); indices.Add(i1);
                    indices.Add(i1); indices.Add(i2); indices.Add(i3);
                }
            }
            Mesh mesh = new Mesh();
            mesh.hideFlags = HideFlags.HideAndDontSave;
            mesh.vertices = verts;
            mesh.triangles = indices.ToArray();
            mesh.RecalculateNormals();
            return mesh;
        }
    }
}
#endif
