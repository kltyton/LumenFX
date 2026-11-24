package com.kltyton.lumenfx.tools.shader;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.Spv;

final class BindingPlanner {
    record PlanBinding(
            String kind,
            String name,
            String sourceName,
            String format,
            String formatRef,
            String formatRule,
            String descriptorForm,
            int descriptorSet,
            int descriptorBinding,
            int glBinding,
            String access,
            boolean array
    ) {
        JsonObject toJson() {
            final JsonObject json = new JsonObject();
            json.addProperty("kind", this.kind);
            json.addProperty("name", this.name);
            if (!this.name.equals(this.sourceName)) {
                json.addProperty("sourceName", this.sourceName);
            }
            if (this.format != null) {
                json.addProperty("format", this.format);
                json.addProperty("formatRef", this.formatRef);
                if (this.formatRule != null) {
                    json.addProperty("formatRule", this.formatRule);
                }
            }
            if (this.descriptorForm != null) {
                json.addProperty("descriptorForm", this.descriptorForm);
            }
            json.addProperty("descriptorSet", this.descriptorSet);
            json.addProperty("descriptorBinding", this.descriptorBinding);
            if (this.access != null) {
                json.addProperty("access", this.access);
            }
            if ("storageImage".equals(this.kind)) {
                json.addProperty("array", this.array);
            }
            json.addProperty("glBinding", this.glBinding);
            return json;
        }
    }

    record SampledBinding(
            String name,
            String imageName,
            String samplerName,
            String format,
            String formatRef,
            String formatRule,
            String descriptorForm,
            int descriptorSet,
            int descriptorBinding,
            int samplerDescriptorBinding,
            int glBinding,
            boolean array,
            boolean volume,
            boolean cube,
            boolean dummySampler,
            boolean sharedImage,
            int imageId,
            int samplerId,
            int combinedId
    ) {
        JsonObject toJson() {
            final JsonObject json = new JsonObject();
            json.addProperty("kind", "sampledTexture");
            json.addProperty("name", this.name);
            json.addProperty("image", this.imageName);
            if (this.samplerName != null) {
                json.addProperty("sampler", this.samplerName);
            }
            json.addProperty("format", this.format);
            json.addProperty("formatRef", this.formatRef);
            if (this.formatRule != null) {
                json.addProperty("formatRule", this.formatRule);
            }
            json.addProperty("descriptorForm", this.descriptorForm);
            json.addProperty("descriptorSet", this.descriptorSet);
            json.addProperty("descriptorBinding", this.descriptorBinding);
            json.addProperty("samplerDescriptorBinding", this.samplerDescriptorBinding);
            json.addProperty("glBinding", this.glBinding);
            json.addProperty("array", this.array);
            json.addProperty("volume", this.volume);
            json.addProperty("cube", this.cube);
            json.addProperty("dummySampler", this.dummySampler);
            json.addProperty("sharedImage", this.sharedImage);
            return json;
        }
    }

    record SamplerPair(
            String image,
            String imageUniform,
            int imageBinding,
            String sampler,
            int samplerBinding,
            int descriptorSet,
            int glBinding,
            boolean dummy,
            boolean shared
    ) {
        JsonObject toJson() {
            final JsonObject json = new JsonObject();
            json.addProperty("image", this.image);
            json.addProperty("imageUniform", this.imageUniform);
            json.addProperty("imageBinding", this.imageBinding);
            if (this.sampler != null) {
                json.addProperty("sampler", this.sampler);
            }
            json.addProperty("samplerBinding", this.samplerBinding);
            json.addProperty("descriptorSet", this.descriptorSet);
            json.addProperty("glBinding", this.glBinding);
            json.addProperty("dummy", this.dummy);
            json.addProperty("shared", this.shared);
            return json;
        }
    }

    record SamplerPlan(String name, int descriptorSet, int descriptorBinding, boolean shared) {
        JsonObject toJson() {
            final JsonObject json = new JsonObject();
            json.addProperty("name", this.name);
            json.addProperty("descriptorSet", this.descriptorSet);
            json.addProperty("descriptorBinding", this.descriptorBinding);
            json.addProperty("shared", this.shared);
            return json;
        }
    }

    record Plan(
            List<PlanBinding> bindings,
            List<SampledBinding> sampledBindings,
            List<SamplerPlan> samplers,
            List<SamplerPair> samplerPairs,
            Map<String, Integer> glBindings,
            Map<Integer, SampledBinding> combinedBindings,
            Map<Integer, SampledBinding> imageOnlyBindings
    ) {
    }

