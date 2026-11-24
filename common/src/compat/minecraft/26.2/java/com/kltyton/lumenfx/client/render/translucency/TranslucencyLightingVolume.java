package com.kltyton.lumenfx.client.render.translucency;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.render.light.ParticleLightBuffers;
import com.kltyton.lumenfx.client.render.light.function.LightFunctionAtlas;
import com.kltyton.lumenfx.client.render.light.function.LightFunctionRenderer;
import com.kltyton.lumenfx.client.render.light.function.LightFunctionView;
import com.kltyton.lumenfx.client.render.light.function.LightFunctionRenderer.VolumeMaterial;
import com.kltyton.lumenfx.client.render.light.shadow.PointShadowSceneRenderer.VolumeShadow;
import com.kltyton.lumenfx.client.render.light.shadow.VolumeLocalShadowBindings;
import com.kltyton.lumenfx.client.render.light.shadow.VolumeLocalShadowBindings.Kind;
import com.kltyton.lumenfx.client.render.light.shadow.directional.DirectionalShadowRenderer;
import com.kltyton.lumenfx.client.resource.texture.lighting.BlueNoiseTextures;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.effect.render.light.ParticleLight;
import com.kltyton.lumenfx.effect.render.light.scene.SceneDirectionalLight;
import com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/** Produces the two compressed SH cascades consumed by translucent particle materials. */
public final class TranslucencyLightingVolume implements AutoCloseable {
    private static final GpuFormat FORMAT = GpuFormat.RGBA16_FLOAT;
    private static final String[] AMBIENT = {"TlvAmbientInner", "TlvAmbientOuter"};
    private static final String[] DIRECTION = {"TlvDirectionInner", "TlvDirectionOuter"};
    public static final String UNIFORM = "TranslucencyLightingVolume";

    public record Settings(boolean enabled, int dimension, float innerDistance, float outerDistance,
                           boolean adaptToPerspective, float minimumFov, float fovSnap, boolean spatialFilter,
                           boolean accurateRectLights, boolean temporal, float historyWeight, float positionOffsetRadius) {
        public static Settings read(NiagaraPlatformEnvironment environment) {
            return new Settings(bits(environment, "") != 0, bits(environment, ".Dim"),
                    number(environment, ".InnerDistance"), number(environment, ".OuterDistance"),
                    bits(environment, ".AdaptToPerspective") > 0, number(environment, ".MinFOV"),
                    number(environment, ".FOVSnapFactor"), bits(environment, ".Blur") != 0, bits(environment, ".AccurateRectLights") != 0,
                    bits(environment, ".Temporal") != 0, Math.clamp(number(environment, ".Temporal.HistoryWeight"), 0f, 1f),
                    Math.max(0f, number(environment, ".PositionOffsetRadius")));
        }

        private static int bits(NiagaraPlatformEnvironment environment, String suffix) {
            return Objects.requireNonNull(environment.console("r.TranslucencyLightingVolume" + suffix)).bits();
        }

        private static float number(NiagaraPlatformEnvironment environment, String suffix) {
            return Float.intBitsToFloat(bits(environment, suffix));
        }
    }

    public record LocalLights(List<ScenePointLight> lights, Map<ScenePointLight, VolumeShadow> shadows,
                              LightFunctionAtlas.@Nullable Frame functions, Map<ScenePointLight, VolumeMaterial> materials) {
        public static final LocalLights EMPTY = new LocalLights(List.of(), Map.of(), null, Map.of());
    }
    public record DirectionalLights(List<SceneDirectionalLight> lights, Map<SceneDirectionalLight, VolumeMaterial> materials,
                                    Function<SceneDirectionalLight, DirectionalShadowRenderer.@Nullable Frame> shadow) {
        public static final DirectionalLights EMPTY = new DirectionalLights(List.of(), Map.of(), light -> null);
        public DirectionalLights withMaterials(Map<SceneDirectionalLight, VolumeMaterial> values) {
            return new DirectionalLights(lights, values, shadow);
        }
    }

