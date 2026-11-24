using System.Diagnostics;
using System.Text;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

internal sealed record NativeAssetTools(string Executable, string WorkDirectory, IReadOnlyDictionary<string, string> ContentRoots);

internal static class NativeAssetExchange
{
    internal static async Task<T> ExportAsync<T>(string source, NativeAssetTools tools, string inputKey, string extension,
        Func<byte[], T> decode, JObject? options = null, string? operation = null)
    {
        if (!Path.IsPathFullyQualified(tools.Executable) || !Path.IsPathFullyQualified(tools.WorkDirectory))
            throw new ConversionException("Native export tools require absolute executable and work paths");
        Directory.CreateDirectory(tools.WorkDirectory);
        string prefix = Path.Combine(tools.WorkDirectory, inputKey + "_" + Guid.NewGuid().ToString("N"));
        string input = prefix + ".json", output = prefix + extension;
        string log = prefix + ".engine.log";
        try {
            var mounts = new JObject();
            foreach (var root in tools.ContentRoots.OrderBy(value => value.Key, StringComparer.Ordinal))
                mounts[root.Key] = root.Value;
            var request = options is null ? new JObject() : (JObject)options.DeepClone();
            request[inputKey] = source;
            request["mounts"] = mounts;
            await File.WriteAllTextAsync(input, request.ToString(Formatting.None), new UTF8Encoding(false)).ConfigureAwait(false);
            var start = new ProcessStartInfo(tools.Executable) {
                UseShellExecute = false, CreateNoWindow = true, RedirectStandardError = true, RedirectStandardOutput = true,
                WorkingDirectory = tools.WorkDirectory
            };
            foreach (string argument in new[] { "-NullRHI", "-Unattended", "-NoSound", "-NoSplash", "-NoLogWindow",
                         "-stdout", "-FullStdOutLogOutput", "-abslog=" + log, "-input=" + input, "-output=" + output })
                start.ArgumentList.Add(argument);
            if (operation is not null) start.ArgumentList.Add("-operation=" + operation);
            start.Environment["TEMP"] = tools.WorkDirectory;
            start.Environment["TMP"] = tools.WorkDirectory;
            start.Environment["TMPDIR"] = tools.WorkDirectory;
            using var process = Process.Start(start) ?? throw new ConversionException($"Unreal {inputKey} exporter could not start");
            var stdout = process.StandardOutput.ReadToEndAsync();
            var stderr = process.StandardError.ReadToEndAsync();
            await process.WaitForExitAsync().ConfigureAwait(false);
            string messages = await stdout.ConfigureAwait(false) + await stderr.ConfigureAwait(false);
            if (process.ExitCode != 0)
                throw new ConversionException($"Unreal {inputKey} export failed for {source} ({process.ExitCode}); {log}\n{messages}");
            var bytes = await File.ReadAllBytesAsync(output).ConfigureAwait(false);
            var result = decode(bytes);
            if (File.Exists(log) && !messages.Contains("Warning:", StringComparison.OrdinalIgnoreCase)) File.Delete(log);
            return result;
        } finally {
            if (File.Exists(input)) File.Delete(input);
            if (File.Exists(output)) File.Delete(output);
        }
    }
}
