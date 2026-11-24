package com.kltyton.lumenfx.client.render.motion.shader;

import com.kltyton.lumenfx.client.resource.shader.glsl.GlslFunction;
import com.kltyton.lumenfx.client.resource.shader.glsl.GlslSource;
import com.kltyton.lumenfx.client.resource.shader.glsl.GlslHeader;
import com.kltyton.lumenfx.client.resource.shader.glsl.GlslOutputDeclaration;
import com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceScanner;
import com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceException;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionLogLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;

public final class MotionShaderAdapter {
    private static final String POINT_VARIANT = "point";
    private static final String MOTION_VARIANT = "motion";
    private static final String ORIGINAL_MAIN = "lfxMotionShader_originalMain";
    private static final String SOURCE_CLIP = "lfxMotionShader_sourceClip";
    private static final String CURRENT_CLIP = "lfxMotionShader_currentClip";
    private static final String PREVIOUS_CLIP = "lfxMotionShader_previousClip";
    private static final String VALIDITY = "lfxMotionShader_validity";
    private static final String POINT_CLIP_OUTPUT = "lfxMotionShader_pointClip";
    private static final String MOTION_CLIP_OUTPUT = "lfxMotionShader_motionClip";
    private static final String MOTION_DEPTH_OUTPUT = "lfxMotionShader_motionDepth";
    private static final String MOTION_OFFSET_OUTPUT = "lfxMotionShader_motionOffset";
    private static final String INSTANCE_ID = "lfxMotionShader_instanceId";
    private static final String LOG_DUMMY_OUTPUT = "lfxMotionShader_logDummy";
    private static final String VELOCITY_ELIGIBLE = "lfxMotionVelocityEligible";
    private static final Pattern LEGACY_TRANSLUCENT_MOTION_DISCARD = Pattern.compile(
            "if\\s*\\(\\s*(opacity\\s*-\\s*1\\.0\\s*/\\s*255\\.0\\s*-\\s*[^<>()]+?)\\s*<\\s*0\\.0\\s*\\)\\s*discard\\s*;");
    private static final Pattern GLSL_VERSION_DIRECTIVE = Pattern.compile("(?m)^([ \\t]*#[ \\t]*version[ \\t]+)(\\d+)");

    private static final Set<String> POINT_VERTEX_NAMES = Set.of(
            ORIGINAL_MAIN,
            SOURCE_CLIP,
            MotionShaderLayout.ATLAS_BLOCK,
            MotionShaderLayout.ATLAS_DIMS,
            MotionShaderLayout.ATLAS_BASE);

    private static final Set<String> MOTION_VERTEX_NAMES = Set.of(
            ORIGINAL_MAIN,
            CURRENT_CLIP,
            PREVIOUS_CLIP,
            VALIDITY,
            MotionShaderLayout.ATLAS_BLOCK,
            MotionShaderLayout.ATLAS_DIMS,
            MotionShaderLayout.ATLAS_BASE,
            MotionShaderLayout.VERTEX_MAP,
            MotionShaderLayout.PREVIOUS_CLIP_ATLAS);

    private static final Set<String> MOTION_FRAGMENT_NAMES = Set.of(
            ORIGINAL_MAIN,
            CURRENT_CLIP,
            PREVIOUS_CLIP,
            VALIDITY,
            MOTION_CLIP_OUTPUT,
            MOTION_DEPTH_OUTPUT,
            MOTION_OFFSET_OUTPUT);

    private static final Set<String> TRANSLUCENT_LOG_VELOCITY_FRAGMENT_NAMES = Set.of(
            ORIGINAL_MAIN,
            CURRENT_CLIP,
            PREVIOUS_CLIP,
            VALIDITY,
            INSTANCE_ID,
            LOG_DUMMY_OUTPUT,
            TranslucentMotionLogLayout.NAME,
            TranslucentMotionLogLayout.DRAW_UNIFORM,
            TranslucentMotionLogLayout.DRAW_INFO);

    private static final Set<String> TRANSLUCENT_LOG_DEPTH_FRAGMENT_NAMES = Set.of(
            ORIGINAL_MAIN,
            INSTANCE_ID,
            LOG_DUMMY_OUTPUT,
            TranslucentMotionLogLayout.NAME,
            TranslucentMotionLogLayout.DRAW_UNIFORM,
            TranslucentMotionLogLayout.DRAW_INFO);

    private final ShaderSource host;
    private record SourceKey(String variant, Identifier vertex, Identifier fragment, ShaderDefines defines,
                             String vertexSource, String fragmentSource) { }
    private final Map<SourceKey, MotionShaderSet> cache = new HashMap<>();

    public MotionShaderAdapter(ShaderSource hostSource) {
        this.host = Objects.requireNonNull(hostSource, "hostSource");
    }

    public MotionShaderSet pointCapture(RenderPipeline hostPipeline) {
        Objects.requireNonNull(hostPipeline, "hostPipeline");
        Identifier hostVertex = hostPipeline.getVertexShader();
        Identifier hostFragment = hostPipeline.getFragmentShader();
        return pointCapture(hostPipeline, requireSource(hostVertex, ShaderType.VERTEX, "vertex"),
                requireSource(hostFragment, ShaderType.FRAGMENT, "fragment"));
    }

    public MotionShaderSet pointCapture(RenderPipeline hostPipeline, String vertexSource, String fragmentSource) {
        Objects.requireNonNull(hostPipeline, "hostPipeline");
        Objects.requireNonNull(vertexSource, "vertexSource");
        Objects.requireNonNull(fragmentSource, "fragmentSource");
        Identifier hostVertex = hostPipeline.getVertexShader();
        Identifier hostFragment = hostPipeline.getFragmentShader();
        SourceKey key = cacheKey(
                POINT_VARIANT,
                hostVertex,
                hostFragment,
                hostPipeline.getShaderDefines(),
                vertexSource,
                fragmentSource);
        MotionShaderSet cached = this.cache.get(key);
        if (cached != null) {
            return cached;
        }

        ShaderDefines defines = hostPipeline.getShaderDefines();
        GlslSource vertex = parseShader(vertexSource, defines);
        GlslHeader fragmentHeader = shaderHeader(fragmentSource);
        requireSingleMain(vertex, "vertex", hostVertex);
        requireNoCollisions(vertex, "vertex", hostVertex, POINT_VERTEX_NAMES);
        requireNoDefineCollisions(defines, hostPipeline.getLocation(), POINT_VERTEX_NAMES);

        String generatedVertex = appendTail(renameOriginalMain(vertexSource, vertex.header()), pointVertexTail());
        String generatedFragment = pointFragmentSource(fragmentSource, fragmentHeader);
        MotionShaderSet generated = buildSet(
                POINT_VARIANT, hostVertex, hostFragment, generatedVertex, generatedFragment);
        this.cache.put(key, generated);
        return generated;
    }

