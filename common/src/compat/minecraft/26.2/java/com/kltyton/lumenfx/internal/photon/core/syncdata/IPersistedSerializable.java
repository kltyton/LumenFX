package com.kltyton.lumenfx.internal.photon.core.syncdata;

import com.kltyton.lumenfx.internal.photon.core.utils.PersistedParser;
import com.lowdragmc.photon.utils.PhotonValueSerializable;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.EndTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.NotNull;

public interface IPersistedSerializable extends PhotonValueSerializable {
    default void beforeSerialize() { }

    @Override
    default void serialize(@NotNull ValueOutput output) {
        PersistedParser.serialize(this, output);
    }

    @Override
    default void deserialize(@NotNull ValueInput input) {
        PersistedParser.deserialize(this, input);
    }

    default void writeToBuff(ByteBuf buf) {
        PersistedParser.writeBuff(buf, this);
    }

    default Tag serializeAdditionalNBT(HolderLookup.@NotNull Provider provider) {
        return EndTag.INSTANCE;
    }

    default void afterSerialize() { }

    default void beforeDeserialize() { }

    default void readFromBuff(ByteBuf buf) {
        PersistedParser.readBuff(buf, this);
    }

    default void deserializeAdditionalNBT(Tag tag, HolderLookup.@NotNull Provider provider) { }

    default void afterDeserialize() { }
}
