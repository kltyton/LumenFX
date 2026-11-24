package com.kltyton.lumenfx.api.client;

import com.kltyton.lumenfx.api.client.component.NiagaraComponentControl.Pooling;
import com.kltyton.lumenfx.client.VfxEngine;
import com.kltyton.lumenfx.client.audio.MinecraftNiagaraAudio;
import com.kltyton.lumenfx.api.client.parameter.NiagaraAudioBackend.DeviceSettings;
import com.kltyton.lumenfx.api.client.parameter.DynamicMaterialValues.OfflineFont;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField;
import com.kltyton.lumenfx.api.client.water.NiagaraWaterScene;
import com.kltyton.lumenfx.api.client.physics.NiagaraChaosScene;
import com.kltyton.lumenfx.api.client.physics.NiagaraChaosScene.EventDemand;
import com.kltyton.lumenfx.api.client.groom.NiagaraGroomAsset;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import com.kltyton.lumenfx.api.client.physics.AuthoredPhysicsFieldComponent;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture.Feedback;
import com.kltyton.lumenfx.api.client.parameter.NiagaraStreamingVirtualTexture;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTextureDefinition.StreamingMips;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTextureDefinition.Volume;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePageRenderer;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePlacement;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePlacement.SceneProfile;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePrimitive;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePrimitive.Bounds;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePrimitive.Registration;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTextureMaterialPass;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTextureVolume;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundMix;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraSoundClass;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class VfxEffects {
    private VfxEffects() { }

    public static EffectHandle spawn(ClientLevel level, Identifier effect, Vec3 position, long seed) {
        return VfxEngine.INSTANCE.spawn(level, effect, position, seed);
    }

    /** Submits the native packed Nanite page request stream to the current world's registered resources. */
    public static void requestNanitePages(ClientLevel level, int[] packedStream) {
        VfxEngine.INSTANCE.requestNanitePages(level, packedStream);
    }

    /** Prefetches the source resource's first optional pages before its first projected use. */
    public static void prefetchNaniteMesh(ClientLevel level, StaticMeshAsset mesh, int framesUntilRender) {
        VfxEngine.INSTANCE.prefetchNaniteMesh(level, mesh, framesUntilRender);
    }

    /** Creates a Niagara component using the saved activation and completion settings. */
    public static EffectHandle createNiagaraComponent(ClientLevel level, Identifier effect, Vec3 position, long seed) {
        return VfxEngine.INSTANCE.createNiagaraComponent(level, effect, position, seed);
    }

    public static EffectHandle createNiagaraComponent(ClientLevel level, Identifier effect, Vec3 position, long seed, Pooling pooling) {
        return VfxEngine.INSTANCE.createNiagaraComponent(level, effect, position, seed, pooling);
    }

    /** Applies source project audio settings to newly started cues on the client thread. */
    public static void setNiagaraAudioDeviceSettings(DeviceSettings settings) {
        MinecraftNiagaraAudio.INSTANCE.setDeviceSettings(settings);
    }

    /** Sets the source project's base mix. A null mix restores its packaged default. */
    public static void setNiagaraBaseSoundMix(String namespace, @Nullable NiagaraSoundMix mix) {
        MinecraftNiagaraAudio.INSTANCE.setBaseSoundMix(namespace, mix);
    }

    public static void pushNiagaraSoundMix(String namespace, NiagaraSoundMix mix) {
        MinecraftNiagaraAudio.INSTANCE.pushSoundMix(namespace, mix);
    }

    public static void popNiagaraSoundMix(String namespace, String sourcePath) {
        MinecraftNiagaraAudio.INSTANCE.popSoundMix(namespace, sourcePath);
    }

    public static void setNiagaraSoundMixClassOverride(String namespace, String mix, String soundClass,
                                                       float volume, float pitch, float fade, boolean children) {
        MinecraftNiagaraAudio.INSTANCE.setSoundMixClassOverride(namespace, mix, soundClass, volume, pitch, fade, children);
    }

    public static void clearNiagaraSoundMixClassOverride(String namespace, NiagaraSoundMix mix, String soundClass, float fade) {
        MinecraftNiagaraAudio.INSTANCE.clearSoundMixClassOverride(namespace, mix, soundClass, fade);
    }

    public static void setNiagaraSoundClassDistanceScale(String namespace, NiagaraSoundClass soundClass, float scale, float seconds) {
        MinecraftNiagaraAudio.INSTANCE.setSoundClassDistanceScale(namespace, soundClass, scale, seconds);
    }

    /** Publish the immutable physics field for the current level on the client thread. */
    public static void publishPhysicsField(ClientLevel level, @Nullable NiagaraPhysicsField field) {
        VfxEngine.INSTANCE.publishPhysicsField(level, field);
    }

    /** Publishes the current world's immutable water body snapshots on the client thread. */
    public static void publishWaterScene(ClientLevel level, @Nullable NiagaraWaterScene scene) {
        VfxEngine.INSTANCE.publishWaterScene(level, scene);
    }

    /** Publishes the active world's Chaos solver clocks and most recent typed event batches. */
    public static void publishChaosScene(ClientLevel level, @Nullable NiagaraChaosScene scene) {
        VfxEngine.INSTANCE.publishChaosScene(level, scene);
    }

    /** Installs the active world's handler for enabling event generation on host solvers. */
    public static void setChaosEventProducer(ClientLevel level, @Nullable Consumer<EventDemand> enable) {
        VfxEngine.INSTANCE.setChaosEventProducer(level, enable);
    }

    /** Resolves a saved FieldSystemComponent command graph from the current effect resources. */
    /** Looks up a Groom packaged with an effect by its saved Unreal object path. */
    public static NiagaraGroomAsset niagaraGroomAsset(Identifier effect, String source) {
        return VfxEngine.INSTANCE.niagaraGroomAsset(effect, source);
    }

    public static AuthoredPhysicsFieldComponent authoredPhysicsFieldComponent(Identifier effect, String component) {
        return VfxEngine.INSTANCE.authoredPhysicsFieldComponent(effect, component);
    }

    /** Resolves the stored texture pages of a font packaged with an effect by its source object path. */
    public static OfflineFont offlineMaterialFont(Identifier effect, String source) {
        return VfxEngine.INSTANCE.offlineMaterialFont(effect, source);
    }

    /** Submit residency demand for an authored streaming virtual texture on the client thread. */
    public static boolean requestVirtualTexture(Identifier effect, String sourceAsset, Feedback region) {
        return VfxEngine.INSTANCE.requestVirtualTexture(effect, sourceAsset, region);
    }

    /** Resolves a streamed texture in the current resource generation for a material or Niagara object parameter. */
    public static @Nullable NiagaraStreamingVirtualTexture streamedVirtualTexture(Identifier effect, String sourceAsset) {
        return VfxEngine.INSTANCE.streamedVirtualTexture(effect, sourceAsset);
    }

    /** Resolves a placed source component, including its main-pass visibility setting. */
    public static RuntimeVirtualTexturePlacement authoredRuntimeVirtualTexture(Identifier effect, String component) {
        return VfxEngine.INSTANCE.authoredRuntimeVirtualTexture(effect, component);
    }

    /** Applies the source component's world transform and game settings in the current client scene. */
    public static @Nullable RuntimeVirtualTextureVolume placeAuthoredRuntimeVirtualTexture(ClientLevel level, Identifier effect,
            String component, SceneProfile profile, int tileSizeBias, int tileCountBias, RuntimeVirtualTexturePageRenderer renderer) {
        return VfxEngine.INSTANCE.placeAuthoredRuntimeVirtualTexture(level, effect, component, profile, tileSizeBias, tileCountBias, renderer);
    }

    /** Places a source component and populates it with eligible primitives registered in the current scene. */
    public static @Nullable RuntimeVirtualTextureVolume placeAuthoredRuntimeVirtualTexture(ClientLevel level, Identifier effect,
            String component, SceneProfile profile, int tileSizeBias, int tileCountBias) {
        return VfxEngine.INSTANCE.placeAuthoredRuntimeVirtualTexture(level, effect, component, profile, tileSizeBias, tileCountBias);
    }

    /** Registers live geometry using its original primitive component settings from the current resource generation. */
    public static Registration registerRuntimeVirtualTexturePrimitive(ClientLevel level, Identifier effect, String component,
            RuntimeVirtualTexturePrimitive geometry, Bounds bounds) {
        return VfxEngine.INSTANCE.registerRuntimeVirtualTexturePrimitive(level, effect, component, geometry, bounds);
    }

    /** Resolves an authored material prepared for the RVT primitive raster in the current resource generation. */
    public static MaterialDefinition runtimeVirtualTextureWriter(Identifier effect, String sourceObject) {
        return VfxEngine.INSTANCE.runtimeVirtualTextureWriter(effect, sourceObject);
    }

    /** Places an authored RVT in the current level and connects its scene material producer. */
    public static RuntimeVirtualTextureVolume placeRuntimeVirtualTexture(ClientLevel level, Identifier effect, String sourceAsset,
                                                                        Volume volume, int tileSizeBias, int tileCountBias,
                                                                        int componentBias, RuntimeVirtualTexturePageRenderer renderer) {
        return VfxEngine.INSTANCE.placeRuntimeVirtualTexture(level, effect, sourceAsset, volume,
                tileSizeBias, tileCountBias, componentBias, renderer);
    }

    /** Places an RVT with cooked coarse mips and a scene renderer for its live fine mips. */
    public static RuntimeVirtualTextureVolume placeRuntimeVirtualTexture(ClientLevel level, Identifier effect, String sourceAsset,
                                                                        Volume volume, int tileSizeBias, int tileCountBias,
                                                                        int componentBias, RuntimeVirtualTexturePageRenderer renderer,
                                                                        StreamingMips streaming) {
        return VfxEngine.INSTANCE.placeRuntimeVirtualTexture(level, effect, sourceAsset, volume,
                tileSizeBias, tileCountBias, componentBias, renderer, streaming);
    }

    /**
     * Observes Unity collider-force contacts produced by the client's visual simulation.
     * The authoritative server simulation is started through {@link com.kltyton.lumenfx.api.server.LumenFxServerEffects}.
     * Per-handle observers can also use {@link EffectHandle#setUnityColliderForceListener}.
     */
    public static void setUnityColliderForceConsumer(
            @Nullable BiConsumer<ClientLevel, UnityParticleCollisionEvent.ForceImpact> consumer) {
        VfxEngine.INSTANCE.setUnityColliderForceConsumer(consumer);
    }

    /** Places an authored RVT and runs the source storage pass over the supplied scene material raster. */
    public static RuntimeVirtualTextureVolume placeRuntimeVirtualTexture(ClientLevel level, Identifier effect, String sourceAsset,
                                                                        Volume volume, int tileSizeBias, int tileCountBias,
                                                                        int componentBias, RuntimeVirtualTextureMaterialPass material) {
        return VfxEngine.INSTANCE.placeRuntimeVirtualTexture(level, effect, sourceAsset, volume,
                tileSizeBias, tileCountBias, componentBias, material);
    }

}
