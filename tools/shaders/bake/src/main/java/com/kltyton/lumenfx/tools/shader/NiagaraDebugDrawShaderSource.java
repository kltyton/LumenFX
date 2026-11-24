package com.kltyton.lumenfx.tools.shader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Retains native packed lines while moving each simulation tile to the line buffer's world tile. */
final class NiagaraDebugDrawShaderSource {
    private NiagaraDebugDrawShaderSource() { }

    static void lower(Path stageRoot) throws IOException {
        Path file = stageRoot.resolve("Engine/Plugins/FX/Niagara/Shaders/Private/NiagaraDebugDraw.ush");
        String source = Files.readString(file, StandardCharsets.UTF_8);
        String signature = "void PackLine(RWBuffer<uint> OutBuffer";
        if (!source.contains(signature) || !source.contains("uint BufferOffset = InstanceIndex * 7;"))
            throw new BakeException("The native Niagara debug line packing declaration is missing");
        source = source.replace(signature, "void PackLine(RWStructuredBuffer<uint> OutBuffer");
        int first = source.indexOf("void PackLine(");
        int body = source.indexOf("uint BufferOffset = InstanceIndex * 7;", first);
        source = source.substring(0, first) + "float3 LfxDebugLineWorldTile;\n" + source.substring(first, body)
                + """
                    float3 tileShift = (GetEngineOwnerLWCTile() - LfxDebugLineWorldTile) * 2097152.0f;
                    InPosition0 += tileShift;
                    InPosition1 += tileShift;
                    """ + source.substring(body);
        Files.writeString(file, source, StandardCharsets.UTF_8);
    }
}
