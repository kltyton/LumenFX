package com.kltyton.lumenfx.client.render.light.shadow.directional;

import com.kltyton.lumenfx.client.render.light.shadow.geometry.DynamicPointShadowRenderer;
import com.kltyton.lumenfx.client.render.light.shadow.geometry.DynamicShadowScene;
import com.kltyton.lumenfx.client.render.light.shadow.geometry.TerrainPointShadowRenderer;
import com.kltyton.lumenfx.client.render.light.shadow.geometry.TerrainShadowScene;
import com.kltyton.lumenfx.client.render.texture.ShadowDepthTarget;
import com.kltyton.lumenfx.client.resource.texture.EffectTextures;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.effect.render.light.scene.SceneDirectionalLight;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/** Rasterizes view-dependent directional depth from the current world's real caster submissions. */
public final class DirectionalShadowRenderer implements AutoCloseable {
    public static final int FORWARD_CASCADE_LIMIT = 4;
    public static final int VOLUME_CASCADE_LIMIT = 20;
    public static final String UNIFORM = "LfxForwardDirectionalShadow";
    public static final String DEPTH = "LfxForwardDirectionalDepth";
    private static final String STATIC_DEPTH = "LfxStaticDirectionalDepth";
    private static final Matrix4d HOST_TO_SOURCE = new Matrix4d().m11(0).m22(0).m12(1).m21(1).scale(100);

    @FunctionalInterface
    public interface ParticleCasters {
        void draw(ShadowDepthTarget target, DirectionalShadowMatrices matrices, int cascade);
    }

    public record StaticFrame(GpuTextureView texture, Matrix4fc matrix) { }
    public record Frame(@Nullable GpuTextureView texture, @Nullable DirectionalShadowMatrices matrices,
                        @Nullable StaticFrame staticShadow, float fadeScale, float fadeOffset, boolean allCascades) { }

    private final TerrainPointShadowRenderer terrain = new TerrainPointShadowRenderer();
    private final DynamicPointShadowRenderer dynamic = new DynamicPointShadowRenderer();
    private final int cascadeLimit;
    private @Nullable DirectionalShadowAtlas atlas;

    public DirectionalShadowRenderer() { this(FORWARD_CASCADE_LIMIT); }
    public DirectionalShadowRenderer(int cascadeLimit) {
        if (cascadeLimit < 1 || cascadeLimit > VOLUME_CASCADE_LIMIT)
            throw new IllegalArgumentException("Directional shadow cascade limit is out of range");
        this.cascadeLimit = cascadeLimit;
    }

    public @Nullable Frame prepare(NiagaraPlatformEnvironment platform, @Nullable SceneDirectionalLight light,
                                    RenderTarget scene, Matrix4fc projection, Matrix4fc modelView, Vec3 camera,
                                    float nearCm, @Nullable ParticleCasters particles) {
        return prepare(platform, light, scene, projection, modelView, camera, nearCm, particles, true);
    }

