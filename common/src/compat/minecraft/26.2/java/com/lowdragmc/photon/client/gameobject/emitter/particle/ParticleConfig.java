/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.particle;
import com.kltyton.lumenfx.internal.photon.core.editor.ui.sceneeditor.sceneobject.TransformRef;
import com.kltyton.lumenfx.internal.photon.core.syncdata.IPersistedSerializable;
import com.kltyton.lumenfx.internal.photon.core.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.PhotonFXRenderPass;
import com.lowdragmc.photon.client.gameobject.particle.ParticleLifetime;
import com.lowdragmc.photon.client.gameobject.emitter.data.*;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.*;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Color;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.RandomColor;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.RandomGradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.mojang.blaze3d.vertex.*;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.ParametersAreNonnullByDefault;


/**
 * @author KilaBash
 * @date 2023/6/11
 * @implNote ParticleConfig
 */
public class ParticleConfig implements IPersistedSerializable {
    @Setter
    @Getter
    @Persisted
    protected int duration = 100;
    @Setter
    @Getter
    @Persisted
    protected boolean looping = true;
    @Setter
    @Getter
    @Persisted
    protected int prewarm = 0;
    @Setter
    @Getter
    @Persisted
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, numberType = NumberFunctionConfig.NumberType.INTEGER, min = 0, curveConfig = @CurveConfig(bound = {0, 100}, xAxis = "duration", yAxis = "delay"))
    protected NumberFunction startDelay = NumberFunction.constant(0);
    @Setter
    @Getter
    @Persisted
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, numberType = NumberFunctionConfig.NumberType.INTEGER, min = 0, defaultValue = 100, curveConfig = @CurveConfig(bound = {0, 200}, xAxis = "duration", yAxis = "life time"))
    protected NumberFunction startLifetime = NumberFunction.constant(100);
    @Setter
    @Getter
    @Persisted
    protected ParticleLifetime.Mode lifetimeMode = ParticleLifetime.Mode.Once;
    @Setter
    @Getter
    @Persisted
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, defaultValue = 1f, curveConfig = @CurveConfig(bound = {-2, 2}, xAxis = "duration", yAxis = "speed"))
    protected NumberFunction startSpeed = NumberFunction.constant(1);
    @Setter
    @Getter
    @Persisted
    @NumberFunction3Config(common = @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, defaultValue = 0.1f, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "duration", yAxis = "size")))
    protected NumberFunction3 startSize = new NumberFunction3(0.1, 0.1, 0.1);
    @Setter
    @Getter
    @Persisted
    @NumberFunction3Config(affectX = false, affectY = false, common = @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, wheelDur = 10, curveConfig = @CurveConfig(bound = {0, 360}, xAxis = "duration", yAxis = "rotation")))
    protected NumberFunction3 startRotation = new NumberFunction3(0, 0, 0);
    @Setter
    @Getter
    @Persisted
    @NumberFunctionConfig(types = {Color.class, RandomColor.class, Gradient.class, RandomGradient.class}, defaultValue = -1)
    protected NumberFunction startColor = NumberFunction.color(-1);
    @Setter
    @Getter
    @Persisted
    protected Space simulationSpace = Space.Local;
    @Persisted
    public final TransformRef customSpace = new TransformRef();
    @Setter
    @Getter
    @Persisted
    protected int maxParticles = 2000;
    @Setter
    @Getter
    @Persisted
    protected boolean parallelUpdate = false;
    @Persisted(subPersisted = true)
    public final EmissionSetting emission = new EmissionSetting();
    @Persisted(subPersisted = true)
    public final ShapeSetting shape = new ShapeSetting();
    @Persisted(subPersisted = true)
    public final ParticleRendererSetting renderer = new ParticleRendererSetting(this);
    @Persisted(subPersisted = true)
    public final PhysicsSetting physics = new PhysicsSetting();
    @Persisted(subPersisted = true)
    public final LightOverLifetimeSetting lights = new LightOverLifetimeSetting();
    @Persisted(subPersisted = true)
    public final VelocityOverLifetimeSetting velocityOverLifetime = new VelocityOverLifetimeSetting();
    @Persisted(subPersisted = true)
    public final InheritVelocitySetting inheritVelocity = new InheritVelocitySetting();
    @Persisted(subPersisted = true)
    public final LifetimeByEmitterSpeedSetting lifetimeByEmitterSpeed = new LifetimeByEmitterSpeedSetting();
    @Persisted(subPersisted = true)
    public final ForceOverLifetimeSetting forceOverLifetime = new ForceOverLifetimeSetting();
    @Persisted(subPersisted = true)
    public final ExternalForcesSetting externalForces = new ExternalForcesSetting();
    @Persisted(subPersisted = true)
    public final ColorOverLifetimeSetting colorOverLifetime = new ColorOverLifetimeSetting();
    @Persisted(subPersisted = true)
    public final ColorBySpeedSetting colorBySpeed = new ColorBySpeedSetting();
    @Persisted(subPersisted = true)
    public final SizeOverLifetimeSetting sizeOverLifetime = new SizeOverLifetimeSetting();
    @Persisted(subPersisted = true)
    public final SizeBySpeedSetting sizeBySpeed = new SizeBySpeedSetting();
    @Persisted(subPersisted = true)
    public final RotationOverLifetimeSetting rotationOverLifetime = new RotationOverLifetimeSetting();
    @Persisted(subPersisted = true)
    public final RotationBySpeedSetting rotationBySpeed = new RotationBySpeedSetting();
    @Persisted(subPersisted = true)
    public final NoiseSetting noise = new NoiseSetting();
    @Persisted(subPersisted = true)
    public final UVAnimationSetting uvAnimation = new UVAnimationSetting();
    @Persisted(subPersisted = true)
    public final LightEmissionSetting lightEmission = new LightEmissionSetting();
    @Persisted(subPersisted = true)
    public final TrailsSetting trails = new TrailsSetting();
    @Persisted(subPersisted = true)
    public final SubEmittersSetting subEmitters = new SubEmittersSetting();
    @Persisted(subPersisted = true)
    public final ParticleAdditionalGPUDataSetting additionalGPUDataSetting = new ParticleAdditionalGPUDataSetting(this);

    // runtime
    /** Shared default render runtime (no overrides → reads config) that the shared pass draws with;
     *  per-emitter overriding runtimes come from {@code ParticleRuntime.renderer}. */
    public final ParticleRendererSetting.Runtime defaultRenderRuntime = renderer.createRuntime();
    public final RenderPass particleRenderType = createRenderPass(defaultRenderRuntime);

    /**
     * Build a tile render pass bound to {@code renderRuntime} (the slot-or-config render runtime): the
     * shared {@link #particleRenderType} uses {@link #defaultRenderRuntime}; a per-emitter override builds
     * another with that emitter's overriding runtime (see {@code ParticleRuntime.renderer}). Equal
     * effective values produce equal passes, so the pipeline still merges them into one draw.
     */
    public RenderPass createRenderPass(ParticleRendererSetting.Runtime renderRuntime) {
        return new RenderPass(renderRuntime);
    }

    public enum Space {
        Local,
        World,
        Custom
    }

    public ParticleConfig() {
        renderer.getMaterials().add(new MaterialSetting());
        // route the shared renderer's structure-change teardown to the shared pass (a per-emitter
        // override copy routes to its own override pass instead — see ParticleRendererSetting.copy)
        renderer.setOwnerRenderPass(particleRenderType);
    }

    @ParametersAreNonnullByDefault
    public class RenderPass extends PhotonFXRenderPass {
        // TODO(M1/M2): 1.21 body = CPU renderQueue + GPU-instanced draw with materials (pre/post,
        // begin/end, instancing backend). Rebuilt as Photon render-state batches per the plan.
        public RenderPass(ParticleRendererSetting.Runtime renderRuntime) {
        }
    }

}
