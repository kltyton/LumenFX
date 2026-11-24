/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import lombok.Getter;
import lombok.Setter;
import org.joml.Vector2i;
import org.joml.Vector4f;

/**
 * @author KilaBash
 * @date 2023/5/31
 * @implNote UVAnimation
 */
public class UVAnimationSetting extends ToggleGroup {
    public enum Animation {
        WholeSheet,
        SingleRow,
    }

    @Setter
    @Getter
    @Configurable(name = "UVAnimationSetting.tiles", tips = "photon.emitter.config.uvAnimation.tiles")
    @ConfigNumber(range = {1, Integer.MAX_VALUE}, type = ConfigNumber.Type.INTEGER)
    protected Vector2i tiles = new Vector2i(1, 1);

    @Setter
    @Getter
    @Configurable(name = "UVAnimationSetting.animation", tips = "photon.emitter.config.uvAnimation.animation")
    protected Animation animation = Animation.WholeSheet;

    @Setter
    @Getter
    @Configurable(name = "UVAnimationSetting.frameOverTime", tips = "photon.emitter.config.uvAnimation.frameOverTime")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, curveConfig = @CurveConfig(bound = {0, 4}, xAxis = "lifetime", yAxis = "frame over time"))
    protected NumberFunction frameOverTime = NumberFunction.constant(0);

    @Setter
    @Getter
    @Configurable(name = "UVAnimationSetting.startFrame", tips = "photon.emitter.config.uvAnimation.startFrame")
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class}, min = 0)
    protected NumberFunction startFrame = NumberFunction.constant(0);

    @Setter
    @Getter
    @Configurable(name = "UVAnimationSetting.cycle", tips = "photon.emitter.config.uvAnimation.cycle")
    @ConfigNumber(range = {0, Integer.MAX_VALUE}, wheel = 1)
    protected float cycle = 1;

    public Vector4f getUVs(IParticle particle, float partialTicks) {
        return sample(particle, partialTicks, tiles, animation, startFrame, frameOverTime, cycle);
    }

    private static Vector4f sample(IParticle particle, float partialTicks, Vector2i tiles, Animation animation,
                                   NumberFunction startFrame, NumberFunction frameOverTime, float cycle) {
        var t = particle.getT(partialTicks);
        var currentFrame = startFrame.get(t, () -> particle.getMemRandom("startFrame")).floatValue();
        currentFrame += cycle * frameOverTime.get(t, () -> particle.getMemRandom("frameOverTime")).floatValue();
        if (animation == Animation.WholeSheet) {
            return ParticleAtlas.wrappedFrame((int) currentFrame, tiles.x(), tiles.y(), -1, new Vector4f());
        }
        int column = (int) (currentFrame % tiles.x());
        int row = (int) (particle.getMemRandom("randomRow") * tiles.y());
        return ParticleAtlas.cell(column, row, tiles.x(), tiles.y(), new Vector4f());
    }

    public Runtime createRuntime() {
        return new Runtime(this);
    }

    /** Per-emitter runtime layer for ParticleEmitter. The config keeps its own {@link #getUVs} for the
     *  Beam/Trail emitters (which have no runtime layer). */
    public static class Runtime {
        public final RuntimeValue<Boolean> enable;
        public final RuntimeValue<Vector2i> tiles;        // slot only (Vector2i → no timeline binding)
        public final RuntimeValue<Animation> animation;   // slot only (enum → no timeline binding)
        public final RuntimeValue<NumberFunction> frameOverTime;
        public final RuntimeValue<NumberFunction> startFrame;
        public final RuntimeValue<Float> cycle;

        public Runtime(UVAnimationSetting config) {
            this.enable = new RuntimeValue<>(config::isEnable);
            this.tiles = new RuntimeValue<>(config::getTiles);
            this.animation = new RuntimeValue<>(config::getAnimation);
            this.frameOverTime = new RuntimeValue<>(config::getFrameOverTime);
            this.startFrame = new RuntimeValue<>(config::getStartFrame);
            this.cycle = new RuntimeValue<>(config::getCycle);
        }

        public boolean isEnable() {
            return enable.get();
        }

        public Vector4f getUVs(IParticle particle, float partialTicks) {
            return sample(particle, partialTicks, tiles.get(), animation.get(), startFrame.get(), frameOverTime.get(), cycle.get());
        }

        public void clear() {
            enable.clear();
            tiles.clear();
            animation.clear();
            frameOverTime.clear();
            startFrame.clear();
            cycle.clear();
        }
    }
}
