package com.kltyton.lumenfx.client.render.light.function;

import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraSceneInputs.Atmosphere;

import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.client.render.texture.OwnedColorTarget;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.render.light.scene.LightFunctionSettings;
import com.kltyton.lumenfx.effect.render.light.scene.SceneDirectionalLight;
import com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/** Renders one tile per material instance and publishes light projections for the current view. */
public final class LightFunctionAtlas implements AutoCloseable {
    public record Slot(Matrix4fc translatedWorldToLight, float fadeDistance, float disabledBrightness,
                       float tangent, int type, float minU, float minV, float span, boolean colored) {
        public static final int BYTES = 96;

        public void write(ByteBuffer data) {
            int start = data.position();
            translatedWorldToLight.get(start, data);
            data.position(start + 64);
            data.putFloat(fadeDistance).putFloat(disabledBrightness).putFloat(tangent).putFloat(type);
            data.putFloat(minU).putFloat(minV).putFloat(span).putFloat(colored ? 1 : 0);
        }
    }
    public record Frame(GpuTextureView texture, Map<ScenePointLight, Slot> slots, @Nullable Slot directional) {
        public Frame(GpuTextureView texture, Map<ScenePointLight, Slot> slots) { this(texture, slots, null); }
        public @Nullable Sample sample(ScenePointLight light) {
            var slot = slots.get(light);
            return slot == null ? null : new Sample(texture, slot);
        }
    }
    public record Sample(GpuTextureView texture, Slot slot) {
        public static void layout(BindGroupLayout.Builder builder) {
            builder.withUniform("LocalLightFunction", UniformType.UNIFORM_BUFFER).withSampler("LocalLightFunctionAtlas");
        }
        public GpuBufferSlice uniforms(CommandEncoder encoder) {
            int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
            try (var mapped = encoder.transientMemory().allocateGpuMapped(Slot.BYTES, alignment, GpuBuffer.USAGE_UNIFORM)) {
                slot.write(mapped.data().order(ByteOrder.nativeOrder()));
                return mapped.slice();
            }
        }
        public void bind(RenderPass pass, GpuBufferSlice uniforms) {
            pass.setUniform("LocalLightFunction", uniforms);
            pass.bindTexture("LocalLightFunctionAtlas", texture, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        }
    }
    private @Nullable OwnedColorTarget texture;

    private record Candidate(@Nullable ScenePointLight light, SceneDirectionalLight.@Nullable Function directional,
                             MaterialDefinition material, double distance, long order) { }

