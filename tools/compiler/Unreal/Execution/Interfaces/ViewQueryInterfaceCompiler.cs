namespace LumenFx.Compiler.Unreal.Execution;

internal static class ViewQueryInterfaceCompiler
{
    internal const string MousePosition = "NiagaraDataInterfaceMousePosition";
    internal const string MovieRenderQueue = "NiagaraDataInterfaceMRQ";

    internal static string CpuFunction(string sourceClass, string name, int inputs, int outputs)
    {
        if (sourceClass == MousePosition && name == "GetMousePosition" && inputs == 1 && outputs == 2) return "mouse_position";
        if (sourceClass == MovieRenderQueue && name == "GetMRQInfo" && inputs == 0 && outputs == 4) return "movie_render_queue";
        throw new ConversionException($"Invalid Niagara {sourceClass} function {name} ({inputs}, {outputs})");
    }
}
