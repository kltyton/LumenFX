package com.kltyton.lumenfx.tools.shader;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lwjgl.util.spvc.Spv;

/** Bakes the material-specific vertex evaluation used before Nanite rasterization. */
public final class NaniteVertexMaterialBake {
    private static final String SUFFIX = ".nanite_vertex.json";
    private static final Pattern DECLARATION = Pattern.compile(
            "(?:layout\\s*\\(\\s*(std140|std430)\\s*\\)\\s*)?(?:(readonly|writeonly)\\s+)?(uniform|buffer)\\s+([A-Za-z_][A-Za-z0-9_]*)(?:\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*;|\\s*(?=\\{))");

    record Descriptor(String name, String type, String kind, String access, int binding, int glBinding) { }
    record Stage(String source, Map<String, Descriptor> descriptors) { }

    private NaniteVertexMaterialBake() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) throw new IllegalArgumentException("Usage: NaniteVertexMaterialBake <resource-root> <lumenfx-include-root>");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Path includes = Path.of(args[1]).toAbsolutePath().normalize();
        try (var paths = Files.walk(root)) {
            for (Path source : paths.filter(path -> path.getFileName().toString().endsWith(SUFFIX)).sorted().toList()) bake(source, includes);
        }
    }

    private static void bake(Path manifest, Path includes) throws IOException {
        JsonObject input = JsonParser.parseString(Files.readString(manifest, StandardCharsets.UTF_8)).getAsJsonObject();
        if (!"lumenfx.nanite_vertex_source.v1".equals(input.get("schema").getAsString()))
            throw new BakeException("Invalid Nanite vertex material source " + manifest);
        String filename = manifest.getFileName().toString();
        String base = filename.substring(0, filename.length() - SUFFIX.length());
        String flattened = VolumeMaterialBake.imports(manifest.resolveSibling(base + ".csh"), includes, new HashSet<>());
        String preprocessed = ShadercCompiler.preprocessCompute(flattened, base + ".csh");
        Stage vk = descriptors(preprocessed, false), gl = descriptors(preprocessed, true);
        byte[] vulkan = ShadercCompiler.compileVulkan(vk.source(), base + ".csh");
        byte[] opengl = ShadercCompiler.compileOpenGl45(gl.source(), base + ".csh");
        JsonObject program = metadata(SpirvReflection.reflect(vulkan), SpirvReflection.reflect(opengl), vk.descriptors());
        String vkName = base + ".nanite.vk.spv", glName = base + ".nanite.gl.spv";
        var artifacts = new JsonObject();
        artifacts.add("vulkan", new ShaderArtifact(vkName, vulkan, ShaderOutputWriter.sha256(vulkan)).toJson());
        artifacts.add("opengl", new ShaderArtifact(glName, opengl, ShaderOutputWriter.sha256(opengl)).toJson());
        var result = new JsonObject();
        result.addProperty("schema", "lumenfx.nanite_vertex_program.v1");
        result.add("interpolator_widths", input.getAsJsonArray("interpolator_widths"));
        result.add("shader", program);
        result.add("artifacts", artifacts);
        Files.write(manifest.resolveSibling(vkName), vulkan);
        Files.write(manifest.resolveSibling(glName), opengl);
        Files.writeString(manifest.resolveSibling(base + ".nanite.program.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(result), StandardCharsets.UTF_8);
    }

    static Stage descriptors(String source, boolean gl) {
        var descriptors = new LinkedHashMap<String, Descriptor>();
        Matcher matcher = DECLARATION.matcher(source);
        var output = new StringBuilder();
        int uniform = 0, storage = 0, sampled = 0;
        while (matcher.find()) {
            String layout = matcher.group(1), qualifier = matcher.group(2), declaration = matcher.group(3), type = matcher.group(4);
            String name = matcher.group(5) == null ? type : matcher.group(5);
            String kind, access = "READ_ONLY";
            int glBinding;
            if (declaration.equals("buffer") && "std430".equals(layout)) {
                kind = "storageBuffer";
                glBinding = storage++;
                access = "readonly".equals(qualifier) ? "READ_ONLY" : "writeonly".equals(qualifier) ? "WRITE_ONLY" : "READ_WRITE";
            } else if (declaration.equals("uniform") && "std140".equals(layout)) {
                kind = "uniformBuffer"; glBinding = uniform++;
            } else if (declaration.equals("uniform") && layout == null && type.contains("sampler")) {
                kind = "sampledTexture"; glBinding = sampled++;
            } else throw new BakeException("Unsupported Nanite vertex descriptor " + name);
            int binding = descriptors.size();
            var value = new Descriptor(name, type, kind, access, binding, glBinding);
            if (descriptors.putIfAbsent(name, value) != null) throw new BakeException("Duplicate Nanite vertex descriptor " + name);
            String prefix = "layout(" + (layout == null ? "" : layout + ", ") + "binding = " + (gl ? glBinding : binding) + ") ";
            if (qualifier != null) prefix += qualifier + " ";
            String tail = matcher.group();
            tail = tail.substring(tail.indexOf(declaration));
            matcher.appendReplacement(output, Matcher.quoteReplacement(prefix + tail));
        }
        matcher.appendTail(output);
        return new Stage(output.toString(), Map.copyOf(descriptors));
    }

    private static JsonObject metadata(SpirvReflection.Reflection vk, SpirvReflection.Reflection gl, Map<String, Descriptor> descriptors) {
        int[] size = vk.localSize(), glSize = gl.localSize();
        if (size[0] != 64 || size[1] != 1 || size[2] != 1 || glSize[0] != 64 || glSize[1] != 1 || glSize[2] != 1)
            throw new BakeException("Invalid Nanite material workgroup");
        var bindings = new JsonArray();
        for (var resource : vk.uniformBuffers()) bindings.add(binding(resource, gl.uniformBuffers(), descriptors));
        for (var resource : vk.storageBuffers()) bindings.add(binding(resource, gl.storageBuffers(), descriptors));
        for (var resource : vk.sampledImages()) bindings.add(binding(resource, gl.sampledImages(), descriptors));
        var workgroup = new JsonObject();
        workgroup.addProperty("x", 64); workgroup.addProperty("y", 1); workgroup.addProperty("z", 1);
        var caps = new JsonObject();
        caps.addProperty("waveOperations", vk.hasWaveOperations()); caps.addProperty("nativeFp16", vk.hasNativeFp16());
        caps.addProperty("storageBuffers", vk.hasStorageBuffers()); caps.addProperty("storageImageExtendedFormats", vk.hasStorageImageExtendedFormats());
        var program = new JsonObject();
        program.addProperty("stage", "compute"); program.addProperty("vulkanEntryPoint", "main"); program.addProperty("openglEntryPoint", "main");
        program.add("workgroup", workgroup); program.add("capabilities", caps); program.add("bindings", bindings);
        return program;
    }

    static JsonObject binding(SpirvReflection.Resource resource, Iterable<SpirvReflection.Resource> gl, Map<String, Descriptor> descriptors) {
        var descriptor = descriptors.values().stream().filter(value -> value.binding() == resource.descriptorBinding()).findFirst()
                .orElseThrow(() -> new BakeException("Unknown Nanite vertex descriptor " + resource.name()));
        if (resource.descriptorSet() != 0) throw new BakeException("Invalid Nanite descriptor set " + resource.name());
        boolean found = false;
        for (var candidate : gl) if (candidate.descriptorBinding() == descriptor.glBinding()) found = true;
        if (!found) throw new BakeException("Nanite vertex OpenGL binding differs: " + resource.name());
        var result = new JsonObject();
        result.addProperty("kind", descriptor.kind()); result.addProperty("name", descriptor.name()); result.addProperty("descriptorSet", 0);
        result.addProperty("descriptorBinding", descriptor.binding()); result.addProperty("glBinding", descriptor.glBinding());
        if (descriptor.kind().equals("storageBuffer")) result.addProperty("access", descriptor.access());
        if (descriptor.kind().equals("sampledTexture")) {
            boolean integer = descriptor.type().startsWith("usampler");
            boolean volume = resource.imageDimension() == Spv.SpvDim3D, cube = resource.imageDimension() == Spv.SpvDimCube;
            String integerFormat = descriptor.name().matches("LfxVT[0-9]+_Pages[0-9]+") ? "RGBA32_UINT" : "R32_UINT";
            String format = switch (descriptor.name()) {
                case "SceneDepthSampler", "LfxMaterialCustomDepth" -> "D32_FLOAT";
                case "LfxMaterialCustomStencil" -> "RG8_UINT";
                default -> integer ? integerFormat : "RGBA16_FLOAT";
            };
            boolean dynamicFloatFormat = !integer && !descriptor.name().equals("SceneDepthSampler")
                    && !descriptor.name().equals("LfxMaterialCustomDepth")
                    && !descriptor.name().equals("LfxMaterialCustomStencil");
            result.addProperty("format", format);
            result.addProperty("dynamicFloatFormat", dynamicFloatFormat); result.addProperty("volume", volume); result.addProperty("cube", cube);
            result.addProperty("array", resource.imageArrayed()); result.addProperty("descriptorForm", "COMBINED_IMAGE_SAMPLER");
            result.addProperty("samplerDescriptorBinding", -1);
        }
        return result;
    }
}
