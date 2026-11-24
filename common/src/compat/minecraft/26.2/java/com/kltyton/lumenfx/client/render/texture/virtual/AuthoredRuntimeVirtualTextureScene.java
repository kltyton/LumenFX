package com.kltyton.lumenfx.client.render.texture.virtual;

import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTextureMaterialPass;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePageRenderer.Request;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePrimitive;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePrimitive.Batch;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePrimitive.Bounds;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePrimitive.Registration;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePrimitive.Settings;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;

/** Routes registered scene primitives by source RVT identity and the current orthographic page view. */
public final class AuthoredRuntimeVirtualTextureScene implements AutoCloseable {
    private record Draw(int priority, Batch batch) { }
    private final ClientLevel level;
    private final BiConsumer<String, AABB> invalidate;
    private final BooleanSupplier hidePrimitives;
    private final Set<Primitive> primitives = new LinkedHashSet<>();
    private boolean closed;

    public AuthoredRuntimeVirtualTextureScene(ClientLevel level, BiConsumer<String, AABB> invalidate, BooleanSupplier hidePrimitives) {
        this.level = level;
        this.invalidate = invalidate;
        this.hidePrimitives = hidePrimitives;
    }

    public Registration register(Settings settings, RuntimeVirtualTexturePrimitive geometry, Bounds bounds) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("RVT scene is closed");
        var primitive = new Primitive(settings, Objects.requireNonNull(geometry), Objects.requireNonNull(bounds));
        primitives.add(primitive);
        primitive.invalidate(bounds);
        return primitive;
    }

    public RuntimeVirtualTextureMaterialPass materialPass(String source) {
        String target = SourceNames.canonical(source);
        return new RuntimeVirtualTextureSceneWriter(new RuntimeVirtualTextureSceneWriter.Scene() {
            @Override public boolean ready(ClientLevel level) { return !closed && level == AuthoredRuntimeVirtualTextureScene.this.level; }
            @Override public List<RuntimeVirtualTextureSceneWriter.Draw> prepare(ClientLevel level, Request request, CommandEncoder encoder) {
                return AuthoredRuntimeVirtualTextureScene.this.prepare(target, request, encoder);
            }
        }, new Vector4f(), new Vector4f());
    }

    private List<RuntimeVirtualTextureSceneWriter.Draw> prepare(String target, Request request, CommandEncoder encoder) {
        var selected = new ArrayList<Draw>();
        for (var primitive : primitives) {
            if (!primitive.settings.targets().contains(target) || !intersects(primitive.bounds, request)) continue;
            var lods = primitive.geometry.staticLods();
            int lod = -1;
            if (lods != null) {
                var page = request.view();
                double areaRatio = 2 * primitive.bounds.radius() / page.halfWidth();
                if (primitive.settings.minCoverage() == 0
                        ? page.maxMip() - page.mip() < primitive.settings.skipMips()
                        : Math.floor(areaRatio * page.pixelSize()) < (1 << primitive.settings.minCoverage())) continue;
                int min = Math.max(lods.minimum(), lods.currentFirst());
                int max = Math.max(lods.maximum(), lods.currentFirst());
                lod = areaRatio > 0 ? Math.clamp(primitive.settings.lodBias() - (int)Math.floor(Math.log(areaRatio) / Math.log(2)), min, max) : max;
            }
            for (var batch : primitive.geometry.prepare(request, lod, encoder))
                selected.add(new Draw(primitive.settings.sortPriority(), batch));
        }
        selected.sort(Comparator.comparingInt(Draw::priority).thenComparingInt(value -> value.batch().meshId()));
        return selected.stream().map(value -> new RuntimeVirtualTextureSceneWriter.Draw(value.batch().pipeline(), value.batch().draw())).toList();
    }

    private static boolean intersects(Bounds bounds, Request request) {
        var page = request.view();
        var volume = request.volume();
        Vec3 delta = bounds.center().subtract(page.camera());
        double x = along(delta, volume.xAxis()), y = along(delta, volume.yAxis()), z = -along(delta, volume.zAxis());
        double radius = bounds.radius();
        return Math.abs(x) <= page.halfWidth() + radius && Math.abs(y) <= page.halfHeight() + radius
                && z + radius >= 0 && z - radius <= page.depth();
    }

    private static double along(Vec3 delta, Vec3 axis) { return delta.dot(axis) / axis.length(); }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        closed = true;
        primitives.clear();
    }

    private final class Primitive implements Registration {
        final Settings settings;
        final RuntimeVirtualTexturePrimitive geometry;
        Bounds bounds;

        Primitive(Settings settings, RuntimeVirtualTexturePrimitive geometry, Bounds bounds) {
            this.settings = settings;
            this.geometry = geometry;
            this.bounds = bounds;
        }

        @Override public void changed(Bounds bounds) {
            RenderSystem.assertOnRenderThread();
            if (!primitives.contains(this)) throw new IllegalStateException("RVT primitive registration is closed");
            Objects.requireNonNull(bounds);
            invalidate(this.bounds);
            if (!this.bounds.equals(bounds)) invalidate(bounds);
            this.bounds = bounds;
        }

        @Override public boolean drawInMainPass() {
            RenderSystem.assertOnRenderThread();
            return !primitives.contains(this) || settings.drawInMainPass(hidePrimitives.getAsBoolean());
        }

        @Override public void close() {
            RenderSystem.assertOnRenderThread();
            if (primitives.remove(this)) invalidate(bounds);
        }

        void invalidate(Bounds bounds) {
            var center = bounds.center();
            double r = bounds.radius();
            var region = new AABB(center.x - r, center.y - r, center.z - r, center.x + r, center.y + r, center.z + r);
            for (var target : settings.targets()) invalidate.accept(target, region);
        }
    }
}