    private BindingPlanner() {
    }

    static Plan plan(
            final SpirvReflection.Reflection reflection,
            final Map<String, ResolvedFormat> formats
    ) {
        final List<PlanBinding> bindings = new ArrayList<>();
        final List<SampledBinding> sampledBindings = new ArrayList<>();
        final List<SamplerPlan> samplerPlans = new ArrayList<>();
        final List<SamplerPair> pairs = new ArrayList<>();
        final Map<String, Integer> glBindings = new LinkedHashMap<>();
        final Map<Integer, SampledBinding> combinedBindings = new LinkedHashMap<>();
        final Map<Integer, SampledBinding> imageOnlyBindings = new LinkedHashMap<>();

        int uniformBinding = 0;
        for (SpirvReflection.Resource resource : reflection.uniformBuffers()) {
            final String canonical = GlslTranslator.canonicalBlockName(resource.name());
            checkSet(resource, "uniform buffer");
            bindings.add(new PlanBinding(
                    "uniformBuffer",
                    canonical,
                    resource.name(),
                    null,
                    null,
                    null,
                    null,
                    resource.descriptorSet(),
                    resource.descriptorBinding(),
                    uniformBinding,
                    null,
                    false
            ));
            glBindings.put(resource.name(), uniformBinding);
            glBindings.put(canonical, uniformBinding);
            uniformBinding++;
        }

        int storageBufferBinding = 0;
        for (SpirvReflection.Resource resource : reflection.storageBuffers()) {
            checkSet(resource, "storage buffer");
            bindings.add(new PlanBinding(
                    "storageBuffer",
                    resource.name(),
                    resource.name(),
                    null,
                    null,
                    null,
                    null,
                    resource.descriptorSet(),
                    resource.descriptorBinding(),
                    storageBufferBinding,
                    access(resource.nonWritable(), resource.nonReadable()),
                    false
            ));
            glBindings.put(resource.name(), storageBufferBinding);
            storageBufferBinding++;
        }

        final Map<Integer, SpirvReflection.Resource> samplersById = new HashMap<>();
        final Map<String, Integer> samplerUseCount = new HashMap<>();
        for (SpirvReflection.Resource sampler : reflection.separateSamplers()) {
            samplersById.put(sampler.id(), sampler);
        }
        final Map<Integer, List<SpirvReflection.CombinedSampler>> combinedByImage = new HashMap<>();
        final Map<String, List<SpirvReflection.CombinedSampler>> combinedByImageName = new HashMap<>();
        for (SpirvReflection.CombinedSampler combined : reflection.combinedSamplers()) {
            combinedByImage.computeIfAbsent(combined.imageId(), ignored -> new ArrayList<>()).add(combined);
            if (combined.imageName() != null) {
                combinedByImageName.computeIfAbsent(combined.imageName(), ignored -> new ArrayList<>()).add(combined);
            }
            if (combined.samplerName() != null) {
                samplerUseCount.merge(combined.samplerName(), 1, Integer::sum);
            }
        }

        final Set<Integer> sharedImages = new HashSet<>();
        final Map<Integer, List<SpirvReflection.CombinedSampler>> usesByImage = new HashMap<>();
        for (SpirvReflection.Resource image : allSampledImages(reflection)) {
            final List<SpirvReflection.CombinedSampler> combinedUses = combinedByImage.getOrDefault(
                    image.id(), combinedByImageName.getOrDefault(image.name(), List.of())
            );
            final List<SpirvReflection.CombinedSampler> uses = new ArrayList<>();
            final Set<Integer> seenSamplers = new LinkedHashSet<>();
            for (SpirvReflection.CombinedSampler combined : combinedUses) {
                if (seenSamplers.add(combined.samplerId())) {
                    uses.add(combined);
                }
            }
            usesByImage.put(image.id(), uses);
            if (uses.size() > 1) {
                sharedImages.add(image.id());
            }
        }

        int sampledBinding = 0;
        final Set<Integer> seenImages = new HashSet<>();
        for (SpirvReflection.Resource image : allSampledImages(reflection)) {
            if (!seenImages.add(image.id())) {
                continue;
            }
            checkSet(image, "sampled image");
            if (image.imageDimension() == Spv.SpvDimBuffer) {
                throw new BakeException(
                        "Sampled texel buffer " + image.name()
                                + " requires a texture-buffer binding; current runtime has no texture-buffer interface"
                );
            }
            final ResolvedFormat format = requireFormat(formats, image.name(), "sampled image");
            final List<SpirvReflection.CombinedSampler> uses = usesByImage.getOrDefault(image.id(), List.of());
            final boolean dummyUse = uses.stream().anyMatch(use -> use.samplerId() == -1);
            if (uses.isEmpty() || dummyUse) {
                final String uniform = uses.size() > 1 ? uniqueName(image.name(), "fetch", sampledBindings) : image.name();
                final SampledBinding sampled = new SampledBinding(
                        uniform,
                        image.name(),
                        null,
                        format.gpuFormat(),
                        format.pf(),
                        format.rule(),
                        "SAMPLED_IMAGE",
                        image.descriptorSet(),
                        image.descriptorBinding(),
                        -1,
                        sampledBinding,
                        image.imageArrayed(),
                        image.imageDimension() == Spv.SpvDim3D,
                        image.imageDimension() == Spv.SpvDimCube,
                        true,
                        false,
                        image.id(),
                        -1,
                        -1
                );
                sampledBindings.add(sampled);
                imageOnlyBindings.put(image.id(), sampled);
                pairs.add(new SamplerPair(
                        image.name(),
                        uniform,
                        image.descriptorBinding(),
                        null,
                        -1,
                        image.descriptorSet(),
                        sampledBinding,
                        true,
                        false
                ));
                glBindings.put(uniform, sampledBinding);
                sampledBinding++;
            }
            for (SpirvReflection.CombinedSampler combined : uses) {
                if (combined.samplerId() == -1) {
                    continue;
                }
                final SpirvReflection.Resource sampler = samplersById.get(combined.samplerId());
                if (sampler == null) {
                    throw new BakeException(
                            "Combined sampler use for image " + image.name() + " has no reflected sampler resource"
                    );
                }
                checkSet(sampler, "sampler");
                final String uniform = uses.size() == 1
                        ? image.name()
                        : uniqueName(image.name(), sampler.name(), sampledBindings);
                final SampledBinding sampled = new SampledBinding(
                        uniform,
                        image.name(),
                        sampler.name(),
                        format.gpuFormat(),
                        format.pf(),
                        format.rule(),
                        image.baseType() == Spvc.SPVC_BASETYPE_SAMPLED_IMAGE
                                ? "COMBINED_IMAGE_SAMPLER"
                                : "SEPARATE_IMAGE_AND_SAMPLER",
                        image.descriptorSet(),
                        image.descriptorBinding(),
                        sampler.descriptorBinding(),
                        sampledBinding,
                        image.imageArrayed(),
                        image.imageDimension() == Spv.SpvDim3D,
                        image.imageDimension() == Spv.SpvDimCube,
                        false,
                        sharedImages.contains(image.id()),
                        image.id(),
                        sampler.id(),
                        combined.combinedId()
                );
                sampledBindings.add(sampled);
                combinedBindings.put(combined.combinedId(), sampled);
                pairs.add(new SamplerPair(
                        image.name(),
                        uniform,
                        image.descriptorBinding(),
                        sampler.name(),
                        sampler.descriptorBinding(),
                        sampler.descriptorSet(),
                        sampledBinding,
                        false,
                        samplerUseCount.getOrDefault(sampler.name(), 0) > 1
                ));
                glBindings.put(uniform, sampledBinding);
                sampledBinding++;
            }
        }

        int storageImageBinding = 0;
        for (SpirvReflection.Resource image : reflection.storageImages()) {
            checkSet(image, "storage image");
            if (image.imageDimension() == Spv.SpvDimBuffer) {
                throw new BakeException(
                        "Storage texel buffer " + image.name()
                                + " was not lowered to a structured buffer; runtime storageTexelBinding is required"
                );
            }
            final ResolvedFormat format = requireFormat(formats, image.name(), "storage image");
            if ("unknown".equals(format.dxcImageFormat()) && reflection.hasStorageImageReadWithoutFormat())
                throw new BakeException("Write-only dynamic image shader contains formatless storage reads: " + image.name());
            verifyStorageImageFormat(image, format);
            bindings.add(new PlanBinding(
                    "storageImage",
                    image.name(),
                    image.name(),
                    format.gpuFormat(),
                    format.pf(),
                    format.rule(),
                    null,
                    image.descriptorSet(),
                    image.descriptorBinding(),
                    storageImageBinding,
                    image.nonReadable() ? "WRITE_ONLY" : imageAccess(image.imageAccess()),
                    image.imageArrayed()
            ));
            glBindings.put(image.name(), storageImageBinding);
            storageImageBinding++;
        }

        final Set<String> samplerNames = new LinkedHashSet<>();
        for (SamplerPair pair : pairs) {
            if (pair.sampler() != null && samplerNames.add(pair.sampler())) {
                final SpirvReflection.Resource sampler = findSampler(reflection.separateSamplers(), pair.sampler());
                samplerPlans.add(new SamplerPlan(
                        pair.sampler(),
                        pair.descriptorSet(),
                        pair.samplerBinding(),
                        samplerUseCount.getOrDefault(pair.sampler(), 0) > 1
                ));
            }
        }

        validateDescriptorTypes(bindings, sampledBindings);
        return new Plan(
                List.copyOf(bindings),
                List.copyOf(sampledBindings),
                List.copyOf(samplerPlans),
                List.copyOf(pairs),
                Map.copyOf(glBindings),
                Map.copyOf(combinedBindings),
                Map.copyOf(imageOnlyBindings)
        );
    }

