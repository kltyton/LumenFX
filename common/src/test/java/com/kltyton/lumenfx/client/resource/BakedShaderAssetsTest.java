package com.kltyton.lumenfx.client.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public final class BakedShaderAssetsTest {
    private static final List<String> EXPECTED_BAKED_PIPELINES = List.of(
            "niagara_async_trace",
            "niagara_bitonic_sort",
            "niagara_draw_indirect",
            "niagara_gaussian_splat",
            "niagara_groom_cache",
            "niagara_mesh_scene",
            "niagara_nanite_scene_v3",
            "niagara_neighbor_query",
            "niagara_persistent_ids",
            "niagara_physics_field",
            "niagara_radix_sort",
            "niagara_render_target_mips",
            "niagara_ribbon_aggregation",
            "niagara_ribbon_indices",
            "niagara_ribbon_sort",
            "niagara_sort_key",
            "niagara_stateless",
            "runtime_virtual_texture",
            "shading_energy",
            "texture_target_mips"
    );

    @Test
    void allTwentyBakedComputePipelinesHaveValidIndicesProgramsAndSpirvBinaries() throws Exception {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        for (String pipeline : EXPECTED_BAKED_PIPELINES) {
            String indexPath = "/assets/lumenfx/shaders/compute/" + pipeline + "/index.json";
            JsonObject index = readJsonResource(indexPath);
            assertEquals("lumenfx.shaders.index.v1", index.get("schema").getAsString(), indexPath);
            assertEquals(pipeline, index.get("pipeline").getAsString(), indexPath);
            assertEquals("2f66b3f91297f7ad9261f18a70c836fa5472d645", index.get("engineHead").getAsString(), indexPath);

            JsonArray programs = index.getAsJsonArray("programs");
            assertNotNull(programs, indexPath);
            assertFalse(programs.isEmpty(), "No programs in " + indexPath);

            for (JsonElement programElement : programs) {
                JsonObject program = programElement.getAsJsonObject();
                String programRelPath = program.get("path").getAsString();
                String programFullPath = "/assets/lumenfx/shaders/compute/" + programRelPath;
                assertNotNull(readJsonResource(programFullPath), "Missing program descriptor " + programFullPath);

                int lastSlash = programFullPath.lastIndexOf('/');
                String programDir = programFullPath.substring(0, lastSlash + 1);
                JsonObject artifacts = program.getAsJsonObject("artifacts");
                for (String stage : artifacts.keySet()) {
                    JsonObject stageObj = artifacts.getAsJsonObject(stage);
                    for (String backend : List.of("vulkan", "opengl")) {
                        if (!stageObj.has(backend)) {
                            continue;
                        }
                        JsonObject artifact = stageObj.getAsJsonObject(backend);
                        String relFile = artifact.get("path").getAsString();
                        int expectedSize = artifact.get("size").getAsInt();
                        String expectedSha = artifact.get("sha256").getAsString();
                        byte[] bytes = readBytesResource(programDir + relFile);
                        assertEquals(expectedSize, bytes.length, programDir + relFile);
                        String actualSha = HexFormat.of().formatHex(sha256.digest(bytes));
                        assertEquals(expectedSha, actualSha, programDir + relFile);
                        if (relFile.endsWith(".spv")) {
                            assertTrue(bytes.length >= 20, "Truncated SPIR-V: " + programDir + relFile);
                            assertEquals((byte) 0x03, bytes[0], programDir + relFile);
                            assertEquals((byte) 0x02, bytes[1], programDir + relFile);
                            assertEquals((byte) 0x23, bytes[2], programDir + relFile);
                            assertEquals((byte) 0x07, bytes[3], programDir + relFile);
                        }
                    }
                }
            }
        }
    }

    @Test
    void niagaraSplatShadingPublishesOnlyUsedViewFieldsAndReadOnlyInput() throws IOException {
        JsonObject program = readJsonResource("/assets/lumenfx/shaders/compute/niagara_gaussian_splat/shading/sh0/program.json");
        JsonObject splats = program.getAsJsonArray("bindings").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(binding -> binding.get("name").getAsString().equals("GaussianSplatBuffer"))
                .findFirst().orElseThrow();
        assertEquals("READ_ONLY", splats.get("access").getAsString());

        JsonObject stage = program.getAsJsonArray("stages").get(0).getAsJsonObject();
        JsonObject view = stage.getAsJsonObject("reflection").getAsJsonArray("uniformBuffers").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(buffer -> buffer.get("name").getAsString().equals("ViewUniformBuffer"))
                .findFirst().orElseThrow();
        List<String> fields = view.getAsJsonArray("members").asList().stream().map(JsonElement::getAsJsonObject)
                .map(member -> member.get("name").getAsString()).toList();
        assertTrue(fields.containsAll(List.of("View_TranslatedWorldToClip", "View_ViewSizeAndInvSize")));
        assertFalse(fields.contains("View_SVPositionToTranslatedWorld"));
    }

    @Test
    void irisMixinConfigAndModMetadataDeclareCompositeMixinsAndOptionalCompatDependencies() throws IOException {
        JsonObject mixinConfig = readJsonResource("/lumenfx.iris.mixins.json");
        JsonArray clientMixins = mixinConfig.getAsJsonArray("client");
        List<String> mixinNames = clientMixins.asList().stream().map(JsonElement::getAsString).toList();
        assertTrue(mixinNames.contains("CompositePassAccessor"), "Missing CompositePassAccessor in lumenfx.iris.mixins.json");
        assertTrue(mixinNames.contains("CompositeRendererMixin"), "Missing CompositeRendererMixin in lumenfx.iris.mixins.json");

        JsonObject sodiumMixins = readJsonResource("/lumenfx.sodium.mixins.json");
        assertEquals("com.kltyton.lumenfx.client.compat.sodium.SodiumMixinConfigPlugin",
                sodiumMixins.get("plugin").getAsString());
        List<String> sodiumClientMixins = sodiumMixins.getAsJsonArray("client").asList().stream()
                .map(JsonElement::getAsString).toList();
        assertTrue(sodiumClientMixins.contains("MotionVertexConsumerMixin"));
        assertTrue(sodiumClientMixins.contains("KbrBillboardSodiumCompatMixin"));

        List<String> declaredMixinConfigs = new java.util.ArrayList<>();
        if (BakedShaderAssetsTest.class.getResource("/META-INF/neoforge.mods.toml") != null) {
            String modsToml = new String(readBytesResource("/META-INF/neoforge.mods.toml"), StandardCharsets.UTF_8);
            assertDependency(modsToml, "iris", "optional", "[1.11.4,)");
            assertDependency(modsToml, "sodium", "optional", "[0.9.2,)");
            assertFalse(modsToml.contains("modId=\"ldlib2\""), "ldlib2 must not be declared as a dependency");
            assertFalse(modsToml.contains("modId=\"kilagraph\""), "kilagraph must not be declared as a required dependency");
            int search = 0;
            while ((search = modsToml.indexOf("config=\"", search)) >= 0) {
                int start = search + "config=\"".length();
                int end = modsToml.indexOf('"', start);
                declaredMixinConfigs.add(modsToml.substring(start, end));
                search = end + 1;
            }
            assertEquals(16, declaredMixinConfigs.size(), "Expected all 16 LumenFX mixin configs in neoforge.mods.toml");
            assertTrue(declaredMixinConfigs.contains("lumenfx.keyframe.mixins.json"));
        } else {
            JsonObject fabricMod = readJsonResource("/fabric.mod.json");
            JsonObject suggests = fabricMod.getAsJsonObject("suggests");
            assertNotNull(suggests, "fabric.mod.json must declare optional iris/sodium suggests");
            assertEquals(">=1.11.4", suggests.get("iris").getAsString());
            assertEquals(">=0.9.2", suggests.get("sodium").getAsString());
            for (JsonElement entry : fabricMod.getAsJsonArray("mixins")) {
                declaredMixinConfigs.add(entry.getAsString());
            }
            assertEquals(16, declaredMixinConfigs.size(), "Expected all 16 LumenFX mixin configs in fabric.mod.json");
            assertTrue(declaredMixinConfigs.contains("lumenfx.keyframe.mixins.json"));
        }
        // Verify embedded LDLib2 runtime utilities are relocated away from com.lowdragmc.lowdraglib2.* inside LumenFX's own classes
        var relocatedPlatform = BakedShaderAssetsTest.class.getResource("/com/kltyton/lumenfx/internal/photon/core/Platform.class");
        assertNotNull(relocatedPlatform, "Relocated Photon runtime core Platform class must exist");
        var unrelocatedPlatform = BakedShaderAssetsTest.class.getResource("/com/lowdragmc/lowdraglib2/Platform.class");
        if (unrelocatedPlatform != null) {
            // Only external compileOnly LDLib2 jars may supply com.lowdragmc.lowdraglib2.*, never LumenFX's own output directory.
            assertFalse(unrelocatedPlatform.toString().contains("/common/build/"),
                    "Unrelocated com.lowdragmc.lowdraglib2.* classes must not be built by LumenFX: " + unrelocatedPlatform);
        }
        // Verify keyframe mixin config declares pure bytecode-probing plugin
        JsonObject keyframeMixins = readJsonResource("/lumenfx.keyframe.mixins.json");
        assertEquals("com.kltyton.lumenfx.client.keyframe.LumenFxKeyframeMixinPlugin",
                keyframeMixins.get("plugin").getAsString());
        // Every declared mixin config must exist and reference real compiled classes.
        for (String configName : declaredMixinConfigs) {
            JsonObject config = readJsonResource("/" + configName);
            String pkg = config.get("package").getAsString();
            for (String section : List.of("mixins", "client", "server")) {
                if (!config.has(section)) continue;
                for (JsonElement entry : config.getAsJsonArray(section)) {
                    String fqcn = pkg + "." + entry.getAsString();
                    String classResource = "/" + fqcn.replace('.', '/') + ".class";
                    assertNotNull(BakedShaderAssetsTest.class.getResource(classResource),
                            "Missing mixin class " + fqcn + " declared in " + configName);
                }
            }
        }

        // Verify dynamic GraphicsBackendPolicy for default Vulkan preference, fallback paths, and explicit overrides.
        var vkDefault = com.kltyton.lumenfx.client.graphics.GraphicsBackendPolicy.decide(
                net.minecraft.client.PreferredGraphicsApi.DEFAULT, true, null, null);
        assertTrue(vkDefault.prefersVulkan(), "Default policy must dynamically select Vulkan when supported");

        var vkNoHardware = com.kltyton.lumenfx.client.graphics.GraphicsBackendPolicy.decide(
                net.minecraft.client.PreferredGraphicsApi.DEFAULT, false, null, "no vulkan driver");
        assertFalse(vkNoHardware.prefersVulkan(), "Default policy must automatically use OpenGL when Vulkan is unavailable");

        var vkOpenGlMod = com.kltyton.lumenfx.client.graphics.GraphicsBackendPolicy.decide(
                net.minecraft.client.PreferredGraphicsApi.DEFAULT, true, "iris 1.11.4+mc26.2", null);
        assertFalse(vkOpenGlMod.prefersVulkan(), "Default policy must automatically keep OpenGL when an OpenGL-only render mod is installed");

        var explicitGl = com.kltyton.lumenfx.client.graphics.GraphicsBackendPolicy.decide(
                net.minecraft.client.PreferredGraphicsApi.OPENGL, true, null, null);
        assertFalse(explicitGl.prefersVulkan(), "Explicit OpenGL preference must always stay OpenGL");

        var explicitVk = com.kltyton.lumenfx.client.graphics.GraphicsBackendPolicy.decide(
                net.minecraft.client.PreferredGraphicsApi.VULKAN, false, "iris 1.11.4+mc26.2", "test");
        assertTrue(explicitVk.prefersVulkan(), "Explicit Vulkan preference must be preserved");

        assertTrue(com.kltyton.lumenfx.client.compat.shaderpack.ShaderpackCompat.isSupportedIrisVersion("1.11.4+mc26.2"));
        assertTrue(com.kltyton.lumenfx.client.compat.shaderpack.ShaderpackCompat.isSupportedIrisVersion("1.11.5+mc26.2"));
        assertTrue(com.kltyton.lumenfx.client.compat.shaderpack.ShaderpackCompat.isSupportedIrisVersion("1.12.0"));
        assertFalse(com.kltyton.lumenfx.client.compat.shaderpack.ShaderpackCompat.isSupportedIrisVersion("1.11.3+mc26.2"));
        assertFalse(com.kltyton.lumenfx.client.compat.shaderpack.ShaderpackCompat.isIrisPresent());
    }

    @Test
    void allPackagedGlslEntryShadersStartWithVersionDirectiveAndVulkanPatchStateIsWellBehaved() throws IOException {
        for (java.nio.file.Path root : List.of(java.nio.file.Path.of("common/src/main/resources"))) {
            if (!java.nio.file.Files.isDirectory(root)) continue;
            try (var stream = java.nio.file.Files.walk(root)) {
                List<java.nio.file.Path> shaders = stream
                        .filter(java.nio.file.Files::isRegularFile)
                        .filter(path -> {
                            String name = path.getFileName().toString();
                            return name.endsWith(".vsh") || name.endsWith(".fsh");
                        })
                        .toList();
                assertFalse(shaders.isEmpty(), "Expected packaged GLSL shaders under " + root);
                for (java.nio.file.Path shader : shaders) {
                    String content = java.nio.file.Files.readString(shader);
                    assertFalse(content.startsWith("\uFEFF"), "Shader must not start with UTF-8 BOM: " + shader);
                    String firstNonEmpty = content.lines()
                            .map(String::trim)
                            .filter(line -> !line.isEmpty())
                            .findFirst()
                            .orElse("");
                    assertTrue(firstNonEmpty.startsWith("#version "),
                            "Vulkan glslang requires #version as the first directive in " + shader + ", found: " + firstNonEmpty);
                }
            }
        }

        // Verify VulkanShaderPatchState nested preservation and SpvDim3D normalization.
        assertFalse(com.kltyton.lumenfx.client.graphics.VulkanShaderPatchState.preservingFragmentOutputLocations());
        boolean outer = com.kltyton.lumenfx.client.graphics.VulkanShaderPatchState.enterFragmentOutputPreservation();
        assertFalse(outer);
        assertTrue(com.kltyton.lumenfx.client.graphics.VulkanShaderPatchState.preservingFragmentOutputLocations());
        boolean inner = com.kltyton.lumenfx.client.graphics.VulkanShaderPatchState.enterFragmentOutputPreservation();
        assertTrue(inner);
        com.kltyton.lumenfx.client.graphics.VulkanShaderPatchState.exitFragmentOutputPreservation(inner);
        assertTrue(com.kltyton.lumenfx.client.graphics.VulkanShaderPatchState.preservingFragmentOutputLocations());
        com.kltyton.lumenfx.client.graphics.VulkanShaderPatchState.exitFragmentOutputPreservation(outer);
        assertFalse(com.kltyton.lumenfx.client.graphics.VulkanShaderPatchState.preservingFragmentOutputLocations());

        assertEquals(com.kltyton.lumenfx.client.graphics.VulkanShaderPatchState.SPV_DIM_2D,
                com.kltyton.lumenfx.client.graphics.VulkanShaderPatchState.normalizeSampledImageDimension(
                        com.kltyton.lumenfx.client.graphics.VulkanShaderPatchState.SPV_DIM_3D));
        assertEquals(com.kltyton.lumenfx.client.graphics.VulkanShaderPatchState.SPV_DIM_2D,
                com.kltyton.lumenfx.client.graphics.VulkanShaderPatchState.normalizeSampledImageDimension(
                        com.kltyton.lumenfx.client.graphics.VulkanShaderPatchState.SPV_DIM_2D));
        assertEquals(com.kltyton.lumenfx.client.graphics.VulkanShaderPatchState.SPV_DIM_CUBE,
                com.kltyton.lumenfx.client.graphics.VulkanShaderPatchState.normalizeSampledImageDimension(
                        com.kltyton.lumenfx.client.graphics.VulkanShaderPatchState.SPV_DIM_CUBE));
        assertEquals(com.kltyton.lumenfx.client.graphics.VulkanShaderPatchState.SPV_DIM_BUFFER,
                com.kltyton.lumenfx.client.graphics.VulkanShaderPatchState.normalizeSampledImageDimension(
                        com.kltyton.lumenfx.client.graphics.VulkanShaderPatchState.SPV_DIM_BUFFER));
        assertEquals(0, com.kltyton.lumenfx.client.graphics.VulkanShaderPatchState.normalizeSampledImageDimension(0));
    }

    @Test
    void kbrSodiumBillboardBridgePreservesRectangularHeightAndCustomBasisAndFailsSafeOnMismatch() {
        var binding = com.kltyton.lumenfx.client.compat.kbr.KbrSodiumBillboardBridge.bind(SyntheticKbrDimensions.class);
        assertTrue(binding.available());

        // Counterexample 1: inactive batch leaves square corner untouched.
        SyntheticKbrDimensions.active = false;
        assertFalse(binding.isActive());

        // Case 2: rectangular billboard (width=1.0, height=2.5) scales only Y while keeping X at width.
        SyntheticKbrDimensions.active = true;
        SyntheticKbrDimensions.selectedHeight = 2.5f;
        SyntheticKbrDimensions.oriented = false;
        float width = binding.selectNext(1.0f);
        var identity = new org.joml.Quaternionf();
        var rectCorner = com.kltyton.lumenfx.client.compat.kbr.KbrSodiumBillboardBridge.transformCorner(
                binding, new org.joml.Vector3f(), identity, 10.0f, 20.0f, 30.0f, 1.0f, 1.0f, width);
        assertEquals(11.0f, rectCorner.x, 1e-5f);
        assertEquals(22.5f, rectCorner.y, 1e-5f);
        assertEquals(30.0f, rectCorner.z, 1e-5f);

        // Case 3: oriented custom basis maps horizontal onto +Z and vertical onto +X (width=2.0, height=3.0).
        SyntheticKbrDimensions.selectedHeight = 3.0f;
        SyntheticKbrDimensions.oriented = true;
        var orientedCorner = com.kltyton.lumenfx.client.compat.kbr.KbrSodiumBillboardBridge.transformCorner(
                binding, new org.joml.Vector3f(), identity, 0.0f, 0.0f, 0.0f, 1.0f, -1.0f, binding.selectNext(2.0f));
        assertEquals(-3.0f, orientedCorner.x, 1e-5f);
        assertEquals(0.0f, orientedCorner.y, 1e-5f);
        assertEquals(2.0f, orientedCorner.z, 1e-5f);

        // Counterexample 4: unrelated class without BillboardQuadDimensions methods degrades safely to unavailable.
        var incompatible = com.kltyton.lumenfx.client.compat.kbr.KbrSodiumBillboardBridge.bind(String.class);
        assertFalse(incompatible.available());
        assertFalse(incompatible.isActive());
    }

    @Test
    void reloadAllowsDistinctExplicitIndirectLightingEffectsAlongsideUnrelatedEffect() {
        String effectAJson = """
                {
                  "format_version": 1,
                  "render": {
                    "hdr": true,
                    "indirect_lighting": { "precomputed_color_scale": [1.0, 0.5, 0.25] }
                  },
                  "materials": { "mat_a": { "blend": "alpha" } },
                  "emitters": [ { "name": "e", "renderer": "sprite", "material": "mat_a", "rate": 0, "lifetime": 1 } ]
                }
                """;
        String effectBJson = """
                {
                  "format_version": 1,
                  "render": {
                    "hdr": true,
                    "indirect_lighting": { "precomputed_color_scale": [0.25, 0.75, 1.0] }
                  },
                  "materials": { "mat_b": { "blend": "alpha" } },
                  "emitters": [ { "name": "e", "renderer": "sprite", "material": "mat_b", "rate": 0, "lifetime": 1 } ]
                }
                """;
        String unrelatedJson = """
                {
                  "format_version": 1,
                  "materials": { "mat_c": { "blend": "alpha" } },
                  "emitters": [ { "name": "e", "renderer": "sprite", "material": "mat_c", "rate": 0, "lifetime": 1 } ]
                }
                """;
        var effectA = EffectJson.read(JsonParser.parseString(effectAJson).getAsJsonObject());
        var effectB = EffectJson.read(JsonParser.parseString(effectBJson).getAsJsonObject());
        var unrelated = EffectJson.read(JsonParser.parseString(unrelatedJson).getAsJsonObject());

        var plan = com.kltyton.lumenfx.client.VfxEngine.resolveParticleViews(Map.of(
                com.kltyton.lumenfx.LumenFx.id("indirect_a"), effectA,
                com.kltyton.lumenfx.LumenFx.id("indirect_b"), effectB,
                com.kltyton.lumenfx.LumenFx.id("unrelated"), unrelated));
        assertTrue(plan.particleViews().containsKey(effectA));
        assertTrue(plan.particleViews().containsKey(effectB));
        assertFalse(plan.particleViews().containsKey(unrelated));
        assertEquals(new net.minecraft.world.phys.Vec3(1.0, 0.5, 0.25),
                effectA.materials().get("mat_a").renderSettings().indirectLighting().precomputedColorScale());
        assertEquals(new net.minecraft.world.phys.Vec3(0.25, 0.75, 1.0),
                effectB.materials().get("mat_b").renderSettings().indirectLighting().precomputedColorScale());
        assertEquals(com.kltyton.lumenfx.effect.render.light.scene.SceneIndirectLighting.NONE,
                unrelated.materials().get("mat_c").renderSettings().indirectLighting());
    }

    @Test
    void photonFloatingOriginPreservesSubBlockPrecisionAtFarWorldCoordinates() {
        double farBase = 16_777_216.0;
        var pos1 = new net.minecraft.world.phys.Vec3(farBase + 0.25, 64.0, -farBase + 0.125);
        var pos2 = new net.minecraft.world.phys.Vec3(farBase + 0.75, 64.0, -farBase + 0.625);
        // Verify raw float cast WOULD have collapsed pos1.x and pos2.x to the same float:
        assertEquals((float) pos1.x, (float) pos2.x, "Sanity: raw float cast loses 0.5-block precision at 2^24");

        var origin1 = com.lowdragmc.photon.client.fx.FXRuntime.snapWorldOrigin(pos1);
        var origin2 = com.lowdragmc.photon.client.fx.FXRuntime.snapWorldOrigin(pos2);
        var localRoot1 = com.lowdragmc.photon.client.fx.FXRuntime.toLocal(pos1, origin1);
        var localRoot2 = com.lowdragmc.photon.client.fx.FXRuntime.toLocal(pos2, origin2);
        var worldRoot1 = com.lowdragmc.photon.client.fx.FXRuntime.toWorld(localRoot1, origin1);
        var worldRoot2 = com.lowdragmc.photon.client.fx.FXRuntime.toWorld(localRoot2, origin2);
        assertEquals(0.5, worldRoot2.x - worldRoot1.x, 1e-6);
        assertEquals(0.5, worldRoot2.z - worldRoot1.z, 1e-6);

        var end1 = new net.minecraft.world.phys.Vec3(farBase + 3.25, 65.5, -farBase + 2.125);
        var end2 = new net.minecraft.world.phys.Vec3(farBase + 3.75, 65.5, -farBase + 2.625);
        var localEnd1 = com.lowdragmc.photon.client.fx.FXRuntime.toLocal(end1, origin1);
        var localEnd2 = com.lowdragmc.photon.client.fx.FXRuntime.toLocal(end2, origin2);
        var worldEnd1 = com.lowdragmc.photon.client.fx.FXRuntime.toWorld(localEnd1, origin1);
        var worldEnd2 = com.lowdragmc.photon.client.fx.FXRuntime.toWorld(localEnd2, origin2);
        assertEquals(0.5, worldEnd2.x - worldEnd1.x, 1e-6);
        assertEquals(0.5, worldEnd2.z - worldEnd1.z, 1e-6);

        // Shifted BlockBoxSource translates world collision boxes into FX-origin space in double precision.
        com.lowdragmc.photon.client.collision.BlockBoxSource worldBlocks = (minX, minY, minZ, maxX, maxY, maxZ, mask, filter, out) -> {
            assertEquals(farBase + 0.0, minX, 1e-6);
            out.add(farBase + 0.25, 64.0, -farBase + 0.125, farBase + 0.75, 65.0, -farBase + 0.625,
                    com.lowdragmc.photon.client.collision.BoxBuffer.BLOCK, 1L, "stone");
            return true;
        };
        var shifted = com.lowdragmc.photon.client.collision.BlockBoxSource.shifted(worldBlocks, origin1.x, origin1.y, origin1.z);
        var boxes = new com.lowdragmc.photon.client.collision.BoxBuffer();
        assertTrue(shifted.collect(0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1, null, boxes));
        assertEquals(1, boxes.size());
        assertEquals(0.25, boxes.minX(0), 1e-6);
        assertEquals(0.75, boxes.maxX(0), 1e-6);
    }

    @Test
    void irisFinalizeMixinInjectsOpaqueBeforePreTranslucentDepthCopyAndDeferredPass() throws Exception {
        byte[] bytes = readBytesResource("/com/kltyton/lumenfx/client/compat/iris/mixin/IrisFinalizeMixin.class");
        var node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
        org.objectweb.asm.tree.MethodNode opaqueMethod = null;
        for (var m : node.methods) {
            if ("lumenfx$opaqueScene".equals(m.name)) {
                opaqueMethod = m;
                break;
            }
        }
        assertNotNull(opaqueMethod, "IrisFinalizeMixin must define lumenfx$opaqueScene");
        org.objectweb.asm.tree.AnnotationNode inject = null;
        for (var list : List.of(
                opaqueMethod.visibleAnnotations != null ? opaqueMethod.visibleAnnotations : List.<org.objectweb.asm.tree.AnnotationNode>of(),
                opaqueMethod.invisibleAnnotations != null ? opaqueMethod.invisibleAnnotations : List.<org.objectweb.asm.tree.AnnotationNode>of())) {
            for (var ann : list) {
                if ("Lorg/spongepowered/asm/mixin/injection/Inject;".equals(ann.desc)) {
                    inject = ann;
                    break;
                }
            }
        }
        assertNotNull(inject, "IrisFinalizeMixin.lumenfx$opaqueScene must carry @Inject");
        Map<String, Object> injectValues = annotationValues(inject);
        assertEquals(List.of("beginTranslucents"), injectValues.get("method"));
        @SuppressWarnings("unchecked")
        var atList = (List<org.objectweb.asm.tree.AnnotationNode>) injectValues.get("at");
        assertNotNull(atList);
        Map<String, Object> atValues = annotationValues(atList.getFirst());
        assertEquals("INVOKE", atValues.get("value"));
        assertEquals("Lnet/irisshaders/iris/targets/RenderTargets;copyPreTranslucentDepth()V", atValues.get("target"));
        String[] shift = (String[]) atValues.get("shift");
        assertNotNull(shift);
        assertEquals("BEFORE", shift[1]);

        // Also verify ServerColliderForceAuthority.Key includes a unique per-authorization instanceId.
        Class<?> keyClass = Class.forName("com.kltyton.lumenfx.common.force.ServerColliderForceAuthority$Key");
        assertEquals(3, keyClass.getRecordComponents().length,
                "ServerColliderForceAuthority.Key must include monotonic instanceId alongside (effect, seed)");
        assertEquals("instanceId", keyClass.getRecordComponents()[0].getName());
    }

    @Test
    void activeIrisPostOpaqueDepthAndFourGpuInterfacesAndPerBatchOpaqueIndirectVariantContract() throws Exception {
        // Issue 9: post-opaque GPU deferral applies whenever composing to the linear world target (native OR active Iris).
        var supportsPostOpaque = com.kltyton.lumenfx.client.VfxEngine.class
                .getDeclaredMethod("supportsPostOpaqueDepth", boolean.class, boolean.class);
        supportsPostOpaque.setAccessible(true);
        assertEquals(Boolean.TRUE, supportsPostOpaque.invoke(null, true, true));
        assertEquals(Boolean.FALSE, supportsPostOpaque.invoke(null, false, true));
        assertEquals(Boolean.FALSE, supportsPostOpaque.invoke(null, true, false));

        List<String> order = new java.util.ArrayList<>();
        com.kltyton.lumenfx.client.render.depth.OpaqueParticleDepthPass.draw(
                List.of("primary_opaque", "reads_partial"),
                true,
                "reads_partial"::equals,
                false,
                (batches, secondary, last) -> order.add((secondary ? "secondary:" : "primary:") + String.join(",", batches)),
                () -> order.add("capturePartialDepth"));
        assertEquals(List.of("primary:primary_opaque", "capturePartialDepth", "secondary:reads_partial"), order);

        // Issue 13: all 4 dedicated GPU data interfaces pass constructor validation by class name and metadata field.
        for (var entry : Map.of(
                "NiagaraDataInterfaceSimCacheReader", "sim_cache_reader",
                "NiagaraDataInterfaceMemoryBuffer", "memory_buffer",
                "NiagaraDataInterfaceDynamicMesh", "dynamic_mesh",
                "NiagaraDataInterfaceSparseVolumeTexture", "sparse_volume_texture").entrySet()) {
            JsonObject byType = new JsonObject();
            assertTrue(com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuInterfaces
                    .supportsRuntimeInterface(entry.getKey(), byType),
                    "GPU interface must be accepted by class name: " + entry.getKey());
            JsonObject byField = new JsonObject();
            byField.add(entry.getValue(), new JsonObject());
            assertTrue(com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuInterfaces
                    .supportsRuntimeInterface("CustomInterfaceAlias", byField),
                    "GPU interface must be accepted by metadata field: " + entry.getValue());
        }
        assertFalse(com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuInterfaces
                .supportsRuntimeInterface("NiagaraDataInterfaceUnknownTestStub", new JsonObject()));

        // Issue 12: per-batch opaque indirect layout resolves strictly from the batch's own sample (never leaks lightmap or frame sample).
        var opaqueEffect = EffectJson.read(JsonParser.parseString("""
                {
                  "format_version": 1,
                  "materials": { "mat_lit": { "blend": "opaque", "default_lit_surface": true } },
                  "emitters": [ { "name": "e", "renderer": "sprite", "material": "mat_lit", "rate": 0, "lifetime": 1 } ]
                }
                """).getAsJsonObject());
        var baseMat = opaqueEffect.materials().get("mat_lit");
        var lightmap = new com.kltyton.lumenfx.effect.render.light.scene.VolumetricLightmap(
                com.kltyton.lumenfx.LumenFx.id("test_lightmap"),
                new net.minecraft.world.phys.Vec3(0, 0, 0),
                new net.minecraft.world.phys.Vec3(100, 100, 100),
                1, false, false);
        var lightmapSample = new com.kltyton.lumenfx.client.render.light.scene.SceneIndirectDiffuse.Sample(null, lightmap, null);
        var skyOnlySample = new com.kltyton.lumenfx.client.render.light.scene.SceneIndirectDiffuse.Sample(null, null, null);

        var vLightmap = com.kltyton.lumenfx.client.render.scene.surface.OpaqueParticleSurfaces.resolveIndirectVariant(baseMat, lightmapSample);
        var vSkyOnly = com.kltyton.lumenfx.client.render.scene.surface.OpaqueParticleSurfaces.resolveIndirectVariant(baseMat, skyOnlySample);
        var vNone = com.kltyton.lumenfx.client.render.scene.surface.OpaqueParticleSurfaces.resolveIndirectVariant(baseMat, null);
        assertTrue(vLightmap.indirectDiffuse() && vLightmap.volumetricLightmap());
        assertTrue(vSkyOnly.indirectDiffuse() && !vSkyOnly.volumetricLightmap());
        assertFalse(vNone.indirectDiffuse() || vNone.volumetricLightmap());
    }

    @Test
    void photonCpuSortDistanceMatchesOriginSpaceAndMeshBoundsAndMotionOffsetContract() throws Exception {
        // Issue 15: Photon CPU bakeJob must compute camera-to-emitter distance in the emitter's worldOrigin space.
        net.minecraft.world.phys.Vec3 camPos = new net.minecraft.world.phys.Vec3(1_000_000.0, 64.0, 0.0);
        net.minecraft.world.phys.Vec3 worldA = new net.minecraft.world.phys.Vec3(1_000_010.75, 64.0, 0.0);
        net.minecraft.world.phys.Vec3 worldB = new net.minecraft.world.phys.Vec3(1_000_005.25, 64.0, 0.0);
        var originA = com.lowdragmc.photon.client.fx.FXRuntime.snapWorldOrigin(worldA);
        var originB = com.lowdragmc.photon.client.fx.FXRuntime.snapWorldOrigin(worldB);
        var localA = com.lowdragmc.photon.client.fx.FXRuntime.toLocal(worldA, originA);
        var localB = com.lowdragmc.photon.client.fx.FXRuntime.toLocal(worldB, originB);
        var eyeA = new org.joml.Vector3f((float) (camPos.x - originA.x), (float) (camPos.y - originA.y), (float) (camPos.z - originA.z));
        var eyeB = new org.joml.Vector3f((float) (camPos.x - originB.x), (float) (camPos.y - originB.y), (float) (camPos.z - originB.z));
        float distSqA = localA.distanceSquared(eyeA);
        float distSqB = localB.distanceSquared(eyeB);
        assertEquals(10.75f * 10.75f, distSqA, 1e-2f);
        assertEquals(5.25f * 5.25f, distSqB, 1e-2f);
        assertTrue(distSqA > distSqB, "Farther emitter A must sort before closer emitter B in far-to-near order");

        // Verify Emitter.bakeJob bytecode invokes PhotonCameraUtils.facingEye(Camera, Vec3) rather than 1-arg facingEye(Camera).
        byte[] emitterBytes = readBytesResource("/com/lowdragmc/photon/client/gameobject/emitter/Emitter.class");
        var emitterClass = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(emitterBytes).accept(emitterClass, 0);
        boolean foundOriginFacingEye = false;
        for (var method : emitterClass.methods) {
            if (!"bakeJob".equals(method.name)) continue;
            for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call
                        && call.owner.equals("com/lowdragmc/photon/client/render/PhotonCameraUtils")
                        && call.name.equals("facingEye")) {
                    assertEquals("(Lnet/minecraft/client/Camera;Lnet/minecraft/world/phys/Vec3;)Lorg/joml/Vector3f;",
                            call.desc, "Emitter.bakeJob must use worldOrigin-relative facingEye");
                    foundOriginFacingEye = true;
                }
            }
        }
        assertTrue(foundOriginFacingEye, "Emitter.bakeJob must invoke PhotonCameraUtils.facingEye(Camera, Vec3)");

        // Issue 17: Niagara Mesh asymmetric off-center bounds convert accurately to min/max local bounds.
        var meshBounds = new com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset.Bounds(
                new net.minecraft.world.phys.Vec3(12.0, -4.0, 7.5),
                new net.minecraft.world.phys.Vec3(20.0, 6.0, 12.5),
                25.0f);
        var localBounds = com.kltyton.lumenfx.client.render.scene.material.MaterialPrimitiveUniforms.toNiagaraBounds(meshBounds);
        assertEquals(-8.0, localBounds.minX(), 1e-6);
        assertEquals(-10.0, localBounds.minY(), 1e-6);
        assertEquals(-5.0, localBounds.minZ(), 1e-6);
        assertEquals(32.0, localBounds.maxX(), 1e-6);
        assertEquals(2.0, localBounds.maxY(), 1e-6);
        assertEquals(20.0, localBounds.maxZ(), 1e-6);

        // Issue 17: NaniteMaterialFrameBindings must pass StaticMeshAsset.extendedBounds() via 3-arg MaterialPrimitiveUniforms.write.
        byte[] naniteBindingsBytes = readBytesResource("/com/kltyton/lumenfx/client/render/mesh/nanite/NaniteMaterialFrameBindings.class");
        var naniteBindingsClass = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(naniteBindingsBytes).accept(naniteBindingsClass, 0);
        int threeArgPrimitiveWrites = 0;
        for (var method : naniteBindingsClass.methods) {
            for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call
                        && call.owner.equals("com/kltyton/lumenfx/client/render/scene/material/MaterialPrimitiveUniforms")
                        && call.name.equals("write")) {
                    assertTrue(call.desc.contains("StaticMeshAsset$Bounds;"),
                            "NaniteMaterialFrameBindings must pass mesh asset bounds to MaterialPrimitiveUniforms.write, found: " + call.desc);
                    threeArgPrimitiveWrites++;
                }
            }
        }
        assertEquals(2, threeArgPrimitiveWrites, "NaniteMaterialFrameBindings must pass mesh bounds in both prepareSurface and uniform");

        // Issue 19: Opaque particle motion tail writes separate location 2 offset while keeping previousNdc pure;
        // translucent particle motion tail omits location 2 so foreground translucent draws cannot clear opaque offsets.
        var tailMethod = com.kltyton.lumenfx.client.render.motion.shader.MotionShaderAdapter.class
                .getDeclaredMethod("motionFragmentTail", boolean.class, boolean.class, boolean.class);
        tailMethod.setAccessible(true);
        String opaqueTail = (String) tailMethod.invoke(null, false, false, true);
        assertTrue(opaqueTail.contains("layout(location = 2) out vec4 lfxMotionShader_motionOffset;"),
                "Opaque particle motion tail must declare dedicated location 2 offset attachment");
        assertTrue(opaqueTail.contains("#if LFX_MATERIAL_MOTION_OFFSET"), "Opaque motion tail must support per-pixel motion offset");
        assertTrue(opaqueTail.contains("lfxMaterialMotionWorldOffset.xzy * 0.01"), "Opaque motion tail must convert UE cm offset to host meters");
        assertTrue(opaqueTail.contains("lfxMotionShader_encodeOffsetVelocity("),
                "Opaque motion tail must encode separate motion vector world offset");
        assertFalse(opaqueTail.contains("previousNdc +="),
                "Base previousNdc (SceneVelocity) must NOT be mutated by material motion vector world offset");
        String translucentTail = (String) tailMethod.invoke(null, false, false, false);
        assertFalse(translucentTail.contains("lfxMotionShader_motionOffset"),
                "Translucent particle motion tail must NOT declare or write location 2 offset attachment");
        assertEquals(com.mojang.blaze3d.GpuFormat.RGBA16_UNORM,
                com.kltyton.lumenfx.client.render.motion.shader.MotionShaderLayout.MOTION_OFFSET_FORMAT);

        // Issue 14: Translucent Substrate Slab anisotropic GGX and Charlie Sheen forward lighting includes exist.
        String slabForwardGlsl = new String(readBytesResource("/assets/lumenfx/shaders/include/lighting/forward_translucent_slab.glsl"), StandardCharsets.UTF_8);
        String slabEnvGlsl = new String(readBytesResource("/assets/lumenfx/shaders/include/lighting/translucent_slab_env.glsl"), StandardCharsets.UTF_8);
        assertTrue(slabForwardGlsl.contains("lfx_forward_translucent_slab(") && slabForwardGlsl.contains("lfx_slab_aniso_ggx("));
        assertTrue(slabEnvGlsl.contains("lfx_translucent_slab_environment(") && slabEnvGlsl.contains("lfx_slab_sheen_d("));

        // Issue 20, 22, 23 & 24: Active Iris Niagara GBuffer shaderpack + hardware Photon .fx opaque coverage mask,
        // exact target format matching, ZWrite=0 opaque particle surface exclusion, and Nanite depth-write admission alignment.
        String shaderpackGBufferGlsl = new String(readBytesResource("/assets/lumenfx/shaders/post/niagara_shaderpack_gbuffer.fsh"), StandardCharsets.UTF_8);
        assertTrue(shaderpackGBufferGlsl.contains("lfx_decode_octahedral("));
        assertTrue(shaderpackGBufferGlsl.contains("LFX_HOST_SPECULAR_ENCODING == 2"));
        assertTrue(shaderpackGBufferGlsl.contains("layout(location = 0) out vec4 GBufferA;"));
        assertTrue(shaderpackGBufferGlsl.contains("lfx_surface_has_default_lit_particle(particleGeometry)"),
                "Active Iris shaderpack GBuffer must unpack foreground lit opaque particles over host terrain");
        assertTrue(shaderpackGBufferGlsl.contains("LFX_UNLIT_PARTICLE_FLAG"),
                "Active Iris shaderpack GBuffer must suppress background terrain behind unlit opaque particles");
        assertTrue(shaderpackGBufferGlsl.contains("#if LFX_GBUFFER_PHOTON_MASK")
                        && shaderpackGBufferGlsl.contains("texelFetch(PhotonOpaqueMask, pixel, 0).r > 0.5"),
                "Issue 20: Active Iris shaderpack GBuffer must consume hardware Photon opaque coverage mask (covering nearer and equal-depth writes)");
        var adaptMaskShader = com.kltyton.lumenfx.client.render.scene.surface.PhotonOpaqueMaskCapture.class
                .getDeclaredMethod("adaptFragmentShader", String.class);
        adaptMaskShader.setAccessible(true);
        String adapted150 = (String) adaptMaskShader.invoke(null, "#version 150\nout vec4 fragColor;\nvoid main() { fragColor = vec4(1.0); }\n");
        assertTrue(adapted150.startsWith("#version 330"), "Issue 20: #version 150 Photon shader must upgrade to #version 330 for explicit MRT locations");
        assertTrue(adapted150.contains("layout(location=0) out vec4 fragColor;"));
        assertTrue(adapted150.contains("layout(location=1) out vec4 lfxPhotonMask_coverage;"));
        assertTrue(adapted150.contains("lfxPhotonMask_coverage = vec4(1.0, 0.0, 0.0, 1.0);"));
        var writesMaskMethod = com.kltyton.lumenfx.client.render.scene.surface.PhotonOpaqueMaskCapture.class
                .getDeclaredMethod("writesOpaqueMask", com.mojang.blaze3d.pipeline.RenderPipeline.class);
        writesMaskMethod.setAccessible(true);
        var testTarget = new com.mojang.blaze3d.pipeline.ColorTargetState(
                java.util.Optional.empty(), com.mojang.blaze3d.GpuFormat.RGBA16_FLOAT, com.mojang.blaze3d.pipeline.ColorTargetState.WRITE_ALL);
        var maskWriteOnPipeline = com.mojang.blaze3d.pipeline.RenderPipeline.builder()
                .withLocation(com.kltyton.lumenfx.LumenFx.id("test/photon_mask_on"))
                .withVertexShader(com.kltyton.lumenfx.LumenFx.id("post/fullscreen"))
                .withFragmentShader(com.kltyton.lumenfx.LumenFx.id("core/color"))
                .withPrimitiveTopology(com.mojang.blaze3d.PrimitiveTopology.TRIANGLES)
                .withColorTargetState(0, testTarget)
                .withDepthStencilState(new com.mojang.blaze3d.pipeline.DepthStencilState(
                        com.mojang.blaze3d.platform.CompareOp.GREATER_THAN_OR_EQUAL, true))
                .build();
        var maskWriteOffPipeline = com.mojang.blaze3d.pipeline.RenderPipeline.builder()
                .withLocation(com.kltyton.lumenfx.LumenFx.id("test/photon_mask_off"))
                .withVertexShader(com.kltyton.lumenfx.LumenFx.id("post/fullscreen"))
                .withFragmentShader(com.kltyton.lumenfx.LumenFx.id("core/color"))
                .withPrimitiveTopology(com.mojang.blaze3d.PrimitiveTopology.TRIANGLES)
                .withColorTargetState(0, testTarget)
                .withDepthStencilState(new com.mojang.blaze3d.pipeline.DepthStencilState(
                        com.mojang.blaze3d.platform.CompareOp.GREATER_THAN_OR_EQUAL, false))
                .build();
        assertTrue((boolean) writesMaskMethod.invoke(null, maskWriteOnPipeline),
                "Issue 20: depthMask=true Photon pipeline must write opaque coverage mask (handles nearer and equal depth)");
        assertFalse((boolean) writesMaskMethod.invoke(null, maskWriteOffPipeline),
                "Issue 20: depthMask=false Photon pipeline must disable writes to opaque coverage mask");
        var matchesPassMethod = com.kltyton.lumenfx.client.render.scene.surface.PhotonOpaqueMaskCapture.class
                .getDeclaredMethod("matchesPass", int.class, int.class, int.class, int.class, int.class, boolean.class);
        matchesPassMethod.setAccessible(true);
        assertTrue((boolean) matchesPassMethod.invoke(null, 1280, 720, 1, 1280, 720, true),
                "Issue 20: PhotonDrawTarget raster pass sharing linearWorld depth must be expanded regardless of color texture identity");
        assertFalse((boolean) matchesPassMethod.invoke(null, 1280, 720, 1, 1280, 720, false),
                "Issue 20: Seed/composite or offscreen passes not bound to shared depth must NOT be expanded");
        assertFalse((boolean) matchesPassMethod.invoke(null, 1280, 720, 1, 640, 360, true),
                "Issue 20: Mismatched viewport dimensions must NOT be expanded");
        assertNotNull(com.kltyton.lumenfx.client.render.scene.surface.OpaqueParticleSurfaces.class.getDeclaredMethod(
                "beginStandalone",
                com.mojang.blaze3d.systems.CommandEncoder.class,
                com.mojang.blaze3d.pipeline.RenderTarget.class,
                boolean.class, boolean.class, boolean.class, int.class));
        assertNotNull(com.kltyton.lumenfx.client.render.niagara.gpu.scene.NiagaraSceneGBuffer.class.getDeclaredMethod(
                "resolveShaderpack",
                com.mojang.blaze3d.systems.CommandEncoder.class,
                com.kltyton.lumenfx.client.compat.shaderpack.ShaderpackMaterialSurfaces.class,
                org.joml.Matrix4fc.class,
                com.kltyton.lumenfx.client.compat.shaderpack.ColourEncoding.class,
                com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraSceneInputs.class,
                com.kltyton.lumenfx.client.render.scene.surface.OpaqueParticleSurfaces.Frame.class,
                com.mojang.blaze3d.textures.GpuTextureView.class));
        assertNotNull(com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture.class.getDeclaredMethod("externalPhotonOpaqueMask"));
        var reusableMethod = com.kltyton.lumenfx.client.render.niagara.gpu.scene.NiagaraSceneGBuffer.class
                .getDeclaredMethod("targetsReusable", boolean.class, boolean.class);
        reusableMethod.setAccessible(true);
        assertTrue((boolean) reusableMethod.invoke(null, true, true));
        assertTrue((boolean) reusableMethod.invoke(null, false, false));
        assertFalse((boolean) reusableMethod.invoke(null, true, false),
                "Issue 22: RGBA32_FLOAT targets from native path must NOT be reused when RGBA8_UNORM is requested under Iris");
        assertFalse((boolean) reusableMethod.invoke(null, false, true),
                "Issue 22: RGBA8_UNORM targets must NOT be reused when RGBA32_FLOAT is requested");
        assertNotNull(com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture.class.getDeclaredMethod(
                "syncExternalMotionDepth", com.mojang.blaze3d.textures.GpuTexture.class));
        assertNotNull(com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture.class.getDeclaredMethod(
                "particleMotionVectorWorldOffset"));

        // Issue 23 & 24: Opaque particle materials with _ZWrite=0 (or Compare.DISABLED) must NOT write particle GBuffer surfaces
        // and must NOT be admitted into Nanite visibility-buffer rasterization (falling back to ordinary mesh path).
        var opaqueZWriteOn = new com.kltyton.lumenfx.effect.MaterialDefinition(
                com.kltyton.lumenfx.LumenFx.id("core/color"), Map.of(),
                com.kltyton.lumenfx.effect.MaterialDefinition.Blend.OPAQUE,
                com.kltyton.lumenfx.effect.render.MaterialDepth.legacy(true, true), false, new float[]{0, 0, 0, 0},
                com.kltyton.lumenfx.effect.RenderSettings.STANDARD, com.kltyton.lumenfx.effect.render.MaterialFog.NONE,
                com.kltyton.lumenfx.effect.render.TranslucencyPass.AFTER_DOF);
        var opaqueZWriteOff = new com.kltyton.lumenfx.effect.MaterialDefinition(
                com.kltyton.lumenfx.LumenFx.id("core/color"), Map.of(),
                com.kltyton.lumenfx.effect.MaterialDefinition.Blend.OPAQUE,
                com.kltyton.lumenfx.effect.render.MaterialDepth.legacy(true, false), false, new float[]{0, 0, 0, 0},
                com.kltyton.lumenfx.effect.RenderSettings.STANDARD, com.kltyton.lumenfx.effect.render.MaterialFog.NONE,
                com.kltyton.lumenfx.effect.render.TranslucencyPass.AFTER_DOF);
        var opaqueDepthDisabled = new com.kltyton.lumenfx.effect.MaterialDefinition(
                com.kltyton.lumenfx.LumenFx.id("core/color"), Map.of(),
                com.kltyton.lumenfx.effect.MaterialDefinition.Blend.OPAQUE,
                new com.kltyton.lumenfx.effect.render.MaterialDepth(com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.DISABLED, true),
                false, new float[]{0, 0, 0, 0},
                com.kltyton.lumenfx.effect.RenderSettings.STANDARD, com.kltyton.lumenfx.effect.render.MaterialFog.NONE,
                com.kltyton.lumenfx.effect.render.TranslucencyPass.AFTER_DOF);
        var writesSurfaceMethod = com.kltyton.lumenfx.client.render.EffectGpuRenderer.class
                .getDeclaredMethod("writesOpaqueSurface", com.kltyton.lumenfx.effect.MaterialDefinition.class);
        writesSurfaceMethod.setAccessible(true);
        assertTrue(com.kltyton.lumenfx.client.render.EffectFrame.writesOpaqueDepth(opaqueZWriteOn));
        assertTrue((boolean) writesSurfaceMethod.invoke(null, opaqueZWriteOn));
        assertFalse(com.kltyton.lumenfx.client.render.EffectFrame.writesOpaqueDepth(opaqueZWriteOff),
                "Issue 23: Opaque material with _ZWrite=0 must NOT be classified as an opaque depth writer");
        assertFalse((boolean) writesSurfaceMethod.invoke(null, opaqueZWriteOff),
                "Issue 23: Opaque material with _ZWrite=0 must NOT write particle GBuffer surfaces");
        assertFalse((boolean) writesSurfaceMethod.invoke(null, opaqueDepthDisabled),
                "Issue 23: Opaque material with Compare.DISABLED must NOT write particle GBuffer surfaces");
        var naniteSource = new com.kltyton.lumenfx.effect.render.mesh.NaniteMaterialSource(
                "MD_Surface",
                com.kltyton.lumenfx.effect.render.mesh.NaniteMaterialSource.BlendMode.BLEND_Opaque,
                com.kltyton.lumenfx.effect.render.mesh.NaniteMaterialSource.ShadingModel.MSM_DefaultLit,
                null, true, false, false, false, 0.3333);
        var naniteZWriteOn = new com.kltyton.lumenfx.effect.MaterialDefinition(
                com.kltyton.lumenfx.LumenFx.id("core/color"), Map.of(),
                com.kltyton.lumenfx.effect.MaterialDefinition.Blend.OPAQUE,
                com.kltyton.lumenfx.effect.render.MaterialDepth.legacy(true, true), false, new float[]{0, 0, 0, 0},
                com.kltyton.lumenfx.effect.RenderSettings.STANDARD, com.kltyton.lumenfx.effect.render.MaterialFog.NONE,
                com.kltyton.lumenfx.effect.render.TranslucencyPass.AFTER_DOF, 0, false, false, false, false, "",
                false, false, null, 0, true, false, 0, null, false,
                com.kltyton.lumenfx.effect.render.material.MaterialInstanceParameters.NONE, List.of(),
                0, List.of(), false, false, com.kltyton.lumenfx.effect.MaterialDefinition.PointShadowCoverage.SOLID,
                false, 0, 0, false, false, 0, null, List.of(), List.of(), 0, false,
                com.kltyton.lumenfx.effect.MaterialDefinition.TranslucencyVolume.NONE, naniteSource, false);
        var naniteZWriteOff = new com.kltyton.lumenfx.effect.MaterialDefinition(
                com.kltyton.lumenfx.LumenFx.id("core/color"), Map.of(),
                com.kltyton.lumenfx.effect.MaterialDefinition.Blend.OPAQUE,
                com.kltyton.lumenfx.effect.render.MaterialDepth.legacy(true, false), false, new float[]{0, 0, 0, 0},
                com.kltyton.lumenfx.effect.RenderSettings.STANDARD, com.kltyton.lumenfx.effect.render.MaterialFog.NONE,
                com.kltyton.lumenfx.effect.render.TranslucencyPass.AFTER_DOF, 0, false, false, false, false, "",
                false, false, null, 0, true, false, 0, null, false,
                com.kltyton.lumenfx.effect.render.material.MaterialInstanceParameters.NONE, List.of(),
                0, List.of(), false, false, com.kltyton.lumenfx.effect.MaterialDefinition.PointShadowCoverage.SOLID,
                false, 0, 0, false, false, 0, null, List.of(), List.of(), 0, false,
                com.kltyton.lumenfx.effect.MaterialDefinition.TranslucencyVolume.NONE, naniteSource, false);
        Class<?> auditClass = Class.forName("com.kltyton.lumenfx.client.render.mesh.nanite.NaniteMaterialAudit");
        var auditMethod = auditClass.getDeclaredMethod("audit",
                com.kltyton.lumenfx.effect.MaterialDefinition.class, boolean.class, boolean.class);
        auditMethod.setAccessible(true);
        assertEquals("ELIGIBLE", auditMethod.invoke(null, naniteZWriteOn, true, false).toString(),
                "Issue 24/25: Depth-writing LESS_EQUAL Opaque Nanite material must remain ELIGIBLE");
        assertEquals("INELIGIBLE", auditMethod.invoke(null, naniteZWriteOff, true, false).toString(),
                "Issue 24: Opaque Nanite material with depth_write=false must be INELIGIBLE and fall back to ordinary mesh path");
        for (var nonStandardCompare : java.util.List.of(
                com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.NEVER,
                com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.LESS,
                com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.EQUAL,
                com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.GREATER,
                com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.GREATER_EQUAL,
                com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.NOT_EQUAL,
                com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.ALWAYS,
                com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.DISABLED)) {
            var naniteCustomCompare = new com.kltyton.lumenfx.effect.MaterialDefinition(
                    com.kltyton.lumenfx.LumenFx.id("core/color"), Map.of(),
                    com.kltyton.lumenfx.effect.MaterialDefinition.Blend.OPAQUE,
                    new com.kltyton.lumenfx.effect.render.MaterialDepth(nonStandardCompare, true),
                    false, new float[]{0, 0, 0, 0},
                    com.kltyton.lumenfx.effect.RenderSettings.STANDARD, com.kltyton.lumenfx.effect.render.MaterialFog.NONE,
                    com.kltyton.lumenfx.effect.render.TranslucencyPass.AFTER_DOF, 0, false, false, false, false, "",
                    false, false, null, 0, true, false, 0, null, false,
                    com.kltyton.lumenfx.effect.render.material.MaterialInstanceParameters.NONE, List.of(),
                    0, List.of(), false, false, com.kltyton.lumenfx.effect.MaterialDefinition.PointShadowCoverage.SOLID,
                    false, 0, 0, false, false, 0, null, List.of(), List.of(), 0, false,
                    com.kltyton.lumenfx.effect.MaterialDefinition.TranslucencyVolume.NONE, naniteSource, false);
            assertEquals("INELIGIBLE", auditMethod.invoke(null, naniteCustomCompare, true, false).toString(),
                    "Issue 25: Nanite material with non-LESS_EQUAL depth compare " + nonStandardCompare
                            + " must be INELIGIBLE and fall back to ordinary mesh path");
        }
        var opaqueNever = new com.kltyton.lumenfx.effect.MaterialDefinition(
                com.kltyton.lumenfx.LumenFx.id("core/color"), Map.of(),
                com.kltyton.lumenfx.effect.MaterialDefinition.Blend.OPAQUE,
                new com.kltyton.lumenfx.effect.render.MaterialDepth(com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.NEVER, true),
                false, new float[]{0, 0, 0, 0},
                com.kltyton.lumenfx.effect.RenderSettings.STANDARD, com.kltyton.lumenfx.effect.render.MaterialFog.NONE,
                com.kltyton.lumenfx.effect.render.TranslucencyPass.AFTER_DOF);
        assertFalse(com.kltyton.lumenfx.client.render.EffectFrame.writesOpaqueDepth(opaqueNever),
                "Issue 25: Compare.NEVER material can never pass depth test or write opaque depth");

        // Issue 26: Invisible NEVER / non-depth-writing Opaque materials must NOT write particle velocity or previous payloads;
        // strict irreflexive Opaque compare modes (LESS/GREATER/NOT_EQUAL) must replay against pre-pass depth,
        // and Translucent velocity must honor material depth compare while excluding NEVER.
        var requiresReplayMethod = com.kltyton.lumenfx.client.render.EffectGpuRenderer.class
                .getDeclaredMethod("requiresOpaqueMotionDepthReplay", com.kltyton.lumenfx.effect.MaterialDefinition.class);
        requiresReplayMethod.setAccessible(true);
        var motionDepthStateMethod = com.kltyton.lumenfx.client.render.EffectGpuRenderer.class
                .getDeclaredMethod("motionDepthStencilState", com.kltyton.lumenfx.effect.MaterialDefinition.class, boolean.class);
        motionDepthStateMethod.setAccessible(true);
        assertTrue(opaqueZWriteOn.writesVelocity(),
                "Issue 26 control: normal LESS_EQUAL depth-writing Opaque material must still write velocity");
        assertTrue(com.kltyton.lumenfx.client.render.mesh.MeshDrawSelection.needsPreviousPayload(opaqueZWriteOn));
        assertFalse((boolean) requiresReplayMethod.invoke(null, opaqueZWriteOn));
        var normalOpaqueDepthState = (com.mojang.blaze3d.pipeline.DepthStencilState) motionDepthStateMethod.invoke(null, opaqueZWriteOn, false);
        assertEquals(com.mojang.blaze3d.platform.CompareOp.EQUAL, normalOpaqueDepthState.depthTest());
        assertFalse(normalOpaqueDepthState.writeDepth());
        assertFalse(opaqueNever.writesVelocity(),
                "Issue 26: Opaque Compare.NEVER material must NOT enter velocity pass");
        assertFalse(com.kltyton.lumenfx.client.render.mesh.MeshDrawSelection.needsPreviousPayload(opaqueNever));
        assertFalse(opaqueZWriteOff.writesVelocity(),
                "Issue 26: Opaque material with depth_write=false must NOT overwrite coplanar surface velocity");
        assertFalse(opaqueDepthDisabled.writesVelocity(),
                "Issue 26: Opaque material with Compare.DISABLED must NOT overwrite coplanar surface velocity");
        for (var compare : com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.values()) {
            var opaqueMat = new com.kltyton.lumenfx.effect.MaterialDefinition(
                    com.kltyton.lumenfx.LumenFx.id("core/color"), Map.of(),
                    com.kltyton.lumenfx.effect.MaterialDefinition.Blend.OPAQUE,
                    new com.kltyton.lumenfx.effect.render.MaterialDepth(compare, true),
                    false, new float[]{0, 0, 0, 0},
                    com.kltyton.lumenfx.effect.RenderSettings.STANDARD, com.kltyton.lumenfx.effect.render.MaterialFog.NONE,
                    com.kltyton.lumenfx.effect.render.TranslucencyPass.AFTER_DOF);
            boolean expectedOpaqueVel = compare != com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.NEVER
                    && compare != com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.DISABLED;
            assertEquals(expectedOpaqueVel, opaqueMat.writesVelocity(),
                    "Issue 26: Opaque writesVelocity mismatch for " + compare);
            boolean expectedReplay = compare == com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.LESS
                    || compare == com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.GREATER
                    || compare == com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.NOT_EQUAL;
            assertEquals(expectedReplay, (boolean) requiresReplayMethod.invoke(null, opaqueMat),
                    "Issue 26: strict opaque replay mismatch for " + compare);
            if (expectedReplay) {
                var replayState = (com.mojang.blaze3d.pipeline.DepthStencilState) motionDepthStateMethod.invoke(null, opaqueMat, true);
                assertTrue(replayState.writeDepth(), "Strict opaque velocity replay must write its private replay depth");
                var expectedOp = switch (compare) {
                    case LESS -> com.mojang.blaze3d.platform.CompareOp.GREATER_THAN;
                    case GREATER -> com.mojang.blaze3d.platform.CompareOp.LESS_THAN;
                    case NOT_EQUAL -> com.mojang.blaze3d.platform.CompareOp.NOT_EQUAL;
                    default -> throw new AssertionError();
                };
                assertEquals(expectedOp, replayState.depthTest());
            }
            var translucentVelMat = new com.kltyton.lumenfx.effect.MaterialDefinition(
                    com.kltyton.lumenfx.LumenFx.id("core/color"), Map.of(),
                    com.kltyton.lumenfx.effect.MaterialDefinition.Blend.ALPHA,
                    new com.kltyton.lumenfx.effect.render.MaterialDepth(compare, false),
                    false, new float[]{0, 0, 0, 0},
                    com.kltyton.lumenfx.effect.RenderSettings.STANDARD, com.kltyton.lumenfx.effect.render.MaterialFog.NONE,
                    com.kltyton.lumenfx.effect.render.TranslucencyPass.AFTER_DOF, 0, false, false, false, false, "",
                    true, false);
            assertEquals(compare != com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.NEVER,
                    translucentVelMat.writesVelocity(),
                    "Issue 26: Translucent writeTranslucentVelocity mismatch for " + compare);
            assertFalse((boolean) requiresReplayMethod.invoke(null, translucentVelMat));
        }
        var translucentLessEqual = new com.kltyton.lumenfx.effect.MaterialDefinition(
                com.kltyton.lumenfx.LumenFx.id("core/color"), Map.of(),
                com.kltyton.lumenfx.effect.MaterialDefinition.Blend.ALPHA,
                new com.kltyton.lumenfx.effect.render.MaterialDepth(com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.LESS_EQUAL, false),
                false, new float[]{0, 0, 0, 0},
                com.kltyton.lumenfx.effect.RenderSettings.STANDARD, com.kltyton.lumenfx.effect.render.MaterialFog.NONE,
                com.kltyton.lumenfx.effect.render.TranslucencyPass.AFTER_DOF, 0, false, false, false, false, "",
                true, false);
        var translucentLessEqualState = (com.mojang.blaze3d.pipeline.DepthStencilState) motionDepthStateMethod.invoke(null, translucentLessEqual, false);
        assertEquals(com.mojang.blaze3d.platform.CompareOp.GREATER_THAN_OR_EQUAL, translucentLessEqualState.depthTest());
        assertTrue(translucentLessEqualState.writeDepth());
        var translucentDisabled = new com.kltyton.lumenfx.effect.MaterialDefinition(
                com.kltyton.lumenfx.LumenFx.id("core/color"), Map.of(),
                com.kltyton.lumenfx.effect.MaterialDefinition.Blend.ALPHA,
                new com.kltyton.lumenfx.effect.render.MaterialDepth(com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.DISABLED, false),
                false, new float[]{0, 0, 0, 0},
                com.kltyton.lumenfx.effect.RenderSettings.STANDARD, com.kltyton.lumenfx.effect.render.MaterialFog.NONE,
                com.kltyton.lumenfx.effect.render.TranslucencyPass.AFTER_DOF, 0, false, false, false, false, "",
                true, false);
        var translucentDisabledState = (com.mojang.blaze3d.pipeline.DepthStencilState) motionDepthStateMethod.invoke(null, translucentDisabled, false);
        assertEquals(com.mojang.blaze3d.platform.CompareOp.ALWAYS_PASS, translucentDisabledState.depthTest());
        assertTrue(translucentDisabledState.writeDepth());
        assertNotNull(com.kltyton.lumenfx.client.render.motion.shader.MotionPipelineFactory.class.getDeclaredMethod(
                "coverage", com.mojang.blaze3d.pipeline.RenderPipeline.class,
                com.kltyton.lumenfx.client.render.motion.shader.MotionShaderSet.class,
                net.minecraft.resources.Identifier.class,
                com.mojang.blaze3d.pipeline.BindGroupLayout.class,
                boolean.class, com.mojang.blaze3d.pipeline.DepthStencilState.class, boolean.class));

        // Issue 27: Native decal receiver pre-pass must split strict-compare opaque batches (LESS/GREATER/NOT_EQUAL),
        // snapshot decal depth strictly BEFORE drawing, and replay velocity against that pre-batch snapshot so a
        // coplanar LESS moving mesh rejected in decal depth cannot overwrite a stationary receiver's velocity.
        record SimDraw(String name, com.kltyton.lumenfx.effect.MaterialDefinition material, float deviceZ, float velocityX) { }
        var opaqueLessMat = new com.kltyton.lumenfx.effect.MaterialDefinition(
                com.kltyton.lumenfx.LumenFx.id("core/color"), Map.of(),
                com.kltyton.lumenfx.effect.MaterialDefinition.Blend.OPAQUE,
                new com.kltyton.lumenfx.effect.render.MaterialDepth(com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.LESS, true),
                false, new float[]{0, 0, 0, 0},
                com.kltyton.lumenfx.effect.RenderSettings.STANDARD, com.kltyton.lumenfx.effect.render.MaterialFog.NONE,
                com.kltyton.lumenfx.effect.render.TranslucencyPass.AFTER_DOF);
        java.util.function.BiPredicate<Float, java.util.Map.Entry<com.mojang.blaze3d.platform.CompareOp, Float>> passReverseZ =
                (fragZ, opAndBuf) -> switch (opAndBuf.getKey()) {
                    case GREATER_THAN_OR_EQUAL -> fragZ >= opAndBuf.getValue();
                    case GREATER_THAN -> fragZ > opAndBuf.getValue();
                    case EQUAL -> Float.compare(fragZ, opAndBuf.getValue()) == 0;
                    case LESS_THAN -> fragZ < opAndBuf.getValue();
                    case LESS_THAN_OR_EQUAL -> fragZ <= opAndBuf.getValue();
                    case NOT_EQUAL -> Float.compare(fragZ, opAndBuf.getValue()) != 0;
                    case ALWAYS_PASS -> true;
                    case NEVER_PASS -> false;
                };
        var decalDraws = java.util.List.of(
                new SimDraw("stationaryReceiver", opaqueZWriteOn, 0.5f, 0.0f),
                new SimDraw("coplanarLessMover", opaqueLessMat, 0.5f, 9.0f));
        float[] decalDepthBuf = {0.2f}; // background reverse-Z
        float[] velocityBuf = {Float.NaN};
        float[] coverageDepthBuf = {Float.NaN};
        var decalEvents = new java.util.ArrayList<String>();
        var decalScratch = new java.util.ArrayList<SimDraw>();
        com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionPassPlan.runDecalReceivers(
                decalDraws, decalScratch, true, SimDraw::material, d -> true,
                (group, snapshotPrePassDepth) -> {
                    float[] replaySnapshot = {Float.NaN};
                    if (snapshotPrePassDepth) {
                        replaySnapshot[0] = decalDepthBuf[0];
                        decalEvents.add("SNAPSHOT_PRE_DECAL:" + group.getFirst().name() + "@" + replaySnapshot[0]);
                    }
                    // Decal receiver depth pass (author compare, writeDepth=true)
                    for (var d : group) {
                        decalEvents.add("DECAL_RASTER:" + d.name());
                        var authorOp = switch (d.material().depth().compare()) {
                            case LESS_EQUAL -> com.mojang.blaze3d.platform.CompareOp.GREATER_THAN_OR_EQUAL;
                            case LESS -> com.mojang.blaze3d.platform.CompareOp.GREATER_THAN;
                            default -> throw new AssertionError();
                        };
                        if (passReverseZ.test(d.deviceZ(), java.util.Map.entry(authorOp, decalDepthBuf[0]))) {
                            decalDepthBuf[0] = d.deviceZ();
                        }
                    }
                    // Motion pass (replay snapshot with author compare + writeDepth if strict, else EQUAL against decalDepthBuf)
                    for (var d : group) {
                        decalEvents.add("DECAL_MOTION:" + d.name() + ":replay=" + snapshotPrePassDepth);
                        try {
                            var state = (com.mojang.blaze3d.pipeline.DepthStencilState)
                                    motionDepthStateMethod.invoke(null, d.material(), snapshotPrePassDepth);
                            float[] motionDepthRef = snapshotPrePassDepth ? replaySnapshot : decalDepthBuf;
                            if (passReverseZ.test(d.deviceZ(), java.util.Map.entry(state.depthTest(), motionDepthRef[0]))) {
                                if (state.writeDepth()) motionDepthRef[0] = d.deviceZ();
                                velocityBuf[0] = d.velocityX();
                                coverageDepthBuf[0] = d.deviceZ();
                            }
                        } catch (ReflectiveOperationException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
        assertEquals(java.util.List.of(
                "DECAL_RASTER:stationaryReceiver",
                "DECAL_MOTION:stationaryReceiver:replay=false",
                "SNAPSHOT_PRE_DECAL:coplanarLessMover@0.5",
                "DECAL_RASTER:coplanarLessMover",
                "DECAL_MOTION:coplanarLessMover:replay=true"), decalEvents,
                "Issue 27: Strict-compare decal receiver must snapshot pre-pass depth strictly before raster & motion replay");
        assertEquals(0.0f, velocityBuf[0],
                "Issue 27: Coplanar LESS moving mesh rejected in decal pre-pass must NOT overwrite stationary receiver velocity");
        assertEquals(0.5f, coverageDepthBuf[0]);

        // Issue 28: First depth-writing translucent velocity batch with LESS must seed ParticleMotionDepth strictly
        // BEFORE the color pass writes its own depth, so motion pass tests LESS against background depth and succeeds.
        var translucentLessWriteDepth = new com.kltyton.lumenfx.effect.MaterialDefinition(
                com.kltyton.lumenfx.LumenFx.id("core/color"), Map.of(),
                com.kltyton.lumenfx.effect.MaterialDefinition.Blend.ALPHA,
                new com.kltyton.lumenfx.effect.render.MaterialDepth(com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.LESS, true),
                false, new float[]{0, 0, 0, 0},
                com.kltyton.lumenfx.effect.RenderSettings.STANDARD, com.kltyton.lumenfx.effect.render.MaterialFog.NONE,
                com.kltyton.lumenfx.effect.render.TranslucencyPass.AFTER_DOF, 0, false, false, false, false, "",
                true, false);
        var translucentLessEqualWriteDepth = new com.kltyton.lumenfx.effect.MaterialDefinition(
                com.kltyton.lumenfx.LumenFx.id("core/color"), Map.of(),
                com.kltyton.lumenfx.effect.MaterialDefinition.Blend.ALPHA,
                new com.kltyton.lumenfx.effect.render.MaterialDepth(com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.LESS_EQUAL, true),
                false, new float[]{0, 0, 0, 0},
                com.kltyton.lumenfx.effect.RenderSettings.STANDARD, com.kltyton.lumenfx.effect.render.MaterialFog.NONE,
                com.kltyton.lumenfx.effect.render.TranslucencyPass.AFTER_DOF, 0, false, false, false, false, "",
                true, false);
        for (var mat : java.util.List.of(translucentLessWriteDepth, translucentLessEqualWriteDepth)) {
            var draw = new SimDraw("translucentMover", mat, 0.5f, 4.25f);
            float[] stageDepthBuf = {0.2f}; // opaque background depth
            float[] particleMotionDepthBuf = {Float.NaN};
            boolean[] particleMotionDepthCopied = {false};
            float[] transVelBuf = {Float.NaN};
            float[] transCovBuf = {Float.NaN};
            var transEvents = new java.util.ArrayList<String>();
            com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionPassPlan.runRasterGroup(
                    java.util.List.of(draw), true, SimDraw::material, d -> true, d -> true,
                    () -> transEvents.add("UNEXPECTED_OPAQUE_SNAPSHOT"),
                    () -> {
                        transEvents.add("SEED_TRANSLUCENT_DEPTH@" + stageDepthBuf[0]);
                        if (!particleMotionDepthCopied[0]) {
                            particleMotionDepthBuf[0] = stageDepthBuf[0];
                            particleMotionDepthCopied[0] = true;
                        }
                    },
                    () -> transEvents.add("SNAPSHOT_PRECOLOR@" + stageDepthBuf[0]),
                    () -> {
                        transEvents.add("COLOR_RASTER");
                        var colorOp = mat.depth().compare() == com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.LESS
                                ? com.mojang.blaze3d.platform.CompareOp.GREATER_THAN
                                : com.mojang.blaze3d.platform.CompareOp.GREATER_THAN_OR_EQUAL;
                        if (passReverseZ.test(draw.deviceZ(), java.util.Map.entry(colorOp, stageDepthBuf[0]))) {
                            stageDepthBuf[0] = draw.deviceZ(); // depth_write=true updates stage depth!
                        }
                    },
                    allowsFarther -> transEvents.add("UNEXPECTED_DEPTH_MERGE"),
                    opaqueReplay -> {
                        transEvents.add("MOTION_PASS:opaqueReplay=" + opaqueReplay);
                        assertFalse(opaqueReplay);
                        assertTrue(particleMotionDepthCopied[0], "Issue 28: ParticleMotionDepth must already be seeded before color pass");
                        try {
                            var state = (com.mojang.blaze3d.pipeline.DepthStencilState)
                                    motionDepthStateMethod.invoke(null, mat, false);
                            if (passReverseZ.test(draw.deviceZ(), java.util.Map.entry(state.depthTest(), particleMotionDepthBuf[0]))) {
                                if (state.writeDepth()) particleMotionDepthBuf[0] = draw.deviceZ();
                                transVelBuf[0] = draw.velocityX();
                                transCovBuf[0] = draw.deviceZ();
                            }
                        } catch (ReflectiveOperationException e) {
                            throw new RuntimeException(e);
                        }
                    });
            assertEquals(java.util.List.of(
                    "SEED_TRANSLUCENT_DEPTH@0.2",
                    "SNAPSHOT_PRECOLOR@0.2",
                    "COLOR_RASTER",
                    "MOTION_PASS:opaqueReplay=false"), transEvents,
                    "Issue 28: Translucent motion depth & pre-color depth must copy before color pass writes for " + mat.depth().compare());
            assertEquals(4.25f, transVelBuf[0],
                    "Issue 28: Single depth-writing translucent mover must produce velocity for " + mat.depth().compare());
            assertEquals(0.5f, transCovBuf[0]);
            assertEquals(0.5f, particleMotionDepthBuf[0],
                    "Issue 28: Resolved ParticleMotionDepth must match coverage depth for scene_velocity.fsh");
        }
        var translucentNonVelDepthOnly = new com.kltyton.lumenfx.effect.MaterialDefinition(
                com.kltyton.lumenfx.LumenFx.id("core/color"), Map.of(),
                com.kltyton.lumenfx.effect.MaterialDefinition.Blend.ALPHA,
                new com.kltyton.lumenfx.effect.render.MaterialDepth(com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.LESS_EQUAL, true),
                false, new float[]{0, 0, 0, 0},
                com.kltyton.lumenfx.effect.RenderSettings.STANDARD, com.kltyton.lumenfx.effect.render.MaterialFog.NONE,
                com.kltyton.lumenfx.effect.render.TranslucencyPass.AFTER_DOF, 0, false, false, false, false, "",
                false, false);
        var translucentNonVelNoDepth = new com.kltyton.lumenfx.effect.MaterialDefinition(
                com.kltyton.lumenfx.LumenFx.id("core/color"), Map.of(),
                com.kltyton.lumenfx.effect.MaterialDefinition.Blend.ALPHA,
                new com.kltyton.lumenfx.effect.render.MaterialDepth(com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.LESS_EQUAL, false),
                false, new float[]{0, 0, 0, 0},
                com.kltyton.lumenfx.effect.RenderSettings.STANDARD, com.kltyton.lumenfx.effect.render.MaterialFog.NONE,
                com.kltyton.lumenfx.effect.render.TranslucencyPass.AFTER_DOF, 0, false, false, false, false, "",
                false, false);
        var translucentNonVelGreaterDepth = new com.kltyton.lumenfx.effect.MaterialDefinition(
                com.kltyton.lumenfx.LumenFx.id("core/color"), Map.of(),
                com.kltyton.lumenfx.effect.MaterialDefinition.Blend.ALPHA,
                new com.kltyton.lumenfx.effect.render.MaterialDepth(com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.GREATER, true),
                false, new float[]{0, 0, 0, 0},
                com.kltyton.lumenfx.effect.RenderSettings.STANDARD, com.kltyton.lumenfx.effect.render.MaterialFog.NONE,
                com.kltyton.lumenfx.effect.render.TranslucencyPass.AFTER_DOF, 0, false, false, false, false, "",
                false, false);
        var translucentVelGreaterDepth = new com.kltyton.lumenfx.effect.MaterialDefinition(
                com.kltyton.lumenfx.LumenFx.id("core/color"), Map.of(),
                com.kltyton.lumenfx.effect.MaterialDefinition.Blend.ALPHA,
                new com.kltyton.lumenfx.effect.render.MaterialDepth(com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.GREATER, true),
                false, new float[]{0, 0, 0, 0},
                com.kltyton.lumenfx.effect.RenderSettings.STANDARD, com.kltyton.lumenfx.effect.render.MaterialFog.NONE,
                com.kltyton.lumenfx.effect.render.TranslucencyPass.AFTER_DOF, 0, false, false, false, false, "",
                true, false);
        assertTrue(com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionPassPlan
                .writesNonVelocityTranslucentDepth(translucentNonVelDepthOnly));
        assertFalse(com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionPassPlan
                .writesNonVelocityTranslucentDepth(translucentLessWriteDepth));
        assertTrue(com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionPassPlan
                .allowsFartherTranslucentDepthWrite(translucentNonVelGreaterDepth));
        assertFalse(com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionPassPlan
                .allowsFartherTranslucentDepthWrite(translucentNonVelDepthOnly));

        // Issue 29, 30, 32, 34 & Round 14 P1: Verify ordered translucent motion log shaders, zero-clear shader tail,
        // graphics storage buffer registry, and execute production ParticleMotionPassPlan + TranslucentMotionReplayLog.resolvePixel.
        String logClearShader = java.nio.file.Files.readString(java.nio.file.Path.of(
                "common/src/main/resources/assets/lumenfx/shaders/temporal/translucent_motion_log_clear.fsh"));
        assertTrue(logClearShader.contains("#version 430")
                && logClearShader.contains("LfxTranslucentMotionLog")
                && logClearShader.contains("lfxMotionLogCounter = 0u;")
                && logClearShader.contains("binding = 1"),
                "Translucent motion log clear shader must reset header and head table at SSBO binding 1");
        String logResolveShader = java.nio.file.Files.readString(java.nio.file.Path.of(
                "common/src/main/resources/assets/lumenfx/shaders/temporal/translucent_motion_log_resolve.fsh"));
        assertTrue(logResolveShader.contains("#version 430")
                && logResolveShader.contains("LfxTranslucentMotionLog")
                && logResolveShader.contains("PreColorDepthSampler")
                && logResolveShader.contains("ExistingMotionDepthSampler")
                && logResolveShader.contains("lfx_apply_node")
                && logResolveShader.contains("LFX_LOCAL_SORT_CAP")
                && logResolveShader.contains("gl_FragDepth = motionDepth;")
                && logResolveShader.contains("discard;"),
                "Translucent motion log resolve shader must replay fragments in exact submission order against pre-color and motion depth");
        assertFalse(logResolveShader.contains("location = 2") || logResolveShader.contains("motionOffset"),
                "Issue 19: Translucent motion log resolve must write only 2 attachments and never touch opaque motionOffset");
        assertTrue(com.kltyton.lumenfx.client.render.texture.virtual.VirtualTextureFeedbackLayout
                .isGraphicsStorageBuffer(com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionLogLayout.NAME),
                "Vulkan graphics storage reflection must cover LfxTranslucentMotionLog alongside LfxVirtualTextureFeedback");
        assertTrue(com.kltyton.lumenfx.client.render.texture.virtual.VirtualTextureFeedbackLayout
                .isGraphicsStorageBuffer(com.kltyton.lumenfx.client.render.texture.virtual.VirtualTextureFeedbackLayout.NAME));
        var zeroTailMethod = com.kltyton.lumenfx.client.render.motion.shader.MotionShaderAdapter.class
                .getDeclaredMethod("zeroOpaqueFragmentTail");
        zeroTailMethod.setAccessible(true);
        String zeroTail = (String) zeroTailMethod.invoke(null);
        assertTrue(zeroTail.contains("lfxMotionShader_originalMain();")
                && zeroTail.contains("lfxMotionShader_motionClip = vec4(0.0);")
                && zeroTail.contains("lfxMotionShader_motionDepth = vec2(0.0);")
                && zeroTail.contains("lfxMotionShader_motionOffset = vec4(0.0);"),
                "Issue 31: Opaque zero-clear shader must preserve original main/discard and clear all 3 velocity attachments");
        assertNotNull(com.kltyton.lumenfx.client.render.motion.shader.MotionPipelineFactory.class.getDeclaredMethod(
                "zeroOpaqueCoverage", com.mojang.blaze3d.pipeline.RenderPipeline.class,
                com.kltyton.lumenfx.client.render.motion.shader.MotionShaderSet.class,
                net.minecraft.resources.Identifier.class, com.mojang.blaze3d.pipeline.DepthStencilState.class));

        record TwoPixelDraw(String name, com.kltyton.lumenfx.effect.MaterialDefinition material,
                            float deviceZ, float velocityX, boolean coversPixel0, boolean coversPixel1, boolean discardsPixel0) {
            TwoPixelDraw(String name, com.kltyton.lumenfx.effect.MaterialDefinition material,
                         float deviceZ, float velocityX, boolean coversPixel0, boolean coversPixel1) {
                this(name, material, deviceZ, velocityX, coversPixel0, coversPixel1, false);
            }
            boolean covers(int pixel) { return pixel == 0 ? (coversPixel0 && !discardsPixel0) : coversPixel1; }
        }
        java.util.function.Function<com.kltyton.lumenfx.effect.render.MaterialDepth.Compare, com.mojang.blaze3d.platform.CompareOp> compareOf =
                c -> switch (c) {
                    case LESS_EQUAL -> com.mojang.blaze3d.platform.CompareOp.GREATER_THAN_OR_EQUAL;
                    case LESS -> com.mojang.blaze3d.platform.CompareOp.GREATER_THAN;
                    case DISABLED, ALWAYS -> com.mojang.blaze3d.platform.CompareOp.ALWAYS_PASS;
                    case NEVER -> com.mojang.blaze3d.platform.CompareOp.NEVER_PASS;
                    case EQUAL -> com.mojang.blaze3d.platform.CompareOp.EQUAL;
                    case GREATER -> com.mojang.blaze3d.platform.CompareOp.LESS_THAN;
                    case NOT_EQUAL -> com.mojang.blaze3d.platform.CompareOp.NOT_EQUAL;
                    case GREATER_EQUAL -> com.mojang.blaze3d.platform.CompareOp.LESS_THAN_OR_EQUAL;
                };
        // Execute translucent stages sharing ONE frame-persistent ParticleMotionDepth + previousNdc + coverageDepth,
        // applying BOTH pre-color depth merge (30a/30c) AND LfxMotionColorDepthSampler guard (30b).
        java.util.function.Function<java.util.List<java.util.List<TwoPixelDraw>>, float[][]> runTranslucentFrame =
                stages -> {
                    float[] motionDepth = {Float.NaN, Float.NaN};
                    boolean[] motionDepthActive = {false};
                    float[] velOut = {Float.NaN, Float.NaN};
                    float[] covDepth = {Float.NaN, Float.NaN};
                    float[] lastPublishedStageDepth = {0.2f, 0.2f};
                    float[] finalColorDepth = {0.2f, 0.2f};
                    for (var stageDraws : stages) {
                        float[] stageDepth = lastPublishedStageDepth.clone();
                        float[] preColorDepth = {Float.NaN, Float.NaN};
                        boolean[] preColorCaptured = {false};
                        var scratch = new java.util.ArrayList<TwoPixelDraw>();
                        boolean[] stageDepthWrittenFlag = {false};
                        com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionPassPlan.runTranslucentGroups(
                                stageDraws, scratch, TwoPixelDraw::material, d -> true, d -> true,
                                group -> com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionPassPlan.runRasterGroup(
                                        group, true, TwoPixelDraw::material, d -> true, d -> true,
                                        () -> { throw new AssertionError("No opaque replay expected in translucent frame"); },
                                        () -> {
                                            if (!motionDepthActive[0]) {
                                                motionDepth[0] = stageDepth[0];
                                                motionDepth[1] = stageDepth[1];
                                                motionDepthActive[0] = true;
                                            }
                                        },
                                        () -> {
                                            if (motionDepthActive[0]) {
                                                preColorDepth[0] = stageDepth[0];
                                                preColorDepth[1] = stageDepth[1];
                                                preColorCaptured[0] = true;
                                            } else {
                                                preColorCaptured[0] = false;
                                            }
                                        },
                                        () -> {
                                            for (var d : group) {
                                                var op = compareOf.apply(d.material().depth().compare());
                                                for (int px = 0; px < 2; px++) {
                                                    if (!d.covers(px)) continue;
                                                    if (passReverseZ.test(d.deviceZ(), java.util.Map.entry(op, stageDepth[px]))) {
                                                        if (d.material().depth().write()) {
                                                            stageDepth[px] = d.deviceZ();
                                                            stageDepthWrittenFlag[0] = true;
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        allowsFarther -> {
                                            // Unified ordered log resolve runs in drawMotion.
                                        },
                                        opaqueReplay -> {
                                            boolean groupHasRealVel = group.stream().anyMatch(d -> d.material().writesVelocity());
                                            boolean groupHasDepthSync = group.stream().anyMatch(d -> !d.material().writesVelocity()
                                                    && com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionPassPlan
                                                            .writesTranslucentColorDepth(d.material()));
                                            if (!groupHasRealVel && (!motionDepthActive[0] || !groupHasDepthSync)) {
                                                preColorCaptured[0] = false;
                                                return;
                                            }
                                            assertTrue(preColorCaptured[0], "Ordered translucent log requires pre-color depth snapshot");
                                            @SuppressWarnings("unchecked")
                                            java.util.List<com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.FragmentNode>[] perPixel =
                                                    new java.util.List[] { new java.util.ArrayList<>(), new java.util.ArrayList<>() };
                                            int drawIndex = 0;
                                            for (var d : group) {
                                                boolean realMotion = d.material().writesVelocity();
                                                boolean writesDepth = com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionPassPlan
                                                        .writesTranslucentColorDepth(d.material());
                                                if (!realMotion && !writesDepth) continue;
                                                for (int px = 0; px < 2; px++) {
                                                    if (!d.covers(px)) continue;
                                                    perPixel[px].add(new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.FragmentNode(
                                                            drawIndex, 0, 0, d.deviceZ(), d.material().depth().compare(),
                                                            writesDepth, realMotion, d.deviceZ(),
                                                            realMotion ? d.velocityX() : 0.0f, 0.0f, 0.0f, realMotion ? 1.0f : 0.0f));
                                                }
                                                drawIndex++;
                                            }
                                            for (int px = 0; px < 2; px++) {
                                                var init = new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.PixelState(
                                                        preColorDepth[px], motionDepth[px],
                                                        Float.isNaN(velOut[px]) ? 0.0f : velOut[px], 0.0f, 0.0f,
                                                        Float.isNaN(velOut[px]) ? 0.0f : 1.0f,
                                                        Float.isNaN(covDepth[px]) ? 0.0f : covDepth[px],
                                                        Float.isNaN(covDepth[px]) ? -1.0f : covDepth[px]);
                                                var res = com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog
                                                        .resolvePixel(init, perPixel[px]);
                                                motionDepth[px] = res.motionDepth();
                                                velOut[px] = res.prevNdcW() >= 1.0f ? res.prevNdcX() : Float.NaN;
                                                covDepth[px] = res.covFragDepth() >= 0.0f ? res.covFragDepth() : Float.NaN;
                                            }
                                            preColorCaptured[0] = false;
                                        }));
                        if (stageDepthWrittenFlag[0]) lastPublishedStageDepth = stageDepth;
                        finalColorDepth = stageDepth;
                    }
                    // Final scene_velocity.fsh resolve: output vel iff depth == coverage.y
                    float[] resolved = new float[2];
                    for (int px = 0; px < 2; px++) {
                        resolved[px] = (motionDepthActive[0] && Float.compare(motionDepth[px], covDepth[px]) == 0)
                                ? velOut[px] : 0.0f;
                    }
                    return new float[][]{resolved, motionDepth, covDepth, finalColorDepth};
                };
        var aDrawDepthOn = new TwoPixelDraw("A", translucentLessEqualWriteDepth, 0.4f, 1.5f, true, true);
        var aDrawDepthOff = new TwoPixelDraw("A_noDepth", translucentLessEqual, 0.4f, 1.5f, true, true);
        var bOccluder = new TwoPixelDraw("B_occluder", translucentNonVelDepthOnly, 0.8f, 0.0f, true, false);
        var bDiscarding = new TwoPixelDraw("B_discard", translucentNonVelDepthOnly, 0.8f, 0.0f, true, false, true);
        var bNoDepth = new TwoPixelDraw("B_noDepth", translucentNonVelNoDepth, 0.8f, 0.0f, true, false);
        var cBehindB = new TwoPixelDraw("C_less", translucentLessWriteDepth, 0.6f, 9.5f, true, false);

        // 1) Issue 29 same-stage A -> B (depth_write=true) vs A -> B -> C: C rejected at pixel 0; A preserved at pixel 1.
        float[][] abOnly = runTranslucentFrame.apply(java.util.List.of(java.util.List.of(aDrawDepthOn, bOccluder)));
        float[][] abcOccluded = runTranslucentFrame.apply(java.util.List.of(java.util.List.of(aDrawDepthOn, bOccluder, cBehindB)));
        assertEquals(0.8f, abcOccluded[1][0], "Issue 29: B color depth merge must update ParticleMotionDepth to 0.8 at pixel 0");
        assertEquals(abOnly[0][0], abcOccluded[0][0], "Issue 29: Toggling occluded C must not change resolved velocity at pixel 0");
        assertEquals(abOnly[2][0], abcOccluded[2][0], "Issue 29: Occluded C must not overwrite coverage depth at pixel 0");
        assertEquals(1.5f, abcOccluded[0][1], "Issue 29: Unoccluded pixel 1 must preserve A's velocity");

        // 2) Issue 29 control groups: B with depth_write=false OR B executing fragment discard -> C visible with velocity 9.5.
        float[][] abcControl = runTranslucentFrame.apply(java.util.List.of(java.util.List.of(aDrawDepthOn, bNoDepth, cBehindB)));
        assertEquals(9.5f, abcControl[0][0], "Issue 29 control: Removing B depth_write must allow visible C to write velocity");
        float[][] abcDiscardControl = runTranslucentFrame.apply(java.util.List.of(java.util.List.of(aDrawDepthOn, bDiscarding, cBehindB)));
        assertEquals(9.5f, abcDiscardControl[0][0], "Issue 29 discard control: Discarded B must not occlude C");

        // 3) Issue 29 cross-stage: Stage 1 has A (depth_write=false), Stage 2 has B + C; and Stage 1 has A+B, Stage 2 has C.
        float[][] crossStage = runTranslucentFrame.apply(java.util.List.of(
                java.util.List.of(aDrawDepthOff), java.util.List.of(bOccluder, cBehindB)));
        assertEquals(0.8f, crossStage[1][0]);
        assertEquals(0.0f, crossStage[0][0]);
        assertTrue(Float.isNaN(crossStage[2][0]), "Issue 29: Occluded pixel 0 coverage must be invalidated to prevent stale resurrection");
        assertEquals(1.5f, crossStage[0][1]);
        assertEquals(0.4f, crossStage[2][1]);
        float[][] crossStageSplitBC = runTranslucentFrame.apply(java.util.List.of(
                java.util.List.of(aDrawDepthOn, bOccluder), java.util.List.of(cBehindB)));
        assertEquals(0.8f, crossStageSplitBC[1][0]);
        assertEquals(0.0f, crossStageSplitBC[0][0]);
        assertEquals(1.5f, crossStageSplitBC[0][1]);

        // 4) Issue 30a: bg=0.2, A(0.6, depth_write=false, vel=1.5) -> B(0.4, GREATER, depth_write=true, no vel).
        var aIssue30 = new TwoPixelDraw("A_06_noDepth", translucentLessEqual, 0.6f, 1.5f, true, true);
        var bIssue30a = new TwoPixelDraw("B_04_greater_noVel", translucentNonVelGreaterDepth, 0.4f, 0.0f, true, false);
        float[][] issue30Control = runTranslucentFrame.apply(java.util.List.of(java.util.List.of(aIssue30)));
        float[][] issue30a = runTranslucentFrame.apply(java.util.List.of(java.util.List.of(aIssue30, bIssue30a)));
        assertEquals(0.2f, issue30a[3][0]);
        assertEquals(issue30Control[1][0], issue30a[1][0], "Issue 30a: Rejected non-vel GREATER B must NOT alter ParticleMotionDepth");
        assertEquals(1.5f, issue30a[0][0], "Issue 30a: Visible A velocity must survive rejected non-vel GREATER B");

        // 5) Issue 30b: bg=0.2, A(0.6, depth_write=false, vel=1.5) -> B(0.4, GREATER, depth_write=true, WITH vel=7.25).
        // B fails color test (0.4 < 0.2 false); LfxMotionColorDepthSampler guard discards B in drawMotion so A's 1.5 survives!
        var bIssue30b = new TwoPixelDraw("B_04_greater_withVel", translucentVelGreaterDepth, 0.4f, 7.25f, true, false);
        float[][] issue30b = runTranslucentFrame.apply(java.util.List.of(java.util.List.of(aIssue30, bIssue30b)));
        assertEquals(0.2f, issue30b[3][0]);
        assertEquals(0.6f, issue30b[1][0], "Issue 30b: Rejected velocity-writing GREATER B must NOT overwrite ParticleMotionDepth");
        assertEquals(0.6f, issue30b[2][0], "Issue 30b: Rejected velocity-writing GREATER B must NOT overwrite coverage depth");
        assertEquals(1.5f, issue30b[0][0], "Issue 30b: Visible A velocity must survive rejected velocity-writing GREATER B");

        // 6) Issue 30c: bg=0.2, A(0.6, depth_write=false, vel=1.5) -> B(0.1, GREATER, depth_write=true, no vel).
        // B passes against bg (0.1 < 0.2), replacing bg (0.2 -> 0.1), NOT foreground A (0.6); ExistingMotionDepthSampler
        // guard (0.6 > 0.2) preserves foreground A's 0.6 and velocity 1.5!
        var bIssue30c = new TwoPixelDraw("B_01_greater_noVel", translucentNonVelGreaterDepth, 0.1f, 0.0f, true, false);
        float[][] issue30c = runTranslucentFrame.apply(java.util.List.of(java.util.List.of(aIssue30, bIssue30c)));
        assertEquals(0.1f, issue30c[3][0], "Issue 30c: B genuinely updates color depth 0.2 -> 0.1");
        assertEquals(0.6f, issue30c[1][0], "Issue 30c: Farther write over bg 0.2 must NOT erase independent foreground A at 0.6");
        assertEquals(1.5f, issue30c[0][0], "Issue 30c: Foreground A velocity must remain 1.5");

        // 7) Issue 30c contrast: A(0.6, depth_write=true, vel=1.5) -> B(0.4, GREATER, depth_write=true) -> C(0.5, LESS, vel=8.0).
        // Here existingMotionDepth (0.6) <= beforeColorDepth (0.6), so B genuinely replaced A's color depth and updates motionDepth to 0.4!
        var aDepth06 = new TwoPixelDraw("A_06_depth", translucentLessEqualWriteDepth, 0.6f, 1.5f, true, true);
        var cAfterGreater = new TwoPixelDraw("C_05_less", translucentLessWriteDepth, 0.5f, 8.0f, true, false);
        float[][] genuineGreater = runTranslucentFrame.apply(java.util.List.of(java.util.List.of(aDepth06, bIssue30a, cAfterGreater)));
        assertEquals(0.5f, genuineGreater[3][0]);
        assertEquals(0.5f, genuineGreater[1][0]);
        assertEquals(8.0f, genuineGreater[0][0]);
        assertEquals(1.5f, genuineGreater[0][1]);

        // 8) Issue 31 (BOTH opaque main pass AND native decal receiver pre-pass):
        // bg=0.2; A = OPAQUE EQUAL 0.6 moving Mesh (hasMotion=true, vel=6.5, offset=2.0);
        // B = OPAQUE ALWAYS 0.6 non-motion Unity CPU Sprite (hasMotion=false).
        // A is rejected in color/decal pass (0.6 != 0.2); B writes 0.6; in drawMotion, B's zero-clear runs after A
        // and clears previousNdc, depthCoverage, and motionOffset to 0 so result is identical with A ON or OFF!
        record OpaqueSimDraw(String name, com.kltyton.lumenfx.effect.MaterialDefinition material,
                             float deviceZ, boolean hasMotion, float velocityX, float offsetX) { }
        var opaqueEqualMat = new com.kltyton.lumenfx.effect.MaterialDefinition(
                com.kltyton.lumenfx.LumenFx.id("core/color"), Map.of(),
                com.kltyton.lumenfx.effect.MaterialDefinition.Blend.OPAQUE,
                new com.kltyton.lumenfx.effect.render.MaterialDepth(com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.EQUAL, true),
                false, new float[]{0, 0, 0, 0},
                com.kltyton.lumenfx.effect.RenderSettings.STANDARD, com.kltyton.lumenfx.effect.render.MaterialFog.NONE,
                com.kltyton.lumenfx.effect.render.TranslucencyPass.AFTER_DOF);
        var opaqueAlwaysMat = new com.kltyton.lumenfx.effect.MaterialDefinition(
                com.kltyton.lumenfx.LumenFx.id("core/color"), Map.of(),
                com.kltyton.lumenfx.effect.MaterialDefinition.Blend.OPAQUE,
                new com.kltyton.lumenfx.effect.render.MaterialDepth(com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.ALWAYS, true),
                false, new float[]{0, 0, 0, 0},
                com.kltyton.lumenfx.effect.RenderSettings.STANDARD, com.kltyton.lumenfx.effect.render.MaterialFog.NONE,
                com.kltyton.lumenfx.effect.render.TranslucencyPass.AFTER_DOF);
        java.util.function.Function<java.util.List<OpaqueSimDraw>, float[]> runOpaqueSequence = draws -> {
            float[] depthBuf = {0.2f};
            float[] velBuf = {0.0f};
            float[] covBuf = {0.0f};
            float[] offBuf = {0.0f};
            var scratch = new java.util.ArrayList<OpaqueSimDraw>();
            com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionPassPlan.runDecalReceivers(
                    draws, scratch, true, OpaqueSimDraw::material, OpaqueSimDraw::hasMotion,
                    (group, snapshotPrePassDepth) -> {
                        float[] replaySnapshot = {snapshotPrePassDepth ? depthBuf[0] : Float.NaN};
                        // Color / decal receiver pass
                        for (var d : group) {
                            var op = compareOf.apply(d.material().depth().compare());
                            if (passReverseZ.test(d.deviceZ(), java.util.Map.entry(op, depthBuf[0]))) {
                                if (com.kltyton.lumenfx.client.render.EffectFrame.writesOpaqueDepth(d.material())) {
                                    depthBuf[0] = d.deviceZ();
                                }
                            }
                        }
                        // drawMotion (real motion OR zeroOpaque clear for !hasMotion opaque depth writers)
                        for (var d : group) {
                            if (!com.kltyton.lumenfx.client.render.EffectFrame.writesOpaqueDepth(d.material())) continue;
                            try {
                                var state = (com.mojang.blaze3d.pipeline.DepthStencilState)
                                        motionDepthStateMethod.invoke(null, d.material(), snapshotPrePassDepth);
                                float[] motionDepthRef = snapshotPrePassDepth ? replaySnapshot : depthBuf;
                                if (passReverseZ.test(d.deviceZ(), java.util.Map.entry(state.depthTest(), motionDepthRef[0]))) {
                                    if (state.writeDepth()) motionDepthRef[0] = d.deviceZ();
                                    if (d.hasMotion() && d.material().writesVelocity()) {
                                        velBuf[0] = d.velocityX();
                                        covBuf[0] = d.deviceZ();
                                        offBuf[0] = d.offsetX();
                                    } else {
                                        velBuf[0] = 0.0f;
                                        covBuf[0] = 0.0f;
                                        offBuf[0] = 0.0f;
                                    }
                                }
                            } catch (ReflectiveOperationException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    });
            float resolvedVel = Float.compare(depthBuf[0], covBuf[0]) == 0 ? velBuf[0] : 0.0f;
            return new float[]{depthBuf[0], resolvedVel, covBuf[0], offBuf[0]};
        };
        var aEqualRejected = new OpaqueSimDraw("A_equal_06", opaqueEqualMat, 0.6f, true, 6.5f, 2.0f);
        var bAlwaysStatic = new OpaqueSimDraw("B_always_static_06", opaqueAlwaysMat, 0.6f, false, 0.0f, 0.0f);
        float[] issue31BOnly = runOpaqueSequence.apply(java.util.List.of(bAlwaysStatic));
        float[] issue31AandB = runOpaqueSequence.apply(java.util.List.of(aEqualRejected, bAlwaysStatic));
        assertEquals(0.6f, issue31AandB[0]);
        assertEquals(issue31BOnly[1], issue31AandB[1], "Issue 31: Rejected EQUAL A followed by static ALWAYS B must produce 0 velocity");
        assertEquals(issue31BOnly[2], issue31AandB[2], "Issue 31: Static ALWAYS B must clear A's coverage depth");
        assertEquals(issue31BOnly[3], issue31AandB[3], "Issue 31: Static ALWAYS B must clear A's motionOffset");
        // Control: Visible LESS_EQUAL moving A followed by coplanar LESS non-motion B (rejected by color pass) -> A preserved!
        var aVisibleMover = new OpaqueSimDraw("A_lessEqual_06", opaqueZWriteOn, 0.6f, true, 6.5f, 2.0f);
        var bCoplanarLessStatic = new OpaqueSimDraw("B_less_static_06", opaqueLessMat, 0.6f, false, 0.0f, 0.0f);
        float[] issue31Control = runOpaqueSequence.apply(java.util.List.of(aVisibleMover, bCoplanarLessStatic));
        assertEquals(6.5f, issue31Control[1], "Issue 31 control: Rejected coplanar LESS static B must NOT clear visible A's velocity");
        assertEquals(2.0f, issue31Control[3], "Issue 31 control: Rejected coplanar LESS static B must NOT clear visible A's motionOffset");

        // Verify ParticleMotionDepth, WorldHdrCapture, MotionPipelineFactory, and EffectGpuRenderer translucent replay wiring exist.
        assertNotNull(com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionDepth.class.getDeclaredMethod("isActive"));
        assertNotNull(com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionDepth.class.getDeclaredMethod("preColorView"));
        assertNotNull(com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionDepth.class.getDeclaredMethod("clearPreColorCapture"));
        assertNotNull(com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionDepth.class.getDeclaredMethod(
                "snapshotPreColorDepth", com.mojang.blaze3d.systems.CommandEncoder.class, com.mojang.blaze3d.pipeline.RenderTarget.class));
        assertNotNull(com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionDepth.class.getDeclaredMethod(
                "executeTranslucentMotionLog", com.mojang.blaze3d.systems.CommandEncoder.class,
                com.mojang.blaze3d.pipeline.RenderTarget.class,
                com.kltyton.lumenfx.client.render.temporal.geometry.GeometryMotionRenderer.CoverageTargets.class,
                com.mojang.blaze3d.systems.ScissorState.class, java.util.List.class, long.class,
                java.util.function.BiConsumer.class, java.util.function.Consumer.class));
        assertNotNull(com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionDepth.class.getDeclaredMethod(
                "beginTranslucentStepReplay", com.mojang.blaze3d.systems.CommandEncoder.class,
                com.mojang.blaze3d.pipeline.RenderTarget.class));
        assertNotNull(com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionDepth.class.getDeclaredMethod(
                "runTranslucentStepPrimitive", com.mojang.blaze3d.systems.CommandEncoder.class,
                com.mojang.blaze3d.pipeline.RenderTarget.class,
                com.kltyton.lumenfx.client.render.temporal.geometry.GeometryMotionRenderer.CoverageTargets.class,
                com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.class, boolean.class,
                int.class, int.class, boolean.class, com.mojang.blaze3d.systems.ScissorState.class,
                java.util.function.BiConsumer.class));
        assertNotNull(com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture.class.getDeclaredMethod("hasParticleMotionDepth"));
        assertNotNull(com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture.class.getDeclaredMethod("translucentMotionPreColorDepth"));
        assertNotNull(com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture.class.getDeclaredMethod("clearTranslucentMotionPreColorDepth"));
        assertNotNull(com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture.class.getDeclaredMethod(
                "snapshotTranslucentMotionPreColorDepth", com.mojang.blaze3d.systems.CommandEncoder.class, com.mojang.blaze3d.pipeline.RenderTarget.class));
        assertNotNull(com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture.class.getDeclaredMethod(
                "executeTranslucentMotionLog", com.mojang.blaze3d.systems.CommandEncoder.class,
                com.mojang.blaze3d.pipeline.RenderTarget.class,
                com.kltyton.lumenfx.client.render.temporal.geometry.GeometryMotionRenderer.CoverageTargets.class,
                com.mojang.blaze3d.systems.ScissorState.class, java.util.List.class, long.class,
                java.util.function.BiConsumer.class, java.util.function.Consumer.class));
        assertNotNull(com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture.class.getDeclaredMethod(
                "beginTranslucentStepReplay", com.mojang.blaze3d.systems.CommandEncoder.class,
                com.mojang.blaze3d.pipeline.RenderTarget.class));
        assertNotNull(com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture.class.getDeclaredMethod(
                "runTranslucentStepPrimitive", com.mojang.blaze3d.systems.CommandEncoder.class,
                com.mojang.blaze3d.pipeline.RenderTarget.class,
                com.kltyton.lumenfx.client.render.temporal.geometry.GeometryMotionRenderer.CoverageTargets.class,
                com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.class, boolean.class,
                int.class, int.class, boolean.class, com.mojang.blaze3d.systems.ScissorState.class,
                java.util.function.BiConsumer.class));
        assertNotNull(com.kltyton.lumenfx.client.render.motion.shader.MotionPipelineFactory.class.getDeclaredMethod(
                "translucentMotionLog", com.mojang.blaze3d.pipeline.RenderPipeline.class,
                com.kltyton.lumenfx.client.render.motion.shader.MotionShaderSet.class,
                net.minecraft.resources.Identifier.class, com.mojang.blaze3d.pipeline.BindGroupLayout.class,
                boolean.class));
        assertNotNull(com.kltyton.lumenfx.client.render.motion.shader.MotionPipelineFactory.class.getDeclaredMethod(
                "translucentStepCapture", com.mojang.blaze3d.pipeline.RenderPipeline.class,
                com.kltyton.lumenfx.client.render.motion.shader.MotionShaderSet.class,
                net.minecraft.resources.Identifier.class, com.mojang.blaze3d.pipeline.BindGroupLayout.class,
                com.mojang.blaze3d.pipeline.DepthStencilState.class, boolean.class, boolean.class));
        assertNotNull(com.kltyton.lumenfx.client.render.EffectGpuRenderer.class.getDeclaredMethod(
                "computeMaxTranslucentPrimitives", java.util.List.class));
        assertNotNull(com.kltyton.lumenfx.client.render.EffectGpuRenderer.class.getDeclaredMethod(
                "drawTranslucentStepReplay", com.mojang.blaze3d.systems.CommandEncoder.class,
                com.mojang.blaze3d.pipeline.RenderTarget.class,
                com.kltyton.lumenfx.client.render.temporal.geometry.GeometryMotionRenderer.CoverageTargets.class,
                java.util.List.class, com.mojang.blaze3d.buffers.GpuBufferSlice.class,
                com.mojang.blaze3d.buffers.GpuBufferSlice.class, int.class,
                com.mojang.blaze3d.buffers.GpuBufferSlice.class, com.mojang.blaze3d.textures.GpuSampler.class,
                net.minecraft.client.Minecraft.class, com.mojang.blaze3d.buffers.GpuBuffer.class,
                com.mojang.blaze3d.IndexType.class, com.mojang.blaze3d.systems.ScissorState.class));
        String vtFeedbackShader = java.nio.file.Files.readString(java.nio.file.Path.of(
                "common/src/main/resources/assets/lumenfx/shaders/include/material_virtual_texture_feedback.glsl"));
        assertTrue(vtFeedbackShader.contains("!LFX_PARTICLE_MOTION && !LFX_SUBSTRATE_COVERAGE_REPLAY && !LFX_NANITE_RASTER_MATERIAL && !LFX_MOTION_AUXILIARY_PASS"),
                "Issue 34: RVT feedback must be gated off in motion and auxiliary passes while remaining active in real color pass");

        var translucentNotEqualWriteDepth = new com.kltyton.lumenfx.effect.MaterialDefinition(
                com.kltyton.lumenfx.LumenFx.id("core/color"), Map.of(),
                com.kltyton.lumenfx.effect.MaterialDefinition.Blend.ALPHA,
                new com.kltyton.lumenfx.effect.render.MaterialDepth(com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.NOT_EQUAL, true),
                false, new float[]{0, 0, 0, 0},
                com.kltyton.lumenfx.effect.RenderSettings.STANDARD, com.kltyton.lumenfx.effect.render.MaterialFog.NONE,
                com.kltyton.lumenfx.effect.render.TranslucencyPass.AFTER_DOF, 0, false, false, false, false, "",
                true, false);
        var translucentNotEqualNoDepth = new com.kltyton.lumenfx.effect.MaterialDefinition(
                com.kltyton.lumenfx.LumenFx.id("core/color"), Map.of(),
                com.kltyton.lumenfx.effect.MaterialDefinition.Blend.ALPHA,
                new com.kltyton.lumenfx.effect.render.MaterialDepth(com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.NOT_EQUAL, false),
                false, new float[]{0, 0, 0, 0},
                com.kltyton.lumenfx.effect.RenderSettings.STANDARD, com.kltyton.lumenfx.effect.render.MaterialFog.NONE,
                com.kltyton.lumenfx.effect.render.TranslucencyPass.AFTER_DOF, 0, false, false, false, false, "",
                true, false);
        var translucentAlwaysWriteDepth = new com.kltyton.lumenfx.effect.MaterialDefinition(
                com.kltyton.lumenfx.LumenFx.id("core/color"), Map.of(),
                com.kltyton.lumenfx.effect.MaterialDefinition.Blend.ALPHA,
                new com.kltyton.lumenfx.effect.render.MaterialDepth(com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.ALWAYS, true),
                false, new float[]{0, 0, 0, 0},
                com.kltyton.lumenfx.effect.RenderSettings.STANDARD, com.kltyton.lumenfx.effect.render.MaterialFog.NONE,
                com.kltyton.lumenfx.effect.render.TranslucencyPass.AFTER_DOF, 0, false, false, false, false, "",
                true, false);
        var translucentEqualWriteDepth = new com.kltyton.lumenfx.effect.MaterialDefinition(
                com.kltyton.lumenfx.LumenFx.id("core/color"), Map.of(),
                com.kltyton.lumenfx.effect.MaterialDefinition.Blend.ALPHA,
                new com.kltyton.lumenfx.effect.render.MaterialDepth(com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.EQUAL, true),
                false, new float[]{0, 0, 0, 0},
                com.kltyton.lumenfx.effect.RenderSettings.STANDARD, com.kltyton.lumenfx.effect.render.MaterialFog.NONE,
                com.kltyton.lumenfx.effect.render.TranslucencyPass.AFTER_DOF, 0, false, false, false, false, "",
                true, false);
        var translucentEqualNoDepth = new com.kltyton.lumenfx.effect.MaterialDefinition(
                com.kltyton.lumenfx.LumenFx.id("core/color"), Map.of(),
                com.kltyton.lumenfx.effect.MaterialDefinition.Blend.ALPHA,
                new com.kltyton.lumenfx.effect.render.MaterialDepth(com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.EQUAL, false),
                false, new float[]{0, 0, 0, 0},
                com.kltyton.lumenfx.effect.RenderSettings.STANDARD, com.kltyton.lumenfx.effect.render.MaterialFog.NONE,
                com.kltyton.lumenfx.effect.render.TranslucencyPass.AFTER_DOF, 0, false, false, false, false, "",
                true, false);
        var translucentNonVelNotEqualDepth = new com.kltyton.lumenfx.effect.MaterialDefinition(
                com.kltyton.lumenfx.LumenFx.id("core/color"), Map.of(),
                com.kltyton.lumenfx.effect.MaterialDefinition.Blend.ALPHA,
                new com.kltyton.lumenfx.effect.render.MaterialDepth(com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.NOT_EQUAL, true),
                false, new float[]{0, 0, 0, 0},
                com.kltyton.lumenfx.effect.RenderSettings.STANDARD, com.kltyton.lumenfx.effect.render.MaterialFog.NONE,
                com.kltyton.lumenfx.effect.render.TranslucencyPass.AFTER_DOF, 0, false, false, false, false, "",
                false, false);
        assertTrue(com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionPassPlan
                .usesTranslucentColorReplay(translucentNotEqualWriteDepth));
        assertTrue(com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionPassPlan
                .usesTranslucentColorReplay(translucentVelGreaterDepth));
        assertTrue(com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionPassPlan
                .usesTranslucentColorReplay(translucentLessWriteDepth));
        assertTrue(com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionPassPlan
                .usesTranslucentColorReplay(translucentAlwaysWriteDepth),
                "Round 14 P1: Write-depth ALWAYS translucent velocity batch MUST enter ordered color replay (cannot bypass)");
        assertFalse(com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionPassPlan
                .usesTranslucentColorReplay(translucentNotEqualNoDepth));
        assertFalse(com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionPassPlan
                .usesTranslucentColorReplay(translucentNonVelGreaterDepth));
        var alwaysPrep = com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionPassPlan.inspectGroup(
                java.util.List.of(translucentAlwaysWriteDepth), m -> m, m -> true, m -> true);
        assertTrue(alwaysPrep.seedTranslucentMotionDepth() && alwaysPrep.snapshotTranslucentPreColorDepth(),
                "Round 14 P1: Write-depth ALWAYS velocity batch MUST snapshot pre-color depth before color pass");
        assertTrue(com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionPassPlan
                .participatesInTranslucentMotionLog(translucentAlwaysWriteDepth, true, true, false));
        assertTrue(com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionPassPlan
                .participatesInTranslucentMotionLog(translucentNonVelNotEqualDepth, true, false, true));
        assertFalse(com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionPassPlan
                .participatesInTranslucentMotionLog(translucentNonVelNotEqualDepth, true, false, false));

        record Prim(float deviceZ, float velocityX, boolean coversPx0, boolean coversPx1, boolean discardsPx0, float opacity) {
            Prim(float deviceZ, float velocityX, boolean coversPx0, boolean coversPx1, boolean discardsPx0) {
                this(deviceZ, velocityX, coversPx0, coversPx1, discardsPx0, 0.8f);
            }
            boolean covers(int px) { return px == 0 ? (coversPx0 && !discardsPx0) : coversPx1; }
            // Real UE MaterialCompiler.cs translucent velocity opacity threshold (default OpacityMaskClipValue = 0.3333)
            boolean velocityEligible() { return (opacity - 1.0f / 255.0f - 0.3333f) >= 0.0f; }
        }
        record MultiPrimBatch(String name, com.kltyton.lumenfx.effect.MaterialDefinition material, java.util.List<Prim> prims) { }
        java.util.function.BiFunction<Float, java.util.List<java.util.List<MultiPrimBatch>>, float[][]> runMultiPrimTranslucent =
                (bgDepth, stages) -> {
                    float[] motionDepth = {Float.NaN, Float.NaN};
                    boolean[] motionDepthActive = {false};
                    float[] velOut = {Float.NaN, Float.NaN};
                    float[] covDepth = {Float.NaN, Float.NaN};
                    float[] lastPublishedStageDepth = {bgDepth, bgDepth};
                    float[] finalColorDepth = {bgDepth, bgDepth};
                    for (var stageBatches : stages) {
                        float[] stageDepth = lastPublishedStageDepth.clone();
                        float[] preColorDepth = {Float.NaN, Float.NaN};
                        boolean[] preColorCaptured = {false};
                        var scratch = new java.util.ArrayList<MultiPrimBatch>();
                        boolean[] stageDepthWrittenFlag = {false};
                        com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionPassPlan.runTranslucentGroups(
                                stageBatches, scratch, MultiPrimBatch::material, b -> true, b -> true,
                                group -> com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionPassPlan.runRasterGroup(
                                        group, true, MultiPrimBatch::material, b -> true, b -> true,
                                        () -> { throw new AssertionError("No opaque replay expected"); },
                                        () -> {
                                            if (!motionDepthActive[0]) {
                                                motionDepth[0] = stageDepth[0];
                                                motionDepth[1] = stageDepth[1];
                                                motionDepthActive[0] = true;
                                            }
                                        },
                                        () -> {
                                            if (motionDepthActive[0]) {
                                                preColorDepth[0] = stageDepth[0];
                                                preColorDepth[1] = stageDepth[1];
                                                preColorCaptured[0] = true;
                                            } else {
                                                preColorCaptured[0] = false;
                                            }
                                        },
                                        () -> {
                                            for (var b : group) {
                                                var op = compareOf.apply(b.material().depth().compare());
                                                for (var p : b.prims()) {
                                                    for (int px = 0; px < 2; px++) {
                                                        if (!p.covers(px)) continue;
                                                        if (passReverseZ.test(p.deviceZ(), java.util.Map.entry(op, stageDepth[px]))) {
                                                            if (b.material().depth().write()) {
                                                                stageDepth[px] = p.deviceZ();
                                                                stageDepthWrittenFlag[0] = true;
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        allowsFarther -> {
                                            // Unified ordered log resolve runs in drawMotion.
                                        },
                                        opaqueReplay -> {
                                            boolean groupHasRealVel = group.stream().anyMatch(b -> b.material().writesVelocity());
                                            boolean groupHasDepthSync = group.stream().anyMatch(b -> !b.material().writesVelocity()
                                                    && com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionPassPlan
                                                            .writesTranslucentColorDepth(b.material()));
                                            if (!groupHasRealVel && (!motionDepthActive[0] || !groupHasDepthSync)) {
                                                preColorCaptured[0] = false;
                                                return;
                                            }
                                            assertTrue(preColorCaptured[0], "Ordered translucent log requires pre-color depth snapshot");
                                            @SuppressWarnings("unchecked")
                                            java.util.List<com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.FragmentNode>[] perPixel =
                                                    new java.util.List[] { new java.util.ArrayList<>(), new java.util.ArrayList<>() };
                                            int drawIndex = 0;
                                            for (var b : group) {
                                                boolean realMotion = b.material().writesVelocity();
                                                boolean writesDepth = com.kltyton.lumenfx.client.render.temporal.geometry.ParticleMotionPassPlan
                                                        .writesTranslucentColorDepth(b.material());
                                                if (!realMotion && !writesDepth) continue;
                                                for (int primIdx = 0; primIdx < b.prims().size(); primIdx++) {
                                                    var p = b.prims().get(primIdx);
                                                    for (int px = 0; px < 2; px++) {
                                                        if (!p.covers(px)) continue;
                                                        boolean eligible = realMotion && p.velocityEligible();
                                                        if (!eligible && !writesDepth) continue;
                                                        perPixel[px].add(new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.FragmentNode(
                                                                drawIndex, primIdx, 0, p.deviceZ(), b.material().depth().compare(),
                                                                writesDepth, eligible, p.deviceZ(),
                                                                eligible ? p.velocityX() : 0.0f, 0.0f, 0.0f, eligible ? 1.0f : 0.0f));
                                                    }
                                                }
                                                drawIndex++;
                                            }
                                            for (int px = 0; px < 2; px++) {
                                                // Reverse list order to simulate out-of-order GPU atomicExchange insertion!
                                                java.util.Collections.reverse(perPixel[px]);
                                                var init = new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.PixelState(
                                                        preColorDepth[px], motionDepth[px],
                                                        Float.isNaN(velOut[px]) ? 0.0f : velOut[px], 0.0f, 0.0f,
                                                        Float.isNaN(velOut[px]) ? 0.0f : 1.0f,
                                                        Float.isNaN(covDepth[px]) ? 0.0f : covDepth[px],
                                                        Float.isNaN(covDepth[px]) ? -1.0f : covDepth[px]);
                                                var res = com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog
                                                        .resolvePixel(init, perPixel[px]);
                                                motionDepth[px] = res.motionDepth();
                                                velOut[px] = res.prevNdcW() >= 1.0f ? res.prevNdcX() : Float.NaN;
                                                covDepth[px] = res.covFragDepth() >= 0.0f ? res.covFragDepth() : Float.NaN;
                                            }
                                            preColorCaptured[0] = false;
                                        }));
                        if (stageDepthWrittenFlag[0]) lastPublishedStageDepth = stageDepth;
                        finalColorDepth = stageDepth;
                    }
                    float[] resolved = new float[2];
                    for (int px = 0; px < 2; px++) {
                        resolved[px] = (motionDepthActive[0] && Float.compare(motionDepth[px], covDepth[px]) == 0)
                                ? velOut[px] : 0.0f;
                    }
                    return new float[][]{resolved, motionDepth, covDepth, finalColorDepth};
                };

        // 32a: Primary counterexample — single batch NOT_EQUAL [P=0.4, Q=0.2] on bg=0.2 MUST match split [P] then [Q]!
        var primP04 = new Prim(0.4f, 4.5f, true, true, false);
        var primQ02 = new Prim(0.2f, 8.25f, true, false, false);
        var primR02 = new Prim(0.2f, 9.75f, true, false, false);
        float[][] singlePQ = runMultiPrimTranslucent.apply(0.2f, java.util.List.of(java.util.List.of(
                new MultiPrimBatch("PQ_single", translucentNotEqualWriteDepth, java.util.List.of(primP04, primQ02)))));
        float[][] splitPQ = runMultiPrimTranslucent.apply(0.2f, java.util.List.of(java.util.List.of(
                new MultiPrimBatch("P", translucentNotEqualWriteDepth, java.util.List.of(primP04)),
                new MultiPrimBatch("Q", translucentNotEqualWriteDepth, java.util.List.of(primQ02)))));
        assertEquals(0.2f, singlePQ[3][0], "Issue 32: Color depth after P=0.4 -> Q=0.2 must return to 0.2");
        assertEquals(0.2f, singlePQ[1][0], "Issue 32: Motion depth must match Q's 0.2, not stale P's 0.4");
        assertEquals(8.25f, singlePQ[0][0], "Issue 32: Single-batch NOT_EQUAL must resolve Q's velocity (8.25)");
        assertEquals(4.5f, singlePQ[0][1], "Issue 32: Pixel 1 covered only by P must keep P's velocity (4.5)");
        assertEquals(splitPQ[0][0], singlePQ[0][0], "Issue 32: Single batch must equal split batches (resolved vel)");
        assertEquals(splitPQ[1][0], singlePQ[1][0], "Issue 32: Single batch must equal split batches (motion depth)");
        assertEquals(splitPQ[3][0], singlePQ[3][0], "Issue 32: Single batch must equal split batches (color depth)");

        // 32b: Reverse order [Q=0.2, P=0.4], 3-primitive [P=0.4, Q=0.2, R=0.2], discard P / discard Q, and depth_write=false.
        float[][] singleQP = runMultiPrimTranslucent.apply(0.2f, java.util.List.of(java.util.List.of(
                new MultiPrimBatch("QP", translucentNotEqualWriteDepth, java.util.List.of(primQ02, primP04)))));
        float[][] splitQP = runMultiPrimTranslucent.apply(0.2f, java.util.List.of(java.util.List.of(
                new MultiPrimBatch("Q", translucentNotEqualWriteDepth, java.util.List.of(primQ02)),
                new MultiPrimBatch("P", translucentNotEqualWriteDepth, java.util.List.of(primP04)))));
        assertEquals(4.5f, singleQP[0][0]);
        assertEquals(splitQP[0][0], singleQP[0][0]);
        float[][] singlePQR = runMultiPrimTranslucent.apply(0.2f, java.util.List.of(java.util.List.of(
                new MultiPrimBatch("PQR", translucentNotEqualWriteDepth, java.util.List.of(primP04, primQ02, primR02)))));
        float[][] splitPQR = runMultiPrimTranslucent.apply(0.2f, java.util.List.of(java.util.List.of(
                new MultiPrimBatch("P", translucentNotEqualWriteDepth, java.util.List.of(primP04)),
                new MultiPrimBatch("Q", translucentNotEqualWriteDepth, java.util.List.of(primQ02)),
                new MultiPrimBatch("R", translucentNotEqualWriteDepth, java.util.List.of(primR02)))));
        assertEquals(8.25f, singlePQR[0][0], "Issue 32: Third coplanar R=0.2 must fail 0.2!=0.2, keeping Q's 8.25");
        assertEquals(splitPQR[0][0], singlePQR[0][0]);
        var primPDiscard = new Prim(0.4f, 4.5f, true, false, true);
        var primQDiscard = new Prim(0.2f, 8.25f, true, false, true);
        float[][] singleDiscardP = runMultiPrimTranslucent.apply(0.2f, java.util.List.of(java.util.List.of(
                new MultiPrimBatch("dPQ", translucentNotEqualWriteDepth, java.util.List.of(primPDiscard, primQ02)))));
        float[][] splitDiscardP = runMultiPrimTranslucent.apply(0.2f, java.util.List.of(java.util.List.of(
                new MultiPrimBatch("dP", translucentNotEqualWriteDepth, java.util.List.of(primPDiscard)),
                new MultiPrimBatch("Q", translucentNotEqualWriteDepth, java.util.List.of(primQ02)))));
        assertEquals(0.0f, singleDiscardP[0][0], "Issue 32 discard: Discarded P leaves color=0.2 so Q=0.2 fails");
        assertEquals(splitDiscardP[0][0], singleDiscardP[0][0]);
        float[][] singleDiscardQ = runMultiPrimTranslucent.apply(0.2f, java.util.List.of(java.util.List.of(
                new MultiPrimBatch("PdQ", translucentNotEqualWriteDepth, java.util.List.of(primP04, primQDiscard)))));
        float[][] splitDiscardQ = runMultiPrimTranslucent.apply(0.2f, java.util.List.of(java.util.List.of(
                new MultiPrimBatch("P", translucentNotEqualWriteDepth, java.util.List.of(primP04)),
                new MultiPrimBatch("dQ", translucentNotEqualWriteDepth, java.util.List.of(primQDiscard)))));
        assertEquals(4.5f, singleDiscardQ[0][0], "Issue 32 discard: Discarded Q leaves P's 4.5");
        assertEquals(splitDiscardQ[0][0], singleDiscardQ[0][0]);
        float[][] singleNoDepth = runMultiPrimTranslucent.apply(0.2f, java.util.List.of(java.util.List.of(
                new MultiPrimBatch("PQ_noDepth", translucentNotEqualNoDepth, java.util.List.of(primP04, primQ02)))));
        float[][] splitNoDepth = runMultiPrimTranslucent.apply(0.2f, java.util.List.of(java.util.List.of(
                new MultiPrimBatch("P_noDepth", translucentNotEqualNoDepth, java.util.List.of(primP04)),
                new MultiPrimBatch("Q_noDepth", translucentNotEqualNoDepth, java.util.List.of(primQ02)))));
        assertEquals(4.5f, singleNoDepth[0][0], "Issue 32 depth_write=false: Q fails against unchanged bg=0.2, keeping P");
        assertEquals(splitNoDepth[0][0], singleNoDepth[0][0]);

        // 32c: Counted NOT_EQUAL theorem — foreground A=0.6 (depth_write=false) followed by single batch [P=0.4, Q=0.6]
        // vs single primitive [Q=0.6] only: 2 passes -> Q wins (matches split); 1 pass equal to motionDepth(0.6) -> A survives!
        var primA06 = new Prim(0.6f, 1.5f, true, true, false);
        var primQ06 = new Prim(0.6f, 6.75f, true, false, false);
        var aForegroundBatch = new MultiPrimBatch("A_06_noDepth", translucentLessEqual, java.util.List.of(primA06));
        float[][] singleAPQ06 = runMultiPrimTranslucent.apply(0.2f, java.util.List.of(java.util.List.of(
                aForegroundBatch,
                new MultiPrimBatch("PQ06", translucentNotEqualWriteDepth, java.util.List.of(primP04, primQ06)))));
        float[][] splitAPQ06 = runMultiPrimTranslucent.apply(0.2f, java.util.List.of(java.util.List.of(
                aForegroundBatch,
                new MultiPrimBatch("P04", translucentNotEqualWriteDepth, java.util.List.of(primP04)),
                new MultiPrimBatch("Q06", translucentNotEqualWriteDepth, java.util.List.of(primQ06)))));
        assertEquals(6.75f, singleAPQ06[0][0], "Issue 32 counted theorem: 2 passing NOT_EQUAL prims must let Q=0.6 overwrite A=0.6");
        assertEquals(splitAPQ06[0][0], singleAPQ06[0][0]);
        float[][] singleAQ06Only = runMultiPrimTranslucent.apply(0.2f, java.util.List.of(java.util.List.of(
                aForegroundBatch,
                new MultiPrimBatch("Q06_only", translucentNotEqualWriteDepth, java.util.List.of(primQ06)))));
        assertEquals(1.5f, singleAQ06Only[0][0], "Issue 32 counted contrast: single NOT_EQUAL prim equal to motionDepth(0.6) preserves A");

        // 32d: LESS and GREATER multi-primitive single batch vs split + cross-stage equivalence.
        var prim03 = new Prim(0.3f, 3.0f, true, false, false);
        var prim04 = new Prim(0.4f, 4.0f, true, false, false);
        var prim06 = new Prim(0.6f, 6.0f, true, false, false);
        float[][] lessSingle = runMultiPrimTranslucent.apply(0.2f, java.util.List.of(java.util.List.of(
                new MultiPrimBatch("L64", translucentLessWriteDepth, java.util.List.of(prim06, prim04)))));
        float[][] lessSplit = runMultiPrimTranslucent.apply(0.2f, java.util.List.of(java.util.List.of(
                new MultiPrimBatch("L6", translucentLessWriteDepth, java.util.List.of(prim06)),
                new MultiPrimBatch("L4", translucentLessWriteDepth, java.util.List.of(prim04)))));
        assertEquals(6.0f, lessSingle[0][0]);
        assertEquals(lessSplit[0][0], lessSingle[0][0]);
        float[][] greaterSingle = runMultiPrimTranslucent.apply(0.5f, java.util.List.of(java.util.List.of(
                new MultiPrimBatch("G34", translucentVelGreaterDepth, java.util.List.of(prim03, prim04)))));
        float[][] greaterSplit = runMultiPrimTranslucent.apply(0.5f, java.util.List.of(java.util.List.of(
                new MultiPrimBatch("G3", translucentVelGreaterDepth, java.util.List.of(prim03)),
                new MultiPrimBatch("G4", translucentVelGreaterDepth, java.util.List.of(prim04)))));
        assertEquals(3.0f, greaterSingle[0][0]);
        assertEquals(greaterSplit[0][0], greaterSingle[0][0]);
        float[][] crossSingle = runMultiPrimTranslucent.apply(0.2f, java.util.List.of(
                java.util.List.of(new MultiPrimBatch("PQ_s1", translucentNotEqualWriteDepth, java.util.List.of(primP04, primQ02))),
                java.util.List.of(new MultiPrimBatch("R_s2", translucentNotEqualWriteDepth, java.util.List.of(primR02)))));
        float[][] crossSplit = runMultiPrimTranslucent.apply(0.2f, java.util.List.of(
                java.util.List.of(
                        new MultiPrimBatch("P_s1", translucentNotEqualWriteDepth, java.util.List.of(primP04)),
                        new MultiPrimBatch("Q_s1", translucentNotEqualWriteDepth, java.util.List.of(primQ02))),
                java.util.List.of(new MultiPrimBatch("R_s2", translucentNotEqualWriteDepth, java.util.List.of(primR02)))));
        assertEquals(8.25f, crossSingle[0][0], "Issue 32 cross-stage: Stage 2 R=0.2 fails against Stage 1 colorDepth=0.2, keeping Q");
        assertEquals(crossSplit[0][0], crossSingle[0][0]);

        // 30c BOTH velocity states (B.vel=false preserves foreground A; B.vel=true writes B's valid surface velocity; 30b rejected B preserves A).
        var primB01NoVel = new Prim(0.1f, 0.0f, true, false, false);
        var primB01WithVel = new Prim(0.1f, 7.25f, true, false, false);
        var primB04WithVel = new Prim(0.4f, 7.25f, true, false, false);
        float[][] case30cVelOff = runMultiPrimTranslucent.apply(0.2f, java.util.List.of(java.util.List.of(
                aForegroundBatch, new MultiPrimBatch("B_01_noVel", translucentNonVelGreaterDepth, java.util.List.of(primB01NoVel)))));
        assertEquals(0.1f, case30cVelOff[3][0]);
        assertEquals(0.6f, case30cVelOff[1][0]);
        assertEquals(1.5f, case30cVelOff[0][0], "Issue 30c (vel=false): Foreground A velocity preserved when B replaces bg 0.2 -> 0.1");
        float[][] case30cVelOn = runMultiPrimTranslucent.apply(0.2f, java.util.List.of(java.util.List.of(
                aForegroundBatch, new MultiPrimBatch("B_01_vel", translucentVelGreaterDepth, java.util.List.of(primB01WithVel)))));
        assertEquals(0.1f, case30cVelOn[3][0]);
        assertEquals(0.1f, case30cVelOn[1][0]);
        assertEquals(0.1f, case30cVelOn[2][0]);
        assertEquals(7.25f, case30cVelOn[0][0], "Issue 30c (vel=true): Visible GREATER B writes its own matched surface velocity 7.25");
        float[][] case30bReplay = runMultiPrimTranslucent.apply(0.2f, java.util.List.of(java.util.List.of(
                aForegroundBatch, new MultiPrimBatch("B_04_vel", translucentVelGreaterDepth, java.util.List.of(primB04WithVel)))));
        assertEquals(0.2f, case30bReplay[3][0]);
        assertEquals(0.6f, case30bReplay[1][0]);
        assertEquals(1.5f, case30bReplay[0][0], "Issue 30b (color replay): Color-rejected GREATER B=0.4 preserves foreground A=0.6");

        // Issue 33 (33a & 33b): Real UE Mesh MaterialCompiler fragment shaders through production MotionShaderAdapter + GlslSourceScanner.
        String minimalVertex = "#version 330\nvoid main() { gl_Position = vec4(0.0, 0.0, 0.0, 1.0); }\n";
        String ueTranslucentNoPdoMeshFrag = """
                #version 330
                #if LFX_NANITE_MATERIAL
                vec3 effectPosition;
                #else
                in vec3 effectPosition;
                #endif
                out vec4 fragColor;
                void main() {
                #if LFX_NANITE_MATERIAL
                    effectPosition = lfxNaniteGeometry.position.xzy * 0.01;
                    gl_FragDepth = lfxNaniteGeometry.deviceDepth;
                #endif
                    fragColor = vec4(1.0);
                }
                """;
        String ueTranslucentWithPdoMeshFrag = """
                #version 330
                #if !LFX_PARTICLE_POINT_SHADOW
                #define LFX_MATERIAL_WRITES_DEPTH 1
                float lfxMaterialDeviceZ;
                float lfxDepthOffsetZ = 0.0;
                float lfxDepthOffsetW = 0.0;
                float lfx_apply_pixel_depth_offset(float o, bool c) { return gl_FragCoord.z - o * 0.001; }
                #endif
                out vec4 fragColor;
                void main() {
                #if LFX_NANITE_MATERIAL
                    gl_FragDepth = lfxNaniteGeometry.deviceDepth;
                #endif
                #if LFX_MATERIAL_WRITES_DEPTH
                    lfxMaterialDeviceZ = lfx_apply_pixel_depth_offset(5.0, false);
                    gl_FragDepth = lfxMaterialDeviceZ;
                #endif
                    fragColor = vec4(1.0);
                }
                """;
        String ueMaskedMeshFrag = """
                #version 330
                #if !LFX_NANITE_SOFTWARE_RASTER
                out vec4 fragColor;
                #endif
                #if LFX_NANITE_SOFTWARE_RASTER
                bool lfx_nanite_evaluate_coverage() {
                #else
                void main() {
                #endif
                #if LFX_NANITE_MATERIAL
                    gl_FragDepth = lfxNaniteGeometry.deviceDepth;
                #endif
                #if LFX_NANITE_SOFTWARE_RASTER
                    float rawOpacity = 0.2;
                    return (rawOpacity - 0.3333) >= 0.0;
                #else
                    float rawOpacity = 0.2;
                    float opacity = clamp(rawOpacity, 0.0, 1.0);
                #if LFX_NANITE_RASTER_MATERIAL
                    if (!((rawOpacity - 0.3333) >= 0.0)) discard;
                #elif !LFX_NANITE_MATERIAL
                #if LFX_PARTICLE_POINT_SHADOW || LFX_PARTICLE_CUSTOM_DEPTH
                    if (rawOpacity < 0.3333) discard;
                #else
                    if (opacity < 0.3333) discard;
                #endif
                #endif
                    fragColor = vec4(1.0, 0.5, 0.25, 1.0);
                #endif
                }
                """;
        String ueOpaquePdoMeshFrag = """
                #version 330
                #if !LFX_PARTICLE_POINT_SHADOW
                #define LFX_MATERIAL_WRITES_DEPTH 1
                float lfxMaterialDeviceZ;
                float lfxDepthOffsetZ = 0.0;
                float lfxDepthOffsetW = 0.0;
                float lfx_apply_pixel_depth_offset(float o, bool c) { return gl_FragCoord.z - o * 0.001; }
                #endif
                #if !LFX_NANITE_SOFTWARE_RASTER
                out vec4 fragColor;
                #endif
                #if LFX_NANITE_SOFTWARE_RASTER
                bool lfx_nanite_evaluate_coverage() {
                #else
                void main() {
                #endif
                #if LFX_NANITE_MATERIAL
                    gl_FragDepth = lfxNaniteGeometry.deviceDepth;
                #endif
                #if LFX_MATERIAL_WRITES_DEPTH
                #if LFX_NANITE_MATERIAL && !LFX_NANITE_RASTER_MATERIAL
                    if (false) lfxMaterialDeviceZ = 0.5; else
                #elif LFX_NANITE_SOFTWARE_RASTER
                    if (false) { lfxMaterialDeviceZ = 0.5; } else
                #endif
                    lfxMaterialDeviceZ = lfx_apply_pixel_depth_offset(4.0, false);
                    gl_FragDepth = lfxMaterialDeviceZ;
                #endif
                #if LFX_NANITE_SOFTWARE_RASTER
                    return true;
                #else
                    fragColor = vec4(0.8, 0.8, 0.8, 1.0);
                #endif
                }
                """;
        String ueColoredTransmittanceFrag = """
                #version 330
                #if LFX_SUBSTRATE_DUAL_SOURCE && !LFX_PARTICLE_MOTION
                #define LFX_COLOR_OUTPUT layout(location = 0, index = 0)
                #define LFX_TRANSMITTANCE_OUTPUT layout(location = 0, index = 1) out
                #else
                #define LFX_COLOR_OUTPUT
                #define LFX_TRANSMITTANCE_OUTPUT
                #endif
                LFX_COLOR_OUTPUT out vec4 fragColor;
                LFX_TRANSMITTANCE_OUTPUT vec4 lfxDualTransmittance;
                void main() {
                #if LFX_NANITE_MATERIAL
                    gl_FragDepth = lfxNaniteGeometry.deviceDepth;
                #endif
                    fragColor = vec4(1.0);
                    lfxDualTransmittance = vec4(0.5);
                }
                """;
        var vtxId = com.kltyton.lumenfx.LumenFx.id("test/ue_mesh_v");
        var fragNoPdoId = com.kltyton.lumenfx.LumenFx.id("test/ue_mesh_no_pdo_f");
        var fragPdoId = com.kltyton.lumenfx.LumenFx.id("test/ue_mesh_pdo_f");
        var fragMaskedId = com.kltyton.lumenfx.LumenFx.id("test/ue_mesh_masked_f");
        var fragOpaquePdoId = com.kltyton.lumenfx.LumenFx.id("test/ue_mesh_opaque_pdo_f");
        var fragDualId = com.kltyton.lumenfx.LumenFx.id("test/ue_mesh_dual_f");
        com.mojang.blaze3d.shaders.ShaderSource ueSources = (id, type) -> {
            if (type == com.mojang.blaze3d.shaders.ShaderType.VERTEX && id.equals(vtxId)) return minimalVertex;
            if (type == com.mojang.blaze3d.shaders.ShaderType.FRAGMENT) {
                if (id.equals(fragNoPdoId)) return ueTranslucentNoPdoMeshFrag;
                if (id.equals(fragPdoId)) return ueTranslucentWithPdoMeshFrag;
                if (id.equals(fragMaskedId)) return ueMaskedMeshFrag;
                if (id.equals(fragOpaquePdoId)) return ueOpaquePdoMeshFrag;
                if (id.equals(fragDualId)) return ueColoredTransmittanceFrag;
            }
            return null;
        };
        var adapter = new com.kltyton.lumenfx.client.render.motion.shader.MotionShaderAdapter(ueSources);
        java.util.function.BiFunction<net.minecraft.resources.Identifier, Boolean, com.mojang.blaze3d.pipeline.RenderPipeline> makeMeshPipeline =
                (fragId, nanite) -> com.mojang.blaze3d.pipeline.RenderPipeline.builder()
                        .withLocation(com.kltyton.lumenfx.LumenFx.id("test/pipe_" + fragId.getPath() + (nanite ? "_nanite" : "_ord")))
                        .withVertexShader(vtxId)
                        .withFragmentShader(fragId)
                        .withPrimitiveTopology(com.mojang.blaze3d.PrimitiveTopology.TRIANGLES)
                        .withShaderDefine("LFX_NANITE_MATERIAL", nanite ? 1 : 0)
                        .withShaderDefine("LFX_PARTICLE_POINT_SHADOW", 0)
                        .withShaderDefine("LFX_PARTICLE_CUSTOM_DEPTH", 0)
                        .withShaderDefine("LFX_SUBSTRATE_DUAL_SOURCE", fragId.equals(fragDualId) ? 1 : 0)
                        .withColorTargetState(0, testTarget)
                        .withDepthStencilState(new com.mojang.blaze3d.pipeline.DepthStencilState(
                                com.mojang.blaze3d.platform.CompareOp.GREATER_THAN_OR_EQUAL, true))
                        .build();

        // 33a: Ordinary translucent UE Mesh WITHOUT PDO must NOT read undefined gl_FragDepth in guard or coverage depth!
        var noPdoOrdPipe = makeMeshPipeline.apply(fragNoPdoId, false);
        var noPdoSet = adapter.particleCoverage(noPdoOrdPipe, false, false,
                com.kltyton.lumenfx.client.render.motion.shader.MotionShaderAdapter.COLOR_DEPTH_COMPARE_LESS);
        String noPdoAdapted = noPdoSet.source().get(noPdoSet.fragmentShader(), com.mojang.blaze3d.shaders.ShaderType.FRAGMENT);
        assertNotNull(noPdoAdapted);
        String noPdoTail = noPdoAdapted.substring(noPdoAdapted.indexOf("#undef main"));
        assertFalse(noPdoTail.contains("gl_FragDepth"),
                "Issue 33a: Ordinary UE Mesh without PDO must NOT read undefined gl_FragDepth in velocity tail:\n" + noPdoTail);
        assertTrue(noPdoTail.contains("float lfxMotionFragDepth = gl_FragCoord.z;")
                && noPdoTail.contains("lfxMotionShader_motionDepth = vec2(currentClip.z / currentClip.w, gl_FragCoord.z);"),
                "Issue 33a: Ordinary UE Mesh without PDO must use gl_FragCoord.z for both guard and depth coverage");
        // Post-GlslPreprocessor.injectDefines sanity check matching GlDevice.compileShader
        var noPdoMotionPipe = com.kltyton.lumenfx.client.render.motion.shader.MotionPipelineFactory.coverage(
                noPdoOrdPipe, noPdoSet, com.kltyton.lumenfx.LumenFx.id("test/motion_no_pdo"), null, true,
                new com.mojang.blaze3d.pipeline.DepthStencilState(com.mojang.blaze3d.platform.CompareOp.LESS_THAN, true), false);
        String noPdoInjected = com.mojang.blaze3d.preprocessor.GlslPreprocessor.injectDefines(noPdoAdapted, noPdoMotionPipe.getShaderDefines());
        var noPdoPostScan = com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceScanner.scan(
                noPdoInjected, noPdoMotionPipe.getShaderDefines());
        assertTrue(noPdoAdapted.contains("#define main lfxMotionShader_originalMain") && noPdoAdapted.contains("#undef main"));
        assertEquals(2, noPdoPostScan.mains().size());
        assertEquals(java.util.List.of("lfxMotionShader_motionClip", "lfxMotionShader_motionDepth"),
                noPdoPostScan.outputs().stream().map(com.kltyton.lumenfx.client.resource.shader.glsl.GlslOutputDeclaration::name).toList());
        assertFalse(noPdoPostScan.identifiers().contains("gl_FragDepth"),
                "Issue 33a: Active compiled ordinary no-PDO shader must have zero active references to gl_FragDepth");

        // 33a PDO control: Ordinary translucent UE Mesh WITH PDO MUST preserve gl_FragDepth!
        var pdoOrdPipe = makeMeshPipeline.apply(fragPdoId, false);
        var pdoSet = adapter.particleCoverage(pdoOrdPipe, false, false,
                com.kltyton.lumenfx.client.render.motion.shader.MotionShaderAdapter.COLOR_DEPTH_COMPARE_GREATER);
        String pdoAdapted = pdoSet.source().get(pdoSet.fragmentShader(), com.mojang.blaze3d.shaders.ShaderType.FRAGMENT);
        String pdoTail = pdoAdapted.substring(pdoAdapted.indexOf("#undef main"));
        assertTrue(pdoTail.contains("gl_FragDepth = gl_FragCoord.z;")
                && pdoTail.contains("float lfxMotionFragDepth = gl_FragDepth;")
                && pdoTail.contains("lfxMotionShader_motionDepth = vec2(currentClip.z / currentClip.w, gl_FragDepth);"),
                "Issue 33a PDO control: UE Mesh with PDO must preserve gl_FragDepth in guard and depth coverage");

        // 33b: SAME Masked UE Mesh shader through Ordinary, Nanite, Fallback, ZeroOpaque (main + decal receiver), and Nanite hardware raster!
        var maskedOrdPipe = makeMeshPipeline.apply(fragMaskedId, false);
        var maskedNanitePipe = makeMeshPipeline.apply(fragMaskedId, true);
        var maskedOrdSet = adapter.particleCoverage(maskedOrdPipe, false, true,
                com.kltyton.lumenfx.client.render.motion.shader.MotionShaderAdapter.COLOR_DEPTH_COMPARE_NONE);
        String maskedOrdFrag = maskedOrdSet.source().get(maskedOrdSet.fragmentShader(), com.mojang.blaze3d.shaders.ShaderType.FRAGMENT);
        assertTrue(maskedOrdFrag.contains("vec4 fragColor;") && !maskedOrdFrag.contains("out vec4 fragColor;"),
                "Issue 33b: Masked ordinary Mesh must demote conditional fragColor output");
        assertTrue(maskedOrdFrag.contains("if (opacity < 0.3333) discard;"),
                "Issue 33b: Masked ordinary Mesh must preserve discard inside originalMain so masked pixels leave no velocity");
        assertFalse(maskedOrdFrag.substring(maskedOrdFrag.indexOf("#undef main")).contains("gl_FragDepth"),
                "Issue 33a+33b: Masked ordinary Mesh without PDO must use gl_FragCoord.z");
        var maskedNaniteSet = adapter.naniteCoverage(maskedNanitePipe);
        String maskedNaniteFrag = maskedNaniteSet.source().get(maskedNaniteSet.fragmentShader(), com.mojang.blaze3d.shaders.ShaderType.FRAGMENT);
        assertTrue(maskedNaniteFrag.contains("vec4 fragColor;") && !maskedNaniteFrag.contains("out vec4 fragColor;"));
        assertTrue(maskedNaniteFrag.substring(maskedNaniteFrag.indexOf("#undef main")).contains("gl_FragDepth"),
                "Issue 33b: Masked Nanite Mesh writes gl_FragDepth under LFX_NANITE_MATERIAL=1 and must read it in nanite tail");
        var maskedZeroSet = adapter.zeroOpaqueCoverage(maskedOrdPipe);
        String maskedZeroFrag = maskedZeroSet.source().get(maskedZeroSet.fragmentShader(), com.mojang.blaze3d.shaders.ShaderType.FRAGMENT);
        assertTrue(maskedZeroFrag.contains("vec4 fragColor;") && !maskedZeroFrag.contains("out vec4 fragColor;")
                && maskedZeroFrag.contains("lfxMotionShader_motionOffset = vec4(0.0);"),
                "Issue 31+33b: Masked zeroOpaqueCoverage must adapt cleanly and clear all 3 attachments after discard");
        // Verify post-injectDefines compiled scan for Masked ordinary + Nanite + zero-clear
        var maskedOrdMotionPipe = com.kltyton.lumenfx.client.render.motion.shader.MotionPipelineFactory.coverage(
                maskedOrdPipe, maskedOrdSet, com.kltyton.lumenfx.LumenFx.id("test/motion_masked_ord"), null, true,
                new com.mojang.blaze3d.pipeline.DepthStencilState(com.mojang.blaze3d.platform.CompareOp.EQUAL, false), true);
        var maskedOrdPostScan = com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceScanner.scan(
                com.mojang.blaze3d.preprocessor.GlslPreprocessor.injectDefines(maskedOrdFrag, maskedOrdMotionPipe.getShaderDefines()),
                maskedOrdMotionPipe.getShaderDefines());
        assertEquals(2, maskedOrdPostScan.mains().size());
        assertEquals(java.util.List.of("lfxMotionShader_motionClip", "lfxMotionShader_motionDepth", "lfxMotionShader_motionOffset"),
                maskedOrdPostScan.outputs().stream().map(com.kltyton.lumenfx.client.resource.shader.glsl.GlslOutputDeclaration::name).toList(),
                "Opaque motion must emit 3 attachments with fragColor demoted");
        var maskedZeroPipe = com.kltyton.lumenfx.client.render.motion.shader.MotionPipelineFactory.zeroOpaqueCoverage(
                maskedOrdPipe, maskedZeroSet, com.kltyton.lumenfx.LumenFx.id("test/zero_masked"),
                new com.mojang.blaze3d.pipeline.DepthStencilState(com.mojang.blaze3d.platform.CompareOp.EQUAL, false));
        var maskedZeroPostScan = com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceScanner.scan(
                com.mojang.blaze3d.preprocessor.GlslPreprocessor.injectDefines(maskedZeroFrag, maskedZeroPipe.getShaderDefines()),
                maskedZeroPipe.getShaderDefines());
        assertEquals(2, maskedZeroPostScan.mains().size());
        assertEquals(java.util.List.of("lfxMotionShader_motionClip", "lfxMotionShader_motionDepth", "lfxMotionShader_motionOffset"),
                maskedZeroPostScan.outputs().stream().map(com.kltyton.lumenfx.client.resource.shader.glsl.GlslOutputDeclaration::name).toList());
        // Nanite hardware programmable raster defines scan on same Masked shader
        var naniteRasterDefines = net.minecraft.client.renderer.ShaderDefines.builder()
                .define("LFX_NANITE_MATERIAL", 1).define("LFX_NANITE_RASTER_MATERIAL", 1).build();
        var maskedRasterScan = com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceScanner.scan(
                ueMaskedMeshFrag, naniteRasterDefines);
        assertEquals(1, maskedRasterScan.mains().size());
        assertEquals(1, maskedRasterScan.outputs().size());
        assertTrue(maskedRasterScan.identifiers().contains("gl_FragDepth"));

        // 33b: SAME Opaque-with-PDO UE Mesh shader through Ordinary, Nanite, and ZeroOpaque!
        var opaquePdoOrdPipe = makeMeshPipeline.apply(fragOpaquePdoId, false);
        var opaquePdoNanitePipe = makeMeshPipeline.apply(fragOpaquePdoId, true);
        var opaquePdoOrdSet = adapter.particleCoverage(opaquePdoOrdPipe, false, true,
                com.kltyton.lumenfx.client.render.motion.shader.MotionShaderAdapter.COLOR_DEPTH_COMPARE_NONE);
        String opaquePdoOrdFrag = opaquePdoOrdSet.source().get(opaquePdoOrdSet.fragmentShader(), com.mojang.blaze3d.shaders.ShaderType.FRAGMENT);
        assertTrue(opaquePdoOrdFrag.substring(opaquePdoOrdFrag.indexOf("#undef main")).contains("gl_FragDepth"),
                "Issue 33a+33b: Opaque UE Mesh with PDO must preserve gl_FragDepth on ordinary Mesh pipeline");
        var opaquePdoNaniteSet = adapter.naniteCoverage(opaquePdoNanitePipe);
        assertNotNull(opaquePdoNaniteSet.source().get(opaquePdoNaniteSet.fragmentShader(), com.mojang.blaze3d.shaders.ShaderType.FRAGMENT));
        var opaquePdoZeroSet = adapter.zeroOpaqueCoverage(opaquePdoOrdPipe);
        assertNotNull(opaquePdoZeroSet.source().get(opaquePdoZeroSet.fragmentShader(), com.mojang.blaze3d.shaders.ShaderType.FRAGMENT));

        // 32+33: ColoredTransmittance dual-source shader through BOTH particleCoverage AND translucentMotionLog!
        var dualPipe = makeMeshPipeline.apply(fragDualId, false);
        var dualMotionSet = adapter.particleCoverage(dualPipe, false, false,
                com.kltyton.lumenfx.client.render.motion.shader.MotionShaderAdapter.COLOR_DEPTH_COMPARE_NONE);
        String dualMotionFrag = dualMotionSet.source().get(dualMotionSet.fragmentShader(), com.mojang.blaze3d.shaders.ShaderType.FRAGMENT);
        assertFalse(dualMotionFrag.substring(dualMotionFrag.indexOf("#undef main")).contains("gl_FragDepth"));
        var dualLogSet = adapter.translucentMotionLog(dualPipe, true);
        String dualLogFrag = dualLogSet.source().get(dualLogSet.fragmentShader(), com.mojang.blaze3d.shaders.ShaderType.FRAGMENT);
        var dualLogPipe = com.kltyton.lumenfx.client.render.motion.shader.MotionPipelineFactory.translucentMotionLog(
                dualPipe, dualLogSet, com.kltyton.lumenfx.LumenFx.id("test/dual_log"), null, true);
        var dualLogPostScan = com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceScanner.scan(
                com.mojang.blaze3d.preprocessor.GlslPreprocessor.injectDefines(dualLogFrag, dualLogPipe.getShaderDefines()),
                dualLogPipe.getShaderDefines());
        assertEquals(2, dualLogPostScan.mains().size());
        assertEquals(java.util.List.of("lfxMotionShader_logDummy"),
                dualLogPostScan.outputs().stream().map(com.kltyton.lumenfx.client.resource.shader.glsl.GlslOutputDeclaration::name).toList(),
                "Translucent motion log capture pipeline must emit exactly 1 dummy output (writeMask = 0)");

        // Issue 34a & 34b: Translucent velocity opacity cutoff (0.3333 + 1/255) diverges from color-pass survival.
        // 34a: bg=0.8, GREATER (<), P=0.4 (op=0.1, color writes 0.4, velocity ineligible), Q=0.6 (op=0.8, color rejects 0.6<0.4).
        var prim34aP = new Prim(0.4f, 4.5f, true, true, false, 0.1f);
        var prim34aQ = new Prim(0.6f, 9.25f, true, false, false, 0.8f);
        float[][] single34a = runMultiPrimTranslucent.apply(0.8f, java.util.List.of(java.util.List.of(
                new MultiPrimBatch("34a_single", translucentVelGreaterDepth, java.util.List.of(prim34aP, prim34aQ)))));
        float[][] split34a = runMultiPrimTranslucent.apply(0.8f, java.util.List.of(java.util.List.of(
                new MultiPrimBatch("34a_P", translucentVelGreaterDepth, java.util.List.of(prim34aP)),
                new MultiPrimBatch("34a_Q", translucentVelGreaterDepth, java.util.List.of(prim34aQ)))));
        assertEquals(0.4f, single34a[3][0], "Issue 34a: Color depth must be 0.4 written by low-opacity P");
        assertEquals(0.4f, single34a[1][0], "Issue 34a: Ineligible P must merge color depth 0.8 -> 0.4 into ParticleMotionDepth");
        assertEquals(0.0f, single34a[0][0], "Issue 34a: Color-rejected Q must NOT publish velocity");
        assertEquals(split34a[0][0], single34a[0][0], "Issue 34a: Single batch must equal split batches (vel)");
        assertEquals(split34a[1][0], single34a[1][0], "Issue 34a: Single batch must equal split batches (motion depth)");
        // 34a with independent closer foreground A=0.9 (depth_write=false, vel=1.5): foreground A survives!
        var aFg09Batch = new MultiPrimBatch("A_09_fg", translucentLessEqual, java.util.List.of(new Prim(0.9f, 1.5f, true, true, false, 0.8f)));
        float[][] single34aFg = runMultiPrimTranslucent.apply(0.8f, java.util.List.of(java.util.List.of(
                aFg09Batch, new MultiPrimBatch("34a_fg", translucentVelGreaterDepth, java.util.List.of(prim34aP, prim34aQ)))));
        float[][] split34aFg = runMultiPrimTranslucent.apply(0.8f, java.util.List.of(java.util.List.of(
                aFg09Batch,
                new MultiPrimBatch("34a_fgP", translucentVelGreaterDepth, java.util.List.of(prim34aP)),
                new MultiPrimBatch("34a_fgQ", translucentVelGreaterDepth, java.util.List.of(prim34aQ)))));
        assertEquals(0.4f, single34aFg[3][0]);
        assertEquals(0.9f, single34aFg[1][0], "Issue 34a foreground control: Independent closer A=0.9 preserved");
        assertEquals(1.5f, single34aFg[0][0]);
        assertEquals(split34aFg[0][0], single34aFg[0][0]);
        // 34a reverse (eligible P=0.6 followed by ineligible Q=0.4 under GREATER on bg=0.8): Q replaces P with no velocity!
        float[][] single34aRev = runMultiPrimTranslucent.apply(0.8f, java.util.List.of(java.util.List.of(
                new MultiPrimBatch("34a_rev", translucentVelGreaterDepth, java.util.List.of(prim34aQ, prim34aP)))));
        float[][] split34aRev = runMultiPrimTranslucent.apply(0.8f, java.util.List.of(java.util.List.of(
                new MultiPrimBatch("34a_rQ", translucentVelGreaterDepth, java.util.List.of(prim34aQ)),
                new MultiPrimBatch("34a_rP", translucentVelGreaterDepth, java.util.List.of(prim34aP)))));
        assertEquals(0.4f, single34aRev[1][0]);
        assertEquals(0.0f, single34aRev[0][0]);
        assertEquals(split34aRev[0][0], single34aRev[0][0]);

        // 34b: bg=0.2, NOT_EQUAL, P=0.4 (op=0.1, ineligible), Q=0.2 (op=0.8, eligible) -> Q MUST publish 8.25!
        var prim34bP = new Prim(0.4f, 4.5f, true, true, false, 0.1f);
        var prim34bQ = new Prim(0.2f, 8.25f, true, false, false, 0.8f);
        var prim34bP08 = new Prim(0.8f, 4.5f, true, false, false, 0.1f);
        float[][] single34b = runMultiPrimTranslucent.apply(0.2f, java.util.List.of(java.util.List.of(
                new MultiPrimBatch("34b_single", translucentNotEqualWriteDepth, java.util.List.of(prim34bP, prim34bQ)))));
        float[][] split34b = runMultiPrimTranslucent.apply(0.2f, java.util.List.of(java.util.List.of(
                new MultiPrimBatch("34b_P", translucentNotEqualWriteDepth, java.util.List.of(prim34bP)),
                new MultiPrimBatch("34b_Q", translucentNotEqualWriteDepth, java.util.List.of(prim34bQ)))));
        assertEquals(0.2f, single34b[3][0]);
        assertEquals(0.2f, single34b[1][0]);
        assertEquals(8.25f, single34b[0][0], "Issue 34b: Low-opacity P=0.4 must advance replay depth so eligible Q=0.2 publishes 8.25");
        assertEquals(split34b[0][0], single34b[0][0], "Issue 34b: Single batch must equal split batches");
        // 34b foreground controls with A=0.6 (depth_write=false, vel=1.5):
        // (1) P=0.4 (op=0.1), Q=0.2 (op=0.8) -> Q wins (0.2 != 0.6)
        float[][] single34bFg1 = runMultiPrimTranslucent.apply(0.2f, java.util.List.of(java.util.List.of(
                aForegroundBatch, new MultiPrimBatch("34b_fg1", translucentNotEqualWriteDepth, java.util.List.of(prim34bP, prim34bQ)))));
        float[][] split34bFg1 = runMultiPrimTranslucent.apply(0.2f, java.util.List.of(java.util.List.of(
                aForegroundBatch,
                new MultiPrimBatch("34b_fg1P", translucentNotEqualWriteDepth, java.util.List.of(prim34bP)),
                new MultiPrimBatch("34b_fg1Q", translucentNotEqualWriteDepth, java.util.List.of(prim34bQ)))));
        assertEquals(8.25f, single34bFg1[0][0]);
        assertEquals(split34bFg1[0][0], single34bFg1[0][0]);
        // (2) P=0.4 (op=0.1, behind A=0.6), Q=0.6 (op=0.8, equal to A=0.6) -> A survives!
        float[][] single34bFg2 = runMultiPrimTranslucent.apply(0.2f, java.util.List.of(java.util.List.of(
                aForegroundBatch, new MultiPrimBatch("34b_fg2", translucentNotEqualWriteDepth, java.util.List.of(prim34bP, primQ06)))));
        float[][] split34bFg2 = runMultiPrimTranslucent.apply(0.2f, java.util.List.of(java.util.List.of(
                aForegroundBatch,
                new MultiPrimBatch("34b_fg2P", translucentNotEqualWriteDepth, java.util.List.of(prim34bP)),
                new MultiPrimBatch("34b_fg2Q", translucentNotEqualWriteDepth, java.util.List.of(primQ06)))));
        assertEquals(1.5f, single34bFg2[0][0], "Issue 34b foreground control: Ineligible P=0.4 behind A=0.6 must NOT let Q=0.6 overwrite A");
        assertEquals(split34bFg2[0][0], single34bFg2[0][0]);
        // (3) P=0.8 (op=0.1, in front of A=0.6), Q=0.6 (op=0.8) -> maxIneligible(0.8) >= 0.6 -> Q wins!
        float[][] single34bFg3 = runMultiPrimTranslucent.apply(0.2f, java.util.List.of(java.util.List.of(
                aForegroundBatch, new MultiPrimBatch("34b_fg3", translucentNotEqualWriteDepth, java.util.List.of(prim34bP08, primQ06)))));
        float[][] split34bFg3 = runMultiPrimTranslucent.apply(0.2f, java.util.List.of(java.util.List.of(
                aForegroundBatch,
                new MultiPrimBatch("34b_fg3P", translucentNotEqualWriteDepth, java.util.List.of(prim34bP08)),
                new MultiPrimBatch("34b_fg3Q", translucentNotEqualWriteDepth, java.util.List.of(primQ06)))));
        assertEquals(6.75f, single34bFg3[0][0], "Issue 34b foreground control: Closer ineligible P=0.8 occludes A=0.6 so Q=0.6 wins");
        assertEquals(split34bFg3[0][0], single34bFg3[0][0]);
        // 34b depth_write=false and cross-stage controls
        float[][] single34bNoDepth = runMultiPrimTranslucent.apply(0.2f, java.util.List.of(java.util.List.of(
                new MultiPrimBatch("34b_noDepth", translucentNotEqualNoDepth, java.util.List.of(prim34bP, prim34bQ)))));
        assertEquals(0.0f, single34bNoDepth[0][0], "Issue 34b depth_write=false: Low-opacity P does not write color depth so Q=0.2 fails");
        float[][] single34bCross = runMultiPrimTranslucent.apply(0.2f, java.util.List.of(
                java.util.List.of(new MultiPrimBatch("34b_s1", translucentNotEqualWriteDepth, java.util.List.of(prim34bP, prim34bQ))),
                java.util.List.of(new MultiPrimBatch("34b_s2", translucentNotEqualWriteDepth, java.util.List.of(primR02)))));
        assertEquals(8.25f, single34bCross[0][0], "Issue 34b cross-stage: Stage 2 R=0.2 fails against Stage 1 colorDepth=0.2, keeping Q");

        // Issue 33 & 34: FULL real MaterialCompiler.Compile(...) UE Mesh outputs expanded through Minecraft's GlslPreprocessor!
        var mojExpander = new com.mojang.blaze3d.preprocessor.GlslPreprocessor() {
            @Override
            public String applyImport(boolean isRelative, String path) {
                int colon = path.indexOf(':');
                String namespace = colon >= 0 ? path.substring(0, colon) : "minecraft";
                String rel = colon >= 0 ? path.substring(colon + 1) : path;
                String resource = "/assets/" + namespace + "/shaders/include/" + rel;
                try (var stream = BakedShaderAssetsTest.class.getResourceAsStream(resource)) {
                    assertNotNull(stream, "Missing real GLSL include on classpath: " + resource);
                    return new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
                } catch (IOException e) {
                    throw new RuntimeException("Failed reading GLSL include: " + resource, e);
                }
            }
        };
        java.util.function.Function<String, String> loadExpandedUeMeshShader = name -> {
            try {
                String raw = new String(readBytesResource("/ue_compiled/" + name + ".fsh"), StandardCharsets.UTF_8)
                        .replace("\r\n", "\n");
                String expanded = String.join("\n", mojExpander.process(raw)).replace("\r\n", "\n");
                assertFalse(expanded.contains("#moj_import"), "All #moj_import directives must be expanded for " + name);
                return expanded;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
        String realUeTranslucentNoPdo = loadExpandedUeMeshShader.apply("M_TranslucentNoPdo");
        String realUeTranslucentPdo = loadExpandedUeMeshShader.apply("M_TranslucentPdo");
        String realUeMasked = loadExpandedUeMeshShader.apply("M_MaskedMesh");
        String realUeOpaquePdo = loadExpandedUeMeshShader.apply("M_OpaquePdoMesh");
        // Verify legacy already-baked UE shader without LFX_TRANSLUCENT_VELOCITY_COLOR_REPLAY is cleanly rewritten on colorReplay=true
        String legacyBakedTranslucent = realUeTranslucentNoPdo.replace(
                "#if LFX_TRANSLUCENT_VELOCITY_COLOR_REPLAY\n    lfxMotionVelocityEligible = (opacity - 1.0 / 255.0 - 0.3333) >= 0.0;\n#else\n    if (opacity - 1.0 / 255.0 - 0.3333 < 0.0) discard;\n#endif",
                "    if (opacity - 1.0 / 255.0 - 0.3333 < 0.0) discard;");
        assertFalse(legacyBakedTranslucent.contains("LFX_TRANSLUCENT_VELOCITY_COLOR_REPLAY"),
                "Legacy translucent fixture must strip LFX_TRANSLUCENT_VELOCITY_COLOR_REPLAY branch");
        // Inject real material_virtual_texture_feedback.glsl into real UE shader to verify RVT feedback side-effect gating
        String realUeWithVtFeedback = String.join("\n", mojExpander.process(
                realUeTranslucentNoPdo.replace("fragColor = lfx_apply_material_fog(vec4(emission, opacity));",
                        "fragColor = lfx_apply_material_fog(vec4(emission, opacity));\n    lfx_vt_commit_feedback();")
                        .replace("out vec4 fragColor;", "#moj_import <lumenfx:material_virtual_texture_feedback.glsl>\nout vec4 fragColor;")));
        var fullNoPdoId = com.kltyton.lumenfx.LumenFx.id("test/full_ue_no_pdo");
        var fullPdoId = com.kltyton.lumenfx.LumenFx.id("test/full_ue_pdo");
        var fullMaskedId = com.kltyton.lumenfx.LumenFx.id("test/full_ue_masked");
        var fullOpaquePdoId = com.kltyton.lumenfx.LumenFx.id("test/full_ue_opaque_pdo");
        var fullLegacyId = com.kltyton.lumenfx.LumenFx.id("test/full_ue_legacy_translucent");
        var fullVtId = com.kltyton.lumenfx.LumenFx.id("test/full_ue_vt_feedback");
        com.mojang.blaze3d.shaders.ShaderSource fullUeSources = (id, type) -> {
            if (type == com.mojang.blaze3d.shaders.ShaderType.VERTEX && id.equals(vtxId)) return minimalVertex;
            if (type == com.mojang.blaze3d.shaders.ShaderType.FRAGMENT) {
                if (id.equals(fullNoPdoId)) return realUeTranslucentNoPdo;
                if (id.equals(fullPdoId)) return realUeTranslucentPdo;
                if (id.equals(fullMaskedId)) return realUeMasked;
                if (id.equals(fullOpaquePdoId)) return realUeOpaquePdo;
                if (id.equals(fullLegacyId)) return legacyBakedTranslucent;
                if (id.equals(fullVtId)) return realUeWithVtFeedback;
            }
            return null;
        };
        var fullAdapter = new com.kltyton.lumenfx.client.render.motion.shader.MotionShaderAdapter(fullUeSources);
        java.util.function.BiFunction<net.minecraft.resources.Identifier, Boolean, com.mojang.blaze3d.pipeline.RenderPipeline> makeFullUePipe =
                (fragId, nanite) -> com.mojang.blaze3d.pipeline.RenderPipeline.builder()
                        .withLocation(com.kltyton.lumenfx.LumenFx.id("test/full_pipe_" + fragId.getPath() + (nanite ? "_nanite" : "_ord")))
                        .withVertexShader(vtxId)
                        .withFragmentShader(fragId)
                        .withPrimitiveTopology(com.mojang.blaze3d.PrimitiveTopology.TRIANGLES)
                        .withShaderDefine("LFX_NANITE_MATERIAL", nanite ? 1 : 0)
                        .withShaderDefine("LFX_PARTICLE_POINT_SHADOW", 0)
                        .withShaderDefine("LFX_PARTICLE_CUSTOM_DEPTH", 0)
                        .withShaderDefine("LFX_APPLY_FOG", 1)
                        .withShaderDefine("LFX_PIXEL_FOG", 0)
                        .withShaderDefine("LFX_EXPONENTIAL_FOG", 0)
                        .withShaderDefine("LFX_VOLUMETRIC_FOG", 0)
                        .withShaderDefine("LFX_FOG_BLEND", 0)
                        .withShaderDefine("LFX_MACRO_UV", 0)
                        .withShaderDefine("LFX_DEPTH_PROJECTION", fragId.equals(fullPdoId) || fragId.equals(fullOpaquePdoId) ? 1 : 0)
                        .withShaderDefine("LFX_SUBSTRATE_DUAL_SOURCE", 0)
                        .withColorTargetState(0, testTarget)
                        .withDepthStencilState(new com.mojang.blaze3d.pipeline.DepthStencilState(
                                com.mojang.blaze3d.platform.CompareOp.GREATER_THAN_OR_EQUAL, true))
                        .build();

        // Full UE 33a + 34: M_TranslucentNoPdo (no-PDO ordinary Mesh) for non-replay, colorReplay, AND NOT_EQUAL passCount!
        var fullNoPdoOrdPipe = makeFullUePipe.apply(fullNoPdoId, false);
        var fullNoPdoReplaySet = fullAdapter.particleCoverage(fullNoPdoOrdPipe, false, false,
                com.kltyton.lumenfx.client.render.motion.shader.MotionShaderAdapter.COLOR_DEPTH_COMPARE_NONE, true);
        String fullNoPdoReplayFrag = fullNoPdoReplaySet.source().get(fullNoPdoReplaySet.fragmentShader(), com.mojang.blaze3d.shaders.ShaderType.FRAGMENT);
        var fullNoPdoReplayPipe = com.kltyton.lumenfx.client.render.motion.shader.MotionPipelineFactory.coverage(
                fullNoPdoOrdPipe, fullNoPdoReplaySet, com.kltyton.lumenfx.LumenFx.id("test/full_no_pdo_replay"), null, true,
                new com.mojang.blaze3d.pipeline.DepthStencilState(com.mojang.blaze3d.platform.CompareOp.NOT_EQUAL, true), false, true);
        var fullNoPdoReplayPostScan = com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceScanner.scan(
                com.mojang.blaze3d.preprocessor.GlslPreprocessor.injectDefines(fullNoPdoReplayFrag, fullNoPdoReplayPipe.getShaderDefines()),
                fullNoPdoReplayPipe.getShaderDefines());
        assertEquals(2, fullNoPdoReplayPostScan.mains().size());
        assertEquals(java.util.List.of("lfxMotionShader_motionClip", "lfxMotionShader_motionDepth"),
                fullNoPdoReplayPostScan.outputs().stream().map(com.kltyton.lumenfx.client.resource.shader.glsl.GlslOutputDeclaration::name).toList());
        assertFalse(fullNoPdoReplayPostScan.identifiers().contains("gl_FragDepth"),
                "Full UE M_TranslucentNoPdo ordinary Mesh must NOT read/write gl_FragDepth");
        assertTrue(fullNoPdoReplayPostScan.identifiers().contains("lfxMotionVelocityEligible")
                && !fullNoPdoReplayPostScan.identifiers().contains("discard"),
                "Full UE M_TranslucentNoPdo colorReplay must evaluate lfxMotionVelocityEligible without velocity discard");
        assertTrue(fullNoPdoReplayFrag.contains("lfxMotionShader_motionDepth = vec2(0.0, -2.0);"));
        var fullNoPdoLogSet = fullAdapter.translucentMotionLog(fullNoPdoOrdPipe, true);
        String fullNoPdoLogFrag = fullNoPdoLogSet.source().get(fullNoPdoLogSet.fragmentShader(), com.mojang.blaze3d.shaders.ShaderType.FRAGMENT);
        assertTrue(fullNoPdoLogFrag.trim().startsWith("#version 430"), "Translucent motion log shader must upgrade to #version 430 for SSBO");
        var fullNoPdoLogPipe = com.kltyton.lumenfx.client.render.motion.shader.MotionPipelineFactory.translucentMotionLog(
                fullNoPdoOrdPipe, fullNoPdoLogSet, com.kltyton.lumenfx.LumenFx.id("test/full_no_pdo_log"), null, true);
        var fullNoPdoLogPostScan = com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceScanner.scan(
                com.mojang.blaze3d.preprocessor.GlslPreprocessor.injectDefines(fullNoPdoLogFrag, fullNoPdoLogPipe.getShaderDefines()),
                fullNoPdoLogPipe.getShaderDefines());
        assertEquals(2, fullNoPdoLogPostScan.mains().size());
        assertEquals(java.util.List.of("lfxMotionShader_logDummy"),
                fullNoPdoLogPostScan.outputs().stream().map(com.kltyton.lumenfx.client.resource.shader.glsl.GlslOutputDeclaration::name).toList());
        assertFalse(fullNoPdoLogPostScan.identifiers().contains("gl_FragDepth"),
                "Full UE M_TranslucentNoPdo translucentMotionLog must NOT reference gl_FragDepth");
        assertTrue(fullNoPdoLogPostScan.identifiers().contains("lfxMotionVelocityEligible")
                && fullNoPdoLogFrag.contains("LfxTranslucentMotionLog")
                && fullNoPdoLogFrag.contains("lfxMotionShader_instanceId"));
        var fullNoPdoDepthOnlyLogSet = fullAdapter.translucentMotionLog(fullNoPdoOrdPipe, false);
        String fullNoPdoDepthOnlyLogFrag = fullNoPdoDepthOnlyLogSet.source().get(fullNoPdoDepthOnlyLogSet.fragmentShader(), com.mojang.blaze3d.shaders.ShaderType.FRAGMENT);
        var fullNoPdoDepthOnlyLogPipe = com.kltyton.lumenfx.client.render.motion.shader.MotionPipelineFactory.translucentMotionLog(
                fullNoPdoOrdPipe, fullNoPdoDepthOnlyLogSet, com.kltyton.lumenfx.LumenFx.id("test/full_no_pdo_depth_log"), null, false);
        var fullNoPdoDepthOnlyPostScan = com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceScanner.scan(
                com.mojang.blaze3d.preprocessor.GlslPreprocessor.injectDefines(fullNoPdoDepthOnlyLogFrag, fullNoPdoDepthOnlyLogPipe.getShaderDefines()),
                fullNoPdoDepthOnlyLogPipe.getShaderDefines());
        assertEquals(2, fullNoPdoDepthOnlyPostScan.mains().size());
        assertFalse(fullNoPdoDepthOnlyPostScan.identifiers().contains("gl_FragDepth"));
        assertFalse(fullNoPdoDepthOnlyPostScan.identifiers().contains("lfxMotionShader_currentClip"),
                "Non-velocity translucent depth-sync log pipeline must not require velocity interpolants");

        // Full UE 33a PDO control + 34: M_TranslucentPdo preserves gl_FragDepth in BOTH velocity and depth-only motion log!
        var fullPdoOrdPipe = makeFullUePipe.apply(fullPdoId, false);
        var fullPdoReplaySet = fullAdapter.particleCoverage(fullPdoOrdPipe, false, false,
                com.kltyton.lumenfx.client.render.motion.shader.MotionShaderAdapter.COLOR_DEPTH_COMPARE_NONE, true);
        String fullPdoReplayFrag = fullPdoReplaySet.source().get(fullPdoReplaySet.fragmentShader(), com.mojang.blaze3d.shaders.ShaderType.FRAGMENT);
        assertTrue(fullPdoReplayFrag.substring(fullPdoReplayFrag.indexOf("#undef main")).contains("gl_FragDepth"),
                "Full UE M_TranslucentPdo must preserve gl_FragDepth in velocity replay tail");
        var fullPdoLogSet = fullAdapter.translucentMotionLog(fullPdoOrdPipe, true);
        String fullPdoLogFrag = fullPdoLogSet.source().get(fullPdoLogSet.fragmentShader(), com.mojang.blaze3d.shaders.ShaderType.FRAGMENT);
        assertTrue(fullPdoLogFrag.substring(fullPdoLogFrag.indexOf("#undef main")).contains("gl_FragDepth"),
                "Full UE M_TranslucentPdo must preserve gl_FragDepth in translucent motion log capture tail");
        var fullPdoDepthLogSet = fullAdapter.translucentMotionLog(fullPdoOrdPipe, false);
        String fullPdoDepthLogFrag = fullPdoDepthLogSet.source().get(fullPdoDepthLogSet.fragmentShader(), com.mojang.blaze3d.shaders.ShaderType.FRAGMENT);
        assertTrue(fullPdoDepthLogFrag.substring(fullPdoDepthLogFrag.indexOf("#undef main")).contains("gl_FragDepth"),
                "Full UE M_TranslucentPdo must preserve gl_FragDepth in non-velocity depth-sync log capture tail");

        // Full UE 33b: M_MaskedMesh through Ordinary, Nanite, ZeroOpaque, and Nanite Hardware Raster!
        var fullMaskedOrdPipe = makeFullUePipe.apply(fullMaskedId, false);
        var fullMaskedNanitePipe = makeFullUePipe.apply(fullMaskedId, true);
        var fullMaskedOrdSet = fullAdapter.particleCoverage(fullMaskedOrdPipe, false, true,
                com.kltyton.lumenfx.client.render.motion.shader.MotionShaderAdapter.COLOR_DEPTH_COMPARE_NONE);
        String fullMaskedOrdFrag = fullMaskedOrdSet.source().get(fullMaskedOrdSet.fragmentShader(), com.mojang.blaze3d.shaders.ShaderType.FRAGMENT);
        var fullMaskedOrdMotionPipe = com.kltyton.lumenfx.client.render.motion.shader.MotionPipelineFactory.coverage(
                fullMaskedOrdPipe, fullMaskedOrdSet, com.kltyton.lumenfx.LumenFx.id("test/full_masked_ord"), null, true,
                new com.mojang.blaze3d.pipeline.DepthStencilState(com.mojang.blaze3d.platform.CompareOp.EQUAL, false), true);
        var fullMaskedOrdPostScan = com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceScanner.scan(
                com.mojang.blaze3d.preprocessor.GlslPreprocessor.injectDefines(fullMaskedOrdFrag, fullMaskedOrdMotionPipe.getShaderDefines()),
                fullMaskedOrdMotionPipe.getShaderDefines());
        assertEquals(2, fullMaskedOrdPostScan.mains().size());
        assertEquals(java.util.List.of("lfxMotionShader_motionClip", "lfxMotionShader_motionDepth", "lfxMotionShader_motionOffset"),
                fullMaskedOrdPostScan.outputs().stream().map(com.kltyton.lumenfx.client.resource.shader.glsl.GlslOutputDeclaration::name).toList());
        assertFalse(fullMaskedOrdPostScan.identifiers().contains("gl_FragDepth"),
                "Full UE M_MaskedMesh on ordinary Mesh pipeline must NOT reference dead Nanite gl_FragDepth");
        assertTrue(fullMaskedOrdPostScan.identifiers().contains("discard"),
                "Full UE M_MaskedMesh on ordinary Mesh pipeline must keep real Masked discard active");
        var fullMaskedNaniteSet = fullAdapter.naniteCoverage(fullMaskedNanitePipe);
        String fullMaskedNaniteFrag = fullMaskedNaniteSet.source().get(fullMaskedNaniteSet.fragmentShader(), com.mojang.blaze3d.shaders.ShaderType.FRAGMENT);
        assertTrue(fullMaskedNaniteFrag.substring(fullMaskedNaniteFrag.indexOf("#undef main")).contains("gl_FragDepth"));
        var fullMaskedZeroSet = fullAdapter.zeroOpaqueCoverage(fullMaskedOrdPipe);
        String fullMaskedZeroFrag = fullMaskedZeroSet.source().get(fullMaskedZeroSet.fragmentShader(), com.mojang.blaze3d.shaders.ShaderType.FRAGMENT);
        var fullMaskedZeroPipe = com.kltyton.lumenfx.client.render.motion.shader.MotionPipelineFactory.zeroOpaqueCoverage(
                fullMaskedOrdPipe, fullMaskedZeroSet, com.kltyton.lumenfx.LumenFx.id("test/full_masked_zero"),
                new com.mojang.blaze3d.pipeline.DepthStencilState(com.mojang.blaze3d.platform.CompareOp.EQUAL, false));
        var fullMaskedZeroPostScan = com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceScanner.scan(
                com.mojang.blaze3d.preprocessor.GlslPreprocessor.injectDefines(fullMaskedZeroFrag, fullMaskedZeroPipe.getShaderDefines()),
                fullMaskedZeroPipe.getShaderDefines());
        assertEquals(2, fullMaskedZeroPostScan.mains().size());
        assertEquals(3, fullMaskedZeroPostScan.outputs().size());
        var fullMaskedRasterScan = com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceScanner.scan(
                realUeMasked, naniteRasterDefines);
        assertEquals(1, fullMaskedRasterScan.mains().size());
        assertEquals(1, fullMaskedRasterScan.outputs().size());

        // Full UE 33b: M_OpaquePdoMesh through Ordinary, Nanite, and ZeroOpaque!
        var fullOpaquePdoOrdPipe = makeFullUePipe.apply(fullOpaquePdoId, false);
        var fullOpaquePdoNanitePipe = makeFullUePipe.apply(fullOpaquePdoId, true);
        var fullOpaquePdoOrdSet = fullAdapter.particleCoverage(fullOpaquePdoOrdPipe, false, true,
                com.kltyton.lumenfx.client.render.motion.shader.MotionShaderAdapter.COLOR_DEPTH_COMPARE_NONE);
        String fullOpaquePdoOrdFrag = fullOpaquePdoOrdSet.source().get(fullOpaquePdoOrdSet.fragmentShader(), com.mojang.blaze3d.shaders.ShaderType.FRAGMENT);
        assertTrue(fullOpaquePdoOrdFrag.substring(fullOpaquePdoOrdFrag.indexOf("#undef main")).contains("gl_FragDepth"));
        assertNotNull(fullAdapter.naniteCoverage(fullOpaquePdoNanitePipe).source().get(
                fullAdapter.naniteCoverage(fullOpaquePdoNanitePipe).fragmentShader(), com.mojang.blaze3d.shaders.ShaderType.FRAGMENT));
        assertNotNull(fullAdapter.zeroOpaqueCoverage(fullOpaquePdoOrdPipe).source().get(
                fullAdapter.zeroOpaqueCoverage(fullOpaquePdoOrdPipe).fragmentShader(), com.mojang.blaze3d.shaders.ShaderType.FRAGMENT));

        // Issue 34: Legacy already-baked translucent shader rewrite + RVT feedback side-effect gating!
        var fullLegacyPipe = makeFullUePipe.apply(fullLegacyId, false);
        var fullLegacyLogSet = fullAdapter.translucentMotionLog(fullLegacyPipe, true);
        String fullLegacyLogFrag = fullLegacyLogSet.source().get(fullLegacyLogSet.fragmentShader(), com.mojang.blaze3d.shaders.ShaderType.FRAGMENT);
        assertTrue(fullLegacyLogFrag.contains("lfxMotionVelocityEligible = (opacity - 1.0 / 255.0 - 0.3333) >= 0.0;"),
                "Issue 34 legacy fallback: Already-baked translucent shader must rewrite velocity cutoff to eligibility flag in translucentMotionLog");
        var fullVtPipe = makeFullUePipe.apply(fullVtId, false);
        var vtColorScan = com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceScanner.scan(
                com.mojang.blaze3d.preprocessor.GlslPreprocessor.injectDefines(realUeWithVtFeedback, fullVtPipe.getShaderDefines()),
                fullVtPipe.getShaderDefines());
        assertTrue(vtColorScan.identifiers().contains("atomicAdd")
                && vtColorScan.identifiers().contains("lfxVtRequestCount"),
                "Issue 34 RVT feedback: Real color pass must retain atomicAdd virtual texture feedback");
        for (boolean hasVel : new boolean[]{true, false}) {
            var vtLogSet = fullAdapter.translucentMotionLog(fullVtPipe, hasVel);
            String vtLogFrag = vtLogSet.source().get(vtLogSet.fragmentShader(), com.mojang.blaze3d.shaders.ShaderType.FRAGMENT);
            var vtLogPipe = com.kltyton.lumenfx.client.render.motion.shader.MotionPipelineFactory.translucentMotionLog(
                    fullVtPipe, vtLogSet, com.kltyton.lumenfx.LumenFx.id("test/full_vt_log_" + hasVel), null, hasVel);
            String vtLogMaterialPart = vtLogFrag.substring(0, vtLogFrag.indexOf("#undef main"));
            var vtLogMaterialScan = com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceScanner.scan(
                    com.mojang.blaze3d.preprocessor.GlslPreprocessor.injectDefines(vtLogMaterialPart, vtLogPipe.getShaderDefines()),
                    vtLogPipe.getShaderDefines());
            assertFalse(vtLogMaterialScan.identifiers().contains("atomicAdd"),
                    "Issue 34 RVT feedback: Translucent motion log capture (hasVelocity=" + hasVel + ") must NOT emit duplicate atomicAdd RVT feedback");
        }
        var vtZeroSet = fullAdapter.zeroOpaqueCoverage(fullVtPipe);
        String vtZeroFrag = vtZeroSet.source().get(vtZeroSet.fragmentShader(), com.mojang.blaze3d.shaders.ShaderType.FRAGMENT);
        var vtZeroPipe = com.kltyton.lumenfx.client.render.motion.shader.MotionPipelineFactory.zeroOpaqueCoverage(
                fullVtPipe, vtZeroSet, com.kltyton.lumenfx.LumenFx.id("test/full_vt_zero"),
                new com.mojang.blaze3d.pipeline.DepthStencilState(com.mojang.blaze3d.platform.CompareOp.EQUAL, false));
        var vtZeroScan = com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceScanner.scan(
                com.mojang.blaze3d.preprocessor.GlslPreprocessor.injectDefines(vtZeroFrag, vtZeroPipe.getShaderDefines()),
                vtZeroPipe.getShaderDefines());
        assertFalse(vtZeroScan.identifiers().contains("atomicAdd"),
                "Issue 34 RVT feedback: Auxiliary opaque zero-clear pass must NOT emit duplicate atomicAdd feedback");

        // Round 14 P1 Regression Matrix (exact per-fragment ordered replay):
        // P1.1: EQUAL, C=V=0.5. Eligible P=0.5 followed by low-opacity Q=0.5 (both ZWrite=true & ZWrite=false).
        var primP11P = new Prim(0.5f, 3.75f, true, true, false, 0.8f);
        var primP11Q = new Prim(0.5f, 9.5f, true, false, false, 0.1f);
        for (var eqMat : java.util.List.of(translucentEqualWriteDepth, translucentEqualNoDepth)) {
            float[][] singleP11 = runMultiPrimTranslucent.apply(0.5f, java.util.List.of(java.util.List.of(
                    new MultiPrimBatch("P1_1_single", eqMat, java.util.List.of(primP11P, primP11Q)))));
            float[][] splitP11 = runMultiPrimTranslucent.apply(0.5f, java.util.List.of(java.util.List.of(
                    new MultiPrimBatch("P1_1_P", eqMat, java.util.List.of(primP11P)),
                    new MultiPrimBatch("P1_1_Q", eqMat, java.util.List.of(primP11Q)))));
            assertEquals(3.75f, singleP11[0][0], "P1.1: Low-opacity Q=0.5 must NOT erase eligible P=0.5 velocity under EQUAL");
            assertEquals(0.5f, singleP11[1][0], "P1.1: Motion depth must remain 0.5");
            assertEquals(0.5f, singleP11[2][0], "P1.1: Coverage depth must remain 0.5");
            assertEquals(0.5f, singleP11[3][0], "P1.1: Color depth must remain 0.5");
            assertEquals(splitP11[0][0], singleP11[0][0], "P1.1: Single batch must equal split batches (vel)");
            assertEquals(splitP11[1][0], singleP11[1][0], "P1.1: Single batch must equal split batches (V)");
            assertEquals(splitP11[2][0], singleP11[2][0], "P1.1: Single batch must equal split batches (cov)");
        }

        // P1.2: Write-depth GREATER, C=0.875, V=0.625 (seeded via earlier GREATER depth_write=false batch at 0.625).
        // Low-opacity P=0.75 followed by eligible Q=0.6875: P advances V 0.625->0.75, then Q writes velocity at 0.6875!
        var translucentVelGreaterNoDepth = new com.kltyton.lumenfx.effect.MaterialDefinition(
                com.kltyton.lumenfx.LumenFx.id("core/color"), Map.of(),
                com.kltyton.lumenfx.effect.MaterialDefinition.Blend.ALPHA,
                new com.kltyton.lumenfx.effect.render.MaterialDepth(com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.GREATER, false),
                false, new float[]{0, 0, 0, 0},
                com.kltyton.lumenfx.effect.RenderSettings.STANDARD, com.kltyton.lumenfx.effect.render.MaterialFog.NONE,
                com.kltyton.lumenfx.effect.render.TranslucencyPass.AFTER_DOF, 0, false, false, false, false, "",
                true, false);
        var seedV0625 = new MultiPrimBatch("seed_V0625", translucentVelGreaterNoDepth,
                java.util.List.of(new Prim(0.625f, 1.25f, true, true, false, 0.8f)));
        var primP12P = new Prim(0.75f, 4.0f, true, false, false, 0.1f);
        var primP12Q = new Prim(0.6875f, 6.5f, true, false, false, 0.8f);
        float[][] singleP12 = runMultiPrimTranslucent.apply(0.875f, java.util.List.of(java.util.List.of(
                seedV0625,
                new MultiPrimBatch("P1_2_single", translucentVelGreaterDepth, java.util.List.of(primP12P, primP12Q)))));
        float[][] splitP12 = runMultiPrimTranslucent.apply(0.875f, java.util.List.of(java.util.List.of(
                seedV0625,
                new MultiPrimBatch("P1_2_P", translucentVelGreaterDepth, java.util.List.of(primP12P)),
                new MultiPrimBatch("P1_2_Q", translucentVelGreaterDepth, java.util.List.of(primP12Q)))));
        assertEquals(0.6875f, singleP12[3][0], "P1.2: Color depth must evolve 0.875->0.75->0.6875");
        assertEquals(0.6875f, singleP12[1][0], "P1.2: Motion depth must evolve 0.625->0.75->0.6875");
        assertEquals(6.5f, singleP12[0][0], "P1.2: Q=0.6875 must write velocity after P=0.75 advances V from 0.625 to 0.75");
        assertEquals(splitP12[0][0], singleP12[0][0], "P1.2: Single batch must equal split batches (vel)");
        assertEquals(splitP12[1][0], singleP12[1][0], "P1.2: Single batch must equal split batches (V)");
        assertEquals(1.25f, singleP12[0][1], "P1.2: Uncovered pixel 1 must preserve earlier 0.625 velocity");

        // P1.3: Write-depth NOT_EQUAL, C=0.25, V=0.625 (independent closer foreground depth_write=false at 0.625).
        // Low-opacity P=0.875 then low-opacity Q=0.5, followed by eligible LESS R=0.625:
        // V must evolve 0.625 -> 0.875 -> 0.5, so R=0.625 passes BOTH color (0.625>0.5) AND velocity (0.625>0.5)!
        var seedFg0625 = new MultiPrimBatch("seed_fg0625", translucentLessEqual,
                java.util.List.of(new Prim(0.625f, 1.5f, true, true, false, 0.8f)));
        var primP13P = new Prim(0.875f, 2.0f, true, false, false, 0.1f);
        var primP13Q = new Prim(0.5f, 3.0f, true, false, false, 0.1f);
        var primP13R = new Prim(0.625f, 7.75f, true, false, false, 0.8f);
        var batchP13R = new MultiPrimBatch("P1_3_R", translucentLessWriteDepth, java.util.List.of(primP13R));
        float[][] singleP13Vel = runMultiPrimTranslucent.apply(0.25f, java.util.List.of(java.util.List.of(
                seedFg0625,
                new MultiPrimBatch("P1_3_single", translucentNotEqualWriteDepth, java.util.List.of(primP13P, primP13Q)),
                batchP13R)));
        float[][] splitP13Vel = runMultiPrimTranslucent.apply(0.25f, java.util.List.of(java.util.List.of(
                seedFg0625,
                new MultiPrimBatch("P1_3_P", translucentNotEqualWriteDepth, java.util.List.of(primP13P)),
                new MultiPrimBatch("P1_3_Q", translucentNotEqualWriteDepth, java.util.List.of(primP13Q)),
                batchP13R)));
        // Consecutive non-velocity depth-writing translucent batches sharing one group (and cross-stage split)!
        float[][] consecutiveNonVelP13 = runMultiPrimTranslucent.apply(0.25f, java.util.List.of(java.util.List.of(
                seedFg0625,
                new MultiPrimBatch("P1_3_nvP", translucentNonVelNotEqualDepth, java.util.List.of(primP13P)),
                new MultiPrimBatch("P1_3_nvQ", translucentNonVelNotEqualDepth, java.util.List.of(primP13Q)),
                batchP13R)));
        float[][] singleNonVelMultiPrimP13 = runMultiPrimTranslucent.apply(0.25f, java.util.List.of(java.util.List.of(
                seedFg0625,
                new MultiPrimBatch("P1_3_nvSingle", translucentNonVelNotEqualDepth, java.util.List.of(primP13P, primP13Q)),
                batchP13R)));
        float[][] crossStageP13 = runMultiPrimTranslucent.apply(0.25f, java.util.List.of(
                java.util.List.of(seedFg0625, new MultiPrimBatch("P1_3_nvP", translucentNonVelNotEqualDepth, java.util.List.of(primP13P))),
                java.util.List.of(new MultiPrimBatch("P1_3_nvQ", translucentNonVelNotEqualDepth, java.util.List.of(primP13Q)), batchP13R)));
        for (float[][] res : java.util.List.of(singleP13Vel, splitP13Vel, consecutiveNonVelP13, singleNonVelMultiPrimP13, crossStageP13)) {
            assertEquals(0.625f, res[3][0], "P1.3: Color depth must end at 0.625 written by R");
            assertEquals(0.625f, res[1][0], "P1.3: Motion depth must evolve 0.625->0.875->0.5->0.625");
            assertEquals(7.75f, res[0][0], "P1.3: R=0.625 MUST write velocity after P=0.875->Q=0.5 pulled V down to 0.5");
            assertEquals(1.5f, res[0][1], "P1.3: Uncovered pixel 1 must preserve independent foreground 0.625 velocity");
        }
        // P1.3 control: when P=0.55 (< V=0.625) then Q=0.5, independent foreground V=0.625 stays protected (R rejected)!
        float[][] ctrlP13Protected = runMultiPrimTranslucent.apply(0.25f, java.util.List.of(java.util.List.of(
                seedFg0625,
                new MultiPrimBatch("P1_3_ctrl", translucentNonVelNotEqualDepth, java.util.List.of(
                        new Prim(0.55f, 2.0f, true, false, false, 0.1f), primP13Q)),
                batchP13R)));
        assertEquals(0.625f, ctrlP13Protected[1][0], "P1.3 control: Independent foreground V=0.625 must stay protected when P=0.55 < 0.625");
        assertEquals(1.5f, ctrlP13Protected[0][0], "P1.3 control: Earlier foreground velocity must remain intact when R=0.625 fails LESS (0.625 > 0.625)");
        // Stale-velocity non-resurrection: eligible P=0.5 -> ineligible Q1=0.8 -> ineligible Q2=0.5 (NOT_EQUAL): stale P velocity MUST NOT resurrect!
        float[][] nonResurrect = runMultiPrimTranslucent.apply(0.25f, java.util.List.of(java.util.List.of(
                new MultiPrimBatch("nr_P", translucentNotEqualWriteDepth, java.util.List.of(
                        new Prim(0.5f, 4.25f, true, false, false, 0.8f),
                        new Prim(0.8f, 0.0f, true, false, false, 0.1f),
                        new Prim(0.5f, 0.0f, true, false, false, 0.1f))))));
        assertEquals(0.5f, nonResurrect[1][0], "Non-resurrection: V must end at 0.5");
        assertEquals(0.0f, nonResurrect[0][0], "Non-resurrection: Occluded P=0.5 velocity must NOT resurrect when Q2 lands back at 0.5");

        // P1.4: Write-depth ALWAYS velocity batch with a SINGLE low-opacity primitive P=0.75 on C=V=0.25,
        // followed by eligible GREATER Q=0.5: P must advance V 0.25->0.75 so Q writes velocity at 0.5!
        var primP14P = new Prim(0.75f, 2.5f, true, false, false, 0.1f);
        var primP14Q = new Prim(0.5f, 8.75f, true, false, false, 0.8f);
        float[][] p14Res = runMultiPrimTranslucent.apply(0.25f, java.util.List.of(java.util.List.of(
                new MultiPrimBatch("P1_4_ALWAYS_P", translucentAlwaysWriteDepth, java.util.List.of(primP14P)),
                new MultiPrimBatch("P1_4_GREATER_Q", translucentVelGreaterDepth, java.util.List.of(primP14Q)))));
        assertEquals(0.5f, p14Res[3][0], "P1.4: Color depth must evolve 0.25->0.75->0.5");
        assertEquals(0.5f, p14Res[1][0], "P1.4: ALWAYS low-opacity P=0.75 must advance V to 0.75 so GREATER Q=0.5 updates V to 0.5");
        assertEquals(8.75f, p14Res[0][0], "P1.4: Q=0.5 must write velocity after ALWAYS low-opacity P=0.75");
        // P1.4 with real discard on P=0.75: P is discarded, C=V stay 0.25, so GREATER Q=0.5 (0.5 < 0.25) is rejected!
        float[][] p14Discard = runMultiPrimTranslucent.apply(0.25f, java.util.List.of(java.util.List.of(
                new MultiPrimBatch("P1_4_ALWAYS_disc", translucentAlwaysWriteDepth, java.util.List.of(new Prim(0.75f, 2.5f, true, false, true, 0.1f))),
                new MultiPrimBatch("P1_4_GREATER_Q", translucentVelGreaterDepth, java.util.List.of(primP14Q)))));
        assertEquals(0.25f, p14Discard[3][0], "P1.4 discard control: Discarded ALWAYS P must not write color depth");
        assertEquals(0.0f, p14Discard[0][0], "P1.4 discard control: Rejected GREATER Q must not write velocity");

        // Overdraw > 32 streaming minimum-extraction fallback in TranslucentMotionReplayLog.resolvePixel:
        // 40 fragments submitted in shuffled order, with eligible winner at step 38 (z=0.6) followed by equal ineligible at step 39 (z=0.6).
        var overdrawFrags = new java.util.ArrayList<com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.FragmentNode>();
        for (int i = 0; i < 38; i++) {
            float z = (i % 2 == 0) ? 0.8f : 0.4f;
            overdrawFrags.add(new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.FragmentNode(
                    0, i, 0, z, com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.NOT_EQUAL,
                    true, false, z, 0.0f, 0.0f, 0.0f, 0.0f));
        }
        overdrawFrags.add(new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.FragmentNode(
                0, 38, 0, 0.6f, com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.NOT_EQUAL,
                true, true, 0.6f, 5.5f, 0.0f, 0.0f, 1.0f));
        overdrawFrags.add(new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.FragmentNode(
                0, 39, 0, 0.6f, com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.EQUAL,
                true, false, 0.6f, 0.0f, 0.0f, 0.0f, 0.0f));
        java.util.Collections.reverse(overdrawFrags);
        var overdrawInit = new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.PixelState(
                0.2f, 0.2f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, -1.0f);
        var overdrawRes = com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog
                .resolvePixel(overdrawInit, overdrawFrags);
        assertEquals(0.6f, overdrawRes.colorDepth(), "Overdraw >32 fallback: final C must be 0.6");
        assertEquals(0.6f, overdrawRes.motionDepth(), "Overdraw >32 fallback: final V must be 0.6");
        assertTrue(overdrawRes.hasValidVelocity() && overdrawRes.prevNdcX() == 5.5f,
                "Overdraw >32 fallback: streaming minimum-extraction must preserve exact submission order and winner velocity");

        // Round 15 P1 #1: Tiled overflow-proof replay (4K partitioning, overflow refusal + recursive subdivision + 1x1 step fallback,
        // static Vulkan tile invariant, and zero dropped fragments + exact pixel-for-pixel log vs step parity).
        int cap4kTile = com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.computeNodeCapacity(
                com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.MAX_TILE_PIXELS,
                com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.MAX_STORAGE_BYTES);
        assertTrue(cap4kTile > 2_000_000, "4K tile-sized head table must preserve >2M nodes inside 128 MiB budget (got " + cap4kTile + ")");
        var region4k = new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.TileRect(0, 0, 3840, 2160);
        for (boolean dynamicGl : new boolean[]{true, false}) {
            long testMaxPrims = dynamicGl ? 64L : 16L;
            var tiles4k = com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.planInitialTiles(
                    region4k, testMaxPrims,
                    com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.MAX_TILE_PIXELS,
                    cap4kTile, dynamicGl);
            long totalArea = 0L;
            for (int i = 0; i < tiles4k.size(); i++) {
                var t = tiles4k.get(i);
                assertTrue(t.width() > 0 && t.height() > 0 && t.x() >= 0 && t.y() >= 0
                        && t.x() + t.width() <= 3840 && t.y() + t.height() <= 2160);
                assertTrue(t.area() <= com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.MAX_TILE_PIXELS);
                if (!dynamicGl) {
                    assertTrue(t.area() * testMaxPrims <= cap4kTile,
                            "Static Vulkan tile must guarantee tile.area() * maxPrimitives <= capacity");
                }
                for (int j = i + 1; j < tiles4k.size(); j++) {
                    var o = tiles4k.get(j);
                    boolean disjoint = t.x() + t.width() <= o.x() || o.x() + o.width() <= t.x()
                            || t.y() + t.height() <= o.y() || o.y() + o.height() <= t.y();
                    assertTrue(disjoint, "Planned tiles must be pairwise disjoint");
                }
                totalArea += t.area();
            }
            assertEquals(3840L * 2160L, totalArea, "Planned 4K tiles must cover 100% of 3840x2160 without gaps");
        }
        // Extreme overflow stress: 4x4 screen, tiny nodeCapacity=3, 22 shuffled fragments including 8 stacked at (1,1)
        // mixing EQUAL, GREATER, NOT_EQUAL, ALWAYS, LESS, eligible & low-opacity.
        var initGrid = new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.PixelState[4][4];
        for (int gy = 0; gy < 4; gy++) {
            for (int gx = 0; gx < 4; gx++) {
                float initC = (gx == 1 && gy == 1) ? 0.25f : 0.5f;
                float initV = (gx == 1 && gy == 1) ? 0.625f : 0.5f;
                initGrid[gy][gx] = new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.PixelState(
                        initC, initV, 1.5f, 0.0f, 0.0f, 1.0f, initV, initV);
            }
        }
        var screenFrags = new java.util.ArrayList<com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.ScreenFragment>();
        // 8 stacked fragments at (1,1): NOT_EQUAL low-opacity 0.875 -> 0.5 -> LESS eligible 0.625 -> EQUAL low-opacity 0.625
        // -> ALWAYS low-opacity 0.75 -> GREATER eligible 0.6875 -> EQUAL eligible 0.6875 -> EQUAL low-opacity 0.6875
        screenFrags.add(new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.ScreenFragment(
                1, 1, new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.FragmentNode(
                        0, 0, 0, 0.875f, com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.NOT_EQUAL, true, false, 0.875f, 0, 0, 0, 0)));
        screenFrags.add(new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.ScreenFragment(
                1, 1, new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.FragmentNode(
                        0, 1, 0, 0.5f, com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.NOT_EQUAL, true, false, 0.5f, 0, 0, 0, 0)));
        screenFrags.add(new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.ScreenFragment(
                1, 1, new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.FragmentNode(
                        1, 0, 0, 0.625f, com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.LESS, true, true, 0.625f, 4.25f, 0, 0, 1.0f)));
        screenFrags.add(new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.ScreenFragment(
                1, 1, new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.FragmentNode(
                        1, 1, 0, 0.625f, com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.EQUAL, true, false, 0.625f, 0, 0, 0, 0)));
        screenFrags.add(new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.ScreenFragment(
                1, 1, new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.FragmentNode(
                        2, 0, 0, 0.75f, com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.ALWAYS, true, false, 0.75f, 0, 0, 0, 0)));
        screenFrags.add(new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.ScreenFragment(
                1, 1, new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.FragmentNode(
                        3, 0, 0, 0.6875f, com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.GREATER, true, true, 0.6875f, 8.75f, 0, 0, 1.0f)));
        screenFrags.add(new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.ScreenFragment(
                1, 1, new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.FragmentNode(
                        3, 1, 0, 0.6875f, com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.EQUAL, true, true, 0.6875f, 9.25f, 0, 0, 1.0f)));
        screenFrags.add(new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.ScreenFragment(
                1, 1, new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.FragmentNode(
                        3, 2, 0, 0.6875f, com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.EQUAL, true, false, 0.6875f, 0, 0, 0, 0)));
        screenFrags.add(new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.ScreenFragment(
                1, 1, new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.FragmentNode(
                        3, 3, 0, 0.6875f, com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.EQUAL, true, false, 0.6875f, 0, 0, 0, 0)));
        screenFrags.add(new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.ScreenFragment(
                1, 1, new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.FragmentNode(
                        3, 4, 0, 0.6875f, com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.EQUAL, true, false, 0.6875f, 0, 0, 0, 0)));
        // Scatter 14 more fragments across other pixels so multiple tiles also overflow and split.
        for (int k = 0; k < 14; k++) {
            int fx = k % 4;
            int fy = (k / 4) % 4;
            if (fx == 1 && fy == 1) fx = 2;
            float z = 0.5f + 0.02f * (k + 1);
            screenFrags.add(new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.ScreenFragment(
                    fx, fy, new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.FragmentNode(
                            4, k, 0, z, com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.LESS, true, (k % 2 == 0), z,
                            2.0f + k, 0, 0, (k % 2 == 0) ? 1.0f : 0.0f)));
        }
        var shuffledScreenFrags = new java.util.ArrayList<>(screenFrags);
        java.util.Collections.reverse(shuffledScreenFrags);
        // nodeCapacity=8 -> dynamic GL starts with 2x1 tiles (maxArea=8/4=2); (1,1) has 10>8 fragments -> splits to 1x1 -> step fallback!
        var glOverflowSim = com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.simulateTiledReplay(
                4, 4, initGrid, shuffledScreenFrags, 8, 16, true, 24L);
        assertTrue(glOverflowSim.overflowSplits() > 0, "Overflowing tile must refuse resolve and split recursively");
        assertTrue(glOverflowSim.singlePixelStepFallbacks() >= 1, "1x1 pixel exceeding nodeCapacity must route to exact step fallback");
        assertEquals(0, glOverflowSim.droppedFragments(), "Tiled overflow-proof replay must drop ZERO fragments");
        var vkStepFallbackSim = com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.simulateTiledReplay(
                4, 4, initGrid, shuffledScreenFrags, 8, 16, false, 24L);
        assertEquals(0, vkStepFallbackSim.droppedFragments(), "Static Vulkan maxPrimitives > capacity fallback must drop ZERO fragments");
        var vkStaticTiledSim = com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.simulateTiledReplay(
                4, 4, initGrid, shuffledScreenFrags, 64, 16, false, 24L);
        assertEquals(0, vkStaticTiledSim.overflowSplits(), "Static Vulkan tiled partition must never overflow");
        assertEquals(0, vkStaticTiledSim.droppedFragments(), "Static Vulkan tiled partition must drop ZERO fragments");
        // Verify pixel-for-pixel equality across all 16 pixels between OpenGL tiled+split+1x1 fallback,
        // Vulkan step fallback, Vulkan static tiling, and direct resolvePixel ground truth!
        for (int gy = 0; gy < 4; gy++) {
            for (int gx = 0; gx < 4; gx++) {
                var pxNodes = new java.util.ArrayList<com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.FragmentNode>();
                for (var sf : shuffledScreenFrags) {
                    if (sf.x() == gx && sf.y() == gy) pxNodes.add(sf.node());
                }
                var groundTruth = com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog
                        .resolvePixel(initGrid[gy][gx], pxNodes);
                assertEquals(groundTruth, glOverflowSim.pixels()[gy][gx], "GL overflow-split pixel (" + gx + "," + gy + ") mismatch");
                assertEquals(groundTruth, vkStepFallbackSim.pixels()[gy][gx], "Vulkan step-fallback pixel (" + gx + "," + gy + ") mismatch");
                assertEquals(groundTruth, vkStaticTiledSim.pixels()[gy][gx], "Vulkan static-tiled pixel (" + gx + "," + gy + ") mismatch");
            }
        }
        assertEquals(0.6875f, glOverflowSim.pixels()[1][1].colorDepth());
        assertEquals(0.6875f, glOverflowSim.pixels()[1][1].motionDepth());
        assertEquals(9.25f, glOverflowSim.pixels()[1][1].prevNdcX());
        assertTrue(glOverflowSim.pixels()[1][1].hasValidVelocity());
        // Defense-in-depth shader checks: sticky overflow flag survives 32-bit counter wraparound; step_merge is pure #version 330, 2 attachments.
        String clearShaderSrc = java.nio.file.Files.readString(java.nio.file.Path.of(
                "common/src/main/resources/assets/lumenfx/shaders/temporal/translucent_motion_log_clear.fsh"));
        assertTrue(clearShaderSrc.contains("lfxMotionLogOverflow = 0u;"),
                "translucent_motion_log_clear.fsh must reset sticky lfxMotionLogOverflow before each tile capture");
        assertTrue(fullNoPdoLogFrag.contains("atomicOr(lfxMotionLogOverflow, 1u);")
                        && fullNoPdoLogFrag.contains("if (lfxMotionLogOverflow != 0u) discard;"),
                "Translucent motion log capture must latch sticky monotonic lfxMotionLogOverflow via atomicOr");
        String resolveShaderSrc = java.nio.file.Files.readString(java.nio.file.Path.of(
                "common/src/main/resources/assets/lumenfx/shaders/temporal/translucent_motion_log_resolve.fsh"));
        assertTrue(resolveShaderSrc.contains("if (lfxMotionLogOverflow != 0u || lfxMotionLogCounter > lfxMotionLogCapacity)"),
                "translucent_motion_log_resolve.fsh must guard on both sticky lfxMotionLogOverflow and counter > capacity");
        assertFalse(resolveShaderSrc.contains("location = 2"), "Translucent resolve shader must keep 2 attachments only (Issue 19)");
        String stepMergeShaderSrc = java.nio.file.Files.readString(java.nio.file.Path.of(
                "common/src/main/resources/assets/lumenfx/shaders/temporal/translucent_motion_step_merge.fsh"));
        assertTrue(stepMergeShaderSrc.trim().startsWith("#version 330"), "Step merge shader must stay pure #version 330");
        assertFalse(stepMergeShaderSrc.contains("gl_PrimitiveID") || stepMergeShaderSrc.contains("std430")
                || stepMergeShaderSrc.contains("location = 2"),
                "Step merge shader must use zero gl_PrimitiveID, zero SSBO, and 2 attachments only");

        // Counterexample 2: 32-bit uint counter wraparound (e.g. 960x540 tile x 8,286 layers = 4,295,462,400 -> wraps to 495,104 < capacity,
        // and exact 2^32 = 4,294,967,296 -> wraps to 0) must still be detected as overflowed and never committed or skipped as empty!
        int cap960x540 = com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.computeNodeCapacity(
                960 * 540, com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.MAX_STORAGE_BYTES);
        var wrap495k = com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog
                .simulateGpuCaptureCounter(8_286L * 960L * 540L, cap960x540);
        assertEquals(495_104L, wrap495k.counter(), "4,295,462,400 mod 2^32 must equal 495,104");
        assertTrue(wrap495k.counter() < cap960x540, "Wrapped 32-bit counter is below tile capacity");
        assertTrue(wrap495k.overflowed() && wrap495k.isOverflowed(cap960x540) && !wrap495k.isEmpty(cap960x540),
                "Sticky overflow flag must catch 32-bit wraparound below tile capacity");
        var wrapZero = com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog
                .simulateGpuCaptureCounter(1L << 32, cap960x540);
        assertEquals(0L, wrapZero.counter());
        assertTrue(wrapZero.overflowed() && wrapZero.isOverflowed(cap960x540) && !wrapZero.isEmpty(cap960x540),
                "Exact 2^32 zero-wrap must be reported as overflowed and NEVER skipped as empty");
        var multiWrap = com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog
                .simulateGpuCaptureCounter((1L << 33) + 100L, cap960x540);
        assertEquals(100L, multiWrap.counter());
        assertTrue(multiWrap.overflowed() && multiWrap.isOverflowed(cap960x540));
        var emptyStatus = com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog
                .simulateGpuCaptureCounter(0L, cap960x540);
        assertTrue(emptyStatus.isEmpty(cap960x540) && !emptyStatus.isOverflowed(cap960x540));
        var exactCapStatus = com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog
                .simulateGpuCaptureCounter(cap960x540, cap960x540);
        assertFalse(exactCapStatus.isEmpty(cap960x540) || exactCapStatus.isOverflowed(cap960x540));
        var capPlusOneStatus = com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog
                .simulateGpuCaptureCounter(cap960x540 + 1L, cap960x540);
        assertTrue(capPlusOneStatus.overflowed() && capPlusOneStatus.isOverflowed(cap960x540));

        // Counterexample 1: Non-convex 4-vertex SpriteCutout (0,0), (0.5,0.2), (1,0), (0.5,1) triangulated with
        // SpriteCutoutBuffers indices {0,2,3, 0,1,2} produces two opposite-winding overlapping triangles at (0.5, 0.1).
        // Under double-sided EQUAL (C=V=0.5), tri 0 eligible (vel=3.75) followed by tri 1 low-opacity (!eligible):
        // log replay AND per-triangle step replay must produce identical coverage and velocity (3.75)!
        var nonConvexCutout = new com.kltyton.lumenfx.effect.render.SpriteCutout(
                4, new float[]{0.0f, 0.0f, 0.5f, 0.2f, 1.0f, 0.0f, 0.5f, 1.0f}, true);
        assertEquals(6, nonConvexCutout.indexCount());
        int[] cutoutIndices = new int[]{0, 2, 3, 0, 1, 2};
        float[] cCoords = nonConvexCutout.coordinates();
        java.util.function.BiPredicate<Integer, float[]> pointInCutoutTri = (triIdx, pt) -> {
            int i0 = cutoutIndices[triIdx * 3], i1 = cutoutIndices[triIdx * 3 + 1], i2 = cutoutIndices[triIdx * 3 + 2];
            float x0 = cCoords[i0 * 2], y0 = cCoords[i0 * 2 + 1];
            float x1 = cCoords[i1 * 2], y1 = cCoords[i1 * 2 + 1];
            float x2 = cCoords[i2 * 2], y2 = cCoords[i2 * 2 + 1];
            float px = pt[0], py = pt[1];
            float e0 = (x1 - x0) * (py - y0) - (y1 - y0) * (px - x0);
            float e1 = (x2 - x1) * (py - y1) - (y2 - y1) * (px - x1);
            float e2 = (x0 - x2) * (py - y2) - (y0 - y2) * (px - x2);
            return (e0 >= 0 && e1 >= 0 && e2 >= 0) || (e0 <= 0 && e1 <= 0 && e2 <= 0);
        };
        float[] overlapSample = new float[]{0.5f, 0.1f};
        assertTrue(pointInCutoutTri.test(0, overlapSample) && pointInCutoutTri.test(1, overlapSample),
                "Non-convex 4-vertex cutout must overlap both triangle 0 {0,2,3} and triangle 1 {0,1,2} at (0.5, 0.1)");
        var cutoutInit = new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.PixelState(
                0.5f, 0.5f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, -1.0f);
        var cutoutTri0 = new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.FragmentNode(
                0, 0, 0, 0.5f, com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.EQUAL, true, true, 0.5f, 3.75f, 0, 0, 1.0f);
        var cutoutTri1 = new com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.FragmentNode(
                0, 0, 1, 0.5f, com.kltyton.lumenfx.effect.render.MaterialDepth.Compare.EQUAL, true, false, 0.5f, 0, 0, 0, 0);
        var cutoutLogRes = com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog
                .resolvePixel(cutoutInit, java.util.List.of(cutoutTri1, cutoutTri0));
        var cutoutStepRes = cutoutInit;
        for (int tri = 0; tri < nonConvexCutout.indexCount() / 3; tri++) {
            var n = tri == 0 ? cutoutTri0 : cutoutTri1;
            cutoutStepRes = com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionStepReplay.applyPrimitiveStep(
                    cutoutStepRes, n.fragDepth(), n.compare(), n.writesColorDepth(), n.velocityEligible(),
                    n.currentClipDepth(), n.prevNdcX(), n.prevNdcY(), n.prevNdcZ(), n.prevNdcW());
        }
        assertEquals(cutoutLogRes, cutoutStepRes,
                "Non-convex SpriteCutout per-triangle step replay must match log replay bit-for-bit");
        assertEquals(3.75f, cutoutStepRes.prevNdcX(), "Triangle 0 velocity must survive low-opacity overlapping triangle 1");
        assertTrue(cutoutStepRes.hasValidVelocity());
        // Bytecode contract check: every draw/drawIndexed call inside EffectGpuRenderer.drawTranslucentStepReplay
        // (and its step lambdas) must submit strictly 1 triangle (constant 3 vertices/indices)!
        byte[] rendererBytes = readBytesResource("/com/kltyton/lumenfx/client/render/EffectGpuRenderer.class");
        var rendererNode = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(rendererBytes).accept(rendererNode, 0);
        int stepDrawCalls = 0;
        for (var m : rendererNode.methods) {
            if (!m.name.startsWith("lambda$drawTranslucentStepReplay$")) continue;
            for (var insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call
                        && call.owner.equals("com/mojang/blaze3d/systems/RenderPass")
                        && (call.name.equals("draw") || call.name.equals("drawIndexed"))) {
                    stepDrawCalls++;
                    // For RenderPass.draw(int count, int instances, int firstVertex, int firstInstance) -> count is 4th arg from top;
                    // for RenderPass.drawIndexed(int count, int instances, int firstIndex, int baseVertex, int firstInstance) -> count is 5th arg from top.
                    // Verify no dynamic indexCount() or ICONST_6 is pushed inside step lambdas!
                    for (var check = m.instructions.getFirst(); check != null; check = check.getNext()) {
                        assertFalse(check instanceof org.objectweb.asm.tree.MethodInsnNode c && c.name.equals("indexCount"),
                                "Step lambda " + m.name + " must not submit whole cutout indexCount() in one step");
                        assertFalse(check.getOpcode() == org.objectweb.asm.Opcodes.BIPUSH
                                && ((org.objectweb.asm.tree.IntInsnNode) check).operand == 6,
                                "Step lambda " + m.name + " must not submit 6-vertex quad in one step");
                    }
                }
            }
        }
        assertTrue(stepDrawCalls >= 6, "Expected all CPU/GPU Sprite/Ribbon/Mesh step lambdas to submit single-triangle draws (found " + stepDrawCalls + ")");

        // Round 15 P1 #2: Vulkan geometryShader + fragmentStoresAndAtomics actual-enabled feature wiring & capability gate.
        byte[] vkMixinBytes = readBytesResource("/com/kltyton/lumenfx/client/render/compute/mixin/VulkanComputeFeaturesMixin.class");
        var vkMixinNode = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(vkMixinBytes).accept(vkMixinNode, 0);
        boolean callsGeomShader = false;
        boolean callsFragStores = false;
        boolean callsRecordEnabled = false;
        for (var m : vkMixinNode.methods) {
            for (var insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call) {
                    if ("geometryShader".equals(call.name)) callsGeomShader = true;
                    if ("fragmentStoresAndAtomics".equals(call.name)) callsFragStores = true;
                    if ("record".equals(call.name) && call.owner.endsWith("VulkanEnabledFeatures")) callsRecordEnabled = true;
                }
            }
        }
        assertTrue(callsGeomShader && callsFragStores && callsRecordEnabled,
                "VulkanComputeFeaturesMixin must enable both fragmentStoresAndAtomics and geometryShader and record actual enabled state");
        try {
            com.kltyton.lumenfx.client.render.compute.VulkanEnabledFeatures.record(true, false);
            assertTrue(com.kltyton.lumenfx.client.render.compute.VulkanEnabledFeatures.isRecorded()
                    && com.kltyton.lumenfx.client.render.compute.VulkanEnabledFeatures.fragmentStoresAndAtomics()
                    && !com.kltyton.lumenfx.client.render.compute.VulkanEnabledFeatures.geometryShader());
            com.kltyton.lumenfx.client.render.compute.VulkanEnabledFeatures.record(true, true);
            assertTrue(com.kltyton.lumenfx.client.render.compute.VulkanEnabledFeatures.geometryShader());
        } finally {
            com.kltyton.lumenfx.client.render.compute.VulkanEnabledFeatures.resetForTesting();
        }
        assertTrue(com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog
                .supportsHardwareLogForFeatures(true, false, true, true));
        assertFalse(com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog
                .supportsHardwareLogForFeatures(true, true, true, false), "Vulkan without geometryShader must route to step replay");
        assertFalse(com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog
                .supportsHardwareLogForFeatures(true, true, false, true), "Vulkan without fragmentStoresAndAtomics must route to step replay");
        assertTrue(com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog
                .supportsHardwareLogForFeatures(false, true, false, false));
        assertFalse(com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog
                .supportsHardwareLogForFeatures(false, false, true, true));

        // Round 15 P1 #3 + Vulkan fallback equivalence: Full UE Mesh, PDO, Legacy, and RVT shaders through translucentStepCapture
        // (pure #version 330, zero gl_PrimitiveID, zero SSBO, 2 attachments, PDO preserved, RVT suppressed, indirect guard supported).
        for (boolean hasVel : new boolean[]{true, false}) {
            for (boolean indirectGuard : new boolean[]{false, true}) {
                var stepNoPdoSet = fullAdapter.translucentStepCapture(fullNoPdoOrdPipe, false, hasVel, indirectGuard);
                String stepNoPdoFrag = stepNoPdoSet.source().get(stepNoPdoSet.fragmentShader(), com.mojang.blaze3d.shaders.ShaderType.FRAGMENT);
                assertTrue(stepNoPdoFrag.trim().startsWith("#version 330"), "Step capture shader must stay #version 330");
                String stepNoPdoTail = stepNoPdoFrag.substring(stepNoPdoFrag.indexOf("#undef main"));
                assertFalse(stepNoPdoTail.contains("gl_PrimitiveID") || stepNoPdoTail.contains("std430")
                        || stepNoPdoTail.contains("LfxTranslucentMotionLog"));
                var stepNoPdoPipe = com.kltyton.lumenfx.client.render.motion.shader.MotionPipelineFactory.translucentStepCapture(
                        fullNoPdoOrdPipe, stepNoPdoSet, com.kltyton.lumenfx.LumenFx.id("test/step_no_pdo_" + hasVel + "_" + indirectGuard),
                        null, new com.mojang.blaze3d.pipeline.DepthStencilState(com.mojang.blaze3d.platform.CompareOp.NOT_EQUAL, true),
                        hasVel, indirectGuard);
                var stepNoPdoScan = com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceScanner.scan(
                        com.mojang.blaze3d.preprocessor.GlslPreprocessor.injectDefines(stepNoPdoFrag, stepNoPdoPipe.getShaderDefines()),
                        stepNoPdoPipe.getShaderDefines());
                assertEquals(2, stepNoPdoScan.mains().size());
                assertEquals(java.util.List.of("lfxMotionShader_motionClip", "lfxMotionShader_motionDepth"),
                        stepNoPdoScan.outputs().stream().map(com.kltyton.lumenfx.client.resource.shader.glsl.GlslOutputDeclaration::name).toList());
                assertFalse(stepNoPdoScan.identifiers().contains("gl_FragDepth"),
                        "No-PDO translucentStepCapture must NOT reference gl_FragDepth");
                assertFalse(stepNoPdoScan.identifiers().contains("gl_PrimitiveID")
                                || stepNoPdoScan.identifiers().contains("LfxTranslucentMotionLog"),
                        "Active preprocessed translucentStepCapture must NOT reference gl_PrimitiveID or SSBO log");
            }
            var stepPdoSet = fullAdapter.translucentStepCapture(fullPdoOrdPipe, false, hasVel, false);
            String stepPdoFrag = stepPdoSet.source().get(stepPdoSet.fragmentShader(), com.mojang.blaze3d.shaders.ShaderType.FRAGMENT);
            assertTrue(stepPdoFrag.substring(stepPdoFrag.indexOf("#undef main")).contains("gl_FragDepth"),
                    "PDO translucentStepCapture (hasVelocity=" + hasVel + ") must preserve gl_FragDepth");
            var stepVtSet = fullAdapter.translucentStepCapture(fullVtPipe, false, hasVel, false);
            String stepVtFrag = stepVtSet.source().get(stepVtSet.fragmentShader(), com.mojang.blaze3d.shaders.ShaderType.FRAGMENT);
            var stepVtPipe = com.kltyton.lumenfx.client.render.motion.shader.MotionPipelineFactory.translucentStepCapture(
                    fullVtPipe, stepVtSet, com.kltyton.lumenfx.LumenFx.id("test/step_vt_" + hasVel),
                    null, new com.mojang.blaze3d.pipeline.DepthStencilState(com.mojang.blaze3d.platform.CompareOp.GREATER_THAN_OR_EQUAL, true),
                    hasVel, false);
            var stepVtScan = com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceScanner.scan(
                    com.mojang.blaze3d.preprocessor.GlslPreprocessor.injectDefines(stepVtFrag, stepVtPipe.getShaderDefines()),
                    stepVtPipe.getShaderDefines());
            assertFalse(stepVtScan.identifiers().contains("atomicAdd"),
                    "RVT feedback must stay suppressed in translucentStepCapture (hasVelocity=" + hasVel + ")");
        }
        var stepLegacySet = fullAdapter.translucentStepCapture(fullLegacyPipe, false, true, false);
        String stepLegacyFrag = stepLegacySet.source().get(stepLegacySet.fragmentShader(), com.mojang.blaze3d.shaders.ShaderType.FRAGMENT);
        assertTrue(stepLegacyFrag.contains("lfxMotionVelocityEligible = (opacity - 1.0 / 255.0 - 0.3333) >= 0.0;"),
                "Legacy baked translucent shader must rewrite cutoff to lfxMotionVelocityEligible in translucentStepCapture");
        // Bit-for-bit parity between TranslucentMotionReplayLog.resolvePixel and TranslucentMotionStepReplay.applyPrimitiveStep on 40-layer overdraw!
        var stepParityState = overdrawInit;
        var sortedOverdraw = new java.util.ArrayList<>(overdrawFrags);
        java.util.Collections.reverse(sortedOverdraw);
        for (var n : sortedOverdraw) {
            stepParityState = com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionStepReplay.applyPrimitiveStep(
                    stepParityState, n.fragDepth(), n.compare(), n.writesColorDepth(), n.velocityEligible(),
                    n.currentClipDepth(), n.prevNdcX(), n.prevNdcY(), n.prevNdcZ(), n.prevNdcW());
        }
        assertEquals(overdrawRes, stepParityState, "TranslucentMotionStepReplay must be bit-for-bit identical to TranslucentMotionReplayLog");

        // Issue 21: Unity root Sorting Group with empty/whitespace root name encodes to non-blank injective #root IDs.
        var origin = new net.minecraft.world.phys.Vec3(0, 0, 0);
        for (String validId : java.util.List.of("#root", "   #root", "%23root#root", "#root/#0", "#root/#1", "A%2FB#root/C%231%252#0")) {
            var group = new com.kltyton.lumenfx.effect.unity.UnityRendererBinding.SortingGroup(validId, 0, 0, origin, 0);
            assertEquals(validId, group.id());
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new com.kltyton.lumenfx.effect.unity.UnityRendererBinding.SortingGroup("   ", 0, 0, origin, 0));
    }

    @Test
    void keyframeBridgeParsesDirectivesRoutesLoadedEffectsPassesThroughKbrAndTracksLocators() {
        // 1. Parse effect ID formats (explicit prefix, Identifier-safe slash prefix, standard, bare)
        var explicitColon = com.kltyton.lumenfx.api.client.keyframe.LumenFxKeyframeBridge.parseEffectId("lumenfx:my_mod:slash");
        assertNotNull(explicitColon);
        assertTrue(explicitColon.explicitLumenFx());
        assertEquals("my_mod:slash", explicitColon.canonicalId());

        var explicitSlash = com.kltyton.lumenfx.api.client.keyframe.LumenFxKeyframeBridge.parseEffectId("lumenfx/my_mod:slash");
        assertNotNull(explicitSlash);
        assertTrue(explicitSlash.explicitLumenFx());
        assertEquals("my_mod:slash", explicitSlash.canonicalId());

        var standard = com.kltyton.lumenfx.api.client.keyframe.LumenFxKeyframeBridge.parseEffectId("my_mod:slash");
        assertNotNull(standard);
        assertFalse(standard.explicitLumenFx());
        assertEquals("my_mod:slash", standard.canonicalId());

        // 2. Parse pre_effect_script directives including Molang-style prefixes
        var params = com.kltyton.lumenfx.api.client.keyframe.KeyframeEffectParams.parse(
                "v.lumenfx_scale=1.75; color=#FF8040CC; offset=0.5,1.25,-0.5; rotate=false; beam_end=target_tip; seed=4242; restart=true",
                true, true);
        assertTrue(params.follow());
        assertFalse(params.rotate());
        assertEquals(1.75F, params.scale(), 1e-5F);
        assertTrue(params.hasColor());
        assertEquals(1.0F, params.red(), 1e-3F);
        assertEquals(128.0F / 255.0F, params.green(), 1e-3F);
        assertEquals(64.0F / 255.0F, params.blue(), 1e-3F);
        assertEquals(204.0F / 255.0F, params.alpha(), 1e-3F);
        assertEquals(0.5, params.offsetX(), 1e-6);
        assertEquals(1.25, params.offsetY(), 1e-6);
        assertEquals(-0.5, params.offsetZ(), 1e-6);
        assertEquals("target_tip", params.beamEndLocator());
        assertEquals(4242L, params.seed());
        assertTrue(params.restart());
        assertFalse(params.stop());

        // 3. Routing, deferred locator spawn, 3D pose update, beam_end, filter cancellation, stop, and controller reset
        var prevAdapter = com.kltyton.lumenfx.client.keyframe.KeyframeEffectManager.adapter();
        List<double[]> poseLog = new java.util.ArrayList<>();
        List<double[]> beamLog = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger closeCount = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger spawnCount = new java.util.concurrent.atomic.AtomicInteger();
        java.util.Set<String> loaded = java.util.Set.of("my_mod:fire_slash", "lumenfx:beam_arc");

        com.kltyton.lumenfx.client.keyframe.KeyframeEffectPlaybackAdapter stub =
                new com.kltyton.lumenfx.client.keyframe.KeyframeEffectPlaybackAdapter() {
                    @Override public boolean hasEffect(String canonicalId) { return loaded.contains(canonicalId); }
                    @Override public boolean requiresLevel() { return false; }
                    @Override public BoundHandle spawn(net.minecraft.client.multiplayer.ClientLevel level, String canonicalId,
                            double x, double y, double z, long seed,
                            com.kltyton.lumenfx.api.client.keyframe.KeyframeEffectParams p, boolean trackRenderSamples) {
                        spawnCount.incrementAndGet();
                        return new BoundHandle() {
                            boolean alive = true;
                            @Override public Object rawHandle() { return this; }
                            @Override public boolean isAlive() { return alive; }
                            @Override public void updatePose(double px, double py, double pz, float qx, float qy, float qz, float qw,
                                    float scale, double[] actorPosition, double[] actorVelocityCmPerSec) {
                                poseLog.add(new double[] { px, py, pz, qx, qy, qz, qw, scale });
                            }
                            @Override public void setBeamEnd(double bx, double by, double bz) {
                                beamLog.add(new double[] { bx, by, bz });
                            }
                            @Override public void close() {
                                if (alive) { alive = false; closeCount.incrementAndGet(); }
                            }
                        };
                    }
                };

        com.kltyton.lumenfx.client.keyframe.KeyframeEffectManager.clearAll();
        com.kltyton.lumenfx.client.keyframe.KeyframeEffectManager.installAdapter(stub);
        try {
            Object host = new Object();
            Object controller = new Object();

            // Counterexample: unknown KBR/SnowStorm particle must return false so KBR handles it untouched.
            assertFalse(com.kltyton.lumenfx.client.keyframe.KeyframeEffectManager.handleKeyframe(
                    null, host, null, controller, "attack", "kbr:snowstorm_spark", "right_hand", null, true, 0, 0, 0, true));

            // Explicit lumenfx: prefix for missing effect returns true (claimed) without crashing.
            assertTrue(com.kltyton.lumenfx.client.keyframe.KeyframeEffectManager.handleKeyframe(
                    null, host, null, controller, "attack", "lumenfx:my_mod:missing_fx", null, null, true, 0, 0, 0, true));

            // Filter can reject a known LumenFX effect and let KBR/custom handler run instead.
            com.kltyton.lumenfx.api.client.keyframe.LumenFxKeyframeBridge.Filter blockSlash =
                    req -> !req.resolvedEffectId().equals("my_mod:fire_slash");
            com.kltyton.lumenfx.api.client.keyframe.LumenFxKeyframeBridge.addFilter(blockSlash);
            try {
                assertFalse(com.kltyton.lumenfx.client.keyframe.KeyframeEffectManager.handleKeyframe(
                        null, host, null, controller, "attack", "my_mod:fire_slash", "sword_tip", null, true, 0, 0, 0, true));
            } finally {
                com.kltyton.lumenfx.api.client.keyframe.LumenFxKeyframeBridge.removeFilter(blockSlash);
            }

            // Identifier-safe cross-namespace syntax (lumenfx:my_mod/fire_slash) with deferred locator spawn + beam_end + SpawnListener
            java.util.concurrent.atomic.AtomicInteger listenerHits = new java.util.concurrent.atomic.AtomicInteger();
            com.kltyton.lumenfx.api.client.keyframe.LumenFxKeyframeBridge.SpawnListener listener =
                    (req, handle) -> listenerHits.incrementAndGet();
            com.kltyton.lumenfx.api.client.keyframe.LumenFxKeyframeBridge.addSpawnListener(listener);
            try {
                assertTrue(com.kltyton.lumenfx.client.keyframe.KeyframeEffectManager.handleKeyframe(
                        null, host, null, controller, "attack", "lumenfx:my_mod/fire_slash",
                        "sword_tip", "scale=2.0; offset=0.5,0,0; beam_end=target_tip", true, 1, 2, 3, true));
                assertEquals(0, spawnCount.get(), "Deferred locator binding spawns on first locator sample");
                assertTrue(com.kltyton.lumenfx.client.keyframe.KeyframeEffectManager.trackedLocators(host)
                        .containsAll(java.util.Set.of("sword_tip", "target_tip")));

                // First locator sample spawns handle and pushes 3D pose (position + offset + quaternion + scale)
                com.kltyton.lumenfx.client.keyframe.KeyframeEffectManager.onLocatorSample(
                        host, null, "sword_tip", 10.0, 20.0, 30.0, 0.0F, 0.7071F, 0.0F, 0.7071F, null);
                assertEquals(1, spawnCount.get());
                assertEquals(1, listenerHits.get());
                assertEquals(1, poseLog.size());
                assertEquals(10.5, poseLog.get(0)[0], 1e-6);
                assertEquals(20.0, poseLog.get(0)[1], 1e-6);
                assertEquals(30.0, poseLog.get(0)[2], 1e-6);
                assertEquals(0.7071F, (float) poseLog.get(0)[4], 1e-4F);
                assertEquals(2.0F, (float) poseLog.get(0)[7], 1e-4F);

                // Beam end locator drives setBeamEnd
                com.kltyton.lumenfx.client.keyframe.KeyframeEffectManager.onLocatorSample(
                        host, null, "target_tip", 14.0, 22.0, 35.0, 0.0F, 0.0F, 0.0F, 1.0F, null);
                assertEquals(1, beamLog.size());
                assertEquals(14.0, beamLog.get(0)[0], 1e-6);

                // Controller reset closes active handle cleanly
                com.kltyton.lumenfx.client.keyframe.KeyframeEffectManager.stopForController(host, controller);
                assertEquals(1, closeCount.get());
                assertFalse(com.kltyton.lumenfx.client.keyframe.KeyframeEffectManager.hasTrackedLocators(host));
            } finally {
                com.kltyton.lumenfx.api.client.keyframe.LumenFxKeyframeBridge.removeSpawnListener(listener);
            }

            // Plugin bytecode probe returns false for absent classes without classloading them.
            var plugin = new com.kltyton.lumenfx.client.keyframe.LumenFxKeyframeMixinPlugin();
            assertFalse(plugin.shouldApplyMixin("com.nonexistent.AbsentGeckoLibClass", "DummyMixin"));
        } finally {
            com.kltyton.lumenfx.client.keyframe.KeyframeEffectManager.clearAll();
            if (prevAdapter != null) {
                com.kltyton.lumenfx.client.keyframe.KeyframeEffectManager.installAdapter(prevAdapter);
            }
        }
    }

    private static Map<String, Object> annotationValues(org.objectweb.asm.tree.AnnotationNode annotation) {
        var values = new java.util.LinkedHashMap<String, Object>();
        if (annotation.values != null) {
            for (int i = 0; i < annotation.values.size(); i += 2) {
                values.put((String) annotation.values.get(i), annotation.values.get(i + 1));
            }
        }
        return values;
    }

    public static final class SyntheticKbrDimensions {
        static boolean active;
        static float selectedHeight = 1.0f;
        static boolean oriented;

        public static boolean isActive() {
            return active;
        }

        public static float selectNext(float width) {
            return width;
        }

        public static float scaleY(float y, float width) {
            return y * (selectedHeight / width);
        }

        public static org.joml.Vector3f applyBasis(
                org.joml.Vector3f point, org.joml.Quaternionfc rotation,
                float horizontal, float vertical, float width) {
            if (!oriented) return point;
            return point.set(vertical * selectedHeight, 0.0f, horizontal * width).rotate(rotation);
        }
    }

    private static void assertDependency(String modsToml, String modId, String expectedType, String expectedRange) {
        String marker = "modId=\"" + modId + "\"";
        int idx = modsToml.indexOf(marker);
        assertTrue(idx >= 0, "Missing dependency block for " + modId);
        int nextBlock = modsToml.indexOf("[[", idx + marker.length());
        String block = nextBlock >= 0 ? modsToml.substring(idx, nextBlock) : modsToml.substring(idx);
        assertTrue(block.contains("type=\"" + expectedType + "\""),
                "Expected " + expectedType + " dependency for " + modId + ":\n" + block);
        assertTrue(block.contains("versionRange=\"" + expectedRange + "\""),
                "Expected versionRange " + expectedRange + " for " + modId + ":\n" + block);
    }

    @Test
    void packedComputeShaderBundleRoundTripsAllVolumeAndFogShadersLosslessly() throws Exception {
        var pack = com.kltyton.lumenfx.client.resource.shader.ComputeShaderPack.fromClasspath();
        assertNotNull(pack, "Missing /assets/lumenfx/shaders/compute.lfpack on test classpath");
        assertTrue(pack.size() >= 390, "Expected packed compute shaders, found " + pack.size());
        assertFalse(pack.contains("fog/integrate.comp"), "Unused GLSL source should not be packed");
        assertFalse(pack.contains("volume/preshaded.csh"), "Unused CSH source should not be packed");

        // Verify volume preshading program metadata + all vk/gl SPIR-V pairs inside the pack
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        JsonObject volProg = readJsonResource("/assets/lumenfx/shaders/compute/volume/preshaded.volume.program.json");
        int checkedVolumeSpirv = 0;
        for (String section : List.of("ray", "cache", "bake")) {
            if (!volProg.has(section)) continue;
            JsonObject secObj = volProg.getAsJsonObject(section);
            checkedVolumeSpirv += verifyVolumeArtifactsRecursive(secObj, sha256);
        }
        assertTrue(checkedVolumeSpirv >= 50, "Expected >= 50 volume SPIR-V shaders verified, got " + checkedVolumeSpirv);

        // Verify fog integration program metadata + vk/gl SPIR-V inside the pack
        JsonObject fogProg = readJsonResource("/assets/lumenfx/shaders/compute/fog/integrate/program.json");
        JsonObject fogArtifacts = fogProg.getAsJsonObject("artifacts");
        for (String backend : List.of("vulkan", "opengl")) {
            JsonObject art = fogArtifacts.getAsJsonObject(backend);
            byte[] bytes = readBytesResource("/assets/lumenfx/shaders/compute/fog/integrate/" + art.get("path").getAsString());
            assertEquals(art.get("size").getAsInt(), bytes.length);
            assertEquals(art.get("sha256").getAsString(), HexFormat.of().formatHex(sha256.digest(bytes)));
        }
    }

    private static int verifyVolumeArtifactsRecursive(JsonObject node, MessageDigest sha256) throws IOException {
        if (node.has("artifacts") && node.get("artifacts").isJsonObject()) {
            JsonObject artifacts = node.getAsJsonObject("artifacts");
            int count = 0;
            for (String backend : List.of("vulkan", "opengl")) {
                if (!artifacts.has(backend)) continue;
                JsonObject art = artifacts.getAsJsonObject(backend);
                String path = "/assets/lumenfx/shaders/compute/volume/" + art.get("path").getAsString();
                byte[] bytes = readBytesResource(path);
                assertEquals(art.get("size").getAsInt(), bytes.length, path);
                assertEquals(art.get("sha256").getAsString(), HexFormat.of().formatHex(sha256.digest(bytes)), path);
                assertEquals((byte) 0x03, bytes[0], path);
                assertEquals((byte) 0x02, bytes[1], path);
                assertEquals((byte) 0x23, bytes[2], path);
                assertEquals((byte) 0x07, bytes[3], path);
                count++;
            }
            return count;
        }
        int total = 0;
        for (String key : node.keySet()) {
            if (node.get(key).isJsonObject()) {
                total += verifyVolumeArtifactsRecursive(node.getAsJsonObject(key), sha256);
            }
        }
        return total;
    }

    private static JsonObject readJsonResource(String path) throws IOException {
        byte[] bytes = readBytesResource(path);
        try (var reader = new InputStreamReader(new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static byte[] readBytesResource(String path) throws IOException {
        try (InputStream stream = BakedShaderAssetsTest.class.getResourceAsStream(path)) {
            if (stream != null) {
                return stream.readAllBytes();
            }
        }
        String prefix = "/assets/lumenfx/shaders/compute/";
        if (path.startsWith(prefix)) {
            var pack = com.kltyton.lumenfx.client.resource.shader.ComputeShaderPack.fromClasspath();
            assertNotNull(pack, "Missing classpath resource and compute.lfpack for: " + path);
            byte[] packed = pack.getBytes(path.substring(prefix.length()));
            assertNotNull(packed, "Missing packed compute shader resource: " + path);
            return packed;
        }
        throw new AssertionError("Missing classpath resource: " + path);
    }
}