    public @Nullable Frame prepare(NiagaraPlatformEnvironment platform, @Nullable SceneDirectionalLight light,
                                    RenderTarget scene, Matrix4fc projection, Matrix4fc modelView, Vec3 camera,
                                    float nearCm, @Nullable ParticleCasters particles, boolean drawCascades) {
        RenderSystem.assertOnRenderThread();
        if (light == null) return null;
        StaticFrame baked = platform.staticLightingAllowed() ? staticFrame(light, camera) : null;
        if (!drawCascades || light.cascades() == null) return staticOnly(baked);
        var settings = light.cascades();
        int maximumCascades = Math.clamp(integer(platform, "r.Shadow.CSM.MaxCascades"), 0, 10);
        int nearCount = settings.count();
        float nearRadius = settings.distance();
        if (settings.unbuiltPreview()) {
            nearCount = integer(platform, "r.Shadow.UnbuiltPreviewInGame") != 0
                    ? integer(platform, "r.Shadow.UnbuiltNumWholeSceneDynamicShadowCascades") : 0;
            nearRadius = scalar(platform, "r.Shadow.UnbuiltWholeSceneDynamicShadowRadius");
        }
        int count = Math.clamp(nearCount, 0, maximumCascades);
        float nearCascadeEnd = maximumCascades == 0 ? 0
                : nearRadius * Math.clamp(scalar(platform, "r.Shadow.DistanceScale"), 0, 2);
        if (nearCascadeEnd <= nearCm) count = 0;
        boolean hasFarCascades = settings.farCount() > 0 && settings.farDistance() - settings.distance() > 100;
        int farCount = hasFarCascades
                ? Math.clamp(settings.farCount(), 0, Math.clamp(integer(platform, "r.Shadow.MaxNumFarShadowCascades"), 0, 10)) : 0;
        float farOverride = scalar(platform, "r.Shadow.FarShadowDistanceOverride");
        float farEnd = farOverride > 0 ? farOverride : hasFarCascades ? settings.farDistance() : 0;
        if (farEnd <= Math.max(nearCascadeEnd, nearCm)) farCount = 0;
        int renderedCount = Math.min(count + farCount, cascadeLimit);
        if (renderedCount == 0) return staticOnly(baked);
        int maximum = DirectionalShadowAtlas.maximumTileSize(renderedCount);
        if (maximum < 32) throw new IllegalStateException("Device cannot allocate the minimum directional shadow atlas");
        int size = Math.clamp(integer(platform, "r.Shadow.MaxCSMResolution"), 32, maximum);
        var target = target(size, renderedCount);
        float transition = settings.transitionFraction() * Math.clamp(scalar(platform, "r.Shadow.CSM.TransitionScale"), 0, 2);
        boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        double[] splits = new double[count + farCount + 1];
        float exponent = settings.unbuiltPreview() ? 4f : Math.clamp(settings.distributionExponent(), .1f, 10f);
        splits[0] = nearCm * .01;
        split(splits, 0, count, splits[0], nearCascadeEnd * .01, exponent);
        split(splits, count, farCount, nearCascadeEnd * .01, farEnd * .01, exponent);
        var matrices = new DirectionalShadowMatrices(camera, light.direction(), projection, modelView, splits, transition,
                scalar(platform, "r.Shadow.CSMDepthBias") * settings.depthBias(),
                scalar(platform, "r.Shadow.CSMSlopeScaleDepthBias") * settings.slopeBias(),
                scalar(platform, "r.Shadow.ShadowMaxSlopeScaleDepthBias"), settings.biasDistribution(), zeroToOne, target,
                light.lightingChannels(), light.identity(), cascadeLimit);
        var minecraft = Minecraft.getInstance();
        var renderer = minecraft.levelRenderer;
        var sampler = renderer.chunkLayerSampler;
        var blocks = minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        Set<GpuTexture> worldDepths = Collections.newSetFromMap(new IdentityHashMap<>());
        addDepth(worldDepths, scene);
        addDepth(worldDepths, minecraft.gameRenderer.mainRenderTarget());
        addDepth(worldDepths, renderer.itemEntityTarget());
        addDepth(worldDepths, renderer.weatherTarget());
        target.clear();
        try {
            for (int index = 0; index < target.viewCount(); index++) {
                var frustum = matrices.frustum(index);
                terrain.drawView(TerrainShadowScene.collectDirectional(renderer, camera, frustum), target, matrices, blocks, sampler, index);
                dynamic.drawView(DynamicShadowScene.collect(minecraft, renderer, camera, frustum), target, matrices, worldDepths, index);
                if (particles != null) particles.draw(target, matrices, index);
            }
        } finally { dynamic.endFrame(); }
        float end = Math.max(nearCascadeEnd, farEnd) * .01f;
        float range = end * settings.distanceFadeFraction() * scalar(platform, "r.Shadow.CSMShadowDistanceFadeoutMultiplier");
        float fadeScale = 1f / Math.max(range, .000001f);
        return new Frame(target.sampled(), matrices, baked, fadeScale, -(end - range) * fadeScale, renderedCount == count + farCount);
    }

    private static @Nullable Frame staticOnly(@Nullable StaticFrame baked) {
        return baked == null ? null : new Frame(null, null, baked, 0, 1, true);
    }

