namespace LumenFx.Compiler.Unreal.Geometry.MeshBuild;

public sealed record MeshBuildTools(string? NativeLibrary, string? UvLayoutExecutable, string WorkDirectory, string UnrealSourceRoot,
    string? MeshDescriptionExecutable = null, int TriangleOrderOptimization = 1,
    IReadOnlyDictionary<string, string>? ContentRoots = null, string? TargetPlatform = null)
{
    internal void RequireAbsolutePaths()
    {
        foreach (string path in new[] { WorkDirectory, UnrealSourceRoot })
            if (!Path.IsPathFullyQualified(path)) throw new ArgumentException("Absolute mesh tool, source and work paths are required");
        if (NativeLibrary is not null && !Path.IsPathFullyQualified(NativeLibrary))
            throw new ArgumentException("Absolute mesh index library path is required");
        if (TriangleOrderOptimization is < 0 or > 2) throw new ArgumentException("Mesh triangle order optimization must be 0, 1 or 2");
        if (UvLayoutExecutable is not null && !Path.IsPathFullyQualified(UvLayoutExecutable))
            throw new ArgumentException("Absolute lightmap layout executable path is required");
        if (MeshDescriptionExecutable is not null && !Path.IsPathFullyQualified(MeshDescriptionExecutable))
            throw new ArgumentException("Absolute mesh description executable path is required");
        if (TargetPlatform is not null && (MeshDescriptionExecutable is null || string.IsNullOrWhiteSpace(TargetPlatform)))
            throw new ArgumentException("A mesh target platform requires the native mesh builder");
        if (ContentRoots is not null) foreach (var root in ContentRoots) {
            if (!System.Text.RegularExpressions.Regex.IsMatch(root.Key, "^/[a-zA-Z0-9_]+/$")
                    || !Path.IsPathFullyQualified(root.Value))
                throw new ArgumentException("Native content mounts require a /Root/ name and an absolute content directory");
            if (!Directory.Exists(root.Value)) throw new DirectoryNotFoundException($"Native content directory does not exist: {root.Value}");
        }
    }
}
