using LumenFx.Compiler.Unreal.Attributes;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private JObject compiledMaterialProperties = new();

    private Expr DefaultAttribute(MaterialAttribute attribute)
    {
        MaterialAttributeInfo info = MaterialAttributes.Get(attribute);
        if (info.TexCoord >= 0) return Coordinates(new JObject { ["CoordinateIndex"] = info.TexCoord });
        if (attribute == MaterialAttribute.ShadingModel)
            return DefaultSourceShadingModel();
        if (attribute is MaterialAttribute.FrontMaterial or MaterialAttribute.MaterialAttributes)
            throw new ConversionException($"Attribute {attribute} requires its dedicated material type protocol");
        if (attribute is MaterialAttribute.Normal or MaterialAttribute.Tangent
                && compiledMaterialProperties.Value<bool?>("bTangentSpaceNormal") == false)
            return VertexBasis(attribute == MaterialAttribute.Tangent);
        if (attribute == MaterialAttribute.SubsurfaceColor
                && compiledShadingModel == "MSM_TwoSidedFoliage")
            return Vector(new[] { 0d, 0d, 0d });
        return info.Width == 1 ? Constant(info.DefaultValue[0]) : Vector(info.DefaultValue.Take(info.Width));
    }

    private ResolvedAttribute MaterialInput(Context context, SourceObject data, MaterialAttribute attribute, Func<Expr> defaultValue)
    {
        if (compiledMaterialProperties.Value<bool?>("bUseMaterialAttributes") == true)
            return AttributeInput(context, data.Properties["MaterialAttributes"], attribute);
        MaterialAttributeInfo info = MaterialAttributes.Get(attribute);
        JToken? input;
        if (info.TexCoord >= 0) {
            var fields = data.Raw["Data"] as JArray ?? throw new ConversionException("Material editor data has no property list");
            JObject? field = fields.OfType<JObject>().SingleOrDefault(value => value.Value<string>("Name") == "CustomizedUVs"
                    && value.Value<int>("ArrayIndex") == info.TexCoord);
            input = field is null ? null : SourceAsset.Value(field);
        } else if (attribute is MaterialAttribute.CustomData0 or MaterialAttribute.CustomData1) input = data.Properties[info.InputName];
        else input = data.Properties[attribute.ToString()];
        return new ResolvedAttribute(Input(context, input, defaultValue), Connected(input));
    }
}
