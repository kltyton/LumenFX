package com.kltyton.lumenfx.client.render.light.shadow.geometry;

import com.kltyton.lumenfx.api.client.render.VfxRenderViews;
import com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Creates native render states for light-visible entities and loaded block entities independently of the main camera list. */
public final class DynamicShadowScene {
    public record Frame(Vec3 origin, List<EntityRenderState> entities, List<BlockEntityRenderState> blockEntities) {
        public Frame { entities = List.copyOf(entities); blockEntities = List.copyOf(blockEntities); }
    }
    private DynamicShadowScene() { }

    public static Frame collect(Minecraft minecraft, LevelRenderer renderer, ScenePointLight light) {
        Vec3 source = light.data().shader().worldPosition();
        Vec3 origin = new Vec3(source.x * .01, source.z * .01, source.y * .01);
        float radius = light.data().radius() * .01f;
        // The axis-aligned light bounds conservatively enclose all six perspective shadow faces.
        var frustum = new Frustum(new Matrix4f(), new Matrix4f().ortho(-radius, radius, -radius, radius, -radius, radius));
        frustum.prepare(origin.x, origin.y, origin.z);
        return collect(minecraft, renderer, origin, frustum);
    }

    public static Frame collect(Minecraft minecraft, LevelRenderer renderer, Vec3 origin, Frustum frustum) {
        try (var view = VfxRenderViews.shadow()) { return collectShadow(minecraft, renderer, origin, frustum); }
    }

    private static Frame collectShadow(Minecraft minecraft, LevelRenderer renderer, Vec3 origin, Frustum frustum) {
        RenderSystem.assertOnRenderThread();
        var level = Objects.requireNonNull(minecraft.level, "Dynamic shadows require the active client world");
        var area = Objects.requireNonNull(renderer.viewArea(), "Dynamic shadows require the loaded world view area");
        var entityRenderer = minecraft.getEntityRenderDispatcher();
        var blockRenderer = minecraft.getBlockEntityRenderDispatcher();
        var delta = minecraft.getDeltaTracker();
        var entities = new ArrayList<EntityRenderState>();
        for (var entity : level.entitiesForRendering()) {
            if (!entityRenderer.shouldRender(entity, frustum, origin.x, origin.y, origin.z)) continue;
            if (entity.tickCount == 0) {
                entity.xOld = entity.getX(); entity.yOld = entity.getY(); entity.zOld = entity.getZ();
            }
            float partial = delta.getGameTimeDeltaPartialTick(!level.tickRateManager().isEntityFrozen(entity));
            entities.add(entityRenderer.extractEntity(entity, partial));
        }
        Set<BlockEntity> candidates = Collections.newSetFromMap(new IdentityHashMap<>());
        var center = area.getCameraSectionPos(); int distance = area.getViewDistance();
        for (int z = center.z() - distance; z <= center.z() + distance; z++)
            for (int x = center.x() - distance; x <= center.x() + distance; x++) {
                var chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk != null) candidates.addAll(chunk.getBlockEntities().values());
            }
        candidates.addAll(level.getGloballyRenderedBlockEntities());
        var blocks = new ArrayList<BlockEntityRenderState>();
        float partial = delta.getGameTimeDeltaPartialTick(false);
        for (BlockEntity block : candidates) {
            if (block.isRemoved() || !block.hasLevel() || !block.getType().isValid(block.getBlockState())) continue;
            BlockEntityRenderer<BlockEntity, BlockEntityRenderState> actual = blockRenderer.getRenderer(block);
            if (actual == null || !actual.shouldRender(block, origin)) continue;
            BlockEntityRenderState state = actual.createRenderState();
            actual.extractRenderState(block, state, partial, origin, null);
            blocks.add(state);
        }
        return new Frame(origin, entities, blocks);
    }
}
