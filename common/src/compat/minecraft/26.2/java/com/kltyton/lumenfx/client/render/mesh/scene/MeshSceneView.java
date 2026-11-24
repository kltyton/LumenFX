package com.kltyton.lumenfx.client.render.mesh.scene;

import com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionView;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/** Camera inputs for source mesh preparation, expressed in source axes and centimetres. */
public record MeshSceneView(Quaternionfc orientation, Vec3 camera, ParticleMotionView.@Nullable Snapshot motion, float delta,
                            com.kltyton.lumenfx.client.render.niagara.gpu.@Nullable NiagaraGpuView nativeView) {
    public MeshSceneView(Quaternionfc orientation, Vec3 camera, ParticleMotionView.@Nullable Snapshot motion, float delta) {
        this(orientation, camera, motion, delta, null);
    }

    public MeshSceneView withView(com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuView value) {
        return new MeshSceneView(orientation, camera, motion, delta, value);
    }

    public GpuBufferSlice write(CommandEncoder encoder, int first, int count, int countOffset, int flags,
                                int vertexBase, int extraBase, int extraStride, int dispatchWidth, Vec3 distanceCenter) {
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        try (var mapped = encoder.transientMemory().allocateGpuMapped(192, alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            data.putInt(first).putInt(count).putInt(countOffset).putInt(flags);
            data.putInt(vertexBase).putInt(extraBase).putInt(extraStride).putInt(dispatchWidth);
            direction(data, false, false);
            direction(data, false, true);
            direction(data, true, false);
            direction(data, true, true);
            Vec3 previous = motion == null ? camera : motion.previous().camera();
            if (nativeView == null) {
                data.putFloat((float)((camera.x - previous.x) * 100))
                        .putFloat((float)((camera.z - previous.z) * 100))
                        .putFloat((float)((camera.y - previous.y) * 100)).putFloat(delta);
            } else {
                var currentCamera = nativeView.camera(false); var oldCamera = nativeView.camera(true);
                data.putFloat((float)(currentCamera.x() - oldCamera.x())).putFloat((float)(currentCamera.y() - oldCamera.y()))
                        .putFloat((float)(currentCamera.z() - oldCamera.z())).putFloat(delta);
            }
            data.putFloat((float)distanceCenter.x).putFloat((float)distanceCenter.y).putFloat((float)distanceCenter.z).putFloat(0);
            if (nativeView == null) {
                worldCamera(data, camera);
                worldCamera(data, previous);
            } else {
                nativeView.writeWorldCamera(data, false);
                nativeView.writeWorldCamera(data, true);
            }
            return mapped.slice();
        }
    }

    private void direction(java.nio.ByteBuffer data, boolean previous, boolean up) {
        if (nativeView != null) {
            var frame = nativeView.camera(previous);
            data.putFloat(up ? frame.upX() : frame.forwardX()).putFloat(up ? frame.upY() : frame.forwardY())
                    .putFloat(up ? frame.upZ() : frame.forwardZ()).putFloat(0);
            return;
        }
        Quaternionfc rotation = previous && motion != null ? motion.previous().orientation() : orientation;
        Vector3f value = rotation.transform(up ? new Vector3f(0, 1, 0) : new Vector3f(0, 0, -1));
        data.putFloat(value.x).putFloat(value.z).putFloat(value.y).putFloat(0);
    }

    private static void worldCamera(java.nio.ByteBuffer data, Vec3 camera) {
        double x = camera.x * 100, y = camera.z * 100, z = camera.y * 100;
        float hx = (float)x, hy = (float)y, hz = (float)z;
        data.putFloat(hx).putFloat(hy).putFloat(hz).putFloat(0);
        data.putFloat((float)(x - hx)).putFloat((float)(y - hy)).putFloat((float)(z - hz)).putFloat(0);
    }
}
