package com.kltyton.lumenfx.tools.shader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Reads shader blobs from the pinned UE source cache when they are not checked out. */
final class GitShaderSource {
    private final Path repository;
    private final String revision;
    private final Path temporaryDirectory;
    private final Map<String, byte[]> blobs = new HashMap<>();
    private Set<String> paths;

    GitShaderSource(Path repository, String revision, Path temporaryDirectory) {
        if (!Files.exists(repository.resolve(".git")) || revision == null || !revision.matches("[0-9a-fA-F]{40}"))
            throw new IllegalArgumentException("Invalid Unreal shader source repository or revision");
        this.repository = repository;
        this.revision = revision;
        this.temporaryDirectory = temporaryDirectory;
    }

    boolean contains(String path) throws IOException {
        if (paths == null) {
            String list = new String(run("ls-tree", "-r", "--full-tree", "-z", "--name-only", revision), StandardCharsets.UTF_8);
            paths = Arrays.stream(list.split("\\x00")).filter(value -> value.startsWith("Engine/Shaders/")
                    || value.startsWith("Engine/Plugins/FX/Niagara/Shaders/")
                    || value.startsWith("Engine/Plugins/Runtime/HairStrands/Shaders/")
                    || value.startsWith("Engine/Plugins/GaussianSplat/Shaders/")
                    || value.equals("Engine/Source/Runtime/Engine/Public/SceneView.h")
                    || value.equals("Engine/Source/Runtime/Engine/Public/SceneTexturesConfig.h")
                    || value.equals("Engine/Source/Runtime/Renderer/Private/Substrate/Substrate.h"))
                    .collect(Collectors.toUnmodifiableSet());
        }
        return paths.contains(path);
    }

    byte[] read(String path) throws IOException {
        byte[] cached = blobs.get(path);
        if (cached != null) return cached;
        if (!contains(path)) throw new IOException("Unreal shader source is not present at the pinned revision: " + path);
        byte[] source = run("show", revision + ":" + path);
        blobs.put(path, source);
        return source;
    }

    private byte[] run(String... arguments) throws IOException {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add("git"); command.add("-C"); command.add(repository.toString());
        command.addAll(Arrays.asList(arguments));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("TEMP", temporaryDirectory.toString());
        builder.environment().put("TMP", temporaryDirectory.toString());
        builder.environment().put("TMPDIR", temporaryDirectory.toString());
        Process process = builder.start();
        CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(() -> readStream(process.getInputStream()));
        CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(() -> readStream(process.getErrorStream()));
        try {
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Timed out reading the pinned Unreal shader source");
            }
            byte[] output = stdout.join();
            byte[] errors = stderr.join();
            if (process.exitValue() != 0)
                throw new IOException("Cannot read the pinned Unreal shader source (git exited " + process.exitValue()
                        + ", " + String.join(" ", arguments) + "): "
                        + new String(errors, StandardCharsets.UTF_8).replaceAll("https?://\\S+", "<remote>").strip());
            return output;
        } catch (InterruptedException error) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while reading Unreal shader source", error);
        } catch (java.util.concurrent.CompletionException error) {
            throw new IOException("Cannot read Unreal shader source process output", error.getCause());
        }
    }

    private static byte[] readStream(java.io.InputStream stream) {
        try (stream) { return stream.readAllBytes(); }
        catch (IOException error) { throw new java.io.UncheckedIOException(error); }
    }
}
