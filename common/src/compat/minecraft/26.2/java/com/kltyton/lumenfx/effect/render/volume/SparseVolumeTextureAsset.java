package com.kltyton.lumenfx.effect.render.volume;

import java.nio.ByteBuffer;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Immutable GPU upload data for a source sparse volume asset. */
public record SparseVolumeTextureAsset(Extent resolution, List<Frame> frames, Address addressX,
                                       Address addressY, Address addressZ, boolean directFrame) {
    public enum Address { WRAP, CLAMP, MIRROR }
    public record Extent(int x, int y, int z) {
        public Extent {
            if (x < 1 || y < 1 || z < 1) throw new IllegalArgumentException("Invalid sparse volume extent");
        }
        public int pixels() { return Math.multiplyExact(Math.multiplyExact(x, y), z); }
        public Extent mip(int level) { return new Extent(Math.max(1, x >> level), Math.max(1, y >> level), Math.max(1, z >> level)); }
    }
    public record Transform(Vec3 position, double qx, double qy, double qz, double qw, Vec3 scale) { }
    public record Atlas(String format, ByteBuffer pixels) {
        public Atlas { pixels = pixels.asReadOnlyBuffer(); }
        @Override public ByteBuffer pixels() { return pixels.duplicate(); }
    }
    public record Frame(int index, Extent pageExtent, int pageX, int pageY, int pageZ, List<ByteBuffer> mips,
                        Extent atlasExtent, @Nullable Atlas attributeA, @Nullable Atlas attributeB, Transform transform) {
        public Frame { mips = mips.stream().map(ByteBuffer::asReadOnlyBuffer).toList(); }
        public int mipCount() { return mips.size(); }
        @Override public List<ByteBuffer> mips() { return mips.stream().map(ByteBuffer::duplicate).toList(); }
    }
    public SparseVolumeTextureAsset {
        frames = List.copyOf(frames);
        if (frames.isEmpty() || directFrame && frames.size() != 1) throw new IllegalArgumentException("Invalid sparse volume animation");
    }
}
