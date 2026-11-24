package com.kltyton.lumenfx.client.resource.volume;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.effect.render.volume.VolumeRendererSettings;

public final class VolumeRendererJson {
    private VolumeRendererJson() { }

    public static VolumeRendererSettings read(JsonObject source) {
        var binding = source.get("material_binding");
        return new VolumeRendererSettings(source.get("step_factor").getAsFloat(), source.get("shadow_step_factor").getAsFloat(),
                source.get("shadow_bias_factor").getAsFloat(), source.get("lighting_downsample_factor").getAsFloat(),
                binding == null || binding.isJsonNull() ? null : binding.getAsString(), source.get("material_variant").getAsString());
    }
}
