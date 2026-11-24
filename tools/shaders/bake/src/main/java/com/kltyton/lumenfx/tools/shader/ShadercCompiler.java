package com.kltyton.lumenfx.tools.shader;

import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

final class ShadercCompiler {
    private enum Target {
        OPENGL,
        VULKAN
    }

    private ShadercCompiler() {
    }

    static String preprocessCompute(String source, String fileName) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0L) throw new BakeException("shaderc_compiler_initialize failed");
        long options = Shaderc.shaderc_compile_options_initialize();
        if (options == 0L) {
            Shaderc.shaderc_compiler_release(compiler);
            throw new BakeException("shaderc_compile_options_initialize failed");
        }
        long result = 0L;
        try {
            var bytes = MemoryUtil.memUTF8(source, false);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                result = Shaderc.shaderc_compile_into_preprocessed_text(compiler, bytes,
                        ShaderStage.COMPUTE.shadercKind(), stack.UTF8(fileName), stack.UTF8("main"), options);
            } finally { MemoryUtil.memFree(bytes); }
            if (result == 0L) throw new BakeException("shaderc returned a null preprocessor result for " + fileName);
            if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success)
                throw new BakeException("shaderc preprocessing failed for " + fileName + ": " + Shaderc.shaderc_result_get_error_message(result));
            ByteBuffer text = Shaderc.shaderc_result_get_bytes(result);
            if (text == null) throw new BakeException("shaderc returned no preprocessed source for " + fileName);
            return MemoryUtil.memUTF8(text);
        } finally {
            if (result != 0L) Shaderc.shaderc_result_release(result);
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    static byte[] compileOpenGl45(final String source, final String fileName) {
        return compileOpenGl45(source, fileName, ShaderStage.COMPUTE);
    }

    static byte[] compileOpenGl45(
            final String source,
            final String fileName,
            final ShaderStage stage
    ) {
        return compile(source, fileName, stage, Target.OPENGL);
    }

    static byte[] compileVulkan(final String source, final String fileName) {
        return compileVulkan(source, fileName, ShaderStage.COMPUTE);
    }

    static byte[] compileVulkan(
            final String source,
            final String fileName,
            final ShaderStage stage
    ) {
        return compile(source, fileName, stage, Target.VULKAN);
    }

    private static byte[] compile(
            final String source,
            final String fileName,
            final ShaderStage stage,
            final Target target
    ) {
        final long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0L) {
            throw new BakeException("shaderc_compiler_initialize failed");
        }
        final long options = Shaderc.shaderc_compile_options_initialize();
        if (options == 0L) {
            Shaderc.shaderc_compiler_release(compiler);
            throw new BakeException("shaderc_compile_options_initialize failed");
        }
        long result = 0L;
        try {
            if (target == Target.OPENGL) {
                Shaderc.shaderc_compile_options_set_target_env(
                        options, Shaderc.shaderc_target_env_opengl, Shaderc.shaderc_env_version_opengl_4_5
                );
            } else {
                Shaderc.shaderc_compile_options_set_target_env(
                        options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_0
                );
            }
            Shaderc.shaderc_compile_options_set_target_spirv(options, Shaderc.shaderc_spirv_version_1_0);
            Shaderc.shaderc_compile_options_set_source_language(options, Shaderc.shaderc_source_language_glsl);
            Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_zero);
            Shaderc.shaderc_compile_options_set_preserve_bindings(options, true);
            Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, false);
            Shaderc.shaderc_compile_options_set_auto_map_locations(options, false);
            Shaderc.shaderc_compile_options_set_auto_combined_image_sampler(options, false);

            final ByteBuffer sourceBytes = MemoryUtil.memUTF8(source, false);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                result = Shaderc.shaderc_compile_into_spv(
                        compiler, sourceBytes, stage.shadercKind(),
                        stack.UTF8(fileName), stack.UTF8("main"), options
                );
            } finally {
                MemoryUtil.memFree(sourceBytes);
            }
            if (result == 0L) {
                throw new BakeException("shaderc returned a null result for " + fileName);
            }
            final int status = Shaderc.shaderc_result_get_compilation_status(result);
            if (status != Shaderc.shaderc_compilation_status_success) {
                throw new BakeException(
                        "shaderc " + (target == Target.OPENGL ? "OpenGL 4.5" : "Vulkan 1.0")
                                + " compilation failed for " + fileName + ": "
                                + Shaderc.shaderc_result_get_error_message(result)
                );
            }
            final ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
            if (bytes == null) {
                throw new BakeException("shaderc returned no SPIR-V bytes for " + fileName);
            }
            final byte[] spirv = new byte[bytes.remaining()];
            bytes.get(spirv);
            DxcCompiler.validateSpirv(spirv, "shaderc output " + fileName);
            return spirv;
        } finally {
            if (result != 0L) {
                Shaderc.shaderc_result_release(result);
            }
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }
}
