package com.kltyton.lumenfx.tools.shader;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.lwjgl.util.spvc.Spv;

final class StageBake {
    private record Baked(
            String pass,
            String variant,
            JsonObject metadata,
            Map<String, byte[]> artifacts,
            Set<String> selectionKeys
    ) {
    }

    private record StageResult(
            ShaderManifest.Stage stage,
            ShaderStage shaderStage,
            JsonObject metadata,
            JsonObject artifacts,
            Map<String, byte[]> files
    ) {
    }

    private StageBake() {
    }

    static void run(final BakeOptions options, final String pipeline) throws IOException {
        final ShaderManifest manifest = options.niagaraEffect() == null
                ? ShaderManifest.load(options.manifest(), pipeline)
                : ShaderManifest.loadNiagara(options.manifest(), pipeline, options.niagaraEffect(), options.niagaraEmitter(),
                        options.generatedNiagaraSource());
        GitHead.verify(options.ueRoot(), manifest.engineHead());
        final Path runRoot = Files.createTempDirectory(options.workDirectory(), "shader-build-");
        try {
            final DxcCompiler dxc = new DxcCompiler(options.dxc(), options.dxcTimeout());
            final GitShaderSource gitSource = ShaderSourceStager.gitSource(manifest, runRoot);
            final ShaderDirectoryMappings shaderDirectories = ShaderDirectoryMappings.read(options.shaderDirectories());
            final List<Baked> baked = new ArrayList<>();
            for (ShaderManifest.Pass pass : manifest.passes()) {
                if (!pass.active() && !(options.includeOptional() && pass.optional())) {
                    continue;
                }
                final Map<String, String> bufferLowerings = bufferLowerings(pass);
                for (ShaderManifest.Variant variant : pass.variants()) {
                    final StageFormatResolver formatResolver = new StageFormatResolver(manifest, pass, options, variant);
                    final Map<String, ResolvedFormat> formats = formatResolver.formats();
                    final Map<String, String> imageFormats = formatResolver.imageFormats();
                    final Map<String, String> defines = defines(manifest, pass, variant);
                    final List<StageResult> stages = new ArrayList<>();
                    final Map<String, byte[]> artifacts = new LinkedHashMap<>();
                    final JsonObject programArtifacts = new JsonObject();
                    final JsonObject clipContract = new JsonObject();
                    boolean graphics = false;
                    boolean vertex = false;

                    for (ShaderManifest.Stage stage : pass.stages()) {
                        final ShaderStage shaderStage = ShaderStage.parse(stage.stage());
                        graphics |= shaderStage.graphics();
                        vertex |= shaderStage == ShaderStage.VERTEX;
                        final StageResult result = compileStage(
                                options,
                                manifest,
                                pass,
                                variant,
                                stage,
                                shaderStage,
                                defines,
                                formats,
                                imageFormats,
                                bufferLowerings,
                                dxc,
                                gitSource,
                                shaderDirectories,
                                runRoot
                        );
                        stages.add(result);
                        programArtifacts.add(shaderStage.id(), result.artifacts());
                        artifacts.putAll(result.files());
                    }
                    if (graphics) {
                        clipContract.addProperty("sourceClipZ", "zero-to-one");
                        clipContract.addProperty("yFlip", "ue-screen-x-neg-y");
                        final JsonObject vulkan = new JsonObject();
                        vulkan.addProperty("clipZ", "zero-to-one");
                        vulkan.addProperty("yFlip", true);
                        clipContract.add("vulkan", vulkan);
                        final JsonArray glVariants = new JsonArray();
                        for (String clip : manifest.glClipRanges()) {
                            final JsonObject gl = new JsonObject();
                            gl.addProperty("id", clip);
                            gl.addProperty("clipZ", clip);
                            gl.addProperty("yFlip", true);
                            gl.addProperty("fixupDepthConvention", "neg-one-to-one".equals(clip));
                            glVariants.add(gl);
                        }
                        clipContract.add("openglVariants", glVariants);
                    }
                    final List<JsonObject> stageJson = new ArrayList<>();
                    for (StageResult result : stages) {
                        stageJson.add(result.metadata());
                    }
                    final JsonObject program = ShaderMetadataBuilder.program(
                            manifest, pass, variant, stageJson, graphics ? clipContract : null, programArtifacts
                    );
                    baked.add(new Baked(
                            pass.name(),
                            variant.id(),
                            program,
                            Map.copyOf(artifacts),
                            new LinkedHashSet<>(variant.selectionKeys())
                    ));
                }
            }

            final ShaderOutputWriter writer = new ShaderOutputWriter(options.outputDirectory(), pipeline);
            for (Baked program : baked) {
                writer.preflight(program.pass(), program.variant(), program.artifacts());
            }
            for (Baked program : baked) {
                writer.writeProgram(program.pass(), program.variant(), program.metadata(), program.artifacts());
            }
            writer.writeIndex(index(manifest, writer, baked));
            System.out.println("Baked " + baked.size() + " " + pipeline + " shader programs into " + writer.root());
            if (!options.keepWork()) {
                deleteRecursively(runRoot);
            } else {
                System.out.println("Work directory retained: " + runRoot);
            }
        } catch (RuntimeException | IOException error) {
            System.err.println("Work directory retained for diagnosis: " + runRoot);
            throw error;
        }
    }

