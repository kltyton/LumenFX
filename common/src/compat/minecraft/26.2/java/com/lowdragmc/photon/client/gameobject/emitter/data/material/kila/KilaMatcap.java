/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;

/** A painted lit sphere looked up by the normal; a flat billboard needs {@link KilaShading} or reads one texel. */
public class KilaMatcap extends KilaModule {
    /** ⚠️ ordinals MIRRORED IN {@code kila.fsh} (KilaMatcap.y). */
    public enum Blend {
        ADD, MULTIPLY, REPLACE
    }

    @Persisted(subPersisted = true)
    public final KilaTexture texture = new KilaTexture(KilaTextures.MATCAP_GLASS);
    @Persisted
    public Blend blend = Blend.ADD;
    @Persisted
    public float strength = 1;

    @Override
    public String id() {
        return "matcap";
    }

    @Override
    public Category category() {
        return Category.LIGHTING;
    }

    @Override
    public void declare(KilaLayout.Builder layout) {
        // x = strength, y = blend (⚠️ Blend ordinals MIRRORED IN kila.fsh: 0 add, 1 multiply, 2 replace)
        layout.define("KILA_MATCAP").vec4("KilaMatcap", 1, 0, 0, 0).texture("MATCAP", texture);
    }

    @Override
    public void apply(KilaValues values, float[] scratch) {
        values.set("KilaMatcap", strength, (float) blend.ordinal(), 0f, 0f);
    }

    @Override
    public void textures(java.util.function.Consumer<KilaTexture> out) {
        out.accept(texture);
    }

}
