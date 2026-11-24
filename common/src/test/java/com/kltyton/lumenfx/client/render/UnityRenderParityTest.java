package com.kltyton.lumenfx.client.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.client.resource.EffectJson;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata;
import com.kltyton.lumenfx.client.simulation.EffectInstance;
import com.kltyton.lumenfx.effect.EffectDefinition;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.render.MaterialBlendOp;
import com.kltyton.lumenfx.effect.render.material.MaterialInstanceParameters;
import com.kltyton.lumenfx.effect.render.material.MaterialTextureSampler;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

final class UnityRenderParityTest {
    private static final float EPS = 1e-4f;

    private static EffectDefinition parse(String json) {
        return EffectJson.read(JsonParser.parseString(json).getAsJsonObject());
    }

    private static List<EffectMesh> stepAndBuild(EffectInstance instance, EffectHandle handle, double now, Vec3 camera) {
        EffectMeshBuilder builder = new EffectMeshBuilder(0);
        builder.begin(camera, new Quaternionf());
        instance.beginSimulationFrame(now);
        for (var frame : instance.packPreparedFrame()) {
            frame.updateAndPack(builder);
        }
        return builder.finish();
    }

    @Test
    void shurikenTrailUsesSourceSubstepsAndClipsExpiredTailWithHeadFirstUv() {
        var definition = parse("""
                {
                  "format_version": 1,
                  "materials": { "mat": {} },
                  "emitters": [
                    {
                      "name": "trail", "renderer": "ribbon", "material": "mat",
                      "rate": 0, "start_speed": 2, "start_direction": [1, 0, 0],
                      "lifetime": 1, "subdivisions": 1,
                      "bursts": [{"time": 0, "min": 1, "max": 1}],
                      "unity_renderer": {
                        "particle_trail": {
                          "maximum_particle_delta_time": 0.03, "lifetime": 0.0875,
                          "size_affects_width": false, "width_over_trail": 0.05
                        },
                        "trail_texture": { "mode": "stretch" }
                      }
                    }
                  ]
                }
                """);
        var handle = new EffectHandle(Vec3.ZERO);
        var camera = new Vec3(0, 0, 5);
        try (var instance = new EffectInstance(definition, handle, 3821L, null)) {
            assertTrue(stepAndBuild(instance, handle, 0, camera).isEmpty());
            // Unity 6000.6.10f1 BakeTrailsMesh reports one visible segment on the birth frame.
            var birthFrame = stepAndBuild(instance, handle, 0.05, camera).getFirst();
            assertEquals(1, birthFrame.instanceCount());
            assertEquals(0.10f, birthFrame.values()[0], EPS);
            assertEquals(0.0f, birthFrame.values()[3], EPS);

            var next = stepAndBuild(instance, handle, 0.10, camera).getFirst();
            assertEquals(3, next.instanceCount());
            int lastNext = 2 * EffectMesh.Kind.RIBBON.floatsPerInstance();
            assertEquals(0.0f, next.values()[lastNext + 3], EPS);

            var clipped = stepAndBuild(instance, handle, 0.15, camera).getFirst();
            assertEquals(4, clipped.instanceCount());
            float[] vertices = clipped.values();
            int last = 3 * EffectMesh.Kind.RIBBON.floatsPerInstance();
            assertEquals(0.30f, vertices[0], EPS);
            assertEquals(0, vertices[12], EPS);
            assertEquals(0.125f, vertices[last + 3], EPS);
            assertEquals(1, vertices[last + 13], EPS);
            assertEquals(0.05f, Math.abs(vertices[7] * 2), EPS);
        }
    }

    @Test
    void parsesExtendedUnityParticleMaterialsBlendOpsAndRuntimeParameters() {
        String json = """
                {
                  "format_version": 1,
                  "materials": {
                    "soft_rev_sub": {
                      "fragment_shader": "lumenfx:core/unity_particle",
                      "blend": "soft_additive",
                      "blend_op": "reverse_subtract",
                      "cull": true,
                      "depth_projection": true,
                      "scene_depth": true,
                      "runtime_parameters": [
                        { "name": "uvST", "kind": "vector", "type": "constant", "default": [1.0, 1.0, 0.0, 0.0] },
                        { "name": "emissionAndCutoff", "kind": "vector", "type": "constant", "default": [2.0, 0.5, 0.0, 0.25] },
                        { "name": "fadeParams", "kind": "vector", "type": "constant", "default": [0.5, 2.0, 1.0, 4.0] },
                        { "name": "config", "kind": "vector", "type": "constant", "default": [1.0, 15.0, 1.0, 0.0] }
                      ]
                    },
                    "multiply_textured": {
                      "fragment_shader": "lumenfx:core/unity_particle",
                      "blend": "modulate",
                      "blend_op": "add",
                      "textures": { "Sampler0": "lumenfx:textures/particle/spark" }
                    }
                  },
                  "emitters": [
                    { "name": "a", "renderer": "sprite", "material": "soft_rev_sub", "max_particles": 4, "rate": 0 },
                    { "name": "b", "renderer": "sprite", "material": "multiply_textured", "max_particles": 4, "rate": 0 }
                  ]
                }
                """;
        EffectDefinition def = parse(json);
        MaterialDefinition soft = def.materials().get("soft_rev_sub");
        assertEquals(Identifier.parse("lumenfx:core/unity_particle"), soft.fragmentShader());
        assertEquals(MaterialDefinition.Blend.SOFT_ADDITIVE, soft.blend());
        assertEquals(MaterialBlendOp.REVERSE_SUBTRACT, soft.blendOp());
        assertTrue(soft.cull());
        assertTrue(soft.depthProjection());
        assertTrue(soft.sceneDepth());
        assertTrue(soft.textures().isEmpty());
        List<MaterialInstanceParameters.Slot> layout = soft.instanceParameters().layout();
        assertEquals(4, layout.size());
        assertEquals("uvST", layout.get(0).name());
        assertArrayEquals(new float[] {2.0f, 0.5f, 0.0f, 0.25f}, layout.get(1).defaults(), EPS);
        assertArrayEquals(new float[] {0.5f, 2.0f, 1.0f, 4.0f}, layout.get(2).defaults(), EPS);
        assertArrayEquals(new float[] {1.0f, 15.0f, 1.0f, 0.0f}, layout.get(3).defaults(), EPS);

        MaterialDefinition mod = def.materials().get("multiply_textured");
        assertEquals(MaterialDefinition.Blend.MODULATE, mod.blend());
        assertEquals(MaterialBlendOp.ADD, mod.blendOp());
        assertTrue(mod.textures().containsKey("Sampler0"));
    }

