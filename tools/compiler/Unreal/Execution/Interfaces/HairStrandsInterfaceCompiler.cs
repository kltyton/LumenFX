using Newtonsoft.Json.Linq;
using LumenFx.Compiler.Unreal.Parameters;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class HairStrandsInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceHairStrands";

    internal static JObject Definition(SourceAsset owner, int reference, AssetLibrary? library)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, ClassName, library);
        var data = source?.Object?.Properties ?? new JObject();
        var asset = source?.Asset ?? owner;
        string actorPath = ObjectPropertyReaderCompiler.ObjectPath(asset, data["SourceActor"]);
        var result = new JObject {
            ["source_actor"] = actorPath,
            ["default_source"] = ObjectPropertyReaderCompiler.ObjectPath(asset, data["DefaultSource"])
        };
        if (actorPath.Length != 0 && ObjectPropertyFieldSource.Resolve(asset, actorPath, library) is { } actor) {
            var components = actor.Asset.Objects.Where(component => actor.Asset.IsWithin(component, actor.Object)
                && ObjectPropertyFieldSource.IsOfClass(actor.Asset, component.Raw.Value<int>("ClassIndex"),
                    "/Script/HairStrandsCore.GroomComponent", library)).Take(2).ToArray();
            if (components.Length == 1)
                result["actor_groom"] = ObjectPropertyReaderCompiler.ObjectPath(actor.Asset, components[0].Properties["GroomAsset"]);
        }
        return result;
    }

    internal static bool ValidateCpu(string name, int inputs, int outputs)
    {
        var writes = name switch {
            "GetNumStrands" or "GetStrandSize" or "GetSubSteps" or "GetIterationCount" or "GetGravityPreloading"
                or "GetAirDrag" or "GetSolveBend" or "GetProjectBend" or "GetBendDamping" or "GetBendStiffness"
                or "GetSolveStretch" or "GetProjectStretch" or "GetStretchDamping" or "GetStretchStiffness"
                or "GetSolveCollision" or "GetProjectCollision" or "GetStaticFriction" or "GetKineticFriction"
                or "GetStrandsViscosity" or "GetCollisionRadius" or "GetStrandsDensity" or "GetTimeDilation"
                or "GetStrandsSmoothing" or "GetStrandsThickness" or "HasGlobalInterpolation" => (0, 1),
            "GetGravityVector" or "GetAirVelocity" or "GetGridDimension" => (0, 3),
            "GetWorldTransform" or "GetWorldInverse" => (0, 16),
            _ => (-1, -1)
        };
        if (writes == (inputs, outputs)) return true;

        // These legacy UE CPU delegates leave their output registers untouched.
        // Their implemented solver functions are supplied by the original GPU HLSL.
        var unwritten = name switch {
            "GetBendScale" or "GetStretchScale" or "GetRadiusScale" or "GetThicknessScale"
                or "ResetPointPosition" or "ResetBoundingBox" or "NeedSimulationReset" or "NeedRestUpdate" => (0, 1),
            "GetPointPosition" or "ComputeNodePosition" => (1, 3),
            "ComputeNodeOrientation" => (3, 4),
            "ComputeNodeMass" => (2, 1),
            "ComputeNodeInertia" => (2, 3),
            "ComputeEdgeLength" => (4, 1),
            "ComputeEdgeRotation" or "ComputeRestOrientation" or "AttachNodeOrientation" => (4, 4),
            "ComputeRestPosition" or "AttachNodePosition" => (3, 3),
            "ComputeLocalState" or "AttachNodeState" => (7, 7),
            "UpdateNodeState" => (10, 7),
            "UpdatePointPosition" => (6, 1),
            "AdvectNodePosition" => (15, 6),
            "AdvectNodeOrientation" => (18, 7),
            "UpdateLinearVelocity" => (7, 3),
            "UpdateAngularVelocity" or "SolveStretchRodMaterial" => (9, 3),
            "GetBoundingBox" => (1, 6),
            "BuildBoundingBox" => (3, 1),
            "SetupDistanceSpringMaterial" or "ProjectDistanceSpringMaterial" => (6, 3),
            "SolveDistanceSpringMaterial" => (8, 1),
            "SetupAngularSpringMaterial" or "SetupStretchRodMaterial" or "SetupBendRodMaterial" => (5, 5),
            "SolveAngularSpringMaterial" => (12, 3),
            "ProjectAngularSpringMaterial" => (8, 3),
            "ProjectStretchRodMaterial" => (5, 3),
            "SolveBendRodMaterial" => (13, 3),
            "ProjectBendRodMaterial" => (9, 4),
            "SolveHardCollisionConstraint" or "ProjectHardCollisionConstraint" => (14, 3),
            "SolveSoftCollisionConstraint" => (20, 3),
            "ProjectSoftCollisionConstraint" or "ComputeEdgeDirection" => (15, 3),
            "SetupSoftCollisionConstraint" => (3, 5),
            "UpdateMaterialFrame" or "ComputeMaterialFrame" => (0, 4),
            "ComputeAirDragForce" => (13, 6),
            "InitGridSamples" => (8, 8),
            "GetSampleState" => (14, 6),
            _ => (-1, -1)
        };
        if (unwritten == (inputs, outputs)) return false;
        throw new ConversionException($"Invalid HairStrands CPU function {name} ({inputs}, {outputs})");
    }
}
