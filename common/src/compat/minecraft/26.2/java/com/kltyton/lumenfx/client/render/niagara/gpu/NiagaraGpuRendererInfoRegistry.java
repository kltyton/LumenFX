package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.render.mesh.MeshRendererInformation;
import com.kltyton.lumenfx.effect.render.sprite.SpriteRendererInformation;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Renderer-info objects own shared mesh bounds while emitter symbols remain private. */
public final class NiagaraGpuRendererInfoRegistry implements AutoCloseable {
    private record Entry(MeshRendererInformation information, @Nullable GpuBuffer buffer) { }
    public record MeshSnapshot(MeshRendererInformation information, GpuBufferSlice buffer) { }
    private final NiagaraGpuPrograms programs;
    private final Function<String, @Nullable SpriteRendererInformation> sprites;
    private final Function<String, @Nullable MeshRendererInformation> meshes;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private boolean closed;

    public NiagaraGpuRendererInfoRegistry(NiagaraGpuPrograms programs, Function<String, @Nullable SpriteRendererInformation> sprites,
                                         Function<String, @Nullable MeshRendererInformation> meshes) {
        this.programs = programs; this.sprites = sprites; this.meshes = meshes;
    }
    public @Nullable SpriteRendererInformation sprite(String identity) {
        if (closed) throw new IllegalStateException("Renderer info registry is closed");
        return sprites.apply(identity);
    }
    public @Nullable MeshSnapshot mesh(String identity) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Renderer info registry is closed");
        var information = meshes.apply(identity);
        if (information == null) return null;
        String key = SourceNames.canonical(identity);
        Entry entry = entries.get(key);
        if (entry == null || entry.information() != information) {
            float[] bounds = information.bounds();
            GpuBuffer buffer = bounds.length == 0 ? null : NiagaraGpuMeshInfo.upload("Niagara.MeshInfo." + identity, bounds, programs.runtime());
            if (entry != null && entry.buffer() != null) entry.buffer().close();
            entry = new Entry(information, buffer); entries.put(key, entry);
        }
        return new MeshSnapshot(information, entry.buffer() == null ? programs.dummyBuffer().slice() : entry.buffer().slice());
    }
    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        for (var entry : entries.values()) if (entry.buffer() != null) entry.buffer().close();
        entries.clear();
    }
}
