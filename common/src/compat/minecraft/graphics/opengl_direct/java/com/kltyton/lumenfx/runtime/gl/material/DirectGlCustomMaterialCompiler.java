package com.kltyton.lumenfx.runtime.gl.material;

import com.kltyton.lumenfx.runtime.bridge.McResourceBridge;
import com.kltyton.lumenfx.runtime.model.MaterialModel;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import org.lwjgl.opengl.ARBBlendFuncExtended;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GLCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compiles and caches custom GLSL 150/330 Core material shaders for {@link MaterialModel} definitions
 * on non-26.2 OpenGL targets, exposing soft-particle depth fade, scene color refraction, multi-sampler
 * slots, RVT bindings, and time/parameter uniforms.
 */
public final class DirectGlCustomMaterialCompiler {
    public record CompiledMaterial(int program, int viewProjLoc, int modelLoc, int timeLoc,
                                   int hdrLoc, int softFadeLoc, int nearFarLoc, int invViewportLoc,
                                   int paramsLoc, int tintLoc, int tex0Loc, int tex1Loc,
                                   int sceneDepthLoc, int sceneColorLoc, int effectFrameBlock,
                                   Map<String, Integer> samplers) { }

    private static final Logger LOGGER = LoggerFactory.getLogger(DirectGlCustomMaterialCompiler.class);

    private static final String DEFAULT_PARTICLE_VERT = """
            #version 150 core
            in vec3 aPos;
            in vec2 aUv;
            in vec4 aColor;
            uniform mat4 uViewProj;
            uniform float uTime;
            uniform vec4 uParams;
            out vec2 vUv;
            out vec4 vColor;
            out vec3 vWorldPos;
            out vec2 effectUV;
            out vec2 effectUV1;
            out vec2 effectNextUV;
            out vec2 effectUnflippedUV;
            out vec2 effectNextUnflippedUV;
            out float effectSubImageLerp;
            out vec4 effectColor;
            out vec4 effectParticle;
            out vec4 effectCustom;
            out vec3 effectPosition;
            in vec4 aParticle;
            in vec4 aCustom;
            in vec3 aNextSubImage;
            in vec2 aUv1;
            void main() {
                vUv = aUv;
                vColor = aColor;
                vWorldPos = aPos;
                effectUV = aUv;
                effectUV1 = aUv1;
                effectNextUV = aUv + aNextSubImage.xy;
                effectUnflippedUV = aUv;
                effectNextUnflippedUV = effectNextUV;
                effectSubImageLerp = aNextSubImage.z;
                effectColor = aColor;
                effectPosition = aPos;
                effectParticle = aParticle;
                effectCustom = aCustom;
                gl_Position = uViewProj * vec4(aPos, 1.0);
            }
            """;

    private static final String FRAG_PREAMBLE = """
            #version 150 core
            in vec2 vUv;
            in vec4 vColor;
            in vec3 vWorldPos;
            uniform sampler2D uTex;
            uniform sampler2D uTex1;
            uniform sampler2D uSceneDepth;
            uniform sampler2D uSceneColor;
            uniform float uTime;
            uniform float uHdr;
            uniform float uSoftFade;
            uniform vec2 uNearFar;
            uniform vec2 uInvViewport;
            uniform vec4 uParams;
            uniform vec4 uTint;
            out vec4 fragColor;
            float lfx_linear_depth(float d) {
                float z = d * 2.0 - 1.0;
                return (2.0 * uNearFar.x * uNearFar.y) / max(uNearFar.y + uNearFar.x - z * (uNearFar.y - uNearFar.x), 0.0001);
            }
            float lfx_soft_particle_alpha() {
                if (uSoftFade <= 0.0001) return 1.0;
                vec2 screenUv = gl_FragCoord.xy * uInvViewport;
                float sceneZ = lfx_linear_depth(texture(uSceneDepth, screenUv).r);
                float fragZ = lfx_linear_depth(gl_FragCoord.z);
                return clamp((sceneZ - fragZ) / uSoftFade, 0.0, 1.0);
            }
            """;

