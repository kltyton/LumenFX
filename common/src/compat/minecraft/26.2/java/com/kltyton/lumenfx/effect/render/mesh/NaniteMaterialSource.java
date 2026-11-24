package com.kltyton.lumenfx.effect.render.mesh;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Source material properties used by the Nanite material audit. */
public record NaniteMaterialSource(String domain, BlendMode blendMode, ShadingModel shadingModel,
                                   @Nullable Integer shadingModelField, boolean usedWithNanite,
                                   boolean automaticallySetUsage, boolean sky, boolean twoSided,
                                   double opacityMaskClip, @Nullable Boolean pixelDepthOffset, boolean ditherOpacityMask) {
    public NaniteMaterialSource(String domain, BlendMode blendMode, ShadingModel shadingModel, @Nullable Integer shadingModelField,
                                boolean usedWithNanite, boolean automaticallySetUsage, boolean sky, boolean twoSided, double opacityMaskClip) {
        this(domain, blendMode, shadingModel, shadingModelField, usedWithNanite, automaticallySetUsage,
                sky, twoSided, opacityMaskClip, null, false);
    }

    public boolean programmableCoverage() { return blendMode == BlendMode.BLEND_Masked || Boolean.TRUE.equals(pixelDepthOffset); }
    public enum BlendMode {
        BLEND_Opaque, BLEND_Masked, BLEND_Translucent, BLEND_Additive, BLEND_Modulate,
        BLEND_AlphaComposite, BLEND_AlphaHoldout, BLEND_TranslucentColoredTransmittance,
        BLEND_TranslucentGreyTransmittance, BLEND_ColoredTransmittanceOnly, BLEND_MAX
    }

    public enum ShadingModel {
        MSM_Unlit, MSM_DefaultLit, MSM_Subsurface, MSM_PreintegratedSkin, MSM_ClearCoat,
        MSM_SubsurfaceProfile, MSM_TwoSidedFoliage, MSM_Hair, MSM_Cloth, MSM_Eye,
        MSM_SingleLayerWater, MSM_ThinTranslucent, MSM_Strata, MSM_NUM, MSM_FromMaterialExpression, MSM_MAX
    }

    public NaniteMaterialSource {
        Objects.requireNonNull(domain);
        Objects.requireNonNull(blendMode);
        Objects.requireNonNull(shadingModel);
        if (shadingModelField != null && (shadingModelField < 0 || shadingModelField > 0xffff))
            throw new IllegalArgumentException("Invalid source material shading model field");
        if (!Double.isFinite(opacityMaskClip)) throw new IllegalArgumentException("Invalid opacity mask clip");
    }
}
