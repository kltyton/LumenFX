/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.math.noise.PerlinNoise;
import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.*;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import com.mojang.blaze3d.vertex.*;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.util.Mth;
import org.joml.Vector3f;


/**
 * @author KilaBash
 * @date 2023/5/31
 * @implNote NoiseSetting
 */
@Setter
@Getter
public class NoiseSetting extends ToggleGroup {
    public enum Quality {
        Noise1D,
        Noise2D,
        Noise3D
    }

    private final ThreadLocal<PerlinNoise> noise = ThreadLocal.withInitial(PerlinNoise::new);

    @Configurable(name = "NoiseSetting.frequency", tips = "photon.emitter.config.noise.frequency")
    @ConfigNumber(range = {-Float.MAX_VALUE, Float.MAX_VALUE})
    protected float frequency = 1;

    @Configurable(name = "NoiseSetting.quality", tips = "photon.emitter.config.noise.quality")
    protected Quality quality = Quality.Noise2D;

    @Configurable(name = "NoiseSetting.remap", subConfigurable = true, tips = "photon.emitter.config.noise.remap")
    protected final Remap remap = new Remap();

    @Configurable(name = "NoiseSetting.position", tips = "photon.emitter.config.noise.position")
    @NumberFunction3Config(common = @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "lifetime", yAxis = "strength")))
    protected NumberFunction3 position = new NumberFunction3(0.1, 0.1, 0.1);

    @Configurable(name = "NoiseSetting.rotation", tips = "photon.emitter.config.noise.rotation")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, wheelDur = 10, curveConfig = @CurveConfig(bound = {0, 180}, xAxis = "lifetime", yAxis = "rotation amount"))
    protected NumberFunction rotation = NumberFunction.constant(0);

    @Configurable(name = "NoiseSetting.size", tips = "photon.emitter.config.noise.size")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, curveConfig = @CurveConfig(bound = {-1, 1}, xAxis = "lifetime", yAxis = "size amount"))
    protected NumberFunction size = NumberFunction.constant(0);


    public Runtime createRuntime() {
        return new Runtime(this);
    }

    /** Per-emitter runtime layer. Value slots (frequency/quality/position/rotation/size) override the
     *  config; the {@code remap} sub-config and the {@code noise} thread-local stay on the config. */
    public static class Runtime {
        private final NoiseSetting config;
        public final RuntimeValue<Boolean> enable;
        public final RuntimeValue<Float> frequency;
        public final RuntimeValue<Quality> quality; // slot only (enum → no timeline binding)
        public final RuntimeValue<NumberFunction3> position;
        public final RuntimeValue<NumberFunction> rotation;
        public final RuntimeValue<NumberFunction> size;

        public Runtime(NoiseSetting config) {
            this.config = config;
            this.enable = new RuntimeValue<>(config::isEnable);
            this.frequency = new RuntimeValue<>(() -> config.frequency);
            this.quality = new RuntimeValue<>(() -> config.quality);
            this.position = new RuntimeValue<>(() -> config.position);
            this.rotation = new RuntimeValue<>(() -> config.rotation);
            this.size = new RuntimeValue<>(() -> config.size);
        }

        public boolean isEnable() {
            return enable.get();
        }

        public float getNoise(float t) {
            var input = t * frequency.get();
            float value = (float) switch (quality.get()) {
                case Noise1D -> config.noise.get().noise(input);
                case Noise2D -> config.noise.get().noise(input, input);
                case Noise3D -> config.noise.get().noise(input, input, input);
            };
            if (config.remap.isEnable()) {
                value = config.remap.remapCurve.get((value + 1) / 2, () -> 0f).floatValue();
            }
            return value;
        }

        public void setupSeed(IParticle particle) {
            var seed = particle.getMemRandom("noise-seed", randomSource -> (float) randomSource.nextGaussian()) * 255;
            var generator = config.noise.get(); // thread-local
            if (generator.getSeed() != seed) { // skip the redundant write for consecutive samples of one particle
                generator.setSeed(seed);
            }
        }

        public Vector3f getRotation(IParticle particle, float partialTicks) {
            setupSeed(particle);
            var t = particle.getT(partialTicks);
            var degree = rotation.get().get(t, () -> particle.getMemRandom("noise-rotation")).floatValue();
            if (degree != 0) {
                return new Vector3f(degree, 0, 0).mul(getNoise((t + 10 * particle.getMemRandom("noise-rotation-degree")) * 100) * Mth.TWO_PI / 360);
            }
            return new Vector3f(0, 0, 0);
        }

        public Vector3f getSize(IParticle particle, float partialTicks) {
            setupSeed(particle);
            var t = particle.getT(partialTicks);
            var scale = size.get().get(t, () -> particle.getMemRandom("noise-size")).floatValue();
            if (scale != 0) {
                return new Vector3f(scale, scale, scale).mul(getNoise((t + 10 * particle.getMemRandom("noise-size-scale")) * 100));
            }
            return new Vector3f(0, 0, 0);
        }

        public Vector3f getPosition(IParticle particle, float partialTicks) {
            setupSeed(particle);
            var t = particle.getT(partialTicks);
            var offset = position.get().get(t, () -> particle.getMemRandom("noise-position"));
            if (!(offset.x == 0 && offset.y == 0 && offset.z == 0)) {
                offset.mul(
                        getNoise((t + 10 * particle.getMemRandom("noise-position-x")) * 100),
                        getNoise((t + 10 * particle.getMemRandom("noise-position-y")) * 100),
                        getNoise((t + 10 * particle.getMemRandom("noise-position-z")) * 100));
                return offset;
            }
            return new Vector3f(0, 0, 0);
        }

        public void clear() {
            enable.clear();
            frequency.clear();
            quality.clear();
            position.clear();
            rotation.clear();
            size.clear();
        }
    }


    public static class Remap extends ToggleGroup {
        @Setter
        @Getter
        @Configurable
        @NumberFunctionConfig(types = {Curve.class}, defaultValue = 1f, curveConfig = @CurveConfig(bound = {-1, 1}, xAxis = "base noise", yAxis = "remap result"))
        protected NumberFunction remapCurve = new Curve(Integer.MIN_VALUE, Integer.MAX_VALUE, -1, 1, 1f, "base noise", "remap result");
    }
}
