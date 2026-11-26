/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data.material;

import com.kltyton.lumenfx.internal.photon.core.Platform;
import com.kltyton.lumenfx.internal.photon.core.editor.resource.BuiltinPath;
import com.kltyton.lumenfx.internal.photon.core.editor.resource.IResourcePath;
import com.kltyton.lumenfx.internal.photon.core.registry.annotation.LDLRegisterClient;
import com.kltyton.lumenfx.internal.photon.core.syncdata.annotation.Persisted;
import com.kltyton.lumenfx.platform.LumenPlatform;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.fx.PreloadContext;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.shadergraph.ShaderGraphBridge;
import com.mojang.blaze3d.PrimitiveTopology;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.annotation.ParametersAreNonnullByDefault;
import java.lang.reflect.Constructor;
import java.util.Objects;

/**
 * Material driven by a Photon shader graph resource. Runs with zero mandatory dependency on KilaGraph or LDLib2;
 * when external KilaGraph and LDLib2 are both installed at runtime, lazily delegates shader-graph compilation
 * through {@link ShaderGraphBridge}.
 */
@ParametersAreNonnullByDefault
@LDLRegisterClient(name = "shader_graph", registry = "photon:material")
public class ShaderGraphMaterial extends ShaderInstanceMaterial {
    private static final String OPTIONAL_BRIDGE_CLASS =
            "com.lowdragmc.photon.client.shadergraph.KilaGraphBridgeImpl";
    private static volatile boolean bridgeChecked;
    @Nullable
    private static volatile Constructor<?> bridgeConstructor;

    @Persisted
    private IResourcePath graphPath = new BuiltinPath("");

    public record RenderState(BlendMode.Preset blend, boolean doubleSided, boolean backFacesFirst, boolean depthTest,
                              boolean depthWrite) {
        public static final RenderState DEFAULT = new RenderState(BlendMode.Preset.ALPHA, false, false, true, false);

        public MaterialRenderState create() {
            boolean twoPass = doubleSided && backFacesFirst;
            return new MaterialRenderState(blend.create(), !doubleSided || twoPass, depthTest, depthWrite, twoPass);
        }

        private CompoundTag save() {
            var tag = new CompoundTag();
            tag.putString("blend", blend.name());
            tag.putBoolean("double_sided", doubleSided);
            tag.putBoolean("back_faces_first", backFacesFirst);
            tag.putBoolean("depth_test", depthTest);
            tag.putBoolean("depth_write", depthWrite);
            return tag;
        }

        private static RenderState load(CompoundTag tag) {
            var blend = BlendMode.Preset.ALPHA;
            var name = tag.getStringOr("blend", "");
            for (var preset : BlendMode.Preset.values()) {
                if (preset.name().equals(name)) blend = preset;
            }
            return new RenderState(blend, tag.getBooleanOr("double_sided", false),
                    tag.getBooleanOr("back_faces_first", false), tag.getBooleanOr("depth_test", true),
                    tag.getBooleanOr("depth_write", false));
        }
    }

    @Nullable
    private RenderState renderState;
    @Nullable
    private transient MaterialRenderState preferredState;
    @Nullable
    private CompoundTag rawAdditional;
    @Nullable
    private transient ShaderGraphBridge bridge;
    @Nullable
    private transient CompoundTag cachedAdditionalTag;
    private transient int cachedAdditionalHash;

    public ShaderGraphMaterial() {
        this.bridge = createOptionalBridge(this);
    }

    public ShaderGraphMaterial(IResourcePath graphPath) {
        this.graphPath = graphPath == null ? new BuiltinPath("") : graphPath;
        this.bridge = createOptionalBridge(this);
        if (this.bridge != null) {
            this.bridge.onGraphPathChanged(this.graphPath);
        }
    }

    @Nullable
    private static ShaderGraphBridge createOptionalBridge(ShaderGraphMaterial owner) {
        if (!LumenPlatform.isModLoaded("kilagraph") || !LumenPlatform.isModLoaded("ldlib2")) {
            return null;
        }
        if (!bridgeChecked) {
            synchronized (ShaderGraphMaterial.class) {
                if (!bridgeChecked) {
                    try {
                        Class<?> impl = Class.forName(OPTIONAL_BRIDGE_CLASS);
                        bridgeConstructor = impl.getConstructor(ShaderGraphMaterial.class);
                    } catch (Throwable ignored) {
                        bridgeConstructor = null;
                    }
                    bridgeChecked = true;
                }
            }
        }
        Constructor<?> ctor = bridgeConstructor;
        if (ctor == null) {
            return null;
        }
        try {
            return (ShaderGraphBridge) ctor.newInstance(owner);
        } catch (Throwable error) {
            Photon.LOGGER.warn("Failed to initialize optional KilaGraph shader-graph bridge", error);
            return null;
        }
    }

