package com.kltyton.lumenfx.runtime.bridge;

import java.util.Map;
import javax.annotation.Nullable;

/**
 * Version-independent Minecraft resource, world-query, and graphics environment bridge
 * implemented once per Minecraft generation under {@code src/compat/minecraft/<version>}.
 */
public interface McResourceBridge {
    record DecodedImage(int width, int height, byte[] rgba) { }

    String mcVersion();

    String loaderName();

    /**
     * Lists all resources under {@code assets/<ns>/<prefix>/} ending with {@code suffix}.
     * Keys are canonical {@code namespace:path} strings.
     */
    Map<String, byte[]> listResources(String prefix, String suffix);

    /** Reads raw bytes for a canonical {@code namespace:path} resource, or null if absent. */
    @Nullable byte[] readResource(String resourceId);

    /** Decodes a PNG payload into tightly packed RGBA8 bytes (top-to-bottom). */
    @Nullable DecodedImage decodePng(byte[] pngBytes);

    /** Returns true if the world block at {@code (x, y, z)} blocks particle motion. */
    boolean isSolidBlock(@Nullable Object level, double x, double y, double z);

    /** Samples packed Minecraft sky/block light {@code (sky << 16) | block} in {@code [0..240]} units. */
    int samplePackedLight(@Nullable Object level, double x, double y, double z);

    /** Returns true when a shader pipeline mod (Iris / Oculus) is installed. */
    boolean isShaderModLoaded();

    /** Returns true when a chunk renderer replacement (Sodium / Embeddium) is installed. */
    boolean isChunkRendererModLoaded();
}
