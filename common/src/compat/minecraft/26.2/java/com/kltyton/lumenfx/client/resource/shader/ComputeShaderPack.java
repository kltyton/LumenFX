package com.kltyton.lumenfx.client.resource.shader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;

/**
 * Lossless packed archive reader for built-in {@code assets/lumenfx/shaders/compute/**} entries.
 * Supports exact aliases, sparse byte patches, and block-copy deltas inside a single Deflate stream,
 * while always allowing external resource packs to override individual shader files first.
 */
public final class ComputeShaderPack {
    public static final Identifier PACK_ID = Identifier.fromNamespaceAndPath("lumenfx", "shaders/compute.lfpack");
    public static final String PACK_RESOURCE_PATH = "/assets/lumenfx/shaders/compute.lfpack";
    private static final String COMPUTE_PREFIX = "shaders/compute/";
    private static final int MAGIC = 0x4C46504B; // "LFPK"
    private static final int VERSION = 1;
    private static final int MODE_RAW = 0;
    private static final int MODE_ALIAS = 1;
    private static final int MODE_SPARSE_PATCH = 2;
    private static final int MODE_COPY_PATCH = 3;

    private static final Map<ResourceManager, ComputeShaderPack> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile @Nullable ComputeShaderPack classpathCache;

    private final Map<String, byte[]> entries;

    private ComputeShaderPack(Map<String, byte[]> entries) {
        this.entries = entries;
    }

    public static @Nullable ComputeShaderPack forResources(ResourceManager resources) throws IOException {
        ComputeShaderPack cached = CACHE.get(resources);
        if (cached != null) {
            return cached;
        }
        var resource = resources.getResource(PACK_ID);
        if (resource.isEmpty()) {
            return null;
        }
        byte[] raw;
        try (InputStream in = resource.get().open()) {
            raw = in.readAllBytes();
        }
        ComputeShaderPack decoded = decode(raw);
        CACHE.put(resources, decoded);
        return decoded;
    }

    public static @Nullable ComputeShaderPack fromClasspath() throws IOException {
        ComputeShaderPack cached = classpathCache;
        if (cached != null) {
            return cached;
        }
        try (InputStream in = ComputeShaderPack.class.getResourceAsStream(PACK_RESOURCE_PATH)) {
            if (in == null) {
                return null;
            }
            ComputeShaderPack decoded = decode(in.readAllBytes());
            classpathCache = decoded;
            return decoded;
        }
    }

    public boolean contains(String relativePath) {
        return entries.containsKey(relativePath);
    }

    public byte @Nullable [] getBytes(String relativePath) {
        return entries.get(relativePath);
    }

    public int size() {
        return entries.size();
    }

    public static @Nullable String relativeComputePath(Identifier path) {
        if (!"lumenfx".equals(path.getNamespace())) {
            return null;
        }
        String p = path.getPath();
        return p.startsWith(COMPUTE_PREFIX) ? p.substring(COMPUTE_PREFIX.length()) : null;
    }

