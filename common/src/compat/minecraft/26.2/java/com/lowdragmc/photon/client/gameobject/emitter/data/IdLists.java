/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data;

import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/** Persistence for lists of block or entity ids and {@code #tags}, for the settings that filter by them. */
final class IdLists {
    private IdLists() {
    }

    static ListTag serialize(List<String> values) {
        var list = new ListTag();
        for (var value : values) list.add(StringTag.valueOf(value == null ? "" : value));
        return list;
    }

    static List<String> deserialize(ListTag tag) {
        var values = new ArrayList<String>();
        for (Tag element : tag) values.add(element.asString().orElse(""));
        return values;
    }
}
