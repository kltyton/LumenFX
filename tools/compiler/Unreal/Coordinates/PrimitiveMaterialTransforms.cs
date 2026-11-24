using System.Text;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private bool primitiveMaterialMatricesUsed;
    private bool volumeMaterialAffineUsed;

    private string PrimitiveLinear(MaterialSpace space, bool toWorld, bool? previousFrame = null)
    {
        primitiveMaterialMatricesUsed = true;
        bool previous = previousFrame ?? (compilingPreviousFrame && (space == MaterialSpace.Local || toWorld));
        string prefix = previous ? "PrimitivePrevious" : "PrimitiveCurrent";
        return PrimitiveMatrix(prefix, false);
    }

    private Expr PrimitiveMaterialTransform(MaterialSpace space, Expr input, bool toWorld, bool position, bool? previousFrame = null)
    {
        if (decalCoordinates) {
            if (space != MaterialSpace.Local || !toWorld)
                throw new ConversionException("Deferred decal instance transforms require the primitive scene-data contract");
            string transformed = $"(DecalLocalToWorld * vec4({input.Code}, {(position ? "1.0" : "0.0")})).xyz.xzy * 100.0";
            return position ? AbsolutePosition(transformed, false) : new Expr(transformed, 3);
        }
        if (volumeCoordinates) {
            string matrix = toWorld ? "VolumeMaterialLocalToWorld" : "VolumeMaterialWorldToLocal";
            if (!position) return new Expr($"(mat3({matrix}) * {input.Code})", 3);
            materialViewUsed |= 8;
            volumeMaterialAffineUsed = true;
            string code = $"lfx_primitive_affine(mat3({matrix}), {input.Code}, {matrix}[3].xyz)";
            return toWorld ? new Expr($"lfx_lwc_promote({code})", 3, LargeWorld: true) : new Expr(code, 3);
        }
        primitiveMaterialMatricesUsed = true;
        bool previous = previousFrame ?? (compilingPreviousFrame && (space == MaterialSpace.Local || toWorld));
        string prefix = previous ? "PrimitivePrevious" : "PrimitiveCurrent";
        string linear = PrimitiveMatrix(prefix, !toWorld);
        if (!position) return new Expr($"({linear} * {input.Code})", 3);
        materialViewUsed |= 8;
        string highOffset = "(PrimitivePositionHigh.xyz - PrimitivePositionTile.xyz * 2097152.0)";
        if (toWorld)
            return new Expr($"LfxLWC3(PrimitivePositionTile.xyz, lfx_primitive_affine({linear}, {input.Code}, {prefix}3.xyz + {highOffset}))",
                    3, LargeWorld: true);
        string tile = compilingVertex ? "PrimitivePositionTile.xyz"
                : "(PrimitivePositionHigh.xyz * 0.000000476837158203125)";
        string relative = $"lfx_lwc_demote(LfxLWC3(({Promote(input)}).Tile - {tile}, ({Promote(input)}).Offset))";
        string translation = compilingVertex
                ? $"lfx_primitive_affine({linear}, -{highOffset}, {prefix}Inverse3.xyz)" : $"{prefix}Inverse3.xyz";
        return new Expr($"lfx_primitive_affine({linear}, {relative}, {translation})", 3);
    }

    private static string PrimitiveMatrix(string prefix, bool inverse)
    {
        string name = prefix + (inverse ? "Inverse" : "");
        return $"mat3({name}0.xyz, {name}1.xyz, {name}2.xyz)";
    }

    private Expr PrimitiveMaterialProperty(string code, int width, bool largeWorld = false)
    {
        if (decalCoordinates || lightFunctionCoordinates || volumeCoordinates)
            throw new ConversionException("The current material domain does not bind Niagara surface primitive properties");
        primitiveMaterialMatricesUsed = true;
        return ZeroGradient(new Expr(code, width, LargeWorld: largeWorld));
    }

    private Expr ObjectMaterialPosition(JObject properties)
    {
        string origin = (properties.Value<string>("OriginType") ?? "Absolute").Split("::").Last();
        if (compilingVertex && meshCoordinates && origin is ("Absolute" or "0"))
            return ZeroGradient(MeshMaterialTransform(MaterialSpace.Instance, Vector([0, 0, 0]), true, true));
        if (lightFunctionCoordinates && origin is ("CameraRelative" or "1"))
            return ZeroGradient(new Expr("LightFunctionObjectRelative.xyz", 3));
        materialViewUsed |= 8;
        string prefix = decalCoordinates ? "DecalObject" : lightFunctionCoordinates ? "LightFunctionObject"
                : volumeCoordinates ? "VolumeObject" : "PrimitiveObject";
        var (code, absolute) = origin switch {
            "Absolute" or "0" => ($"LfxLWC3({prefix}Tile.xyz, {prefix}Offset.xyz)", true),
            "CameraRelative" or "1" => ($"lfx_lwc_demote(LfxLWC3({prefix}Tile.xyz - MaterialWorldTile.xyz, {prefix}Offset.xyz + MaterialPreViewTranslation.xyz))", false),
            _ => throw new ConversionException($"Unknown object position origin {origin}")
        };
        return decalCoordinates || lightFunctionCoordinates || volumeCoordinates ? ZeroGradient(new Expr(code, 3, LargeWorld: absolute))
            : PrimitiveMaterialProperty(code, 3, absolute);
    }

    private Expr ActorMaterialPosition(JObject properties)
    {
        string origin = (properties.Value<string>("OriginType") ?? "Absolute").Split("::").Last();
        bool absolute = origin switch {
            "Absolute" or "0" => true,
            "CameraRelative" or "1" => false,
            _ => throw new ConversionException($"Unknown actor position origin {origin}")
        };
        materialViewUsed |= 8;
        if (decalCoordinates && absolute) return ObjectMaterialPosition(properties);
        string prefix = volumeCoordinates ? "VolumeActor" : "PrimitiveActor";
        string raw = decalCoordinates || lightFunctionCoordinates ? "LfxLWC3(vec3(0.0), vec3(0.0))"
                : $"LfxLWC3({prefix}Tile.xyz, {prefix}Offset.xyz)";
        Expr actor = volumeCoordinates || decalCoordinates || lightFunctionCoordinates
                ? new Expr(raw, 3, LargeWorld: true) : PrimitiveMaterialProperty(raw, 3, true);
        if (compilingVertex && compilingPreviousFrame) {
            Expr local = meshCoordinates
                    ? MeshMaterialTransform(MaterialSpace.Instance, actor, false, true)
                    : PrimitiveMaterialTransform(MaterialSpace.Instance, actor, false, true, false);
            actor = meshCoordinates
                    ? MeshMaterialTransform(MaterialSpace.Instance, local, true, true)
                    : PrimitiveMaterialTransform(MaterialSpace.Instance, local, true, true, true);
        }
        return ZeroGradient(absolute ? actor : new Expr(
                $"lfx_lwc_demote(lfx_lwc_add({actor.RawCode}, LfxLWC3(-MaterialWorldTile.xyz, MaterialPreViewTranslation.xyz)))", 3));
    }

    private Expr ObjectMaterialOrientation()
    {
        if (decalCoordinates) return ZeroGradient(new Expr("DecalOrientationValue.xzy", 3));
        if (volumeCoordinates) return ZeroGradient(new Expr("VolumeObjectOrientation.xyz", 3));
        const string z = "PrimitiveCurrent2.xyz";
        return PrimitiveMaterialProperty($"(dot({z}, {z}) >= 1e-8 ? normalize({z}) : vec3(0.0))", 3);
    }

    private Expr ObjectMaterialExtent(bool radius)
    {
        string component = radius ? "w" : "xyz";
        int width = radius ? 1 : 3;
        return volumeCoordinates ? ZeroGradient(new Expr($"VolumeObjectExtentRadius.{component}", width))
                : PrimitiveMaterialProperty($"PrimitiveObjectExtentRadius.{component}", width);
    }

    private Expr ObjectMaterialLocalBounds(int output)
    {
        return LocalBoundsProperty(volumeCoordinates ? "VolumeBounds" : "PrimitiveLocalBounds", output, "object local bounds");
    }

    private Expr MaterialBounds(JObject properties, int output)
    {
        string rawType = (properties.Value<string>("Type") ?? "MEILB_InstanceLocal").Split("::").Last();
        string prefix = rawType switch {
            "MEILB_ObjectLocal" or "1" => volumeCoordinates ? "VolumeBounds" : "PrimitiveLocalBounds",
            "MEILB_InstanceLocal" or "0" or "MEILB_PreSkinnedLocal" or "2" => volumeCoordinates ? "VolumeBounds" : "PrimitiveInstanceBounds",
            _ => throw new ConversionException($"Unknown bounds type {rawType}")
        };
        return LocalBoundsProperty(prefix, output, "bounds");
    }

    private Expr PreSkinnedMaterialLocalBounds(int output)
    {
        if (decalCoordinates || lightFunctionCoordinates)
            throw new ConversionException("MaterialExpressionPreSkinnedLocalBounds is only supported in Surface and Volume domains");
        return LocalBoundsProperty(volumeCoordinates ? "VolumeBounds" : "PrimitiveInstanceBounds", output, "pre-skinned local bounds");
    }

    private Expr LocalBoundsProperty(string prefix, int output, string label)
    {
        string minimum = prefix + "Min.xyz", maximum = prefix + "Max.xyz";
        string extent = $"({maximum} - {minimum})";
        string code = output switch {
            0 => extent + " * 0.5",
            1 => extent,
            2 => minimum,
            3 => maximum,
            _ => throw new ConversionException($"Invalid {label} output {output}")
        };
        return volumeCoordinates ? ZeroGradient(new Expr(code, 3)) : PrimitiveMaterialProperty(code, 3);
    }

    private Expr PrimitiveCustomMaterialParameter(JObject properties, int width)
    {
        const int primitiveFloats = 36;
        int index = properties.Value<int?>("PrimitiveDataIndex") ?? 0;
        if (index < 0 || index >= primitiveFloats)
            throw new ConversionException($"Invalid custom primitive material data index {index}");
        string field = volumeCoordinates ? "VolumeCustomPrimitiveData" : "PrimitiveCustomData";
        var components = new string[width];
        for (int i = 0; i < width; i++) {
            int slot = index + i;
            components[i] = slot >= primitiveFloats || decalCoordinates || lightFunctionCoordinates
                    ? "0.0" : $"{field}[{slot / 4}][{slot % 4}]";
        }
        string code = width == 1 ? components[0] : $"vec{width}({string.Join(", ", components)})";
        return volumeCoordinates || decalCoordinates || lightFunctionCoordinates
                ? ZeroGradient(new Expr(code, width)) : PrimitiveMaterialProperty(code, width);
    }

    private void AppendPrimitiveMaterialMatrices(StringBuilder shader)
    {
        if (!primitiveMaterialMatricesUsed) return;
        shader.AppendLine("layout(std140) uniform MaterialPrimitive {");
        foreach (string prefix in new[] { "PrimitiveCurrent", "PrimitiveCurrentInverse", "PrimitivePrevious", "PrimitivePreviousInverse" })
            for (int row = 0; row < 4; row++) shader.AppendLine($"vec4 {prefix}{row};");
        shader.AppendLine("vec4 PrimitivePositionHigh; vec4 PrimitivePositionTile;");
        shader.AppendLine("vec4 PrimitiveObjectTile; vec4 PrimitiveObjectOffset; vec4 PrimitiveObjectExtentRadius;");
        shader.AppendLine("vec4 PrimitiveLocalBoundsMin; vec4 PrimitiveLocalBoundsMax;");
        shader.AppendLine("vec4 PrimitiveActorTile; vec4 PrimitiveActorOffset;");
        shader.AppendLine("vec4 PrimitiveCustomData[9];");
        shader.AppendLine("vec4 PrimitiveInstanceBoundsMin; vec4 PrimitiveInstanceBoundsMax; };");
        AppendPrimitiveMaterialAffine(shader);
    }

    private static void AppendPrimitiveMaterialAffine(StringBuilder shader)
    {
        shader.AppendLine("vec3 lfx_primitive_affine(mat3 linear, vec3 position, vec3 translation) { return (position.x * linear[0] + position.y * linear[1] + position.z * linear[2]) + translation; }");
    }
}
