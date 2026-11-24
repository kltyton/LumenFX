package com.kltyton.lumenfx.client.render.light.forward;

import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.light.function.LightFunctionAtlas;
import com.kltyton.lumenfx.client.render.light.profile.LightProfileAtlas;
import com.kltyton.lumenfx.client.render.light.rect.RectLightLtcTables;
import com.kltyton.lumenfx.client.render.light.rect.RectLightTextureAtlas;
import com.kltyton.lumenfx.client.render.light.scene.PointLightProjection;
import com.kltyton.lumenfx.client.render.light.scene.SceneIndirectDiffuse;
import com.kltyton.lumenfx.client.render.light.scene.SkyReflectionBindings;
import com.kltyton.lumenfx.client.render.light.shadow.directional.DirectionalShadowRenderer;
import com.kltyton.lumenfx.client.render.light.shadow.PointShadowSceneRenderer.VolumeShadow;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraSceneInputs.Atmosphere;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.effect.render.light.ParticleLight;
import com.kltyton.lumenfx.effect.render.light.scene.LocalReflectionCaptures;
import com.kltyton.lumenfx.effect.render.light.scene.RectLightShape;
import com.kltyton.lumenfx.effect.render.light.scene.SceneDirectionalLight;
import com.kltyton.lumenfx.effect.render.light.scene.SceneIndirectLighting;
import com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.logging.LogUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/** One view's local-light list and screen/depth grid for transparent forward materials. */
public final class ForwardTranslucentLights implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String GRID = "LfxForwardLightGrid";
    private static final String HEADERS = "LfxForwardLightHeaders";
    private static final String INDICES = "LfxForwardLightIndices";
    private static final String LIGHTS = "LfxForwardLights";
    private static final String PRIMITIVE = "LfxForwardPrimitive";
    private static final String FUNCTIONS = "LfxForwardLightFunctions";
    private static final String ATLAS = "LfxForwardLightFunctionAtlas";
    private static final String PROFILES = "LfxForwardLightProfiles";
    private static final String SPOT_SHADOW_ATLAS = "LfxForwardSpotShadowAtlas";
    private static final String RECT_ATLAS = "LfxForwardRectAtlas";
    private static final int LIGHT_VECTORS = 14;
    private static final int LIGHT_STRIDE = LIGHT_VECTORS * 16;
    private GpuBuffer headers, indices, lights, functions;
    private GpuBuffer captureHeaders, captureIndices;
    private final LightProfileAtlas profileAtlas = new LightProfileAtlas();
    private final RectLightTextureAtlas rectAtlas = new RectLightTextureAtlas();
    private final ForwardSpotShadowAtlas spotShadowAtlas = new ForwardSpotShadowAtlas();
    private final ForwardPointShadowAtlas pointShadowAtlas = new ForwardPointShadowAtlas();
    private long maxTexels;
    private boolean previousOverflow;

    public record Features(boolean rectLights, boolean volumetricLightmap, boolean skyReflection, boolean preintegratedGf,
                           boolean skyBlend, boolean forceSkyBlend, boolean localCaptures, boolean forwardShading,
                           boolean desktopCaptureBlending, boolean clearCoatBottomNormal, LegacyEnergy legacyEnergy,
                           boolean roughDiffuse, boolean atmosphereTransmittance) {
        public static final Features NONE = new Features(false, false, false, false, false, false, false, false, true, false,
                LegacyEnergy.NONE, false, false);

        public boolean blendsLocalCaptures(boolean highQuality) {
            return desktopCaptureBlending && (!forwardShading || highQuality);
        }
    }

    public record LegacyEnergy(boolean enabled, boolean conserve, boolean preserve) {
        public static final LegacyEnergy NONE = new LegacyEnergy(false, false, false);

        public boolean needsTable() { return conserve || preserve; }

        private static LegacyEnergy resolve(NiagaraPlatformEnvironment platform, String shaderFormat) {
            if (shaderFormat.startsWith("OPENGL_")
                    || Objects.requireNonNull(platform.console("r.Material.EnergyConservation")).bits() <= 0) return NONE;
            return new LegacyEnergy(true, Objects.requireNonNull(platform.console("r.Shading.EnergyConservation")).bits() > 0,
                    Objects.requireNonNull(platform.console("r.Shading.EnergyPreservation")).bits() > 0);
        }
    }

    public static Features features(NiagaraPlatformEnvironment platform, SceneIndirectLighting indirect) {
        return features(platform, indirect, false);
    }

    public static Features features(NiagaraPlatformEnvironment platform, SceneIndirectLighting indirect,
                                    boolean atmosphereTransmittance) {
        var sky = SkyReflectionBindings.reflection(indirect.skyLight());
        boolean blend = sky != null && sky.blend() != null;
        String format = platform.shaderPlatforms().featureLevel();
        boolean desktopCaptureBlending = !format.contains("ES3_1") && !format.equals("METAL_SIM");
        return new Features(rectLightsEnabled(platform), SceneIndirectDiffuse.activeLightmap(indirect, platform) != null,
                sky != null, sky != null && sky.preintegrated(), blend,
                blend && Objects.requireNonNull(platform.console("r.ForwardShading.ForceSkyLightCubemapBlending")).bits() > 0,
                indirect.reflectionCaptures() != null, Objects.requireNonNull(platform.console("r.ForwardShading")).bits() != 0,
                desktopCaptureBlending, Objects.requireNonNull(platform.console("r.ClearCoatNormal")).bits() != 0,
                LegacyEnergy.resolve(platform, format),
                Objects.requireNonNull(platform.console("r.Material.RoughDiffuse")).bits() != 0, atmosphereTransmittance);
    }

    public record GridFrame(PointLightProjection lightView, List<ScenePointLight> visible, ForwardLightGrid grid,
                            ForwardLightGrid.Cells lights, ForwardLightGrid.@Nullable Cells captures) { }

    public record ReflectionGrid(GpuBufferSlice grid, GpuBufferSlice headers, GpuBufferSlice indices) {
        private static final String GRID = "LfxForwardCaptureGrid";
        private static final String HEADERS = "LfxForwardCaptureHeaders";
        private static final String INDICES = "LfxForwardCaptureIndices";

        static void layout(BindGroupLayout.Builder layout) {
            layout.withUniform(GRID, UniformType.UNIFORM_BUFFER)
                    .withUniform(HEADERS, UniformType.TEXEL_BUFFER, GpuFormat.RG32_UINT)
                    .withUniform(INDICES, UniformType.TEXEL_BUFFER, GpuFormat.R32_UINT);
        }

        void bind(RenderPass pass) {
            pass.setUniform(GRID, grid);
            pass.setUniform(HEADERS, headers);
            pass.setUniform(INDICES, indices);
        }
    }

    public record Sample(GpuBufferSlice grid, GpuBufferSlice headers, GpuBufferSlice indices, GpuBufferSlice lights,
                         GpuBufferSlice[] primitiveChannels, GpuBufferSlice functions, GpuTextureView functionAtlas,
                         GpuTextureView profiles, @Nullable GpuTextureView rectAtlas, @Nullable RectLightLtcTables rectTables,
                         GpuBufferSlice directionalShadow, DirectionalShadowRenderer.@Nullable Frame directionalFrame,
                         GpuTextureView directionalFallback, GpuTextureView spotShadowAtlas,
                         ForwardPointShadowAtlas.Frame pointShadowAtlas,
                         SurfaceTranslucencyEnvironment environment, @Nullable Atmosphere atmosphere,
                         @Nullable GpuBufferSlice atmosphereUniforms) {
        public void bind(RenderPass pass, int channels, @Nullable GpuBufferSlice primitiveCapture, boolean samplesAtmosphere) {
            pass.setUniform(GRID, grid);
            pass.setUniform(HEADERS, headers);
            pass.setUniform(INDICES, indices);
            pass.setUniform(LIGHTS, lights);
            pass.setUniform(PRIMITIVE, primitiveChannels[channels]);
            pass.setUniform(FUNCTIONS, functions);
            var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            pass.bindTexture(ATLAS, functionAtlas, linear);
            pass.bindTexture(PROFILES, profiles, linear);
            DirectionalShadowRenderer.bind(pass, directionalShadow, directionalFrame, directionalFallback);
            pass.bindTexture(SPOT_SHADOW_ATLAS, spotShadowAtlas, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pointShadowAtlas.bind(pass);
            environment.bind(pass, primitiveCapture);
            if (samplesAtmosphere) ForwardAtmosphereTransmittanceBinding.bind(pass, atmosphere, atmosphereUniforms);
            if (rectAtlas != null) {
                pass.bindTexture(RECT_ATLAS, rectAtlas, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR, true));
                Objects.requireNonNull(rectTables).bind(pass);
            }
        }
    }

    public static void layout(BindGroupLayout.Builder layout, Features features, boolean singleCapture) {
        layout.withUniform(GRID, UniformType.UNIFORM_BUFFER)
                .withUniform(PRIMITIVE, UniformType.UNIFORM_BUFFER)
                .withUniform(HEADERS, UniformType.TEXEL_BUFFER, GpuFormat.RG32_UINT)
                .withUniform(INDICES, UniformType.TEXEL_BUFFER, GpuFormat.R32_UINT)
                .withUniform(LIGHTS, UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT)
                .withUniform(FUNCTIONS, UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT)
                .withSampler(ATLAS).withSampler(PROFILES).withSampler(SPOT_SHADOW_ATLAS);
        DirectionalShadowRenderer.layout(layout);
        ForwardPointShadowAtlas.layout(layout);
        SurfaceTranslucencyEnvironment.layout(layout, features, singleCapture);
        if (features.rectLights()) {
            layout.withSampler(RECT_ATLAS);
            RectLightLtcTables.layout(layout);
        }
    }

    public static boolean lightFunctionsEnabled(NiagaraPlatformEnvironment platform) {
        return Objects.requireNonNull(platform.console("r.Translucent.UsesLightFunctionAtlas")).bits() > 0;
    }

    public static boolean rectLightsEnabled(NiagaraPlatformEnvironment platform) {
        return Objects.requireNonNull(platform.console("r.Translucent.UsesRectLights")).bits() > 0;
    }

    public GridFrame prepareGrid(NiagaraPlatformEnvironment platform, List<ParticleLight> source, List<ScenePointLight> components,
                                 Matrix4fc projection, Matrix4fc view, Vec3 camera, int width, int height, float nearClipCm,
                                 @Nullable LocalReflectionCaptures captures) {
        boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        var lightView = new PointLightProjection(projection, view, camera, width, height, zeroToOne);
        var visible = components.stream().filter(lightView::visibleBounds).toList();
        int lightCount = Math.addExact(source.size(), visible.size());
        var settings = ForwardLightGrid.Settings.read(platform);
        List<ForwardLightGrid.Bounds> bounds = new ArrayList<>(lightCount);
        var point = new Vector3f();
        for (ParticleLight light : source) {
            var position = light.position().subtract(camera);
            view.transformPosition(point.set((float)position.x, (float)position.y, (float)position.z));
            bounds.add(new ForwardLightGrid.Bounds(point.x * 100, point.y * 100, -point.z * 100, light.radius()));
        }
        float furthestComponent = 0f;
        Vec3 sourceCamera = new Vec3(camera.x * 100, camera.z * 100, camera.y * 100);
        for (ScenePointLight light : visible) {
            var shader = light.data().shader();
            var position = shader.worldPosition().subtract(sourceCamera);
            float radius = light.data().radius();
            view.transformPosition(point.set((float)position.x * .01f, (float)position.z * .01f, (float)position.y * .01f));
            float x = point.x * 100, y = point.y * 100, depth = -point.z * 100;
            var cone = shader.cone();
            ForwardLightGrid.Cone cullingCone = null;
            if (cone != null) {
                if (cone.cosineOuter() > (float)Math.cos(Math.PI / 2.001f)) {
                    var direction = shader.direction();
                    view.transformDirection(point.set((float)-direction.x, (float)-direction.z, (float)-direction.y));
                    int tangentBits = Float.floatToRawIntBits(cone.tangentOuter()) & 0xfffffff8;
                    cullingCone = new ForwardLightGrid.Cone(new Vec3(point.x, point.y, -point.z), Float.intBitsToFloat(tangentBits));
                }
                position = position.subtract(shader.direction().scale(cone.boundsCenterOffset(radius)));
                float boundRadius = (float) cone.boundsRadius(radius);
                view.transformPosition(point.set((float)position.x * .01f, (float)position.z * .01f, (float)position.y * .01f));
                furthestComponent = Math.max(furthestComponent, -point.z * 100 + boundRadius);
            } else {
                furthestComponent = Math.max(furthestComponent, depth + radius);
            }
            var planes = settings.refineRectBounds() && shader.rect() != null
                    ? ForwardRectLightBounds.planes(light.data(), shader.rect(), view, sourceCamera, new Vec3(x, y, depth))
                    : List.<ForwardLightGrid.Plane>of();
            bounds.add(new ForwardLightGrid.Bounds(x, y, depth, radius, cullingCone, planes));
        }
        List<ForwardLightGrid.Bounds> captureBounds = new ArrayList<>();
        if (captures != null) for (var capture : captures.captures()) {
            var position = capture.position().subtract(sourceCamera);
            view.transformPosition(point.set((float) position.x * .01f, (float) position.z * .01f, (float) position.y * .01f));
            float depth = -point.z * 100f;
            captureBounds.add(new ForwardLightGrid.Bounds(point.x * 100f, point.y * 100f, depth, capture.radius()));
            furthestComponent = Math.max(furthestComponent, depth + capture.radius());
        }
        var grid = ForwardLightGrid.create(width, height, nearClipCm, furthestComponent, settings);
        int capacity = Math.multiplyExact(grid.cells(), settings.entriesPerCell());
        int lightCapacity = settings.sharedCapacity() ? Math.multiplyExact(capacity, 2) : capacity;
        var lights = grid.cull(projection, bounds, lightCapacity);
        var reflections = captures == null ? null : grid.cull(projection, captureBounds,
                settings.sharedCapacity() ? lightCapacity - lights.indexCount() : capacity);
        boolean overflowed = lights.overflowed() || reflections != null && reflections.overflowed();
        if (overflowed && !previousOverflow)
            LOGGER.warn("Forward light grid exceeded its culling capacity. Increase r.Forward.MaxCulledLightsPerCell.");
        previousOverflow = overflowed;
        return new GridFrame(lightView, visible, grid, lights, reflections);
    }

    public @Nullable ReflectionGrid prepareReflections(CommandEncoder encoder, GridFrame lightingGrid, Matrix4fc view, boolean glViewport) {
        var cells = lightingGrid.captures();
        if (cells == null) return null;
        prepareLimits();
        requireTexels(lightingGrid.grid().cells());
        int count = Math.max(1, cells.indexCount());
        requireTexels(count);
        long headerBytes = (long) cells.headers().length * Integer.BYTES;
        long indexBytes = (long) count * Integer.BYTES;
        captureHeaders = reserve(captureHeaders, "LumenFX forward reflection capture headers", headerBytes);
        captureIndices = reserve(captureIndices, "LumenFX forward reflection capture indices", indexBytes);
        var headers = upload(encoder, captureHeaders, headerBytes, data -> data.asIntBuffer().put(cells.headers()));
        var indices = upload(encoder, captureIndices, indexBytes, data -> {
            if (cells.indexCount() == 0) data.putInt(0);
            else data.asIntBuffer().put(cells.indices(), 0, count);
        });
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        try (var upload = encoder.transientMemory().allocateGpuMapped(96, alignment, GpuBuffer.USAGE_UNIFORM)) {
            lightingGrid.grid().write(upload.data().order(ByteOrder.nativeOrder()), glViewport, view);
            return new ReflectionGrid(upload.slice(), headers, indices);
        }
    }

    public Sample prepare(CommandEncoder encoder, NiagaraPlatformEnvironment platform, List<ParticleLight> source,
                          GridFrame lightingGrid, List<SceneDirectionalLight> directional, Matrix4fc view, Vec3 camera,
                          float exposure, boolean glViewport, LightFunctionAtlas.@Nullable Frame atlas,
                          DirectionalShadowRenderer.@Nullable Frame shadowFrame, GpuTextureView fallback,
                          Map<ScenePointLight, VolumeShadow> localShadows,
                          SurfaceTranslucencyEnvironment environment, @Nullable Atmosphere atmosphere) {
        RenderSystem.assertOnRenderThread();
        var device = RenderSystem.getDevice();
        prepareLimits();
        var lightView = lightingGrid.lightView();
        var visible = lightingGrid.visible();
        var spotShadows = spotShadowAtlas.prepare(encoder, visible, localShadows);
        var pointShadows = pointShadowAtlas.prepare(encoder, visible, localShadows,
                device.getDeviceInfo().limits().maxTextureSize());
        var profiles = profileAtlas.prepare(encoder, platform, visible);
        boolean rectLights = rectLightsEnabled(platform);
        var rectSources = rectLights ? rectAtlas.prepare(encoder, platform, visible, fallback) : null;
        Map<ScenePointLight, RectLightTextureAtlas.Slot> rectSlots = rectSources == null ? Map.of() : rectSources.slots();
        var rectTables = rectLights ? RectLightLtcTables.resolve() : null;
        if (!rectLights) rectAtlas.close();
        var functionSlots = new ArrayList<LightFunctionAtlas.Slot>();
        var functionIndices = new IdentityHashMap<ScenePointLight, Integer>();
        int directionalFunction = -1;
        if (atlas != null && atlas.directional() != null) {
            directionalFunction = 0;
            functionSlots.add(atlas.directional());
        }
        if (atlas != null) for (ScenePointLight light : visible) {
            var slot = atlas.slots().get(light);
            if (slot != null) {
                functionIndices.put(light, functionSlots.size());
                functionSlots.add(slot);
            }
        }
        int lightCount = Math.addExact(source.size(), visible.size());
        requireTexels((long) Math.max(1, lightCount) * LIGHT_VECTORS);
        var grid = lightingGrid.grid();
        requireTexels(grid.cells());
        var cells = lightingGrid.lights();
        var sourceCamera = new Vec3(camera.x * 100, camera.z * 100, camera.y * 100);
        int indexCount = Math.max(1, cells.indexCount());
        requireTexels(indexCount);

        long headerBytes = (long)cells.headers().length * Integer.BYTES;
        long indexBytes = (long)indexCount * Integer.BYTES;
        long lightBytes = (long)Math.max(1, lightCount) * LIGHT_STRIDE;
        headers = reserve(headers, "LumenFX forward light grid headers", headerBytes);
        indices = reserve(indices, "LumenFX forward light grid indices", indexBytes);
        lights = reserve(lights, "LumenFX forward light records", lightBytes);
        var headerData = upload(encoder, headers, headerBytes, data -> data.asIntBuffer().put(cells.headers()));
        var indexData = upload(encoder, indices, indexBytes, data -> {
            if (cells.indexCount() == 0) data.putInt(0);
            else data.asIntBuffer().put(cells.indices(), 0, indexCount);
        });
        var lightData = upload(encoder, lights, lightBytes, data -> {
            if (lightCount == 0) {
                for (int i = 0; i < LIGHT_STRIDE / Integer.BYTES; i++) data.putInt(0);
            } else {
                for (ParticleLight light : source) writeLight(data, light, camera, exposure);
                for (ScenePointLight light : visible) {
                    var pointShadow = pointShadows.slots().get(light);
                    writeComponent(data, light, sourceCamera, exposure, lightView.colorFade(light),
                            functionIndices.getOrDefault(light, -1), profiles.index(light.iesTexture()), rectSlots.get(light),
                            spotShadows.slots().get(light), pointShadow == null ? -1 : pointShadow.index());
                }
            }
        });
        GpuBufferSlice functionData = lightData;
        if (!functionSlots.isEmpty()) {
            long bytes = (long)functionSlots.size() * LightFunctionAtlas.Slot.BYTES;
            requireTexels(bytes / 16);
            functions = reserve(functions, "LumenFX forward light function projections", bytes);
            functionData = upload(encoder, functions, bytes, data -> functionSlots.forEach(slot -> slot.write(data)));
        }
        int alignment = device.getDeviceInfo().limits().minUniformOffsetAlignment();
        var channels = new GpuBufferSlice[8];
        int stride = Math.ceilDiv(16, alignment) * alignment;
        try (var memory = encoder.transientMemory().allocateGpuMapped(8L * stride, alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = memory.data().order(ByteOrder.nativeOrder());
            for (int mask = 0; mask < channels.length; mask++) {
                int offset = mask * stride;
                data.position(offset).putInt(mask).putInt(0).putInt(0).putInt(0);
                var slice = memory.slice();
                channels[mask] = new GpuBufferSlice(slice.buffer(), slice.offset() + offset, 16);
            }
        }
        try (var memory = encoder.transientMemory().allocateGpuMapped(160, alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = memory.data().order(ByteOrder.nativeOrder());
            grid.write(data, glViewport, view);
            float distance = Float.intBitsToFloat(Objects.requireNonNull(platform.console("r.SSS.SubSurfaceColorAsTansmittanceAtDistance")).bits());
            data.position(96).putFloat(Math.clamp(distance, .05f, 1f)).putFloat(directionalFunction).putFloat(0).putFloat(0);
            writeDirectional(data, SceneDirectionalLight.selectForward(directional), atmosphere);
            return new Sample(memory.slice(), headerData, indexData, lightData, channels, functionData,
                    functionSlots.isEmpty() ? fallback : Objects.requireNonNull(atlas).texture(), profiles.texture(),
                    rectSources == null ? null : rectSources.texture(), rectTables,
                    DirectionalShadowRenderer.uniforms(encoder, shadowFrame, DirectionalShadowRenderer.FORWARD_CASCADE_LIMIT),
                    shadowFrame, fallback, spotShadows.texture(), pointShadows, environment, atmosphere,
                    ForwardAtmosphereTransmittanceBinding.write(encoder, atmosphere));
        }
    }

    private void prepareLimits() {
        if (maxTexels == 0) try (var runtime = ComputeRuntime.create(RenderSystem.getDevice())) {
            maxTexels = runtime.maxTexelBufferElements();
        }
    }

    private void requireTexels(long texels) {
        if (texels > maxTexels)
            throw new IllegalArgumentException("Forward light grid requires " + texels + " texels but the device supports " + maxTexels);
    }

    private static void writeDirectional(ByteBuffer data, @Nullable SceneDirectionalLight light, @Nullable Atmosphere atmosphere) {
        if (light == null || !light.affectsTranslucency()) {
            for (int i = 0; i < 12; i++) data.putInt(0);
            return;
        }
        Vec3 direction = light.direction(), color = atmosphere == null ? light.color() : atmosphere.directionalShaderColor(light);
        data.putFloat((float) direction.x).putFloat((float) direction.y).putFloat((float) direction.z).putFloat(light.sourceRadius());
        data.putFloat((float) color.x).putFloat((float) color.y).putFloat((float) color.z).putFloat(light.softSourceRadius());
        data.putFloat(light.diffuseScale()).putFloat(light.specularScale()).putInt(light.lightingChannels()).putInt(1);
    }

    private static void writeLight(ByteBuffer data, ParticleLight light, Vec3 camera, float exposure) {
        var position = light.position().subtract(camera).scale(100);
        data.putFloat((float)position.x).putFloat((float)position.z).putFloat((float)position.y)
                .putFloat(1f / Math.max(light.radius(), 0.0001f));
        writeColor(data, light.color(), exposure, light.inverseExposureBlend(), light.exponent());
        data.putFloat(forwardScale(light.diffuseScale())).putFloat(forwardScale(light.specularScale()))
                .putFloat(light.affectsTranslucency() ? 7 : 0).putFloat(half(light.exponent()) == 0 ? 1 : 0);
        writeShape(data, Vec3.ZERO, new Vec3(1, 0, 0), 0f, 0f, 0f, -2f, 1f, 32);
        writeFeatures(data, -1, -1, null, null);
        writeLocalShadow(data, null, -1);
    }

    private static void writeComponent(ByteBuffer data, ScenePointLight light, Vec3 sourceCamera, float exposure, float fade,
                                       int functionIndex, int profileIndex, RectLightTextureAtlas.@Nullable Slot rectSlot,
                                       ForwardSpotShadowAtlas.@Nullable Slot spotShadow, int pointShadowIndex) {
        var shader = light.data().shader();
        writeVector(data, shader.worldPosition().subtract(sourceCamera), shader.inverseRadius());
        writeColor(data, shader.color().scale(fade), exposure, shader.inverseExposureBlend(), shader.falloffExponent());
        data.putFloat(forwardScale(shader.diffuseScale())).putFloat(forwardScale(shader.specularScale()))
                .putFloat(shader.affectsTranslucency() ? light.data().lightingChannels() : 0)
                .putFloat(half(shader.falloffExponent()) == 0 ? 1 : 0);
        var cone = shader.cone();
        var rect = shader.rect();
        writeShape(data, shader.direction(), shader.tangent(),
                rect == null ? shader.sourceRadius() : rect.halfWidth(),
                rect == null ? shader.sourceLength() : rect.halfHeight(), shader.softSourceRadius(),
                rect != null ? -3f : cone == null ? -2f : cone.cosineOuter(),
                cone == null ? 1f : cone.inverseCosineDifference(),
                rectSlot == null ? 32 : rectSlot.maxMip());
        writeFeatures(data, functionIndex, profileIndex, rect, rectSlot);
        writeLocalShadow(data, spotShadow, pointShadowIndex);
    }

    private static void writeLocalShadow(ByteBuffer data, ForwardSpotShadowAtlas.@Nullable Slot slot, int pointShadowIndex) {
        if (slot == null) {
            for (int vector = 0; vector < 5; vector++)
                data.putFloat(0f).putFloat(0f).putFloat(0f).putFloat(0f);
            data.putFloat(pointShadowIndex).putFloat(0f).putFloat(0f).putFloat(0f);
            return;
        }
        var matrices = slot.shadow().matrices();
        var projection = ByteBuffer.allocate(com.kltyton.lumenfx.client.render.light.shadow.PointShadowMatrices.BYTES).order(data.order());
        matrices.writeSampling(projection);
        projection.flip();
        projection.limit(64);
        data.put(projection);
        data.putFloat(slot.x()).putFloat(slot.y()).putFloat(slot.size()).putFloat(slot.shadow().strength());
        var depth = ByteBuffer.allocate(2 * Float.BYTES).order(data.order());
        matrices.writeLinearDepth(depth);
        depth.flip();
        data.put(depth).putFloat(matrices.spotSettings().casterBias()).putFloat(matrices.spotSettings().slopeBias());
    }

    private static void writeShape(ByteBuffer data, Vec3 direction, Vec3 tangent, float radius, float length,
                                   float softRadius, float outerCosine, float inverseCosineDifference, int maxRectMip) {
        writeVector(data, direction, half(radius));
        writeVector(data, tangent, half(length));
        data.putFloat(half(softRadius)).putFloat(outerCosine).putFloat(inverseCosineDifference).putFloat(maxRectMip);
    }

    private static void writeFeatures(ByteBuffer data, int functionIndex, int profileIndex, @Nullable RectLightShape rect,
                                      RectLightTextureAtlas.@Nullable Slot rectSlot) {
        data.putFloat(functionIndex).putFloat(profileIndex)
                .putFloat(rect == null ? 0f : (int)(rect.barnCosine() * 1023f) / 1023f)
                .putFloat(rect == null ? 0f : half(rect.barnLength()));
        if (rectSlot == null) data.putFloat(0f).putFloat(0f).putFloat(0f).putFloat(0f);
        else rectSlot.write(data);
    }

    private static void writeVector(ByteBuffer data, Vec3 vector, float last) {
        data.putFloat((float)vector.x).putFloat((float)vector.y).putFloat((float)vector.z).putFloat(last);
    }

    private static void writeColor(ByteBuffer data, Vec3 input, float exposure, float inverseExposureBlend, float exponent) {
        if (inverseExposureBlend != 0 && !Float.isFinite(exposure))
            throw new IllegalArgumentException("Forward lights need the current view exposure");
        var color = input.scale(inverseExposureBlend == 0 || exposure <= 0 ? 1 : Math.pow(exposure, -inverseExposureBlend));
        double length = color.length();
        double directionScale = length > 0 ? 1023 / length : 0;
        float magnitude = (float)(length / 1023);
        data.putFloat(((int)(color.x * directionScale) & 1023) * magnitude)
                .putFloat(((int)(color.y * directionScale) & 1023) * magnitude)
                .putFloat(((int)(color.z * directionScale) & 1023) * magnitude)
                .putFloat(half(exponent));
    }

    private static float half(float value) {
        return Float.float16ToFloat(Float.floatToFloat16(value));
    }

    private static float forwardScale(float scale) {
        float encoded = scale > 1f ? Math.max(1f / scale, 1f / 1023f) : scale;
        float decoded = (int)Math.clamp(encoded * 1023f, 0f, 1023f) / 1023f;
        return scale > 1f ? 1f / decoded : decoded;
    }

    private static GpuBuffer reserve(GpuBuffer buffer, String name, long bytes) {
        if (buffer != null && buffer.size() >= bytes) return buffer;
        var device = RenderSystem.getDevice();
        if (bytes > device.getDeviceInfo().limits().maxMemoryAllocationSize())
            throw new IllegalArgumentException("Forward light buffer exceeds device allocation limit: " + name);
        var replacement = device.createBuffer(() -> name, GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST, bytes);
        if (buffer != null) buffer.close();
        return replacement;
    }

    private static GpuBufferSlice upload(CommandEncoder encoder, GpuBuffer target, long bytes, Consumer<ByteBuffer> fill) {
        GpuBufferSlice staging;
        try (var mapped = encoder.transientMemory().allocateGpuMapped(bytes, 4, GpuBuffer.USAGE_COPY_SRC)) {
            fill.accept(mapped.data().order(ByteOrder.nativeOrder()));
            staging = mapped.slice();
        }
        var output = target.slice(0, bytes);
        encoder.copyToBuffer(staging, output);
        return output;
    }

    @Override public void close() {
        if (headers != null) { headers.close(); headers = null; }
        if (indices != null) { indices.close(); indices = null; }
        if (lights != null) { lights.close(); lights = null; }
        if (functions != null) { functions.close(); functions = null; }
        if (captureHeaders != null) { captureHeaders.close(); captureHeaders = null; }
        if (captureIndices != null) { captureIndices.close(); captureIndices = null; }
        profileAtlas.close();
        rectAtlas.close();
        spotShadowAtlas.close();
        pointShadowAtlas.close();
        maxTexels = 0;
        previousOverflow = false;
    }
}