    public MotionShaderSet motionCoverage(RenderPipeline hostPipeline) {
        Objects.requireNonNull(hostPipeline, "hostPipeline");
        Identifier hostVertex = hostPipeline.getVertexShader();
        Identifier hostFragment = hostPipeline.getFragmentShader();
        return motionCoverage(hostPipeline, requireSource(hostVertex, ShaderType.VERTEX, "vertex"),
                requireSource(hostFragment, ShaderType.FRAGMENT, "fragment"));
    }

    public MotionShaderSet motionCoverage(RenderPipeline hostPipeline, String vertexSource, String fragmentSource) {
        Objects.requireNonNull(hostPipeline, "hostPipeline");
        Objects.requireNonNull(vertexSource, "vertexSource");
        Objects.requireNonNull(fragmentSource, "fragmentSource");
        Identifier hostVertex = hostPipeline.getVertexShader();
        Identifier hostFragment = hostPipeline.getFragmentShader();
        ShaderDefines defines = hostPipeline.getShaderDefines();
        SourceKey key = cacheKey(
                MOTION_VARIANT,
                hostVertex,
                hostFragment,
                defines,
                vertexSource,
                fragmentSource);
        MotionShaderSet cached = this.cache.get(key);
        if (cached != null) {
            return cached;
        }

        GlslSource vertex = parseShader(vertexSource, defines);
        GlslSource fragment = parseShader(fragmentSource, defines);
        requireSingleMain(vertex, "vertex", hostVertex);
        requireSingleMain(fragment, "fragment", hostFragment);
        List<GlslOutputDeclaration> outputs = requireVec4Outputs(fragment, hostFragment);
        requireNoCollisions(vertex, "vertex", hostVertex, MOTION_VERTEX_NAMES);
        requireNoCollisions(fragment, "fragment", hostFragment, MOTION_FRAGMENT_NAMES);
        requireNoDefineCollisions(defines, hostPipeline.getLocation(), MOTION_VERTEX_NAMES);
        requireNoDefineCollisions(defines, hostPipeline.getLocation(), MOTION_FRAGMENT_NAMES);

        String demotedFragment = demoteOutputs(fragmentSource, outputs);
        String generatedVertex = appendTail(renameOriginalMain(vertexSource, vertex.header()), motionVertexTail());
        String generatedFragment = appendTail(
                renameOriginalMain(demotedFragment, fragment.header()),
                motionFragmentTail(fragment.identifiers().contains("gl_FragDepth")));
        MotionShaderSet generated = buildSet(
                MOTION_VARIANT, hostVertex, hostFragment, generatedVertex, generatedFragment);
        this.cache.put(key, generated);
        return generated;
    }

    /** Uses the particle vertex factory's previous transform while preserving material discard/depth. */
    public MotionShaderSet particleCoverage(RenderPipeline hostPipeline) {
        return particleCoverage(hostPipeline, false);
    }

    public MotionShaderSet particleCoverage(RenderPipeline hostPipeline, boolean velocityFromDepth) {
        return particleCoverage(hostPipeline, velocityFromDepth, true);
    }

    public static final int COLOR_DEPTH_COMPARE_NONE = 0;
    public static final int COLOR_DEPTH_COMPARE_GREATER = 1;
    public static final int COLOR_DEPTH_COMPARE_EQUAL = 2;
    public static final int COLOR_DEPTH_COMPARE_GREATER_EQUAL = 3;
    public static final int COLOR_DEPTH_COMPARE_LESS = 4;
    public static final int COLOR_DEPTH_COMPARE_NOT_EQUAL = 5;
    public static final int COLOR_DEPTH_COMPARE_LESS_EQUAL = 6;
    public static final String COLOR_DEPTH_SAMPLER = "LfxMotionColorDepthSampler";

    public MotionShaderSet particleCoverage(RenderPipeline hostPipeline, boolean velocityFromDepth, boolean withMotionOffset) {
        return particleCoverage(hostPipeline, velocityFromDepth, withMotionOffset, COLOR_DEPTH_COMPARE_NONE);
    }

    public MotionShaderSet particleCoverage(RenderPipeline hostPipeline, boolean velocityFromDepth, boolean withMotionOffset,
                                            int colorDepthCompare) {
        return particleCoverage(hostPipeline, velocityFromDepth, withMotionOffset, colorDepthCompare, false);
    }

    public MotionShaderSet particleCoverage(RenderPipeline hostPipeline, boolean velocityFromDepth, boolean withMotionOffset,
                                            int colorDepthCompare, boolean colorReplay) {
        return particleCoverage(hostPipeline, velocityFromDepth, false, withMotionOffset, colorDepthCompare, colorReplay);
    }

    public MotionShaderSet naniteCoverage(RenderPipeline hostPipeline) {
        return particleCoverage(hostPipeline, false, true, true, COLOR_DEPTH_COMPARE_NONE, false);
    }

    /** Clears opaque motion coverage/offset attachments using the host vertex/fragment shader (preserving discard/depth). */
    public MotionShaderSet zeroOpaqueCoverage(RenderPipeline hostPipeline) {
        Identifier vertexId = hostPipeline.getVertexShader(), fragmentId = hostPipeline.getFragmentShader();
        String vertexSource = requireSource(vertexId, ShaderType.VERTEX, "vertex");
        String fragmentSource = requireSource(fragmentId, ShaderType.FRAGMENT, "fragment");
        String variant = "particle_zero_opaque";
        ShaderDefines effectiveDefines = hostPipeline.getShaderDefines().withOverrides(
                ShaderDefines.builder().define("LFX_MOTION_AUXILIARY_PASS", 1).build());
        SourceKey key = cacheKey(variant, vertexId, fragmentId, effectiveDefines, vertexSource, fragmentSource);
        MotionShaderSet cached = cache.get(key);
        if (cached != null) return cached;
        GlslSource fragment = parseShader(fragmentSource, effectiveDefines);
        requireSingleMain(fragment, "fragment", fragmentId);
        requireNoCollisions(fragment, "fragment", fragmentId, MOTION_FRAGMENT_NAMES);
        requireNoDefineCollisions(effectiveDefines, hostPipeline.getLocation(), MOTION_FRAGMENT_NAMES);
        String demoted = demoteOutputs(fragmentSource, requireVec4Outputs(fragment, fragmentId));
        String generatedFragment = appendTail(renameOriginalMain(demoted, fragment.header()), zeroOpaqueFragmentTail());
        MotionShaderSet generated = buildSet(variant, vertexId, fragmentId, vertexSource, generatedFragment);
        cache.put(key, generated);
        return generated;
    }

