/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderCompiler;

/** Turns the flat quad into a ball of fog: alpha is the view ray's path through a sphere (Inigo Quilez). */
public class KilaVolume extends KilaModule {
    /** the sphere's radius as a share of the quad's half width */
    @Persisted
    public float size = 1;
    /** above 1 the core thickens */
    @Persisted
    public float density = 1;
    /** above 1 the edge sharpens */
    @Persisted
    public float power = 1;
    /** cut by the scene behind it; costs the scene depth copy */
    @Persisted
    public boolean clip = true;

    @Override
    public String id() {
        return "volume";
    }

    @Override
    public Category category() {
        return Category.ALPHA;
    }

    @Override
    public long structure() {
        return clip ? 1 : 0;
    }

    @Override
    public void declare(KilaLayout.Builder layout) {
        // x = the radius as a share of the quad's half width, y = density, z = power, w = 1 while the scene depth clips it
        layout.define("KILA_VOLUME").vec4("KilaVolume", 1, 1, 1, 0);
        if (clip) {
            layout.define("KILA_VOLUME_CLIP").sampler(PhotonShaderCompiler.SCENE_DEPTH);
        }
    }

    @Override
    public void apply(KilaValues values, float[] scratch) {
        boolean bound = clip;
        values.set("KilaVolume", Math.max(size, 0.01f), density, power, bound ? 1f : 0f);
    }

}
