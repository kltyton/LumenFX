package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.kltyton.lumenfx.internal.photon.core.editor.resource.BuiltinPath;
import com.kltyton.lumenfx.internal.photon.core.editor.resource.IResourcePath;
import com.kltyton.lumenfx.internal.photon.core.registry.annotation.LDLRegisterClient;
import com.kltyton.lumenfx.internal.photon.core.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.fx.PreloadContext;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.resource.PhotonAssetLibrary;
import com.mojang.blaze3d.PrimitiveTopology;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Objects;

/** A material stored separately from the effect that references it. */
@LDLRegisterClient(name = "ui_resource_material", registry = "photon:material")
public final class ResourceMaterial implements IMaterial {
    private static final IResourcePath DEFAULT_PATH = new BuiltinPath("missing");

    @Persisted
    private IResourcePath resourcePath = DEFAULT_PATH;

    public ResourceMaterial() {}

    private ResourceMaterial(IResourcePath resourcePath) {
        this.resourcePath = resourcePath;
    }

    private IResourcePath path() {
        return resourcePath == null ? DEFAULT_PATH : resourcePath;
    }

    private IMaterial resolve() {
        IResourcePath path = path();
        IMaterial material = PhotonAssetLibrary.material(path);
        if (!(material instanceof ResourceMaterial)) return material;
        var visited = new HashSet<IResourcePath>();
        visited.add(path);
        while (material instanceof ResourceMaterial reference) {
            path = reference.path();
            if (!visited.add(path)) throw new IllegalArgumentException("Cyclic Photon material reference: " + path);
            material = PhotonAssetLibrary.material(path);
        }
        return material;
    }

    @Override
    public @Nullable RenderType getRenderType(MaterialSetting setting, PrimitiveTopology topology) {
        return resolve().getRenderType(setting, topology);
    }

    @Override
    public void preload(PreloadContext context) {
        resolve().preload(context);
    }

    @Override
    public long getUsedChannelMask() {
        return resolve().getUsedChannelMask();
    }

    @Override
    public boolean usesCustomData() {
        return resolve().usesCustomData();
    }

    @Override
    public int[] requiredCustomChannels() {
        return resolve().requiredCustomChannels();
    }

    @Override
    public @Nullable MaterialRenderState getPreferredRenderState() {
        return resolve().getPreferredRenderState();
    }

    @Override
    public ResourceMaterial copy() {
        return new ResourceMaterial(path());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ResourceMaterial material && Objects.equals(path(), material.path());
    }

    @Override
    public int hashCode() {
        return path().hashCode();
    }
}
