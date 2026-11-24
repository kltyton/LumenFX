package com.kltyton.lumenfx.tools.shader;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lwjgl.util.spvc.Spv;

/** Bakes authored volume materials into backend-specific compute programs. */
public final class VolumeMaterialBake {
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*#moj_import\\s+<([a-z0-9_]+):([a-z0-9_/-]+\\.glsl)>\\s*$");
    private static final Pattern GLSL_VERSION = Pattern.compile("(?m)^\\h*#\\h*version\\h+(\\d+)\\b[^\\r\\n]*");
    private static final Pattern DECLARATION = Pattern.compile(
            "(?m)^\\h*(layout\\h*\\(\\h*(std140|r16f|rgba16f|rgba32f|r11f_g11f_b10f)\\h*\\)\\s+)?uniform\\s+"
                    + "(?:(writeonly)\\s+)?([A-Za-z][A-Za-z0-9_]*)(?:\\s+([A-Za-z][A-Za-z0-9_]*)\\s*;|\\s*(?=\\{))");
    private static final String SUFFIX = ".volume.json";

    private record Descriptor(String type, String name, int binding, int glBinding) { }
    private record Stage(String source, Map<String, Descriptor> descriptors) { }
    private record Variant(String key, String... flags) { }
    private record Output(Set<String> images, String format, int spirvFormat, boolean volume, boolean readWrite) { }
    private static final Output RADIANCE = new Output(Set.of("VolumeResult"), "RGBA16_FLOAT", Spv.SpvImageFormatRgba16f, false, false);
    private static final Output LIGHTING = new Output(Set.of("VolumeCacheOutput"), "RG11B10_FLOAT", Spv.SpvImageFormatR11fG11fB10f, true, true);
    private static final Set<String> COEFFICIENTS = Set.of("VolumeBakeExtinction", "VolumeBakeEmission", "VolumeBakeAlbedo");
    private record CoefficientFormat(String key, String layout, String gpuFormat, int spirv) { }
    private static final List<CoefficientFormat> COEFFICIENT_FORMATS = List.of(
            new CoefficientFormat("red", "r16f", "R16_FLOAT", Spv.SpvImageFormatR16f),
            new CoefficientFormat("packed", "r11f_g11f_b10f", "RG11B10_FLOAT", Spv.SpvImageFormatR11fG11fB10f),
            new CoefficientFormat("rgba", "rgba16f", "RGBA16_FLOAT", Spv.SpvImageFormatRgba16f));

