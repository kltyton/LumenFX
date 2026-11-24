#if UNITY_EDITOR
using System;
using System.Collections.Generic;
using UnityEngine;

namespace LumenFx.UnityExport
{
    internal static class LumenFxShape
    {
        internal static Dictionary<string, object> Build(ParticleSystem system, AxisMapper mapper,
            string context, List<ExportIssue> issues, ExportSettings export, LumenFxPlan plan, string path)
        {
            var shape = system.shape;
            if (!shape.enabled)
                return Shape("point", Vector3.zero, false, 0);

            if (!Finite(shape.position) || !Finite(shape.rotation) || !Finite(shape.scale)
                || shape.scale.x < 0 || shape.scale.y < 0 || shape.scale.z < 0
                || !IsFraction(shape.radiusThickness) || !IsFraction(shape.randomDirectionAmount)
                || !IsFraction(shape.sphericalDirectionAmount) || !Finite(shape.randomPositionAmount)
                || shape.randomPositionAmount < 0)
            {
                issues.Add(new ExportIssue(context, "Invalid Shape transform, radius thickness or randomization"));
                return Shape("point", Vector3.zero, false, 0);
            }

            Dictionary<string, object> result;
            Dictionary<string, object> arc = null;
            Dictionary<string, object> edge = null;
            Dictionary<string, object> donut = null;
            Dictionary<string, object> mesh = null;
            Dictionary<string, object> cone = null;
            Dictionary<string, object> texture = null;
            Vector3 boxThickness = Vector3.zero;
            var rotation = mapper.Orientation(Quaternion.Euler(shape.rotation));
            bool radial = false;
            switch (shape.shapeType)
            {
                case ParticleSystemShapeType.Sphere:
                case ParticleSystemShapeType.Hemisphere:
                {
                    arc = BuildShapeArc(shape, mapper, context, issues);
                    radial = true;
                    Vector3 size = new Vector3(shape.scale.x, shape.scale.z, shape.scale.y) * shape.radius;
                    if (!Finite(size) || shape.radius < 0)
                    {
                        issues.Add(new ExportIssue(context, "Invalid sphere or hemisphere radius"));
                        return Shape("point", Vector3.zero, false, 0);
                    }
                    rotation *= RadialAxes(mapper);
                    string type = shape.shapeType == ParticleSystemShapeType.Hemisphere ? "hemisphere" : "sphere";
                    result = Shape(type, size, shape.radiusThickness == 0, 0);
                    break;
                }
                case ParticleSystemShapeType.SingleSidedEdge:
                {
                    float extent = shape.radius * shape.scale.x;
                    if (!Finite(extent) || extent < 0 || !IsFraction(shape.radiusSpread))
                    {
                        issues.Add(new ExportIssue(context, "Invalid Edge extent or spread"));
                        return Shape("point", Vector3.zero, false, 0);
                    }
                    var mode = shape.radiusMode;
                    var speed = mode == ParticleSystemShapeMultiModeValue.Loop || mode == ParticleSystemShapeMultiModeValue.PingPong
                        ? LumenFxCurves.FromMinMaxCurve(shape.radiusSpeed, 1, context + " shape.radiusSpeed", issues)
                        : ScalarPlan.Constant(1);
                    edge = BuildTraversal(mode, shape.radiusSpread, speed);
                    // The shared edge sampler runs along X and emits along local +Y.
                    rotation *= Quaternion.FromToRotation(Vector3.up, mapper.Direction(Vector3.up));
                    result = Shape("edge", new Vector3(extent, 0, 0), false, 0);
                    break;
                }
                case ParticleSystemShapeType.BoxShell:
                case ParticleSystemShapeType.BoxEdge:
                case ParticleSystemShapeType.Box:
                {
                    if (shape.shapeType != ParticleSystemShapeType.Box)
                    {
                        var authoredThickness = shape.boxThickness;
                        if (!IsFraction(authoredThickness.x) || !IsFraction(authoredThickness.y) || !IsFraction(authoredThickness.z))
                            issues.Add(new ExportIssue(context, "Box thickness must be between 0 and 1 per axis"));
                        boxThickness = mapper.Extent(authoredThickness);
                    }
                    Vector3 size = mapper.Extent(shape.scale) * 0.5f;
                    if (!Finite(size))
                    {
                        issues.Add(new ExportIssue(context, "Box shape size is not finite"));
                        return Shape("point", Vector3.zero, false, 0);
                    }
                    string type = shape.shapeType == ParticleSystemShapeType.BoxEdge ? "box_edge" : "box";
                    if (IsZero(size)) type = "point";
                    result = Shape(type, size, shape.shapeType == ParticleSystemShapeType.BoxShell, 0);
                    break;
                }
                case ParticleSystemShapeType.Rectangle:
                {
                    var size = mapper.Extent(new Vector3(shape.scale.x, shape.scale.y, 0)) * 0.5f;
                    result = Shape("box", size, false, 0);
                    break;
                }
                case ParticleSystemShapeType.Cone:
                case ParticleSystemShapeType.ConeVolume:
                {
                    radial = true;
                    arc = BuildShapeArc(shape, mapper, context, issues);
                    float length = shape.shapeType == ParticleSystemShapeType.ConeVolume ? shape.length : 0;
                    Vector3 size = new Vector3(shape.scale.x, shape.scale.z, shape.scale.y);
                    if (!Finite(shape.radius) || !Finite(length) || shape.radius < 0 || length < 0)
                    {
                        issues.Add(new ExportIssue(context, "Invalid Cone radius or length"));
                        return Shape("point", Vector3.zero, false, 0);
                    }
                    double angle = shape.angle * (Math.PI / 180);
                    if (!Finite(angle) || angle < 0 || angle > Math.PI * 0.5)
                        issues.Add(new ExportIssue(context, "Cone angle must be between 0 and 90 degrees"));
                    cone = new Dictionary<string, object> { { "radius", shape.radius }, { "length", length } };
                    rotation *= RadialAxes(mapper);
                    result = Shape("cone", size, shape.radiusThickness == 0, angle);
                    break;
                }
                case ParticleSystemShapeType.Circle:
                {
                    radial = true;
                    arc = BuildShapeArc(shape, mapper, context, issues);
                    var size = new Vector3(Mathf.Abs(shape.radius * shape.scale.x), 0, Mathf.Abs(shape.radius * shape.scale.y));
                    if (!Finite(size))
                    {
                        issues.Add(new ExportIssue(context, "Circle size is not finite"));
                        return Shape("point", Vector3.zero, false, 0);
                    }
                    rotation *= RadialAxes(mapper);
                    result = Shape("circle", size, shape.radiusThickness == 0, 0);
                    break;
                }
                case ParticleSystemShapeType.Donut:
                {
                    if (!Finite(shape.radius) || shape.radius < 0 || !Finite(shape.donutRadius) || shape.donutRadius < 0)
                    {
                        issues.Add(new ExportIssue(context, "Invalid Donut radius"));
                        return Shape("point", Vector3.zero, false, 0);
                    }
                    radial = true;
                    arc = BuildShapeArc(shape, mapper, context, issues);
                    donut = new Dictionary<string, object> { { "ring", shape.radius }, { "tube", shape.donutRadius } };
                    rotation *= RadialAxes(mapper);
                    result = Shape("donut", new Vector3(shape.scale.x, shape.scale.z, shape.scale.y), shape.radiusThickness == 0, 0);
                    break;
                }
                case ParticleSystemShapeType.Mesh:
                case ParticleSystemShapeType.MeshRenderer:
                case ParticleSystemShapeType.SkinnedMeshRenderer:
                case ParticleSystemShapeType.Sprite:
                case ParticleSystemShapeType.SpriteRenderer:
                {
                    mesh = LumenFxMeshShape.Build(system, mapper, export, plan, path, context, issues);
                    if (mesh == null) return Shape("point", Vector3.zero, false, 0);
                    result = Shape("mesh", mapper.Extent(shape.scale), false, 0);
                    break;
                }
                default:
                    issues.Add(new ExportIssue(context, "Unsupported Shape type " + shape.shapeType));
                    return Shape("point", Vector3.zero, false, 0);
            }

            if (mesh == null && shape.texture != null && shape.shapeType != ParticleSystemShapeType.SingleSidedEdge)
            {
                texture = LumenFxShapeTexture.Build(shape, export, plan, export.EffectName + "/" + path, context, issues);
                if (texture == null) return Shape("point", Vector3.zero, false, 0);
                texture["forward_to_up"] = mapper.ConvertsForwardToUp;
            }
            float thickness = radial ? shape.radiusThickness : 1;
            bool hasBoxThickness = boxThickness.x != 0 || boxThickness.y != 0 || boxThickness.z != 0;
            if (shape.position.sqrMagnitude != 0 || shape.rotation.sqrMagnitude != 0
                || Quaternion.Angle(rotation, Quaternion.identity) > .01f
                || thickness > 0 && thickness < 1 || shape.randomDirectionAmount != 0
                || shape.sphericalDirectionAmount != 0 || shape.randomPositionAmount != 0 || arc != null || edge != null
                || donut != null || cone != null || mesh != null || texture != null || hasBoxThickness || shape.alignToDirection)
            {
                var settings = new Dictionary<string, object>
                {
                    { "position", VectorJson(mapper.Vector(shape.position)) },
                    { "rotation", new[] { rotation.x, rotation.y, rotation.z, rotation.w } },
                    { "radius_thickness", thickness },
                    { "random_direction", shape.randomDirectionAmount },
                    { "spherical_direction", shape.sphericalDirectionAmount },
                    { "random_position", shape.randomPositionAmount }
                };
                if (arc != null) settings["arc"] = arc;
                if (edge != null) settings["edge"] = edge;
                if (donut != null) settings["donut"] = donut;
                if (cone != null) settings["cone"] = cone;
                if (hasBoxThickness) settings["box_thickness"] = VectorJson(boxThickness);
                if (mesh != null) settings["mesh"] = mesh;
                if (texture != null) settings["texture"] = texture;
                if (shape.alignToDirection) settings["align_to_direction"] = true;
                result["unity"] = settings;
            }
            return result;
        }

