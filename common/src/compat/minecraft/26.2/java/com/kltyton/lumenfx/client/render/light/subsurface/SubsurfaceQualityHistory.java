package com.kltyton.lumenfx.client.render.light.subsurface;

import com.kltyton.lumenfx.client.render.texture.OwnedColorTarget;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

final class SubsurfaceQualityHistory implements AutoCloseable {
    record View(Object world, Object cameraOwner, long cut, long sequence, boolean paused, Vec3 camera,
                Matrix4fc worldToClip, float jitterX, float jitterY) { }

    record Frame(View view, int slot, @Nullable GpuTextureView previous, GpuTextureView output,
                 Matrix4fc previousClip, float jitterDeltaX, float jitterDeltaY) { }

    private final OwnedColorTarget[] textures = new OwnedColorTarget[2];
    private @Nullable View previousView;
    private long previousSequence = Long.MIN_VALUE;
    private int previousSlot;

    Frame begin(View current, int width, int height) {
        View previous = previousView;
        var prior = textures[previousSlot];
        boolean valid = previous != null && prior != null && prior.width == width && prior.height == height
                && previous.world() == current.world() && previous.cameraOwner() == current.cameraOwner()
                && previous.cut() == current.cut() && previousSequence == current.sequence() - 1;
        int slot = 1 - previousSlot;
        var target = textures[slot];
        if (target == null || target.width != width || target.height != height) {
            var next = new OwnedColorTarget("LumenFX subsurface quality " + slot, width, height, GpuFormat.RGBA16_FLOAT);
            if (target != null) target.close();
            textures[slot] = target = next;
        }
        if (!valid) return new Frame(current, slot, null, target.getColorTextureView(), new Matrix4f(), 0, 0);
        var delta = current.camera().subtract(previous.camera());
        var clip = new Matrix4f().set(new Matrix4d(previous.worldToClip()).translate(delta.x, delta.y, delta.z)
                .mul(new Matrix4d(current.worldToClip()).invert()));
        return new Frame(current, slot, prior.getColorTextureView(), target.getColorTextureView(), clip,
                (current.jitterX() - previous.jitterX()) * 0.5f, (current.jitterY() - previous.jitterY()) * 0.5f);
    }

    void commit(Frame frame) {
        if (frame.view().paused()) {
            if (frame.previous() != null) previousSequence = frame.view().sequence();
            else invalidate();
            return;
        }
        previousView = frame.view();
        previousSequence = frame.view().sequence();
        previousSlot = frame.slot();
    }

    void invalidate() {
        previousView = null;
        previousSequence = Long.MIN_VALUE;
    }

    @Override public void close() {
        invalidate();
        for (int index = 0; index < textures.length; index++) {
            var texture = textures[index];
            textures[index] = null;
            if (texture != null) texture.close();
        }
    }
}
