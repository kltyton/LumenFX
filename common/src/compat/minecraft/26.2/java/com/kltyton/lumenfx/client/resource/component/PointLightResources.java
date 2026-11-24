package com.kltyton.lumenfx.client.resource.component;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.resource.EffectResources;
import com.kltyton.lumenfx.client.render.light.photometry.PointLightPhotometry;
import com.kltyton.lumenfx.client.render.math.SourceColorSpace;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Component resource identifiers and photometry are decoded with the owning effect definition. */
public record PointLightResources(SourceColorSpace workingColorSpace, @Nullable IesProfile ies,
                                  @Nullable String sourceLightFunction, @Nullable String lightFunctionMaterial,
                                  @Nullable Identifier rectSourceTexture) {
    public PointLightResources(SourceColorSpace workingColorSpace, @Nullable IesProfile ies,
                               @Nullable String sourceLightFunction, @Nullable String lightFunctionMaterial) {
        this(workingColorSpace, ies, sourceLightFunction, lightFunctionMaterial, null);
    }
    public PointLightResources(SourceColorSpace workingColorSpace, @Nullable IesProfile ies, @Nullable String sourceLightFunction) {
        this(workingColorSpace, ies, sourceLightFunction, null);
    }
    public record IesProfile(Identifier texture, PointLightPhotometry.IesBrightness brightness) { }

    public static PointLightResources read(JsonObject template) {
        SourceColorSpace working = SourceColorSpace.SRGB;
        JsonArray values = EffectResources.jsonArray(template, "lumenfx_working_color_space");
        if (values != null) {
            if (values.size() != 8) throw new IllegalArgumentException("Working color space needs four chromaticity pairs");
            working = new SourceColorSpace(values.get(0).getAsDouble(), values.get(1).getAsDouble(), values.get(2).getAsDouble(),
                    values.get(3).getAsDouble(), values.get(4).getAsDouble(), values.get(5).getAsDouble(),
                    values.get(6).getAsDouble(), values.get(7).getAsDouble());
        }
        IesProfile ies = null;
        JsonObject profile = EffectResources.jsonObject(template, "lumenfx_ies");
        if (profile != null) {
            if (profile.has("source_texture")) throw new IllegalArgumentException("IES profile texture was not converted to a runtime resource");
            if (!profile.has("texture") || profile.get("texture").isJsonNull()) throw new IllegalArgumentException("IES profile has no runtime texture");
            ies = new IesProfile(Identifier.parse(profile.get("texture").getAsString()),
                    new PointLightPhotometry.IesBrightness(profile.get("brightness").getAsFloat(), profile.get("texture_multiplier").getAsFloat()));
        } else if (reference(template, "IESTexture") != null) throw new IllegalArgumentException("Missing exported IES profile data");
        String sourceFunction = reference(template, "LightFunctionMaterial");
        String material = reference(template, "lumenfx_light_function");
        if (sourceFunction != null && material == null) throw new IllegalArgumentException("Missing exported light-function material");
        JsonObject rect = EffectResources.jsonObject(template, "lumenfx_rect_source");
        Identifier rectTexture = null;
        if (rect != null) {
            String texture = reference(rect, "texture");
            if (texture == null || rect.has("source_texture")) throw new IllegalArgumentException("Missing exported rect light source texture");
            rectTexture = Identifier.parse(texture);
        } else if (reference(template, "SourceTexture") != null)
            throw new IllegalArgumentException("Missing exported rect light source data");
        return new PointLightResources(working, ies, sourceFunction, material, rectTexture);
    }

    private static @Nullable String reference(JsonObject template, String field) {
        if (!template.has(field) || template.get(field).isJsonNull()) return null;
        String value = template.get(field).getAsString();
        return value.equals("None") ? null : value;
    }
}
