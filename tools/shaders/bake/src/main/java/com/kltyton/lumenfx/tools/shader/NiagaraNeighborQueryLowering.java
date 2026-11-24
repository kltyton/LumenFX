package com.kltyton.lumenfx.tools.shader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Preserves typed-buffer zero reads and discarded writes for absent and out-of-range cells or slots. */
final class NiagaraNeighborQueryLowering {
    private static final Pattern RESOURCE = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*_(?:CellCount|CellOffset|ParticleList|AcquireTagList|OutputCellId|OutputParticleIdIndex|OutputAcquireTag)");

    private NiagaraNeighborQueryLowering() { }

    static boolean isQueryBuffer(String name) { return RESOURCE.matcher(name).matches(); }

    static void apply(Path root, List<String> buffers) throws IOException {
        if (buffers.isEmpty()) return;
        List<Path> sources;
        try (var paths = Files.walk(root)) {
            sources = paths.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".hlsl")
                    || path.toString().endsWith(".usf") || path.toString().endsWith(".ush")).toList();
        }
        for (var path : sources) {
            String original = Files.readString(path, StandardCharsets.UTF_8);
            String lowered = original;
            for (String buffer : buffers) lowered = rewrite(lowered, buffer);
            if (!lowered.equals(original)) Files.writeString(path, lowered, StandardCharsets.UTF_8);
        }
    }

    private static String rewrite(String source, String buffer) {
        String access = "\\b" + Pattern.quote(buffer) + "\\s*\\[\\s*([^\\]\\r\\n]+)\\]";
        if (buffer.endsWith("_OutputCellId") || buffer.endsWith("_OutputParticleIdIndex") || buffer.endsWith("_OutputAcquireTag")) {
            return Pattern.compile(access + "\\s*=\\s*([^;]+);").matcher(source).replaceAll(match -> {
                String index = match.group(1).trim();
                return Matcher.quoteReplacement("{ uint LfxSize, LfxStride; " + buffer + ".GetDimensions(LfxSize, LfxStride); "
                        + "if (uint(" + index + ") < LfxSize) " + buffer + "[" + index + "] = " + match.group(2) + "; }");
            });
        }
        String reader = "LfxNeighborRead_" + buffer;
        String lowered = Pattern.compile(access).matcher(source).replaceAll(match ->
                Matcher.quoteReplacement(reader + "(" + match.group(1).trim() + ")"));
        String declaration = "(\\bStructuredBuffer\\s*<\\s*int\\s*>\\s+" + Pattern.quote(buffer) + "\\s*;)";
        // The declaration can be in a generated include while references are in the entry source.
        return Pattern.compile(declaration).matcher(lowered).replaceFirst("$1"
                + Matcher.quoteReplacement("\nint " + reader + "(int index) { uint size, stride; " + buffer
                + ".GetDimensions(size, stride); if (uint(index) >= size) return 0; return " + buffer + "[index]; }\n"));
    }
}
