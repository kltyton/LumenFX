package com.kltyton.lumenfx.runtime.gl;

import org.lwjgl.opengl.GL20C;

/** Compiles and owns the GLSL 330 Core graphics programs for soft particles/ribbons and 3D static meshes. */
public final class DirectGlShaderProgram {
    private static final String PARTICLE_VERT = """
            #version 330 core
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec2 aUv;
            layout(location = 2) in vec4 aColor;
            uniform mat4 uViewProj;
            out vec2 vUv;
            out vec4 vColor;
            void main() {
                vUv = aUv;
                vColor = aColor;
                gl_Position = uViewProj * vec4(aPos, 1.0);
            }
            """;

    private static final String PARTICLE_FRAG = """
            #version 330 core
            in vec2 vUv;
            in vec4 vColor;
            uniform sampler2D uTex;
            uniform sampler2D uSceneDepth;
            uniform float uHdr;
            uniform float uSoftFade;
            uniform vec2 uNearFar;
            uniform vec2 uInvViewport;
            out vec4 fragColor;
            float linearDepth(float d) {
                float z = d * 2.0 - 1.0;
                return (2.0 * uNearFar.x * uNearFar.y) / max(uNearFar.y + uNearFar.x - z * (uNearFar.y - uNearFar.x), 0.0001);
            }
            void main() {
                vec4 sampled = texture(uTex, vUv);
                vec4 outCol = sampled * vColor;
                if (uSoftFade > 0.0001) {
                    float sceneZ = linearDepth(texture(uSceneDepth, gl_FragCoord.xy * uInvViewport).r);
                    float fragZ = linearDepth(gl_FragCoord.z);
                    outCol.a *= clamp((sceneZ - fragZ) / uSoftFade, 0.0, 1.0);
                }
                outCol.rgb *= uHdr;
                if (outCol.a <= 0.002) discard;
                fragColor = outCol;
            }
            """;

    private static final String MESH_VERT = """
            #version 330 core
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec3 aNormal;
            layout(location = 2) in vec2 aUv;
            layout(location = 3) in vec4 aColor;
            uniform mat4 uViewProj;
            uniform mat4 uModel;
            out vec3 vNormal;
            out vec2 vUv;
            out vec4 vColor;
            void main() {
                vNormal = mat3(uModel) * aNormal;
                vUv = aUv;
                vColor = aColor;
                gl_Position = uViewProj * uModel * vec4(aPos, 1.0);
            }
            """;

    private static final String MESH_FRAG = """
            #version 330 core
            in vec3 vNormal;
            in vec2 vUv;
            in vec4 vColor;
            uniform sampler2D uTex;
            uniform vec4 uTint;
            uniform float uHdr;
            out vec4 fragColor;
            void main() {
                vec3 n = length(vNormal) > 0.0001 ? normalize(vNormal) : vec3(0.0, 1.0, 0.0);
                float ndl = max(dot(n, normalize(vec3(0.35, 0.85, 0.40))), 0.0);
                float shade = 0.55 + 0.45 * ndl;
                vec4 sampled = texture(uTex, vUv);
                vec4 outCol = sampled * vColor * uTint;
                outCol.rgb *= shade * uHdr;
                if (outCol.a <= 0.002) discard;
                fragColor = outCol;
            }
            """;

    public int particleProgram;
    public int particleViewProjLoc;
    public int particleTexLoc;
    public int particleSceneDepthLoc;
    public int particleHdrLoc;
    public int particleSoftFadeLoc;
    public int particleNearFarLoc;
    public int particleInvViewportLoc;

    public int meshProgram;
    public int meshViewProjLoc;
    public int meshModelLoc;
    public int meshTexLoc;
    public int meshTintLoc;
    public int meshHdrLoc;

    public void ensureCompiled() {
        if (particleProgram != 0 && meshProgram != 0) return;
        particleProgram = link(PARTICLE_VERT, PARTICLE_FRAG);
        particleViewProjLoc = GL20C.glGetUniformLocation(particleProgram, "uViewProj");
        particleTexLoc = GL20C.glGetUniformLocation(particleProgram, "uTex");
        particleSceneDepthLoc = GL20C.glGetUniformLocation(particleProgram, "uSceneDepth");
        particleHdrLoc = GL20C.glGetUniformLocation(particleProgram, "uHdr");
        particleSoftFadeLoc = GL20C.glGetUniformLocation(particleProgram, "uSoftFade");
        particleNearFarLoc = GL20C.glGetUniformLocation(particleProgram, "uNearFar");
        particleInvViewportLoc = GL20C.glGetUniformLocation(particleProgram, "uInvViewport");

        meshProgram = link(MESH_VERT, MESH_FRAG);
        meshViewProjLoc = GL20C.glGetUniformLocation(meshProgram, "uViewProj");
        meshModelLoc = GL20C.glGetUniformLocation(meshProgram, "uModel");
        meshTexLoc = GL20C.glGetUniformLocation(meshProgram, "uTex");
        meshTintLoc = GL20C.glGetUniformLocation(meshProgram, "uTint");
        meshHdrLoc = GL20C.glGetUniformLocation(meshProgram, "uHdr");
    }

    private static int link(String vertSrc, String fragSrc) {
        int vs = compile(GL20C.GL_VERTEX_SHADER, vertSrc);
        int fs = compile(GL20C.GL_FRAGMENT_SHADER, fragSrc);
        int prog = GL20C.glCreateProgram();
        GL20C.glAttachShader(prog, vs);
        GL20C.glAttachShader(prog, fs);
        GL20C.glLinkProgram(prog);
        GL20C.glDeleteShader(vs);
        GL20C.glDeleteShader(fs);
        if (GL20C.glGetProgrami(prog, GL20C.GL_LINK_STATUS) == 0) {
            String log = GL20C.glGetProgramInfoLog(prog, 4096);
            GL20C.glDeleteProgram(prog);
            throw new IllegalStateException("Failed to link LumenFX GL shader: " + log);
        }
        return prog;
    }

    private static int compile(int type, String src) {
        int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, src);
        GL20C.glCompileShader(shader);
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == 0) {
            String log = GL20C.glGetShaderInfoLog(shader, 4096);
            GL20C.glDeleteShader(shader);
            throw new IllegalStateException("Failed to compile LumenFX GL shader: " + log);
        }
        return shader;
    }

    public void dispose() {
        if (particleProgram != 0) {
            GL20C.glDeleteProgram(particleProgram);
            particleProgram = 0;
        }
        if (meshProgram != 0) {
            GL20C.glDeleteProgram(meshProgram);
            meshProgram = 0;
        }
    }
}
