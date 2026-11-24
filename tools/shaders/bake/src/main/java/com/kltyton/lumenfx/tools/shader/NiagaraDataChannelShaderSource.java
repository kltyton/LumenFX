package com.kltyton.lumenfx.tools.shader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/** Adapts the source NDC resource handles and counts to host storage buffers. */
final class NiagaraDataChannelShaderSource {
    private NiagaraDataChannelShaderSource() { }

    static void lower(Path root, List<String> readers, List<String> writers) throws IOException {
        var translated = root.resolve("Engine/Shaders/Generated/NiagaraEmitterInstance.ush");
        String source = Files.readString(translated, StandardCharsets.UTF_8);
        for (String symbol : readers) {
            int start = source.indexOf("struct FNDCAccessContext_" + symbol);
            int end = start < 0 ? -1 : source.indexOf("};", start);
            if (end < 0) throw new BakeException("Missing Niagara data channel shader context: " + symbol);
            String body = source.substring(start, end);
            body = lowerBufferTypes(body);
            body = body.replaceAll("RWInstanceCounts\\s*\\[\\s*GetInstanceCountOffset\\(\\)\\s*\\]", symbol + "_NDCInstanceCount[0]");
            body = body.replaceAll("RWInstanceCounts\\s*\\[\\s*GetConsumeInstanceCountOffset\\(\\)\\s*\\]", symbol + "_NDCConsumeCount[0]");
            source = source.substring(0, start) + "RWStructuredBuffer<uint> " + symbol + "_NDCInstanceCount;\n"
                    + "RWStructuredBuffer<uint> " + symbol + "_NDCConsumeCount;\n"
                    + "int " + symbol + "_NDCEmitterCount;\n" + body + source.substring(end);
            String count = "RWInstanceCounts\\s*\\[\\s*" + Pattern.quote(symbol + "_InstanceCountOffset") + "\\s*\\]";
            source = source.replaceAll(count, symbol + "_NDCInstanceCount[0]");
            var emitterRead = Pattern.compile("(\\buint\\s+NDCSpawnDataOffset\\s*=\\s*" + Pattern.quote(symbol)
                    + "_NDCSpawnDataBuffer\\s*\\[\\s*InEmitterID\\.ID\\s*\\]\\s*;)");
            source = emitterRead.matcher(source).replaceAll("if (InEmitterID.ID < 0 || InEmitterID.ID >= " + symbol + "_NDCEmitterCount) return;\n    $1");
        }
        for (String symbol : writers) {
            int start = source.indexOf("struct FNDCAccessContext_" + symbol);
            int end = start < 0 ? -1 : source.indexOf("};", start);
            if (end < 0) throw new BakeException("Missing Niagara data channel writer shader context: " + symbol);
            String body = lowerBufferTypes(source.substring(start, end));
            body = lowerWriteDestinations(body, symbol);
            String counter = "RWInstanceCounts\\s*\\[\\s*InstanceCountOffset\\s*\\]";
            body = body.replaceAll("InterlockedAdd\\(\\s*" + counter + "\\s*,\\s*1[uU]\\s*,\\s*OrigVal\\s*\\);",
                    "if (InstanceCountOffset == 0) InterlockedAdd(" + symbol + "_NDCGpuCount[0], 1u, OrigVal);\n"
                            + "            else InterlockedAdd(" + symbol + "_NDCCpuCount[0], 1u, OrigVal);");
            body = body.replaceAll("InterlockedMin\\(\\s*" + counter + "\\s*,\\s*\\(uint\\)BufferSize\\s*\\);",
                    "if (InstanceCountOffset == 0) InterlockedMin(" + symbol + "_NDCGpuCount[0], (uint)BufferSize);\n"
                            + "                else InterlockedMin(" + symbol + "_NDCCpuCount[0], (uint)BufferSize);");
            if (body.contains("RWInstanceCounts"))
                throw new BakeException("Unmapped Niagara data channel writer counter in " + symbol);
            source = source.substring(0, start) + "RWStructuredBuffer<uint> " + symbol + "_NDCGpuCount;\n"
                    + "RWStructuredBuffer<uint> " + symbol + "_NDCCpuCount;\n" + body + source.substring(end);
        }
        Files.writeString(translated, source, StandardCharsets.UTF_8);
        var common = root.resolve("Engine/Plugins/FX/Niagara/Shaders/Private/DataChannel/NiagaraDataInterfaceDataChannelCommon.ush");
        if (Files.isRegularFile(common)) {
            String original = Files.readString(common, StandardCharsets.UTF_8);
            Files.writeString(common, lowerBufferTypes(original), StandardCharsets.UTF_8);
        }
    }

    private static String lowerWriteDestinations(String source, String symbol) {
        // SPIR-V cannot select between writable structured resource handles. Keep the source
        // channel selection on the write itself, with independent destination buffers and counts.
        for (String storage : List.of("Float", "Int32")) {
            var calls = Pattern.compile("(NiagaraDataChannelWrite_\\w+\\()GetCurrBuffer_" + storage
                    + "\\(\\)([^;\\r\\n]*\\);)");
            source = calls.matcher(source).replaceAll("if (bReadGPUOrCPU) $1" + symbol + "_GPUBuffer" + storage
                    + "$2 else $1" + symbol + "_CPUBuffer" + storage + "$2");
            source = removeResourceAccessors(source, storage);
        }
        return source;
    }

    private static String removeResourceAccessors(String source, String storage) {
        var accessors = Pattern.compile("(?m)^[ \\t]*RWStructuredBuffer<[^>]+>\\s+Get(?:GPU|CPU|Curr)Buffer_"
                + storage + "\\(\\)\\s*\\{").matcher(source);
        var result = new StringBuilder(source.length());
        int cursor = 0;
        while (accessors.find(cursor)) {
            result.append(source, cursor, accessors.start());
            int depth = 1;
            cursor = accessors.end();
            // Preview 2 uses a branch inside GetCurrBuffer in its generated NDC source.
            while (cursor < source.length() && depth != 0) {
                char next = source.charAt(cursor++);
                if (next == '{') depth++;
                else if (next == '}') depth--;
            }
            if (depth != 0) throw new BakeException("Unterminated Niagara channel " + storage + " accessor");
        }
        return result.append(source, cursor, source.length()).toString();
    }

    private static String lowerBufferTypes(String source) {
        return source.replaceAll("\\bRWBuffer\\s*<", "RWStructuredBuffer<")
                .replaceAll("\\bBuffer\\s*<", "StructuredBuffer<");
    }
}
