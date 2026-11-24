package com.kltyton.lumenfx.effect.render.material;

import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture.Material;
import com.kltyton.lumenfx.api.client.parameter.DynamicMaterialValues.ParameterInfo;
import com.kltyton.lumenfx.api.client.parameter.NiagaraStreamingVirtualTexture;
import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import java.util.List;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** One compiled material stack and its authored runtime virtual texture parameter identity. */
public record VirtualTextureRead(String name, String source, @Nullable Material material, @Nullable Identifier streamed,
                                 int layers, boolean packed, boolean adaptive,
                                 @Nullable ParameterInfo parameter, @Nullable StreamedBinding rendererBinding) {
    public record ObjectInput(String variable, boolean defaultPresent, @Nullable String defaultTexture) { }
    public record Collection(String path, String name, String sourceType, boolean systemOverride, @Nullable String baseDefault) { }
    public record StreamedBinding(String variable, String sourceType, int typeFlags, @Nullable String dataInterface,
                                  @Nullable String child, @Nullable String objectDefault, @Nullable String interfaceId,
                                  boolean staticExposure, @Nullable Identifier base,
                                  @Nullable Collection collection, List<ObjectInput> objectInputs) {
        public StreamedBinding {
            Objects.requireNonNull(variable);
            Objects.requireNonNull(sourceType);
            objectInputs = List.copyOf(objectInputs);
            if (typeFlags < 0 || typeFlags > 255 || (dataInterface == null) != (child == null))
                throw new IllegalArgumentException("Invalid renderer VT parameter type");
        }
    }
    public sealed interface StreamedValue {
        record Virtual(NiagaraStreamingVirtualTexture source) implements StreamedValue { }
        record Ordinary(TextureParameter source) implements StreamedValue { }
        enum Default implements StreamedValue { BASE, UNPAGED }
    }

    public VirtualTextureRead {
        Objects.requireNonNull(name);
        Objects.requireNonNull(source);
        if ((material == null) == (streamed == null)) throw new IllegalArgumentException("Virtual texture stack requires one source type");
        if (rendererBinding != null && (streamed == null || parameter == null))
            throw new IllegalArgumentException("Renderer textures require a streamed texture parameter");
        int expected = switch (material) {
            case null -> 1;
            case BaseColor_Normal_Roughness, BaseColor_Normal_Specular -> 2;
            case BaseColor_Normal_Specular_YCoCg, BaseColor_Normal_Specular_Mask_YCoCg -> 3;
            default -> 1;
        };
        if (!name.matches("LfxVT[0-9]+") || source.isBlank() || layers != expected)
            throw new IllegalArgumentException("Invalid runtime virtual texture material stack " + name);
    }
    public String uniform() { return name + "View"; }
    public String pages(int layer) { return name + "_Pages" + layer; }
    public String physical(int layer) { return name + "_Physical" + layer; }
    public String indirection() { return name + "_Indirection"; }
    public String fine() { return name + "_Fine"; }
}
