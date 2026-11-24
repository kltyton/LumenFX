/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data.material.kila;

import com.lowdragmc.lowdraglib2.math.GradientColor;
import com.lowdragmc.lowdraglib2.math.HDRColor;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.photon.client.shadergraph.PhotonShaderCompiler;

import java.util.function.BiConsumer;

/**
 * Fades softly into the opaque scene behind the particle and glows where it touches. Declaring the scene depth is
 * what makes the drain take its copy, so it stays pull-based.
 */
public class KilaDepth extends KilaModule {
    /** ⚠️ ordinals MIRRORED IN {@code kila.fsh} (KilaDepthOpts.z). */
    public enum ContactMode {
        ADD, REPLACE
    }

    /** ⚠️ ordinals MIRRORED IN {@code kila.fsh} (KilaDepthOpts.y). */
    public enum Measure {
        /** along the view ray: a band seen at a grazing angle looks thinner */
        VIEW,
        /** square to the surface behind, where that is a flat face: the same width from any angle */
        SURFACE
    }

    @Persisted
    public boolean soft = true;
    @Persisted
    public float distance = 1;
    @Persisted
    public float power = 1;

    /** blocks; 0 = no glow */
    @Persisted(subPersisted = true)
    public final KilaDriver contactWidth = new KilaDriver(0);
    @Persisted(subPersisted = true)
    public final KilaDriver contactStrength = new KilaDriver(1);
    @Persisted
    public HDRColor contactColor = new HDRColor(0.6f, 0.9f, 1f, 1f, 2f);
    @Persisted
    public ContactMode contactMode = ContactMode.ADD;
    /** 1 = linear; above 1 the glow hugs the surface */
    @Persisted
    public float contactPower = 1;
    /** 0 fades across the whole band; towards 1 it stays solid to its edge */
    @Persisted
    public float contactHardness;
    /** the glow is cut by the particle's own shape; off, it fills the quad wherever it touches */
    @Persisted
    public boolean contactFollowsShape = true;
    @Persisted
    public Measure measure = Measure.VIEW;
    @Persisted
    public boolean contactGradient;
    /** left = at the surface, right = the band's outer edge */
    @Persisted(subPersisted = true)
    public final GradientColor contactGradientColors = new GradientColor(0xFFFFFFFF, 0xFF7FE6FF, 0xFF2A6CFF, 0x002A6CFF);
    /** bands per width, running outwards; 0 = none */
    @Persisted
    public int contactPulses;
    /** bands per second */
    @Persisted
    public float contactPulseSpeed = 1;
    /** how much a noise eats into the band from its outer edge */
    @Persisted
    public float contactBreakup;
    @Persisted(subPersisted = true)
    public final KilaTexture contactNoise = new KilaTexture(KilaTextures.NOISE_CLOUD).noise(KilaTexture.Noise.GRADIENT, 6)
            .scroll(0, 0.3f);

    private final KilaBakedGradient bakedGradient = new KilaBakedGradient(contactGradientColors);

    @Override
    public String id() {
        return "depth";
    }

    @Override
    public Category category() {
        return Category.ALPHA;
    }

    private boolean breakup() {
        return contactBreakup > 0;
    }

    @Override
    public long structure() {
        return (measure == Measure.SURFACE ? 1 : 0) | (contactGradient ? 2 : 0) | (breakup() ? 4 : 0);
    }

    @Override
    public void declare(KilaLayout.Builder layout) {
        layout.define("KILA_DEPTH").vec4("SoftParticleParams", 1, 1, 0, 0)
                // x = 1 fades softly into the scene, y = measure, z = contact mode (⚠️ both MIRRORED IN kila.fsh),
                // w = 1 cuts the glow by the shape's alpha
                .ivec4("KilaDepthOpts")
                // rgb = HDR colour where the particle touches the scene
                .vec4("KilaContactColor")
                // x = falloff power, y = hardness, z = breakup, w = pulses across the band (0 = none)
                .vec4("KilaContactShape", 1, 0, 0, 0)
                // x = pulses per second
                .vec4("KilaContactPulse")
                .sampler(PhotonShaderCompiler.SCENE_DEPTH).params(KilaParam.CONTACT_STRENGTH, KilaParam.CONTACT_WIDTH);
        if (measure == Measure.SURFACE) layout.define("KILA_REL_POS");
        if (contactGradient) layout.define("KILA_CONTACT_RAMP").dynamicTexture("CONTACT_RAMP", bakedGradient);
        if (breakup()) {
            layout.define("KILA_CONTACT_BREAKUP").uvChain("KilaContact")
                    .source("CONTACT", "KilaContact", contactNoise, true);
        }
    }

    @Override
    public void apply(KilaValues values, float[] scratch) {
        values.set("SoftParticleParams", distance, power, 0f, 1f);
        values.set("KilaDepthOpts", soft ? 1 : 0, measure.ordinal(), contactMode.ordinal(),
                contactFollowsShape ? 1 : 0);
        var glow = contactColor.toVector4fOpaque();
        values.set("KilaContactColor", glow.x, glow.y, glow.z, 0f);
        values.set("KilaContactShape", contactPower, contactHardness, contactBreakup, (float) contactPulses);
        values.set("KilaContactPulse", contactPulseSpeed, 0f, 0f, 0f);
        if (breakup()) {
            contactNoise.uploadUv(values, "KilaContact");
            values.set("KilaContactOpts", contactNoise.flags(), 0, 0, 0);
            contactNoise.uploadSource(values, "KilaContact", scratch);
        }
    }


    @Override
    public void textures(java.util.function.Consumer<KilaTexture> out) {
        if (breakup()) out.accept(contactNoise);
    }

    @Override
    public void drivers(BiConsumer<KilaParam, KilaDriver> out) {
        out.accept(KilaParam.CONTACT_STRENGTH, contactStrength);
        out.accept(KilaParam.CONTACT_WIDTH, contactWidth);
    }

}
