package com.kltyton.lumenfx.tools.shader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Adapts the pinned GSDF ray march to disjoint dispatch lanes and translated host coordinates. */
final class NiagaraAsyncTraceSource {
    private NiagaraAsyncTraceSource() { }

    static void apply(Path path) throws IOException {
        String source = Files.readString(path, StandardCharsets.UTF_8);
        source = replace(source, "float DistanceThreshold;", "float DistanceThreshold;\nuint MaxTraceCount;\nuint TraceRowThreads;");
        source = replace(source, "uint GroupIndex : SV_GroupIndex", "uint3 DispatchThread : SV_DispatchThreadID");
        source = replace(source, "if (GroupIndex >= TraceCounts[TraceCountsOffset + 0])",
                "uint GroupIndex = DispatchThread.x + DispatchThread.y * TraceRowThreads;\n\tif (GroupIndex >= min(MaxTraceCount, TraceCounts[TraceCountsOffset + 0]))");
        source = replace(source, "FNiagaraAsyncGpuTrace Trace = Traces[TracesOffset + GroupIndex];",
                "FNiagaraAsyncGpuTrace Trace = Traces[TracesOffset + GroupIndex];\n\tif (!(Trace.TFar > 0.0f)) return;");
        source = replace(source, "float3 TranslatedWorldPosition = Result.WorldPosition + DFHackToFloat(PrimaryView.PreViewTranslation);",
                "float3 TranslatedOrigin = Trace.Origin + DFHackToFloat(PrimaryView.PreViewTranslation);\n\tfloat3 TranslatedWorldPosition = TranslatedOrigin;");
        source = replace(source, "TranslatedWorldPosition = Trace.Origin + Trace.Direction * min(Result.HitT, Trace.TFar);",
                "TranslatedWorldPosition = TranslatedOrigin + Trace.Direction * min(Result.HitT, Trace.TFar);");
        Files.writeString(path, source, StandardCharsets.UTF_8);
    }

    static void lowerCountParameters(Path root) throws IOException {
        Path path = root.resolve("Engine/Plugins/FX/Niagara/Shaders/Private/NiagaraDataInterfaceAsyncGpuTrace.ush");
        if (!Files.isRegularFile(path)) return;
        String source = Files.readString(path, StandardCharsets.UTF_8);
        String lowered = source.replace("RWBuffer<uint> RWRayTraceCounts", "RWStructuredBuffer<uint> RWRayTraceCounts");
        if (source.equals(lowered)) throw new BakeException("Missing Niagara asynchronous GPU trace count parameters");
        Files.writeString(path, lowered, StandardCharsets.UTF_8);
    }

    private static String replace(String source, String before, String after) {
        int position = source.indexOf(before);
        if (position < 0 || source.indexOf(before, position + before.length()) >= 0)
            throw new BakeException("Unexpected Niagara GSDF ray trace source: " + before);
        return source.substring(0, position) + after + source.substring(position + before.length());
    }
}
