package com.kltyton.lumenfx.tools.shader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps Direct3D buffer bounds behavior when the grid's typed buffers become structured buffers. */
final class NiagaraNeighborGridLowering {
    private static final Pattern RESOURCE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*_(?:Output)?ParticleNeighbor(?:s|Count)");

    private NiagaraNeighborGridLowering() { }

    static boolean isGridBuffer(String name) { return RESOURCE.matcher(name).matches(); }

    static void apply(Path root, List<String> buffers) throws IOException {
        if (buffers.isEmpty()) return;
        List<Path> paths;
        try (var stream = Files.walk(root)) {
            paths = stream.filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".hlsl") || file.toString().endsWith(".ush")
                            || file.toString().endsWith(".usf")).toList();
        }
        for (var path : paths) {
            String original = Files.readString(path, StandardCharsets.UTF_8);
            String source = original;
            for (String buffer : buffers) source = lower(source, buffer);
            if (!source.equals(original)) Files.writeString(path, source, StandardCharsets.UTF_8);
        }
    }

    private static String lower(String source, String buffer) {
        String access = "\\b" + Pattern.quote(buffer) + "\\s*\\[\\s*(\\w+)\\s*]";
        String dimensions = "uint LfxCount, LfxStride; " + buffer + ".GetDimensions(LfxCount, LfxStride); ";
        if (!buffer.endsWith("_OutputParticleNeighbors") && !buffer.endsWith("_OutputParticleNeighborCount")) {
            return Pattern.compile("(\\w+)\\s*=\\s*" + access + "\\s*;").matcher(source).replaceAll(match -> {
                String target = match.group(1), index = match.group(2);
                return Matcher.quoteReplacement("{ " + dimensions + target + " = 0; if (uint(" + index + ") < LfxCount) "
                        + target + " = " + buffer + "[" + index + "]; }");
            });
        }
        source = Pattern.compile(access + "\\s*=\\s*([^;]+);").matcher(source).replaceAll(match -> {
            String index = match.group(1);
            return Matcher.quoteReplacement("{ " + dimensions + "if (uint(" + index + ") < LfxCount) "
                    + buffer + "[" + index + "] = " + match.group(2) + "; }");
        });
        return Pattern.compile("\\bInterlockedAdd\\s*\\(\\s*" + access + "\\s*,\\s*([^,]+),\\s*(\\w+)\\s*\\)\\s*;")
                .matcher(source).replaceAll(match -> {
                    String index = match.group(1), increment = match.group(2), previous = match.group(3);
                    return Matcher.quoteReplacement("{ " + dimensions + previous + " = 0; if (uint(" + index
                            + ") < LfxCount) InterlockedAdd(" + buffer + "[" + index + "], " + increment + ", " + previous + "); }");
                });
    }
}
