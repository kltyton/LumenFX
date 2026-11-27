package com.kltyton.lumenfx.runtime.gl;

import com.kltyton.lumenfx.runtime.bridge.McResourceBridge;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.system.MemoryUtil;

/**
 * Uploads and caches 2D OpenGL textures decoded via {@link McResourceBridge}.
 * Always resets and restores OpenGL pixel-unpack state ({@code GL_PIXEL_UNPACK_BUFFER},
 * {@code GL_UNPACK_ROW_LENGTH}, {@code GL_UNPACK_SKIP_PIXELS}, {@code GL_UNPACK_SKIP_ROWS},
 * {@code GL_UNPACK_ALIGNMENT}) so Minecraft Blaze3D state cannot cause out-of-bounds driver reads.
 */
public final class DirectGlTextureCache {
    private final Map<String, Integer> textures = new HashMap<>();
    private int whiteTexture;

    public int resolve(@Nullable String textureId, @Nullable McResourceBridge bridge) {
        if (textureId == null || textureId.isBlank() || bridge == null) return whiteTexture();
        Integer cached = textures.get(textureId);
        if (cached != null) return cached;
        byte[] raw = bridge.readResource(textureId);
        if (raw == null && !textureId.endsWith(".png")) {
            raw = bridge.readResource(textureId + ".png");
        }
        if (raw == null) {
            int fallback = whiteTexture();
            textures.put(textureId, fallback);
            return fallback;
        }
        McResourceBridge.DecodedImage img = bridge.decodePng(raw);
        if (img == null || img.width() <= 0 || img.height() <= 0
                || img.rgba() == null || img.rgba().length < img.width() * img.height() * 4) {
            int fallback = whiteTexture();
            textures.put(textureId, fallback);
            return fallback;
        }
        int uploadWidth = img.width();
        int uploadHeight = img.height();
        byte[] uploadRgba = img.rgba();
        // Minecraft animated block/item strips (e.g. 16x80 sea_lantern.png + .png.mcmeta) store frames vertically.
        // Extract frame 0 so standalone mesh/particle draws do not squash all animation frames onto one face.
        if (uploadHeight > uploadWidth && uploadHeight % uploadWidth == 0 && hasAnimationMcmeta(textureId, bridge)) {
            int frameBytes = Math.multiplyExact(Math.multiplyExact(uploadWidth, uploadWidth), 4);
            if (uploadRgba.length >= frameBytes) {
                byte[] frame0 = new byte[frameBytes];
                System.arraycopy(uploadRgba, 0, frame0, 0, frameBytes);
                uploadHeight = uploadWidth;
                uploadRgba = frame0;
            }
        }
        int id = uploadRgba(uploadWidth, uploadHeight, uploadRgba);
        textures.put(textureId, id);
        return id;
    }

    private static boolean hasAnimationMcmeta(String textureId, McResourceBridge bridge) {
        String pngId = textureId.endsWith(".png") ? textureId : (textureId + ".png");
        byte[] mcmeta = bridge.readResource(pngId + ".mcmeta");
        if (mcmeta == null || mcmeta.length == 0) return false;
        String text = new String(mcmeta, java.nio.charset.StandardCharsets.UTF_8);
        return text.contains("\"animation\"");
    }

    public int whiteTexture() {
        if (whiteTexture == 0) {
            whiteTexture = uploadRgba(1, 1, new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255});
        }
        return whiteTexture;
    }

    private static int uploadRgba(int width, int height, byte[] rgba) {
        int byteCount = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        if (rgba.length < byteCount) {
            throw new IllegalArgumentException("Decoded RGBA buffer too small: " + rgba.length + " < " + byteCount);
        }
        int prevPbo = GL11C.glGetInteger(GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int prevAlign = GL11C.glGetInteger(GL11C.GL_UNPACK_ALIGNMENT);
        int prevRowLen = GL11C.glGetInteger(GL11C.GL_UNPACK_ROW_LENGTH);
        int prevSkipPixels = GL11C.glGetInteger(GL11C.GL_UNPACK_SKIP_PIXELS);
        int prevSkipRows = GL11C.glGetInteger(GL11C.GL_UNPACK_SKIP_ROWS);
        int prevImgHeight = GL11C.glGetInteger(GL12C.GL_UNPACK_IMAGE_HEIGHT);

        int tex = GL11C.glGenTextures();
        ByteBuffer buf = MemoryUtil.memAlloc(byteCount);
        try {
            buf.put(rgba, 0, byteCount).flip();
            GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 1);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ROW_LENGTH, 0);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_PIXELS, 0);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_ROWS, 0);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_IMAGE_HEIGHT, 0);

            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, tex);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_REPEAT);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL11C.GL_REPEAT);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, width, height, 0,
                    GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, buf);
        } finally {
            MemoryUtil.memFree(buf);
            GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, prevPbo);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, prevAlign);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ROW_LENGTH, prevRowLen);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_PIXELS, prevSkipPixels);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_ROWS, prevSkipRows);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_IMAGE_HEIGHT, prevImgHeight);
        }
        return tex;
    }

    public void clear() {
        for (int tex : textures.values()) {
            if (tex != 0 && tex != whiteTexture) GL11C.glDeleteTextures(tex);
        }
        textures.clear();
        if (whiteTexture != 0) {
            GL11C.glDeleteTextures(whiteTexture);
            whiteTexture = 0;
        }
    }
}