    @Test
    void packsCpuSpriteFlipbookCurrentNextUvAndBlendFraction() {
        String json = """
                {
                  "format_version": 1,
                  "materials": {
                    "atlas_mat": {
                      "fragment_shader": "lumenfx:core/unity_particle",
                      "blend": "alpha",
                      "textures": { "Sampler0": "lumenfx:textures/particle/atlas" }
                    }
                  },
                  "emitters": [
                    {
                      "name": "flipbook",
                      "renderer": "sprite",
                      "material": "atlas_mat",
                      "duration": 2,
                      "loop": false,
                      "max_particles": 4,
                      "rate": 0,
                      "bursts": [ { "time": 0, "min": 1, "max": 1 } ],
                      "lifetime": 2,
                      "atlas_columns": 2,
                      "atlas_rows": 2,
                      "atlas_frame": {
                        "keys": [
                          { "time": 0.0, "value": 0.25, "interpolation": "linear" },
                          { "time": 1.0, "value": 2.25, "interpolation": "linear" }
                        ]
                      }
                    }
                  ]
                }
                """;
        EffectDefinition def = parse(json);
        EffectHandle handle = new EffectHandle(Vec3.ZERO);
        EffectInstance instance = new EffectInstance(def, handle, 11L);

        // At t=0: normalized age = 0 -> phase = 0.25 -> frame 0 [0,0,0.5,0.5], next frame 1 [0.5,0,1.0,0.5], blend = 0.25
        List<EffectMesh> m0 = stepAndBuild(instance, handle, 0.0, new Vec3(0, 0, 10));
        assertEquals(1, m0.size());
        assertEquals(EffectMesh.Kind.SPRITE, m0.get(0).kind());
        assertEquals(1, m0.get(0).instanceCount());
        float[] d0 = m0.get(0).values();
        assertArrayEquals(new float[] {0f, 0f, 0.5f, 0.5f}, new float[] {d0[6], d0[7], d0[8], d0[9]}, EPS);
        assertArrayEquals(new float[] {0.5f, 0f, 1f, 0.5f}, new float[] {d0[22], d0[23], d0[24], d0[25]}, EPS);
        assertEquals(0.25f, d0[26], EPS);

        // At t=1.5: normalized age = 0.75 -> phase = 1.75 -> frame 1 [0.5,0,1.0,0.5], next frame 2 [0,0.5,0.5,1.0], blend = 0.75
        List<EffectMesh> m1 = stepAndBuild(instance, handle, 1.5, new Vec3(0, 0, 10));
        float[] d1 = m1.get(0).values();
        assertArrayEquals(new float[] {0.5f, 0f, 1f, 0.5f}, new float[] {d1[6], d1[7], d1[8], d1[9]}, EPS);
        assertArrayEquals(new float[] {0f, 0.5f, 0.5f, 1f}, new float[] {d1[22], d1[23], d1[24], d1[25]}, EPS);
        assertEquals(0.75f, d1[26], EPS);
    }