    /**
     * Builds the ordered per-pixel translucent motion log capture shader for either a velocity-writing
     * translucent batch ({@code hasVelocity = true}) or a non-velocity depth-writing translucent batch
     * ({@code hasVelocity = false}), suppressing RVT feedback side effects via {@code LFX_MOTION_AUXILIARY_PASS}.
     */
    public MotionShaderSet translucentMotionLog(RenderPipeline hostPipeline, boolean hasVelocity) {
        Identifier vertexId = hostPipeline.getVertexShader(), fragmentId = hostPipeline.getFragmentShader();
        String vertexSource = requireSource(vertexId, ShaderType.VERTEX, "vertex");
        String fragmentSource = requireSource(fragmentId, ShaderType.FRAGMENT, "fragment");
        String variant = hasVelocity ? "particle_translucent_log_velocity" : "particle_translucent_log_depth";
        ShaderDefines.Builder overrideBuilder = ShaderDefines.builder()
                .define("LFX_PARTICLE_MOTION", hasVelocity ? 1 : 0)
                .define("LFX_OPAQUE_MOTION", 0)
                .define("LFX_TRANSLUCENT_MOTION_LOG", 1)
                .define("LFX_MOTION_AUXILIARY_PASS", 1)
                .define("LFX_SUBSTRATE_DUAL_SOURCE", 0);
        if (hasVelocity) {
            overrideBuilder.define("LFX_TRANSLUCENT_VELOCITY_COLOR_REPLAY", 1);
        }
        ShaderDefines effectiveDefines = hostPipeline.getShaderDefines().withOverrides(overrideBuilder.build());
        SourceKey key = cacheKey(variant, vertexId, fragmentId, effectiveDefines, vertexSource, fragmentSource);
        MotionShaderSet cached = cache.get(key);
        if (cached != null) return cached;
        GlslSource fragment = parseShader(fragmentSource, effectiveDefines);
        requireSingleMain(fragment, "fragment", fragmentId);
        Set<String> reserved = hasVelocity ? TRANSLUCENT_LOG_VELOCITY_FRAGMENT_NAMES : TRANSLUCENT_LOG_DEPTH_FRAGMENT_NAMES;
        requireNoCollisions(fragment, "fragment", fragmentId, reserved);
        requireNoDefineCollisions(effectiveDefines, hostPipeline.getLocation(), reserved);
        String demoted = demoteOutputs(fragmentSource, requireVec4Outputs(fragment, fragmentId));
        if (hasVelocity) {
            demoted = rewriteLegacyTranslucentVelocityThreshold(demoted);
        }
        boolean writesDepth = fragment.identifiers().contains("gl_FragDepth");
        String generatedFragment = ensureMinimumGlslVersion(
                appendTail(
                        renameOriginalMain(demoted, fragment.header(), hasVelocity),
                        translucentMotionLogFragmentTail(writesDepth, hasVelocity)),
                430);
        MotionShaderSet generated = buildSet(variant, vertexId, fragmentId, vertexSource, generatedFragment);
        cache.put(key, generated);
        return generated;
    }

    /**
     * Builds the pure GLSL 330 per-primitive step-capture shader for OpenGL 3.3–4.2 and minimal-Vulkan devices
     * (no SSBO, no {@code gl_PrimitiveID}, preserves PDO {@code gl_FragDepth}, legacy baked threshold rewrite,
     * and suppresses RVT feedback via {@code LFX_MOTION_AUXILIARY_PASS}).
     */
    public MotionShaderSet translucentStepCapture(RenderPipeline hostPipeline, boolean velocityFromDepth,
                                                  boolean hasVelocity) {
        return translucentStepCapture(hostPipeline, velocityFromDepth, hasVelocity, false);
    }

    public MotionShaderSet translucentStepCapture(RenderPipeline hostPipeline, boolean velocityFromDepth,
                                                  boolean hasVelocity, boolean indirectGuard) {
        Identifier vertexId = hostPipeline.getVertexShader(), fragmentId = hostPipeline.getFragmentShader();
        String vertexSource = requireSource(vertexId, ShaderType.VERTEX, "vertex");
        String fragmentSource = requireSource(fragmentId, ShaderType.FRAGMENT, "fragment");
        String variant = (hasVelocity
                ? (velocityFromDepth ? "particle_translucent_step_depth" : "particle_translucent_step_velocity")
                : "particle_translucent_step_nonvel") + (indirectGuard ? "_indirect" : "");
        ShaderDefines.Builder overrideBuilder = ShaderDefines.builder()
                .define("LFX_PARTICLE_MOTION", hasVelocity ? 1 : 0)
                .define("LFX_OPAQUE_MOTION", 0)
                .define("LFX_TRANSLUCENT_STEP_INSTANCE_BASE", 1)
                .define("LFX_TRANSLUCENT_STEP_INDIRECT_GUARD", indirectGuard ? 1 : 0)
                .define("LFX_MOTION_AUXILIARY_PASS", 1)
                .define("LFX_SUBSTRATE_DUAL_SOURCE", 0);
        if (hasVelocity) {
            overrideBuilder.define("LFX_TRANSLUCENT_VELOCITY_COLOR_REPLAY", 1);
        }
        ShaderDefines effectiveDefines = hostPipeline.getShaderDefines().withOverrides(overrideBuilder.build());
        SourceKey key = cacheKey(variant, vertexId, fragmentId, effectiveDefines, vertexSource, fragmentSource);
        MotionShaderSet cached = cache.get(key);
        if (cached != null) return cached;
        GlslSource fragment = parseShader(fragmentSource, effectiveDefines);
        requireSingleMain(fragment, "fragment", fragmentId);
        requireNoCollisions(fragment, "fragment", fragmentId, MOTION_FRAGMENT_NAMES);
        requireNoDefineCollisions(effectiveDefines, hostPipeline.getLocation(), MOTION_FRAGMENT_NAMES);
        String demoted = demoteOutputs(fragmentSource, requireVec4Outputs(fragment, fragmentId));
        if (hasVelocity) {
            demoted = rewriteLegacyTranslucentVelocityThreshold(demoted);
        }
        boolean writesDepth = fragment.identifiers().contains("gl_FragDepth");
        String generatedFragment = appendTail(
                renameOriginalMain(demoted, fragment.header(), hasVelocity),
                hasVelocity
                        ? motionFragmentTail(writesDepth, velocityFromDepth, false, COLOR_DEPTH_COMPARE_NONE, true)
                        : translucentNonVelocityStepFragmentTail(writesDepth));
        MotionShaderSet generated = buildSet(variant, vertexId, fragmentId, vertexSource, generatedFragment);
        cache.put(key, generated);
        return generated;
    }

