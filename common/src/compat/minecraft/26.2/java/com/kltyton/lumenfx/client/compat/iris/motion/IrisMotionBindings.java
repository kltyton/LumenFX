package com.kltyton.lumenfx.client.compat.iris.motion;

import com.kltyton.lumenfx.client.compat.iris.mixin.IrisExtendedShaderAccessor;
import com.kltyton.lumenfx.client.compat.iris.mixin.IrisGlProgramAccessor;
import com.kltyton.lumenfx.client.compat.iris.mixin.IrisProgramSamplersAccessor;
import com.kltyton.lumenfx.client.compat.iris.mixin.IrisSamplerBindingAccessor;
import com.kltyton.lumenfx.client.render.motion.shader.MotionShaderLayout;
import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.BitSet;
import java.util.Map;
import net.irisshaders.iris.pipeline.programs.ExtendedShader;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL31C;

/** Assigns capture bindings without reusing the shaderpack's uniform or sampler slots. */
final class IrisMotionBindings {
    // Iris 1.11.4 expands GlStateManager's texture-state array to 128 entries.
    private static final int IRIS_TRACKED_TEXTURE_UNITS = 128;

    private IrisMotionBindings() { }

    static void attach(GlProgram program) {
        RenderSystem.assertOnRenderThread();
        int id = program.getProgramId();
        Map<String, Uniform> uniforms = ((IrisGlProgramAccessor) (Object) program).lumenfx$uniformsByName();
        int atlasBlock = GL31C.glGetUniformBlockIndex(id, MotionShaderLayout.ATLAS_BLOCK);
        if (atlasBlock == GL31C.GL_INVALID_INDEX)
            throw new IllegalStateException("Iris motion shader has no atlas uniform block");
        int maxBindings = GL11C.glGetInteger(GL31C.GL_MAX_UNIFORM_BUFFER_BINDINGS);
        BitSet occupiedBindings = new BitSet(maxBindings);
        int blocks = GL20C.glGetProgrami(id, GL31C.GL_ACTIVE_UNIFORM_BLOCKS);
        for (int index = 0; index < blocks; index++) {
            if (index != atlasBlock)
                occupiedBindings.set(GL31C.glGetActiveUniformBlocki(id, index, GL31C.GL_UNIFORM_BLOCK_BINDING));
        }
        int atlasBinding = occupiedBindings.nextClearBit(0);
        if (atlasBinding >= maxBindings)
            throw new IllegalStateException("Iris motion shader has no free uniform buffer binding");
        GL31C.glUniformBlockBinding(id, atlasBlock, atlasBinding);
        uniforms.put(MotionShaderLayout.ATLAS_BLOCK, new Uniform.Ubo(atlasBinding));

        int vertexMap = GL20C.glGetUniformLocation(id, MotionShaderLayout.VERTEX_MAP);
        int previousAtlas = GL20C.glGetUniformLocation(id, MotionShaderLayout.PREVIOUS_CLIP_ATLAS);
        if (vertexMap < 0 && previousAtlas < 0) return;
        if (vertexMap < 0 || previousAtlas < 0)
            throw new IllegalStateException("Iris motion shader has incomplete history samplers");

        int maxUnits = Math.min(GL11C.glGetInteger(GL20C.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS),
                IRIS_TRACKED_TEXTURE_UNITS);
        BitSet occupiedUnits = new BitSet(maxUnits);
        occupiedUnits.set(0, Math.min(3, maxUnits));
        for (Uniform uniform : uniforms.values()) {
            if (uniform instanceof Uniform.Sampler sampler) occupiedUnits.set(sampler.samplerIndex());
            else if (uniform instanceof Uniform.Utb buffer) occupiedUnits.set(buffer.samplerIndex());
        }
        if (program instanceof ExtendedShader) {
            var samplers = ((IrisExtendedShaderAccessor) (Object) program).lumenfx$samplers();
            for (var binding : ((IrisProgramSamplersAccessor) (Object) samplers).lumenfx$samplerBindings())
                occupiedUnits.set(((IrisSamplerBindingAccessor) (Object) binding).lumenfx$textureUnit());
        }
        int mapUnit = occupiedUnits.nextClearBit(0);
        occupiedUnits.set(mapUnit);
        int atlasUnit = occupiedUnits.nextClearBit(0);
        if (mapUnit >= maxUnits || atlasUnit >= maxUnits)
            throw new IllegalStateException("Iris motion shader has no free texture units for history");
        uniforms.put(MotionShaderLayout.VERTEX_MAP,
                new Uniform.Utb(vertexMap, mapUnit, MotionShaderLayout.VERTEX_MAP_FORMAT));
        uniforms.put(MotionShaderLayout.PREVIOUS_CLIP_ATLAS, new Uniform.Sampler(previousAtlas, atlasUnit));
    }
}
