package com.kltyton.lumenfx.client.render.scene.capture;

import com.kltyton.lumenfx.api.client.parameter.NiagaraSceneCaptureInputs.ManagedSettings;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSceneCaptureInputs.ManagedAttachment;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSceneCaptureInputs.EntityAttachment;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSceneCaptureInputs.BlockEntityAttachment;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jspecify.annotations.Nullable;

/** Independent submission and staging for native scene entities viewed from a capture camera. */
final class CaptureFeatures implements AutoCloseable {
    private final StagedVertexBuffer staging;
    private final FeatureRenderDispatcher renderer;

    CaptureFeatures(Minecraft minecraft) {
        staging = new StagedVertexBuffer(() -> "Niagara capture features", 4194304);
        try {
            renderer = new FeatureRenderDispatcher(minecraft.gameRenderer.renderBuffers(), minecraft.getModelManager(),
                    minecraft.getAtlasManager(), minecraft.font, minecraft.gameRenderer.gameRenderState());
            renderer.stagedVertexBuffer = staging;
        } catch (RuntimeException | Error failure) {
            staging.close();
            throw failure;
        }
    }

    FeatureRenderDispatcher.PreparedFrame prepare(Minecraft minecraft, ClientLevel level, CameraRenderState camera,
                                                   ManagedSettings settings, @Nullable ManagedAttachment attachment) {
        var output = new SubmitNodeStorage();
        var pose = new PoseStack();
        var entityRenderer = minecraft.getEntityRenderDispatcher();
        var blockRenderer = minecraft.getBlockEntityRenderDispatcher();
        var delta = minecraft.getDeltaTracker();
        var origin = camera.pos;
        boolean hideAttachment = settings.hideAttachment() && settings.showOnlyActors().isEmpty();
        for (var entity : level.entitiesForRendering()) {
            String identity = entity.getStringUUID();
            var selected = settings.showOnlyActors();
            if (selected.isEmpty() ? matches(settings.hiddenActors(), identity) : !matches(selected, identity)) continue;
            if (hideAttachment && attachment instanceof EntityAttachment attachedEntity
                    && attachedEntity.identity().equals(entity.getUUID())) continue;
            if (!entityRenderer.shouldRender(entity, camera.cullFrustum, origin.x, origin.y, origin.z)) continue;
            float partial = entity.tickCount == 0 ? 1 : delta.getGameTimeDeltaPartialTick(!level.tickRateManager().isEntityFrozen(entity));
            var state = entityRenderer.extractEntity(entity, partial);
            entityRenderer.submit(state, camera, state.x - origin.x, state.y - origin.y, state.z - origin.z, pose, output);
        }
        if (!settings.showOnlyActors().isEmpty()) return renderer.prepareFrame(output);
        var area = minecraft.levelRenderer.viewArea();
        Set<BlockEntity> blocks = Collections.newSetFromMap(new IdentityHashMap<>());
        if (area != null) {
            var center = area.getCameraSectionPos();
            int range = area.getViewDistance();
            for (int z = center.z() - range; z <= center.z() + range; z++)
                for (int x = center.x() - range; x <= center.x() + range; x++) {
                    var chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                    if (chunk != null) blocks.addAll(chunk.getBlockEntities().values());
                }
        }
        blocks.addAll(level.getGloballyRenderedBlockEntities());
        float partial = delta.getGameTimeDeltaPartialTick(false);
        for (BlockEntity block : blocks) {
            if (hideAttachment && attachment instanceof BlockEntityAttachment attachedBlockEntity
                    && attachedBlockEntity.identity() == block) continue;
            if (block.isRemoved() || !block.hasLevel() || !block.getType().isValid(block.getBlockState())) continue;
            BlockEntityRenderer<BlockEntity, BlockEntityRenderState> actual = blockRenderer.getRenderer(block);
            if (actual == null || !actual.shouldRender(block, origin)) continue;
            var state = actual.createRenderState();
            actual.extractRenderState(block, state, partial, origin, null);
            var position = state.blockPos;
            pose.pushPose();
            try {
                pose.translate(position.getX() - origin.x, position.getY() - origin.y, position.getZ() - origin.z);
                blockRenderer.submit(state, pose, output, camera);
            } finally {
                pose.popPose();
            }
        }
        return renderer.prepareFrame(output);
    }

    private static boolean matches(Iterable<String> candidates, String identity) {
        for (String candidate : candidates) if (SourceNames.equal(candidate, identity)) return true;
        return false;
    }

    void endFrame() { staging.endFrame(); }

    @Override public void close() {
        try { renderer.close(); }
        finally { staging.close(); }
    }
}
