/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.utils;

import com.lowdragmc.photon.Photon;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.neoforged.neoforge.common.util.ValueIOSerializable;

/** Tag-level bridges for ValueIOSerializable values when external NeoForge/LDLib2 is active. */
public final class ValueIONbt {
    private ValueIONbt() {
    }

    public static CompoundTag toTag(ValueIOSerializable value, HolderLookup.Provider provider) {
        try (var reporter = new ProblemReporter.ScopedCollector(Photon.LOGGER)) {
            var output = TagValueOutput.createWithContext(reporter, provider);
            value.serialize(output);
            return output.buildResult();
        }
    }

    public static void fromTag(ValueIOSerializable value, HolderLookup.Provider provider, CompoundTag tag) {
        try (var reporter = new ProblemReporter.ScopedCollector(Photon.LOGGER)) {
            value.deserialize(TagValueInput.create(reporter, provider, tag));
        }
    }
}
