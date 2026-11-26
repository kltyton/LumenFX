package com.lowdragmc.photon.client.shadergraph;

import com.kltyton.lumenfx.internal.photon.core.editor.resource.IResourcePath;
import com.lowdragmc.photon.client.fx.PreloadContext;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.mojang.blaze3d.PrimitiveTopology;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

/**
 * Optional runtime bridge implemented only when external KilaGraph + LDLib2 are installed.
 * Keeps {@code ShaderGraphMaterial} free of any hard dependency on KilaGraph or LDLib2.
 */
public interface ShaderGraphBridge {
    void onGraphPathChanged(IResourcePath graphPath);

    boolean isCompiledError();

    String getCompiledErrorMessage();

    long getUsedChannelMask();

    boolean usesCustomData();

    @Nullable
    RenderType getRenderType(MaterialSetting setting, PrimitiveTopology mode);

    void preload(PreloadContext context);

    Tag serializeOverrides(HolderLookup.Provider provider);

    void deserializeOverrides(CompoundTag overridesTag, HolderLookup.Provider provider);

    void invalidateCache();
}
