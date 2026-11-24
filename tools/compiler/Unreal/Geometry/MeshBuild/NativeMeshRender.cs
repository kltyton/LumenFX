using System.Text;
using LumenFx.Compiler.Unreal.Geometry.MeshDescription;
using LumenFx.Compiler.Unreal.Geometry.MeshBuild.Nanite;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Geometry.MeshBuild;

internal sealed record NativeMeshRenderResult(MeshSourceBounds Bounds, IReadOnlyList<MeshSourceBuilder.BuiltLod> Lods,
    byte[]? DistanceField, bool NaniteFallbackStripped, bool OmitsDefaultMeshBufferViews, int FirstInlineLod, int FirstAvailableLod,
    int FirstNonOptionalLod, bool CookedFallbackUnavailable, IReadOnlyDictionary<string, NativeMeshShaderAccess> ShaderAccess,
    IReadOnlyList<NativeMeshLodBufferPolicy> LodBufferPolicies, NativeNaniteMesh? Nanite);
internal sealed record NativeMeshShaderAccess(bool DefaultViews, bool FallbackAvailable);
internal sealed record NativeMeshCpuBufferAccess(bool Indices, bool Positions, bool Tangents, bool TextureCoordinates, bool Colors);
internal sealed record NativeMeshShaderBuffers(bool Indices, bool Positions, bool Tangents, bool TextureCoordinates, bool Colors);
internal sealed record NativeMeshLodBufferPolicy(NativeMeshCpuBufferAccess Cpu,
    IReadOnlyDictionary<string, NativeMeshShaderBuffers> GpuByFormat);

internal static class NativeMeshRender
{
    internal static bool Required(StaticMeshSourceData mesh) => mesh.Settings["NaniteSettings"]?.Value<bool?>("bEnabled") == true
        || mesh.Settings.Value<bool?>("bGenerateMeshDistanceField") == true;

    internal static async Task<StaticMeshSourceData> BuildAsync(StaticMeshSourceData mesh, MeshBuildTools tools)
    {
        if (mesh.Rendered is not null) return mesh;
        tools.RequireAbsolutePaths();
        if (tools.MeshDescriptionExecutable is null)
            throw new ConversionException("The native render mesh build requires its builder executable");
        Directory.CreateDirectory(tools.WorkDirectory);
        string prefix = Path.Combine(tools.WorkDirectory, "mesh_render_" + Guid.NewGuid().ToString("N"));
        string input = prefix + ".input", output = prefix + ".output";
        try {
            NativeMeshDescriptions.WriteInput(input, mesh, tools);
            var run = await NativeMeshProcess.RunAsync(tools, input, output, "render").ConfigureAwait(false);
            StaticMeshSourceData result = ReadOutput(output, mesh, tools.TargetPlatform);
            run.Complete();
            return result;
        } finally {
            if (File.Exists(input)) File.Delete(input);
            if (File.Exists(output)) File.Delete(output);
        }
    }

