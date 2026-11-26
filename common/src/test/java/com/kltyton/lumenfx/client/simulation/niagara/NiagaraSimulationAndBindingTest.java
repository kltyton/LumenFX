package com.kltyton.lumenfx.client.simulation.niagara;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.parameter.RendererParameter;
import com.kltyton.lumenfx.client.resource.EffectJson;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.client.simulation.niagara.renderer.NiagaraRendererFields;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache;
import com.kltyton.lumenfx.effect.niagara.parameter.RendererParameterDefaults;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Storage;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Variable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class NiagaraSimulationAndBindingTest {
    @Test
    void sourceCacheIncludesItsLastQuantizedFrame() {
        // UE 5.9 Preview 2 stores 1.9999991655 as StartSeconds and 2 as the only frame age.
        var layout = new NiagaraSimulationCache.Layout("system", true, false, 0, 0, 0, -1, List.of());
        var transform = new NiagaraSimulationCache.Transform(Vec3.ZERO, 0, 0, 0, 1, new Vec3(1, 1, 1), Vec3.ZERO);
        var system = new NiagaraSimulationCache.Buffer(1, 0, new int[0], new int[0], new short[0], new int[0], new int[0]);
        var frame = new NiagaraSimulationCache.Frame(2, 40, transform, null, system, List.of());
        var cache = new NiagaraSimulationCache("/Game/QA/NS_QaTrails", 1.9999992f, 0, false, false,
                layout, List.of(), List.of(frame));
        assertSame(frame, cache.sample(frame.age()).first());
        assertNull(cache.sample(2.0001f));
    }

    @Test
    void matrixFastPathPreservesUnrealRowOrderAndUsesTheDominantAxisAtHalfTurns() {
        float[] quarterTurn = {
                0, 1, 0, 0,
                -1, 0, 0, 0,
                0, 0, 1, 0,
                12, 34, 56, 1,
                0, 0, 0, 0
        };
        NiagaraFastPathMath.matrixToQuaternion(quarterTurn);
        assertEquals(0, quarterTurn[16]);
        assertEquals(0, quarterTurn[17]);
        assertEquals(0.70710677f, quarterTurn[18], 1e-6f);
        assertEquals(0.70710677f, quarterTurn[19], 1e-6f);

        float[] halfTurn = {
                -1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, -1, 0,
                0, 0, 0, 1,
                0, 0, 0, 0
        };
        NiagaraFastPathMath.matrixToQuaternion(halfTurn);
        assertEquals(0, halfTurn[16]);
        assertEquals(1, halfTurn[17]);
        assertEquals(0, halfTurn[18]);
        assertEquals(0, halfTurn[19]);
    }

    @Test
    void persistentParticleIdsClearDeadRowsAndRebuildFreeListFromCache() {
        var ids = new PersistentParticleIds();
        ids.begin(3, 1);
        ids.beginAcquireBatch(3);
        int id0 = ids.acquire();
        int id1 = ids.acquire();
        int id2 = ids.acquire();
        ids.alive(id0, 0);
        ids.alive(id1, 1);
        ids.alive(id2, 2);
        ids.dead(id1);
        assertEquals(-1, ids.indexTable()[id1]);
        assertEquals(2, ids.indexTable()[id2]);

        // Reset + restore via SimCache readCache must rebuild free list so new spawns reuse freed IDs.
        ids.resetIdTable();
        assertEquals(-1, ids.indexTable()[id0]);
        assertEquals(-1, ids.indexTable()[id2]);

        int[] cached = new int[] { 0, -1, 1 };
        ids.readCache(4, cached);
        cached[0] = 999; // Mutating caller array must not alias internal table.
        assertEquals(0, ids.indexTable()[0]);
        assertEquals(-1, ids.indexTable()[1]);
        assertEquals(1, ids.indexTable()[2]);

        ids.begin(3, 5);
        ids.beginAcquireBatch(1);
        int reused = ids.acquire();
        ids.alive(reused, 2);
        assertEquals(1, reused);
        assertEquals(2, ids.indexTable()[1]);
    }

    @Test
    void rendererBindingsResolveCompiledUserParameterStoreAndCaseInsensitiveOverrides() {
        Key radiusKey = new Key("User.LightRadius", "/Script/Niagara.NiagaraFloat", 2, 0, Storage.NUMERIC);
        Key enabledKey = new Key("User.LightEnabled", "/Script/Niagara.NiagaraBool", 2, 0, Storage.NUMERIC);
        Key tintKey = new Key("User.TintColor", "/Script/CoreUObject.LinearColor", 2, 0, Storage.NUMERIC);
        var layout = new NiagaraParameterLayout(List.of(
                new Variable(radiusKey, Float.BYTES, false, 0, -1),
                new Variable(enabledKey, Integer.BYTES, false, 4, -1),
                new Variable(tintKey, 4 * Float.BYTES, false, 8, -1)), 24, 0, 0);
        byte[] bytes = new byte[24];
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.putFloat(0, 425.0f);
        buf.putInt(4, -1);
        buf.putFloat(8, 0.25f).putFloat(12, 0.5f).putFloat(16, 0.75f).putFloat(20, 1.0f);

        try (var store = new NiagaraParameterStore(layout, false)) {
            store.setNumeric(radiusKey, bytes, 0, Float.BYTES);
            store.setNumeric(enabledKey, bytes, 4, Integer.BYTES);
            store.setNumeric(tintKey, bytes, 8, 4 * Float.BYTES);

            var emptySystem = new VectorDataSet(0, 0, 0);
            var handle = new EffectHandle(Vec3.ZERO);
            var inputs = new NiagaraRendererFields.Inputs(emptySystem, Map.of(), handle.input(), store);

            float[] outRadius = { 0f };
            assertTrue(NiagaraRendererParameters.floating("user.lightradius", emptySystem, Map.of(), handle.input(), store, outRadius));
            assertEquals(425.0f, outRadius[0], 1e-6f);

            var enabledField = new NiagaraRendererFields.IntegerField(List.of(), "USER.LIGHTENABLED", true, true);
            assertEquals(-1, enabledField.defaultValue(0, inputs));

            float[] outColor = new float[4];
            assertTrue(NiagaraRendererParameters.floating("user.tintcolor", emptySystem, Map.of(), handle.input(),
                    store, outColor, RendererParameterDefaults.EMPTY));
            assertEquals(0.25f, outColor[0], 1e-6f);
            assertEquals(0.75f, outColor[2], 1e-6f);
        }

        // EffectHandle renderer parameter overrides normalize FName case-insensitively.
        var materials = EffectJson.read(JsonParser.parseString("""
                {
                  "format_version": 1,
                  "materials": { "mat_a": {}, "mat_b": {} },
                  "emitters": [
                    {
                      "name": "dummy",
                      "renderer": "sprite",
                      "material": "mat_a",
                      "duration": 1,
                      "loop": false,
                      "max_particles": 1,
                      "rate": 0,
                      "bursts": [ { "time": 0, "min": 1, "max": 1 } ],
                      "lifetime": 1,
                      "width": 1,
                      "height": 1
                    }
                  ]
                }
                """).getAsJsonObject()).materials();
        MaterialDefinition matA = materials.get("mat_a");
        MaterialDefinition matB = materials.get("mat_b");

        var handle = new EffectHandle(Vec3.ZERO);
        var withDefaults = handle.input().withRendererDefaults(Map.of(
                "User.Material", new RendererParameter.MaterialVariants(Map.of("DefaultVariant", new RendererParameter.Material(matA)))));
        assertNotNull(RendererParameter.named(withDefaults.rendererParameters(), "user.material"));

        handle.setRendererParameter("user.material",
                new RendererParameter.MaterialVariants(Map.of("Alt", new RendererParameter.Material(matB))));
        var merged = handle.input().withRendererDefaults(Map.of(
                "User.Material", new RendererParameter.MaterialVariants(Map.of("DefaultVariant", new RendererParameter.Material(matA)))));
        var selected = RendererParameter.named(merged.rendererParameters(), "USER.MATERIAL");
        assertTrue(selected instanceof RendererParameter.MaterialVariants);
        assertSame(matB, RendererParameter.material(selected, "alt"));
        assertEquals(1, merged.rendererParameters().size());

        handle.removeRendererParameter("USER.MATERIAL");
        assertNull(RendererParameter.named(handle.input().rendererParameters(), "User.Material"));
    }

    @Test
    void integerRendererParameterAndGameplayBurstChannelResolveStoreAndAlignedExtents() {
        Key meshKey = new Key("User.MeshIndex", "/Script/Niagara.NiagaraInt32", 2, 0, Storage.NUMERIC);
        var layout = new NiagaraParameterLayout(List.of(
                new Variable(meshKey, Integer.BYTES, false, 0, -1)), Integer.BYTES, 0, 0);
        byte[] bytes = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(7).array();
        try (var store = new NiagaraParameterStore(layout, false)) {
            store.setNumeric(meshKey, bytes, 0, Integer.BYTES);
            var emptySystem = new VectorDataSet(0, 0, 0);
            var handle = new EffectHandle(Vec3.ZERO);
            assertEquals(Integer.valueOf(7), NiagaraRendererParameters.integer(
                    "user.meshindex", false, emptySystem, Map.of(), handle.input(), store));
        }

        var dcJson = JsonParser.parseString("""
                {
                  "writer": true,
                  "read_current": true,
                  "refresh": true,
                  "only_final_subtick": true,
                  "publish_game": false,
                  "publish_cpu": true,
                  "publish_gpu": false,
                  "spawn_groups": false,
                  "spawn_once": true,
                  "gpu_spawning_reader": false,
                  "allocation_count": 0,
                  "channel": {
                    "path": "/Game/NDC_Burst",
                    "fields": [
                      { "name": "Intensity", "type": "/Script/Niagara.NiagaraFloat", "registers": ["FLOAT"] }
                    ],
                    "keep_previous": true,
                    "enforce_tick_order": false,
                    "final_write_group": "TG_EndPhysics",
                    "gameplay_burst": {
                      "cell_size": [2500.0, 2500.0, 2500.0],
                      "bounds_padding": [250.0, 250.0, 250.0],
                      "systems": [],
                      "attachment": {
                        "speed_threshold": 5.0,
                        "gameplay_tags": ["Attach.Fast"],
                        "component_types": []
                      }
                    }
                  }
                }
                """).getAsJsonObject();
        var dc = com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition.read(dcJson);
        assertNotNull(dc.channel());
        assertNotNull(dc.channel().gameplayBurst());
        assertEquals(2500.0, dc.channel().gameplayBurst().cellSize().x(), 1e-6);
        assertEquals(250.0, dc.channel().gameplayBurst().boundsPadding().x(), 1e-6);

        // Runtime #9: same location with different GameplayTags, cellSizeOverride, or speed attachment routes to separate buckets.
        var vis = new com.kltyton.lumenfx.api.client.NiagaraDataChannels.Visibility(true, true, false);
        var fireCtx = com.kltyton.lumenfx.api.client.NiagaraDataChannels.AccessContext.withTag(new Vec3(1.0, 2.0, 3.0), "fx.fire");
        var iceCtx = com.kltyton.lumenfx.api.client.NiagaraDataChannels.AccessContext.withTag(new Vec3(1.0, 2.0, 3.0), "fx.ice");
        var rowFire = new com.kltyton.lumenfx.api.client.NiagaraDataChannels.Row(Map.of("Intensity", List.of(1.5)));
        var rowIce = new com.kltyton.lumenfx.api.client.NiagaraDataChannels.Row(Map.of("Intensity", List.of(9.0)));
        com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelWorlds.publishGame(
                null, dc.channel(), fireCtx, List.of(rowFire), vis);
        com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelWorlds.publishGame(
                null, dc.channel(), iceCtx, List.of(rowIce), vis);
        // Fast owner exceeds SpeedThreshold=5.0 -> routed to dedicated attached owner bucket separate from world grid!
        var attachedCtx = com.kltyton.lumenfx.api.client.NiagaraDataChannels.AccessContext.attached(
                "owner_entity_42", new Vec3(1.0, 2.0, 3.0), null, false, 8.0, List.of(), null, "fx.fire", null);
        var rowAttached = new com.kltyton.lumenfx.api.client.NiagaraDataChannels.Row(Map.of("Intensity", List.of(42.0)));
        com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelWorlds.publishGame(
                null, dc.channel(), attachedCtx, List.of(rowAttached), vis);
        com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelWorlds.flush(null);

        var readFire = com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelWorlds.readGame(
                null, dc.channel(), fireCtx, false);
        var readIce = com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelWorlds.readGame(
                null, dc.channel(), iceCtx, false);
        var readAttached = com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelWorlds.readGame(
                null, dc.channel(), attachedCtx, false);
        assertEquals(1, readFire.size());
        assertEquals(1.5, readFire.get(0).fields().get("intensity").get(0).doubleValue(), 1e-5);
        assertEquals(1, readIce.size());
        assertEquals(9.0, readIce.get(0).fields().get("intensity").get(0).doubleValue(), 1e-5);
        assertEquals(1, readAttached.size());
        assertEquals(42.0, readAttached.get(0).fields().get("intensity").get(0).doubleValue(), 1e-5);
        com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelWorlds.clear();

        // #11: NiagaraSplineLut single-point / empty guards and NiagaraSpline singular transform
        var emptyCurves = com.kltyton.lumenfx.effect.curve.NiagaraSplineCurves.EMPTY;
        assertEquals(0.0f, emptyCurves.nearestPositionTime(Vec3.ZERO), 1e-6f);
        var singular = new org.joml.Matrix4d().scale(0.0, 0.0, 0.0);
        assertTrue(Double.isFinite(com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices
                .inverse(singular, new org.joml.Matrix4d()).m00()),
                "Singular spline matrix inverse must produce finite fallback without NaN/Infinity");
    }

    @Test
    void niagaraDynamicMaterialParametersEncodeDoubleVectorToonProfileAndLifecycleOperations() {
        var doubleSlot = new com.kltyton.lumenfx.effect.render.material.MaterialInstanceParameters.Slot(
                "WorldAnchor",
                com.kltyton.lumenfx.effect.render.material.MaterialInstanceParameters.Kind.DOUBLE_VECTOR,
                null,
                com.kltyton.lumenfx.effect.render.material.MaterialInstanceParameters.Source.CONSTANT,
                null,
                null,
                new float[] { 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f });
        var scalarSlot = new com.kltyton.lumenfx.effect.render.material.MaterialInstanceParameters.Slot(
                "Intensity",
                com.kltyton.lumenfx.effect.render.material.MaterialInstanceParameters.Kind.SCALAR,
                null,
                com.kltyton.lumenfx.effect.render.material.MaterialInstanceParameters.Source.CONSTANT,
                null,
                null,
                new float[] { 1.0f, 0f, 0f, 0f });
        var toonSlot = new com.kltyton.lumenfx.effect.render.material.MaterialInstanceParameters.Slot(
                "ToonAsset",
                com.kltyton.lumenfx.effect.render.material.MaterialInstanceParameters.Kind.TOON_PROFILE,
                null,
                com.kltyton.lumenfx.effect.render.material.MaterialInstanceParameters.Source.CONSTANT,
                null,
                null,
                new float[] { 0f, 0f, 0f, 0f },
                com.kltyton.lumenfx.effect.render.material.MaterialInstanceParameters.Association.GLOBAL,
                -1,
                "/Game/Toon/DefaultToon.DefaultToon",
                false);

        var definitionParams = com.kltyton.lumenfx.effect.render.material.MaterialInstanceParameters.definition(
                List.of(doubleSlot, scalarSlot, toonSlot));
        var midA = new com.kltyton.lumenfx.api.client.parameter.DynamicMaterialValues();
        var midB = new com.kltyton.lumenfx.api.client.parameter.DynamicMaterialValues();
        var midOut = new com.kltyton.lumenfx.api.client.parameter.DynamicMaterialValues();

        // Large double coordinate requiring high+low float split to preserve sub-millimeter precision.
        double exactX = 16777216.25;
        double exactY = -33554432.5;
        midA.setDoubleVector("WorldAnchor",
                new com.kltyton.lumenfx.api.client.parameter.DynamicMaterialValues.DoubleVector(exactX, exactY, 1.5, 0.0));
        midA.setScalar("Intensity", 2.0f);
        midA.setToonProfile("ToonAsset", "/Game/Toon/StylizedAlt:StylizedAlt");
        midB.setScalar("Intensity", 6.0f);

        midOut.copyParameterOverrides(midA);
        midOut.interpolateParameters(midA, midB, 0.25f);

        var instanceParams = definitionParams.instantiate(null, midOut);
        ByteBuffer uniformBuf = ByteBuffer.allocate(instanceParams.byteSize()).order(ByteOrder.LITTLE_ENDIAN);
        instanceParams.write(instanceParams.publication(), midOut.snapshot(), uniformBuf,
                path -> "/Game/Toon/StylizedAlt.StylizedAlt".equals(path) ? 7 : 1);
        uniformBuf.flip();

        float highX = uniformBuf.getFloat();
        float highY = uniformBuf.getFloat();
        float highZ = uniformBuf.getFloat();
        float highW = uniformBuf.getFloat();
        float lowX = uniformBuf.getFloat();
        float lowY = uniformBuf.getFloat();
        float lowZ = uniformBuf.getFloat();
        float lowW = uniformBuf.getFloat();
        assertEquals(exactX, (double) highX + (double) lowX, 1e-6);
        assertEquals(exactY, (double) highY + (double) lowY, 1e-6);
        assertEquals(1.5, (double) highZ + (double) lowZ, 1e-6);
        assertEquals(0.0, (double) highW + (double) lowW, 1e-6);

        float interpolatedScalar = uniformBuf.getFloat();
        uniformBuf.getFloat();
        uniformBuf.getFloat();
        uniformBuf.getFloat();
        assertEquals(3.0f, interpolatedScalar, 1e-5f);

        float resolvedToonIndex = uniformBuf.getFloat();
        assertEquals(7.0f, resolvedToonIndex, 1e-5f);

        // Clearing numeric overrides and writing with null edits must restore default scalar & default Toon profile cleanly.
        midOut.clearParameterValues(true, false);
        ByteBuffer resetBuf = ByteBuffer.allocate(instanceParams.byteSize()).order(ByteOrder.LITTLE_ENDIAN);
        instanceParams.write(instanceParams.publication(), null, resetBuf,
                path -> "/Game/Toon/DefaultToon.DefaultToon".equals(path) ? 3 : 99);
        resetBuf.flip();
        resetBuf.position(8 * Float.BYTES);
        assertEquals(1.0f, resetBuf.getFloat(), 1e-5f);
        resetBuf.position(12 * Float.BYTES);
        assertEquals(3.0f, resetBuf.getFloat(), 1e-5f);
    }

    @Test
    void qualifiedParticlesRendererBindingsResolveBareDatasetAttributesForGpuAndCpuRenderers() {
        // Real UE5 compiled Niagara assets (e.g. NS_Fountain) store bare names in emitter.attributes
        // ("Position", "SpriteSize", "Previous.Position", ...) while renderer.attributeBindings uses
        // "Particles.Position", "Particles.SpriteSize", etc.
        var attributes = List.of(
                new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.Attribute("Age", 0, 0, 1, "/Script/Niagara.NiagaraFloat"),
                new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.Attribute("Color", 0, 1, 4, "/Script/CoreUObject.LinearColor"),
                new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.Attribute("Lifetime", 0, 9, 1, "/Script/Niagara.NiagaraFloat"),
                new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.Attribute("Mass", 0, 10, 1, "/Script/Niagara.NiagaraFloat"),
                new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.Attribute("MaterialRandom", 0, 11, 1, "/Script/Niagara.NiagaraFloat"),
                new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.Attribute("NormalizedAge", 0, 12, 1, "/Script/Niagara.NiagaraFloat"),
                new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.Attribute("Position", 0, 13, 3, "/Script/Niagara.NiagaraPosition"),
                new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.Attribute("Previous.Position", 0, 16, 3, "/Script/Niagara.NiagaraPosition"),
                new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.Attribute("Previous.SpriteRotation", 0, 19, 1, "/Script/Niagara.NiagaraFloat"),
                new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.Attribute("Previous.SpriteSize", 0, 20, 2, "/Script/CoreUObject.Vector2f"),
                new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.Attribute("Previous.Velocity", 0, 22, 3, "/Script/CoreUObject.Vector3f"),
                new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.Attribute("SpriteRotation", 0, 25, 1, "/Script/Niagara.NiagaraFloat"),
                new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.Attribute("SpriteSize", 0, 26, 2, "/Script/CoreUObject.Vector2f"),
                new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.Attribute("Velocity", 0, 28, 3, "/Script/CoreUObject.Vector3f"),
                new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.Attribute("Scale", 0, 31, 3, "/Script/CoreUObject.Vector3f"),
                new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.Attribute("MeshOrientation", 0, 34, 4, "/Script/CoreUObject.Quat4f"),
                new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.Attribute("SubImageIndex", 2, 0, 1, "/Script/Niagara.NiagaraHalf"),
                new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.Attribute("RibbonWidth", 0, 38, 1, "/Script/Niagara.NiagaraFloat"));

        Map<String, com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.RendererAttributeBinding> bindings = Map.ofEntries(
                Map.entry("Position", new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.RendererAttributeBinding("Particles.Position", true)),
                Map.entry("SpriteSize", new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.RendererAttributeBinding("Particles.SpriteSize", true)),
                Map.entry("SpriteRotation", new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.RendererAttributeBinding("Particles.SpriteRotation", true)),
                Map.entry("Color", new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.RendererAttributeBinding("Particles.Color", true)),
                Map.entry("NormalizedAge", new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.RendererAttributeBinding("particles.normalizedage", true)),
                Map.entry("MaterialRandom", new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.RendererAttributeBinding("Particles.MaterialRandom", true)),
                Map.entry("Velocity", new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.RendererAttributeBinding("Particles.Velocity", true)),
                Map.entry("PreviousPosition", new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.RendererAttributeBinding("Particles.Previous.Position", true)),
                Map.entry("PreviousSpriteSize", new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.RendererAttributeBinding("Particles.Previous.SpriteSize", true)),
                Map.entry("PreviousSpriteRotation", new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.RendererAttributeBinding("Particles.Previous.SpriteRotation", true)),
                Map.entry("PreviousVelocity", new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.RendererAttributeBinding("Particles.Previous.Velocity", true)),
                Map.entry("Scale", new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.RendererAttributeBinding("Particles.Scale", true)),
                Map.entry("MeshOrientation", new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.RendererAttributeBinding("Particles.MeshOrientation", true)),
                Map.entry("RibbonWidth", new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.RendererAttributeBinding("Particles.RibbonWidth", true)),
                Map.entry("CameraOffset", new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.RendererAttributeBinding("User.HostOffset", false)));

        var renderer = new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.Renderer(
                "fountain", com.kltyton.lumenfx.effect.render.SpriteCoverage.DISABLED,
                1f, 1f, false, "Particles.SubImageIndex", true, 0f,
                "Particles.UVScale", true, "Particles.DynamicMaterialParameter", true, 15,
                null, null, false, true, "Particles.NormalizedAge", true, "Default",
                false, 0f, 1000f, 0, "Particles.VisibilityTag", true, null, "Particles",
                bindings, "Automatic", "Automatic", 0.5f, 0.5f, 0f, 0f);

        // GPU Sprite & Mesh uniform descriptors must resolve every bare attribute (never MISSING=-1).
        assertEquals(13, com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSpriteUniforms.descriptor(attributes, renderer, "Position", 3, false));
        assertEquals(26, com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSpriteUniforms.descriptor(attributes, renderer, "SpriteSize", 2, false));
        assertEquals(25, com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSpriteUniforms.descriptor(attributes, renderer, "SpriteRotation", 1, false));
        assertEquals(1, com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSpriteUniforms.descriptor(attributes, renderer, "Color", 4, false));
        assertEquals(12, com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSpriteUniforms.descriptor(attributes, renderer, "NormalizedAge", 1, false));
        assertEquals(11, com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSpriteUniforms.descriptor(attributes, renderer, "MaterialRandom", 1, false));
        assertEquals(28, com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSpriteUniforms.descriptor(attributes, renderer, "Velocity", 3, false));
        assertEquals(16, com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSpriteUniforms.descriptor(attributes, renderer, "PreviousPosition", 3, false));
        assertEquals(20, com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSpriteUniforms.descriptor(attributes, renderer, "PreviousSpriteSize", 2, false));
        assertEquals(19, com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSpriteUniforms.descriptor(attributes, renderer, "PreviousSpriteRotation", 1, false));
        assertEquals(22, com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSpriteUniforms.descriptor(attributes, renderer, "PreviousVelocity", 3, false));
        assertEquals(31, com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSpriteUniforms.descriptor(attributes, renderer, "Scale", 3, false));
        assertEquals(34, com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSpriteUniforms.descriptor(attributes, renderer, "MeshOrientation", 4, false));
        assertEquals(Integer.MIN_VALUE, com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSpriteUniforms.descriptor(attributes, "Particles.SubImageIndex", 1));
        assertEquals(-1, com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSpriteUniforms.descriptor(attributes, renderer, "CameraOffset", 1, false));
        assertEquals(-1, com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSpriteUniforms.descriptor(attributes, renderer, "Position", 3, true));

        // GPU Ribbon bindings must also resolve qualified/case-variant bindings against bare attributes.
        var emitter = new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.Emitter(
                "fountain", "Fountain", 0, false,
                new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.DisabledSimulation(false, true),
                attributes, List.of(), com.kltyton.lumenfx.effect.render.SpriteCoverage.DISABLED,
                1f, 1f, false, null, true, 0f, List.of(renderer), false, 1024);
        var ribbon = com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuRibbonBindings.resolve(emitter, renderer);
        assertEquals(13, ribbon.position());
        assertEquals(1, ribbon.color());
        assertEquals(38, ribbon.width());
        assertEquals(12, ribbon.normalizedAge());

        // CPU NiagaraParticles must also read actual dataset rows rather than falling back to HostPosition / HostSize=50.
        var cpuParticles = new NiagaraParticles(emitter, renderer, false, false, true);
        var dataset = new VectorDataSet(39, 0, 1);
        dataset.count(1);
        dataset.floating(13, 0, 125.0f); // X = 1.25m
        dataset.floating(14, 0, 250.0f); // UE Y -> MC Z = 2.50m
        dataset.floating(15, 0, 375.0f); // UE Z -> MC Y = 3.75m
        dataset.floating(26, 0, 8.0f);   // SpriteSize.x = 8cm = 0.08m (NOT 50cm fallback)
        dataset.floating(27, 0, 10.0f);  // SpriteSize.y = 10cm = 0.10m
        dataset.floating(1, 0, 0.25f);
        dataset.floating(2, 0, 0.50f);
        dataset.floating(3, 0, 0.75f);
        dataset.floating(4, 0, 0.90f);
        dataset.floating(25, 0, 90.0f);
        dataset.floating(31, 0, 2.0f);
        dataset.floating(32, 0, 3.0f);
        dataset.floating(33, 0, 4.0f);
        cpuParticles.bind(dataset, 0.0, 0.0, 0.0, new VectorDataSet(0, 0, 0), Map.of(), new EffectHandle(Vec3.ZERO).input());

        assertEquals(1.25, cpuParticles.x(0), 1e-5);
        assertEquals(3.75, cpuParticles.y(0), 1e-5);
        assertEquals(2.50, cpuParticles.z(0), 1e-5);
        assertEquals(0.08f, cpuParticles.width(0), 1e-5f);
        assertEquals(0.10f, cpuParticles.height(0), 1e-5f);
        assertEquals(0.25f, cpuParticles.color(0, 0), 1e-5f);
        assertEquals(0.90f, cpuParticles.color(0, 3), 1e-5f);
        assertEquals((float) (Math.PI * 0.5), cpuParticles.rotation(0), 1e-5f);
        assertEquals(2.0f, cpuParticles.meshScale(0, 0), 1e-5f);
        assertEquals(4.0f, cpuParticles.meshScale(0, 2), 1e-5f);
    }

    @Test
    void cpuSocketReaderInterfaceValidationAcceptsValidSignaturesAndRejectsInvalidOnes() {
        var emptyProg = """
                {
                  "temporary_registers": 1,
                  "output_registers": 1,
                  "max_output_datasets": 1,
                  "max_external_registers": 16,
                  "dummy_registers": 0,
                  "runtime_bytes": 32,
                  "interpolated": false,
                  "parameter_bytes": 0,
                  "parameters": [],
                  "constant_data": "",
                  "constant_slots": [],
                  "inputs": [],
                  "outputs": [],
                  "external": [],
                  "instructions": []
                }
                """;
        var keyObj = """
                { "name": "User.Sock", "source_type": "/Script/Niagara.NiagaraDataInterfaceSocketReader", "underlying": 1, "flags": 0, "storage": "interface" }""";
        var validJson = JsonParser.parseString("""
                {
                  "system_spawn": %s,
                  "system_update": %s,
                  "system_layout": [
                    { "name": "System.ExecutionState", "float": -1, "float_count": 0, "int": 0, "int_count": 1, "half": -1, "half_count": 0, "source_type": "/Script/Niagara.ENiagaraExecutionState" },
                    { "name": "CpuSocket.ExecutionState", "float": -1, "float_count": 0, "int": 1, "int_count": 1, "half": -1, "half_count": 0, "source_type": "/Script/Niagara.ENiagaraExecutionState" }
                  ],
                  "spawn_input_layout": [],
                  "update_input_layout": [],
                  "spawn_input_bindings": [],
                  "update_input_bindings": [],
                  "system_random_seed": 0,
                  "system_deterministic": false,
                  "emitters": [{
                    "name": "CpuSocket",
                    "source_name": "CpuSocket",
                    "random_seed": 0,
                    "deterministic": false,
                    "local_space": false,
                    "pixel_coverage": { "channels": 0, "blend": 1.0 },
                    "spawn_info": [],
                    "spawn": {
                      "temporary_registers": 1,
                      "output_registers": 1,
                      "max_output_datasets": 1,
                      "max_external_registers": 16,
                      "dummy_registers": 0,
                      "runtime_bytes": 32,
                      "interpolated": false,
                      "parameter_bytes": 0,
                      "parameters": [],
                      "constant_data": "",
                      "constant_slots": [],
                      "inputs": [],
                      "outputs": [],
                      "external": [
                        { "interface_type": "socket_reader", "interface_key": %s, "interface_id": "sock0", "name": "IsValid", "inputs": 0, "outputs": 1 },
                        { "interface_type": "socket_reader", "interface_key": %s, "interface_id": "sock0", "name": "GetSocketCount", "inputs": 0, "outputs": 1 },
                        { "interface_type": "socket_reader", "interface_key": %s, "interface_id": "sock0", "name": "GetComponentToWorld", "inputs": 0, "outputs": 10 },
                        { "interface_type": "socket_reader", "interface_key": %s, "interface_id": "sock0", "name": "GetSocketTransform", "inputs": 1, "outputs": 13 },
                        { "interface_type": "socket_reader", "interface_key": %s, "interface_id": "sock0", "name": "GetSocketTransformInterpolated", "inputs": 2, "outputs": 13 }
                      ],
                      "instructions": []
                    },
                    "update": %s,
                    "attributes": [],
                    "renderers": []
                  }]
                }
                """.formatted(emptyProg, emptyProg, keyObj, keyObj, keyObj, keyObj, keyObj, emptyProg)).getAsJsonObject();
        var def = com.kltyton.lumenfx.client.resource.niagara.NiagaraJson.read(validJson, id -> {
            throw new AssertionError("Unexpected vector field lookup " + id);
        });
        assertEquals(1, def.emitters().size());

        var invalidJson = JsonParser.parseString(validJson.toString().replace("\"outputs\":13", "\"outputs\":99")).getAsJsonObject();
        org.junit.jupiter.api.Assertions.assertThrows(com.google.gson.JsonParseException.class,
                () -> com.kltyton.lumenfx.client.resource.niagara.NiagaraJson.read(invalidJson, id -> {
                    throw new AssertionError("Unexpected vector field lookup " + id);
                }));
    }

    @Test
    void simCachePlaybackTruncatesPrefixInterpolatesNextRowsAndInvalidatesTruncatedIds() {
        var posField = new NiagaraSimulationCache.Field("position", "/script/niagara.niagaraposition",
                new NiagaraSimulationCache.Range(0, 3), new NiagaraSimulationCache.Range(-1, 0), new NiagaraSimulationCache.Range(-1, 0), false, true);
        var tagField = new NiagaraSimulationCache.Field("qainttag", "/script/niagara.niagaraint32",
                new NiagaraSimulationCache.Range(-1, 0), new NiagaraSimulationCache.Range(0, 1), new NiagaraSimulationCache.Range(-1, 0), false, false);
        var halfField = new NiagaraSimulationCache.Field("qahalf", "/script/niagara.niagarahalf",
                new NiagaraSimulationCache.Range(-1, 0), new NiagaraSimulationCache.Range(-1, 0), new NiagaraSimulationCache.Range(0, 1), false, false);
        var sysLayout = new NiagaraSimulationCache.Layout("system", true, false, 0, 0, 0, -1, List.of());
        var emLayout = new NiagaraSimulationCache.Layout("gpu", true, false, 3, 1, 1, -1, List.of(posField, tagField, halfField));
        var attrs = List.of(
                new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.Attribute("Position", 0, 0, 3, "/Script/Niagara.NiagaraPosition"),
                new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.Attribute("QaIntTag", 1, 0, 1, "/Script/Niagara.NiagaraInt32"),
                new com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.Attribute("QaHalf", 2, 0, 1, "/Script/Niagara.NiagaraHalf"));
        var playback = com.kltyton.lumenfx.client.simulation.niagara.cache.NiagaraCachePlayback.emitter(emLayout, attrs);
        var transform = new NiagaraSimulationCache.Transform(Vec3.ZERO, 0, 0, 0, 1, new Vec3(1, 1, 1), Vec3.ZERO);
        var pose = new EffectHandle.Pose(Vec3.ZERO, 0, 0, 0, 1, 1, 1, 1);

        // Frame 1: 4 particles (rows 0..3), non-contiguous ID table [id0->row1, id1->-1(gap), id2->row3(truncated), id3->row0]
        // nextRows maps row0->row1 and row1->row0 in Frame 2 (non-identity!)
        int[] f1Floats = {
                Float.floatToRawIntBits(10f), Float.floatToRawIntBits(20f), Float.floatToRawIntBits(30f), Float.floatToRawIntBits(40f),
                Float.floatToRawIntBits(0f),  Float.floatToRawIntBits(0f),  Float.floatToRawIntBits(0f),  Float.floatToRawIntBits(0f),
                Float.floatToRawIntBits(0f),  Float.floatToRawIntBits(0f),  Float.floatToRawIntBits(0f),  Float.floatToRawIntBits(0f)
        };
        int[] f1Ints = { 777, 777, 777, 777 };
        short[] f1Halves = { Float.floatToFloat16(1.25f), Float.floatToFloat16(1.25f), Float.floatToFloat16(1.25f), Float.floatToFloat16(1.25f) };
        int[] f1Ids = { 1, -1, 3, 0 };
        int[] f1NextRows = { 1, 0, -1, -1 };
        var b1 = new NiagaraSimulationCache.Buffer(4, 500000, f1Floats, f1Ints, f1Halves, f1Ids, f1NextRows);

        // Frame 2: 2 surviving particles (row0 X=120, row1 X=110 -> so f1.row0 (10)->f2.row1 (110) mid=60; f1.row1 (20)->f2.row0 (120) mid=70)
        int[] f2Floats = {
                Float.floatToRawIntBits(120f), Float.floatToRawIntBits(110f),
                Float.floatToRawIntBits(0f),   Float.floatToRawIntBits(0f),
                Float.floatToRawIntBits(0f),   Float.floatToRawIntBits(0f)
        };
        var b2 = new NiagaraSimulationCache.Buffer(2, 500001, f2Floats, new int[]{ 777, 777 },
                new short[]{ Float.floatToFloat16(1.25f), Float.floatToFloat16(1.25f) }, new int[]{ 0, -1, -1, 1 }, new int[0]);

        var out = new VectorDataSet(3, 1, 1);
        playback.copy(b1, b2, 0.5f, 0.5f, 0.05f, false, true, transform, transform, pose, Vec3.ZERO, out, 2);
        assertEquals(2, out.count());
        assertEquals(60.0f, out.floating(0, 0), 1e-4f);
        assertEquals(70.0f, out.floating(0, 1), 1e-4f);
        assertEquals(777, out.integer(0, 1));
        assertEquals(1.25f, out.half(0, 1), 1e-3f);

        // Verify truncated ID invalidation via NiagaraCachePlayer.particleIndex (public entry)
        var sysBuf = new NiagaraSimulationCache.Buffer(1, 0, new int[0], new int[0], new short[0], new int[0], new int[0]);
        var fr1 = new NiagaraSimulationCache.Frame(0.1f, 1, transform, null, sysBuf, List.of(new NiagaraSimulationCache.EmitterFrame(null, 999, b1)));
        var fr2 = new NiagaraSimulationCache.Frame(0.6f, 2, transform, null, sysBuf, List.of(new NiagaraSimulationCache.EmitterFrame(null, 999, b2)));
        var cache = new NiagaraSimulationCache("/Game/QA/NS_TruncTest", 0.1f, 0.5f, true, false, sysLayout, List.of(emLayout), List.of(fr1, fr2));
        var emptyProg = """
                { "temporary_registers": 1, "output_registers": 1, "max_output_datasets": 1, "max_external_registers": 1, "dummy_registers": 0, "runtime_bytes": 32, "interpolated": false, "parameter_bytes": 0, "parameters": [], "constant_data": "", "constant_slots": [], "inputs": [], "outputs": [], "external": [], "instructions": [] }
                """;
        var defJson = JsonParser.parseString("""
                {
                  "source_system": "/Game/QA/NS_TruncTest",
                  "system_spawn": %s,
                  "system_update": %s,
                  "system_layout": [
                    { "name": "System.ExecutionState", "float": -1, "float_count": 0, "int": 0, "int_count": 1, "half": -1, "half_count": 0, "source_type": "/Script/Niagara.ENiagaraExecutionState" },
                    { "name": "gpu.ExecutionState", "float": -1, "float_count": 0, "int": 1, "int_count": 1, "half": -1, "half_count": 0, "source_type": "/Script/Niagara.ENiagaraExecutionState" }
                  ],
                  "spawn_input_layout": [],
                  "update_input_layout": [],
                  "spawn_input_bindings": [],
                  "update_input_bindings": [],
                  "system_random_seed": 0,
                  "system_deterministic": false,
                  "emitters": [{
                    "name": "gpu",
                    "source_name": "gpu",
                    "random_seed": 0,
                    "deterministic": false,
                    "local_space": true,
                    "pixel_coverage": { "channels": 0, "blend": 1.0 },
                    "spawn_info": [],
                    "spawn": %s,
                    "update": %s,
                    "attributes": [
                      { "name": "Position", "type": 0, "component": 0, "count": 3, "source_type": "/Script/Niagara.NiagaraPosition" },
                      { "name": "QaIntTag", "type": 1, "component": 0, "count": 1, "source_type": "/Script/Niagara.NiagaraInt32" },
                      { "name": "QaHalf", "type": 2, "component": 0, "count": 1, "source_type": "/Script/Niagara.NiagaraHalf" }
                    ],
                    "renderers": []
                  }]
                }
                """.formatted(emptyProg, emptyProg, emptyProg, emptyProg)).getAsJsonObject();
        var def = com.kltyton.lumenfx.client.resource.niagara.NiagaraJson.read(defJson, id -> {
            throw new AssertionError("Unexpected vector field lookup " + id);
        });
        var player = new com.kltyton.lumenfx.client.simulation.niagara.cache.NiagaraCachePlayer(cache, def);
        int[] remapped = player.particleIndex(0, b1, out);
        assertEquals(1, remapped[0]);  // retained row 1 (< 2) stays accessible
        assertEquals(-1, remapped[1]); // dead gap stays -1
        assertEquals(-1, remapped[2]); // truncated row 3 (>= 2) invalidated to -1
        assertEquals(0, remapped[3]);  // retained row 0 (< 2) stays accessible
    }

    @Test
    void frameExecutorWorkStealingOverlapsOwnerWorkAndPreservesSemantics() {
        var executor = com.kltyton.lumenfx.client.concurrent.VfxFrameExecutor.INSTANCE;
        int partitions = Math.min(executor.maximumWorkers(), 4);
        int itemCount = 128;
        var visited = new java.util.concurrent.atomic.AtomicIntegerArray(itemCount);
        var callerThread = Thread.currentThread();
        var ownerRanOnCaller = new java.util.concurrent.atomic.AtomicBoolean(false);
        var callerParticipatedInStealing = new java.util.concurrent.atomic.AtomicBoolean(false);
        executor.executeIndexed(itemCount, partitions, () -> {
            ownerRanOnCaller.set(Thread.currentThread() == callerThread);
        }, (partition, index) -> {
            assertTrue(partition >= 0 && partition < partitions, "partition out of range: " + partition);
            if (partition == 0) {
                assertSame(callerThread, Thread.currentThread());
                callerParticipatedInStealing.set(true);
            }
            assertEquals(0, visited.getAndIncrement(index), "duplicate work item index " + index);
        });
        assertTrue(ownerRanOnCaller.get(), "ownerWork must execute on caller thread");
        for (int i = 0; i < itemCount; i++) {
            assertEquals(1, visited.get(i), "missing work item " + i);
        }
        if (partitions > 1) {
            assertTrue(callerParticipatedInStealing.get() || itemCount < partitions,
                    "caller thread should participate in stealing when items remain");
        }

        // Verify executeRanges with OwnerRequests services caller reads without deadlock.
        int rangeCount = 64;
        var rangeVisited = new java.util.concurrent.atomic.AtomicIntegerArray(rangeCount);
        var ownerReads = new java.util.concurrent.atomic.AtomicInteger(0);
        var requests = partitions > 1 ? new com.kltyton.lumenfx.client.concurrent.VfxFrameExecutor.OwnerRequests(partitions) : null;
        executor.executeRanges(0, rangeCount, partitions, requests, (partition, from, to) -> {
            for (int i = from; i < to; i++) {
                if (requests != null && i == from) {
                    int token = requests.read(() -> {
                        assertSame(callerThread, Thread.currentThread());
                        return ownerReads.incrementAndGet();
                    });
                    assertTrue(token > 0);
                }
                assertEquals(0, rangeVisited.getAndIncrement(i));
            }
        });
        for (int i = 0; i < rangeCount; i++) {
            assertEquals(1, rangeVisited.get(i));
        }
        if (requests != null) {
            assertEquals(partitions, ownerReads.get());
        }

        // Verify failure propagation from worker threads.
        var thrown = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                executor.executeIndexed(16, partitions, null, (partition, index) -> {
                    if (index == 11) throw new IllegalArgumentException("boom-11");
                }));
        assertEquals("boom-11", thrown.getCause().getMessage());
    }

    @Test
    void vectorMachineHoistedArithmeticMatchesExactFloatAndIntegerBitSemantics() {
        // Constants: slot 0 = 2.5f (reg 4), slot 1 = 0.0f (reg 5), slot 2 = 1.0f (reg 6), slot 3 = 10.0f (reg 7), slot 4 = -1 (true mask, reg 8)
        ByteBuffer constBuf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        constBuf.putFloat(2.5f).putFloat(0.0f).putFloat(1.0f).putFloat(10.0f).putInt(-1);
        byte[] constants = constBuf.array();
        // Registers layout:
        // temp: 0..3 (4 temps)
        // const: 4 (2.5f), 5 (0.0f), 6 (1.0f), 7 (10.0f), 8 (-1 mask)
        // inputs (dataset 0): 9 (float[0] = a), 10 (float[1] = b), 11 (int[0] = ia)
        // duplicate input bank: 12, 13, 14
        // outputs (dataset 0): 15 (float[0]), 16 (float[1]), 17 (float[2]), 18 (int[0])
        var op = com.kltyton.lumenfx.effect.niagara.VectorProgram.Opcode.class;
        var program = new com.kltyton.lumenfx.effect.niagara.VectorProgram(
                4, 4, 1, 0, 0, 32, false, 0, List.of(),
                constants, new int[] { 0, 1, 2, 3, 4 },
                List.of(
                        new com.kltyton.lumenfx.effect.niagara.VectorProgram.Input(0, 0, 0, false),
                        new com.kltyton.lumenfx.effect.niagara.VectorProgram.Input(0, 0, 1, false),
                        new com.kltyton.lumenfx.effect.niagara.VectorProgram.Input(0, 1, 0, false)
                ),
                List.of(
                        new com.kltyton.lumenfx.effect.niagara.VectorProgram.Output(0, 0, 0),
                        new com.kltyton.lumenfx.effect.niagara.VectorProgram.Output(0, 0, 1),
                        new com.kltyton.lumenfx.effect.niagara.VectorProgram.Output(0, 0, 2),
                        new com.kltyton.lumenfx.effect.niagara.VectorProgram.Output(0, 1, 0)
                ),
                List.of(),
                List.of(
                        new com.kltyton.lumenfx.effect.niagara.VectorProgram.Instruction(com.kltyton.lumenfx.effect.niagara.VectorProgram.Opcode.MAD, 3, 0, 0, new int[] { 9, 4, 10, 0 }),
                        new com.kltyton.lumenfx.effect.niagara.VectorProgram.Instruction(com.kltyton.lumenfx.effect.niagara.VectorProgram.Opcode.CLAMP, 3, 0, 0, new int[] { 0, 6, 7, 1 }),
                        new com.kltyton.lumenfx.effect.niagara.VectorProgram.Instruction(com.kltyton.lumenfx.effect.niagara.VectorProgram.Opcode.DIV, 2, 0, 0, new int[] { 9, 5, 2 }),
                        new com.kltyton.lumenfx.effect.niagara.VectorProgram.Instruction(com.kltyton.lumenfx.effect.niagara.VectorProgram.Opcode.CMPLT, 2, 0, 0, new int[] { 9, 10, 3 }),
                        new com.kltyton.lumenfx.effect.niagara.VectorProgram.Instruction(com.kltyton.lumenfx.effect.niagara.VectorProgram.Opcode.SELECT, 3, 0, 0, new int[] { 3, 1, 2, 0 }),
                        new com.kltyton.lumenfx.effect.niagara.VectorProgram.Instruction(com.kltyton.lumenfx.effect.niagara.VectorProgram.Opcode.ADDI, 2, 0, 0, new int[] { 11, 11, 3 }),
                        new com.kltyton.lumenfx.effect.niagara.VectorProgram.Instruction(com.kltyton.lumenfx.effect.niagara.VectorProgram.Opcode.ACQUIREINDEX, 1, 0, 0, new int[] { 8 }),
                        new com.kltyton.lumenfx.effect.niagara.VectorProgram.Instruction(com.kltyton.lumenfx.effect.niagara.VectorProgram.Opcode.OUTPUTDATA_FLOAT, 3, 0, 0, new int[] { 0, 1, 2, 15, 16, 17 }),
                        new com.kltyton.lumenfx.effect.niagara.VectorProgram.Instruction(com.kltyton.lumenfx.effect.niagara.VectorProgram.Opcode.OUTPUTDATA_INT32, 1, 0, 0, new int[] { 3, 18 })
                ));
        assertNotNull(op);
        var vm = new VectorMachine(program);
        int n = 17; // non-multiple of 4 to test chunk padding + exact count
        var inDs = new VectorDataSet(2, 1, 0);
        inDs.reserve(n + 4);
        inDs.count(n);
        for (int i = 0; i < n; i++) {
            inDs.floating(0, i, i - 3.0f);
            inDs.floating(1, i, (i % 5) * 1.5f);
            inDs.integer(0, i, i * 7 - 10);
        }
        var outDs = new VectorDataSet(3, 1, 0);
        vm.execute(n, new VectorDataSet[] { inDs }, new VectorDataSet[] { outDs }, 0, constants);
        assertEquals(n, outDs.count());
        for (int i = 0; i < n; i++) {
            float a = i - 3.0f;
            float b = (i % 5) * 1.5f;
            float mad = a * 2.5f + b;
            float clamped = Math.min(Math.max(mad, 1.0f), 10.0f);
            float divZero = 0.0f; // divide by 0.0f <= EPSILON yields 0.0f
            float selected = a < b ? clamped : divZero;
            assertEquals(Float.floatToRawIntBits(selected), Float.floatToRawIntBits(outDs.floating(0, i)), "row " + i);
            assertEquals(Float.floatToRawIntBits(clamped), Float.floatToRawIntBits(outDs.floating(1, i)), "row " + i);
            assertEquals(Float.floatToRawIntBits(divZero), Float.floatToRawIntBits(outDs.floating(2, i)), "row " + i);
            assertEquals((i * 7 - 10) * 2, outDs.integer(0, i), "row " + i);
        }
    }
}
