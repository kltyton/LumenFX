using System.Diagnostics;
using System.Text;
using LumenFx.Compiler.Unreal.Geometry.MeshDescription;
using Newtonsoft.Json;

namespace LumenFx.Compiler.Unreal.Geometry.MeshBuild;

internal sealed record PreparedMeshLod(StaticMeshSourceLod Source, float MaximumDeviation, float PixelError,
    int BaseLod, bool HadSource, bool Reduced);

internal static class NativeMeshDescriptions
{
    internal static async Task<StaticMeshSourceData> PrepareAsync(StaticMeshSourceData mesh, MeshBuildTools tools)
    {
        if (mesh.PreparedLods is not null) return mesh;
        tools.RequireAbsolutePaths();
        if (tools.MeshDescriptionExecutable is null) throw new ConversionException("Native mesh description preparation executable is required");
        Directory.CreateDirectory(tools.WorkDirectory);
        string prefix = Path.Combine(tools.WorkDirectory, "mesh_description_" + Guid.NewGuid().ToString("N"));
        string input = prefix + ".input", output = prefix + ".output";
        try {
            WriteInput(input, mesh, tools);
            var run = await NativeMeshProcess.RunAsync(tools, input, output, "descriptions").ConfigureAwait(false);
            var prepared = ReadOutput(output, mesh, out var settings);
            run.Complete();
            return mesh with { Lods = prepared.Select(value => value.Source).ToArray(), PreparedLods = prepared, Settings = settings };
        } finally {
            if (File.Exists(input)) File.Delete(input);
            if (File.Exists(output)) File.Delete(output);
        }
    }

    internal static void WriteInput(string path, StaticMeshSourceData mesh, MeshBuildTools tools)
    {
        if (mesh.PreparedLods is not null || mesh.Rendered is not null)
            throw new ConversionException("Native mesh build requires original source description archives");
        SourceAsset owner = mesh.ArchiveOwner ?? throw new ConversionException("Mesh description has no source archive versions");
        using var writer = new BinaryWriter(File.Create(path), Encoding.UTF8);
        writer.Write(0x49444d4c); writer.Write(4);
        MeshArchiveVersions.Read(owner, tools.UnrealSourceRoot).Write(writer);
        writer.Write(mesh.Settings.Value<int?>("LightmapUVVersion") ?? 0);
        Text(writer, mesh.Settings.Value<string>("LODGroup") ?? "None");
        writer.Write(mesh.Materials.Count);
        foreach (var slot in mesh.Materials) Text(writer, slot.ImportedName);
        WriteSections(writer, new MeshSectionInfoMap(mesh.Settings["SectionInfoMap"]));
        WriteSections(writer, new MeshSectionInfoMap(mesh.Settings["OriginalSectionInfoMap"]));
        writer.Write(mesh.Lods.Count);
        foreach (var lod in mesh.Lods) WriteLod(writer, lod);
        var assetSettings = (Newtonsoft.Json.Linq.JObject)mesh.Settings.DeepClone();
        if (tools.TargetPlatform is { } targetPlatform) assetSettings["LumenFxMeshTargetPlatform"] = targetPlatform;
        if (mesh.NativeSettings?["NaniteSettings"] is { } nanite) assetSettings["NaniteSettings"] = nanite.DeepClone();
        assetSettings["bUseLegacyTangentScaling"] = mesh.Settings.Value<bool?>("legacy_tangent_scaling") ?? false;
        if (mesh.Settings.Value<bool?>("bGenerateMeshDistanceField") == true) {
            assetSettings["LumenFxDistanceFieldMeshName"] = mesh.Source;
            assetSettings["LumenFxDistanceFieldMaterials"] = new Newtonsoft.Json.Linq.JArray(mesh.Materials.Select(slot => slot.Material));
        }
        Text(writer, assetSettings.ToString(Formatting.None));
        writer.Write(tools.TriangleOrderOptimization);
        WriteLod(writer, mesh.HiResLod);
        writer.Write(tools.ContentRoots?.Count ?? 0);
        if (tools.ContentRoots is not null) foreach (var root in tools.ContentRoots.OrderBy(pair => pair.Key, StringComparer.Ordinal)) {
            Text(writer, root.Key); Text(writer, root.Value);
        }
        writer.Write(mesh.NativeObjectPaths.Count);
        foreach (string pathValue in mesh.NativeObjectPaths) Text(writer, pathValue);
    }

