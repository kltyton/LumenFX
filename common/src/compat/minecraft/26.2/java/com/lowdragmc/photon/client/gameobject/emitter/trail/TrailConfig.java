/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.trail;
import com.kltyton.lumenfx.internal.photon.core.syncdata.annotation.Persisted;
import com.kltyton.lumenfx.internal.photon.core.syncdata.IPersistedSerializable;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.PhotonFXRenderPass;
import com.lowdragmc.photon.client.gameobject.emitter.data.InstancedRendererSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.LightOverLifetimeSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.UVAnimationSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Color;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.RandomColor;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.RandomGradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.particle.TrailParticle;
import com.mojang.blaze3d.vertex.*;
import lombok.Getter;
import lombok.Setter;



/**
 * @author KilaBash
 * @date 2023/6/11
 * @implNote TrailConfig
 */
public class TrailConfig implements IPersistedSerializable {
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
    protected int startDelay = 0;
    @Setter
    @Getter
    @Persisted
    protected int time = 20;
    @Setter
    @Getter
    @Persisted
    protected float minVertexDistance = 0.05f;
    @Getter
    @Persisted
    protected boolean smoothInterpolation = false;
    @Getter
//    @Persisted
    protected boolean calculateSmoothByShader = false;
    @Setter
    @Getter
    @Persisted
    protected TrailParticle.UVMode uvMode = TrailParticle.UVMode.Stretch;
    @Setter
    @Getter
    @Persisted
    @NumberFunctionConfig(types = {Constant.class, Curve.class}, min = 0, defaultValue = 0.1f, curveConfig = @CurveConfig(bound = {0, 0.1f}, xAxis = "trail position", yAxis = "width"))
    protected NumberFunction widthOverTrail = NumberFunction.constant(0.2f);
    @Setter
    @Getter
    @Persisted
    @NumberFunctionConfig(types = {Color.class, RandomColor.class, Gradient.class, RandomGradient.class}, defaultValue = -1)
    protected NumberFunction colorOverTrail = new Gradient();
    @Getter
    @Persisted(subPersisted = true)
    public final InstancedRendererSetting renderer = new InstancedRendererSetting();
    @Getter
    @Persisted(subPersisted = true)
    public final LightOverLifetimeSetting lights = new LightOverLifetimeSetting();
    @Getter
    @Persisted(subPersisted = true)
    public final UVAnimationSetting uvAnimation = new UVAnimationSetting();
    @Persisted(subPersisted = true)
    public final TrailAdditionalGPUDataSetting additionalGPUDataSetting = new TrailAdditionalGPUDataSetting(this);

    // runtime
    public final InstancedRendererSetting.Runtime defaultRenderRuntime = renderer.createRuntime();
    public final PhotonFXRenderPass particleRenderType = createRenderPass(defaultRenderRuntime);

    /**
     * Build a trail render pass bound to {@code renderRuntime} (slot-or-config): the shared pass uses
     * {@link #defaultRenderRuntime}; a per-emitter override uses that emitter's runtime (see
     * {@code TrailRuntime.renderer}). Equal effective values produce equal passes → one draw.
     */
    public PhotonFXRenderPass createRenderPass(InstancedRendererSetting.Runtime renderRuntime) {
        return new RenderPass(renderRuntime);
    }

    public TrailConfig() {
        renderer.getMaterials().add(new MaterialSetting());
    }

    private class RenderPass extends PhotonFXRenderPass {
        // TODO(M1/M2): 1.21 body = CPU renderQueue + GPU-instanced draw with materials (pre/post,
        // begin/end, instancing backend). Rebuilt as Photon render-state batches per the plan.
        public RenderPass(InstancedRendererSetting.Runtime renderRuntime) {
        }
    }
}
