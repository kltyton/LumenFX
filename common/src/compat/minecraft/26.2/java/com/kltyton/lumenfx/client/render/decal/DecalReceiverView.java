package com.kltyton.lumenfx.client.render.decal;

import java.nio.ByteBuffer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Render-thread normal transforms shared by material reception and direct-light reception. */
public final class DecalReceiverView {
    public static final int UNIFORM_BYTES = 128;
    private final Matrix4f worldToView = new Matrix4f();
    private final Matrix4f viewToWorld = new Matrix4f();

    public void write(Matrix4fc modelView, ByteBuffer data, int offset) {
        worldToView.set(modelView).invert().transpose().get(offset, data);
        viewToWorld.set(modelView).transpose().get(offset + 64, data);
    }
}
