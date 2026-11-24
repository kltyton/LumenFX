package com.kltyton.lumenfx.client.render.mesh.nanite;

import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.client.VfxEngine;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture;
import com.kltyton.lumenfx.client.render.light.shadow.geometry.PointShadowParticleView;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuView;
import com.kltyton.lumenfx.client.render.scene.atmosphere.AtmosphereMaterialLighting;
import com.kltyton.lumenfx.client.render.scene.atmosphere.AtmosphereMaterialTextures;
import com.kltyton.lumenfx.client.render.scene.material.MaterialCollectionUniforms;
import com.kltyton.lumenfx.client.render.scene.material.MaterialInstanceUniforms;
import com.kltyton.lumenfx.client.render.scene.material.MaterialPrimitiveFrame;
import com.kltyton.lumenfx.client.render.scene.material.MaterialPrimitiveUniforms;
import com.kltyton.lumenfx.client.render.scene.material.MaterialViewUniforms;
import com.kltyton.lumenfx.client.render.texture.MaterialTextureBindings;
import com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionUniforms;
import com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionView;
import com.kltyton.lumenfx.client.resource.texture.EffectTextures;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import java.nio.ByteOrder;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/** Current view resources for Nanite vertex graphs, shared with the regular material path. */
final class NaniteMaterialFrameBindings implements NaniteVertexMaterials.MaterialBindings {
    private final VfxEngine.FrameInput frame;
    private final CommandEncoder encoder;
    private final NiagaraGpuView.Frame view;
    private final ParticleMotionView.@Nullable Snapshot motion;
    private final Quaternionf orientation;
    private final boolean shadowDepth;
    private final @Nullable AtmosphereMaterialLighting atmosphere;
    private final MaterialCollectionUniforms.Frame collections;
    private final Map<MaterialDefinition, GpuBufferSlice> instances = new IdentityHashMap<>();
    private @Nullable GpuBufferSlice sceneViewProjection;
    private @Nullable GpuBufferSlice materialProjection;

    NaniteMaterialFrameBindings(CommandEncoder encoder, VfxEngine.FrameInput frame, NiagaraGpuView.Frame view,
                                ParticleMotionView.@Nullable Snapshot motion, @Nullable AtmosphereMaterialLighting atmosphere) {
        this(encoder, frame, view, motion, atmosphere, null);
    }

    NaniteMaterialFrameBindings(CommandEncoder encoder, VfxEngine.FrameInput frame, NiagaraGpuView.Frame view,
                                ParticleMotionView.@Nullable Snapshot motion, @Nullable AtmosphereMaterialLighting atmosphere,
                                @Nullable PointShadowParticleView shadow) {
        this.frame = frame;
        this.encoder = encoder;
        this.view = view;
        this.motion = motion;
        this.atmosphere = atmosphere;
        shadowDepth = shadow != null;
        orientation = shadow == null ? new Quaternionf(frame.qx(), frame.qy(), frame.qz(), frame.qw()) : shadow.orientation();
        collections = MaterialCollectionUniforms.INSTANCE.frame(encoder, frame.level());
    }

    NaniteWorldRenderer.SurfaceBindings prepareSurface(MaterialDefinition material, NaniteInstanceCollector.Prepared source) {
        var effectFrame = effectFrame(encoder, material, source);
        var effectView = effectView(encoder, material);
        var instance = material.instanceParameters().byteSize() == 0 ? null
                : instances.computeIfAbsent(material, value -> MaterialInstanceUniforms.write(encoder, value));
        var collection = collections.prepare(material);
        var vertex = material.vertexMaterial();
        int viewInputs = material.materialView(source.input().firstPerson());
        var materialView = material.materialView() != 0 || vertex != null && vertex.firstPersonInputs() != 0
                ? MaterialViewUniforms.write(encoder, viewInputs, frame.camera(), view.modelView(), orientation, shadowDepth, atmosphere) : null;
        var primitive = vertex != null && vertex.primitiveMatrices()
                ? MaterialPrimitiveUniforms.write(encoder, Objects.requireNonNull(
                        MaterialPrimitiveFrame.forMaterial(material, source.input(), source.previousPose(), source::bounds)),
                        source.asset().extendedBounds()) : null;
        return pass -> {
            pass.setUniform("EffectFrame", effectFrame);
            pass.setUniform("EffectView", effectView);
            if (instance != null) pass.setUniform("MaterialInstance", instance);
            MaterialCollectionUniforms.bind(pass, collection);
            if (materialView != null) pass.setUniform("MaterialView", materialView);
            if (primitive != null) pass.setUniform("MaterialPrimitive", primitive);
        };
    }

