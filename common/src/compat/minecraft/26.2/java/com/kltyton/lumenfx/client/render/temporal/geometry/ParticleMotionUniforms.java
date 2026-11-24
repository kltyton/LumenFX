package com.kltyton.lumenfx.client.render.temporal.geometry;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.kltyton.lumenfx.client.render.precision.WorldOriginUniforms;
import java.nio.ByteOrder;
import org.joml.Vector3f;
import com.kltyton.lumenfx.client.render.scene.material.MaterialCoordinateUniforms;
import com.kltyton.lumenfx.client.render.scene.material.FirstPersonMaterialView;

public final class ParticleMotionUniforms {
    private ParticleMotionUniforms() { }

    public static GpuBufferSlice write(CommandEncoder encoder, ParticleMotionView.Snapshot view, float delta) {
        return write(encoder, view, delta, false);
    }

    public static GpuBufferSlice write(CommandEncoder encoder, ParticleMotionView.Snapshot view, float delta,
                                       boolean materialTransforms) {
        return write(encoder, view, delta, materialTransforms ? 32 : 0);
    }

    public static GpuBufferSlice write(CommandEncoder encoder, ParticleMotionView.Snapshot view, float delta,
                                       int materialInputs) {
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        try (var mapped = encoder.transientMemory().allocateGpuMapped(144 + WorldOriginUniforms.BYTES + MaterialCoordinateUniforms.BYTES + FirstPersonMaterialView.BYTES, alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            view.previous().viewProjection().get(0, data);
            data.position(64);
            Vector3f right = view.previous().orientation().transform(new Vector3f(1, 0, 0));
            Vector3f up = view.previous().orientation().transform(new Vector3f(0, 1, 0));
            data.putFloat(right.x).putFloat(right.y).putFloat(right.z).putFloat(0);
            data.putFloat(up.x).putFloat(up.y).putFloat(up.z).putFloat(0);
            var current = view.current().camera(); var previous = view.previous().camera();
            data.putFloat((float)((current.x - previous.x) * 100)).putFloat((float)((current.z - previous.z) * 100))
                    .putFloat((float)((current.y - previous.y) * 100)).putFloat(view.historyValid() ? 1 : 0);
            var previousFrame = view.previous();
            boolean clocksKnown = Double.isFinite(previousFrame.realTime()) && Double.isFinite(previousFrame.engineTime());
            data.putFloat(delta).putFloat((float)previousFrame.realTime()).putFloat((float)previousFrame.engineTime())
                    .putFloat(clocksKnown ? 1 : 0);
            WorldOriginUniforms.write(data, current, previous);
            var previousProjection = previousFrame.projection();
            data.putFloat(previousProjection == null ? 0 : previousProjection.m00())
                    .putFloat(previousProjection == null ? 0 : previousProjection.m11())
                    .putFloat(previousProjection == null ? 0 : previousProjection.m33())
                    .putFloat(previousProjection == null ? 0 : 1);
            if ((materialInputs & 32) != 0) MaterialCoordinateUniforms.write(data, previousFrame.modelView(), previousFrame.orientation());
            else for (int component = 0; component < MaterialCoordinateUniforms.BYTES / 4; component++) data.putFloat(0);
            if ((materialInputs & 128) != 0 && previousFrame.firstPerson() != null) previousFrame.firstPerson().write(data);
            else for (int component = 0; component < FirstPersonMaterialView.BYTES / 4; component++) data.putFloat(0);
            return mapped.slice();
        }
    }
}