    @Test
    void fixedUnityRowIsQuantizedAfterAddingAnimation() {
        var definition = parse("""
                {
                  "format_version": 1,
                  "materials": {"mat": {}},
                  "emitters": [{
                    "name": "sheet", "renderer": "sprite", "material": "mat",
                    "duration": 2, "rate": 0, "lifetime": 1,
                    "bursts": [{"time": 0, "min": 1, "max": 1}],
                    "atlas_columns": 4, "atlas_rows": 97, "atlas_row": 51,
                    "atlas_frame": {"keys": [
                      {"time": 0, "value": 0, "interpolation": "linear"},
                      {"time": 1, "value": 3.9996, "interpolation": "linear"}
                    ]},
                    "unity_renderer": {"tint": [1, 1, 1, 1]}
                  }]
                }
                """);
        var handle = new EffectHandle(Vec3.ZERO);
        var instance = new EffectInstance(definition, handle, 3821);
        var camera = new Vec3(0, 0, 10);
        var initial = stepAndBuild(instance, handle, 0, camera).getFirst().values();
        var animated = stepAndBuild(instance, handle, .05, camera).getFirst().values();

        // Unity 6000.6.10f1 reads tile (3,49) at phase zero and (0,50) at the first positive phase.
        assertEquals(.75f, initial[6], EPS);
        assertEquals(49f / 97f, initial[7], EPS);
        assertEquals(0f, animated[6], EPS);
        assertEquals(50f / 97f, animated[7], EPS);
    }

    @Test
    void ribbonModeJoinsLiveParticlesAndUsesItsOwnWidthAndColors() {
        String json = """
                {
                  "format_version": 1,
                  "materials": { "m": { "fragment_shader": "lumenfx:core/color", "blend": "alpha" } },
                  "emitters": [
                    {
                      "name": "live_ribbon",
                      "renderer": "ribbon",
                      "material": "m",
                      "local_space": false,
                      "duration": 0.22,
                      "loop": false,
                      "max_particles": 16,
                      "rate": 25,
                      "bursts": [ { "time": 0, "min": 1, "max": 1 } ],
                      "lifetime": 0.35,
                      "width": 4.0,
                      "start_color": [1.0, 0.2, 0.4, 1.0],
                      "start_speed": 0,
                      "velocity_over_life": [0, 0, 0],
                      "subdivisions": 1,
                      "unity_renderer": {
                        "particle_trail": {
                          "mode": "ribbon",
                          "ratio": 1.0,
                          "minimum_vertex_distance": 0.5,
                          "world_space": false,
                          "die_with_particles": false,
                          "size_affects_width": true,
                          "inherit_color": true,
                          "lifetime": 1.0,
                          "width_over_trail": 0.5,
                          "color_over_trail": [0.5, 1.0, 1.0, 1.0],
                          "color_over_lifetime": [1.0, 0.5, 1.0, 1.0]
                        }
                      }
                    }
                  ]
                }
                """;
        EffectDefinition def = parse(json);
        EffectHandle handle = new EffectHandle(Vec3.ZERO);
        EffectInstance instance = new EffectInstance(def, handle, 101L);

        stepAndBuild(instance, handle, 0.0, new Vec3(0, 0, 20));
        // Unity Ribbon connects the two new nodes even though each gap is below the particle-trail threshold.
        handle.setPose(EffectHandle.Pose.at(new Vec3(0.1, 0, 0)));
        List<EffectMesh> shortStep = stepAndBuild(instance, handle, 0.1, new Vec3(0, 0, 20));
        assertEquals(2, shortStep.getFirst().instanceCount());

        // Move to x=2.0 and x=4.0 well past minimum_vertex_distance
        handle.setPose(EffectHandle.Pose.at(new Vec3(2.0, 0, 0)));
        stepAndBuild(instance, handle, 0.2, new Vec3(0, 0, 20));
        handle.setPose(EffectHandle.Pose.at(new Vec3(4.0, 0, 0)));
        List<EffectMesh> activeMesh = stepAndBuild(instance, handle, 0.35, new Vec3(0, 0, 20));
        assertEquals(1, activeMesh.size());
        assertEquals(EffectMesh.Kind.RIBBON, activeMesh.get(0).kind());
        assertTrue(activeMesh.getFirst().instanceCount() > 2);

        // Width check: width=4.0 * width_over_trail=0.5 => full width 2.0 => OffsetA length ~ 1.0
        float[] rv = activeMesh.get(0).values();
        float offsetLen = (float) Math.sqrt(rv[6] * rv[6] + rv[7] * rv[7] + rv[8] * rv[8]);
        assertEquals(1.0f, offsetLen, 0.15f);
        // Inherit color (1, 0.2, 0.4) * color_over_trail (0.5, 1, 1) * color_over_lifetime (1, 0.5, 1) -> (~0.5, ~0.1, ~0.4)
        assertEquals(0.5f, rv[20], 0.05f);
        assertEquals(0.1f, rv[21], 0.05f);
        assertEquals(0.4f, rv[22], 0.05f);

        assertTrue(stepAndBuild(instance, handle, 0.6, new Vec3(0, 0, 20)).isEmpty());
        instance.close();

        // The native Ribbon mesh still joins the live nodes with the Particle mode ratio set to zero.
        String ratioJson = """
                {
                  "format_version": 1,
                  "materials": { "m": { "fragment_shader": "lumenfx:core/color", "blend": "alpha" } },
                  "emitters": [
                    {
                      "name": "partial_ribbon",
                      "renderer": "ribbon",
                      "material": "m",
                      "duration": 5,
                      "loop": false,
                      "max_particles": 64,
                      "rate": 0,
                      "bursts": [ { "time": 0, "min": 20, "max": 20 } ],
                      "lifetime": 2.0,
                      "width": 2.0,
                      "start_color": [0.1, 0.9, 0.2, 1.0],
                      "subdivisions": 1,
                      "attach_head": true,
                      "unity_renderer": {
                        "ribbon_count": 20,
                        "particle_trail": {
                          "mode": "ribbon",
                          "ratio": 0,
                          "world_space": true,
                          "die_with_particles": true,
                          "size_affects_width": false,
                          "inherit_color": false,
                          "lifetime": 1.0,
                          "width_over_trail": 1.5,
                          "color_over_trail": [0.8, 0.6, 0.4, 1.0],
                          "color_over_lifetime": [1.0, 1.0, 1.0, 1.0]
                        }
                      }
                    }
                  ]
                }
                """;
        EffectDefinition ratioDef = parse(ratioJson);
        EffectHandle ratioHandle = new EffectHandle(Vec3.ZERO);
        EffectInstance ratioInstance = new EffectInstance(ratioDef, ratioHandle, 202L);
        stepAndBuild(ratioInstance, ratioHandle, 0.0, new Vec3(0, 0, 20));
        ratioHandle.setPose(EffectHandle.Pose.at(new Vec3(3.0, 0, 0)));
        List<EffectMesh> partial = stepAndBuild(ratioInstance, ratioHandle, 0.2, new Vec3(0, 0, 20));
        assertEquals(1, partial.size());
        assertEquals(20, partial.getFirst().instanceCount());
        float[] pv = partial.get(0).values();
        // inherit_color=false ignores start_color [0.1,0.9,0.2] -> uses trail colors directly [0.8, 0.6, 0.4]
        assertEquals(0.8f, pv[20], 0.05f);
        assertEquals(0.6f, pv[21], 0.05f);
        assertEquals(0.4f, pv[22], 0.05f);
        // size_affects_width=false -> width=1.5*width_over_trail (half-width ~ 0.75, not scaled by particle width=2.0 -> 1.5)
        float pOffLen = (float) Math.sqrt(pv[6] * pv[6] + pv[7] * pv[7] + pv[8] * pv[8]);
        assertEquals(0.75f, pOffLen, 0.1f);
        ratioInstance.close();
    }

