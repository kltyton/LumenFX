package com.kltyton.lumenfx.tools.texture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import net.minecraft.resources.Identifier;

public final class TextureBaker {
    private static final String DOTNET = "C:/Program Files/dotnet/dotnet.exe";
    private static final String SIDECAR_SUFFIX = ".lftex.json";
    private static final int LFMI_MAGIC = 0x494d464c;
    private static final int LFTR_MAGIC = 0x5254464c;
    private static final int FORMAT_VERSION = 2;

    private record Sidecar(TextureMetadata metadata, byte[] digest, String asset, String sourceFormat, TextureProcessing processing) { }
    private record SourceAssets(Path directory, String oodleDataLibrary) { }

    private TextureBaker() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--native")) {
            NativeTextureBaker.run(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length != 5 && args.length != 7)
            throw new IllegalArgumentException("Expected: <texture-directory> <compiler-dll> <oodle-sdk-root> <unreal-source-root> <work-directory> [<unreal-packages> <oodle-data-library-or-dash>]");
        Path textureDirectory = directory(args[0], "texture-directory");
        Path compilerDll = file(args[1], "compiler-dll");
        Path oodleSdkRoot = directory(args[2], "oodle-sdk-root");
        Path unrealSourceRoot = directory(args[3], "unreal-source-root");
        Path workDirectory = workDirectory(args[4]);
        SourceAssets assets = args.length == 7 ? new SourceAssets(directory(args[5], "unreal-packages"),
                args[6].equals("-") ? "-" : file(args[6], "oodle-data-library").toString()) : null;
        SourceColorTables tables = SourceColorTables.load(unrealSourceRoot);
        for (Path sidecar : sidecars(textureDirectory))
            bake(sidecar, compilerDll, oodleSdkRoot, workDirectory, tables, assets);
    }

    private static void bake(Path sidecar, Path compilerDll, Path sdkRoot, Path workDirectory,
                             SourceColorTables tables, SourceAssets assets) throws Exception {
        String fileName = sidecar.getFileName().toString();
        String stem = fileName.substring(0, fileName.length() - SIDECAR_SUFFIX.length());
        Path png = sidecar.resolveSibling(stem + ".png");
        Path target = sidecar.resolveSibling(stem + ".lftx");
        Sidecar source = readSidecar(sidecar, tables);
        if (!Files.isRegularFile(png)) throw new IOException("Missing original PNG for " + sidecar + ": " + png);
        requireOverwritable(target);
        TextureMetadata metadata = source.metadata();
        byte[] pngBytes = Files.readAllBytes(png);
        verifyPng(png, pngBytes, metadata);
        TextureMipGenerator.Result generated = RawTextureSource.required(source.sourceFormat())
                ? decodeOriginalSource(source, compilerDll, workDirectory, assets)
                : decodeAndGenerate(png, pngBytes, metadata, tables, source.processing());
        List<TextureMipGenerator.Level> levels = generated.levels();
        if (generated.hasAlpha() == null) throw new IOException("Texture alpha prediction requires its color-space/red-channel build path: " + source.asset());

        Path sourceTemp = null;
        Path outputTemp = null;
        Throwable failure = null;
        try {
            sourceTemp = Files.createTempFile(workDirectory, "lumenfx-", ".lfmi");
            outputTemp = Files.createTempFile(workDirectory, "lumenfx-", ".lftx");
            writeMipStream(sourceTemp, levels, metadata, tables, source.digest(), generated.hasAlpha());
            runCompiler(compilerDll, sidecar, sourceTemp, outputTemp, sdkRoot);
            requireGeneratedLftr(outputTemp, levels.size(), source.digest());
            replaceTarget(target, outputTemp);
        } catch (Exception | Error error) {
            failure = error;
            throw error;
        } finally {
            try {
                deleteTemporaryFiles(sourceTemp, outputTemp);
            } catch (IOException error) {
                if (failure != null) failure.addSuppressed(error); else throw error;
            }
        }
    }

    private static Sidecar readSidecar(Path sidecar, SourceColorTables tables) throws IOException {
        byte[] bytes = Files.readAllBytes(sidecar);
        JsonObject json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        if (json.has("build_backend") && json.get("build_backend").getAsString().equals("unreal_native"))
            throw new IOException("Unreal source settings require the --native texture build entry: " + sidecar);
        TextureMetadata metadata = TextureMetadata.parse(json);
        return new Sidecar(metadata, sha256Bytes(bytes), json.has("asset") ? json.get("asset").getAsString() : "",
                json.has("source_pixel_format") ? json.get("source_pixel_format").getAsString() : "",
                new TextureProcessing(json.has("processing") && json.get("processing").isJsonObject() ? json.getAsJsonObject("processing") : new JsonObject(),
                        metadata.useNewMipFilter(), tables));
    }

    private static TextureMipGenerator.Result decodeOriginalSource(Sidecar source, Path compilerDll,
            Path work, SourceAssets assets) throws Exception {
        if (assets == null || source.asset().isEmpty())
            throw new IOException("High-precision texture requires its original package source: " + source.asset());
        Path raw = Files.createTempFile(work, "texture-source-", ".raw");
        Path description = raw.resolveSibling(raw.getFileName() + ".json");
        Throwable failure = null;
        try {
            var process = new ProcessBuilder(DOTNET, compilerDll.toString(), "texture-source", assets.directory().toString(),
                    source.asset(), raw.toString(), assets.oodleDataLibrary()).inheritIO().start();
            int exit = waitForCompiler(process);
            if (exit != 0) throw new IOException("texture-source exited with code " + exit + " for " + source.asset());
            TextureMetadata metadata = source.metadata();
            return TextureMipGenerator.generateLinearProcessed(metadata.width(), metadata.height(),
                    RawTextureSource.read(raw, metadata, source.sourceFormat()), metadata, source.processing());
        } catch (Exception | Error error) { failure = error; throw error; }
        finally {
            try { deleteTemporaryFiles(raw, description); }
            catch (IOException error) { if (failure != null) failure.addSuppressed(error); else throw error; }
        }
    }

    static void verifyPng(Path png, byte[] bytes, TextureMetadata metadata) throws IOException {
        if (!HexFormat.of().formatHex(sha256Bytes(bytes)).equalsIgnoreCase(metadata.pngSha256()))
            throw new IOException("PNG SHA-256 mismatch for " + png);
    }

    static void verifySource(Path sidecar, TextureMetadata metadata) throws IOException {
        String fileName = sidecar.getFileName().toString();
        String stem = fileName.substring(0, fileName.length() - SIDECAR_SUFFIX.length());
        var source = metadata.sourceInput();
        if (source == null) {
            Path png = sidecar.resolveSibling(stem + ".png");
            verifyPng(png, Files.readAllBytes(png), metadata);
            return;
        }
        Identifier id = Identifier.parse(source.resource());
        String name = id.getPath().substring(id.getPath().lastIndexOf('/') + 1);
        if (!name.equals(stem + ".lfts")) throw new IOException("Native texture source is not paired with its sidecar: " + sidecar);
        Path data = sidecar.resolveSibling(name);
        if (!HexFormat.of().formatHex(sha256Bytes(Files.readAllBytes(data))).equalsIgnoreCase(source.sha256()))
            throw new IOException("Native texture source SHA-256 mismatch for " + data);
    }

    private static TextureMipGenerator.Result decodeAndGenerate(Path png, byte[] pngBytes, TextureMetadata metadata,
                                                                     SourceColorTables tables, TextureProcessing processing) throws Exception {
        if (pngBytes.length == 0) throw new IOException("Empty PNG: " + png);
        ByteBuffer encoded = MemoryUtil.memAlloc(pngBytes.length);
        try {
            encoded.put(pngBytes).flip();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer width = stack.mallocInt(1);
                IntBuffer height = stack.mallocInt(1);
                IntBuffer channels = stack.mallocInt(1);
                ByteBuffer pixels = STBImage.stbi_load_from_memory(encoded, width, height, channels, 4);
                if (pixels == null)
                    throw new IOException("Unable to decode " + png + ": " + STBImage.stbi_failure_reason());
                try {
                    int w = width.get(0);
                    int h = height.get(0);
                    if (w != metadata.width() || h != metadata.height())
                        throw new IOException("PNG dimensions " + w + "x" + h + " do not match metadata "
                                + metadata.width() + "x" + metadata.height() + " for " + png);
                    int[] argb = new int[Math.multiplyExact(w, h)];
                    for (int i = 0; i < argb.length; i++) {
                        int offset = i * 4;
                        int r = pixels.get(offset) & 0xff;
                        int g = pixels.get(offset + 1) & 0xff;
                        int b = pixels.get(offset + 2) & 0xff;
                        int a = pixels.get(offset + 3) & 0xff;
                        argb[i] = (a << 24) | (r << 16) | (g << 8) | b;
                    }
                    return TextureMipGenerator.generateProcessed(w, h, argb, metadata,
                            tables.decodeTable(metadata.legacyGamma()), processing);
                } finally {
                    STBImage.stbi_image_free(pixels);
                }
            }
        } finally {
            MemoryUtil.memFree(encoded);
        }
    }

    private static void writeMipStream(Path file, List<TextureMipGenerator.Level> levels,
                                       TextureMetadata metadata, SourceColorTables tables,
                                       byte[] sourceDigest, boolean hasAlpha) throws IOException {
        boolean linear = linearOutput(metadata.cookedFormat(), metadata.srgb());
        long total = 16L + 32L;
        for (TextureMipGenerator.Level level : levels) {
            long size = (long) level.width() * level.height() * (linear ? 16L : 4L);
            if (size > Integer.MAX_VALUE || (total += 12L + size) > Integer.MAX_VALUE)
                throw new IOException("Mip stream exceeds the LFMI size limit");
        }
        ByteBuffer buffer = ByteBuffer.allocate((int) total).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(LFMI_MAGIC).putInt(4).put(sourceDigest).putInt(levels.size()).putInt((linear ? 1 : 0) | (hasAlpha ? 2 : 0));
        for (TextureMipGenerator.Level level : levels) {
            if (linear) {
                buffer.putInt(level.width()).putInt(level.height()).putInt(Math.multiplyExact(level.rgba().length, Float.BYTES));
                for (float value : level.rgba()) buffer.putFloat(value);
            } else {
                byte[] bgra = quantize(level, metadata.srgb(), tables);
                buffer.putInt(level.width()).putInt(level.height()).putInt(bgra.length).put(bgra);
            }
        }
        buffer.flip();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            while (buffer.hasRemaining()) channel.write(buffer);
        }
    }

    private static boolean linearOutput(String format, boolean srgb) {
        return switch (format) {
            case "G16", "PF_G16", "R16F", "PF_R16F", "R32F", "R32_FLOAT", "PF_R32_FLOAT",
                    "RGBA16F", "FloatRGBA", "PF_FloatRGBA", "RGBA32F", "A32B32G32R32F", "PF_A32B32G32R32F", "BC6H", "PF_BC6H" -> true;
            case "BC4", "PF_BC4", "BC5", "PF_BC5" -> !srgb;
            default -> false;
        };
    }

    private static byte[] quantize(TextureMipGenerator.Level level, boolean srgb, SourceColorTables tables) {
        int pixels = Math.multiplyExact(level.width(), level.height());
        byte[] bgra = new byte[pixels * 4];
        float[] rgba = level.rgba();
        for (int i = 0; i < pixels; i++) {
            int offset = i * 4;
            int r;
            int g;
            int b;
            if (srgb) {
                r = tables.toSrgb8(rgba[offset]);
                g = tables.toSrgb8(rgba[offset + 1]);
                b = tables.toSrgb8(rgba[offset + 2]);
            } else {
                r = SourceColorTables.quantizeRound(rgba[offset]);
                g = SourceColorTables.quantizeRound(rgba[offset + 1]);
                b = SourceColorTables.quantizeRound(rgba[offset + 2]);
            }
            bgra[offset] = (byte) b;
            bgra[offset + 1] = (byte) g;
            bgra[offset + 2] = (byte) r;
            bgra[offset + 3] = (byte) SourceColorTables.quantizeRound(rgba[offset + 3]);
        }
        return bgra;
    }

    private static void runCompiler(Path compilerDll, Path metadata, Path input, Path output,
                                    Path sdkRoot) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(DOTNET, compilerDll.toString(), "texture-codec",
                metadata.toString(), input.toString(), output.toString(), sdkRoot.toString());
        builder.inheritIO();
        int exit = waitForCompiler(builder.start());
        if (exit != 0) throw new IOException("texture-codec exited with code " + exit + " for " + metadata);
    }

    static int waitForCompiler(Process process) throws InterruptedException {
        try { return process.waitFor(); }
        catch (InterruptedException interrupted) {
            process.destroyForcibly();
            try { process.waitFor(); }
            catch (InterruptedException cleanup) { interrupted.addSuppressed(cleanup); }
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    static void requireOverwritable(Path target) throws IOException {
        if (!Files.exists(target)) return;
        if (!Files.isRegularFile(target))
            throw new IOException("Refusing to overwrite non-file texture output " + target);
        requireKnownTextureOutput(target);
    }

    static void replaceTarget(Path target, Path output) throws IOException {
        requireOverwritable(target);
        Files.copy(output, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void requireKnownTextureOutput(Path file) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            int magic = Integer.reverseBytes(input.readInt());
            int version = Integer.reverseBytes(input.readInt());
            boolean virtual = file.getFileName().toString().endsWith(".lfvt");
            if (virtual ? magic != 0x5456464c || version != 1
                    : magic != LFTR_MAGIC || (version != 1 && version != FORMAT_VERSION && version != 3 && version != 4))
                throw new IOException("Refusing to overwrite unknown texture output " + file);
        }
    }

    private static void requireGeneratedLftr(Path file, int expectedLevels, byte[] expectedDigest)
            throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            int magic = Integer.reverseBytes(input.readInt());
            int version = Integer.reverseBytes(input.readInt());
            if (magic != LFTR_MAGIC || version != FORMAT_VERSION)
                throw new IOException("Encoded texture output is not a v2 stream " + file);
            byte[] digest = new byte[32];
            input.readFully(digest);
            if (!MessageDigest.isEqual(digest, expectedDigest))
                throw new IOException("Encoded texture output metadata hash mismatch for " + file);
            int levels = Integer.reverseBytes(input.readInt());
            if (levels != expectedLevels)
                throw new IOException("Encoded mip count " + levels + " does not match " + expectedLevels + " for " + file);
        }
    }

    static byte[] sha256Bytes(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    static List<Path> sidecars(Path directory) throws IOException {
        List<Path> sidecars = sidecars(directory, SIDECAR_SUFFIX);
        if (sidecars.isEmpty()) throw new IOException("No " + SIDECAR_SUFFIX + " files in " + directory);
        return sidecars;
    }

    static List<Path> sidecars(Path directory, String suffix) throws IOException {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    static Path directory(String value, String name) throws IOException {
        Path path = requiredPath(value, name);
        if (!Files.isDirectory(path)) throw new IOException(name + " is not a directory: " + path);
        return path;
    }

    static Path file(String value, String name) throws IOException {
        Path path = requiredPath(value, name);
        if (!Files.isRegularFile(path)) throw new IOException(name + " is not a file: " + path);
        return path;
    }

    static Path workDirectory(String value) throws IOException {
        Path work = requiredPath(value, "work-directory");
        Files.createDirectories(work);
        if (!Files.isDirectory(work)) throw new IOException("work-directory is not a directory: " + work);
        return work;
    }

    private static Path requiredPath(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static void deleteTemporaryFiles(Path... paths) throws IOException {
        IOException failure = null;
        for (Path path : paths) {
            if (path == null) continue;
            try {
                Files.deleteIfExists(path);
            } catch (IOException error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }
}