    public record ViewHistory(Object world, Object cameraOwner, long cameraCut, long frame, boolean readOnly) { }
    private record History(ViewHistory view, Vec3 camera, TranslucencyVolumeGrid grid, int buffer) { }
    private enum LocalFunction { NONE, ATLAS, MATERIAL }
    private record LocalPipeline(Kind shadow, LocalFunction function) { }
    private enum Stage { INJECT, SPATIAL, TEMPORAL }

    private record Coefficients(TextureTarget ambient, TextureTarget direction) {
        RenderPassDescriptor pass(String label) {
            return RenderPassDescriptor.create(() -> label).withColorAttachment(ambient.getColorTextureView())
                    .withColorAttachment(direction.getColorTextureView())
                    .withRenderArea(new RenderPass.RenderArea(0, 0, ambient.width, ambient.height));
        }
        void clear(CommandEncoder encoder) {
            encoder.clearColorTexture(ambient.getColorTexture(), new Vector4f());
            encoder.clearColorTexture(direction.getColorTexture(), new Vector4f());
        }
        void destroy() {
            try { ambient.destroyBuffers(); }
            finally { direction.destroyBuffers(); }
        }
    }

    public record Sample(GpuBufferSlice uniforms, GpuTextureView innerAmbient, GpuTextureView outerAmbient,
                         GpuTextureView innerDirection, GpuTextureView outerDirection, @Nullable BlueNoiseTextures noise) {
        public void bind(RenderPass pass, boolean perPixel) {
            pass.setUniform(UNIFORM, uniforms);
            var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            pass.bindTexture(AMBIENT[0], innerAmbient, sampler);
            pass.bindTexture(AMBIENT[1], outerAmbient, sampler);
            pass.bindTexture(DIRECTION[0], innerDirection, sampler);
            pass.bindTexture(DIRECTION[1], outerDirection, sampler);
            if (perPixel) {
                var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
                pass.bindTexture("TlvBlueNoiseScalar", noise == null ? innerAmbient : noise.scalar(), nearest);
                pass.bindTexture("TlvBlueNoiseVector", noise == null ? innerAmbient : noise.vector(), nearest);
            }
        }
    }

    private final ParticleLightBuffers lights = new ParticleLightBuffers();
    private final VolumeLocalShadowBindings shadowBindings = new VolumeLocalShadowBindings();
    private final LightFunctionRenderer volumeFunctions = new LightFunctionRenderer();
    private final Map<LocalPipeline, RenderPipeline> localPipelines = new HashMap<>();
    private final Map<Boolean, RenderPipeline> directionalPipelines = new HashMap<>();
    private final Coefficients[] injected = new Coefficients[2], filtered = new Coefficients[2];
    private final Coefficients[][] historyTargets = {new Coefficients[2], new Coefficients[2]};
    private @Nullable History history;
    private @Nullable TextureTarget lightFunctionVoxels;
    private long lastTemporalFrame = Long.MIN_VALUE;
    private RenderPipeline injectPipeline, filterPipeline, temporalPipeline;

    public static void addBindings(BindGroupLayout.Builder bindings, boolean perPixel) {
        bindings.withUniform(UNIFORM, UniformType.UNIFORM_BUFFER);
        for (String name : AMBIENT) bindings.withSampler(name);
        for (String name : DIRECTION) bindings.withSampler(name);
        if (perPixel) bindings.withSampler("TlvBlueNoiseScalar").withSampler("TlvBlueNoiseVector");
    }

