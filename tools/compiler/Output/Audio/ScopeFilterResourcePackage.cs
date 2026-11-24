using System.Diagnostics;
using System.Globalization;
using System.Text.RegularExpressions;
using LumenFx.Compiler.Unreal;

namespace LumenFx.Compiler.Output;

/** Packages the pinned libsamplerate high-quality impulse response as float32 data. */
internal static class ScopeFilterResourcePackage
{
    internal static async Task WriteAsync(string unreal, string revision, string resources)
    {
        const string source = "Engine/Source/ThirdParty/libSampleRate/Private/high_qual_coeffs.h";
        const string licenseSource = "Engine/Source/ThirdParty/Licenses/Libsamplerate_License.txt";
        if (!Regex.IsMatch(revision, @"\A[0-9a-fA-F]{40}\z"))
            throw new ArgumentException("The oscilloscope filter requires the exact UE commit ID");
        Task<string> coefficients = ReadPinnedAsync(unreal, revision, source);
        Task<string> license = ReadPinnedAsync(unreal, revision, licenseSource);
        await Task.WhenAll(coefficients, license);
        string text = await coefficients;
        Match table = Regex.Match(text, @"coeffs\s*\[\s*(?<count>\d+)\s*\]\s*;\s*}\s*slow_high_qual_coeffs\s*=\s*\{\s*(?<increment>\d+)\s*,\s*\{(?<coefficients>[^}]+)\}");
        if (!table.Success) throw new ConversionException($"Missing source oscilloscope resampling coefficients: {source}");
        int count = int.Parse(table.Groups["count"].Value, CultureInfo.InvariantCulture);
        int increment = int.Parse(table.Groups["increment"].Value, CultureInfo.InvariantCulture);
        string cleanCoefficients = Regex.Replace(table.Groups["coefficients"].Value, @"/\*[\s\S]*?\*/", "");
        string[] tokens = cleanCoefficients.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        if (increment <= 0 || count < 3 || count > 524_289 || count != tokens.Length)
            throw new ConversionException($"Invalid source oscilloscope resampling table: {source}");
        float[] values = tokens.Select(value => float.Parse(value, CultureInfo.InvariantCulture)).ToArray();
        if (values.Any(value => !float.IsFinite(value))) throw new ConversionException($"Non-finite oscilloscope coefficient: {source}");
        string path = Path.Combine(resources, "assets", "lumenfx", "niagara", "audio", "oscilloscope.lfsr");
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        using var output = new BinaryWriter(File.Create(path));
        output.Write("LFSR"u8);
        output.Write(1);
        output.Write(increment);
        output.Write(count);
        foreach (float value in values) output.Write(value);
        string notice = Path.Combine(resources, "META-INF", "licenses", "libsamplerate.txt");
        Directory.CreateDirectory(Path.GetDirectoryName(notice)!);
        await File.WriteAllTextAsync(notice, await license);
    }

    private static async Task<string> ReadPinnedAsync(string directory, string revision, string path)
    {
        var info = new ProcessStartInfo("git") {
            WorkingDirectory = Path.GetFullPath(directory), RedirectStandardOutput = true, RedirectStandardError = true,
            UseShellExecute = false, CreateNoWindow = true
        };
        info.ArgumentList.Add("--no-lazy-fetch");
        info.ArgumentList.Add("show");
        info.ArgumentList.Add(revision + ":" + path);
        using var process = Process.Start(info) ?? throw new ConversionException("Unable to open the local Unreal source archive");
        var text = process.StandardOutput.ReadToEndAsync();
        var diagnostic = process.StandardError.ReadToEndAsync();
        await process.WaitForExitAsync();
        if (process.ExitCode != 0) throw new ConversionException($"Missing pinned audio source {path}: {await diagnostic}");
        return await text;
    }
}
