package com.kltyton.lumenfx.tools.shader;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Uses typed GL buffer views for read-only Niagara arrays in storage-heavy compute stages. */
final class OpenGlStorageTexels {
    private static final int COMPUTE_STORAGE_BUDGET = 16;
    private static final Set<String> BUFFER_KINDS = Set.of("niagara_skeletal_mesh_buffer",
            "niagara_data_channel_read", "niagara_data_channel_read_count", "niagara_data_channel_write",
            "niagara_physics_field_buffer");

    record View(int unit, String format) { }
    record Result(String source, Map<String, View> views) { }

    private record Texels(String sampler, String components, String format) {
        static Texels forType(String type) {
            return switch (type) {
                case "float" -> new Texels("samplerBuffer", ".x", "R32_FLOAT");
                case "vec2" -> new Texels("samplerBuffer", ".xy", "RG32_FLOAT");
                case "vec4" -> new Texels("samplerBuffer", "", "RGBA32_FLOAT");
                case "int" -> new Texels("isamplerBuffer", ".x", "R32_SINT");
                case "ivec2" -> new Texels("isamplerBuffer", ".xy", "RG32_SINT");
                case "ivec4" -> new Texels("isamplerBuffer", "", "RGBA32_SINT");
                case "uint" -> new Texels("usamplerBuffer", ".x", "R32_UINT");
                case "uvec2" -> new Texels("usamplerBuffer", ".xy", "RG32_UINT");
                case "uvec4" -> new Texels("usamplerBuffer", "", "RGBA32_UINT");
                default -> throw new BakeException("Unsupported OpenGL buffer-view element " + type);
            };
        }
    }

    private OpenGlStorageTexels() { }

    static Result lower(String source, ShaderManifest.Pass pass, BindingPlanner.Plan plan) {
        long buffers = plan.bindings().stream().filter(binding -> binding.kind().equals("storageBuffer")).count();
        if (buffers <= COMPUTE_STORAGE_BUDGET) return new Result(source, Map.of());
        Set<String> candidates = ShaderManifest.resources(pass).stream()
                .filter(resource -> BUFFER_KINDS.contains(resource.kind()))
                .map(ShaderManifest.Resource::name).collect(Collectors.toUnmodifiableSet());
        int unit = plan.sampledBindings().stream().mapToInt(BindingPlanner.SampledBinding::glBinding).max().orElse(-1) + 1;
        var views = new LinkedHashMap<String, View>();
        for (var binding : plan.bindings()) {
            if (buffers - views.size() <= COMPUTE_STORAGE_BUDGET) break;
            if (!binding.kind().equals("storageBuffer") || !candidates.contains(binding.sourceName())) continue;
            // SPIRV-Cross can name a block after its type. Its GL slot identifies the
            // actual variable, and readonly also covers member-level NonWritable decorations.
            var declaration = Pattern.compile("layout\\s*\\(\\s*binding\\s*=\\s*" + binding.glBinding()
                    + "\\s*,\\s*std430\\s*\\)\\s+readonly\\s+buffer\\s+\\w+"
                    + "\\s*\\{\\s*(float|int|uint|[iu]?vec[24])\\s+(\\w+)\\s*\\[\\s*]\\s*;\\s*}\\s*(\\w+)\\s*;")
                    .matcher(source);
            if (!declaration.find()) continue;
            var texels = Texels.forType(declaration.group(1));
            String array = declaration.group(3) + "." + declaration.group(2);
            String sampler = "LfxTexel_" + binding.name();
            String uniform = "layout(binding = " + unit + ") uniform " + texels.sampler() + " " + sampler + ";";
            source = source.substring(0, declaration.start()) + uniform + source.substring(declaration.end());
            source = rewriteReads(source, array, sampler, texels.components());
            views.put(binding.name(), new View(unit++, texels.format()));
        }
        return new Result(source, Map.copyOf(views));
    }

    private static String rewriteReads(String source, String array, String sampler, String components) {
        var access = Pattern.compile("\\b" + Pattern.quote(array) + "(?:\\.length\\(\\)|\\[)");
        StringBuilder out = new StringBuilder(source.length());
        var reads = access.matcher(source);
        int cursor = 0;
        while (reads.find(cursor)) {
            out.append(source, cursor, reads.start());
            if (source.charAt(reads.end() - 1) != '[') {
                out.append("textureSize(").append(sampler).append(')');
                cursor = reads.end();
                continue;
            }
            int end = arrayIndexEnd(source, reads.end());
            String index = rewriteReads(source.substring(reads.end(), end), array, sampler, components);
            out.append("texelFetch(").append(sampler).append(", int(").append(index).append("))").append(components);
            cursor = end + 1;
        }
        out.append(source, cursor, source.length());
        return out.toString();
    }

    private static int arrayIndexEnd(String source, int start) {
        int depth = 1;
        for (int i = start; i < source.length(); i++) {
            char next = source.charAt(i);
            if (next == '[') depth++;
            else if (next == ']' && --depth == 0) return i;
        }
        throw new BakeException("Unterminated index in translated OpenGL storage block");
    }
}
