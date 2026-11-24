package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.render.scene.material.MaterialPrimitiveFrame;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public final class NiagaraGpuMeshDraw implements AutoCloseable {
    private record Key(int mesh, int lod, int section) { }
    private final String name;
    private final NiagaraGpuPrograms programs;
    private final boolean partialDepth;
    private final Map<Key, Commands> commands = new HashMap<>();
    private boolean closed;

    public NiagaraGpuMeshDraw(String name, NiagaraGpuPrograms programs) {
        this(name, programs, false);
    }

    public NiagaraGpuMeshDraw(String name, NiagaraGpuPrograms programs, boolean partialDepth) {
        this.name = name;
        this.programs = programs;
        this.partialDepth = partialDepth;
    }

    public boolean readsPartialDepth() { return partialDepth; }

    public NiagaraGpuMeshFrame prepare(CommandEncoder encoder, NiagaraGpuMeshCollector.Task task,
                                        NiagaraGpuSortCollector.@Nullable Sorted sorted) {
        var emitterScene = com.kltyton.lumenfx.client.render.mesh.scene.MeshEmitterSceneData.applies(task.geometry().renderer(), task.renderer().sourceMode().equals("Emitter"))
                ? NiagaraMeshEmitterScene.prepare(task) : null;
        return prepare(encoder, task, sorted, emitterScene);
    }

    public NiagaraGpuMeshFrame prepare(CommandEncoder encoder, NiagaraGpuMeshCollector.Task task,
                                        NiagaraGpuSortCollector.@Nullable Sorted sorted,
                                        com.kltyton.lumenfx.client.render.mesh.scene.@Nullable MeshEmitterSceneData emitterScene) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Niagara mesh draw is closed");
        NiagaraGpuDataBuffer data = task.particles().current();
        var uniforms = NiagaraGpuSpriteUniforms.writeMesh(encoder, task.emitter(), task.renderer(), data,
                task.system(), task.variables(), task.input(), task.store(), task.camera(), task.originX(), task.originY(), task.originZ(),
                task.time(), sorted != null, sorted == null ? 0 : sorted.offset(), task.previousPose(), task.componentVelocity());
        var key = new Key(task.meshIndex(), task.lod(), task.geometry().section());
        var indirect = commands.computeIfAbsent(key, value -> new Commands(name + "." + value, programs));
        indirect.beginFrame();
        GpuBuffer dummy = programs.dummyBuffer();
        boolean emitterSource = task.renderer().sourceMode().equals("Emitter");
        var componentBounds = task.componentBounds();
        return new NiagaraGpuMeshFrame(task.material(), task.definition().sortOrder(), task.depth(), task.geometry(), uniforms,
                emitterSource || data.floats() == null ? dummy : data.floats(), emitterSource || data.halves() == null ? dummy : data.halves(),
                sorted == null ? dummy : sorted.indices(), task.particles(), sorted == null ? null : sorted.culledCounts(),
                sorted == null ? 0 : sorted.culledOffset(), emitterSource, task.stereo(), task.postOpaque(), indirect, task.input().receivesDecals(),
                MaterialPrimitiveFrame.forMaterial(task.material(), task.input(), task.previousPose(), () -> componentBounds),
                task.input().firstPerson(), task.renderer(), emitterScene, null, partialDepth);
    }

    /** Different device geometry chunks need their own index count in the GPU command. */
    public static final class Commands implements AutoCloseable {
        private final String name;
        private final NiagaraGpuPrograms programs;
        private final java.util.List<NiagaraGpuIndirectDraw> draws = new java.util.ArrayList<>();
        private int next;
        private Commands(String name, NiagaraGpuPrograms programs) { this.name = name; this.programs = programs; }
        private void beginFrame() { next = 0; }
        GpuBufferSlice prepare(CommandEncoder encoder, NiagaraGpuParticleView particles, int indices,
                                        @Nullable GpuBufferSlice culledCounts, int culledOffset, boolean stereo, boolean postOpaque) {
            if (next == draws.size()) draws.add(new NiagaraGpuIndirectDraw(name + ".Chunk" + next, programs));
            return draws.get(next++).prepare(encoder, particles, indices, 0, culledCounts, culledOffset, stereo, postOpaque);
        }
        @Override public void close() { draws.forEach(NiagaraGpuIndirectDraw::close); draws.clear(); }
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        commands.values().forEach(Commands::close); commands.clear();
    }
}
