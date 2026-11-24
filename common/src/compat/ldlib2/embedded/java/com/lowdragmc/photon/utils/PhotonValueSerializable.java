package com.lowdragmc.photon.utils;

import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Self-contained ValueIO serialization contract when running without external NeoForge/LDLib2. */
public interface PhotonValueSerializable {
    void serialize(ValueOutput output);

    void deserialize(ValueInput input);
}
