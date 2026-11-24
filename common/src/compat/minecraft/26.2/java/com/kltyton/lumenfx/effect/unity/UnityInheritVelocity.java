package com.kltyton.lumenfx.effect.unity;

import com.kltyton.lumenfx.effect.curve.ScalarFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.InheritVelocitySetting.Mode;
import java.util.Objects;

/** Authored Shuriken velocity inheritance for an exported particle simulation. */
public record UnityInheritVelocity(Mode mode, Source source, ScalarFunction multiplier) {
    public enum Source { TRANSFORM, RIGIDBODY }

    public UnityInheritVelocity {
        Objects.requireNonNull(mode);
        Objects.requireNonNull(source);
        Objects.requireNonNull(multiplier);
    }
}
