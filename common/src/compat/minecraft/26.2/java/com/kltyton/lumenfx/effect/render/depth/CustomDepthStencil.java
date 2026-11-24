package com.kltyton.lumenfx.effect.render.depth;

import java.util.Objects;

/** Component flags used by the source CustomDepth pass. */
public record CustomDepthStencil(boolean enabled, int stencil, WriteMask writeMask) {
    public static final CustomDepthStencil DISABLED = new CustomDepthStencil(false, 0, WriteMask.DEFAULT);

    public enum WriteMask {
        DEFAULT(255, false), ALL(255, true),
        BIT_1(1, true), BIT_2(2, true), BIT_4(4, true), BIT_8(8, true),
        BIT_16(16, true), BIT_32(32, true), BIT_64(64, true), BIT_128(128, true);

        private final int bits;
        private final boolean ignoresDepth;

        WriteMask(int bits, boolean ignoresDepth) { this.bits = bits; this.ignoresDepth = ignoresDepth; }
        public int bits() { return bits; }
        public boolean ignoresDepth() { return ignoresDepth; }
    }

    public CustomDepthStencil {
        if ((stencil & ~255) != 0) throw new IllegalArgumentException("Custom stencil must be in [0, 255]");
        Objects.requireNonNull(writeMask, "writeMask");
    }
}