    private static void validateDescriptorTypes(final List<PlanBinding> bindings,
                                                final List<SampledBinding> sampledBindings) {
        final Map<Integer, String> types = new HashMap<>();
        for (PlanBinding binding : bindings) {
            claimDescriptorType(types, binding.descriptorBinding(), binding.kind(), binding.name());
        }
        for (SampledBinding binding : sampledBindings) {
            final String imageType = "COMBINED_IMAGE_SAMPLER".equals(binding.descriptorForm())
                    ? "combinedImageSampler" : "sampledImage";
            claimDescriptorType(types, binding.descriptorBinding(), imageType, binding.name());
            if ("SEPARATE_IMAGE_AND_SAMPLER".equals(binding.descriptorForm())) {
                claimDescriptorType(types, binding.samplerDescriptorBinding(), "sampler", binding.name());
            }
        }
    }

    private static void claimDescriptorType(final Map<Integer, String> types, final int binding,
                                            final String type, final String name) {
        final String previous = types.putIfAbsent(binding, type);
        if (previous != null && !previous.equals(type)) {
            throw new BakeException("Vulkan descriptor " + binding + " for " + name
                    + " is both " + previous + " and " + type);
        }
    }

    private static SpirvReflection.Resource findSampler(
            final List<SpirvReflection.Resource> samplers,
            final String name
    ) {
        for (SpirvReflection.Resource sampler : samplers) {
            if (sampler.name().equals(name)) {
                return sampler;
            }
        }
        return null;
    }

