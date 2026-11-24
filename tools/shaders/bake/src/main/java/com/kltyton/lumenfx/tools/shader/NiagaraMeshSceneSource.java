package com.kltyton.lumenfx.tools.shader;

import java.util.regex.Pattern;

/** Selects the source GPUScene transform codec without its scene-buffer declarations. */
final class NiagaraMeshSceneSource {
    private NiagaraMeshSceneSource() { }

    static String codec(String sceneData) {
        String begin = "void GetHemiOrthoBasis(";
        String end = "// Helpers to pack/unpack the primitive ID";
        int first = sceneData.indexOf(begin);
        int last = first < 0 ? -1 : sceneData.indexOf(end, first);
        if (first < 0 || last < 0 || sceneData.indexOf(begin, first + begin.length()) >= 0)
            throw new BakeException("Pinned GPUScene transform codec boundaries are missing or ambiguous");
        String code = sceneData.substring(first, last);
        if (!code.contains("uint4 EncodeScaleAndRotation(") || !code.contains("float4x4 DecodeTransform("))
            throw new BakeException("Pinned GPUScene transform codec is incomplete");
        return "// Copyright Epic Games, Inc. All Rights Reserved.\n#pragma once\n#include \"/Engine/Private/OctahedralCommon.ush\"\n" + code;
    }

    static String uniform(String source, String name) {
        var declarations = Pattern.compile("\\buniform\\s+" + Pattern.quote(name) + "\\s*\\{").matcher(source);
        if (!declarations.find()) throw new BakeException("Mesh scene uniform is missing: " + name);
        int open = declarations.end();
        if (declarations.find()) throw new BakeException("Mesh scene uniform is ambiguous: " + name);
        int close = source.indexOf('}', open);
        if (close < 0) throw new BakeException("Mesh scene uniform is unterminated: " + name);
        String fields = source.substring(open, close).replaceAll("\\buvec4\\b", "uint4")
                .replaceAll("\\bvec4\\b", "float4");
        return "#pragma once\ncbuffer " + name + "\n{\n" + fields + "\n};\n";
    }

    static String frustum(String source) {
        String structBegin = "struct FFrustumCullData";
        String structEnd = "struct FScreenRect";
        String functionsBegin = "FFrustumCullData BoxCullFrustumOrtho(";
        String functionsEnd = "float2 ProjectSphere(";
        int first = source.indexOf(structBegin), last = source.indexOf(structEnd, first);
        int functions = source.indexOf(functionsBegin), end = source.indexOf(functionsEnd, functions);
        if (first < 0 || last < 0 || functions < 0 || end < 0
                || source.indexOf(functionsBegin, functions + functionsBegin.length()) >= 0)
            throw new BakeException("Pinned GPUScene box-frustum source boundaries are missing or ambiguous");
        return "// Copyright Epic Games, Inc. All Rights Reserved.\n#pragma once\n#ifndef PLATFORM_SPECIFIC_ISOLATE\n#define PLATFORM_SPECIFIC_ISOLATE ISOLATE\n#endif\n"
                + source.substring(first, last) + source.substring(functions, end);
    }
}
