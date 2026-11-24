package com.kltyton.lumenfx.client.render.compute;

import com.mojang.blaze3d.opengl.GlConst;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;

/**
 * Exact GL state touched by a compute dispatch.
 *
 * <p>The host caches the active program and 2D texture bindings outside GlStateManager. This class
 * restores the actual GL objects instead of invalidating those caches, so the host cache remains
 * truthful after the dispatch.</p>
 */
public final class GlComputeState {
    private final int previousProgram;
    private final int previousActiveTexture;
    private final int previousDispatchIndirectBuffer;
    private final int previousUniformBuffer;
    private final int previousStorageBuffer;
    private final TextureUnitState[] textureUnits;
    private final BufferState[] uniformBuffers;
    private final BufferState[] storageBuffers;
    private final ImageState[] images;
    private final boolean enabledCubeSeamless;
    private final List<MipRangeState> mipRanges = new ArrayList<>();
    private final List<Integer> temporaryTextures = new ArrayList<>();

    private GlComputeState(
            final int previousProgram,
            final int previousActiveTexture,
            final int previousDispatchIndirectBuffer,
            final int previousUniformBuffer,
            final int previousStorageBuffer,
            final TextureUnitState[] textureUnits,
            final BufferState[] uniformBuffers,
            final BufferState[] storageBuffers,
            final ImageState[] images,
            final boolean enabledCubeSeamless
    ) {
        this.previousProgram = previousProgram;
        this.previousActiveTexture = previousActiveTexture;
        this.previousDispatchIndirectBuffer = previousDispatchIndirectBuffer;
        this.previousUniformBuffer = previousUniformBuffer;
        this.previousStorageBuffer = previousStorageBuffer;
        this.textureUnits = textureUnits;
        this.uniformBuffers = uniformBuffers;
        this.storageBuffers = storageBuffers;
        this.images = images;
        this.enabledCubeSeamless = enabledCubeSeamless;
    }

