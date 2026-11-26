/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data;
import com.kltyton.lumenfx.internal.photon.core.editor.ui.sceneeditor.sceneobject.TransformRef;
import com.kltyton.lumenfx.internal.photon.core.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.*;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.gameobject.particle.IParticle;
import org.joml.Vector3f;
import lombok.Getter;
import lombok.Setter;

/**
 * Pure-data force-over-lifetime config; value-use behaviour lives on the co-located {@link Runtime}.
 *
 * @author KilaBash
 * @date 2023/5/30
 */
@Setter
@Getter
public class ForceOverLifetimeSetting extends ToggleGroup {

    @Persisted
    @NumberFunction3Config(common = @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, curveConfig = @CurveConfig(bound = {-1, 1}, xAxis = "lifetime", yAxis = "force")))
    protected NumberFunction3 force = new NumberFunction3(0, 0, 0);

    /** The space the force vector is expressed in. @see ValueSpace */
    @Setter
    @Getter
    @Persisted
    protected ValueSpace simulationSpace = ValueSpace.Local;
    @Persisted
    public final TransformRef customSpace = new TransformRef();

    public Runtime createRuntime() {
        return new Runtime(this);
    }

    public static class Runtime {
        private final ForceOverLifetimeSetting config;
        public final RuntimeValue<Boolean> enable;
        public final RuntimeValue<NumberFunction3> force;

        public Runtime(ForceOverLifetimeSetting config) {
            this.config = config;
            this.enable = new RuntimeValue<>(config::isEnable);
            this.force = new RuntimeValue<>(() -> config.force);
        }

        public boolean isEnable() {
            return enable.get();
        }

        public ValueSpace getSimulationSpace() {
            return config.simulationSpace;
        }

        public TransformRef getCustomSpace() {
            return config.customSpace;
        }

        public Vector3f getForce(IParticle particle) {
            return force.get().get(particle.getT(), () -> particle.getMemRandom(this)).mul(0.05f);
        }

        public void clear() {
            enable.clear();
            force.clear();
        }
    }
}
