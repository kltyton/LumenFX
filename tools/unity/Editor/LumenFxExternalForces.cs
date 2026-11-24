#if UNITY_EDITOR
using System;
using System.Collections.Generic;
using System.IO;
using UnityEngine;
using UnityEngine.Experimental.Rendering;
using UnityEngine.Rendering;

namespace LumenFx.UnityExport
{
    internal static class LumenFxExternalForces
    {
        internal static Dictionary<string, object> Build(ParticleSystem.ExternalForcesModule source, Transform root,
            AxisMapper mapper, string context, List<ExportIssue> issues)
        {
            var candidates = new List<ParticleSystemForceField>();
            if (source.influenceFilter != ParticleSystemGameObjectFilter.LayerMask)
            {
                for (int i = 0; i < source.influenceCount; i++)
                {
                    var field = source.GetInfluence(i);
                    if (field != null && !candidates.Contains(field)) candidates.Add(field);
                }
            }
            if (source.influenceFilter != ParticleSystemGameObjectFilter.List)
                foreach (var field in ParticleSystemForceField.FindAll())
                    if ((source.influenceMask.value & (1 << field.gameObject.layer)) != 0 && !candidates.Contains(field))
                        candidates.Add(field);

            var fields = new List<object>();
            foreach (var field in candidates)
            {
                string label = context + " / " + field.name;
                var transform = field.transform;
                if (transform != root && !transform.IsChildOf(root))
                {
                    issues.Add(new ExportIssue(label,
                        "Force field is outside the exported root; baking initial relative transform.",
                        IssueSeverity.Warning));
                }
                var basis = LumenFxExporter.UnscaledRootWorld(root).inverse * transform.localToWorldMatrix;
                if (float.IsNaN(basis.determinant) || float.IsInfinity(basis.determinant)
                    || Mathf.Abs(basis.determinant) < 1e-9f)
                {
                    issues.Add(new ExportIssue(label, "Force field has a singular or invalid transform"));
                    continue;
                }
                var data = new Dictionary<string, object>
                {
                    { "source_id", SourceId(field, root) }, { "enabled", field.isActiveAndEnabled },
                    { "shape", field.shape.ToString().ToLowerInvariant() },
                    { "center", Vector(mapper.Vector(basis.MultiplyPoint3x4(Vector3.zero))) },
                    { "x_axis", Vector(mapper.Vector(basis.MultiplyVector(Vector3.right))) },
                    { "y_axis", Vector(mapper.Vector(basis.MultiplyVector(Vector3.up))) },
                    { "z_axis", Vector(mapper.Vector(basis.MultiplyVector(Vector3.forward))) },
                    { "start", field.startRange }, { "end", field.endRange }, { "length", field.length },
                    { "direction", new[] { Read(field.directionX, label, issues), Read(field.directionY, label, issues),
                        Read(field.directionZ, label, issues) } },
                    { "gravity", Read(field.gravity, label, issues) }, { "gravity_focus", field.gravityFocus },
                    { "rotation_speed", Read(field.rotationSpeed, label, issues) },
                    { "rotation_attraction", Read(field.rotationAttraction, label, issues) },
                    { "rotation_randomness_x", field.rotationRandomness.x },
                    { "rotation_randomness_z", field.rotationRandomness.y },
                    { "drag", Read(field.drag, label, issues) },
                    { "drag_by_size", field.multiplyDragByParticleSize },
                    { "drag_by_speed", field.multiplyDragByParticleVelocity }
                };
                if (field.vectorField != null)
                {
                    var texture = field.vectorField;
                    try
                    {
                        data["vector_field"] = EncodeField(texture);
                        data["vector_speed"] = Read(field.vectorFieldSpeed, label, issues);
                        data["vector_attraction"] = Read(field.vectorFieldAttraction, label, issues);
                    }
                    catch (InvalidOperationException error)
                    {
                        issues.Add(new ExportIssue(label, error.Message));
                    }
                }
                fields.Add(data);
            }
            var windZones = new List<object>();
            if (source.influenceFilter != ParticleSystemGameObjectFilter.List)
            {
                var windCandidates = new List<WindZone>();
                foreach (var wind in root.GetComponentsInChildren<WindZone>(true))
                    if ((source.influenceMask.value & (1 << wind.gameObject.layer)) != 0 && !windCandidates.Contains(wind))
                        windCandidates.Add(wind);
#if UNITY_2023_1_OR_NEWER
                var sceneWinds = UnityEngine.Object.FindObjectsByType<WindZone>(FindObjectsSortMode.None);
#else
                var sceneWinds = UnityEngine.Object.FindObjectsOfType<WindZone>();
#endif
                foreach (var wind in sceneWinds)
                    if ((source.influenceMask.value & (1 << wind.gameObject.layer)) != 0 && !windCandidates.Contains(wind))
                        windCandidates.Add(wind);
                foreach (var wind in windCandidates)
                {
                    string label = context + " / WindZone " + wind.name;
                    var transform = wind.transform;
                    if (transform != root && !transform.IsChildOf(root))
                    {
                        issues.Add(new ExportIssue(label,
                            "WindZone is outside the exported root; baking initial relative transform.",
                            IssueSeverity.Warning));
                    }
                    var basis = LumenFxExporter.UnscaledRootWorld(root).inverse * transform.localToWorldMatrix;
                    Vector3 center = mapper.Vector(basis.MultiplyPoint3x4(Vector3.zero));
                    Vector3 forward = mapper.Vector(basis.MultiplyVector(Vector3.forward));
                    if (!LumenFxExporter.Finite(center) || !LumenFxExporter.Finite(forward) || forward.sqrMagnitude <= 1e-12f)
                    {
                        issues.Add(new ExportIssue(label, "WindZone has a singular or invalid transform"));
                        continue;
                    }
                    if (float.IsNaN(wind.radius) || float.IsInfinity(wind.radius) || wind.radius < 0
                        || float.IsNaN(wind.windMain) || float.IsInfinity(wind.windMain)
                        || float.IsNaN(wind.windTurbulence) || float.IsInfinity(wind.windTurbulence)
                        || float.IsNaN(wind.windPulseMagnitude) || float.IsInfinity(wind.windPulseMagnitude)
                        || float.IsNaN(wind.windPulseFrequency) || float.IsInfinity(wind.windPulseFrequency))
                    {
                        issues.Add(new ExportIssue(label, "WindZone parameters must be finite with non-negative radius"));
                        continue;
                    }
                    windZones.Add(new Dictionary<string, object>
                    {
                        { "source_id", WindSourceId(wind, root) },
                        { "enabled", wind.gameObject.activeInHierarchy },
                        { "mode", wind.mode == WindZoneMode.Spherical ? "spherical" : "directional" },
                        { "center", Vector(center) },
                        { "direction", Vector(forward.normalized) },
                        { "radius", wind.radius },
                        { "main", wind.windMain },
                        { "turbulence", wind.windTurbulence },
                        { "pulse_magnitude", wind.windPulseMagnitude },
                        { "pulse_frequency", wind.windPulseFrequency }
                    });
                }
            }

            var result = new Dictionary<string, object>
            {
                { "multiplier", Read(source.multiplierCurve, context + " Multiplier", issues) },
                { "fields", fields }
            };
            if (windZones.Count != 0) result["wind_zones"] = windZones;
            return result;
        }

