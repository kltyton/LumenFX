package com.kltyton.lumenfx.client.render.compute;

import com.kltyton.lumenfx.client.render.texture.layer.TextureLayerView;
import com.kltyton.lumenfx.client.resource.texture.storage.SampledTextureStorage;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlSampler;
import com.mojang.blaze3d.opengl.GlTextureView;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.logging.LogUtils;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.List;
import org.lwjgl.opengl.ARBGLSPIRV;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.GL41C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/** GL compute program, exact state restoration and explicit dispatch barrier. */
final class GlComputeKernel extends ComputeKernel {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final int programId;
    private final GlComputeTexelViews texelViews;
    private final int preDispatchBarriers;
    private boolean closed;

    GlComputeKernel(final GpuDevice device, final GlDevice gl, final ShaderProgram program) {
        super(program);
        RenderSystem.assertOnRenderThread();
        verifyCapabilities(program);
        this.texelViews = program.bindings().stream().anyMatch(binding ->
                binding instanceof ShaderBinding.StorageBuffer storage && storage.openGlTexelView() != null)
                ? new GlComputeTexelViews() : null;
        this.preDispatchBarriers = GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                | GL43C.GL_SHADER_STORAGE_BARRIER_BIT | GL42C.GL_COMMAND_BARRIER_BIT
                | (texelViews == null ? 0 : GL42C.GL_TEXTURE_FETCH_BARRIER_BIT);
        this.programId = compile(program.openglSpirvBytes(), program.openglEntryPoint(), program.name());
    }

    @Override
    public void dispatch(final ComputeBindings bindings, final int width, final int height, final int depth) {
        if (this.closed) {
            throw new IllegalStateException("Compute kernel is closed: " + this.program().name());
        }
        Objects.requireNonNull(bindings, "bindings");
        final int groupsX = groups(width, this.program().workgroupX());
        final int groupsY = groups(height, this.program().workgroupY());
        final int groupsZ = groups(depth, this.program().workgroupZ());

        dispatchCommand(bindings, groupsX, groupsY, groupsZ, null);
    }

    @Override
    protected void dispatchIndirectInternal(final ComputeBindings bindings, final GpuBufferSlice args) {
        if (this.closed) throw new IllegalStateException("Compute kernel is closed: " + this.program().name());
        Objects.requireNonNull(bindings, "bindings");
        dispatchCommand(bindings, 0, 0, 0, args);
    }

