package com.kltyton.lumenfx.client.render.scene.material;

import com.kltyton.lumenfx.client.render.niagara.NiagaraComponentUniforms;
import com.kltyton.lumenfx.client.render.precision.WorldOriginUniforms;
import com.kltyton.lumenfx.client.transform.NiagaraComponentBounds;
import com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.render.material.PrimitiveCustomData;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;
import java.nio.ByteBuffer;
import org.joml.Matrix4d;
import org.jspecify.annotations.Nullable;

public final class MaterialPrimitiveUniforms {
    private static final double TILE_SIZE = 2097152;

    private MaterialPrimitiveUniforms() { }

    public static GpuBufferSlice write(CommandEncoder encoder, MaterialPrimitiveFrame frame) {
        return write(encoder, frame, null);
    }

    public static GpuBufferSlice write(CommandEncoder encoder, MaterialPrimitiveFrame frame,
                                       StaticMeshAsset.@Nullable Bounds meshBounds) {
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        try (var mapped = encoder.transientMemory().allocateGpuMapped(432 + PrimitiveCustomData.BYTES, alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            Matrix4d current = NiagaraComponentMatrices.source(frame.current(), 0, 0, 0, true, new Matrix4d());
            Matrix4d previous = NiagaraComponentMatrices.source(frame.previous(), 0, 0, 0, true, new Matrix4d());
            double tx = current.m30(), ty = current.m31(), tz = current.m32();
            float hx = (float)tx, hy = (float)ty, hz = (float)tz;
            NiagaraComponentMatrices.relative(current, hx, hy, hz, false, current);
            NiagaraComponentMatrices.relative(previous, hx, hy, hz, true, previous);
            NiagaraComponentUniforms.writeMatrix(data, current);
            NiagaraComponentUniforms.writeMatrix(data, NiagaraComponentMatrices.inverse(current, new Matrix4d()));
            NiagaraComponentUniforms.writeMatrix(data, previous);
            NiagaraComponentUniforms.writeMatrix(data, NiagaraComponentMatrices.inverse(previous, new Matrix4d()));
            data.putFloat(hx).putFloat(hy).putFloat(hz).putFloat(0);
            data.putFloat((float)Math.rint((float)(hx / TILE_SIZE))).putFloat((float)Math.rint((float)(hy / TILE_SIZE)))
                    .putFloat((float)Math.rint((float)(hz / TILE_SIZE))).putFloat(0);
            current.m30(tx).m31(ty).m32(tz);
            writeBounds(data, frame, current, meshBounds);
            var actor = frame.actorPosition();
            WorldOriginUniforms.writeSourcePosition(data, actor.x * 100, actor.z * 100, actor.y * 100);
            frame.customPrimitiveData().write(data);
            writeInstanceBounds(data, frame.sourceLocalBounds(), meshBounds);
            return mapped.slice();
        }
    }

    private static void writeBounds(ByteBuffer data, MaterialPrimitiveFrame frame, Matrix4d transform,
                                    StaticMeshAsset.@Nullable Bounds meshBounds) {
        var local = frame.sourceLocalBounds();
        if (local == null && meshBounds != null) {
            local = toNiagaraBounds(meshBounds);
        }
        var bounds = NiagaraComponentBounds.boxSphere(transform, local);
        WorldOriginUniforms.writeSourcePosition(data, bounds.origin().x, bounds.origin().y, bounds.origin().z);
        vector(data, bounds.extent().x, bounds.extent().y, bounds.extent().z, bounds.radius());
        writeLocalBounds(data, local);
    }

    private static void writeInstanceBounds(ByteBuffer data, @Nullable NiagaraBounds componentLocal,
                                            StaticMeshAsset.@Nullable Bounds meshBounds) {
        writeLocalBounds(data, meshBounds != null ? toNiagaraBounds(meshBounds) : componentLocal);
    }

    private static void writeLocalBounds(ByteBuffer data, @Nullable NiagaraBounds local) {
        if (local == null) {
            vector(data, 0, 0, 0, 0);
            vector(data, 0, 0, 0, 0);
        } else {
            vector(data, local.minX(), local.minY(), local.minZ(), 0);
            vector(data, local.maxX(), local.maxY(), local.maxZ(), 0);
        }
    }

    public static NiagaraBounds toNiagaraBounds(StaticMeshAsset.Bounds meshBounds) {
        var origin = meshBounds.origin();
        var extent = meshBounds.extent();
        return new NiagaraBounds(
                origin.x - extent.x, origin.y - extent.y, origin.z - extent.z,
                origin.x + extent.x, origin.y + extent.y, origin.z + extent.z);
    }

    private static void vector(ByteBuffer data, double x, double y, double z, double w) {
        data.putFloat((float)x).putFloat((float)y).putFloat((float)z).putFloat((float)w);
    }
}
