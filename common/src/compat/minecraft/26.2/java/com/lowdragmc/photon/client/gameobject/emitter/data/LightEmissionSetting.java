/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data;
import com.kltyton.lumenfx.internal.photon.core.editor.ui.sceneeditor.sceneobject.TransformRef;
import com.kltyton.lumenfx.internal.photon.core.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.HDRColorFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.HDRConstantColor;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.HDRGradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.HDRRandomColor;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.HDRRandomGradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.gameobject.light.VolumetricSetting;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import com.lowdragmc.photon.client.light.DynamicLight;
import com.lowdragmc.photon.client.light.LightSink;
import lombok.Getter;
import lombok.Setter;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Unity-style Lights module: a share of the particles become dynamic lights that follow and fade with them. */
@Setter
@Getter
public class LightEmissionSetting extends ToggleGroup {

    @Persisted
    protected float ratio = 1;

    @Persisted
    protected int maxLights = 32;

    @Persisted
    protected boolean useParticleColor = true;

    @Persisted
    @NumberFunctionConfig(types = {HDRConstantColor.class, HDRRandomColor.class, HDRGradient.class, HDRRandomGradient.class}, defaultValue = -1)
    protected NumberFunction color = new HDRConstantColor();

    @Persisted
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, defaultValue = 4,
            curveConfig = @CurveConfig(bound = {0, 32}, xAxis = "lifetime", yAxis = "intensity"))
    protected NumberFunction intensity = NumberFunction.constant(4);

    @Persisted
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, defaultValue = 4,
            curveConfig = @CurveConfig(bound = {0, 32}, xAxis = "lifetime", yAxis = "range"))
    protected NumberFunction range = NumberFunction.constant(4);

    @Persisted
    protected boolean alphaAffectsIntensity = true;

    @Persisted
    protected boolean sizeAffectsRange = false;

    @Persisted
    protected boolean castShadows = false;

    @Persisted
    protected float sourceRadius = 0.1f;

    @Persisted(subPersisted = true)
    protected final Spot spot = new Spot();

    @Persisted(subPersisted = true)
    protected final VolumetricSetting volumetric = new VolumetricSetting();

    public Runtime createRuntime() {
        return new Runtime(this);
    }

    /** Off: point lights. On: cones aimed along each particle's velocity, or a fixed direction. */
    @Setter
    @Getter
    public static class Spot extends ToggleGroup {
        @Persisted
        protected float innerAngle = 20;

        @Persisted
        protected float outerAngle = 35;

        @Persisted
        protected boolean alignToVelocity = true;

        @Persisted
        protected Vector3f direction = new Vector3f(0, -1, 0);

        @Persisted
        protected ValueSpace space = ValueSpace.Local;
        @Persisted
        public final TransformRef customSpace = new TransformRef();

        private void aim(DynamicLight light, TileParticle particle) {
            light.spot(innerAngle, outerAngle);
            if (alignToVelocity) {
                var velocity = particle.getRealVelocity();
                if (velocity.lengthSquared() > 1e-8f) {
                    light.direction.set(velocity);
                    return;
                }
            }
            light.direction.set(direction);
            switch (space) {
                case Local -> particle.simDirToWorld(particle.emitterDirToSim(light.direction));
                case Custom -> CustomSpace.dirToWorld(customSpace, particle.getEmitter().getScene(), light.direction);
                case World -> {
                }
            }
        }
    }

    public static class Runtime {
        private final LightEmissionSetting config;
        // per-module keys for the particle's memoized randoms
        private final Object pickKey = new Object();
        private final Object colorKey = new Object();
        private final Object intensityKey = new Object();
        private final Object rangeKey = new Object();
        private final Object volumetricKey = new Object();
        private final Vector4f sample = new Vector4f();
        public final RuntimeValue<Boolean> enable;
        public final RuntimeValue<NumberFunction> color;
        public final RuntimeValue<NumberFunction> intensity;
        public final RuntimeValue<NumberFunction> range;

        public Runtime(LightEmissionSetting config) {
            this.config = config;
            this.enable = new RuntimeValue<>(config::isEnable);
            this.color = new RuntimeValue<>(() -> config.color);
            this.intensity = new RuntimeValue<>(() -> config.intensity);
            this.range = new RuntimeValue<>(() -> config.range);
        }

        public boolean isEnable() {
            return enable.get();
        }

        public void submit(Iterable<? extends Iterable<?>> particleQueues, LightSink sink, float partialTick) {
            int budget = config.maxLights;
            for (var queue : particleQueues) {
                for (var element : queue) {
                    if (budget <= 0) return;
                    if (!(element instanceof TileParticle particle) || particle.getDelay() > 0 || !particle.isAlive()) continue;
                    if (particle.getMemRandom(pickKey) > config.ratio) continue;
                    float t = particle.getT(partialTick);
                    float intensity = this.intensity.get().get(t, () -> particle.getMemRandom(intensityKey)).floatValue();
                    float range = this.range.get().get(t, () -> particle.getMemRandom(rangeKey)).floatValue();
                    var rgba = particle.getRealColor(partialTick);
                    if (config.alphaAffectsIntensity) intensity *= rgba.w;
                    if (config.sizeAffectsRange) {
                        var size = particle.getRealSize(partialTick);
                        range *= Math.max(size.x, Math.max(size.y, size.z));
                    }
                    if (intensity <= 0 || range <= 0) continue;
                    HDRColorFunction.sample(color.get(), t, () -> particle.getMemRandom(colorKey), sample);
                    float r = sample.x, g = sample.y, b = sample.z;
                    if (config.useParticleColor) {
                        r *= rgba.x;
                        g *= rgba.y;
                        b *= rgba.z;
                    }
                    var position = particle.getWorldPos(partialTick);
                    var light = sink.next().at(position.x, position.y, position.z).color(r, g, b).intensity(intensity).range(range)
                            .shadows(config.castShadows).sourceRadius(config.sourceRadius);
                    if (config.spot.isEnable()) config.spot.aim(light, particle);
                    if (config.volumetric.isEnable()) {
                        light.volumetric(Math.max(0f, config.volumetric.getStrength()
                                .get(t, () -> particle.getMemRandom(volumetricKey)).floatValue()));
                    }
                    budget--;
                }
            }
        }

        public void clear() {
            enable.clear();
            color.clear();
            intensity.clear();
            range.clear();
        }
    }
}
