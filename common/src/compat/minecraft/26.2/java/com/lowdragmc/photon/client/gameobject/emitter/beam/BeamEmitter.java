/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.beam;

import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.fx.PreloadContext;
import com.lowdragmc.photon.client.fx.timeline.AnimatedPropertyType;
import com.lowdragmc.photon.client.fx.timeline.property.ConfigValueType;
import com.lowdragmc.photon.client.gameobject.FXObject;
import com.lowdragmc.photon.client.gameobject.FXObjectType;
import com.lowdragmc.photon.client.gameobject.IFXObject;
import com.lowdragmc.photon.client.gameobject.RuntimeBinding;
import com.lowdragmc.photon.client.gameobject.emitter.Emitter;
import com.lowdragmc.photon.client.gameobject.emitter.data.CustomDataBindings;
import com.lowdragmc.photon.client.gameobject.emitter.data.RendererSetting;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.PhotonFXRenderPass;
import com.lowdragmc.photon.client.gameobject.particle.BeamParticle;
import com.lowdragmc.photon.client.gameobject.particle.renderer.BeamParticleRenderer;
import com.lowdragmc.photon.client.render.PhotonCameraUtils;
import com.lowdragmc.photon.client.render.PhotonPipelines;
import com.lowdragmc.photon.client.render.PhotonWorldRenderState;
import com.mojang.blaze3d.vertex.VertexConsumer;
import lombok.Getter;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.*;
import java.util.List;

/**
 * @author KilaBash
 * @date 2023/6/21
 * @implNote BeamEmitter
 */
@ParametersAreNonnullByDefault
public class BeamEmitter extends Emitter {
    @LDLRegisterClient(name = "beam_emitter", registry = "photon:fx_object")
    public static final FXObjectType TYPE = new FXObjectType() {
        @Override
        public IFXObject create() {
            return new BeamEmitter();
        }


        @Override
        public int version() {
            return 2;
        }

        @Override
        public List<RuntimeBinding> runtimeBindings() {
            return RUNTIME_BINDINGS;
        }

        // custom-data channels are per-CONFIG dynamic (variable stream/channel count), so they can't live
        // in the static RUNTIME_BINDINGS / cached type-level list — enumerate them per target instance.
        @Override
        public List<AnimatedPropertyType> animatableProperties(FXObject target) {
            if (!(target instanceof BeamEmitter emitter)) {
                return animatableProperties();
            }
            return CustomDataBindings.appendAnimatable(animatableProperties(),
                    emitter.config.additionalGPUDataSetting.customDataStreams(),
                    o -> ((BeamEmitter) o).runtime().customData);
        }

        @Override
        public RuntimeBinding resolveRuntimeBinding(FXObject target, String path) {
            var b = super.resolveRuntimeBinding(target, path);
            return b != null ? b : CustomDataBindings.resolve(path, o -> ((BeamEmitter) o).runtime().customData);
        }
    };

