/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.kltyton.lumenfx.internal.photon.core.syncdata.IPersistedSerializable;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jetbrains.annotations.NotNull;

public class ToggleGroup implements IPersistedSerializable {
    @Getter
    @Setter
    protected boolean enable;

    public boolean skipDisableSerialize() {
        return true;
    }

    @Override
    public void serialize(@NotNull ValueOutput output) {
        output.putBoolean("_enable", isEnable());
        if (isEnable() || !skipDisableSerialize()) {
            IPersistedSerializable.super.serialize(output);
        }
    }

    @Override
    public void deserialize(@NotNull ValueInput input) {
        setEnable(input.getBooleanOr("_enable", false));
        if (isEnable() || !skipDisableSerialize()) {
            IPersistedSerializable.super.deserialize(input);
        }
    }
}
