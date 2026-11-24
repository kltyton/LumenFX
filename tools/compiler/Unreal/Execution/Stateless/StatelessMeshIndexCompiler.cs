using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution.Stateless;

/** Mesh index tables retain the source sampler's reverse visitation and LIFO alias construction. */
internal static class StatelessMeshIndexCompiler
{
    internal static JObject? Compile(JObject source, StatelessModuleCompiler layout, StatelessDistributionCompiler distributions, StatelessDataBuilder data)
    {
        int component = layout.Offset("MeshIndex", 1);
        if (component < 0) return null;
        JObject range = distributions.IntegerRange(source["MeshIndex"], 0);
        int parameterOffset = range.Value<int>("parameter_offset"), minimum = range.Value<int>("minimum"), maximum = range.Value<int>("maximum");
        int index = parameterOffset >= 0 ? parameterOffset | int.MinValue : minimum, offset = 0, last = 0;
        int span = unchecked(maximum - minimum);
        if (parameterOffset < 0 && span > 0 && span < 256) {
            int count = span + 1;
            JArray weights = source["MeshIndexWeight"] as JArray ?? new();
            float[] probability = new float[count], normalized = new float[count];
            int[] alias = new int[count];
            float total = 0f;
            for (int entry = 0; entry < count; entry++) {
                float weight = weights.Count == 0 ? 1f : entry < weights.Count ? (float)SourceAsset.Number(weights[entry]) : 0f;
                normalized[entry] = 0f < weight ? weight : 0f;
                total += normalized[entry];
            }
            float scale = total != 0f ? count / total : 0f;
            Stack<int> small = new(count), large = new(count);
            for (int entry = count - 1; entry >= 0; entry--) {
                normalized[entry] *= scale;
                if (normalized[entry] < 1f) small.Push(entry); else large.Push(entry);
            }
            while (small.Count != 0 && large.Count != 0) {
                int smallIndex = small.Pop(), largeIndex = large.Pop();
                probability[smallIndex] = normalized[smallIndex]; alias[smallIndex] = largeIndex;
                normalized[largeIndex] = (normalized[largeIndex] + normalized[smallIndex]) - 1f;
                if (normalized[largeIndex] < 1f) small.Push(largeIndex); else large.Push(largeIndex);
            }
            foreach (int entry in large) { probability[entry] = 1f; alias[entry] = entry; }
            foreach (int entry in small) { probability[entry] = 1f; alias[entry] = entry; }
            float[] table = new float[count * 2];
            for (int entry = 0; entry < count; entry++) {
                table[entry * 2] = probability[entry]; table[entry * 2 + 1] = unchecked(minimum + alias[entry]);
            }
            offset = data.AddStatic(StatelessDistributionCompiler.Words(table)); last = count - 1;
        }
        return new JObject { ["component"] = component, ["index"] = unchecked((uint)index), ["table_offset"] = offset, ["table_last"] = last };
    }
}
