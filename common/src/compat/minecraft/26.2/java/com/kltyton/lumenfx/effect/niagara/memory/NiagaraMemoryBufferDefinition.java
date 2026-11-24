package com.kltyton.lumenfx.effect.niagara.memory;

import com.google.gson.JsonObject;

public record NiagaraMemoryBufferDefinition(int defaultElements, SyncMode sync) {
    public enum SyncMode {
        None, SyncCpuToGpu, SyncGpuToCpu, SyncBoth;
        public boolean toGpu() { return this == SyncCpuToGpu || this == SyncBoth; }
        public boolean toCpu() { return this == SyncGpuToCpu || this == SyncBoth; }
    }

    public NiagaraMemoryBufferDefinition {
        if (defaultElements < 0) throw new IllegalArgumentException("Negative Niagara memory buffer size");
    }

    public static NiagaraMemoryBufferDefinition read(JsonObject value) {
        return new NiagaraMemoryBufferDefinition(value.get("default_elements").getAsInt(),
                SyncMode.valueOf(value.get("gpu_sync_mode").getAsString()));
    }
}
