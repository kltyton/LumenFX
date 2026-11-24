/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.mojang.blaze3d.PrimitiveTopology;
import com.lowdragmc.lowdraglib2.configurator.annotation.ConfigNumber;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.syncdata.annotation.ReadOnlyManaged;
import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.emitter.Emitter;
import com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.PhotonFXRenderPass;
import com.lowdragmc.photon.client.render.FXCompositeMode;
import com.lowdragmc.photon.client.render.PhotonStage;
import com.lowdragmc.photon.client.render.PremultipliedBlendPlan;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.nbt.IntTag;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class RendererSetting {

    /**
     * Which frame slot this emitter's draws belong to — the 1.21 setting, restored. 1.21 routed the
     * emitter into the opaque or the translucent particle queue; 26.1 has no such queues, so the
     * layer picks the {@link PhotonStage} Photon opens its own pass at (right after the solid
     * feature pass, or after vanilla's translucent particles).
     * <p>
     * Independent of a material's blend function: {@code Opaque} means "drawn early, writes depth so
     * later translucent geometry sorts against it", not "unblended".
     */
    public enum Layer {
        Opaque(PhotonStage.AFTER_OPAQUE_FEATURES),
        Translucent(PhotonStage.AFTER_TRANSLUCENT_PARTICLES);

        public final PhotonStage stage;

        Layer(PhotonStage stage) {
            this.stage = stage;
        }
    }

    public enum SortMode {
        NONE,
        DISTANCE;

        /**
         * The point to sort distances from, or null when this mode does not sort. 1.21 read
         * {@code RenderSystem.getVertexSorting()} — the engine's current global, which 26.1 dropped when
         * sorting moved onto {@code RenderSetup.sortOnUpload}; Photon bypasses that path (its drain opens
         * its own passes), so the extraction hands in the eye position instead. The sort itself is
         * {@link com.lowdragmc.photon.client.render.PhotonDistanceSort}.
         *
         * @param eye the viewer, in the space the geometry was baked in (Photon bakes camera-relative, so
         *            that is zero in-world and the scene eye in the editor, whose camera sits at origin)
         */
        @Nullable
        public Vector3fc sortOrigin(Vector3fc eye) {
            return this == DISTANCE ? eye : null;
        }
    }

    @Configurable(name = "RendererSetting.materials", collapse = false)
    @ReadOnlyManaged(serializeMethod = "materialSerialize", deserializeMethod = "materialDeserialize")
    @EqualsAndHashCode.Include
    protected List<MaterialSetting> materials = new ArrayList<>();

    @Configurable(name = "RendererSetting.layer", tips = "photon.emitter.config.renderer.layer")
    @EqualsAndHashCode.Include
    protected Layer layer = Layer.Translucent;

    @Configurable(name = "RendererSetting.cull", subConfigurable = true, tips = "photon.emitter.config.renderer.cull")
    protected final Cull cull = new Cull();

    @Configurable(name = "photon.emitter.config.renderer.orderInLayer", tips = "photon.emitter.config.renderer.orderInLayer.tips")
    @EqualsAndHashCode.Include
    protected int orderInLayer = 0;

    @Configurable(name = "photon.emitter.config.renderer.vertexSortingMode", tips = "photon.emitter.config.renderer.vertexSortingMode.tips")
    @EqualsAndHashCode.Include
    protected SortMode vertexSortingMode = SortMode.NONE;

    /** When this emitter's FX are merged into the frame — see {@link FXCompositeMode}. INHERIT (the
     *  default) follows {@code PhotonConfig.fxCompositeMode}. Part of the batching key: passes with
     *  different modes accumulate into different targets and must never merge into one draw. */
    @Configurable(name = "photon.emitter.config.renderer.compositeMode", tips = "photon.emitter.config.renderer.compositeMode.tips")
    @EqualsAndHashCode.Include
    protected FXCompositeMode compositeMode = FXCompositeMode.INHERIT;

    /**
     * The render pass whose instanced GL to tear down when this AUTHORED renderer's structure changes via
     * a setter (transient; NOT persisted / NOT in equals/hashCode). The config renderer points this at its
     * {@code config.particleRenderType} so editor-inspector edits rebuild the shared pass's GL. Per-emitter
     * overrides go through {@link Runtime} slots (not these setters), so they never touch this. Only the
     * tile {@code ParticleRendererSetting} has setters that call {@link #clearRenderPassInstance()}; the
     * instanced settings never tear down GL from a setter.
     */
    @Nullable
    protected transient PhotonFXRenderPass ownerRenderPass;

    public static class Cull extends ToggleGroup {
        @Setter
        @Getter
        @Configurable
        @ConfigNumber(range = {-Float.MAX_VALUE, Float.MAX_VALUE})
        protected AABB cullBox = new AABB(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5);

        public AABB getCullAABB(Emitter particle, float partialTicks) {
            var transform = particle.transform();
            var scale = transform.scale();
            var pos = particle.toWorldSpace(transform.position());
            return new AABB(cullBox.minX * scale.x, cullBox.minY * scale.y, cullBox.minZ * scale.z,
                    cullBox.maxX * scale.x, cullBox.maxY * scale.y, cullBox.maxZ * scale.z)
                    .move(pos.x, pos.y, pos.z);
        }
    }

    private IntTag materialSerialize(List<MaterialSetting> value) {
        return IntTag.valueOf(value.size());
    }

    private List<MaterialSetting> materialDeserialize(IntTag size) {
        var materials = new ArrayList<MaterialSetting>();
        for (int i = 0; i < size.intValue(); i++) {
            materials.add(new MaterialSetting());
        }
        return materials;
    }

    public void setOwnerRenderPass(@Nullable PhotonFXRenderPass ownerRenderPass) {
        this.ownerRenderPass = ownerRenderPass;
    }

    /** Tear down the owning pass's instanced GL after a structure change (no-op until the pass is wired). */
    protected void clearRenderPassInstance() {
        if (ownerRenderPass != null) {
            ownerRenderPass.clearInstance();
        }
    }

    /**
     * Per-emitter render-override layer, co-located here exactly like the other {@code Setting.Runtime}s:
     * the config stays immutable pure data, this holds a named {@link RuntimeValue} slot per renderer
     * field, and each {@code getX()} reads "slot if overridden, else config". Overriding one field (e.g.
     * {@code renderMode}) writes ONE slot — there is NO whole-renderer copy. The owning {@code
     * ParticleRuntime} turns "any slot overridden" into a per-emitter override {@link PhotonFXRenderPass};
     * {@link #effectiveEquals}/{@link #effectiveHashCode} over the effective values are that pass's
     * batching key, so identical overrides still merge into a single draw. Subclasses add their own slots
     * and extend {@code getX}/{@link #hasOverride}/{@link #clear}/{@link #effectiveEquals}.
     */
    public static class Runtime {
        protected final RendererSetting config;
        public final RuntimeValue<List<MaterialSetting>> materials;
        public final RuntimeValue<Layer> layer;
        public final RuntimeValue<Cull> cull;
        public final RuntimeValue<Integer> orderInLayer;
        public final RuntimeValue<SortMode> vertexSortingMode;
        public final RuntimeValue<FXCompositeMode> compositeMode;

        protected Runtime(RendererSetting config) {
            this.config = config;
            this.materials = new RuntimeValue<>(config::getMaterials);
            this.layer = new RuntimeValue<>(config::getLayer);
            this.cull = new RuntimeValue<>(config::getCull);
            this.orderInLayer = new RuntimeValue<>(config::getOrderInLayer);
            this.vertexSortingMode = new RuntimeValue<>(config::getVertexSortingMode);
            this.compositeMode = new RuntimeValue<>(config::getCompositeMode);
        }

        public List<MaterialSetting> getMaterials() { return materials.get(); }
        public Layer getLayer() { return layer.get(); }
        public Cull getCull() { return cull.get(); }
        public int getOrderInLayer() { return orderInLayer.get(); }
        public SortMode getVertexSortingMode() { return vertexSortingMode.get(); }
        public FXCompositeMode getCompositeMode() { return compositeMode.get(); }

        /**
         * The frame slot this emitter's geometry is baked into: normally its {@link Layer}'s, but
         * {@link PhotonStage#DEFERRED} when the effect asked to be composited late and every one of
         * its materials survives the translation into a premultiplied layer.
         *
         * <p>The layer-safety test is what keeps LATE honest on the plain path: a blend that reads the
         * destination colour (multiply, min/max) has no equivalent against a transparent backdrop, so
         * rather than silently changing how it looks, that emitter keeps drawing in place and keeps the
         * vanilla cloud/water artefacts. A shader pack has no such choice — there is no in-place path
         * under Iris — which is why {@code PremultipliedBlendPlan} still approximates there.
         *
         * <p>Opaque effects are never deferred: they write depth and are meant to be occluded by the
         * world, which is exactly what compositing after everything would break.
         */
        public PhotonStage effectiveStage() {
            var layer = getLayer();
            if (layer != Layer.Translucent
                    || getCompositeMode().resolve() != FXCompositeMode.LATE) {
                return layer.stage;
            }
            for (var material : getMaterials()) {
                if (!PremultipliedBlendPlan
                        .isLayerSafe(material.authoredPipelineKey(PrimitiveTopology.QUADS).blend())) {
                    return layer.stage;
                }
            }
            return PhotonStage.DEFERRED;
        }

        /**
         * Whether a PASS-LEVEL slot is overridden — i.e. this emitter needs its own render pass instead
         * of sharing the config's. Only fields the {@code RenderPass}/its renderer read at pass level (the
         * batching-key set = {@link #effectiveEquals}) count. {@code cull} is deliberately excluded: it is
         * per-emitter culling read by {@code getCullBox}, not a pass concern, so overriding it needs no pass.
         */
        public boolean hasOverride() {
            return materials.isOverridden() || layer.isOverridden()
                    || orderInLayer.isOverridden() || vertexSortingMode.isOverridden()
                    || compositeMode.isOverridden();
        }

        /** Clear every override slot (fall back to the authored config). */
        public void clear() {
            materials.clear();
            layer.clear();
            cull.clear();
            orderInLayer.clear();
            vertexSortingMode.clear();
            compositeMode.clear();
        }

        /**
         * Effective-value equality for the batching key. It MUST mirror {@link RendererSetting}'s
         * {@code @EqualsAndHashCode.Include} set (keep in sync when a keyed field is added) — {@code cull}
         * is excluded, matching the config. Only ever compares runtimes of the same concrete type.
         */
        public boolean effectiveEquals(Runtime o) {
            return Objects.equals(getMaterials(), o.getMaterials())
                    && getLayer() == o.getLayer()
                    && getOrderInLayer() == o.getOrderInLayer()
                    && getVertexSortingMode() == o.getVertexSortingMode()
                    && getCompositeMode() == o.getCompositeMode();
        }

        public int effectiveHashCode() {
            return Objects.hash(getMaterials(), getLayer(), getOrderInLayer(), getVertexSortingMode(),
                    getCompositeMode());
        }
    }

}
