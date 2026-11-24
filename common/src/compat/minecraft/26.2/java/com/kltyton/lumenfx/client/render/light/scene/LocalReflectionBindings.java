package com.kltyton.lumenfx.client.render.light.scene;

import com.kltyton.lumenfx.client.resource.texture.EffectTexture;
import com.kltyton.lumenfx.client.resource.texture.EffectTextures;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.effect.render.light.scene.LocalReflectionCaptures;
import com.kltyton.lumenfx.effect.render.light.scene.LocalReflectionCaptures.Box;
import com.kltyton.lumenfx.effect.render.light.scene.LocalReflectionCaptures.Plane;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Binds the built cubemap faces and source view-relative reflection proxies. */
public final class LocalReflectionBindings implements AutoCloseable {
    private static final int STRIDE = 7 * 16;
    private static final String VIEW = "LfxLocalReflectionView";
    private static final String PROXIES = "LfxLocalReflectionProxies";
    private static final String FACES = "LfxLocalReflectionFaces";
    private static final String GF = "LfxLocalReflectionGF";
    private @Nullable GpuBuffer proxies;
    private @Nullable Frame frame;
    private @Nullable LocalReflectionCaptures frameCaptures;
    private @Nullable Vec3 frameCamera;
    private @Nullable ReflectionLightmapMixing frameMixing;
    private long frameSequence = Long.MIN_VALUE;

    public record Frame(GpuBufferSlice view, GpuBufferSlice proxies, EffectTexture faces, EffectTexture preintegrated) {
        public void bind(RenderPass pass) {
            pass.setUniform(VIEW, view);
            pass.setUniform(PROXIES, proxies);
            pass.bindTexture(FACES, faces.view(), faces.sampler());
            pass.bindTexture(GF, preintegrated.view(), preintegrated.sampler());
        }
    }

    public static void layout(BindGroupLayout.Builder layout) {
        layout.withUniform(VIEW, UniformType.UNIFORM_BUFFER)
                .withUniform(PROXIES, UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT)
                .withSampler(FACES).withSampler(GF);
    }

    public @Nullable Frame prepare(CommandEncoder encoder, @Nullable LocalReflectionCaptures captures, NiagaraPlatformEnvironment platform,
                                    Vec3 camera, int alignment, long sequence) {
        if (captures == null) return null;
        var mixing = ReflectionLightmapMixing.read(platform);
        if (frame != null && frameSequence == sequence && frameCaptures == captures
                && camera.equals(frameCamera) && mixing.equals(frameMixing)) return frame;
        var sourceCamera = new Vec3(camera.x * 100, camera.z * 100, camera.y * 100);
        long bytes = Math.multiplyExact((long)captures.captures().size(), STRIDE);
        GpuBuffer target = reserve(bytes);
        GpuBufferSlice staging;
        try (var mapped = encoder.transientMemory().allocateGpuMapped(bytes, 4, GpuBuffer.USAGE_COPY_SRC)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            int layer = 0;
            for (var capture : captures.captures()) {
                vector(data, capture.position().subtract(sourceCamera), capture.radius());
                vector(data, capture.offset(), capture.averageBrightness());
                int shape = switch (capture.shape()) {
                    case Box box -> {
                        vector(data, box.scales(), box.transition());
                        vector(data, box.relativeWorldToBoxX(), 0);
                        vector(data, box.relativeWorldToBoxY(), 0);
                        vector(data, box.relativeWorldToBoxZ(), 0);
                        yield 1;
                    }
                    case Plane plane -> {
                        vector(data, Vec3.ZERO, 0);
                        vector(data, plane.normal(), plane.distance());
                        vector(data, plane.reflectionAxis(), plane.axisScale());
                        vector(data, Vec3.ZERO, 0);
                        yield 2;
                    }
                    case LocalReflectionCaptures.Sphere ignored -> {
                        for (int index = 0; index < 16; index++) data.putInt(0);
                        yield 0;
                    }
                };
                data.putFloat(capture.brightness()).putFloat(layer).putFloat(shape).putFloat(1);
                layer += 6;
            }
            staging = mapped.slice();
        }
        var output = target.slice(0, bytes);
        encoder.copyToBuffer(staging, output);
        try (var mapped = encoder.transientMemory().allocateGpuMapped(48, alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            data.putFloat(captures.captures().size()).putFloat(captures.mipCount()).putFloat(mixing.scale()).putFloat(mixing.bias());
            vector(data, captures.specularColorScale(), mixing.limit());
            vector(data, captures.luminanceWeights(), 0);
            frame = new Frame(mapped.slice(), output, texture(captures.textureArray()), texture(captures.preintegratedTexture()));
            frameCaptures = captures;
            frameCamera = camera;
            frameMixing = mixing;
            frameSequence = sequence;
            return frame;
        }
    }

    private GpuBuffer reserve(long bytes) {
        var current = proxies;
        if (current != null && current.size() >= bytes) return current;
        var device = RenderSystem.getDevice();
        if (bytes > device.getDeviceInfo().limits().maxMemoryAllocationSize())
            throw new IllegalArgumentException("Reflection capture proxies exceed the device allocation limit");
        var replacement = device.createBuffer(() -> "LumenFX reflection capture proxies",
                GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST, bytes);
        if (current != null) current.close();
        proxies = replacement;
        return replacement;
    }

    private static EffectTexture texture(Identifier id) {
        return Objects.requireNonNull(EffectTextures.get(id), "Missing prepared reflection capture texture " + id);
    }

    private static void vector(ByteBuffer data, Vec3 value, float w) {
        data.putFloat((float)value.x).putFloat((float)value.y).putFloat((float)value.z).putFloat(w);
    }

    @Override public void close() {
        if (proxies != null) proxies.close();
        proxies = null;
        frame = null;
        frameCaptures = null;
        frameCamera = null;
        frameMixing = null;
    }
}
