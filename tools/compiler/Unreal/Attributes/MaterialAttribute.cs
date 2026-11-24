namespace LumenFx.Compiler.Unreal.Attributes;

internal enum MaterialAttribute
{
    BaseColor, Metallic, Specular, Roughness, Anisotropy, EmissiveColor, Opacity, OpacityMask, Normal, Tangent,
    WorldPositionOffset, Displacement, SubsurfaceColor, CustomData0, CustomData1, AmbientOcclusion, Refraction,
    PixelDepthOffset, ShadingModel, SurfaceThickness, FrontMaterial, MaterialAttributes,
    CustomizedUV0, CustomizedUV1, CustomizedUV2, CustomizedUV3, CustomizedUV4, CustomizedUV5, CustomizedUV6, CustomizedUV7,
    DiffuseColor, SpecularColor, BentNormal, ClearCoatBottomNormal, CustomEyeTangent, TransmittanceColor,
    ThinTranslucentSurfaceCoverage, FirstPersonInterpolationAlpha, MaterialCache, TemporalResponsiveness, MotionVectorWorldOffset,
    WorldDisplacement, TessellationMultiplier
}

internal sealed record MaterialAttributeInfo(MaterialAttribute Attribute, string Id, int Width, IReadOnlyList<double> DefaultValue,
    bool Vertex = false, int TexCoord = -1)
{
    internal string InputName => Attribute switch {
        MaterialAttribute.CustomData0 => "ClearCoat", MaterialAttribute.CustomData1 => "ClearCoatRoughness",
        _ => Attribute.ToString()
    };
}

internal static class MaterialAttributes
{
    private static MaterialAttributeInfo Define(MaterialAttribute attribute, string id, int width, double x = 0,
        double y = 0, double z = 0, double w = 0, bool vertex = false, int texCoord = -1)
        => new(attribute, id, width, new[] { x, y, z, w }, vertex, texCoord);

