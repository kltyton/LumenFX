package com.lowdragmc.lowdraglib2.math;

import com.lowdragmc.lowdraglib2.utils.ColorUtils;
import com.lowdragmc.photon.utils.PhotonValueSerializable;
import com.mojang.serialization.Codec;
import lombok.Getter;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.joml.Vector2f;
import org.joml.Vector2fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class GradientColor implements PhotonValueSerializable {
    private static final Codec<List<Vector2fc>> LEGACY_ALPHA_POINTS = Codec.FLOAT.listOf().xmap(
            flat -> {
                List<Vector2fc> points = new ArrayList<>();
                for (int i = 0; i + 1 < flat.size(); i += 2) {
                    points.add(new Vector2f(flat.get(i), flat.get(i + 1)));
                }
                return points;
            },
            points -> {
                List<Float> flat = new ArrayList<>(points.size() * 2);
                for (var p : points) { flat.add(p.x()); flat.add(p.y()); }
                return flat;
            }
    );

    private static final Codec<List<Vector4fc>> LEGACY_RGB_POINTS = Codec.FLOAT.listOf().xmap(
            flat -> {
                List<Vector4fc> points = new ArrayList<>();
                for (int i = 0; i + 3 < flat.size(); i += 4) {
                    points.add(new Vector4f(flat.get(i), flat.get(i + 1), flat.get(i + 2), flat.get(i + 3)));
                }
                return points;
            },
            points -> {
                List<Float> flat = new ArrayList<>(points.size() * 4);
                for (var p : points) { flat.add(p.x()); flat.add(p.y()); flat.add(p.z()); flat.add(p.w()); }
                return flat;
            }
    );

    private static final Codec<List<Vector2fc>> ALPHA_POINTS =
            Codec.withAlternative(Codec.list(ExtraCodecs.VECTOR2F), LEGACY_ALPHA_POINTS);
    private static final Codec<List<Vector4fc>> RGB_POINTS =
            Codec.withAlternative(Codec.list(ExtraCodecs.VECTOR4F), LEGACY_RGB_POINTS);

    @Getter private final List<Vector2fc> aP = new ArrayList<>();
    @Getter private final List<Vector4fc> rgbP = new ArrayList<>();

    public GradientColor() {
        aP.add(new Vector2f(0f, 1f));
        aP.add(new Vector2f(1f, 1f));
        rgbP.add(new Vector4f(0f, 1f, 1f, 1f));
        rgbP.add(new Vector4f(1f, 1f, 1f, 1f));
    }

    public GradientColor(int... colors) {
        if (colors.length == 0) {
            aP.add(new Vector2f(0f, 1f));
            rgbP.add(new Vector4f(0f, 1f, 1f, 1f));
        } else if (colors.length == 1) {
            int c = colors[0];
            aP.add(new Vector2f(0f, ColorUtils.alpha(c)));
            aP.add(new Vector2f(1f, ColorUtils.alpha(c)));
            rgbP.add(new Vector4f(0f, ColorUtils.red(c), ColorUtils.green(c), ColorUtils.blue(c)));
            rgbP.add(new Vector4f(1f, ColorUtils.red(c), ColorUtils.green(c), ColorUtils.blue(c)));
        } else {
            for (int i = 0; i < colors.length; i++) {
                float t = i / (float) (colors.length - 1);
                int c = colors[i];
                aP.add(new Vector2f(t, ColorUtils.alpha(c)));
                rgbP.add(new Vector4f(t, ColorUtils.red(c), ColorUtils.green(c), ColorUtils.blue(c)));
            }
        }
    }

    public float getAlpha(float t) {
        if (aP.isEmpty()) return 1f;
        if (t <= aP.get(0).x()) return aP.get(0).y();
        for (int i = 0; i < aP.size() - 1; i++) {
            var s = aP.get(i);
            var e = aP.get(i + 1);
            if (t >= s.x() && t <= e.x()) {
                float span = e.x() - s.x();
                float k = span <= 1e-6f ? 0f : (t - s.x()) / span;
                return s.y() + (e.y() - s.y()) * k;
            }
        }
        return aP.get(aP.size() - 1).y();
    }

    public Vector3f getRGB(float t) {
        if (rgbP.isEmpty()) return new Vector3f(1f, 1f, 1f);
        if (t <= rgbP.get(0).x()) {
            var first = rgbP.get(0);
            return new Vector3f(first.y(), first.z(), first.w());
        }
        for (int i = 0; i < rgbP.size() - 1; i++) {
            var s = rgbP.get(i);
            var e = rgbP.get(i + 1);
            if (t >= s.x() && t <= e.x()) {
                float span = e.x() - s.x();
                float k = span <= 1e-6f ? 0f : (t - s.x()) / span;
                return new Vector3f(
                        s.y() + (e.y() - s.y()) * k,
                        s.z() + (e.z() - s.z()) * k,
                        s.w() + (e.w() - s.w()) * k);
            }
        }
        var last = rgbP.get(rgbP.size() - 1);
        return new Vector3f(last.y(), last.z(), last.w());
    }

    public int getColor(float t) {
        float alpha = getAlpha(t);
        var rgb = getRGB(t);
        return ColorUtils.color(alpha, rgb.x, rgb.y, rgb.z);
    }

    public int getRGBColor(float t) {
        var rgb = getRGB(t);
        return ColorUtils.color(1f, rgb.x, rgb.y, rgb.z);
    }

    public int addAlpha(float t, float value) {
        if (aP.isEmpty() || t < aP.get(0).x()) {
            aP.add(0, new Vector2f(t, value));
            return 0;
        }
        for (int i = 0; i < aP.size() - 1; i++) {
            if (t >= aP.get(i).x() && t <= aP.get(i + 1).x()) {
                aP.add(i + 1, new Vector2f(t, value));
                return i + 1;
            }
        }
        aP.add(new Vector2f(t, value));
        return aP.size() - 1;
    }

    public int addRGB(float t, float r, float g, float b) {
        if (rgbP.isEmpty() || t < rgbP.get(0).x()) {
            rgbP.add(0, new Vector4f(t, r, g, b));
            return 0;
        }
        for (int i = 0; i < rgbP.size() - 1; i++) {
            if (t >= rgbP.get(i).x() && t <= rgbP.get(i + 1).x()) {
                rgbP.add(i + 1, new Vector4f(t, r, g, b));
                return i + 1;
            }
        }
        rgbP.add(new Vector4f(t, r, g, b));
        return rgbP.size() - 1;
    }

    @Override
    public void serialize(ValueOutput output) {
        output.store("a", ALPHA_POINTS, aP);
        output.store("rgb", RGB_POINTS, rgbP);
    }

    @Override
    public void deserialize(ValueInput input) {
        aP.clear();
        rgbP.clear();
        input.read("a", ALPHA_POINTS).ifPresent(aP::addAll);
        input.read("rgb", RGB_POINTS).ifPresent(rgbP::addAll);
    }

    public GradientColor copy() {
        var copy = new GradientColor();
        copy.aP.clear();
        copy.rgbP.clear();
        for (var p : aP) copy.aP.add(new Vector2f(p));
        for (var p : rgbP) copy.rgbP.add(new Vector4f(p));
        return copy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GradientColor that)) return false;
        return Objects.equals(aP, that.aP) && Objects.equals(rgbP, that.rgbP);
    }

    @Override
    public int hashCode() {
        return Objects.hash(aP, rgbP);
    }
}