    private static final String DEFAULT_MESH_VERT = """
            #version 150 core
            in vec3 aPos;
            in vec3 aNormal;
            in vec2 aUv;
            in vec4 aColor;
            uniform mat4 uViewProj;
            uniform mat4 uModel;
            uniform vec4 uTint;
            uniform float uTime;
            uniform vec4 uParams;
            out vec2 vUv;
            out vec4 vColor;
            out vec3 vWorldPos;
            out vec2 effectUV;
            out vec2 effectUV1;
            out vec2 effectNextUV;
            out vec2 effectUnflippedUV;
            out vec2 effectNextUnflippedUV;
            out float effectSubImageLerp;
            out vec4 effectColor;
            out vec4 effectParticle;
            out vec4 effectCustom;
            out vec3 effectPosition;
            void main() {
                vec4 world = uModel * vec4(aPos, 1.0);
                vUv = aUv;
                vColor = aColor * uTint;
                vWorldPos = world.xyz;
                effectUV = aUv;
                effectUV1 = aUv;
                effectNextUV = aUv;
                effectUnflippedUV = aUv;
                effectNextUnflippedUV = aUv;
                effectSubImageLerp = 0.0;
                effectColor = vColor;
                effectPosition = world.xyz;
                effectParticle = vec4(0.0);
                effectCustom = vec4(0.0);
                gl_Position = uViewProj * world;
            }
            """;

    private static final String DEFAULT_COLOR_FRAG = """
            #version 150 core
            in vec4 vColor;
            uniform sampler2D uSceneDepth;
            uniform float uHdr;
            uniform float uSoftFade;
            uniform vec2 uNearFar;
            uniform vec2 uInvViewport;
            out vec4 fragColor;
            float lfx_linear_depth(float d) {
                float z = d * 2.0 - 1.0;
                return (2.0 * uNearFar.x * uNearFar.y) / max(uNearFar.y + uNearFar.x - z * (uNearFar.y - uNearFar.x), 0.0001);
            }
            void main() {
                vec4 outCol = vColor;
                if (uSoftFade > 0.0001) {
                    float sceneZ = lfx_linear_depth(texture(uSceneDepth, gl_FragCoord.xy * uInvViewport).r);
                    float fragZ = lfx_linear_depth(gl_FragCoord.z);
                    outCol.a *= clamp((sceneZ - fragZ) / uSoftFade, 0.0, 1.0);
                }
                outCol.rgb *= uHdr;
                if (outCol.a <= 0.002) discard;
                fragColor = outCol;
            }
            """;

    private static final String DEFAULT_PARTICLE_FRAG = """
            #version 150 core
            in vec2 vUv;
            in vec4 vColor;
            uniform sampler2D uTex;
            uniform sampler2D uSceneDepth;
            uniform float uHdr;
            uniform float uSoftFade;
            uniform vec2 uNearFar;
            uniform vec2 uInvViewport;
            out vec4 fragColor;
            float lfx_linear_depth(float d) {
                float z = d * 2.0 - 1.0;
                return (2.0 * uNearFar.x * uNearFar.y) / max(uNearFar.y + uNearFar.x - z * (uNearFar.y - uNearFar.x), 0.0001);
            }
            void main() {
                vec4 sampled = texture(uTex, vUv);
                vec4 outCol = sampled * vColor;
                if (uSoftFade > 0.0001) {
                    float sceneZ = lfx_linear_depth(texture(uSceneDepth, gl_FragCoord.xy * uInvViewport).r);
                    float fragZ = lfx_linear_depth(gl_FragCoord.z);
                    outCol.a *= clamp((sceneZ - fragZ) / uSoftFade, 0.0, 1.0);
                }
                outCol.rgb *= uHdr;
                if (outCol.a <= 0.002) discard;
                fragColor = outCol;
            }
            """;

    private final Map<String, CompiledMaterial> cache = new HashMap<>();

    public @Nullable CompiledMaterial resolve(MaterialModel material, @Nullable McResourceBridge bridge) {
        return resolve(material, bridge, false);
    }

    public @Nullable CompiledMaterial resolveForMesh(MaterialModel material, @Nullable McResourceBridge bridge) {
        return resolve(material, bridge, true);
    }

