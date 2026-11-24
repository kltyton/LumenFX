/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.photon.client.gameobject.emitter.data.ToggleGroup;

import java.util.function.BiConsumer;

/** One switchable feature of {@link KilaMaterial}; off, it adds nothing to the program. */
public abstract class KilaModule extends ToggleGroup {
    public enum Category {
        TEXTURE, COLOR, ALPHA, VERTEX, LIGHTING
    }

    public abstract String id();

    public abstract Category category();

    public String langKey() {
        return "kila.module." + id();
    }

    /** Defines, uniforms, samplers and parameter groups of the program variant — only called when enabled. */
    public abstract void declare(KilaLayout.Builder layout);

    /** Per draw: stage this module's uniforms. Only called when enabled and part of the variant. */
    public abstract void apply(KilaValues values, float[] scratch);

    /** The parameters this module lets particle data drive. */
    public void drivers(BiConsumer<KilaParam, KilaDriver> out) {
    }

    /** Every texture this module can sample — which ones share a sampler is part of the variant. */
    public void textures(java.util.function.Consumer<KilaTexture> out) {
    }

    /** What, besides being enabled, changes the program variant; mixed into the material's structure hash. */
    public long structure() {
        return 0;
    }

    /** Settings survive switching the module off and on again, also across a save. */
    @Override
    public boolean skipDisableSerialize() {
        return false;
    }
}
