package com.kltyton.lumenfx.effect.niagara.texture;

import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

public record SparseVolumeInterfaceDefinition(@Nullable Identifier asset, String userParameter, boolean blocking) {
    public static SparseVolumeInterfaceDefinition read(JsonObject data) {
        return new SparseVolumeInterfaceDefinition(data.has("asset") ? Identifier.parse(data.get("asset").getAsString()) : null,
                data.get("user_parameter").getAsString(), data.get("blocking").getAsBoolean());
    }
}