    private static StageResult compileStage(
            final BakeOptions options,
            final ShaderManifest manifest,
            final ShaderManifest.Pass pass,
            final ShaderManifest.Variant variant,
            final ShaderManifest.Stage stage,
            final ShaderStage shaderStage,
            final Map<String, String> defines,
            final Map<String, ResolvedFormat> formats,
            final Map<String, String> imageFormats,
            final Map<String, String> bufferLowerings,
            final DxcCompiler dxc,
            final GitShaderSource gitSource,
            final ShaderDirectoryMappings shaderDirectories,
            final Path runRoot
    ) throws IOException {
        final String passSegment = ShaderOutputWriter.resourceSegment(pass.name());
        final String variantSegment = ShaderOutputWriter.resourceSegment(variant.id());
        final Path stageRoot = runRoot.resolve("stage").resolve(passSegment)
                .resolve(variantSegment).resolve(shaderStage.id());
        final ShaderSourceStager stager = new ShaderSourceStager(
                options.ueRoot(), options.compatDirectory(), stageRoot, manifest, pass,
                shaderDirectories, gitSource
        );
        final ShaderSourceStager.StagedSource staged = stager.stage(
                Path.of(stage.source()), imageFormats, bufferLowerings,
                generatedIncludes(options),
                pass.raw().has("niagara_packed_half") && pass.raw().get("niagara_packed_half").getAsBoolean(),
                pass.raw().has("niagara_sort_packed_half") && pass.raw().get("niagara_sort_packed_half").getAsBoolean(),
                pass.raw().has("niagara_ribbon_packed_half") && pass.raw().get("niagara_ribbon_packed_half").getAsBoolean(),
                preIncludes(pass), stringList(pass, "niagara_uint8_array_writes"), stringList(pass, "niagara_shared_array_readers"),
                stringList(pass, "niagara_particle_read_half_buffers")
        );
        final Path compileRoot = runRoot.resolve("compile").resolve(passSegment)
                .resolve(variantSegment).resolve(shaderStage.id());
        Files.createDirectories(compileRoot);

        final Map<String, String> stageDefines = new LinkedHashMap<>(defines);
        stageDefines.putAll(stage.defines());
        final boolean native16 = "1".equals(stageDefines.get("DIM_16BIT_VALU"))
                || "1".equals(stageDefines.get("PLATFORM_SUPPORTS_REAL_TYPES"));
        final List<String> vulkanArgs = dxcArguments(manifest, shaderStage, true);
        final Path vulkanPath = compileRoot.resolve("vulkan.spv");
        final byte[] compiledVulkanSpirv = dxc.compile(
                staged.sourceFile(),
                vulkanPath,
                stage.entrypoint(),
                shaderStage,
                stageDefines,
                staged.includeDirectories(),
                vulkanArgs,
                native16,
                compileRoot
        );
        final byte[] vulkanSpirv = patchUnknownStorageImageFormats(compiledVulkanSpirv, vulkanPath, formats);
        final SpirvReflection.Reflection vulkanReflection = SpirvReflection.reflect(vulkanSpirv);
        if (shaderStage == ShaderStage.COMPUTE
                && (vulkanReflection.localSize()[0] <= 0
                || vulkanReflection.localSize()[1] <= 0
                || vulkanReflection.localSize()[2] <= 0)) {
            throw new BakeException("Compute pass " + pass.name() + " has no LocalSize");
        }
        final BindingPlanner.Plan plan = BindingPlanner.plan(vulkanReflection, formats);
        if (vulkanReflection.hasNativeFp16() || vulkanReflection.hasStorage16()) {
            throw new BakeException(
                    "Shader " + pass.name() + ":" + shaderStage.id()
                            + " reflected unexpected capabilities nativeFp16="
                            + vulkanReflection.hasNativeFp16() + ", storage16="
                            + vulkanReflection.hasStorage16()
            );
        }

        final boolean vertex = shaderStage == ShaderStage.VERTEX;
        byte[] glSourceSpirv = vulkanSpirv;
        SpirvReflection.Reflection glSourceReflection = vulkanReflection;
        if (vertex) {
            final List<String> glArgs = dxcArguments(manifest, shaderStage, false);
            glSourceSpirv = dxc.compile(
                    staged.sourceFile(),
                    compileRoot.resolve("vulkan-gl-source.spv"),
                    stage.entrypoint(),
                    shaderStage,
                    stageDefines,
                    staged.includeDirectories(),
                    glArgs,
                    native16,
                    compileRoot
            );
            glSourceReflection = SpirvReflection.reflect(glSourceSpirv);
        }

        final Map<String, Object> stageArtifacts = new LinkedHashMap<>();
        final JsonObject artifactsJson = new JsonObject();
        final Map<String, byte[]> files = new LinkedHashMap<>();
        Map<String, OpenGlStorageTexels.View> glTexelViews = Map.of();

        final String vulkanName = "vulkan." + shaderStage.id() + ".spv";
        addArtifact(stageArtifacts, artifactsJson, files, "vulkan", vulkanName, vulkanSpirv);

        if (shaderStage.graphics()) {
            final JsonObject glVariants = new JsonObject();
            for (String clip : manifest.glClipRanges()) {
                final GlslTranslator.Options glOptions = new GlslTranslator.Options(
                        vertex, "neg-one-to-one".equals(clip)
                );
                final GlslTranslator.Translation translation = GlslTranslator.translate(
                        glSourceSpirv, glSourceReflection, plan, glOptions
                );
                final Path glsl = compileRoot.resolve("opengl." + shaderStage.id() + "." + clip + ".glsl");
                Files.writeString(glsl, translation.source(), java.nio.charset.StandardCharsets.UTF_8);
                final byte[] opengl = ShadercCompiler.compileOpenGl45(
                        translation.source(), glsl.toString(), shaderStage
                );
                final String name = "opengl." + shaderStage.id() + "." + clip + ".spv";
                addArtifact(stageArtifacts, glVariants, files, clip, name, opengl);
            }
            artifactsJson.add("openglVariants", glVariants);
            stageArtifacts.put("openglVariants", glVariants);
        } else {
            final GlslTranslator.Options glOptions = GlslTranslator.Options.COMPUTE;
            final GlslTranslator.Translation translation = GlslTranslator.translate(
                    glSourceSpirv, glSourceReflection, plan, glOptions
            );
            var gl = OpenGlStorageTexels.lower(translation.source(), pass, plan);
            glTexelViews = gl.views();
            final Path glsl = compileRoot.resolve("opengl." + shaderStage.id() + ".glsl");
            Files.writeString(glsl, gl.source(), java.nio.charset.StandardCharsets.UTF_8);
            final byte[] opengl = ShadercCompiler.compileOpenGl45(
                    gl.source(), glsl.toString(), shaderStage
            );
            final String name = "opengl." + shaderStage.id() + ".spv";
            addArtifact(stageArtifacts, artifactsJson, files, "opengl", name, opengl);
        }

        final JsonObject clip = shaderStage.graphics() ? clipContract(manifest) : null;
        final JsonObject metadata = ShaderMetadataBuilder.stage(
                manifest, pass, stage, variant, shaderStage, vulkanReflection, plan, stageArtifacts, clip
        );
        for (var entry : metadata.getAsJsonArray("bindings")) {
            JsonObject binding = entry.getAsJsonObject();
            if (!binding.get("kind").getAsString().equals("storageBuffer")) continue;
            var view = glTexelViews.get(binding.get("name").getAsString());
            if (view == null) continue;
            JsonObject json = new JsonObject();
            json.addProperty("unit", view.unit());
            json.addProperty("format", view.format());
            binding.add("openGlTexelView", json);
            binding.addProperty("access", "READ_ONLY");
        }
        metadata.addProperty("glRoute", "PRECOMPILED_OPENGL_SPIRV");
        metadata.addProperty("vulkanEntryPoint", stage.entrypoint());
        metadata.addProperty("openglEntryPoint", "main");
        metadata.add("artifacts", artifactsJson);
        return new StageResult(stage, shaderStage, metadata, artifactsJson, Map.copyOf(files));
    }

