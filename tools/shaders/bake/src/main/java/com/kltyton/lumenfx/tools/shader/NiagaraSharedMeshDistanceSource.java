package com.kltyton.lumenfx.tools.shader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;

/** Adapts the pinned mesh ID and sparse scene field lookups to the host's shared object and mip tables. */
final class NiagaraSharedMeshDistanceSource {
    static final String KIND = "niagara_shared_mesh_field";
    static final String RIGID_KIND = "niagara_rigid_mesh_field";
    private static final List<String> RIGID_QUERIES = List.of(
            "GetElementPointMeshDistanceFieldNoNormal", "GetClosestPointMeshDistanceField",
            "GetClosestPointMeshDistanceFieldAccurate", "GetClosestPointMeshDistanceFieldNoNormal",
            "GetMaxEncodedDistanceMeshDistanceField");
    private static final String SOURCE = """
            StructuredBuffer<uint4> LfxSharedMeshObjects;
            StructuredBuffer<uint4> LfxSharedMeshMips;
            uint LfxSharedMeshSlotCount;
            Texture3D<uint> LfxSharedMeshTable;
            Texture3D<float> LfxSharedMeshAtlas;
            SamplerState LfxSharedMeshAtlasSampler;

            struct LfxMeshFieldObject
            {
                uint firstMip;
                float3 offset;
                float3 tile;
                float3x3 worldToVolume;
                float3x3 volumeToWorld;
                float3 extent;
                float4 scale;
            };

            bool LfxResolveMeshField(int index, int tag, out LfxMeshFieldObject field)
            {
                field = (LfxMeshFieldObject)0;
                if (index < 0 || uint(index) >= LfxSharedMeshSlotCount) return false;
                uint at = uint(index) * 10;
                uint4 id = LfxSharedMeshObjects[at];
                if (id.y == 0 || asint(id.x) != tag) return false;
                float4 x = asfloat(LfxSharedMeshObjects[at + 3]);
                float4 y = asfloat(LfxSharedMeshObjects[at + 4]);
                float4 z = asfloat(LfxSharedMeshObjects[at + 5]);
                field.firstMip = id.z;
                field.offset = asfloat(LfxSharedMeshObjects[at + 1].xyz);
                field.tile = asfloat(LfxSharedMeshObjects[at + 2].xyz);
                field.worldToVolume = float3x3(x.xyz, y.xyz, z.xyz);
                field.extent = float3(x.w, y.w, z.w);
                field.scale = asfloat(LfxSharedMeshObjects[at + 6]);
                field.volumeToWorld = float3x3(asfloat(LfxSharedMeshObjects[at + 7].xyz),
                    asfloat(LfxSharedMeshObjects[at + 8].xyz), asfloat(LfxSharedMeshObjects[at + 9].xyz));
                return true;
            }

            float LfxSampleMeshField(uint mip, float3 volume)
            {
                uint at = mip * 4;
                int4 layout = asint(LfxSharedMeshMips[at]);
                uint4 atlas = LfxSharedMeshMips[at + 1];
                float4 scale = asfloat(LfxSharedMeshMips[at + 2]);
                float4 add = asfloat(LfxSharedMeshMips[at + 3]);
                float3 uv = volume * scale.xyz + add.xyz;
                int3 cell = clamp(int3(uv), 0, layout.xyz - 1);
                uint brick = LfxSharedMeshTable.Load(int4(cell + int3(0, 0, layout.w), 0));
                if (brick == 0xffffffffu) return scale.w + add.w;
                brick += atlas.x;
                uint tiles = atlas.y;
                uint3 tile = uint3(brick % tiles, (brick / tiles) % tiles, brick / (tiles * tiles));
                float3 pixel = float3(tile) * 8.0f + saturate(uv - float3(cell)) * 7.0f + 0.5f;
                float3 atlasSize = float3(atlas.z, atlas.z, atlas.w);
                float packed = LfxSharedMeshAtlas.SampleLevel(LfxSharedMeshAtlasSampler, pixel / atlasSize, 0);
                return packed * scale.w + add.w;
            }

            float LfxMeshFieldMaximum(uint mip)
            {
                return asfloat(LfxSharedMeshMips[mip * 4 + 2].w) + asfloat(LfxSharedMeshMips[mip * 4 + 3].w);
            }

            float LfxMeshSurface(LfxMeshFieldObject field, float3 volume)
            {
                float distance = 0;
                for (int level = 2; level >= 0; --level)
                {
                    uint mip = field.firstMip + uint(level);
                    distance = LfxSampleMeshField(mip, volume);
                    if (abs(distance) > .25f * LfxMeshFieldMaximum(mip)) break;
                }
                return distance * field.scale.w;
            }

            float LfxMeshBoxDistance(LfxMeshFieldObject field, float3 volume)
            {
                float3 toBox = (abs(volume) - field.extent) * field.scale.xyz;
                return length(max(0, toBox)) + min(0, max(toBox.x, max(toBox.y, toBox.z)));
            }

            float3 LfxMeshRelativeOrigin(LfxMeshFieldObject field)
            {
                return (field.tile - GetEngineOwnerLWCTile()) * 2097152.0f + field.offset;
            }

            float3 LfxMeshFieldGradient(uint mip, float3 volume)
            {
                float3 delta = 0.5f / (7.0f * asfloat(LfxSharedMeshMips[mip * 4 + 2].xyz));
                float3 x = float3(delta.x, 0, 0), y = float3(0, delta.y, 0), z = float3(0, 0, delta.z);
                return float3(LfxSampleMeshField(mip, volume + x) - LfxSampleMeshField(mip, volume - x),
                    LfxSampleMeshField(mip, volume + y) - LfxSampleMeshField(mip, volume - y),
                    LfxSampleMeshField(mip, volume + z) - LfxSampleMeshField(mip, volume - z));
            }

            """;
    private static final String RIGID_SOURCE = """
            bool LfxValidRigidMeshField(uint index)
            {
                if (index >= LfxSharedMeshSlotCount) return false;
                return LfxSharedMeshObjects[index * 10].y != 0;
            }

            LfxMeshFieldObject LfxRigidMeshField(uint index)
            {
                LfxMeshFieldObject field;
                LfxResolveMeshField(int(index), asint(LfxSharedMeshObjects[index * 10].x), field);
                return field;
            }

            float LfxRigidMeshDistance(uint index, float3 position, float limit, bool includeOuterBox)
            {
                LfxMeshFieldObject field = LfxRigidMeshField(index);
                float3 volume = mul(field.worldToVolume, position - LfxMeshRelativeOrigin(field));
                float3 clamped = clamp(volume, -field.extent, field.extent);
                float box = LfxMeshBoxDistance(field, includeOuterBox ? volume : clamped);
                if (box >= limit) return limit;
                float distance = LfxMeshSurface(field, clamped);
                return includeOuterBox ? max(distance + max(0.0f, box), box) : distance;
            }

            void LfxRigidMeshClosest(uint index, float3 position, float distance, out float3 closest,
                out float3 normal, out float maxEncodedDistance, out bool normalIsValid)
            {
                LfxMeshFieldObject field = LfxRigidMeshField(index);
                float3 origin = LfxMeshRelativeOrigin(field);
                float3 volume = clamp(mul(field.worldToVolume, position - origin), -field.extent, field.extent);
                maxEncodedDistance = LfxMeshFieldMaximum(field.firstMip) * field.scale.w;
                float3 gradient = LfxMeshFieldGradient(field.firstMip, volume);
                float volumeLength = length(gradient);
                float3 worldGradient = volumeLength > 0.0f ? mul(gradient / volumeLength, field.worldToVolume) : 0;
                float worldLength = length(worldGradient);
                normalIsValid = worldLength > 0.0f && maxEncodedDistance - abs(distance) > 1e-8f;
                normal = normalIsValid ? worldGradient / worldLength : 0;
                closest = normalIsValid ? origin + mul(field.volumeToWorld, volume) - normal * distance : position;
            }

            float LfxRigidMeshMaximum(uint index)
            {
                LfxMeshFieldObject field = LfxRigidMeshField(index);
                return LfxMeshFieldMaximum(field.firstMip) * field.scale.w;
            }

            """;
    private static final String DISTANCE = """
            {
                if (!bUseMaxDistance) MaxDistance = 1e38f;
                LfxMeshFieldObject field;
                OutIsValid = bExecute && LfxResolveMeshField(StaticMeshID.Index, StaticMeshID.AcquireTag, field);
                OutDistance = MaxDistance;
                if (!OutIsValid) return;
                MaxDistance = max(0.001f, MaxDistance);
                float3 volume = mul(field.worldToVolume, WorldPosition - LfxMeshRelativeOrigin(field));
                float box = LfxMeshBoxDistance(field, volume);
                OutDistance = MaxDistance;
                if (box < MaxDistance)
                {
                    float distance = LfxMeshSurface(field, clamp(volume, -field.extent, field.extent));
                    OutDistance = max(distance + max(0.0f, box), box);
                }
            }
            """;
    private static final String CLOSEST = """
            {
                if (!bUseMaxDistance) MaxDistance = MAX_DISTANCE;
                MaxDistance = max(0.001f, MaxDistance);
                LfxMeshFieldObject field;
                OutIsValid = bExecute && LfxResolveMeshField(StaticMeshID.Index, StaticMeshID.AcquireTag, field);
                OutClosestDistance = MaxDistance;
                OutClosestPosition = WorldPosition;
                OutClosestNormal = float3(0, 0, 1);
                OutMaxEncodedDistance = MaxDistance;
                OutNormalIsValid = false;
                if (!OutIsValid) return;
                float3 origin = LfxMeshRelativeOrigin(field);
                float3 volume = clamp(mul(field.worldToVolume, WorldPosition - origin), -field.extent, field.extent);
                if (LfxMeshBoxDistance(field, volume) < MaxDistance) OutClosestDistance = LfxMeshSurface(field, volume);
                uint mip = field.firstMip;
                OutMaxEncodedDistance = LfxMeshFieldMaximum(mip) * field.scale.w;
                float3 gradient = LfxMeshFieldGradient(mip, volume);
                float gradientLength = length(gradient);
                float3 worldGradient = gradientLength > 0 ? mul(gradient / gradientLength, transpose(field.worldToVolume)) : 0;
                float worldLength = length(worldGradient);
                OutNormalIsValid = worldLength > 0 && OutMaxEncodedDistance - abs(OutClosestDistance) > 1e-8f;
                if (OutNormalIsValid)
                {
                    OutClosestNormal = worldGradient / worldLength;
                    OutClosestPosition = origin + mul(field.volumeToWorld, volume) - OutClosestNormal * OutClosestDistance;
                }
            }
            """;

