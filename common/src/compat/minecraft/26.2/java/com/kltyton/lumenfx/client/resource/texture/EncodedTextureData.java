package com.kltyton.lumenfx.client.resource.texture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Encoded mip data shared by resource loading and the offline texture producer. */
public record EncodedTextureData(TextureMetadata metadata, List<ByteBuffer> mips, int volumeDepth) {
    public EncodedTextureData(TextureMetadata metadata, List<ByteBuffer> mips) { this(metadata, mips, 0); }
    public EncodedTextureData { mips = List.copyOf(mips); }

    public static EncodedTextureData read(ByteBuffer input, TextureMetadata source, byte[] metadataDigest) {
        return read(input, source, metadataDigest, 1);
    }

    public static EncodedTextureData read(ByteBuffer input, TextureMetadata source, byte[] metadataDigest, int layers) {
        return read(input, source, metadataDigest, layers, false);
    }

    public static EncodedTextureData read(ByteBuffer input, TextureMetadata source, byte[] metadataDigest, int layers, boolean volume) {
        if (layers < 1) throw new IllegalArgumentException("Invalid encoded texture layer count");
        ByteBuffer data = input.slice().order(ByteOrder.LITTLE_ENDIAN);
        if (data.getInt() != 0x5254464c) throw new IllegalArgumentException("Invalid encoded texture stream");
        int version = data.getInt();
        if (volume ? version != 4 || layers != 1 : version != 2 && version != 3)
            throw new IllegalArgumentException("Unsupported encoded texture version " + version);
        byte[] digest = new byte[32];
        data.get(digest);
        if (!MessageDigest.isEqual(digest, metadataDigest))
            throw new IllegalArgumentException("Encoded texture metadata hash mismatch");
        int count = data.getInt();
        if (count < 1 || count > 31) throw new IllegalArgumentException("Invalid encoded texture mip count " + count);
        TextureMetadata metadata = source;
        String formatName = source.cookedFormat();
        boolean srgb = source.srgb();
        TextureMetadata.FilterMode minMag = source.minMag(), mip = source.mip();
        int anisotropy = source.maxAnisotropy();
        if (version >= 3) {
            int nameLength = data.getInt();
            if (nameLength < 1 || nameLength > 64 || nameLength > data.remaining())
                throw new IllegalArgumentException("Invalid native texture format name");
            byte[] name = new byte[nameLength];
            data.get(name);
            formatName = new String(name, StandardCharsets.US_ASCII);
            int flags = data.getInt();
            if ((flags & ~1) != 0) throw new IllegalArgumentException("Invalid native texture flags");
            srgb = (flags & 1) != 0;
            minMag = filter(data.getInt());
            mip = filter(data.getInt());
            anisotropy = data.getInt();
            if (mip == TextureMetadata.FilterMode.ANISO || anisotropy < 1)
                throw new IllegalArgumentException("Invalid native texture sampler");
        } else {
            if (metadata.mipMode() == TextureMetadata.MipMode.NATIVE_BUILD)
                throw new IllegalArgumentException("Native mip settings require a native encoded texture stream");
            int expected = metadata.mipMode() == TextureMetadata.MipMode.NO_MIPMAPS ? 1
                    : 32 - Integer.numberOfLeadingZeros(Math.max(metadata.width(), metadata.height()));
            if (count != expected) throw new IllegalArgumentException("Encoded mip count differs from source settings");
        }
        TextureStorageFormat format = TextureStorageFormat.from(formatName);
        List<ByteBuffer> mips = new ArrayList<>(count);
        int baseWidth = source.width(), baseHeight = source.height();
        int baseDepth = 0;
        for (int level = 0; level < count; level++) {
            int width = data.getInt(), height = data.getInt();
            int depth = volume ? data.getInt() : 1;
            int length = data.getInt();
            if (version >= 3 && level == 0) {
                baseWidth = width; baseHeight = height;
                if (volume) baseDepth = depth;
                if (width < 1 || height < 1 || depth < 1
                        || count > 32 - Integer.numberOfLeadingZeros(Math.max(depth, Math.max(width, height))))
                    throw new IllegalArgumentException("Invalid native texture dimensions or mip count");
                metadata = source.withStorage(width, height, srgb, formatName, minMag, mip, anisotropy);
            }
            if (width != Math.max(1, baseWidth >> level) || height != Math.max(1, baseHeight >> level)
                    || depth != Math.max(1, baseDepth >> level)
                    || length != Math.multiplyExact(Math.multiplyExact(format.byteSize(width, height), layers), depth)
                    || length > data.remaining())
                throw new IllegalArgumentException("Encoded mip layout differs at level " + level);
            ByteBuffer pixels = ByteBuffer.allocateDirect(length);
            pixels.put(data.slice(data.position(), length)).flip();
            data.position(data.position() + length);
            mips.add(pixels);
        }
        if (data.hasRemaining()) throw new IllegalArgumentException("Trailing encoded texture data");
        return new EncodedTextureData(metadata, mips, baseDepth);
    }

    private static TextureMetadata.FilterMode filter(int value) {
        return switch (value) {
            case 0 -> TextureMetadata.FilterMode.POINT;
            case 1 -> TextureMetadata.FilterMode.LINEAR;
            case 2 -> TextureMetadata.FilterMode.ANISO;
            default -> throw new IllegalArgumentException("Invalid native sampler filter " + value);
        };
    }
}
