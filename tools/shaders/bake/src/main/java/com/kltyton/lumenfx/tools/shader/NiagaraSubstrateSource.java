package com.kltyton.lumenfx.tools.shader;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Emits the public Substrate parameter declarations from the pinned engine. */
final class NiagaraSubstrateSource {
    private static final Pattern PARAMETER = Pattern.compile(
            "SHADER_PARAMETER(_RDG_TEXTURE)?\\s*\\(\\s*([^,]+),\\s*([A-Za-z_]\\w*)\\s*\\)");

    private NiagaraSubstrateSource() { }

    static String generate(String source) {
        String common = block(source, "FSubstrateCommonParameters");
        String publicParameters = block(source, "FSubstratePublicParameters");
        StringBuilder constants = new StringBuilder("#pragma once\ncbuffer SubstratePublicUniformBuffer {\n");
        StringBuilder resources = new StringBuilder();
        append(common, constants, resources);
        append(publicParameters, constants, resources);
        return constants.append("};\n").append(resources).toString();
    }

    private static String block(String source, String name) {
        int start = source.indexOf("BEGIN_SHADER_PARAMETER_STRUCT(" + name + ",");
        int end = source.indexOf("END_SHADER_PARAMETER_STRUCT()", start);
        if (start < 0 || end < 0) throw new BakeException("UE Substrate parameter table is missing: " + name);
        return source.substring(start, end);
    }

    private static void append(String block, StringBuilder constants, StringBuilder resources) {
        Matcher matcher = PARAMETER.matcher(block);
        int count = 0;
        while (matcher.find()) {
            String type = matcher.group(2).trim(), name = matcher.group(3);
            if (matcher.group(1) != null) resources.append(type).append(" SubstratePublic_").append(name).append(";\n");
            else {
                String hlsl = switch (type) {
                    case "uint32" -> "uint";
                    case "int32" -> "int";
                    default -> throw new BakeException("Unsupported UE Substrate parameter type " + type);
                };
                constants.append(hlsl).append(" SubstratePublic_").append(name).append(";\n");
            }
            count++;
        }
        if (count == 0) throw new BakeException("UE Substrate parameter table is empty");
    }
}