    private static void addArtifact(
            final Map<String, Object> stageArtifacts,
            final JsonObject artifactsJson,
            final Map<String, byte[]> files,
            final String key,
            final String name,
            final byte[] bytes
    ) {
        files.put(name, bytes);
        final JsonObject artifact = new JsonObject();
        artifact.addProperty("path", name);
        artifact.addProperty("size", bytes.length);
        artifact.addProperty("sha256", ShaderOutputWriter.sha256(bytes));
        artifactsJson.add(key, artifact);
        stageArtifacts.put(key, artifact);
    }


    private static Map<String, String> bufferLowerings(final ShaderManifest.Pass pass) {
        final Map<String, String> result = new LinkedHashMap<>();
        for (ShaderManifest.Resource resource : ShaderManifest.resources(pass)) {
            if (resource.niagaraBufferLowering() != null) {
                result.put(resource.name(), resource.niagaraBufferLowering());
            }
        }
        return Map.copyOf(result);
    }

    private static List<String> preIncludes(final ShaderManifest.Pass pass) {
        final JsonArray values = pass.raw().getAsJsonArray("generated_preincludes");
        if (values == null) return List.of();
        final List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.getAsString()));
        return List.copyOf(result);
    }

    private static List<String> stringList(final ShaderManifest.Pass pass, final String field) {
        final JsonArray values = pass.raw().getAsJsonArray(field);
        if (values == null) return List.of();
        final List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.getAsString()));
        return result;
    }

    private static Map<String, Path> generatedIncludes(final BakeOptions options) throws IOException {
        final Map<String, Path> includes = new LinkedHashMap<>();
        final Path root = options.generatedIncludeDirectory();
        if (root != null) {
            try (var paths = Files.walk(root)) {
                for (Path file : paths.filter(Files::isRegularFile).toList()) {
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    includes.put("/Engine/Generated/" + relative, file);
                }
            }
        }
        if (options.generatedNiagaraSource() != null) {
            String name = "/Engine/Generated/NiagaraEmitterInstance.ush";
            if (includes.putIfAbsent(name, options.generatedNiagaraSource()) != null)
                throw new BakeException("Generated include is supplied twice: " + name);
        }
        return Map.copyOf(includes);
    }

    private static List<String> dxcArguments(
            final ShaderManifest manifest,
            final ShaderStage stage,
            final boolean vulkan
    ) {
        final List<String> arguments = new ArrayList<>(manifest.dxcExtraArguments());
        if (stage == ShaderStage.VERTEX) {
            arguments.removeIf(argument -> "-fvk-invert-y".equals(argument));
            if (vulkan) {
                arguments.add("-fvk-invert-y");
            }
        }
        return arguments;
    }

    private static Map<String, String> defines(
            final ShaderManifest manifest,
            final ShaderManifest.Pass pass,
            final ShaderManifest.Variant variant
    ) {
        final Map<String, String> result = new LinkedHashMap<>(manifest.baseDefines());
        result.putAll(pass.defines());
        result.putAll(variant.defines());
        if (pass.raw().has("niagara_stage")) {
            pass.raw().getAsJsonObject("niagara_stage").getAsJsonObject("defines").entrySet()
                    .forEach(entry -> result.put(entry.getKey(), entry.getValue().getAsString()));
        }
        return result;
    }

    private static JsonObject clipContract(final ShaderManifest manifest) {
        final JsonObject json = new JsonObject();
        json.addProperty("sourceClipZ", "zero-to-one");
        json.addProperty("yFlip", "ue-screen-x-neg-y");
        final JsonObject vulkan = new JsonObject();
        vulkan.addProperty("clipZ", "zero-to-one");
        vulkan.addProperty("yFlip", true);
        json.add("vulkan", vulkan);
        final JsonArray gl = new JsonArray();
        for (String clip : manifest.glClipRanges()) {
            final JsonObject entry = new JsonObject();
            entry.addProperty("id", clip);
            entry.addProperty("clipZ", clip);
            entry.addProperty("yFlip", true);
            entry.addProperty("fixupDepthConvention", "neg-one-to-one".equals(clip));
            gl.add(entry);
        }
        json.add("openglVariants", gl);
        return json;
    }

    private static JsonObject index(
            final ShaderManifest manifest,
            final ShaderOutputWriter writer,
            final List<Baked> baked
    ) {
        final JsonObject index = new JsonObject();
        index.addProperty("schema", "lumenfx.shaders.index.v1");
        index.addProperty("pipeline", manifest.pipeline());
        index.addProperty("engineHead", manifest.engineHead());
        final JsonArray programs = new JsonArray();
        for (Baked program : baked) {
            final JsonObject entry = new JsonObject();
            entry.addProperty("pass", program.pass());
            entry.addProperty("variant", program.variant());
            final JsonArray keys = new JsonArray();
            for (String key : program.selectionKeys()) {
                keys.add(key);
            }
            entry.add("selectionKeys", keys);
            entry.addProperty("path", ShaderOutputWriter.resourceSegment(manifest.pipeline()) + "/"
                    + ShaderOutputWriter.resourceSegment(program.pass()) + "/"
                    + ShaderOutputWriter.resourceSegment(program.variant()) + "/program.json");
            if (program.metadata().has("sourceCondition")) {
                entry.addProperty("sourceCondition", program.metadata().get("sourceCondition").getAsString());
            }
            entry.add("artifacts", program.metadata().getAsJsonObject("artifacts"));
            final JsonArray stages = new JsonArray();
            for (com.google.gson.JsonElement stage : program.metadata().getAsJsonArray("stages")) {
                stages.add(stage.getAsJsonObject().get("stage").getAsString());
            }
            entry.add("stages", stages);
            programs.add(entry);
        }
        index.add("programs", programs);
        return index;
    }

    private static byte[] patchUnknownStorageImageFormats(
            final byte[] spirv,
            final Path outputPath,
            final Map<String, ResolvedFormat> formats
    ) throws IOException {
        boolean expectsUnknown = false;
        for (ResolvedFormat format : formats.values()) {
            if ("unknown".equals(format.dxcImageFormat())) {
                expectsUnknown = true;
                break;
            }
        }
        if (!expectsUnknown) {
            return spirv;
        }
        final SpirvReflection.Reflection initial = SpirvReflection.reflect(spirv);
        final Set<Integer> targetVarIds = new HashSet<>();
        for (SpirvReflection.Resource image : initial.storageImages()) {
            final ResolvedFormat format = formats.get(image.name());
            if (format != null && "unknown".equals(format.dxcImageFormat())
                    && image.imageStorageFormat() == Spv.SpvImageFormatRgba32f
                    && BindingPlanner.supportsFormatlessStorage(image)) {
                targetVarIds.add(image.id());
            }
        }
        if (targetVarIds.isEmpty()) {
            return spirv;
        }
        final int wordCount = spirv.length / Integer.BYTES;
        final int[] words = new int[wordCount];
        ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(words);
        final Map<Integer, Integer> varToPtrType = new HashMap<>();
        final Map<Integer, Integer> ptrToPointee = new HashMap<>();
        boolean hasWriteWithoutFormatCap = false;
        int pos = 5;
        while (pos < words.length) {
            final int instructionWords = words[pos] >>> 16;
            if (instructionWords <= 0 || pos + instructionWords > words.length) {
                throw new BakeException("Malformed SPIR-V instruction in " + outputPath);
            }
            final int opcode = words[pos] & 0xFFFF;
            if (opcode == 17 && instructionWords >= 2
                    && words[pos + 1] == Spv.SpvCapabilityStorageImageWriteWithoutFormat) {
                hasWriteWithoutFormatCap = true;
            } else if (opcode == 32 && instructionWords >= 4) {
                ptrToPointee.put(words[pos + 1], words[pos + 3]);
            } else if (opcode == 59 && instructionWords >= 3) {
                varToPtrType.put(words[pos + 2], words[pos + 1]);
            }
            pos += instructionWords;
        }
        final Set<Integer> targetImageTypeIds = new HashSet<>();
        for (Integer varId : targetVarIds) {
            final Integer imageTypeId = ptrToPointee.get(varToPtrType.get(varId));
            if (imageTypeId != null) {
                targetImageTypeIds.add(imageTypeId);
            }
        }
        if (targetImageTypeIds.isEmpty()) {
            return spirv;
        }
        for (Map.Entry<Integer, Integer> entry : varToPtrType.entrySet()) {
            if (!targetVarIds.contains(entry.getKey())
                    && targetImageTypeIds.contains(ptrToPointee.get(entry.getValue()))) {
                throw new BakeException("Cannot safely coerce shared OpTypeImage format in " + outputPath);
            }
        }
        int patchedTypes = 0;
        pos = 5;
        while (pos < words.length) {
            final int instructionWords = words[pos] >>> 16;
            final int opcode = words[pos] & 0xFFFF;
            if (opcode == 25 && instructionWords >= 9 && targetImageTypeIds.contains(words[pos + 1])
                    && words[pos + 7] == 2 && words[pos + 8] == Spv.SpvImageFormatRgba32f) {
                words[pos + 8] = Spv.SpvImageFormatUnknown;
                patchedTypes++;
            }
            pos += instructionWords;
        }
        if (patchedTypes == 0) {
            return spirv;
        }
        final int[] finalWords;
        if (hasWriteWithoutFormatCap) {
            finalWords = words;
        } else {
            finalWords = new int[words.length + 2];
            System.arraycopy(words, 0, finalWords, 0, 5);
            finalWords[5] = (2 << 16) | 17;
            finalWords[6] = Spv.SpvCapabilityStorageImageWriteWithoutFormat;
            System.arraycopy(words, 5, finalWords, 7, words.length - 5);
        }
        final byte[] patchedBytes = new byte[finalWords.length * Integer.BYTES];
        ByteBuffer.wrap(patchedBytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(finalWords);
        DxcCompiler.validateSpirv(patchedBytes, "patched SPIR-V " + outputPath);
        Files.write(outputPath, patchedBytes);
        return patchedBytes;
    }

    private static void deleteRecursively(final Path root) {
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            final List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        } catch (IOException error) {
            System.err.println("Unable to remove work directory " + root + ": " + error.getMessage());
        }
    }
}
