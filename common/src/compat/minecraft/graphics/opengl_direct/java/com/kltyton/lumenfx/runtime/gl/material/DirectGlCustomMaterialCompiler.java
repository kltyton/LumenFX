package com.kltyton.lumenfx.runtime.gl.material;

import com.kltyton.lumenfx.runtime.bridge.McResourceBridge;
import com.kltyton.lumenfx.runtime.model.MaterialModel;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL31C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compiles and caches custom GLSL 330 Core material shaders for {@link MaterialModel} definitions
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
            #version 330 core
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec2 aUv;
            layout(location = 2) in vec4 aColor;
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
            layout(location = 3) in vec4 aParticle;
            layout(location = 4) in vec4 aCustom;
            layout(location = 5) in vec3 aNextSubImage;
            void main() {
                vUv = aUv;
                vColor = aColor;
                vWorldPos = aPos;
                effectUV = aUv;
                effectUV1 = aUv;
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
            #version 330 core
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

    private final Map<String, CompiledMaterial> cache = new HashMap<>();

    public @Nullable CompiledMaterial resolve(MaterialModel material, @Nullable McResourceBridge bridge) {
        String customSource = material.customGlslFragment();
        String shaderId = material.fragmentShader();
        if ((customSource == null || customSource.isBlank()) && isBuiltinShaderId(shaderId)) {
            return null;
        }
        String cacheKey = (customSource != null && !customSource.isBlank())
                ? "inline:" + customSource
                : "res:" + shaderId;
        cacheKey += "\n" + material.vertexMaterial() + "\n" + material.customGlslVertex() + "\n" + material.textures().keySet();
        CompiledMaterial existing = cache.get(cacheKey);
        if (existing != null) return existing.program() != 0 ? existing : null;

        String fragBody = customSource;
        if ((fragBody == null || fragBody.isBlank()) && bridge != null && shaderId != null) {
            fragBody = loadShaderResource(shaderId, ".fsh", bridge);
        }
        if (fragBody == null || fragBody.isBlank()) {
            LOGGER.error("Missing OpenGL fragment shader {} for material {}", shaderId, material.name());
            cache.put(cacheKey, empty());
            return null;
        }
        String vertSrc = DEFAULT_PARTICLE_VERT;
        String vertexSourceId = null;
        if (material.customGlslVertex() != null && !material.customGlslVertex().isBlank()) {
            vertSrc = material.customGlslVertex();
        } else if (material.vertexMaterial() != null && !material.vertexMaterial().isBlank() && bridge != null) {
            String loadedVert = loadShaderResource(material.vertexMaterial(), ".vsh", bridge);
            if (loadedVert != null && loadedVert.contains("void main")) {
                vertSrc = loadedVert;
                vertexSourceId = shaderResourceId(material.vertexMaterial(), ".vsh");
            }
        }
        CompiledMaterial compiled;
        try {
            String fullFrag = wrapFragmentSource(fragBody);
            if (bridge != null) {
                String fragmentSourceId = shaderId == null ? null : shaderResourceId(shaderId, ".fsh");
                fullFrag = DirectGlShaderImports.expand(fullFrag, fragmentSourceId, bridge);
                vertSrc = DirectGlShaderImports.expand(vertSrc, vertexSourceId, bridge);
            }
            compiled = compileMaterial(vertSrc, fullFrag, material, shaderId);
        } catch (IllegalArgumentException failure) {
            LOGGER.error("Could not prepare OpenGL material {} ({})", material.name(), shaderId, failure);
            compiled = empty();
        }
        cache.put(cacheKey, compiled);
        return compiled.program() != 0 ? compiled : null;
    }

    /** Builds a valid GLSL 330 Core fragment source from either a full shader or a custom snippet. */
    public static String wrapFragmentSource(String source) {
        String trimmed = source.trim();
        if (trimmed.startsWith("#version")) {
            return trimmed;
        }
        if (trimmed.contains("void main(")) {
            return FRAG_PREAMBLE + "\n" + trimmed;
        }
        return FRAG_PREAMBLE + "\nvoid main() {\n" + trimmed + "\n}\n";
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

    private static CompiledMaterial compileMaterial(String vertSrc, String fragSrc, MaterialModel material, String shaderId) {
        int vs = compileShader(GL20C.GL_VERTEX_SHADER, vertSrc, shaderId);
        if (vs == 0) return empty();
        int fs = compileShader(GL20C.GL_FRAGMENT_SHADER, fragSrc, shaderId);
        if (fs == 0) {
            GL20C.glDeleteShader(vs);
            return empty();
        }
        int prog = GL20C.glCreateProgram();
        GL20C.glAttachShader(prog, vs);
        GL20C.glAttachShader(prog, fs);
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
