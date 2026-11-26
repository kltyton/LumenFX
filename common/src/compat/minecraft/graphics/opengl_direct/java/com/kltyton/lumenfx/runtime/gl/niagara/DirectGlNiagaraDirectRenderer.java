package com.kltyton.lumenfx.runtime.gl.niagara;

import com.kltyton.lumenfx.runtime.gl.DirectGlMeshCache;
import com.kltyton.lumenfx.runtime.gl.DirectGlMeshCache.GpuMesh;
import com.kltyton.lumenfx.runtime.model.MaterialModel;
import com.kltyton.lumenfx.runtime.model.NiagaraSystemModel.RendererKind;
import com.kltyton.lumenfx.runtime.model.StaticMeshModel;
import java.nio.FloatBuffer;
import javax.annotation.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;

/**
 * Zero-readback OpenGL 4.3+ GPU-direct renderer for Niagara GPU emitters (sprites, ribbons, meshes)
 * reading directly from live compute SSBOs with GPU bitonic distance sorting.
 * Completely eliminates the legacy 2,048-particle CPU readback cap and per-frame GPU-to-CPU stalls.
 */
public final class DirectGlNiagaraDirectRenderer {
    public record GpuDrawDescriptor(int floatSsbo, int intSsbo, int halfSsbo,
                                    int stride, int liveCount,
                                    int posComp, int colComp, int sizeComp, int widthComp,
                                    int rotComp, int velComp, int ageComp, int lifeComp,
                                    boolean localSpace, double originX, double originY, double originZ,
                                    RendererKind kind, MaterialModel material,
                                    @Nullable StaticMeshModel meshModel) { }

    private static final String SORT_INIT_COMP = """
            #version 430 core
            layout(local_size_x = 128) in;
            layout(std430, binding = 0) readonly buffer InputFloat { float fData[]; };
            layout(std430, binding = 3) writeonly buffer SortedIndices { uint indices[]; };
            layout(std430, binding = 4) writeonly buffer SortKeys { float keys[]; };
            uniform int uStride;
            uniform int uCount;
            uniform int uPaddedCount;
            uniform int uPosComp;
            uniform int uLocalSpace;
            uniform vec3 uOrigin;
            uniform vec3 uCamPos;
            void main() {
                uint id = gl_GlobalInvocationID.x;
                if (int(id) >= uPaddedCount) return;
                indices[id] = id;
                if (int(id) >= uCount || uPosComp < 0) {
                    keys[id] = -1.0e30;
                    return;
                }
                float ux = fData[uPosComp * uStride + int(id)];
                float uy = fData[(uPosComp + 1) * uStride + int(id)];
                float uz = fData[(uPosComp + 2) * uStride + int(id)];
                vec3 worldPos = vec3(ux * 0.01, uz * 0.01, uy * 0.01);
                if (uLocalSpace != 0) worldPos += uOrigin;
                vec3 d = worldPos - uCamPos;
                keys[id] = dot(d, d);
            }
            """;

    private static final String SORT_STEP_COMP = """
            #version 430 core
            layout(local_size_x = 128) in;
            layout(std430, binding = 3) buffer SortedIndices { uint indices[]; };
            layout(std430, binding = 4) buffer SortKeys { float keys[]; };
            uniform int uPaddedCount;
            uniform int uJ;
            uniform int uK;
            void main() {
                uint i = gl_GlobalInvocationID.x;
                if (int(i) >= uPaddedCount) return;
                uint ixj = i ^ uint(uJ);
                if (ixj > i && int(ixj) < uPaddedCount) {
                    bool descending = (i & uint(uK)) == 0u;
                    float ki = keys[i];
                    float kj = keys[ixj];
                    if ((descending && ki < kj) || (!descending && ki > kj)) {
                        keys[i] = kj;
                        keys[ixj] = ki;
                        uint tmp = indices[i];
                        indices[i] = indices[ixj];
                        indices[ixj] = tmp;
                    }
                }
            }
            """;

