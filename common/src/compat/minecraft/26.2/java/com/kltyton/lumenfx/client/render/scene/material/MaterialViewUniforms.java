package com.kltyton.lumenfx.client.render.scene.material;

import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture;
import com.kltyton.lumenfx.client.render.precision.WorldOriginUniforms;
import com.kltyton.lumenfx.client.render.scene.atmosphere.AtmosphereMaterialLighting;
import com.kltyton.lumenfx.client.render.scene.atmosphere.AtmosphereMaterialTextures;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.joml.Matrix4fc;
import org.joml.Quaternionfc;

/** One current-frame material view block shared by geometry and decal draws. */
public final class MaterialViewUniforms {
    private static final int DISTANT_SKY_LIGHT_BYTES = 16;
    private static final int EYE_ADAPTATION_BYTES = 16;
    private static final int POST_VOLUME_BYTES = 16;
    private MaterialViewUniforms() { }

    public static GpuBufferSlice write(CommandEncoder encoder, int inputs) {
        return write(encoder, inputs, null);
    }

    public static GpuBufferSlice write(CommandEncoder encoder, int inputs, @Nullable Vec3 worldCamera) {
        return write(encoder, inputs, worldCamera, null);
    }

    public static GpuBufferSlice write(CommandEncoder encoder, int inputs, @Nullable Vec3 worldCamera,
                                      @Nullable Matrix4fc modelView) {
        return write(encoder, inputs, worldCamera, modelView, null);
    }

    public static GpuBufferSlice write(CommandEncoder encoder, int inputs, @Nullable Vec3 worldCamera,
                                      @Nullable Matrix4fc modelView, @Nullable Quaternionfc camera) {
        return write(encoder, inputs, worldCamera, modelView, camera, false);
    }

    public static GpuBufferSlice write(CommandEncoder encoder, int inputs, @Nullable Vec3 worldCamera,
                                      @Nullable Matrix4fc modelView, @Nullable Quaternionfc camera, boolean pointShadow) {
        return write(encoder, inputs, worldCamera, modelView, camera, pointShadow, null);
    }

    public static GpuBufferSlice write(CommandEncoder encoder, int inputs, @Nullable Vec3 worldCamera,
                                      @Nullable Matrix4fc modelView, @Nullable Quaternionfc camera, boolean pointShadow,
                                      @Nullable AtmosphereMaterialLighting atmosphere) {
        RenderSystem.assertOnRenderThread();
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        GpuBufferSlice distant = (inputs & MaterialDefinition.DISTANT_SKY_LIGHT_VIEW_INPUT) != 0 && atmosphere != null
                ? atmosphere.distantSkyLight() : null;
        int distantOffset = 80 + WorldOriginUniforms.BYTES + MaterialCoordinateUniforms.BYTES + FirstPersonMaterialView.BYTES
                + AtmosphereMaterialLighting.BYTES;
        int bytes = distantOffset + DISTANT_SKY_LIGHT_BYTES + AtmosphereMaterialLighting.TEXTURE_VIEW_BYTES
                + EYE_ADAPTATION_BYTES + POST_VOLUME_BYTES;
        int usage = GpuBuffer.USAGE_UNIFORM | (distant == null ? 0 : GpuBuffer.USAGE_COPY_DST);
        GpuBufferSlice view;
        try (var mapped = encoder.transientMemory().allocateGpuMapped(bytes, alignment, usage)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            if (pointShadow) {
                data.putFloat(0).putFloat((inputs & 1) == 0 ? 0 : 1).putFloat(0).putFloat(0);
                data.putFloat((inputs & 4) == 0 ? 0 : WorldHdrCapture.INSTANCE.nearClipCm())
                        .putFloat(1).putFloat(1).putFloat(0);
            } else WorldHdrCapture.INSTANCE.writeMaterialView(data, inputs);
            if ((inputs & 8) != 0) WorldOriginUniforms.write(data, Objects.requireNonNull(worldCamera,
                    "Absolute material positions require the current draw camera"));
            else for (int component = 0; component < 8; component++) data.putFloat(0);
            if ((inputs & 16) != 0) WorldOriginUniforms.writeViewOrigin(data, Objects.requireNonNull(worldCamera,
                    "Material view origin requires the current draw camera"), Objects.requireNonNull(modelView,
                    "Material view origin requires the current model-view matrix"));
            else for (int component = 0; component < 4; component++) data.putFloat(0);
            if ((inputs & 32) != 0) MaterialCoordinateUniforms.write(data, Objects.requireNonNull(modelView,
                    "Material view transforms require the current model-view matrix"), Objects.requireNonNull(camera,
                    "Material camera transforms require the current camera orientation"));
            else for (int component = 0; component < MaterialCoordinateUniforms.BYTES / 4; component++) data.putFloat(0);
            if ((inputs & 64) != 0) WorldOriginUniforms.writeDoublePreViewTranslation(data, Objects.requireNonNull(worldCamera,
                    "Periodic material positions require the current draw camera"));
            else for (int component = 0; component < 8; component++) data.putFloat(0);
            if ((inputs & 128) != 0) Objects.requireNonNull(WorldHdrCapture.INSTANCE.firstPersonMaterialView(),
                    "First-person material inputs require the captured view parameters").write(data);
            else for (int component = 0; component < FirstPersonMaterialView.BYTES / 4; component++) data.putFloat(0);
            if ((inputs & AtmosphereMaterialLighting.VIEW_INPUT) != 0) {
                var lighting = atmosphere == null ? AtmosphereMaterialLighting.resolve(null, List.of(), NiagaraPlatforms.snapshot()) : atmosphere;
                lighting.write(data);
            } else for (int component = 0; component < AtmosphereMaterialLighting.BYTES / 4; component++) data.putFloat(0);
            data.putFloat(0).putFloat(0).putFloat(0).putFloat(0);
            if (atmosphere != null && (inputs & AtmosphereMaterialTextures.MASK) != 0) atmosphere.writeTextureView(data, inputs);
            else for (int component = 0; component < AtmosphereMaterialLighting.TEXTURE_VIEW_BYTES / 4; component++) data.putFloat(0f);
            float eyeAdaptation = (inputs & MaterialDefinition.EYE_ADAPTATION_VIEW_INPUT) != 0
                    ? WorldHdrCapture.INSTANCE.eyeAdaptationExposure() : 1f;
            data.putFloat(eyeAdaptation).putFloat(1f / eyeAdaptation).putFloat(0f).putFloat(0f);
            data.putInt((inputs & MaterialDefinition.POST_VOLUME_FLAGS_VIEW_INPUT) != 0
                    ? WorldHdrCapture.INSTANCE.postVolumeUserFlags() : 0)
                    .putInt(0).putInt(0).putInt(0);
            view = mapped.slice();
        }
        if (distant != null) encoder.copyToBuffer(distant,
                new GpuBufferSlice(view.buffer(), view.offset() + distantOffset, DISTANT_SKY_LIGHT_BYTES));
        return view;
    }
}
