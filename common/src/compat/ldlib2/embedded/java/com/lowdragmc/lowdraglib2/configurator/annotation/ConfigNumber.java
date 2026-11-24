package com.lowdragmc.lowdraglib2.configurator.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ConfigNumber {
    enum Type {
        AUTO(0, 0, 0),
        INTEGER(Integer.MIN_VALUE, Integer.MAX_VALUE, 1),
        FLOAT(-Float.MAX_VALUE, Float.MAX_VALUE, 0.1f),
        DOUBLE(-Double.MAX_VALUE, Double.MAX_VALUE, 0.1d),
        LONG(Long.MIN_VALUE, Long.MAX_VALUE, 1L),
        SHORT(Short.MIN_VALUE, Short.MAX_VALUE, (short) 1),
        BYTE(Byte.MIN_VALUE, Byte.MAX_VALUE, (byte) 1);

        public final Number min;
        public final Number max;
        public final Number wheel;

        Type(Number min, Number max, Number wheel) {
            this.min = min;
            this.max = max;
            this.wheel = wheel;
        }
    }

    double[] range();
    double wheel() default 0;
    Type type() default Type.AUTO;
}