    private NiagaraSharedMeshDistanceSource() { }

    static void lower(Path root, List<String> symbols, List<String> rigidSymbols) throws IOException {
        Path translated = root.resolve("Engine/Shaders/Generated/NiagaraEmitterInstance.ush");
        String source = Files.readString(translated, StandardCharsets.UTF_8);
        int first = Integer.MAX_VALUE;
        for (String symbol : symbols) {
            String query = "void QueryDistanceField_" + symbol + "(";
            String closest = "void GetClosestPointOnMeshDistanceField_" + symbol + "(";
            first = firstOccurrence(source, query, first);
            first = firstOccurrence(source, closest, first);
            source = replaceBody(source, query, original -> DISTANCE);
            source = replaceBody(source, closest, original -> CLOSEST);
        }
        if (first == Integer.MAX_VALUE && !symbols.isEmpty())
            throw new BakeException("Missing translated Niagara indirect mesh field functions");
        int firstRigid = Integer.MAX_VALUE;
        for (String symbol : rigidSymbols) {
            for (String query : RIGID_QUERIES) {
                String function = "void " + query + "_" + symbol + "(";
                firstRigid = firstOccurrence(source, function, firstRigid);
                source = replaceBody(source, function, original -> lowerRigidQuery(query, original));
            }
        }
        first = Math.min(first, firstRigid);
        if (first == Integer.MAX_VALUE) return;
        String helpers = firstRigid == Integer.MAX_VALUE ? SOURCE : SOURCE + RIGID_SOURCE;
        Files.writeString(translated, source.substring(0, first) + helpers + source.substring(first), StandardCharsets.UTF_8);
    }

