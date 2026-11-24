package com.kltyton.lumenfx.client.resource.texture.virtual;

import com.google.gson.JsonParser;
import com.kltyton.lumenfx.client.resource.texture.virtual.VirtualTexturePages.Page;
import com.kltyton.lumenfx.client.resource.texture.virtual.VirtualTexturePages.Range;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/** Resource-pack pages are read off-thread and coalesced by file order. */
public record VirtualTextureTileSource(Identifier id, VirtualTexturePages directory, Resource resource) {
    record Request(Page page, int layer, Range range) { }
    record Tile(Page page, int layer, byte[] data) { }

    public static VirtualTextureTileSource read(ResourceManager manager, Identifier id) throws IOException {
        byte[] bytes;
        try (var input = manager.getResourceOrThrow(id).open()) {
            bytes = input.readAllBytes();
        }
        var metadata = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        if (metadata.get("format_version").getAsInt() != 1) throw new IOException("Unknown virtual texture manifest version " + id);
        var payload = Identifier.parse(metadata.get("page_data").getAsString());
        var resource = manager.getResourceOrThrow(payload);
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        try (var stream = new BufferedInputStream(resource.open())) {
            return new VirtualTextureTileSource(id, VirtualTexturePages.read(stream, digest), resource);
        }
    }

    List<Tile> readTiles(List<Request> requested, BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean()) return List.of();
        var requests = new ArrayList<>(requested);
        requests.sort(Comparator.comparingLong(request -> request.range().offset()));
        List<Tile> result = new ArrayList<>(requests.size());
        try (var input = new BufferedInputStream(resource.open())) {
            long position = 0;
            for (var request : requests) {
                if (cancelled.getAsBoolean()) return List.of();
                var range = request.range();
                input.skipNBytes(range.offset() - position);
                byte[] bytes = input.readNBytes(range.bytes());
                if (bytes.length != range.bytes()) throw new EOFException("Truncated tile " + request.page() + " of " + id);
                result.add(new Tile(request.page(), request.layer(), bytes));
                position = range.offset() + bytes.length;
            }
        }
        return result;
    }
}
