package com.kltyton.lumenfx.tools.shader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Preserves two half components per word for particle-reader SRVs. */
final class NiagaraParticleReadHalfLowering {
    private NiagaraParticleReadHalfLowering() { }

    static void apply(Path stageRoot, List<String> names) throws IOException {
        Path file = stageRoot.resolve("Engine/Shaders/Generated/NiagaraEmitterInstance.ush");
        String source = Files.readString(file, StandardCharsets.UTF_8);
        for (String name : names) {
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*_InputHalfBuffer"))
                throw new BakeException("Invalid Niagara particle reader half buffer " + name);
            String helper = "lfx_" + name + "_read";
            Pattern declaration = Pattern.compile("\\bBuffer\\s*<\\s*half\\s*>\\s+" + Pattern.quote(name) + "\\s*;");
            Matcher match = declaration.matcher(source);
            if (!match.find()) throw new BakeException("Missing Niagara particle reader half buffer " + name);
            String replacement = "StructuredBuffer<uint> " + name + ";\nfloat " + helper
                    + "(uint i) { uint word = " + name + "[i >> 1]; return f16tof32((word >> ((i & 1u) * 16u)) & 0xffffu); }\n";
            source = match.replaceFirst(Matcher.quoteReplacement(replacement));
            if (declaration.matcher(source).find()) throw new BakeException("Duplicate Niagara particle reader half buffer " + name);
            int helperEnd = source.indexOf("\n", source.indexOf("float " + helper));
            String prefix = source.substring(0, helperEnd + 1), body = source.substring(helperEnd + 1);
            Pattern access = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\[");
            StringBuilder output = new StringBuilder(prefix);
            int cursor = 0;
            while (true) {
                Matcher reader = access.matcher(body);
                if (!reader.find(cursor)) break;
                int open = reader.end() - 1, depth = 1, end = open + 1;
                while (end < body.length() && depth != 0) {
                    char character = body.charAt(end++);
                    if (character == '[') depth++;
                    else if (character == ']') depth--;
                }
                if (depth != 0) throw new BakeException("Unterminated Niagara particle reader half access " + name);
                output.append(body, cursor, reader.start()).append(helper).append('(')
                        .append(body, open + 1, end - 1).append(')');
                cursor = end;
            }
            source = output.append(body, cursor, body.length()).toString();
        }
        Files.writeString(file, source, StandardCharsets.UTF_8);
    }
}
