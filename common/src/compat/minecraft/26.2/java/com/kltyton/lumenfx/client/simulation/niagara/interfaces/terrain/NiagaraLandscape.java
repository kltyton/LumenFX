package com.kltyton.lumenfx.client.simulation.niagara.interfaces.terrain;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.parameter.NiagaraLandscapeInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraLandscapeInputs.Bounds;
import com.kltyton.lumenfx.api.client.parameter.NiagaraLandscapeInputs.Grid;
import com.kltyton.lumenfx.api.client.parameter.NiagaraLandscapeInputs.Landscape;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture.Feedback;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture.Lease;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture.Snapshot;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Landscape actor selection, collision snapshot and queued virtual-texture feedback for one physical interface. */
public final class NiagaraLandscape {
    private enum Mode { Default, Source, AttachParent }
    private record Request(Bounds bounds, int mip, int channels) { }
    private record Definition(String source, Mode mode, boolean virtualTextures, List<String> materials) {
        static Definition read(JsonObject data) {
            var materials = new ArrayList<String>();
            for (var value : data.getAsJsonArray("physical_materials")) materials.add(value.getAsString());
            return new Definition(data.get("source").getAsString(), Mode.valueOf(data.get("mode").getAsString()),
                    data.get("virtual_textures").getAsBoolean(), List.copyOf(materials));
        }
    }
    public record Usage(boolean color, boolean height, boolean normal, boolean physicalMaterial) {
        public static final Usage NONE = new Usage(false, false, false, false);
        public static final Usage ALL = new Usage(true, true, true, true);
        public static Usage read(@Nullable JsonObject data) {
            return data == null ? ALL : new Usage(data.get("base_color").getAsBoolean(), data.get("height").getAsBoolean(),
                    data.get("normal").getAsBoolean(), data.get("material").getAsBoolean());
        }
        public Usage merge(Usage other) {
            return new Usage(color || other.color, height || other.height, normal || other.normal, physicalMaterial || other.physicalMaterial);
        }
    }
    public record Frame(@Nullable Grid grid, @Nullable Snapshot color, @Nullable Snapshot height, @Nullable Snapshot normal,
                        List<Lease> leases) implements AutoCloseable {
        @Override public void close() { leases.forEach(Lease::close); }
    }

    private final Definition definition;
    private final List<Request> requests = new ArrayList<>();
    private @Nullable Landscape landscape;
    private @Nullable NiagaraRuntimeVirtualTexture color, height, normal;
    private @Nullable Grid grid;
    private Usage usage = Usage.NONE;

    public NiagaraLandscape(JsonObject definition) { this.definition = Definition.read(definition); }
    public void usage(Usage value) { usage = usage.merge(value); }
    public void gpuUse() { if (usage.equals(Usage.NONE)) usage = Usage.ALL; }

    public void begin(NiagaraLandscapeInputs inputs, Bounds bounds) {
        landscape = select(inputs, bounds);
        color = height = normal = null;
        grid = null;
        if (landscape == null) return;
        if (definition.virtualTextures()) {
            for (var texture : landscape.virtualTextures()) switch (texture.material()) {
                case WorldHeight -> { if (height == null) height = texture.texture(); }
                case BaseColor -> { if (color == null) color = texture.texture(); }
                case BaseColor_Normal_Roughness, BaseColor_Normal_Specular,
                     BaseColor_Normal_Specular_YCoCg, BaseColor_Normal_Specular_Mask_YCoCg -> {
                    if (color == null) color = texture.texture();
                    if (normal == null) normal = texture.texture();
                }
                default -> { }
            }
        }
        boolean needHeight = usage.height() && height == null || usage.normal() && normal == null || usage.color() && color == null;
        boolean needMaterial = usage.physicalMaterial() && !definition.materials().isEmpty();
        var region = landscape.bounds().overlap(bounds);
        if (region != null && (needHeight || needMaterial))
            grid = landscape.collision().capture(region, definition.materials(), needHeight, needMaterial);
    }

    private @Nullable Landscape select(NiagaraLandscapeInputs inputs, Bounds bounds) {
        if (definition.mode() != Mode.AttachParent) {
            var explicit = inputs.find(definition.source());
            if (explicit != null) return explicit;
        }
        if (landscape != null) {
            var current = inputs.find(landscape.identity());
            if (current != null && current.bounds().overlaps(bounds)) return current;
        }
        for (var candidate : inputs.landscapes()) if (candidate.bounds().overlaps(bounds)) return candidate;
        return null;
    }

    public synchronized void feedback(float minX, float minY, float maxX, float maxY, int mip, int channels) {
        if (landscape == null || color == null && height == null && normal == null || channels == 0
                || !Float.isFinite(minX) || !Float.isFinite(minY) || !Float.isFinite(maxX) || !Float.isFinite(maxY)
                || maxX < minX || maxY < minY) return;
        var region = landscape.bounds().overlap(new Bounds(minX, minY, maxX, maxY));
        if (region != null) requests.add(new Request(region, mip, channels));
    }

    public Frame publish() {
        var leases = new IdentityHashMap<NiagaraRuntimeVirtualTexture, Lease>();
        try {
            Snapshot baseColor = acquire(leases, color);
            Snapshot worldHeight = acquire(leases, height);
            Snapshot worldNormal = acquire(leases, normal);
            for (var request : requests) {
                if ((request.channels() & 1) != 0) submit(color, baseColor, request);
                if ((request.channels() & 2) != 0) submit(height, worldHeight, request);
                if ((request.channels() & 4) != 0) submit(normal, worldNormal, request);
            }
            requests.clear();
            return new Frame(grid, baseColor, worldHeight, worldNormal, List.copyOf(leases.values()));
        } catch (RuntimeException | Error failure) {
            leases.values().forEach(Lease::close);
            throw failure;
        }
    }

    public void flushFeedback() {
        if (!requests.isEmpty()) publish().close();
    }

    private static @Nullable Snapshot acquire(Map<NiagaraRuntimeVirtualTexture, Lease> leases, @Nullable NiagaraRuntimeVirtualTexture texture) {
        if (texture == null) return null;
        var lease = leases.computeIfAbsent(texture, NiagaraRuntimeVirtualTexture::acquire);
        return lease == null ? null : lease.snapshot();
    }

    private static void submit(@Nullable NiagaraRuntimeVirtualTexture texture, @Nullable Snapshot view, Request request) {
        if (texture == null || view == null || view.layers().isEmpty()) return;
        var region = request.bounds();
        var minimum = new Vec3(region.minX(), region.minY(), 0).subtract(view.origin());
        var maximum = new Vec3(region.maxX(), region.maxY(), 0).subtract(view.origin());
        texture.request(new Feedback(clamp(minimum.dot(view.u())), clamp(minimum.dot(view.v())),
                clamp(maximum.dot(view.u())), clamp(maximum.dot(view.v())), request.mip()));
    }

    private static double clamp(double value) { return Math.clamp(value, 0.0, 1.0); }
}