    public static ComputeShaderPack decode(byte[] packed) throws IOException {
        DataInputStream header = new DataInputStream(new ByteArrayInputStream(packed));
        int magic = header.readInt();
        if (magic != MAGIC) {
            throw new IOException("Invalid compute shader pack magic: 0x" + Integer.toHexString(magic));
        }
        int version = header.readInt();
        if (version != VERSION) {
            throw new IOException("Unsupported compute shader pack version: " + version);
        }
        int uncompressedSize = header.readInt();
        if (uncompressedSize < 0) {
            throw new IOException("Invalid compute shader pack size: " + uncompressedSize);
        }
        byte[] inflated = inflate(packed, 12, packed.length - 12, uncompressedSize);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(inflated));
        int count = in.readInt();
        if (count < 0) {
            throw new IOException("Invalid compute shader pack entry count: " + count);
        }
        byte[][] indexed = new byte[count][];
        Map<String, byte[]> map = new HashMap<>(Math.max(16, (int) (count / 0.75f) + 1));
        for (int i = 0; i < count; i++) {
            String path = in.readUTF();
            int mode = in.readUnsignedByte();
            byte[] data = switch (mode) {
                case MODE_RAW -> {
                    int len = in.readInt();
                    byte[] buf = new byte[len];
                    in.readFully(buf);
                    yield buf;
                }
                case MODE_ALIAS -> {
                    int baseIdx = in.readInt();
                    yield requireBase(indexed, baseIdx, i, path);
                }
                case MODE_SPARSE_PATCH -> {
                    int baseIdx = in.readInt();
                    byte[] base = requireBase(indexed, baseIdx, i, path);
                    byte[] out = base.clone();
                    int runs = in.readInt();
                    for (int r = 0; r < runs; r++) {
                        int offset = in.readInt();
                        int len = in.readInt();
                        if (offset < 0 || len < 0 || offset + len > out.length) {
                            throw new IOException("Out-of-bounds sparse patch for " + path);
                        }
                        in.readFully(out, offset, len);
                    }
                    yield out;
                }
                case MODE_COPY_PATCH -> {
                    int baseIdx = in.readInt();
                    byte[] base = requireBase(indexed, baseIdx, i, path);
                    int outLen = in.readInt();
                    int opCount = in.readInt();
                    byte[] out = new byte[outLen];
                    int dst = 0;
                    for (int op = 0; op < opCount; op++) {
                        int tag = in.readInt();
                        if (tag >= 0) {
                            int srcOff = in.readInt();
                            if (srcOff < 0 || tag > base.length - srcOff || tag > outLen - dst) {
                                throw new IOException("Out-of-bounds copy patch for " + path);
                            }
                            System.arraycopy(base, srcOff, out, dst, tag);
                            dst += tag;
                        } else {
                            int litLen = -tag;
                            if (litLen > outLen - dst) {
                                throw new IOException("Out-of-bounds literal patch for " + path);
                            }
                            in.readFully(out, dst, litLen);
                            dst += litLen;
                        }
                    }
                    if (dst != outLen) {
                        throw new IOException("Truncated copy patch for " + path + ": " + dst + " != " + outLen);
                    }
                    yield out;
                }
                default -> throw new IOException("Unknown compute shader pack mode " + mode + " for " + path);
            };
            indexed[i] = data;
            map.put(path, data);
        }
        return new ComputeShaderPack(Map.copyOf(map));
    }

    private static byte[] requireBase(byte[][] indexed, int baseIdx, int currentIdx, String path) throws IOException {
        if (baseIdx < 0 || baseIdx >= currentIdx || indexed[baseIdx] == null) {
            throw new IOException("Invalid base entry index " + baseIdx + " for " + path);
        }
        return indexed[baseIdx];
    }

    private static byte[] inflate(byte[] input, int offset, int length, int expectedSize) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(input, offset, length);
            byte[] out = new byte[expectedSize];
            int filled = 0;
            while (!inflater.finished()) {
                if (filled == out.length) {
                    ByteArrayOutputStream extra = new ByteArrayOutputStream(out.length + 1024);
                    extra.write(out);
                    byte[] scratch = new byte[4096];
                    while (!inflater.finished()) {
                        int n = inflater.inflate(scratch);
                        if (n == 0) {
                            if (inflater.needsInput()) {
                                throw new EOFException("Truncated compute shader pack stream");
                            }
                            break;
                        }
                        extra.write(scratch, 0, n);
                    }
                    return extra.toByteArray();
                }
                int n = inflater.inflate(out, filled, out.length - filled);
                if (n == 0) {
                    if (inflater.needsInput()) {
                        throw new EOFException("Truncated compute shader pack stream");
                    }
                    break;
                }
                filled += n;
            }
            if (filled != expectedSize) {
                throw new IOException("Compute shader pack size mismatch: " + filled + " != " + expectedSize);
            }
            return out;
        } catch (DataFormatException error) {
            throw new IOException("Corrupt compute shader pack stream", error);
        } finally {
            inflater.end();
        }
    }
}
