package com.kltyton.lumenfx.client.render.mesh.nanite;

import com.kltyton.lumenfx.client.render.depth.HierarchicalSceneDepth;
import com.kltyton.lumenfx.client.render.light.shadow.PointShadowMatrices;
import com.kltyton.lumenfx.client.render.light.shadow.ShadowRasterViews;
import com.kltyton.lumenfx.client.render.light.shadow.directional.DirectionalShadowMatrices;
import com.kltyton.lumenfx.client.render.texture.ProjectedShadowDepthTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Keeps previous raw Nanite depths by light and projection, independent of reused scene shadow attachments. */
final class NaniteShadowHistory implements AutoCloseable {
    enum Projection { POINT, SPOT, DIRECTIONAL }
    record Key(long light, Projection projection, int view, int cascadeLimit) { }
    private static final class History {
        final HierarchicalSceneDepth depth = HierarchicalSceneDepth.fullPrecision();
        long frame = Long.MIN_VALUE;
        NaniteHierarchyCulling.@Nullable OcclusionView view;
    }

    private final Map<Key, History> histories = new HashMap<>();
    private final HierarchicalSceneDepth postDepth = HierarchicalSceneDepth.fullPrecision();
    private @Nullable ProjectedShadowDepthTarget raw;
    private long frame = Long.MIN_VALUE;

    static @Nullable Key key(ShadowRasterViews matrices, int index) {
        return switch (matrices) {
            case PointShadowMatrices point -> point.lightIdentity() == 0 ? null
                    : new Key(point.lightIdentity(), point.spot() ? Projection.SPOT : Projection.POINT, index, 0);
            case DirectionalShadowMatrices directional -> directional.lightIdentity() == 0 ? null
                    : new Key(directional.lightIdentity(), Projection.DIRECTIONAL, index, directional.cascadeLimit());
            default -> throw new IllegalArgumentException("Unsupported Nanite shadow projection " + matrices);
        };
    }

    void advance(long sequence) {
        if (frame == sequence) return;
        frame = sequence;
        var entries = histories.values().iterator();
        while (entries.hasNext()) {
            var history = entries.next();
            if (history.frame >= sequence - 1) continue;
            entries.remove();
            history.depth.close();
        }
    }

    NaniteHierarchyCulling.@Nullable OcclusionView previous(Key key, NaniteViewProjection current) {
        var history = histories.get(key);
        var view = history == null || history.frame != frame - 1 ? null : history.view;
        return view != null && view.width() == current.raster().width() && view.height() == current.raster().height()
                ? view : null;
    }

    ProjectedShadowDepthTarget beginDepth(CommandEncoder encoder, int size) {
        var target = raw;
        if (target == null || target.size() != size) {
            var replacement = new ProjectedShadowDepthTarget("LumenFX Nanite raw shadow depth", size);
            if (target != null) target.close();
            raw = target = replacement;
        }
        encoder.clearDepthTexture(target.face(0).texture(), 0);
        return target;
    }

    NaniteHierarchyCulling.OcclusionView post(CommandEncoder encoder, NaniteViewProjection current, GpuTextureView depth) {
        return current.occlusion(postDepth.buildFurthest(encoder, depth));
    }

    void capture(CommandEncoder encoder, Key key, NaniteViewProjection current, GpuTextureView depth) {
        var history = histories.computeIfAbsent(key, ignored -> new History());
        if (history.frame == frame) return;
        history.view = current.occlusion(history.depth.buildFurthest(encoder, depth));
        history.frame = frame;
    }

    @Override public void close() {
        try {
            if (raw != null) raw.close();
        } finally {
            raw = null;
            try { postDepth.close(); }
            finally {
                histories.values().forEach(history -> history.depth.close());
                histories.clear();
                frame = Long.MIN_VALUE;
            }
        }
    }
}