    private static void WriteLod(BinaryWriter writer, StaticMeshSourceLod? lod)
    {
        byte[] archive = lod?.Description?.SourceArchive ?? Array.Empty<byte>();
        if (lod?.Description is not null && archive.Length == 0)
            throw new ConversionException("Source mesh has no original description archive");
        writer.Write(archive.Length); writer.Write(archive);
        var build = lod?.Settings["BuildSettings"]?.DeepClone() as Newtonsoft.Json.Linq.JObject;
        if (build?["DistanceFieldReplacementMesh"]?.Type == Newtonsoft.Json.Linq.JTokenType.Integer)
            build.Remove("DistanceFieldReplacementMesh");
        Text(writer, build?.ToString(Formatting.None) ?? "{}");
        Text(writer, lod?.Settings["ReductionSettings"]?.ToString(Formatting.None) ?? "{}");
    }

    private static IReadOnlyList<PreparedMeshLod> ReadOutput(string path, StaticMeshSourceData source, out Newtonsoft.Json.Linq.JObject settings)
    {
        using var reader = new BinaryReader(File.OpenRead(path), Encoding.UTF8);
        if (reader.ReadInt32() != 0x4f444d4c || reader.ReadInt32() != 2 || reader.ReadInt32() != source.Lods.Count)
            throw new ConversionException("Native mesh description output header disagrees");
        settings = (Newtonsoft.Json.Linq.JObject)source.Settings.DeepClone();
        settings["SectionInfoMap"] = ReadSections(reader);
        settings["OriginalSectionInfoMap"] = ReadSections(reader);
        List<PreparedMeshLod> result = new();
        for (int index = 0; index < source.Lods.Count; index++) {
            float deviation = reader.ReadSingle(), pixelError = reader.ReadSingle();
            int basis = reader.ReadInt32(), flags = reader.ReadInt32(), size = reader.ReadInt32();
            if (!float.IsFinite(deviation) || deviation < 0 || !float.IsFinite(pixelError) || pixelError < 0
                    || basis < 0 || basis > index || (flags & ~3) != 0 || size < 0 || size > reader.BaseStream.Length - reader.BaseStream.Position)
                throw new ConversionException("Native mesh description output bounds disagree");
            byte[] archive = reader.ReadBytes(size);
            MeshDescriptionData description = MeshDescriptionReader.ReadNativeArchive(archive) with { SourceArchive = archive };
            var lodSettings = (Newtonsoft.Json.Linq.JObject)source.Lods[index].Settings.DeepClone();
            bool hadSource = (flags & 1) != 0;
            if (!hadSource && index > 0) lodSettings["BuildSettings"] = source.Lods[basis].Settings["BuildSettings"]?.DeepClone();
            var lod = new StaticMeshSourceLod(index, lodSettings, description, StaticMeshGeometryCompiler.Compile(description));
            result.Add(new PreparedMeshLod(lod, deviation, pixelError, basis, hadSource, (flags & 2) != 0));
        }
        if (reader.BaseStream.Position != reader.BaseStream.Length) throw new ConversionException("Unexpected native mesh description output tail");
        return result;
    }

    private static void Text(BinaryWriter writer, string value)
    {
        byte[] bytes = Encoding.UTF8.GetBytes(value);
        writer.Write(bytes.Length); writer.Write(bytes);
    }

    private static void WriteSections(BinaryWriter writer, MeshSectionInfoMap map)
    {
        var entries = map.Entries.ToArray();
        writer.Write(entries.Length);
        foreach (var pair in entries) {
            var info = pair.Value;
            uint flags = (info.Collision ? 1u : 0) | (info.CastShadow ? 2u : 0) | (info.VisibleInRayTracing ? 4u : 0)
                | (info.AffectDistanceFieldLighting ? 8u : 0) | (info.ForceOpaque ? 16u : 0);
            writer.Write(pair.Key); writer.Write(info.MaterialIndex); writer.Write(flags);
        }
    }

    private static Newtonsoft.Json.Linq.JObject ReadSections(BinaryReader reader)
    {
        int count = reader.ReadInt32();
        if (count < 0 || count > (reader.BaseStream.Length - reader.BaseStream.Position) / 12)
            throw new ConversionException("Native mesh section map bounds disagree");
        var pairs = new Newtonsoft.Json.Linq.JArray();
        var seen = new HashSet<uint>();
        for (int index = 0; index < count; index++) {
            uint key = reader.ReadUInt32(); int material = reader.ReadInt32(); uint flags = reader.ReadUInt32();
            if (!seen.Add(key) || (flags & ~31u) != 0) throw new ConversionException("Invalid native mesh section map entry");
            pairs.Add(new Newtonsoft.Json.Linq.JArray(key, new Newtonsoft.Json.Linq.JObject {
                ["MaterialIndex"] = material, ["bEnableCollision"] = (flags & 1) != 0, ["bCastShadow"] = (flags & 2) != 0,
                ["bVisibleInRayTracing"] = (flags & 4) != 0, ["bAffectDistanceFieldLighting"] = (flags & 8) != 0,
                ["bForceOpaque"] = (flags & 16) != 0 }));
        }
        return new Newtonsoft.Json.Linq.JObject { ["Map"] = pairs };
    }
}