    public IResourcePath getGraphPath() {
        if (graphPath == null) graphPath = new BuiltinPath("");
        return graphPath;
    }

    public void setGraphPath(@Nullable IResourcePath graphPath) {
        this.graphPath = graphPath == null ? new BuiltinPath("") : graphPath;
        if (bridge != null) {
            bridge.onGraphPathChanged(this.graphPath);
        }
        invalidateCache();
    }

    @Nullable
    public RenderState getRenderState() {
        return renderState;
    }

    public void setRenderState(@Nullable RenderState renderState) {
        this.renderState = renderState;
        this.preferredState = null;
        invalidateCache();
    }

    @Override
    @Nullable
    public MaterialRenderState getPreferredRenderState() {
        if (renderState == null) return null;
        if (preferredState == null) preferredState = renderState.create();
        return preferredState;
    }

    public boolean isCompiledError() {
        return bridge != null && bridge.isCompiledError();
    }

    public String getCompiledErrorMessage() {
        return bridge == null ? "" : bridge.getCompiledErrorMessage();
    }

    @Override
    public long getUsedChannelMask() {
        return bridge != null ? bridge.getUsedChannelMask() : 0L;
    }

    @Override
    public boolean usesCustomData() {
        return bridge != null && bridge.usesCustomData();
    }

    @Override
    @Nullable
    public RenderType getRenderType(MaterialSetting setting, PrimitiveTopology mode) {
        return bridge != null ? bridge.getRenderType(setting, mode) : null;
    }

    @Override
    public void preload(PreloadContext context) {
        if (bridge != null) {
            bridge.preload(context);
        }
    }

    @Override
    public Tag serializeAdditionalNBT(HolderLookup.@NotNull Provider provider) {
        if (bridge != null) {
            var tag = new CompoundTag();
            tag.put("overrides", bridge.serializeOverrides(provider));
            if (renderState != null) tag.put("render_state", renderState.save());
            return tag;
        }
        if (rawAdditional != null) {
            var copy = rawAdditional.copy();
            if (renderState != null) copy.put("render_state", renderState.save());
            return copy;
        }
        var tag = new CompoundTag();
        if (renderState != null) tag.put("render_state", renderState.save());
        return tag;
    }

    @Override
    public void deserializeAdditionalNBT(Tag tag, HolderLookup.@NotNull Provider provider) {
        renderState = null;
        preferredState = null;
        rawAdditional = tag instanceof CompoundTag compound ? compound.copy() : null;
        if (tag instanceof CompoundTag compound) {
            if (compound.get("render_state") instanceof CompoundTag stateTag) {
                renderState = RenderState.load(stateTag);
            }
            if (bridge != null) {
                bridge.deserializeOverrides(compound.getCompoundOrEmpty("overrides"), provider);
            }
        } else if (bridge != null) {
            bridge.deserializeOverrides(new CompoundTag(), provider);
        }
        invalidateCache();
    }

    @Override
    public IMaterial copy() {
        var copied = new ShaderGraphMaterial(getGraphPath());
        copied.deserializeAdditionalNBT(serializeAdditionalNBT(Platform.getFrozenRegistry()),
                Platform.getFrozenRegistry());
        return copied;
    }

    private void invalidateCache() {
        cachedAdditionalTag = null;
        if (bridge != null) {
            bridge.invalidateCache();
        }
    }

    private CompoundTag additionalTag() {
        if (cachedAdditionalTag == null) {
            cachedAdditionalTag = (CompoundTag) serializeAdditionalNBT(Platform.getFrozenRegistry());
            cachedAdditionalHash = cachedAdditionalTag.hashCode();
        }
        return cachedAdditionalTag;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ShaderGraphMaterial that)) return false;
        if (!getGraphPath().equals(that.getGraphPath())) return false;
        return Objects.equals(additionalTag(), that.additionalTag());
    }

    @Override
    public int hashCode() {
        additionalTag();
        return getGraphPath().hashCode() * 31 + cachedAdditionalHash;
    }
}
