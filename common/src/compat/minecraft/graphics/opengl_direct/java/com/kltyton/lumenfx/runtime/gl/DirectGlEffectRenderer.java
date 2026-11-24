package com.kltyton.lumenfx.runtime.gl;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.runtime.bridge.McResourceBridge;
import com.kltyton.lumenfx.runtime.gl.DirectGlMeshCache.GpuMesh;
import com.kltyton.lumenfx.runtime.model.BlendMode;
import com.kltyton.lumenfx.runtime.model.MaterialModel;
import com.kltyton.lumenfx.runtime.model.NiagaraSystemModel.RendererKind;
import com.kltyton.lumenfx.runtime.model.PhotonFxModel;
import com.kltyton.lumenfx.runtime.model.StaticMeshModel;
import com.kltyton.lumenfx.runtime.model.UnityEmitterModel;
import com.kltyton.lumenfx.runtime.simulation.EffectRuntimeInstance;
import com.kltyton.lumenfx.runtime.simulation.NiagaraSystemRuntime;
import com.kltyton.lumenfx.runtime.simulation.ParticleInstance;
import com.kltyton.lumenfx.runtime.simulation.PhotonFxRuntime;
import com.kltyton.lumenfx.runtime.simulation.UnityEmitterRuntime;
import java.nio.FloatBuffer;
import java.util.List;
import javax.annotation.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;

/**
 * Direct OpenGL 3.3+ / 4.6 renderer driving Unity Shuriken, Niagara CPU/GPU, and Photon authored-model effects.
 */
public final class DirectGlEffectRenderer {
    private static final int FLOATS_PER_VERTEX = 9; // pos(3) + uv(2) + color(4)
    private static final int MAX_BATCH_VERTICES = 65536;

    private final DirectGlShaderProgram shaders = new DirectGlShaderProgram();
    private final DirectGlTextureCache textures = new DirectGlTextureCache();
    private final DirectGlMeshCache meshes = new DirectGlMeshCache();
    private final DirectGlNiagaraGpuRunner niagaraGpuRunner = new DirectGlNiagaraGpuRunner();

    private int quadVao;
    private int quadVbo;
    private FloatBuffer vertexBuf;
    private final FloatBuffer matBuf = MemoryUtil.memAllocFloat(16);
    private final Matrix4f viewProj = new Matrix4f();
    private final Matrix4f modelMat = new Matrix4f();
    private final Vector3f camRight = new Vector3f();
    private final Vector3f camUp = new Vector3f();
    private final Vector3f camForward = new Vector3f();