    private MotionShaderSet particleCoverage(RenderPipeline hostPipeline, boolean velocityFromDepth, boolean nanite,
                                             boolean withMotionOffset, int colorDepthCompare, boolean colorReplay) {
        Identifier vertexId = hostPipeline.getVertexShader(), fragmentId = hostPipeline.getFragmentShader();
        String vertexSource = requireSource(vertexId, ShaderType.VERTEX, "vertex");
        String fragmentSource = requireSource(fragmentId, ShaderType.FRAGMENT, "fragment");
        String variant = nanite ? "nanite"
                : (velocityFromDepth ? "particle_depth" : "particle")
                        + (withMotionOffset ? "_offset" : "")
                        + (colorReplay ? "_replay" : "")
                        + (colorDepthCompare != COLOR_DEPTH_COMPARE_NONE ? "_cd" + colorDepthCompare : "");
        ShaderDefines.Builder overrideBuilder = ShaderDefines.builder()
                .define("LFX_PARTICLE_MOTION", 1)
                .define("LFX_OPAQUE_MOTION", (nanite || withMotionOffset) ? 1 : 0)
                .define("LFX_MOTION_AUXILIARY_PASS", 1)
                .define("LFX_SUBSTRATE_DUAL_SOURCE", 0);
        if (colorReplay) {
            overrideBuilder.define("LFX_TRANSLUCENT_VELOCITY_COLOR_REPLAY", 1);
        }
        ShaderDefines effectiveDefines = hostPipeline.getShaderDefines().withOverrides(overrideBuilder.build());
        SourceKey key = cacheKey(variant, vertexId, fragmentId, effectiveDefines, vertexSource, fragmentSource);
        MotionShaderSet cached = cache.get(key);
        if (cached != null) return cached;
        GlslSource fragment = parseShader(fragmentSource, effectiveDefines);
        requireSingleMain(fragment, "fragment", fragmentId);
        requireNoCollisions(fragment, "fragment", fragmentId, MOTION_FRAGMENT_NAMES);
        requireNoDefineCollisions(effectiveDefines, hostPipeline.getLocation(), MOTION_FRAGMENT_NAMES);
        String demoted = demoteOutputs(fragmentSource, requireVec4Outputs(fragment, fragmentId));
        if (colorReplay) {
            demoted = rewriteLegacyTranslucentVelocityThreshold(demoted);
        }
        boolean writesDepth = fragment.identifiers().contains("gl_FragDepth");
        String generatedFragment = appendTail(
                renameOriginalMain(demoted, fragment.header(), colorReplay),
                nanite ? naniteFragmentTail(writesDepth)
                        : motionFragmentTail(writesDepth, velocityFromDepth,
                                withMotionOffset, colorDepthCompare, colorReplay));
        MotionShaderSet generated = buildSet(variant, vertexId, fragmentId, vertexSource, generatedFragment);
        cache.put(key, generated);
        return generated;
    }

    public void clear() {
        this.cache.clear();
    }

    private static GlslSource parseShader(String source, ShaderDefines defines) {
        try { return GlslSourceScanner.scan(source, defines); }
        catch (GlslSourceException error) { throw new MotionShaderContractException(error.getMessage(), error); }
    }

    private static GlslHeader shaderHeader(String source) {
        try { return GlslSourceScanner.header(source); }
        catch (GlslSourceException error) { throw new MotionShaderContractException(error.getMessage(), error); }
    }

    private String requireSource(Identifier id, ShaderType type, String role) {
        String source = this.host.get(id, type);
        if (source == null) {
            throw new MotionShaderContractException(
                    "host " + role + " source is missing for " + id + " (" + type + ")");
        }
        return source;
    }

    private static SourceKey cacheKey(
            String variant,
            Identifier vertex,
            Identifier fragment,
            ShaderDefines defines,
            String vertexSource,
            String fragmentSource) {
        return new SourceKey(variant, vertex, fragment, defines, vertexSource, fragmentSource);
    }

    private MotionShaderSet buildSet(
            String variant,
            Identifier hostVertex,
            Identifier hostFragment,
            String vertexSource,
            String fragmentSource) {
        Identifier vertexId = generatedId(variant, ShaderType.VERTEX, hostVertex, vertexSource);
        Identifier fragmentId = generatedId(variant, ShaderType.FRAGMENT, hostFragment, fragmentSource);
        ShaderSource source = (id, type) -> {
            if (vertexId.equals(id) && type == ShaderType.VERTEX) {
                return vertexSource;
            }
            if (fragmentId.equals(id) && type == ShaderType.FRAGMENT) {
                return fragmentSource;
            }
            return this.host.get(id, type);
        };
        return new MotionShaderSet(vertexId, fragmentId, source);
    }