    private @Nullable CompiledMaterial resolve(MaterialModel material, @Nullable McResourceBridge bridge,
                                               boolean meshStage) {
        String customSource = material.customGlslFragment();
        String shaderId = material.fragmentShader();
        boolean hasCustomFrag = (customSource != null && !customSource.isBlank()) || !isBuiltinShaderId(shaderId);
        boolean hasInlineVert = material.customGlslVertex() != null && !material.customGlslVertex().isBlank();
        boolean hasResourceVert = material.vertexMaterial() != null && !material.vertexMaterial().isBlank();
        boolean hasCustomVert = hasInlineVert || hasResourceVert;
        if (!hasCustomFrag && !hasCustomVert) {
            return null;
        }
        String cacheKey = (meshStage ? "mesh\0" : "particle\0")
                + (shaderId == null ? "" : shaderId) + "\0"
                + (customSource == null ? "" : customSource) + "\0"
                + (material.vertexMaterial() == null ? "" : material.vertexMaterial()) + "\0"
                + (material.customGlslVertex() == null ? "" : material.customGlslVertex()) + "\0"
                + material.textures().keySet();
        CompiledMaterial existing = cache.get(cacheKey);
        if (existing != null) return existing.program() != 0 ? existing : null;

        String fragBody = null;
        if (hasCustomFrag) {
            fragBody = customSource;
            if ((fragBody == null || fragBody.isBlank()) && bridge != null && shaderId != null) {
                fragBody = loadShaderResource(shaderId, ".fsh", bridge);
            }
            if (fragBody == null || fragBody.isBlank()) {
                LOGGER.error("Missing OpenGL fragment shader {} for material {}", shaderId, material.name());
                cache.put(cacheKey, empty());
                return null;
            }
        }
        CompiledMaterial compiled;
        try {
            String fullFrag;
            if (hasCustomFrag) {
                String expandedFrag = fragBody;
                if (bridge != null) {
                    String fragmentSourceId = shaderId == null ? null : shaderResourceId(shaderId, ".fsh");
                    expandedFrag = DirectGlShaderImports.expand(expandedFrag, fragmentSourceId, bridge);
                }
                fullFrag = wrapFragmentSource(expandedFrag);
            } else {
                fullFrag = "lumenfx:core/color".equals(shaderId) ? DEFAULT_COLOR_FRAG : DEFAULT_PARTICLE_FRAG;
            }

            String vertSrc = meshStage ? DEFAULT_MESH_VERT : DEFAULT_PARTICLE_VERT;
            if (hasInlineVert) {
                String vertexSourceId = hasResourceVert ? shaderResourceId(material.vertexMaterial(), ".vsh") : null;
                vertSrc = bridge != null
                        ? DirectGlShaderImports.expand(material.customGlslVertex(), vertexSourceId, bridge)
                        : material.customGlslVertex();
            } else if (hasResourceVert) {
                String loadedVert = bridge != null ? loadShaderResource(material.vertexMaterial(), ".vsh", bridge) : null;
                if (loadedVert == null) {
                    if (!hasCustomFrag) {
                        LOGGER.error("Missing OpenGL vertex shader {} for material {}",
                                material.vertexMaterial(), material.name());
                        cache.put(cacheKey, empty());
                        return null;
                    }
                } else {
                    String vertexSourceId = shaderResourceId(material.vertexMaterial(), ".vsh");
                    String expandedVert = DirectGlShaderImports.expand(loadedVert, vertexSourceId, bridge);
                    if (DirectGlGlslPreprocessor.hasMainFunction(expandedVert)) {
                        vertSrc = expandedVert;
                    } else if (!hasCustomFrag) {
                        LOGGER.error("OpenGL vertex shader {} lacks main() for material {}",
                                material.vertexMaterial(), material.name());
                        cache.put(cacheKey, empty());
                        return null;
                    }
                }
            }
            compiled = compileMaterial(vertSrc, fullFrag, material, shaderId, meshStage);
        } catch (IllegalArgumentException failure) {
            LOGGER.error("Could not prepare OpenGL material {} ({})", material.name(), shaderId, failure);
            compiled = empty();
        }
        cache.put(cacheKey, compiled);
        return compiled.program() != 0 ? compiled : null;
    }

