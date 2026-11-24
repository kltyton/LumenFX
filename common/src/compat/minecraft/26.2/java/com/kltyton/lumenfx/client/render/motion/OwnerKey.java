package com.kltyton.lumenfx.client.render.motion;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Stable world-scoped owner identity for motion history. */
public sealed interface OwnerKey permits OwnerKey.Entity, OwnerKey.Block, OwnerKey.Level {
    Object world();

    static OwnerKey entity(Object world, int entityId, UUID uuid) {
        return new Entity(world, entityId, uuid);
    }

    static OwnerKey block(Object world, BlockEntityType<?> type, BlockPos pos, BlockEntity instance) {
        return new Block(world, type, pos, instance);
    }

    static OwnerKey level(Object world) {
        return new Level(world);
    }

    record Entity(Object world, int entityId, UUID uuid) implements OwnerKey {
        public Entity {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(uuid, "uuid");
        }
    }

    record Block(Object world, BlockEntityType<?> type, BlockPos pos, BlockEntity instance) implements OwnerKey {
        public Block {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(pos, "pos");
            Objects.requireNonNull(instance, "instance");
            pos = pos.immutable();
        }

        @Override public boolean equals(Object other) {
            return other instanceof Block block && world == block.world && type == block.type
                    && instance == block.instance && pos.equals(block.pos);
        }

        @Override public int hashCode() {
            return 31 * (31 * (31 * System.identityHashCode(world) + System.identityHashCode(type)) + pos.hashCode())
                    + System.identityHashCode(instance);
        }
    }

    record Level(Object world) implements OwnerKey {
        public Level {
            Objects.requireNonNull(world, "world");
        }
    }
}
