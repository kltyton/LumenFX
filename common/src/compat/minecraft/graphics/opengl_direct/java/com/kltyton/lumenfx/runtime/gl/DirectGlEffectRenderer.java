package com.kltyton.lumenfx.runtime.gl;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.runtime.bridge.McResourceBridge;
import com.kltyton.lumenfx.runtime.gl.DirectGlMeshCache.GpuMesh;
import com.kltyton.lumenfx.runtime.gl.depth.DirectGlSceneDepthCapture;
import com.kltyton.lumenfx.runtime.gl.groom.DirectGlGroomRenderer;
import com.kltyton.lumenfx.runtime.gl.hdr.DirectGlHdrBloomPipeline;
import com.kltyton.lumenfx.runtime.gl.material.DirectGlCustomMaterialCompiler;
import com.kltyton.lumenfx.runtime.gl.material.DirectGlCustomMaterialCompiler.CompiledMaterial;
import com.kltyton.lumenfx.runtime.gl.nanite.DirectGlNaniteRenderer;
import com.kltyton.lumenfx.runtime.gl.niagara.DirectGlNiagaraDirectRenderer;
import com.kltyton.lumenfx.runtime.gl.rvt.DirectGlRuntimeVirtualTexture;
import com.kltyton.lumenfx.runtime.gl.splat.DirectGlGaussianSplatRenderer;
import com.kltyton.lumenfx.runtime.gl.volume.DirectGlVolumeAndLightRenderer;
import com.kltyton.lumenfx.runtime.gl.volume.DirectGlVolumeAndLightRenderer.DecalSpec;
import com.kltyton.lumenfx.runtime.gl.volume.DirectGlVolumeAndLightRenderer.LightSpec;
import com.kltyton.lumenfx.runtime.gl.volume.DirectGlVolumeAndLightRenderer.VolumeSpec;
import com.kltyton.lumenfx.runtime.model.BlendMode;
import com.kltyton.lumenfx.runtime.model.EffectModel;
import com.kltyton.lumenfx.runtime.model.GaussianSplatModel;
import com.kltyton.lumenfx.runtime.model.GroomAssetModel;
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
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Full-parity Direct OpenGL 3.3+ / 4.6 renderer driving:
 * <ul>
 *   <li>Unity Shuriken, UE5 Niagara (CPU + zero-readback GPU SSBO direct draw), and Photon {@code .fx} effects</li>
 *   <li>Offscreen RGBA16F HDR Bloom FBO pipeline + ACES tonemap</li>
 *   <li>Scene depth &amp; color capture for soft particles, refraction, volumetric fog, deferred decals, and dynamic lights</li>
 *   <li>Custom GLSL 330 Core material shaders</li>
 *   <li>Nanite meshlet LOD culling, 3D Gaussian Splatting, Groom hair strands, and Runtime Virtual Texture (RVT)</li>
 * </ul>
 */
public final class DirectGlEffectRenderer {
    private static final int FLOATS_PER_VERTEX = 20; // Position, UV, color, particle/custom data, next subimage
    private static final int MAX_BATCH_VERTICES = 65536;
    private static final int NANITE_AUTO_INDEX_THRESHOLD = 1536; // 512 triangles

    private final DirectGlShaderProgram shaders = new DirectGlShaderProgram();
    private final DirectGlTextureCache textures = new DirectGlTextureCache();
    private final DirectGlMeshCache meshes = new DirectGlMeshCache();
    private final DirectGlNiagaraGpuRunner niagaraGpuRunner = new DirectGlNiagaraGpuRunner();
    private final DirectGlSceneDepthCapture sceneDepthCapture = new DirectGlSceneDepthCapture();
    private final DirectGlHdrBloomPipeline hdrBloom = new DirectGlHdrBloomPipeline();
    private final DirectGlCustomMaterialCompiler customMaterials = new DirectGlCustomMaterialCompiler();
    private final DirectGlNiagaraDirectRenderer niagaraDirectRenderer = new DirectGlNiagaraDirectRenderer();
    private final DirectGlNaniteRenderer naniteRenderer = new DirectGlNaniteRenderer();
    private final DirectGlGaussianSplatRenderer splatRenderer = new DirectGlGaussianSplatRenderer();
    private final DirectGlGroomRenderer groomRenderer = new DirectGlGroomRenderer();
    private final DirectGlVolumeAndLightRenderer volumeLightRenderer = new DirectGlVolumeAndLightRenderer();
    private final DirectGlRuntimeVirtualTexture rvt = new DirectGlRuntimeVirtualTexture();