        private static Quaternion RadialAxes(AxisMapper mapper)
        {
            return Quaternion.FromToRotation(Vector3.up, mapper.Direction(Vector3.forward));
        }

        private static Dictionary<string, object> BuildShapeArc(ParticleSystem.ShapeModule shape, AxisMapper mapper,
            string context, List<ExportIssue> issues)
        {
            var mode = shape.arcMode;
            if (Mathf.Approximately(shape.arc, 360) && Mathf.Approximately(shape.arcSpread, 0)
                && mode == ParticleSystemShapeMultiModeValue.Random) return null;
            if (!Finite(shape.arc) || shape.arc < 0 || shape.arc > 360 || !IsFraction(shape.arcSpread))
            {
                issues.Add(new ExportIssue(context, "Invalid Shape arc or spread"));
                return null;
            }
            var speed = mode == ParticleSystemShapeMultiModeValue.Loop || mode == ParticleSystemShapeMultiModeValue.PingPong
                ? LumenFxCurves.FromMinMaxCurve(shape.arcSpeed, 1, context + " shape.arcSpeed", issues)
                : ScalarPlan.Constant(1);
            var arc = BuildTraversal(mode, shape.arcSpread, speed);
            arc["radians"] = shape.arc * (Math.PI / 180) * (mapper.ConvertsForwardToUp ? -1 : 1);
            return arc;
        }

        internal static Dictionary<string, object> BuildTraversal(ParticleSystemShapeMultiModeValue mode, float spread, ScalarPlan speed)
        {
            return new Dictionary<string, object> { { "mode", mode.ToString() }, { "spread", spread }, { "speed", speed.ToJson() } };
        }

        private static bool IsFraction(float value) { return value >= 0 && value <= 1; }

        private static Dictionary<string, object> Shape(string type, Vector3 size, bool surface, double angle)
        {
            return new Dictionary<string, object>
            {
                { "type", type },
                { "size", VectorJson(size) },
                { "surface", surface },
                { "angle", angle }
            };
        }

        private static float[] VectorJson(Vector3 value) { return new[] { value.x, value.y, value.z }; }
        private static bool Finite(Vector3 value) { return LumenFxExporter.Finite(value); }
        private static bool Finite(double value) { return !double.IsNaN(value) && !double.IsInfinity(value); }
        private static bool IsZero(Vector3 value)
        {
            return Mathf.Abs(value.x) <= 1e-6f && Mathf.Abs(value.y) <= 1e-6f && Mathf.Abs(value.z) <= 1e-6f;
        }
    }
}
#endif
