package com.kltyton.lumenfx.api.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import org.jspecify.annotations.Nullable;

/** View identity covers both state extraction and geometry submission without changing the display-frame clock. */
public final class VfxRenderViews {
    private static final ThreadLocal<Boolean> SHADOW = new ThreadLocal<>();
    private VfxRenderViews() { }
    public static boolean isShadowView() { return Boolean.TRUE.equals(SHADOW.get()); }

    public static Scope shadow() {
        RenderSystem.assertOnRenderThread();
        Boolean previous = SHADOW.get(); SHADOW.set(true);
        return new Scope(previous);
    }

    public static final class Scope implements AutoCloseable {
        private final @Nullable Boolean previous;
        private boolean closed;
        private Scope(@Nullable Boolean previous) { this.previous = previous; }
        @Override public void close() {
            RenderSystem.assertOnRenderThread();
            if (closed) return;
            closed = true;
            if (previous == null) SHADOW.remove(); else SHADOW.set(previous);
        }
    }
}
