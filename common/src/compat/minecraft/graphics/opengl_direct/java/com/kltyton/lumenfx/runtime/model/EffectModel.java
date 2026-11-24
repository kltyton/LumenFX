package com.kltyton.lumenfx.runtime.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** Unified immutable compiled effect asset matching LumenFX 26.2 {@code EffectDefinition}. */
public final class EffectModel {
    public enum Kind { UNITY, NIAGARA, PHOTON }

    private final String id;
    private final Kind kind;
    private final Map<String, MaterialModel> materials;
    private final List<UnityEmitterModel> emitters;
    private final @Nullable NiagaraSystemModel niagara;
    private final @Nullable PhotonFxModel photon;

    public EffectModel(String id, Kind kind, Map<String, MaterialModel> materials,
                       List<UnityEmitterModel> emitters,
                       @Nullable NiagaraSystemModel niagara,
                       @Nullable PhotonFxModel photon) {
        this.id = id;
        this.kind = kind;
        this.materials = Collections.unmodifiableMap(new LinkedHashMap<>(materials));
        this.emitters = List.copyOf(emitters);
        this.niagara = niagara;
        this.photon = photon;
    }

    public String id() { return id; }
    public Kind kind() { return kind; }
    public Map<String, MaterialModel> materials() { return materials; }
    public List<UnityEmitterModel> emitters() { return emitters; }
    public @Nullable NiagaraSystemModel niagara() { return niagara; }
    public @Nullable PhotonFxModel photon() { return photon; }

    public static EffectModel fromPhoton(PhotonFxModel photon) {
        return new EffectModel(photon.id(), Kind.PHOTON, Map.of(), List.of(), null, photon);
    }
}
