package com.kltyton.lumenfx.tools.shader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** Reads the same packed binary16 particle data used by Niagara GPU simulation. */
final class NiagaraSortHalfBufferLowering {
    private static final String SOURCE = "Engine/Plugins/FX/Niagara/Shaders/Private/NiagaraSortKeyGen.usf";
    private NiagaraSortHalfBufferLowering() { }

    static void apply(Path stageRoot) throws IOException {
        Path file = stageRoot.resolve(SOURCE);
        String source = Files.readString(file, StandardCharsets.UTF_8);
        var declaration = Pattern.compile("\\bBuffer\\s*<\\s*half\\s*>\\s+NiagaraParticleDataHalf\\b").matcher(source);
        if (!declaration.find()) throw new BakeException("Niagara sort half buffer declaration is missing");
        source = declaration.replaceFirst("StructuredBuffer<uint> NiagaraParticleDataHalf");
        String original = "return NiagaraParticleDataHalf[RegisterIdx * HalfDataStride + InstanceID];";
        int first = source.indexOf(original);
        if (first < 0 || source.indexOf(original, first + original.length()) >= 0)
            throw new BakeException("Niagara sort half buffer read is missing or ambiguous");
        String replacement = "uint HalfIndex = RegisterIdx * HalfDataStride + InstanceID;\n"
                + "        uint HalfWord = NiagaraParticleDataHalf[HalfIndex >> 1];\n"
                + "        return f16tof32((HalfWord >> ((HalfIndex & 1u) * 16)) & 0xffffu);";
        source = source.substring(0, first) + replacement + source.substring(first + original.length());
        Files.writeString(file, source, StandardCharsets.UTF_8);
    }
}
