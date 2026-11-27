package com.kltyton.lumenfx.runtime.gl;

import com.kltyton.lumenfx.runtime.model.NiagaraSystemModel.ComputeStageSpec;
import com.kltyton.lumenfx.runtime.model.NiagaraSystemModel.ShaderBindingSpec;
import com.kltyton.lumenfx.runtime.model.NiagaraSystemModel.UniformBlockSpec;
import com.kltyton.lumenfx.runtime.model.NiagaraSystemModel.UniformFieldSpec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import javax.annotation.Nullable;
import org.lwjgl.opengl.ARBGLSPIRV;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL41C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;

/**
 * Direct OpenGL 4.6 / {@code GL_ARB_gl_spirv} compute kernel executing baked LumenFX Niagara SPIR-V
 * shaders ({@code opengl.compute.spv}) with reflected UBO packing and SSBO/texel-buffer binding.
 */
public final class DirectGlComputeKernel implements AutoCloseable {
    private final ComputeStageSpec spec;
    private final int programId;
    private boolean closed;

    private DirectGlComputeKernel(ComputeStageSpec spec, int programId) {
        this.spec = spec;
        this.programId = programId;
    }

    public static boolean isSupported() {
        try {
            GLCapabilities caps = GL.getCapabilities();
            if (caps == null || !caps.OpenGL43) return false;
            return caps.OpenGL46 || caps.GL_ARB_gl_spirv;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static @Nullable DirectGlComputeKernel compile(ComputeStageSpec spec) {
        if (!isSupported() || spec.openglSpirv() == null || spec.openglSpirv().length < 20) return null;
        int shader = GL20C.glCreateShader(GL43C.GL_COMPUTE_SHADER);
        if (shader == 0) return null;
        ByteBuffer binary = MemoryUtil.memAlloc(spec.openglSpirv().length);
        try {
            binary.put(spec.openglSpirv()).flip();
            GLCapabilities caps = GL.getCapabilities();
            String entry = (spec.openglEntryPoint() == null || spec.openglEntryPoint().isBlank())
                    ? "main" : spec.openglEntryPoint();
            if (caps.OpenGL46) {
                GL41C.glShaderBinary(new int[]{shader}, GL46C.GL_SHADER_BINARY_FORMAT_SPIR_V, binary);
                GL46C.glSpecializeShader(shader, entry, new int[0], new int[0]);
            } else {
                GL41C.glShaderBinary(new int[]{shader}, ARBGLSPIRV.GL_SHADER_BINARY_FORMAT_SPIR_V_ARB, binary);
                ARBGLSPIRV.glSpecializeShaderARB(shader, entry, new int[0], new int[0]);
            }
            if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == 0) {
                return null;
            }
            int prog = GL20C.glCreateProgram();
            if (prog == 0) return null;
            GL20C.glAttachShader(prog, shader);
            GL20C.glLinkProgram(prog);
            if (GL20C.glGetProgrami(prog, GL20C.GL_LINK_STATUS) == 0) {
                GL20C.glDeleteProgram(prog);
                return null;
            }
            return new DirectGlComputeKernel(spec, prog);
        } catch (RuntimeException ignored) {
            return null;
        } finally {
            GL20C.glDeleteShader(shader);
            MemoryUtil.memFree(binary);
        }
    }

    public ComputeStageSpec spec() { return spec; }

    public void dispatch(int elementCount, Map<String, Integer> uboByName, Map<String, Integer> ssboByName,
                         Map<Integer, Integer> texelTextures) {
        if (closed || programId == 0 || elementCount <= 0) return;
        int prevProg = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        int prevActiveTex = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        try {
            GL20C.glUseProgram(programId);
            boolean usedTexel = false;
            for (ShaderBindingSpec b : spec.bindings()) {
                if ("uniformBuffer".equals(b.kind())) {
                    Integer ubo = uboByName.get(b.name());
                    if (ubo != null && ubo != 0) {
                        GL30C.glBindBufferBase(GL31C.GL_UNIFORM_BUFFER, b.glBinding(), ubo);
                    }
                } else if ("storageBuffer".equals(b.kind())) {
                    Integer ssbo = ssboByName.get(b.name());
                    if (ssbo == null || ssbo == 0) continue;
                    if (b.texelUnit() != null) {
                        usedTexel = true;
                        int unit = b.texelUnit();
                        int tex = texelTextures.computeIfAbsent(unit, u -> GL11C.glGenTextures());
                        int fmt = resolveTexelFormat(b.texelFormat());
                        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
                        GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, tex);
                        GL31C.glTexBuffer(GL31C.GL_TEXTURE_BUFFER, fmt, ssbo);
                    } else {
                        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, b.glBinding(), ssbo);
                    }
                }
            }
            int barrier = GL43C.GL_SHADER_STORAGE_BARRIER_BIT | GL42C.GL_UNIFORM_BARRIER_BIT
                    | (usedTexel ? GL42C.GL_TEXTURE_FETCH_BARRIER_BIT : 0);
            GL42C.glMemoryBarrier(barrier);

            int wgX = Math.max(1, spec.workgroupX());
            int groupsX = (elementCount + wgX - 1) / wgX;
            GL43C.glDispatchCompute(groupsX, 1, 1);

            GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT | GL42C.GL_BUFFER_UPDATE_BARRIER_BIT);
        } finally {
            GL13C.glActiveTexture(prevActiveTex);
            GL20C.glUseProgram(prevProg);
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
            GL15C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, 0);
        }
    }

    /** Packs reflected {@link UniformBlockSpec} fields into a tightly aligned little-endian byte buffer. */
    public static byte[] packUniformBlock(UniformBlockSpec block, Map<String, Object> values) {
        byte[] out = new byte[block.size()];
        ByteBuffer buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        for (UniformFieldSpec f : block.fields()) {
            Object v = values.get(f.name());
            if (v == null) continue;
            writeField(buf, f, v);
        }
        return out;
    }

    private static void writeField(ByteBuffer buf, UniformFieldSpec f, Object value) {
        int base = f.offset();
        int cap = buf.capacity();
        if (value instanceof Integer iv) {
            if (base + 4 <= cap) buf.putInt(base, iv);
        } else if (value instanceof Float fv) {
            if (base + 4 <= cap) buf.putFloat(base, fv);
        } else if (value instanceof int[] arr) {
            int stride = f.count() > 1 && f.rows() == 1 ? Math.max(4, f.arrayStride()) : 4;
            for (int i = 0; i < arr.length; i++) {
                int off = f.count() > 1 && f.rows() > 1
                        ? base + (i / f.rows()) * Math.max(f.rows() * 4, f.arrayStride()) + (i % f.rows()) * 4
                        : base + i * stride;
                if (off + 4 <= cap) buf.putInt(off, arr[i]);
            }
        } else if (value instanceof float[] arr) {
            if (f.count() > 1 && f.rows() > 1) {
                int elemStride = Math.max(f.rows() * 4, f.arrayStride());
                for (int i = 0; i < arr.length; i++) {
                    int elem = i / f.rows();
                    int lane = i % f.rows();
                    int off = base + elem * elemStride + lane * 4;
                    if (off + 4 <= cap) buf.putFloat(off, arr[i]);
                }
            } else if (f.columns() > 1) {
                int colStride = Math.max(f.rows() * 4, f.matrixStride());
                for (int c = 0; c < f.columns(); c++) {
                    for (int r = 0; r < f.rows(); r++) {
                        int idx = c * f.rows() + r;
                        if (idx >= arr.length) break;
                        int off = base + c * colStride + r * 4;
                        if (off + 4 <= cap) buf.putFloat(off, arr[idx]);
                    }
                }
            } else {
                int stride = f.count() > 1 && f.rows() == 1 ? Math.max(4, f.arrayStride()) : 4;
                for (int i = 0; i < arr.length; i++) {
                    int off = base + i * stride;
                    if (off + 4 <= cap) buf.putFloat(off, arr[i]);
                }
            }
        }
    }

    private static int resolveTexelFormat(@Nullable String fmt) {
        if (fmt == null) return GL30C.GL_R32F;
        return switch (fmt.toUpperCase(java.util.Locale.ROOT)) {
            case "R16F", "R16_FLOAT" -> GL30C.GL_R16F;
            case "R16I", "R16_SINT" -> GL30C.GL_R16I;
            case "R16UI", "R16_UINT" -> GL30C.GL_R16UI;
            case "R32I", "R32_SINT" -> GL30C.GL_R32I;
            case "R32UI", "R32_UINT" -> GL30C.GL_R32UI;
            case "RGBA32F" -> GL30C.GL_RGBA32F;
            case "RGBA32UI" -> GL30C.GL_RGBA32UI;
            default -> GL30C.GL_R32F;
        };
    }

    @Override
    public void close() {
        if (!closed && programId != 0) {
            GL20C.glDeleteProgram(programId);
            closed = true;
        }
    }
}