        private static string WindSourceId(WindZone wind, Transform root)
        {
            var path = new List<int>();
            var node = wind.transform;
            while (node != null && node != root)
            {
                path.Add(node.GetSiblingIndex());
                node = node.parent;
            }
            path.Reverse();
            var scene = wind.gameObject.scene;
            string scope = node == root ? "root" : "scene:" + (string.IsNullOrEmpty(scene.path) ? scene.name : scene.path);
            int component = Array.IndexOf(wind.GetComponents<WindZone>(), wind);
            return "wind:" + scope + "/" + string.Join("/", path) + "#" + component;
        }

        private static string SourceId(ParticleSystemForceField field, Transform root)
        {
            var path = new List<int>();
            var node = field.transform;
            while (node != null && node != root)
            {
                path.Add(node.GetSiblingIndex());
                node = node.parent;
            }
            path.Reverse();
            var scene = field.gameObject.scene;
            string scope = node == root ? "root" : "scene:" + (string.IsNullOrEmpty(scene.path) ? scene.name : scene.path);
            int component = Array.IndexOf(field.GetComponents<ParticleSystemForceField>(), field);
            return scope + "/" + string.Join("/", path) + "#" + component;
        }

        private static Dictionary<string, object> EncodeField(Texture3D texture)
        {
            int texelCount = checked(texture.width * texture.height * texture.depth);
            using (var bytes = new MemoryStream(checked(texelCount * 3 * sizeof(float))))
            using (var writer = new BinaryWriter(bytes))
            {
                if (texture.isReadable)
                {
                    foreach (Color pixel in texture.GetPixels(0)) WriteVector(writer, pixel);
                }
                else
                {
                    var readback = AsyncGPUReadback.Request(texture, 0, GraphicsFormat.R32G32B32A32_SFloat);
                    readback.WaitForCompletion();
                    if (readback.hasError || readback.width != texture.width || readback.height != texture.height)
                        throw new InvalidOperationException("Cannot read mip 0 of force-field texture '" + texture.name + "'");
                    for (int layer = 0; layer < readback.layerCount; layer++)
                        foreach (Color pixel in readback.GetData<Color>(layer)) WriteVector(writer, pixel);
                }
                if (bytes.Length != texelCount * 3L * sizeof(float))
                    throw new InvalidOperationException("Unexpected texel count in force-field texture '" + texture.name + "'");
                return new Dictionary<string, object>
                {
                    { "width", texture.width }, { "height", texture.height }, { "depth", texture.depth },
                    { "rgb32le", Convert.ToBase64String(bytes.ToArray()) }
                };
            }
        }

        private static void WriteVector(BinaryWriter writer, Color pixel)
        {
            writer.Write(pixel.r);
            writer.Write(pixel.g);
            writer.Write(pixel.b);
        }

        private static object Read(ParticleSystem.MinMaxCurve curve, string context, List<ExportIssue> issues)
        {
            return LumenFxCurves.FromMinMaxCurve(curve, 1, context, issues).ToJson();
        }

        private static float[] Vector(Vector3 value) { return new[] { value.x, value.y, value.z }; }
    }
}
#endif