    private static final String GPU_SPRITE_VERT = """
            #version 430 core
            layout(std430, binding = 0) readonly buffer InputFloat { float fData[]; };
            layout(std430, binding = 3) readonly buffer SortedIndices { uint sortedIdx[]; };
            uniform mat4 uViewProj;
            uniform vec3 uCamRight;
            uniform vec3 uCamUp;
            uniform vec3 uCamPos;
            uniform vec3 uOrigin;
            uniform int uLocalSpace;
            uniform int uStride;
            uniform int uCount;
            uniform int uUseSorted;
            uniform int uPosComp;
            uniform int uColComp;
            uniform int uSizeComp;
            uniform int uRotComp;
            uniform int uVelComp;
            uniform vec4 uTint;
            out vec2 vUv;
            out vec4 vColor;
            const vec2 CORNERS[6] = vec2[6](
                vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(1.0, 1.0),
                vec2(-1.0, -1.0), vec2(1.0, 1.0), vec2(-1.0, 1.0)
            );
            const vec2 UVS[6] = vec2[6](
                vec2(0.0, 1.0), vec2(1.0, 1.0), vec2(1.0, 0.0),
                vec2(0.0, 1.0), vec2(1.0, 0.0), vec2(0.0, 0.0)
            );
            void main() {
                int idx = uUseSorted != 0 ? int(sortedIdx[gl_InstanceID]) : gl_InstanceID;
                if (idx < 0 || idx >= uCount || uPosComp < 0) {
                    gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
                    vUv = vec2(0.0);
                    vColor = vec4(0.0);
                    return;
                }
                float ux = fData[uPosComp * uStride + idx];
                float uy = fData[(uPosComp + 1) * uStride + idx];
                float uz = fData[(uPosComp + 2) * uStride + idx];
                vec3 worldPos = vec3(ux * 0.01, uz * 0.01, uy * 0.01);
                if (uLocalSpace != 0) worldPos += uOrigin;
                vec3 relPos = worldPos - uCamPos;

                float sz = 0.25;
                if (uSizeComp >= 0) {
                    float raw = fData[uSizeComp * uStride + idx];
                    if (raw > 0.0) sz = raw > 4.0 ? raw * 0.01 : raw;
                }
                float hw = clamp(sz * 0.5, 0.01, 16.0);
                float rot = uRotComp >= 0 ? radians(fData[uRotComp * uStride + idx]) : 0.0;
                float c = cos(rot), s = sin(rot);
                vec3 rx = (uCamRight * c + uCamUp * s) * hw;
                vec3 uxVec = (uCamUp * c - uCamRight * s) * hw;

                vec2 corner = CORNERS[gl_VertexID];
                vec3 vPos = relPos + rx * corner.x + uxVec * corner.y;
                vUv = UVS[gl_VertexID];

                vec4 col = vec4(1.0);
                if (uColComp >= 0) {
                    col = vec4(
                        fData[uColComp * uStride + idx],
                        fData[(uColComp + 1) * uStride + idx],
                        fData[(uColComp + 2) * uStride + idx],
                        fData[(uColComp + 3) * uStride + idx]
                    );
                }
                vColor = col * uTint;
                gl_Position = uViewProj * vec4(vPos, 1.0);
            }
            """;

    private static final String GPU_RIBBON_VERT = """
            #version 430 core
            layout(std430, binding = 0) readonly buffer InputFloat { float fData[]; };
            uniform mat4 uViewProj;
            uniform vec3 uCamForward;
            uniform vec3 uCamRight;
            uniform vec3 uCamPos;
            uniform vec3 uOrigin;
            uniform int uLocalSpace;
            uniform int uStride;
            uniform int uCount;
            uniform int uPosComp;
            uniform int uColComp;
            uniform int uWidthComp;
            uniform vec4 uTint;
            out vec2 vUv;
            out vec4 vColor;
            vec3 readPos(int i) {
                float ux = fData[uPosComp * uStride + i];
                float uy = fData[(uPosComp + 1) * uStride + i];
                float uz = fData[(uPosComp + 2) * uStride + i];
                vec3 p = vec3(ux * 0.01, uz * 0.01, uy * 0.01);
                return (uLocalSpace != 0 ? p + uOrigin : p) - uCamPos;
            }
            vec4 readColor(int i) {
                if (uColComp < 0) return vec4(1.0);
                return vec4(
                    fData[uColComp * uStride + i],
                    fData[(uColComp + 1) * uStride + i],
                    fData[(uColComp + 2) * uStride + i],
                    fData[(uColComp + 3) * uStride + i]
                );
            }
            float readHalfWidth(int i) {
                if (uWidthComp < 0) return 0.15;
                float w = fData[uWidthComp * uStride + i];
                if (w <= 0.0) return 0.15;
                return clamp((w > 4.0 ? w * 0.01 : w) * 0.5, 0.01, 12.0);
            }
            void main() {
                int a = gl_InstanceID;
                int b = a + 1;
                if (b >= uCount || uPosComp < 0) {
                    gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
                    vUv = vec2(0.0);
                    vColor = vec4(0.0);
                    return;
                }
                vec3 pa = readPos(a);
                vec3 pb = readPos(b);
                vec3 dir = pb - pa;
                vec3 side = cross(dir, uCamForward);
                float sl = length(side);
                side = sl > 1e-4 ? side / sl : uCamRight;
                float wa = readHalfWidth(a);
                float wb = readHalfWidth(b);
                float u0 = float(a) / float(max(1, uCount - 1));
                float u1 = float(b) / float(max(1, uCount - 1));
                vec4 ca = readColor(a) * uTint;
                vec4 cb = readColor(b) * uTint;

                vec3 pos;
                if (gl_VertexID == 0 || gl_VertexID == 3) { pos = pa - side * wa; vUv = vec2(u0, 0.0); vColor = ca; }
                else if (gl_VertexID == 1) { pos = pa + side * wa; vUv = vec2(u0, 1.0); vColor = ca; }
                else if (gl_VertexID == 2 || gl_VertexID == 4) { pos = pb + side * wb; vUv = vec2(u1, 1.0); vColor = cb; }
                else { pos = pb - side * wb; vUv = vec2(u1, 0.0); vColor = cb; }
                gl_Position = uViewProj * vec4(pos, 1.0);
            }
            """;

