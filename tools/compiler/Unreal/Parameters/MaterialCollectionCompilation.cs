using System.Text;
using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

internal sealed record CompiledMaterialCollection(string Uniform, MaterialParameterCollectionSource.Definition Source, int Stages);

public sealed partial class MaterialCompiler
{
    private readonly List<CompiledMaterialCollection> materialCollections = new();
    private bool collectionLwcUsed;

    private Expr CollectionParameter(Context context, JObject properties)
    {
        var (source, vector, component) = CollectionLocation(context, properties);
        int index = RegisterCollection(source);
        string code = $"lfxCollection{index}.Vectors[{vector}]";
        return component < 0 ? UniformExpression(code, 4) : UniformExpression(code + "." + "xyzw"[component], 1);
    }

    private (MaterialParameterCollectionSource.Definition Source, int Vector, int Component) CollectionLocation(Context context, JObject properties)
    {
        int reference = properties.Value<int?>("Collection") ?? 0;
        if (reference == 0) throw new ConversionException("CollectionParameter has invalid Collection");
        var source = MaterialParameterCollectionSource.Read(context.Asset, reference, library);
        string id = properties["ParameterId"] is { } parameterId ? MaterialParameterCollectionSource.GuidText(parameterId)
            : "00000000000000000000000000000000";
        var location = source.Location(id);
        if (location.Vector < 0) throw new ConversionException($"CollectionParameter has invalid parameter GUID {id} in {source.Path}");
        return (source, location.Vector, location.Component);
    }

    private int RegisterCollection(MaterialParameterCollectionSource.Definition source)
    {
        int index = materialCollections.FindIndex(value => SourceNameOrder.Equal(value.Source.Path, source.Path));
        int stage = compilingVertex ? 1 : 2;
        if (index < 0) {
            if (materialCollections.Count >= 2)
                throw new ConversionException("A material may only reference two different material parameter collections");
            index = materialCollections.Count;
            materialCollections.Add(new("MaterialCollection" + index, source, stage));
        } else materialCollections[index] = materialCollections[index] with { Stages = materialCollections[index].Stages | stage };
        return index;
    }

    private Expr CollectionTransform(Context context, JObject properties)
    {
        Expr input = RequiredInput(context, properties, "Input");
        if (input.Sampler || input.Width is not (3 or 4)) throw new ConversionException("CollectionTransform requires float3 or float4 input");
        var (source, vector, component) = CollectionLocation(context, properties);
        if (component >= 0) throw new ConversionException("CollectionTransform requires a vector collection parameter");
        string mode = (properties.Value<string>("TransformType") ?? "Position").Split("::").Last();
        mode = mode switch { "0" => "Position", "1" => "Vector", "2" => "Projection", "3" => "LocalToWorld", "4" => "WorldToLocal", _ => mode };
        int count = mode switch {
            "Vector" => 3, "Position" or "Projection" => 4, "LocalToWorld" or "WorldToLocal" => 5,
            _ => throw new ConversionException($"Unknown collection transform mode {mode}")
        };
        if (vector > source.VectorCount - count) throw new ConversionException($"CollectionTransform {mode} requires {count} consecutive vector rows");
        int index = RegisterCollection(source);
        string Row(int offset) => $"lfxCollection{index}.Vectors[{vector + offset}]";
        int width = mode == "Vector" ? 3 : input.Width;
        string code = input.Code;
        string tile = input.Width == 3 ? $"LfxLWC3({Row(4)}.xyz, vec3(0.0))" : $"LfxLWC4(vec4({Row(4)}.xyz, 0.0), vec4(0.0))";
        if (mode == "WorldToLocal") {
            collectionLwcUsed = true;
            code = $"lfx_lwc_demote(lfx_lwc_add({Promote(input)}, {tile}))";
        }
        if (mode == "Projection") return new Expr($"vec4(({code}).x * {Row(0)}.x, ({code}).y * {Row(1)}.y, ({code}).z * {Row(2)}.z + {Row(3)}.z, ({code}).z * {Row(2)}.w + {Row(3)}.w)", 4);
        string Mask(int offset) => Row(offset) + (width == 3 ? ".xyz" : "");
        string result = $"(({code}).z * {Mask(2)} + (({code}).y * {Mask(1)} + ({code}).x * {Mask(0)}))";
        if (mode != "Vector") result = input.Width == 3 ? $"({Mask(3)} + {result})" : $"(({code}).w * {Mask(3)} + {result})";
        if (mode == "LocalToWorld") {
            collectionLwcUsed = true;
            return new Expr($"lfx_lwc_add(lfx_lwc_promote({result}), {tile})", width, LargeWorld: true);
        }
        return new Expr(result, width);
    }

    private void AppendMaterialCollections(StringBuilder target, bool vertex)
    {
        for (int index = 0; index < materialCollections.Count; index++) {
            CompiledMaterialCollection collection = materialCollections[index];
            if ((collection.Stages & (vertex ? 1 : 2)) != 0)
                target.AppendLine($"layout(std140) uniform {collection.Uniform} {{ vec4 Vectors[{collection.Source.VectorCount}]; }} lfxCollection{index};");
        }
    }
}
