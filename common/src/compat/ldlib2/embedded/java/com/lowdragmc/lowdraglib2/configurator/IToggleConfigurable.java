package com.lowdragmc.lowdraglib2.configurator;

import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.NotNull;

public interface IToggleConfigurable extends IConfigurable, IPersistedSerializable {
    boolean isEnable();

    void setEnable(boolean enable);

    default boolean skipDisableSerialize() {
        return true;
    }

    @Override
    default void serialize(@NotNull ValueOutput output) {
        output.putBoolean("_enable", isEnable());
        if (isEnable() || !skipDisableSerialize()) {
            IPersistedSerializable.super.serialize(output);
        }
    }

    @Override
    default void deserialize(@NotNull ValueInput input) {
        setEnable(input.getBooleanOr("_enable", false));
        if (isEnable() || !skipDisableSerialize()) {
            IPersistedSerializable.super.deserialize(input);
        }
    }
}
