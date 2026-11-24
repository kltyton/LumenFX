using System.Diagnostics;
using System.Text;

namespace LumenFx.Compiler.Unreal.Geometry.MeshBuild;

internal static class NativeMeshProcess
{
    internal readonly record struct Result(bool Diagnostics, string Log, string EngineLog)
    {
        internal void Complete() {
            if (Diagnostics) return;
            if (File.Exists(Log)) File.Delete(Log);
            if (File.Exists(EngineLog)) File.Delete(EngineLog);
        }
    }

    internal static async Task<Result> RunAsync(MeshBuildTools tools, string input, string output, string operation)
    {
        string executable = tools.MeshDescriptionExecutable ?? throw new ConversionException("Native mesh processing executable is required");
        string log = input + ".log", engineLog = input + ".engine.log";
        var start = new ProcessStartInfo(executable) {
            UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true,
            WorkingDirectory = tools.WorkDirectory
        };
        foreach (string argument in new[] { "-NullRHI", "-Unattended", "-NoSound", "-NoSplash", "-NoLogWindow", "-stdout", "-FullStdOutLogOutput",
                "-abslog=" + engineLog, "-operation=" + operation, "-input=" + input, "-output=" + output }) start.ArgumentList.Add(argument);
        start.Environment["TEMP"] = tools.WorkDirectory; start.Environment["TMP"] = tools.WorkDirectory; start.Environment["TMPDIR"] = tools.WorkDirectory;
        start.Environment["UE-LocalDataCachePath"] = Path.Combine(tools.UnrealSourceRoot, "Engine", "DerivedDataCache");
        using Process process = Process.Start(start) ?? throw new ConversionException("Native mesh process could not start");
        Task<string> stdout = process.StandardOutput.ReadToEndAsync(), stderr = process.StandardError.ReadToEndAsync();
        await process.WaitForExitAsync().ConfigureAwait(false);
        string messages = await stdout.ConfigureAwait(false) + await stderr.ConfigureAwait(false);
        File.WriteAllText(log, messages, new UTF8Encoding(false));
        if (process.ExitCode != 0) throw new ConversionException($"Native mesh {operation} failed ({process.ExitCode}); {log}");
        bool diagnostics = messages.Contains("Warning:", StringComparison.OrdinalIgnoreCase) || messages.Contains("Error:", StringComparison.OrdinalIgnoreCase);
        if (diagnostics) Console.Error.WriteLine($"Native mesh processing diagnostics: {log}");
        return new Result(diagnostics, log, engineLog);
    }
}