    private static @Nullable StaticFrame staticFrame(SceneDirectionalLight light, Vec3 camera) {
        var shadow = light.staticShadow();
        if (shadow == null) return null;
        var texture = Objects.requireNonNull(EffectTextures.get(shadow.texture()), "Missing prepared directional shadow depth " + shadow.texture());
        var translated = new Matrix4d(shadow.worldToLight()).translate(camera.x * 100, camera.z * 100, camera.y * 100).mul(HOST_TO_SOURCE);
        return new StaticFrame(texture.view(), new Matrix4f(translated));
    }

    private static void split(double[] splits, int start, int count, double near, double end, float exponent) {
        double weight = 1, total = 0;
        for (int index = 0; index < count; index++, weight *= exponent) total += weight;
        double accumulated = 0;
        weight = 1;
        for (int index = 1; index <= count; index++, weight *= exponent) {
            accumulated += weight;
            splits[start + index] = near + (end - near) * accumulated / total;
        }
    }

    public static GpuBufferSlice uniforms(CommandEncoder encoder, @Nullable Frame frame, int cascadeLimit) {
        int bakedOffset = cascadeLimit * 96 + 32;
        int bytes = bakedOffset + 80;
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        try (var mapped = encoder.transientMemory().allocateGpuMapped(bytes, alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            var matrices = frame == null ? null : frame.matrices();
            int count = matrices == null ? 0 : Math.min(cascadeLimit, matrices.cascades().size());
            for (int offset = 0; offset < bytes; offset += Integer.BYTES) data.putInt(offset, 0);
            data.putInt(cascadeLimit * 96, count);
            if (frame != null) {
                data.putFloat(cascadeLimit * 96 + 16, frame.fadeScale()).putFloat(cascadeLimit * 96 + 20, frame.fadeOffset());
                var baked = frame.staticShadow();
                if (baked != null) {
                    baked.matrix().get(bakedOffset, data);
                    data.putInt(bakedOffset + 64, 1);
                }
            }
            if (matrices != null) {
                for (int index = 0; index < count; index++) {
                    var cascade = matrices.cascades().get(index);
                    cascade.sampling().get(index * 64, data);
                    cascade.uvBounds().get(cascadeLimit * 64 + index * 16, data);
                    cascade.range().get(cascadeLimit * 80 + index * 16, data);
                }
            }
            return mapped.slice();
        }
    }

    public static void layout(BindGroupLayout.Builder layout) {
        layout.withUniform(UNIFORM, UniformType.UNIFORM_BUFFER).withSampler(DEPTH).withSampler(STATIC_DEPTH);
    }

    public static void bind(RenderPass pass, GpuBufferSlice uniforms, @Nullable Frame frame, GpuTextureView fallback) {
        var baked = frame == null ? null : frame.staticShadow();
        var depth = frame == null ? null : frame.texture();
        var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        pass.setUniform(UNIFORM, uniforms);
        pass.bindTexture(DEPTH, depth == null ? fallback : depth, sampler);
        pass.bindTexture(STATIC_DEPTH, baked == null ? fallback : baked.texture(), sampler);
    }

    private DirectionalShadowAtlas target(int size, int count) {
        if (atlas == null || atlas.size() != size || atlas.viewCount() != count) {
            var old = atlas;
            atlas = new DirectionalShadowAtlas(size, count);
            if (old != null) old.close();
        }
        return atlas;
    }

    private static void addDepth(Set<GpuTexture> depths, @Nullable RenderTarget target) {
        if (target != null && target.getDepthTexture() != null) depths.add(target.getDepthTexture());
    }
    private static int integer(NiagaraPlatformEnvironment platform, String name) {
        return Objects.requireNonNull(platform.console(name)).bits();
    }
    private static float scalar(NiagaraPlatformEnvironment platform, String name) {
        return Float.intBitsToFloat(integer(platform, name));
    }

    @Override public void close() {
        terrain.close();
        dynamic.close();
        if (atlas != null) { atlas.close(); atlas = null; }
    }
}
