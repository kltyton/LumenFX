package com.kltyton.lumenfx.tools.shader;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.regex.Pattern;
import org.lwjgl.util.spvc.Spv;

/** Bakes software Nanite raster material compute programs for Vulkan and OpenGL. */
public final class NaniteRasterMaterialBake {
    private static final String SUFFIX = ".nanite_raster.json";
    private static final Pattern FRAG_COORD = Pattern.compile("\\bgl_FragCoord\\b");
    private static final Pattern FRAG_DEPTH = Pattern.compile("\\bgl_FragDepth\\b");

    private NaniteRasterMaterialBake() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 2 && args.length != 3)
            throw new IllegalArgumentException("Usage: NaniteRasterMaterialBake <resource-root> <lumenfx-include-root> [minecraft-shader-include-root]");
        Path resources = Path.of(args[0]).toAbsolutePath().normalize();
        var roots = new HashMap<String, Path>();
        roots.put("lumenfx", Path.of(args[1]).toAbsolutePath().normalize());
        if (args.length == 3) roots.put("minecraft", Path.of(args[2]).toAbsolutePath().normalize());
        Map<String, Path> includeRoots = Map.copyOf(roots);
        try (var paths = Files.walk(resources)) {
            for (Path manifest : paths.filter(path -> path.getFileName().toString().endsWith(SUFFIX)).sorted().toList())
                bake(manifest, includeRoots);
        }
    }

    private static void bake(Path manifest, Map<String, Path> includeRoots) throws IOException {
        JsonObject input = JsonParser.parseString(Files.readString(manifest, StandardCharsets.UTF_8)).getAsJsonObject();
        if (!"lumenfx.nanite_raster_source.v1".equals(input.get("schema").getAsString()))
            throw new BakeException("Invalid Nanite raster material source " + manifest);
        String filename = manifest.getFileName().toString();
        String base = filename.substring(0, filename.length() - SUFFIX.length());
        Path source = manifest.resolveSibling(base + ".csh");
        String flattened = VolumeMaterialBake.imports(source, includeRoots, new HashSet<>(), new HashMap<>());
        String software = FRAG_DEPTH.matcher(FRAG_COORD.matcher(flattened)
                .replaceAll("lfxNaniteSoftwareFragCoord")).replaceAll("lfxNaniteSoftwareDepth");
        String preprocessed = ShadercCompiler.preprocessCompute(software, source.getFileName().toString());
        var vk = NaniteVertexMaterialBake.descriptors(preprocessed, false);
        var gl = NaniteVertexMaterialBake.descriptors(preprocessed, true);
        byte[] vulkan = ShadercCompiler.compileVulkan(vk.source(), source.getFileName().toString());
        byte[] opengl = ShadercCompiler.compileOpenGl45(gl.source(), source.getFileName().toString());
        JsonObject program = metadata(SpirvReflection.reflect(vulkan), SpirvReflection.reflect(opengl), vk.descriptors());
        String vkName = base + ".nanite_raster.vk.spv", glName = base + ".nanite_raster.gl.spv";
        var artifacts = new JsonObject();
        artifacts.add("vulkan", new ShaderArtifact(vkName, vulkan, ShaderOutputWriter.sha256(vulkan)).toJson());
        artifacts.add("opengl", new ShaderArtifact(glName, opengl, ShaderOutputWriter.sha256(opengl)).toJson());
        var result = new JsonObject();
        result.addProperty("schema", "lumenfx.nanite_raster_program.v2");
        result.add("shader", program);
        result.add("artifacts", artifacts);
        Files.write(manifest.resolveSibling(vkName), vulkan);
        Files.write(manifest.resolveSibling(glName), opengl);
        Files.writeString(manifest.resolveSibling(base + ".nanite.program.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(result), StandardCharsets.UTF_8);
    }

    private static JsonObject metadata(SpirvReflection.Reflection vk, SpirvReflection.Reflection gl,
                                      Map<String, NaniteVertexMaterialBake.Descriptor> descriptors) {
        int[] size = vk.localSize(), glSize = gl.localSize();
        if (size[0] != 64 || size[1] != 1 || size[2] != 1 || !java.util.Arrays.equals(size, glSize))
            throw new BakeException("Nanite raster material requires 64×1×1 workgroups on both backends");
        var bindings = new JsonArray();
        for (var resource : vk.uniformBuffers()) bindings.add(NaniteVertexMaterialBake.binding(resource, gl.uniformBuffers(), descriptors));
        for (var resource : vk.storageBuffers()) bindings.add(NaniteVertexMaterialBake.binding(resource, gl.storageBuffers(), descriptors));
        for (var resource : vk.sampledImages()) bindings.add(NaniteVertexMaterialBake.binding(resource, gl.sampledImages(), descriptors));
        var workgroup = new JsonObject();
        workgroup.addProperty("x", size[0]); workgroup.addProperty("y", size[1]); workgroup.addProperty("z", size[2]);
        var caps = new JsonObject();
        caps.addProperty("waveOperations", vk.hasWaveOperations()); caps.addProperty("nativeFp16", vk.hasNativeFp16());
        caps.addProperty("storageBuffers", vk.hasStorageBuffers()); caps.addProperty("storageImageExtendedFormats", vk.hasStorageImageExtendedFormats());
        var program = new JsonObject();
        program.addProperty("stage", "compute"); program.addProperty("vulkanEntryPoint", "main"); program.addProperty("openglEntryPoint", "main");
        program.add("workgroup", workgroup); program.add("capabilities", caps); program.add("bindings", bindings);
        return program;
    }
}
