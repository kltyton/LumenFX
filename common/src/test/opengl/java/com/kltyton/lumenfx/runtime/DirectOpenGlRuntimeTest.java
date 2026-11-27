package com.kltyton.lumenfx.runtime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache;
import com.kltyton.lumenfx.math.SourceRandomStream;
import com.kltyton.lumenfx.runtime.audio.DirectSpatialAudioEngine;
import com.kltyton.lumenfx.runtime.bridge.McResourceBridge;
import com.kltyton.lumenfx.runtime.concurrent.DirectFrameExecutor;
import com.kltyton.lumenfx.runtime.gl.DirectGlNiagaraGpuRunner;
import com.kltyton.lumenfx.runtime.gl.material.DirectGlCustomMaterialCompiler;
import com.kltyton.lumenfx.runtime.gl.material.DirectGlShaderImports;
import com.kltyton.lumenfx.runtime.gl.rvt.DirectGlRuntimeVirtualTexture;
import com.kltyton.lumenfx.runtime.model.BlendMode;
import com.kltyton.lumenfx.runtime.model.EffectModel;
import com.kltyton.lumenfx.runtime.model.GaussianSplatModel;
import com.kltyton.lumenfx.runtime.model.GroomAssetModel;
import com.kltyton.lumenfx.runtime.model.MaterialModel;
import com.kltyton.lumenfx.runtime.model.NaniteClusterModel;
import com.kltyton.lumenfx.runtime.model.NiagaraSystemModel;
import com.kltyton.lumenfx.runtime.model.PhotonFxModel;
import com.kltyton.lumenfx.runtime.model.ProductionEffectParser;
import com.kltyton.lumenfx.runtime.model.ScalarCurve;
import com.kltyton.lumenfx.runtime.model.StaticMeshModel;
import com.kltyton.lumenfx.runtime.model.UnityEmitterModel;
import com.kltyton.lumenfx.runtime.niagara.DirectVectorDataSet;
import com.kltyton.lumenfx.runtime.niagara.DirectVectorProgram;
import com.kltyton.lumenfx.runtime.niagara.DirectVectorVm;
import com.kltyton.lumenfx.math.HalfFloat;
import com.kltyton.lumenfx.runtime.simulation.EffectRuntimeInstance;
import com.kltyton.lumenfx.runtime.simulation.NiagaraSystemRuntime;
import com.kltyton.lumenfx.runtime.simulation.ParticleInstance;
import com.kltyton.lumenfx.runtime.simulation.PhotonFxRuntime;
import com.kltyton.lumenfx.runtime.simulation.ServerForceAuthority;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class DirectOpenGlRuntimeTest {
    private static final Path QA_PACK_ROOT = Path.of(
            "E:/.aaabackup/智能体产物/测试文件/LumenFX/release-matrix/standalone/resourcepacks/vfx_qa");
    private static final Path GAP_PACK_ROOT = Path.of(
            "E:/.aaabackup/智能体产物/测试文件/LumenFX/release-matrix/standalone/resourcepacks/vfx_gap_closure");

    private static final String UNITY_262_DUAL_CURVE_JSON = """
            {
              "format_version": 1,
              "materials": {
                "mat_0": {
                  "blend": "additive",
                  "depth_test": true,
                  "depth_write": false,
                  "cull": false,
                  "textures": {
                    "Sampler0": "qa_release:textures/effects/test_dot.png"
                  }
                }
              },
              "emitters": [
                {
                  "name": "burst_dual_curve",
                  "renderer": "billboard",
                  "material": "mat_0",
                  "duration": 2.0,
                  "loop": false,
                  "max_particles": 256,
                  "rate": 0.0,
                  "bursts": [
                    { "time": 0.0, "min": 12, "max": 12, "cycles": 1, "interval": 0.1, "probability": 1.0 }
                  ],
                  "lifetime": {
                    "kind": "blend",
                    "low": { "kind": "curve", "keys": [ [0.0, 0.4, 0.0, 0.0], [1.0, 0.8, 0.0, 0.0] ] },
                    "high": { "kind": "curve", "keys": [ [0.0, 1.0, 0.0, 0.0], [1.0, 1.6, 0.0, 0.0] ] }
                  },
                  "speed": { "kind": "blend", "low": 1.0, "high": 3.0 },
                  "width": 0.5,
                  "height": 0.5,
                  "shape": {
                    "kind": "box_shell_thick",
                    "size": [4.0, 4.0, 4.0],
                    "unity": { "box_thickness": [0.25, 0.25, 0.25] }
                  },
                  "collision": { "enabled": true, "collider_force": 4.5, "bounce": 0.6 }
                }
              ]
            }
            """;

    @Test
    void unity262DualCurveLifetimeBoundsBoxShellAndFixedSeedDeterminism() {
        EffectModel model = ProductionEffectParser.parse("lumenfx:test_dual_curve", UNITY_262_DUAL_CURVE_JSON, null);
        assertEquals(1, model.emitters().size());
        UnityEmitterModel em = model.emitters().get(0);
        assertFalse(em.loop());
        assertEquals(0.0f, em.rate().evaluate(0f, 0f), 1e-6f);
        assertEquals(1, em.bursts().size());
        assertEquals(12, em.bursts().get(0).minCount());
        assertEquals("qa_release:textures/effects/test_dot.png", em.material().primaryTexture());
        assertEquals(ScalarCurve.Mode.TWO_CURVES, em.lifetime().mode());

        int totalParticles = 0;
        for (int seed = 1; seed <= 33; seed++) {
            EffectRuntimeInstance instA = new EffectRuntimeInstance(model, new EffectHandle(new Vec3(1, 2, 3)), null, seed, 1);
            EffectRuntimeInstance instB = new EffectRuntimeInstance(model, new EffectHandle(new Vec3(1, 2, 3)), null, seed, 1);
            assertTrue(instA.tick(0.05f, 1, null));
            assertTrue(instB.tick(0.05f, 1, null));

            List<ParticleInstance> pa = instA.unityEmitters().get(0).particles;
            List<ParticleInstance> pb = instB.unityEmitters().get(0).particles;
            assertEquals(12, pa.size());
            assertEquals(12, pb.size());

            float lo = em.lifetime().evaluateMin(0f) - 1e-4f;
            float hi = em.lifetime().evaluateMax(0f) + 1e-4f;
            for (int i = 0; i < pa.size(); i++) {
                float lt = pa.get(i).lifetime;
                assertTrue(lt >= lo && lt <= hi, "Lifetime " + lt + " outside [" + lo + ", " + hi + "]");
                assertEquals(lt, pb.get(i).lifetime, 1e-6f, "Fixed seed replay must be deterministic");
                assertEquals(pa.get(i).x, pb.get(i).x, 1e-6);
                totalParticles++;
            }
        }
        assertEquals(396, totalParticles);
    }

    @Test
    void syntheticLfMeshAndPhotonAuthoredModelDecodeCorrectly() throws Exception {
        // Build a minimal valid v1 .lfmesh binary (magic 0x484d464c, 3 verts, 1 triangle)
        ByteBuffer lf = ByteBuffer.allocate(24 + 3 * 4 + 3 * 12 + 3 * 12 + 3 * 12 + 3 * 4 + 3 * 16 + 3 * 8 + 3 * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        lf.putInt(0x484d464c).putInt(1).putInt(3).putInt(3).putInt(1).putInt(0);
        lf.putInt(0).putInt(1).putInt(2); // instanceIds
        lf.putFloat(0f).putFloat(0f).putFloat(0f).putFloat(1f).putFloat(0f).putFloat(0f).putFloat(0f).putFloat(1f).putFloat(0f); // pos
        lf.putFloat(0f).putFloat(0f).putFloat(1f).putFloat(0f).putFloat(0f).putFloat(1f).putFloat(0f).putFloat(0f).putFloat(1f); // norm
        lf.putFloat(1f).putFloat(0f).putFloat(0f).putFloat(1f).putFloat(0f).putFloat(0f).putFloat(1f).putFloat(0f).putFloat(0f); // tan
        lf.putFloat(1f).putFloat(1f).putFloat(1f); // binormalSign
        for (int i = 0; i < 12; i++) lf.putFloat(1f); // colors
        lf.putFloat(0f).putFloat(0f).putFloat(1f).putFloat(0f).putFloat(0f).putFloat(1f); // uv0
        lf.putInt(0).putInt(1).putInt(2); // indices
        StaticMeshModel decoded = StaticMeshModel.decodeLfMesh("test:tri", lf.array());
        assertEquals(3, decoded.vertexCount());
        assertEquals(3, decoded.indexCount());

        // Build minimal Photon 26.2 fxData NBT with parent empty + child authored Model
        byte[] fxBytes = buildSyntheticPhotonModelFx();
        PhotonFxModel photon = PhotonFxModel.parseNbt("test:authored_cube", fxBytes, null);
        assertEquals(2, photon.objects().size());
        assertEquals(PhotonFxModel.ObjectType.EMPTY, photon.objects().get(0).type());
        PhotonFxModel.FxObjectSpec child = photon.objects().get(1);
        assertEquals("qa_photon_model", child.name());
        assertEquals(PhotonFxModel.RenderMode.MODEL, child.renderMode());
        assertEquals("minecraft:block/sea_lantern", child.modelLocation());
        assertEquals("minecraft:textures/block/sea_lantern.png", child.material().primaryTexture());
        assertNotNull(child.authoredModel());
        assertEquals(24, child.authoredModel().vertexCount());
        assertEquals(36, child.authoredModel().indexCount());
    }

    @Test
    void resourceReloadInvalidatesOldHandlesAndAllowsCleanRespawn() {
        DirectVfxEngine engine = DirectVfxEngine.INSTANCE;
        engine.clearLevel();
        engine.registerEffect(ProductionEffectParser.parse("lumenfx:reload_test", UNITY_262_DUAL_CURVE_JSON, null));
        EffectHandle oldHandle = engine.spawn(null, "lumenfx:reload_test", Vec3.ZERO, 101L);
        assertTrue(oldHandle.isAlive());
        assertEquals(1, engine.activeInstanceCount());

        engine.reloadResources();
        assertFalse(oldHandle.isAlive(), "Resource reload must invalidate old handles");
        assertEquals(0, engine.activeInstanceCount());

        engine.registerEffect(ProductionEffectParser.parse("lumenfx:reload_test", UNITY_262_DUAL_CURVE_JSON, null));
        EffectHandle newHandle = engine.spawn(null, "lumenfx:reload_test", Vec3.ZERO, 202L);
        assertTrue(newHandle.isAlive());
        newHandle.close();
        engine.tickClient(0.05f);
        assertFalse(newHandle.isAlive());
        assertEquals(0, engine.activeInstanceCount());
    }

    @Test
    void keyframeBridgeAutoRoutesLoadedDirectEffectsAndPassesThroughKbrParticles() {
        DirectVfxEngine engine = DirectVfxEngine.INSTANCE;
        engine.clearLevel();
        engine.registerEffect(ProductionEffectParser.parse("my_mod:sword_arc", UNITY_262_DUAL_CURVE_JSON, null));
        assertTrue(engine.hasEffect("my_mod:sword_arc"));
        assertFalse(engine.hasEffect("kbr:snowstorm_flame"));

        // DirectKeyframeAdapter is installed automatically by DirectVfxEngine.
        assertNotNull(com.kltyton.lumenfx.client.keyframe.KeyframeEffectManager.adapter());
        assertTrue(com.kltyton.lumenfx.client.keyframe.KeyframeEffectManager.adapter().hasEffect("my_mod:sword_arc"));
        assertFalse(com.kltyton.lumenfx.client.keyframe.KeyframeEffectManager.adapter().hasEffect("kbr:snowstorm_flame"));

        Object host = new Object();
        Object controller = new Object();
        // KBR particle not registered in LumenFX passes through (returns false)
        assertFalse(com.kltyton.lumenfx.client.keyframe.KeyframeEffectManager.handleKeyframe(
                null, host, null, controller, "attack", "kbr:snowstorm_flame", "hand_r", null, true, 0, 0, 0, true));

        // Registered LumenFX effect auto-routes, defers spawn to locator sample, updates 3D pose, and closes on stop
        assertTrue(com.kltyton.lumenfx.client.keyframe.KeyframeEffectManager.handleKeyframe(
                null, host, null, controller, "attack", "my_mod:sword_arc", "hand_r", "scale=1.5; offset=0,1,0", true, 0, 0, 0, true));
        assertEquals(0, engine.activeInstanceCount());
        com.kltyton.lumenfx.client.keyframe.KeyframeEffectManager.onLocatorSample(
                host, null, "hand_r", 4.0, 5.0, 6.0, 0.0F, 0.0F, 0.0F, 1.0F, null);
        assertEquals(1, engine.activeInstanceCount());
        com.kltyton.lumenfx.client.keyframe.KeyframeEffectManager.stopForController(host, controller);
        engine.tickClient(0.05f);
        assertEquals(0, engine.activeInstanceCount());

        // Params parser & explicit prefix check
        var parsed = com.kltyton.lumenfx.api.client.keyframe.LumenFxKeyframeBridge.parseEffectId("lumenfx:my_mod:sword_arc");
        assertNotNull(parsed);
        assertTrue(parsed.explicitLumenFx());
        assertEquals("my_mod:sword_arc", parsed.canonicalId());

        var params = com.kltyton.lumenfx.api.client.keyframe.KeyframeEffectParams.parse(
                "follow=true; scale=1.5; offset=0,1,0; color=#33CCFF; beam_end=tip", null, true);
        assertTrue(params.follow());
        assertEquals(1.5F, params.scale(), 1e-5F);
        assertTrue(params.hasColor());
        assertEquals("tip", params.beamEndLocator());

        // Mixin config plugin never loads absent GeckoLib/KBR classes
        var plugin = new com.kltyton.lumenfx.client.keyframe.LumenFxKeyframeMixinPlugin();
        assertFalse(plugin.shouldApplyMixin("software.bernie.geckolib.DoesNotExist", "DummyMixin"));

        engine.clearLevel();
    }

    @Test
    void realQaProductionAssetsParseWith262Parity() throws Exception {
        if (!Files.isDirectory(QA_PACK_ROOT)) return;
        McResourceBridge packBridge = new DirectoryResourceBridge(QA_PACK_ROOT, GAP_PACK_ROOT);

        // 1. Unity shape_box_shell_thick.json: loop=false, burst=48, rate=0, real texture, bounded box shell
        byte[] unityRaw = packBridge.readResource("qa_release:lumenfx/effects/shape_box_shell_thick.json");
        assertNotNull(unityRaw, "QA pack missing shape_box_shell_thick.json");
        EffectModel unity = ProductionEffectParser.parse("qa_release:shape_box_shell_thick",
                new String(unityRaw, StandardCharsets.UTF_8), packBridge);
        assertEquals(1, unity.emitters().size());
        UnityEmitterModel uem = unity.emitters().get(0);
        assertFalse(uem.loop(), "shape_box_shell_thick must not loop");
        assertEquals(0.0f, uem.rate().evaluate(0f, 0f), 1e-6f, "shape_box_shell_thick continuous rate must be 0");
        assertEquals(1, uem.bursts().size());
        assertEquals(48, uem.bursts().get(0).minCount());
        assertEquals(48, uem.bursts().get(0).maxCount());
        assertEquals("qa_release:textures/effects/shape_box_shell_thick/shape_qa_dot_unitywhite.png",
                uem.material().primaryTexture());

        EffectRuntimeInstance uInst = new EffectRuntimeInstance(unity, new EffectHandle(Vec3.ZERO), null, 77L, 1);
        assertTrue(uInst.tick(0.05f, 1, packBridge));
        assertEquals(48, uInst.unityEmitters().get(0).particles.size(), "Burst must spawn exact 48 particles");

        // 2. Mesh compat_mesh.json + qa_cube.lfmesh
        byte[] meshEffRaw = packBridge.readResource("vfx_qa:lumenfx/effects/compat_mesh.json");
        assertNotNull(meshEffRaw, "QA pack missing compat_mesh.json");
        EffectModel meshEff = ProductionEffectParser.parse("vfx_qa:compat_mesh",
                new String(meshEffRaw, StandardCharsets.UTF_8), packBridge);
        UnityEmitterModel mem = meshEff.emitters().get(0);
        assertEquals(UnityEmitterModel.RendererKind.MESH, mem.renderer());
        assertNotNull(mem.meshEntry());
        assertNotNull(mem.meshEntry().mesh(), "Real .lfmesh must be decoded from qa_cube");
        assertTrue(mem.meshEntry().mesh().vertexCount() >= 8);
        assertTrue(mem.meshEntry().mesh().indexCount() >= 12 && mem.meshEntry().mesh().indexCount() % 3 == 0);

        // 3. Niagara GPU ribbon_gpu_fountain.json -> gpuProgram + SPIR-V stages + declaredMax=1,000,000 + RIBBON
        byte[] ribbonRaw = packBridge.readResource("qa_release:lumenfx/effects/ribbon_gpu_fountain.json");
        assertNotNull(ribbonRaw, "QA pack missing ribbon_gpu_fountain.json");
        EffectModel ribbon = ProductionEffectParser.parse("qa_release:ribbon_gpu_fountain",
                new String(ribbonRaw, StandardCharsets.UTF_8), packBridge);
        assertNotNull(ribbon.niagara());
        NiagaraSystemModel.EmitterSpec rem = ribbon.niagara().emitters().get(0);
        assertTrue(rem.isGpu(), "ribbon_gpu_fountain must be recognized as GPU emitter");
        assertEquals(1_000_000, rem.declaredMaxParticles(), "Declared max_particles must not be truncated to 8192");
        assertEquals(NiagaraSystemModel.RendererKind.RIBBON, rem.renderers().get(0).kind());
        assertNotNull(rem.gpuProgram());
        assertTrue(rem.gpuProgram().hasSpirvShaders(), "Baked OpenGL SPIR-V compute shaders must be loaded");
        assertTrue(rem.gpuProgram().stages().get(0).openglSpirv().length > 1000);
        assertTrue(rem.gpuProgram().stages().get(0).uniformBlocks().containsKey("Globals"));

        // 4. Niagara GPU SimCache limit asset (if gap pack exists)
        byte[] simLimitRaw = packBridge.readResource("qa_release:lumenfx/effects/ns_qagpusimcachelimit.json");
        if (simLimitRaw != null) {
            EffectModel simLimit = ProductionEffectParser.parse("qa_release:ns_qagpusimcachelimit",
                    new String(simLimitRaw, StandardCharsets.UTF_8), packBridge);
            assertNotNull(simLimit.niagara());
            NiagaraSystemModel.EmitterSpec sem = simLimit.niagara().emitters().get(0);
            assertTrue(sem.isGpu());
            assertEquals(1_000_000, sem.declaredMaxParticles());
            assertTrue(sem.gpuProgram().hasSpirvShaders());
        }

        // 5. Photon photon_authored_model.fx
        byte[] photonRaw = packBridge.readResource("qa_release:fx/photon_authored_model.fx");
        assertNotNull(photonRaw, "QA pack missing photon_authored_model.fx");
        PhotonFxModel photon = PhotonFxModel.parseNbt("qa_release:photon_authored_model", photonRaw, packBridge);
        assertEquals(2, photon.objects().size());
        assertEquals("qa_photon_model_parent", photon.objects().get(0).name());
        assertEquals(PhotonFxModel.ObjectType.EMPTY, photon.objects().get(0).type());
        PhotonFxModel.FxObjectSpec modelObj = photon.objects().get(1);
        assertEquals("qa_photon_model", modelObj.name());
        assertEquals(PhotonFxModel.RenderMode.MODEL, modelObj.renderMode());
        assertEquals("minecraft:block/sea_lantern", modelObj.modelLocation());
        assertEquals("minecraft:textures/block/sea_lantern.png", modelObj.material().primaryTexture());
        assertNotNull(modelObj.authoredModel());
        assertEquals(24, modelObj.authoredModel().vertexCount());
        assertEquals(36, modelObj.authoredModel().indexCount());
    }

    @Test
    void serverColliderForceRegistryAndLifecycle() {
        ServerForceAuthority.clearAll();
        JsonObject json = JsonParser.parseString(UNITY_262_DUAL_CURVE_JSON).getAsJsonObject();
        ServerForceAuthority.registerFromJson("lumenfx:test_force", json);
        var specs = ServerForceAuthority.specsFor("lumenfx:test_force");
        assertEquals(1, specs.size());
        assertEquals(4.5, specs.get("burst_dual_curve").colliderForce(), 1e-6);

        Runnable cancel = ServerForceAuthority.authorize("lumenfx:test_force", 1L, () -> Vec3.ZERO, 12.0, 3);
        assertEquals(1, ServerForceAuthority.activeAuthorizedCount());
        ServerForceAuthority.tickServer();
        assertEquals(1, ServerForceAuthority.activeAuthorizedCount());
        cancel.run();
        assertEquals(0, ServerForceAuthority.activeAuthorizedCount());
    }

    @Test
    void modMetadataHasCompatibleVersionRangesOnly() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        List<String> checked = new ArrayList<>();
        Pattern singleLockToml = Pattern.compile("versionRange\\s*=\\s*\"\\[[^,\\]]+\\]\"");

        for (String path : List.of("META-INF/neoforge.mods.toml", "META-INF/mods.toml", "fabric.mod.json")) {
            try (InputStream in = cl.getResourceAsStream(path)) {
                if (in == null) continue;
                String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                checked.add(path);
                assertFalse(content.contains("${"), "Unexpanded placeholder found in " + path);
                if (path.endsWith(".toml")) {
                    assertFalse(singleLockToml.matcher(content).find(), "Single-version lock found in " + path);
                } else {
                    JsonObject mod = JsonParser.parseString(content).getAsJsonObject();
                    if (mod.has("depends") && mod.get("depends").isJsonObject()) {
                        for (var entry : mod.getAsJsonObject("depends").entrySet()) {
                            String v = entry.getValue().getAsString().trim();
                            assertTrue(v.startsWith(">=") || v.startsWith(">") || v.startsWith("~") || v.startsWith("^") || v.equals("*"),
                                    "Dependency " + entry.getKey() + " in fabric.mod.json is locked to exact version: " + v);
                        }
                    }
                }
            }
        }
        assertFalse(checked.isEmpty(), "Expected at least one processed mod descriptor on test classpath");
    }

    @Test
    void realAuroraTrailFixedOriginEmitsZeroAndMovingOriginEmitsByDistance() throws Exception {
        Path edenResources = Path.of("G:/weituo/code/26.2/Eden Realm/src/main/resources");
        Path auroraPath = edenResources.resolve("assets/eden_realm/lumenfx/effects/pegasus/aurora_trail.json");
        if (!Files.isRegularFile(auroraPath)) return;

        String json = Files.readString(auroraPath, StandardCharsets.UTF_8);
        EffectModel model = ProductionEffectParser.parse("eden_realm:pegasus/aurora_trail", json,
                new DirectoryResourceBridge(edenResources));
        assertNotNull(model.niagara());
        assertTrue(model.niagara().hasSystemVm(), "aurora_trail must load real system_spawn/system_update VM");
        assertEquals(8, model.niagara().emitters().size());
        for (NiagaraSystemModel.EmitterSpec em : model.niagara().emitters()) {
            assertTrue(em.hasCpuVm(), "Emitter " + em.name() + " must have compiled CPU VM");
            assertEquals(0.0f, em.explicitSpawnRate(), 1e-6f, "Real VM emitters must NOT inject hardcoded 40/s spawn rate");
        }

        // Fixed origin: all 7 SpawnPerUnit emitters MUST emit 0 particles at tick 10 and tick 20;
        // emitter 7 (Minimal007, SpawnBurst_Instantaneous) emits its authored 1 burst particle.
        Vec3 fixedOrigin = new Vec3(7.5, -58.38, 5.5);
        EffectHandle handle = new EffectHandle(fixedOrigin);
        EffectRuntimeInstance inst = new EffectRuntimeInstance(model, handle, null, 1337L, 1);
        for (int t = 1; t <= 20; t++) {
            assertTrue(inst.tick(0.05f, 1, null));
            if (t == 10 || t == 20) {
                int spawnPerUnitCount = 0;
                for (int e = 0; e < 7; e++) {
                    spawnPerUnitCount += inst.niagaraRuntime().emitters().get(e).particleCount();
                }
                assertEquals(0, spawnPerUnitCount,
                        "All 7 fixed-origin SpawnPerUnit emitters in aurora_trail must emit 0 particles at tick " + t);
                assertTrue(inst.totalParticles() <= 1,
                        "Total fixed-origin particles must be <= 1 (Minimal007 burst only), got " + inst.totalParticles());
            }
        }

        // Moving origin: translating 0.6 blocks/tick MUST make the 7 SpawnPerUnit emitters emit real particles!
        for (int t = 1; t <= 12; t++) {
            handle.moveTo(fixedOrigin.add(t * 0.6, 0.0, 0.0));
            assertTrue(inst.tick(0.05f, 1, null));
        }
        int movingSpawnPerUnitCount = 0;
        for (int e = 0; e < 7; e++) {
            movingSpawnPerUnitCount += inst.niagaraRuntime().emitters().get(e).particleCount();
        }
        assertTrue(movingSpawnPerUnitCount > 0,
                "Moving SpawnPerUnit emitters in aurora_trail must emit real particles, got " + movingSpawnPerUnitCount);

        // Close & drain cleanly
        handle.close();
        assertFalse(inst.tick(0.05f, 1, null));
    }

    @Test
    void niagaraSimCacheDecodeTruncateInvalidateIdsInterpolateAndResume() throws Exception {
        byte[] lfsc = buildSyntheticSimCacheLfsc();
        NiagaraSimulationCache cache = NiagaraSimulationCache.decode("qa_release:test_cache", lfsc);
        assertEquals("qa_test_system", cache.sourceSystem());
        assertEquals(2, cache.frames().size());
        assertEquals(120, cache.frames().get(0).emitters().get(0).particles().count());

        String dummyNiagara = """
                {
                  "format_version": 1,
                  "niagara": {
                    "emitters": [
                      {
                        "name": "e0",
                        "source_name": "E0",
                        "max_particles": 1000000,
                        "attributes": [
                          { "name": "Position", "type": 0, "component": 0, "count": 3 },
                          { "name": "QaIntTag", "type": 1, "component": 0, "count": 1 },
                          { "name": "QaHalfScalar", "type": 2, "component": 0, "count": 1 }
                        ]
                      }
                    ]
                  }
                }
                """;
        EffectModel model = ProductionEffectParser.parse("qa_release:test_cache_sys", dummyNiagara, null);
        EffectHandle handle = new EffectHandle(Vec3.ZERO);
        EffectRuntimeInstance inst = new EffectRuntimeInstance(model, handle, null, 7L, 1);
        NiagaraSystemRuntime.EmitterState es = inst.niagaraRuntime().emitters().get(0);
        es.setSimCacheCapacityLimit(64);

        // Evaluate halfway between frame 0 (age=0.0) and frame 1 (age=1.0) with nextRows interpolation!
        handle.setNiagaraCache(cache, 0.5f);
        assertTrue(inst.tick(0.05f, 1, null));

        assertEquals(64, es.current.count(), "Hardware cap must truncate 120 rows to 64");
        assertEquals(120, es.cachedTotalSpawned(), "Untruncated totalSpawned must be preserved");
        assertEquals(500000, es.cachedAcquireTag(), "High acquireTag must be preserved");
        // Row 0 interpolated between z=10.0 (frame 0) and nextRow=2 z=30.0 (frame 1) -> midpoint 20.0
        assertEquals(20.0f, es.current.floating(2, 0), 1e-4f);
        // Retained boundary row 63 keeps Int=777 and Half=1.25
        assertEquals(777, es.readIntAttribute("QaIntTag", 63));
        assertEquals(1.25f, es.readHalfAttribute("QaHalfScalar", 63), 1e-3f);
        // Persistent ID table: retained ID 0 -> row 0; dead-gap ID 5 -> -1; truncated ID 100 -> -1
        assertEquals(0, es.lookupIdRow(0));
        assertEquals(-1, es.lookupIdRow(5));
        assertEquals(-1, es.lookupIdRow(100));

        // Exiting cache resumes live cleanly without crashing
        handle.setNiagaraCache(null, null);
        assertTrue(inst.tick(0.05f, 1, null));
        assertEquals(500000, es.cachedAcquireTag());
    }

    @Test
    void gpuSimCacheHandoffUsesDynamicDeviceLimitAndPreservesHalfIdsAndAcquireTag() throws Exception {
        // Exact 26.2 RTX 5080 OpenGL formula: 2061 floats, 4 ints, 48 halves under 2 GiB SSBO / 256M texels -> 130,239!
        assertEquals(130_239, DirectGlNiagaraGpuRunner.maximumParticleCapacity(
                2061, 4, 48, 2_147_483_647L, 268_435_456L));
        // Smaller GPU emitter must NOT be artificially clamped to 16,384!
        assertTrue(DirectGlNiagaraGpuRunner.maximumParticleCapacity(
                16, 4, 4, 2_147_483_647L, 268_435_456L) > 1_000_000);

        DirectGlNiagaraGpuRunner runner = new DirectGlNiagaraGpuRunner();
        runner.observeCachedParticleTag(500_000);
        assertEquals(500_001, runner.nextAcquireTag());
        assertEquals(500_002, runner.nextAcquireTag());

        byte[] lfsc = buildSyntheticSimCacheLfsc();
        NiagaraSimulationCache cache = NiagaraSimulationCache.decode("qa_release:gpu_handoff_cache", lfsc);
        String gpuNiagara = """
                {
                  "format_version": 1,
                  "niagara": {
                    "emitters": [
                      {
                        "name": "e0",
                        "source_name": "E0",
                        "sim_target": "gpu",
                        "max_particles": 1000000,
                        "gpu_program": { "shader_pipeline": "synthetic_gpu_handoff" },
                        "attributes": [
                          { "name": "Position", "type": 0, "component": 0, "count": 3 },
                          { "name": "QaIntTag", "type": 1, "component": 0, "count": 1 },
                          { "name": "QaHalfScalar", "type": 2, "component": 0, "count": 1 }
                        ]
                      }
                    ]
                  }
                }
                """;
        EffectModel model = ProductionEffectParser.parse("qa_release:gpu_handoff_sys", gpuNiagara, null);
        EffectHandle handle = new EffectHandle(Vec3.ZERO);
        EffectRuntimeInstance inst = new EffectRuntimeInstance(model, handle, null, 7L, 1);
        NiagaraSystemRuntime.EmitterState es = inst.niagaraRuntime().emitters().get(0);
        assertTrue(es.spec.isGpu());
        es.setSimCacheCapacityLimit(64);

        handle.setNiagaraCache(cache, 0.0f);
        assertTrue(inst.tick(0.05f, 1, null));

        // Transition out of SimCache into live GPU simulation -> must enqueue full handoff payload
        handle.setNiagaraCache(null, null);
        assertTrue(inst.tick(0.05f, 1, null));
        assertEquals(1, es.pendingGpuTicks.size());
        NiagaraSystemRuntime.GpuTickRequest req = es.pendingGpuTicks.get(0);
        assertNotNull(req.cacheUpload(), "Must enqueue Float/Int/Half dataset for GPU upload");
        assertEquals(64, req.cacheUpload().count());
        assertEquals(1, req.cacheUpload().componentCount(2), "Half components must be preserved in GPU upload");
        assertEquals(1.25f, req.cacheUpload().half(0, 63), 1e-3f);
        assertEquals(777, req.cacheUpload().integer(0, 63));
        assertNotNull(req.cacheIdTable(), "Persistent ID table must be passed to GPU handoff");
        assertEquals(0, req.cacheIdTable()[0]);
        assertEquals(-1, req.cacheIdTable()[5]);
        assertEquals(-1, req.cacheIdTable()[100]);
        assertEquals(120, req.cacheTotalSpawned(), "Untruncated totalSpawned must be passed to GPU handoff");
        assertEquals(500_000, req.cacheAcquireTag(), "Cached AcquireTag must be passed to GPU handoff");
        // Stale CPU cache mirror must be cleared immediately on GPU handoff
        assertEquals(0, es.current.count());
        assertTrue(es.cpuParticles.isEmpty());

        // Simulate live GPU drain (64 -> 1 -> 0) after handle.stopEmitting() -> instance MUST reclaim cleanly!
        handle.stopEmitting();
        es.pendingGpuTicks.clear();
        es.liveGpuParticleCount.set(1);
        assertEquals(1, inst.totalParticles());
        assertEquals(1, inst.liveGpuParticles());
        assertTrue(inst.tick(0.05f, 1, null), "Instance must stay alive while 1 GPU particle remains");
        assertTrue(handle.isAlive());

        // Drain final GPU particle to 0
        es.pendingGpuTicks.clear();
        es.liveGpuParticleCount.set(0);
        es.gpuReadbackParticles = List.of();
        assertEquals(0, inst.totalParticles());
        assertEquals(0, inst.liveGpuParticles());
        assertFalse(inst.tick(0.05f, 1, null), "Instance must finish and reclaim once GPU particles drain to 0");
        assertFalse(handle.isAlive());
    }

    @Test
    void sourceRandomStreamDeterministic() {
        SourceRandomStream a = new SourceRandomStream(123456);
        SourceRandomStream b = new SourceRandomStream(123456);
        for (int i = 0; i < 64; i++) {
            assertEquals(a.fraction(), b.fraction(), 0f);
        }
    }

    @Test
    void directFrameExecutorOverlapsOwnerWorkAndPreservesWorkStealingSemantics() {
        DirectFrameExecutor exec = DirectFrameExecutor.INSTANCE;
        int n = 64;
        int[] visits = new int[n];
        boolean[] ownerRan = {false};
        int parts = exec.partitions(n, 256);
        exec.executeIndexed(n, parts, () -> ownerRan[0] = true, (partition, index) -> visits[index]++);
        assertTrue(ownerRan[0], "Owner work must execute alongside worker-stealing tasks");
        for (int i = 0; i < n; i++) {
            assertEquals(1, visits[i], "Each indexed work item must run exactly once");
        }
    }

    @Test
    void directVectorVmHoistedArithmeticAndCurlNoiseMatchExactSemantics() {
        // Register layout: temp[0..4], const[5=2.5f, 6=-1], inputs[7=in0, 8=in1, mirror 9..10], outputs[11=out0, 12=out1]
        List<DirectVectorProgram.Instruction> ins = List.of(
                new DirectVectorProgram.Instruction(DirectVectorProgram.Opcode.MUL, 2, 0, 0, new int[]{7, 8, 0}),
                new DirectVectorProgram.Instruction(DirectVectorProgram.Opcode.ADD, 2, 0, 0, new int[]{0, 5, 1}),
                new DirectVectorProgram.Instruction(DirectVectorProgram.Opcode.EXTERNAL_FUNC_CALL, 3, 0, 0, new int[]{7, 8, 1, 2, 3, 4}),
                new DirectVectorProgram.Instruction(DirectVectorProgram.Opcode.ACQUIREINDEX, 1, 0, 0, new int[]{6}),
                new DirectVectorProgram.Instruction(DirectVectorProgram.Opcode.OUTPUTDATA_FLOAT, 2, 0, 0, new int[]{1, 2, 11, 12})
        );
        byte[] consts = new byte[48];
        ByteBuffer cb = ByteBuffer.wrap(consts).order(ByteOrder.LITTLE_ENDIAN);
        cb.putFloat(32, 2.5f); // slot 8 -> reg 5
        cb.putInt(36, -1);     // slot 9 -> reg 6 (true mask)
        DirectVectorProgram prog = new DirectVectorProgram(
                5, 2, 1, 32, false, 16,
                List.of(),
                consts,
                new int[]{8, 9},
                List.of(
                        new DirectVectorProgram.Input(0, 0, 0, false),
                        new DirectVectorProgram.Input(0, 0, 1, false)
                ),
                List.of(
                        new DirectVectorProgram.Output(0, 0, 0),
                        new DirectVectorProgram.Output(0, 0, 1)
                ),
                List.of(new DirectVectorProgram.External("curl_noise", 3, 3, "CurlNoise", List.of(), null)),
                ins);
        DirectVectorDataSet in = new DirectVectorDataSet(2, 0, 0);
        in.count(8);
        for (int i = 0; i < 8; i++) {
            in.floating(0, i, (i + 1) * 1.5f);
            in.floating(1, i, 4.0f);
        }

        DirectVectorDataSet out = new DirectVectorDataSet(2, 0, 0);
        DirectVectorVm vm = new DirectVectorVm(prog);
        vm.execute(8, new DirectVectorDataSet[]{in}, new DirectVectorDataSet[]{out}, 0, consts, new SourceRandomStream(42));
        assertEquals(8, out.count());
        for (int i = 0; i < 8; i++) {
            float expected = (i + 1) * 1.5f * 4.0f + 2.5f;
            assertEquals(expected, out.floating(0, i), 1e-5f);
            assertTrue(Float.isFinite(out.floating(1, i)), "curl_noise must produce finite output");
        }
        // Verify O(1) swapWith
        DirectVectorDataSet alt = new DirectVectorDataSet(2, 0, 0);
        alt.swapWith(out);
        assertEquals(8, alt.count());
        assertEquals(0, out.count());
        assertEquals(1.5f * 4.0f + 2.5f, alt.floating(0, 0), 1e-5f);
    }

    @Test
    void photonFullTimelineBurstsForcefieldCollisionAndSubEmittersWork() {
        PhotonFxModel.PhysicsExtras parentExtras = new PhotonFxModel.PhysicsExtras(
                1024,
                List.of(new PhotonFxModel.BurstSpec(0.0f, 6, 1, 0.1f, 1.0f)),
                new PhotonFxModel.ShapeSpec("sphere", 0.2f, 25f, 0.5f, 0.5f, 0.5f, true),
                1.0f, 0.05f, 0.2f, 2.0f,
                new float[]{0f, 0f, 0f}, new float[]{0f, 0f, 0f},
                1.0f, 0.5f, 0f, 90f, 1f, 0.2f, 0.1f, 0f, true,
                new PhotonFxModel.CollisionSpec(true, 0.8f, 0.1f, 0.0f, false),
                List.of(new PhotonFxModel.ForceFieldSpec("vortex", 3.0f, 0f, 0f, 0f, 10f)),
                List.of(new PhotonFxModel.SubEmitterSpec("COLLISION", "sparks", 2, 1.0f)),
                4.0f, 10, 0.1f, 0.1f, true, true, 0.6f);

        PhotonFxModel.TransformSpec ident = new PhotonFxModel.TransformSpec("root", null, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1);
        PhotonFxModel.FxObjectSpec main = new PhotonFxModel.FxObjectSpec(
                "main", PhotonFxModel.ObjectType.PARTICLE, ident, PhotonFxModel.RenderMode.BILLBOARD, false,
                2.0f, false, 0.0f, 0.0f, 6, 1.0f, 0.0f, 0.4f, 1f, 1f, 1f, 1f,
                null, BlendMode.ADDITIVE, true, false, false, null, null, parentExtras);
        PhotonFxModel.FxObjectSpec sparks = new PhotonFxModel.FxObjectSpec(
                "sparks", PhotonFxModel.ObjectType.PARTICLE, ident, PhotonFxModel.RenderMode.BILLBOARD, false,
                2.0f, false, 0.0f, 0.0f, 0, 0.5f, 1.0f, 0.15f, 1f, 0.8f, 0.2f, 1f,
                null, BlendMode.ADDITIVE, true, false, false, null, null, PhotonFxModel.PhysicsExtras.DEFAULT);
        PhotonFxModel.FxObjectSpec beam = new PhotonFxModel.FxObjectSpec(
                "laser", PhotonFxModel.ObjectType.BEAM, ident, PhotonFxModel.RenderMode.BEAM, true,
                2.0f, true, 0.0f, 0.0f, 1, 1.0f, 0.0f, 0.2f, 0.3f, 0.8f, 1f, 1f,
                null, BlendMode.ADDITIVE, true, false, false, null, null, PhotonFxModel.PhysicsExtras.DEFAULT);

        PhotonFxModel model = new PhotonFxModel("test:photon_full", List.of(main, sparks, beam));
        assertTrue(main.material().hdr());
        assertTrue(main.material().softParticles());

        PhotonFxRuntime rt = new PhotonFxRuntime(model, 99L);
        assertTrue(rt.requiresOwnerThread());
        McResourceBridge floorBridge = new DirectoryResourceBridge() {
            @Override public boolean isSolidBlock(Object level, double x, double y, double z) { return y <= 0.0; }
        };
        EffectHandle handle = new EffectHandle(new Vec3(0.0, 0.4, 0.0));
        Object dummyLevel = new Object();
        for (int t = 0; t < 15; t++) {
            assertTrue(rt.tick(handle.snapshot(), 0.05f, false, dummyLevel, floorBridge));
        }
        assertEquals(6, rt.objects().get(0).particles.size(), "Burst must spawn 6 main particles");
        assertTrue(rt.objects().get(1).particles.size() > 0, "Block collision must trigger COLLISION sub-emitter sparks");
        assertEquals(1, rt.objects().get(2).particles.size(), "Beam must maintain resident segmented geometry");
        assertTrue(rt.objects().get(2).particles.get(0).trailCount >= 3, "Beam must populate trail segment points");
    }

    @Test
    void nativeOpenGlParityModelsNaniteSplatGroomRvtCustomShaderAudioAndStatelessWork() {
        String parityJson = """
                {
                  "format_version": 1,
                  "materials": {
                    "custom_hdr": {
                      "blend": "additive",
                      "hdr": true,
                      "emissive_scale": 2.2,
                      "soft_particles": true,
                      "soft_fade_distance": 1.25,
                      "nanite": true,
                      "rvt": true,
                      "sub_uv": [2, 2],
                      "custom_glsl": "fragColor = texture(uTex, vUv) * vColor * lfx_soft_particle_alpha();"
                    }
                  },
                  "niagara": {
                    "emitters": [
                      {
                        "name": "stateless_vortex",
                        "material": "custom_hdr",
                        "stateless": {
                          "spawn_rate": 0.0,
                          "burst_count": 10,
                          "lifetime": 1.0,
                          "speed": 2.0,
                          "vortex_strength": 4.0
                        }
                      }
                    ]
                  },
                  "audio": [
                    { "sound": "lumenfx:sfx/burst", "volume": 0.8, "pitch": 1.1, "spatialized": true }
                  ],
                  "gaussian_splats": {
                    "splats": [
                      { "position": [0.0, 1.0, 0.0], "scale": [0.2, 0.1, 0.2], "rotation": [0, 0, 0, 1], "color": [1, 0.5, 0.2, 0.9] }
                    ]
                  },
                  "grooms": [
                    {
                      "gravity": 9.81,
                      "wind_strength": 0.4,
                      "strands": [
                        { "points": [[0,0,0], [0,-0.2,0.05], [0,-0.4,0.1]], "root_width": 0.03, "tip_width": 0.005, "color": [0.2, 0.1, 0.05] }
                      ]
                    }
                  ],
                  "volumes": [
                    { "position": [0, 1, 0], "size": [3, 2, 3], "color": [0.5, 0.7, 1.0], "density": 0.8 }
                  ],
                  "decals": [
                    { "position": [0, 0, 0], "size": [2, 1, 2], "color": [1, 0.2, 0.1, 0.9], "texture": "lumenfx:textures/decal.png" }
                  ],
                  "lights": [
                    { "position": [0, 2, 0], "radius": 8.0, "color": [1, 0.8, 0.4], "intensity": 2.0, "type": "spot", "direction": [0, -1, 0] }
                  ]
                }
                """;
        EffectModel model = ProductionEffectParser.parse("lumenfx:full_parity", parityJson, null);
        MaterialModel mat = model.materials().get("custom_hdr");
        assertNotNull(mat);
        assertTrue(mat.hdr());
        assertTrue(mat.softParticles());
        assertEquals(1.25f, mat.softFadeDistance(), 1e-5f);
        assertEquals(2.2f, mat.emissiveScale(), 1e-5f);
        assertTrue(mat.nanite());
        assertTrue(mat.rvt());
        assertTrue(mat.hasCustomShader());
        assertEquals(2, mat.subUvRows());
        assertEquals(2, mat.subUvCols());
        assertEquals(1, model.audioCues().size());
        assertEquals(1, model.gaussianSplats().size());
        assertEquals(1, model.gaussianSplats().get(0).count());
        assertEquals(1, model.grooms().size());
        assertEquals(1, model.grooms().get(0).strands().size());
        assertEquals(1, model.volumes().size());
        assertEquals(1, model.decals().size());
        assertEquals(1, model.lights().size());
        assertTrue(model.lights().get(0).spot());
        assertEquals(1, model.rvtMaterials().size());

        // Stateless Niagara emitter burst ticks cleanly
        EffectRuntimeInstance inst = new EffectRuntimeInstance(model, new EffectHandle(Vec3.ZERO), null, 55L, 1);
        assertTrue(inst.tick(0.05f, 1, null));
        assertEquals(10, inst.niagaraRuntime().emitters().get(0).particleCount());

        // Custom GLSL fragment wrapper produces valid #version 150 core program (strict OpenGL 3.2+)
        String wrapped = DirectGlCustomMaterialCompiler.wrapFragmentSource(mat.customGlslFragment());
        assertTrue(wrapped.startsWith("#version 150 core"));
        assertTrue(wrapped.contains("void main()"));
        assertTrue(wrapped.contains("lfx_soft_particle_alpha"));

        // External #version 330 core shader adapts cleanly down to #version 150 core with explicit bindings,
        // including multiple same-line declarations, leading comments/BOM, qualifiers, arrays, and UBO preservation
        Map<String, Integer> extraAttribs = new LinkedHashMap<>();
        Map<String, Integer> extraFrags = new LinkedHashMap<>();
        String adaptedVert = DirectGlCustomMaterialCompiler.downgradeGlsl330To150(
                """
                \uFEFF// SPDX-License-Identifier: MIT
                /* #version 330 core inside comment; layout(location = 9) in vec4 dead; */
                #version 330 core
                layout(location = 0) in vec3 aPos; layout(location = 6) in vec2 aUv1;
                layout(
                    location = 3
                ) flat in highp int aFlag;
                layout(std140) uniform EffectFrame { mat4 uViewProj; };
                void main() { gl_Position = uViewProj * vec4(aPos, 1.0); }
                """,
                true, extraAttribs, extraFrags);
        assertTrue(adaptedVert.contains("#version 150 core"));
        assertTrue(adaptedVert.contains("/* #version 330 core inside comment; layout(location = 9) in vec4 dead; */"));
        String adaptedVertCode = adaptedVert.substring(adaptedVert.indexOf("#version 150 core"));
        assertFalse(adaptedVertCode.contains("layout(location"));
        assertTrue(adaptedVertCode.contains("layout(std140) uniform EffectFrame"));
        assertTrue(adaptedVertCode.contains("flat in highp int aFlag;"));
        assertEquals(0, extraAttribs.get("aPos"));
        assertEquals(6, extraAttribs.get("aUv1"));
        assertEquals(3, extraAttribs.get("aFlag"));
        assertFalse(extraAttribs.containsKey("dead"));

        String adaptedFrag = DirectGlCustomMaterialCompiler.downgradeGlsl330To150(
                "// License\n#version 330 core\nlayout(location = 0) out vec4 customOut; layout(location = 1) out vec4 auxOut;\nvoid main(){customOut=vec4(1.0);auxOut=vec4(0.0);}",
                false, extraAttribs, extraFrags);
        assertTrue(adaptedFrag.contains("#version 150 core"));
        assertFalse(adaptedFrag.contains("layout(location"));
        assertEquals(0, extraFrags.get("customOut"));
        assertEquals(1, extraFrags.get("auxOut"));

        // Complete shader with leading license comments & BOM must NOT get wrapped with a duplicate #version
        String commentedFullFrag = "\uFEFF// SPDX-License-Identifier: MIT\n/* Multi-line header */\n#version 150 core\nin vec2 uv;\nout vec4 outputColor;\nvoid main(){outputColor=vec4(uv,0,1);}\n";
        String wrappedFull = DirectGlCustomMaterialCompiler.wrapFragmentSource(commentedFullFrag);
        assertEquals(commentedFullFrag.substring(1), wrappedFull);
        assertEquals(1, wrappedFull.split("#version", -1).length - 1);

        // Snippet with comment containing "#version 150 core" but no real #version directive MUST still wrap cleanly
        String commentedSnippet = "// example: #version 150 core\nvoid main () { fragColor = vColor; }";
        String wrappedSnippet = DirectGlCustomMaterialCompiler.wrapFragmentSource(commentedSnippet);
        assertTrue(wrappedSnippet.startsWith("#version 150 core"));
        assertEquals(1, wrappedSnippet.split("void main", -1).length - 1);

        // Conditional compilation (#if/#else/#elif/#ifdef/#undef) and macro/const-expr locations
        Map<String, Integer> condAttribs = new LinkedHashMap<>();
        Map<String, Integer> condFrags = new LinkedHashMap<>();
        String condVert = DirectGlCustomMaterialCompiler.downgradeGlsl330To150(
                """
                #version 330 core
                #define STANDARD_UV 1
                #define BASE_SLOT 4
                #define UV_SLOT (BASE_SLOT + 2)
                #define DECL_LOC(n) layout(location = n)
                #define SELF_REF SELF_REF
                DECL_LOC(0) in vec3 aPos;
                #if STANDARD_UV && defined(UV_SLOT)
                layout(location = UV_SLOT) in vec2 aUv1;
                #else
                layout(location = 7) in vec2 aUv1;
                #endif
                #ifdef UNDEFINED_MACRO
                layout(location = 9) in vec4 aUnused;
                #elif __VERSION__ >= 330
                const int EXTRA_SLOT = 5;
                layout(location = EXTRA_SLOT) in vec3 aExtra;
                #endif
                void main() { gl_Position = vec4(aPos + aExtra, 1.0); }
                """,
                true, condAttribs, condFrags);
        assertTrue(condVert.startsWith("#version 150 core"));
        assertFalse(condVert.contains("layout("));
        assertEquals(0, condAttribs.get("aPos"));
        assertEquals(6, condAttribs.get("aUv1"), "Active #if branch must bind aUv1=6 and ignore inactive #else branch");
        assertEquals(5, condAttribs.get("aExtra"), "Active #elif branch const int location must bind aExtra=5");
        assertFalse(condAttribs.containsKey("aUnused"), "Inactive #ifdef branch must never record bindings");

        String condFrag = DirectGlCustomMaterialCompiler.downgradeGlsl330To150(
                """
                #version 330 core
                #define PRIMARY_OUTPUT 1
                #if PRIMARY_OUTPUT
                layout(location = 0) out vec4 outputColor;
                #else
                layout(location = 1) out vec4 outputColor;
                #endif
                void main() { outputColor = vec4(1.0); }
                """,
                false, condAttribs, condFrags);
        assertTrue(condFrag.startsWith("#version 150 core"));
        assertFalse(condFrag.contains("layout("));
        assertEquals(0, condFrags.get("outputColor"), "Active #if branch must bind outputColor=0");

        // C99/GLSL 3.30 macro expansion: callable aliases, ## token pasting, two-level XCAT,
        // pasted function-macro names, placemarker ##, object-like ##, mutual recursion,
        // and reversed qualifier / type-side array syntax.
        Map<String, Integer> macroAttribs = new LinkedHashMap<>();
        Map<String, Integer> macroFrags = new LinkedHashMap<>();
        String macroVert = DirectGlCustomMaterialCompiler.downgradeGlsl330To150(
                """
                #version 330 core
                #define ID(x) x
                #define UV_BIND ID
                #define CAT(a, b) a ## b
                #define XCAT(a, b) CAT(a, b)
                #define SLOT_UV 6
                #define SLOT_NUM 3
                #define SLOT_3 3
                #define MAKE_SLOT SLOT_ ## UV
                #define EMPTY()
                #define DEFER(f) f EMPTY()
                #define EVAL(x) x
                #define GET_LOC(n) n
                #define CYC_A CYC_B
                #define CYC_B CYC_A
                const float CYC_A = 0.5;
                layout(location = UV_BIND(0)) in vec3 aPos;
                layout(location = CAT(SLOT_, UV)) in vec2 aUv1;
                layout(location = XCAT(SLOT_, SLOT_NUM)) in vec4 aColor;
                layout(location = CAT(GET_, LOC)(4)) in vec4 aCustom;
                layout(location = EVAL(DEFER(ID)(5))) in vec2 aNext;
                in layout(location = CAT(, 2)) vec2 aUv0;
                #if UV_BIND(CAT(SLOT_, UV)) == MAKE_SLOT
                layout(location = 1) in vec2 aActive;
                #else
                layout(location = 9) in vec2 aActive;
                #endif
                void main() { gl_Position = vec4(aPos * CYC_A, 1.0); }
                """,
                true, macroAttribs, macroFrags);
        assertTrue(macroVert.startsWith("#version 150 core"));
        assertFalse(macroVert.contains("layout("));
        assertFalse(macroVert.contains("##"));
        assertTrue(macroVert.contains("aPos * CYC_A"), "Mutual recursion must stop at CYC_A without infinite loop");
        assertEquals(0, macroAttribs.get("aPos"));
        assertEquals(6, macroAttribs.get("aUv1"));
        assertEquals(3, macroAttribs.get("aColor"));
        assertEquals(4, macroAttribs.get("aCustom"));
        assertEquals(5, macroAttribs.get("aNext"));
        assertEquals(2, macroAttribs.get("aUv0"));
        assertEquals(1, macroAttribs.get("aActive"));

        String macroFrag = DirectGlCustomMaterialCompiler.downgradeGlsl330To150(
                """
                #version 330 core
                #define CAT(a, b) a ## b
                #define OUT_0 0
                layout(location = CAT(OUT_, 0)) out vec4[2] fragArr;
                void main() { fragArr[0] = vec4(1.0); fragArr[1] = vec4(0.5); }
                """,
                false, macroAttribs, macroFrags);
        assertTrue(macroFrag.startsWith("#version 150 core"));
        assertFalse(macroFrag.contains("layout("));
        assertEquals(0, macroFrags.get("fragArr"));

        // #line remapping for __LINE__ & __FILE__ (including macro-expanded #line, file preservation,
        // leading comments before #version, inactive #line ignored, and GL_ARB_explicit_attrib_location).
        Map<String, Integer> lineAttribs = new LinkedHashMap<>();
        Map<String, Integer> lineFrags = new LinkedHashMap<>();
        String lineVert = DirectGlCustomMaterialCompiler.downgradeGlsl330To150(
                """
                /* line 1
                   line 2
                   line 3 */
                #version 330 core
                #if __LINE__ == 5 && defined(GL_ARB_explicit_attrib_location)
                layout(location = 0) in vec3 aPos;
                #else
                layout(location = 9) in vec3 aPos;
                #endif
                #if 0
                #line 999 99
                #endif
                #define TARGET_LINE 100
                #define TARGET_FILE 7
                #line TARGET_LINE TARGET_FILE
                #if __LINE__ == 100 && __FILE__ == 7
                layout(location = 6) in vec2 aUv1;
                #else
                layout(location = 7) in vec2 aUv1;
                #endif
                #line 200
                #if __LINE__ == 200 && __FILE__ == 7
                layout(location = 2) in vec4 aColor;
                #else
                layout(location = 8) in vec4 aColor;
                #endif
                #line 4 5
                layout(location = __LINE__) in vec4 aCustom;
                layout(location = __FILE__) in vec2 aNext;
                void main() { gl_Position = vec4(aPos, 1.0); }
                """,
                true, lineAttribs, lineFrags);
        assertTrue(lineVert.contains("#version 150 core"));
        assertFalse(lineVert.contains("TARGET_LINE"), "Macro-expanded #line must emit numeric literal");
        assertTrue(lineVert.contains("#line 100 7"));
        assertEquals(0, lineAttribs.get("aPos"));
        assertEquals(6, lineAttribs.get("aUv1"));
        assertEquals(2, lineAttribs.get("aColor"));
        assertEquals(4, lineAttribs.get("aCustom"));
        assertEquals(5, lineAttribs.get("aNext"));

        // Fix7: #version with trailing multi-line block comment, inline block comments,
        // backslash continuations preserving physical line numbers across directives/code/comments,
        // multi-line block comments inside #define/#if, and per-token __LINE__ in multi-line macro calls.
        Map<String, Integer> fix7Attribs = new LinkedHashMap<>();
        Map<String, Integer> fix7Frags = new LinkedHashMap<>();
        String verCommentVert = DirectGlCustomMaterialCompiler.downgradeGlsl330To150(
                """
                #version /* inline */ 330 /* inline */ core /* first line
                second line */
                #if __LINE__ == 3
                layout(location=0) in vec3 aPos;
                layout(location=6) in vec2 aUv1;
                #else
                layout(location=7) in vec2 aUv1;
                #endif
                out vec2 uv;
                void main(){uv=aUv1;gl_Position=vec4(aPos,1.0);}
                """,
                true, fix7Attribs, fix7Frags);
        assertTrue(verCommentVert.startsWith("#version 150 core\n"));
        assertFalse(verCommentVert.contains("second line"), "Trailing block comment on #version must not leak");
        assertFalse(verCommentVert.contains("*/"), "Closing comment delimiter must not leak into GLSL 150");
        assertEquals(0, fix7Attribs.get("aPos"));
        assertEquals(6, fix7Attribs.get("aUv1"));

        Map<String, Integer> contAttribs = new LinkedHashMap<>();
        String contVert = DirectGlCustomMaterialCompiler.downgradeGlsl330To150(
                "#version 330 core\n"
                        + "#extension GL_ARB_shading_language_420pack : require\n"
                        + "layout(location=0) in vec3 aPos;\n"
                        + "#line 100 7\n"
                        + "#define SHIFT \\\n"
                        + "1\n"
                        + "#if __LINE__ == 102 && SHIFT == 1\n"
                        + "layout(location=6) in vec2 aUv1;\n"
                        + "#else\n"
                        + "layout(location=7) in vec2 aUv1;\n"
                        + "#endif\n"
                        + "#line 200 7\n"
                        + "#define BLOCK_DEF /* c1\n"
                        + "c2 */ 5\n"
                        + "#if __LINE__ == 202 /* c3\n"
                        + "c4 */ && __LINE__ == 203 && BLOCK_DEF == 5\n"
                        + "layout(location=2) in vec4 aColor;\n"
                        + "#else\n"
                        + "layout(location=8) in vec4 aColor;\n"
                        + "#endif\n"
                        + "#line 300 7\n"
                        + "// comment \\\n"
                        + "#error must_stay_commented\n"
                        + "#if __LINE__ == 302\n"
                        + "layout(location=3) in vec4 aParticle;\n"
                        + "#else\n"
                        + "layout(location=9) in vec4 aParticle;\n"
                        + "#endif\n"
                        + "#line 400 7\n"
                        + "#define LINE_GAP(FIRST,LAST) ((LAST) - (FIRST))\n"
                        + "#define HERE __LINE__\n"
                        + "#define BODY_LINE(A,B) __LINE__\n"
                        + "float marker[LINE_GAP(\n"
                        + "    __LINE__,\n"
                        + "    __LINE__\n"
                        + ")];\n"
                        + "float marker2[LINE_GAP(\n"
                        + "    HERE,\n"
                        + "    HERE\n"
                        + ")];\n"
                        + "float marker3[BODY_LINE(\n"
                        + "    1,\n"
                        + "    2\n"
                        + ") == 414 ? 1 : 0];\n"
                        + "void main(){gl_Position=vec4(aPos,1.0);}\n",
                true, contAttribs, fix7Frags);
        assertEquals(0, contAttribs.get("aPos"));
        assertEquals(6, contAttribs.get("aUv1"), "Backslash continuation must preserve physical line count");
        assertEquals(2, contAttribs.get("aColor"), "Multi-line block comment in #define and #if must preserve line count");
        assertEquals(3, contAttribs.get("aParticle"), "Spliced // comment must swallow continued line and count 2 lines");
        assertFalse(contVert.contains("must_stay_commented"));
        assertTrue(contVert.contains("float marker[((405) - (404))];"), "Actual: " + contVert);
        assertTrue(contVert.contains("float marker2[((409) - (408))];"), "Actual: " + contVert);
        assertTrue(contVert.contains("float marker3[414 == 414 ? 1 : 0];"), "Actual: " + contVert);

        // Fix8: GLSL extension predefined macros (#extension : require / : enable / : disable,
        // pre-#extension #ifdef guards, unknown extension : enable stays undefined, API-only ext stays undefined).
        Map<String, Integer> extAttribs = new LinkedHashMap<>();
        Map<String, Integer> extFrags = new LinkedHashMap<>();
        String extVert = DirectGlCustomMaterialCompiler.downgradeGlsl330To150(
                """
                #version 330 core
                #ifdef GL_ARB_shading_language_420pack
                #extension GL_ARB_shading_language_420pack : require
                #endif
                #extension GL_FAKE_NONEXISTENT_XYZ : enable
                #extension GL_ARB_shading_language_420pack : disable
                layout(location=0) in vec3 aPos;
                #if defined(GL_ARB_shading_language_420pack) && (GL_ARB_shading_language_420pack == 1) && !defined(GL_FAKE_NONEXISTENT_XYZ) && !defined(GL_ARB_vertex_array_object)
                layout(location=6) in vec2 aUv1;
                #else
                layout(location=7) in vec2 aUv1;
                #endif
                out vec2 uv;
                void main(){uv=aUv1;gl_Position=vec4(aPos,1.0);}
                """,
                true, extAttribs, extFrags);
        assertTrue(extVert.startsWith("#version 150 core\n"));
        assertTrue(extVert.contains("#extension GL_ARB_shading_language_420pack : require"));
        assertEquals(0, extAttribs.get("aPos"));
        assertEquals(6, extAttribs.get("aUv1"), "GL_ARB_shading_language_420pack guard must select slot 6");

        // Fix8 sweep: macro-generated main detection, inactive #if 0 main ignored, multi-name
        // layout(location) declarations, and fragment layout(location, index) dual-source lowering.
        String macroFragNoVer = "#define ENTRY main\nvoid ENTRY(){fragColor=vec4(0.3,0.5,0.7,1.0);}\n";
        String wrappedMacroFrag = DirectGlCustomMaterialCompiler.wrapFragmentSource(macroFragNoVer);
        assertTrue(wrappedMacroFrag.startsWith("#version 150 core"));
        assertFalse(wrappedMacroFrag.contains("void main() {"), "Macro-generated main must not be wrapped in another main");

        String inactiveMainSnippet = "#if 0\nvoid main(){}\n#endif\nfragColor=vec4(0.3,0.5,0.7,1.0);\n";
        String wrappedInactiveSnippet = DirectGlCustomMaterialCompiler.wrapFragmentSource(inactiveMainSnippet);
        assertTrue(wrappedInactiveSnippet.contains("void main() {"), "Inactive #if 0 main must still wrap snippet in main");

        Map<String, Integer> multiAttribs = new LinkedHashMap<>();
        Map<String, Integer> multiFrags = new LinkedHashMap<>();
        Map<String, Integer> multiFragIndices = new LinkedHashMap<>();
        String multiVert = DirectGlCustomMaterialCompiler.downgradeGlsl330To150(
                """
                #version 330 core
                layout(location=0) in vec3 aPos;
                layout(location=6) in vec2 aUv1, otherUV;
                out vec2 auditUV;
                void main(){auditUV=aUv1+otherUV;gl_Position=vec4(aPos,1.0);}
                """,
                true, multiAttribs, multiFrags, multiFragIndices);
        assertFalse(multiVert.contains("layout("));
        assertEquals(0, multiAttribs.get("aPos"));
        assertEquals(6, multiAttribs.get("aUv1"));
        assertEquals(6, multiAttribs.get("otherUV"), "All names in comma-separated declaration must inherit location");

        String dualFrag = DirectGlCustomMaterialCompiler.downgradeGlsl330To150(
                """
                #version 330 core
                in vec2 auditUV;
                layout(location=0,index=0) out vec4 outputColor;
                layout(location=0,index=1) out vec4 outputFactor;
                void main(){
                    outputColor=vec4(auditUV,0.25,1.0);
                    outputFactor=vec4(1.0-auditUV,0.5,1.0);
                }
                """,
                false, multiAttribs, multiFrags, multiFragIndices);
        assertFalse(dualFrag.contains("layout("), "Both location and index must be stripped in GLSL 150: " + dualFrag);
        assertFalse(dualFrag.contains("index="));
        assertEquals(0, multiFrags.get("outputColor"));
        assertEquals(0, multiFrags.get("outputFactor"));
        assertEquals(0, multiFragIndices.get("outputColor"));
        assertEquals(1, multiFragIndices.get("outputFactor"));

        // Fix9: versionless fragment #extension hoisting before generated preamble declarations,
        // preserving #define placement after preamble so user macros cannot collide with preamble locals.
        String versionlessExt = """
                #extension GL_ARB_shading_language_420pack : require
                #define d 2.0
                #define ENTRY main
                void ENTRY(){fragColor=vec4(0.3,0.5,0.7,1.0);}
                """;
        String wrappedExt = DirectGlCustomMaterialCompiler.wrapFragmentSource(versionlessExt);
        assertTrue(wrappedExt.startsWith("#version 150 core\n#extension GL_ARB_shading_language_420pack : require\n"),
                "Leading #extension must appear immediately after #version before preamble declarations: " + wrappedExt);
        assertTrue(wrappedExt.indexOf("#extension GL_ARB_shading_language_420pack : require")
                        < wrappedExt.indexOf("in vec2 vUv;"),
                "Extension must precede first non-preprocessor token");
        assertTrue(wrappedExt.indexOf("float lfx_linear_depth(float d)")
                        < wrappedExt.indexOf("#define d 2.0"),
                "User #define must stay after preamble declarations");
        assertFalse(wrappedExt.contains("void main() {"), "Macro-generated main must not be double-wrapped");
        assertEquals(wrappedExt.indexOf("#extension"), wrappedExt.lastIndexOf("#extension"),
                "Hoisted #extension must not remain duplicated after preamble");

        // Fix9: inactive #if 0 #extension is not hoisted; conditional active #extension is hoisted;
        // versionless wrappers emit #line 1 0 so user __LINE__ guards see original line numbers.
        String condExt = """
                #define WANT_420 1
                #if WANT_420
                #extension GL_ARB_shading_language_420pack : require
                #endif
                #if 0
                #extension GL_AUDIT_missing : require
                #endif
                fragColor = vec4(0.3, 0.5, 0.7, 1.0);
                """;
        String wrappedCondExt = DirectGlCustomMaterialCompiler.wrapFragmentSource(condExt);
        assertTrue(wrappedCondExt.startsWith("#version 150 core\n#extension GL_ARB_shading_language_420pack : require\n"));
        assertFalse(wrappedCondExt.contains("GL_AUDIT_missing"), "Inactive #if 0 extension must not be hoisted");
        assertTrue(wrappedCondExt.contains("void main() {\n#line 1 0\n"), "Versionless snippet must restore #line 1 0");

        String lineGuardedEntry = "#if __LINE__ == 1\nvoid main(){fragColor=vec4(0.2,0.6,0.8,1.0);}\n#endif\n";
        String wrappedLineEntry = DirectGlCustomMaterialCompiler.wrapFragmentSource(lineGuardedEntry);
        assertTrue(wrappedLineEntry.contains("#line 1 0\n#if __LINE__ == 1\n"), wrappedLineEntry);
        assertFalse(wrappedLineEntry.contains("void main() {"), "Line-1 guarded entry must not be double-wrapped");

        // Fix9: enhanced layouts scalar constructor constant expression `const int SHADER_UV = int(3u * 2u);`
        Map<String, Integer> enhAttribs = new LinkedHashMap<>();
        Map<String, Integer> enhFrags = new LinkedHashMap<>();
        String enhVert = DirectGlCustomMaterialCompiler.downgradeGlsl330To150(
                """
                #version 330 core
                #extension GL_ARB_enhanced_layouts : require
                const int SHADER_UV = int(3u * 2u);
                layout(location=SHADER_UV) in vec2 aUv1;
                layout(location=0) in vec3 aPos;
                out vec2 auditUV;
                void main(){auditUV=aUv1;gl_Position=vec4(aPos,1.0);}
                """,
                true, enhAttribs, enhFrags);
        assertFalse(enhVert.contains("layout("), "Enhanced layout constructor constant must be lowered: " + enhVert);
        assertEquals(6, enhAttribs.get("aUv1"));

        // Fix9: vertex-only custom materials (inline vertex_glsl and resource vertex_material) report hasCustomShader()
        JsonObject inlineVertJson = new JsonObject();
        inlineVertJson.addProperty("fragment_shader", "lumenfx:core/color");
        inlineVertJson.addProperty("vertex_glsl", "#version 330 core\nlayout(location=0) in vec3 aPos;\nvoid main(){gl_Position=vec4(aPos,1.0);}\n");
        assertTrue(MaterialModel.fromJson("inline_v", inlineVertJson).hasCustomShader());

        JsonObject resVertJson = new JsonObject();
        resVertJson.addProperty("fragment_shader", "lumenfx:core/color");
        resVertJson.addProperty("vertex_material", "audit:replacement");
        assertTrue(MaterialModel.fromJson("res_v", resVertJson).hasCustomShader());

        JsonObject builtinOnlyJson = new JsonObject();
        builtinOnlyJson.addProperty("fragment_shader", "lumenfx:core/color");
        assertFalse(MaterialModel.fromJson("builtin_only", builtinOnlyJson).hasCustomShader());

        // Fix10: #moj_import version promotion re-evaluates earlier __VERSION__ conditionals,
        // preserves comments inside #version directive, and switches #line offset to GLSL 3.30 mode.
        Map<String, byte[]> importFiles = Map.of(
                "audit:shaders/tone.glsl", "vec4 tone(vec2 uv){return vec4(uv,0.25,1.0);}\n".getBytes(StandardCharsets.UTF_8),
                "audit:shaders/upgrade.glsl", "#version 330 core\nvec4 helper(vec2 uv){return vec4(uv,0.25,1.0);}\n".getBytes(StandardCharsets.UTF_8),
                "audit:shaders/helper150.glsl", "#version 150 core\nvec4 h150(vec2 uv){return vec4(uv,0.25,1.0);}\n".getBytes(StandardCharsets.UTF_8)
        );
        McResourceBridge memBridge = new DirectoryResourceBridge() {
            @Override public byte[] readResource(String id) { return importFiles.get(id); }
        };
        String promotedCond = DirectGlShaderImports.expand(
                """
                #version 150 core
                #if __VERSION__ >= 330
                #moj_import "tone.glsl"
                #endif
                #moj_import "upgrade.glsl"
                void main(){fragColor=tone(vUv);}
                """,
                "audit:shaders/probe.fsh", memBridge, true);
        assertTrue(promotedCond.startsWith("#version 330 core\n"), promotedCond);
        assertTrue(promotedCond.contains("vec4 tone(vec2 uv)"), "Earlier __VERSION__ >= 330 import must expand after promotion: " + promotedCond);
        assertTrue(promotedCond.contains("vec4 helper(vec2 uv)"), promotedCond);

        String commentVersion = DirectGlShaderImports.expand(
                """
                #/* 150 */version /* format 150 */ 150 /* tail 150 */ core
                #moj_import "upgrade.glsl"
                void main(){fragColor=helper(vUv);}
                """,
                "audit:shaders/probe.fsh", memBridge, true);
        assertTrue(commentVersion.startsWith("#/* 150 */version /* format 150 */ 330 /* tail 150 */ core\n"),
                "Version promotion must only replace the actual version number token: " + commentVersion);

        String upgradedLine = DirectGlShaderImports.expand(
                """
                #version 150 core
                #line 99 7
                #moj_import "upgrade.glsl"
                #if __LINE__ == 100 && __FILE__ == 7
                void main(){fragColor=helper(vUv);}
                #endif
                """,
                "audit:shaders/probe.fsh", memBridge, true);
        assertTrue(upgradedLine.startsWith("#version 330 core\n"), upgradedLine);
        assertTrue(upgradedLine.contains("#line 1 1\n"), "Promoted 330 shader must use GLSL 3.30 #line 1 1: " + upgradedLine);
        assertTrue(upgradedLine.contains("#line 100 7\n"), "Promoted 330 shader must restore #line 100 7 on NVIDIA: " + upgradedLine);
        assertFalse(upgradedLine.contains("#line 0 1\n"), upgradedLine);

        String pure150NvidiaLine = DirectGlShaderImports.expand(
                """
                #version 150 core
                #line 99 7
                #moj_import "helper150.glsl"
                void main(){fragColor=h150(vUv);}
                """,
                "audit:shaders/probe.fsh", memBridge, true);
        assertTrue(pure150NvidiaLine.startsWith("#version 150 core\n"), pure150NvidiaLine);
        assertTrue(pure150NvidiaLine.contains("#line 0 1\n"), "Pure 150 on NVIDIA must use #line 0 1: " + pure150NvidiaLine);
        assertTrue(pure150NvidiaLine.contains("#line 100 7\n"), "Pure 150 on NVIDIA after #line 99 7 (next=100, post=101) must emit #line 100 7: " + pure150NvidiaLine);

        // Fix11: missing import inside a provisional <330 branch must not abort version discovery
        // before a later #version 330 import renders that branch inactive, while still failing for
        // any import that remains active under the converged effective version.
        String discardedProvisionalMissing = DirectGlShaderImports.expand(
                """
                #version 150 core
                #if __VERSION__ < 330
                #moj_import "preupgrade.glsl"
                #endif
                #moj_import "upgrade.glsl"
                void main(){fragColor=helper(vUv);}
                """,
                "audit:shaders/probe.fsh", memBridge, true);
        assertTrue(discardedProvisionalMissing.startsWith("#version 330 core\n"), discardedProvisionalMissing);
        assertTrue(discardedProvisionalMissing.contains("vec4 helper(vec2 uv)"), discardedProvisionalMissing);

        IllegalArgumentException activeMissingEx = assertThrows(IllegalArgumentException.class, () ->
                DirectGlShaderImports.expand(
                        """
                        #version 150 core
                        #if __VERSION__ >= 330
                        #moj_import "still_missing.glsl"
                        #endif
                        #moj_import "upgrade.glsl"
                        void main(){fragColor=helper(vUv);}
                        """,
                        "audit:shaders/probe.fsh", memBridge, true));
        assertTrue(activeMissingEx.getMessage().contains("audit:shaders/still_missing.glsl"), activeMissingEx.getMessage());

        IllegalArgumentException unpromotedMissingEx = assertThrows(IllegalArgumentException.class, () ->
                DirectGlShaderImports.expand(
                        """
                        #version 150 core
                        #moj_import "never_exists.glsl"
                        void main(){fragColor=vec4(1.0);}
                        """,
                        "audit:shaders/probe.fsh", memBridge, true));
        assertTrue(unpromotedMissingEx.getMessage().contains("audit:shaders/never_exists.glsl"), unpromotedMissingEx.getMessage());

        // Fix10: GL_ARB_enhanced_layouts float cast, named bool, integer built-in min/max/clamp,
        // and constant vector member/subscript expressions in GL 3.2 lowering.
        Map<String, Integer> fix10Attribs = new LinkedHashMap<>();
        Map<String, Integer> fix10Frags = new LinkedHashMap<>();
        String fix10ConstVert = DirectGlCustomMaterialCompiler.downgradeGlsl330To150(
                """
                #version 330 core
                #extension GL_ARB_enhanced_layouts : require
                const bool USE_EIGHT = false;
                const float BASE_F = 6.0;
                const ivec2 PAIR = ivec2(6, 7);
                const int S_FLOAT = int(BASE_F);
                const int S_BOOL = USE_EIGHT ? 8 : 2;
                const int S_MIN = min(3, 9);
                const int S_VEC_X = PAIR.x;
                const int S_VEC_IDX = PAIR[1];
                const int S_CLAMP = clamp(12, 0, 5);
                layout(location = 0) in vec3 aPos;
                layout(location = S_FLOAT) in vec2 aUv1;
                layout(location = S_BOOL) in vec4 aColor;
                layout(location = S_MIN) in vec4 aParticle;
                layout(location = S_VEC_X) in vec2 aOther;
                layout(location = S_VEC_IDX) in vec2 aAlt;
                layout(location = S_CLAMP) in vec3 aNext;
                void main(){gl_Position=vec4(aPos,1.0);}
                """,
                true, fix10Attribs, fix10Frags);
        assertFalse(fix10ConstVert.contains("layout("), "Enhanced layout float/bool/builtin/vector constants must lower: " + fix10ConstVert);
        assertEquals(6, fix10Attribs.get("aUv1"));
        assertEquals(2, fix10Attribs.get("aColor"));
        assertEquals(3, fix10Attribs.get("aParticle"));
        assertEquals(6, fix10Attribs.get("aOther"));
        assertEquals(7, fix10Attribs.get("aAlt"));
        assertEquals(5, fix10Attribs.get("aNext"));

        // Nanite meshlet clustering produces bounded clusters
        StaticMeshModel cube = StaticMeshModel.unitTexturedCube("test:cube");
        NaniteClusterModel clusters = NaniteClusterModel.buildFromMesh(cube);
        assertTrue(clusters.clusters().size() >= 1);
        assertEquals(cube.indexCount(), clusters.clusters().get(0).indexCount());

        // RVT page allocation & LRU eviction across >64 virtual tiles
        DirectGlRuntimeVirtualTexture rvt = new DirectGlRuntimeVirtualTexture();
        for (int i = 0; i < 70; i++) {
            assertNotNull(rvt.requestPage(i % 64, i / 64, 0));
        }
        assertEquals(64, rvt.residentPageCount(), "Physical atlas holds at most 64 LRU resident pages");

        // Spatial audio PCM WAV parser decodes valid mono 16-bit WAV header cleanly
        ByteBuffer wav = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
        wav.putInt(0x46464952).putInt(40).putInt(0x45564157); // RIFF, size, WAVE
        wav.putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(44100).putInt(88200).putShort((short) 2).putShort((short) 16);
        wav.putInt(0x61746164).putInt(4).putShort((short) 1000).putShort((short) -1000);
        DirectSpatialAudioEngine.DecodedPcm pcm = DirectSpatialAudioEngine.parseWavPcm(wav.array());
        assertNotNull(pcm);
        assertEquals(1, pcm.channels());
        assertEquals(44100, pcm.sampleRate());
        assertEquals(16, pcm.bitsPerSample());
        assertEquals(4, pcm.pcm().length);
    }

    @Test
    void unitySpawnAppliesColorAndSizeOverLifeOnFirstTickWithoutWhiteFlash() {
        String sprayJson = """
                {
                  "format_version": 1,
                  "materials": {
                    "mat_0": {
                      "blend": "alpha",
                      "depth_test": true,
                      "depth_write": false,
                      "cull": false
                    }
                  },
                  "emitters": [
                    {
                      "name": "color_spray_check",
                      "renderer": "billboard",
                      "material": "mat_0",
                      "duration": 2.0,
                      "loop": true,
                      "max_particles": 64,
                      "rate": 40.0,
                      "lifetime": 1.0,
                      "width": 0.6,
                      "height": 0.6,
                      "color_over_life": {
                        "keys": [ [0.0, 1.0, 0.1, 0.9, 1.0], [1.0, 0.2, 0.9, 1.0, 0.0] ]
                      },
                      "size_over_life": {
                        "kind": "curve",
                        "keys": [ [0.0, 0.5, 0.0, 0.0], [1.0, 1.5, 0.0, 0.0] ]
                      }
                    }
                  ]
                }
                """;
        EffectModel model = ProductionEffectParser.parse("qa_release:color_spray_unit", sprayJson, null);
        EffectRuntimeInstance inst = new EffectRuntimeInstance(model, new EffectHandle(Vec3.ZERO), null, 42L, 1);
        assertTrue(inst.tick(0.05f, 1, null));
        List<ParticleInstance> particles = inst.unityEmitters().get(0).particles;
        assertFalse(particles.isEmpty(), "Emitter must spawn particles on tick 1");
        for (ParticleInstance p : particles) {
            assertEquals(1.0f, p.r, 1e-4f, "Spawned particle must immediately apply colorOverLife(0) red");
            assertEquals(0.1f, p.g, 1e-4f, "Spawned particle must never flash white on tick 1");
            assertEquals(0.9f, p.b, 1e-4f, "Spawned particle must immediately apply colorOverLife(0) blue");
            assertEquals(0.3f, p.width, 1e-4f, "Spawned particle must immediately apply sizeOverLife(0)");
        }
    }

    @Test
    void niagaraCmToBlockScaleRibbonConfigAndSortOrderParseAccurately() throws Exception {
        String niagaraJson = """
                {
                  "format_version": 1,
                  "emitters": [
                    {
                      "name": "ribbon_a",
                      "renderer": "ribbon",
                      "sort_order": 0,
                      "scale_size_with_owner": true,
                      "ribbon": {
                        "uv0": { "distribution": "distance", "tile_length": 2.0, "scale_u": 1.5, "scale_v": 1.0, "offset_u": 0.25, "offset_v": 0.0 },
                        "uv1": { "distribution": "uniform", "tile_length": 1.0, "scale_u": 1.0, "scale_v": 2.0, "offset_u": 0.0, "offset_v": 0.5 }
                      }
                    },
                    {
                      "name": "sparkle_b",
                      "renderer": "billboard",
                      "sort_order": 3
                    }
                  ],
                  "niagara": {
                    "emitters": [
                      {
                        "name": "ribbon_a",
                        "source_name": "RibbonA",
                        "max_particles": 64,
                        "renderers": [ { "kind": "ribbon", "material": "mat_0" } ],
                        "attributes": [
                          { "name": "Position", "type": 0, "component": 0, "count": 3 },
                          { "name": "RibbonWidth", "type": 0, "component": 3, "count": 1 },
                          { "name": "RibbonLinkOrder", "type": 0, "component": 4, "count": 1 }
                        ]
                      },
                      {
                        "name": "sparkle_b",
                        "source_name": "SparkleB",
                        "max_particles": 64,
                        "renderers": [ { "kind": "sprite", "material": "mat_0", "sub_image_size": [4, 2] } ],
                        "attributes": [
                          { "name": "Position", "type": 0, "component": 0, "count": 3 },
                          { "name": "SpriteSize", "type": 0, "component": 3, "count": 2 }
                        ]
                      }
                    ]
                  }
                }
                """;
        EffectModel model = ProductionEffectParser.parse("qa_release:niagara_scale_unit", niagaraJson, null);
        assertNotNull(model.niagara());
        NiagaraSystemModel.EmitterSpec rib = model.niagara().emitters().get(0);
        NiagaraSystemModel.EmitterSpec spk = model.niagara().emitters().get(1);
        assertEquals(0, rib.sortOrder());
        assertEquals(3, spk.sortOrder());
        assertEquals("distance", rib.ribbonConfig().uv0().distribution());
        assertEquals(2.0f, rib.ribbonConfig().uv0().tileLength(), 1e-5f);
        assertEquals("uniform", rib.ribbonConfig().uv1().distribution());
        assertEquals(4, spk.subImageCols());
        assertEquals(2, spk.subImageRows());

        // Verify real aurora_trail moving sparkles stay in cm->block scale (< 1 block, never blown up 100x!)
        Path edenResources = Path.of("G:/weituo/code/26.2/Eden Realm/src/main/resources");
        Path auroraPath = edenResources.resolve("assets/eden_realm/lumenfx/effects/pegasus/aurora_trail.json");
        if (Files.isRegularFile(auroraPath)) {
            EffectModel aurora = ProductionEffectParser.parse("eden_realm:pegasus/aurora_trail",
                    Files.readString(auroraPath, StandardCharsets.UTF_8),
                    new DirectoryResourceBridge(edenResources));
            assertEquals(1, aurora.niagara().emitters().get(7).sortOrder());
            EffectHandle h = new EffectHandle(new Vec3(0, 64, 0));
            EffectRuntimeInstance inst = new EffectRuntimeInstance(aurora, h, null, 777L, 1);
            for (int t = 1; t <= 12; t++) {
                h.moveTo(new Vec3(t * 0.6, 64, 0));
                inst.tick(0.05f, 1, null);
            }
            for (NiagaraSystemRuntime.EmitterState es : inst.niagaraRuntime().emitters()) {
                boolean isRibbon = es.primaryRenderer.kind() == NiagaraSystemModel.RendererKind.RIBBON;
                boolean isFlareBurst = "minimal007".equals(es.spec.name());
                float maxExpected = isRibbon ? 4.0f : (isFlareBurst ? 2.0f : 1.0f);
                for (ParticleInstance p : es.renderableParticles()) {
                    assertTrue(p.width > 0f && p.width < maxExpected && p.height > 0f && p.height < maxExpected,
                            "Aurora emitter " + es.spec.name() + " (" + es.primaryRenderer.kind()
                                    + ") particle size must be in blocks (< " + maxExpected + "), got " + p.width + "x" + p.height);
                    if (isFlareBurst) {
                        assertTrue(Math.abs(p.width - p.height) > 0.05f,
                                "minimal007 must preserve 2D non-square SpriteSize.xy, got " + p.width + "x" + p.height);
                    }
                }
            }
        }
    }

    @Test
    void photonNbtParsesCurveSpeedCompoundColorBurstsPayloadEnableFlagsAndShade() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(raw); DataOutputStream o = new DataOutputStream(gz)) {
            o.writeByte(10); o.writeUTF("");
            o.writeByte(10); o.writeUTF("fxData");
            o.writeByte(9); o.writeUTF("fxObjects"); o.writeByte(10); o.writeInt(1);
            o.writeByte(8); o.writeUTF("type"); o.writeUTF("particle");
            o.writeByte(10); o.writeUTF("data");
            o.writeByte(8); o.writeUTF("name"); o.writeUTF("rich_photon");
            o.writeByte(10); o.writeUTF("config");
            // startSpeed curve: min=0, max=3, y0=0.41666666 -> 1.25
            o.writeByte(10); o.writeUTF("startSpeed");
            o.writeByte(8); o.writeUTF("type"); o.writeUTF("curve");
            o.writeByte(10); o.writeUTF("data");
            o.writeByte(5); o.writeUTF("min"); o.writeFloat(0.0f);
            o.writeByte(5); o.writeUTF("max"); o.writeFloat(3.0f);
            o.writeByte(9); o.writeUTF("curves"); o.writeByte(9); o.writeInt(1);
            o.writeByte(5); o.writeInt(4);
            o.writeFloat(0.0f); o.writeFloat(0.41666666f); o.writeFloat(1.0f); o.writeFloat(0.41666666f);
            o.writeByte(0); o.writeByte(0); // data, startSpeed
            // startColor compound: 0xFF3399FF
            o.writeByte(10); o.writeUTF("startColor");
            o.writeByte(8); o.writeUTF("type"); o.writeUTF("color");
            o.writeByte(10); o.writeUTF("data");
            o.writeByte(3); o.writeUTF("number"); o.writeInt(0xFF3399FF);
            o.writeByte(0); o.writeByte(0);
            // emission.bursts.payload
            o.writeByte(10); o.writeUTF("emission");
            o.writeByte(10); o.writeUTF("emissionRate");
            o.writeByte(10); o.writeUTF("data"); o.writeByte(5); o.writeUTF("number"); o.writeFloat(0.0f); o.writeByte(0);
            o.writeByte(0);
            o.writeByte(10); o.writeUTF("bursts");
            o.writeByte(9); o.writeUTF("payload"); o.writeByte(10); o.writeInt(1);
            o.writeByte(5); o.writeUTF("time"); o.writeFloat(0.0f);
            o.writeByte(3); o.writeUTF("cycles"); o.writeInt(1);
            o.writeByte(5); o.writeUTF("interval"); o.writeFloat(1.0f);
            o.writeByte(5); o.writeUTF("probability"); o.writeFloat(1.0f);
            o.writeByte(10); o.writeUTF("count");
            o.writeByte(10); o.writeUTF("data"); o.writeByte(3); o.writeUTF("number"); o.writeInt(5); o.writeByte(0);
            o.writeByte(0);
            o.writeByte(0); // burst[0]
            o.writeByte(0); // bursts
            o.writeByte(0); // emission
            // Disabled optional module via _enable = 0 must NOT activate
            o.writeByte(10); o.writeUTF("noise");
            o.writeByte(1); o.writeUTF("_enable"); o.writeByte(0);
            o.writeByte(5); o.writeUTF("strength"); o.writeFloat(9.5f);
            o.writeByte(0);
            o.writeByte(10); o.writeUTF("sizeOverLifetime");
            o.writeByte(1); o.writeUTF("_enable"); o.writeByte(0);
            o.writeByte(5); o.writeUTF("start"); o.writeFloat(4.0f);
            o.writeByte(5); o.writeUTF("end"); o.writeFloat(8.0f);
            o.writeByte(0);
            // renderer.shade = 0
            o.writeByte(10); o.writeUTF("renderer");
            o.writeByte(1); o.writeUTF("shade"); o.writeByte(0);
            o.writeByte(0);
            o.writeByte(0); o.writeByte(0); o.writeByte(0); o.writeByte(0); o.writeByte(0); // config, data, obj0, fxData, root
        }
        PhotonFxModel parsed = PhotonFxModel.parseNbt("test:rich_photon", raw.toByteArray(), null);
        assertNotNull(parsed);
        PhotonFxModel.FxObjectSpec spec = parsed.objects().get(0);
        assertEquals(1.25f, spec.startSpeed(), 1e-3f);
        assertEquals(0x33 / 255f, spec.r(), 1e-3f);
        assertEquals(0x99 / 255f, spec.g(), 1e-3f);
        assertEquals(1.0f, spec.b(), 1e-3f);
        assertEquals(1, spec.extras().bursts().size());
        assertEquals(5, spec.extras().bursts().get(0).count());
        assertEquals(0f, spec.extras().noiseStrength(), 1e-5f, "Disabled _enable=0 noise module must stay inactive");
        assertEquals(1f, spec.extras().sizeStartMult(), 1e-5f, "Disabled _enable=0 sizeOverLifetime must stay 1.0");
        assertFalse(spec.extras().shade(), "renderer.shade=0 must be parsed as false");

        // Also check real photon_authored_model.fx if present on disk
        Path realFx = Path.of("E:/.aaabackup/智能体产物/测试文件/LumenFX/0.3.0-client/instances/fabric-26.1.2/resourcepacks/recheck/assets/qa_release/fx/photon_authored_model.fx");
        if (Files.isRegularFile(realFx)) {
            PhotonFxModel realModel = PhotonFxModel.parseNbt("qa_release:photon_authored_model", Files.readAllBytes(realFx), null);
            assertNotNull(realModel);
            PhotonFxModel.FxObjectSpec realObj = realModel.objects().get(1);
            assertEquals(1.25f, realObj.startSpeed(), 1e-3f, "Real photon_authored_model.fx curve startSpeed must decode to 1.25");
            assertEquals(0f, realObj.extras().noiseStrength(), 1e-5f);
            assertTrue(realObj.extras().shade());
        }
    }

    @Test
    void photonStartSpeedCurveEvaluatesDynamicallyAcrossEmitterTime() throws Exception {
        byte[] risingFx = buildCurveSpeedPhotonFx(0.95f);
        PhotonFxModel rising = PhotonFxModel.parseNbt("test:rising_speed", risingFx, null);
        assertNotNull(rising);
        PhotonFxRuntime rt = new PhotonFxRuntime(rising, 847539L);
        EffectHandle handle = new EffectHandle(Vec3.ZERO);
        for (int tick = 1; tick <= 72; tick++) {
            rt.tick(handle.snapshot(), 0.05f, false);
            if (tick == 4 || tick == 40 || tick == 72) {
                List<ParticleInstance> pool = rt.objects().get(0).particles;
                assertEquals(tick, pool.size());
                ParticleInstance newest = pool.get(pool.size() - 1);
                double mag = Math.sqrt(newest.vx * newest.vx + newest.vy * newest.vy + newest.vz * newest.vz);
                double expected = 3.0 * (0.25 + (0.95 - 0.25) * (tick / 80.0));
                assertEquals(expected, mag, 1e-3, "Spawned speed must follow startSpeed curve at tick " + tick);
            }
        }
    }

    private static byte[] buildCurveSpeedPhotonFx(float y1) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(raw); DataOutputStream o = new DataOutputStream(gz)) {
            o.writeByte(10); o.writeUTF("");
            o.writeByte(10); o.writeUTF("fxData");
            o.writeByte(9); o.writeUTF("fxObjects"); o.writeByte(10); o.writeInt(1);
            o.writeByte(8); o.writeUTF("type"); o.writeUTF("particle");
            o.writeByte(10); o.writeUTF("data");
            o.writeByte(8); o.writeUTF("name"); o.writeUTF("curve_speed_emitter");
            o.writeByte(10); o.writeUTF("config");
            o.writeByte(5); o.writeUTF("duration"); o.writeFloat(80f);
            o.writeByte(5); o.writeUTF("startLifetime"); o.writeFloat(80f);
            o.writeByte(10); o.writeUTF("emission");
            o.writeByte(5); o.writeUTF("emissionRate"); o.writeFloat(1f);
            o.writeByte(0);
            o.writeByte(10); o.writeUTF("startSpeed");
            o.writeByte(8); o.writeUTF("type"); o.writeUTF("curve");
            o.writeByte(10); o.writeUTF("data");
            o.writeByte(5); o.writeUTF("min"); o.writeFloat(0f);
            o.writeByte(5); o.writeUTF("max"); o.writeFloat(3f);
            o.writeByte(5); o.writeUTF("lower"); o.writeFloat(0f);
            o.writeByte(5); o.writeUTF("upper"); o.writeFloat(3f);
            o.writeByte(9); o.writeUTF("curves"); o.writeByte(9); o.writeInt(1);
            o.writeByte(5); o.writeInt(8);
            float[] pts = {0f, 0.25f, 1f / 3f, 0.25f + (y1 - 0.25f) / 3f,
                    2f / 3f, 0.25f + 2f * (y1 - 0.25f) / 3f, 1f, y1};
            for (float v : pts) o.writeFloat(v);
            for (int i = 0; i < 7; i++) o.writeByte(0);
        }
        return raw.toByteArray();
    }

    private static byte[] buildSyntheticSimCacheLfsc() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        ByteBuffer b = ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x4353464c).putInt(6); // magic + v6
        putText(b, "qa_test_system");
        b.putFloat(0f).putFloat(1f); // start, duration
        b.putInt(1 | (1 << 2)); // interpolation=true, CaptureMode.ALL
        b.putInt(1).putInt(2); // 1 emitter, 2 frames
        b.putInt(0); // 0 materials
        // System layout (empty)
        putText(b, "system"); b.putInt(0).putInt(0).putInt(0).putInt(0).putInt(-1).putInt(0);
        // Emitter layout: 3 floats (Position), 1 int (QaIntTag), 1 half (QaHalfScalar)
        putText(b, "e0"); b.putInt(2).putInt(3).putInt(1).putInt(1).putInt(-1).putInt(3);
        putField(b, "Position", "/Script/Niagara.NiagaraPosition", 0, 3, -1, 0, -1, 0, 2);
        putField(b, "QaIntTag", "/Script/Niagara.NiagaraInt32", -1, 0, 0, 1, -1, 0, 0);
        putField(b, "QaHalfScalar", "/Script/Niagara.NiagaraHalf", -1, 0, -1, 0, 0, 1, 0);

        // Frame 0: 120 rows, acquireTag=500000, idTable len 120 (id 0 -> 0, id 5 -> -1, id 100 -> 100), nextRows[0] = 2
        writeCacheFrame(b, 0.0f, 0, 120, 500000, 10.0f, true);
        // Frame 1: 120 rows, acquireTag=500001, z=30.0f
        writeCacheFrame(b, 1.0f, 1, 120, 500001, 30.0f, false);

        raw.write(b.array(), 0, b.position());
        return raw.toByteArray();
    }

    private static void writeCacheFrame(ByteBuffer b, float age, int tick, int count, int acquireTag,
                                        float zVal, boolean withNextRows) {
        b.putFloat(age).putInt(tick);
        // Transform: pos(3d), quat(4d), scale(3d), tile(3d)
        b.putDouble(0).putDouble(0).putDouble(0);
        b.putDouble(0).putDouble(0).putDouble(0).putDouble(1);
        b.putDouble(1).putDouble(1).putDouble(1);
        b.putDouble(0).putDouble(0).putDouble(0);
        b.putInt(0); // null system bounds
        // System buffer (1 row, 0 comps)
        b.putInt(1).putInt(0).putInt(0).putInt(0).putInt(0);
        // Emitter 0 frame
        b.putInt(0); // null bounds
        b.putInt(count); // totalSpawned
        b.putInt(count).putInt(acquireTag).putInt(count).putInt(withNextRows ? count : 0).putInt(0);
        // 3 float components planar
        for (int r = 0; r < count; r++) b.putFloat(1.0f); // X
        for (int r = 0; r < count; r++) b.putFloat(2.0f); // Y
        for (int r = 0; r < count; r++) b.putFloat(zVal); // Z
        // 1 int component
        for (int r = 0; r < count; r++) b.putInt(777);
        // 1 half component
        short h = HalfFloat.fromFloat(1.25f);
        for (int r = 0; r < count; r++) b.putShort(h);
        // idTable: id 5 is dead gap (-1), others map identity
        for (int i = 0; i < count; i++) b.putInt(i == 5 ? -1 : i);
        // nextRows: row 0 maps to row 2 in next frame
        if (withNextRows) {
            for (int r = 0; r < count; r++) b.putInt(r == 0 ? 2 : r);
        }
    }

    private static void putField(ByteBuffer b, String name, String type,
                                 int fs, int fc, int is, int ic, int hs, int hc, int flags) {
        putText(b, name); putText(b, type);
        b.putInt(fs).putInt(fc).putInt(is).putInt(ic).putInt(hs).putInt(hc).putInt(flags);
    }

    private static void putText(ByteBuffer b, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        b.putInt(bytes.length).put(bytes);
    }

    private static byte[] buildSyntheticPhotonModelFx() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(raw); DataOutputStream o = new DataOutputStream(gz)) {
            o.writeByte(10); o.writeUTF("");
            o.writeByte(10); o.writeUTF("fxData");
            o.writeByte(9); o.writeUTF("fxObjects"); o.writeByte(10); o.writeInt(2);
            // Obj 0: parent empty
            o.writeByte(8); o.writeUTF("type"); o.writeUTF("empty");
            o.writeByte(10); o.writeUTF("data");
            o.writeByte(8); o.writeUTF("name"); o.writeUTF("qa_photon_model_parent");
            o.writeByte(10); o.writeUTF("_transform");
            o.writeByte(8); o.writeUTF("id"); o.writeUTF("p0");
            o.writeByte(9); o.writeUTF("localPosition"); o.writeByte(5); o.writeInt(3);
            o.writeFloat(0.75f); o.writeFloat(1.25f); o.writeFloat(0.5f);
            o.writeByte(0); // _transform
            o.writeByte(0); // data
            o.writeByte(0); // obj 0
            // Obj 1: child Model
            o.writeByte(8); o.writeUTF("type"); o.writeUTF("particle");
            o.writeByte(10); o.writeUTF("data");
            o.writeByte(8); o.writeUTF("name"); o.writeUTF("qa_photon_model");
            o.writeByte(10); o.writeUTF("_transform");
            o.writeByte(8); o.writeUTF("id"); o.writeUTF("p1");
            o.writeByte(8); o.writeUTF("_parentId"); o.writeUTF("p0");
            o.writeByte(0); // _transform
            o.writeByte(10); o.writeUTF("config");
            o.writeByte(10); o.writeUTF("renderer");
            o.writeByte(8); o.writeUTF("renderMode"); o.writeUTF("Model");
            o.writeByte(10); o.writeUTF("model");
            o.writeByte(10); o.writeUTF("source");
            o.writeByte(10); o.writeUTF("data");
            o.writeByte(8); o.writeUTF("modelLocation"); o.writeUTF("minecraft:block/sea_lantern");
            o.writeByte(0); o.writeByte(0); o.writeByte(0); // data, source, model
            o.writeByte(10); o.writeUTF("materials");
            o.writeByte(9); o.writeUTF("payload"); o.writeByte(10); o.writeInt(1);
            o.writeByte(10); o.writeUTF("material");
            o.writeByte(10); o.writeUTF("data");
            o.writeByte(8); o.writeUTF("texture"); o.writeUTF("minecraft:textures/block/sea_lantern.png");
            o.writeByte(0); o.writeByte(0); o.writeByte(0); // data, material, payload[0]
            o.writeByte(0); // materials
            o.writeByte(0); // renderer
            o.writeByte(0); // config
            o.writeByte(0); // data
            o.writeByte(0); // obj 1
            o.writeByte(0); // fxData
            o.writeByte(0); // root
        }
        return raw.toByteArray();
    }

    private static class DirectoryResourceBridge implements McResourceBridge {
        private final Path[] roots;
        DirectoryResourceBridge(Path... roots) { this.roots = roots; }
        @Override public String mcVersion() { return "test"; }
        @Override public String loaderName() { return "junit"; }
        @Override public Map<String, byte[]> listResources(String prefix, String suffix) {
            Map<String, byte[]> out = new LinkedHashMap<>();
            for (Path root : roots) {
                Path assets = root.resolve("assets");
                if (!Files.isDirectory(assets)) continue;
                try (Stream<Path> nsDirs = Files.list(assets)) {
                    for (Path nsDir : nsDirs.toList()) {
                        if (!Files.isDirectory(nsDir)) continue;
                        String ns = nsDir.getFileName().toString();
                        Path base = nsDir.resolve(prefix);
                        if (!Files.isDirectory(base)) continue;
                        try (Stream<Path> walk = Files.walk(base)) {
                            for (Path f : walk.filter(Files::isRegularFile).toList()) {
                                String rel = nsDir.relativize(f).toString().replace('\\', '/');
                                if (rel.endsWith(suffix)) out.put(ns + ":" + rel, Files.readAllBytes(f));
                            }
                        }
                    }
                } catch (IOException ignored) { }
            }
            return out;
        }
        @Override public byte[] readResource(String resourceId) {
            int c = resourceId.indexOf(':');
            if (c < 0) return null;
            String ns = resourceId.substring(0, c);
            String rel = resourceId.substring(c + 1);
            for (Path root : roots) {
                Path p = root.resolve("assets").resolve(ns).resolve(rel);
                if (Files.isRegularFile(p)) {
                    try { return Files.readAllBytes(p); } catch (IOException ignored) { }
                }
            }
            return null;
        }
        @Override public DecodedImage decodePng(byte[] pngBytes) { return null; }
        @Override public boolean isSolidBlock(Object level, double x, double y, double z) { return false; }
        @Override public int samplePackedLight(Object level, double x, double y, double z) { return 0x00F000F0; }
        @Override public boolean isShaderModLoaded() { return false; }
        @Override public boolean isChunkRendererModLoaded() { return false; }
    }
}
