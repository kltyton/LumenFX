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
import java.util.Map;

final class ShaderOutputWriter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String PROGRAM_SCHEMA = "lumenfx.shaders.program.v1";
    private static final String INDEX_SCHEMA = "lumenfx.shaders.index.v1";

    private final Path root;
    private final String pipeline;

    ShaderOutputWriter(final Path outputDirectory, final String pipeline) {
        this.pipeline = pipeline;
        this.root = outputDirectory.toAbsolutePath().normalize()
                .resolve(resourceSegment(pipeline));
    }

    Path root() {
        return this.root;
    }

    Path programDirectory(final String pass, final String variant) {
        return this.root.resolve(resourceSegment(pass))
                .resolve(resourceSegment(variant));
    }

    void preflight(final String pass, final String variant, final Map<String, byte[]> artifacts) throws IOException {
        final Path directory = programDirectory(pass, variant);
        final Path metadata = directory.resolve("program.json");
        if (Files.exists(metadata)) {
            final JsonObject existing = parse(metadata, "program");
            if (!PROGRAM_SCHEMA.equals(ShaderManifest.string(existing, "schema"))
                    || !pass.equals(ShaderManifest.string(existing, "pass"))) {
                throw new BakeException("Refusing to overwrite unknown shader program " + metadata);
            }
        }
        for (String name : artifacts.keySet()) {
            final Path target = directory.resolve(name).normalize();
            if (!target.startsWith(directory)) {
                throw new BakeException("Artifact escapes program directory: " + name);
            }
            if (Files.exists(target) && !Files.isRegularFile(target)) {
                throw new BakeException("Refusing to overwrite non-file artifact " + target);
            }
            if (Files.exists(target) && !Files.exists(metadata)) {
                throw new BakeException("Refusing to overwrite " + target + " without program.json");
            }
        }
    }

    Path writeProgram(
            final String pass,
            final String variant,
            final JsonObject metadata,
            final Map<String, byte[]> artifacts
    ) {
        final Path directory = programDirectory(pass, variant);
        try {
            Files.createDirectories(directory);
            preflight(pass, variant, artifacts);
            for (Map.Entry<String, byte[]> artifact : artifacts.entrySet()) {
                writeAtomically(directory.resolve(artifact.getKey()), artifact.getValue());
            }
            final Path metadataPath = directory.resolve("program.json");
            writeAtomically(metadataPath, GSON.toJson(metadata).getBytes(StandardCharsets.UTF_8));
            return metadataPath;
        } catch (IOException error) {
            throw new BakeException("Unable to write shader program " + directory, error);
        }
    }

    void writeIndex(final JsonObject index) {
        final Path path = this.root.resolve("index.json");
        try {
            Files.createDirectories(this.root);
            if (Files.exists(path)) {
                final JsonObject existing = parse(path, "index");
                if (!INDEX_SCHEMA.equals(ShaderManifest.string(existing, "schema"))) {
                    throw new BakeException("Refusing to overwrite unknown shader index " + path);
                }
            }
            writeAtomically(path, GSON.toJson(index).getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new BakeException("Unable to write shader index " + path, error);
        }
    }

    private static JsonObject parse(final Path path, final String kind) throws IOException {
        try {
            return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException error) {
            throw new BakeException("Refusing to overwrite unknown shader " + kind + " " + path, error);
        }
    }

    static String resourceSegment(final String value) {
        if (value == null || value.isBlank()) {
            throw new BakeException("Empty shader resource path segment");
        }
        final String lowered = value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        if (lowered.isEmpty() || ".".equals(lowered) || "..".equals(lowered)) {
            throw new BakeException("Unsafe shader resource path segment: " + value);
        }
        return lowered;
    }

    static void writeAtomically(final Path target, final byte[] bytes) throws IOException {
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

    static String sha256(final byte[] bytes) {
        try {
            final byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
