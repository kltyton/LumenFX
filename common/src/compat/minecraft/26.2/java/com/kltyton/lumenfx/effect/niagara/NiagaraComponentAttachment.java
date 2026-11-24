package com.kltyton.lumenfx.effect.niagara;

import com.kltyton.lumenfx.api.client.parameter.NiagaraAutoAttachmentParameters;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Serialized Niagara component auto-attachment state and its initial local SceneComponent transform. */
public record NiagaraComponentAttachment(boolean enabled, NiagaraAutoAttachmentParameters parameters,
                                         boolean weldSimulatedBodies, RelativeTransform relativeTransform) {
    public static final NiagaraComponentAttachment DISABLED = new NiagaraComponentAttachment(false,
            new NiagaraAutoAttachmentParameters(null, "None", NiagaraAutoAttachmentParameters.Rule.KeepRelative,
                    NiagaraAutoAttachmentParameters.Rule.KeepRelative, NiagaraAutoAttachmentParameters.Rule.KeepRelative),
            false, RelativeTransform.IDENTITY);

    public NiagaraComponentAttachment {
        Objects.requireNonNull(parameters);
        Objects.requireNonNull(relativeTransform);
    }

    public record RelativeTransform(double x, double y, double z, double pitch, double yaw, double roll,
                                    double scaleX, double scaleY, double scaleZ,
                                    boolean absoluteLocation, boolean absoluteRotation, boolean absoluteScale) {
        public static final RelativeTransform IDENTITY = new RelativeTransform(0, 0, 0, 0, 0, 0, 1, 1, 1,
                false, false, false);

        public RelativeTransform {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Double.isFinite(pitch) || !Double.isFinite(yaw) || !Double.isFinite(roll)
                    || !Double.isFinite(scaleX) || !Double.isFinite(scaleY) || !Double.isFinite(scaleZ))
                throw new IllegalArgumentException("Non-finite Niagara component relative transform");
        }
    }
}
