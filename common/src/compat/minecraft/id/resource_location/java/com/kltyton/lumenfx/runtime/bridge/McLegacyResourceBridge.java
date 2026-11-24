package com.kltyton.lumenfx.runtime.bridge;

import com.kltyton.lumenfx.platform.LumenPlatform;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;

/** Minecraft 1.20.1 and 1.21.1 resource, world-query, and image decoding bridge. */
public final class McLegacyResourceBridge implements McResourceBridge {
    private final String mcVersion;
    private final String loaderName;

    public McLegacyResourceBridge(String mcVersion, String loaderName) {
        this.mcVersion = mcVersion;
        this.loaderName = loaderName;
    }

    @Override public String mcVersion() { return mcVersion; }
    @Override public String loaderName() { return loaderName; }

    @Override
    public Map<String, byte[]> listResources(String prefix, String suffix) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return Map.of();
        ResourceManager rm = mc.getResourceManager();
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, Resource> entry : rm.listResources(prefix, id -> id.getPath().endsWith(suffix)).entrySet()) {
            try (InputStream in = entry.getValue().open()) {
                out.put(entry.getKey().toString(), in.readAllBytes());
            } catch (IOException ignored) { }
        }
        return out;
    }

    @Override
    public @Nullable byte[] readResource(String resourceId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return null;
        ResourceLocation id = ResourceLocation.tryParse(resourceId);
        if (id == null) return null;
        Optional<Resource> res = mc.getResourceManager().getResource(id);
        if (res.isEmpty()) return null;
        try (InputStream in = res.get().open()) {
            return in.readAllBytes();
        } catch (IOException ignored) {
            return null;
        }
    }

    @Override
    public @Nullable DecodedImage decodePng(byte[] pngBytes) {
        try (NativeImage img = NativeImage.read(new ByteArrayInputStream(pngBytes))) {
            int w = img.getWidth();
            int h = img.getHeight();
            byte[] rgba = new byte[w * h * 4];
            int idx = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int abgr = img.getPixelRGBA(x, y);
                    rgba[idx++] = (byte) (abgr & 0xFF);
                    rgba[idx++] = (byte) ((abgr >>> 8) & 0xFF);
                    rgba[idx++] = (byte) ((abgr >>> 16) & 0xFF);
                    rgba[idx++] = (byte) ((abgr >>> 24) & 0xFF);
                }
            }
            return new DecodedImage(w, h, rgba);
        } catch (IOException ignored) {
            return null;
        }
    }

    @Override
    public boolean isSolidBlock(@Nullable Object levelObj, double x, double y, double z) {
        if (!(levelObj instanceof Level level)) return false;
        BlockPos pos = BlockPos.containing(x, y, z);
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    @Override
    public int samplePackedLight(@Nullable Object levelObj, double x, double y, double z) {
        if (!(levelObj instanceof Level level)) return 0x00F000F0;
        BlockPos pos = BlockPos.containing(x, y, z);
        int block = level.getBrightness(LightLayer.BLOCK, pos);
        int sky = level.getBrightness(LightLayer.SKY, pos);
        return ((sky << 4) << 16) | (block << 4);
    }

    @Override
    public boolean isShaderModLoaded() {
        return LumenPlatform.isModLoaded("iris") || LumenPlatform.isModLoaded("oculus");
    }

    @Override
    public boolean isChunkRendererModLoaded() {
        return LumenPlatform.isModLoaded("sodium") || LumenPlatform.isModLoaded("embeddium");
    }
}
