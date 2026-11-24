package com.kltyton.lumenfx.tools.shader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** Keeps two 16-bit particle components per uint while allowing independent GPU writes. */
final class NiagaraHalfBufferLowering {
    private static final String SOURCE = "Engine/Plugins/FX/Niagara/Shaders/Private/NiagaraEmitterInstanceShader.usf";

    private NiagaraHalfBufferLowering() { }

    static void apply(Path stageRoot) throws IOException {
        Path file = stageRoot.resolve(SOURCE);
        String source = Files.readString(file, StandardCharsets.UTF_8);
        source = replaceDeclaration(source, "Buffer", "InputHalf", "StructuredBuffer");
        source = replaceDeclaration(source, "RWBuffer", "RWOutputHalf", "RWStructuredBuffer");
        source = replaceFunction(source, "float InputDataHalf", """
                float InputDataHalf(int DataSetIndex, int RegisterIdx, int InstanceIdx)
                {
                #if NIAGARA_PARTICLE_PARTIAL_ENABLED
                    uint ElementIndex = RegisterIdx * ComponentBufferSizeWrite + InstanceIdx;
                    uint Packed;
                    InterlockedOr(RWOutputHalf[ElementIndex >> 1], 0u, Packed);
                #else
                    uint ElementIndex = RegisterIdx * ComponentBufferSizeRead + InstanceIdx;
                    uint Packed = InputHalf[ElementIndex >> 1];
                #endif
                    return f16tof32((Packed >> ((ElementIndex & 1) * 16)) & 0xffffu);
                }
                """);
        source = replaceFunction(source, "void OutputDataHalf", """
                void OutputDataHalf(int DataSetIndex, int RegisterIndex, int InstanceIndex, float Value)
                {
                    uint ElementIndex = RegisterIndex * ComponentBufferSizeWrite + InstanceIndex;
                    uint WordIndex = ElementIndex >> 1;
                    uint Shift = (ElementIndex & 1) * 16;
                    uint Mask = 0xffffu << Shift;
                    uint ValueBits = (f32tof16(Value) & 0xffffu) << Shift;
                    uint Expected;
                    InterlockedOr(RWOutputHalf[WordIndex], 0u, Expected);
                    uint Previous;
                    do
                    {
                        uint Updated = (Expected & ~Mask) | ValueBits;
                        InterlockedCompareExchange(RWOutputHalf[WordIndex], Expected, Updated, Previous);
                        if (Previous == Expected) break;
                        Expected = Previous;
                    } while (true);
                }
                """);
        Files.writeString(file, source, StandardCharsets.UTF_8);
    }

    private static String replaceDeclaration(String source, String original, String name, String target) {
        Pattern pattern = Pattern.compile("(?<![A-Za-z0-9_])" + original + "\\s*<\\s*half\\s*>\\s+" + name + "\\b");
        var matcher = pattern.matcher(source);
        if (!matcher.find()) throw new BakeException("Niagara half buffer declaration is missing: " + name);
        String replacement = target + "<uint> " + name;
        String result = matcher.replaceFirst(replacement);
        if (pattern.matcher(result).find()) throw new BakeException("Multiple Niagara half buffer declarations: " + name);
        return result;
    }

    private static String replaceFunction(String source, String name, String replacement) {
        var definitions = Pattern.compile(Pattern.quote(name) + "\\s*\\([^;{}]*\\)\\s*\\{").matcher(source);
        if (!definitions.find())
            throw new BakeException("Niagara half buffer function is missing or ambiguous: " + name);
        int start = definitions.start(), open = definitions.end() - 1;
        if (definitions.find())
            throw new BakeException("Niagara half buffer function is missing or ambiguous: " + name);
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') depth++;
            else if (character == '}' && --depth == 0)
                return source.substring(0, start) + replacement + source.substring(index + 1);
        }
        throw new BakeException("Unterminated Niagara half buffer function: " + name);
    }
}