    @Override public void dispatchIndependentBatch(List<Dispatch> requests) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Compute kernel is closed: " + program().name());
        if (requests.isEmpty()) return;
        GlComputeState state = GlComputeState.capture(program().bindings());
        int bufferBarriers = 0;
        boolean dispatched = false;
        try {
            GL42C.glMemoryBarrier(preDispatchBarriers);
            GL20C.glUseProgram(programId);
            for (Dispatch request : requests) {
                bindResources(state, request.bindings());
                GL43C.glDispatchCompute(groups(request.width(), program().workgroupX()),
                        groups(request.height(), program().workgroupY()), groups(request.depth(), program().workgroupZ()));
                dispatched = true;
                bufferBarriers |= writtenBufferBarriers(request.bindings());
                state.releaseTemporaryTextures();
            }
        } finally {
            try { if (dispatched) shaderWriteBarrier(bufferBarriers); }
            finally { restore(state); }
        }
    }

    private void dispatchCommand(ComputeBindings bindings, int groupsX, int groupsY, int groupsZ, GpuBufferSlice indirect) {
        final GlComputeState state = GlComputeState.capture(this.program().bindings());
        try {
            GL42C.glMemoryBarrier(preDispatchBarriers);
            GL20C.glUseProgram(this.programId);
            bindResources(state, bindings);

            if (indirect == null) GL43C.glDispatchCompute(groupsX, groupsY, groupsZ);
            else {
                GL15C.glBindBuffer(GL43C.GL_DISPATCH_INDIRECT_BUFFER, ((GlBuffer)indirect.buffer()).handle());
                GL43C.glDispatchComputeIndirect(indirect.offset());
            }
            shaderWriteBarrier(writtenBufferBarriers(bindings));
        } finally {
            restore(state);
        }
    }

    private void restore(GlComputeState state) {
        try {
            // Do not retain another owner's storage allocation between dispatches.
            if (texelViews != null) texelViews.detach();
        } finally {
            state.restore();
        }
    }

    private void bindResources(GlComputeState state, ComputeBindings bindings) {
        for (ShaderBinding binding : program().bindings()) switch (binding) {
            case ShaderBinding.UniformBuffer uniform -> bindUniform(bindings, uniform);
            case ShaderBinding.SampledTexture texture -> bindTexture(state, bindings, texture);
            case ShaderBinding.StorageImage image -> bindImage(bindings, image);
            case ShaderBinding.StorageBuffer storage -> bindStorage(bindings, storage);
        }
    }
    private static int writtenBufferBarriers(ComputeBindings bindings) {
        int barriers = 0;
        for (ComputeBindings.Storage storage : bindings.storageBindings().values()) {
            if (storage.access() == ComputeAccess.READ_ONLY) continue;
            int usage = storage.slice().buffer().usage();
            if ((usage & GpuBuffer.USAGE_VERTEX) != 0) barriers |= GL42C.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT;
            if ((usage & GpuBuffer.USAGE_INDEX) != 0) barriers |= GL42C.GL_ELEMENT_ARRAY_BARRIER_BIT;
            if ((usage & GpuBuffer.USAGE_COPY_SRC) != 0) barriers |= GL42C.GL_BUFFER_UPDATE_BARRIER_BIT;
        }
        return barriers;
    }
    private static void shaderWriteBarrier(int bufferBarriers) {
        GL42C.glMemoryBarrier(bufferBarriers | GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42C.GL_TEXTURE_FETCH_BARRIER_BIT
                | GL43C.GL_SHADER_STORAGE_BARRIER_BIT | GL42C.GL_UNIFORM_BARRIER_BIT | GL42C.GL_COMMAND_BARRIER_BIT);
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        RenderSystem.assertOnRenderThread();
        this.closed = true;
        RenderSystem.queueFencedTask(() -> {
            if (this.texelViews != null) this.texelViews.delete();
            GL20C.glDeleteProgram(this.programId);
        });
    }

    private static void bindUniform(final ComputeBindings bindings, final ShaderBinding.UniformBuffer binding) {
        final ComputeBindings.Uniform value = require(bindings.uniform(binding.name()), binding.name());
        final GpuBufferSlice slice = value.slice();
        if (slice.length() <= 0L) {
            throw new IllegalArgumentException("Empty uniform buffer slice for " + binding.name());
        }
        if (slice.buffer().isClosed()) {
            throw new IllegalStateException("Closed uniform buffer for " + binding.name());
        }
        if ((slice.buffer().usage() & GpuBuffer.USAGE_UNIFORM) == 0) {
            throw new IllegalArgumentException("Buffer lacks USAGE_UNIFORM for " + binding.name());
        }
        final GlBuffer buffer = (GlBuffer) slice.buffer();
        GL30C.glBindBufferRange(
                GL31C.GL_UNIFORM_BUFFER,
                binding.glBinding(),
                buffer.handle(),
                slice.offset(),
                slice.length()
        );
    }

    private static void bindTexture(final GlComputeState state, final ComputeBindings bindings, final ShaderBinding.SampledTexture binding) {
        final ComputeBindings.Sampled value = require(bindings.sampled(binding.name()), binding.name());
        if (value instanceof ComputeBindings.VolumeTexture volume) {
            if (!binding.volume() || volume.volume().isClosed() || !volume.volume().accepts(binding))
                throw new IllegalArgumentException("Invalid sampled volume for " + binding.name());
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + binding.glBinding());
            GL11C.glBindTexture(org.lwjgl.opengl.GL12C.GL_TEXTURE_3D, volume.volume().glTexture());
            int sampler = binding.form() == ShaderBinding.DescriptorForm.SAMPLED_IMAGE ? 0
                    : volume.sampler() == null ? volume.volume().glSampler(volume.tilingAxes()) : ((GlSampler) volume.sampler()).getId();
            GL33C.glBindSampler(binding.glBinding(), sampler);
            return;
        }
        final GpuTextureView hostView;
        final ComputeTexture nativeTexture;
        final ComputeTexture.SampledView nativeView;
        final GpuSampler sampler;
        switch (value) {
            case ComputeBindings.Texture host -> {
                hostView = host.view();
                nativeTexture = null;
                nativeView = null;
                sampler = host.sampler();
            }
            case ComputeBindings.SampledImage host -> {
                hostView = host.view();
                nativeTexture = null;
                nativeView = null;
                sampler = null;
            }
            case ComputeBindings.NativeTexture nativeValue -> {
                hostView = null;
                nativeTexture = nativeValue.texture();
                if (nativeValue.arrayView() != binding.array()) {
                    throw new IllegalArgumentException(
                            "Sampled view dimensionality mismatch for " + binding.name()
                                    + ": shader expects array=" + binding.array()
                                    + ", view array=" + nativeValue.arrayView()
                    );
                }
                nativeView = nativeValue.sampledView();
                sampler = nativeValue.sampler();
            }
            case ComputeBindings.NativeSampledImage nativeValue -> {
                hostView = null;
                nativeTexture = nativeValue.texture();
                if (nativeValue.arrayView() != binding.array()) {
                    throw new IllegalArgumentException(
                            "Sampled view dimensionality mismatch for " + binding.name()
                                    + ": shader expects array=" + binding.array()
                                    + ", view array=" + nativeValue.arrayView()
                    );
                }
                nativeView = nativeValue.sampledView();
                sampler = null;
            }
            case ComputeBindings.VolumeTexture ignored -> throw new IllegalStateException("Volume handled before ordinary texture binding");
            default -> throw new IllegalStateException("Unhandled sampled binding " + value);
        }

        final GpuFormat format;
        final int target;
        final int textureId;
        if (hostView != null) {
            if (hostView.isClosed()) {
                throw new IllegalStateException("Closed texture view for " + binding.name());
            }
            final GpuTexture texture = hostView.texture();
            TextureLayerView layers = hostView instanceof TextureLayerView view ? view : null;
            boolean singleLayer = layers != null && layers.selectedImageLayer() >= 0 && !layers.samplesImageArray();
            final boolean resourceVolume = !singleLayer && texture instanceof VolumeGpuTexture;
            final boolean cube = !singleLayer && (texture.usage() & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0;
            final boolean cubeArray = cube && SampledTextureStorage.isCubeArray(texture);
            final boolean resourceArray = !singleLayer && (cubeArray || !resourceVolume && !cube
                    && (texture.getDepthOrLayers() > 1 || layers != null && layers.samplesImageArray()));
            if (binding.volume() != resourceVolume || binding.array() != resourceArray || binding.cube() != cube) {
                throw new IllegalArgumentException(
                        "Texture dimensionality mismatch for " + binding.name()
                                + ": shader expects array=" + binding.array() + ", cube=" + binding.cube()
                                + "; use a compute-native binding for a one-layer 2D array"
                );
            }
            format = null;
            target = resourceVolume ? GL12C.GL_TEXTURE_3D : cubeArray ? GL40C.GL_TEXTURE_CUBE_MAP_ARRAY : cube
                    ? GL13C.GL_TEXTURE_CUBE_MAP
                    : resourceArray ? GL30C.GL_TEXTURE_2D_ARRAY : GL11C.GL_TEXTURE_2D;
            textureId = ((GlTextureView) hostView).glId();
        } else {
            if (binding.volume()) throw new IllegalArgumentException("Expected volume texture for " + binding.name());
            if (nativeTexture == null || nativeView == null || nativeTexture.isClosed()) {
                throw new IllegalStateException("Closed compute texture for " + binding.name());
            }
            format = nativeTexture.format();
            target = nativeView.glTextureTarget();
            textureId = nativeView.glTextureId();
        }

        if (!com.kltyton.lumenfx.client.render.compute.format.SampledTextureFormats.accepts(binding,
                hostView != null ? hostView.texture() : nativeTexture.texture())) {
            throw new IllegalArgumentException(
                    "Texture format mismatch for " + binding.name() + ": shader expects " + binding.format()
                            + ", resource is " + (hostView == null ? format : com.kltyton.lumenfx.client.render.compute.format.SampledTextureFormats.describe(hostView.texture()))
            );
        }
        if (binding.form() != ShaderBinding.DescriptorForm.SAMPLED_IMAGE && sampler == null) {
            throw new IllegalArgumentException("Missing sampler for " + binding.name());
        }

        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + binding.glBinding());
        if (hostView == null) {
            GL11C.glBindTexture(target, textureId);
        } else {
            GlSampledMipView.bind(state, (GlTextureView) hostView, target);
        }
        GL33C.glBindSampler(binding.glBinding(), sampler == null ? 0 : ((GlSampler) sampler).getId());
    }

    private static void bindImage(final ComputeBindings bindings, final ShaderBinding.StorageImage binding) {
        if (binding.volume()) {
            var value = require(bindings.volumeImage(binding.name()), binding.name());
            var volume = value.volume();
            if (volume.isClosed() || !binding.accepts(volume.format()) || value.access() != binding.access())
                throw new IllegalArgumentException("Invalid storage volume for " + binding.name());
            GL42C.glBindImageTexture(binding.glBinding(), volume.glStorageTexture(), 0, true, 0,
                    glAccess(value.access()), volume.glStorageFormat());
            return;
        }
        var cube = bindings.cubeImage(binding.name());
        if (cube != null) {
            if (!binding.array() || !cube.target().writable() || !binding.accepts(cube.target().format())
                    || cube.access() != binding.access())
                throw new IllegalArgumentException("Invalid storage cube array for " + binding.name());
            GL42C.glBindImageTexture(binding.glBinding(), cube.target().glStorageTexture(), 0, true, 0,
                    glAccess(cube.access()), cube.target().glStorageFormat());
            return;
        }
        final ComputeBindings.Image value = require(bindings.image(binding.name()), binding.name());
        final ComputeTexture texture = value.texture();
        if (texture.isClosed()) {
            throw new IllegalStateException("Closed storage image for " + binding.name());
        }
        if (!binding.accepts(texture.format())) {
            throw new IllegalArgumentException(
                    "Storage image format mismatch for " + binding.name() + ": shader expects "
                            + binding.format() + ", resource is " + texture.format()
            );
        }
        if (value.access() != binding.access()) {
            throw new IllegalArgumentException(
                    "Storage image access mismatch for " + binding.name() + ": shader expects "
                            + binding.access() + ", resource declares " + value.access()
            );
        }
        if (texture.isArray() != binding.array()) {
            throw new IllegalArgumentException(
                    "Storage image dimensionality mismatch for " + binding.name()
                            + ": shader expects array=" + binding.array()
                            + ", resource array=" + texture.isArray()
            );
        }

        final int mipLevel = value.mipLevel();
        final int baseLayer = value.baseLayer();
        final int layerCount = value.layerCount();
        if (mipLevel < 0 || mipLevel >= texture.mips()) {
            throw new IllegalArgumentException(
                    "Storage mip " + mipLevel + " is outside [0," + texture.mips() + ") for " + binding.name()
            );
        }
        if (baseLayer < 0 || layerCount < 1 || baseLayer + layerCount > texture.layers()) {
            throw new IllegalArgumentException(
                    "Storage layers [" + baseLayer + "," + (baseLayer + layerCount)
                            + ") are outside [0," + texture.layers() + ") for " + binding.name()
            );
        }

        if (!texture.isArray()) {
            if (baseLayer != 0 || layerCount != 1) {
                throw new IllegalArgumentException(
                        "2D storage binding must use layer 0 and layerCount 1 for " + binding.name()
                );
            }
            GL42C.glBindImageTexture(
                    binding.glBinding(),
                    texture.glTextureId(),
                    mipLevel,
                    false,
                    0,
                    glAccess(value.access()),
                    texture.glInternalFormat()
            );
            return;
        }

        // Every array UAV is an array image, including one-layer arrays and partial ranges.
        // glTextureView supplies the exact mip/layer window and glBindImageTexture uses level 0.
        final ComputeTexture.SampledView view = texture.cachedSampledView(
                mipLevel,
                baseLayer,
                layerCount,
                true
        );
        GL42C.glBindImageTexture(
                binding.glBinding(),
                view.glTextureId(),
                0,
                true,
                0,
                glAccess(value.access()),
                texture.glInternalFormat()
        );
    }

    private void bindStorage(final ComputeBindings bindings, final ShaderBinding.StorageBuffer binding) {
        final ComputeBindings.Storage value = require(bindings.storage(binding.name()), binding.name());
        if (value.access() != binding.access()) {
            throw new IllegalArgumentException(
                    "Storage buffer access mismatch for " + binding.name() + ": shader expects "
                            + binding.access() + ", resource declares " + value.access()
            );
        }
        final GpuBufferSlice slice = value.slice();
        if (slice.length() <= 0L) {
            throw new IllegalArgumentException("Empty storage buffer slice for " + binding.name());
        }
        if (slice.buffer().isClosed()) {
            throw new IllegalStateException("Closed storage buffer for " + binding.name());
        }
        if ((slice.buffer().usage() & ComputeUsage.STORAGE_BUFFER) == 0) {
            throw new IllegalArgumentException("Buffer lacks the compute storage marker for " + binding.name());
        }
        final GlBuffer buffer = (GlBuffer) slice.buffer();
        if (binding.openGlTexelView() != null) {
            this.texelViews.bind(binding, slice, buffer);
            return;
        }
        GL30C.glBindBufferRange(
                GL43C.GL_SHADER_STORAGE_BUFFER,
                binding.glBinding(),
                buffer.handle(),
                slice.offset(),
                slice.length()
        );
    }

    private static int glAccess(final ComputeAccess access) {
        return switch (access) {
            case READ_ONLY -> GlConst.GL_READ_ONLY;
            case WRITE_ONLY -> GlConst.GL_WRITE_ONLY;
            case READ_WRITE -> GlConst.GL_READ_WRITE;
        };
    }

    private static <T> T require(final T value, final String name) {
        if (value == null) {
            throw new IllegalArgumentException("Missing compute binding " + name);
        }
        return value;
    }

    private static boolean supportsSpirv(GLCapabilities capabilities) {
        return capabilities.GL_ARB_gl_spirv || capabilities.OpenGL46;
    }

    private static boolean supportsComputeShaders(GLCapabilities capabilities) {
        return capabilities.OpenGL43 || capabilities.GL_ARB_compute_shader;
    }

    static boolean supportsStorageBufferKernels() {
        GLCapabilities capabilities = GL.getCapabilities();
        return supportsSpirv(capabilities) && supportsComputeShaders(capabilities)
                && capabilities.GL_ARB_shader_storage_buffer_object;
    }

    private static void verifyCapabilities(final ShaderProgram program) {
        final GLCapabilities capabilities = GL.getCapabilities();
        if (!supportsSpirv(capabilities)) {
            throw new UnsupportedOperationException("GL_ARB_gl_spirv/OpenGL 4.6 is required for compute SPIR-V");
        }
        if (!supportsComputeShaders(capabilities)) {
            throw new UnsupportedOperationException("OpenGL 4.3 or GL_ARB_compute_shader is required");
        }
        if (program.capabilities().nativeFp16()) {
            throw new UnsupportedOperationException(
                    "Native FP16 permutation is not wired: this runtime does not enable GL FP16 arithmetic; "
                            + "use the distinct FP32 LDS permutation"
            );
        }
        if (program.capabilities().waveOperations() && !capabilities.GL_KHR_shader_subgroup) {
            throw new UnsupportedOperationException(
                    "Shader requires subgroup operations but GL_KHR_shader_subgroup is absent "
                            + "(OpenGL 4.6 alone does not guarantee the extension)"
            );
        }
        if (program.hasStorageImages() && !capabilities.GL_ARB_shader_image_load_store) {
            throw new UnsupportedOperationException("GL_ARB_shader_image_load_store is required");
        }
        if ((program.hasStorageBuffers() || program.capabilities().storageBuffers())
                && !capabilities.GL_ARB_shader_storage_buffer_object) {
            throw new UnsupportedOperationException("GL_ARB_shader_storage_buffer_object is required");
        }
        if (program.hasStorageBuffers()) {
            long blocks = program.bindings().stream().filter(binding ->
                    binding instanceof ShaderBinding.StorageBuffer storage && storage.openGlTexelView() == null).count();
            int limit = GL11C.glGetInteger(GL43C.GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS);
            if (blocks > limit) {
                throw new UnsupportedOperationException(program.name() + " needs " + blocks
                        + " GL compute storage blocks, device limit is " + limit);
            }
        }
    }
    private static int compile(final byte[] spirv, final String entryPoint, final String name) {
        final int shader = GL20C.glCreateShader(GL43C.GL_COMPUTE_SHADER);
        if (shader == 0) {
            throw new IllegalStateException("Could not create GL compute shader for " + name);
        }

        final ByteBuffer binary = MemoryUtil.memAlloc(spirv.length);
        try {
            binary.put(spirv).flip();
            final GLCapabilities capabilities = GL.getCapabilities();
            if (capabilities.OpenGL46) {
                GL41C.glShaderBinary(
                        new int[]{shader},
                        GL46C.GL_SHADER_BINARY_FORMAT_SPIR_V,
                        binary
                );
                GL46C.glSpecializeShader(shader, entryPoint, new int[0], new int[0]);
            } else {
                GL41C.glShaderBinary(
                        new int[]{shader},
                        ARBGLSPIRV.GL_SHADER_BINARY_FORMAT_SPIR_V_ARB,
                        binary
                );
                ARBGLSPIRV.glSpecializeShaderARB(shader, entryPoint, new int[0], new int[0]);
            }
            if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == 0) {
                final String log = GL20C.glGetShaderInfoLog(shader, 32768);
                LOGGER.error("Could not specialize GL compute shader {}: {}", name, log);
                throw new IllegalStateException("Could not specialize GL compute shader " + name + ": " + log);
            }

            final int program = GL20C.glCreateProgram();
            if (program == 0) {
                throw new IllegalStateException("Could not create GL compute program for " + name);
            }
            GL20C.glAttachShader(program, shader);
            GL20C.glLinkProgram(program);
            if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == 0) {
                final String log = GL20C.glGetProgramInfoLog(program, 32768);
                GL20C.glDeleteProgram(program);
                LOGGER.error("Could not link GL compute program {}: {}", name, log);
                throw new IllegalStateException("Could not link GL compute program " + name + ": " + log);
            }
            return program;
        } finally {
            GL20C.glDeleteShader(shader);
            MemoryUtil.memFree(binary);
        }
    }

}
