using Newtonsoft.Json.Linq;
using LumenFx.Compiler.Unreal.Attributes;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private sealed record FunctionInputBinding(Context Caller, JObject? Connection,
        Func<MaterialAttribute, AttributeResult>? Attributes = null);
    private sealed record FunctionScope(Context Context, SourceObject Owner, JArray Outputs);
    private readonly Dictionary<(int Context, int Call), FunctionScope> functionScopes = new();

    private FunctionScope GetFunctionScope(Context caller, SourceObject call)
    {
        var key = (caller.Id, call.Index);
        if (functionScopes.TryGetValue(key, out var cached)) return cached;
        JObject properties = call.Properties;
        string path = caller.Asset.ObjectPath(properties.Value<int>("MaterialFunction"));
        var resolved = ResolveFunction(caller, path);
        SourceAsset function = resolved.Asset;
        SourceObject owner = resolved.Owner;
        Dictionary<int, FunctionInputBinding> inputs = new();
        foreach (JObject input in properties["FunctionInputs"] as JArray ?? new JArray()) {
            string id = input.Value<string>("ExpressionInputId") ?? throw new ConversionException($"Missing function input ID: {path}");
            var target = function.Objects.SingleOrDefault(value => value.ClassName == "MaterialExpressionFunctionInput"
                    && function.IsWithin(value, owner) && value.Properties.Value<string>("Id") == id)
                ?? throw new ConversionException($"Unknown material function input {id}: {path}");
            if (input["Input"] is JObject connection && Connected(connection))
                inputs.Add(target.Index, new FunctionInputBinding(caller, connection));
        }
        var context = new Context(nextContext++, function, inputs, resolved.Chain,
                caller.ParameterAssociation, caller.ParameterIndex, resolved.Overrides, caller.LayerParameters);
        var scope = new FunctionScope(context, owner, properties["FunctionOutputs"] as JArray
                ?? throw new ConversionException($"Missing material function output bindings: {path}"));
        functionScopes.Add(key, scope);
        return scope;
    }

    private sealed record ResolvedFunction(SourceAsset Asset, SourceObject Owner, IReadOnlyList<string> Chain,
        FunctionParameterOverrides Overrides, bool Instance);

    private ResolvedFunction ResolveFunction(Context caller, string path)
    {
        List<string> chain = new(caller.FunctionChain);
        FunctionParameterOverrides overrides = new(caller.FunctionOverrides);
        bool instance = false;
        while (true) {
            if (chain.Contains(path, StringComparer.Ordinal)) throw new ConversionException($"Recursive material function definition: {path}");
            chain.Add(path);
            SourceAsset asset = library.Load(path);
            SourceObject owner = asset.ResolveObject(path, value => value.ClassName.StartsWith("MaterialFunction", StringComparison.Ordinal));
            if (!owner.ClassName.EndsWith("Instance", StringComparison.Ordinal)) return new ResolvedFunction(asset, owner, chain, overrides, instance);
            instance = true;
            overrides.Read(asset, owner);
            int parent = owner.Properties.Value<int?>("Parent") ?? 0;
            if (parent == 0) throw new ConversionException($"Material function instance has no parent: {path}");
            path = asset.ObjectPath(parent);
        }
    }

    private SourceObject GetFunctionOutput(FunctionScope scope, int output)
    {
        if (output < 0 || output >= scope.Outputs.Count) throw new ConversionException("Material function output index is outside its binding list");
        string id = scope.Outputs[output].Value<string>("ExpressionOutputId")
                ?? throw new ConversionException("Missing material function output ID");
        return scope.Context.Asset.Objects.SingleOrDefault(value => value.ClassName == "MaterialExpressionFunctionOutput"
                && scope.Context.Asset.IsWithin(value, scope.Owner) && value.Properties.Value<string>("Id") == id)
            ?? throw new ConversionException($"Unknown material function output {id}: {scope.Context.Asset.Name}");
    }

    private Expr Function(Context caller, SourceObject call, int output)
    {
        FunctionScope scope = GetFunctionScope(caller, call);
        return Node(scope.Context, GetFunctionOutput(scope, output).Index);
    }

    private Expr FunctionInput(Context context, SourceObject node)
    {
        JObject properties = node.Properties;
        string type = (properties.Value<string>("InputType") ?? "FunctionInput_Vector3").Split("::").Last();
        if (type == "FunctionInput_MaterialAttributes")
            throw new ConversionException($"Material attributes require an attribute property request: {context.Asset.Name}:{node.Name}");
        Expr value;
        if (context.Inputs.TryGetValue(node.Index, out var binding)) {
            if (binding.Attributes is not null) throw new ConversionException($"Material attributes bound to numeric function input: {node.Name}");
            value = Input(binding.Caller, binding.Connection, () => throw new ConversionException("A connected function argument has no expression"));
        } else {
            if (properties.Value<bool?>("bUsePreviewValueAsDefault") != true)
                throw new ConversionException($"Unbound required material function input {context.Asset.Name}:{node.Name}");
            value = Input(context, properties["Preview"], () => type switch {
                "FunctionInput_Scalar" => Constant(Color(properties["PreviewValue"], 1)[0]),
                "FunctionInput_Vector2" or "FunctionInput_Vector3" or "FunctionInput_Vector4"
                    => Vector(Color(properties["PreviewValue"], FunctionInputWidth(type))),
                "FunctionInput_StaticBool" or "FunctionInput_Bool"
                    => StaticBool(properties["PreviewValue"] is JToken pv && pv.Type == JTokenType.Boolean
                        ? pv.Value<bool>()
                        : Color(properties["PreviewValue"], 1)[0] != 0.0),
                _ => throw new ConversionException($"Missing preview connection for {type}: {context.Asset.Name}:{node.Name}")
            });
        }
        if (type is "FunctionInput_Texture2D" or "FunctionInput_TextureCube" or "FunctionInput_TextureCubeArray"
            or "FunctionInput_Texture2DArray" or "FunctionInput_VolumeTexture" or "FunctionInput_TextureExternal")
            return value.Sampler ? value : throw new ConversionException($"Numeric value bound to texture input {context.Asset.Name}:{node.Name}");
        return Coerce(value, FunctionInputWidth(type));
    }

    private static int FunctionInputWidth(string type) => type switch {
        "FunctionInput_Scalar" or "FunctionInput_StaticBool" or "FunctionInput_Bool" => 1,
        "FunctionInput_Vector2" => 2, "FunctionInput_Vector3" => 3, "FunctionInput_Vector4" => 4,
        _ => throw new ConversionException($"Unsupported function input type {type}")
    };

    private static bool Connected(JToken? input) => input is JObject connection
            && (connection.Value<int?>("expression") is int expression && expression != 0 || connection.Value<bool?>("constant") == true);
}
