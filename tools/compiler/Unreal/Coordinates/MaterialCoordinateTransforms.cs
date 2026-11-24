using System.Text;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private enum MaterialSpace { Tangent, Local, World, TranslatedWorld, View, Camera, Particle, Instance, PeriodicWorld, FirstPerson }
    private bool pixelBasisMatrixUsed;
    private bool previousMatricesUsed;

    private Expr TextureCoordinateInput(Context context, JObject properties) =>
        Input(context, properties["Coordinate"], () => Coordinates(
            new JObject { ["CoordinateIndex"] = properties.Value<int>("ConstCoordinate") }, explicitNode: true));

    private Expr BumpOffset(Context context, JObject properties)
    {
        if (compilingVertex) throw new ConversionException("BumpOffset uses CameraVector, which requires pixel or compute shading");
        Expr height = DemoteLargeWorld(Cast(RequiredInput(context, properties, "Height"), 1));
        float referencePlane = (float)SourceAsset.Number(properties["ReferencePlane"], .5f);
        float authoredRatio = (float)SourceAsset.Number(properties["HeightRatio"], .05f);
        bool connectedRatio = Connected(properties["HeightRatioInput"]);
        Expr ratio = connectedRatio
                ? DemoteLargeWorld(Cast(RequiredInput(context, properties, "HeightRatioInput"), 1))
                : Constant(authoredRatio);
        Expr referenceOffset = connectedRatio
                ? Arithmetic(Constant(-referencePlane), ratio, "*")
                : Constant(-referencePlane * authoredRatio);
        Expr eye = TransformMaterialValue(MaterialSpace.World, MaterialSpace.Tangent, CameraVector(), false);
        Expr displacement = Arithmetic(Arithmetic(ratio, height, "*"), referenceOffset, "+");
        return Arithmetic(Arithmetic(Swizzle(eye, "rg"), displacement, "*"), TextureCoordinateInput(context, properties), "+");
    }

    private Expr CoordinateTransform(Context context, JObject properties, bool position)
    {
        MaterialSpace source = CoordinateSpace(properties["TransformSourceType"], position, true);
        MaterialSpace destination = CoordinateSpace(properties["TransformType"], position, false);
        Expr input = RequiredInput(context, properties, "Input");
        if (position && (source == MaterialSpace.Tangent || destination == MaterialSpace.Tangent))
            throw new ConversionException("Tangent basis is not available for position transformations");
        if (source == MaterialSpace.FirstPerson && destination != source)
            throw new ConversionException("Transforming from first person space is not supported by the source material compiler");
        if (input.Width == 1 && source == MaterialSpace.Tangent) input = LargeOrOrdinaryAppend(Vector([0, 0]), input);
        else if (input.Width < 3) throw new ConversionException("Material transform requires at least three components, or a scalar tangent input");
        Expr? tileSize = source == MaterialSpace.PeriodicWorld || destination == MaterialSpace.PeriodicWorld
                ? Input(context, properties["PeriodicWorldTileSize"],
                        () => Constant((float)SourceAsset.Number(properties["ConstPeriodicWorldTileSize"], 32))) : null;
        Expr? firstPersonAlpha = source == MaterialSpace.FirstPerson || destination == MaterialSpace.FirstPerson
                ? Input(context, properties["FirstPersonInterpolationAlpha"],
                        () => Constant((float)SourceAsset.Number(properties["ConstFirstPersonInterpolationAlpha"], 1))) : null;
        if (source == destination) return input;
        Expr? periodicOrigin = tileSize is null ? null : PeriodicWorldOrigin(tileSize);
        if (destination == MaterialSpace.FirstPerson)
            return FirstPersonCoordinate(TransformMaterialValue(source, MaterialSpace.TranslatedWorld, Cast(input, 3), position, periodicOrigin), firstPersonAlpha!);
        return TransformMaterialValue(source, destination, Cast(input, 3), position, periodicOrigin);
    }

    private static Expr LargeOrOrdinaryAppend(Expr a, Expr b) => a.LargeWorld || b.LargeWorld
            ? LargeWorldAppend(a, b) : new Expr($"vec{a.Width + b.Width}({a.Code}, {b.Code})", a.Width + b.Width);

    private static MaterialSpace CoordinateSpace(JToken? value, bool position, bool source)
    {
        MaterialSpace[] order = position
                ? [MaterialSpace.Local, MaterialSpace.World, MaterialSpace.PeriodicWorld, MaterialSpace.TranslatedWorld,
                    MaterialSpace.FirstPerson, MaterialSpace.View, MaterialSpace.Camera, MaterialSpace.Particle, MaterialSpace.Instance]
                : [MaterialSpace.Tangent, MaterialSpace.Local, MaterialSpace.World, MaterialSpace.View,
                    MaterialSpace.Camera, MaterialSpace.Particle, MaterialSpace.Instance];
        if (value is null || value.Type == JTokenType.Null) return position ? MaterialSpace.Local
                : source ? MaterialSpace.Tangent : MaterialSpace.World;
        string name = value.Value<string>() ?? throw new ConversionException("Material coordinate space must be an enum value");
        if (int.TryParse(name, out int ordinal)) return ordinal >= 0 && ordinal < order.Length ? order[ordinal]
                : throw new ConversionException($"Invalid material coordinate space {ordinal}");
        name = name.Split("::").Last().Split('_').Last();
        return name switch {
            "Tangent" when !position => MaterialSpace.Tangent, "Local" => MaterialSpace.Local,
            "World" => MaterialSpace.World, "TranslatedWorld" when position => MaterialSpace.TranslatedWorld,
            "PeriodicWorld" when position => MaterialSpace.PeriodicWorld,
            "FirstPersonTranslatedWorld" when position => MaterialSpace.FirstPerson,
            "View" => MaterialSpace.View, "Camera" => MaterialSpace.Camera,
            "Particle" or "ParticleWorld" => MaterialSpace.Particle, "Instance" => MaterialSpace.Instance,
            _ => throw new ConversionException($"Unknown material coordinate space {name}")
        };
    }

    private Expr TransformMaterialValue(MaterialSpace source, MaterialSpace destination, Expr input, bool position, Expr? periodicOrigin = null)
    {
        if (source == destination) return input;
        if (source == MaterialSpace.World && destination == MaterialSpace.Local) {
            string domain = (compiledMaterialProperties.Value<string>("MaterialDomain") ?? "MD_Surface").Split("::").Last();
            if (domain is not ("MD_Surface" or "MD_Volume"))
                throw new ConversionException("World-to-local transformation requires the Surface or Volume material domain");
            return PrimitiveMaterialTransform(destination, input, false, position);
        }
        if (destination == MaterialSpace.World && source == MaterialSpace.Local && (!compilingVertex || !meshCoordinates))
            return PrimitiveMaterialTransform(source, input, true, position);
        if (!meshCoordinates && source == MaterialSpace.World && destination == MaterialSpace.Instance)
            return PrimitiveMaterialTransform(destination, input, false, position);
        if (!meshCoordinates && destination == MaterialSpace.World && source == MaterialSpace.Instance)
            return PrimitiveMaterialTransform(source, input, true, position);
        if (destination == MaterialSpace.World && (source is MaterialSpace.Particle or MaterialSpace.Instance
                || source == MaterialSpace.Local && compilingVertex && meshCoordinates))
            return MeshMaterialTransform(source == MaterialSpace.Local ? MaterialSpace.Instance : source, input, true, position);
        if (source == MaterialSpace.World && destination is MaterialSpace.Particle or MaterialSpace.Instance)
            return MeshMaterialTransform(destination, input, false, position);
        if (!position && source == MaterialSpace.Tangent && destination == MaterialSpace.World)
            return new Expr($"({MaterialTangentBasis()} * {input.Code})", 3);
        if (!position && source == MaterialSpace.World && destination == MaterialSpace.Tangent)
            return new Expr($"(transpose({MaterialTangentBasis()}) * {input.Code})", 3);
        if (source == MaterialSpace.TranslatedWorld && destination == MaterialSpace.World)
            return position ? AbsolutePosition(input.Code) : DemoteLargeWorld(input);
        if (source == MaterialSpace.World && destination == MaterialSpace.TranslatedWorld) {
            if (!position) return DemoteLargeWorld(input);
            materialViewUsed |= 8;
            string view = compilingPreviousFrame ? "Motion" : "Material";
            return new Expr($"lfx_lwc_demote(lfx_lwc_add({Promote(input)}, LfxLWC3(-{view}WorldTile.xyz, {view}PreViewTranslation.xyz)))", 3);
        }
        if (source == MaterialSpace.TranslatedWorld && destination is MaterialSpace.View or MaterialSpace.Camera)
            return ViewCoordinate(input, destination == MaterialSpace.Camera ? "WorldToCamera" : "WorldToView", position);
        if (destination == MaterialSpace.TranslatedWorld && source is MaterialSpace.View or MaterialSpace.Camera)
            return ViewCoordinate(input, source == MaterialSpace.Camera ? "CameraToWorld" : "ViewToWorld", position);
        if (source == MaterialSpace.TranslatedWorld && destination == MaterialSpace.PeriodicWorld)
            return new Expr($"({input.Code} - {periodicOrigin!.Code})", 3);
        if (source == MaterialSpace.PeriodicWorld && destination == MaterialSpace.TranslatedWorld)
            return new Expr($"({input.Code} + {periodicOrigin!.Code})", 3);
        if (source is MaterialSpace.Particle or MaterialSpace.Instance or MaterialSpace.Local)
            return TransformMaterialValue(MaterialSpace.World, destination,
                    TransformMaterialValue(source, MaterialSpace.World, input, position, periodicOrigin), position, periodicOrigin);
        if (destination is MaterialSpace.Particle or MaterialSpace.Instance or MaterialSpace.Local)
            return TransformMaterialValue(MaterialSpace.World, destination,
                    TransformMaterialValue(source, MaterialSpace.World, input, position, periodicOrigin), position, periodicOrigin);
        if (source == MaterialSpace.Tangent)
            return TransformMaterialValue(MaterialSpace.World, destination,
                    TransformMaterialValue(source, MaterialSpace.World, input, position, periodicOrigin), position, periodicOrigin);
        if (destination == MaterialSpace.Tangent)
            return TransformMaterialValue(MaterialSpace.World, destination,
                    TransformMaterialValue(source, MaterialSpace.World, input, position, periodicOrigin), position, periodicOrigin);
        bool sourceView = source is MaterialSpace.View or MaterialSpace.Camera;
        bool destinationView = destination is MaterialSpace.View or MaterialSpace.Camera;
        if (source == MaterialSpace.World && destinationView || destination == MaterialSpace.World && sourceView
                || sourceView && destinationView || source == MaterialSpace.PeriodicWorld || destination == MaterialSpace.PeriodicWorld)
            return TransformMaterialValue(MaterialSpace.TranslatedWorld, destination,
                    TransformMaterialValue(source, MaterialSpace.TranslatedWorld, input, position, periodicOrigin), position, periodicOrigin);
        throw new ConversionException($"Material coordinate transform requires source matrices for {source} to {destination}");
    }

    private Expr ViewCoordinate(Expr input, string matrix, bool position)
    {
        materialViewUsed |= 32;
        previousMatricesUsed |= compilingPreviousFrame && (matrix is "WorldToView" or "ViewToWorld");
        string view = compilingPreviousFrame ? "Motion" : "Material";
        return new Expr($"({view}{matrix} * vec4({input.Code}, {(position ? "1.0" : "0.0")})).xyz", 3);
    }

    private string MaterialTangentBasis()
    {
        if (lightFunctionCoordinates || volumeCoordinates) return "mat3(1.0)";
        vertexBasisUsed = true;
        if (compilingVertex) {
            const string basis = "mat3(lfxVertexMaterialTangent, lfxVertexMaterialBinormal, lfxVertexMaterialNormal)";
            return meshCoordinates ? basis : $"({PrimitiveLinear(MaterialSpace.Local, true, false)} * {basis})";
        }
        pixelBasisMatrixUsed = true;
        _ = VertexBasis(false);
        _ = VertexBasis(true);
        return "lfx_material_tangent_basis()";
    }

    private void AppendPixelTangentBasis(StringBuilder shader)
    {
        if (!pixelBasisMatrixUsed) return;
        AppendNanitePixelInput(shader, "float effectMaterialBasisDeterminant");
        shader.AppendLine(UsesSphericalPixelBasis()
                ? "mat3 lfx_material_tangent_basis() { return lfxMaterialSphericalBasis; }"
                : "mat3 lfx_material_tangent_basis() { return mat3(effectMaterialVertexTangent, cross(effectMaterialVertexNormal, effectMaterialVertexTangent) * effectMaterialBasisDeterminant, effectMaterialVertexNormal); }");
    }
}
