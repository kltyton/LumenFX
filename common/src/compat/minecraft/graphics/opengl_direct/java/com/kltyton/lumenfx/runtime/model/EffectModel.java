package com.kltyton.lumenfx.runtime.model;

import com.kltyton.lumenfx.runtime.audio.DirectSpatialAudioEngine.SoundCueSpec;
import com.kltyton.lumenfx.runtime.gl.volume.DirectGlVolumeAndLightRenderer.LightSpec;
import com.kltyton.lumenfx.runtime.gl.volume.DirectGlVolumeAndLightRenderer.VolumeSpec;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** Unified immutable compiled effect asset matching LumenFX 26.2 {@code EffectDefinition} across all OpenGL-direct targets. */
public final class EffectModel {
    public enum Kind { UNITY, NIAGARA, PHOTON }

    public record DecalAssetSpec(double x, double y, double z, float sx, float sy, float sz,
                                 float r, float g, float b, float a, @Nullable String textureId) { }

    private final String id;
    private final Kind kind;
    private final Map<String, MaterialModel> materials;
    private final List<UnityEmitterModel> emitters;
    private final @Nullable NiagaraSystemModel niagara;
    private final @Nullable PhotonFxModel photon;
    private final List<SoundCueSpec> audioCues;
    private final List<GaussianSplatModel> gaussianSplats;
    private final List<GroomAssetModel> grooms;
    private final List<VolumeSpec> volumes;
    private final List<DecalAssetSpec> decals;
    private final List<LightSpec> lights;
    private final List<String> rvtMaterials;

    public EffectModel(String id, Kind kind, Map<String, MaterialModel> materials,
                       List<UnityEmitterModel> emitters,
                       @Nullable NiagaraSystemModel niagara,
                       @Nullable PhotonFxModel photon) {
        this(id, kind, materials, emitters, niagara, photon,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public EffectModel(String id, Kind kind, Map<String, MaterialModel> materials,
                       List<UnityEmitterModel> emitters,
                       @Nullable NiagaraSystemModel niagara,
                       @Nullable PhotonFxModel photon,
                       List<SoundCueSpec> audioCues,
                       List<GaussianSplatModel> gaussianSplats,
                       List<GroomAssetModel> grooms,
                       List<VolumeSpec> volumes,
                       List<DecalAssetSpec> decals,
                       List<LightSpec> lights,
                       List<String> rvtMaterials) {
        this.id = id;
        this.kind = kind;
        this.materials = Collections.unmodifiableMap(new LinkedHashMap<>(materials));
        this.emitters = List.copyOf(emitters);
        this.niagara = niagara;
        this.photon = photon;
        this.audioCues = List.copyOf(audioCues);
        this.gaussianSplats = List.copyOf(gaussianSplats);
        this.grooms = List.copyOf(grooms);
        this.volumes = List.copyOf(volumes);
        this.decals = List.copyOf(decals);
        this.lights = List.copyOf(lights);
        this.rvtMaterials = List.copyOf(rvtMaterials);
    }

    public String id() { return id; }
    public Kind kind() { return kind; }
    public Map<String, MaterialModel> materials() { return materials; }
    public List<UnityEmitterModel> emitters() { return emitters; }
    public @Nullable NiagaraSystemModel niagara() { return niagara; }
    public @Nullable PhotonFxModel photon() { return photon; }
    public List<SoundCueSpec> audioCues() { return audioCues; }
    public List<GaussianSplatModel> gaussianSplats() { return gaussianSplats; }
    public List<GroomAssetModel> grooms() { return grooms; }
    public List<VolumeSpec> volumes() { return volumes; }
    public List<DecalAssetSpec> decals() { return decals; }
    public List<LightSpec> lights() { return lights; }
    public List<String> rvtMaterials() { return rvtMaterials; }

    public boolean hasVisualAttachments() {
        return !gaussianSplats.isEmpty() || !grooms.isEmpty()
                || !volumes.isEmpty() || !decals.isEmpty() || !lights.isEmpty();
    }

    public static EffectModel fromPhoton(PhotonFxModel photon) {
        return new EffectModel(photon.id(), Kind.PHOTON, Map.of(), List.of(), null, photon);
    }
}
