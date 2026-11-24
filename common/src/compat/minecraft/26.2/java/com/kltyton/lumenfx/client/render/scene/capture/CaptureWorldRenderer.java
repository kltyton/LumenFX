package com.kltyton.lumenfx.client.render.scene.capture;

import com.kltyton.lumenfx.api.client.parameter.NiagaraSceneCaptureInputs.CaptureSource;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSceneCaptureInputs.ManagedSettings;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSceneCaptureInputs.ManagedAttachment;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSceneCaptureInputs.Pose;
import com.kltyton.lumenfx.client.render.scene.surface.SceneSurfaceCapture;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import java.nio.ByteOrder;
import java.util.Objects;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/** Draws a loaded world from a private view without invoking the primary level renderer recursively. */
final class CaptureWorldRenderer implements AutoCloseable {
    private final CaptureColorOutput output = new CaptureColorOutput();
    private final CapturePipelineFormats pipelineFormats = new CapturePipelineFormats();
    private final SceneSurfaceCapture surfaces = new SceneSurfaceCapture();
    private @Nullable TextureTarget scratch;
    private @Nullable GlobalSettingsUniform globals;
    private @Nullable CaptureFeatures features;
    private @Nullable SkyRenderer sky;
    private @Nullable FogRenderer fog;
    private @Nullable Marker cameraAnchor;

    void render(ClientLevel level, Pose pose, ManagedSettings settings, Matrix4fc projection, GpuTextureView destination,
                @Nullable ManagedAttachment attachment) {
        RenderSystem.assertOnRenderThread();
        var minecraft = Minecraft.getInstance();
        if (minecraft.level != level) throw new IllegalStateException("Niagara capture level no longer belongs to the client");
        if (scratch == null) scratch = new TextureTarget("Niagara scene capture staging", settings.width(), settings.height(), true, GpuFormat.RGBA16_FLOAT);
        else if (scratch.width != settings.width() || scratch.height != settings.height()) scratch.resize(settings.width(), settings.height());
        if (globals == null) globals = new GlobalSettingsUniform();
        if (features == null) features = new CaptureFeatures(minecraft);
        if (fog == null) fog = new FogRenderer();
        if (sky == null) sky = new SkyRenderer(minecraft.getTextureManager(), minecraft.getAtlasManager(), scratch);
        var device = RenderSystem.getDevice();
        boolean zeroToOne = device.getDeviceInfo().isZZeroToOne();
        int renderDistance = minecraft.options.getEffectiveRenderDistance();
        var camera = CaptureCamera.host(pose, projection, renderDistance * 64f, zeroToOne);
        // NeoForge fog callbacks resolve the level through the camera entity.
        // The unregistered anchor represents the capture view without inheriting player effects.
        if (cameraAnchor == null || cameraAnchor.level() != level) cameraAnchor = new Marker(EntityTypes.MARKER, level);
        cameraAnchor.setPos(camera.pos);
        var auxiliary = new AuxiliaryCamera(level, camera, cameraAnchor);
        var fogData = fog.setupFog(auxiliary, renderDistance, minecraft.getDeltaTracker(), 0, level);
        fog.updateBuffer(fogData);
        boolean captureSurfaces = settings.source() == CaptureSource.SCS_BaseColor || settings.source() == CaptureSource.SCS_Normal;
        var encoder = device.createCommandEncoder();
        GpuBufferSlice projectionUniform;
        try (var mapped = encoder.transientMemory().allocateGpuMapped(64, device.getDeviceInfo().limits().minUniformOffsetAlignment(),
                GpuBuffer.USAGE_UNIFORM)) {
            camera.projectionMatrix.get(mapped.data().order(ByteOrder.nativeOrder()));
            projectionUniform = mapped.slice();
        }
        var oldProjection = Objects.requireNonNull(RenderSystem.getProjectionMatrixBuffer());
        var oldProjectionType = RenderSystem.getProjectionType();
        var oldGlobals = Objects.requireNonNull(RenderSystem.getGlobalSettingsUniform());
        var oldFog = RenderSystem.getShaderFog();
        var oldColor = RenderSystem.outputColorTextureOverride;
        var oldDepth = RenderSystem.outputDepthTextureOverride;
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        try {
            modelView.set(camera.viewRotationMatrix);
            RenderSystem.setProjectionMatrix(projectionUniform, settings.perspective() ? ProjectionType.PERSPECTIVE : ProjectionType.ORTHOGRAPHIC);
            var options = minecraft.gameRenderer.gameRenderState().optionsRenderState;
            globals.update(settings.width(), settings.height(), options.glintStrength, level.getGameTime(), minecraft.getDeltaTracker(), 0,
                    camera.pos, false);
            RenderSystem.outputColorTextureOverride = scratch.getColorTextureView();
            RenderSystem.outputDepthTextureOverride = scratch.getDepthTextureView();
            var clear = new Vector4f(fogData.color);
            clear.w = 0f;
            encoder.clearColorAndDepthTextures(scratch.getColorTexture(), clear, scratch.getDepthTexture(), 0);
            if (captureSurfaces) surfaces.begin(scratch);
            try {
                pipelineFormats.draw(scratch.getColorTextureView(),
                        () -> renderScene(minecraft, level, camera, auxiliary, settings, attachment));
            } finally {
                if (captureSurfaces) surfaces.end();
            }
            output.write(scratch, destination, settings.source(), captureSurfaces ? surfaces.surfaces() : null,
                    camera.viewRotationMatrix, projection, !zeroToOne);
        } finally {
            RenderSystem.outputColorTextureOverride = oldColor;
            RenderSystem.outputDepthTextureOverride = oldDepth;
            RenderSystem.setGlobalSettingsUniform(oldGlobals);
            RenderSystem.setShaderFog(oldFog);
            RenderSystem.setProjectionMatrix(oldProjection, oldProjectionType);
            modelView.popMatrix();
            fog.endFrame();
        }
    }