    /** Timeline-animatable config values backed by named {@link BeamRuntime} slots (no map/reflection). */
    public static final List<RuntimeBinding> RUNTIME_BINDINGS = List.of(
            new RuntimeBinding("duration", "ParticleConfig.duration", ConfigValueType.INT,
                    o -> ((BeamEmitter) o).runtime().duration),
            new RuntimeBinding("looping", "ParticleConfig.looping", ConfigValueType.BOOL,
                    o -> ((BeamEmitter) o).runtime().looping),
            new RuntimeBinding("startDelay", "ParticleConfig.startDelay", ConfigValueType.INT,
                    o -> ((BeamEmitter) o).runtime().startDelay),
            new RuntimeBinding("width", "BeamConfig.width", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((BeamEmitter) o).runtime().width),
            new RuntimeBinding("emitRate", "BeamConfig.emitRate", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((BeamEmitter) o).runtime().emitRate),
            new RuntimeBinding("color", "BeamConfig.color", ConfigValueType.COLOR,
                    o -> ((BeamEmitter) o).runtime().color),
            new RuntimeBinding("lights.enable", "enable", ConfigValueType.BOOL,
                    o -> ((BeamEmitter) o).runtime().lights.enable),
            new RuntimeBinding("lights.skyLight", "LightOverLifetimeSetting.skyLight", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((BeamEmitter) o).runtime().lights.skyLight),
            new RuntimeBinding("lights.blockLight", "LightOverLifetimeSetting.blockLight", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((BeamEmitter) o).runtime().lights.blockLight),
            new RuntimeBinding("uvAnimation.enable", "enable", ConfigValueType.BOOL,
                    o -> ((BeamEmitter) o).runtime().uvAnimation.enable),
            new RuntimeBinding("uvAnimation.frameOverTime", "UVAnimationSetting.frameOverTime", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((BeamEmitter) o).runtime().uvAnimation.frameOverTime),
            new RuntimeBinding("uvAnimation.startFrame", "UVAnimationSetting.startFrame", ConfigValueType.NUMBER_FUNCTION,
                    o -> ((BeamEmitter) o).runtime().uvAnimation.startFrame),
            new RuntimeBinding("uvAnimation.cycle", "UVAnimationSetting.cycle", ConfigValueType.FLOAT,
                    o -> ((BeamEmitter) o).runtime().uvAnimation.cycle));

    @Getter
    @Persisted(subPersisted = true)
    protected final BeamConfig config;

    /** Per-instance runtime layer: named override slots (timeline-driven) over the immutable config. */
    private BeamRuntime runtime;

    // runtime
    protected BeamParticle beamParticle;
    private @Nullable Vec3 worldEndpoint;

    public void setWorldEndpoint(@Nullable Vec3 end) {
        worldEndpoint = end;
    }

    public void setWorldEndpoint(@Nullable Vector3f end) {
        worldEndpoint = end == null ? null : new Vec3(end.x, end.y, end.z);
    }

    public @Nullable Vec3 worldEndpoint() {
        return worldEndpoint;
    }

    public Vector3f resolveWorldEndpoint(Vector3f origin) {
        return worldEndpoint == null
                ? transform().localToWorldMatrix().transformDirection(config.getEnd(), new Vector3f()).add(origin)
                : toLocalSpace(worldEndpoint);
    }

    public BeamEmitter() {
        this(new BeamConfig());
    }

    public BeamEmitter(BeamConfig config) {
        this.config = config;
    }

    /** This emitter's per-instance runtime layer (lazily created; timeline overrides live here). */
    public BeamRuntime runtime() {
        if (runtime == null) {
            runtime = new BeamRuntime(config);
        }
        return runtime;
    }

    @Override
    public FXObjectType getFXObjectType() {
        return TYPE;
    }

    @Override
    public BeamEmitter shallowCopy() {
        return new BeamEmitter(config);
    }

    /** The group {@link #bakeBatches} draws (see {@link Emitter#preloadGroup}), from the config's renderer. */
    @Override
    public void preload(PreloadContext context) {
        var renderer = config.defaultRenderRuntime;
        var instanced = renderer.isUseGPUInstance();
        preloadGroup(context, renderer, config.additionalGPUDataSetting, geometryMode(), instanced ? PhotonPipelines.InstancedVariant.BEAM : null,
                !instanced, false);
    }

    //////////////////////////////////////
    //*****     particle logic     *****//
    //////////////////////////////////////

    @Override
    public int getParticleAmount() {
        return beamParticle.isAlive() ? 1 : 0;
    }

    @Override
    public int getLifetime() {
        return durationWithInheritance(runtime().duration.get());
    }

    @Override
    public int getStartDelay() {
        return runtime().startDelay.get();
    }

    @Override
    protected void updateOrigin() {
        super.updateOrigin();
        setLifetime(getLifetime());
    }

    @Override
    public boolean isLooping() {
        return runtime().looping.get();
    }

    //////////////////////////////////////
    //*****     particle logic     *****//
    //////////////////////////////////////

