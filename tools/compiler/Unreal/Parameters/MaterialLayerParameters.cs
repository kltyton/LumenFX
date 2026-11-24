using Newtonsoft.Json.Linq;
using LumenFx.Compiler.Unreal.Localization;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private sealed record MaterialInstanceSource(SourceAsset Asset, SourceObject Instance);
    private readonly List<MaterialInstanceSource> materialInstanceSources = new();

    private sealed class MaterialLayerParameters
    {
        internal readonly Dictionary<(InstanceParameterType Type, MaterialParameterKey Key), JToken> Values = new();
        internal readonly Dictionary<MaterialParameterKey, JToken> ParentFonts = new();
        internal readonly Dictionary<MaterialParameterKey, bool> Switches = new();
        internal readonly Dictionary<MaterialParameterKey, bool> ParentSwitches = new();
        internal readonly Dictionary<MaterialParameterKey, int> Masks = new();
        internal LayerStackSource Stack { get; }

        internal MaterialLayerParameters(LayerStackSource defaults, IReadOnlyList<MaterialInstanceSource> instances, AssetLibrary library)
        {
            LayerStackSource[] stacks = new LayerStackSource[instances.Count + 1];
            int[]?[] previousIndices = new int[instances.Count][];
            LayerStackSource?[] authored = instances.Select(value => InstanceLayerSource(value.Asset, value.Instance)).ToArray();
            var textClasses = new Lazy<IReadOnlyDictionary<string, int>>(() => {
                NativeLocalization resolver = library.Localization ?? throw new ConversionException(
                    $"Legacy material layer names require fixed Unreal text resolution for {defaults.Asset.Name}. Supply --text-native-tools.");
                return resolver.Classify(defaults.Asset.Name, authored.OfType<LayerStackSource>().Append(defaults)
                    .SelectMany(stack => LayerArray(stack.Editor, "LayerNames").Skip(1)));
            });
            stacks[^1] = defaults;
            for (int depth = instances.Count - 1; depth >= 0; depth--) {
                LayerStackSource? own = authored[depth];
                if (own is null) {
                    stacks[depth] = Linked(stacks[depth + 1]);
                }
                else {
                    LayerResolution resolution = ResolveLayerParent(own, stacks[depth + 1], textClasses);
                    stacks[depth] = resolution.Stack;
                    previousIndices[depth] = resolution.IndicesChanged ? resolution.PreviousIndices : null;
                }
            }
            Stack = stacks[0];
            int[] rootIndices = Enumerable.Range(0, Layers(Stack).Count).ToArray();
            for (int depth = 0; depth < instances.Count; depth++) {
                var instance = instances[depth];
                foreach (var parameter in InstanceParameterValues(instance.Asset, instance.Instance)) {
                    if (Relocate(parameter.Key, previousIndices[depth]) is not { } relocated) continue;
                    foreach (var key in RootKeys(relocated, rootIndices)) {
                        Values.TryAdd((parameter.Type, key), parameter.Value);
                        if (depth != 0 && parameter.Type == InstanceParameterType.Font)
                            ParentFonts.TryAdd(key, parameter.Value);
                    }
                }
                foreach (var parameter in StaticParameterValues(instance.Asset, instance.Instance)) {
                    if (Relocate(parameter.Key, previousIndices[depth]) is not { } relocated) continue;
                    foreach (var key in RootKeys(relocated, rootIndices)) {
                        if (parameter.Mask is int bits) Masks.TryAdd(key, bits);
                        else if (parameter.Switch is bool enabled) {
                            Switches.TryAdd(key, enabled);
                            if (depth != 0) ParentSwitches.TryAdd(key, enabled);
                        }
                    }
                }
                if (depth + 1 == instances.Count) continue;
                int[] remap = ParentRemap(stacks[depth], stacks[depth + 1]);
                for (int root = 0; root < rootIndices.Length; root++) {
                    int index = rootIndices[root];
                    rootIndices[root] = index >= 0 && index < remap.Length ? remap[index] : -1;
                }
            }
        }

        private static MaterialParameterKey? Relocate(MaterialParameterKey parameter, int[]? previousIndices)
        {
            if (previousIndices is null || parameter.Association == "GlobalParameter") return parameter;
            bool blend = parameter.Association == "BlendParameter";
            int oldLayer = blend ? checked(parameter.Index + 1) : parameter.Index;
            if (oldLayer < 0 || oldLayer >= previousIndices.Length)
                throw new ConversionException($"Material parameter has an invalid layer index: {parameter.Name}:{parameter.Index}");
            int layer = previousIndices[oldLayer];
            return layer < 0 ? null : parameter with { Index = blend ? layer - 1 : layer };
        }

        private static IEnumerable<MaterialParameterKey> RootKeys(MaterialParameterKey parameter, int[] indices)
        {
            if (parameter.Association == "GlobalParameter") yield break;
            bool blend = parameter.Association == "BlendParameter";
            int target = blend ? checked(parameter.Index + 1) : parameter.Index;
            for (int root = blend ? 1 : 0; root < indices.Length; root++)
                if (indices[root] >= 0 && indices[root] == target) yield return parameter with { Index = blend ? root - 1 : root };
        }

        private static LayerStackSource Linked(LayerStackSource parent)
        {
            JObject editor = (JObject)parent.Editor.DeepClone();
            JArray links = editor["LayerLinkStates"] as JArray ?? new JArray();
            editor["LayerLinkStates"] = new JArray(Enumerable.Repeat("LinkedToParent", links.Count));
            return parent with { Editor = editor };
        }

        private static int[] ParentRemap(LayerStackSource child, LayerStackSource parent)
        {
            int count = Layers(child).Count;
            Guid[] childIds = LayerIds(child), parentIds = LayerIds(parent);
            JArray links = child.Editor["LayerLinkStates"] as JArray ?? new JArray();
            if (count > 0 && (childIds.Length != count || links.Count != count))
                throw new ConversionException("Material layer stack has inconsistent GUID/link arrays");
            int[] result = Enumerable.Repeat(-1, count).ToArray();
            for (int layer = 0; layer < count; layer++) {
                if (LayerLink(links[layer]) == LayerLinkState.LinkedToParent) result[layer] = Array.IndexOf(parentIds, childIds[layer]);
            }
            return result;
        }

        private static JArray Layers(LayerStackSource stack) => stack.Runtime["Layers"] as JArray ?? new JArray();
    }

    private static LayerStackSource DefaultLayerSource(SourceAsset asset, JObject? value)
    {
        JObject runtime = value is null ? new JObject() : (JObject)value.DeepClone();
        runtime["Layers"] ??= new JArray(0);
        runtime["Blends"] ??= new JArray();
        JObject editor = runtime["EditorOnly"] as JObject ?? new JObject();
        editor["LayerStates"] ??= new JArray(true);
        editor["LayerNames"] ??= new JArray("Background");
        editor["RestrictToLayerRelatives"] ??= new JArray(false);
        editor["RestrictToBlendRelatives"] ??= new JArray();
        editor["LayerGuids"] ??= new JArray("00000002-0000-0000-0000-000000000000");
        editor["LayerLinkStates"] ??= new JArray("NotFromParent");
        return NormalizeLayerSource(asset, runtime, editor);
    }
}
