package com.kltyton.lumenfx.tools.shader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

public final class FogIntegrationBake {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private FogIntegrationBake() {
    }

    public static void main(final String[] args) {
        if (args.length == 0 || containsHelp(args)) {
            System.out.print(FogBakeOptions.USAGE);
            return;
        }

        final FogBakeOptions options;
        try {
            options = FogBakeOptions.parse(args);
        } catch (BakeException error) {
            System.err.println(error.getMessage());
            System.exit(2);
            return;
        }

        try {
            bake(options);
        } catch (BakeException error) {
            System.err.println("FogIntegrationBake failed: " + error.getMessage());
            if (error.getCause() != null && !(error.getCause() instanceof BakeException)) {
                System.err.println("Caused by: " + error.getCause());
            }
            System.exit(1);
        } catch (IOException | RuntimeException error) {
            System.err.println("FogIntegrationBake failed: " + error);
            System.exit(1);
        } catch (LinkageError error) {
            System.err.println("FogIntegrationBake failed to load an LWJGL native library (shaderc/spvc): " + error);
            System.exit(1);
        }
    }

    private static void bake(final FogBakeOptions options) throws IOException {
        Files.createDirectories(options.workDirectory());
        final Path runRoot = Files.createTempDirectory(options.workDirectory(), "fog-integration-");
        final Path stageRoot = Files.createDirectories(runRoot.resolve("stage"));
        final FogSourceStager.StagedSource staged = new FogSourceStager(
                options.shaderRoot(), stageRoot
        ).stage();

        final byte[] vulkan = ShadercCompiler.compileVulkan(staged.flattenedSource(), staged.entryName());
        final SpirvReflection.Reflection vulkanReflection = SpirvReflection.reflect(vulkan);
        final byte[] opengl = ShadercCompiler.compileOpenGl45(staged.flattenedSource(), staged.entryName());
        final SpirvReflection.Reflection openglReflection = SpirvReflection.reflect(opengl);

        final ShaderArtifact vulkanArtifact = new ShaderArtifact(
                "vulkan.spv", vulkan, ShaderOutputWriter.sha256(vulkan)
        );
        final ShaderArtifact openglArtifact = new ShaderArtifact(
                "opengl.spv", opengl, ShaderOutputWriter.sha256(opengl)
        );
        final JsonObject metadata = FogIntegrationMetadataBuilder.build(
                vulkanReflection,
                openglReflection,
                vulkanArtifact,
                openglArtifact,
                staged
        );
        writeOutputs(options.outputDirectory(), metadata, vulkan, opengl);
        System.out.println("Baked " + FogIntegrationMetadataBuilder.NAME + " into "
                + options.outputDirectory().toAbsolutePath().normalize());
        System.out.println("Work directory retained: " + runRoot);
    }

    private static void writeOutputs(
            final Path outputDirectory,
            final JsonObject metadata,
            final byte[] vulkan,
            final byte[] opengl
    ) throws IOException {
        final Path root = outputDirectory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        final Path metadataPath = root.resolve("program.json");
        final Path vulkanPath = root.resolve("vulkan.spv");
        final Path openglPath = root.resolve("opengl.spv");
        preflight(metadataPath, vulkanPath, openglPath);
        writeAtomically(vulkanPath, vulkan);
        writeAtomically(openglPath, opengl);
        writeAtomically(metadataPath, GSON.toJson(metadata).getBytes(StandardCharsets.UTF_8));
    }

    private static void preflight(
            final Path metadataPath,
            final Path vulkanPath,
            final Path openglPath
    ) throws IOException {
        final boolean existingVulkan = Files.exists(vulkanPath);
        final boolean existingOpenGl = Files.exists(openglPath);
        for (Path artifact : new Path[]{vulkanPath, openglPath}) {
            if (Files.exists(artifact) && !Files.isRegularFile(artifact)) {
                throw new BakeException("Refusing to overwrite non-file shader output " + artifact);
            }
        }
        if (!Files.exists(metadataPath)) {
            if (existingVulkan || existingOpenGl) {
                throw new BakeException("Refusing to overwrite shader outputs without " + metadataPath);
            }
            return;
        }
        if (!Files.isRegularFile(metadataPath)) {
            throw new BakeException("Refusing to overwrite non-file shader metadata " + metadataPath);
        }
        final JsonObject existing;
        try {
            existing = JsonParser.parseString(
                    Files.readString(metadataPath, StandardCharsets.UTF_8)
            ).getAsJsonObject();
        } catch (RuntimeException error) {
            throw new BakeException("Refusing to overwrite unknown shader metadata " + metadataPath, error);
        }
        final String schema = ShaderManifest.string(existing, "schema");
        if (!FogIntegrationMetadataBuilder.SCHEMA.equals(schema)) {
            throw new BakeException("Refusing to overwrite shader metadata with unknown schema: " + metadataPath);
        }
    }

    private static void writeAtomically(final Path target, final byte[] bytes) throws IOException {
        final Path directory = target.getParent();
        Files.createDirectories(directory);
        final Path temporary = Files.createTempFile(directory, "." + target.getFileName() + "-", ".tmp");
        try {
            Files.write(temporary, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean containsHelp(final String[] args) {
        for (String argument : args) {
            if ("--help".equals(argument) || "-h".equals(argument)) {
                return true;
            }
        }
        return false;
    }
}
