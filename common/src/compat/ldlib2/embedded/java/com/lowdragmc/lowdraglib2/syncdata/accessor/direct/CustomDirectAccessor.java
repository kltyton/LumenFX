package com.lowdragmc.lowdraglib2.syncdata.accessor.direct;

import com.mojang.serialization.Codec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.function.BiPredicate;
import java.util.function.Function;

public final class CustomDirectAccessor<TYPE> {
    private final Class<TYPE> type;
    private final boolean supportChildClass;
    private final Codec<TYPE> codec;
    private final StreamCodec<? super RegistryFriendlyByteBuf, TYPE> streamCodec;

    private CustomDirectAccessor(Class<TYPE> type, boolean supportChildClass, Codec<TYPE> codec,
                                 StreamCodec<? super RegistryFriendlyByteBuf, TYPE> streamCodec) {
        this.type = type;
        this.supportChildClass = supportChildClass;
        this.codec = codec;
        this.streamCodec = streamCodec;
    }

    public static <TYPE> Builder<TYPE> builder(Class<TYPE> type) {
        return new Builder<>(type, true);
    }

    public static <TYPE> Builder<TYPE> builder(Class<TYPE> type, boolean supportChildClass) {
        return new Builder<>(type, supportChildClass);
    }

    public boolean test(Class<?> candidate) {
        return supportChildClass ? type.isAssignableFrom(candidate) : type == candidate;
    }

    public Class<TYPE> getType() { return type; }
    public boolean isSupportChildClass() { return supportChildClass; }
    public Codec<TYPE> getCodec() { return codec; }
    public StreamCodec<? super RegistryFriendlyByteBuf, TYPE> getStreamCodec() { return streamCodec; }

    public static final class Builder<TYPE> {
        private final Class<TYPE> type;
        private final boolean supportChildClass;
        private Codec<TYPE> codec;
        private StreamCodec<? super RegistryFriendlyByteBuf, TYPE> streamCodec;

        private Builder(Class<TYPE> type, boolean supportChildClass) {
            this.type = type;
            this.supportChildClass = supportChildClass;
        }

        public Builder<TYPE> codec(Codec<TYPE> codec) { this.codec = codec; return this; }
        public Builder<TYPE> streamCodec(StreamCodec<? super RegistryFriendlyByteBuf, TYPE> streamCodec) { this.streamCodec = streamCodec; return this; }
        public Builder<TYPE> codecMark() { return this; }
        public Builder<TYPE> copyMark(Function<TYPE, TYPE> copier) { return this; }
        public <MARK> Builder<TYPE> customMark(Function<TYPE, MARK> obtain, BiPredicate<MARK, TYPE> diff) { return this; }

        public CustomDirectAccessor<TYPE> build() {
            return new CustomDirectAccessor<>(type, supportChildClass, codec, streamCodec);
        }
    }
}