    @Nullable Frame prepare(CommandEncoder encoder, LightFunctionRenderer renderer,
                            Map<ScenePointLight, MaterialDefinition> materials, Map<MaterialDefinition, GpuBufferSlice> effectFrames,
                            SceneDirectionalLight.@Nullable Function directional, @Nullable MaterialDefinition directionalMaterial,
                            GpuBufferSlice effectView, @Nullable GpuBufferSlice materialView, Vec3 camera,
                            @Nullable Atmosphere atmosphere, boolean unrealConsumers) {
        var platform = NiagaraPlatforms.snapshot();
        int maximumLights = Objects.requireNonNull(platform.console("r.LightFunctionAtlas.MaxLightCount")).bits();
        boolean unrealAtlas = unrealConsumers && Objects.requireNonNull(platform.console("r.LightFunctionAtlas")).bits() > 0
                && maximumLights != 0 && maximumLights != 1;
        boolean unrealColor = Objects.requireNonNull(platform.console("r.LightFunctionAtlas.Format")).bits() != 0;
        boolean hasUnityCookies = materials.keySet().stream().anyMatch(light -> light.data().lightFunction().unityCookie());
        GpuFormat format = unrealColor || hasUnityCookies ? GpuFormat.RGBA8_UNORM : GpuFormat.R8_UNORM;
        int edgeTiles = Math.clamp(Objects.requireNonNull(platform.console("r.LightFunctionAtlas.Size")).bits(), 2, 16);
        int requestedTileSize = Math.max(32, Objects.requireNonNull(platform.console("r.LightFunctionAtlas.SlotResolution")).bits());
        var sourceCamera = new Vec3(camera.x * 100.0, camera.z * 100.0, camera.y * 100.0);
        var lights = new ArrayList<Candidate>();
        if (unrealAtlas && directional != null && directionalMaterial != null && directionalMaterial.lightFunctionAtlasCompatible())
            lights.add(new Candidate(null, directional, directionalMaterial, 0, Long.MIN_VALUE));
        materials.forEach((light, material) -> {
            if (material.lightFunctionAtlasCompatible() && (unrealAtlas || light.data().lightFunction().unityCookie()))
                lights.add(new Candidate(light, null, material,
                        light.data().shader().worldPosition().distanceToSqr(sourceCamera), light.identity()));
        });
        if (lights.isEmpty()) return null;
        lights.sort(Comparator.comparingInt((Candidate candidate) -> isUnityCookie(candidate) ? 0 : 1)
                .thenComparingDouble(Candidate::distance).thenComparingLong(Candidate::order));
        var limits = RenderSystem.getDevice().getDeviceInfo().limits();
        int tileSize = Math.min(requestedTileSize, Math.min(32768, limits.maxTextureSizeForFormat(format)) / edgeTiles);
        if (tileSize < 2) throw new IllegalStateException("Device cannot render a light-function atlas tile");
        int size = tileSize * edgeTiles;
        if (texture == null || texture.width != size || texture.getColorTexture().getFormat() != format) {
            var next = new OwnedColorTarget("LumenFX light-function atlas", size, size, format);
            if (texture != null) texture.close();
            texture = next;
        }
        var tiles = new IdentityHashMap<MaterialDefinition, Integer>();
        var slots = new IdentityHashMap<ScenePointLight, Slot>();
        Slot directionalSlot = null;
        int count = 1;
        float span = (tileSize - 1f) / size;
        for (var light : lights) {
            boolean unityCookie = isUnityCookie(light);
            if (!unityCookie && maximumLights >= 0 && count >= maximumLights) break;
            boolean colored = unityCookie || unrealColor;
            var material = light.material();
            Integer index = tiles.get(material);
            if (index == null) {
                if (tiles.size() == edgeTiles * edgeTiles) continue;
                index = tiles.size();
                tiles.put(material, index);
                int x = index % edgeTiles * tileSize;
                int y = index / edgeTiles * tileSize;
                GpuBufferSlice tile;
                try (var mapped = encoder.transientMemory().allocateGpuMapped(16, limits.minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM)) {
                    var data = mapped.data().order(ByteOrder.nativeOrder());
                    data.putFloat(x / (float)size).putFloat(y / (float)size)
                            .putFloat(tileSize / (float)size).putFloat(tileSize);
                    tile = mapped.slice();
                }
                renderer.drawAtlas(encoder, material, texture, tile, Objects.requireNonNull(effectFrames.get(material)),
                        effectView, materialView, atmosphere, colored);
            }
            float u = (index % edgeTiles * tileSize + .5f) / size;
            float v = (index / edgeTiles * tileSize + .5f) / size;
            if (light.directional() != null) {
                var function = light.directional();
                directionalSlot = slot(function.settings(), function.worldToLight(), sourceCamera, 0, -1f, u, v, span, colored);
            } else {
                var point = Objects.requireNonNull(light.light());
                var shader = point.data().shader();
                var cone = shader.cone();
                var rect = shader.rect();
                int type = unityCookie ? cone == null ? 4 : 5 : cone != null ? 2 : rect != null ? 3 : 1;
                float tangent = cone != null ? cone.tangentOuter() : rect != null ? rect.lightFunctionTangent() : -1f;
                slots.put(point, slot(point.data().lightFunction(), point.data().worldToLight(new Matrix4d()),
                        sourceCamera, type, tangent, u, v, span, colored));
            }
            if (!unityCookie) count++;
        }
        return new Frame(texture.getColorTextureView(), Collections.unmodifiableMap(slots), directionalSlot);
    }

    private static boolean isUnityCookie(Candidate candidate) {
        return candidate.light() != null && candidate.light().data().lightFunction().unityCookie();
    }

    public static @Nullable Frame unityCookies(@Nullable Frame frame) {
        if (frame == null) return null;
        var cookies = new IdentityHashMap<ScenePointLight, Slot>();
        frame.slots().forEach((light, slot) -> {
            if (light.data().lightFunction().unityCookie()) cookies.put(light, slot);
        });
        return cookies.isEmpty() ? null : new Frame(frame.texture(), Collections.unmodifiableMap(cookies), null);
    }

    private static Slot slot(LightFunctionSettings settings, Matrix4dc worldToLight, Vec3 sourceCamera, int type,
                             float tangent, float minU, float minV, float span, boolean colored) {
        float disabledBrightness = Float.float16ToFloat(Float.floatToFloat16(settings.disabledBrightness()));
        return new Slot(translatedWorldToLight(settings.scale(), worldToLight, sourceCamera), settings.fadeDistance(), disabledBrightness, tangent, type,
                Math.round(minU * 65536f) / 65536f, Math.round(minV * 65536f) / 65536f, span, colored);
    }

    public static Matrix4f translatedWorldToLight(ScenePointLight light, Vec3 sourceCamera) {
        return translatedWorldToLight(light.data().lightFunction().scale(), light.data().worldToLight(new Matrix4d()), sourceCamera);
    }

    public static Matrix4f translatedWorldToLight(SceneDirectionalLight.Function function, Vec3 sourceCamera) {
        return translatedWorldToLight(function.settings().scale(), function.worldToLight(), sourceCamera);
    }

    private static Matrix4f translatedWorldToLight(Vec3 scale, Matrix4dc worldToLight, Vec3 sourceCamera) {
        return new Matrix4f(new Matrix4d().scaling(1.0 / scale.z, 1.0 / scale.y, 1.0 / scale.x)
                .mul(worldToLight).translate(sourceCamera.x, sourceCamera.y, sourceCamera.z));
    }

    @Override public void close() {
        if (texture != null) { texture.close(); texture = null; }
    }
}
