package com.kltyton.lumenfx.client.render.motion;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** Immutable per-owner group inside a {@link MotionSnapshot}. */
public final class OwnerMotion {
    private final OwnerKey owner;
    private final List<SubmissionMotion> submissions;

    OwnerMotion(OwnerKey owner, List<SubmissionMotion> submissions) {
        this.owner = owner;
        this.submissions = submissions;
    }

    public OwnerKey owner() {
        return this.owner;
    }

    public List<SubmissionMotion> submissions() {
        return this.submissions;
    }

    public @Nullable SubmissionMotion find(SubmitKind kind, String renderSignature, int occurrence) {
        for (SubmissionMotion submission : this.submissions) {
            SubmitKey key = submission.key();
            if (key.kind() == kind
                    && key.occurrence() == occurrence
                    && key.renderSignature().equals(renderSignature)) {
                return submission;
            }
        }
        return null;
    }
}