    private static StaticMeshSourceData ReadOutput(string path, StaticMeshSourceData source, string? targetPlatform)
    {
        using var reader = new BinaryReader(File.OpenRead(path), Encoding.UTF8);
        int magic = reader.ReadInt32(), version = reader.ReadInt32(), lodCount = reader.ReadInt32();
        if (magic != 0x524d4e4c || version is < 1 or > 8 || lodCount != source.Lods.Count)
            throw new ConversionException("Native render mesh output header disagrees");
        if (targetPlatform is not null && version < 4)
            throw new ConversionException("The native mesh builder does not support the selected target platform");
        bool fallbackStripped = false;
        bool omitsDefaultMeshBufferViews = false;
        bool cookedFallbackUnavailable = false;
        int firstInlineLod = 0, firstAvailableLod = 0, firstNonOptionalLod = 0;
        if (version >= 4) {
            uint flags = reader.ReadUInt32();
            uint supportedFlags = version >= 5 ? 15u : 7u;
            if ((flags & ~supportedFlags) != 0 || (flags & 10u) != 0 && (flags & 1u) == 0)
                throw new ConversionException("Native render mesh resource flags disagree");
            fallbackStripped = (flags & 2) != 0;
            omitsDefaultMeshBufferViews = (flags & 4) != 0;
            cookedFallbackUnavailable = (flags & 8) != 0;
            firstInlineLod = reader.ReadInt32();
            firstAvailableLod = reader.ReadInt32();
            firstNonOptionalLod = version >= 6 ? reader.ReadInt32() : Math.Min(firstAvailableLod, firstInlineLod);
            if (firstInlineLod < 0 || firstInlineLod >= lodCount || firstAvailableLod < 0 || firstAvailableLod >= lodCount)
                throw new ConversionException("Native render mesh LOD cook bounds disagree");
            if (firstNonOptionalLod < Math.Min(firstAvailableLod, firstInlineLod) || firstNonOptionalLod > firstInlineLod)
                throw new ConversionException("Native render mesh optional LOD range disagrees");
        }
        var shaderAccess = new Dictionary<string, NativeMeshShaderAccess>(StringComparer.OrdinalIgnoreCase);
        if (version >= 5) {
            int formats = reader.ReadInt32();
            if (formats < 0 || formats > 1024) throw new ConversionException("Invalid native mesh shader-format count");
            for (int index = 0; index < formats; index++) {
                int length = reader.ReadInt32();
                if (length < 1 || length > 1024) throw new ConversionException("Invalid native mesh shader-format name");
                string format = Encoding.UTF8.GetString(reader.ReadBytes(length));
                uint access = reader.ReadUInt32();
                if ((access & ~3u) != 0 || !shaderAccess.TryAdd(format, new((access & 1) != 0, (access & 2) != 0)))
                    throw new ConversionException("Invalid or duplicate native mesh shader-format access: " + format);
            }
        }
        MeshBoundVector origin = new(reader.ReadDouble(), reader.ReadDouble(), reader.ReadDouble());
        MeshBoundVector extent = new(reader.ReadDouble(), reader.ReadDouble(), reader.ReadDouble());
        double radius = reader.ReadDouble();
        foreach (double value in new[] { origin.X, origin.Y, origin.Z, extent.X, extent.Y, extent.Z, radius })
            if (!double.IsFinite(value)) throw new ConversionException("Native render mesh bounds are not finite");
        if (extent.X < 0 || extent.Y < 0 || extent.Z < 0 || radius < 0)
            throw new ConversionException("Native render mesh bounds have negative extent");
        var built = new List<MeshSourceBuilder.BuiltLod>();
        var lods = new List<StaticMeshSourceLod>();
        var lodBufferPolicies = new List<NativeMeshLodBufferPolicy>();
        var sections = new MeshSectionInfoMap(null);
        for (int lodIndex = 0; lodIndex < source.Lods.Count; lodIndex++) {
            float deviation = reader.ReadSingle(), pixelError = reader.ReadSingle();
            int vertices = reader.ReadInt32(), indexCount = reader.ReadInt32(), uvCount = reader.ReadInt32(), sectionCount = reader.ReadInt32();
            if (!float.IsFinite(deviation) || deviation < 0 || !float.IsFinite(pixelError) || vertices < 0 || indexCount < 0
                || indexCount % 3 != 0 || uvCount is < 0 or > 8 || sectionCount < 0)
                throw new ConversionException("Native render mesh counts or reduction settings disagree");
            bool? hasVertexColors = null;
            if (version >= 3) {
                int flag = reader.ReadInt32();
                if (flag is not (0 or 1) || (flag == 1 && vertices == 0))
                    throw new ConversionException("Native render mesh vertex color flag disagrees");
                hasVertexColors = flag == 1;
            }
            long required = ((long)vertices * (14 + 2L * uvCount) + indexCount) * 4 + (long)sectionCount * 16;
            if (required > reader.BaseStream.Length - reader.BaseStream.Position)
                throw new ConversionException("Truncated native render mesh channels");
            int vectorCount = checked(vertices * 3);
            float[] positions = Floats(reader, vectorCount), normals = Floats(reader, vectorCount), tangents = Floats(reader, vectorCount);
            float[] signs = Floats(reader, vertices), colors = Floats(reader, checked(vertices * 4));
            var coordinates = new List<float[]>(uvCount);
            for (int channel = 0; channel < uvCount; channel++) coordinates.Add(Floats(reader, checked(vertices * 2), true));
            int[] indices = new int[indexCount];
            for (int index = 0; index < indices.Length; index++) {
                indices[index] = reader.ReadInt32();
                if (indices[index] < 0 || indices[index] >= vertices) throw new ConversionException("Native render mesh index is out of range");
            }
            var geometrySections = new List<StaticMeshSection>(sectionCount);
            int nextIndex = 0;
            for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
                int material = reader.ReadInt32(), first = reader.ReadInt32(), count = reader.ReadInt32(), flags = reader.ReadInt32();
                if (first < 0 || count < 0 || count % 3 != 0 || first != (count == 0 ? 0 : nextIndex) || (flags & ~31) != 0)
                    throw new ConversionException("Native render mesh section range or flags disagree");
                nextIndex = checked(nextIndex + count);
                string slot = material >= 0 && material < source.Materials.Count ? source.Materials[material].ImportedName : "None";
                geometrySections.Add(new StaticMeshSection(sectionIndex, slot, first, count));
                sections.Set(lodIndex, sectionIndex, new MeshSectionInfo(material, (flags & 1) != 0, (flags & 2) != 0,
                    (flags & 4) != 0, (flags & 8) != 0, (flags & 16) != 0));
            }
            if (nextIndex != indexCount) throw new ConversionException("Native render mesh sections do not cover its indices");
            if (version >= 8) {
                uint cpuFlags = reader.ReadUInt32();
                if ((cpuFlags & ~31u) != 0) throw new ConversionException("Invalid native static mesh CPU buffer policy");
                int policyCount = reader.ReadInt32();
                if (policyCount < 0 || policyCount > 1024) throw new ConversionException("Invalid native static mesh GPU buffer policy count");
                var gpuByFormat = new Dictionary<string, NativeMeshShaderBuffers>(StringComparer.OrdinalIgnoreCase);
                for (int policyIndex = 0; policyIndex < policyCount; policyIndex++) {
                    int nameLength = reader.ReadInt32();
                    if (nameLength < 1 || nameLength > 1024) throw new ConversionException("Invalid native static mesh GPU policy format name");
                    string format = Encoding.UTF8.GetString(reader.ReadBytes(nameLength));
                    uint gpuFlags = reader.ReadUInt32();
                    if ((gpuFlags & ~31u) != 0 || !gpuByFormat.TryAdd(format, new((gpuFlags & 1) != 0,
                        (gpuFlags & 2) != 0, (gpuFlags & 4) != 0, (gpuFlags & 8) != 0, (gpuFlags & 16) != 0)))
                        throw new ConversionException("Invalid or duplicate native static mesh GPU policy format: " + format);
                }
                lodBufferPolicies.Add(new(new((cpuFlags & 1) != 0, (cpuFlags & 2) != 0, (cpuFlags & 4) != 0,
                    (cpuFlags & 8) != 0, (cpuFlags & 16) != 0), gpuByFormat));
            }
            var geometry = new StaticMeshGeometry(Enumerable.Range(0, vertices).ToArray(), positions, normals, tangents, signs,
                colors, coordinates, indices, geometrySections) { VertexIdKind = MeshVertexIdKind.RenderVertex };
            built.Add(new MeshSourceBuilder.BuiltLod(geometry, MeshSourceBounds.From(geometry), Array.Empty<int>(),
                InvalidMeshChannels.None, deviation, pixelError, hasVertexColors));
            lods.Add(source.Lods[lodIndex] with { Geometry = geometry });
        }
        byte[]? distanceField = null;
        if (version >= 2) {
            int size = reader.ReadInt32();
            if (size < 0 || size > reader.BaseStream.Length - reader.BaseStream.Position)
                throw new ConversionException("Native mesh distance field payload length disagrees");
            if (size != 0) distanceField = reader.ReadBytes(size);
        } else if (source.Settings.Value<bool?>("bGenerateMeshDistanceField") == true)
            throw new ConversionException("The native mesh builder does not export authored distance fields");
        NativeNaniteMesh? nanite = version >= 7 ? NativeNaniteMesh.Read(reader) : null;
        if (reader.BaseStream.Position != reader.BaseStream.Length) throw new ConversionException("Unexpected native render mesh output tail");
        var settings = (JObject)source.Settings.DeepClone();
        settings["SectionInfoMap"] = sections.ToJson();
        return source with { Settings = settings, Lods = lods,
            Rendered = new NativeMeshRenderResult(new MeshSourceBounds(origin, extent, radius), built, distanceField,
                fallbackStripped, omitsDefaultMeshBufferViews, firstInlineLod, firstAvailableLod,
                firstNonOptionalLod, cookedFallbackUnavailable, shaderAccess, lodBufferPolicies, nanite) };
    }

    private static float[] Floats(BinaryReader reader, int count, bool uv = false)
    {
        var values = new float[count];
        for (int index = 0; index < count; index++) {
            float value = reader.ReadSingle();
            if (float.IsNaN(value) || !uv && !float.IsFinite(value))
                throw new ConversionException("Invalid native render mesh channel value");
            values[index] = value;
        }
        return values;
    }
}