    public Sample prepare(CommandEncoder encoder, Matrix4fc view, Matrix4fc projection, Matrix4fc materialProjection, Vec3 camera,
                          Settings settings, List<ParticleLight> source, LocalLights locals, DirectionalLights directionals,
                          GpuTextureView shadowFallback, float exposure, @Nullable ViewHistory viewHistory, boolean perPixel,
                          long stateFrameIndex, int glViewportHeight) {
        RenderSystem.assertOnRenderThread();
        var limits = RenderSystem.getDevice().getDeviceInfo().limits();
        float noiseRadius = perPixel && settings.enabled() ? settings.positionOffsetRadius() : 0f;
        var noise = noiseRadius > 0f ? BlueNoiseTextures.active() : null;
        var grid = TranslucencyVolumeGrid.create(view, projection, camera, settings.enabled() ? settings.dimension() : 4,
                limits.maxTextureSizeForFormat(FORMAT), settings.innerDistance(), settings.outerDistance(),
                settings.adaptToPerspective(), settings.minimumFov(), settings.fovSnap());
        var visible = settings.enabled() ? source.stream().filter(ParticleLight::affectsTranslucency).toList() : List.<ParticleLight>of();
        var visibleLocal = settings.enabled() ? locals.lights().stream()
                .filter(light -> light.data().shader().affectsTranslucency()).toList() : List.<ScenePointLight>of();
        var visibleDirectional = settings.enabled() ? directionals.lights().stream()
                .filter(SceneDirectionalLight::affectsTranslucency).toList() : List.<SceneDirectionalLight>of();
        if (!Float.isFinite(exposure) && (visible.stream().anyMatch(light -> light.inverseExposureBlend() != 0)
                || visibleLocal.stream().anyMatch(light -> light.data().shader().inverseExposureBlend() != 0)))
            throw new IllegalArgumentException("Translucency lighting requires finite scene exposure");
        var activeView = settings.enabled() && settings.temporal() ? viewHistory : null;
        if (activeView == null) clearHistory();
        var previous = history;
        boolean historyValid = activeView != null && previous != null && canUseHistory(previous, activeView, grid);
        int historyBuffer = previous == null ? 0 : previous.buffer() ^ 1;
        var batches = lights.uploadWorld(encoder, visible, camera, Integer.MAX_VALUE / grid.dimension());
        var worldToClip = new Matrix4f(materialProjection).mul(view);
        int noiseFrame = (int)(stateFrameIndex & 63);
        int alignment = limits.minUniformOffsetAlignment();
        GpuBufferSlice[] cascadeUniforms = {
                uniforms(encoder, grid, 0, exposure, noiseRadius, noiseFrame, glViewportHeight, alignment),
                uniforms(encoder, grid, 1, exposure, noiseRadius, noiseFrame, glViewportHeight, alignment)
        };
        var output = new Coefficients[2];
        for (int cascade = 0; cascade < 2; cascade++) {
            var target = resize(injected, cascade, grid);
            target.clear(encoder);
        }
        for (var light : visibleDirectional) {
            var shadow = directionals.shadow().apply(light);
            injectDirectional(encoder, cascadeUniforms, grid, light, directionals.materials().get(light), shadow,
                    shadowFallback, camera, view, worldToClip, alignment);
        }
        for (int cascade = 0; cascade < 2; cascade++) {
            var uniforms = cascadeUniforms[cascade];
            var target = injected[cascade];
            for (var batch : batches) {
                if (injectPipeline == null) injectPipeline = pipeline(Stage.INJECT);
                try (var pass = encoder.createRenderPass(target.pass("LumenFX translucent volume simple lights"))) {
                    pass.setPipeline(injectPipeline);
                    pass.setUniform(UNIFORM, uniforms);
                    pass.setUniform("ParticleLights", batch.buffer());
                    pass.draw(6, batch.lights() * grid.dimension(), 0, 0);
                }
            }
            for (var light : visibleLocal) {
                var function = locals.functions() == null ? null : locals.functions().sample(light);
                var shadow = locals.shadows().get(light);
                boolean needsMaterial = function == null || shadow != null
                        || !settings.accurateRectLights() && light.data().shader().rect() != null;
                var volumeMaterial = needsMaterial ? locals.materials().get(light) : null;
                injectLocal(encoder, target, uniforms, grid, light, shadow, volumeMaterial == null ? function : null,
                        volumeMaterial, camera, view, worldToClip, settings.accurateRectLights(), limits.minUniformOffsetAlignment());
            }
            output[cascade] = target;
            if (activeView != null && (historyValid || !activeView.readOnly())) {
                var result = resize(historyTargets[historyBuffer], cascade, grid);
                resolveHistory(encoder, target, result, uniforms, historyValid ? previous : null,
                        camera, settings.historyWeight(), cascade, limits.minUniformOffsetAlignment());
                output[cascade] = result;
            }
            if (settings.spatialFilter() && (!batches.isEmpty() || !visibleLocal.isEmpty() || !visibleDirectional.isEmpty() || historyValid)) {
                var input = output[cascade];
                var result = resize(filtered, cascade, grid);
                if (filterPipeline == null) filterPipeline = pipeline(Stage.SPATIAL);
                try (var pass = encoder.createRenderPass(result.pass("LumenFX translucent volume spatial filter"))) {
                    pass.setPipeline(filterPipeline);
                    pass.setUniform(UNIFORM, uniforms);
                    var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
                    pass.bindTexture("TlvUnfilteredAmbient", input.ambient().getColorTextureView(), sampler);
                    pass.bindTexture("TlvUnfilteredDirection", input.direction().getColorTextureView(), sampler);
                    pass.draw(3, 1, 0, 0);
                }
                output[cascade] = result;
            }
        }
        if (activeView != null) {
            lastTemporalFrame = activeView.frame();
            if (!activeView.readOnly()) history = new History(activeView, camera, grid, historyBuffer);
        }
        return new Sample(cascadeUniforms[0], output[0].ambient().getColorTextureView(), output[1].ambient().getColorTextureView(),
                output[0].direction().getColorTextureView(), output[1].direction().getColorTextureView(), noise);
    }

