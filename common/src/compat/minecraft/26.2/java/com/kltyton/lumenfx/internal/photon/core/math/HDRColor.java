package com.kltyton.lumenfx.internal.photon.core.math;

import com.kltyton.lumenfx.internal.photon.core.utils.ColorUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import lombok.Getter;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ExtraCodecs;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.util.Objects;

public class HDRColor {
    public static final Codec<HDRColor> CODEC = Codec.withAlternative(
            RecordCodecBuilder.create(instance -> instance.group(
                    ExtraCodecs.VECTOR4F.fieldOf("color").forGetter(HDRColor::toRawVector4f),
                    Codec.FLOAT.optionalFieldOf("intensity", 1f).forGetter(HDRColor::getIntensity)
            ).apply(instance, HDRColor::new)),
            ExtraCodecs.VECTOR4F,
            HDRColor::fromPremultiplied
    );

    public static final StreamCodec<ByteBuf, HDRColor> STREAM_CODEC = StreamCodec.of(
            (buf, c) -> {
                buf.writeFloat(c.r);
                buf.writeFloat(c.g);
                buf.writeFloat(c.b);
                buf.writeFloat(c.a);
                buf.writeFloat(c.intensity);
            },
            buf -> new HDRColor(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat())
    );

    @Getter private float r;
    @Getter private float g;
    @Getter private float b;
    @Getter private float a;
    @Getter private float intensity;

    public HDRColor() {
        this(1f, 1f, 1f, 1f, 1f);
    }

    public HDRColor(float r, float g, float b, float a, float intensity) {
        set(r, g, b, a, intensity);
    }

    public HDRColor(Vector4fc raw, float intensity) {
        this(raw.x(), raw.y(), raw.z(), raw.w(), intensity);
    }

    public HDRColor(HDRColor other) {
        this(other.r, other.g, other.b, other.a, other.intensity);
    }

    public static HDRColor white() {
        return new HDRColor(1f, 1f, 1f, 1f, 1f);
    }

    public static HDRColor black() {
        return new HDRColor(0f, 0f, 0f, 1f, 1f);
    }

    public static HDRColor fromARGB(int argb) {
        return new HDRColor(ColorUtils.red(argb), ColorUtils.green(argb), ColorUtils.blue(argb), ColorUtils.alpha(argb), 1f);
    }

    public static HDRColor fromPremultiplied(Vector4fc v) {
        return fromPremultiplied(v.x(), v.y(), v.z(), v.w());
    }

    public static HDRColor fromPremultiplied(float r, float g, float b, float a) {
        float peak = Math.max(r, Math.max(g, b));
        if (peak > 1f) {
            return new HDRColor(r / peak, g / peak, b / peak, a, peak);
        }
        return new HDRColor(r, g, b, a, 1f);
    }

    public void set(float r, float g, float b, float a, float intensity) {
        this.r = r;
        this.g = g;
        this.b = b;
        this.a = a;
        this.intensity = Math.max(0f, intensity);
    }

    public void set(HDRColor other) {
        set(other.r, other.g, other.b, other.a, other.intensity);
    }

    public void setIntensity(float intensity) {
        this.intensity = Math.max(0f, intensity);
    }

    public HDRColor withIntensity(float intensity) {
        return new HDRColor(r, g, b, a, intensity);
    }

    public int baseARGB() {
        return ColorUtils.color(a, r, g, b);
    }

    public Vector4f toVector4f() {
        return new Vector4f(r * intensity, g * intensity, b * intensity, a);
    }

    public Vector4f toVector4fOpaque() {
        return new Vector4f(r * intensity, g * intensity, b * intensity, 1f);
    }

    public Vector4f toRawVector4f() {
        return new Vector4f(r, g, b, a);
    }

    public int toARGB() {
        return ColorUtils.color(a, Math.min(1f, r * intensity), Math.min(1f, g * intensity), Math.min(1f, b * intensity));
    }

    public HDRColor copy() {
        return new HDRColor(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HDRColor that)) return false;
        return Float.compare(r, that.r) == 0
                && Float.compare(g, that.g) == 0
                && Float.compare(b, that.b) == 0
                && Float.compare(a, that.a) == 0
                && Float.compare(intensity, that.intensity) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(r, g, b, a, intensity);
    }

    @Override
    public String toString() {
        return "HDRColor(" + r + "," + g + "," + b + "," + a + " * " + intensity + ")";
    }
}