    private static readonly MaterialAttributeInfo[] Definitions = {
        Define(MaterialAttribute.BaseColor, "69B8D33616ED4D499AA497292F050F7A", 3),
        Define(MaterialAttribute.Metallic, "57C3A1617F064296B00B24A5A496F34C", 1),
        Define(MaterialAttribute.Specular, "9FDAB39925564CC98CD2D572C12C8FED", 1, .5),
        Define(MaterialAttribute.Roughness, "D1DD967C4CAD47D39E6346FB08ECF210", 1, .5),
        Define(MaterialAttribute.Anisotropy, "55E2B4FBC1C54DB29F11875F7231EB1E", 1),
        Define(MaterialAttribute.EmissiveColor, "B769B54DD08D4440ABC21BA6CD27D0E2", 3),
        Define(MaterialAttribute.Opacity, "B8F50FBA2A754EC19EF672CFEB27BF51", 1, 1),
        Define(MaterialAttribute.OpacityMask, "679FFB172BB5422CAD520483166E0C75", 1, 1),
        Define(MaterialAttribute.Normal, "0FA2821A200F4A4AB719B789C1259C64", 3, 0, 0, 1),
        Define(MaterialAttribute.Tangent, "D5F8E9CFCDC3468DB10E4465596A7BBA", 3, 1),
        Define(MaterialAttribute.WorldPositionOffset, "F905F895D5814314916D24348C40CE9E", 3, vertex: true),
        Define(MaterialAttribute.Displacement, "199A7166C67041DCA68EAD0D7017D0AD", 1, -1),
        Define(MaterialAttribute.SubsurfaceColor, "5B8FC67951CE40829D777BEEF4F72C44", 3, 1, 1, 1),
        Define(MaterialAttribute.CustomData0, "9E502E693C8F48FA94645CFD28E5428D", 1, 1),
        Define(MaterialAttribute.CustomData1, "BE4F2FFD12FC4296B0124EEA12C28D92", 1, .1),
        Define(MaterialAttribute.AmbientOcclusion, "E8EBD0ADB1654CBEB079C3A8B39B9F15", 1, 1),
        Define(MaterialAttribute.Refraction, "D0B0FA0314D74455A851BAC581A0788B", 3, 1),
        Define(MaterialAttribute.PixelDepthOffset, "0AC97EC3E3D047BAB610167DC4D919FF", 1),
        Define(MaterialAttribute.ShadingModel, "D9423FFFD77E4D828FF9CF5E055D1255", 1),
        Define(MaterialAttribute.SurfaceThickness, "42BDD2E0BE714189A0984BC3DD0BE872", 1, .01f),
        Define(MaterialAttribute.FrontMaterial, "5973A03E13A74E0892D0CEDDF2936CF8", 0),
        Define(MaterialAttribute.MaterialAttributes, "E0ED040B82794D93BD2D59B2A5BBF41C", 0),
        Define(MaterialAttribute.CustomizedUV0, "D30EC284E13A416087BB52302ED115DC", 2, vertex: true, texCoord: 0),
        Define(MaterialAttribute.CustomizedUV1, "C67B093C2A5249AAABC97ADE4A1F49C5", 2, vertex: true, texCoord: 1),
        Define(MaterialAttribute.CustomizedUV2, "85C15B24F3E047CA8585687201AE0F4F", 2, vertex: true, texCoord: 2),
        Define(MaterialAttribute.CustomizedUV3, "777819DC31AE4676B864EF77B807E873", 2, vertex: true, texCoord: 3),
        Define(MaterialAttribute.CustomizedUV4, "DA63B233DDF44CADB93D867B8DAFDBCC", 2, vertex: true, texCoord: 4),
        Define(MaterialAttribute.CustomizedUV5, "C2F52B764A034388891195282071B190", 2, vertex: true, texCoord: 5),
        Define(MaterialAttribute.CustomizedUV6, "8214A8CA0CB944CF9DFD78DBE48BB55F", 2, vertex: true, texCoord: 6),
        Define(MaterialAttribute.CustomizedUV7, "D8F8D01FC6F74715A3CFB4FF9EF51FAC", 2, vertex: true, texCoord: 7),
        Define(MaterialAttribute.DiffuseColor, "68934E1B70EB411B86DF5AA5DF2F626C", 3),
        Define(MaterialAttribute.SpecularColor, "E89CBD8462EA48BE80F885212B0C403C", 3),
        Define(MaterialAttribute.BentNormal, "FBD7B46EB1234824BDE76B23609F984C", 3, 0, 0, 1),
        Define(MaterialAttribute.ClearCoatBottomNormal, "AA3D5C0416294716BBDEC8696A27DD72", 3, 0, 0, 1),
        Define(MaterialAttribute.CustomEyeTangent, "8EAB2CB273634A248CD14F473F9C8E55", 3),
        Define(MaterialAttribute.TransmittanceColor, "F2D8C70E42ECA0D14652D0ADB785A065", 3, .5, .5, .5),
        Define(MaterialAttribute.ThinTranslucentSurfaceCoverage, "AAA1D0E0E312458EF3556BADA01277CD", 1, 1),
        Define(MaterialAttribute.FirstPersonInterpolationAlpha, "DB380E51BDED47B6B185A8BE8EACBFA4", 1, 1, vertex: true),
        Define(MaterialAttribute.MaterialCache, "6B1237CA1B8C43C2A9C8A020430F1258", 4),
        Define(MaterialAttribute.TemporalResponsiveness, "B58CD3FB8CB4411D83C4C11EF89953AD", 1),
        Define(MaterialAttribute.MotionVectorWorldOffset, "48A2CEAB8E63473F821345BC6FA907F7", 3),
        Define(MaterialAttribute.WorldDisplacement, "2091ECA2B59248EE8E2CD578D371926D", 3, vertex: true),
        Define(MaterialAttribute.TessellationMultiplier, "A0119D44C456450D9C39C9331F72D8D1", 1, 1, vertex: true)
    };
    private static readonly IReadOnlyDictionary<MaterialAttribute, MaterialAttributeInfo> ByAttribute = Definitions.ToDictionary(value => value.Attribute);
    private static readonly IReadOnlyDictionary<string, MaterialAttributeInfo> ById = Definitions.ToDictionary(value => value.Id, StringComparer.OrdinalIgnoreCase);

    internal static MaterialAttributeInfo Get(MaterialAttribute attribute) => ByAttribute[attribute];

    internal static MaterialAttributeInfo FromId(string source)
    {
        // The exporter's GUID converter retains Unreal's four-word text order.
        string id = source.Trim().Trim('{', '}').Replace("-", "");
        if (id.Length != 32 || id.Any(value => !Uri.IsHexDigit(value)) || !ById.TryGetValue(id, out var info))
            throw new ConversionException($"Unknown material attribute ID {source}");
        return info;
    }

    internal static readonly MaterialAttribute[] BreakOutputs = {
        MaterialAttribute.BaseColor, MaterialAttribute.Metallic, MaterialAttribute.Specular, MaterialAttribute.Roughness,
        MaterialAttribute.Anisotropy, MaterialAttribute.EmissiveColor, MaterialAttribute.Opacity, MaterialAttribute.OpacityMask,
        MaterialAttribute.Normal, MaterialAttribute.Tangent, MaterialAttribute.WorldPositionOffset, MaterialAttribute.SubsurfaceColor,
        MaterialAttribute.CustomData0, MaterialAttribute.CustomData1, MaterialAttribute.AmbientOcclusion, MaterialAttribute.Refraction,
        MaterialAttribute.CustomizedUV0, MaterialAttribute.CustomizedUV1, MaterialAttribute.CustomizedUV2, MaterialAttribute.CustomizedUV3,
        MaterialAttribute.CustomizedUV4, MaterialAttribute.CustomizedUV5, MaterialAttribute.CustomizedUV6, MaterialAttribute.CustomizedUV7,
        MaterialAttribute.PixelDepthOffset, MaterialAttribute.ShadingModel, MaterialAttribute.Displacement
    };
}
