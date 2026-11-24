/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import org.joml.Vector3f;

import java.util.function.BiConsumer;

/** Pushes vertices by a scrolling noise, along the normal or a fixed axis. */
public class KilaVertexOffset extends KilaModule {
    public enum Direction {
        NORMAL, AXIS
    }

    @Persisted(subPersisted = true)
    public final KilaTexture texture = new KilaTexture(KilaTextures.NOISE_PERLIN).scroll(0.1f, 0f);
    @Persisted(subPersisted = true)
    public final KilaDriver strength = new KilaDriver(0.1f);
    @Persisted
    public Direction direction = Direction.NORMAL;
    @Persisted
    public Vector3f axis = new Vector3f(0, 1, 0);
    @Persisted
    public boolean bidirectional;
    /** fades the push to nothing towards v = 0 and v = 1, which keeps a sphere's poles from tearing */
    @Persisted
    public float edgeFade;

    @Override
    public String id() {
        return "vertex_offset";
    }

    @Override
    public Category category() {
        return Category.VERTEX;
    }

    @Override
    public void declare(KilaLayout.Builder layout) {
        layout.define("KILA_VERTEX_OFFSET").uvChain("KilaVertex")
                // x = 1 pushes along the axis in yzw instead of the normal
                .vec4("KilaVertexDir")
                // x = 1 remaps the texture to -1..1, y = width of the fade towards v = 0 and v = 1 (0 = none)
                .vec4("KilaVertexShape")
                .source("VERTEX", "KilaVertex", texture, true)
                .params(KilaParam.VERTEX_OFFSET_STRENGTH);
    }

    @Override
    public void apply(KilaValues values, float[] scratch) {
        texture.uploadUv(values, "KilaVertex");
        values.set("KilaVertexOpts", texture.flags(), 0, 0, 0);
        texture.uploadSource(values, "KilaVertex", scratch);
        values.set("KilaVertexDir", direction == Direction.AXIS ? 1f : 0f, axis.x, axis.y, axis.z);
        values.set("KilaVertexShape", bidirectional ? 1f : 0f, edgeFade, 0f, 0f);
    }

    @Override
    public void textures(java.util.function.Consumer<KilaTexture> out) {
        out.accept(texture);
    }

    @Override
    public void drivers(BiConsumer<KilaParam, KilaDriver> out) {
        out.accept(KilaParam.VERTEX_OFFSET_STRENGTH, strength);
    }

}
