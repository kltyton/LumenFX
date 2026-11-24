package com.kltyton.lumenfx.tools.shader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Applies the D3D array-texture out-of-bounds behavior to the saved Grid2D functions. */
final class NiagaraGrid2DShaderLowering {
    private NiagaraGrid2DShaderLowering() { }

    static void apply(Path root, List<String> outputs) throws IOException {
        var path = root.resolve("Engine/Shaders/Generated/NiagaraEmitterInstance.ush");
        String source = Files.readString(path, StandardCharsets.UTF_8);
        for (String output : outputs) source = lower(source, output);
        Files.writeString(path, source, StandardCharsets.UTF_8);
    }

    private static String lower(String source, String output) {
        String symbol = output.substring(0, output.length() - "_OutputGrid".length());
        String input = symbol + "_Grid";
        String helper = "LfxGrid2D_" + symbol;
        String translated = Pattern.compile("\\b" + Pattern.quote(input) + "\\s*\\.\\s*Load\\s*\\(")
                .matcher(source).replaceAll(Matcher.quoteReplacement(helper + "_Load("));
        translated = Pattern.compile("\\b" + Pattern.quote(output) + "\\s*\\[\\s*([^\\[\\]]+?)\\s*]\\s*=(?!=)\\s*([^;]+);")
                .matcher(translated).replaceAll(match -> Matcher.quoteReplacement(helper + "_Store("
                        + match.group(1) + ", " + match.group(2) + ");"));
        if (source.equals(translated)) return source;
        var declarations = Pattern.compile("\\b(?:RW)?Texture2DArray\\s*<\\s*float\\s*>\\s+(?:"
                + Pattern.quote(input) + "|" + Pattern.quote(output) + ")\\s*;").matcher(translated);
        int position = -1;
        while (declarations.find()) position = declarations.end();
        if (position < 0) throw new BakeException("Cannot locate Niagara Grid2D Collection resources for " + symbol);
        String helpers = """

                float $H_Load(int4 Location)
                {
                    uint Width, Height, Layers, Mips;
                    $Input.GetDimensions(0, Width, Height, Layers, Mips);
                    if (Location.w != 0 || any(uint3(Location.xyz) >= uint3(Width, Height, Layers))) return 0;
                    return $Input.Load(Location);
                }

                void $H_Store(int3 Location, float Value)
                {
                    uint Width, Height, Layers;
                    $Output.GetDimensions(Width, Height, Layers);
                    if (all(uint3(Location) < uint3(Width, Height, Layers))) $Output[Location] = Value;
                }
                """.replace("$H", helper).replace("$Input", input).replace("$Output", output);
        return translated.substring(0, position) + helpers + translated.substring(position);
    }
}