    private VolumeMaterialBake() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) throw new IllegalArgumentException("Usage: VolumeMaterialBake <resource-root> <lumenfx-include-root>");
        Path resources = Path.of(args[0]).toAbsolutePath().normalize();
        Path includes = Path.of(args[1]).toAbsolutePath().normalize();
        try (var paths = Files.walk(resources)) {
            for (Path manifest : paths.filter(path -> path.getFileName().toString().endsWith(SUFFIX)).sorted().toList())
                bake(manifest, includes);
        }
        Path preshaded = includes.resolveSibling("compute").resolve("volume/preshaded.volume.json");
        if (!preshaded.startsWith(resources)) bake(preshaded, includes);
    }

    private static void bake(Path manifest, Path includes) throws IOException {
        JsonObject inputs;
        try (var reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            inputs = JsonParser.parseReader(reader).getAsJsonObject();
        }
        if (!"lumenfx.volume_material_source.v1".equals(inputs.get("schema").getAsString()))
            throw new BakeException("Invalid volume material source manifest " + manifest);
        String file = manifest.getFileName().toString();
        String base = file.substring(0, file.length() - SUFFIX.length());
        Path entry = manifest.resolveSibling(base + ".csh");
        String source = imports(entry, Map.of("lumenfx", includes), new HashSet<>(), new HashMap<>());
        var result = new JsonObject();
        result.addProperty("schema", "lumenfx.volume_material_program.v2");
        int versionEnd = source.indexOf('\n');
        if (versionEnd < 0 || !source.startsWith("#version ")) throw new BakeException("Volume material has no GLSL version " + entry);
        var fogModes = List.of(new Variant("none"), new Variant("height", "LFX_VOLUME_HEIGHT_FOG"),
                new Variant("froxel", "LFX_VOLUME_HEIGHT_FOG", "LFX_VOLUME_FROXEL_FOG"));
        var rayModes = List.of(new Variant("direct"), new Variant("shadow", "LFX_VOLUME_SURFACE_SHADOW"),
                new Variant("inscattering", "LFX_VOLUME_CACHE_READ"),
                new Variant("transmittance", "LFX_VOLUME_CACHE_TRANS_READ"),
                new Variant("transmittance_shadow", "LFX_VOLUME_CACHE_TRANS_READ", "LFX_VOLUME_SURFACE_SHADOW"),
                new Variant("spot_shadow", "LFX_VOLUME_SURFACE_SHADOW", "LFX_VOLUME_SPOT_SHADOW"),
                new Variant("transmittance_spot_shadow", "LFX_VOLUME_CACHE_TRANS_READ", "LFX_VOLUME_SURFACE_SHADOW", "LFX_VOLUME_SPOT_SHADOW"));
        var rays = new JsonObject();
        for (var fog : fogModes) {
            var modes = new JsonObject();
            for (var ray : rayModes) {
                String[] flags = Arrays.copyOf(fog.flags(), fog.flags().length + ray.flags().length);
                System.arraycopy(ray.flags(), 0, flags, fog.flags().length, ray.flags().length);
                modes.add(ray.key(), compile(manifest, base + "." + fog.key() + "." + ray.key(),
                        define(source, versionEnd, flags), RADIANCE));
            }
            rays.add(fog.key(), modes);
        }
        var cacheModes = List.of(new Variant("inscattering", "LFX_VOLUME_CACHE_WRITE"),
                new Variant("inscattering_shadow", "LFX_VOLUME_CACHE_WRITE", "LFX_VOLUME_SURFACE_SHADOW"),
                new Variant("inscattering_spot_shadow", "LFX_VOLUME_CACHE_WRITE", "LFX_VOLUME_SURFACE_SHADOW", "LFX_VOLUME_SPOT_SHADOW"),
                new Variant("transmittance", "LFX_VOLUME_CACHE_WRITE", "LFX_VOLUME_CACHE_TRANS_WRITE"));
        var caches = new JsonObject();
        for (var cache : cacheModes)
            caches.add(cache.key(), compile(manifest, base + ".cache." + cache.key(), define(source, versionEnd, cache.flags()), LIGHTING));
        result.add("ray", rays);
        result.add("cache", caches);
        var bake = new JsonObject();
        if (!inputs.has("preshaded") || !inputs.get("preshaded").getAsBoolean()) {
            for (var format : COEFFICIENT_FORMATS) {
                String writer = define(source, versionEnd, "LFX_VOLUME_PRESHADE_WRITE").replace(
                        "layout(r11f_g11f_b10f) uniform writeonly image3D VolumeBake",
                        "layout(" + format.layout() + ") uniform writeonly image3D VolumeBake");
                bake.add(format.key(), compile(manifest, base + ".bake." + format.key(), writer,
                        new Output(COEFFICIENTS, format.gpuFormat(), format.spirv(), true, false)));
            }
        }
        result.add("bake", bake);
        result.add("sparse_volumes", inputs.getAsJsonObject("sparse_volumes"));
        Files.writeString(manifest.resolveSibling(base + ".volume.program.json"), new GsonBuilder().setPrettyPrinting().create().toJson(result),
                StandardCharsets.UTF_8);
    }

    private static String define(String source, int versionEnd, String... names) {
        var output = new StringBuilder(source.substring(0, versionEnd + 1));
        for (String name : names) output.append("#define ").append(name).append(" 1\n");
        return output.append(source.substring(versionEnd + 1)).toString();
    }

    private static JsonObject compile(Path manifest, String base, String source, Output output) throws IOException {
        String filename = base + ".csh";
        String glSource = define(source, source.indexOf('\n'), "LFX_VOLUME_OPENGL");
        Stage vulkan = descriptors(ShadercCompiler.preprocessCompute(source, filename), false);
        Stage gl = descriptors(ShadercCompiler.preprocessCompute(glSource, filename), true);
        if (!vulkan.descriptors().equals(gl.descriptors()))
            throw new BakeException("Volume shader descriptor layout differs between backends " + filename);
        byte[] vkCode = ShadercCompiler.compileVulkan(vulkan.source(), filename);
        byte[] glCode = ShadercCompiler.compileOpenGl45(gl.source(), filename);
        JsonObject program = metadata(SpirvReflection.reflect(vkCode), SpirvReflection.reflect(glCode), vulkan.descriptors(), output);
        String vk = base + ".volume.vk.spv", opengl = base + ".volume.gl.spv";
        var artifacts = new JsonObject();
        artifacts.add("vulkan", new ShaderArtifact(vk, vkCode, ShaderOutputWriter.sha256(vkCode)).toJson());
        artifacts.add("opengl", new ShaderArtifact(opengl, glCode, ShaderOutputWriter.sha256(glCode)).toJson());
        var result = new JsonObject();
        result.add("shader", program);
        result.add("artifacts", artifacts);
        Files.write(manifest.resolveSibling(vk), vkCode);
        Files.write(manifest.resolveSibling(opengl), glCode);
        return result;
    }

    static String imports(Path file, Path root, Set<Path> stack) throws IOException {
        return imports(file, Map.of("lumenfx", root), stack);
    }

    static String imports(Path file, Map<String, Path> roots, Set<Path> stack) throws IOException {
        return imports(file, roots, stack, null);
    }

    static String imports(Path file, Map<String, Path> roots, Set<Path> stack, Map<Path, Integer> includeGuards) throws IOException {
        var versions = new ImportedGlslVersions();
        String flattened = expandImports(file, roots, stack, includeGuards, versions);
        Matcher entryVersion = GLSL_VERSION.matcher(flattened);
        if (!entryVersion.find() || Integer.parseInt(entryVersion.group(1)) >= versions.maximum) return flattened;
        return flattened.substring(0, entryVersion.start(1)) + versions.maximum + flattened.substring(entryVersion.end(1));
    }

    private static String expandImports(Path file, Map<String, Path> roots, Set<Path> stack,
                                        Map<Path, Integer> includeGuards, ImportedGlslVersions versions) throws IOException {
        if (!stack.add(file)) throw new BakeException("Cyclic volume shader import " + file);
        try {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            Matcher matcher = IMPORT.matcher(source);
            var output = new StringBuilder();
            while (matcher.find()) {
                String namespace = matcher.group(1);
                Path root = roots.get(namespace);
                if (root == null) throw new BakeException("Unsupported shader import namespace " + namespace + " in " + file);
                Path include = root.resolve(matcher.group(2)).normalize();
                if (!include.startsWith(root)) throw new BakeException("Shader import escapes its namespace " + include);
                String imported = versions.consume(expandImports(include, roots, stack, includeGuards, versions));
                if (includeGuards != null) {
                    int id = includeGuards.computeIfAbsent(include, ignored -> includeGuards.size());
                    String guard = "LFX_BAKE_IMPORT_" + id;
                    imported = "#ifndef " + guard + "\n#define " + guard + "\n" + imported + "\n#endif";
                }
                matcher.appendReplacement(output, Matcher.quoteReplacement("\n" + imported + "\n"));
            }
            matcher.appendTail(output);
            if (output.indexOf("#moj_import") >= 0) throw new BakeException("Unresolved volume shader import in " + file);
            return output.toString();
        } finally { stack.remove(file); }
    }

    private static final class ImportedGlslVersions {
        int maximum;

        String consume(String imported) {
            Matcher version = GLSL_VERSION.matcher(imported);
            if (!version.find()) return imported;
            maximum = Math.max(maximum, Integer.parseInt(version.group(1)));
            return imported.substring(0, version.start()) + imported.substring(version.end());
        }
    }

    private static Stage descriptors(String source, boolean gl) {
        var result = new LinkedHashMap<String, Descriptor>();
        Matcher matcher = DECLARATION.matcher(source);
        var output = new StringBuilder();
        int uniform = 0, sampled = 0, image = 0;
        while (matcher.find()) {
            String layout = matcher.group(2), qualifier = matcher.group(3), type = matcher.group(4);
            String name = matcher.group(5) == null ? type : matcher.group(5);
            int glBinding;
            if ("std140".equals(layout)) glBinding = uniform++;
            else if ("rgba16f".equals(layout) && "writeonly".equals(qualifier) && type.equals("image2D")) glBinding = image++;
            else if ("rgba32f".equals(layout) && qualifier == null && type.equals("image2D")) glBinding = image++;
            else if (type.equals("image3D") && ("r16f".equals(layout) || "rgba16f".equals(layout) || "r11f_g11f_b10f".equals(layout)))
                glBinding = image++;
            else if (layout == null && qualifier == null && (type.equals("sampler2D") || type.equals("sampler2DArray")
                    || type.equals("sampler3D") || type.equals("usampler3D")
                    || type.equals("samplerCube") || type.equals("samplerCubeArray") || type.equals("samplerCubeShadow")))
                glBinding = sampled++;
            else throw new BakeException("Unsupported volume material descriptor " + name);
            int binding = result.size();
            if (result.putIfAbsent(name, new Descriptor(type, name, binding, glBinding)) != null)
                throw new BakeException("Duplicate volume material descriptor " + name);
            String declaration = matcher.group();
            String prefix = "layout(" + (layout == null ? "" : layout + ", ") + "binding = " + (gl ? glBinding : binding) + ") ";
            matcher.appendReplacement(output, Matcher.quoteReplacement(prefix + declaration.substring(declaration.indexOf("uniform"))));
        }
        matcher.appendTail(output);
        return new Stage(output.toString(), Map.copyOf(result));
    }

    private static JsonObject metadata(SpirvReflection.Reflection vk, SpirvReflection.Reflection gl, Map<String, Descriptor> descriptors,
                                       Output output) {
        var localSize = output.volume() ? new int[] {4, 4, 4} : new int[] {8, 8, 1};
        if (!Arrays.equals(vk.localSize(), localSize) || !Arrays.equals(gl.localSize(), localSize))
            throw new BakeException("Invalid volume material workgroup");
        var bindings = new JsonArray();
        for (var resource : vk.uniformBuffers()) {
            var binding = binding("uniformBuffer", resource, gl.uniformBuffers(), descriptors);
            bindings.add(binding);
        }
        for (var resource : vk.sampledImages()) {
            var binding = binding("sampledTexture", resource, gl.sampledImages(), descriptors);
            String name = binding.get("name").getAsString();
            var descriptor = descriptors.get(name);
            boolean pageTable = descriptor.type().equals("usampler3D");
            boolean cubeShadow = descriptor.type().equals("samplerCubeShadow");
            boolean cubeArray = descriptor.type().equals("samplerCubeArray");
            boolean cube = cubeArray || cubeShadow || descriptor.type().equals("samplerCube");
            boolean depth = cubeShadow || switch (name) {
                case "VolumeSceneDepth", "VolumeSpotShadowDepth", "LfxForwardDirectionalDepth", "LfxStaticDirectionalDepth" -> true;
                default -> false;
            };
            boolean volume = descriptor.type().endsWith("3D");
            boolean array = cubeArray || descriptor.type().equals("sampler2DArray");
            binding.addProperty("format", pageTable ? "R32_UINT" : depth ? "D32_FLOAT" : "RGBA16_FLOAT");
            binding.addProperty("dynamicFloatFormat", !pageTable && !depth);
            binding.addProperty("volume", volume);
            binding.addProperty("cube", cube);
            binding.addProperty("array", array);
            binding.addProperty("descriptorForm", "COMBINED_IMAGE_SAMPLER");
            binding.addProperty("samplerDescriptorBinding", -1);
            if (resource.imageArrayed() != array || resource.imageDimension() != (volume ? Spv.SpvDim3D : cube ? Spv.SpvDimCube : Spv.SpvDim2D))
                throw new BakeException("Unexpected material image type for " + resource.name());
            bindings.add(binding);
        }
        if (vk.storageImages().size() != output.images().size() || gl.storageImages().size() != output.images().size())
            throw new BakeException("Volume shader output image count differs from its pass");
        for (var image : vk.storageImages()) {
            if (!output.images().contains(image.name()) || image.imageStorageFormat() != output.spirvFormat()
                    || image.imageDimension() != (output.volume() ? Spv.SpvDim3D : Spv.SpvDim2D)
                    || image.nonReadable() == output.readWrite() || image.nonWritable())
                throw new BakeException("Unexpected volume shader output image " + image.name());
            var descriptor = binding("storageImage", image, gl.storageImages(), descriptors);
            descriptor.addProperty("format", output.format());
            descriptor.addProperty("access", output.readWrite() ? "READ_WRITE" : "WRITE_ONLY");
            descriptor.addProperty("array", false);
            descriptor.addProperty("volume", output.volume());
            bindings.add(descriptor);
        }
        var program = new JsonObject();
        var caps = new JsonObject();
        caps.addProperty("waveOperations", vk.hasWaveOperations());
        caps.addProperty("nativeFp16", vk.hasNativeFp16());
        caps.addProperty("storageBuffers", false);
        caps.addProperty("storageImageExtendedFormats", vk.hasStorageImageExtendedFormats());
        var workgroup = new JsonObject();
        workgroup.addProperty("x", localSize[0]); workgroup.addProperty("y", localSize[1]); workgroup.addProperty("z", localSize[2]);
        program.addProperty("stage", "compute");
        program.addProperty("vulkanEntryPoint", "main");
        program.addProperty("openglEntryPoint", "main");
        program.add("capabilities", caps);
        program.add("workgroup", workgroup);
        program.add("bindings", bindings);
        return program;
    }

    private static JsonObject binding(String kind, SpirvReflection.Resource resource, Iterable<SpirvReflection.Resource> gl,
                                      Map<String, Descriptor> descriptors) {
        Descriptor descriptor = descriptors.values().stream().filter(value -> value.binding() == resource.descriptorBinding())
                .findFirst().orElseThrow(() -> new BakeException("Unresolved volume material shader binding " + resource.name()));
        if (resource.descriptorSet() != 0) throw new BakeException("Unexpected volume material descriptor set " + resource.name());
        boolean found = false;
        for (var candidate : gl) {
            if (candidate.descriptorBinding() == descriptor.glBinding()) found = true;
        }
        if (!found) throw new BakeException("OpenGL volume shader binding differs for " + resource.name());
        var result = new JsonObject();
        result.addProperty("kind", kind);
        result.addProperty("name", descriptor.name());
        result.addProperty("descriptorSet", 0);
        result.addProperty("descriptorBinding", descriptor.binding());
        result.addProperty("glBinding", descriptor.glBinding());
        return result;
    }
}
