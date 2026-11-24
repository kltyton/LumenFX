package com.kltyton.lumenfx.tools.shader;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Maps the Chaos typed SRVs to storage buffers and preserves zero for invalid indices. */
final class NiagaraChaosBufferSource {
    record Field(String suffix, String element) {
        String storageElement() { return element.equals("float3") ? "float4" : element; }
        int stride() { return storageElement().equals("float4") ? 16 : 4; }
    }

    static final String KIND = "niagara_chaos_events";
    static final String PREFIX = "chaos:";
    static final List<Field> FIELDS = List.of(
            new Field("PositionBuffer", "float3"), new Field("VelocityBuffer", "float3"),
            new Field("ExtentMinBuffer", "float"), new Field("ExtentMaxBuffer", "float"),
            new Field("VolumeBuffer", "float"), new Field("SolverIDBuffer", "int"),
            new Field("DensityBuffer", "float"), new Field("FrictionBuffer", "float"),
            new Field("RestitutionBuffer", "float"), new Field("SurfaceTypeBuffer", "int"),
            new Field("ColorBuffer", "float4"), new Field("IncomingLocationBuffer", "float3"),
            new Field("IncomingAccumulatedImpulseBuffer", "float3"), new Field("IncomingNormalBuffer", "float3"),
            new Field("IncomingVelocity1Buffer", "float3"), new Field("IncomingVelocity2Buffer", "float3"),
            new Field("IncomingAngularVelocity1Buffer", "float3"), new Field("IncomingAngularVelocity2Buffer", "float3"),
            new Field("IncomingMass1Buffer", "float"), new Field("IncomingMass2Buffer", "float"),
            new Field("IncomingTimeBuffer", "float"), new Field("TransformTranslationBuffer", "float3"),
            new Field("TransformRotationBuffer", "float4"), new Field("TransformScaleBuffer", "float3"),
            new Field("BoundsBuffer", "float3"));

    private NiagaraChaosBufferSource() { }

    static String lower(String source, String name, String element) {
        String resource = Pattern.quote(name);
        Pattern declaration = Pattern.compile("(?<![\\w])Buffer\\s*<\\s*" + element + "\\s*>\\s+" + resource + "\\s*;");
        if (!declaration.matcher(source).find()) return source;
        String helper = "LfxChaosRead_" + name;
        Pattern read = Pattern.compile("(?<![\\w])" + resource + "\\s*\\[\\s*([^\\[\\]\\r\\n]+?)\\s*\\]");
        String rewritten = read.matcher(source).replaceAll(helper + "($1)");
        String storage = element.equals("float3") ? "float4" : element;
        String component = element.equals("float3") ? ".xyz" : "";
        String replacement = "StructuredBuffer<" + storage + "> " + name + ";\n"
                + element + " " + helper + "(int Index)\n{\n"
                + "    uint Count, Stride;\n"
                + "    " + name + ".GetDimensions(Count, Stride);\n"
                + "    if (Index < 0 || uint(Index) >= Count) return (" + element + ")0;\n"
                + "    return " + name + "[Index]" + component + ";\n"
                + "}";
        return declaration.matcher(rewritten).replaceAll(Matcher.quoteReplacement(replacement));
    }
}
