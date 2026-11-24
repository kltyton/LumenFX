package com.kltyton.lumenfx.effect.niagara.mesh;

import com.google.gson.JsonObject;

/** GPU sampling buffer encodings selected by the emitter's compiled skeletal mesh permutation. */
public record NiagaraSkeletalGpuLayout(int probabilityBits, boolean halfAdjacency, boolean allowDeformed) {
    public NiagaraSkeletalGpuLayout(int probabilityBits, boolean halfAdjacency) {
        this(probabilityBits, halfAdjacency, true);
    }

    public static NiagaraSkeletalGpuLayout read(JsonObject program) {
        NiagaraSkeletalGpuLayout layout = null;
        for (var variant : program.getAsJsonArray("variants")) {
            var defines = variant.getAsJsonObject().getAsJsonObject("defines");
            int bits = switch (define(defines, "DISKELMESH_PROBALIAS_FORMAT")) {
                case 0 -> 0;
                case 1 -> 8;
                case 2 -> 9;
                default -> throw new IllegalArgumentException("Invalid Niagara skeletal triangle sampling format");
            };
            boolean half = switch (define(defines, "DISKELMESH_ADJ_INDEX_FORMAT")) {
                case 0 -> false;
                case 1 -> true;
                default -> throw new IllegalArgumentException("Invalid Niagara skeletal adjacency format");
            };
            boolean allowDeformed = !defines.has("DISKELMESH_ALLOW_DEFORMED") || define(defines, "DISKELMESH_ALLOW_DEFORMED") != 0;
            var next = new NiagaraSkeletalGpuLayout(bits, half, allowDeformed);
            if (layout != null && !layout.equals(next))
                throw new IllegalArgumentException("Niagara skeletal mesh stages must share one sampling buffer layout");
            layout = next;
        }
        return layout == null ? new NiagaraSkeletalGpuLayout(0, false) : layout;
    }

    public int aliasWords() { return probabilityBits == 0 ? 2 : 1; }

    private static int define(JsonObject defines, String name) {
        return defines.has(name) ? defines.get(name).getAsInt() : 0;
    }
}
