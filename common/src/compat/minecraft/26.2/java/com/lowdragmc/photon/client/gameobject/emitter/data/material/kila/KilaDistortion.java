/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import org.joml.Vector2f;

import java.util.function.BiConsumer;

/** Pushes the uv of the slots it targets by a noise; as a flow map it carries them along its vectors instead. */
public class KilaDistortion extends KilaModule {
    /** ⚠️ bits MIRRORED IN {@code kila.fsh} (KILA_FLOW_*). */
    public enum Target {
        MAIN(1), LAYER(2), MASK1(4), MASK2(8), DISSOLVE(16);

        public final int bit;

        Target(int bit) {
            this.bit = bit;
        }
    }

    /** ⚠️ ordinals MIRRORED IN {@code kila_flow}. */
    public enum Mask {
        NONE, U, V, RADIAL
    }

    public enum Mode {
        OFFSET, FLOW_MAP
    }

    @Persisted(subPersisted = true)
    public final KilaTexture texture = new KilaTexture(KilaTextures.NOISE_FLOW).scroll(0.15f, 0.08f);
    @Persisted(subPersisted = true)
    public final KilaDriver strength = new KilaDriver(0.05f);
    @Persisted
    public Vector2f amount = new Vector2f(1, 1);
    /** remaps the texture to -1..1 so it pushes both ways */
    @Persisted
    public boolean bidirectional = true;
    @Persisted
    public Mask mask = Mask.NONE;
    @Persisted
    public Mode mode = Mode.OFFSET;
    /** flow map cycles per second */
    @Persisted
    public float flowSpeed = 0.5f;
    @Persisted
    public boolean main = true;
    @Persisted
    public boolean layer = true;
    @Persisted
    public boolean mask1;
    @Persisted
    public boolean mask2;
    @Persisted
    public boolean dissolve = true;

    @Override
    public String id() {
        return "distortion";
    }

    @Override
    public Category category() {
        return Category.TEXTURE;
    }

    public int targets() {
        return (main ? Target.MAIN.bit : 0) | (layer ? Target.LAYER.bit : 0) | (mask1 ? Target.MASK1.bit : 0)
                | (mask2 ? Target.MASK2.bit : 0) | (dissolve ? Target.DISSOLVE.bit : 0);
    }

    @Override
    public long structure() {
        return mode.ordinal();
    }

    @Override
    public void declare(KilaLayout.Builder layout) {
        // KilaDistortOpts: x = uv flags, z = targets, w = strength mask (0 none, 1 u, 2 v, 3 radial)
        layout.define("KILA_DISTORT").uvChain("KilaDistort")
                // xy = amount per axis, z = 1 pushes both ways (rg * 2 - 1)
                .vec4("KilaDistortParams", 1, 1, 1, 0)
                .source("DISTORT", "KilaDistort", texture, false).params(KilaParam.DISTORT_STRENGTH);
        // x = flow cycles per second
        if (mode == Mode.FLOW_MAP) layout.define("KILA_FLOWMAP").vec4("KilaFlowMap", 0.5f, 0, 0, 0);
    }

    @Override
    public void apply(KilaValues values, float[] scratch) {
        texture.uploadUv(values, "KilaDistort");
        if (texture.isProcedural()) texture.uploadSource(values, "KilaDistort", scratch);
        values.set("KilaDistortOpts", texture.flags(), 0, targets(), mask.ordinal());
        values.set("KilaDistortParams", amount.x, amount.y, bidirectional ? 1f : 0f, 0f);
        if (mode == Mode.FLOW_MAP) values.set("KilaFlowMap", flowSpeed, 0f, 0f, 0f);
    }

    @Override
    public void textures(java.util.function.Consumer<KilaTexture> out) {
        out.accept(texture);
    }

    @Override
    public void drivers(BiConsumer<KilaParam, KilaDriver> out) {
        out.accept(KilaParam.DISTORT_STRENGTH, strength);
    }

}
