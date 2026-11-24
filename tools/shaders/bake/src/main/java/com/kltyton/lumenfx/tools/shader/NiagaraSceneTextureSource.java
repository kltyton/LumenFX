package com.kltyton.lumenfx.tools.shader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Emits the deferred scene-texture resources declared by the pinned engine. */
final class NiagaraSceneTextureSource {
    private static final Pattern RESOURCE = Pattern.compile(
            "SHADER_PARAMETER_(?:RDG_TEXTURE(?:_SRV)?|SAMPLER)\\s*\\(\\s*([^,]+),\\s*([A-Za-z_]\\w*)\\s*\\)");
    private static final Pattern LEGACY_DECODER_WITHOUT_SGGX = Pattern.compile(
            "\\bfloat4 InGBufferVelocity,\\s*float CustomNativeDepth,");
    private static final Pattern STALE_SGGX_CALL = Pattern.compile(
            "(\\bDecodeGBufferData\\([^\\r\\n]*?\\bGBufferVelocity,)\\s*SGGX,(\\s*CustomNativeDepth,)");

    private NiagaraSceneTextureSource() { }

    static String generate(String sceneTextures) {
        int begin = sceneTextures.indexOf("BEGIN_GLOBAL_SHADER_PARAMETER_STRUCT(FSceneTextureUniformParameters");
        int end = sceneTextures.indexOf("END_GLOBAL_SHADER_PARAMETER_STRUCT()", begin);
        if (begin < 0 || end < 0) throw new BakeException("UE scene texture parameter table is missing");
        Matcher matcher = RESOURCE.matcher(sceneTextures.substring(begin, end));
        StringBuilder out = new StringBuilder("#pragma once\n");
        int count = 0;
        while (matcher.find()) {
            out.append(matcher.group(1).trim()).append(" SceneTexturesStruct_")
                    .append(matcher.group(2)).append(";\n");
            count++;
        }
        if (count == 0) throw new BakeException("UE scene texture parameter table is empty");
        return out.toString();
    }

    static void repairLegacyGBufferCalls(Path stageRoot) throws IOException {
        Path header = stageRoot.resolve("Engine/Shaders/Private/DeferredShadingCommon.ush");
        if (!Files.isRegularFile(header)) return;
        String source = Files.readString(header, StandardCharsets.UTF_8);
        // UE 5.9 preview's legacy decoder removed SGGX, while two call sites still pass it.
        if (!LEGACY_DECODER_WITHOUT_SGGX.matcher(source).find()) return;
        String corrected = STALE_SGGX_CALL.matcher(source).replaceAll("$1$2");
        if (!corrected.equals(source)) Files.writeString(header, corrected, StandardCharsets.UTF_8);
    }
}
