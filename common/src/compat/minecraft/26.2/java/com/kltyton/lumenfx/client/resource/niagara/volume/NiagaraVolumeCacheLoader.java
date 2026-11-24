package com.kltyton.lumenfx.client.resource.niagara.volume;

import com.kltyton.lumenfx.effect.niagara.interfaces.volume.NiagaraVolumeCacheAsset;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

public final class NiagaraVolumeCacheLoader {
    private NiagaraVolumeCacheLoader() { }

    public static NiagaraVolumeCacheAsset load(ResourceManager resources, Identifier id) {
        Identifier path = Identifier.fromNamespaceAndPath(id.getNamespace(), "lumenfx/volume_caches/" + id.getPath() + ".lfvc");
        try (var input = new DataInputStream(new BufferedInputStream(resources.getResourceOrThrow(path).open()))) {
            if (input.readInt() != 0x4c465643 || word(input) != 1) throw new IOException("Unsupported LFVC volume format");
            int width = word(input), height = word(input), depth = word(input), count = word(input);
            if (count <= 0) throw new IOException("Empty Niagara Volume Cache");
            var frames = new HashMap<Integer, NiagaraVolumeCacheAsset.Frame>();
            for (int index = 0; index < count; index++) {
                int frame = word(input), size = word(input);
                if (size <= 0) throw new IOException("Invalid Niagara Volume Cache frame size");
                byte[] compressed = input.readNBytes(size);
                if (compressed.length != size) throw new EOFException("Incomplete Niagara Volume Cache frame");
                if (frames.putIfAbsent(frame, new NiagaraVolumeCacheAsset.Frame(compressed)) != null)
                    throw new IOException("Duplicate Niagara Volume Cache frame " + frame);
            }
            if (input.read() != -1) throw new IOException("Trailing Niagara Volume Cache data");
            return new NiagaraVolumeCacheAsset(width, height, depth, frames);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not load Niagara Volume Cache " + id, failure);
        }
    }

    private static int word(DataInputStream input) throws IOException { return Integer.reverseBytes(input.readInt()); }
}
