package com.kltyton.lumenfx.effect.render.material;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Objects;

/** Explicit per-slot sampler state for exported materials whose textures carry custom filter or address modes. */
public record MaterialTextureSampler(Address addressX, Address addressY, Filter minMag, Filter mip, int maxAnisotropy) {
    public enum Address { WRAP, CLAMP, MIRROR, MIRROR_ONCE }
    public enum Filter { POINT, LINEAR, ANISO }

    public MaterialTextureSampler {
        Objects.requireNonNull(addressX, "addressX");
        Objects.requireNonNull(addressY, "addressY");
        Objects.requireNonNull(minMag, "minMag");
        Objects.requireNonNull(mip, "mip");
        if (mip == Filter.ANISO) throw new IllegalArgumentException("Mip filter cannot be anisotropic");
        if (maxAnisotropy < 1) throw new IllegalArgumentException("Invalid max_anisotropy " + maxAnisotropy);
    }

    public static MaterialTextureSampler read(JsonObject data) {
        return new MaterialTextureSampler(
                enumValue(Address.class, string(data, "address_x")),
                enumValue(Address.class, string(data, "address_y")),
                enumValue(Filter.class, string(data, "min_mag_filter")),
                enumValue(Filter.class, string(data, "mip_filter")),
                integer(data, "max_anisotropy"));
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || value.isJsonNull()) throw new IllegalArgumentException("Missing texture sampler " + key);
        return value.getAsString();
    }

    private static int integer(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || value.isJsonNull()) throw new IllegalArgumentException("Missing texture sampler " + key);
        return value.getAsInt();
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
    }
}
