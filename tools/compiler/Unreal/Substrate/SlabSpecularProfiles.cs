using System.Text;
using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private readonly Dictionary<string, int> specularProfiles = new(SourceNameOrder.Instance);

    private Expr? CompileSlabSpecularProfile(Context context, SourceObject node)
    {
        int reference = node.Properties.Value<int?>("SpecularProfile") ?? 0;
        if (reference == 0) return null;
        string source = context.Asset.ObjectPath(reference);
        if (!specularProfiles.TryGetValue(source, out int index)) {
            index = specularProfiles.Count;
            specularProfiles.Add(source, index);
        }
        return new Expr($"float(LFX_SPECULAR_PROFILE_ID_{index})", 1);
    }

    private string[] SpecularProfileSources()
        => specularProfiles.OrderBy(profile => profile.Value).Select(profile => profile.Key).ToArray();

    private void AppendSpecularProfileLookup(StringBuilder shader)
    {
        if (specularProfiles.Count != 0)
            shader.AppendLine("#moj_import <lumenfx:substrate/specular_profile.glsl>");
    }
}
