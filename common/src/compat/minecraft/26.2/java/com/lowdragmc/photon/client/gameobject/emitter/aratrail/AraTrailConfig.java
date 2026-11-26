/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.aratrail;
import com.kltyton.lumenfx.internal.photon.core.editor.ui.sceneeditor.sceneobject.TransformRef;
import com.kltyton.lumenfx.internal.photon.core.syncdata.IPersistedSerializable;
import com.kltyton.lumenfx.internal.photon.core.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.data.InstancedRendererSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.ValueSpace;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Color;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.Gradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.RandomColor;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.color.RandomGradient;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.PhotonFXRenderPass;
import lombok.Getter;
import lombok.Setter;
import org.joml.Vector3f;



/**
 * @author KilaBash
 * @date 2023/6/11
 * @implNote TrailConfig
 */
public class AraTrailConfig implements IPersistedSerializable {

    public enum TrailAlignment {
        View,
        Velocity,
        Local
    }

    public enum TrailSpace
    {
        World,
        Local,
        Custom
    }

    public enum TrailSorting {
        OlderOnTop,
        NewerOnTop
    }

    public enum Timescale {
        Normal,
        Unscaled
    }

    public enum TextureMode {
        Stretch,
        Tile,
        WorldTile
    }
    @Setter
    @Getter
    @Persisted
    protected int duration = 100;
    @Setter
    @Getter
    @Persisted
    protected boolean looping = true;
    @Persisted(subPersisted = true)
    public final TrailSection section = new TrailSection();
    @Persisted
    public TrailSpace space = TrailSpace.World;
    @Persisted
    public final TransformRef customSpace = new TransformRef();
    @Persisted
    public TrailAlignment alignment = TrailAlignment.View;
    @Persisted
    public TrailSorting sorting = TrailSorting.OlderOnTop;
    @Persisted
    public float thickness = 0.2f;
    @Persisted
    public int smoothness = 1;
    @Persisted
    public float smoothingDistance = 0.05f;
    @Persisted
    public boolean highQualityCorners = false;
    @Persisted
    public int cornerRoundness = 5;

    @Persisted
    @NumberFunctionConfig(types = {Constant.class, Curve.class}, min = 0, defaultValue = 1f, curveConfig = @CurveConfig(bound = {0, 1f}, xAxis = "trail length", yAxis = "thickness"))
    public NumberFunction thicknessOverLength = NumberFunction.constant(1f);    /**< maps trail length to thickness.*/
    @Persisted
    @NumberFunctionConfig(types = {Color.class, RandomColor.class, Gradient.class, RandomGradient.class}, defaultValue = -1)
    public NumberFunction colorOverLength = NumberFunction.color(-1);

    @Persisted
    @NumberFunctionConfig(types = {Constant.class, Curve.class}, min = 0, defaultValue = 1f, curveConfig = @CurveConfig(bound = {0, 1f}, xAxis = "trail length", yAxis = "thickness"))
    public NumberFunction thicknessOverTime = NumberFunction.constant(1f);  /**< maps trail lifetime to thickness.*/
    @Persisted
    @NumberFunctionConfig(types = {Constant.class, Curve.class}, min = 0, defaultValue = 1f, curveConfig = @CurveConfig(bound = {0, 1f}, xAxis = "trail length", yAxis = "thickness"))
    public NumberFunction thicknessOverSegmentTime = NumberFunction.constant(1f);  /**< maps segment lifetime to thickness.*/
    @Persisted
    @NumberFunctionConfig(types = {Color.class, RandomColor.class, Gradient.class, RandomGradient.class}, defaultValue = -1)
    public NumberFunction colorOverTime = NumberFunction.color(-1);
    @Persisted
    @NumberFunctionConfig(types = {Color.class, RandomColor.class, Gradient.class, RandomGradient.class}, defaultValue = -1)
    public NumberFunction colorOverSegmentTime = NumberFunction.color(-1);


    @Persisted
    public boolean emit = true;
    @Persisted
    public float initialThickness = 1;
    @Persisted
    public int initialColor = -1;
    @Persisted
    public Vector3f initialVelocity = new Vector3f(0, 0, 0);
    @Persisted
    public float timeInterval = 0.05f;
    @Persisted
    public float minDistance = 0.025f;
    @Persisted
    public float time = 1f;

    @Persisted(subPersisted = true)
    public final AraPhysicsSetting physicsSetting = new AraPhysicsSetting();

    @Persisted
    public TextureMode textureMode = TextureMode.Stretch;
    @Persisted
    public float uvFactor = 1;
    @Persisted
    public float uvWidthFactor = 1;
    @Persisted
    public float tileAnchor = 1;
    @Getter
    @Persisted(subPersisted = true)
    public final InstancedRendererSetting renderer = new InstancedRendererSetting();
    @Persisted(subPersisted = true)
    public final AraTrailAdditionalGPUDataSetting additionalGPUDataSetting = new AraTrailAdditionalGPUDataSetting(this);

    // runtime
    public final InstancedRendererSetting.Runtime defaultRenderRuntime = renderer.createRuntime();
    public final PhotonFXRenderPass particleRenderType = createRenderPass(defaultRenderRuntime);

    /**
     * Build an ara-trail render pass bound to {@code renderRuntime} (slot-or-config): the shared pass uses
     * {@link #defaultRenderRuntime}; a per-emitter override uses that emitter's runtime (see
     * {@code AraTrailRuntime.renderer}). Equal effective values produce equal passes → one draw.
     */
    public PhotonFXRenderPass createRenderPass(InstancedRendererSetting.Runtime renderRuntime) {
        return new RenderPass(renderRuntime);
    }

    public AraTrailConfig() {
        renderer.getMaterials().add(new MaterialSetting());
    }

    // Pre-gravitySpace files applied gravity in trail space: null it before the field pass, derive it after if the key
    // was absent. Hooks, not a deserialize() override: PersistedParser.deserializeNBT (TrailsSetting) skips overrides.
    @Override
    public void beforeDeserialize() {
        physicsSetting.gravitySpace = null;
    }

    @Override
    public void afterDeserialize() {
        if (physicsSetting.gravitySpace == null) {
            physicsSetting.gravitySpace = space == TrailSpace.Local ? ValueSpace.Local
                    : space == TrailSpace.Custom ? ValueSpace.Custom : ValueSpace.World;
            if (space == TrailSpace.Custom) {
                physicsSetting.gravityCustomSpace.setTransformId(customSpace.getTransformId());
            }
        }
    }

    private class RenderPass extends PhotonFXRenderPass {
        // TODO(M1/M2): 1.21 body = CPU renderQueue + GPU-instanced draw with materials (pre/post,
        // begin/end, instancing backend). Rebuilt as Photon render-state batches per the plan.
        public RenderPass(InstancedRendererSetting.Runtime renderRuntime) {
        }
    }

}