    private static String uniqueName(
            final String imageName,
            final String samplerName,
            final List<SampledBinding> existing
    ) {
        final String base = imageName + "_" + samplerName;
        String candidate = base;
        int suffix = 2;
        while (containsUniform(existing, candidate)) {
            candidate = base + "_" + suffix++;
        }
        return candidate;
    }

    private static boolean containsUniform(final List<SampledBinding> existing, final String name) {
        for (SampledBinding binding : existing) {
            if (binding.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static void verifyStorageImageFormat(
            final SpirvReflection.Resource image,
            final ResolvedFormat format
    ) {
        if (format.dxcImageFormat() == null) {
            throw new BakeException(
                    "Storage image " + image.name() + " has no explicit vk::image_format contract"
            );
        }
        final int expected = spvImageFormat(format.dxcImageFormat());
        if (expected == 0 && !"unknown".equals(format.dxcImageFormat())) {
            throw new BakeException(
                    "Unknown SPIR-V image format token '" + format.dxcImageFormat()
                            + "' for storage image " + image.name()
            );
        }
        if (image.imageStorageFormat() != expected) {
            throw new BakeException(
                    "Storage image " + image.name() + " reflects "
                            + image.imageStorageFormat() + " but manifest requires "
                            + format.dxcImageFormat() + " (" + expected + ")"
            );
        }
        if (expected == Spv.SpvImageFormatUnknown && !supportsFormatlessStorage(image)) {
            throw new BakeException("Formatless storage requires a write-only floating 2D, 2D array or 3D image: " + image.name());
        }
    }

    static boolean supportsFormatlessStorage(SpirvReflection.Resource image) {
        return image.nonReadable() && !image.nonWritable() && image.imageSampledBaseType() == Spvc.SPVC_BASETYPE_FP32
                && (image.imageDimension() == Spv.SpvDim2D
                    || image.imageDimension() == Spv.SpvDim3D && !image.imageArrayed());
    }

    private static int spvImageFormat(final String dxc) {
        return switch (dxc) {
            case "rgba32f" -> Spv.SpvImageFormatRgba32f;
            case "rgba16f" -> Spv.SpvImageFormatRgba16f;
            case "r32f" -> Spv.SpvImageFormatR32f;
            case "rgba8" -> Spv.SpvImageFormatRgba8;
            case "rgba8_snorm" -> Spv.SpvImageFormatRgba8Snorm;
            case "rg32f" -> Spv.SpvImageFormatRg32f;
            case "rg16f" -> Spv.SpvImageFormatRg16f;
            case "r11g11b10f" -> Spv.SpvImageFormatR11fG11fB10f;
            case "r16f" -> Spv.SpvImageFormatR16f;
            case "rgba16" -> Spv.SpvImageFormatRgba16;
            case "rgb10a2" -> Spv.SpvImageFormatRgb10A2;
            case "rg16" -> Spv.SpvImageFormatRg16;
            case "rg8" -> Spv.SpvImageFormatRg8;
            case "r16" -> Spv.SpvImageFormatR16;
            case "r8" -> Spv.SpvImageFormatR8;
            case "rgba16_snorm" -> Spv.SpvImageFormatRgba16Snorm;
            case "rg16_snorm" -> Spv.SpvImageFormatRg16Snorm;
            case "rg8_snorm" -> Spv.SpvImageFormatRg8Snorm;
            case "r16_snorm" -> Spv.SpvImageFormatR16Snorm;
            case "r8_snorm" -> Spv.SpvImageFormatR8Snorm;
            case "rgba32i" -> Spv.SpvImageFormatRgba32i;
            case "rgba16i" -> Spv.SpvImageFormatRgba16i;
            case "rgba8i" -> Spv.SpvImageFormatRgba8i;
            case "r32i" -> Spv.SpvImageFormatR32i;
            case "rg32i" -> Spv.SpvImageFormatRg32i;
            case "rg16i" -> Spv.SpvImageFormatRg16i;
            case "rg8i" -> Spv.SpvImageFormatRg8i;
            case "r16i" -> Spv.SpvImageFormatR16i;
            case "r8i" -> Spv.SpvImageFormatR8i;
            case "rgba32ui" -> Spv.SpvImageFormatRgba32ui;
            case "rgba16ui" -> Spv.SpvImageFormatRgba16ui;
            case "rgba8ui" -> Spv.SpvImageFormatRgba8ui;
            case "r32ui" -> Spv.SpvImageFormatR32ui;
            case "rgb10a2ui" -> Spv.SpvImageFormatRgb10a2ui;
            case "rg32ui" -> Spv.SpvImageFormatRg32ui;
            case "rg16ui" -> Spv.SpvImageFormatRg16ui;
            case "rg8ui" -> Spv.SpvImageFormatRg8ui;
            case "r16ui" -> Spv.SpvImageFormatR16ui;
            case "r8ui" -> Spv.SpvImageFormatR8ui;
            default -> 0;
        };
    }

    private static List<SpirvReflection.Resource> allSampledImages(final SpirvReflection.Reflection reflection) {
        final List<SpirvReflection.Resource> result = new ArrayList<>();
        result.addAll(reflection.sampledImages());
        result.addAll(reflection.separateImages());
        return result;
    }

    private static ResolvedFormat requireFormat(
            final Map<String, ResolvedFormat> formats,
            final String name,
            final String kind
    ) {
        final ResolvedFormat format = formats.get(name);
        if (format == null) {
            throw new BakeException(
                    "Reflected " + kind + " " + name + " has no manifest binding/format entry"
            );
        }
        return format;
    }

    private static void checkSet(final SpirvReflection.Resource resource, final String kind) {
        if (resource.descriptorSet() != 0) {
            throw new BakeException(
                    "Reflected " + kind + " " + resource.name() + " is in descriptor set "
                            + resource.descriptorSet() + "; the runtime uses push descriptor set 0 only"
            );
        }
        if (resource.descriptorBinding() < 0) {
            throw new BakeException(
                    "Reflected " + kind + " " + resource.name() + " has no descriptor binding"
            );
        }
    }

    private static String access(final boolean nonWritable, final boolean nonReadable) {
        if (nonWritable) {
            return "READ_ONLY";
        }
        if (nonReadable) {
            return "WRITE_ONLY";
        }
        return "READ_WRITE";
    }

    private static String imageAccess(final int access) {
        return switch (access) {
            case Spv.SpvAccessQualifierReadOnly -> "READ_ONLY";
            case Spv.SpvAccessQualifierWriteOnly -> "WRITE_ONLY";
            default -> "READ_WRITE";
        };
    }
}
