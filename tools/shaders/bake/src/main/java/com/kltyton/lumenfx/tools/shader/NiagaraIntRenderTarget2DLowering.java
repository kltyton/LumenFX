package com.kltyton.lumenfx.tools.shader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps the source's signed atomic operations and D3D bounds behavior on the host image. */
final class NiagaraIntRenderTarget2DLowering {
    private static final List<String> ATOMICS = List.of("Add", "Min", "Max", "CompareExchange");
    private NiagaraIntRenderTarget2DLowering() { }

    static void apply(Path root, List<String> images) throws IOException {
        var file = root.resolve("Engine/Shaders/Generated/NiagaraEmitterInstance.ush");
        String source = Files.readString(file, StandardCharsets.UTF_8);
        for (String image : images) source = lower(source, image);
        Files.writeString(file, source, StandardCharsets.UTF_8);
    }

    private static String lower(String source, String image) {
        String helper = "LfxIntTarget_" + image;
        String access = "\\b" + Pattern.quote(image) + "\\s*\\[\\s*([^\\[\\]]+?)\\s*]";
        String translated = source;
        var emitted = new ArrayList<String>();
        for (String operation : ATOMICS) {
            var calls = Pattern.compile("\\bInterlocked" + operation + "\\s*\\(\\s*" + access + "\\s*,").matcher(translated);
            if (calls.find()) {
                emitted.add(operation);
                translated = calls.replaceAll(match -> Matcher.quoteReplacement(helper + "_" + operation + "(" + match.group(1) + ","));
            }
        }
        translated = Pattern.compile(access + "\\s*=(?!=)\\s*([^;]+);").matcher(translated)
                .replaceAll(match -> Matcher.quoteReplacement(helper + "_Store(" + match.group(1) + ", " + match.group(2) + ");"));
        translated = Pattern.compile(access).matcher(translated)
                .replaceAll(match -> Matcher.quoteReplacement(helper + "_Load(" + match.group(1) + ")"));
        if (source.equals(translated)) return source;
        var declaration = Pattern.compile("\\bRWTexture2D\\s*<\\s*int\\s*>\\s+" + Pattern.quote(image) + "\\s*;").matcher(translated);
        if (!declaration.find()) throw new BakeException("Cannot locate Niagara signed render target " + image);
        String helpers = """

                bool $H_Contains(int2 Pixel)
                {
                    uint Width, Height;
                    $Image.GetDimensions(Width, Height);
                    return all(uint2(Pixel) < uint2(Width, Height));
                }

                int $H_Load(int2 Pixel)
                {
                    if (!$H_Contains(Pixel)) return 0;
                    return $Image[Pixel];
                }

                void $H_Store(int2 Pixel, int Value)
                {
                    if ($H_Contains(Pixel)) $Image[Pixel] = Value;
                }
                """.replace("$H", helper).replace("$Image", image);
        var result = new StringBuilder(helpers);
        for (String operation : emitted) {
            boolean compare = operation.equals("CompareExchange");
            result.append("""

                    void %s_%s(int2 Pixel, %sint Value, out int Previous)
                    {
                        Previous = 0;
                        if (%s_Contains(Pixel)) Interlocked%s(%s[Pixel], %sValue, Previous);
                    }
                    """.formatted(helper, operation, compare ? "int Comparison, " : "", helper, operation, image,
                    compare ? "Comparison, " : ""));
        }
        int offset = declaration.end();
        return translated.substring(0, offset) + result + translated.substring(offset);
    }
}
