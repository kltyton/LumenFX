/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data.number;

import com.kltyton.lumenfx.internal.photon.core.registry.annotation.LDLRegisterClient;
import com.kltyton.lumenfx.internal.photon.core.syncdata.annotation.Persisted;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.util.function.Supplier;

/**
 * @author KilaBash
 * @date 2023/5/26
 * @implNote RandomConstant
 */
@LDLRegisterClient(name = "random_constant", registry = "photon:number_function")
@EqualsAndHashCode(callSuper = false)
public class RandomConstant implements NumberFunction {
    @Setter
    @Getter
    @Persisted
    private Number a, b;

    public RandomConstant() {
        a = 0;
        b = 0;
    }

    public RandomConstant(Number a, Number b) {
        this.a = a;
        this.b = b;
    }

    public void loadConfig(NumberFunctionConfig config) {
        a = switch (config.numberType()) {
            case INTEGER -> (int) config.defaultValue();
            case FLOAT -> (float) config.defaultValue();
            case LONG -> (long) config.defaultValue();
            case SHORT -> (short) config.defaultValue();
            case BYTE -> (byte) config.defaultValue();
            default -> config.defaultValue();
        };
        b = switch (config.numberType()) {
            case INTEGER -> (int) config.defaultValue();
            case FLOAT -> (float) config.defaultValue();
            case LONG -> (long) config.defaultValue();
            case SHORT -> (short) config.defaultValue();
            case BYTE -> (byte) config.defaultValue();
            default -> config.defaultValue();
        };
    }

    @Override
    public Number get(float t, Supplier<Float> lerp) {
        var min = Math.min(a.doubleValue(), b.doubleValue());
        var max = Math.max(a.doubleValue(), b.doubleValue());
        if (min == max) return max;
        return (min + lerp.get() * (max - min));
    }

    @Override
    public NumberFunction copy() {
        return new RandomConstant(a, b);
    }

}
