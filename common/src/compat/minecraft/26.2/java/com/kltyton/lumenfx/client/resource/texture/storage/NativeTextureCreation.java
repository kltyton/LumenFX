package com.kltyton.lumenfx.client.resource.texture.storage;

import java.util.function.Supplier;

/** Physical image/view format changes are limited to one host allocation scope. */
public final class NativeTextureCreation {
    private record VolumeImage(int depth, int usage, int flags, int imageFormat) { }
    private record Allocation(NativeTextureStorage storage, boolean sampleOnly, @org.jspecify.annotations.Nullable VolumeImage volume) { }
    private static final ThreadLocal<Allocation> ALLOCATION = new ThreadLocal<>();

    private NativeTextureCreation() { }

    public static <T> T create(NativeTextureStorage storage, Supplier<T> create) { return scoped(storage, true, null, create); }
    public static <T> T colorTarget(NativeTextureStorage storage, Supplier<T> create) {
        if (storage.format().compressed()) throw new IllegalArgumentException("Compressed storage cannot be a color attachment");
        return scoped(storage, false, null, create);
    }

    /** Supplies the native 3D image contract while the host Vulkan texture constructor owns allocation and release. */
    public static <T> T volume(NativeTextureStorage viewStorage, int depth, int usage, int flags, int imageFormat,
                               Supplier<T> create) {
        if (depth < 1 || usage == 0) throw new IllegalArgumentException("Invalid native volume image contract");
        return scoped(viewStorage, false, new VolumeImage(depth, usage, flags, imageFormat), create);
    }

    private static <T> T scoped(NativeTextureStorage storage, boolean sampleOnly,
                                @org.jspecify.annotations.Nullable VolumeImage volume, Supplier<T> create) {
        Allocation previous = ALLOCATION.get();
        ALLOCATION.set(new Allocation(storage, sampleOnly, volume));
        try { return create.get(); }
        finally { if (previous == null) ALLOCATION.remove(); else ALLOCATION.set(previous); }
    }

    public static NativeTextureStorage current() {
        Allocation allocation = ALLOCATION.get();
        return allocation == null ? null : allocation.storage();
    }
    public static int glFormat(int original) {
        Allocation allocation = ALLOCATION.get();
        return allocation == null ? original : allocation.storage().glFormat();
    }
    public static int imageFormat(int original) {
        Allocation allocation = ALLOCATION.get();
        return allocation == null ? original : allocation.volume() == null
                ? allocation.storage().vkFormat() : allocation.volume().imageFormat();
    }
    public static int imageUsage(int original) {
        Allocation allocation = ALLOCATION.get();
        return allocation == null ? original : allocation.volume() != null ? allocation.volume().usage()
                : allocation.sampleOnly() ? org.lwjgl.vulkan.VK12.VK_IMAGE_USAGE_SAMPLED_BIT
                        | org.lwjgl.vulkan.VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT : original;
    }
    public static int imageFlags(int original) {
        Allocation allocation = ALLOCATION.get();
        return allocation == null || allocation.volume() == null ? original : original | allocation.volume().flags();
    }
    public static int imageType(int original) {
        Allocation allocation = ALLOCATION.get();
        return allocation != null && allocation.volume() != null ? org.lwjgl.vulkan.VK10.VK_IMAGE_TYPE_3D : original;
    }
    public static int imageDepth(int original) {
        Allocation allocation = ALLOCATION.get();
        return allocation == null || allocation.volume() == null ? original : allocation.volume().depth();
    }
}