    /** Builds a valid GLSL 150 Core fragment source from either a full shader (including leading comments) or a snippet. */
    public static String wrapFragmentSource(String source) {
        String cleaned = DirectGlGlslPreprocessor.stripLeadingBom(source);
        if (DirectGlGlslPreprocessor.hasLeadingVersionDirective(cleaned)) {
            return cleaned;
        }
        DirectGlGlslPreprocessor.LeadingExtensions leading = DirectGlGlslPreprocessor.extractLeadingExtensions(cleaned);
        String body = leading.remainingSource();
        int firstNewline = FRAG_PREAMBLE.indexOf('\n');
        int startLineArg = DirectGlGlslPreprocessor.tryQueryLiveGlsl150LineOffsetsByOne() ? 0 : 1;
        StringBuilder out = new StringBuilder(FRAG_PREAMBLE.length() + leading.extensionBlock().length()
                + body.length() + 48);
        out.append(FRAG_PREAMBLE, 0, firstNewline + 1);
        out.append(leading.extensionBlock());
        out.append(FRAG_PREAMBLE, firstNewline + 1, FRAG_PREAMBLE.length());
        if (DirectGlGlslPreprocessor.hasMainFunction(cleaned)) {
            out.append("#line ").append(startLineArg).append(" 0\n").append(body);
            if (!body.endsWith("\n")) {
                out.append('\n');
            }
        } else {
            out.append("void main() {\n#line ").append(startLineArg).append(" 0\n").append(body);
            if (!body.endsWith("\n")) {
                out.append('\n');
            }
            out.append("}\n");
        }
        return out.toString();
    }

    /**
     * Adapts GLSL 330 Core shaders using {@code layout(location = N)} down to strict GLSL 150 Core
     * for OpenGL 3.2 Core contexts, collecting explicit attribute and fragment-output bindings.
     */
    public static String downgradeGlsl330To150(String source, boolean vertexStage,
                                              Map<String, Integer> attribBindings,
                                              Map<String, Integer> fragBindings) {
        return DirectGlGlslPreprocessor.downgradeGlsl330To150(source, vertexStage, attribBindings, fragBindings);
    }

    /**
     * Adapts GLSL 330 Core shaders using {@code layout(location = N[, index = I])} down to strict
     * GLSL 150 Core, also collecting explicit dual-source fragment-output {@code index} bindings.
     */
    public static String downgradeGlsl330To150(String source, boolean vertexStage,
                                              Map<String, Integer> attribBindings,
                                              Map<String, Integer> fragBindings,
                                              Map<String, Integer> fragIndexBindings) {
        return DirectGlGlslPreprocessor.downgradeGlsl330To150(
                source, vertexStage, attribBindings, fragBindings, fragIndexBindings);
    }

