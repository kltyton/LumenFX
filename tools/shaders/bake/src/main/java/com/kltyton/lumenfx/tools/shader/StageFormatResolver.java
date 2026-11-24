package com.kltyton.lumenfx.tools.shader;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class StageFormatResolver {
    private final ShaderManifest manifest;
    private final ShaderManifest.Pass pass;
    private final BakeOptions options;
    private final JsonObject storageOverrides;
    private final Map<String, ResolvedFormat> formats = new LinkedHashMap<>();

    StageFormatResolver(
            final ShaderManifest manifest,
            final ShaderManifest.Pass pass,
            final BakeOptions options, final ShaderManifest.Variant variant
    ) {
        this.manifest = manifest;
        this.pass = pass;
        this.options = options;
        var selector = variant.selector();
        storageOverrides = selector == null || !selector.has(NiagaraGrid3DShaderFormats.SELECTOR) ? new JsonObject()
                : selector.getAsJsonObject(NiagaraGrid3DShaderFormats.SELECTOR);
        for (ShaderManifest.Resource resource : ShaderManifest.resources(pass)) {
            final ResolvedFormat format = resolve(resource);
            if (format != null) {
                this.formats.put(resource.name(), format);
            }
        }
    }

    Map<String, ResolvedFormat> formats() {
        return Map.copyOf(this.formats);
    }

    Map<String, String> imageFormats() {
        final Map<String, String> result = new LinkedHashMap<>();
        for (ShaderManifest.Resource resource : ShaderManifest.resources(this.pass)) {
            if (!resource.uav()) {
                continue;
            }
            final ResolvedFormat format = this.formats.get(resource.name());
            if (format != null && format.dxcImageFormat() != null) {
                result.put(resource.name(), format.dxcImageFormat());
            }
        }
        return result;
    }

    private ResolvedFormat resolve(final ShaderManifest.Resource resource) {
        final String contract = (NiagaraGrid3DShaderFormats.IMAGE_KIND.equals(resource.kind())
                || NiagaraGrid3DShaderFormats.ARRAY_IMAGE_KIND.equals(resource.kind())) && resource.uav()
                && storageOverrides.has(resource.name()) ? storageOverrides.get(resource.name()).getAsString() : resource.format();
        if (isDepthInput(resource, contract)) {
            return new ResolvedFormat(
                    "PF_DepthStencil",
                    null,
                    hostGpu(this.options.sceneDepthFormat(), "--scene-depth-format"),
                    null,
                    true,
                    "host SceneDepth RHI texture, not the unresolved source PF_DepthStencil"
            );
        }
        if (isVelocityInput(resource, contract)) {
            return new ResolvedFormat(
                    "PF_G16R16 or PF_A16B16G16R16",
                    null,
                    hostGpu(this.options.velocityFormat(), "--velocity-format"),
                    null,
                    true,
                    "host SceneVelocity RHI texture selected by --velocity-format"
            );
        }

        final String pf = pf(contract);
        final ShaderManifest.FormatEntry entry = pf == null ? null : this.manifest.formats().get(pf);
        String gpu = firstNonBlank(
                resource.gpuFormat(),
                entry == null ? null : entry.gpuFormat(),
                pf == null ? null : FormatResolver.gpuFormatForPf(pf)
        );
        final String dxc = firstNonBlank(
                resource.dxcImageFormat(),
                entry == null ? null : entry.dxcImageFormat(),
                pf == null ? null : FormatResolver.dxcImageFormatForPf(pf)
        );
        final boolean hostColor = "ColorTexture".equals(resource.name())
                && this.options.sceneColorFormat() != null;
        if (hostColor) {
            gpu = hostGpu(this.options.sceneColorFormat(), "--scene-color-format");
        }
        if (gpu == null && dxc == null) {
            if ("PF_FloatRGB".equals(pf) || "PF_ShadowDepth".equals(pf)) {
                throw new BakeException(
                        "Manifest format " + pf + " for " + resource.name()
                                + " requires explicit gpuFormat/dxcImageFormat; the RHI alias must not be inferred"
                );
            }
            return null;
        }
        if (gpu == null) {
            throw new BakeException(
                    "Manifest resource " + resource.name() + " has no gpuFormat; refusing to emit UNKNOWN"
            );
        }
        return new ResolvedFormat(
                pf,
                resource.vkFormat() != null ? resource.vkFormat() : (entry == null ? null : entry.vk()),
                gpu,
                dxc,
                hostColor,
                hostColor
                        ? "host SceneColor RHI texture selected by --scene-color-format"
                        : "source-selected manifest resource format"
        );
    }

    private boolean isDepthInput(final ShaderManifest.Resource resource, final String contract) {
        return "PF_DepthStencil".equals(pf(contract))
                || resource.name().contains("DepthTexture");
    }

    private boolean isVelocityInput(final ShaderManifest.Resource resource, final String contract) {
        return contract != null
                && (contract.contains("PF_G16R16 or PF_A16B16G16R16")
                || resource.name().contains("VelocityTexture"));
    }

    private String hostGpu(final String value, final String option) {
        if (value == null || value.isBlank()) {
            throw new BakeException(
                    "Manifest resource " + this.pass.name()
                            + " requires host input format; provide " + option
            );
        }
        if (value.startsWith("PF_")) {
            final String gpu = FormatResolver.gpuFormatForPf(value);
            if (gpu == null) {
                throw new BakeException(
                        option + " must name a mapped host GpuFormat or PF format, got " + value
                );
            }
            return gpu;
        }
        return value;
    }

    private String pf(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        final String first = value.strip().split("\\s+or\\s+", 2)[0].strip();
        return first.startsWith("PF_") ? first : null;
    }

    private static String firstNonBlank(final String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
