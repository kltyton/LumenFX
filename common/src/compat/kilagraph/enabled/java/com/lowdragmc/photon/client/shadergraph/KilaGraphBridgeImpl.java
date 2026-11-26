/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.shadergraph;

import com.kltyton.lumenfx.internal.photon.core.editor.resource.IResourcePath;
import com.lowdragmc.kilagraph.rendertype.RenderTypeGraphTypes;
import com.lowdragmc.kilagraph.rendertype.compiler.SamplerDefault;
import com.lowdragmc.kilagraph.rendertype.runtime.RenderTypeFactory;
import com.lowdragmc.kilagraph.rendertype.runtime.RenderTypeGraphMaterial;
import com.lowdragmc.lowdraglib2.math.HDRColor;
import com.lowdragmc.photon.client.AutoCloseCleaner;
import com.lowdragmc.photon.client.fx.PreloadContext;
import com.lowdragmc.photon.client.gameobject.emitter.data.MaterialSetting;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.ShaderGraphMaterial;
import com.lowdragmc.photon.client.render.PhotonGraphPipelines;
import com.lowdragmc.photon.client.render.PhotonPipelines;
import com.lowdragmc.photon.client.render.PhotonRenderTypes;
import com.lowdragmc.photon.client.render.PhotonWorldRenderState;
import com.lowdragmc.photon.client.shadergraph.runtime.ShaderGraphRuntime;
import com.mojang.blaze3d.PrimitiveTopology;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Optional runtime KilaGraph delegate loaded reflectively ONLY when both kilagraph and ldlib2 are installed. */
public final class KilaGraphBridgeImpl implements ShaderGraphBridge {
    private final ShaderGraphMaterial owner;
    private final Map<String, Object> overrides = new LinkedHashMap<>();
    @Nullable
    private ShaderGraphRuntime.Entry entry;
    @Nullable
    private GraphState state;
    @Nullable
    private ShaderGraphRuntime.Entry compiledMaterialEntry;
    private boolean overridesStale = true;

    public KilaGraphBridgeImpl(ShaderGraphMaterial owner) {
        this.owner = owner;
    }

    private static final class GraphState implements AutoCloseable {
        final RenderTypeGraphMaterial material;
        final Map<PhotonPipelines.ParticlePipelineKey, RenderType> renderTypes = new HashMap<>();
        private boolean closed;

        GraphState(RenderTypeGraphMaterial material) {
            this.material = material;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            renderTypes.values().forEach(PhotonRenderTypes::dropCustomShader);
            renderTypes.clear();
            material.close();
        }
    }

    @Override
    public void onGraphPathChanged(IResourcePath graphPath) {
        this.entry = null;
        this.overridesStale = true;
    }

    @Override
    public boolean isCompiledError() {
        return entry != null && !entry.isValid();
    }

    @Override
    public String getCompiledErrorMessage() {
        return entry == null ? "" : entry.getErrorMessage();
    }

    @Nullable
    private ShaderGraphRuntime.Entry refreshEntry() {
        var current = ShaderGraphRuntime.get(owner.getGraphPath());
        if (current != entry) {
            entry = current;
            if (current != null) {
                reconcileOverrides(current);
            }
        }
        return current;
    }

    private void reconcileOverrides(ShaderGraphRuntime.Entry entry) {
        if (overrides.isEmpty() || entry.getGraph() == null) return;
        var expected = new HashMap<String, Class<?>>();
        for (var declaration : entry.getGraph().graphModel.getGraphVariableModels()) {
            if (declaration == null) continue;
            var def = declaration.tryGetDefaultValue(declaration.getDataType()).result().orElse(null);
            if (def != null) expected.put(declaration.getName(), def.getClass());
        }
        if (overrides.entrySet().removeIf(e -> {
            var cls = expected.get(e.getKey());
            return cls != null && !cls.isInstance(e.getValue());
        })) {
            overridesStale = true;
        }
    }

    @Override
    public long getUsedChannelMask() {
        var e = refreshEntry();
        return e != null && e.isValid() ? e.getUsedChannelMask() : 0L;
    }

    @Override
    public boolean usesCustomData() {
        var e = refreshEntry();
        return e != null && e.isValid() && e.isUsesCustomData();
    }

    @Override
    @Nullable
    public RenderType getRenderType(MaterialSetting setting, PrimitiveTopology mode) {
        var e = refreshEntry();
        if (e == null || !e.isValid() || e.getCompiled() == null) return null;
        if (compiledMaterialEntry != e) {
            if (state != null) {
                PhotonWorldRenderState.closeAtFrameEnd(state);
                state = null;
            }
            var material = RenderTypeFactory.createMaterial(e.getCompiled());
            if (material != null) {
                state = new GraphState(material);
                AutoCloseCleaner.registerRenderThread(owner, state);
            }
            compiledMaterialEntry = e;
            overridesStale = true;
        }
        var current = state;
        if (current == null) return null;
        if (overridesStale) {
            overrides.forEach(this::applyOverride);
            overridesStale = false;
        }
        var key = setting.pipelineKey(mode);
        var existing = current.renderTypes.get(key);
        if (existing != null) return existing;
        var created = PhotonGraphPipelines.createGraphShader(e.getCompiled(), current.material, key,
                e.getUsedChannelMask(), e.isUsesCustomData()).orElse(null);
        if (created != null) current.renderTypes.put(key, created);
        return created;
    }

