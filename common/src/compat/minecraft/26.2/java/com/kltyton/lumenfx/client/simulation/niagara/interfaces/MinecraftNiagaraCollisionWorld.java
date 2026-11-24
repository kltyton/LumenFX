package com.kltyton.lumenfx.client.simulation.niagara.interfaces;

import com.kltyton.lumenfx.api.client.parameter.NiagaraCollisionWorld;
import com.lowdragmc.photon.client.collision.CollisionQuery;
import com.lowdragmc.photon.client.collision.Contact;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.ClipContext.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/** Maps the engine channels to the shapes and entities available in the Minecraft scene. */
public record MinecraftNiagaraCollisionWorld(ClientLevel level) implements NiagaraCollisionWorld {
    private static final int WORLD_STATIC = 0, WORLD_DYNAMIC = 1, PAWN = 2, VISIBILITY = 3,
            CAMERA = 4, PHYSICS_BODY = 5, VEHICLE = 6, DESTRUCTIBLE = 7;

    @Override
    public @Nullable Hit trace(Vec3 start, Vec3 end, int sourceChannel, boolean traceComplex) {
        Block blockShape = switch (sourceChannel) {
            case WORLD_STATIC, DESTRUCTIBLE -> traceComplex ? Block.OUTLINE : Block.COLLIDER;
            case VISIBILITY -> Block.OUTLINE;
            case CAMERA -> Block.VISUAL;
            case WORLD_DYNAMIC, PAWN, PHYSICS_BODY, VEHICLE -> null;
            default -> throw new IllegalArgumentException("Niagara collision channel " + sourceChannel
                    + " requires a collision world with the source project's channel mapping");
        };
        Hit closest = null;
        if (blockShape != null) {
            var context = sourceChannel == DESTRUCTIBLE
                    ? new DestructibleTrace(start, end, blockShape)
                    : new ClipContext(start, end, blockShape, ClipContext.Fluid.NONE, CollisionContext.empty());
            var hit = level.clip(context);
            if (hit.getType() == HitResult.Type.BLOCK) closest = hit(hit);
        }
        if (sourceChannel == WORLD_STATIC || sourceChannel == CAMERA || sourceChannel == DESTRUCTIBLE) return closest;

        var contact = new Contact();
        if (CollisionQuery.raycastEntitiesWithVanillaClip(level, start, end,
                entity -> matchesEntity(entity, sourceChannel), contact)) {
            var position = new Vec3(contact.px, contact.py, contact.pz);
            if (closest == null || start.distanceToSqr(position) < start.distanceToSqr(closest.position())) {
                return new Hit(position, new Vec3(contact.nx, contact.ny, contact.nz), contact.rayStartedInside, 0f, 0f, 0);
            }
        }
        return closest;
    }

    private static boolean matchesEntity(Entity entity, int channel) {
        if (entity.isSpectator()) return false;
        return switch (channel) {
            case WORLD_DYNAMIC, VISIBILITY -> entity.isPickable();
            case PAWN -> entity instanceof LivingEntity && entity.isPickable();
            case PHYSICS_BODY -> entity.isPushable();
            case VEHICLE -> entity instanceof AbstractBoat || entity instanceof AbstractMinecart;
            default -> false;
        };
    }

    private static Hit hit(BlockHitResult hit) {
        Direction direction = hit.getDirection();
        return new Hit(hit.getLocation(), new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ()),
                hit.isInside(), 0f, 0f, 0);
    }

    private static final class DestructibleTrace extends ClipContext {
        private DestructibleTrace(Vec3 start, Vec3 end, Block block) {
            super(start, end, block, Fluid.NONE, CollisionContext.empty());
        }

        @Override
        public VoxelShape getBlockShape(BlockState state, BlockGetter level, BlockPos position) {
            return state.getDestroySpeed(level, position) < 0 ? Shapes.empty() : super.getBlockShape(state, level, position);
        }
    }
}
