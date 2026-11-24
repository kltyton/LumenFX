/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data;

import com.mojang.blaze3d.PrimitiveTopology;
import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.configurator.annotation.Configurable;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.BlendMode;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.MaterialRenderState;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.CustomShaderMaterial;
import com.lowdragmc.photon.client.render.PhotonPipelines;
import com.lowdragmc.photon.client.render.PremultipliedBlendPlan;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.jetbrains.annotations.Nullable;

import javax.annotation.Nonnull;

@Getter
@Setter
@Accessors(chain = true)
@EqualsAndHashCode
public class MaterialSetting implements IConfigurable, IPersistedSerializable {
    /** Where the blend / cull / depth state of this slot comes from. */
    public enum RenderStateSource {
        /** The material's own state when it has one, otherwise this slot's. */
        MATERIAL,
        /** Always this slot's. */
        CUSTOM
    }

    @Nonnull
    @Configurable(name = "material")
    protected IMaterial material;
    @Configurable(name = "MaterialSetting.renderStateSource", tips = "MaterialSetting.renderStateSource.tips")
    protected RenderStateSource renderStateSource = RenderStateSource.MATERIAL;
    @Configurable(name = "MaterialSetting.blendMode", subConfigurable = true)
    protected final BlendMode blendMode = new BlendMode();
    @Configurable
    protected boolean cull = true;
    @Configurable
    protected boolean depthTest = true;
    @Configurable
    protected boolean depthMask = false;

    public MaterialSetting() {
        this(new CustomShaderMaterial(Photon.id("circle")));
    }

    public MaterialSetting(@Nonnull IMaterial material) {
        this.material = material;
    }

    /** The material's render state when this slot follows it, else null. */
    @Nullable
    public MaterialRenderState getFollowedRenderState() {
        return renderStateSource == RenderStateSource.MATERIAL ? material.getPreferredRenderState() : null;
    }

    public BlendMode getEffectiveBlendMode() {
        var followed = getFollowedRenderState();
        return followed != null ? followed.blendMode() : blendMode;
    }

    public boolean isEffectiveCull() {
        var followed = getFollowedRenderState();
        return followed != null ? followed.cull() : cull;
    }

    public boolean isEffectiveDepthTest() {
        var followed = getFollowedRenderState();
        return followed != null ? followed.depthTest() : depthTest;
    }

    public boolean isEffectiveDepthMask() {
        var followed = getFollowedRenderState();
        return followed != null ? followed.depthMask() : depthMask;
    }

    /**
     * Whether the slot draws its geometry twice, back faces then front ones, so a closed transparent mesh sorts
     * itself. A pipeline only knows whether to cull, not which face, so the drain draws such a job twice, the
     * first time through indices wound the other way — see {@code PhotonReversedIndices}. Culling is forced on.
     */
    public boolean isBackFacesFirst() {
        var followed = getFollowedRenderState();
        return followed != null && followed.backFacesFirst();
    }

    // blend/cull/depth are pipeline state, so this setting contributes the pipeline key — built from the
    // EFFECTIVE state, so a material that brings its own render state is followed on every draw path
    public PhotonPipelines.ParticlePipelineKey pipelineKey(PrimitiveTopology mode) {
        var key = authoredPipelineKey(mode);
        // Drawing into a transparent layer rather than onto the scene changes what the alpha half of the
        // blend must do — see PremultipliedBlendPlan. Consulted here, at the one place every material's
        // pipeline is chosen, so no draw path can bypass it.
        return PremultipliedBlendPlan.accumulating()
                ? key.withBlend(PremultipliedBlendPlan.premultiply(key.blend())) : key;
    }

    /**
     * The key as authored, whatever the bake is accumulating into: what deciding whether to accumulate at all
     * must look at ({@code RendererSetting.Runtime.effectiveStage}), or the group baked before would decide it.
     */
    public PhotonPipelines.ParticlePipelineKey authoredPipelineKey(PrimitiveTopology mode) {
        // back faces first culls each pass; a strip has no reversed winding to show its back faces with
        boolean backPass = isBackFacesFirst() && (mode == PrimitiveTopology.QUADS || mode == PrimitiveTopology.TRIANGLES);
        return new PhotonPipelines.ParticlePipelineKey(getEffectiveBlendMode().toBlendFunction(),
                isEffectiveCull() || backPass, isEffectiveDepthTest(), isEffectiveDepthMask(), mode);
    }

    /** The RenderType this material slot draws with (material + this setting's pipeline state +
     *  the emitter's primitive mode). */
    @Nullable
    public RenderType getRenderType(
            PrimitiveTopology mode) {
        return material.getRenderType(this, mode);
    }

    /** Copies {@code blend} into this slot's own blend, so the slot retains the same persisted state object. */
    public void setBlend(BlendMode blend) {
        blendMode.setEnableBlend(blend.isEnableBlend());
        blendMode.setSrcColorFactor(blend.getSrcColorFactor());
        blendMode.setDstColorFactor(blend.getDstColorFactor());
        blendMode.setSrcAlphaFactor(blend.getSrcAlphaFactor());
        blendMode.setDstAlphaFactor(blend.getDstAlphaFactor());
        blendMode.setBlendFunc(blend.getBlendFunc());
    }
}
