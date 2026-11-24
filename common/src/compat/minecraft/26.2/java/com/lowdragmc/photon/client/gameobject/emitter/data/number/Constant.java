/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data.number;

import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import lombok.Setter;
import net.minecraft.util.RandomSource;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * @author KilaBash
 * @date 2023/5/26
 * @implNote Constant
 */
@LDLRegisterClient(name = "constant", registry = "photon:number_function")
public class Constant implements NumberFunction {
    @Setter
    @Persisted
    private Number number;

    public Constant() {
        number = 0;
    }

    public Constant(Number number) {
        this.number = number;
    }

    public void loadConfig(NumberFunctionConfig config) {
        number = switch (config.numberType()) {
            case INTEGER -> (int) config.defaultValue();
            case FLOAT -> (float) config.defaultValue();
            case LONG -> (long) config.defaultValue();
            case SHORT -> (short) config.defaultValue();
            case BYTE -> (byte) config.defaultValue();
            default -> config.defaultValue();
        };
    }

    public Number getNumber() {
        return number;
    }

    @Override
    public Number get(RandomSource randomSource, float t) {
        return number;
    }

    @Override
    public Number get(float t, Supplier<Float> lerp) {
        return number;
    }

    @Override
    public NumberFunction copy() {
        return new Constant(number);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) return false;
        if (obj == this) return true;
        if (obj instanceof Constant constant) {
            return Objects.equals(number, constant.number);
        }
        return super.equals(obj);
    }
}