    private void ensureBuffers() {
        shaders.ensureCompiled();
        if (quadVao == 0) {
            quadVao = GL30C.glGenVertexArrays();
            quadVbo = GL15C.glGenBuffers();
            vertexBuf = MemoryUtil.memAllocFloat(MAX_BATCH_VERTICES * FLOATS_PER_VERTEX);
            GL30C.glBindVertexArray(quadVao);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, quadVbo);
            GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, (long) MAX_BATCH_VERTICES * FLOATS_PER_VERTEX * Float.BYTES,
                    GL15C.GL_DYNAMIC_DRAW);
            int stride = FLOATS_PER_VERTEX * Float.BYTES;
            GL20C.glEnableVertexAttribArray(0);
            GL20C.glVertexAttribPointer(0, 3, GL11C.GL_FLOAT, false, stride, 0L);
            GL20C.glEnableVertexAttribArray(1);
            GL20C.glVertexAttribPointer(1, 2, GL11C.GL_FLOAT, false, stride, 3L * Float.BYTES);
            GL20C.glEnableVertexAttribArray(2);
            GL20C.glVertexAttribPointer(2, 4, GL11C.GL_FLOAT, false, stride, 5L * Float.BYTES);
            GL30C.glBindVertexArray(0);
        }
    }

    public DirectGlNiagaraGpuRunner gpuRunner() {
        return niagaraGpuRunner;
    }

    public void render(List<EffectRuntimeInstance> instances, Matrix4f projection, Matrix4f modelView,
                       double camX, double camY, double camZ, float partialTick,
                       @Nullable McResourceBridge bridge) {
        if (DirectGlNiagaraGpuRunner.cachedMaxStorageBufferBytes() <= 0L) {
            DirectGlNiagaraGpuRunner.refreshDeviceLimits();
        }
        if (instances.isEmpty()) return;
        // Execute pending Niagara GPU SPIR-V compute dispatches on the render thread first
        niagaraGpuRunner.stepPending(instances);

        try (GlStateGuard ignored = new GlStateGuard()) {
            ensureBuffers();
            viewProj.set(projection).mul(modelView);
            camRight.set(modelView.m00(), modelView.m10(), modelView.m20()).normalize();
            camUp.set(modelView.m01(), modelView.m11(), modelView.m21()).normalize();
            camForward.set(-modelView.m02(), -modelView.m12(), -modelView.m22()).normalize();

            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            for (EffectRuntimeInstance inst : instances) {
                EffectHandle.Snapshot snap = inst.handle().snapshot();
                for (UnityEmitterRuntime u : inst.unityEmitters()) {
                    renderUnityEmitter(u, camX, camY, camZ, partialTick, bridge);
                }
                NiagaraSystemRuntime niagara = inst.niagaraRuntime();
                if (niagara != null) {
                    renderNiagara(niagara, camX, camY, camZ, partialTick, bridge);
                }
                PhotonFxRuntime photon = inst.photonRuntime();
                if (photon != null) {
                    renderPhoton(photon, snap, camX, camY, camZ, partialTick, bridge);
                }
            }
        }
    }

    private void renderUnityEmitter(UnityEmitterRuntime u, double camX, double camY, double camZ,
                                    float pt, @Nullable McResourceBridge bridge) {
        if (u.particles.isEmpty()) return;
        MaterialModel mat = u.spec.material();
        applyMaterialState(mat);
        int tex = textures.resolve(mat.primaryTexture(), bridge);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, tex);

        if (u.spec.renderer() == UnityEmitterModel.RendererKind.MESH
                && u.spec.meshEntry() != null && u.spec.meshEntry().mesh() != null) {
            renderMeshParticles(u.particles, u.spec.meshEntry().mesh(), mat, camX, camY, camZ, pt);
            return;
        }

        boolean stretched = u.spec.stretchedBillboard();
        boolean ribbon = u.spec.renderer() == UnityEmitterModel.RendererKind.RIBBON;

        vertexBuf.clear();
        int verts = 0;
        if (ribbon) {
            verts = appendRibbonChain(u.particles, camX, camY, camZ, pt, verts);
        } else {
            for (ParticleInstance p : u.particles) {
                verts = appendBillboard(p, stretched, camX, camY, camZ, pt, verts);
                if (u.spec.trail().enabled() && p.trailCount >= 2) {
                    verts = appendParticleTrail(p, camX, camY, camZ, verts);
                }
            }
        }
        flushParticles(verts, mat.hdr() ? 1.5f : 1.0f);
    }

    private void renderNiagara(NiagaraSystemRuntime niagara, double camX, double camY, double camZ,
                               float pt, @Nullable McResourceBridge bridge) {
        for (NiagaraSystemRuntime.EmitterState e : niagara.emitters()) {
            List<ParticleInstance> list = e.renderableParticles();
            if (list.isEmpty()) continue;
            applyMaterialState(e.material);
            int tex = textures.resolve(e.material.primaryTexture(), bridge);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, tex);

            if (e.primaryRenderer.kind() == RendererKind.MESH && e.primaryRenderer.meshModel() != null) {
                renderMeshParticles(list, e.primaryRenderer.meshModel(), e.material, camX, camY, camZ, pt);
                continue;
            }
            vertexBuf.clear();
            int verts = 0;
            if (e.primaryRenderer.kind() == RendererKind.RIBBON) {
                verts = appendRibbonChain(list, camX, camY, camZ, pt, verts);
            } else {
                for (ParticleInstance p : list) {
                    verts = appendBillboard(p, false, camX, camY, camZ, pt, verts);
                }
            }
            flushParticles(verts, e.material.hdr() ? 1.5f : 1.0f);
        }
    }

    private void renderPhoton(PhotonFxRuntime photon, EffectHandle.Snapshot snap,
                              double camX, double camY, double camZ, float pt, @Nullable McResourceBridge bridge) {
        for (PhotonFxRuntime.ObjectRuntime obj : photon.objects()) {
            if (obj.particles.isEmpty()) continue;
            PhotonFxModel.FxObjectSpec spec = obj.spec;
            applyMaterialState(spec.material());
            int tex = textures.resolve(spec.material().primaryTexture(), bridge);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, tex);

            if ((spec.renderMode() == PhotonFxModel.RenderMode.MODEL || spec.renderMode() == PhotonFxModel.RenderMode.MESH)
                    && spec.authoredModel() != null) {
                renderPhotonAuthoredModel(obj, spec.authoredModel(), snap, camX, camY, camZ);
                continue;
            }
            vertexBuf.clear();
            int verts = 0;
            boolean stretched = spec.renderMode() == PhotonFxModel.RenderMode.STRETCHED_BILLBOARD;
            boolean ribbon = spec.renderMode() == PhotonFxModel.RenderMode.TRAIL
                    || spec.renderMode() == PhotonFxModel.RenderMode.BEAM;
            if (ribbon) {
                for (ParticleInstance p : obj.particles) {
                    verts = appendParticleTrail(p, camX, camY, camZ, verts);
                }
            } else {
                for (ParticleInstance p : obj.particles) {
                    verts = appendBillboard(p, stretched, camX, camY, camZ, pt, verts);
                }
            }
            flushParticles(verts, spec.material().hdr() ? 1.5f : 1.0f);
        }
    }

    private void renderPhotonAuthoredModel(PhotonFxRuntime.ObjectRuntime obj, StaticMeshModel mesh,
                                           EffectHandle.Snapshot snap, double camX, double camY, double camZ) {
        GpuMesh gpu = meshes.resolve(mesh);
        if (gpu.vao() == 0 || gpu.indexCount() <= 0) return;
        GL20C.glUseProgram(shaders.meshProgram);
        matBuf.clear();
        viewProj.get(matBuf);
        GL20C.glUniformMatrix4fv(shaders.meshViewProjLoc, false, matBuf);
        GL20C.glUniform1i(shaders.meshTexLoc, 0);
        GL20C.glUniform1f(shaders.meshHdrLoc, obj.spec.material().hdr() ? 1.5f : 1.0f);
        GL30C.glBindVertexArray(gpu.vao());
        GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, gpu.ebo());

        EffectHandle.Pose pose = snap.pose();
        for (ParticleInstance p : obj.particles) {
            modelMat.identity()
                    .translate((float) (pose.position().x - camX), (float) (pose.position().y - camY),
                            (float) (pose.position().z - camZ))
                    .rotate(new org.joml.Quaternionf(pose.x(), pose.y(), pose.z(), pose.w()))
                    .scale(pose.scaleX(), pose.scaleY(), pose.scaleZ())
                    .mul(obj.localToEffectMatrix)
                    .scale(gpu.unitScale());
            matBuf.clear();
            modelMat.get(matBuf);
            GL20C.glUniformMatrix4fv(shaders.meshModelLoc, false, matBuf);
            GL20C.glUniform4f(shaders.meshTintLoc, p.r, p.g, p.b, p.a);
            GL11C.glDrawElements(GL11C.GL_TRIANGLES, gpu.indexCount(), GL11C.GL_UNSIGNED_INT, 0L);
        }
        GL30C.glBindVertexArray(0);
    }

    private void renderMeshParticles(List<ParticleInstance> particles, StaticMeshModel mesh, MaterialModel mat,
                                     double camX, double camY, double camZ, float pt) {
        GpuMesh gpu = meshes.resolve(mesh);
        if (gpu.vao() == 0 || gpu.indexCount() <= 0) return;
        GL20C.glUseProgram(shaders.meshProgram);
        matBuf.clear();
        viewProj.get(matBuf);
        GL20C.glUniformMatrix4fv(shaders.meshViewProjLoc, false, matBuf);
        GL20C.glUniform1i(shaders.meshTexLoc, 0);
        GL20C.glUniform1f(shaders.meshHdrLoc, mat.hdr() ? 1.5f : 1.0f);
        GL30C.glBindVertexArray(gpu.vao());
        GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, gpu.ebo());

        for (ParticleInstance p : particles) {
            float x = (float) (p.lerpX(pt) - camX);
            float y = (float) (p.lerpY(pt) - camY);
            float z = (float) (p.lerpZ(pt) - camZ);
            float sx = Math.max(0.01f, p.width) * gpu.unitScale();
            float sy = Math.max(0.01f, p.height) * gpu.unitScale();
            modelMat.identity().translate(x, y, z).rotateZ((float) Math.toRadians(p.rotationDeg)).scale(sx, sy, sx);
            matBuf.clear();
            modelMat.get(matBuf);
            GL20C.glUniformMatrix4fv(shaders.meshModelLoc, false, matBuf);
            GL20C.glUniform4f(shaders.meshTintLoc, p.r, p.g, p.b, p.a);
            GL11C.glDrawElements(GL11C.GL_TRIANGLES, gpu.indexCount(), GL11C.GL_UNSIGNED_INT, 0L);
        }
        GL30C.glBindVertexArray(0);
    }

    private int appendBillboard(ParticleInstance p, boolean stretched,
                                double camX, double camY, double camZ, float pt, int verts) {
        if (verts + 6 > MAX_BATCH_VERTICES) return verts;
        float cx = (float) (p.lerpX(pt) - camX);
        float cy = (float) (p.lerpY(pt) - camY);
        float cz = (float) (p.lerpZ(pt) - camZ);
        float hw = Math.max(0.005f, p.width * 0.5f);
        float hh = Math.max(0.005f, p.height * 0.5f);

        float rx, ry, rz, ux, uy, uz;
        if (stretched) {
            float spd = (float) Math.sqrt(p.vx * p.vx + p.vy * p.vy + p.vz * p.vz);
            if (spd > 1e-4f) {
                ux = p.vx / spd; uy = p.vy / spd; uz = p.vz / spd;
                rx = uy * camForward.z - uz * camForward.y;
                ry = uz * camForward.x - ux * camForward.z;
                rz = ux * camForward.y - uy * camForward.x;
                float rl = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
                if (rl > 1e-4f) { rx /= rl; ry /= rl; rz /= rl; }
                else { rx = camRight.x; ry = camRight.y; rz = camRight.z; }
                hh = Math.max(hh, spd * 0.15f);
            } else {
                rx = camRight.x; ry = camRight.y; rz = camRight.z;
                ux = camUp.x; uy = camUp.y; uz = camUp.z;
            }
        } else {
            float rad = (float) Math.toRadians(p.rotationDeg);
            float c = (float) Math.cos(rad);
            float s = (float) Math.sin(rad);
            rx = camRight.x * c + camUp.x * s;
            ry = camRight.y * c + camUp.y * s;
            rz = camRight.z * c + camUp.z * s;
            ux = camUp.x * c - camRight.x * s;
            uy = camUp.y * c - camRight.y * s;
            uz = camUp.z * c - camRight.z * s;
        }

        float rX = rx * hw, rY = ry * hw, rZ = rz * hw;
        float uX = ux * hh, uY = uy * hh, uZ = uz * hh;

        putVert(cx - rX - uX, cy - rY - uY, cz - rZ - uZ, 0f, 1f, p.r, p.g, p.b, p.a);
        putVert(cx + rX - uX, cy + rY - uY, cz + rZ - uZ, 1f, 1f, p.r, p.g, p.b, p.a);
        putVert(cx + rX + uX, cy + rY + uY, cz + rZ + uZ, 1f, 0f, p.r, p.g, p.b, p.a);
        putVert(cx - rX - uX, cy - rY - uY, cz - rZ - uZ, 0f, 1f, p.r, p.g, p.b, p.a);
        putVert(cx + rX + uX, cy + rY + uY, cz + rZ + uZ, 1f, 0f, p.r, p.g, p.b, p.a);
        putVert(cx - rX + uX, cy - rY + uY, cz - rZ + uZ, 0f, 0f, p.r, p.g, p.b, p.a);
        return verts + 6;
    }

    private int appendRibbonChain(List<ParticleInstance> list, double camX, double camY, double camZ,
                                  float pt, int verts) {
        if (list.size() < 2) {
            if (list.size() == 1) return appendBillboard(list.get(0), false, camX, camY, camZ, pt, verts);
            return verts;
        }
        int n = list.size();
        for (int i = 0; i < n - 1 && verts + 6 <= MAX_BATCH_VERTICES; i++) {
            ParticleInstance a = list.get(i);
            ParticleInstance b = list.get(i + 1);
            float ax = (float) (a.lerpX(pt) - camX), ay = (float) (a.lerpY(pt) - camY), az = (float) (a.lerpZ(pt) - camZ);
            float bx = (float) (b.lerpX(pt) - camX), by = (float) (b.lerpY(pt) - camY), bz = (float) (b.lerpZ(pt) - camZ);
            verts = appendSegmentQuad(ax, ay, az, bx, by, bz,
                    Math.max(0.02f, a.width * 0.5f), Math.max(0.02f, b.width * 0.5f),
                    (float) i / (n - 1), (float) (i + 1) / (n - 1),
                    a.r, a.g, a.b, a.a, b.r, b.g, b.b, b.a, verts);
        }
        return verts;
    }

    private int appendParticleTrail(ParticleInstance p, double camX, double camY, double camZ, int verts) {
        int n = p.trailCount;
        if (n < 2) return verts;
        for (int i = 0; i < n - 1 && verts + 6 <= MAX_BATCH_VERTICES; i++) {
            float ax = (float) (p.trailX[i] - camX), ay = (float) (p.trailY[i] - camY), az = (float) (p.trailZ[i] - camZ);
            float bx = (float) (p.trailX[i + 1] - camX), by = (float) (p.trailY[i + 1] - camY), bz = (float) (p.trailZ[i + 1] - camZ);
            float t0 = (float) i / (n - 1);
            float t1 = (float) (i + 1) / (n - 1);
            float w0 = Math.max(0.01f, p.width * 0.35f * (1f - t0));
            float w1 = Math.max(0.01f, p.width * 0.35f * (1f - t1));
            verts = appendSegmentQuad(ax, ay, az, bx, by, bz, w0, w1, t0, t1,
                    p.r, p.g, p.b, p.a * (1f - t0), p.r, p.g, p.b, p.a * (1f - t1), verts);
        }
        return verts;
    }

    private int appendSegmentQuad(float ax, float ay, float az, float bx, float by, float bz,
                                  float wa, float wb, float u0, float u1,
                                  float r0, float g0, float b0, float a0,
                                  float r1, float g1, float b1, float a1, int verts) {
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        float sx = dy * camForward.z - dz * camForward.y;
        float sy = dz * camForward.x - dx * camForward.z;
        float sz = dx * camForward.y - dy * camForward.x;
        float sl = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (sl < 1e-4f) { sx = camRight.x; sy = camRight.y; sz = camRight.z; }
        else { sx /= sl; sy /= sl; sz /= sl; }

        putVert(ax - sx * wa, ay - sy * wa, az - sz * wa, u0, 0f, r0, g0, b0, a0);
        putVert(ax + sx * wa, ay + sy * wa, az + sz * wa, u0, 1f, r0, g0, b0, a0);
        putVert(bx + sx * wb, by + sy * wb, bz + sz * wb, u1, 1f, r1, g1, b1, a1);
        putVert(ax - sx * wa, ay - sy * wa, az - sz * wa, u0, 0f, r0, g0, b0, a0);
        putVert(bx + sx * wb, by + sy * wb, bz + sz * wb, u1, 1f, r1, g1, b1, a1);
        putVert(bx - sx * wb, by - sy * wb, bz - sz * wb, u1, 0f, r1, g1, b1, a1);
        return verts + 6;
    }

    private void putVert(float x, float y, float z, float u, float v, float r, float g, float b, float a) {
        vertexBuf.put(x).put(y).put(z).put(u).put(v).put(r).put(g).put(b).put(a);
    }

    private void flushParticles(int verts, float hdr) {
        if (verts <= 0) return;
        vertexBuf.flip();
        GL20C.glUseProgram(shaders.particleProgram);
        matBuf.clear();
        viewProj.get(matBuf);
        GL20C.glUniformMatrix4fv(shaders.particleViewProjLoc, false, matBuf);
        GL20C.glUniform1i(shaders.particleTexLoc, 0);
        GL20C.glUniform1f(shaders.particleHdrLoc, hdr);

        GL30C.glBindVertexArray(quadVao);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, quadVbo);
        GL15C.glBufferSubData(GL15C.GL_ARRAY_BUFFER, 0L, vertexBuf);
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, verts);
        GL30C.glBindVertexArray(0);
    }

    private static void applyMaterialState(MaterialModel mat) {
        if (mat.depthTest()) GL11C.glEnable(GL11C.GL_DEPTH_TEST); else GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthMask(mat.depthWrite());
        if (mat.cull()) GL11C.glEnable(GL11C.GL_CULL_FACE); else GL11C.glDisable(GL11C.GL_CULL_FACE);

        BlendMode mode = mat.blend();
        if (mode == BlendMode.OPAQUE) {
            GL11C.glDisable(GL11C.GL_BLEND);
            return;
        }
        GL11C.glEnable(GL11C.GL_BLEND);
        int eq = switch (mode) {
            case SUBTRACT -> GL14C.GL_FUNC_SUBTRACT;
            case REVERSE_SUBTRACT -> GL14C.GL_FUNC_REVERSE_SUBTRACT;
            default -> GL14C.GL_FUNC_ADD;
        };
        GL20C.glBlendEquationSeparate(eq, GL14C.GL_FUNC_ADD);
        switch (mode) {
            case ADDITIVE, SUBTRACT, REVERSE_SUBTRACT ->
                    GL14C.glBlendFuncSeparate(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE, GL11C.GL_ONE, GL11C.GL_ONE);
            case PREMULTIPLIED ->
                    GL14C.glBlendFuncSeparate(GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA, GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA);
            case MULTIPLY, MODULATE ->
                    GL14C.glBlendFuncSeparate(GL11C.GL_DST_COLOR, GL11C.GL_ZERO, GL11C.GL_DST_ALPHA, GL11C.GL_ZERO);
            default ->
                    GL14C.glBlendFuncSeparate(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA, GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA);
        }
    }

    public void onResourcesReloaded() {
        textures.clear();
        meshes.clear();
        niagaraGpuRunner.clear();
    }

    public void dispose() {
        onResourcesReloaded();
        shaders.dispose();
        if (quadVao != 0) {
            GL30C.glDeleteVertexArrays(quadVao);
            quadVao = 0;
        }
        if (quadVbo != 0) {
            GL15C.glDeleteBuffers(quadVbo);
            quadVbo = 0;
        }
        if (vertexBuf != null) {
            MemoryUtil.memFree(vertexBuf);
            vertexBuf = null;
        }
        MemoryUtil.memFree(matBuf);
    }
}
