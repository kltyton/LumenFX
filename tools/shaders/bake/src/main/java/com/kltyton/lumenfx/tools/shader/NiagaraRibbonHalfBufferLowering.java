package com.kltyton.lumenfx.tools.shader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Preserves the R16F ribbon particle view when the simulation stores packed binary16. */
final class NiagaraRibbonHalfBufferLowering {
    private static final String ROOT = "Engine/Plugins/FX/Niagara/Shaders/Private/";

    private NiagaraRibbonHalfBufferLowering() { }

    static void apply(Path stageRoot) throws IOException {
        Path common = stageRoot.resolve(ROOT + "Ribbons/NiagaraRibbonCommon.ush");
        String source = Files.readString(common, StandardCharsets.UTF_8);
        source = replaceOnce(source, "Buffer<float> NiagaraParticleDataHalf;",
                "StructuredBuffer<uint> NiagaraParticleDataHalf;");
        Files.writeString(common, source, StandardCharsets.UTF_8);

        NiagaraParticleHalfAccess.apply(stageRoot, "NiagaraParticleDataHalf", "LfxRibbonHalf");

        Path sort = stageRoot.resolve(ROOT + "Ribbons/NiagaraRibbonSortParticles.usf");
        if (Files.isRegularFile(sort)) {
            String commonSource = Files.readString(common, StandardCharsets.UTF_8).replace("\r\n", "\n");
            String linkGetter = "float GetRibbonLinkID(int InstanceID)\n{";
            commonSource = replaceOnce(commonSource, linkGetter, linkGetter
                    + "\n\tif (RibbonLinkOrderDataOffset == INDEX_NONE) return 0.0f;");
            Files.writeString(common, commonSource, StandardCharsets.UTF_8);
            source = Files.readString(sort, StandardCharsets.UTF_8).replace("\r\n", "\n");
            String anchor = "const uint TotalNumParticles = GetTotalNumParticles();\n\tconst int GroupStartOffset";
            source = replaceOnce(source, anchor,
                    "const uint TotalNumParticles = GetTotalNumParticles();\n"
                    + "\tif (TotalNumParticles == 0) return;\n\tconst int GroupStartOffset");
            Files.writeString(sort, source, StandardCharsets.UTF_8);
        }
        Path indices = stageRoot.resolve(ROOT + "Ribbons/NiagaraRibbonGenerateIndices.usf");
        if (Files.isRegularFile(indices)) {
            source = Files.readString(indices, StandardCharsets.UTF_8);
            source = replaceOnce(source, "const uint SegmentIndex = Segments[RawParticleID];",
                    "const uint SegmentIndex = RawParticleID < MaxPossibleSegments ? Segments[RawParticleID] : INDEX_NONE;");
            Files.writeString(indices, source, StandardCharsets.UTF_8);
        }
        Path uv = stageRoot.resolve(ROOT + "Ribbons/NiagaraRibbonRibbonUVParamCalculation.usf");
        if (Files.isRegularFile(uv)) {
            source = Files.readString(uv, StandardCharsets.UTF_8);
            for (String index : new String[]{"FirstIndex", "FirstIndex + 1", "LastIndex - 1", "LastIndex"}) {
                String original = "GetFloat(NormalizedAgeDataOffset, SortedIndices[" + index + "])";
                source = replaceOnce(source, original, "SafeGetFloat(NormalizedAgeDataOffset, SortedIndices[" + index + "], 0.0f)");
            }
            Files.writeString(uv, source, StandardCharsets.UTF_8);
        }
    }

    private static String replaceOnce(String source, String original, String replacement) {
        int index = source.indexOf(original);
        if (index < 0 || source.indexOf(original, index + original.length()) >= 0)
            throw new BakeException("Ribbon particle access source is missing or ambiguous: " + original);
        return source.substring(0, index) + replacement + source.substring(index + original.length());
    }
}
