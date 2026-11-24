package com.kltyton.lumenfx.client.render.mesh;

import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.effect.render.mesh.MeshRendererSettings;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset;
import net.minecraft.world.phys.Vec3;

public final class MeshLodSelection {
    private MeshLodSelection() { }

    public static int minimum(StaticMeshAsset asset, MeshView view) {
        if (view.lodPolicy().perQuality()) return Math.max(0, asset.qualityMinimumLod().at(view.lodPolicy().qualityLevel()));
        return Math.max(0, asset.minimumLod().overrides().getOrDefault(view.platform(), asset.minimumLod().value()));
    }

    public static float screenSize(StaticMeshAsset asset, int lod, MeshView view) {
        var setting = asset.lods().get(lod).screenSize();
        return setting.overrides().getOrDefault(view.platform(), setting.value());
    }

    static void requestStreamingLod(StaticMeshAsset asset, MeshRendererSettings.Entry entry, MeshView view,
                                    Vec3 ownerCentre, Vec3 camera, boolean recentlyRendered) {
        var streaming = asset.streaming();
        if (streaming == null) return;
        var bounds = asset.extendedBounds();
        var origin = bounds.origin();
        var extent = bounds.extent().multiply(entry.scale());
        double x = (ownerCentre.x - camera.x) * 100 + origin.x;
        double y = (ownerCentre.z - camera.z) * 100 + origin.y;
        double z = (ownerCentre.y - camera.y) * 100 + origin.z;
        var platform = NiagaraPlatforms.snapshot();
        double distanceSquared;
        if (platform.renderAssetStreamingUsesBoxDistance()) {
            double dx = Math.max(0, Math.abs(x) - Math.abs(extent.x));
            double dy = Math.max(0, Math.abs(y) - Math.abs(extent.y));
            double dz = Math.max(0, Math.abs(z) - Math.abs(extent.z));
            distanceSquared = dx * dx + dy * dy + dz * dz;
        } else distanceSquared = x * x + y * y + z * z - extent.x * extent.x;
        double normalizedSize = 2 * bounds.radius() / Math.sqrt(Math.max(1, distanceSquared));
        int minimum = minimum(asset, view);
        if (view.lodPolicy().perQuality()) {
            var quality = asset.qualityMinimumLod();
            minimum = quality.value();
            for (int value : quality.overrides().values()) minimum = Math.min(minimum, value);
        }
        int firstInline = asset.sampling().firstInlineLod();
        int floor = Math.clamp(minimum, Math.min(firstInline, asset.sampling().firstAvailableLod()), firstInline);
        double hiddenSize = normalizedSize * platform.hiddenPrimitiveStreamingScale();
        streaming.request(streamingLod(asset, view, floor, firstInline, hiddenSize), false);
        if (recentlyRendered) streaming.request(streamingLod(asset, view, floor, firstInline, normalizedSize), true);
    }

    private static int streamingLod(StaticMeshAsset asset, MeshView view, int floor, int lod, double wantedSize) {
        while (lod > floor && screenSize(asset, lod, view) * .5 < wantedSize) lod--;
        return lod;
    }

    public static int component(StaticMeshAsset asset, MeshView view, double distanceSquared, float radius, float factor) {
        float projected = view.screenMultiple() * radius;
        float screenRadiusSquared = projected * projected / Math.max(1, (float)distanceSquared * view.perspective());
        float scale = factor * view.lodDistanceFactor();
        int minimum = minimum(asset, view);
        for (int lod = asset.lods().size() - 1; lod >= 0; lod--) {
            float size = screenSize(asset, lod, view) * scale * .5f;
            if (size * size > screenRadiusSquared) return Math.max(lod, minimum);
        }
        return minimum;
    }

    public record ParticleRange(int first, int end) {
        public int drawCount() { return Math.max(0, end - first); }
    }

    public static ParticleRange particleRange(StaticMeshAsset asset, MeshRendererSettings.Entry entry, MeshView view) {
        int minimum = minimum(asset, view);
        int end = asset.lods().size();
        return entry.useLodRange()
                ? new ParticleRange(Math.max(minimum, entry.minimumLod()), Math.max(end, entry.maximumLod()))
                : new ParticleRange(minimum, end);
    }

    public static float particleLowerScreenSize(StaticMeshAsset asset, ParticleRange range, int lod, MeshView view) {
        return lod < range.end() - 1 && lod + 1 < asset.lods().size() ? screenSize(asset, lod + 1, view) * .5f : 0;
    }

}