    @Test
    void parsesMaterialTextureSamplersAndMirrorOnceTextureMetadata() {
        String json = """
                {
                  "format_version": 1,
                  "materials": {
                    "point_clamp_mat": {
                      "fragment_shader": "lumenfx:core/unity_particle",
                      "blend": "alpha",
                      "depth": { "test": true, "write": false, "compare": "less_equal" },
                      "cull": true,
                      "parameters": [0, 0, 0, 0],
                      "textures": {
                        "Sampler0": "lumenfx:textures/effect/pixel_sprite.png",
                        "Sampler1": "lumenfx:textures/effect/mirror_mask.png"
                      },
                      "texture_samplers": {
                        "Sampler0": {
                          "address_x": "clamp",
                          "address_y": "clamp",
                          "min_mag_filter": "point",
                          "mip_filter": "point",
                          "max_anisotropy": 1
                        },
                        "Sampler1": {
                          "address_x": "mirror_once",
                          "address_y": "mirror",
                          "min_mag_filter": "linear",
                          "mip_filter": "linear",
                          "max_anisotropy": 4
                        }
                      }
                    }
                  },
                  "emitters": [
                    {
                      "name": "sprite",
                      "renderer": "sprite",
                      "material": "point_clamp_mat",
                      "duration": 1.0,
                      "loop": false,
                      "max_particles": 4,
                      "rate": 1,
                      "lifetime": 1.0,
                      "size": 1.0
                    }
                  ]
                }
                """;
        EffectDefinition def = parse(json);
        MaterialDefinition mat = def.materials().get("point_clamp_mat");
        MaterialTextureSampler s0 = mat.textureSamplers().get("Sampler0");
        MaterialTextureSampler s1 = mat.textureSamplers().get("Sampler1");
        assertEquals(MaterialTextureSampler.Address.CLAMP, s0.addressX());
        assertEquals(MaterialTextureSampler.Address.CLAMP, s0.addressY());
        assertEquals(MaterialTextureSampler.Filter.POINT, s0.minMag());
        assertEquals(MaterialTextureSampler.Filter.POINT, s0.mip());
        assertEquals(1, s0.maxAnisotropy());
        assertEquals(MaterialTextureSampler.Address.MIRROR_ONCE, s1.addressX());
        assertEquals(MaterialTextureSampler.Address.MIRROR, s1.addressY());
        assertEquals(MaterialTextureSampler.Filter.LINEAR, s1.minMag());
        assertEquals(MaterialTextureSampler.Filter.LINEAR, s1.mip());
        assertEquals(4, s1.maxAnisotropy());

        String texMetaJson = """
                {
                  "format_version": 1,
                  "source": "unity-texture2d-png",
                  "width": 64,
                  "height": 64,
                  "srgb": true,
                  "mip_gen": "simple_average",
                  "sharpen": 0.0,
                  "kernel_size": 2,
                  "downsample_with_average": false,
                  "sharpen_without_color_shift": false,
                  "use_new_mip_filter": false,
                  "address_x": "clamp",
                  "address_y": "mirror_once",
                  "min_mag_filter": "point",
                  "mip_filter": "point",
                  "max_anisotropy": 1,
                  "lod_group": "TEXTUREGROUP_Effects",
                  "compression": "TC_Default",
                  "cooked_format": "RGBA8",
                  "png_sha256": "0000000000000000000000000000000000000000000000000000000000000000",
                  "mip_data": "",
                  "legacy_gamma": false
                }
                """;
        TextureMetadata meta = TextureMetadata.parse(JsonParser.parseString(texMetaJson).getAsJsonObject());
        assertEquals(TextureMetadata.Address.CLAMP, meta.addressX());
        assertEquals(TextureMetadata.Address.MIRROR_ONCE, meta.addressY());
        assertEquals(TextureMetadata.FilterMode.POINT, meta.minMag());
        assertEquals(TextureMetadata.FilterMode.POINT, meta.mip());
        assertEquals("RGBA8", meta.cookedFormat());
    }

