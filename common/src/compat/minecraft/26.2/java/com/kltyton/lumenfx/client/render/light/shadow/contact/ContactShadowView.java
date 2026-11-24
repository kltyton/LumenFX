package com.kltyton.lumenfx.client.render.light.shadow.contact;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4d;

/** Source-centimeter rays project into the exact physical depth convention of the captured receiver. */
public final class ContactShadowView {
    public static final int BYTES = 96;
    private ContactShadowView() { }
    public static GpuBufferSlice write(CommandEncoder encoder, Matrix4fc projection, Matrix4fc viewToSourceWorld,
                                       boolean hostLogicalDepth, boolean zeroToOne, long stateFrameIndex) {
        var clip = new Matrix4d();
        if (hostLogicalDepth) clip.m22(-.5).m32(.5);
        else if (!zeroToOne) clip.m22(.5).m32(.5);
        var viewToClip = clip.mul(new Matrix4d(projection));
        var sourceToClip = new Matrix4d(viewToClip).mul(new Matrix4d(viewToSourceWorld).invert());
        Vector4d depthDirection = viewToClip.transform(new Vector4d(0, 0, -.01, 0));
        boolean perspective = projection.m33() < 1;
        float vertical = 1f / Math.abs(projection.m11());
        var device = RenderSystem.getDevice();
        try (var mapped = encoder.transientMemory().allocateGpuMapped(BYTES,
                device.getDeviceInfo().limits().minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM)) {
            var bytes = mapped.data().order(ByteOrder.nativeOrder());
            new Matrix4f(sourceToClip).get(0, bytes); bytes.position(64);
            bytes.putFloat((float)depthDirection.x).putFloat((float)depthDirection.y).putFloat((float)depthDirection.z).putFloat((float)depthDirection.w);
            bytes.putFloat(perspective ? vertical : 0).putFloat(perspective ? 0 : vertical * 100).putFloat(stateFrameIndex & 7).putFloat(0);
            return mapped.slice();
        }
    }
}
