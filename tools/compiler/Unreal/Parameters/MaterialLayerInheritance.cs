using Newtonsoft.Json.Linq;
using LumenFx.Compiler.Unreal.Localization;
using LumenFx.Compiler.Unreal.Parameters;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private enum LayerLinkState { Uninitialized, LinkedToParent, UnlinkedFromParent, NotFromParent }
    private sealed record LayerResolution(LayerStackSource Stack, int[] PreviousIndices, bool IndicesChanged);
    private sealed record ResolvedLayer(LayerStackSource Source, int Index, bool Visible, Guid Id, LayerLinkState Link);

    private static LayerResolution ResolveLayerParent(LayerStackSource child, LayerStackSource parent,
        Lazy<IReadOnlyDictionary<string, int>> textClasses)
    {
        JArray childLayers = LayerArray(child.Runtime, "Layers"), parentLayers = LayerArray(parent.Runtime, "Layers");
        ValidateLayout(child); ValidateLayout(parent);
        Guid[] childIds = LayerIds(child), parentIds = LayerIds(parent);
        JArray links = LayerArray(child.Editor, "LayerLinkStates");
        if (childIds.Length != childLayers.Count || links.Count != childLayers.Count || parentIds.Length != parentLayers.Count)
            throw new ConversionException("Material layer stack has inconsistent GUID/link arrays");
        var resolved = new List<ResolvedLayer>();
        var parentIndices = new List<int>();
        var deleted = new List<Guid>();
        Guid[] oldDeleted = LayerArray(child.Editor, "DeletedParentLayerGuids").Select(LayerGuid).ToArray();
        bool uninitialized = false;
        for (int index = 0; index < childLayers.Count; index++) {
            LayerLinkState link = LayerLink(links[index]);
            bool visible = LayerArray(child.Editor, "LayerStates")[index].Value<bool>();
            int parentIndex = -1;
            if (link == LayerLinkState.Uninitialized) {
                uninitialized = true;
                if (index == 0) parentIndex = parentLayers.Count > 0 ? 0 : -1;
                else for (int candidate = 1; candidate < parentLayers.Count; candidate++) {
                    if (!parentIndices.Contains(candidate) && SameLayerName(child, index, parent, candidate, textClasses.Value)) {
                        parentIndex = candidate; break;
                    }
                }
                if (parentIndex < 0) resolved.Add(Copy(child, index, visible, LayerLinkState.NotFromParent));
                else {
                    bool same = SourceNameOrder.Equal(childLayers[index].Value<string>(), parentLayers[parentIndex].Value<string>())
                        && (index == 0 || SourceNameOrder.Equal(LayerArray(child.Runtime, "Blends")[index - 1].Value<string>(),
                            LayerArray(parent.Runtime, "Blends")[parentIndex - 1].Value<string>()));
                    resolved.Add(same ? Copy(parent, parentIndex, visible, LayerLinkState.LinkedToParent)
                        : Copy(child, index, visible, LayerLinkState.UnlinkedFromParent) with { Id = parentIds[parentIndex] });
                }
            }
            else {
                if (childIds[index] == Guid.Empty) throw new ConversionException("Material layer has an invalid empty GUID");
                if (link == LayerLinkState.LinkedToParent) {
                    parentIndex = Array.IndexOf(parentIds, childIds[index]);
                    if (parentIndex < 0) continue;
                    resolved.Add(Copy(parent, parentIndex, visible, link));
                }
                else {
                    if (link == LayerLinkState.UnlinkedFromParent) parentIndex = Array.IndexOf(parentIds, childIds[index]);
                    resolved.Add(Copy(child, index, visible, parentIndex < 0 ? LayerLinkState.NotFromParent : LayerLinkState.UnlinkedFromParent));
                }
            }
            if (parentIndex >= 0 && parentIndices.Contains(parentIndex))
                throw new ConversionException("Multiple material layers link to the same parent layer");
            parentIndices.Add(parentIndex);
        }
        for (int parentIndex = 1; parentIndex < parentLayers.Count; parentIndex++) {
            if (parentIndices.Contains(parentIndex)) continue;
            if (oldDeleted.Contains(parentIds[parentIndex]) || uninitialized) {
                deleted.Add(parentIds[parentIndex]); continue;
            }
            int previous = parentIndex, insertion = -1;
            while (insertion < 0) { previous--; insertion = previous == 0 ? 0 : parentIndices.IndexOf(previous); }
            parentIndices.Insert(insertion + 1, parentIndex);
            resolved.Insert(insertion + 1, Copy(parent, parentIndex,
                LayerArray(parent.Editor, "LayerStates")[parentIndex].Value<bool>(), LayerLinkState.LinkedToParent));
        }
        JObject runtime = (JObject)child.Runtime.DeepClone(), editor = (JObject)child.Editor.DeepClone();
        runtime["Layers"] = new JArray(resolved.Select(layer => LayerArray(layer.Source.Runtime, "Layers")[layer.Index].DeepClone()));
        runtime["Blends"] = new JArray(resolved.Skip(1).Select(layer => layer.Index > 0
            ? LayerArray(layer.Source.Runtime, "Blends")[layer.Index - 1].DeepClone()
            : throw new ConversionException("Material background layer cannot be used as a blend layer")));
        editor["LayerStates"] = new JArray(resolved.Select(layer => layer.Visible));
        editor["LayerNames"] = new JArray(resolved.Select(layer => LayerArray(layer.Source.Editor, "LayerNames")[layer.Index].DeepClone()));
        editor["LayerGuids"] = new JArray(resolved.Select(layer => layer.Id.ToString("D")));
        editor["LayerLinkStates"] = new JArray(resolved.Select(layer => layer.Link.ToString()));
        editor["DeletedParentLayerGuids"] = new JArray(deleted.Select(id => id.ToString("D")));
        editor["RestrictToLayerRelatives"] = new JArray(resolved.Select(layer =>
            LayerArray(layer.Source.Editor, "RestrictToLayerRelatives")[layer.Index].DeepClone()));
        editor["RestrictToBlendRelatives"] = new JArray(resolved.Skip(1).Select(layer =>
            LayerArray(layer.Source.Editor, "RestrictToBlendRelatives")[layer.Index - 1].DeepClone()));
        Guid[] resolvedIds = resolved.Select(layer => layer.Id).ToArray();
        int[] remap = childIds.Select(id => Array.IndexOf(resolvedIds, id)).ToArray();
        bool changed = childLayers.Count != resolved.Count || oldDeleted.Length != deleted.Count
            || remap.Where((index, previous) => index != previous).Any();
        return new LayerResolution(new LayerStackSource(child.Asset, runtime, editor), remap, changed);

        static ResolvedLayer Copy(LayerStackSource source, int index, bool visible, LayerLinkState link) =>
            new(source, index, visible, LayerIds(source)[index], link);
    }

    private static void ValidateLayout(LayerStackSource source)
    {
        int count = LayerArray(source.Runtime, "Layers").Count;
        if (LayerArray(source.Runtime, "Blends").Count != Math.Max(0, count - 1))
            throw new ConversionException($"Material layer stack has an inconsistent blend count: {source.Asset.Name}");
        if (count == 0) return;
        foreach (string field in new[] { "LayerStates", "LayerNames", "LayerGuids", "LayerLinkStates", "RestrictToLayerRelatives" })
            if (LayerArray(source.Editor, field).Count != count)
                throw new ConversionException($"Material layer stack has inconsistent {field}: {source.Asset.Name}");
        if (LayerArray(source.Editor, "RestrictToBlendRelatives").Count != count - 1)
            throw new ConversionException($"Material layer stack has inconsistent blend restrictions: {source.Asset.Name}");
    }

    private static JArray LayerArray(JObject value, string field) => value[field] is null ? new JArray()
        : value[field] as JArray ?? throw new ConversionException($"Material layer {field} are not an array");

    private static Guid LayerGuid(JToken value) => Guid.TryParse(value.Value<string>(), out Guid id) ? id
        : throw new ConversionException("Material layer stack has an invalid GUID");

    private static Guid[] LayerIds(LayerStackSource source) => LayerArray(source.Editor, "LayerGuids").Select(LayerGuid).ToArray();

    private static LayerLinkState LayerLink(JToken value) => (value.Value<string>()?.Split("::").Last() ?? "Uninitialized") switch {
        "Uninitialized" or "0" => LayerLinkState.Uninitialized,
        "LinkedToParent" or "1" => LayerLinkState.LinkedToParent,
        "UnlinkedFromParent" or "2" => LayerLinkState.UnlinkedFromParent,
        "NotFromParent" or "3" => LayerLinkState.NotFromParent,
        _ => throw new ConversionException("Material layer stack has an invalid link state")
    };

    private static bool SameLayerName(LayerStackSource child, int index, LayerStackSource parent, int candidate,
        IReadOnlyDictionary<string, int> classes)
    {
        string childName = NativeLocalization.Identity(LayerArray(child.Editor, "LayerNames")[index]);
        string parentName = NativeLocalization.Identity(LayerArray(parent.Editor, "LayerNames")[candidate]);
        return classes[childName] == classes[parentName];
    }
}
