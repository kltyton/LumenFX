using LumenFx.Compiler.Unreal.Attributes;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private sealed record AttributeResult(Expr? Value, bool Authored = true)
    {
        public bool Connected => Value is not null && Authored;
    }
    private sealed record ResolvedAttribute(Expr Value, bool Connected);
    private readonly Dictionary<(int Context, int Node, int Output, MaterialAttribute Attribute), AttributeResult> attributeCache = new();
    private readonly HashSet<(int Context, int Node, int Output, MaterialAttribute Attribute)> attributeVisiting = new();

    private ResolvedAttribute AttributeInput(Context context, JToken? token, MaterialAttribute attribute)
    {
        AttributeResult result = AttributeConnection(context, token, attribute);
        return new ResolvedAttribute(result.Value ?? DefaultAttribute(attribute), result.Connected);
    }

    private AttributeResult AttributeConnection(Context context, JToken? token, MaterialAttribute attribute)
    {
        if (token is not null && token.Type != JTokenType.Null && token is not JObject)
            throw new ConversionException("Material-attributes connection is not an expression input");
        if (token is not JObject input || input.Value<int>("expression") == 0) {
            if (inputConstant(token)) throw new ConversionException("A numeric constant cannot be connected to a material-attributes pin");
            return new AttributeResult(null);
        }
        if (input.Value<int>("mask") != 0) throw new ConversionException("Material-attributes pins cannot have numeric component masks");
        return AttributeNode(context, input.Value<int>("expression"), input.Value<int>("output"), attribute);

        static bool inputConstant(JToken? value) => value is JObject item && item.Value<bool?>("constant") == true;
    }

    private AttributeResult AttributeNode(Context context, int index, int output, MaterialAttribute attribute)
    {
        var key = (context.Id, index, output, attribute);
        if (attributeCache.TryGetValue(key, out var cached)) return cached;
        if (!attributeVisiting.Add(key)) throw new ConversionException($"Cyclic material attribute {attribute} at {context.Asset.Name}:{index}");
        try {
            SourceObject node = context.Asset.Object(index);
            JObject properties = node.Properties;
            if (attribute is MaterialAttribute.FrontMaterial or MaterialAttribute.MaterialAttributes)
                throw new ConversionException($"Attribute {attribute} requires its dedicated material type protocol");
            AttributeResult result = node.ClassName switch {
                "MaterialExpressionMakeMaterialAttributes" => MakeAttribute(context, node, attribute),
                "MaterialExpressionSetMaterialAttributes" => SetAttribute(context, properties, attribute),
                "MaterialExpressionBlendMaterialAttributes" or "MaterialExpressionLegacyBlendMaterialAttributes" => BlendAttribute(context, properties, attribute),
                "MaterialExpressionGetMaterialAttributes" when output == 0
                    => AttributeConnection(context, properties["MaterialAttributes"], attribute),
                "MaterialExpressionCustom" => CustomAttribute(context, node, output, attribute),
                "MaterialExpressionReroute" or "MaterialExpressionNamedRerouteDeclaration" or "MaterialExpressionNamedRerouteUsage"
                    => AttributeConnection(context, RerouteInput(context, node), attribute),
                "MaterialExpressionNaniteReplace" => NaniteReplaceAttribute(context, properties, attribute),
                "MaterialExpressionShadowReplace" => ShadowReplaceAttribute(context, properties, attribute),
                "MaterialExpressionSwitch" => MultiSwitchAttribute(context, node, properties, attribute),
                "MaterialExpressionStaticSwitch" => SwitchAttribute(context, properties, attribute),
                "MaterialExpressionStaticSwitchParameter" => StaticSwitchParameterAttribute(context, properties, attribute),
                "MaterialExpressionIf" => ConditionalAttribute(context, properties, attribute),
                "MaterialExpressionIfThenElse" => IfThenElseAttribute(context, properties, attribute),
                "MaterialExpressionMaterialFunctionCall" => FunctionAttribute(context, node, output, attribute),
                "MaterialExpressionMaterialAttributeLayers" => LayerAttribute(context, node, output, attribute),
                "MaterialExpressionFunctionInput" => FunctionInputAttribute(context, node, attribute),
                "MaterialExpressionFunctionOutput" when Connected(properties["A"])
                    => AttributeConnection(context, properties["A"], attribute),
                _ when TrySelectStaticSwitchInput(context, node, properties, out JToken? selected)
                    => AttributeConnection(context, selected, attribute),
                _ => throw new ConversionException($"Expected a material-attributes output at {context.Asset.Name}:{node.Name}, output {output}")
            };
            attributeCache.Add(key, result);
            return result;
        } finally { attributeVisiting.Remove(key); }
    }

    private AttributeResult MakeAttribute(Context context, SourceObject node, MaterialAttribute attribute)
    {
        MaterialAttributeInfo info = MaterialAttributes.Get(attribute);
        JToken? input;
        if (info.TexCoord >= 0) {
            // CustomizedUVs is a native fixed array; one serialized nonzero index must not become slot zero.
            var fields = node.Raw["Data"] as JArray ?? throw new ConversionException("Material attribute export has no property data");
            var field = fields.OfType<JObject>().SingleOrDefault(value => value.Value<string>("Name") == "CustomizedUVs"
                    && value.Value<int>("ArrayIndex") == info.TexCoord);
            input = field is null ? null : SourceAsset.Value(field);
        } else if (MaterialAttributes.BreakOutputs.Contains(attribute)) input = node.Properties[info.InputName];
        else return new AttributeResult(null);
        return Connected(input) ? new AttributeResult(Input(context, input,
                () => throw new ConversionException($"Connected attribute {attribute} has no expression"))) : new AttributeResult(null);
    }

    private AttributeResult SetAttribute(Context context, JObject properties, MaterialAttribute attribute)
    {
        var types = AttributeTypes(properties["AttributeSetTypes"]);
        JArray inputs = properties["Inputs"] switch {
            null or { Type: JTokenType.Null } => new JArray(new JObject()),
            JArray array => array,
            _ => throw new ConversionException("SetMaterialAttributes input list is not an array")
        };
        if (inputs.Count < types.Length + 1) throw new ConversionException("SetMaterialAttributes has fewer inputs than attribute IDs");
        int pin = Array.FindIndex(types, value => value.Attribute == attribute);
        if (pin >= 0 && Connected(inputs[pin + 1])) {
            Expr value = Input(context, inputs[pin + 1], () => throw new ConversionException("Missing material attribute override"));
            return new AttributeResult(Coerce(value, MaterialAttributes.Get(attribute).Width));
        }
        if (Connected(inputs[0])) return AttributeConnection(context, inputs[0], attribute);
        return new AttributeResult(DefaultAttribute(attribute), false);
    }

    private AttributeResult BlendAttribute(Context context, JObject properties, MaterialAttribute attribute)
    {
        MaterialAttributeInfo info = MaterialAttributes.Get(attribute);
        string field = info.Vertex ? "VertexAttributeBlendType" : "PixelAttributeBlendType";
        string mode = (properties.Value<string>(field) ?? "Blend").Split("::").Last();
        if (mode is "UseA" or "UseB") return AttributeConnection(context, properties[mode == "UseA" ? "A" : "B"], attribute);
        if (mode != "Blend") throw new ConversionException($"Unknown material attribute blend mode {mode}");
        var first = AttributeInput(context, properties["A"], attribute);
        var second = AttributeInput(context, properties["B"], attribute);
        Expr a = first.Value, b = second.Value;
        bool authored = first.Connected || second.Connected;
        if (a == b) return new AttributeResult(a, authored);
        Expr alpha = Coerce(RequiredInput(context, properties, "Alpha"), 1);
        if (attribute == MaterialAttribute.ShadingModel) {
            if (double.TryParse(alpha.Code, System.Globalization.NumberStyles.Float,
                    System.Globalization.CultureInfo.InvariantCulture, out double midpoint))
                return new AttributeResult(midpoint >= .5 ? b : a, authored);
            return new AttributeResult(Shared(ConditionalValue(alpha, Constant(.5), b, null, a, 0)), authored);
        }
        int width = ArithmeticWidth(a, b);
        return new AttributeResult(Shared(Interpolate(Cast(a, width), Cast(b, width), alpha)), authored);
    }

    private AttributeResult SwitchAttribute(Context context, JObject properties, MaterialAttribute attribute)
    {
        Expr condition = Input(context, properties["Value"], () => Constant(properties.Value<bool?>("DefaultValue") == true ? 1 : 0));
        if (double.TryParse(condition.Code, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out double constant))
            return AttributeConnection(context, properties[constant != 0 ? "A" : "B"], attribute);
        AttributeResult resultA = AttributeConnection(context, properties["A"], attribute);
        AttributeResult resultB = AttributeConnection(context, properties["B"], attribute);
        if (resultB.Value is null) return resultA;
        if (resultA.Value is null) return resultB;
        Expr a = resultA.Value, b = resultB.Value;
        bool authored = resultA.Connected || resultB.Connected;
        if (a == b) return new AttributeResult(a, authored);
        int width = ArithmeticWidth(a, b);
        return new AttributeResult(Shared(new Expr($"({condition.Code} != 0.0 ? {Cast(a, width).Code} : {Cast(b, width).Code})", width)), authored);
    }

    private AttributeResult FunctionAttribute(Context caller, SourceObject call, int output, MaterialAttribute attribute)
    {
        FunctionScope scope = GetFunctionScope(caller, call);
        return AttributeNode(scope.Context, GetFunctionOutput(scope, output).Index, 0, attribute);
    }

    private AttributeResult FunctionInputAttribute(Context context, SourceObject node, MaterialAttribute attribute)
    {
        JObject properties = node.Properties;
        string type = (properties.Value<string>("InputType") ?? "FunctionInput_Vector3").Split("::").Last();
        if (type != "FunctionInput_MaterialAttributes") throw new ConversionException($"Numeric function input used as material attributes: {node.Name}");
        if (context.Inputs.TryGetValue(node.Index, out var binding)) return binding.Attributes is { } attributes
                ? attributes(attribute) : AttributeConnection(binding.Caller, binding.Connection, attribute);
        if (properties.Value<bool?>("bUsePreviewValueAsDefault") != true) throw new ConversionException($"Unbound required material-attributes input: {node.Name}");
        if (Connected(properties["Preview"])) return AttributeConnection(context, properties["Preview"], attribute);
        return new AttributeResult(attribute == MaterialAttribute.EmissiveColor
                ? Vector(Color(properties["PreviewValue"], 3)) : DefaultAttribute(attribute), attribute == MaterialAttribute.EmissiveColor);
    }

    private Expr GetAttribute(Context context, JObject properties, int output)
    {
        var types = AttributeTypes(properties["AttributeGetTypes"]);
        if (output < 1 || output > types.Length) throw new ConversionException("Material-attributes output requires a property request");
        return AttributeInput(context, properties["MaterialAttributes"], types[output - 1].Attribute).Value;
    }

    private Expr BreakAttribute(Context context, JObject properties, int output)
    {
        if (output < 0 || output >= MaterialAttributes.BreakOutputs.Length) throw new ConversionException("Invalid BreakMaterialAttributes output");
        var attribute = MaterialAttributes.BreakOutputs[output];
        Expr value = AttributeInput(context, properties["MaterialAttributes"], attribute).Value;
        return attribute == MaterialAttribute.Refraction ? Swizzle(value, "rg") : value;
    }

    private static MaterialAttributeInfo[] AttributeTypes(JToken? token)
    {
        JArray values = token switch {
            null or { Type: JTokenType.Null } => new JArray(), JArray array => array,
            _ => throw new ConversionException("Material attribute type list is not an array")
        };
        MaterialAttributeInfo[] result = values.Select(value => MaterialAttributes.FromId(value.Value<string>()
                ?? throw new ConversionException("Material attribute ID is not a string"))).ToArray();
        if (result.Select(value => value.Attribute).Distinct().Count() != result.Length)
            throw new ConversionException("Duplicate material attribute IDs");
        return result;
    }

    private AttributeResult StaticSwitchParameterAttribute(Context context, JObject properties, MaterialAttribute attribute)
    {
        if (properties.Value<bool?>("DynamicBranch") == true) return DynamicSwitchAttribute(context, properties, attribute);
        ValidateStaticSwitchInputs(properties);
        return AttributeConnection(context, properties[StaticBoolValue(context, properties) ? "A" : "B"], attribute);
    }

    private AttributeResult ConditionalAttribute(Context context, JObject properties, MaterialAttribute attribute)
    {
        Expr a = RequiredInput(context, properties, "A");
        Expr b = Input(context, properties["B"], () => Constant(SourceAsset.Number(properties["ConstB"])));
        var greaterResult = AttributeConnection(context, properties["AGreaterThanB"], attribute);
        var equalResult = AttributeConnection(context, properties["AEqualsB"], attribute);
        var lessResult = AttributeConnection(context, properties["ALessThanB"], attribute);
        Expr greater = greaterResult.Value
                ?? throw new ConversionException($"If failed to compile AGreaterThanB attribute {attribute}");
        Expr less = lessResult.Value
                ?? throw new ConversionException($"If failed to compile ALessThanB attribute {attribute}");
        return new AttributeResult(Shared(ConditionalValue(a, b, greater, equalResult.Value, less,
                SourceAsset.Number(properties["EqualsThreshold"], .00001))),
                greaterResult.Connected || equalResult.Connected || lessResult.Connected);
    }

    private AttributeResult IfThenElseAttribute(Context context, JObject properties, MaterialAttribute attribute)
    {
        Expr condition = Input(context, properties["Condition"], () => StaticBool(false));
        if (TryReadConstantBool(condition, out bool constantCondition))
            return AttributeConnection(context, properties[constantCondition ? "True" : "False"], attribute);
        RequireExpressionConnection(context, properties["True"], "True");
        RequireExpressionConnection(context, properties["False"], "False");
        AttributeResult whenTrue = AttributeConnection(context, properties["True"], attribute);
        AttributeResult whenFalse = AttributeConnection(context, properties["False"], attribute);
        if (whenTrue.Value is null && whenFalse.Value is null) return new AttributeResult(null, false);
        bool authored = whenTrue.Connected || whenFalse.Connected;
        Expr a = whenTrue.Value ?? DefaultAttribute(attribute);
        Expr b = whenFalse.Value ?? DefaultAttribute(attribute);
        if (a == b) return new AttributeResult(a, authored);
        return new AttributeResult(Shared(MirTernary("Select", CastScalarToMirBoolean(condition), a, b)), authored);
    }

    private AttributeResult NaniteReplaceAttribute(Context context, JObject properties, MaterialAttribute attribute) =>
        PassSwitchAttribute(context, properties, "Nanite", "Default", MaterialPassSwitchFlags.NaniteReplace,
            "lfx_nanite_replace_state()", attribute);

    private AttributeResult ShadowReplaceAttribute(Context context, JObject properties, MaterialAttribute attribute) =>
        PassSwitchAttribute(context, properties, "Shadow", "Default", MaterialPassSwitchFlags.ShadowReplace,
            "lfx_shadow_replace_state()", attribute);

    private AttributeResult PassSwitchAttribute(Context context, JObject properties, string truePin, string falsePin,
            MaterialPassSwitchFlags flag, string condition, MaterialAttribute attribute)
    {
        RequireExpressionConnection(context, properties[falsePin], falsePin);
        RequireExpressionConnection(context, properties[truePin], truePin);
        AttributeResult whenTrue = AttributeConnection(context, properties[truePin], attribute);
        AttributeResult whenFalse = AttributeConnection(context, properties[falsePin], attribute);
        if (whenTrue.Value is null && whenFalse.Value is null) return new AttributeResult(null, false);
        bool authored = whenTrue.Connected || whenFalse.Connected;
        Expr a = whenTrue.Value ?? DefaultAttribute(attribute);
        Expr b = whenFalse.Value ?? DefaultAttribute(attribute);
        if (a == b) return new AttributeResult(a, authored);
        MarkPassSwitchFlag(flag);
        return new AttributeResult(Shared(SelectBooleanCondition(condition, a, b)), authored);
    }

    private AttributeResult MultiSwitchAttribute(Context context, SourceObject node, JObject properties, MaterialAttribute attribute)
    {
        var entries = (properties["Inputs"] as JArray)?.OfType<JObject>().ToArray() ?? Array.Empty<JObject>();
        if (entries.Length == 0)
            throw new ConversionException($"Material Switch expression has no inputs in {context.Asset.Name}:{node.Name}");
        for (int index = 0; index < entries.Length; index++) {
            string pinName = entries[index].Value<string>("InputName") ?? $"Input {index}";
            RequireExpressionConnection(context, entries[index]["Input"], pinName);
        }
        Expr selector = Input(context, properties["SwitchValue"], () => Constant(SourceAsset.Number(properties["ConstSwitchValue"])));
        selector = Cast(DemoteLargeWorld(selector), 1);
        if (double.TryParse(selector.Code, System.Globalization.NumberStyles.Float,
                System.Globalization.CultureInfo.InvariantCulture, out double constantSelector)) {
            for (int index = 0; index < entries.Length; index++) {
                if (constantSelector >= index && constantSelector < index + 1)
                    return AttributeConnection(context, entries[index]["Input"], attribute);
            }
            return AttributeInputOrConstant(context, properties["Default"], SourceAsset.Number(properties["ConstDefault"]), attribute);
        }
        Expr selectorShared = Shared(selector);
        AttributeResult fallback = AttributeInputOrConstant(context, properties["Default"], SourceAsset.Number(properties["ConstDefault"]), attribute);
        var branches = new AttributeResult[entries.Length];
        bool anyValue = fallback.Value is not null;
        bool authored = fallback.Connected;
        for (int index = 0; index < entries.Length; index++) {
            branches[index] = AttributeConnection(context, entries[index]["Input"], attribute);
            anyValue |= branches[index].Value is not null;
            authored |= branches[index].Connected;
        }
        if (!anyValue) return new AttributeResult(null, false);
        Expr acc = fallback.Value ?? DefaultAttribute(attribute);
        for (int index = branches.Length - 1; index >= 0; index--) {
            Expr branchValue = branches[index].Value ?? DefaultAttribute(attribute);
            string condition = $"({selectorShared.Code} >= {Number(index)} && {selectorShared.Code} < {Number(index + 1)})";
            acc = SelectBooleanCondition(condition, branchValue, acc);
        }
        return new AttributeResult(Shared(acc), authored);
    }

    private AttributeResult AttributeInputOrConstant(Context context, JToken? connection, double constantDefault, MaterialAttribute attribute)
    {
        if (Connected(connection)) return AttributeConnection(context, connection, attribute);
        return new AttributeResult(null, false);
    }
}
