#if UNITY_EDITOR
using System.Collections.Generic;
using UnityEngine;

namespace LumenFx.UnityExport
{
    internal static class LumenFxCollision
    {
        internal static Dictionary<string, object> Build(ParticleSystem.CollisionModule collision, Transform root,
            AxisMapper mapper, string context, List<ExportIssue> issues)
        {
            switch (collision.type)
            {
                case ParticleSystemCollisionType.Planes:
                    return BuildPlanes(collision, root, mapper, context, issues);
                case ParticleSystemCollisionType.World:
                    return BuildWorld(collision, mapper, context, issues);
                default:
                    issues.Add(new ExportIssue(context, "Unknown collision type " + collision.type));
                    return null;
            }
        }

        private static Dictionary<string, object> BuildPlanes(ParticleSystem.CollisionModule collision, Transform root,
            AxisMapper mapper, string context, List<ExportIssue> issues)
        {
            var planes = new List<Dictionary<string, object>>();
            for (int index = 0; index < collision.planeCount; index++)
            {
                Transform plane = collision.GetPlane(index);
                if (plane == null) continue;
                if (plane != root && !plane.IsChildOf(root))
                {
                    issues.Add(new ExportIssue(context,
                        "Collision plane '" + plane.name + "' is outside the exported root; exporting initial relative pose at slot " + index + ".",
                        IssueSeverity.Warning));
                }
                Matrix4x4 unscaledRoot = LumenFxExporter.UnscaledRootWorld(root);
                Vector3 point = mapper.Vector(unscaledRoot.inverse.MultiplyPoint3x4(plane.position));
                Vector3 normal = mapper.Direction(Quaternion.Inverse(root.rotation) * plane.up).normalized;
                if (!LumenFxExporter.Finite(point) || !LumenFxExporter.Finite(normal))
                {
                    issues.Add(new ExportIssue(context, "Collision plane must be finite: " + plane.name));
                    return null;
                }
                planes.Add(new Dictionary<string, object>
                {
                    { "slot", index },
                    { "point", new[] { point.x, point.y, point.z } },
                    { "normal", new[] { normal.x, normal.y, normal.z } }
                });
            }
            if (planes.Count == 0) return null;
            var result = BuildResponse(collision, context, issues);
            if (result != null) result["planes"] = planes;
            return result;
        }

        private static Dictionary<string, object> BuildWorld(ParticleSystem.CollisionModule collision,
            AxisMapper mapper, string context, List<ExportIssue> issues)
        {
            if (collision.collidesWith.value == 0) return null;
            if (collision.mode != ParticleSystemCollisionMode.Collision3D
                && collision.mode != ParticleSystemCollisionMode.Collision2D)
            {
                issues.Add(new ExportIssue(context, "Unsupported world collision mode " + collision.mode));
                return null;
            }
            float voxelSize = collision.voxelSize;
            float colliderForce = collision.colliderForce;
            if (!LumenFxExporter.Finite(voxelSize) || voxelSize <= 0 || !LumenFxExporter.Finite(colliderForce))
            {
                issues.Add(new ExportIssue(context, "Invalid world collision voxel size or collider force"));
                return null;
            }
            var result = BuildResponse(collision, context, issues);
            if (result == null) return null;
            result["dynamic_colliders"] = collision.enableDynamicColliders;
            result["max_dynamic_shapes"] = Mathf.Max(1, collision.maxCollisionShapes);
            result["layer_mask"] = collision.collidesWith.value;
            result["quality"] = collision.quality.ToString().ToLowerInvariant();
            result["voxel_size"] = voxelSize;
            result["collider_force"] = colliderForce;
            result["multiply_force_by_collision_angle"] = collision.multiplyColliderForceByCollisionAngle;
            result["multiply_force_by_particle_speed"] = collision.multiplyColliderForceByParticleSpeed;
            result["multiply_force_by_particle_size"] = collision.multiplyColliderForceByParticleSize;
            if (collision.mode == ParticleSystemCollisionMode.Collision2D)
            {
                // Unity 2D collision always sweeps in Unity XY; map Unity's free Z normal through AxisMapper.
                Vector3 mappedNormal = mapper.Direction(Vector3.forward);
                string plane2d = Mathf.Abs(mappedNormal.y) >= Mathf.Abs(mappedNormal.x) && Mathf.Abs(mappedNormal.y) >= Mathf.Abs(mappedNormal.z)
                    ? "xz"
                    : Mathf.Abs(mappedNormal.x) >= Mathf.Abs(mappedNormal.z) ? "yz" : "xy";
                result["mode_2d"] = true;
                result["forward_to_up"] = mapper.ConvertsForwardToUp;
                result["plane_2d"] = plane2d;
            }
            return result;
        }

        private static Dictionary<string, object> BuildResponse(ParticleSystem.CollisionModule collision,
            string context, List<ExportIssue> issues)
        {
            float minimum = collision.minKillSpeed, maximum = collision.maxKillSpeed, radius = collision.radiusScale;
            if (!LumenFxExporter.Finite(new Vector3(minimum, maximum, radius)) || minimum < 0 || maximum < minimum || radius < 0)
            {
                issues.Add(new ExportIssue(context, "Invalid collision speed or radius"));
                return null;
            }
            return new Dictionary<string, object>
            {
                { "dampen", LumenFxCurves.FromMinMaxCurve(collision.dampen, 1, context + " collision.dampen", issues).ToJson() },
                { "bounce", LumenFxCurves.FromMinMaxCurve(collision.bounce, 1, context + " collision.bounce", issues).ToJson() },
                { "lifetime_loss", LumenFxCurves.FromMinMaxCurve(collision.lifetimeLoss, 1, context + " collision.lifetimeLoss", issues).ToJson() },
                { "minimum_speed", minimum },
                { "maximum_speed", maximum },
                { "radius_scale", radius },
                { "send_collision_messages", collision.sendCollisionMessages }
            };
        }
    }
}
#endif
