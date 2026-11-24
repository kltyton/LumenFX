package com.kltyton.lumenfx.client.render.scene.surface;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.render.texture.OwnedColorTarget;
import com.kltyton.lumenfx.client.resource.shader.glsl.GlslSource;
import com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceException;
import com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceScanner;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Records exact hardware depth-passing coverage for external Photon {@code .fx} opaque draws that write depth,
 * distinguishing equal-depth writes ({@code GREATER_THAN_OR_EQUAL}) from occluded fragments and {@code depthMask=false}.
 */
public final class PhotonOpaqueMaskCapture implements AutoCloseable {
    private static final AtomicLong NEXT = new AtomicLong();
    private static final String PREFIX = "lfxPhotonMask_";
    private static final Pattern VERSION_LINE = Pattern.compile("(?m)^(\\s*#version\\s+)(\\d+)");
    private static final Pattern EXPLICIT_LOCATION = Pattern.compile("\\blocation\\s*=");
    private static @Nullable PhotonOpaqueMaskCapture active;

    private final Map<RenderPipeline, RenderPipeline> variants = new HashMap<>();
    private @Nullable OwnedColorTarget mask;
    private @Nullable GpuTexture depthTarget;
    private boolean frameValid;

    /**
     * Begins capturing hardware depth-passing writes for any single-color pass bound to {@code depthTarget}
     * (including Photon's intermediate {@code PhotonDrawTarget} HDR texture) while leaving seed/composite
     * color-only passes untouched.
     */
    public void begin(CommandEncoder encoder, GpuTexture depthTarget) {
        RenderSystem.assertOnRenderThread();
        if (active != null) throw new IllegalStateException("External Photon opaque mask capture is already active");
        int width = depthTarget.getWidth(0);
        int height = depthTarget.getHeight(0);
        if (mask == null || mask.width != width || mask.height != height) {
            if (mask != null) { mask.close(); mask = null; }
            mask = new OwnedColorTarget("LumenFX external Photon opaque coverage mask", width, height, GpuFormat.R8_UNORM);
        }
        encoder.clearColorTexture(mask.getColorTexture(), new Vector4f());
        this.depthTarget = depthTarget;
        this.frameValid = true;
        active = this;
    }

    public void end() {
        if (active == this) active = null;
        depthTarget = null;
    }

    public void discardFrame() {
        end();
        frameValid = false;
    }

    public @Nullable GpuTextureView maskView() {
        return frameValid && mask != null ? mask.getColorTextureView() : null;
    }

    static boolean matchesPass(int maskWidth, int maskHeight, int colorAttachmentCount,
                               int colorWidth, int colorHeight, boolean sameDepthTarget) {
        return sameDepthTarget && colorAttachmentCount == 1
                && colorWidth == maskWidth && colorHeight == maskHeight;
    }

    public static SceneSurfaceCapture.@Nullable Expansion expand(RenderPassDescriptor descriptor) {
        PhotonOpaqueMaskCapture capture = active;
        if (capture == null || capture.mask == null || descriptor.depthAttachment == null
                || descriptor.colorAttachments.size() != 1 || descriptor.colorAttachments.getFirst() == null) {
            return null;
        }
        var color = descriptor.colorAttachments.getFirst();
        var colorTex = color.textureView().texture();
        boolean sameDepth = descriptor.depthAttachment.textureView().texture() == capture.depthTarget;
        if (!matchesPass(capture.mask.width, capture.mask.height, descriptor.colorAttachments.size(),
                colorTex.getWidth(0), colorTex.getHeight(0), sameDepth)) {
            return null;
        }
        if (RenderSystem.getDevice().getDeviceInfo().limits().maxColorAttachments() < 2) {
            throw new IllegalStateException("External Photon opaque mask capture requires 2 color attachments");
        }
        var expanded = RenderPassDescriptor.create(descriptor.label())
                .withColorAttachment(color.textureView(), color.clearValue())
                .withColorAttachment(capture.mask.getColorTextureView())
                .withDepthAttachment(descriptor.depthAttachment.textureView(), descriptor.depthAttachment.clearValue())
                .withRenderArea(Objects.requireNonNull(descriptor.renderArea));
        return new SceneSurfaceCapture.Expansion(expanded, capture::variant);
    }

