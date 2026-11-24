package com.kltyton.lumenfx.client.render.decal;

import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.render.decal.DecalMaterialSettings;
import com.kltyton.lumenfx.effect.render.decal.ParticleDecal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Scene proxy slots survive updates and are removed with their renderer or material identity. */
public final class DecalScene {
    public record Visible(ParticleDecal decal, DecalProjectionView.Shading shading, long proxyIdentity,
                          long materialIdentity, int signature, boolean writesNormal) { }
    private record RendererKey(long primitive, String renderer) { }
    private static final class Renderer {
        final MaterialDefinition material;
        final List<Long> proxies = new ArrayList<>();
        final long materialIdentity;
        long frame;
        int count;
        Renderer(MaterialDefinition material, long identity) { this.material = material; materialIdentity = identity; }
    }
    private final Map<RendererKey, Renderer> renderers = new HashMap<>();
    private final Map<MaterialDefinition, Long> materials = new IdentityHashMap<>();
    private final List<Visible> visible = new ArrayList<>();
    private long frame, nextProxy, nextMaterial;
    private static final Comparator<Visible> ORDER = Comparator.comparingInt((Visible value) -> value.decal().sortOrder())
            .thenComparing(Visible::writesNormal, Comparator.reverseOrder())
            .thenComparing(Visible::signature, Comparator.reverseOrder())
            .thenComparing(Visible::materialIdentity, Comparator.reverseOrder())
            .thenComparing(value -> value.decal().sourcePrimitive(), Comparator.reverseOrder());

    public List<Visible> prepare(List<ParticleDecal> decals, DecalProjectionView view, double worldSeconds,
                                 float nearClipCentimeters, float screenFadeMultiplier, float preExposure) {
        frame++;
        visible.clear();
        for (ParticleDecal decal : decals) {
            DecalMaterialSettings settings = Objects.requireNonNull(decal.material().decalSettings(), "Scene decal requires a deferred-decal material");
            RendererKey key = new RendererKey(decal.sourcePrimitive(), decal.renderer());
            Renderer renderer = renderers.get(key);
            if (renderer == null || renderer.material != decal.material()) {
                long identity = materials.computeIfAbsent(decal.material(), ignored -> ++nextMaterial);
                renderer = new Renderer(decal.material(), identity);
                renderers.put(key, renderer);
            }
            if (renderer.frame != frame) { renderer.frame = frame; renderer.count = 0; }
            int slot = decal.proxySlot();
            if (slot != renderer.count++) throw new IllegalArgumentException("Decal scene slots must follow visible dataset order");
            if (renderer.proxies.size() == slot) renderer.proxies.add(++nextProxy);
            float radius = view.conservativeRadiusCentimeters(decal);
            if (radius < 1e-8f || !view.visible(decal, radius)) continue;
            float screen = view.screenOpacity(decal, screenFadeMultiplier);
            if (!(screen > 0)) continue;
            double updateSeconds = Double.isNaN(decal.updateWorldSeconds()) ? worldSeconds : decal.updateWorldSeconds();
            float lifetime = decal.fading() == null ? DecalLifetime.opacity(decal.fade(), updateSeconds, worldSeconds)
                    : decal.fading().opacity(worldSeconds);
            visible.add(new Visible(decal, new DecalProjectionView.Shading(screen, lifetime, preExposure,
                    view.inside(decal, nearClipCentimeters)), renderer.proxies.get(slot), renderer.materialIdentity,
                    signature(settings), settings.writes(DecalMaterialSettings.NORMAL)));
        }
        renderers.entrySet().removeIf(entry -> entry.getValue().frame != frame);
        for (Renderer renderer : renderers.values())
            if (renderer.proxies.size() > renderer.count) renderer.proxies.subList(renderer.count, renderer.proxies.size()).clear();
        // Material identities are retained only while a renderer uses the corresponding material.
        materials.keySet().removeIf(material -> renderers.values().stream().noneMatch(renderer -> renderer.material == material));
        visible.sort(ORDER);
        return visible;
    }

    private static int signature(DecalMaterialSettings settings) {
        int stages = (settings.writesDBuffer() ? 1 << 1 : 0) | (settings.writes(DecalMaterialSettings.EMISSIVE) ? 1 << 5 : 0)
                | (settings.writes(DecalMaterialSettings.AMBIENT_OCCLUSION) ? 1 << 6 : 0);
        // Only the relative blend ordering matters; descriptor flag and stage significance match FDecalBlendDesc.
        int blendRank = settings.blend() == DecalMaterialSettings.Blend.ALPHA_COMPOSITE ? 1 : 0;
        return (settings.outputs() << 16) | (stages << 8) | blendRank;
    }

    public void clear() { visible.clear(); renderers.clear(); materials.clear(); frame = nextProxy = nextMaterial = 0; }
}