    public static GlComputeState capture(final List<ShaderBinding> metadata) {
        final TreeSet<Integer> textureUnits = new TreeSet<>();
        final TreeSet<Integer> texelBufferUnits = new TreeSet<>();
        final TreeSet<Integer> uniformBuffers = new TreeSet<>();
        final TreeSet<Integer> storageBuffers = new TreeSet<>();
        final TreeSet<Integer> images = new TreeSet<>();
        boolean samplesCube = false;

        for (ShaderBinding binding : metadata) {
            switch (binding) {
                case ShaderBinding.SampledTexture texture -> {
                    textureUnits.add(texture.glBinding());
                    samplesCube |= texture.cube();
                }
                case ShaderBinding.UniformBuffer uniform -> uniformBuffers.add(uniform.glBinding());
                case ShaderBinding.StorageBuffer storage -> {
                    if (storage.openGlTexelView() == null) {
                        storageBuffers.add(storage.glBinding());
                    } else {
                        int unit = storage.openGlTexelView().unit();
                        textureUnits.add(unit);
                        texelBufferUnits.add(unit);
                    }
                }
                case ShaderBinding.StorageImage image -> images.add(image.glBinding());
            }
        }

        final int previousProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        final int previousActiveTexture = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        final int previousDispatchIndirectBuffer = GL11C.glGetInteger(GL43C.GL_DISPATCH_INDIRECT_BUFFER_BINDING);
        final int previousUniformBuffer = uniformBuffers.isEmpty() ? 0 : GL11C.glGetInteger(GL31C.GL_UNIFORM_BUFFER_BINDING);
        final int previousStorageBuffer = storageBuffers.isEmpty() ? 0 : GL11C.glGetInteger(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING);
        final TextureUnitState[] textureStates = new TextureUnitState[textureUnits.size()];
        try {
            int i = 0;
            for (int unit : textureUnits) {
                GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
                textureStates[i++] = new TextureUnitState(
                        unit,
                        GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D),
                        GL11C.glGetInteger(GL30C.GL_TEXTURE_BINDING_2D_ARRAY),
                        GL11C.glGetInteger(GL12C.GL_TEXTURE_BINDING_3D),
                        GL11C.glGetInteger(GL13C.GL_TEXTURE_BINDING_CUBE_MAP),
                        GL11C.glGetInteger(GL40C.GL_TEXTURE_BINDING_CUBE_MAP_ARRAY),
                        texelBufferUnits.contains(unit) ? GL11C.glGetInteger(GL31C.GL_TEXTURE_BINDING_BUFFER) : -1,
                        GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING)
                );
            }
        } finally {
            GL13C.glActiveTexture(previousActiveTexture);
        }

        final BufferState[] uniformStates = new BufferState[uniformBuffers.size()];
        int u = 0;
        for (int index : uniformBuffers) {
            uniformStates[u++] = new BufferState(
                    index,
                    GL30C.glGetIntegeri(GL31C.GL_UNIFORM_BUFFER_BINDING, index),
                    getInteger64i(GL31C.GL_UNIFORM_BUFFER_START, index),
                    getInteger64i(GL31C.GL_UNIFORM_BUFFER_SIZE, index)
            );
        }

        final BufferState[] storageStates = new BufferState[storageBuffers.size()];
        int s = 0;
        for (int index : storageBuffers) {
            storageStates[s++] = new BufferState(
                    index,
                    GL30C.glGetIntegeri(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING, index),
                    getInteger64i(GL43C.GL_SHADER_STORAGE_BUFFER_START, index),
                    getInteger64i(GL43C.GL_SHADER_STORAGE_BUFFER_SIZE, index)
            );
        }

        final ImageState[] imageStates = new ImageState[images.size()];
        int m = 0;
        for (int index : images) {
            imageStates[m++] = new ImageState(
                    index,
                    GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_NAME, index),
                    GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_LEVEL, index),
                    GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_LAYERED, index),
                    GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_LAYER, index),
                    GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_ACCESS, index),
                    GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_FORMAT, index)
            );
        }

        boolean enableCubeSeamless = samplesCube && !GL11C.glIsEnabled(GL32C.GL_TEXTURE_CUBE_MAP_SEAMLESS);
        var state = new GlComputeState(
                previousProgram,
                previousActiveTexture,
                previousDispatchIndirectBuffer,
                previousUniformBuffer,
                previousStorageBuffer,
                textureStates,
                uniformStates,
                storageStates,
                imageStates,
                enableCubeSeamless
        );
        if (enableCubeSeamless) GL11C.glEnable(GL32C.GL_TEXTURE_CUBE_MAP_SEAMLESS);
        return state;
    }

    void recordMipRange(final int target, final int textureId, final int baseLevel, final int maxLevel) {
        for (MipRangeState existing : this.mipRanges) {
            if (existing.textureId == textureId && existing.target == target) {
                return;
            }
        }
        this.mipRanges.add(new MipRangeState(target, textureId, baseLevel, maxLevel));
    }

    void releaseTextureAfterDispatch(int textureId) {
        temporaryTextures.add(textureId);
    }

    void releaseTemporaryTextures() {
        for (int texture : temporaryTextures) GL11C.glDeleteTextures(texture);
        temporaryTextures.clear();
    }

    public void restore() {
        for (int i = this.mipRanges.size() - 1; i >= 0; i--) {
            MipRangeState mip = this.mipRanges.get(i);
            GL11C.glBindTexture(mip.target, mip.textureId);
            GL11C.glTexParameteri(mip.target, GL12C.GL_TEXTURE_BASE_LEVEL, mip.baseLevel);
            GL11C.glTexParameteri(mip.target, GL12C.GL_TEXTURE_MAX_LEVEL, mip.maxLevel);
        }
        if (enabledCubeSeamless) GL11C.glDisable(GL32C.GL_TEXTURE_CUBE_MAP_SEAMLESS);
        for (TextureUnitState state : this.textureUnits) {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + state.unit);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, state.texture2d);
            GL11C.glBindTexture(GL30C.GL_TEXTURE_2D_ARRAY, state.texture2dArray);
            GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, state.texture3d);
            GL11C.glBindTexture(GL13C.GL_TEXTURE_CUBE_MAP, state.textureCube);
            GL11C.glBindTexture(GL40C.GL_TEXTURE_CUBE_MAP_ARRAY, state.textureCubeArray);
            if (state.textureBuffer != -1) GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, state.textureBuffer);
            GL33C.glBindSampler(state.unit, state.sampler);
        }
        releaseTemporaryTextures();

        for (BufferState state : this.uniformBuffers) {
            restoreBuffer(GL31C.GL_UNIFORM_BUFFER, state);
        }
        for (BufferState state : this.storageBuffers) {
            restoreBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, state);
        }
        if (this.uniformBuffers.length != 0) GL15C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, this.previousUniformBuffer);
        if (this.storageBuffers.length != 0) GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, this.previousStorageBuffer);
        for (ImageState state : this.images) {
            if (state.name == 0) {
                GL42C.glBindImageTexture(
                        state.unit,
                        0,
                        0,
                        false,
                        0,
                        GlConst.GL_READ_ONLY,
                        GlConst.GL_R8
                );
            } else {
                GL42C.glBindImageTexture(
                        state.unit,
                        state.name,
                        state.level,
                        state.layered != 0,
                        state.layer,
                        state.access,
                        state.format
                );
            }
        }

        GL15C.glBindBuffer(GL43C.GL_DISPATCH_INDIRECT_BUFFER, this.previousDispatchIndirectBuffer);
        GL20C.glUseProgram(this.previousProgram);
        GL13C.glActiveTexture(this.previousActiveTexture);
    }

    private static void restoreBuffer(final int target, final BufferState state) {
        if (state.buffer == 0) {
            GL30C.glBindBufferBase(target, state.index, 0);
        } else if (state.size > 0L) {
            GL30C.glBindBufferRange(target, state.index, state.buffer, state.offset, state.size);
        } else {
            GL30C.glBindBufferBase(target, state.index, state.buffer);
        }
    }

    private static long getInteger64i(final int name, final int index) {
        final long[] value = new long[1];
        GL32C.glGetInteger64i_v(name, index, value);
        return value[0];
    }

    private record TextureUnitState(
            int unit,
            int texture2d,
            int texture2dArray,
            int texture3d,
            int textureCube,
            int textureCubeArray,
            int textureBuffer,
            int sampler
    ) {
    }

    private record BufferState(int index, int buffer, long offset, long size) {
    }

    private record ImageState(
            int unit,
            int name,
            int level,
            int layered,
            int layer,
            int access,
            int format
    ) {
    }

    private record MipRangeState(int target, int textureId, int baseLevel, int maxLevel) {
    }
}
