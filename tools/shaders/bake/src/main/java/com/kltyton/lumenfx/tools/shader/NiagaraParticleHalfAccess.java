package com.kltyton.lumenfx.tools.shader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Adapts the source particle-access functions to packed binary16 storage. */
final class NiagaraParticleHalfAccess {
    private static final String SOURCE = "Engine/Plugins/FX/Niagara/Shaders/Private/NiagaraParticleAccess.ush";

    private NiagaraParticleHalfAccess() { }

    static void apply(Path stageRoot, String halfBuffer, String helper) throws IOException {
        Path access = stageRoot.resolve(SOURCE);
        String source = Files.readString(access, StandardCharsets.UTF_8);
        String parameter = "Buffer<float> FloatBuffer";
        if (source.split(parameter, -1).length != 5)
            throw new BakeException("Particle float accessor parameters do not match the expected source");
        source = source.replace(parameter, "StructuredBuffer<float> FloatBuffer");
        String anchor = "float NiagaraGetFloat(int RegisterIdx, uint ParticleIdx)";
        String function = "float " + helper + "(int RegisterIdx, uint ParticleIdx)\n{\n"
                + "    uint Index = RegisterIdx * NiagaraGetFloatDataStride() + ParticleIdx;\n"
                + "    uint Word = " + halfBuffer + "[Index >> 1];\n"
                + "    return f16tof32((Word >> ((Index & 1u) * 16)) & 0xffffu);\n}\n\n";
        source = replaceOnce(source, anchor, function + anchor);
        source = replaceOnce(source, "NiagaraGetFloat_Internal(NiagaraGetHalfBuffer(), RegisterIdx, ParticleIdx)",
                helper + "(RegisterIdx, ParticleIdx)");
        for (int width = 2; width <= 4; width++) {
            var value = new StringBuilder("float").append(width).append('(');
            for (int component = 0; component < width; component++) {
                if (component != 0) value.append(", ");
                value.append(helper).append("(RegisterIdx + ").append(component).append(", ParticleIdx)");
            }
            value.append(')');
            source = replaceOnce(source, "NiagaraGetVec" + width
                    + "_Internal(NiagaraGetHalfBuffer(), RegisterIdx, ParticleIdx)", value.toString());
        }
        Files.writeString(access, source, StandardCharsets.UTF_8);
    }

    private static String replaceOnce(String source, String original, String replacement) {
        int index = source.indexOf(original);
        if (index < 0 || source.indexOf(original, index + original.length()) >= 0)
            throw new BakeException("Particle access source is missing or ambiguous: " + original);
        return source.substring(0, index) + replacement + source.substring(index + original.length());
    }
}