    private boolean canUseHistory(History previous, ViewHistory view, TranslucencyVolumeGrid grid) {
        var old = previous.view();
        return old.world() == view.world() && old.cameraOwner() == view.cameraOwner() && old.cameraCut() == view.cameraCut()
                && previous.grid().dimension() == grid.dimension() && view.frame() > old.frame()
                && (view.frame() == lastTemporalFrame + 1 || view.readOnly() && view.frame() == lastTemporalFrame);
    }

    private void resolveHistory(CommandEncoder encoder, Coefficients input, Coefficients output, GpuBufferSlice uniforms,
                                @Nullable History previous, Vec3 camera, float historyWeight, int cascade, int alignment) {
        var prior = previous == null ? input : historyTargets[previous.buffer()][cascade];
        GpuBufferSlice parameters;
        try (var mapped = encoder.transientMemory().allocateGpuMapped(32, alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            if (previous == null) vector(data, 0, 0, 0, 0);
            else {
                var oldGrid = cascade == 0 ? previous.grid().inner() : previous.grid().outer();
                var oldMin = oldGrid.minimum().add(previous.camera().subtract(camera).scale(100));
                vector(data, oldMin.x, oldMin.y, oldMin.z, oldGrid.voxelSize());
            }
            vector(data, previous == null ? 0f : historyWeight, 0, 0, 0);
            parameters = mapped.slice();
        }
        if (temporalPipeline == null) temporalPipeline = pipeline(Stage.TEMPORAL);
        try (var pass = encoder.createRenderPass(output.pass("LumenFX translucent volume temporal filter"))) {
            pass.setPipeline(temporalPipeline);
            pass.setUniform(UNIFORM, uniforms);
            pass.setUniform("TlvHistory", parameters);
            var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            pass.bindTexture("TlvUnfilteredAmbient", input.ambient().getColorTextureView(), sampler);
            pass.bindTexture("TlvUnfilteredDirection", input.direction().getColorTextureView(), sampler);
            pass.bindTexture("TlvHistoryAmbient", prior.ambient().getColorTextureView(), sampler);
            pass.bindTexture("TlvHistoryDirection", prior.direction().getColorTextureView(), sampler);
            pass.draw(3, 1, 0, 0);
        }
    }

    private void injectLocal(CommandEncoder encoder, Coefficients target, GpuBufferSlice volumeUniforms, TranslucencyVolumeGrid grid,
                             ScenePointLight light, @Nullable VolumeShadow shadow, LightFunctionAtlas.@Nullable Sample function,
                             @Nullable VolumeMaterial material, Vec3 camera, Matrix4fc view, Matrix4fc worldToClip,
                             boolean accurateRectLights, int alignment) {
        GpuBufferSlice lightUniforms;
        try (var mapped = encoder.transientMemory().allocateGpuMapped(96, alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            var shader = light.data().shader();
            var position = shader.worldPosition();
            var direction = shader.direction();
            var tangent = shader.tangent();
            var color = shader.color();
            var rect = shader.rect();
            var cone = shader.cone();
            double cosine = cone == null ? -1 : cone.cosineOuter();
            double inverseDifference = cone == null ? 0 : cone.inverseCosineDifference();
            float exponent = shader.falloffExponent();
            boolean inverseSquared = shader.inverseSquared();
            if (rect != null && !accurateRectLights) {
                color = color.scale(2 * rect.halfWidth() * rect.halfHeight());
                cosine = Math.cos(Math.toRadians(89.001));
                inverseDifference = 1 / (Math.cos(Math.toRadians(70)) - cosine);
                exponent = 8;
                inverseSquared = false;
                rect = null;
            }
            vector(data, position.x * .01 - camera.x, position.z * .01 - camera.y, position.y * .01 - camera.z, light.data().radius());
            vector(data, color.x, color.y, color.z, exponent);
            vector(data, direction.x, direction.y, direction.z, cosine);
            vector(data, tangent.x, tangent.y, tangent.z, 0);
            if (rect == null) vector(data, 0, 0, 0, 0);
            else vector(data, rect.halfWidth(), rect.halfHeight(), rect.barnCosine(), rect.barnLength());
            vector(data, shader.inverseExposureBlend(), inverseDifference, inverseSquared ? 1 : 0, 0);
            lightUniforms = mapped.slice();
        }
        var shadowUniforms = shadow == null ? null : VolumeLocalShadowBindings.uniforms(encoder, shadow, alignment);
        var functionUniforms = function == null ? null : function.uniforms(encoder);
        GpuTextureView voxelFunction = null;
        if (material != null) {
            var output = lightFunctionTarget(grid);
            encoder.clearColorTexture(output.getColorTexture(), new Vector4f());
            var projection = functionProjection(encoder, light, camera, view, worldToClip, alignment);
            volumeFunctions.drawVolume(encoder, material, output, volumeUniforms, lightUniforms, projection, grid.dimension());
            voxelFunction = output.getColorTextureView();
        }
        var kind = material != null ? LocalFunction.MATERIAL : function != null ? LocalFunction.ATLAS : LocalFunction.NONE;
        var key = new LocalPipeline(Kind.of(shadow), kind);
        try (var pass = encoder.createRenderPass(target.pass("LumenFX translucent volume component light"))) {
            pass.setPipeline(localPipelines.computeIfAbsent(key, TranslucencyLightingVolume::localPipeline));
            pass.setUniform(UNIFORM, volumeUniforms);
            pass.setUniform("TlvLocalLight", lightUniforms);
            if (shadow != null) shadowBindings.bind(pass, shadow, Objects.requireNonNull(shadowUniforms));
            if (function != null) function.bind(pass, Objects.requireNonNull(functionUniforms));
            if (voxelFunction != null) pass.bindTexture("TlvMaterialFunction", voxelFunction,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(6, grid.dimension(), 0, 0);
        }
    }

    private void injectDirectional(CommandEncoder encoder, GpuBufferSlice[] cascadeUniforms, TranslucencyVolumeGrid grid,
                                   SceneDirectionalLight light, @Nullable VolumeMaterial material,
                                   DirectionalShadowRenderer.@Nullable Frame shadow, GpuTextureView shadowFallback,
                                   Vec3 camera, Matrix4fc view, Matrix4fc worldToClip, int alignment) {
        GpuBufferSlice lightUniforms;
        try (var mapped = encoder.transientMemory().allocateGpuMapped(48, alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            var direction = light.direction();
            var color = light.color();
            vector(data, direction.x, direction.y, direction.z, 0);
            vector(data, color.x, color.y, color.z, 0);
            vector(data, -view.m02(), -view.m12(), -view.m22(), -view.m32());
            lightUniforms = mapped.slice();
        }
        GpuBufferSlice projection = material == null ? null : functionProjection(encoder,
                LightFunctionAtlas.translatedWorldToLight(Objects.requireNonNull(light.lightFunction()),
                        new Vec3(camera.x * 100, camera.z * 100, camera.y * 100)), view, worldToClip, 0, false,
                camera, null, alignment);
        GpuBufferSlice shadowUniforms = DirectionalShadowRenderer.uniforms(encoder, shadow, DirectionalShadowRenderer.VOLUME_CASCADE_LIMIT);
        var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        for (int cascade = 0; cascade < cascadeUniforms.length; cascade++) {
            GpuTextureView voxelFunction = null;
            if (material != null) {
                var output = lightFunctionTarget(grid);
                encoder.clearColorTexture(output.getColorTexture(), new Vector4f());
                volumeFunctions.drawVolume(encoder, material, output, cascadeUniforms[cascade], lightUniforms,
                        Objects.requireNonNull(projection), grid.dimension(), true);
                voxelFunction = output.getColorTextureView();
            }
            try (var pass = encoder.createRenderPass(injected[cascade].pass("LumenFX translucent volume directional light"))) {
                pass.setPipeline(directionalPipelines.computeIfAbsent(material != null, TranslucencyLightingVolume::directionalPipeline));
                pass.setUniform(UNIFORM, cascadeUniforms[cascade]);
                pass.setUniform("TlvDirectionalLight", lightUniforms);
                DirectionalShadowRenderer.bind(pass, shadowUniforms, shadow, shadowFallback);
                if (voxelFunction != null) pass.bindTexture("TlvMaterialFunction", voxelFunction, sampler);
                pass.draw(6, grid.dimension(), 0, 0);
            }
        }
    }

    private TextureTarget lightFunctionTarget(TranslucencyVolumeGrid grid) {
        if (lightFunctionVoxels == null || lightFunctionVoxels.width != grid.width() || lightFunctionVoxels.height != grid.height()) {
            var next = new TextureTarget("LumenFX translucent volume light function", grid.width(), grid.height(), false, GpuFormat.R16_FLOAT);
            var old = lightFunctionVoxels;
            lightFunctionVoxels = next;
            if (old != null) old.destroyBuffers();
        }
        return lightFunctionVoxels;
    }

    private static GpuBufferSlice functionProjection(CommandEncoder encoder, ScenePointLight light, Vec3 camera, Matrix4fc view,
                                                      Matrix4fc worldToClip, int alignment) {
        var sourceCamera = new Vec3(camera.x * 100.0, camera.z * 100.0, camera.y * 100.0);
        var shader = light.data().shader();
        var cone = shader.cone();
        var rect = shader.rect();
        float tangent = cone != null ? cone.tangentOuter() : rect != null ? rect.lightFunctionTangent() : 0f;
        return functionProjection(encoder, LightFunctionAtlas.translatedWorldToLight(light, sourceCamera),
                view, worldToClip, tangent, cone == null && rect == null, camera, shader.worldPosition(), alignment);
    }

    private static GpuBufferSlice functionProjection(CommandEncoder encoder, Matrix4fc worldToLight, Matrix4fc view,
                                                     Matrix4fc worldToClip, float tangent, boolean spherical,
                                                     Vec3 camera, @Nullable Vec3 lightOrigin, int alignment) {
        try (var mapped = encoder.transientMemory().allocateGpuMapped(160 + LightFunctionView.OBJECT_POSITION_BYTES,
                alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            worldToLight.get(0, data);
            worldToClip.get(64, data);
            data.position(128);
            vector(data, -100 * view.m02(), -100 * view.m12(), -100 * view.m22(), -100 * view.m32());
            vector(data, tangent, tangent > 0f ? 1 : 0, spherical ? 1 : 0, 0);
            var origin = LightFunctionView.sourceViewOrigin(camera, view);
            LightFunctionView.writeObjectPosition(data, lightOrigin == null ? origin : lightOrigin, origin);
            return mapped.slice();
        }
    }

    private static void vector(ByteBuffer data, double x, double y, double z, double w) {
        data.putFloat((float)x).putFloat((float)y).putFloat((float)z).putFloat((float)w);
    }

    private static GpuBufferSlice uniforms(CommandEncoder encoder, TranslucencyVolumeGrid grid, int cascade, float exposure,
                                           float noiseRadius, int noiseFrame, int glViewportHeight, int alignment) {
        try (var allocation = encoder.transientMemory().allocateGpuMapped(80, alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = allocation.data().order(ByteOrder.nativeOrder());
            grid.inner().write(data);
            grid.outer().write(data);
            data.putInt(grid.dimension()).putInt(grid.columns()).putInt(grid.rows()).putInt(cascade);
            data.putFloat(exposure > 0 && Float.isFinite(exposure) ? exposure : 1f).putFloat(0).putFloat(0).putFloat(0);
            vector(data, noiseRadius, noiseFrame, glViewportHeight, 0);
            return allocation.slice();
        }
    }

    private static Coefficients resize(Coefficients[] targets, int cascade, TranslucencyVolumeGrid grid) {
        var old = targets[cascade];
        if (old != null && old.ambient().width == grid.width() && old.ambient().height == grid.height()) return old;
        var ambient = new TextureTarget("LumenFX translucent ambient volume", grid.width(), grid.height(), false, FORMAT);
        Coefficients replacement;
        try {
            replacement = new Coefficients(ambient,
                    new TextureTarget("LumenFX translucent directional volume", grid.width(), grid.height(), false, FORMAT));
        } catch (RuntimeException | Error failure) {
            ambient.destroyBuffers();
            throw failure;
        }
        targets[cascade] = replacement;
        if (old != null) old.destroy();
        return replacement;
    }

    private static RenderPipeline pipeline(Stage stage) {
        var bindings = BindGroupLayout.builder().withUniform(UNIFORM, UniformType.UNIFORM_BUFFER);
        if (stage == Stage.INJECT) bindings.withUniform("ParticleLights", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT);
        else bindings.withSampler("TlvUnfilteredAmbient").withSampler("TlvUnfilteredDirection");
        if (stage == Stage.TEMPORAL)
            bindings.withUniform("TlvHistory", UniformType.UNIFORM_BUFFER).withSampler("TlvHistoryAmbient").withSampler("TlvHistoryDirection");
        var attachment = new ColorTargetState(stage == Stage.INJECT ? Optional.of(BlendFunction.ADDITIVE) : Optional.empty(),
                FORMAT, ColorTargetState.WRITE_ALL);
        String pass = switch (stage) {
            case INJECT -> "inject_lights";
            case SPATIAL -> "filter";
            case TEMPORAL -> "temporal";
        };
        return RenderPipeline.builder().withLocation(LumenFx.id("pipeline/translucency/" + pass))
                .withVertexShader(LumenFx.id(stage == Stage.INJECT ? "translucency/inject_lights" : "post/fullscreen"))
                .withFragmentShader(LumenFx.id("translucency/" + pass))
                .withBindGroupLayout(bindings.build()).withColorTargetState(attachment).withColorTargetState(1, attachment)
                .withDepthStencilState(Optional.empty()).withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).build();
    }

    private static RenderPipeline localPipeline(LocalPipeline key) {
        var bindings = BindGroupLayout.builder().withUniform(UNIFORM, UniformType.UNIFORM_BUFFER)
                .withUniform("TlvLocalLight", UniformType.UNIFORM_BUFFER);
        var attachment = new ColorTargetState(Optional.of(BlendFunction.ADDITIVE), FORMAT, ColorTargetState.WRITE_ALL);
        var pipeline = RenderPipeline.builder()
                .withLocation(LumenFx.id("pipeline/translucency/inject_local_" + key.shadow().name().toLowerCase(Locale.ROOT)
                        + "_" + key.function().name().toLowerCase(Locale.ROOT)))
                .withVertexShader(LumenFx.id("translucency/inject_lights")).withFragmentShader(LumenFx.id("translucency/inject_lights"))
                .withShaderDefine("LFX_TLV_LOCAL_LIGHT")
                .withColorTargetState(attachment).withColorTargetState(1, attachment)
                .withDepthStencilState(Optional.empty()).withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES);
        key.shadow().configure(pipeline, bindings);
        switch (key.function()) {
            case ATLAS -> {
                pipeline.withShaderDefine("LFX_LOCAL_LIGHT_FUNCTION");
                LightFunctionAtlas.Sample.layout(bindings);
            }
            case MATERIAL -> {
                pipeline.withShaderDefine("LFX_TLV_MATERIAL_FUNCTION");
                bindings.withSampler("TlvMaterialFunction");
            }
            case NONE -> { }
        }
        return pipeline.withBindGroupLayout(bindings.build()).build();
    }

    private static RenderPipeline directionalPipeline(boolean material) {
        var bindings = BindGroupLayout.builder().withUniform(UNIFORM, UniformType.UNIFORM_BUFFER)
                .withUniform("TlvDirectionalLight", UniformType.UNIFORM_BUFFER);
        DirectionalShadowRenderer.layout(bindings);
        var attachment = new ColorTargetState(Optional.of(BlendFunction.ADDITIVE), FORMAT, ColorTargetState.WRITE_ALL);
        var pipeline = RenderPipeline.builder().withLocation(LumenFx.id("pipeline/translucency/inject_directional_" + material))
                .withVertexShader(LumenFx.id("translucency/inject_lights")).withFragmentShader(LumenFx.id("translucency/inject_lights"))
                .withShaderDefine("LFX_TLV_DIRECTIONAL_LIGHT")
                .withShaderDefine("LFX_DIRECTIONAL_CASCADE_LIMIT", DirectionalShadowRenderer.VOLUME_CASCADE_LIMIT)
                .withColorTargetState(attachment).withColorTargetState(1, attachment)
                .withDepthStencilState(Optional.empty()).withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES);
        if (material) {
            pipeline.withShaderDefine("LFX_TLV_MATERIAL_FUNCTION");
            bindings.withSampler("TlvMaterialFunction");
        }
        return pipeline.withBindGroupLayout(bindings.build()).build();
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        lights.close();
        shadowBindings.close();
        volumeFunctions.close();
        directionalPipelines.clear();
        if (lightFunctionVoxels != null) { lightFunctionVoxels.destroyBuffers(); lightFunctionVoxels = null; }
        clearHistory();
        destroy(injected);
        destroy(filtered);
    }

    private void clearHistory() {
        history = null;
        lastTemporalFrame = Long.MIN_VALUE;
        for (var targets : historyTargets) destroy(targets);
    }

    private static void destroy(Coefficients[] targets) {
        for (int cascade = 0; cascade < targets.length; cascade++) {
            var target = targets[cascade];
            targets[cascade] = null;
            if (target != null) target.destroy();
        }
    }
}
