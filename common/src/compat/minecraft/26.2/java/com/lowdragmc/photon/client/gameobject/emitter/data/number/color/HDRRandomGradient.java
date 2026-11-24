/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data.number.color;

import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib2.utils.ColorUtils;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import net.minecraft.util.Mth;
import org.joml.Vector2f;
import org.joml.Vector4f;

import java.util.function.Supplier;

/**
 * The HDR counterpart of {@link RandomGradient}: a random blend between two HDR gradients. Both the
 * gradient sampling and the blend stay in float space (no ARGB round-trip), so values above 1 survive.
 * See {@link HDRGradient} for the premultiplied-storage convention.
 */
@LDLRegisterClient(name = "hdr_random_gradient", registry = "photon:number_function")
@EqualsAndHashCode(callSuper = false)
public class HDRRandomGradient implements HDRColorFunction {

    @Getter
    @Persisted
    private final GradientColor gradientColor0, gradientColor1;

    public HDRRandomGradient() {
        this.gradientColor0 = new GradientColor();
        this.gradientColor1 = new GradientColor();
    }

    public HDRRandomGradient(int color) {
        this.gradientColor0 = new GradientColor(color, color);
        this.gradientColor1 = new GradientColor(color, color);
    }

    public HDRRandomGradient(GradientColor a, GradientColor b) {
        this.gradientColor0 = a;
        this.gradientColor1 = b;
    }

    @Override
    public void loadConfig(NumberFunctionConfig config) {
        var color = (int) config.defaultValue();
        gradientColor0.getAP().clear();
        gradientColor0.getRgbP().clear();
        gradientColor1.getAP().clear();
        gradientColor1.getRgbP().clear();
        var colors = new int[]{color, color};
        for (int i = 0; i < colors.length; i++) {
            var t = i / (colors.length - 1f);
            gradientColor0.getAP().add(new Vector2f(t, ColorUtils.alpha(colors[i])));
            gradientColor0.getRgbP().add(new Vector4f(t, ColorUtils.red(colors[i]), ColorUtils.green(colors[i]), ColorUtils.blue(colors[i])));
            gradientColor1.getAP().add(new Vector2f(t, ColorUtils.alpha(colors[i])));
            gradientColor1.getRgbP().add(new Vector4f(t, ColorUtils.red(colors[i]), ColorUtils.green(colors[i]), ColorUtils.blue(colors[i])));
        }
    }

    @Override
    public void sampleHDR(float t, Supplier<Float> lerp, Vector4f out) {
        var rgb0 = gradientColor0.getRGB(t);
        var rgb1 = gradientColor1.getRGB(t);
        var blend = lerp.get();
        out.set(Mth.lerp(blend, rgb0.x, rgb1.x),
                Mth.lerp(blend, rgb0.y, rgb1.y),
                Mth.lerp(blend, rgb0.z, rgb1.z),
                Mth.lerp(blend, gradientColor0.getAlpha(t), gradientColor1.getAlpha(t)));
    }

    @Override
    public NumberFunction copy() {
        return new HDRRandomGradient(gradientColor0.copy(), gradientColor1.copy());
    }

}
