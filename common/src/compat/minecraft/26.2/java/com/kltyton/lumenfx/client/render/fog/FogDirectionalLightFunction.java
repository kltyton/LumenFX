package com.kltyton.lumenfx.client.render.fog;

import com.kltyton.lumenfx.client.render.light.function.LightFunctionAtlas;
import com.kltyton.lumenfx.client.render.light.function.LightFunctionRenderer;
import com.kltyton.lumenfx.client.render.light.function.LightFunctionView;
import com.kltyton.lumenfx.client.render.light.function.LightFunctionRenderer.VolumeMaterial;
import com.kltyton.lumenfx.client.render.light.shadow.directional.DirectionalShadowMatrices;
import com.kltyton.lumenfx.effect.render.light.scene.SceneDirectionalLight;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteOrder;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/** Projects the authored directional function into a fog-frustum texture when an atlas tile is unavailable. */
final class FogDirectionalLightFunction implements AutoCloseable {
    record Frame(GpuTextureView texture, Matrix4fc projection) { }
    private static final GpuFormat FORMAT = GpuFormat.R8_UNORM;
    private final LightFunctionRenderer renderer = new LightFunctionRenderer();
    private @Nullable TextureTarget texture;

    Frame prepare(CommandEncoder encoder, FogView view, Vec3 camera, FroxelGrid grid, float distanceCm, float supersample,
                  SceneDirectionalLight light, VolumeMaterial material) {
        var function = Objects.requireNonNull(light.lightFunction());
        var direction = light.direction();
        var viewDirection = view.modelView().transformDirection((float) direction.x, (float) direction.z, (float) direction.y, new Vector3f());
        float estimate = Math.max(Math.abs(viewDirection.x) * grid.x(),
                Math.max(Math.abs(viewDirection.y) * grid.y(), Math.abs(viewDirection.z) * grid.z()));
        var limits = RenderSystem.getDevice().getDeviceInfo().limits();
        int size = (int) Math.clamp(Math.ceilDiv((long) (estimate * supersample), 32L) * 32L,
                1L, (long) limits.maxTextureSizeForFormat(FORMAT));
        if (texture == null || texture.width != size) {
            var old = texture;
            texture = new TextureTarget("LumenFX fog directional light function", size, size, false, FORMAT);
            if (old != null) old.destroyBuffers();
        }
        var lightView = DirectionalShadowMatrices.viewRotation(direction);
        var bounds = DirectionalShadowMatrices.viewSphere(view.projection(), new Matrix4f(view.modelView()).invert(), 0, distanceCm * .01);
        var center = lightView.transformDirection(new Vector3d(bounds.center()));
        double radius = bounds.radius(), depth = Math.max(50, radius);
        var projection = new Matrix4d().m00(1 / radius).m11(1 / radius).m22(.5 / depth)
                .m30(-center.x / radius).m31(-center.y / radius).m32(.5 - center.z * .5 / depth).mul(lightView);
        var hostToClip = new Matrix4f();
        view.copyWorldToClip(hostToClip);
        var sourceCamera = new Vec3(camera.x * 100, camera.z * 100, camera.y * 100);
        try (var mapped = encoder.transientMemory().allocateGpuMapped(208 + LightFunctionView.OBJECT_POSITION_BYTES,
                limits.minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            new Matrix4f(new Matrix4d(projection).invert()).get(0, data);
            LightFunctionAtlas.translatedWorldToLight(function, sourceCamera).get(64, data);
            hostToClip.get(128, data);
            data.position(192).putFloat(1f / size).putFloat(function.settings().fadeDistance())
                    .putFloat(function.settings().disabledBrightness()).putFloat(0);
            var origin = LightFunctionView.sourceViewOrigin(camera, view.modelView());
            LightFunctionView.writeObjectPosition(data, origin, origin);
            renderer.drawDirectionalFog(encoder, material, texture, mapped.slice());
        }
        return new Frame(texture.getColorTextureView(), new Matrix4f(projection));
    }

    @Override public void close() {
        try { renderer.close(); }
        finally { if (texture != null) { texture.destroyBuffers(); texture = null; } }
    }
}