    private static Identifier generatedId(
            String variant, ShaderType type, Identifier hostId, String source) {
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new ExceptionInInitializerError(unavailable);
        }
        String path = "motion_shader/"
                + variant
                + "/"
                + type.getName()
                + "/"
                + hostId.getNamespace()
                + "/"
                + hostId.getPath()
                + "_"
                + hash;
        return LumenFx.id(path);
    }

    private static void requireSingleMain(GlslSource source, String role, Identifier id) {
        List<GlslFunction> mains = source.mains();
        if (mains.isEmpty()) {
            throw new MotionShaderContractException(
                    role + " shader " + id + " is missing a 'void main()' definition");
        }
        if (mains.size() > 1) {
            throw new MotionShaderContractException(
                    role + " shader " + id + " declares " + mains.size()
                            + " 'void main()' definitions; exactly one is required");
        }
    }

    private static List<GlslOutputDeclaration> requireVec4Outputs(GlslSource source, Identifier id) {
        List<GlslOutputDeclaration> outputs = source.outputs();
        if (outputs.isEmpty()) {
            throw new MotionShaderContractException(
                    "fragment shader " + id + " must declare at least one top-level fragment output");
        }

        for (GlslOutputDeclaration output : outputs) {
            if (output.conditionalDepth() != 0) {
                throw new MotionShaderContractException(
                        "fragment shader " + id + " declares fragment output '" + output.type() + " "
                                + output.name() + "' inside a preprocessor conditional; an unconditional output is required");
            }
            if (!output.type().equals("vec4")) {
                throw new MotionShaderContractException(
                        "fragment shader " + id + " declares fragment output '" + output.type() + " "
                                + output.name() + "'; an 'out vec4' is required");
            }
            if (output.array()) {
                throw new MotionShaderContractException(
                        "fragment shader " + id + " declares fragment output array '" + output.name()
                                + "'; a scalar 'out vec4' is required");
            }
            if (output.declarators() != 1) {
                throw new MotionShaderContractException(
                        "fragment shader " + id + " declares " + output.declarators()
                                + " outputs in one statement; separate 'out vec4' declarations are required");
            }
        }
        return outputs;
    }

    private static void requireNoCollisions(
            GlslSource source, String role, Identifier id, Set<String> generatedNames) {
        for (String name : generatedNames) {
            if (source.identifiers().contains(name)) {
                throw new MotionShaderContractException(
                        role + " shader " + id + " already uses reserved identifier '" + name + "'");
            }
        }
    }

    private static void requireNoDefineCollisions(
            ShaderDefines defines, Identifier pipelineLocation, Set<String> generatedNames) {
        for (String name : generatedNames) {
            if (defines.values().containsKey(name) || defines.flags().contains(name)) {
                throw new MotionShaderContractException(
                        "host pipeline " + pipelineLocation + " defines reserved identifier '" + name + "'");
            }
        }
    }

    private static String renameOriginalMain(String source, GlslHeader header) {
        return renameOriginalMain(source, header, false);
    }

    private static String renameOriginalMain(String source, GlslHeader header, boolean declareVelocityEligible) {
        int index = header.insertionIndex();
        String head = source.substring(0, index);
        StringBuilder builder = new StringBuilder(source.length() + 128);
        builder.append(head);
        appendNewline(builder, head);
        if (declareVelocityEligible) {
            builder.append("bool ").append(VELOCITY_ELIGIBLE).append(" = true;\n");
        }
        builder.append("#define main ").append(ORIGINAL_MAIN).append('\n');
        builder.append(source.substring(index));
        return builder.toString();
    }

    private static String rewriteLegacyTranslucentVelocityThreshold(String source) {
        if (source.contains("LFX_TRANSLUCENT_VELOCITY_COLOR_REPLAY")) {
            return source;
        }
        return LEGACY_TRANSLUCENT_MOTION_DISCARD.matcher(source)
                .replaceAll(VELOCITY_ELIGIBLE + " = ($1) >= 0.0;");
    }

    private static String appendTail(String source, String tail) {
        StringBuilder builder = new StringBuilder(source.length() + tail.length() + 32);
        builder.append(source);
        appendNewline(builder, source);
        builder.append("#undef main\n");
        builder.append(tail);
        return builder.toString();
    }

    private static void appendNewline(StringBuilder builder, String current) {
        if (!current.isEmpty() && !current.endsWith("\n") && !current.endsWith("\r")) {
            builder.append('\n');
        }
    }

    private static String demoteOutputs(String source, List<GlslOutputDeclaration> outputs) {
        StringBuilder result = new StringBuilder(source);
        for (int index = outputs.size() - 1; index >= 0; index--) {
            GlslOutputDeclaration output = outputs.get(index);
            String precision = output.precision();
            String replacement = (precision == null || precision.isEmpty() ? "" : precision + " ")
                    + "vec4 " + output.name() + ";";
            result.replace(output.start(), output.end(), replacement);
        }
        return result.toString();
    }

    private static String pointFragmentSource(String hostSource, GlslHeader header) {
        String headerText = hostSource.substring(0, header.insertionIndex());
        StringBuilder builder = new StringBuilder(headerText.length() + 256);
        builder.append(headerText);
        appendNewline(builder, headerText);
        builder.append("flat in vec4 ").append(SOURCE_CLIP).append(";\n");
        builder.append("out vec4 ").append(POINT_CLIP_OUTPUT).append(";\n");
        builder.append("void main() {\n");
        builder.append("    ").append(POINT_CLIP_OUTPUT).append(" = ").append(SOURCE_CLIP).append(";\n");
        builder.append("}\n");
        return builder.toString();
    }

    private static String pointVertexTail() {
        return "layout(std140) uniform " + MotionShaderLayout.ATLAS_BLOCK + " {\n"
                + "    uvec4 " + MotionShaderLayout.ATLAS_DIMS + ";\n"
                + "    ivec4 " + MotionShaderLayout.ATLAS_BASE + ";\n"
                + "};\n"
                + "flat out vec4 " + SOURCE_CLIP + ";\n"
                + "void main() {\n"
                + "    " + ORIGINAL_MAIN + "();\n"
                + "    " + SOURCE_CLIP + " = gl_Position;\n"
                + "    uint vertex = uint(gl_VertexID - " + MotionShaderLayout.ATLAS_BASE + ".x + " + MotionShaderLayout.ATLAS_BASE + ".y);\n"
                + "    uvec2 dims = " + MotionShaderLayout.ATLAS_DIMS + ".xy;\n"
                + "    ivec2 cell = ivec2(int(vertex % dims.x), int(vertex / dims.x));\n"
                + "    vec2 ndc = (vec2(cell) + vec2(0.5)) / vec2(dims) * 2.0 - 1.0;\n"
                + "    gl_Position = vec4(ndc, 0.0, 1.0);\n"
                + "    gl_PointSize = 1.0;\n"
                + "}\n";
    }

    private static String motionVertexTail() {
        return "layout(std140) uniform " + MotionShaderLayout.ATLAS_BLOCK + " {\n"
                + "    uvec4 " + MotionShaderLayout.ATLAS_DIMS + ";\n"
                + "    ivec4 " + MotionShaderLayout.ATLAS_BASE + ";\n"
                + "};\n"
                + "uniform usamplerBuffer " + MotionShaderLayout.VERTEX_MAP + ";\n"
                + "uniform sampler2D " + MotionShaderLayout.PREVIOUS_CLIP_ATLAS + ";\n"
                + "out vec4 " + CURRENT_CLIP + ";\n"
                + "out vec4 " + PREVIOUS_CLIP + ";\n"
                + "out float " + VALIDITY + ";\n"
                + "void main() {\n"
                + "    " + ORIGINAL_MAIN + "();\n"
                + "    int vertex = gl_VertexID - " + MotionShaderLayout.ATLAS_BASE + ".x + " + MotionShaderLayout.ATLAS_BASE + ".z;\n"
                + "    " + CURRENT_CLIP + " = gl_Position;\n"
                + "    uint encoded = texelFetch(" + MotionShaderLayout.VERTEX_MAP + ", vertex).r;\n"
                + "    uvec2 dims = " + MotionShaderLayout.ATLAS_DIMS + ".xy;\n"
                + "    if (encoded == 0u) {\n"
                + "        " + PREVIOUS_CLIP + " = vec4(0.0, 0.0, 0.0, 1.0);\n"
                + "        " + VALIDITY + " = 0.0;\n"
                + "    } else {\n"
                + "        uint texel = encoded - 1u;\n"
                + "        ivec2 cell = ivec2(int(texel % dims.x), int(texel / dims.x));\n"
                + "        " + PREVIOUS_CLIP + " = texelFetch(" + MotionShaderLayout.PREVIOUS_CLIP_ATLAS
                + ", cell, 0);\n"
                + "        " + VALIDITY + " = 1.0;\n"
                + "    }\n"
                + "}\n";
    }

    private static String naniteFragmentTail(boolean writesDepth) {
        String initDepth = writesDepth ? "    gl_FragDepth = gl_FragCoord.z;\n" : "";
        String depthExpr = writesDepth ? "gl_FragDepth" : "gl_FragCoord.z";
        return """
                layout(location = 0) out vec4 %s;
                layout(location = 1) out vec2 %s;
                layout(location = 2) out vec4 %s;
                void main() {
                %s    %s();
                    vec4 currentClip = NaniteCurrentClip * vec4(lfxNaniteGeometry.position, 1.0);
                    vec4 previousClip = NanitePreviousClip * vec4(lfxNaniteGeometry.previous, 1.0);
                    float originalPreviousW = previousClip.w;
                #if LFX_MATERIAL_WRITES_DEPTH
                    vec2 rasterNdc = currentClip.xy / currentClip.w;
                    currentClip.zw += vec2(lfxDepthOffsetZ, lfxDepthOffsetW);
                    previousClip.zw += vec2(lfxDepthOffsetZ, lfxDepthOffsetW);
                #endif
                    bool valid = originalPreviousW > 0.0 && previousClip.w > 0.0 && (NaniteShadingInstanceLayout.w & 1) != 0;
                    vec3 previousNdc = valid ? previousClip.xyz / previousClip.w : vec3(0.0);
                #if LFX_MATERIAL_WRITES_DEPTH
                    previousNdc.xy += rasterNdc - currentClip.xy / currentClip.w;
                #endif
                    %s = vec4(previousNdc, valid ? 1.0 : 0.0);
                    %s = vec2(currentClip.z / currentClip.w, %s);
                    %s = vec4(0.0);
                }
                """.formatted(MOTION_CLIP_OUTPUT, MOTION_DEPTH_OUTPUT, MOTION_OFFSET_OUTPUT,
                initDepth, ORIGINAL_MAIN, MOTION_CLIP_OUTPUT, MOTION_DEPTH_OUTPUT, depthExpr, MOTION_OFFSET_OUTPUT);
    }

    private static String motionFragmentTail(boolean writesDepth) {
        return motionFragmentTail(writesDepth, false, false);
    }

    private static String motionFragmentTail(boolean writesDepth, boolean velocityFromDepth, boolean particle) {
        return motionFragmentTail(writesDepth, velocityFromDepth, particle, COLOR_DEPTH_COMPARE_NONE);
    }

    private static String zeroOpaqueFragmentTail() {
        return "layout(location = 0) out vec4 " + MOTION_CLIP_OUTPUT + ";\n"
                + "layout(location = 1) out vec2 " + MOTION_DEPTH_OUTPUT + ";\n"
                + "layout(location = 2) out vec4 " + MOTION_OFFSET_OUTPUT + ";\n"
                + "void main() {\n"
                + "    " + ORIGINAL_MAIN + "();\n"
                + "    " + MOTION_CLIP_OUTPUT + " = vec4(0.0);\n"
                + "    " + MOTION_DEPTH_OUTPUT + " = vec2(0.0);\n"
                + "    " + MOTION_OFFSET_OUTPUT + " = vec4(0.0);\n"
                + "}\n";
    }

    private static String ensureMinimumGlslVersion(String source, int minVersion) {
        var matcher = GLSL_VERSION_DIRECTIVE.matcher(source);
        if (!matcher.find()) return source;
        int current = Integer.parseInt(matcher.group(2));
        if (current >= minVersion) return source;
        return source.substring(0, matcher.start(2)) + minVersion + source.substring(matcher.end(2));
    }

    private static String translucentNonVelocityStepFragmentTail(boolean writesDepth) {
        String initDepth = writesDepth ? "    gl_FragDepth = gl_FragCoord.z;\n" : "";
        return "layout(location = 0) out vec4 " + MOTION_CLIP_OUTPUT + ";\n"
                + "layout(location = 1) out vec2 " + MOTION_DEPTH_OUTPUT + ";\n"
                + "void main() {\n"
                + initDepth
                + "    " + ORIGINAL_MAIN + "();\n"
                + "    " + MOTION_CLIP_OUTPUT + " = vec4(0.0);\n"
                + "    " + MOTION_DEPTH_OUTPUT + " = vec2(0.0, -2.0);\n"
                + "}\n";
    }

    private static String translucentMotionLogFragmentTail(boolean writesDepth, boolean hasVelocity) {
        StringBuilder tail = new StringBuilder();
        tail.append("layout(std140) uniform ").append(TranslucentMotionLogLayout.DRAW_UNIFORM).append(" {\n")
                .append("    uvec4 ").append(TranslucentMotionLogLayout.DRAW_INFO).append(";\n")
                .append("};\n")
                .append("layout(std430, binding = ").append(TranslucentMotionLogLayout.GL_SLOT).append(") buffer ")
                .append(TranslucentMotionLogLayout.NAME).append(" {\n")
                .append("    uint lfxMotionLogCounter;\n")
                .append("    uint lfxMotionLogOriginX;\n")
                .append("    uint lfxMotionLogOriginY;\n")
                .append("    uint lfxMotionLogWidth;\n")
                .append("    uint lfxMotionLogHeight;\n")
                .append("    uint lfxMotionLogCapacity;\n")
                .append("    uint lfxMotionLogOverflow;\n")
                .append("    uint lfxMotionLogPad1;\n")
                .append("    uint lfxMotionLogWords[];\n")
                .append("};\n")
                .append("flat in uint ").append(INSTANCE_ID).append(";\n");
        if (hasVelocity) {
            tail.append("in vec4 ").append(CURRENT_CLIP).append(";\n")
                    .append("in vec4 ").append(PREVIOUS_CLIP).append(";\n")
                    .append("in float ").append(VALIDITY).append(";\n");
        }
        tail.append("layout(location = 0) out vec4 ").append(LOG_DUMMY_OUTPUT).append(";\n")
                .append("void main() {\n");
        if (writesDepth) {
            tail.append("    gl_FragDepth = gl_FragCoord.z;\n");
        }
        if (hasVelocity) {
            tail.append("    ").append(VELOCITY_ELIGIBLE).append(" = true;\n");
        }
        tail.append("    ").append(ORIGINAL_MAIN).append("();\n")
                .append("    float lfxLogFragDepth = ").append(writesDepth ? "gl_FragDepth" : "gl_FragCoord.z").append(";\n");
        if (hasVelocity) {
            tail.append("    uint lfxLogEligible = (").append(TranslucentMotionLogLayout.DRAW_INFO)
                    .append(".y & ").append(TranslucentMotionLogLayout.FLAG_VELOCITY_ELIGIBLE).append("u) != 0u && ")
                    .append(VELOCITY_ELIGIBLE).append(" ? ")
                    .append(TranslucentMotionLogLayout.FLAG_VELOCITY_ELIGIBLE).append("u : 0u;\n");
        } else {
            tail.append("    uint lfxLogEligible = 0u;\n");
        }
        tail.append("    uint lfxLogWritesDepth = ").append(TranslucentMotionLogLayout.DRAW_INFO)
                .append(".y & ").append(TranslucentMotionLogLayout.FLAG_WRITES_COLOR_DEPTH).append("u;\n")
                .append("    if (lfxLogEligible == 0u && lfxLogWritesDepth == 0u) discard;\n")
                .append("    ivec2 lfxLogLocal = ivec2(gl_FragCoord.xy) - ivec2(int(lfxMotionLogOriginX), int(lfxMotionLogOriginY));\n")
                .append("    if (lfxLogLocal.x >= 0 && lfxLogLocal.y >= 0\n")
                .append("            && uint(lfxLogLocal.x) < lfxMotionLogWidth && uint(lfxLogLocal.y) < lfxMotionLogHeight) {\n")
                .append("        if (lfxMotionLogOverflow != 0u) discard;\n")
                .append("        uint lfxNodeIndex = atomicAdd(lfxMotionLogCounter, 1u);\n")
                .append("        if (lfxNodeIndex < lfxMotionLogCapacity) {\n")
                .append("            uint lfxPixelIndex = uint(lfxLogLocal.y) * lfxMotionLogWidth + uint(lfxLogLocal.x);\n")
                .append("            uint lfxNodeId = lfxNodeIndex + 1u;\n")
                .append("            uint lfxPrevHead = atomicExchange(lfxMotionLogWords[lfxPixelIndex], lfxNodeId);\n")
                .append("            uint lfxBase = lfxMotionLogWidth * lfxMotionLogHeight + lfxNodeIndex * ")
                .append(TranslucentMotionLogLayout.NODE_UINTS).append("u;\n")
                .append("            vec4 lfxLogPrevNdc = vec4(0.0);\n")
                .append("            float lfxLogCurrDepth = 0.0;\n");
        if (hasVelocity) {
            tail.append("            if (lfxLogEligible != 0u) {\n")
                    .append("                vec4 currentClip = ").append(CURRENT_CLIP).append(";\n")
                    .append("                vec4 previousClip = ").append(PREVIOUS_CLIP).append(";\n")
                    .append("#if LFX_MATERIAL_WRITES_DEPTH\n")
                    .append("                vec2 rasterNdc = currentClip.xy / currentClip.w;\n")
                    .append("                currentClip.zw += vec2(lfxDepthOffsetZ, lfxDepthOffsetW);\n")
                    .append("                previousClip.zw += vec2(lfxDepthOffsetZ, lfxDepthOffsetW);\n")
                    .append("#endif\n")
                    .append("                bool validPrevious = ").append(PREVIOUS_CLIP)
                    .append(".w > 0.0 && previousClip.w > 0.0;\n")
                    .append("                vec3 previousNdc = validPrevious ? previousClip.xyz / previousClip.w : vec3(0.0);\n")
                    .append("#if LFX_MATERIAL_WRITES_DEPTH\n")
                    .append("                previousNdc.xy += rasterNdc - currentClip.xy / currentClip.w;\n")
                    .append("#endif\n")
                    .append("                float lfxLogValid = (").append(TranslucentMotionLogLayout.DRAW_INFO)
                    .append(".y & ").append(TranslucentMotionLogLayout.FLAG_VELOCITY_FROM_DEPTH).append("u) != 0u ? 0.0\n")
                    .append("                        : (validPrevious ? ").append(VALIDITY).append(" : 0.0);\n")
                    .append("                lfxLogPrevNdc = vec4(previousNdc, lfxLogValid);\n")
                    .append("                lfxLogCurrDepth = currentClip.z / currentClip.w;\n")
                    .append("            }\n");
        }
        tail.append("            uint lfxNodeFlags = (").append(TranslucentMotionLogLayout.DRAW_INFO)
                .append(".y & 31u) | lfxLogEligible;\n")
                .append("            lfxMotionLogWords[lfxBase + 0u] = lfxPrevHead;\n")
                .append("            lfxMotionLogWords[lfxBase + 1u] = ").append(TranslucentMotionLogLayout.DRAW_INFO).append(".x;\n")
                .append("            lfxMotionLogWords[lfxBase + 2u] = ").append(INSTANCE_ID).append(";\n")
                .append("            lfxMotionLogWords[lfxBase + 3u] = uint(gl_PrimitiveID);\n")
                .append("            lfxMotionLogWords[lfxBase + 4u] = floatBitsToUint(lfxLogFragDepth);\n")
                .append("            lfxMotionLogWords[lfxBase + 5u] = lfxNodeFlags;\n")
                .append("            lfxMotionLogWords[lfxBase + 6u] = floatBitsToUint(lfxLogCurrDepth);\n")
                .append("            lfxMotionLogWords[lfxBase + 7u] = floatBitsToUint(lfxLogPrevNdc.x);\n")
                .append("            lfxMotionLogWords[lfxBase + 8u] = floatBitsToUint(lfxLogPrevNdc.y);\n")
                .append("            lfxMotionLogWords[lfxBase + 9u] = floatBitsToUint(lfxLogPrevNdc.z);\n")
                .append("            lfxMotionLogWords[lfxBase + 10u] = floatBitsToUint(lfxLogPrevNdc.w);\n")
                .append("            lfxMotionLogWords[lfxBase + 11u] = 0u;\n")
                .append("        } else {\n")
                .append("            atomicOr(lfxMotionLogOverflow, 1u);\n")
                .append("        }\n")
                .append("    }\n")
                .append("    ").append(LOG_DUMMY_OUTPUT).append(" = vec4(0.0);\n")
                .append("}\n");
        return tail.toString();
    }

    private static String motionFragmentTail(boolean writesDepth, boolean velocityFromDepth, boolean particle,
                                             int colorDepthCompare) {
        return motionFragmentTail(writesDepth, velocityFromDepth, particle, colorDepthCompare, false);
    }

    private static String motionFragmentTail(boolean writesDepth, boolean velocityFromDepth, boolean particle,
                                             int colorDepthCompare, boolean colorReplay) {
        StringBuilder tail = new StringBuilder();
        tail.append("in vec4 ").append(CURRENT_CLIP).append(";\n")
                .append("in vec4 ").append(PREVIOUS_CLIP).append(";\n")
                .append("in float ").append(VALIDITY).append(";\n")
                .append("layout(location = 0) out vec4 ").append(MOTION_CLIP_OUTPUT).append(";\n")
                .append("layout(location = 1) out vec2 ").append(MOTION_DEPTH_OUTPUT).append(";\n");
        if (colorDepthCompare != COLOR_DEPTH_COMPARE_NONE) {
            tail.append("uniform sampler2D ").append(COLOR_DEPTH_SAMPLER).append(";\n");
        }
        if (particle) {
            tail.append("layout(location = 2) out vec4 ").append(MOTION_OFFSET_OUTPUT).append(";\n")
                    .append("#if LFX_MATERIAL_MOTION_OFFSET\n")
                    .append("vec4 lfxMotionShader_encodeOffsetVelocity(vec3 velocity) {\n")
                    .append("    velocity.xy = sign(velocity.xy) * sqrt(abs(velocity.xy)) * (2.0 / sqrt(2.0));\n")
                    .append("    vec4 encoded;\n")
                    .append("    encoded.xy = velocity.xy * (0.499 * 0.5) + 32767.0 / 65535.0;\n")
                    .append("    uint depthBits = floatBitsToUint(velocity.z);\n")
                    .append("    encoded.z = clamp(float((depthBits >> 16u) & 0xffffu) / 65535.0 + 0.1 / 65535.0, 0.0, 1.0);\n")
                    .append("    encoded.w = clamp(float(depthBits & 0xfffcu) / 65535.0 + 0.1 / 65535.0, 0.0, 1.0);\n")
                    .append("    return encoded;\n")
                    .append("}\n")
                    .append("#endif\n");
        }
        tail.append("void main() {\n");
        if (writesDepth) {
            tail.append("    gl_FragDepth = gl_FragCoord.z;\n");
        }
        if (colorReplay) {
            tail.append("    ").append(VELOCITY_ELIGIBLE).append(" = true;\n");
        }
        tail.append("    ").append(ORIGINAL_MAIN).append("();\n");
        if (colorReplay) {
            tail.append("    if (!").append(VELOCITY_ELIGIBLE).append(") {\n")
                    .append("        ").append(MOTION_CLIP_OUTPUT).append(" = vec4(0.0);\n")
                    .append("        ").append(MOTION_DEPTH_OUTPUT).append(" = vec2(0.0, -2.0);\n")
                    .append("        return;\n")
                    .append("    }\n");
        }
        if (colorDepthCompare != COLOR_DEPTH_COMPARE_NONE) {
            String op = switch (colorDepthCompare) {
                case COLOR_DEPTH_COMPARE_GREATER -> ">";
                case COLOR_DEPTH_COMPARE_EQUAL -> "==";
                case COLOR_DEPTH_COMPARE_GREATER_EQUAL -> ">=";
                case COLOR_DEPTH_COMPARE_LESS -> "<";
                case COLOR_DEPTH_COMPARE_NOT_EQUAL -> "!=";
                case COLOR_DEPTH_COMPARE_LESS_EQUAL -> "<=";
                default -> throw new IllegalArgumentException("Unsupported color depth compare code: " + colorDepthCompare);
            };
            tail.append("    float lfxMotionFragDepth = ").append(writesDepth ? "gl_FragDepth" : "gl_FragCoord.z").append(";\n")
                    .append("    float lfxMotionColorDepth = texelFetch(").append(COLOR_DEPTH_SAMPLER)
                    .append(", ivec2(gl_FragCoord.xy), 0).r;\n")
                    .append("    if (!(lfxMotionFragDepth ").append(op).append(" lfxMotionColorDepth)) discard;\n");
        }
        tail.append("    vec4 currentClip = ").append(CURRENT_CLIP).append(";\n")
                .append("    vec4 previousClip = ").append(PREVIOUS_CLIP).append(";\n")
                .append("#if LFX_MATERIAL_WRITES_DEPTH\n")
                .append("    vec2 rasterNdc = currentClip.xy / currentClip.w;\n")
                .append("    currentClip.zw += vec2(lfxDepthOffsetZ, lfxDepthOffsetW);\n")
                .append("    previousClip.zw += vec2(lfxDepthOffsetZ, lfxDepthOffsetW);\n")
                .append("#endif\n")
                .append("    bool validPrevious = ").append(PREVIOUS_CLIP).append(".w > 0.0 && previousClip.w > 0.0;\n")
                .append("    vec3 previousNdc = validPrevious ? previousClip.xyz / previousClip.w : vec3(0.0);\n")
                .append("#if LFX_MATERIAL_WRITES_DEPTH\n")
                .append("    previousNdc.xy += rasterNdc - currentClip.xy / currentClip.w;\n")
                .append("#endif\n")
                .append("    ").append(MOTION_CLIP_OUTPUT).append(" = vec4(previousNdc, ")
                .append(velocityFromDepth ? "0.0" : "validPrevious ? " + VALIDITY + " : 0.0").append(");\n")
                .append("    ").append(MOTION_DEPTH_OUTPUT).append(" = vec2(currentClip.z / currentClip.w, ")
                .append(writesDepth ? "gl_FragDepth" : "gl_FragCoord.z").append(");\n");
        if (particle) {
            tail.append("#if LFX_MATERIAL_MOTION_OFFSET\n")
                    .append("    vec4 lfxMotionOffsetClip = ProjMat * ModelViewMat * vec4(effectPosition + lfxMaterialMotionWorldOffset.xzy * 0.01, 1.0);\n")
                    .append("#if LFX_MATERIAL_WRITES_DEPTH\n")
                    .append("    lfxMotionOffsetClip.zw += vec2(lfxDepthOffsetZ, lfxDepthOffsetW);\n")
                    .append("#endif\n")
                    .append("    if (lfxMotionOffsetClip.w > 0.0 && currentClip.w > 0.0) {\n")
                    .append("        vec3 lfxCurrentNdc = currentClip.xyz / currentClip.w;\n")
                    .append("        vec3 lfxOffsetNdc = lfxMotionOffsetClip.xyz / lfxMotionOffsetClip.w;\n")
                    .append("        vec2 lfxOffsetVel = lfxCurrentNdc.xy - lfxOffsetNdc.xy;\n")
                    .append("        lfxOffsetVel.y = -lfxOffsetVel.y;\n")
                    .append("        ").append(MOTION_OFFSET_OUTPUT).append(" = lfxMotionShader_encodeOffsetVelocity(vec3(lfxOffsetVel, lfxCurrentNdc.z - lfxOffsetNdc.z));\n")
                    .append("    } else {\n")
                    .append("        ").append(MOTION_OFFSET_OUTPUT).append(" = vec4(0.0);\n")
                    .append("    }\n")
                    .append("#else\n")
                    .append("    ").append(MOTION_OFFSET_OUTPUT).append(" = vec4(0.0);\n")
                    .append("#endif\n");
        }
        tail.append("}\n");
        return tail.toString();
    }
}