    private int quadVao;
    private int quadVbo;
    private int effectFrameUbo;
    private FloatBuffer vertexBuf;
    private final FloatBuffer matBuf = MemoryUtil.memAllocFloat(16);
    private final Matrix4f viewProj = new Matrix4f();
    private final Matrix4f modelMat = new Matrix4f();
    private final Vector3f camRight = new Vector3f();
    private final Vector3f camUp = new Vector3f();
    private final Vector3f camForward = new Vector3f();
    private final long startNanos = System.nanoTime();

    // Per-frame cached capture parameters
    private int activeSceneDepthTex;
    private int activeSceneColorTex;
    private int activeVpWidth = 1;
    private int activeVpHeight = 1;
    private float activeNear = 0.05f;
    private float activeFar = 256.0f;
    private float frameTimeSeconds;

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
            GL20C.glEnableVertexAttribArray(3);
            GL20C.glVertexAttribPointer(3, 4, GL11C.GL_FLOAT, false, stride, 9L * Float.BYTES);
            GL20C.glEnableVertexAttribArray(4);
            GL20C.glVertexAttribPointer(4, 4, GL11C.GL_FLOAT, false, stride, 13L * Float.BYTES);
            GL20C.glEnableVertexAttribArray(5);
            GL20C.glVertexAttribPointer(5, 3, GL11C.GL_FLOAT, false, stride, 17L * Float.BYTES);
            GL30C.glBindVertexArray(0);
        }
    }

    public DirectGlNiagaraGpuRunner gpuRunner() { return niagaraGpuRunner; }
    public DirectGlRuntimeVirtualTexture rvt() { return rvt; }
    public DirectGlHdrBloomPipeline hdrBloom() { return hdrBloom; }
    public DirectGlNaniteRenderer naniteRenderer() { return naniteRenderer; }
    public DirectGlNiagaraDirectRenderer niagaraDirectRenderer() { return niagaraDirectRenderer; }

    public void render(List<EffectRuntimeInstance> instances, Matrix4f projection, Matrix4f modelView,
                       double camX, double camY, double camZ, float partialTick,
                       @Nullable McResourceBridge bridge) {
        if (DirectGlNiagaraGpuRunner.cachedMaxStorageBufferBytes() <= 0L) {
            DirectGlNiagaraGpuRunner.refreshDeviceLimits();
        }
        if (instances.isEmpty()) return;

        // Execute pending Niagara GPU SPIR-V compute dispatches on the render thread first
        niagaraGpuRunner.stepPending(instances);

        boolean needsDepth = false;
        boolean needsColor = false;
        boolean hasHdr = false;
        boolean usesRvt = false;
        int textureUnits = GlStateGuard.MATERIAL_TEXTURE_START;
        for (EffectRuntimeInstance inst : instances) {
            EffectModel em = inst.model();
            if (!em.volumes().isEmpty() || !em.decals().isEmpty() || !em.lights().isEmpty()) {
                needsDepth = true;
            }
            if (!em.rvtMaterials().isEmpty()) usesRvt = true;
            for (UnityEmitterRuntime u : inst.unityEmitters()) {
                MaterialModel m = u.spec.material();
                if (m.hdr()) hasHdr = true;
                if (m.softParticles() || m.hasCustomShader()) needsDepth = true;
                if (m.refraction() || m.hasCustomShader()) needsColor = true;
                textureUnits = Math.max(textureUnits, materialTextureUnits(m));
                if (m.rvt()) usesRvt = true;
            }
            NiagaraSystemRuntime nr = inst.niagaraRuntime();
            if (nr != null) {
                for (NiagaraSystemRuntime.EmitterState e : nr.emitters()) {
                    MaterialModel m = e.material;
                    if (m.hdr()) hasHdr = true;
                    if (m.softParticles() || m.hasCustomShader()) needsDepth = true;
                    if (m.refraction() || m.hasCustomShader()) needsColor = true;
                    textureUnits = Math.max(textureUnits, materialTextureUnits(m));
                    if (m.rvt()) usesRvt = true;
                    RendererKind rk = e.primaryRenderer.kind();
                    if (rk == RendererKind.VOLUME || rk == RendererKind.DECAL || rk == RendererKind.LIGHT) {
                        needsDepth = true;
                    }
                }
            }
            PhotonFxRuntime pr = inst.photonRuntime();
            if (pr != null) {
                for (PhotonFxRuntime.ObjectRuntime o : pr.objects()) {
                    textureUnits = Math.max(textureUnits, materialTextureUnits(o.spec.material()));
                    PhotonFxModel.PhysicsExtras ex = o.spec.extras();
                    if (ex != null) {
                        if (ex.hdr()) hasHdr = true;
                        if (ex.softParticles()) needsDepth = true;
                    }
                }
            }
        }

        frameTimeSeconds = (float) (((System.nanoTime() - startNanos) * 1e-9) % 10000.0);

        try (GlStateGuard guard = new GlStateGuard(textureUnits)) {
            ensureBuffers();
            activeVpWidth = Math.max(1, guard.viewportWidth());
            activeVpHeight = Math.max(1, guard.viewportHeight());
            viewProj.set(projection).mul(modelView);
            camRight.set(modelView.m00(), modelView.m10(), modelView.m20()).normalize();
            camUp.set(modelView.m01(), modelView.m11(), modelView.m21()).normalize();
            camForward.set(-modelView.m02(), -modelView.m12(), -modelView.m22()).normalize();

            if (needsDepth) {
                sceneDepthCapture.captureFromActiveFbo(guard.drawFramebuffer(), activeVpWidth, activeVpHeight,
                        projection, needsColor);
                activeSceneDepthTex = sceneDepthCapture.depthTextureId();
                activeSceneColorTex = needsColor ? sceneDepthCapture.colorTextureId() : 0;
                activeNear = sceneDepthCapture.nearPlane();
                activeFar = sceneDepthCapture.farPlane();
            } else {
                activeSceneDepthTex = 0;
                activeSceneColorTex = 0;
            }
            if (usesRvt) {
                rvt.syncPageTableToGpu();
            }

            // Pass 1: Standard (non-HDR) effects + attachments (Volumes, Decals, Lights, Groom, 3D Gaussian Splats)
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            renderInstancesPass(instances, false, projection, modelView, camX, camY, camZ, partialTick, bridge);
            renderAttachments(instances, projection, modelView, camX, camY, camZ, bridge);

            // Pass 2: Offscreen RGBA16F HDR Bloom pass + ACES composite
            if (hasHdr) {
                boolean inHdrFbo = hdrBloom.beginHdrPass(guard.drawFramebuffer(), activeVpWidth, activeVpHeight);
                GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
                renderInstancesPass(instances, true, projection, modelView, camX, camY, camZ, partialTick, bridge);
                if (inHdrFbo) {
                    hdrBloom.endAndComposite();
                }
            }
        }
    }

    private static int materialTextureUnits(MaterialModel material) {
        return GlStateGuard.MATERIAL_TEXTURE_START + (material.hasCustomShader() ? material.textures().size() : 0);
    }

    private void renderInstancesPass(List<EffectRuntimeInstance> instances, boolean hdrPass,
                                     Matrix4f projection, Matrix4f modelView,
                                     double camX, double camY, double camZ, float partialTick,
                                     @Nullable McResourceBridge bridge) {
        for (EffectRuntimeInstance inst : instances) {
            EffectHandle.Snapshot snap = inst.handle().snapshot();
            for (UnityEmitterRuntime u : inst.unityEmitters()) {
                if (u.spec.material().hdr() != hdrPass) continue;
                renderUnityEmitter(u, camX, camY, camZ, partialTick, bridge);
            }
            NiagaraSystemRuntime niagara = inst.niagaraRuntime();
            if (niagara != null) {
                renderNiagara(niagara, snap, hdrPass, projection, modelView, camX, camY, camZ, partialTick, bridge);
            }
            PhotonFxRuntime photon = inst.photonRuntime();
            if (photon != null) {
                renderPhoton(photon, snap, hdrPass, camX, camY, camZ, partialTick, bridge);
            }
        }
    }

    private void renderAttachments(List<EffectRuntimeInstance> instances, Matrix4f projection, Matrix4f modelView,
                                   double camX, double camY, double camZ, @Nullable McResourceBridge bridge) {
        for (EffectRuntimeInstance inst : instances) {
            EffectModel em = inst.model();
            if (!em.hasVisualAttachments()) continue;
            EffectHandle.Snapshot snap = inst.handle().snapshot();
            EffectHandle.Pose pose = snap.pose();
            Vec3 origin = pose.position();
            float scale = pose.scaleX();

            for (GaussianSplatModel splat : em.gaussianSplats()) {
                splatRenderer.renderSplats(splat.splats(), origin.x, origin.y, origin.z, scale,
                        snap.red(), snap.green(), snap.blue(), snap.alpha(), false,
                        projection, modelView, camX, camY, camZ, activeVpWidth, activeVpHeight);
            }
            for (GroomAssetModel groom : em.grooms()) {
                groomRenderer.renderGroom(groom, origin.x, origin.y, origin.z, scale, frameTimeSeconds,
                        viewProj, camForward, camRight, camX, camY, camZ);
            }
            for (VolumeSpec v : em.volumes()) {
                Vec3 wp = pose.transformPosition(new Vec3(v.x(), v.y(), v.z()));
                VolumeSpec worldVol = new VolumeSpec(wp.x, wp.y, wp.z,
                        v.sx() * scale, v.sy() * scale, v.sz() * scale,
                        v.r() * snap.red(), v.g() * snap.green(), v.b() * snap.blue(),
                        v.density() * snap.alpha(), v.anisotropy());
                volumeLightRenderer.renderVolume(worldVol, viewProj, camX, camY, camZ,
                        activeSceneDepthTex, activeVpWidth, activeVpHeight, frameTimeSeconds);
            }
            for (EffectModel.DecalAssetSpec d : em.decals()) {
                Vec3 wp = pose.transformPosition(new Vec3(d.x(), d.y(), d.z()));
                int tex = textures.resolve(d.textureId(), bridge);
                DecalSpec worldDecal = new DecalSpec(wp.x, wp.y, wp.z,
                        d.sx() * scale, d.sy() * scale, d.sz() * scale,
                        d.r() * snap.red(), d.g() * snap.green(), d.b() * snap.blue(), d.a() * snap.alpha(), tex);
                volumeLightRenderer.renderDecal(worldDecal, viewProj, camX, camY, camZ,
                        activeSceneDepthTex, activeVpWidth, activeVpHeight);
            }
            for (LightSpec l : em.lights()) {
                Vec3 wp = pose.transformPosition(new Vec3(l.x(), l.y(), l.z()));
                LightSpec worldLight = new LightSpec(wp.x, wp.y, wp.z, l.radius() * scale,
                        l.r() * snap.red(), l.g() * snap.green(), l.b() * snap.blue(),
                        l.intensity() * snap.alpha(), l.spot(), l.dirX(), l.dirY(), l.dirZ(), l.cosOuterCone());
                volumeLightRenderer.renderLight(worldLight, viewProj, camX, camY, camZ,
                        activeSceneDepthTex, activeVpWidth, activeVpHeight);
            }
        }
    }

    private void renderUnityEmitter(UnityEmitterRuntime u, double camX, double camY, double camZ,
                                    float pt, @Nullable McResourceBridge bridge) {
        if (u.particles.isEmpty()) return;
        MaterialModel mat = u.spec.material();
        applyMaterialState(mat);
        int tex = textures.resolve(mat.primaryTexture(), bridge);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, tex);

        if (u.spec.renderer() == UnityEmitterModel.RendererKind.MESH
                && u.spec.meshEntry() != null && u.spec.meshEntry().mesh() != null) {
            renderMeshParticles(u.particles, u.spec.meshEntry().mesh(), mat, tex, camX, camY, camZ, pt);
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
                verts = appendBillboard(p, stretched, mat, camX, camY, camZ, pt, verts);
                if (u.spec.trail().enabled() && p.trailCount >= 2) {
                    verts = appendParticleTrail(p, camX, camY, camZ, verts);
                }
            }
        }
        flushParticles(verts, mat, bridge);
    }

    private void renderNiagara(NiagaraSystemRuntime niagara, EffectHandle.Snapshot snap, boolean hdrPass,
                               Matrix4f projection, Matrix4f modelView,
                               double camX, double camY, double camZ, float pt,
                               @Nullable McResourceBridge bridge) {
        for (NiagaraSystemRuntime.EmitterState e : niagara.emitters()) {
            MaterialModel mat = e.material;
            if (mat.hdr() != hdrPass) continue;

            // Zero-readback GPU SSBO direct rendering (no 2,048 cap!)
            if (e.spec.isGpu() && !e.isCacheActiveLastTick()) {
                DirectGlNiagaraDirectRenderer.GpuDrawDescriptor desc = niagaraGpuRunner.directDrawDescriptor(e);
                if (desc != null) {
                    RendererKind rk = e.primaryRenderer.kind();
                    if (rk == RendererKind.SPRITE || rk == RendererKind.RIBBON || rk == RendererKind.MESH) {
                        applyMaterialState(mat);
                        int tex = textures.resolve(mat.primaryTexture(), bridge);
                        if (niagaraDirectRenderer.drawDirect(desc, viewProj, camRight, camUp, camForward,
                                camX, camY, camZ, tex, activeSceneDepthTex, activeNear, activeFar,
                                activeVpWidth, activeVpHeight, meshes,
                                snap.red(), snap.green(), snap.blue(), snap.alpha())) {
                            continue;
                        }
                    }
                }
            }

            List<ParticleInstance> list = e.renderableParticles();
            if (list.isEmpty()) continue;

            RendererKind rk = e.primaryRenderer.kind();
            if (rk == RendererKind.SPLAT) {
                List<GaussianSplatModel.Splat> dynamicSplats = new ArrayList<>(list.size());
                for (ParticleInstance p : list) {
                    float sz = Math.max(0.02f, p.width * 0.5f);
                    dynamicSplats.add(new GaussianSplatModel.Splat(
                            (float) p.lerpX(pt), (float) p.lerpY(pt), (float) p.lerpZ(pt),
                            sz, sz, sz, 0f, 0f, 0f, 1f, p.r, p.g, p.b, p.a));
                }
                splatRenderer.renderSplats(dynamicSplats, 0.0, 0.0, 0.0, 1.0f, 1f, 1f, 1f, 1f, mat.hdr(),
                        projection, modelView, camX, camY, camZ, activeVpWidth, activeVpHeight);
                continue;
            }
            if (rk == RendererKind.VOLUME) {
                for (ParticleInstance p : list) {
                    volumeLightRenderer.renderVolume(new VolumeSpec(
                            p.lerpX(pt), p.lerpY(pt), p.lerpZ(pt), p.width, p.height, p.width,
                            p.r, p.g, p.b, p.a, 0.3f), viewProj, camX, camY, camZ,
                            activeSceneDepthTex, activeVpWidth, activeVpHeight, frameTimeSeconds);
                }
                continue;
            }
            if (rk == RendererKind.DECAL) {
                int tex = textures.resolve(mat.primaryTexture(), bridge);
                for (ParticleInstance p : list) {
                    volumeLightRenderer.renderDecal(new DecalSpec(
                            p.lerpX(pt), p.lerpY(pt), p.lerpZ(pt), p.width, p.height, p.width,
                            p.r, p.g, p.b, p.a, tex), viewProj, camX, camY, camZ,
                            activeSceneDepthTex, activeVpWidth, activeVpHeight);
                }
                continue;
            }
            if (rk == RendererKind.LIGHT) {
                for (ParticleInstance p : list) {
                    volumeLightRenderer.renderLight(new LightSpec(
                            p.lerpX(pt), p.lerpY(pt), p.lerpZ(pt), Math.max(1.0f, p.width * 4.0f),
                            p.r, p.g, p.b, p.a * (mat.hdr() ? mat.emissiveScale() : 1.0f),
                            false, 0f, -1f, 0f, -1f), viewProj, camX, camY, camZ,
                            activeSceneDepthTex, activeVpWidth, activeVpHeight);
                }
                continue;
            }

            applyMaterialState(mat);
            int tex = textures.resolve(mat.primaryTexture(), bridge);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, tex);

            if (rk == RendererKind.MESH && e.primaryRenderer.meshModel() != null) {
                renderMeshParticles(list, e.primaryRenderer.meshModel(), mat, tex, camX, camY, camZ, pt);
                continue;
            }
            vertexBuf.clear();
            int verts = 0;
            if (rk == RendererKind.RIBBON) {
                verts = appendRibbonChain(list, camX, camY, camZ, pt, verts);
            } else {
                for (ParticleInstance p : list) {
                    verts = appendBillboard(p, false, mat, camX, camY, camZ, pt, verts);
                }
            }
            flushParticles(verts, mat, bridge);
        }
    }

    private void renderPhoton(PhotonFxRuntime photon, EffectHandle.Snapshot snap, boolean hdrPass,
                              double camX, double camY, double camZ, float pt, @Nullable McResourceBridge bridge) {
        for (PhotonFxRuntime.ObjectRuntime obj : photon.objects()) {
            if (obj.particles.isEmpty()) continue;
            PhotonFxModel.FxObjectSpec spec = obj.spec;
            MaterialModel mat = spec.material();
            if (mat.hdr() != hdrPass) continue;

            applyMaterialState(mat);
            int tex = textures.resolve(mat.primaryTexture(), bridge);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, tex);

            if ((spec.renderMode() == PhotonFxModel.RenderMode.MODEL || spec.renderMode() == PhotonFxModel.RenderMode.MESH)
                    && spec.authoredModel() != null) {
                var pose = snap.pose();
                float unit = Math.max(0.0001f, Math.abs(pose.scaleX()));
                var basis = new Matrix4f().rotation(new org.joml.Quaternionf(pose.x(), pose.y(), pose.z(), pose.w()))
                        .scale(pose.scaleX() / unit, pose.scaleY() / unit, pose.scaleZ() / unit)
                        .mul(obj.localToEffectMatrix).setTranslation(0f, 0f, 0f);
                renderMeshParticles(obj.particles, spec.authoredModel(), mat, tex, camX, camY, camZ, pt, basis);
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
                    verts = appendBillboard(p, stretched, mat, camX, camY, camZ, pt, verts);
                }
            }
            flushParticles(verts, mat, bridge);
        }
    }

    private void renderMeshParticles(List<ParticleInstance> particles, StaticMeshModel mesh, MaterialModel mat,
                                     int tex, double camX, double camY, double camZ, float pt) {
        renderMeshParticles(particles, mesh, mat, tex, camX, camY, camZ, pt, null);
    }

    private void renderMeshParticles(List<ParticleInstance> particles, StaticMeshModel mesh, MaterialModel mat,
                                     int tex, double camX, double camY, double camZ, float pt,
                                     @Nullable Matrix4f basis) {
        GpuMesh gpu = meshes.resolve(mesh);
        if (gpu.vao() == 0 || gpu.indexCount() <= 0) return;
        boolean useNanite = mat.nanite() || mesh.indexCount() >= NANITE_AUTO_INDEX_THRESHOLD;
        if (useNanite) {
            for (ParticleInstance p : particles) {
                float x = (float) (p.lerpX(pt) - camX);
                float y = (float) (p.lerpY(pt) - camY);
                float z = (float) (p.lerpZ(pt) - camZ);
                float sx = Math.max(0.01f, p.width) * gpu.unitScale();
                float sy = Math.max(0.01f, p.height) * gpu.unitScale();
                meshParticleTransform(x, y, z, p.rotationDeg, sx, sy, basis);
                naniteRenderer.drawInstance(mesh, mat, viewProj, modelMat, x, y, z,
                        meshParticleScale(modelMat),
                        activeVpHeight, tex, meshes, p.r, p.g, p.b, p.a);
            }
            return;
        }

        GL20C.glUseProgram(shaders.meshProgram);
        matBuf.clear();
        viewProj.get(matBuf);
        GL20C.glUniformMatrix4fv(shaders.meshViewProjLoc, false, matBuf);
        GL20C.glUniform1i(shaders.meshTexLoc, 0);
        GL20C.glUniform1f(shaders.meshHdrLoc, mat.hdr() ? Math.max(1.5f, mat.emissiveScale()) : 1.0f);
        GL30C.glBindVertexArray(gpu.vao());
        GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, gpu.ebo());

        for (ParticleInstance p : particles) {
            float x = (float) (p.lerpX(pt) - camX);
            float y = (float) (p.lerpY(pt) - camY);
            float z = (float) (p.lerpZ(pt) - camZ);
            float sx = Math.max(0.01f, p.width) * gpu.unitScale();
            float sy = Math.max(0.01f, p.height) * gpu.unitScale();
            meshParticleTransform(x, y, z, p.rotationDeg, sx, sy, basis);
            matBuf.clear();
            modelMat.get(matBuf);
            GL20C.glUniformMatrix4fv(shaders.meshModelLoc, false, matBuf);
            GL20C.glUniform4f(shaders.meshTintLoc, p.r, p.g, p.b, p.a);
            GL11C.glDrawElements(GL11C.GL_TRIANGLES, gpu.indexCount(), GL11C.GL_UNSIGNED_INT, 0L);
        }
        GL30C.glBindVertexArray(0);
    }

    private static float meshParticleScale(Matrix4f transform) {
        float x = transform.m00() * transform.m00() + transform.m01() * transform.m01() + transform.m02() * transform.m02();
        float y = transform.m10() * transform.m10() + transform.m11() * transform.m11() + transform.m12() * transform.m12();
        float z = transform.m20() * transform.m20() + transform.m21() * transform.m21() + transform.m22() * transform.m22();
        return (float) Math.sqrt(Math.max(x, Math.max(y, z)));
    }

    private void meshParticleTransform(float x, float y, float z, float angle, float sx, float sy,
                                       @Nullable Matrix4f basis) {
        modelMat.translation(x, y, z);
        if (basis != null) modelMat.mul(basis);
        modelMat.rotateZ((float) Math.toRadians(angle)).scale(sx, sy, sx);
    }

    private int appendBillboard(ParticleInstance p, boolean stretched, MaterialModel mat,
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

        float u0 = 0f, v0 = 0f, u1 = 1f, v1 = 1f;
        float nextU = 0f, nextV = 0f, subImageLerp = 0f;
        int rows = mat.subUvRows();
        int cols = mat.subUvCols();
        if (rows > 1 || cols > 1) {
            int total = rows * cols;
            float frameProgress = Math.min(total - 1, Math.max(0f, p.normalizedAge() * total));
            int frame = (int) frameProgress;
            int nextFrame = Math.min(total - 1, frame + 1);
            int col = frame % cols;
            int row = frame / cols;
            u0 = (float) col / cols;
            u1 = (float) (col + 1) / cols;
            v0 = (float) row / rows;
            v1 = (float) (row + 1) / rows;
            nextU = (float) (nextFrame % cols - col) / cols;
            nextV = (float) (nextFrame / cols - row) / rows;
            subImageLerp = frameProgress - frame;
        }

        putVert(cx - rX - uX, cy - rY - uY, cz - rZ - uZ, u0, v1, p.r, p.g, p.b, p.a, p, nextU, nextV, subImageLerp);
        putVert(cx + rX - uX, cy + rY - uY, cz + rZ - uZ, u1, v1, p.r, p.g, p.b, p.a, p, nextU, nextV, subImageLerp);
        putVert(cx + rX + uX, cy + rY + uY, cz + rZ + uZ, u1, v0, p.r, p.g, p.b, p.a, p, nextU, nextV, subImageLerp);
        putVert(cx - rX - uX, cy - rY - uY, cz - rZ - uZ, u0, v1, p.r, p.g, p.b, p.a, p, nextU, nextV, subImageLerp);
        putVert(cx + rX + uX, cy + rY + uY, cz + rZ + uZ, u1, v0, p.r, p.g, p.b, p.a, p, nextU, nextV, subImageLerp);
        putVert(cx - rX + uX, cy - rY + uY, cz - rZ + uZ, u0, v0, p.r, p.g, p.b, p.a, p, nextU, nextV, subImageLerp);
        return verts + 6;
    }

    private int appendRibbonChain(List<ParticleInstance> list, double camX, double camY, double camZ,
                                  float pt, int verts) {
        if (list.size() < 2) {
            if (list.size() == 1) return appendBillboard(list.get(0), false, MaterialModel.defaultMaterial("ribbon"), camX, camY, camZ, pt, verts);
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
                    a.r, a.g, a.b, a.a, b.r, b.g, b.b, b.a, a, b, verts);
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
            float w0 = Math.max(0.01f, p.width * 0.35f * (1f - t0 * 0.85f));
            float w1 = Math.max(0.01f, p.width * 0.35f * (1f - t1 * 0.85f));
            verts = appendSegmentQuad(ax, ay, az, bx, by, bz, w0, w1, t0, t1,
                    p.r, p.g, p.b, p.a * (1f - t0 * 0.7f), p.r, p.g, p.b, p.a * (1f - t1 * 0.7f), p, p, verts);
        }
        return verts;
    }

    private int appendSegmentQuad(float ax, float ay, float az, float bx, float by, float bz,
                                  float wa, float wb, float u0, float u1,
                                  float r0, float g0, float b0, float a0,
                                  float r1, float g1, float b1, float a1,
                                  ParticleInstance start, ParticleInstance end, int verts) {
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        float sx = dy * camForward.z - dz * camForward.y;
        float sy = dz * camForward.x - dx * camForward.z;
        float sz = dx * camForward.y - dy * camForward.x;
        float sl = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (sl < 1e-4f) { sx = camRight.x; sy = camRight.y; sz = camRight.z; }
        else { sx /= sl; sy /= sl; sz /= sl; }

        putVert(ax - sx * wa, ay - sy * wa, az - sz * wa, u0, 0f, r0, g0, b0, a0, start);
        putVert(ax + sx * wa, ay + sy * wa, az + sz * wa, u0, 1f, r0, g0, b0, a0, start);
        putVert(bx + sx * wb, by + sy * wb, bz + sz * wb, u1, 1f, r1, g1, b1, a1, end);
        putVert(ax - sx * wa, ay - sy * wa, az - sz * wa, u0, 0f, r0, g0, b0, a0, start);
        putVert(bx + sx * wb, by + sy * wb, bz + sz * wb, u1, 1f, r1, g1, b1, a1, end);
        putVert(bx - sx * wb, by - sy * wb, bz - sz * wb, u1, 0f, r1, g1, b1, a1, end);
        return verts + 6;
    }

    private void putVert(float x, float y, float z, float u, float v, float r, float g, float b, float a,
                         ParticleInstance particle) {
        putVert(x, y, z, u, v, r, g, b, a, particle, 0f, 0f, 0f);
    }

    private void putVert(float x, float y, float z, float u, float v, float r, float g, float b, float a,
                         ParticleInstance particle, float nextU, float nextV, float subImageLerp) {
        vertexBuf.put(x).put(y).put(z).put(u).put(v).put(r).put(g).put(b).put(a)
                .put(particle.normalizedAge()).put(particle.curveRandom).put(particle.lifetime)
                .put(frameTimeSeconds - particle.age)
                .put(particle.customX).put(particle.customY).put(particle.customZ).put(particle.customW)
                .put(nextU).put(nextV).put(subImageLerp);
    }

    private void flushParticles(int verts, MaterialModel mat, @Nullable McResourceBridge bridge) {
        if (verts <= 0) return;
        vertexBuf.flip();

        float hdr = mat.hdr() ? Math.max(1.5f, mat.emissiveScale()) : 1.0f;
        float softFade = (activeSceneDepthTex != 0 && mat.softParticles()) ? mat.softFadeDistance() : 0f;

        if (activeSceneDepthTex != 0) {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, activeSceneDepthTex);
        }
        if (activeSceneColorTex != 0) {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE2);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, activeSceneColorTex);
        }

        CompiledMaterial custom = mat.hasCustomShader() ? customMaterials.resolve(mat, bridge) : null;
        if (custom != null) {
            String tex1Id = mat.secondaryTexture();
            if (tex1Id != null) {
                GL13C.glActiveTexture(GL13C.GL_TEXTURE3);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, textures.resolve(tex1Id, bridge));
            }
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            GL20C.glUseProgram(custom.program());
            matBuf.clear();
            viewProj.get(matBuf);
            if (custom.viewProjLoc() >= 0) GL20C.glUniformMatrix4fv(custom.viewProjLoc(), false, matBuf);
            if (custom.timeLoc() >= 0) GL20C.glUniform1f(custom.timeLoc(), frameTimeSeconds);
            if (custom.hdrLoc() >= 0) GL20C.glUniform1f(custom.hdrLoc(), hdr);
            if (custom.softFadeLoc() >= 0) GL20C.glUniform1f(custom.softFadeLoc(), softFade);
            if (custom.nearFarLoc() >= 0) GL20C.glUniform2f(custom.nearFarLoc(), activeNear, activeFar);
            if (custom.invViewportLoc() >= 0) GL20C.glUniform2f(custom.invViewportLoc(), 1f / activeVpWidth, 1f / activeVpHeight);
            if (custom.paramsLoc() >= 0) {
                float[] p = mat.parameters();
                GL20C.glUniform4f(custom.paramsLoc(), p[0], p[1], p[2], p[3]);
            }
            if (custom.tintLoc() >= 0) GL20C.glUniform4f(custom.tintLoc(), 1f, 1f, 1f, 1f);
            if (custom.tex0Loc() >= 0) GL20C.glUniform1i(custom.tex0Loc(), 0);
            if (custom.sceneDepthLoc() >= 0) GL20C.glUniform1i(custom.sceneDepthLoc(), 1);
            if (custom.sceneColorLoc() >= 0) GL20C.glUniform1i(custom.sceneColorLoc(), 2);
            if (custom.tex1Loc() >= 0) GL20C.glUniform1i(custom.tex1Loc(), 3);
            bindAuthoredMaterial(custom, mat, bridge);
        } else {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            GL20C.glUseProgram(shaders.particleProgram);
            matBuf.clear();
            viewProj.get(matBuf);
            GL20C.glUniformMatrix4fv(shaders.particleViewProjLoc, false, matBuf);
            GL20C.glUniform1i(shaders.particleTexLoc, 0);
            GL20C.glUniform1i(shaders.particleSceneDepthLoc, 1);
            GL20C.glUniform1f(shaders.particleHdrLoc, hdr);
            GL20C.glUniform1f(shaders.particleSoftFadeLoc, softFade);
            GL20C.glUniform2f(shaders.particleNearFarLoc, activeNear, activeFar);
            GL20C.glUniform2f(shaders.particleInvViewportLoc, 1f / activeVpWidth, 1f / activeVpHeight);
        }

        GL30C.glBindVertexArray(quadVao);
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, quadVbo);
        GL15C.glBufferSubData(GL15C.GL_ARRAY_BUFFER, 0L, vertexBuf);
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, verts);
        GL30C.glBindVertexArray(0);
    }

    private void bindAuthoredMaterial(CompiledMaterial custom, MaterialModel material, @Nullable McResourceBridge bridge) {
        int unit = GlStateGuard.MATERIAL_TEXTURE_START;
        for (var sampler : custom.samplers().entrySet()) {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, textures.resolve(material.textures().get(sampler.getKey()), bridge));
            GL20C.glUniform1i(sampler.getValue(), unit++);
        }
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        if (custom.effectFrameBlock() < 0) return;

        if (effectFrameUbo == 0) effectFrameUbo = GL15C.glGenBuffers();
        GL15C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, effectFrameUbo);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            float[] params = material.parameters();
            GL15C.glBufferData(GL31C.GL_UNIFORM_BUFFER,
                    stack.floats(frameTimeSeconds, 0f, 0f, 0f, params[0], params[1], params[2], params[3]),
                    GL15C.GL_STREAM_DRAW);
        }
        GL31C.glUniformBlockBinding(custom.program(), custom.effectFrameBlock(), 0);
        GL30C.glBindBufferBase(GL31C.GL_UNIFORM_BUFFER, 0, effectFrameUbo);
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
        customMaterials.clear();
        naniteRenderer.clear();
        rvt.clear();
        niagaraGpuRunner.clear();
    }

    public void dispose() {
        onResourcesReloaded();
        shaders.dispose();
        sceneDepthCapture.dispose();
        hdrBloom.dispose();
        niagaraDirectRenderer.shutdown();
        naniteRenderer.dispose();
        splatRenderer.dispose();
        groomRenderer.dispose();
        volumeLightRenderer.dispose();
        rvt.dispose();
        niagaraGpuRunner.shutdown();
        if (effectFrameUbo != 0) {
            GL15C.glDeleteBuffers(effectFrameUbo);
            effectFrameUbo = 0;
        }
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
