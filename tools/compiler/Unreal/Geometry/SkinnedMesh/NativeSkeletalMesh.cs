using System.Buffers.Binary;
using System.Text;
using LumenFx.Compiler.Unreal.Geometry.MeshBuild;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Geometry.SkinnedMesh;

internal static class NativeSkeletalMesh
{
    internal sealed record Package(byte[] Header, IReadOnlyDictionary<int, byte[]> StreamedLods);

    internal static async Task<Package> CollectAsync(string source, MeshBuildTools tools)
    {
        tools.RequireAbsolutePaths();
        if (tools.MeshDescriptionExecutable is null)
            throw new ConversionException($"The native skeletal mesh reader requires its executable: {source}");
        Directory.CreateDirectory(tools.WorkDirectory);
        string prefix = Path.Combine(tools.WorkDirectory, "skeletal_mesh_" + Guid.NewGuid().ToString("N"));
        string input = prefix + ".json", output = prefix + ".lfsk";
        try {
            var mounts = new JObject();
            if (tools.ContentRoots is not null)
                foreach (var entry in tools.ContentRoots.OrderBy(item => item.Key, StringComparer.Ordinal))
                    mounts[entry.Key] = entry.Value;
            var request = new JObject { ["mesh"] = source, ["mounts"] = mounts };
            await File.WriteAllTextAsync(input, request.ToString(Formatting.None), new UTF8Encoding(false)).ConfigureAwait(false);
            var run = await NativeMeshProcess.RunAsync(tools, input, output, "skeletal").ConfigureAwait(false);
            var data = await File.ReadAllBytesAsync(output).ConfigureAwait(false);
            if (data.Length < 88 || BinaryPrimitives.ReadUInt32LittleEndian(data) != 0x4b53464c
                    || BinaryPrimitives.ReadUInt32LittleEndian(data.AsSpan(4)) is not (2 or 3 or 4 or 5 or 6))
                throw new ConversionException($"Invalid native skeletal mesh payload: {source}");
            int lodCount = checked((int)BinaryPrimitives.ReadUInt32LittleEndian(data.AsSpan(8)));
            int inlineLods = checked((int)BinaryPrimitives.ReadUInt32LittleEndian(data.AsSpan(24)));
            if (lodCount < 1 || inlineLods < 0 || inlineLods > lodCount)
                throw new ConversionException($"Invalid native skeletal LOD counts: {source}");
            var streamed = new Dictionary<int, byte[]>();
            if (BinaryPrimitives.ReadUInt32LittleEndian(data.AsSpan(4)) == 5) {
                for (int lod = 0; lod < lodCount - inlineLods; lod++) {
                    string path = output + $".lod{lod}.lfsl";
                    var lodBytes = await File.ReadAllBytesAsync(path).ConfigureAwait(false);
                    if (lodBytes.Length == 0) throw new ConversionException($"Empty native streamed skeletal LOD {lod}: {source}");
                    streamed.Add(lod, lodBytes);
                }
            }
            run.Complete();
            return new Package(data, streamed);
        } finally {
            if (File.Exists(input)) File.Delete(input);
            if (File.Exists(output)) File.Delete(output);
            string pattern = Path.GetFileName(output) + ".lod*.lfsl";
            foreach (string sidecar in Directory.EnumerateFiles(tools.WorkDirectory, pattern)) File.Delete(sidecar);
        }
    }
}
