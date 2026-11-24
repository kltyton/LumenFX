package com.lowdragmc.lowdraglib2.math;

import com.lowdragmc.lowdraglib2.utils.LDLibExtraCodecs;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.Objects;

public final class Range {
    public static final Codec<Range> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            LDLibExtraCodecs.NUMBER.fieldOf("a").forGetter(Range::getA),
            LDLibExtraCodecs.NUMBER.fieldOf("b").forGetter(Range::getB)
    ).apply(instance, Range::of));

    public static final StreamCodec<FriendlyByteBuf, Range> STREAM_CODEC = StreamCodec.of(
            (buf, r) -> { buf.writeDouble(r.a.doubleValue()); buf.writeDouble(r.b.doubleValue()); },
            buf -> of(buf.readDouble(), buf.readDouble())
    );

    private final Number a;
    private final Number b;

    private Range(Number a, Number b) {
        this.a = a;
        this.b = b;
    }

    public static Range of(Number a, Number b) {
        return new Range(a, b);
    }

    public Number getA() { return a; }
    public Number getB() { return b; }

    public Number getMin() {
        return a.doubleValue() <= b.doubleValue() ? a : b;
    }

    public Number getMax() {
        return a.doubleValue() >= b.doubleValue() ? a : b;
    }

    public Range copy() {
        return new Range(a, b);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Range that)) return false;
        return Double.compare(a.doubleValue(), that.a.doubleValue()) == 0
                && Double.compare(b.doubleValue(), that.b.doubleValue()) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(a.doubleValue(), b.doubleValue());
    }

    @Override
    public String toString() {
        return "Range[" + a + ", " + b + "]";
    }
}
