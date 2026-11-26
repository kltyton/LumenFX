package com.lowdragmc.photon.client.resource;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generates lightweight procedural fallback textures for built-in {@code photon:textures/...} paths
 * when no resource pack provides an explicit PNG, avoiding bundling ~1.9 MB of demo textures in the JAR.
 */
public final class PhotonProceduralTextures {
    private static final Map<Identifier, DynamicTexture> FALLBACKS = new ConcurrentHashMap<>();

    private PhotonProceduralTextures() {
    }

    public static boolean isMissingBuiltin(Identifier location) {
        if (location == null || !"photon".equals(location.getNamespace())) {
            return false;
        }
        String path = location.getPath();
        if (!path.startsWith("textures/")) {
            return false;
        }
        var mc = Minecraft.getInstance();
        return mc != null && mc.getResourceManager().getResource(location).isEmpty();
    }

    public static AbstractTexture resolve(TextureManager manager, Identifier location) {
        if (!isMissingBuiltin(location)) {
            return manager.getTexture(location);
        }
        DynamicTexture existing = FALLBACKS.get(location);
        if (existing != null) {
            return existing;
        }
        DynamicTexture created = createProcedural(location);
        DynamicTexture prior = FALLBACKS.putIfAbsent(location, created);
        if (prior != null) {
            created.close();
            return prior;
        }
        manager.register(location, created);
        return created;
    }

    public static void clear() {
        var mc = Minecraft.getInstance();
        var manager = mc != null ? mc.getTextureManager() : null;
        for (var entry : FALLBACKS.entrySet()) {
            if (manager != null) {
                manager.release(entry.getKey());
            } else {
                entry.getValue().close();
            }
        }
        FALLBACKS.clear();
    }

    private static DynamicTexture createProcedural(Identifier location) {
        String name = location.getPath().toLowerCase(Locale.ROOT);
        if (name.contains("normal") || name.contains("_mv")) {
            return solid(2, 0xFF_FF8080); // ABGR: R=128, G=128, B=255, A=255 (flat tangent normal / zero motion)
        }
        if (name.contains("white")) {
            return solid(2, 0xFF_FFFFFF);
        }
        if (name.contains("ring") || name.contains("rune")) {
            return radialRing(32);
        }
        if (name.contains("laser") || name.contains("trail") || name.contains("tail") || name.contains("slash")) {
            return softBand(32);
        }
        if (name.contains("gradient_linear")) {
            return linearGradient(32);
        }
        if (name.contains("noise") || name.contains("smoke") || name.contains("cloud") || name.contains("flow")) {
            return softNoise(32, name.hashCode());
        }
        if (name.contains("circle") || name.contains("glow") || name.contains("spark") || name.contains("gradient_radial") || name.contains("matcap")) {
            return softCircle(32);
        }
        return softCircle(16);
    }

    private static DynamicTexture solid(int size, int abgr) {
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, size, size, false);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                image.setPixelABGR(x, y, abgr);
            }
        }
        return new DynamicTexture(() -> "photon_fallback_solid", image);
    }

    private static DynamicTexture softCircle(int size) {
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, size, size, false);
        float half = (size - 1) * 0.5f;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float dx = (x - half) / half;
                float dy = (y - half) / half;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                float a = Math.max(0f, 1f - d);
                a = a * a * (3f - 2f * a);
                int alpha = Math.clamp(Math.round(a * 255f), 0, 255);
                image.setPixelABGR(x, y, (alpha << 24) | 0x00FFFFFF);
            }
        }
        return new DynamicTexture(() -> "photon_fallback_circle", image);
    }

    private static DynamicTexture radialRing(int size) {
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, size, size, false);
        float half = (size - 1) * 0.5f;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float dx = (x - half) / half;
                float dy = (y - half) / half;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                float ring = Math.max(0f, 1f - Math.abs(d - 0.72f) / 0.24f);
                ring = ring * ring * (3f - 2f * ring);
                int alpha = Math.clamp(Math.round(ring * 255f), 0, 255);
                image.setPixelABGR(x, y, (alpha << 24) | 0x00FFFFFF);
            }
        }
        return new DynamicTexture(() -> "photon_fallback_ring", image);
    }

    private static DynamicTexture softBand(int size) {
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, size, size, false);
        float half = (size - 1) * 0.5f;
        for (int y = 0; y < size; y++) {
            float dy = Math.abs((y - half) / half);
            float v = Math.max(0f, 1f - dy);
            v = v * v;
            int alpha = Math.clamp(Math.round(v * 255f), 0, 255);
            int abgr = (alpha << 24) | 0x00FFFFFF;
            for (int x = 0; x < size; x++) {
                image.setPixelABGR(x, y, abgr);
            }
        }
        return new DynamicTexture(() -> "photon_fallback_band", image);
    }

    private static DynamicTexture linearGradient(int size) {
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, size, size, false);
        for (int x = 0; x < size; x++) {
            int g = Math.clamp(Math.round((x / (float) (size - 1)) * 255f), 0, 255);
            int abgr = (0xFF << 24) | (g << 16) | (g << 8) | g;
            for (int y = 0; y < size; y++) {
                image.setPixelABGR(x, y, abgr);
            }
        }
        return new DynamicTexture(() -> "photon_fallback_grad", image);
    }

    private static DynamicTexture softNoise(int size, int seed) {
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, size, size, false);
        float half = (size - 1) * 0.5f;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float u = x / (float) size * 4f;
                float v = y / (float) size * 4f;
                float n = valueNoise(u, v, seed) * 0.65f + valueNoise(u * 2f, v * 2f, seed ^ 0x517CC1B7) * 0.35f;
                float dx = (x - half) / half;
                float dy = (y - half) / half;
                float falloff = Math.max(0f, 1f - (float) Math.sqrt(dx * dx + dy * dy));
                int val = Math.clamp(Math.round(n * 255f), 0, 255);
                int alpha = Math.clamp(Math.round(n * falloff * 255f), 0, 255);
                image.setPixelABGR(x, y, (alpha << 24) | (val << 16) | (val << 8) | val);
            }
        }
        return new DynamicTexture(() -> "photon_fallback_noise", image);
    }

    private static float valueNoise(float x, float y, int seed) {
        int xi = (int) Math.floor(x);
        int yi = (int) Math.floor(y);
        float xf = x - xi;
        float yf = y - yi;
        float u = xf * xf * (3f - 2f * xf);
        float v = yf * yf * (3f - 2f * yf);
        float a = hash(xi & 3, yi & 3, seed);
        float b = hash((xi + 1) & 3, yi & 3, seed);
        float c = hash(xi & 3, (yi + 1) & 3, seed);
        float d = hash((xi + 1) & 3, (yi + 1) & 3, seed);
        return (a + (b - a) * u) * (1f - v) + (c + (d - c) * u) * v;
    }

    private static float hash(int x, int y, int seed) {
        int h = x * 374761393 + y * 668265263 + seed * 1442695041;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFF) / 65535f;
    }
}
