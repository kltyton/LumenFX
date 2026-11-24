package com.kltyton.lumenfx.tools.shader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcCombinedImageSampler;
import org.lwjgl.util.spvc.Spv;

final class GlslTranslator {
    record Translation(String source, Map<String, String> renames) {
    }

    record Options(boolean flipVertexY, boolean fixupDepthConvention) {
        static final Options COMPUTE = new Options(false, false);
    }

    private GlslTranslator() {
    }

    static Translation translate(
            final byte[] spirv,
            final SpirvReflection.Reflection reflection,
            final BindingPlanner.Plan plan
    ) {
        return translate(spirv, reflection, plan, Options.COMPUTE);
    }

    static Translation translate(
            final byte[] spirv,
            final SpirvReflection.Reflection reflection,
            final BindingPlanner.Plan plan,
            final Options options
    ) {
        final IntBuffer words = MemoryUtil.memAllocInt(spirv.length / 4);
        try {
            final ByteBuffer bytes = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN);
            while (bytes.remaining() >= 4) {
                words.put(bytes.getInt());
            }
            words.flip();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                final org.lwjgl.PointerBuffer contextOut = stack.mallocPointer(1);
                check(Spvc.spvc_context_create(contextOut), "spvc_context_create");
                final long context = contextOut.get(0);
                try {
                    final org.lwjgl.PointerBuffer parsedOut = stack.mallocPointer(1);
                    check(Spvc.spvc_context_parse_spirv(context, words, words.remaining(), parsedOut),
                            "spvc_context_parse_spirv");
                    final org.lwjgl.PointerBuffer compilerOut = stack.mallocPointer(1);
                    check(Spvc.spvc_context_create_compiler(
                                    context,
                                    Spvc.SPVC_BACKEND_GLSL,
                                    parsedOut.get(0),
                                    Spvc.SPVC_CAPTURE_MODE_COPY,
                                    compilerOut
                            ),
                            "spvc_context_create_compiler");
                    final long compiler = compilerOut.get(0);
                    return compileGlsl(context, compiler, reflection, plan, options, stack);
                } finally {
                    Spvc.spvc_context_destroy(context);
                }
            }
        } finally {
            MemoryUtil.memFree(words);
        }
    }

    private static Translation compileGlsl(
            final long context,
            final long compiler,
            final SpirvReflection.Reflection reflection,
            final BindingPlanner.Plan plan,
            final Options glslOptions,
            final MemoryStack stack
    ) {
        setResourceBinding(compiler, reflection.uniformBuffers(), plan.glBindings());
        setResourceBinding(compiler, reflection.storageBuffers(), plan.glBindings());
        setResourceBinding(compiler, reflection.storageImages(), plan.glBindings());

        final IntBuffer dummyOut = stack.mallocInt(1);
        check(Spvc.spvc_compiler_build_dummy_sampler_for_combined_images(compiler, dummyOut),
                "spvc_compiler_build_dummy_sampler_for_combined_images");
        final int dummySamplerId = dummyOut.get(0);
        check(Spvc.spvc_compiler_build_combined_image_samplers(compiler),
                "spvc_compiler_build_combined_image_samplers");
        final List<SpvcCombinedImageSampler> combined = combinedSamplers(compiler, stack);

        final org.lwjgl.PointerBuffer optionsOut = stack.mallocPointer(1);
        check(Spvc.spvc_compiler_create_compiler_options(compiler, optionsOut),
                "spvc_compiler_create_compiler_options");
        final long options = optionsOut.get(0);
        check(Spvc.spvc_compiler_options_set_uint(options,
                Spvc.SPVC_COMPILER_OPTION_GLSL_VERSION, 430), "set GLSL version");
        check(Spvc.spvc_compiler_options_set_bool(options,
                Spvc.SPVC_COMPILER_OPTION_GLSL_ES, false), "set GLSL ES");
        check(Spvc.spvc_compiler_options_set_bool(options,
                Spvc.SPVC_COMPILER_OPTION_GLSL_VULKAN_SEMANTICS, false), "set GLSL Vulkan semantics");
        if (glslOptions.flipVertexY()) {
            // The scatter draw uses a zero base instance on both backends.
            check(Spvc.spvc_compiler_options_set_bool(options,
                    Spvc.SPVC_COMPILER_OPTION_GLSL_SUPPORT_NONZERO_BASE_INSTANCE, false),
                    "set GLSL base instance support");
        }
        check(Spvc.spvc_compiler_options_set_bool(options,
                Spvc.SPVC_COMPILER_OPTION_GLSL_EMIT_UNIFORM_BUFFER_AS_PLAIN_UNIFORMS, false),
                "set GLSL uniform blocks");
        check(Spvc.spvc_compiler_options_set_bool(options,
                Spvc.SPVC_COMPILER_OPTION_ENABLE_STORAGE_IMAGE_QUALIFIER_DEDUCTION, true),
                "set storage image qualifier deduction");
        check(Spvc.spvc_compiler_options_set_bool(options,
                Spvc.SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, glslOptions.flipVertexY),
                "set GLSL vertex Y flip");
        check(Spvc.spvc_compiler_options_set_bool(options,
                Spvc.SPVC_COMPILER_OPTION_FIXUP_DEPTH_CONVENTION, glslOptions.fixupDepthConvention),
                "set GLSL depth convention fixup");
        check(Spvc.spvc_compiler_install_compiler_options(compiler, options),
                "spvc_compiler_install_compiler_options");

        final Map<String, String> renames = new LinkedHashMap<>();
        final Set<Integer> imageOnlyBound = new HashSet<>();
        for (SpvcCombinedImageSampler sampler : combined) {
            BindingPlanner.SampledBinding binding = find(
                    plan, sampler.image_id(), sampler.sampler_id(), compiler
            );
            if (binding == null && dummySamplerId != 0 && sampler.sampler_id() == dummySamplerId) {
                binding = plan.imageOnlyBindings().get(sampler.image_id());
            }
            if (binding == null) {
                binding = plan.combinedBindings().get(sampler.combined_id());
            }
            if (binding == null) {
                continue;
            }
            Spvc.spvc_compiler_set_decoration(
                    compiler, sampler.combined_id(), Spv.SpvDecorationDescriptorSet, 0
            );
            Spvc.spvc_compiler_set_decoration(
                    compiler, sampler.combined_id(), Spv.SpvDecorationBinding, binding.glBinding()
            );
            Spvc.spvc_compiler_set_name(compiler, sampler.combined_id(), binding.name());
            if (binding.dummySampler()) {
                imageOnlyBound.add(binding.imageId());
            }
        }

        for (BindingPlanner.SampledBinding binding : plan.sampledBindings()) {
            if (!binding.dummySampler()) {
                continue;
            }
            if (imageOnlyBound.contains(binding.imageId())) {
                continue;
            }
            Spvc.spvc_compiler_set_decoration(
                    compiler, binding.imageId(), Spv.SpvDecorationDescriptorSet, 0
            );
            Spvc.spvc_compiler_set_decoration(
                    compiler, binding.imageId(), Spv.SpvDecorationBinding, binding.glBinding()
            );
            final String generated = rawName(compiler, binding.imageId());
            if (generated != null && !generated.equals(binding.name())) {
                renames.put(generated, binding.name());
            }
        }

        for (SpirvReflection.Resource resource : reflection.uniformBuffers()) {
            addBlockRename(compiler, resource, renames);
        }
        for (SpirvReflection.Resource resource : reflection.storageBuffers()) {
            addBlockRename(compiler, resource, renames);
        }

        final org.lwjgl.PointerBuffer sourceOut = stack.mallocPointer(1);
        final int result = Spvc.spvc_compiler_compile(compiler, sourceOut);
        if (result != 0) {
            throw new BakeException("SPIRV-Cross spvc_compiler_compile(GLSL) failed: "
                    + Spvc.spvc_context_get_last_error_string(context));
        }
        final String source = MemoryUtil.memUTF8(sourceOut.get(0));
        return new Translation(rename(source, renames), Map.copyOf(renames));
    }

    private static BindingPlanner.SampledBinding find(
            final BindingPlanner.Plan plan,
            final int imageId,
            final int samplerId,
            final long compiler
    ) {
        final String imageName = rawName(compiler, imageId);
        final String samplerName = rawName(compiler, samplerId);
        for (BindingPlanner.SampledBinding binding : plan.sampledBindings()) {
            final boolean imageMatches = binding.imageId() == imageId
                    || (imageName != null && imageName.equals(binding.imageName()));
            final boolean samplerMatches = binding.samplerId() == samplerId
                    || (samplerName != null && samplerName.equals(binding.samplerName()));
            if (imageMatches && samplerMatches) {
                return binding;
            }
        }
        return null;
    }

    private static void addBlockRename(
            final long compiler,
            final SpirvReflection.Resource resource,
            final Map<String, String> renames
    ) {
        final String canonical = canonicalBlockName(resource.name());
        final String generated = Spvc.spvc_compiler_get_remapped_declared_block_name(compiler, resource.id());
        if (generated != null && !generated.isBlank() && !generated.equals(canonical)) {
            renames.put(generated, canonical);
        }
    }

    private static String rename(final String source, final Map<String, String> renames) {
        String result = source;
        final List<Map.Entry<String, String>> ordered = new ArrayList<>(renames.entrySet());
        ordered.sort((left, right) -> Integer.compare(right.getKey().length(), left.getKey().length()));
        for (Map.Entry<String, String> entry : ordered) {
            final Pattern pattern = Pattern.compile(
                    "(?<![A-Za-z0-9_])" + Pattern.quote(entry.getKey()) + "(?![A-Za-z0-9_])"
            );
            result = pattern.matcher(result).replaceAll(java.util.regex.Matcher.quoteReplacement(entry.getValue()));
        }
        return result;
    }

    static String canonicalBlockName(final String name) {
        if (name == null) {
            return "Globals";
        }
        if (name.startsWith("$") || "_Globals".equals(name) || "type_Globals".equals(name)) {
            return "Globals";
        }
        return name;
    }

    private static void setResourceBinding(
            final long compiler,
            final List<SpirvReflection.Resource> resources,
            final Map<String, Integer> glBindings
    ) {
        for (SpirvReflection.Resource resource : resources) {
            final Integer binding = glBindings.get(resource.name());
            if (binding == null) {
                continue;
            }
            Spvc.spvc_compiler_set_decoration(compiler, resource.id(), Spv.SpvDecorationDescriptorSet, 0);
            Spvc.spvc_compiler_set_decoration(compiler, resource.id(), Spv.SpvDecorationBinding, binding);
        }
    }

    private static List<SpvcCombinedImageSampler> combinedSamplers(
            final long compiler,
            final MemoryStack stack
    ) {
        final org.lwjgl.PointerBuffer listOut = stack.mallocPointer(1);
        final org.lwjgl.PointerBuffer countOut = stack.mallocPointer(1);
        check(Spvc.spvc_compiler_get_combined_image_samplers(compiler, listOut, countOut),
                "spvc_compiler_get_combined_image_samplers");
        final long address = listOut.get(0);
        final int count = Math.toIntExact(countOut.get(0));
        if (count == 0 || address == MemoryUtil.NULL) {
            return List.of();
        }
        final SpvcCombinedImageSampler.Buffer entries = SpvcCombinedImageSampler.create(address, count);
        final List<SpvcCombinedImageSampler> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(entries.get(i));
        }
        return result;
    }

    private static String rawName(final long compiler, final int id) {
        final String name = Spvc.spvc_compiler_get_name(compiler, id);
        return name == null || name.isBlank() ? null : name;
    }

    private static void check(final int result, final String operation) {
        if (result != 0) {
            throw new BakeException("SPIRV-Cross " + operation + " failed with result " + result);
        }
    }
}