    @Override
    protected void onTickBegin() {
        super.onTickBegin();
        if (beamParticle != null) beamParticle.syncOrigin();
    }

    @Override
    protected void update(float dt) {
        if (beamParticle.isAlive()) {
            beamParticle.updateTick(dt);
            if(beamParticle.getDelay() > 0) { age = 0; ageF = 0; }
        } else {
            remove();
        }

        super.update(dt);
    }

    @Override
    public float getT() {
        if(beamParticle != null && beamParticle.getDelay() > 0) return 0;
        return super.getT();
    }

    @Override
    public float getT(float partialTicks) {
        if(beamParticle != null && beamParticle.getDelay() > 0) return 0;
        return super.getT(partialTicks);
    }

    @Override
    public void reset() {
        super.reset();
        if (runtime != null) {
            runtime.clear(); // drop timeline overrides; fall back to authored config
        }
        beamParticle = new BeamParticle(this, config);
    }

    /** The render pass this emitter draws through (per-instance override pass, or the shared singleton). */
    public PhotonFXRenderPass effectiveRenderPass() {
        return runtime().effectiveRenderPass();
    }

    /** Lazily built CPU beam renderer. Transient: render-only state, never persisted or copied. */
    @Nullable
    private transient BeamParticleRenderer extractRenderer;

    @Override
    public RendererSetting.Runtime rendererRuntime() {
        return runtime().renderer;
    }

    @Override
    protected void bakeBatches(List<PhotonWorldRenderState.DrawJob> out,
                               Camera camera, float partialTicks) {
        var setting = config.additionalGPUDataSetting;
        if (runtime().renderer.isUseGPUInstance()) {
            if (extractRenderer == null) {
                extractRenderer = new BeamParticleRenderer();
            }
            var origin = worldOrigin();
            if (bakeInstancedGroup(out, camera, rendererRuntime(), setting,
                    PhotonPipelines.InstancedVariant.BEAM,
                    BaseMesh.quads(PhotonWorldRenderState.beamQuad(), 6),
                    16, 0,
                    new Vector3f(PhotonCameraUtils.facingEye(camera, origin)).sub(PhotonCameraUtils.renderOrigin(camera, origin)),
                    (instances, points, data, custom) -> extractRenderer.fillInstances(
                            Collections.singleton(beamParticle), camera, partialTicks, instances,
                            setting, data, custom))) {
                return;
            }
        }
        super.bakeBatches(out, camera, partialTicks);
    }

    @Override
    protected void bakeGeometry(VertexConsumer geometry,
                                Camera camera, float partialTicks) {
        if (extractRenderer == null) {
            extractRenderer = new BeamParticleRenderer();
        }
        extractRenderer.renderQueue(geometry, Collections.singleton(beamParticle), camera, partialTicks);
    }

    //////////////////////////////////////
    //********      Emitter    *********//
    //////////////////////////////////////

    @Override
    @Nullable
    public AABB getCullBox(float partialTicks) {
        var cull = runtime().renderer.getCull();
        if (!cull.isEnable()) return null;
        var bounds = cull.getCullAABB(this, partialTicks);
        if (worldEndpoint == null || bounds == null) return bounds;
        var start = toWorldSpace(beamParticle.getWorldPos());
        float width = Math.abs(beamParticle.getRealWidth(partialTicks));
        var beamBounds = new AABB(
                Math.min(start.x, worldEndpoint.x), Math.min(start.y, worldEndpoint.y), Math.min(start.z, worldEndpoint.z),
                Math.max(start.x, worldEndpoint.x), Math.max(start.y, worldEndpoint.y), Math.max(start.z, worldEndpoint.z));
        return bounds.minmax(beamBounds.inflate(width));
    }

    @Override
    public void remove(boolean force) {
        super.remove(force);
        beamParticle.setRemoved(true);
        if (force && runtime != null) {
            runtime.clearRenderOverride(); // free the per-instance override pass's GL on force removal
        }
    }
}
