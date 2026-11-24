package com.kltyton.lumenfx.client.compat.iris.motion;

import com.kltyton.lumenfx.client.compat.iris.mixin.IrisCustomUniformsAccessor;
import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.List;
import net.irisshaders.iris.pipeline.programs.PartialShader;
import net.irisshaders.iris.pipeline.programs.ShaderSupplier;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.lwjgl.opengl.GL20C;

/** Creates a private program using the original Iris shader's binding factory. */
public final class IrisMotionShaderClone implements AutoCloseable {
    private record UniformMapping(CustomUniforms uniforms, Object pass) {
        void remove() {
            ((IrisCustomUniformsAccessor) (Object) uniforms).lumenfx$locationMap().remove(pass);
        }
    }

    private static final class Creation {
        final PartialShader original;
        final int replacement;
        final List<UniformMapping> mappings = new ArrayList<>();

        Creation(PartialShader original, int replacement) {
            this.original = original;
            this.replacement = replacement;
        }
    }

    private static Creation creation;
    private final GlProgram program;
    private final List<UniformMapping> mappings;
    private boolean closed;

    private IrisMotionShaderClone(GlProgram program, List<UniformMapping> mappings) {
        this.program = program;
        this.mappings = mappings;
    }

    public static IrisMotionShaderClone create(ShaderSupplier source, PartialShader linked) {
        RenderSystem.assertOnRenderThread();
        if (creation != null) throw new IllegalStateException("An Iris motion shader is already being created");
        int programId = linked.program();
        com.kltyton.lumenfx.client.compat.iris.material.IrisMaterialPrograms.remove(programId);
        if (source.id().program() == programId)
            throw new IllegalArgumentException("The Iris motion shader must have its own linked program");
        if (GlStateManager.glGetProgrami(programId, GL20C.GL_LINK_STATUS) == 0) {
            String log = GlStateManager.glGetProgramInfoLog(programId, 32768);
            linked.getFinally();
            GlStateManager.glDeleteProgram(programId);
            throw new IllegalStateException("Iris motion shader failed to link: " + log);
        }
        linked.getFinally();
        Creation current = new Creation(source.id(), programId);
        creation = current;
        GlProgram program = null;
        try {
            program = source.shader().get();
            if (program.getProgramId() != programId)
                throw new IllegalStateException("Iris motion shader factory returned a different program");
            IrisMotionBindings.attach(program);
            return new IrisMotionShaderClone(program, List.copyOf(current.mappings));
        } catch (RuntimeException | Error failure) {
            try {
                for (UniformMapping mapping : current.mappings) mapping.remove();
            } finally {
                if (program != null && program.getProgramId() == programId) program.close();
                else GlStateManager.glDeleteProgram(programId);
            }
            throw failure;
        } finally {
            creation = null;
        }
    }

    public static int replacement(PartialShader source) {
        RenderSystem.assertOnRenderThread();
        return creation != null && creation.original == source ? creation.replacement : 0;
    }

    public static void recordUniformMapping(CustomUniforms uniforms, Object pass) {
        RenderSystem.assertOnRenderThread();
        if (creation != null) creation.mappings.add(new UniformMapping(uniforms, pass));
    }

    public GlProgram program() {
        if (closed) throw new IllegalStateException("Iris motion shader is closed");
        return program;
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        try {
            for (UniformMapping mapping : mappings) mapping.remove();
        } finally {
            program.close();
        }
    }
}
