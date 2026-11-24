package com.kltyton.lumenfx.client.render.fog;

import com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture;
import java.nio.ByteBuffer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Current view in camera-relative MC coordinates, including the submitted camera effects. */
public final class FogView {
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f modelView = new Matrix4f();
    private final Matrix4f worldToClip = new Matrix4f();
    private final Matrix4f unjitteredWorldToClip = new Matrix4f();
    private final Matrix4f clipToWorld = new Matrix4f();
    private double baseWorldHeightCm;
    private float forwardX, forwardY, forwardZ;

    public void prepare(Matrix4fc modelView, Vec3 cameraWorldPosition) {
        this.modelView.set(modelView);
        var capture = WorldHdrCapture.INSTANCE;
        capture.copyProjection(projection);
        worldToClip.set(projection).mul(modelView);
        capture.copyUnjitteredProjection(projection);
        unjitteredWorldToClip.set(projection).mul(modelView);
        unjitteredWorldToClip.invert(clipToWorld);
        baseWorldHeightCm = cameraWorldPosition.y * 100.0;
        forwardX = -modelView.m02();
        forwardY = -modelView.m22();
        forwardZ = -modelView.m12();
    }

    public double cameraHeightCm() { return baseWorldHeightCm; }

    public void copyWorldToClip(Matrix4f destination) { destination.set(worldToClip); }
    public void copyUnjitteredWorldToClip(Matrix4f destination) { destination.set(unjitteredWorldToClip); }
    public Matrix4fc projection() { return projection; }
    public Matrix4fc modelView() { return modelView; }

    public void writeForward(ByteBuffer target) {
        target.putFloat(forwardX).putFloat(forwardY).putFloat(forwardZ).putFloat(0);
    }

    public void writeClipMatrices(ByteBuffer target) {
        int start = target.position();
        worldToClip.get(target);
        target.position(start + 64);
        clipToWorld.get(target);
        target.position(start + 128);
    }
}
