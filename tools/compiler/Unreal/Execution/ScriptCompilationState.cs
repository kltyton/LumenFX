using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class ScriptCompilationState
{
    internal static void RequireCurrent(JObject executable, string source)
    {
        string status = (executable.Value<string>("LastCompileStatus") ?? "NCS_Unknown").Split("::").Last();
        if (status is "NCS_UpToDate" or "3") return;
        if (status is "NCS_UpToDateWithWarnings" or "NCS_ComputeUpToDateWithWarnings" or "5" or "6") {
            Console.Error.WriteLine($"Niagara source compiled with warnings ({status}): {source}");
            return;
        }
        throw new ConversionException($"Niagara saved program is not current ({status}): {source}");
    }
}