    private void renderScene(Minecraft minecraft, ClientLevel level, CameraRenderState camera, AuxiliaryCamera auxiliary,
                             ManagedSettings settings, @Nullable ManagedAttachment attachment) {
        boolean worldGeometry = settings.showOnlyActors().isEmpty();
        if (worldGeometry) renderSky(minecraft, level, auxiliary);
        RenderSystem.setShaderFog(fog.getBuffer(FogRenderer.FogMode.WORLD));
        var terrain = worldGeometry ? new CaptureTerrain(minecraft, camera) : null;
        var entityDispatcher = minecraft.getEntityRenderDispatcher();
        var blockDispatcher = minecraft.getBlockEntityRenderDispatcher();
        var mainCamera = minecraft.gameRenderer.mainCamera();
        entityDispatcher.prepare(auxiliary, null);
        blockDispatcher.prepare(camera.pos);
        try (var frame = features.prepare(minecraft, level, camera, settings, attachment)) {
            if (terrain != null) terrain.draw(minecraft, scratch, ChunkSectionLayerGroup.OPAQUE);
            frame.executeSolid();
            frame.executeTranslucent();
            if (terrain != null) terrain.draw(minecraft, scratch, ChunkSectionLayerGroup.TRANSLUCENT);
            frame.executeTranslucentAfterTerrain();
        } finally {
            entityDispatcher.prepare(mainCamera, minecraft.crosshairPickEntity);
            blockDispatcher.prepare(mainCamera.position());
            features.endFrame();
        }
    }

    private void renderSky(Minecraft minecraft, ClientLevel level, AuxiliaryCamera auxiliary) {
        FogType fluid = auxiliary.getFluidInCamera();
        if (fluid == FogType.POWDER_SNOW || fluid == FogType.LAVA || minecraft.player == null) return;
        var state = new SkyRenderState();
        sky.extractRenderState(level, minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false), auxiliary, state);
        RenderSystem.setShaderFog(fog.getBuffer(FogRenderer.FogMode.NONE));
        var poses = new PoseStack();
        if (state.skybox == DimensionType.Skybox.END) {
            sky.renderEndSky();
            if (state.endFlashIntensity > 1e-5f) sky.renderEndFlash(poses, state.endFlashIntensity, state.endFlashXAngle, state.endFlashYAngle);
        } else if (state.skybox != DimensionType.Skybox.NONE) {
            sky.renderSkyDisc(state.skyColor);
            sky.renderSunriseAndSunset(poses, state.sunAngle, state.sunriseAndSunsetColor);
            sky.renderSunMoonAndStars(poses, state.sunAngle, state.moonAngle, state.starAngle, state.moonPhase,
                    state.rainBrightness, state.starBrightness);
            if (auxiliary.position().y < level.getLevelData().getHorizonHeight(level) && fluid != FogType.WATER) sky.renderDarkDisc();
        }
    }

    private static final class AuxiliaryCamera extends Camera {
        private final ClientLevel level;
        AuxiliaryCamera(ClientLevel level, CameraRenderState state, Marker anchor) {
            this.level = level;
            setLevel(level);
            setEntity(anchor);
            setPosition(state.pos);
            setRotation(state.yRot, state.xRot);
            attributeProbe().tick(level, state.pos);
        }

        @Override public FogType getFluidInCamera() {
            var position = blockPosition();
            var state = level.getBlockState(position);
            if (state.is(Blocks.POWDER_SNOW)) return FogType.POWDER_SNOW;
            var fluid = state.getFluidState();
            if (position().y >= position.getY() + fluid.getHeight(level, position)) return FogType.NONE;
            if (fluid.is(FluidTags.WATER)) return FogType.WATER;
            return fluid.is(FluidTags.LAVA) ? FogType.LAVA : FogType.NONE;
        }
    }

    @Override public void close() {
        cameraAnchor = null;
        pipelineFormats.clear();
        surfaces.close();
        if (features != null) { features.close(); features = null; }
        if (sky != null) { sky.close(); sky = null; }
        if (fog != null) { fog.close(); fog = null; }
        if (globals != null) { globals.close(); globals = null; }
        if (scratch != null) { scratch.destroyBuffers(); scratch = null; }
    }
}
