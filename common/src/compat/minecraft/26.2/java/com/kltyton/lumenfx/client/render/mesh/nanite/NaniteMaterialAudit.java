package com.kltyton.lumenfx.client.render.mesh.nanite;

import com.kltyton.lumenfx.client.render.EffectFrame;
import com.kltyton.lumenfx.client.render.mesh.MeshDrawSelection;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.render.MaterialDepth;
import com.kltyton.lumenfx.effect.render.mesh.NaniteMaterialSource;
import com.kltyton.lumenfx.effect.render.mesh.NaniteMaterialSource.BlendMode;
import com.kltyton.lumenfx.effect.render.mesh.NaniteMaterialSource.ShadingModel;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Source material audit is separate from the availability of a particular raster path. */
final class NaniteMaterialAudit {
    enum Verdict { ELIGIBLE, INELIGIBLE, UNKNOWN }
    record Slot(int index, @Nullable NaniteMaterialSource source, Verdict verdict) { }
    record Result(Verdict verdict, List<Slot> slots) {
        Result { slots = List.copyOf(slots); }
    }

    private NaniteMaterialAudit() { }

    static Result audit(NaniteInstanceCollector.Prepared instance, boolean allowMasked, boolean worldAllowsMasked,
                        boolean forceMasked, boolean allowTranslucency) {
        var slots = new ArrayList<Slot>();
        Verdict result = Verdict.ELIGIBLE;
        int count = Math.max(1, Math.max(instance.asset().materials().size(), instance.entry().materials().size()));
        for (int index = 0; index < count; index++) {
            var base = MeshDrawSelection.materialAtSlot(instance.effect(), instance.renderer(), instance.asset(),
                    instance.entry(), index, instance.input());
            var material = instance.materialInstances().apply(base);
            var source = material.naniteSource();
            Verdict verdict = audit(material, allowMasked && (worldAllowsMasked || forceMasked), allowTranslucency);
            slots.add(new Slot(index, source, verdict));
            if (verdict == Verdict.INELIGIBLE) result = Verdict.INELIGIBLE;
            else if (verdict == Verdict.UNKNOWN && result == Verdict.ELIGIBLE) result = Verdict.UNKNOWN;
        }
        return new Result(result, slots);
    }

    static Verdict audit(MaterialDefinition material, boolean allowMasked, boolean allowTranslucency) {
        if (!EffectFrame.writesOpaqueDepth(material)
                || material.depth().compare() != MaterialDepth.Compare.LESS_EQUAL) {
            return Verdict.INELIGIBLE;
        }
        var source = material.naniteSource();
        return source == null ? Verdict.UNKNOWN : audit(source, allowMasked, allowTranslucency);
    }

    private static Verdict audit(NaniteMaterialSource source, boolean allowMasked, boolean allowTranslucency) {
        if (!source.domain().equals("MD_Surface") || source.sky()) return Verdict.INELIGIBLE;
        BlendMode blend = source.blendMode();
        if (blend == BlendMode.BLEND_Masked && !allowMasked) return Verdict.INELIGIBLE;
        if (blend != BlendMode.BLEND_Opaque && blend != BlendMode.BLEND_Masked) {
            if (!allowTranslucency) return Verdict.INELIGIBLE;
            // The exact TranslucentOnly blend set must come from the source material rules.
            return Verdict.UNKNOWN;
        }
        Integer models = source.shadingModelField();
        if (models == null) {
            if (source.shadingModel() == ShadingModel.MSM_FromMaterialExpression) return Verdict.UNKNOWN;
            if (source.shadingModel() == ShadingModel.MSM_NUM || source.shadingModel() == ShadingModel.MSM_MAX) return Verdict.UNKNOWN;
            if (source.shadingModel() == ShadingModel.MSM_SingleLayerWater) return Verdict.INELIGIBLE;
        } else if ((models & (1 << ShadingModel.MSM_SingleLayerWater.ordinal())) != 0) return Verdict.INELIGIBLE;
        if (!source.usedWithNanite()) return source.automaticallySetUsage() ? Verdict.UNKNOWN : Verdict.INELIGIBLE;
        return Verdict.ELIGIBLE;
    }
}
