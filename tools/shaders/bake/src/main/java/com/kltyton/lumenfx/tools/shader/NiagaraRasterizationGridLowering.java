package com.kltyton.lumenfx.tools.shader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Carries the source D3D texture and attribute-table bounds behavior across Vulkan and OpenGL. */
final class NiagaraRasterizationGridLowering {
    private NiagaraRasterizationGridLowering() { }

    static void apply(Path root, List<String> inputs) throws IOException {
        var file = root.resolve("Engine/Shaders/Generated/NiagaraEmitterInstance.ush");
        String source = Files.readString(file, StandardCharsets.UTF_8);
        for (String input : inputs) {
            String symbol = input.substring(0, input.length() - "_IntGrid".length());
            source = lower(source, symbol);
        }
        Files.writeString(file, source, StandardCharsets.UTF_8);
    }

    private static String lower(String source, String symbol) {
        String input = symbol + "_IntGrid", output = symbol + "_OutputIntGrid", attributes = symbol + "_PerAttributeData";
        String helper = "LfxRaster_" + symbol;
        String original = source;
        source = Pattern.compile("\\b" + Pattern.quote(input) + "\\s*\\.\\s*Load\\s*\\(").matcher(source)
                .replaceAll(Matcher.quoteReplacement(helper + "_Load("));
        source = Pattern.compile(access(attributes)).matcher(source)
                .replaceAll(match -> Matcher.quoteReplacement(helper + "_Attribute(" + match.group(1) + ")"));
        source = Pattern.compile("\\bInterlocked(Add|Min|Max)\\s*\\(\\s*" + access(output) + "\\s*,").matcher(source)
                .replaceAll(match -> Matcher.quoteReplacement(helper + "_" + match.group(1) + "(" + match.group(2) + ","));
        source = Pattern.compile(access(output) + "\\s*=\\s*([^;]+);").matcher(source)
                .replaceAll(match -> Matcher.quoteReplacement(helper + "_Store(" + match.group(1) + ", " + match.group(2) + ");"));
        if (source.equals(original)) return source;

        var declaration = Pattern.compile("\\b(?:Texture3D\\s*<\\s*int|RWTexture3D\\s*<\\s*int|StructuredBuffer\\s*<\\s*float4)"
                + "\\s*>\\s+(?:" + Pattern.quote(input) + "|" + Pattern.quote(output) + "|" + Pattern.quote(attributes) + ")\\s*;").matcher(source);
        int after = -1;
        while (declaration.find()) after = declaration.end();
        if (after < 0) throw new BakeException("Cannot locate Niagara Rasterization Grid3D declarations for " + symbol);
        String helpers = """

                float4 $H_Attribute(int Index)
                {
                    uint Count, Stride;
                    $Attributes.GetDimensions(Count, Stride);
                    if (uint(Index) >= Count) return float4(0, 0, 0, 0);
                    return $Attributes[Index];
                }

                int $H_Load(int4 Location)
                {
                    uint Width, Height, Depth, Levels;
                    $Input.GetDimensions(0, Width, Height, Depth, Levels);
                    if (any(uint3(Location.xyz) >= uint3(Width, Height, Depth)) || Location.w != 0) return 0;
                    return $Input.Load(Location);
                }

                bool $H_Writable(int3 Location)
                {
                    uint Width, Height, Depth;
                    $Output.GetDimensions(Width, Height, Depth);
                    return all(uint3(Location) < uint3(Width, Height, Depth));
                }

                void $H_Store(int3 Location, int Value)
                {
                    if ($H_Writable(Location)) $Output[Location] = Value;
                }

                void $H_Add(int3 Location, int Value)
                {
                    if ($H_Writable(Location)) InterlockedAdd($Output[Location], Value);
                }
                """;
        for (String operation : List.of("Add", "Min", "Max")) helpers += """

                void $H_%s(int3 Location, int Value, out int Previous)
                {
                    Previous = 0;
                    if ($H_Writable(Location)) Interlocked%s($Output[Location], Value, Previous);
                }
                """.formatted(operation, operation);
        helpers = helpers.replace("$H", helper).replace("$Input", input).replace("$Output", output).replace("$Attributes", attributes);
        return source.substring(0, after) + helpers + source.substring(after);
    }

    private static String access(String name) {
        return "\\b" + Pattern.quote(name) + "\\s*\\[\\s*([^\\[\\]]+?)\\s*]";
    }
}