    @Test
    void parsesUnityUvChannelMaskIncludingNonUv0Channels() throws Exception {
        String json = """
                {
                  "format_version": 1,
                  "materials": { "mat": {} },
                  "emitters": [
                    {
                      "name": "uv1_mesh",
                      "renderer": "sprite",
                      "material": "mat",
                      "duration": 1,
                      "loop": false,
                      "max_particles": 4,
                      "rate": 0,
                      "bursts": [ { "time": 0, "min": 1, "max": 1 } ],
                      "lifetime": 1,
                      "width": 1,
                      "height": 1,
                      "unity_renderer": {
                        "uv_channel_mask": 2
                      }
                    }
                  ]
                }
                """;
        EffectDefinition def = parse(json);
        var binding = def.unityRenderers().get("uv1_mesh");
        assertEquals(2, binding.uvChannelMask());

        String meshShader = java.nio.file.Files.readString(
                java.nio.file.Path.of("common/src/main/resources/assets/lumenfx/shaders/include/niagara_mesh_main.glsl"));
        assertTrue(meshShader.contains("int subImageUvMask = (subImageFlags >> 1) == 0 ? 1 : (subImageFlags >> 1);"));
        assertTrue(meshShader.contains("(subImageUvMask & 4) != 0"));
    }

    @Test
    void decodesWideVec4UvStaticMeshV3AndTiltsBillboardWithStartRotationXY() throws Exception {
        // Version 3 .lfmesh with 1 vertex, 1 triangle (0,0,0), 2 UV channels of widths [2, 4].
        var buf = java.nio.ByteBuffer.allocate(2048).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0x484d464c); // "LFMH"
        buf.putInt(3);          // version 3
        buf.putInt(0);          // SOURCE_VERTEX_INSTANCE
        buf.putInt(1);          // vertexCount = 1
        buf.putInt(3);          // indexCount = 3
        buf.putInt(2);          // uvCount = 2
        buf.putInt(1);          // sectionCount = 1
        buf.putInt(2);          // uv0 width = 2
        buf.putInt(4);          // uv1 width = 4
        buf.putInt(0);          // instanceId
        buf.putFloat(0).putFloat(0).putFloat(0); // pos
        buf.putFloat(0).putFloat(1).putFloat(0); // normal
        buf.putFloat(1).putFloat(0).putFloat(0); // tangent
        buf.putFloat(1);                         // binormalSign
        buf.putFloat(1).putFloat(1).putFloat(1).putFloat(1); // color
        buf.putFloat(0.25f).putFloat(0.75f);     // uv0 (2D)
        buf.putFloat(0.1f).putFloat(0.2f).putFloat(0.3f).putFloat(0.4f); // uv1 (4D)
        buf.putInt(0).putInt(0).putInt(0);       // triangle indices
        byte[] slotBytes = "mat".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buf.putInt(0);                           // polygonGroup
        buf.putInt(slotBytes.length);
        buf.put(slotBytes);
        buf.putInt(0);                           // firstIndex
        buf.putInt(3);                           // indexCount
        byte[] payloadBytes = new byte[buf.position()];
        buf.flip();
        buf.get(payloadBytes);

        var resource = new net.minecraft.server.packs.resources.Resource(
                null, () -> new java.io.ByteArrayInputStream(payloadBytes));
        var rm = (net.minecraft.server.packs.resources.ResourceManager) java.lang.reflect.Proxy.newProxyInstance(
                UnityRenderParityTest.class.getClassLoader(),
                new Class<?>[] { net.minecraft.server.packs.resources.ResourceManager.class },
                (proxy, method, args) -> "getResourceOrThrow".equals(method.getName()) ? resource : null);
        var mesh = com.kltyton.lumenfx.effect.asset.mesh.StaticMeshGeometryLoader.load(
                rm, Identifier.fromNamespaceAndPath("lumenfx", "test_v3.lfmesh"));
        assertTrue(mesh.wideTextureCoordinates());
        assertEquals(2, mesh.textureCoordinateWidth(0));
        assertEquals(4, mesh.textureCoordinateWidth(1));
        assertEquals(0.3f, mesh.textureCoordinate(1, 0, 2), EPS);
        assertEquals(0.4f, mesh.textureCoordinate(1, 0, 3), EPS);