    private static final String GPU_MESH_VERT = """
            #version 430 core
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec3 aNormal;
            layout(location = 2) in vec2 aUv;
            layout(location = 3) in vec4 aVertColor;
            layout(std430, binding = 0) readonly buffer InputFloat { float fData[]; };
            uniform mat4 uViewProj;
            uniform vec3 uCamPos;
            uniform vec3 uOrigin;
            uniform float uUnitScale;
            uniform int uLocalSpace;
            uniform int uStride;
            uniform int uCount;
            uniform int uPosComp;
            uniform int uColComp;
            uniform int uSizeComp;
            uniform int uRotComp;
            uniform vec4 uTint;
            out vec3 vNormal;
            out vec2 vUv;
            out vec4 vColor;
            void main() {
                int idx = gl_InstanceID;
                if (idx >= uCount || uPosComp < 0) {
                    gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
                    vNormal = vec3(0.0, 1.0, 0.0);
                    vUv = vec2(0.0);
                    vColor = vec4(0.0);
                    return;
                }
                float ux = fData[uPosComp * uStride + idx];
                float uy = fData[(uPosComp + 1) * uStride + idx];
                float uz = fData[(uPosComp + 2) * uStride + idx];
                vec3 p = vec3(ux * 0.01, uz * 0.01, uy * 0.01);
                if (uLocalSpace != 0) p += uOrigin;
                vec3 rel = p - uCamPos;
                float sz = 1.0;
                if (uSizeComp >= 0) {
                    float raw = fData[uSizeComp * uStride + idx];
                    if (raw > 0.0) sz = raw > 4.0 ? raw * 0.01 : raw;
                }
                float ang = uRotComp >= 0 ? radians(fData[uRotComp * uStride + idx]) : 0.0;
                float c = cos(ang), s = sin(ang);
                vec3 scaled = aPos * (sz * uUnitScale);
                vec3 rotPos = vec3(scaled.x * c - scaled.z * s, scaled.y, scaled.x * s + scaled.z * c);
                vNormal = vec3(aNormal.x * c - aNormal.z * s, aNormal.y, aNormal.x * s + aNormal.z * c);
                vUv = aUv;
                vec4 col = vec4(1.0);
                if (uColComp >= 0) {
                    col = vec4(
                        fData[uColComp * uStride + idx],
                        fData[(uColComp + 1) * uStride + idx],
                        fData[(uColComp + 2) * uStride + idx],
                        fData[(uColComp + 3) * uStride + idx]
                    );
                }
                vColor = aVertColor * col * uTint;
                gl_Position = uViewProj * vec4(rel + rotPos, 1.0);
            }
            """;

