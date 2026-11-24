package com.lowdragmc.lowdraglib2.math.curve;

import com.lowdragmc.photon.utils.PhotonValueSerializable;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.joml.Vector2f;
import org.joml.Vector2fc;

import java.util.Objects;

public class ExplicitCubicBezierCurve2 implements PhotonValueSerializable {
    public Vector2fc p0;
    public Vector2fc c0;
    public Vector2fc c1;
    public Vector2fc p1;

    public ExplicitCubicBezierCurve2(Vector2fc p0, Vector2fc c0, Vector2fc c1, Vector2fc p1) {
        this.p0 = new Vector2f(p0);
        this.c0 = new Vector2f(c0);
        this.c1 = new Vector2f(c1);
        this.p1 = new Vector2f(p1);
    }

    public ExplicitCubicBezierCurve2(ValueInput input) {
        deserialize(input);
    }

    public Vector2f getPoint(float t) {
        float u = 1f - t;
        float b0 = u * u * u;
        float b1 = 3f * u * u * t;
        float b2 = 3f * u * t * t;
        float b3 = t * t * t;
        return new Vector2f(
                b0 * p0.x() + b1 * c0.x() + b2 * c1.x() + b3 * p1.x(),
                b0 * p0.y() + b1 * c0.y() + b2 * c1.y() + b3 * p1.y());
    }

    @Override
    public void serialize(ValueOutput output) {
        output.store("p0", ExtraCodecs.VECTOR2F, p0);
        output.store("c0", ExtraCodecs.VECTOR2F, c0);
        output.store("c1", ExtraCodecs.VECTOR2F, c1);
        output.store("p1", ExtraCodecs.VECTOR2F, p1);
    }

    @Override
    public void deserialize(ValueInput input) {
        p0 = input.read("p0", ExtraCodecs.VECTOR2F).orElseGet(Vector2f::new);
        c0 = input.read("c0", ExtraCodecs.VECTOR2F).orElseGet(Vector2f::new);
        c1 = input.read("c1", ExtraCodecs.VECTOR2F).orElseGet(Vector2f::new);
        p1 = input.read("p1", ExtraCodecs.VECTOR2F).orElseGet(Vector2f::new);
    }

    public ExplicitCubicBezierCurve2 copy() {
        return new ExplicitCubicBezierCurve2(p0, c0, c1, p1);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ExplicitCubicBezierCurve2 that)) return false;
        return Objects.equals(p0, that.p0) && Objects.equals(c0, that.c0)
                && Objects.equals(c1, that.c1) && Objects.equals(p1, that.p1);
    }

    @Override
    public int hashCode() {
        return Objects.hash(p0, c0, c1, p1);
    }
}
