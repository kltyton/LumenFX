package com.kltyton.lumenfx.api.client.parameter;

import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTextureDefinition.StreamingMips;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTextureDefinition.Volume;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** One authored runtime virtual texture component for a deferred game scene. */
public record RuntimeVirtualTexturePlacement(String source, Volume volume, boolean hidePrimitives,
                                             boolean platformEnabled, boolean naniteOnly, @Nullable MaterialQuality minimumQuality,
                                             int scalabilityGroup, @Nullable StreamingMips streaming) {
    public enum MaterialQuality { Low, Medium, High, Epic }
    public record SceneProfile(MaterialQuality materialQuality, boolean nanite, boolean streamedMips,
                               List<Integer> tileCountBiasByGroup) {
        public SceneProfile {
            Objects.requireNonNull(materialQuality);
            tileCountBiasByGroup = List.copyOf(tileCountBiasByGroup);
            if (tileCountBiasByGroup.size() != 3) throw new IllegalArgumentException("RVT requires three scalability groups");
        }
    }

    public RuntimeVirtualTexturePlacement {
        Objects.requireNonNull(source);
        Objects.requireNonNull(volume);
        if (scalabilityGroup < -1 || scalabilityGroup > 2) throw new IllegalArgumentException("Invalid RVT scalability group");
    }

    public boolean enabled(SceneProfile profile) {
        return platformEnabled && (!naniteOnly || profile.nanite())
                && (minimumQuality == null || profile.materialQuality().ordinal() >= minimumQuality.ordinal());
    }

    public int componentBias(SceneProfile profile) {
        return scalabilityGroup < 0 ? 0 : profile.tileCountBiasByGroup().get(scalabilityGroup);
    }
}