    private static boolean isStrictGl32Context() {
        try {
            GLCapabilities caps = GL.getCapabilities();
            return caps != null && !caps.OpenGL33;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isBuiltinShaderId(@Nullable String id) {
        if (id == null || id.isBlank()) return true;
        return id.equals("lumenfx:core/color") || id.equals("lumenfx:core/effect");
    }

    private static @Nullable String loadShaderResource(String shaderId, String ext, McResourceBridge bridge) {
        byte[] bytes = bridge.readResource(shaderResourceId(shaderId, ext));
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private static String shaderResourceId(String shaderId, String ext) {
        String id = shaderId.contains(":") ? shaderId : ("lumenfx:" + shaderId);
        int colon = id.indexOf(':');
        String ns = id.substring(0, colon);
        String path = id.substring(colon + 1);
        String resPath = path.startsWith("shaders/") ? path : ("shaders/" + path);
        if (!resPath.endsWith(ext)) resPath = resPath + ext;
        return ns + ":" + resPath;
    }

    private static CompiledMaterial compileMaterial(String vertSrc, String fragSrc, MaterialModel material,
                                                    String shaderId, boolean meshStage) {
        Map<String, Integer> attribBindings = new LinkedHashMap<>();
        if (meshStage) {
            attribBindings.put("aPos", 0);
            attribBindings.put("aNormal", 1);
            attribBindings.put("aUv", 2);
            attribBindings.put("aColor", 3);
        } else {
            attribBindings.put("aPos", 0);
            attribBindings.put("aUv", 1);
            attribBindings.put("aColor", 2);
            attribBindings.put("aParticle", 3);
            attribBindings.put("aCustom", 4);
            attribBindings.put("aNextSubImage", 5);
            attribBindings.put("aUv1", 6);
        }
        Map<String, Integer> fragBindings = new LinkedHashMap<>();
        fragBindings.put("fragColor", 0);
        Map<String, Integer> fragIndexBindings = new LinkedHashMap<>();

        String effectiveVert = vertSrc;
        String effectiveFrag = fragSrc;
        if (isStrictGl32Context()) {
            effectiveVert = downgradeGlsl330To150(effectiveVert, true, attribBindings, fragBindings, fragIndexBindings);
            effectiveFrag = downgradeGlsl330To150(effectiveFrag, false, attribBindings, fragBindings, fragIndexBindings);
        }

        int vs = compileShader(GL20C.GL_VERTEX_SHADER, effectiveVert, shaderId);
        if (vs == 0) return empty();
        int fs = compileShader(GL20C.GL_FRAGMENT_SHADER, effectiveFrag, shaderId);
        if (fs == 0) {
            GL20C.glDeleteShader(vs);
            return empty();
        }
        int prog = GL20C.glCreateProgram();
        GL20C.glAttachShader(prog, vs);
        GL20C.glAttachShader(prog, fs);
        for (Map.Entry<String, Integer> e : attribBindings.entrySet()) {
            GL20C.glBindAttribLocation(prog, e.getValue(), e.getKey());
        }
        for (Map.Entry<String, Integer> e : fragBindings.entrySet()) {
            Integer index = fragIndexBindings.get(e.getKey());
            if (index != null && index > 0) {
                bindFragDataLocationIndexed(prog, e.getValue(), index, e.getKey(), shaderId);
            } else {
                GL30C.glBindFragDataLocation(prog, e.getValue(), e.getKey());
            }
        }
        GL20C.glLinkProgram(prog);
        GL20C.glDeleteShader(vs);
        GL20C.glDeleteShader(fs);
        if (GL20C.glGetProgrami(prog, GL20C.GL_LINK_STATUS) == 0) {
            LOGGER.error("Could not link OpenGL material {}: {}", shaderId, GL20C.glGetProgramInfoLog(prog));
            GL20C.glDeleteProgram(prog);
            return empty();
        }
        Map<String, Integer> samplers = new LinkedHashMap<>();
        for (String sampler : material.textures().keySet()) {
            int location = GL20C.glGetUniformLocation(prog, sampler);
            if (location >= 0) samplers.put(sampler, location);
        }
        return new CompiledMaterial(
                prog,
                GL20C.glGetUniformLocation(prog, "uViewProj"),
                GL20C.glGetUniformLocation(prog, "uModel"),
                GL20C.glGetUniformLocation(prog, "uTime"),
                GL20C.glGetUniformLocation(prog, "uHdr"),
                GL20C.glGetUniformLocation(prog, "uSoftFade"),
                GL20C.glGetUniformLocation(prog, "uNearFar"),
                GL20C.glGetUniformLocation(prog, "uInvViewport"),
                GL20C.glGetUniformLocation(prog, "uParams"),
                GL20C.glGetUniformLocation(prog, "uTint"),
                GL20C.glGetUniformLocation(prog, "uTex"),
                GL20C.glGetUniformLocation(prog, "uTex1"),
                GL20C.glGetUniformLocation(prog, "uSceneDepth"),
                GL20C.glGetUniformLocation(prog, "uSceneColor"),
                GL31C.glGetUniformBlockIndex(prog, "EffectFrame"), Map.copyOf(samplers)
        );
    }

    private static void bindFragDataLocationIndexed(int program, int colorNumber, int index,
                                                    String name, @Nullable String shaderId) {
        GLCapabilities caps = GL.getCapabilities();
        if (caps != null && caps.OpenGL33) {
            GL33C.glBindFragDataLocationIndexed(program, colorNumber, index, name);
        } else if (caps != null && caps.GL_ARB_blend_func_extended) {
            ARBBlendFuncExtended.glBindFragDataLocationIndexed(program, colorNumber, index, name);
        } else {
            LOGGER.warn("OpenGL context lacks GL_ARB_blend_func_extended for fragment output {} index={} in {}",
                    name, index, shaderId);
            GL30C.glBindFragDataLocation(program, colorNumber, name);
        }
    }

    private static int compileShader(int type, String src, String shaderId) {
        int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, src);
        GL20C.glCompileShader(shader);
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == 0) {
            LOGGER.error("Could not compile OpenGL shader {}: {}", shaderId, GL20C.glGetShaderInfoLog(shader));
            GL20C.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private static CompiledMaterial empty() {
        return new CompiledMaterial(0, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, Map.of());
    }

    public void clear() {
        for (CompiledMaterial m : cache.values()) {
            if (m.program() != 0) GL20C.glDeleteProgram(m.program());
        }
        cache.clear();
    }
}
