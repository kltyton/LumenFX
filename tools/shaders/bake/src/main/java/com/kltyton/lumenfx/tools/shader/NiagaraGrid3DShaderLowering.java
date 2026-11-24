package com.kltyton.lumenfx.tools.shader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Preserves typed reads and stores and applies the source texture's bounds semantics. */
final class NiagaraGrid3DShaderLowering {
    private NiagaraGrid3DShaderLowering() { }

    static void apply(Path root, List<String> outputs) throws IOException {
        var file = root.resolve("Engine/Shaders/Generated/NiagaraEmitterInstance.ush");
        String source = Files.readString(file, StandardCharsets.UTF_8);
        for (String output : outputs) source = lower(source, output);
        Files.writeString(file, source, StandardCharsets.UTF_8);
    }

    private static String lower(String source, String output) {
        String symbol = output.substring(0, output.length() - "_OutputGrid".length());
        String input = symbol + "_Grid", metadata = symbol + "_PerAttributeData";
        String helper = "LfxGrid3D_" + symbol;
        var declaration = Pattern.compile("\\bRWTexture3D\\s*<\\s*(float[234]?)\\s*>\\s+" + Pattern.quote(output) + "\\s*;").matcher(source);
        if (!declaration.find()) throw new BakeException("Cannot locate Niagara Grid3D Collection output " + output);
        String type = declaration.group(1);
        String prior = source;
        // The packed CopyPreviousToCurrentForCell template has a malformed Load call.
        source = Pattern.compile("\\b" + Pattern.quote(input) + "\\s*\\.\\s*Load\\s*\\(\\s*Index\\s*,\\s*0\\s*\\)\\s*\\)(?=\\s*;)")
                .matcher(source).replaceAll(Matcher.quoteReplacement(helper + "_Load(int4(Index, 0))"));
        source = Pattern.compile("\\b" + Pattern.quote(input) + "\\s*\\.\\s*Load\\s*\\(").matcher(source)
                .replaceAll(Matcher.quoteReplacement(helper + "_Load("));
        source = Pattern.compile(access(metadata)).matcher(source)
                .replaceAll(match -> Matcher.quoteReplacement(helper + "_Attribute(" + match.group(1) + ")"));
        var writtenChannels = new LinkedHashSet<String>();
        source = Pattern.compile(access(output) + "\\s*\\.\\s*([rgba]{1,4}|[xyzw]{1,4})\\s*=(?!=)\\s*([^;]+);")
                .matcher(source).replaceAll(match -> {
                    String channels = match.group(2);
                    writtenChannels.add(channels);
                    return Matcher.quoteReplacement(helper + "_Store_" + channels + "(" + match.group(1) + ", " + match.group(3) + ");");
                });
        source = Pattern.compile(access(output) + "\\s*=(?!=)\\s*([^;]+);").matcher(source)
                .replaceAll(match -> Matcher.quoteReplacement(helper + "_Store(" + match.group(1) + ", " + match.group(2) + ");"));
        source = Pattern.compile(access(output)).matcher(source)
                .replaceAll(match -> Matcher.quoteReplacement(helper + "_ReadOutput(" + match.group(1) + ")"));
        source = Pattern.compile("\\b" + Pattern.quote(output) + "\\s*\\.\\s*Load\\s*\\(").matcher(source)
                .replaceAll(Matcher.quoteReplacement(helper + "_LoadOutput("));
        if (prior.equals(source)) return source;

        var declarations = Pattern.compile("\\b(?:Texture3D\\s*<\\s*float[234]?|RWTexture3D\\s*<\\s*float[234]?|StructuredBuffer\\s*<\\s*float4)"
                + "\\s*>\\s+(?:" + Pattern.quote(input) + "|" + Pattern.quote(output) + "|" + Pattern.quote(metadata) + ")\\s*;").matcher(source);
        int after = -1;
        while (declarations.find()) after = declarations.end();
        if (after < 0) throw new BakeException("Cannot locate Niagara Grid3D Collection resources for " + symbol);
        String helpers = """

                float4 $H_Attribute(int Index)
                {
                    uint Count, Stride;
                    $Metadata.GetDimensions(Count, Stride);
                    if (uint(Index) >= Count) return float4(0, 0, 0, 0);
                    return $Metadata[Index];
                }

                $Type $H_Load(int4 Location)
                {
                    uint Width, Height, Depth, Levels;
                    $Input.GetDimensions(0, Width, Height, Depth, Levels);
                    if (any(uint3(Location.xyz) >= uint3(Width, Height, Depth)) || Location.w != 0) return ($Type)0;
                    return $Input.Load(Location);
                }

                bool $H_OutputContains(int3 Location)
                {
                    uint Width, Height, Depth;
                    $Output.GetDimensions(Width, Height, Depth);
                    return all(uint3(Location) < uint3(Width, Height, Depth));
                }

                $Type $H_ReadOutput(int3 Location)
                {
                    if (!$H_OutputContains(Location)) return ($Type)0;
                    return $Output[Location];
                }

                $Type $H_LoadOutput(int4 Location)
                {
                    return $H_ReadOutput(Location.xyz);
                }

                void $H_Store(int3 Location, $Type Value)
                {
                    if ($H_OutputContains(Location)) $Output[Location] = Value;
                }
                """.replace("$H", helper).replace("$Type", type).replace("$Input", input)
                .replace("$Output", output).replace("$Metadata", metadata);
        var definitions = new StringBuilder(helpers);
        for (String channels : writtenChannels) {
            String channelType = channels.length() == 1 ? "float" : "float" + channels.length();
            definitions.append("""

                    void %s_Store_%s(int3 Location, %s Value)
                    {
                        if (!%s_OutputContains(Location)) return;
                        %s Previous = %s[Location];
                        Previous.%s = Value;
                        %s[Location] = Previous;
                    }
                    """.formatted(helper, channels, channelType, helper, type, output, channels, output));
        }
        return source.substring(0, after) + definitions + source.substring(after);
    }

    private static String access(String name) {
        return "\\b" + Pattern.quote(name) + "\\s*\\[\\s*([^\\[\\]]+?)\\s*]";
    }
}
