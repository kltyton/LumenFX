/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Runtime checks for what an emitter's materials read from custom data streams and instancing.
 */
public final class MaterialRequirements {
    private MaterialRequirements() {
    }

    /** Channels each custom-data stream needs, over all the materials (0 = not read). */
    public static int[] neededChannels(List<MaterialSetting> materials) {
        var needed = new int[AdditionalGPUDataSetting.MAX_CUSTOM_DATA];
        for (var setting : materials) {
            var reads = setting.getMaterial().requiredCustomChannels();
            for (int i = 0; i < Math.min(reads.length, needed.length); i++) {
                needed[i] = Math.max(needed[i], reads[i]);
            }
        }
        return needed;
    }

    public static boolean needsInstancing(List<MaterialSetting> materials, boolean cpuCarriesData, boolean instanced) {
        if (cpuCarriesData || instanced) return false;
        for (var setting : materials) {
            var material = setting.getMaterial();
            if (material.getUsedChannelMask() != 0 || material.usesCustomData()) return true;
        }
        return false;
    }

    @Nullable
    public static Component describe(List<MaterialSetting> materials, AdditionalGPUDataSetting data,
                                     boolean cpuCarriesData, BooleanSupplier instanced) {
        MutableComponent text = null;
        var needed = neededChannels(materials);
        var streams = data.customDataStreams();
        for (int i = 0; i < needed.length; i++) {
            if (needed[i] == 0) continue;
            boolean defined = data.isEnable() && i < streams.size()
                    && (streams.get(i).getType() == CustomData.Type.COLOR || streams.get(i).getChannelCount() >= needed[i]);
            if (defined) continue;
            var line = Component.translatable("photon.material_requirements.stream", i, needed[i]);
            text = text == null ? line : text.append(" ").append(line);
        }
        if (needsInstancing(materials, cpuCarriesData, instanced.getAsBoolean())) {
            var line = Component.translatable("photon.material_requirements.instancing");
            text = text == null ? line : text.append(" ").append(line);
        }
        return text;
    }

    public static void fix(List<MaterialSetting> materials, AdditionalGPUDataSetting data, boolean cpuCarriesData,
                           BooleanSupplier instanced, Consumer<Boolean> setInstanced) {
        var needed = neededChannels(materials);
        var streams = data.customDataStreams();
        for (int i = 0; i < needed.length; i++) {
            if (needed[i] == 0) continue;
            data.setEnable(true);
            while (streams.size() <= i) streams.add(new CustomData());
            var stream = streams.get(i);
            if (stream.getType() == CustomData.Type.VECTOR && stream.getChannelCount() < needed[i]) {
                stream.setChannelCount(needed[i]);
            }
        }
        if (needsInstancing(materials, cpuCarriesData, instanced.getAsBoolean())) {
            setInstanced.accept(true);
        }
    }
}
