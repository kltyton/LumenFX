package com.kltyton.lumenfx.tools.shader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Preserves Direct3D's zero result for out-of-bounds layered and volume texel loads on GL and Vulkan. */
final class NiagaraTextureLoadLowering {
    private NiagaraTextureLoadLowering() { }

    static void lowerSparsePageLoads(Path root) throws IOException {
        Path source = root.resolve("Engine/Shaders/Private/SparseVolumeTexture/SparseVolumeTextureCommon.ush");
        if (!Files.isRegularFile(source)) return;
        String text = Files.readString(source, StandardCharsets.UTF_8);
        String load = "PageTable.Load(int4(floor(PageTableCoord), MipLevel)).x";
        if (!text.contains(load)) throw new BakeException("Sparse volume page-table load changed in the Unreal shader");
        int function = text.indexOf("float3 SparseVolumeTextureGetVoxelCoord(");
        if (function < 0) throw new BakeException("Sparse volume page-table sampling function is missing");
        text = text.replace(load, "LfxSparseVolumePageLoad(PageTable, int4(floor(PageTableCoord), MipLevel))");
        String helper = """
                uint LfxSparseVolumePageLoad(Texture3D<uint> PageTable, int4 Location)
                {
                    if (any(Location < 0))
                        return 0u;
                    uint Width, Height, Depth, Levels;
                    PageTable.GetDimensions(0, Width, Height, Depth, Levels);
                    if (uint(Location.w) >= Levels)
                        return 0u;
                    PageTable.GetDimensions(uint(Location.w), Width, Height, Depth, Levels);
                    if (any(uint3(Location.xyz) >= uint3(Width, Height, Depth)))
                        return 0u;
                    return PageTable.Load(Location);
                }

                """;
        Files.writeString(source, text.substring(0, function) + helper + text.substring(function), StandardCharsets.UTF_8);
    }

    static void lowerCubeWrites(Path root, List<String> resources) throws IOException {
        Path source = root.resolve("Engine/Shaders/Generated/NiagaraEmitterInstance.ush");
        String text = Files.readString(source, StandardCharsets.UTF_8);
        for (String resource : resources) {
            var declaration = Pattern.compile("\\bRWTextureCube(?=\\s*<\\s*float4\\s*>\\s+"
                    + Pattern.quote(resource) + "\\b)").matcher(text);
            if (!declaration.find()) throw new BakeException("Niagara writable cube declaration is missing: " + resource);
            text = declaration.replaceAll("RWTexture2DArray");
        }
        Files.writeString(source, text, StandardCharsets.UTF_8);
    }

    static void apply(Path root, List<String> resources) throws IOException {
        Path source = root.resolve("Engine/Shaders/Generated/NiagaraEmitterInstance.ush");
        String original = Files.readString(source, StandardCharsets.UTF_8);
        String text = original;
        for (String resource : resources) {
            String escaped = Pattern.quote(resource);
            var load = Pattern.compile("\\b" + escaped + "\\s*\\.\\s*Load\\s*\\(").matcher(text);
            if (!load.find()) continue;
            String function = "LfxLoadArray_" + resource;
            text = load.replaceAll(Matcher.quoteReplacement(function + "("));
            var declaration = Pattern.compile("\\b(Texture2DArray|Texture3D)(?:\\s*<\\s*float4\\s*>)?\\s+"
                    + escaped + "\\s*;").matcher(text);
            if (!declaration.find()) throw new BakeException("Niagara layered or volume texture declaration is missing: " + resource);
            int position = declaration.end();
            boolean volume = declaration.group(1).equals("Texture3D");
            String helper = """

                    float4 %1$s(int4 Location)
                    {
                        if (any(Location < 0))
                            return float4(0, 0, 0, 0);
                        uint Width, Height, Layers, Levels;
                        %2$s.GetDimensions(0, Width, Height, Layers, Levels);
                        if (uint(Location.w) >= Levels)
                            return float4(0, 0, 0, 0);
                        %2$s.GetDimensions(uint(Location.w), Width, Height, Layers, Levels);
                        if (any(uint3(Location.xyz) >= uint3(Width, Height, Layers)))
                            return float4(0, 0, 0, 0);
                        return %2$s.Load(Location);
                    }

                    float4 %1$s(int4 Location, %3$s Offset)
                    {
                        Location.%4$s += Offset;
                        return %1$s(Location);
                    }

                    """.formatted(function, resource, volume ? "int3" : "int2", volume ? "xyz" : "xy");
            text = text.substring(0, position) + helper + text.substring(position);
        }
        if (!original.equals(text)) Files.writeString(source, text, StandardCharsets.UTF_8);
    }
}
