/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.forcefield;
import com.kltyton.lumenfx.internal.photon.core.syncdata.annotation.Persisted;
import com.kltyton.lumenfx.internal.photon.core.syncdata.IPersistedSerializable;
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

/**
 * Pure-data force-field config (Unity {@code ParticleSystemForceField} parity, minus vector fields);
 * value-use behaviour lives on the co-located {@link Runtime}. Curves are evaluated over the affected
 * particle's normalized lifetime with per-particle memoized randoms.
 */
@Getter
@Setter
public class ForceFieldConfig implements IPersistedSerializable {

    public enum Shape {
        Sphere,
        Hemisphere,
        Cylinder,
        Box
    }

    @Persisted
    protected Shape shape = Shape.Sphere;

    @Persisted
    protected float startRange = 0;

    @Persisted
    protected float endRange = 1;

    @Persisted
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, curveConfig = @CurveConfig(bound = {-2, 2}, xAxis = "lifetime", yAxis = "force"))
    protected NumberFunction directionX = NumberFunction.constant(0);

    @Persisted
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, curveConfig = @CurveConfig(bound = {-2, 2}, xAxis = "lifetime", yAxis = "force"))
    protected NumberFunction directionY = NumberFunction.constant(0);

    @Persisted
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, curveConfig = @CurveConfig(bound = {-2, 2}, xAxis = "lifetime", yAxis = "force"))
    protected NumberFunction directionZ = NumberFunction.constant(0);

    @Persisted
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, curveConfig = @CurveConfig(bound = {-2, 2}, xAxis = "lifetime", yAxis = "gravity"))
    protected NumberFunction gravity = NumberFunction.constant(0);

    @Persisted
    protected float gravityFocus = 0;

    @Persisted
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, curveConfig = @CurveConfig(bound = {-2, 2}, xAxis = "lifetime", yAxis = "speed"))
    protected NumberFunction rotationSpeed = NumberFunction.constant(0);

    @Persisted
    protected float rotationAttraction = 0.5f;

    @Persisted
    protected float rotationRandomnessX = 0;

    @Persisted
    protected float rotationRandomnessZ = 0;

    @Persisted
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, curveConfig = @CurveConfig(bound = {0, 2}, xAxis = "lifetime", yAxis = "drag"))
    protected NumberFunction drag = NumberFunction.constant(0);

    @Persisted
    protected boolean multiplyDragByParticleSize = true;

    @Persisted
    protected boolean multiplyDragByParticleVelocity = true;

    public Runtime createRuntime() {
        return new Runtime(this);
    }

    /** Per-object mutable layer: timeline override slots over the immutable config. */
    public static class Runtime {
        private final ForceFieldConfig config;
        public final RuntimeValue<NumberFunction> directionX;
        public final RuntimeValue<NumberFunction> directionY;
        public final RuntimeValue<NumberFunction> directionZ;
        public final RuntimeValue<NumberFunction> gravity;
        public final RuntimeValue<NumberFunction> rotationSpeed;
        public final RuntimeValue<NumberFunction> drag;
        public final RuntimeValue<Float> startRange;
        public final RuntimeValue<Float> endRange;
        public final RuntimeValue<Float> gravityFocus;
        public final RuntimeValue<Float> rotationAttraction;

        public Runtime(ForceFieldConfig config) {
            this.config = config;
            this.directionX = new RuntimeValue<>(() -> config.directionX);
            this.directionY = new RuntimeValue<>(() -> config.directionY);
            this.directionZ = new RuntimeValue<>(() -> config.directionZ);
            this.gravity = new RuntimeValue<>(() -> config.gravity);
            this.rotationSpeed = new RuntimeValue<>(() -> config.rotationSpeed);
            this.drag = new RuntimeValue<>(() -> config.drag);
            this.startRange = new RuntimeValue<>(config::getStartRange);
            this.endRange = new RuntimeValue<>(config::getEndRange);
            this.gravityFocus = new RuntimeValue<>(config::getGravityFocus);
            this.rotationAttraction = new RuntimeValue<>(config::getRotationAttraction);
        }

        public Shape getShape() {
            return config.shape;
        }

        public float getStartRange() {
            return startRange.get();
        }

        public float getEndRange() {
            return endRange.get();
        }

        public float getGravityFocus() {
            return gravityFocus.get();
        }

        public float getRotationAttraction() {
            return rotationAttraction.get();
        }

        public float getDirectionX(IParticle particle, float t) {
            return directionX.get().get(t, () -> particle.getMemRandom(directionX)).floatValue();
        }

        public float getDirectionY(IParticle particle, float t) {
            return directionY.get().get(t, () -> particle.getMemRandom(directionY)).floatValue();
        }

        public float getDirectionZ(IParticle particle, float t) {
            return directionZ.get().get(t, () -> particle.getMemRandom(directionZ)).floatValue();
        }

        public float getGravity(IParticle particle, float t) {
            return gravity.get().get(t, () -> particle.getMemRandom(gravity)).floatValue();
        }

        public float getRotationSpeed(IParticle particle, float t) {
            return rotationSpeed.get().get(t, () -> particle.getMemRandom(rotationSpeed)).floatValue();
        }

        public float getDrag(IParticle particle, float t) {
            return drag.get().get(t, () -> particle.getMemRandom(drag)).floatValue();
        }

        public void clear() {
            directionX.clear();
            directionY.clear();
            directionZ.clear();
            gravity.clear();
            rotationSpeed.clear();
            drag.clear();
            startRange.clear();
            endRange.clear();
            gravityFocus.clear();
            rotationAttraction.clear();
        }
    }
}
