layout(std430, binding = 0) buffer LfxVirtualTextureFeedback {
    uint lfxVtRequestCount;
    uint lfxVtUnused0;
    uint lfxVtUnused1;
    uint lfxVtUnused2;
    uvec4 lfxVtRequests[];
};

uvec4 lfxVtSelected = uvec4(0u);
uint lfxVtFeedbackFrame = 0u;
uint lfxVtFeedbackCount = 0u;

// A material stack can feed several outputs; only its first executed page lookup is a feedback request.
LfxVirtualPage lfx_vt_feedback(LfxVirtualPage page, uint textureId, uint frame, uint stack, uint stacks, inout bool seen) {
    if (!seen) {
        seen = true;
        uint index = lfxVtFeedbackCount++;
        if (index == frame % max(stacks, 1u)) {
            lfxVtSelected = uvec4(textureId, page.requestPage, uint(page.requestedMip));
            lfxVtFeedbackFrame = frame;
        }
    }
    return page;
}

// Previously compiled materials use static stack selection.
LfxVirtualPage lfx_vt_feedback(LfxVirtualPage page, uint textureId, uint frame, uint stack, uint stacks) {
    if (textureId != 0u && frame % stacks == stack) {
        lfxVtSelected = uvec4(textureId, page.requestPage, uint(page.requestedMip));
        lfxVtFeedbackFrame = frame;
    }
    return page;
}

void lfx_vt_commit_feedback() {
#if !LFX_PARTICLE_MOTION && !LFX_SUBSTRATE_COVERAGE_REPLAY && !LFX_NANITE_RASTER_MATERIAL && !LFX_MOTION_AUXILIARY_PASS
    if (lfxVtSelected.x == 0u) return;
    uvec2 pixel = uvec2(gl_FragCoord.xy) & 15u;
    uint jitter = (lfxVtFeedbackFrame * 37u) & 255u;
    if (pixel.x + 16u * pixel.y != jitter) return;
    uint index = atomicAdd(lfxVtRequestCount, 1u);
    if (index < uint(lfxVtRequests.length())) lfxVtRequests[index] = lfxVtSelected;
#endif
}
