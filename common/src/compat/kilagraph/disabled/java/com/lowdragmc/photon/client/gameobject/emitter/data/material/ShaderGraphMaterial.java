/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.lowdragmc.lowdraglib2.editor.resource.BuiltinPath;
import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.mojang.blaze3d.PrimitiveTopology;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Objects;

/**
 * Fallback deserialization-preserving stub for KilaGraph shader-graph materials on loaders where KilaGraph
 * is not published upstream.
 */
@ParametersAreNonnullByDefault
@LDLRegisterClient(name = "shader_graph", registry = "photon:material")
public class ShaderGraphMaterial extends ShaderInstanceMaterial {
    @Persisted
    private IResourcePath graphPath = new BuiltinPath("");
    @Nullable
    private CompoundTag rawAdditional = null;

    public ShaderGraphMaterial() {
    }

    public ShaderGraphMaterial(IResourcePath graphPath) {
        this.graphPath = graphPath;
    }

    public IResourcePath getGraphPath() {
        if (graphPath == null) graphPath = new BuiltinPath("");
        return graphPath;
    }

    public void setGraphPath(@Nullable IResourcePath graphPath) {
        this.graphPath = graphPath == null ? new BuiltinPath("") : graphPath;
    }

    @Override
    @Nullable
    public RenderType getRenderType(MaterialSetting setting, PrimitiveTopology mode) {
        return null;
    }

    @Override
    public Tag serializeAdditionalNBT(HolderLookup.@NotNull Provider provider) {
        return rawAdditional != null ? rawAdditional.copy() : new CompoundTag();
    }

    @Override
    public void deserializeAdditionalNBT(Tag tag, HolderLookup.@NotNull Provider provider) {
        rawAdditional = tag instanceof CompoundTag compound ? compound.copy() : null;
    }

    @Override
    public IMaterial copy() {
        var copy = new ShaderGraphMaterial(getGraphPath());
        copy.rawAdditional = rawAdditional != null ? rawAdditional.copy() : null;
        return copy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ShaderGraphMaterial that)) return false;
        return getGraphPath().equals(that.getGraphPath()) && Objects.equals(rawAdditional, that.rawAdditional);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getGraphPath(), rawAdditional);
    }
}
