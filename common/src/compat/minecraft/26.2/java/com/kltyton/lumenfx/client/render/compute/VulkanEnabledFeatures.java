package com.kltyton.lumenfx.client.render.compute;

/**
 * Records the optional {@code VkPhysicalDeviceFeatures} actually enabled on the logical Vulkan device
 * when {@code vkCreateDevice} succeeds.
 */
public final class VulkanEnabledFeatures {
    private static volatile boolean recorded;
    private static volatile boolean fragmentStoresAndAtomics;
    private static volatile boolean geometryShader;

    private VulkanEnabledFeatures() { }

    public static void record(boolean fragmentStoresAndAtomicsEnabled, boolean geometryShaderEnabled) {
        fragmentStoresAndAtomics = fragmentStoresAndAtomicsEnabled;
        geometryShader = geometryShaderEnabled;
        recorded = true;
    }

    public static boolean isRecorded() {
        return recorded;
    }

    public static boolean fragmentStoresAndAtomics() {
        return fragmentStoresAndAtomics;
    }

    public static boolean geometryShader() {
        return geometryShader;
    }

    public static void resetForTesting() {
        recorded = false;
        fragmentStoresAndAtomics = false;
        geometryShader = false;
    }
}
