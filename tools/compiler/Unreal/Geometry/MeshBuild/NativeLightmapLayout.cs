using System.Diagnostics;
using LumenFx.Compiler.Unreal.Geometry.MeshDescription;

namespace LumenFx.Compiler.Unreal.Geometry.MeshBuild;

internal sealed class NativeLightmapLayout
{
    private readonly string executable, workDirectory;

    internal NativeLightmapLayout(string executable, string workDirectory)
    {
        if (!Path.IsPathFullyQualified(executable) || !Path.IsPathFullyQualified(workDirectory))
            throw new ArgumentException("Absolute lightmap tool and work paths are required");
        this.executable = Path.GetFullPath(executable);
        this.workDirectory = Path.GetFullPath(workDirectory);
    }

    internal async Task<MeshUvLayoutResult> PackAsync(StaticMeshGeometry source, MeshTopology topology, MeshUvLayoutRequest request)
    {
        if (!File.Exists(executable)) throw new FileNotFoundException("Native lightmap layout tool is missing", executable);
        int padding = request.Version >= 7 ? 2 : 0;
        if (request.Version is < 0 or > 10 || request.Resolution <= padding || (long)request.Resolution - padding > ushort.MaxValue
                || request.SourceChannel < 0 || request.SourceChannel >= source.TextureCoordinates.Count)
            throw new ConversionException("Native lightmap layout options are outside the source allocator/channel contract");
        Directory.CreateDirectory(workDirectory);
        string stem = Path.Combine(workDirectory, "lightmap_" + Guid.NewGuid().ToString("N"));
        string inputPath = stem + ".input", outputPath = stem + ".output", logPath = stem + ".log";
        bool completed = false;
        try
        {
            MeshUvExchange.WriteInput(inputPath, source, topology, request);
            var start = new ProcessStartInfo(executable) {
                WorkingDirectory = workDirectory, UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardOutput = true, RedirectStandardError = true };
            start.ArgumentList.Add("-input=" + inputPath);
            start.ArgumentList.Add("-output=" + outputPath);
            start.ArgumentList.Add("-abslog=" + logPath);
            start.Environment["TEMP"] = workDirectory;
            start.Environment["TMP"] = workDirectory;
            start.Environment["TMPDIR"] = workDirectory;
            using var process = new Process { StartInfo = start };
            if (!process.Start()) throw new ConversionException("Native lightmap layout process did not start");
            Task<string> stdout = process.StandardOutput.ReadToEndAsync(), stderr = process.StandardError.ReadToEndAsync();
            await process.WaitForExitAsync();
            string output = await stdout, error = await stderr;
            if (process.ExitCode != 0)
                throw new ConversionException($"Native lightmap layout exited with {process.ExitCode}: {error}\n{output}\nLog: {logPath}");
            MeshUvLayoutResult result = MeshUvExchange.ReadOutput(outputPath, checked(topology.Triangles.Count * 3));
            completed = result.Packed;
            return result;
        }
        finally
        {
            File.Delete(inputPath);
            File.Delete(outputPath);
            if (completed) File.Delete(logPath);
        }
    }
}
