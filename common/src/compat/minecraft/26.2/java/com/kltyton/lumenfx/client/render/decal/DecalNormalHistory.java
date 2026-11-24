package com.kltyton.lumenfx.client.render.decal;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.client.render.scene.surface.SceneSurfaceCapture.Surfaces;
import com.kltyton.lumenfx.client.render.texture.OwnedColorTarget;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/** Owns the last submitted native view's opaque world normals for DBuffer reprojection. */
public final class DecalNormalHistory implements AutoCloseable {
    private static final String ENABLED = "r.Decal.NormalReprojectionEnabled";
    private static final String LOW = "r.Decal.NormalReprojectionThresholdLow";
    private static final String HIGH = "r.Decal.NormalReprojectionThresholdHigh";
    private static final RenderPipeline CAPTURE = RenderPipeline.builder()
            .withLocation(LumenFx.id("pipeline/decal/normal_history"))
            .withVertexShader(LumenFx.id("post/fullscreen")).withFragmentShader(LumenFx.id("post/decal/normal_history"))
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("NormalHistoryView", UniformType.UNIFORM_BUFFER)
                    .withSampler("SurfaceNormal").build())
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
            .withDepthStencilState(Optional.empty()).withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).build();

    public record Input(GpuTextureView normals, GpuTextureView velocity, GpuBufferSlice view) { }
    private record View(Object world, Object cameraOwner, long cut, Vec3 camera, Matrix4fc worldToClip,
                        Matrix4fc viewToWorld, int width, int height) { }

    private @Nullable OwnedColorTarget normals;
    private @Nullable View current, previous;
    private boolean submitted;

    public static boolean enabled() {
        var platform = NiagaraPlatforms.snapshot();
        return Objects.requireNonNull(platform.console(ENABLED)).bits() != 0
                && Objects.requireNonNull(platform.console("r.VelocityOutputPass")).bits() == 0;
    }

    public void begin(Object world, Object cameraOwner, long cut, Vec3 camera, Matrix4fc projection,
                      Matrix4fc modelView, int width, int height) {
        current = new View(world, cameraOwner, cut, camera, new Matrix4f(projection).mul(modelView),
                new Matrix4f(modelView).transpose(), width, height);
        if (previous != null && (previous.world() != world || previous.cameraOwner() != cameraOwner || previous.cut() != cut
                || previous.width() != width || previous.height() != height)) previous = null;
        submitted = false;
    }

    public boolean hasPreviousView() { return current != null && previous != null && normals != null; }

    public @Nullable Input input(@Nullable GpuTextureView velocity) {
        var view = current;
        var history = previous;
        var normalTarget = normals;
        if (view == null || history == null || normalTarget == null || velocity == null) return null;
        if (velocity.getWidth(0) != view.width() || velocity.getHeight(0) != view.height()) return null;
        var delta = view.camera().subtract(history.camera());
        var clipToPrevious = new Matrix4f().set(new Matrix4d(history.worldToClip())
                .translate(delta.x, delta.y, delta.z).mul(new Matrix4d(view.worldToClip()).invert()));
        var device = RenderSystem.getDevice();
        var platform = NiagaraPlatforms.snapshot();
        float low = Float.intBitsToFloat(Objects.requireNonNull(platform.console(LOW)).bits());
        float high = Float.intBitsToFloat(Objects.requireNonNull(platform.console(HIGH)).bits());
        try (var mapped = device.createCommandEncoder().transientMemory().allocateGpuMapped(80,
                device.getDeviceInfo().limits().minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            clipToPrevious.get(0, data);
            data.position(64);
            data.putFloat(low).putFloat(1 / Math.max(high - low, 1e-4f))
                    .putFloat(device.getDeviceInfo().isZZeroToOne() ? 1 : 0).putFloat(0);
            return new Input(Objects.requireNonNull(normalTarget.getColorTextureView()), velocity, mapped.slice());
        }
    }

    public void finish(@Nullable Surfaces surfaces, boolean paused) {
        var view = current;
        if (view == null || surfaces == null) return;
        if (!paused) {
            if (normals == null || normals.width != view.width() || normals.height != view.height()) {
                var next = new OwnedColorTarget("LumenFX decal normal history", view.width(), view.height(), GpuFormat.RGBA16_FLOAT);
                if (normals != null) normals.close();
                normals = next;
            }
            var device = RenderSystem.getDevice();
            var encoder = device.createCommandEncoder();
            GpuBufferSlice uniformSlice;
            try (var mapped = encoder.transientMemory().allocateGpuMapped(64, device.getDeviceInfo().limits().minUniformOffsetAlignment(),
                    GpuBuffer.USAGE_UNIFORM)) {
                view.viewToWorld().get(0, mapped.data().order(ByteOrder.nativeOrder()));
                uniformSlice = mapped.slice();
            }
            try (var pass = encoder.createRenderPass(() -> "LumenFX retain opaque decal normals",
                    normals.getColorTextureView(), Optional.empty())) {
                pass.setPipeline(CAPTURE);
                pass.setUniform("NormalHistoryView", uniformSlice);
                pass.bindTexture("SurfaceNormal", surfaces.normalAmbient(),
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.draw(3, 1, 0, 0);
            }
            previous = view;
        }
        submitted = true;
    }

    public void end() {
        if (current != null && !submitted) previous = null;
        current = null;
    }

    @Override public void close() {
        if (normals != null) { normals.close(); normals = null; }
        current = previous = null;
        submitted = false;
    }
}
