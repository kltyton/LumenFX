package com.kltyton.lumenfx.tools.shader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads a shared array's GPU-owned count when another emitter can mutate it. */
final class NiagaraSharedArrayReadLowering {
    private NiagaraSharedArrayReadLowering() { }

    static void apply(Path root, List<String> symbols) throws IOException {
        Path file = root.resolve("Engine/Shaders/Generated/NiagaraEmitterInstance.ush");
        String source = Files.readString(file, StandardCharsets.UTF_8);
        for (String symbol : symbols) {
            String quoted = Pattern.quote(symbol);
            source = replaceOnce(source, "\\bint\\s+GetLength_" + quoted
                    + "\\s*\\(\\s*\\)\\s*\\{\\s*return\\s+" + quoted + "_ArrayBufferParams\\.x\\s*;\\s*\\}",
                    "int GetLength_" + symbol + "() { return RWInstanceCounts[" + symbol + "_ArrayBufferParams.x]; }");
            source = replaceOnce(source, "\\bint\\s+GetLengthMinusOne_" + quoted
                    + "\\s*\\(\\s*\\)\\s*\\{\\s*return\\s+" + quoted + "_ArrayBufferParams\\.y\\s*;\\s*\\}",
                    "int GetLengthMinusOne_" + symbol + "() { return max(GetLength_" + symbol + "() - 1, 0); }");
            source = replaceOnce(source, "\\bIndex\\s*=\\s*GetLengthMinusOne_" + quoted + "\\s*\\(\\s*\\)\\s*;",
                    "Index = GetLength_" + symbol + "() - 1;");
            source = replaceOnce(source, "\\bIndex\\s*=\\s*clamp\\s*\\(\\s*Index\\s*,\\s*0\\s*,\\s*GetLengthMinusOne_"
                    + quoted + "\\s*\\(\\s*\\)\\s*\\)\\s*;",
                    "Index = GetLength_" + symbol + "() > 0 ? clamp(Index, 0, GetLengthMinusOne_" + symbol
                            + "()) : " + symbol + "_ArrayBufferParams.y;");
        }
        Files.writeString(file, source, StandardCharsets.UTF_8);
    }

    private static String replaceOnce(String source, String expression, String replacement) {
        Matcher matcher = Pattern.compile(expression).matcher(source);
        if (!matcher.find()) throw new BakeException("Shared Niagara array read source is missing: " + expression);
        String result = matcher.replaceFirst(Matcher.quoteReplacement(replacement));
        if (Pattern.compile(expression).matcher(result).find())
            throw new BakeException("Shared Niagara array read source is ambiguous: " + expression);
        return result;
    }
}
