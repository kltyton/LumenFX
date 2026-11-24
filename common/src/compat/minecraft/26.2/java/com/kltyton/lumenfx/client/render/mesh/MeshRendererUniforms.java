package com.kltyton.lumenfx.client.render.mesh;

import com.kltyton.lumenfx.effect.render.mesh.MeshBatchGeometry;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;

public final class MeshRendererUniforms {
    private MeshRendererUniforms() { }

    public static GpuBufferSlice write(CommandEncoder encoder, MeshBatchGeometry batch) {
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        try (var mapped = encoder.transientMemory().allocateGpuMapped(96, alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            var mesh = batch.mesh();
            var renderer = batch.renderer();
            data.putFloat((float)mesh.scale().x).putFloat((float)mesh.scale().y).putFloat((float)mesh.scale().z)
                    .putFloat(mesh.pivotSpace().ordinal());
            var rotation = mesh.rotation();
            data.putFloat(rotation.x()).putFloat(rotation.y()).putFloat(rotation.z()).putFloat(rotation.w());
            data.putFloat((float)mesh.pivot().x).putFloat((float)mesh.pivot().y).putFloat((float)mesh.pivot().z)
                    .putFloat(batch.localSpace() ? 1 : 0);
            data.putFloat(renderer.facing().ordinal()).putFloat(renderer.lockedAxisEnabled() ? 1 : 0)
                    .putFloat(renderer.lockedAxisSpace().ordinal()).putFloat(batch.motion().precise() ? 1 : 2);
            int subImageFlags = (batch.subImageBlend() ? 1 : 0) | ((batch.subImageUvMask() & 0xFF) << 1);
            data.putFloat((float)renderer.lockedAxis().x).putFloat((float)renderer.lockedAxis().y)
                    .putFloat((float)renderer.lockedAxis().z).putFloat(subImageFlags);
            data.putFloat(batch.subImageColumns()).putFloat(batch.subImageRows())
                    .putFloat(1 / batch.subImageColumns()).putFloat(1 / batch.subImageRows());
            return mapped.slice();
        }
    }
}
