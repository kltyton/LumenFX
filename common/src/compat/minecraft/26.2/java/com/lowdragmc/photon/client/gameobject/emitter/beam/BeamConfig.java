/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.beam;
import com.lowdragmc.photon.Photon;
import com.kltyton.lumenfx.internal.photon.core.syncdata.IPersistedSerializable;
import com.kltyton.lumenfx.internal.photon.core.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.PhotonFXRenderPass;
import com.lowdragmc.photon.client.gameobject.emitter.data.InstancedRendererSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.LightOverLifetimeSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.UVAnimationSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
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
import net.minecraft.world.level.ClipContext;
import org.joml.Vector3f;



/**
 * @author KilaBash
 * @date 2023/6/21
 * @implNote BeamConfig
 */
public class BeamConfig implements IPersistedSerializable {
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
    @Getter
    @Persisted
    protected Vector3f end = new Vector3f(0, 0, -3);
    @Setter
    @Getter
    @Persisted
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "duration", yAxis = "width"))
    protected NumberFunction width = NumberFunction.constant(0.2);
    @Setter
    @Getter
    @Persisted
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, curveConfig = @CurveConfig(bound = {0, 1}, xAxis = "duration", yAxis = "width"))
    protected NumberFunction emitRate = NumberFunction.constant(0);
    @Setter
    @Getter
    @Persisted
    protected RaycastMode raycast = RaycastMode.NONE;
    @Setter
    @Getter
    @Persisted
    protected ClipContext.Block raycastBlockMode = ClipContext.Block.VISUAL;
    @Setter
    @Getter
    @Persisted
    protected ClipContext.Fluid raycastFluidMode = ClipContext.Fluid.NONE;
    @Setter
    @Getter
    @Persisted
    @NumberFunctionConfig(types = {Color.class, RandomColor.class, Gradient.class, RandomGradient.class}, defaultValue = -1)
    protected NumberFunction color = new Color();
    @Getter
    @Persisted(subPersisted = true)
    public final InstancedRendererSetting renderer = new InstancedRendererSetting();
    @Persisted(subPersisted = true)
    public final UVAnimationSetting uvAnimation = new UVAnimationSetting();
    @Getter
    @Persisted(subPersisted = true)
    public final LightOverLifetimeSetting lights = new LightOverLifetimeSetting();
    @Persisted(subPersisted = true)
    public final BeamAdditionalGPUDataSetting additionalGPUDataSetting = new BeamAdditionalGPUDataSetting(this);

    public enum RaycastMode {
        NONE,
        BLOCKS,
        ENTITIES,
        BLOCKS_AND_ENTITIES;
    }

    // runtime
    public final InstancedRendererSetting.Runtime defaultRenderRuntime = renderer.createRuntime();
    public final PhotonFXRenderPass particleRenderType = createRenderPass(defaultRenderRuntime);

    /**
     * Build a beam render pass bound to {@code renderRuntime} (slot-or-config): the shared pass uses
     * {@link #defaultRenderRuntime}; a per-emitter override uses that emitter's runtime (see
     * {@code BeamRuntime.renderer}). Equal effective values produce equal passes → one draw.
     */
    public PhotonFXRenderPass createRenderPass(InstancedRendererSetting.Runtime renderRuntime) {
        return new RenderPass(renderRuntime);
    }

    public BeamConfig() {
        renderer.getMaterials().add(new MaterialSetting(new TextureMaterial(Photon.id("textures/particle/laser.png"))));
    }

    private class RenderPass extends PhotonFXRenderPass {
        // TODO(M1/M2): 1.21 body = CPU renderQueue + GPU-instanced draw with materials (pre/post,
        // begin/end, instancing backend). Rebuilt as Photon render-state batches per the plan.
        public RenderPass(InstancedRendererSetting.Runtime renderRuntime) {
        }
    }
}
