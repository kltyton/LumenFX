using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution.Stateless;

internal static class StatelessOrbitCompiler
{
    internal static JObject? Compile(JObject source, StatelessModuleCompiler layout, StatelessDistributionCompiler distributions)
    {
        int current = layout.Offset("Position", 0), previous = layout.Offset("Previous.Position", 0);
        if (current < 0 && previous < 0) return null;
        return new JObject { ["current"] = current, ["previous"] = previous,
            ["rate"] = distributions.LiteralRange(source["Rate"], 360f),
            ["radius"] = distributions.LiteralRange(source["Radius"], 100f),
            ["phase"] = distributions.LiteralRange(source["InitialPhase"], 0f),
            ["center"] = distributions.LiteralRange(source["Center"], 0f, 0f, 0f),
            ["rotation"] = distributions.LiteralRange(source["RotationAxis"], 0f, 0f, 0f),
            ["center_space"] = StatelessPhysicsCompiler.Space(source["CenterCoordinateSpace"], "Simulation"),
            ["rotation_space"] = StatelessPhysicsCompiler.Space(source["RotationCoordinateSpace"], "Simulation") };
    }
}
