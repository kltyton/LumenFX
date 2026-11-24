package com.kltyton.lumenfx.client.resource.shader;

/** Compatibility of a compiled emitter with the host's Niagara utility stages. */
public final class NiagaraEmitterShaderAbi {
    private static final String PREVIEW_1 = "acf0265dd82938ee7ee971165f14f7bfea7d3808";
    private static final String PREVIEW_2 = "2f66b3f91297f7ad9261f18a70c836fa5472d645";

    private NiagaraEmitterShaderAbi() { }

    public static boolean matchesUtility(String emitterHead, String utilityHead) {
        // Preview 2 changes the NDC writer's generated resource selection and empty UAVs.
        // Both previews use the same particle columns, counters and utility bindings.
        // The stateless simulation also produces identical SPIR-V for both backends.
        return emitterHead.equals(utilityHead)
                || PREVIEW_1.equals(emitterHead) && PREVIEW_2.equals(utilityHead)
                || PREVIEW_2.equals(emitterHead) && PREVIEW_1.equals(utilityHead);
    }
}