    @Override
    public void preload(PreloadContext context) {
        var e = refreshEntry();
        var compiled = e == null || !e.isValid() ? null : e.getCompiled();
        if (compiled == null) return;
        var samplers = new HashMap<>(compiled.samplerDefaults());
        overrides.forEach((name, value) -> {
            var sampler = SamplerDefault.of(value);
            if (sampler != null) samplers.put(compiled.variableSamplers().getOrDefault(name, name), sampler);
        });
        for (var sampler : samplers.values()) {
            if (sampler.texture() != null) context.texture(sampler.texture());
        }
    }

    private void applyOverride(String name, Object value) {
        var current = state;
        if (current == null) return;
        var material = current.material;
        switch (value) {
            case Float f -> material.setUniform(name, f);
            case Integer color -> material.setColorUniform(name, color);
            case Boolean b -> material.setUniform(name, b ? 1f : 0f);
            case HDRColor hdr -> material.setHDRColorUniform(name, hdr);
            case Vector2f v -> material.setUniform(name, v);
            case Vector3f v -> material.setUniform(name, v);
            case Vector4f v -> material.setUniform(name, v);
            case RenderTypeGraphTypes.GradientValue gradient -> material.setGradient(name, gradient);
            case RenderTypeGraphTypes.CurveValue curve -> material.setCurve(name, curve);
            case RenderTypeGraphTypes.Sampler2DValue sampler -> material.setSampler(name, sampler);
            default -> { }
        }
    }

    @Override
    public Tag serializeOverrides(HolderLookup.Provider provider) {
        var overridesTag = new CompoundTag();
        overrides.forEach((name, value) -> {
            var encoded = encodeValue(value);
            if (encoded != null) overridesTag.put(name, encoded);
        });
        return overridesTag;
    }

    @Override
    public void deserializeOverrides(CompoundTag overridesTag, HolderLookup.Provider provider) {
        overrides.clear();
        entry = null;
        overridesStale = true;
        for (var name : overridesTag.keySet()) {
            var value = decodeValue(overridesTag.get(name));
            if (value != null) overrides.put(name, value);
        }
    }

    @Override
    public void invalidateCache() {
        overridesStale = true;
    }

    @Nullable
    private static Tag encodeValue(Object value) {
        return switch (value) {
            case Float f -> FloatTag.valueOf(f);
            case Integer i -> IntTag.valueOf(i);
            case Boolean b -> ByteTag.valueOf(b);
            case Vector2f v -> floatList(v.x, v.y);
            case Vector3f v -> floatList(v.x, v.y, v.z);
            case Vector4f v -> floatList(v.x, v.y, v.z, v.w);
            case RenderTypeGraphTypes.Sampler2DValue sampler ->
                    typed("sampler", RenderTypeGraphTypes.SAMPLER2D_CODEC.encodeStart(NbtOps.INSTANCE, sampler)
                            .result().orElse(null));
            case RenderTypeGraphTypes.GradientValue gradient ->
                    typed("gradient", RenderTypeGraphTypes.GRADIENT_CODEC.encodeStart(NbtOps.INSTANCE, gradient)
                            .result().orElse(null));
            case RenderTypeGraphTypes.CurveValue curve ->
                    typed("curve", RenderTypeGraphTypes.CURVE_CODEC.encodeStart(NbtOps.INSTANCE, curve)
                            .result().orElse(null));
            case HDRColor hdr ->
                    typed("hdr_color", HDRColor.CODEC.encodeStart(NbtOps.INSTANCE, hdr).result().orElse(null));
            default -> null;
        };
    }

    @Nullable
    private static Object decodeValue(@Nullable Tag tag) {
        return switch (tag) {
            case FloatTag f -> f.floatValue();
            case IntTag i -> i.intValue();
            case ByteTag b -> b.byteValue() != 0;
            case ListTag list -> switch (list.size()) {
                case 2 -> new Vector2f(list.getFloatOr(0, 0.0F), list.getFloatOr(1, 0.0F));
                case 3 -> new Vector3f(list.getFloatOr(0, 0.0F), list.getFloatOr(1, 0.0F), list.getFloatOr(2, 0.0F));
                case 4 -> new Vector4f(list.getFloatOr(0, 0.0F), list.getFloatOr(1, 0.0F), list.getFloatOr(2, 0.0F), list.getFloatOr(3, 0.0F));
                default -> null;
            };
            case CompoundTag compound -> switch (compound.getStringOr("type", "")) {
                case "sampler" -> RenderTypeGraphTypes.SAMPLER2D_CODEC.parse(NbtOps.INSTANCE, compound.get("data"))
                        .result().orElse(null);
                case "gradient" -> RenderTypeGraphTypes.GRADIENT_CODEC.parse(NbtOps.INSTANCE, compound.get("data"))
                        .result().orElse(null);
                case "curve" -> RenderTypeGraphTypes.CURVE_CODEC.parse(NbtOps.INSTANCE, compound.get("data"))
                        .result().orElse(null);
                case "hdr_color" -> HDRColor.CODEC.parse(NbtOps.INSTANCE, compound.get("data"))
                        .result().orElse(null);
                default -> null;
            };
            case null, default -> null;
        };
    }

    private static ListTag floatList(float... components) {
        var list = new ListTag();
        for (var component : components) list.add(FloatTag.valueOf(component));
        return list;
    }

    @Nullable
    private static Tag typed(String type, @Nullable Tag data) {
        if (data == null) return null;
        var tag = new CompoundTag();
        tag.putString("type", type);
        tag.put("data", data);
        return tag;
    }
}
