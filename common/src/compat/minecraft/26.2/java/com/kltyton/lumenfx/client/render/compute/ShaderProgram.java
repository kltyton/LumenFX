package com.kltyton.lumenfx.client.render.compute;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable preloaded compute shader and its exact device-interface contract. */
public final class ShaderProgram {
    private final String name;
    private final byte[] vulkanSpirv;
    private final byte[] openglSpirv;
    private final String vulkanEntryPoint;
    private final String openglEntryPoint;
    private final int workgroupX;
    private final int workgroupY;
    private final int workgroupZ;
    private final List<ShaderBinding> bindings;
    private final ShaderCapabilities capabilities;

    private ShaderProgram(final Builder builder) {
        this.name = builder.name;
        this.vulkanSpirv = builder.vulkanSpirv.clone();
        this.openglSpirv = builder.openglSpirv.clone();
        this.vulkanEntryPoint = builder.vulkanEntryPoint;
        this.openglEntryPoint = builder.openglEntryPoint;
        this.workgroupX = builder.workgroupX;
        this.workgroupY = builder.workgroupY;
        this.workgroupZ = builder.workgroupZ;
        this.bindings = List.copyOf(builder.bindings);
        this.capabilities = builder.capabilities;
    }

    public static Builder builder(final String name) {
        return new Builder(name);
    }

    public String name() {
        return this.name;
    }

    /** Read-only view of the Vulkan SPIR-V; kernel creation copies it into a direct buffer. */
    public ByteBuffer vulkanSpirv() {
        return ByteBuffer.wrap(this.vulkanSpirv).asReadOnlyBuffer();
    }

    /** Read-only view of the OpenGL-environment SPIR-V; GL specialization copies it directly. */
    public ByteBuffer openglSpirv() {
        return ByteBuffer.wrap(this.openglSpirv).asReadOnlyBuffer();
    }

    public String vulkanEntryPoint() {
        return this.vulkanEntryPoint;
    }

    public String openglEntryPoint() {
        return this.openglEntryPoint;
    }

    public int workgroupX() {
        return this.workgroupX;
    }

    public int workgroupY() {
        return this.workgroupY;
    }

    public int workgroupZ() {
        return this.workgroupZ;
    }

    public List<ShaderBinding> bindings() {
        return this.bindings;
    }

    public ShaderCapabilities capabilities() {
        return this.capabilities;
    }

    public boolean hasStorageImages() {
        for (ShaderBinding binding : this.bindings) {
            if (binding instanceof ShaderBinding.StorageImage) {
                return true;
            }
        }
        return false;
    }

    public boolean hasStorageBuffers() {
        for (ShaderBinding binding : this.bindings) {
            if (binding instanceof ShaderBinding.StorageBuffer) {
                return true;
            }
        }
        return false;
    }

    byte[] vulkanSpirvBytes() {
        return this.vulkanSpirv;
    }

    byte[] openglSpirvBytes() {
        return this.openglSpirv;
    }

    public static final class Builder {
        private final String name;
        private byte[] vulkanSpirv;
        private byte[] openglSpirv;
        private String vulkanEntryPoint;
        private String openglEntryPoint;
        private int workgroupX = 1;
        private int workgroupY = 1;
        private int workgroupZ = 1;
        private final List<ShaderBinding> bindings = new ArrayList<>();
        private final Set<String> bindingNames = new HashSet<>();
        private final Map<Integer, ShaderBinding> descriptorBindings = new HashMap<>();
        private final Set<Integer> samplerBindings = new HashSet<>();
        private ShaderCapabilities capabilities = ShaderCapabilities.NONE;

