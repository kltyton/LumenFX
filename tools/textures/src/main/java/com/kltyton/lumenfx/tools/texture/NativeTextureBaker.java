package com.kltyton.lumenfx.tools.texture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kltyton.lumenfx.client.resource.texture.EncodedTextureData;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

final class NativeTextureBaker {
    private record Output(Path target, Path built, TextureMetadata metadata, byte[] digest, int layers, boolean volume) { }

    private NativeTextureBaker() { }

    static void run(String[] args) throws Exception {
        if (args.length < 7 || args.length > 8) throw new IllegalArgumentException("Expected --native <texture-directory> <native-executable>"
                + " <source-project> <target-platform> <content-mounts-json> <work-directory> <cache-directory> [encode-speed]");
        Path textures = TextureBaker.directory(args[0], "texture-directory");
        Path executable = TextureBaker.file(args[1], "native-executable");
        Path sourceProject = TextureBaker.file(args[2], "source-project");
        if (!sourceProject.getFileName().toString().endsWith(".uproject"))
            throw new IllegalArgumentException("source-project must be an Unreal project descriptor");
        String platform = args[3];
        if (!platform.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("Invalid target-platform");
        String encodeSpeed = args.length == 8 ? args[7] : null;
        if (encodeSpeed != null && !Set.of("Final", "FinalIfAvailable", "Fast").contains(encodeSpeed))
            throw new IllegalArgumentException("Invalid Unreal texture encode speed: " + encodeSpeed);
        JsonObject roots = roots(TextureBaker.file(args[4], "content-mounts-json"));
        Path work = TextureBaker.workDirectory(args[5]);
        Path cache = TextureBaker.workDirectory(args[6]);
        Path job = Files.createTempDirectory(work, "texture-build-");
        Throwable failure = null;
        try {
            Path project = stageConfiguration(sourceProject, job.resolve("project"));
            List<Output> outputs = new ArrayList<>();
            JsonObject request = new JsonObject();
            request.addProperty("platform", platform);
            request.add("content_roots", roots);
            JsonArray entries = new JsonArray();
            for (Path sidecar : TextureBaker.sidecars(textures, ".lftex.json")) {
                byte[] bytes = Files.readAllBytes(sidecar);
                JsonObject json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                String sourceType = json.has("source") ? json.get("source").getAsString() : "";
                boolean array = sourceType.equals("unreal-texture2d-array-source");
                boolean cube = sourceType.equals("unreal-texture-cube-source");
                boolean cubeArray = sourceType.equals("unreal-texture-cube-array-source");
                boolean volume = sourceType.equals("unreal-texture-volume-source");
                if (!array && !cube && !cubeArray && !volume && !sourceType.equals("unreal-texture2d") && !sourceType.equals("unreal-texture2d-source"))
                    throw new IOException("Native texture build requires Unreal source metadata: " + sidecar);
                int layers = json.has("array_layers") ? json.get("array_layers").getAsInt() : 0;
                if (array ? layers < 1 : json.has("array_layers"))
                    throw new IOException("Invalid native texture array layer metadata: " + sidecar);
                int cubes = json.has("cube_array_layers") ? json.get("cube_array_layers").getAsBigDecimal().intValueExact() : 0;
                if (cubeArray ? cubes < 1 || cubes > Integer.MAX_VALUE / 6 : json.has("cube_array_layers"))
                    throw new IOException("Invalid native cubemap array layer metadata: " + sidecar);
                TextureMetadata metadata = TextureMetadata.parse(json);
                String stem = sidecar.getFileName().toString().replaceFirst("\\.lftex\\.json$", "");
                Path target = sidecar.resolveSibling(stem + ".lftx");
                TextureBaker.verifySource(sidecar, metadata);
                TextureBaker.requireOverwritable(target);
                byte[] digest = TextureBaker.sha256Bytes(bytes);
                Path built = job.resolve("texture-" + outputs.size() + ".lftx");
                JsonObject entry = new JsonObject();
                entry.addProperty("asset", json.get("asset").getAsString());
                entry.addProperty("output", built.toString());
                entry.addProperty("metadata_sha256", HexFormat.of().formatHex(digest));
                entry.addProperty("format", metadata.cookedFormat());
                if (array) entry.addProperty("array_layers", layers);
                if (cube) entry.addProperty("cube", true);
                if (cubeArray) entry.addProperty("cube_array_layers", cubes);
                if (volume) entry.addProperty("volume", true);
                entries.add(entry);
                outputs.add(new Output(target, built, metadata, digest, cubeArray ? cubes * 6 : cube ? 6 : Math.max(1, layers), volume));
            }
            var virtualOutputs = NativeVirtualTextureBake.stage(textures, job, entries);
            if (entries.isEmpty()) throw new IOException("No native texture metadata in " + textures);
            request.add("textures", entries);
            Path manifest = job.resolve("textures.json");
            Files.writeString(manifest, request.toString() + "\n", StandardCharsets.UTF_8);
            run(executable, project, manifest, job, work, cache, encodeSpeed);
            for (Output output : outputs) {
                ByteBuffer data = ByteBuffer.wrap(Files.readAllBytes(output.built())).order(ByteOrder.LITTLE_ENDIAN);
                if (data.remaining() < 8 || data.getInt(4) != (output.volume() ? 4 : 3))
                    throw new IOException("Native texture build returned a different stream version: " + output.built());
                EncodedTextureData.read(data, output.metadata(), output.digest(), output.layers(), output.volume());
            }
            for (var output : virtualOutputs) output.readDirectory();
            for (Output output : outputs) TextureBaker.replaceTarget(output.target(), output.built());
            for (var output : virtualOutputs) TextureBaker.replaceTarget(output.target(), output.built());
        } catch (Exception | Error error) {
            failure = error;
            throw error;
        } finally {
            try { deleteJob(job); }
            catch (IOException error) { if (failure != null) failure.addSuppressed(error); else throw error; }
        }
    }

    private static JsonObject roots(Path path) throws IOException {
        JsonObject roots = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        if (roots.isEmpty()) throw new IllegalArgumentException("At least one source content mount is required");
        for (String key : roots.keySet()) {
            Path directory = Path.of(roots.get(key).getAsString());
            if (!key.matches("/[A-Za-z0-9_]+(?:/[A-Za-z0-9_]+)*/")
                    || !directory.isAbsolute() || !Files.isDirectory(directory))
                throw new IllegalArgumentException("Invalid source content mount " + key);
            roots.addProperty(key, directory.normalize().toString());
        }
        return roots;
    }

    private static Path stageConfiguration(Path source, Path projectDirectory) throws IOException {
        Files.createDirectories(projectDirectory);
        Path config = source.getParent().resolve("Config");
        if (Files.isDirectory(config)) {
            try (Stream<Path> files = Files.walk(config)) {
                for (Path input : files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".ini")).toList()) {
                    Path output = projectDirectory.resolve("Config").resolve(config.relativize(input));
                    Files.createDirectories(output.getParent());
                    Files.copy(input, output);
                }
            }
        }
        Path project = projectDirectory.resolve(source.getFileName());
        Files.writeString(project, "{\"FileVersion\":3,\"Modules\":[{\"Name\":\"LumenFxTextureBuild\","
                + "\"Type\":\"Program\",\"LoadingPhase\":\"Default\"}]}\n", StandardCharsets.UTF_8);
        return project;
    }

    private static void run(Path executable, Path project, Path manifest, Path job, Path work, Path cache,
                            @Nullable String encodeSpeed)
            throws IOException, InterruptedException {
        Path stdout = Files.createTempFile(work, "texture-build-", ".log");
        Path engineLog = stdout.resolveSibling(stdout.getFileName() + ".engine.log");
        ProcessBuilder builder = new ProcessBuilder(executable.toString(), "-Project=" + project,
                "-NullRHI", "-Unattended", "-NoSound", "-NoSplash", "-NoLogWindow", "-stdout", "-FullStdOutLogOutput",
                "-abslog=" + engineLog, "-UserDir=" + job.resolve("user"), "-DDC=(Local)",
                "-LocalDataCachePath=" + cache, "-input=" + manifest);
        if (encodeSpeed != null) builder.command().add("-ForceTextureEncodeSpeed=" + encodeSpeed);
        builder.directory(job.toFile()).redirectErrorStream(true).redirectOutput(stdout.toFile());
        builder.environment().put("TEMP", job.toString());
        builder.environment().put("TMP", job.toString());
        builder.environment().put("TMPDIR", job.toString());
        builder.environment().put("UE-LocalDataCachePath", cache.toString());
        int exit = TextureBaker.waitForCompiler(builder.start());
        if (exit != 0) throw new IOException("Native texture build exited with code " + exit + ": " + stdout);
        String messages = Files.readString(stdout, StandardCharsets.UTF_8);
        if (messages.contains("Warning:") || messages.contains("Error:"))
            System.err.println("Native texture build diagnostics: " + stdout);
        else {
            Files.deleteIfExists(stdout);
            Files.deleteIfExists(engineLog);
        }
    }

    private static void deleteJob(Path job) throws IOException {
        IOException failure = null;
        try (Stream<Path> paths = Files.walk(job)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try { Files.delete(path); }
                catch (IOException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            }
        }
        if (failure != null) throw failure;
    }
}