    private static String lowerRigidQuery(String name, String body) {
        String lowered = body
                .replace("const FDFVector3 LWCWorldPosition = DFFromTileOffset_Hack(MakeLWCVector3(GetEngineOwnerLWCTile(), WorldPosition));",
                        "const float3 LWCWorldPosition = WorldPosition;")
                .replace("DFIndex >= 0 && DFIndex < NumSceneObjects", "LfxValidRigidMeshField(DFIndex)")
                .replace("DistanceToNearestSurfaceForObject(DFIndex, LWCWorldPosition, MaxDistance)",
                        "LfxRigidMeshDistance(DFIndex, LWCWorldPosition, MaxDistance, true)")
                .replace("GetDistanceToMeshDistanceField(DFIndex, LWCWorldPosition, MaxDistance)",
                        "LfxRigidMeshDistance(DFIndex, LWCWorldPosition, MaxDistance, false)")
                .replace("ComputeClosestPointMeshDistanceField(DFIndex,", "LfxRigidMeshClosest(DFIndex,");
        if (name.equals("GetClosestPointMeshDistanceFieldAccurate"))
            return lowered.replace("float OutClosestDistanceTmp = LfxRigidMeshDistance(",
                    "if (!LfxValidRigidMeshField(DFIndex)) continue;\n\t\tfloat OutClosestDistanceTmp = LfxRigidMeshDistance(");
        if (name.equals("GetMaxEncodedDistanceMeshDistanceField"))
            return lowered.replaceFirst("(?s)FDFObjectData DFObjectData = LoadDFObjectData\\(DFIndex\\);.*?DFObjectData\\.VolumeScale;",
                    "MaxEncodedDistance = LfxRigidMeshMaximum(DFIndex);");
        return lowered;
    }

