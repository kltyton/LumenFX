package com.kltyton.lumenfx.client.resource.shader;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

/** Reads one offline metadata/binary contract during resource preparation. */
public final class ShaderResourceFiles {
    private ShaderResourceFiles() { }

    public static boolean exists(ResourceManager resources, Identifier path) {
        if (resources.getResource(path).isPresent()) {
            return true;
        }
        String rel = ComputeShaderPack.relativeComputePath(path);
        if (rel == null) {
            return false;
        }
        try {
            ComputeShaderPack pack = ComputeShaderPack.forResources(resources);
            return pack != null && pack.contains(rel);
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot read packed compute shaders for " + path, error);
        }
    }

    static byte[] readBytes(ResourceManager resources, Identifier path) throws IOException {
        var direct = resources.getResource(path);
        if (direct.isPresent()) {
            try (var input = direct.get().open()) {
                return input.readAllBytes();
            }
        }
        String rel = ComputeShaderPack.relativeComputePath(path);
        if (rel != null) {
            ComputeShaderPack pack = ComputeShaderPack.forResources(resources);
            if (pack != null) {
                byte[] packed = pack.getBytes(rel);
                if (packed != null) {
                    return packed;
                }
            }
        }
        throw new FileNotFoundException(path.toString());
    }

    static JsonObject json(ResourceManager resources, Identifier path) throws IOException {
        byte[] bytes = readBytes(resources, path);
        try (var reader = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    static byte[] artifact(ResourceManager resources, Identifier metadata, JsonObject artifact) throws IOException {
        String file = artifact.get("path").getAsString();
        if (!file.matches("[a-z0-9_.-]+\\.spv") || file.contains(".."))
            throw new IOException("Invalid native shader artifact path " + file);
        String folder = metadata.getPath().substring(0, metadata.getPath().lastIndexOf('/') + 1);
        Identifier path = Identifier.fromNamespaceAndPath(metadata.getNamespace(), folder + file);
        byte[] bytes = readBytes(resources, path);
        if (bytes.length != artifact.get("size").getAsInt() || bytes.length < 20 || (bytes.length & 3) != 0
                || ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt() != 0x07230203)
            throw new IOException("Invalid SPIR-V binary " + path);
        require(artifact.get("sha256").getAsString(), sha256(bytes), path.toString());
        return bytes;
    }

    static Identifier metadataPath(String pipeline, String relative) throws IOException {
        if (!relative.startsWith(pipeline + "/") || relative.contains("..") || relative.contains(":"))
            throw new IOException("Shader metadata escapes its pipeline root: " + relative);
        return Identifier.fromNamespaceAndPath("lumenfx", "shaders/compute/" + relative);
    }

    static void require(String expected, String actual, String path) throws IOException {
        if (!expected.equals(actual)) throw new IOException("Native shader contract mismatch at " + path);
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 is unavailable", error); }
    }
}
