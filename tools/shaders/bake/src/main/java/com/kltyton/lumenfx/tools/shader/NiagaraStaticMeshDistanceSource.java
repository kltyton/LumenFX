package com.kltyton.lumenfx.tools.shader;

/** Keeps source mesh SDF query semantics while binding each selected asset's native sparse mips directly. */
final class NiagaraStaticMeshDistanceSource {
    private static final String PARAMETERS = """
            uint $_MeshFieldValid;
            float4x4 $_MeshFieldTransform;
            float3 $_MeshFieldExtent;
            float3 $_MeshFieldWorldScale;
            int4 $_MeshFieldMipLayout[3];
            uint3 $_MeshFieldBrickStarts;
            float4 $_MeshFieldScales[3];
            float4 $_MeshFieldAdds[3];
            uint2 $_MeshFieldTiles;
            float3 $_MeshFieldAtlasInverseExtent;
            Texture3D<uint> $_MeshFieldTable;
            Texture3D<float> $_MeshFieldAtlas;
            SamplerState $_MeshFieldAtlasSampler;
            """;

    private static final String BODY = """
            {
                OutDistance = bUseMaxDistance ? MaxDistance : 1e38f;
                OutIsValid = bExecute && $_MeshFieldValid != 0;
                if (!OutIsValid) return;
                MaxDistance = max(0.001f, OutDistance);
                float3 local = mul(float4(WorldPosition, 1.0f), $_MeshFieldTransform).xyz;
                float3 toBox = (abs(local) - $_MeshFieldExtent) * $_MeshFieldWorldScale;
                float outside = length(max(toBox, 0.0f));
                float inside = min(0.0f, max(toBox.x, max(toBox.y, toBox.z)));
                float boxDistance = outside + inside;
                if (boxDistance >= MaxDistance) return;
                float3 volume = clamp(local, -$_MeshFieldExtent, $_MeshFieldExtent);
                float distance = 0.0f;
                for (int level = 2; level >= 0; --level)
                {
                    int4 layout = $_MeshFieldMipLayout[level];
                    float4 scale = $_MeshFieldScales[level], add = $_MeshFieldAdds[level];
                    float3 uv = volume * scale.xyz + add.xyz;
                    int3 cell = clamp(int3(uv), int3(0, 0, 0), layout.xyz - 1);
                    uint brick = $_MeshFieldTable.Load(int4(cell + int3(0, 0, layout.w), 0));
                    float maxEncoded = scale.w + add.w;
                    distance = maxEncoded;
                    if (brick != 0xffffffffu)
                    {
                        brick += $_MeshFieldBrickStarts[level];
                        uint xTiles = $_MeshFieldTiles.x, yTiles = $_MeshFieldTiles.y;
                        uint3 tile = uint3(brick % xTiles, (brick / xTiles) % yTiles, brick / (xTiles * yTiles));
                        float3 pixel = float3(tile) * 8.0f + saturate(uv - float3(cell)) * 7.0f + 0.5f;
                        float packed = $_MeshFieldAtlas.SampleLevel($_MeshFieldAtlasSampler, pixel * $_MeshFieldAtlasInverseExtent, 0);
                        distance = packed * scale.w + add.w;
                    }
                    if (abs(distance) > 0.25f * maxEncoded) break;
                }
                float worldScale = min($_MeshFieldWorldScale.x, min($_MeshFieldWorldScale.y, $_MeshFieldWorldScale.z));
                OutDistance = max(distance * worldScale + max(0.0f, boxDistance), boxDistance);
            }
            """;

    private NiagaraStaticMeshDistanceSource() { }

    static String lower(String source, String symbol) {
        String name = "void QueryDistanceField_" + symbol + "(";
        int function = source.indexOf(name);
        if (function < 0) return source;
        int body = source.indexOf('{', function);
        if (body < 0) throw new BakeException("Missing source static mesh distance field function " + symbol);
        int depth = 1, end = body + 1;
        while (end < source.length() && depth > 0) {
            char character = source.charAt(end++);
            if (character == '{') depth++;
            else if (character == '}') depth--;
        }
        if (depth != 0) throw new BakeException("Incomplete source static mesh distance field function " + symbol);
        return source.substring(0, function) + PARAMETERS.replace("$", symbol) + source.substring(function, body)
                + BODY.replace("$", symbol) + source.substring(end);
    }
}
