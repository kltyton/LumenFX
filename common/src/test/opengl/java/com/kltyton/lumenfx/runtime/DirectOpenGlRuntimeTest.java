package com.kltyton.lumenfx.runtime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache;
import com.kltyton.lumenfx.math.SourceRandomStream;
import com.kltyton.lumenfx.runtime.bridge.McResourceBridge;
import com.kltyton.lumenfx.runtime.gl.DirectGlNiagaraGpuRunner;
import com.kltyton.lumenfx.runtime.model.EffectModel;
import com.kltyton.lumenfx.runtime.model.NiagaraSystemModel;
import com.kltyton.lumenfx.runtime.model.PhotonFxModel;
import com.kltyton.lumenfx.runtime.model.ProductionEffectParser;
import com.kltyton.lumenfx.runtime.model.ScalarCurve;
import com.kltyton.lumenfx.runtime.model.StaticMeshModel;
import com.kltyton.lumenfx.runtime.model.UnityEmitterModel;
import com.kltyton.lumenfx.runtime.niagara.HalfFloat;
import com.kltyton.lumenfx.runtime.simulation.EffectRuntimeInstance;
import com.kltyton.lumenfx.runtime.simulation.NiagaraSystemRuntime;
import com.kltyton.lumenfx.runtime.simulation.ParticleInstance;
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
        Path auroraPath = Path.of("G:/weituo/code/26.2/Eden Realm/src/main/resources/assets/eden_realm/lumenfx/effects/pegasus/aurora_trail.json");
        if (!Files.isRegularFile(auroraPath)) return;

        String json = Files.readString(auroraPath, StandardCharsets.UTF_8);
        EffectModel model = ProductionEffectParser.parse("eden_realm:pegasus/aurora_trail", json, null);
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

    private static final class DirectoryResourceBridge implements McResourceBridge {
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
