package com.kltyton.lumenfx.tools.texture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kltyton.lumenfx.client.resource.texture.virtual.VirtualTexturePages;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import net.minecraft.resources.Identifier;

final class NativeVirtualTextureBake {
    private static final String SUFFIX = ".lfvt.json";

    record Output(Path target, Path built, byte[] metadataDigest) {
        void readDirectory() throws IOException {
            try (var input = new BufferedInputStream(Files.newInputStream(built))) {
                if (VirtualTexturePages.read(input, metadataDigest).length() != Files.size(built))
                    throw new IOException("Native virtual texture payload length does not match its directory " + built);
            }
        }
    }

    private NativeVirtualTextureBake() { }

    static List<Output> stage(Path textures, Path job, JsonArray entries) throws IOException {
        List<Output> outputs = new ArrayList<>();
        for (Path sidecar : TextureBaker.sidecars(textures, SUFFIX)) {
            byte[] bytes = Files.readAllBytes(sidecar);
            var metadata = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            String asset = metadata.get("asset").getAsString();
            String name = sidecar.getFileName().toString();
            String outputName = name.substring(0, name.length() - SUFFIX.length()) + ".lfvt";
            var outputId = Identifier.parse(metadata.get("page_data").getAsString());
            if (metadata.get("format_version").getAsInt() != 1
                    || !outputId.getPath().substring(outputId.getPath().lastIndexOf('/') + 1).equals(outputName))
                throw new IOException("Invalid native virtual texture metadata " + sidecar);
            Path target = sidecar.resolveSibling(outputName);
            TextureBaker.requireOverwritable(target);
            Path built = job.resolve("virtual-texture-" + outputs.size() + ".lfvt");
            byte[] digest = TextureBaker.sha256Bytes(bytes);
            var entry = new JsonObject();
            entry.addProperty("asset", asset);
            entry.addProperty("output", built.toString());
            entry.addProperty("metadata_sha256", HexFormat.of().formatHex(digest));
            entry.addProperty("format", "");
            entry.addProperty("virtual_texture", true);
            entries.add(entry);
            outputs.add(new Output(target, built, digest));
        }
        return outputs;
    }
}