    private static int firstOccurrence(String source, String function, int previous) {
        int at = source.indexOf(function);
        return at < 0 ? previous : Math.min(at, previous);
    }

    private static String replaceBody(String source, String function, UnaryOperator<String> rewrite) {
        int signature = source.indexOf(function);
        int body;
        while (true) {
            if (signature < 0) return source;
            int parametersEnd = source.indexOf(')', signature + function.length());
            if (parametersEnd < 0) throw new BakeException("Unclosed Niagara mesh field signature " + function);
            body = parametersEnd + 1;
            while (body < source.length() && Character.isWhitespace(source.charAt(body))) body++;
            if (body < source.length() && source.charAt(body) == '{') break;
            if (body == source.length() || source.charAt(body) != ';')
                throw new BakeException("Missing Niagara mesh field function body " + function);
            // A translated VM external may declare the function before its native implementation.
            signature = source.indexOf(function, body + 1);
        }
        int depth = 1, end = body + 1;
        while (end < source.length() && depth > 0) {
            char value = source.charAt(end++);
            if (value == '{') depth++;
            else if (value == '}') depth--;
        }
        if (depth != 0) throw new BakeException("Unclosed Niagara mesh field function body " + function);
        return source.substring(0, body) + rewrite.apply(source.substring(body, end)) + source.substring(end);
    }
}
