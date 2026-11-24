package com.kltyton.lumenfx.tools.shader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds the monoscopic ViewState from the pinned engine's view parameter table. */
final class NiagaraViewStateSource {
    private static final Pattern MEMBER = Pattern.compile(
            "VIEW_UNIFORM_BUFFER_MEMBER(?:_PER_VIEW|_ARRAY|_EX)*\\s*\\(([^)]*)\\)");
    private static final Pattern PARAMETER = Pattern.compile(
            "SHADER_PARAMETER(_TEXTURE|_SRV|_UAV|_SAMPLER)?\\s*\\(\\s*([^,]+),\\s*([A-Za-z_]\\w*)\\s*\\)");
    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("FMatrix44f", "float4x4"), Map.entry("FVector2f", "float2"),
            Map.entry("FVector3f", "float3"), Map.entry("FVector4f", "float4"),
            Map.entry("FLinearColor", "float4"), Map.entry("FIntPoint", "int2"),
            Map.entry("FIntVector", "int3"), Map.entry("FIntVector4", "int4"),
            Map.entry("FUintVector", "uint3"), Map.entry("FUintVector2", "uint2"),
            Map.entry("FUintVector3", "uint3"), Map.entry("FUintVector4", "uint4"),
            Map.entry("FUintPoint", "uint2"),
            Map.entry("float", "float"), Map.entry("int", "int"),
            Map.entry("int32", "int"), Map.entry("uint32", "uint"));
    private static final Map<String, String> DIMENSIONS = Map.of(
            "TVC_MAX", "2", "NUM_ATMOSPHERE_LIGHTS", "2",
            "SKY_IRRADIANCE_ENVIRONMENT_MAP_VEC4_COUNT", "8",
            "GlobalDistanceField::MaxClipmaps", "6", "MAX_PHYSICS_FIELD_TARGETS", "32");

    private record Member(String type, String name, String dimension) { }

    private NiagaraViewStateSource() { }

    private static List<Member> members(String sceneView) {
        int begin = sceneView.indexOf("#define VIEW_UNIFORM_BUFFER_MEMBER_TABLE");
        int end = sceneView.indexOf("/** The uniform shader parameters associated with a view. */", begin);
        if (begin < 0 || end < 0) throw new BakeException("UE view parameter table is missing");
        List<Member> members = new ArrayList<>();
        Matcher matcher = MEMBER.matcher(sceneView.substring(begin, end));
        while (matcher.find()) {
            String[] args = matcher.group(1).split(",");
            if (args.length < 2) throw new BakeException("Invalid UE view member " + matcher.group());
            String type = TYPES.get(args[0].trim());
            if (type == null) throw new BakeException("Unsupported UE view member type " + args[0].trim());
            String name = args[1].trim();
            boolean array = matcher.group().substring(0, matcher.group().indexOf('(')).contains("_ARRAY");
            String dimension = "";
            if (array) {
                if (args.length != 3) throw new BakeException("Invalid UE view array " + name);
                String source = args[2].trim();
                dimension = DIMENSIONS.getOrDefault(source, source);
                if (!dimension.matches("[1-9][0-9]*")) throw new BakeException("Unknown UE view array size " + source);
            }
            members.add(new Member(type, name, dimension));
        }
        if (members.isEmpty()) throw new BakeException("UE view parameter table is empty");
        return members;
    }

    static String generate(String sceneView) {
        List<Member> members = members(sceneView);
        StringBuilder out = new StringBuilder("#if INSTANCED_STEREO || MOBILE_MULTI_VIEW\n#error Niagara view requires monoscopic rendering\n#endif\n");
        out.append("#if VIEW_HAS_TILEOFFSET_DATA\nstruct ViewStateTileOffsetData {\n")
                .append("FLWCVector3 WorldCameraOrigin, WorldViewOrigin, PrevWorldCameraOrigin, PrevWorldViewOrigin;\n")
                .append("FLWCVector3 PreViewTranslation, PrevPreViewTranslation;\n};\n#endif\nstruct ViewState {\n");
        for (Member member : members) {
            out.append(member.type()).append(' ').append(member.name());
            if (!member.dimension().isEmpty()) out.append('[').append(member.dimension()).append(']');
            out.append(";\n");
        }
        out.append("FDFInverseMatrix WorldToClip;\nFDFMatrix ClipToWorld, ScreenToWorld, PrevClipToWorld;\n")
                .append("FDFVector3 WorldCameraOrigin, WorldViewOrigin, PrevWorldCameraOrigin, PrevWorldViewOrigin;\n")
                .append("FDFVector3 PreViewTranslation, PrevPreViewTranslation;\n")
                .append("#if VIEW_HAS_TILEOFFSET_DATA\nViewStateTileOffsetData TileOffset;\n#endif\n};\n")
                .append("void FinalizeViewState(inout ViewState InOutView);\nViewState GetPrimaryView() {\nViewState Result;\n");
        for (Member member : members) out.append("Result.").append(member.name()).append(" = View.").append(member.name()).append(";\n");
        return out.append("FinalizeViewState(Result);\nreturn Result;\n}\n").toString();
    }

    static String viewUniform(String sceneView) {
        List<Member> members = new ArrayList<>(members(sceneView));
        int begin = sceneView.indexOf("BEGIN_GLOBAL_SHADER_PARAMETER_STRUCT_WITH_CONSTRUCTOR(FViewUniformShaderParameters");
        int end = sceneView.indexOf("END_GLOBAL_SHADER_PARAMETER_STRUCT()", begin);
        if (begin < 0 || end < 0) throw new BakeException("UE view parameter resources are missing");
        List<String> resources = new ArrayList<>();
        Matcher matcher = PARAMETER.matcher(sceneView.substring(begin, end));
        while (matcher.find()) {
            String kind = matcher.group(1), type = matcher.group(2).trim(), name = matcher.group(3);
            if (kind == null) {
                String hlsl = TYPES.get(type);
                if (hlsl == null) throw new BakeException("Unsupported UE view parameter type " + type);
                members.add(new Member(hlsl, name, ""));
            } else {
                resources.add(type + " View_" + name + ";\n");
            }
        }
        StringBuilder out = new StringBuilder("#pragma once\ncbuffer ViewUniformBuffer {\n");
        for (Member member : members) {
            out.append(member.type()).append(" View_").append(member.name());
            if (!member.dimension().isEmpty()) out.append('[').append(member.dimension()).append(']');
            out.append(";\n");
        }
        out.append("};\n");
        for (String resource : resources) out.append(resource);
        return out.toString();
    }
}