        String bbJson = """
                {
                  "format_version": 1,
                  "materials": {
                    "mat": {
                      "material_view": 8,
                      "sg_uv4_mask": 2
                    }
                  },
                  "emitters": [
                    {
                      "name": "tilted_bb",
                      "renderer": "sprite",
                      "material": "mat",
                      "duration": 1,
                      "loop": false,
                      "max_particles": 2,
                      "rate": 0,
                      "bursts": [ { "time": 0, "min": 1, "max": 1 } ],
                      "lifetime": 1,
                      "width": 1,
                      "height": 1,
                      "unity_renderer": {
                        "billboard": {
                          "mode": "Billboard",
                          "alignment": "world",
                          "order": "none",
                          "start_rotation_xy": [ 1.5707963, 0.0 ],
                          "angular_velocity_xy": [ 0.0, 0.0 ]
                        }
                      }
                    }
                  ]
                }
                """;
        EffectDefinition def = parse(bbJson);
        assertEquals(8, def.materials().get("mat").materialView());
        assertEquals(2, def.materials().get("mat").sgUv4Mask());

        var bb = def.unityRenderers().get("tilted_bb").billboard();
        assertEquals(2, bb.startRotation().size());

        var handle = new EffectHandle(Vec3.ZERO);
        var instance = new EffectInstance(def, handle, 1L);
        List<EffectMesh> meshes = stepAndBuild(instance, handle, 0.1, new Vec3(0, 0, 20));
        assertEquals(1, meshes.size());
        float[] packed = meshes.get(0).values();
        // World alignment starts facing=+Z=(0,0,1), up=(0,1,0); rx=PI/2 tilts facing onto Y axis.
        assertTrue(Math.abs(packed[36]) < 0.01f && Math.abs(Math.abs(packed[35]) - 1.0f) < 0.01f,
                "Expected X-tilt rotation to tilt facing off +Z onto Y axis, got ("
                        + packed[34] + ", " + packed[35] + ", " + packed[36] + ")");
    }

    @Test
    void unitySortingGroupOrdersNestedRenderersByRootGroupAndHierarchyAndIgnoresGroupedDistanceBias() {
        String json = """
                {
                  "format_version": 1,
                  "materials": {
                    "m_back": { "fragment_shader": "lumenfx:core/color", "blend": "alpha" },
                    "m_mid":  { "fragment_shader": "lumenfx:core/color", "blend": "alpha" },
                    "m_front":{ "fragment_shader": "lumenfx:core/color", "blend": "alpha" },
                    "m_free": { "fragment_shader": "lumenfx:core/color", "blend": "alpha" }
                  },
                  "emitters": [
                    {
                      "name": "nested_front",
                      "renderer": "sprite",
                      "material": "m_front",
                      "local_space": true,
                      "duration": 2,
                      "loop": false,
                      "max_particles": 2,
                      "rate": 0,
                      "bursts": [ { "time": 0, "min": 1, "max": 1 } ],
                      "lifetime": 2,
                      "unity_renderer": {
                        "sorting": {
                          "layer": 0,
                          "order": 5,
                          "queue": 3000,
                          "distance_bias": 50.0,
                          "hierarchy_order": 3,
                          "groups": [
                            { "id": "root_g", "layer": 1, "order": 10, "position": [0, 0, 0], "hierarchy_order": 0 },
                            { "id": "sub_b",  "layer": 0, "order": 2,  "position": [0, 0, 0], "hierarchy_order": 2 }
                          ]
                        }
                      }
                    },
                    {
                      "name": "nested_back",
                      "renderer": "sprite",
                      "material": "m_back",
                      "local_space": true,
                      "duration": 2,
                      "loop": false,
                      "max_particles": 2,
                      "rate": 0,
                      "bursts": [ { "time": 0, "min": 1, "max": 1 } ],
                      "lifetime": 2,
                      "unity_renderer": {
                        "sorting": {
                          "layer": 0,
                          "order": 99,
                          "queue": 3000,
                          "distance_bias": -50.0,
                          "hierarchy_order": 1,
                          "groups": [
                            { "id": "root_g", "layer": 1, "order": 10, "position": [0, 0, 0], "hierarchy_order": 0 },
                            { "id": "sub_a",  "layer": 0, "order": 1,  "position": [0, 0, 0], "hierarchy_order": 1 }
                          ]
                        }
                      }
                    },
                    {
                      "name": "root_direct_mid",
                      "renderer": "sprite",
                      "material": "m_mid",
                      "local_space": true,
                      "duration": 2,
                      "loop": false,
                      "max_particles": 2,
                      "rate": 0,
                      "bursts": [ { "time": 0, "min": 1, "max": 1 } ],
                      "lifetime": 2,
                      "unity_renderer": {
                        "sorting": {
                          "layer": 0,
                          "order": 2,
                          "queue": 3000,
                          "distance_bias": 0.0,
                          "hierarchy_order": 1,
                          "groups": [
                            { "id": "root_g", "layer": 1, "order": 10, "position": [0, 0, 0], "hierarchy_order": 0 }
                          ]
                        }
                      }
                    },
                    {
                      "name": "ungrouped_higher_layer",
                      "renderer": "sprite",
                      "material": "m_free",
                      "local_space": true,
                      "duration": 2,
                      "loop": false,
                      "max_particles": 2,
                      "rate": 0,
                      "bursts": [ { "time": 0, "min": 1, "max": 1 } ],
                      "lifetime": 2,
                      "unity_renderer": {
                        "sorting": {
                          "layer": 2,
                          "order": 0,
                          "queue": 3000,
                          "distance_bias": 3.0,
                          "hierarchy_order": 0
                        }
                      }
                    }
                  ]
                }
                """;
        EffectDefinition def = parse(json);
        var groupedSort = def.unityRenderers().get("nested_front").sorting();
        var ungroupedSort = def.unityRenderers().get("ungrouped_higher_layer").sorting();
        assertTrue(groupedSort.grouped());
        assertEquals(25.0, groupedSort.applyDistanceBias(25.0), 1e-6,
                "Grouped renderers must ignore per-renderer distance_bias");
        assertEquals(64.0, ungroupedSort.applyDistanceBias(25.0), 1e-6,
                "Ungrouped renderers must apply signed distance_bias: (5 + 3)^2 = 64");

        var handle = new EffectHandle(Vec3.ZERO);
        var instance = new EffectInstance(def, handle, 303L);
        List<EffectMesh> built = stepAndBuild(instance, handle, 0.1, new Vec3(0, 0, 10));
        assertEquals(4, built.size());

        List<EffectMesh> sorted = EffectGpuRenderer.sortMeshesForDraw(built);
        // Expected draw order inside root_g (layer 1, order 10):
        //   1) nested_back (depth-1 group sub_a: layer 0, order 1)
        //   2) root_direct_mid (depth-1 leaf: layer 0, order 2, hierarchy 1)
        //   3) nested_front (depth-1 group sub_b: layer 0, order 2, hierarchy 2)
        //   4) ungrouped_higher_layer (layer 2 > root_g layer 1)
        assertEquals(def.materials().get("m_back"), sorted.get(0).material());
        assertEquals(def.materials().get("m_mid"), sorted.get(1).material());
        assertEquals(def.materials().get("m_front"), sorted.get(2).material());
        assertEquals(def.materials().get("m_free"), sorted.get(3).material());

        // Separate instances of the same grouped effect carry distinct unityInstanceId so groups never interleave.
        var secondHandle = new EffectHandle(Vec3.ZERO);
        var secondInstance = new EffectInstance(def, secondHandle, 304L);
        List<EffectMesh> secondBuilt = stepAndBuild(secondInstance, secondHandle, 0.1, new Vec3(0, 0, 10));
        assertTrue(built.get(0).unityInstanceId() != secondBuilt.get(0).unityInstanceId());
    }

    @Test
    void sameNameSiblingUnitySortingGroupsNeverInterleaveChildrenAtEqualDepthAndPriority() {
        // Two sibling Sorting Groups with the exact same name, position, layer, order, and queue:
        // Group A (hierarchy 2) has children with orders 1 and 10;
        // Group B (hierarchy 6) has children with orders 2 and 9.
        // Without disambiguating distinct sibling groups, child orders 1, 2, 9, 10 would interleave.
        // Test BOTH unique sibling-indexed IDs (new exporter) AND colliding legacy IDs with distinct hierarchy_order.
        for (String[] ids : new String[][] { { "Root/Group#0", "Root/Group#1" }, { "Root/Group", "Root/Group" } }) {
            String json = """
                    {
                      "format_version": 1,
                      "materials": {
                        "a_low":  { "fragment_shader": "lumenfx:core/color", "blend": "alpha" },
                        "a_high": { "fragment_shader": "lumenfx:core/color", "blend": "alpha" },
                        "b_low":  { "fragment_shader": "lumenfx:core/color", "blend": "alpha" },
                        "b_high": { "fragment_shader": "lumenfx:core/color", "blend": "alpha" }
                      },
                      "emitters": [
                        {
                          "name": "a1", "renderer": "sprite", "material": "a_low",
                          "local_space": true, "duration": 2, "loop": false, "max_particles": 2, "rate": 0,
                          "bursts": [ { "time": 0, "min": 1, "max": 1 } ], "lifetime": 2,
                          "unity_renderer": {
                            "sorting": {
                              "layer": 0, "order": 1, "queue": 3000, "hierarchy_order": 3,
                              "groups": [ { "id": "%s", "layer": 0, "order": 0, "position": [0, 0, 0], "hierarchy_order": 2 } ]
                            }
                          }
                        },
                        {
                          "name": "a2", "renderer": "sprite", "material": "a_high",
                          "local_space": true, "duration": 2, "loop": false, "max_particles": 2, "rate": 0,
                          "bursts": [ { "time": 0, "min": 1, "max": 1 } ], "lifetime": 2,
                          "unity_renderer": {
                            "sorting": {
                              "layer": 0, "order": 10, "queue": 3000, "hierarchy_order": 4,
                              "groups": [ { "id": "%s", "layer": 0, "order": 0, "position": [0, 0, 0], "hierarchy_order": 2 } ]
                            }
                          }
                        },
                        {
                          "name": "b1", "renderer": "sprite", "material": "b_low",
                          "local_space": true, "duration": 2, "loop": false, "max_particles": 2, "rate": 0,
                          "bursts": [ { "time": 0, "min": 1, "max": 1 } ], "lifetime": 2,
                          "unity_renderer": {
                            "sorting": {
                              "layer": 0, "order": 2, "queue": 3000, "hierarchy_order": 7,
                              "groups": [ { "id": "%s", "layer": 0, "order": 0, "position": [0, 0, 0], "hierarchy_order": 6 } ]
                            }
                          }
                        },
                        {
                          "name": "b2", "renderer": "sprite", "material": "b_high",
                          "local_space": true, "duration": 2, "loop": false, "max_particles": 2, "rate": 0,
                          "bursts": [ { "time": 0, "min": 1, "max": 1 } ], "lifetime": 2,
                          "unity_renderer": {
                            "sorting": {
                              "layer": 0, "order": 9, "queue": 3000, "hierarchy_order": 8,
                              "groups": [ { "id": "%s", "layer": 0, "order": 0, "position": [0, 0, 0], "hierarchy_order": 6 } ]
                            }
                          }
                        }
                      ]
                    }
                    """.formatted(ids[0], ids[0], ids[1], ids[1]);
            EffectDefinition def = parse(json);
            var handle = new EffectHandle(Vec3.ZERO);
            var instance = new EffectInstance(def, handle, 404L);
            List<EffectMesh> built = stepAndBuild(instance, handle, 0.1, new Vec3(0, 0, 10));
            assertEquals(4, built.size());
            List<EffectMesh> sorted = EffectGpuRenderer.sortMeshesForDraw(built);
            // All of Group A (a_low, a_high) must submit contiguously before Group B (b_low, b_high).
            assertEquals(def.materials().get("a_low"), sorted.get(0).material());
            assertEquals(def.materials().get("a_high"), sorted.get(1).material());
            assertEquals(def.materials().get("b_low"), sorted.get(2).material());
            assertEquals(def.materials().get("b_high"), sorted.get(3).material());
        }
    }

    @Test
    void flushesTransientMappedViewsBeforeGpuConsumptionInFallbackMode() throws Exception {
        // Under GlTransientMemory.Fallback (persistentMapping == false), MappedView.close() performs the
        // bufferSubData upload. Consuming the slice via copyToBuffer/createRenderPass before MappedView.close()
        // reads uninitialized/zeroed GPU memory (e.g., all-zero static mesh index buffers -> invisible meshes).
        String[][] methods = {
                { "com/kltyton/lumenfx/client/render/mesh/StaticMeshBuffers.class", "uploadIndices", "copyToBuffer" },
                { "com/kltyton/lumenfx/client/render/decal/DecalNormalHistory.class", "finish", "createRenderPass" },
                { "com/kltyton/lumenfx/client/render/decal/NativeDecalMaterialResolver.class", "resolve", "createRenderPass" },
                { "com/kltyton/lumenfx/client/render/hdr/external/HostSurfaceReconstructor.class", "reconstruct", "createRenderPass" },
                { "com/kltyton/lumenfx/client/render/light/rect/RectLightTextureAtlas.class", "draw", "createRenderPass" },
                { "com/kltyton/lumenfx/client/render/temporal/geometry/GeometryMotionRenderer.class", "drawPointCapture", "createRenderPass" },
                { "com/kltyton/lumenfx/client/render/temporal/geometry/GeometryMotionRenderer.class", "drawCoverage", "createRenderPass" }
        };
        for (String[] entry : methods) {
            try (var in = UnityRenderParityTest.class.getClassLoader().getResourceAsStream(entry[0])) {
                assertTrue(in != null, "Missing class bytecode for " + entry[0]);
                var reader = new org.objectweb.asm.ClassReader(in.readAllBytes());
                boolean[] verified = { false };
                reader.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    @Override
                    public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                        if (!name.equals(entry[1])) return null;
                        return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                            private int step;
                            private int allocStep = -1;
                            private int closeStep = -1;
                            private int consumeStep = -1;

                            @Override
                            public void visitMethodInsn(int opcode, String owner, String mName, String mDesc, boolean isInterface) {
                                step++;
                                if ("allocateGpuMapped".equals(mName) || "allocateStaging".equals(mName)) {
                                    allocStep = step;
                                } else if (allocStep > 0 && closeStep < 0 && "close".equals(mName)
                                        && owner.endsWith("GpuBufferSlice$MappedView")) {
                                    closeStep = step;
                                } else if (allocStep > 0 && consumeStep < 0 && entry[2].equals(mName)) {
                                    consumeStep = step;
                                }
                            }

                            @Override
                            public void visitEnd() {
                                if (allocStep > 0) {
                                    assertTrue(closeStep > allocStep && consumeStep > closeStep,
                                            entry[0] + "#" + entry[1] + " must close MappedView before " + entry[2]
                                                    + " (alloc=" + allocStep + ", close=" + closeStep + ", consume=" + consumeStep + ")");
                                    verified[0] = true;
                                }
                            }
                        };
                    }
                }, 0);
                assertTrue(verified[0], "Failed to verify transient flush order in " + entry[0] + "#" + entry[1]);
            }
        }
    }
}