        private Builder(final String name) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("ShaderProgram name must not be blank");
            }
            this.name = name;
        }

        public Builder vulkanSpirv(final byte[] spirv) {
            this.vulkanSpirv = requireSpirv(spirv, "Vulkan SPIR-V");
            return this;
        }

        public Builder openglSpirv(final byte[] spirv) {
            this.openglSpirv = requireSpirv(spirv, "OpenGL SPIR-V");
            return this;
        }

        /** Original DXC {@code -E} entry point used by the Vulkan module. */
        public Builder vulkanEntryPoint(final String entryPoint) {
            this.vulkanEntryPoint = requireEntryPoint(entryPoint, "Vulkan");
            return this;
        }

        /** Entry point in the OpenGL-environment SPIR-V (usually {@code main}). */
        public Builder openglEntryPoint(final String entryPoint) {
            this.openglEntryPoint = requireEntryPoint(entryPoint, "OpenGL");
            return this;
        }

        public Builder workgroup(final int x, final int y, final int z) {
            if (x <= 0 || y <= 0 || z <= 0) {
                throw new IllegalArgumentException("Compute workgroup size must be positive");
            }
            this.workgroupX = x;
            this.workgroupY = y;
            this.workgroupZ = z;
            return this;
        }

        public Builder binding(final ShaderBinding binding) {
            final ShaderBinding checked = Objects.requireNonNull(binding, "binding");
            if (!this.bindingNames.add(checked.name())) {
                throw new IllegalArgumentException("Duplicate shader binding " + checked.name());
            }
            claimDescriptorBinding(checked);
            if (checked instanceof ShaderBinding.SampledTexture sampled
                    && sampled.form() == ShaderBinding.DescriptorForm.SEPARATE_IMAGE_AND_SAMPLER) {
                claimSamplerBinding(sampled.samplerDescriptorBinding(), checked.name());
            }
            this.bindings.add(checked);
            return this;
        }

        private void claimDescriptorBinding(final ShaderBinding binding) {
            final int number = binding.descriptorBinding();
            if (this.samplerBindings.contains(number)) {
                throw new IllegalArgumentException(
                        "Image descriptor binding " + number + " collides with a sampler binding for "
                                + binding.name()
                );
            }
            final ShaderBinding existing = this.descriptorBindings.get(number);
            if (existing == null) {
                this.descriptorBindings.put(number, binding);
                return;
            }
            if (!canShareImage(existing, binding)) {
                throw new IllegalArgumentException(
                        "Duplicate Vulkan descriptor binding " + number + " for " + binding.name()
                );
            }
        }

        private void claimSamplerBinding(final int binding, final String name) {
            if (this.descriptorBindings.containsKey(binding)) {
                throw new IllegalArgumentException(
                        "Sampler descriptor binding " + binding + " collides with an image/buffer binding for " + name
                );
            }
            // DXC may share one SamplerState across several Texture2D declarations.
            this.samplerBindings.add(binding);
        }

        private static boolean canShareImage(final ShaderBinding existing, final ShaderBinding incoming) {
            if (!(existing instanceof ShaderBinding.SampledTexture first)
                    || !(incoming instanceof ShaderBinding.SampledTexture second)) {
                return false;
            }
            if (first.form() == ShaderBinding.DescriptorForm.COMBINED_IMAGE_SAMPLER
                    || second.form() == ShaderBinding.DescriptorForm.COMBINED_IMAGE_SAMPLER) {
                return false;
            }
            return first.format() == second.format() && first.array() == second.array() && first.volume() == second.volume()
                    && first.dynamicFloatFormat() == second.dynamicFloatFormat();
        }

        public Builder capabilities(final ShaderCapabilities capabilities) {
            this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
            return this;
        }

        public ShaderProgram build() {
            if (!this.capabilities.storageImageWriteWithoutFormat() && this.bindings.stream().anyMatch(binding ->
                    binding instanceof ShaderBinding.StorageImage image && image.dynamicFloatFormat())) {
                throw new IllegalStateException("Formatless storage image has no declared write capability: " + this.name);
            }
            if (this.vulkanSpirv == null) {
                throw new IllegalStateException("Missing Vulkan SPIR-V for " + this.name);
            }
            if (this.openglSpirv == null) {
                throw new IllegalStateException("Missing OpenGL SPIR-V for " + this.name);
            }
            if (this.vulkanEntryPoint == null) {
                throw new IllegalStateException("Missing Vulkan entry point for " + this.name);
            }
            if (this.openglEntryPoint == null) {
                throw new IllegalStateException("Missing OpenGL entry point for " + this.name);
            }
            return new ShaderProgram(this);
        }

        private static String requireEntryPoint(final String entryPoint, final String environment) {
            if (entryPoint == null || entryPoint.isBlank()) {
                throw new IllegalArgumentException(environment + " entry point must not be blank");
            }
            return entryPoint;
        }

        private static byte[] requireSpirv(final byte[] spirv, final String description) {
            Objects.requireNonNull(spirv, description);
            if (spirv.length == 0 || (spirv.length & 3) != 0) {
                throw new IllegalArgumentException(description + " length must be a non-zero multiple of 4");
            }
            return spirv.clone();
        }
    }
}
