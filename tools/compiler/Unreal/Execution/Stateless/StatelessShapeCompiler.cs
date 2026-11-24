using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution.Stateless;

internal static class StatelessShapeCompiler
{
    internal static JObject? Compile(JObject source, StatelessModuleCompiler layout, StatelessDistributionCompiler distributions)
    {
        int current = layout.Offset("Position", 0), previous = layout.Offset("Previous.Position", 0);
        if (current < 0 && previous < 0) return null;
        int primitive = StatelessDistributionCompiler.EnumValue(source["ShapePrimitive"], "Sphere") switch {
            "Box" or "0" => 0, "Cylinder" or "1" => 1, "Plane" or "2" => 2,
            "Ring" or "3" => 3, "Sphere" or "4" => 4, "Max" or "5" => 5,
            var value => throw new ConversionException($"Unknown stateless shape primitive {value}")
        };
        JObject Range(string name, float[] defaults, float[] fallback) => distributions.Range(source[name], defaults, fallback);
        JObject result = new() { ["current"] = current, ["previous"] = previous, ["primitive"] = primitive,
            ["space"] = StatelessPhysicsCompiler.Space(source["CoordinateSpace"], "Simulation"),
            ["rotation"] = distributions.Range(source["ShapeRotation"], new[] { 0f, 0f, 0f }, new[] { 0f, 0f, 0f }, rotator: true),
            ["scale"] = Range("ShapeScale", new[] { 1f, 1f, 1f }, new[] { 1f, 1f, 1f }),
            ["offset"] = StatelessDistributionCompiler.Packed(distributions.Distribution(source["TransformOffset"], new[] { 0f, 0f, 0f })),
            ["offset_scale"] = Range("TransformOffsetScale", new[] { 1f, 1f, 1f }, new[] { 1f, 1f, 1f }),
            ["surface"] = false, ["end_caps"] = false, ["expansion"] = 1, ["callback"] = primitive != 5 };
        switch (primitive) {
            case 0:
                result["size"] = Range("BoxSize", new[] { 100f, 100f, 100f }, new[] { 0f, 0f, 0f });
                result["surface"] = source.Value<bool?>("bBoxSurfaceOnly") ?? false;
                result["expansion"] = Expansion(source["BoxSurfaceExpansion"]);
                result["thickness"] = Range("BoxSurfaceThickness", new[] { 0f }, new[] { 0f });
                break;
            case 1:
                result["height"] = Range("CylinderHeight", new[] { 100f }, new[] { 0f });
                result["radius"] = Range("CylinderRadius", new[] { 100f }, new[] { 0f });
                result["midpoint"] = Range("CylinderHeightMidpoint", new[] { .5f }, new[] { 0f });
                result["surface"] = source.Value<bool?>("bCylinderSurfaceOnly") ?? false;
                result["end_caps"] = source.Value<bool?>("bCylinderSurfaceOnlyIncludeEndCaps") ?? true;
                result["expansion"] = Expansion(source["CylinderSurfaceExpansion"]);
                result["thickness"] = Range("CylinderSurfaceThickness", new[] { 0f }, new[] { 0f });
                break;
            case 2:
                result["size"] = Range("PlaneSize", new[] { 100f, 100f }, new[] { 0f, 0f });
                result["surface"] = source.Value<bool?>("bPlaneEdgesOnly") ?? false;
                result["expansion"] = Expansion(source["PlaneEdgeExpansion"]);
                result["thickness"] = Range("PlaneEdgeThickness", new[] { 0f }, new[] { 0f });
                break;
            case 3:
                result["radius"] = Range("RingRadius", new[] { 100f }, new[] { 0f });
                result["coverage"] = Range("DiscCoverage", new[] { 0f }, new[] { 0f });
                result["distribution"] = Range("RingUDistribution", new[] { 0f }, new[] { 0f });
                break;
            case 4:
                float minimum = (float)SourceAsset.Number(source["SphereMin_DEPRECATED"] ?? source["SphereMin"], 0f);
                float maximum = (float)SourceAsset.Number(source["SphereMax_DEPRECATED"] ?? source["SphereMax"], 100f);
                JToken? radius = source["SphereRadius"];
                if (MathF.Abs(minimum) > 1e-8f || MathF.Abs(maximum - 100f) > 1e-8f)
                    radius = new JObject { ["Mode"] = "UniformRange", ["Min"] = minimum, ["Max"] = maximum };
                result["radius"] = distributions.Range(radius, new[] { 100f }, new[] { 0f });
                break;
        }
        return result;
    }

    private static int Expansion(JToken? value) => StatelessDistributionCompiler.EnumValue(value, "Centered") switch {
        "Inner" or "0" => 0, "Centered" or "1" => 1, "Outside" or "2" => 2,
        var mode => throw new ConversionException($"Unknown stateless surface expansion {mode}")
    };
}
