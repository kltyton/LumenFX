package com.kltyton.lumenfx.client.compat.iris.motion;

import com.mojang.blaze3d.systems.RenderSystem;
import com.kltyton.lumenfx.client.compat.iris.material.IrisSurfaceInputs;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.HashMap;
import java.util.Map;
import net.irisshaders.iris.pipeline.programs.ShaderSupplier;
import net.irisshaders.iris.pipeline.programs.PartialShader;

/** Immutable Iris shader inputs and original creation suppliers indexed by live GL program. */
public final class IrisShaderSources {
    public record Sources(String vertex, String geometry, String tessControl,
                          String tessEval, String fragment, VertexFormat vertexFormat,
                          boolean fallback) { }

    public record Entry(Sources sources, ShaderSupplier supplier, IrisSurfaceInputs materialInputs) {
        public Entry(Sources sources, ShaderSupplier supplier) {
            this(sources, supplier, sources == null ? null : IrisSurfaceInputs.find(sources.fragment()));
        }
    }

    private static final Map<Integer, Entry> SOURCES = new HashMap<>();

    private IrisShaderSources() { }

    public static void register(int program, String vertex, String geometry,
                                String tessControl, String tessEval, String fragment,
                                VertexFormat vertexFormat, boolean fallback) {
        RenderSystem.assertOnRenderThread();
        Entry previous = SOURCES.get(program);
        SOURCES.put(program, new Entry(new Sources(vertex, geometry, tessControl, tessEval,
                fragment, vertexFormat, fallback), previous == null ? null : previous.supplier()));
    }

    public static void registerSupplier(int program, ShaderSupplier supplier) {
        RenderSystem.assertOnRenderThread();
        Entry previous = SOURCES.get(program);
        SOURCES.put(program, new Entry(previous == null ? null : previous.sources(), supplier,
                previous == null ? null : previous.materialInputs()));
    }

    public static Entry get(int program) {
        RenderSystem.assertOnRenderThread();
        return SOURCES.get(program);
    }

    public static IrisMotionShaderClone cloneProgram(int program, PartialShader linked) {
        RenderSystem.assertOnRenderThread();
        Entry entry = SOURCES.get(program);
        if (entry == null || entry.supplier() == null)
            throw new IllegalStateException("Missing Iris shader supplier for program " + program);
        return IrisMotionShaderClone.create(entry.supplier(), linked);
    }

    public static void remove(int program) {
        RenderSystem.assertOnRenderThread();
        com.kltyton.lumenfx.client.compat.iris.material.IrisMaterialPrograms.remove(program);
        SOURCES.remove(program);
    }
}