    private static final String GPU_PARTICLE_FRAG = """
            #version 430 core
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

    private static final String GPU_MESH_FRAG = """
            #version 430 core
            in vec3 vNormal;
            in vec2 vUv;
            in vec4 vColor;
            uniform sampler2D uTex;
            uniform float uHdr;
            out vec4 fragColor;
            void main() {
                vec3 n = length(vNormal) > 0.0001 ? normalize(vNormal) : vec3(0.0, 1.0, 0.0);
                float ndl = max(dot(n, normalize(vec3(0.35, 0.85, 0.40))), 0.0);
                vec4 outCol = texture(uTex, vUv) * vColor;
                outCol.rgb *= (0.55 + 0.45 * ndl) * uHdr;
                if (outCol.a <= 0.002) discard;
                fragColor = outCol;
            }
            """;

    private int spriteProg;
    private int ribbonProg;
    private int meshProg;
    private int sortInitProg;
    private int sortStepProg;
    private int emptyVao;
    private int sortIndicesSsbo;
    private int sortKeysSsbo;
    private int sortCapacity;
    private final FloatBuffer matBuf = MemoryUtil.memAllocFloat(16);
    private boolean initialized;
    private boolean supported;

    public static boolean isGl43Supported() {
        try {
            GLCapabilities caps = GL.getCapabilities();
            return caps != null && (caps.OpenGL43 || (caps.GL_ARB_shader_storage_buffer_object && caps.GL_ARB_compute_shader));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public boolean ensureInitialized() {
        if (initialized) return supported;
        initialized = true;
        if (!isGl43Supported()) {
            supported = false;
            return false;
        }
        try {
            spriteProg = linkGraphics(GPU_SPRITE_VERT, GPU_PARTICLE_FRAG);
            ribbonProg = linkGraphics(GPU_RIBBON_VERT, GPU_PARTICLE_FRAG);
            meshProg = linkGraphics(GPU_MESH_VERT, GPU_MESH_FRAG);
            sortInitProg = linkCompute(SORT_INIT_COMP);
            sortStepProg = linkCompute(SORT_STEP_COMP);
            emptyVao = GL30C.glGenVertexArrays();
            supported = true;
        } catch (RuntimeException ignored) {
            dispose();
            supported = false;
        }
        return supported;
    }

    /**
     * Renders an arbitrary number of live Niagara GPU particles directly from SSBO memory with
     * zero CPU readback and optional GPU bitonic distance sorting for translucent sprites.
     */
    public boolean drawDirect(GpuDrawDescriptor desc, Matrix4f viewProj,
                              Vector3f camRight, Vector3f camUp, Vector3f camForward,
                              double camX, double camY, double camZ,
                              int textureId, int sceneDepthTex, float nearPlane, float farPlane,
                              int vpWidth, int vpHeight, DirectGlMeshCache meshCache,
                              float tintR, float tintG, float tintB, float tintA) {
        if (desc.liveCount() <= 0 || desc.floatSsbo() == 0 || desc.posComp() < 0) return false;
        if (!ensureInitialized()) return false;

        GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT | GL42C.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, desc.floatSsbo());

        boolean sortSprites = desc.kind() == RendererKind.SPRITE
                && desc.material().blend().isTranslucent()
                && desc.liveCount() > 1 && desc.liveCount() <= 131072;
        if (sortSprites) {
            runGpuBitonicSort(desc, camX, camY, camZ);
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 3, sortIndicesSsbo);
        }

        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, textureId);
        if (sceneDepthTex != 0) {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, sceneDepthTex);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        }

        float hdr = desc.material().hdr() ? Math.max(1.5f, desc.material().emissiveScale()) : 1.0f;
        float softFade = (sceneDepthTex != 0 && desc.material().softParticles()) ? desc.material().softFadeDistance() : 0f;

        matBuf.clear();
        viewProj.get(matBuf);

        if (desc.kind() == RendererKind.MESH && desc.meshModel() != null) {
            GpuMesh gpu = meshCache.resolve(desc.meshModel());
            if (gpu.vao() == 0 || gpu.indexCount() <= 0) return false;
            GL20C.glUseProgram(meshProg);
            GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(meshProg, "uViewProj"), false, matBuf);
            GL20C.glUniform3f(GL20C.glGetUniformLocation(meshProg, "uCamPos"), (float) camX, (float) camY, (float) camZ);
            GL20C.glUniform3f(GL20C.glGetUniformLocation(meshProg, "uOrigin"), (float) desc.originX(), (float) desc.originY(), (float) desc.originZ());
            GL20C.glUniform1f(GL20C.glGetUniformLocation(meshProg, "uUnitScale"), gpu.unitScale());
            GL20C.glUniform1i(GL20C.glGetUniformLocation(meshProg, "uLocalSpace"), desc.localSpace() ? 1 : 0);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(meshProg, "uStride"), desc.stride());
            GL20C.glUniform1i(GL20C.glGetUniformLocation(meshProg, "uCount"), desc.liveCount());
            GL20C.glUniform1i(GL20C.glGetUniformLocation(meshProg, "uPosComp"), desc.posComp());
            GL20C.glUniform1i(GL20C.glGetUniformLocation(meshProg, "uColComp"), desc.colComp());
            GL20C.glUniform1i(GL20C.glGetUniformLocation(meshProg, "uSizeComp"), desc.sizeComp());
            GL20C.glUniform1i(GL20C.glGetUniformLocation(meshProg, "uRotComp"), desc.rotComp());
            GL20C.glUniform4f(GL20C.glGetUniformLocation(meshProg, "uTint"), tintR, tintG, tintB, tintA);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(meshProg, "uTex"), 0);
            GL20C.glUniform1f(GL20C.glGetUniformLocation(meshProg, "uHdr"), hdr);

            GL30C.glBindVertexArray(gpu.vao());
            GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, gpu.ebo());
            GL31C.glDrawElementsInstanced(GL11C.GL_TRIANGLES, gpu.indexCount(), GL11C.GL_UNSIGNED_INT, 0L, desc.liveCount());
            GL30C.glBindVertexArray(0);
            return true;
        }

        if (desc.kind() == RendererKind.RIBBON && desc.liveCount() >= 2) {
            GL20C.glUseProgram(ribbonProg);
            GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(ribbonProg, "uViewProj"), false, matBuf);
            GL20C.glUniform3f(GL20C.glGetUniformLocation(ribbonProg, "uCamForward"), camForward.x, camForward.y, camForward.z);
            GL20C.glUniform3f(GL20C.glGetUniformLocation(ribbonProg, "uCamRight"), camRight.x, camRight.y, camRight.z);
            GL20C.glUniform3f(GL20C.glGetUniformLocation(ribbonProg, "uCamPos"), (float) camX, (float) camY, (float) camZ);
            GL20C.glUniform3f(GL20C.glGetUniformLocation(ribbonProg, "uOrigin"), (float) desc.originX(), (float) desc.originY(), (float) desc.originZ());
            GL20C.glUniform1i(GL20C.glGetUniformLocation(ribbonProg, "uLocalSpace"), desc.localSpace() ? 1 : 0);
            GL20C.glUniform1i(GL20C.glGetUniformLocation(ribbonProg, "uStride"), desc.stride());
            GL20C.glUniform1i(GL20C.glGetUniformLocation(ribbonProg, "uCount"), desc.liveCount());
            GL20C.glUniform1i(GL20C.glGetUniformLocation(ribbonProg, "uPosComp"), desc.posComp());
            GL20C.glUniform1i(GL20C.glGetUniformLocation(ribbonProg, "uColComp"), desc.colComp());
            GL20C.glUniform1i(GL20C.glGetUniformLocation(ribbonProg, "uWidthComp"), desc.widthComp() >= 0 ? desc.widthComp() : desc.sizeComp());
            GL20C.glUniform4f(GL20C.glGetUniformLocation(ribbonProg, "uTint"), tintR, tintG, tintB, tintA);
            bindFragmentCommon(ribbonProg, hdr, softFade, nearPlane, farPlane, vpWidth, vpHeight);

            GL30C.glBindVertexArray(emptyVao);
            GL31C.glDrawArraysInstanced(GL11C.GL_TRIANGLES, 0, 6, desc.liveCount() - 1);
            GL30C.glBindVertexArray(0);
            return true;
        }

        // Default: GPU-direct instanced sprites (unlimited particles up to GPU VRAM)
        GL20C.glUseProgram(spriteProg);
        GL20C.glUniformMatrix4fv(GL20C.glGetUniformLocation(spriteProg, "uViewProj"), false, matBuf);
        GL20C.glUniform3f(GL20C.glGetUniformLocation(spriteProg, "uCamRight"), camRight.x, camRight.y, camRight.z);
        GL20C.glUniform3f(GL20C.glGetUniformLocation(spriteProg, "uCamUp"), camUp.x, camUp.y, camUp.z);
        GL20C.glUniform3f(GL20C.glGetUniformLocation(spriteProg, "uCamPos"), (float) camX, (float) camY, (float) camZ);
        GL20C.glUniform3f(GL20C.glGetUniformLocation(spriteProg, "uOrigin"), (float) desc.originX(), (float) desc.originY(), (float) desc.originZ());
        GL20C.glUniform1i(GL20C.glGetUniformLocation(spriteProg, "uLocalSpace"), desc.localSpace() ? 1 : 0);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(spriteProg, "uStride"), desc.stride());
        GL20C.glUniform1i(GL20C.glGetUniformLocation(spriteProg, "uCount"), desc.liveCount());
        GL20C.glUniform1i(GL20C.glGetUniformLocation(spriteProg, "uUseSorted"), sortSprites ? 1 : 0);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(spriteProg, "uPosComp"), desc.posComp());
        GL20C.glUniform1i(GL20C.glGetUniformLocation(spriteProg, "uColComp"), desc.colComp());
        GL20C.glUniform1i(GL20C.glGetUniformLocation(spriteProg, "uSizeComp"), desc.sizeComp() >= 0 ? desc.sizeComp() : desc.widthComp());
        GL20C.glUniform1i(GL20C.glGetUniformLocation(spriteProg, "uRotComp"), desc.rotComp());
        GL20C.glUniform1i(GL20C.glGetUniformLocation(spriteProg, "uVelComp"), desc.velComp());
        GL20C.glUniform4f(GL20C.glGetUniformLocation(spriteProg, "uTint"), tintR, tintG, tintB, tintA);
        bindFragmentCommon(spriteProg, hdr, softFade, nearPlane, farPlane, vpWidth, vpHeight);

        GL30C.glBindVertexArray(emptyVao);
        GL31C.glDrawArraysInstanced(GL11C.GL_TRIANGLES, 0, 6, desc.liveCount());
        GL30C.glBindVertexArray(0);
        return true;
    }

    private void runGpuBitonicSort(GpuDrawDescriptor desc, double camX, double camY, double camZ) {
        int padded = Integer.highestOneBit(desc.liveCount() - 1) << 1;
        padded = Math.max(128, padded);
        ensureSortBuffers(padded);

        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, desc.floatSsbo());
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 3, sortIndicesSsbo);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 4, sortKeysSsbo);

        int groups = (padded + 127) / 128;
        GL20C.glUseProgram(sortInitProg);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(sortInitProg, "uStride"), desc.stride());
        GL20C.glUniform1i(GL20C.glGetUniformLocation(sortInitProg, "uCount"), desc.liveCount());
        GL20C.glUniform1i(GL20C.glGetUniformLocation(sortInitProg, "uPaddedCount"), padded);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(sortInitProg, "uPosComp"), desc.posComp());
        GL20C.glUniform1i(GL20C.glGetUniformLocation(sortInitProg, "uLocalSpace"), desc.localSpace() ? 1 : 0);
        GL20C.glUniform3f(GL20C.glGetUniformLocation(sortInitProg, "uOrigin"), (float) desc.originX(), (float) desc.originY(), (float) desc.originZ());
        GL20C.glUniform3f(GL20C.glGetUniformLocation(sortInitProg, "uCamPos"), (float) camX, (float) camY, (float) camZ);
        GL43C.glDispatchCompute(groups, 1, 1);
        GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);

        GL20C.glUseProgram(sortStepProg);
        int paddedLoc = GL20C.glGetUniformLocation(sortStepProg, "uPaddedCount");
        int jLoc = GL20C.glGetUniformLocation(sortStepProg, "uJ");
        int kLoc = GL20C.glGetUniformLocation(sortStepProg, "uK");
        GL20C.glUniform1i(paddedLoc, padded);
        for (int k = 2; k <= padded; k <<= 1) {
            GL20C.glUniform1i(kLoc, k);
            for (int j = k >> 1; j > 0; j >>= 1) {
                GL20C.glUniform1i(jLoc, j);
                GL43C.glDispatchCompute(groups, 1, 1);
                GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
            }
        }
    }

    private void ensureSortBuffers(int padded) {
        if (padded <= sortCapacity && sortIndicesSsbo != 0 && sortKeysSsbo != 0) return;
        if (sortIndicesSsbo != 0) GL15C.glDeleteBuffers(sortIndicesSsbo);
        if (sortKeysSsbo != 0) GL15C.glDeleteBuffers(sortKeysSsbo);
        sortIndicesSsbo = GL15C.glGenBuffers();
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, sortIndicesSsbo);
        GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, (long) padded * Integer.BYTES, GL15C.GL_DYNAMIC_COPY);
        sortKeysSsbo = GL15C.glGenBuffers();
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, sortKeysSsbo);
        GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, (long) padded * Float.BYTES, GL15C.GL_DYNAMIC_COPY);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        sortCapacity = padded;
    }

    private static void bindFragmentCommon(int prog, float hdr, float softFade,
                                           float nearPlane, float farPlane, int vpW, int vpH) {
        GL20C.glUniform1i(GL20C.glGetUniformLocation(prog, "uTex"), 0);
        GL20C.glUniform1i(GL20C.glGetUniformLocation(prog, "uSceneDepth"), 1);
        GL20C.glUniform1f(GL20C.glGetUniformLocation(prog, "uHdr"), hdr);
        GL20C.glUniform1f(GL20C.glGetUniformLocation(prog, "uSoftFade"), softFade);
        GL20C.glUniform2f(GL20C.glGetUniformLocation(prog, "uNearFar"), nearPlane, farPlane);
        GL20C.glUniform2f(GL20C.glGetUniformLocation(prog, "uInvViewport"),
                vpW > 0 ? 1.0f / vpW : 0f, vpH > 0 ? 1.0f / vpH : 0f);
    }

    private static int linkGraphics(String vert, String frag) {
        int vs = compile(GL20C.GL_VERTEX_SHADER, vert);
        int fs = compile(GL20C.GL_FRAGMENT_SHADER, frag);
        int prog = GL20C.glCreateProgram();
        GL20C.glAttachShader(prog, vs);
        GL20C.glAttachShader(prog, fs);
        GL20C.glLinkProgram(prog);
        GL20C.glDeleteShader(vs);
        GL20C.glDeleteShader(fs);
        if (GL20C.glGetProgrami(prog, GL20C.GL_LINK_STATUS) == 0) {
            String log = GL20C.glGetProgramInfoLog(prog, 4096);
            GL20C.glDeleteProgram(prog);
            throw new IllegalStateException("Failed to link GPU direct Niagara program: " + log);
        }
        return prog;
    }

    private static int linkCompute(String src) {
        int cs = compile(GL43C.GL_COMPUTE_SHADER, src);
        int prog = GL20C.glCreateProgram();
        GL20C.glAttachShader(prog, cs);
        GL20C.glLinkProgram(prog);
        GL20C.glDeleteShader(cs);
        if (GL20C.glGetProgrami(prog, GL20C.GL_LINK_STATUS) == 0) {
            String log = GL20C.glGetProgramInfoLog(prog, 4096);
            GL20C.glDeleteProgram(prog);
            throw new IllegalStateException("Failed to link GPU sort compute program: " + log);
        }
        return prog;
    }

    private static int compile(int type, String src) {
        int s = GL20C.glCreateShader(type);
        GL20C.glShaderSource(s, src);
        GL20C.glCompileShader(s);
        if (GL20C.glGetShaderi(s, GL20C.GL_COMPILE_STATUS) == 0) {
            String log = GL20C.glGetShaderInfoLog(s, 4096);
            GL20C.glDeleteShader(s);
            throw new IllegalStateException("Failed to compile GPU direct shader: " + log);
        }
        return s;
    }

    public void dispose() {
        if (spriteProg != 0) { GL20C.glDeleteProgram(spriteProg); spriteProg = 0; }
        if (ribbonProg != 0) { GL20C.glDeleteProgram(ribbonProg); ribbonProg = 0; }
        if (meshProg != 0) { GL20C.glDeleteProgram(meshProg); meshProg = 0; }
        if (sortInitProg != 0) { GL20C.glDeleteProgram(sortInitProg); sortInitProg = 0; }
        if (sortStepProg != 0) { GL20C.glDeleteProgram(sortStepProg); sortStepProg = 0; }
        if (emptyVao != 0) { GL30C.glDeleteVertexArrays(emptyVao); emptyVao = 0; }
        if (sortIndicesSsbo != 0) { GL15C.glDeleteBuffers(sortIndicesSsbo); sortIndicesSsbo = 0; }
        if (sortKeysSsbo != 0) { GL15C.glDeleteBuffers(sortKeysSsbo); sortKeysSsbo = 0; }
        sortCapacity = 0;
        initialized = false;
    }

    public void shutdown() {
        dispose();
        MemoryUtil.memFree(matBuf);
    }
}