    @Override public @Nullable GpuBufferSlice uniform(CommandEncoder encoder, String name, MaterialDefinition material,
            NaniteInstanceCollector.Prepared source, NaniteViewProjection current, NaniteViewProjection previous) {
        instances.computeIfAbsent(material, value -> MaterialInstanceUniforms.write(encoder, value));
        int viewInputs = material.materialView(source.input().firstPerson());
        return switch (name) {
            case "Projection" -> materialProjection(encoder);
            case "NaniteSceneViewProjection" -> sceneViewProjection(encoder);
            case "EffectFrame" -> effectFrame(encoder, material, source);
            case "EffectView" -> effectView(encoder, material);
            case "MaterialInstance" -> instances.computeIfAbsent(material, value -> MaterialInstanceUniforms.write(encoder, value));
            case "MaterialView" -> MaterialViewUniforms.write(encoder, viewInputs, frame.camera(), view.modelView(), orientation,
                    shadowDepth, atmosphere);
            case "MaterialPrimitive" -> MaterialPrimitiveUniforms.write(encoder, Objects.requireNonNull(
                    MaterialPrimitiveFrame.forMaterial(material, source.input(), source.previousPose(), source::bounds)),
                    source.asset().extendedBounds());
            case "ParticleMotionView" -> ParticleMotionUniforms.write(encoder,
                    motion == null ? motionWithoutHistory() : motion, frame.delta(), viewInputs);
            default -> {
                GpuBufferSlice value = collections.prepare(material).get(name);
                if (value == null) value = VfxEngine.INSTANCE.materialVirtualTextures().uniform(material, name);
                yield value;
            }
        };
    }

    private ParticleMotionView.Snapshot motionWithoutHistory() {
        var firstPerson = com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture.INSTANCE.firstPersonMaterialView();
        var current = new ParticleMotionView.Frame(frame.camera(), orientation,
                new Matrix4f(view.projection()).mul(view.modelView()), Double.NaN, Double.NaN,
                view.projection(), view.modelView(), firstPerson);
        return new ParticleMotionView.Snapshot(current, current, false);
    }

    private GpuBufferSlice sceneViewProjection(CommandEncoder encoder) {
        if (sceneViewProjection == null)
            sceneViewProjection = projectionBuffer(encoder, new Matrix4f(view.projection()).mul(view.modelView()));
        return sceneViewProjection;
    }

    private GpuBufferSlice materialProjection(CommandEncoder encoder) {
        if (materialProjection == null) materialProjection = projectionBuffer(encoder, view.projection());
        return materialProjection;
    }

    private static GpuBufferSlice projectionBuffer(CommandEncoder encoder, Matrix4fc projection) {
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        try (var upload = encoder.transientMemory().allocateGpuMapped(64, alignment, GpuBuffer.USAGE_UNIFORM)) {
            projection.get(upload.data().order(ByteOrder.nativeOrder()));
            return upload.slice();
        }
    }

    private GpuBufferSlice effectFrame(CommandEncoder encoder, MaterialDefinition material, NaniteInstanceCollector.Prepared source) {
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        try (var upload = encoder.transientMemory().allocateGpuMapped(48, alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = upload.data().order(ByteOrder.nativeOrder());
            var capture = WorldHdrCapture.INSTANCE;
            data.putFloat((float) frame.time()).putFloat(shadowDepth ? 0f : capture.materialViewMipBias())
                    .putFloat((float) frame.realTime()).putFloat((float) frame.engineTime());
            material.writeParameters(data);
            float fraction = shadowDepth ? 1f : capture.materialResolutionFraction();
            data.putFloat(frame.delta()).putFloat(fraction).putFloat(1f / fraction).putFloat(source.input().firstPerson() ? 1 : 0);
            return upload.slice();
        }
    }

    private GpuBufferSlice effectView(CommandEncoder encoder, MaterialDefinition material) {
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        int bytes = 48 + (material.macroUV() ? 16 : 0) + (material.depthProjection() ? 16 : 0);
        try (var upload = encoder.transientMemory().allocateGpuMapped(bytes, alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = upload.data().order(ByteOrder.nativeOrder());
            var right = orientation.transform(new Vector3f(1, 0, 0));
            var up = orientation.transform(new Vector3f(0, 1, 0));
            data.putFloat(right.x).putFloat(right.y).putFloat(right.z).putFloat(RenderSystem.getDevice().backend instanceof GlDevice ? 1 : 0);
            data.putFloat(up.x).putFloat(up.y).putFloat(up.z).putFloat(1f / view.height());
            data.putFloat(0).putFloat(0).putFloat(0).putFloat(1f / view.width());
            if (material.macroUV()) data.putFloat(.5f).putFloat(.5f).putFloat(0).putFloat(0);
            if (material.depthProjection()) {
                for (float component : com.kltyton.lumenfx.client.render.niagara.ParticleSceneDepth.depthTransform(
                        view.baseProjection(), RenderSystem.getDevice().getDeviceInfo().isZZeroToOne())) data.putFloat(component);
            }
            return upload.slice();
        }
    }

    @Override public boolean texture(ComputeBindings.Builder builder, ShaderBinding.SampledTexture binding, MaterialDefinition material) {
        instances.computeIfAbsent(material, value -> MaterialInstanceUniforms.write(encoder, value));
        if (VfxEngine.INSTANCE.materialVirtualTextures().bind(builder, material, binding)) return true;
        String name = binding.name();
        if (AtmosphereMaterialTextures.bind(builder, name, atmosphere == null ? null : atmosphere.atmosphere())) return true;
        var instance = material.instanceParameters().texture(name);
        if (instance == null) {
            var id = material.textures().get(name);
            if (id == null) return false;
            if (EffectTextures.get(id) != null || EffectTextures.target(id) != null) instance = EffectTextures.snapshot(id);
            else {
                var texture = Minecraft.getInstance().getTextureManager().getTexture(id);
                instance = TextureParameter.snapshot(texture.getTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            }
        }
        MaterialTextureBindings.bind(builder, binding, instance, material,
                shadowDepth ? 0f : WorldHdrCapture.INSTANCE.materialViewMipBias());
        return true;
    }
}
