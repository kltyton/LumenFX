/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data.number;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author KilaBash
 * @date 2023/5/27
 * @implNote NumberFunctionConfig
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD})
public @interface NumberFunctionConfig {
    enum NumberType {
        INTEGER,
        FLOAT,
        DOUBLE,
        LONG,
        SHORT,
        BYTE,
        AUTO
    }

    Class<? extends NumberFunction>[] types() default { Constant.class };
    float min() default Integer.MIN_VALUE;
    float max() default Integer.MAX_VALUE;
    float wheelDur() default 0.1f;
    double defaultValue() default 0;
    NumberType numberType() default NumberType.FLOAT;
    CurveConfig curveConfig() default @CurveConfig();
}
