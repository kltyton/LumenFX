/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.mojang.blaze3d.PrimitiveTopology;
import com.lowdragmc.lowdraglib2.configurator.IConfigurable;
import com.lowdragmc.lowdraglib2.registry.ILDLRegisterClient;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.IPersistedSerializable;
import com.lowdragmc.lowdraglib2.utils.PersistedParser;
import com.lowdragmc.photon.PhotonRegistries;
import com.lowdragmc.photon.client.fx.PreloadContext;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.function.Supplier;

/**
 * @author KilaBash
 * @date 2023/5/29
 * @implNote Material
 */
@ParametersAreNonnullByDefault
public interface IMaterial extends IConfigurable, IPersistedSerializable, ILDLRegisterClient<IMaterial, Supplier<IMaterial>> {
    // region builtin material
    @LDLRegisterClient(name = "missing", registry = "photon:material")
    final class MissingMaterial implements IMaterial {
        /**
         * 1.21 bound the missing checkerboard in {@code begin()} — keep broken materials visibly broken.
         * <p>
         * ⚠️ PHOTON's own RenderType, never a vanilla one. 1.21 returned {@code rendertype_solid} here,
         * which draws correctly on the CPU path — the vertices arrive already transformed — and
         * catastrophically under GPU instancing, where the transform lives in per-instance attributes a
         * vanilla program does not declare, so every model drew untransformed at the origin and enormous.
         * Going through {@code MaterialRenderTypes} is what makes the instanced variant derivable:
         * {@code Emitter.bakeInstancedGroup} re-derives the pipeline from this type's
         * {@code InstancedRecipe}, which a foreign RenderType has none of (and the group then falls back
         * to the CPU path rather than drawing wrongly).
         */
        @Override
        public RenderType getRenderType(MaterialSetting setting, PrimitiveTopology mode) {
            return MaterialRenderTypes.hdrParticle(MissingTextureAtlasSprite.getLocation(),
                    setting.pipelineKey(mode));
        }
    }
    MissingMaterial MISSING = new MissingMaterial();
    // endregion

    Codec<IMaterial> CODEC = PhotonRegistries.MATERIALS.optionalCodec().dispatch(ILDLRegisterClient::getRegistryHolderOptional,
            optional -> optional.map(holder -> PersistedParser.createCodec(holder.value()).fieldOf("data"))
                    .orElseGet(() -> MapCodec.unit(MISSING)));

    @Nullable
    default CompoundTag serializeWrapper() {
        return (CompoundTag) CODEC.encodeStart(NbtOps.INSTANCE, this).result().orElse(null);
    }

    static IMaterial deserializeWrapper(Tag tag) {
        return CODEC.parse(NbtOps.INSTANCE, tag).result().orElse(MISSING);
    }

    // 26.1: the 1.21 render seam `ShaderInstance begin(MaterialContext)` / `end(MaterialContext)` is
    // gone with ShaderInstance itself — its job (blend/cull/depth state, #define variants, the material
    // uniform block) is now carried by the RenderType/pipeline this returns.

    /** The RenderType this material draws with under the given MaterialSetting state (blend/cull/depth
     *  select the pipeline variant), or null when it can't render this geometry — an unresolved
     *  resource, a failed shader compile, or a mode the material has no vertex stage for. */
    @Nullable
    default RenderType getRenderType(MaterialSetting setting, PrimitiveTopology mode) {
        return null;
    }

    /**
     * Report what this material loads on its first draw that its render type does not name ({@code FXHelper.preload}):
     * textures it binds itself, such as a shader graph's sampler defaults. The pipelines and the textures of the
     * render type's bindings are warmed from {@link #getRenderType} by the emitter. Render thread, outside any draw.
     * Nothing by default.
     */
    default void preload(PreloadContext context) {
    }

    /** {@code PhotonGpuChannels} bits of the additional-data channels this material reads; passes upload them. */
    default long getUsedChannelMask() {
        return 0L;
    }

    /** Whether this material reads user custom data; passes then upload the {@code PhotonCustomData} buffer. */
    default boolean usesCustomData() {
        return false;
    }

    /** Channels this material reads from each custom-data stream (index = stream, 0 = none), when it knows. */
    default int[] requiredCustomChannels() {
        return new int[0];
    }

    /** The render state this material asks for, or null to leave it to the {@code MaterialSetting}. */
    @Nullable
    default MaterialRenderState getPreferredRenderState() {
        return null;
    }

    default IMaterial copy() {
        return CODEC.encodeStart(NbtOps.INSTANCE, this).result()
                .flatMap(tag -> CODEC.parse(NbtOps.INSTANCE, tag).result())
                .orElse(MISSING);
    }
}