    static boolean writesOpaqueMask(RenderPipeline host) {
        var depth = host.getDepthStencilState();
        return depth != null && depth.writeDepth();
    }

    static String adaptFragmentShader(String fragment) {
        var versionMatcher = VERSION_LINE.matcher(fragment);
        if (versionMatcher.find() && Integer.parseInt(versionMatcher.group(2)) < 330) {
            fragment = versionMatcher.replaceFirst("$1330");
        }
        GlslSource parsed = GlslSourceScanner.scan(fragment);
        if (parsed.mains().size() != 1 || parsed.identifiers().stream().anyMatch(name -> name.startsWith(PREFIX))) {
            throw new GlslSourceException("Photon opaque mask capture requires a single main without reserved prefix");
        }
        if (parsed.outputs().size() != 1) {
            throw new GlslSourceException("Photon opaque mask capture requires a single color output");
        }
        var output = parsed.outputs().getFirst();
        if (output.array() || output.declarators() != 1 || output.conditionalDepth() != 0) {
            throw new GlslSourceException("Photon opaque mask capture color output declaration is unsupported");
        }
        String existingDecl = fragment.substring(output.start(), output.end());
        if (!EXPLICIT_LOCATION.matcher(existingDecl).find()) {
            String declaration = "layout(location=0) out "
                    + (output.precision() == null ? "" : output.precision() + " ")
                    + output.type() + " " + output.name() + ";";
            fragment = fragment.substring(0, output.start()) + declaration + fragment.substring(output.end());
        }
        int insertion = GlslSourceScanner.header(fragment).insertionIndex();
        return fragment.substring(0, insertion)
                + "\n#define main " + PREFIX + "originalMain\n"
                + fragment.substring(insertion)
                + "\n#undef main\n"
                + "layout(location=1) out vec4 " + PREFIX + "coverage;\n"
                + "void main() {\n"
                + "    " + PREFIX + "originalMain();\n"
                + "    " + PREFIX + "coverage = vec4(1.0, 0.0, 0.0, 1.0);\n"
                + "}\n";
    }

    private RenderPipeline variant(RenderPipeline host) {
        RenderPipeline cached = variants.get(host);
        if (cached != null) return cached;
        ShaderSource hostShaders = (id, type) -> Minecraft.getInstance().getShaderManager().getShader(id, type);
        Identifier fragmentId = host.getFragmentShader();
        String originalFragment = hostShaders.get(fragmentId, ShaderType.FRAGMENT);
        if (originalFragment == null) {
            throw new GlslSourceException("Photon opaque mask shader source is missing: " + fragmentId);
        }
        String adaptedFragment = adaptFragmentShader(originalFragment);
        long id = NEXT.getAndIncrement();
        Identifier generatedFragment = LumenFx.id("photon_opaque_mask/" + id + "_fragment");
        boolean writesMask = writesOpaqueMask(host);
        RenderPipeline pipeline = com.kltyton.lumenfx.client.platform.LumenClientPlatform.toBuilder(host)
                .withLocation(com.kltyton.lumenfx.client.render.scene.capture.SceneSurfacePassAccess.derivedPipelineLocation(
                        host, "pipeline/photon_opaque_mask/" + id))
                .withFragmentShader(generatedFragment)
                .withColorTargetState(0, host.getColorTargetState())
                .withColorTargetState(1, new ColorTargetState(Optional.empty(), GpuFormat.R8_UNORM,
                        writesMask ? ColorTargetState.WRITE_RED : 0))
                .build();
        if (!RenderSystem.getDevice().precompilePipeline(pipeline, (shader, type) ->
                shader.equals(generatedFragment) && type == ShaderType.FRAGMENT
                        ? adaptedFragment : hostShaders.get(shader, type)).isValid()) {
            throw new IllegalStateException("Photon opaque mask pipeline failed to compile: " + host.getLocation());
        }
        variants.put(host, pipeline);
        return pipeline;
    }

    @Override public void close() {
        discardFrame();
        if (mask != null) { mask.close(); mask = null; }
        variants.clear();
    }
}
