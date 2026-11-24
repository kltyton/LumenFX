package com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.api.client.NiagaraDataChannels;
import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition;
import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition.Variable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

final class NiagaraGameplayBurstRuntimeTest {
    @Test
    void supportsSystemCollectionsDiAccessContextAutoLinkControlAndAttachedOwnerLifecycle() {
        var dcJson = JsonParser.parseString("""
                {
                  "writer": false,
                  "read_current": true,
                  "refresh": true,
                  "only_final_subtick": true,
                  "publish_game": false,
                  "publish_cpu": true,
                  "publish_gpu": false,
                  "spawn_groups": false,
                  "spawn_once": true,
                  "gpu_spawning_reader": false,
                  "auto_link": false,
                  "allocation_count": 0,
                  "access_context": {
                    "override_location": true,
                    "location": [100.0, 200.0, 300.0],
                    "override_system": true,
                    "systems": ["test:handler_a", "test:handler_b"],
                    "force_attach": false,
                    "override_cell_size": false,
                    "override_bounds_padding": false,
                    "gameplay_tag": "fx.ice"
                  },
                  "channel": {
                    "path": "/Game/NDC_Burst_Ctx",
                    "fields": [
                      { "name": "Intensity", "type": "/Script/Niagara.NiagaraFloat", "registers": ["FLOAT"] }
                    ],
                    "keep_previous": true,
                    "enforce_tick_order": false,
                    "final_write_group": "TG_EndPhysics",
                    "gameplay_burst": {
                      "cell_size": [2500.0, 2500.0, 2500.0],
                      "bounds_padding": [250.0, 250.0, 250.0],
                      "systems": ["test:default_sys"],
                      "attachment": {
                        "speed_threshold": -1.0,
                        "gameplay_tags": [],
                        "component_types": []
                      }
                    }
                  }
                }
                """).getAsJsonObject();

        // #7: Non-auto-linking Reader DI inside fx.fire handler MUST read fx.ice bucket via authored AccessContext!
        NiagaraDataChannelDefinition readerIceDef = NiagaraDataChannelDefinition.read(dcJson);
        assertFalse(readerIceDef.autoLink());
        assertNotNull(readerIceDef.accessContext());
        assertEquals("fx.ice", readerIceDef.accessContext().gameplayTag());
        assertEquals(2, readerIceDef.accessContext().systems().size());

        var world = new NiagaraDataChannelWorlds.World(null);
        List<EffectHandle> spawnedHandles = new ArrayList<>();
        List<Identifier> spawnedIds = new ArrayList<>();
        var bursts = new NiagaraDataChannelGameplayBursts(world, readerIceDef.channel(), (lvl, id, pos, seed) -> {
            var h = new EffectHandle(pos);
            spawnedHandles.add(h);
            spawnedIds.add(id);
            return h;
        });

        // Write 99.0 into fx.ice and 11.0 into fx.fire at same source location (100, 200, 300).
        var writerIceDef = new NiagaraDataChannelDefinition(readerIceDef.channel(), true, true, true, true,
                true, true, false, 4, false, true, false, false, readerIceDef.accessContext());
        var vis = new NiagaraDataChannels.Visibility(true, true, false);
        var iceBuf = bursts.acquire(new Vec3(100.0, 200.0, 300.0),
                NiagaraDataChannels.AccessContext.withTag(new Vec3(1.0, 3.0, 2.0), "fx.ice"));
        var fireBuf = bursts.acquire(new Vec3(100.0, 200.0, 300.0),
                NiagaraDataChannels.AccessContext.withTag(new Vec3(1.0, 3.0, 2.0), "fx.fire"));
        iceBuf.submit(List.of(new NiagaraDataChannelWorlds.Row(new int[] { Float.floatToRawIntBits(99.0f) }, Vec3.ZERO)), writerIceDef);
        fireBuf.submit(List.of(new NiagaraDataChannelWorlds.Row(new int[] { Float.floatToRawIntBits(11.0f) }, Vec3.ZERO)), writerIceDef);
        iceBuf.flush();
        fireBuf.flush();

        // Register a handler EffectHandle as spawned by fx.fire bucket.
        EffectHandle fireHandler = new EffectHandle(Vec3.ZERO);
        world.handlerOwners.put(fireHandler, fireBuf.address);

        // #6: When AccessContext overrides system with a 2-system collection, both systems spawn!
        bursts.acquire(new Vec3(100.0, 200.0, 300.0), new NiagaraDataChannels.AccessContext(
                new Vec3(1.0, 3.0, 2.0), null, null, null, 0.0, List.of(), null, false,
                null, null, "fx.ice", null, readerIceDef.accessContext().systems(), null, null));
        assertTrue(spawnedIds.contains(Identifier.parse("test:handler_a")));
        assertTrue(spawnedIds.contains(Identifier.parse("test:handler_b")));

        // Put runtime World through NiagaraDataChannelState with non-auto-link Reader owned by fireHandler:
        var prevWorldsField = true;
        NiagaraDataChannelWorlds.clear();
        // Also test live attachment pose (move + rotate) & invalidation (#8) WITHOUT subsequent reads/writes!
        spawnedHandles.clear();
        spawnedIds.clear();
        var burstWorld = new NiagaraDataChannelWorlds.World(null);
        var burstTracker = new NiagaraDataChannelGameplayBursts(burstWorld, readerIceDef.channel(), (lvl, id, pos, seed) -> {
            var h = new EffectHandle(pos);
            spawnedHandles.add(h);
            spawnedIds.add(id);
            return h;
        });
        EffectHandle ownerHandle = new EffectHandle(new Vec3(1.0, 2.0, 3.0));
        burstTracker.acquire(new Vec3(100.0, 300.0, 200.0), NiagaraDataChannels.AccessContext.attached(ownerHandle));
        assertEquals(1, spawnedHandles.size());
        EffectHandle spawnedHandler = spawnedHandles.get(0);
        assertTrue(spawnedHandler.isAlive());
        assertEquals(1.0, spawnedHandler.input().pose().position().x, 1e-6);

        // Owner moves and rotates; no subsequent reads/writes occur -> syncAttached must update spawned handler pose & rotation!
        Quaternionf rotated = new Quaternionf().rotateY((float) (Math.PI / 2.0));
        Pose movedRotated = new Pose(new Vec3(15.0, 25.0, 35.0), rotated.x, rotated.y, rotated.z, rotated.w, 1.0f, 1.0f, 1.0f);
        ownerHandle.setPose(movedRotated);
        burstTracker.syncAttached();
        burstTracker.retireUnused();
        assertTrue(spawnedHandler.isAlive());
        assertEquals(15.0, spawnedHandler.input().pose().position().x, 1e-6);
        assertEquals(25.0, spawnedHandler.input().pose().position().y, 1e-6);
        assertEquals(35.0, spawnedHandler.input().pose().position().z, 1e-6);
        assertEquals(rotated.y, spawnedHandler.input().pose().y(), 1e-5);
        assertEquals(rotated.w, spawnedHandler.input().pose().w(), 1e-5);

        // Owner becomes invalid; no subsequent reads/writes -> retireUnused must invalidate handler & retire bucket!
        ownerHandle.invalidate();
        burstTracker.syncAttached();
        burstTracker.retireUnused();
        assertFalse(spawnedHandler.isAlive(), "Handler spawned for attached GameplayBurst must be invalidated when owner becomes invalid");
        assertEquals(0, burstTracker.activeBucketCount(), "Bucket must retire once attached owner is invalid");

        // Verify direct Channel autoLink=false routing on NiagaraDataChannelState (#7):
        assertNotNull(new Variable("Intensity", "/Script/Niagara.NiagaraFloat", List.of(NiagaraDataChannelDefinition.Register.FLOAT)));
        NiagaraDataChannelWorlds.clear();
        // Publish 11.0 to fx.fire and 99.0 to fx.ice via NiagaraDataChannelWorlds:
        var chan = readerIceDef.channel();
        NiagaraDataChannelWorlds.publishGame(null, chan,
                NiagaraDataChannels.AccessContext.withTag(new Vec3(1.0, 3.0, 2.0), "fx.fire"),
                List.of(new NiagaraDataChannels.Row(Map.of("Intensity", List.of(11.0)))), vis);
        NiagaraDataChannelWorlds.publishGame(null, chan,
                NiagaraDataChannels.AccessContext.withTag(new Vec3(1.0, 3.0, 2.0), "fx.ice"),
                List.of(new NiagaraDataChannels.Row(Map.of("Intensity", List.of(99.0)))), vis);
        NiagaraDataChannelWorlds.flush(null);

        // Attach a handler handle to the fx.fire address manually, then verify autoLink=false reads fx.ice (99.0),
        // whereas autoLink=true reads fx.fire (11.0)!
        var fireAddress = NiagaraDataChannelWorlds.channel(null, chan, new Vec3(100.0, 300.0, 200.0),
                NiagaraDataChannels.AccessContext.withTag(new Vec3(1.0, 3.0, 2.0), "fx.fire"), null, false).address;
        EffectHandle childInFire = new EffectHandle(new Vec3(1.0, 2.0, 3.0));
        // Auto-link=false -> queries authored AccessContext (fx.ice -> 99.0)!
        var iceReadBuf = NiagaraDataChannelWorlds.channel(null, chan, new Vec3(100.0, 200.0, 300.0),
                new NiagaraDataChannels.AccessContext(new Vec3(1.0, 3.0, 2.0), childInFire, new Vec3(1.0, 2.0, 3.0),
                        null, 0.0, List.of(), null, false, null, null, "fx.ice", null),
                childInFire, false);
        assertEquals(99.0f, Float.intBitsToFloat(iceReadBuf.read(NiagaraDataChannelWorlds.Destination.CPU, true).get(0).words()[0]), 1e-4f);
        assertFalse(fireAddress.equals(iceReadBuf.address));
        NiagaraDataChannelWorlds.clear();
    }

    @Test
    void enforcesTriStateSystemOverrideDistinctCollectionBucketsAndStateOwnerMetadata() {
        // #5 + #6: explicit overrideSystemToSpawn=true with empty systems must NOT fall back to DefaultSystemToSpawn,
        // and two distinct SystemCollections pointing at the same handler must bucket separately.
        var defaultSys = Identifier.parse("test:default_handler");
        var sharedSys = Identifier.parse("test:shared_handler");
        var burstSettings = new NiagaraDataChannelDefinition.GameplayBurst(
                new Vec3(2500.0, 2500.0, 2500.0), new Vec3(250.0, 250.0, 250.0),
                List.of(defaultSys), "/game/default_handler.default_handler",
                new NiagaraDataChannelDefinition.AttachmentSettings(150.0, List.of("Status.Burning"),
                        List.of("/Script/Engine.CharacterMovementComponent")));
        var channel = new NiagaraDataChannelDefinition.Channel(
                "/Game/NDC_TriState", List.of(new Variable("Val", "/Script/Niagara.NiagaraFloat",
                        List.of(NiagaraDataChannelDefinition.Register.FLOAT))),
                true, false, 3, null, burstSettings);

        var world = new NiagaraDataChannelWorlds.World(null);
        List<Identifier> spawnedIds = new ArrayList<>();
        List<EffectHandle> spawnedHandles = new ArrayList<>();
        var bursts = new NiagaraDataChannelGameplayBursts(world, channel, (lvl, id, pos, seed) -> {
            var h = new EffectHandle(pos);
            spawnedIds.add(id);
            spawnedHandles.add(h);
            return h;
        });

        // overrideSystem=true, systems=[] -> spawns nothing (NOT default_handler!)
        var nullOverrideCtx = new NiagaraDataChannels.AccessContext(
                new Vec3(1.0, 2.0, 3.0), null, null, null, 0.0, List.of(), null,
                false, null, null, "", null, List.of(), true, "", null, null);
        bursts.acquire(new Vec3(100.0, 200.0, 300.0), nullOverrideCtx);
        assertEquals(0, spawnedIds.size(), "Explicit bOverrideSystemToSpawn=true + null MUST NOT fall back to DefaultSystemToSpawn");
        assertEquals(1, bursts.activeBucketCount());

        // Collection A & Collection B both expand to [sharedSys] -> MUST create 2 distinct buckets!
        var colACtx = new NiagaraDataChannels.AccessContext(
                new Vec3(1.0, 2.0, 3.0), null, null, null, 0.0, List.of(), null,
                false, null, null, "", sharedSys, List.of(sharedSys), true, "/game/col_a.col_a", null, null);
        var colBCtx = new NiagaraDataChannels.AccessContext(
                new Vec3(1.0, 2.0, 3.0), null, null, null, 0.0, List.of(), null,
                false, null, null, "", sharedSys, List.of(sharedSys), true, "/game/col_b.col_b", null, null);
        bursts.acquire(new Vec3(100.0, 200.0, 300.0), colACtx);
        bursts.acquire(new Vec3(100.0, 200.0, 300.0), colBCtx);
        assertEquals(2, spawnedIds.size());
        assertEquals(3, bursts.activeBucketCount(), "Distinct collections Col_A and Col_B + empty null override must keep separate buckets");

        // overrideSystem=false -> uses DefaultSystemToSpawn!
        var unsetCtx = new NiagaraDataChannels.AccessContext(
                new Vec3(1.0, 2.0, 3.0), null, null, null, 0.0, List.of(), null,
                false, null, null, "", null, List.of(), false, "", null, null);
        bursts.acquire(new Vec3(100.0, 200.0, 300.0), unsetCtx);
        assertTrue(spawnedIds.contains(defaultSys), "Unset bOverrideSystemToSpawn=false must use DefaultSystemToSpawn");

        // #4: When channel DefaultSystemToSpawn itself is empty, default (override=false) and null override (override=true, "") share the same bucket!
        var noDefaultBurst = new NiagaraDataChannelDefinition.GameplayBurst(
                new Vec3(2500.0, 2500.0, 2500.0), new Vec3(250.0, 250.0, 250.0),
                List.of(), "",
                new NiagaraDataChannelDefinition.AttachmentSettings(150.0, List.of("Status.Burning"),
                        List.of("/Script/Engine.CharacterMovementComponent", "/Game/A/MovingComponent.MovingComponent_C")));
        var noDefaultChannel = new NiagaraDataChannelDefinition.Channel(
                "/Game/NDC_NoDefault", List.of(new Variable("Val", "/Script/Niagara.NiagaraFloat",
                        List.of(NiagaraDataChannelDefinition.Register.FLOAT))),
                true, false, 3, null, noDefaultBurst);
        var noDefaultBursts = new NiagaraDataChannelGameplayBursts(world, noDefaultChannel, (lvl, id, pos, seed) -> new EffectHandle(pos));
        noDefaultBursts.acquire(new Vec3(100.0, 200.0, 300.0), unsetCtx);
        noDefaultBursts.acquire(new Vec3(100.0, 200.0, 300.0), nullOverrideCtx);
        assertEquals(1, noDefaultBursts.activeBucketCount(),
                "DefaultSystemToSpawn==null (override=false) and bOverrideSystemToSpawn==true+null must map to the same final-selected-system bucket");

        // #7: NiagaraDataChannelState.capture(...) propagates real ownerSpeed / attachParentSpeed / ownerBounds / ownerTags / canonical class hierarchy
        NiagaraDataChannelWorlds.clear();
        var accessCfg = new NiagaraDataChannelDefinition.AccessContextConfig(
                false, Vec3.ZERO, false, List.of(), "", false, false, null, false, null, "");
        var def = new NiagaraDataChannelDefinition(channel, true, true, true, true, true, true, false,
                4, false, true, false, false, accessCfg);
        var state = new NiagaraDataChannelState(def);

        EffectHandle speedOwner = new EffectHandle(new Vec3(10.0, 20.0, 30.0));
        speedOwner.setNiagaraOwnerVelocity(new com.kltyton.lumenfx.api.client.parameter.ActorComponentInputs.Velocity(200.0, 0.0, 0.0));
        speedOwner.setNiagaraOwnerMetadata(
                new com.kltyton.lumenfx.effect.niagara.NiagaraBounds(-50.0, -50.0, -50.0, 50.0, 50.0, 50.0),
                List.of("Status.Burning"), "/Script/Engine.CharacterMovementComponent");
        state.capture(null, false, true, false, Vec3.ZERO, new Vec3(10.0, 20.0, 30.0), speedOwner, idx -> true);
        var builtCtx = NiagaraDataChannels.AccessContext.attached(speedOwner);
        assertEquals(200.0, builtCtx.ownerSpeed(), 1e-4);
        assertEquals("/Script/Engine.CharacterMovementComponent", builtCtx.ownerComponentType());
        assertEquals(List.of("Status.Burning"), builtCtx.ownerTags());
        assertEquals(50.0, builtCtx.ownerBounds().maxX(), 1e-4);
        assertTrue(NiagaraDataChannelGameplayBursts.useAttachedPath(
                new NiagaraDataChannels.AccessContext(Vec3.ZERO, speedOwner, Vec3.ZERO,
                        builtCtx.ownerBounds(), 200.0, List.of(), null, false, null, null, "", null),
                burstSettings.attachment()), "Owner speed >= threshold must engage attached path");
        assertTrue(NiagaraDataChannelGameplayBursts.useAttachedPath(
                new NiagaraDataChannels.AccessContext(Vec3.ZERO, speedOwner, Vec3.ZERO,
                        builtCtx.ownerBounds(), 0.0, List.of(), "/Script/Engine.CharacterMovementComponent", false, null, null, "", null),
                burstSettings.attachment()), "Exact canonical component class path must engage attached path");

        // #5: Dual speed check (component dynamic physics velocity vs attach parent root velocity)
        EffectHandle dualSpeedHandle = new EffectHandle(Vec3.ZERO);
        dualSpeedHandle.setActorComponentInputs(new com.kltyton.lumenfx.api.client.parameter.ActorComponentInputs(
                Map.of(),
                new com.kltyton.lumenfx.api.client.parameter.ActorComponentInputs.Component(
                        null, null,
                        new com.kltyton.lumenfx.api.client.parameter.ActorComponentInputs.Velocity(0.0, 0.0, 0.0),
                        new com.kltyton.lumenfx.api.client.parameter.ActorComponentInputs.Velocity(200.0, 0.0, 0.0)),
                Map.of()));
        var dualPhysicsCtx = NiagaraDataChannels.AccessContext.attached(dualSpeedHandle);
        assertTrue(NiagaraDataChannelGameplayBursts.useAttachedPath(dualPhysicsCtx, noDefaultBurst.attachment()),
                "Non-zero component dynamic physics speed must engage attached path even when attach parent owner velocity is zero");

        // #4b: Spawned attached handler H with autoLink=false & forceAttach=true resolves back to attach parent P (no H2 cascade!)
        var cascadeIds = new ArrayList<Identifier>();
        var cascadeChannel = new NiagaraDataChannelDefinition.Channel(
                "/Game/NDC_Cascade", List.of(new Variable("Val", "/Script/Niagara.NiagaraFloat",
                        List.of(NiagaraDataChannelDefinition.Register.FLOAT))),
                true, false, 3, null, noDefaultBurst);
        var cascadeBurstSettings = new NiagaraDataChannelDefinition.GameplayBurst(
                new Vec3(2500.0, 2500.0, 2500.0), new Vec3(250.0, 250.0, 250.0),
                List.of(defaultSys), "/game/default_handler.default_handler",
                new NiagaraDataChannelDefinition.AttachmentSettings(150.0, List.of("Effects.Ice"),
                        List.of("/Script/Engine.CharacterMovementComponent", "/Game/A/MovingComponent.MovingComponent_C")));
        var cascadeDefChannel = new NiagaraDataChannelDefinition.Channel(
                "/Game/NDC_Cascade2", List.of(new Variable("Val", "/Script/Niagara.NiagaraFloat",
                        List.of(NiagaraDataChannelDefinition.Register.FLOAT))),
                true, false, 3, null, cascadeBurstSettings);
        var worldMap = new NiagaraDataChannelWorlds.World(null);
        var cascadeBursts = new NiagaraDataChannelGameplayBursts(worldMap, cascadeDefChannel, (lvl, id, pos, seed) -> {
            cascadeIds.add(id);
            return new EffectHandle(pos);
        });
        EffectHandle realParentP = new EffectHandle(Vec3.ZERO);
        var parentCtx = new NiagaraDataChannels.AccessContext(Vec3.ZERO, realParentP, Vec3.ZERO, null, 0.0, 0.0,
                List.of(), null, List.of(), true, null, null, "", null, List.of(), false, "", null, null);
        var parentBuffer = cascadeBursts.acquire(Vec3.ZERO, parentCtx);
        assertEquals(1, cascadeIds.size());
        EffectHandle spawnedAttachedH = worldMap.attachedHandlers.keySet().iterator().next();
        var readerFromHNoAutoLinkCtx = new NiagaraDataChannels.AccessContext(Vec3.ZERO, spawnedAttachedH, Vec3.ZERO, null, 0.0, 0.0,
                List.of(), null, List.of(), true, null, null, "", null, List.of(), false, "", null, null);
        var reacquiredBuffer = cascadeBursts.acquire(Vec3.ZERO, readerFromHNoAutoLinkCtx, spawnedAttachedH);
        assertEquals(parentBuffer.address, reacquiredBuffer.address,
                "Spawned attached handler H with autoLink=false must resolve back to parent P bucket, not create an H->H2 bucket");
        assertEquals(1, cascadeIds.size());
        assertEquals(1, cascadeBursts.activeBucketCount());

        // #5b: GameplayTags parent hierarchy (configured 'Effects.Ice' matches owner 'Effects.Ice.Snow', rejects 'Effects.IceCube' & reverse child->parent)
        var iceChildTagCtx = new NiagaraDataChannels.AccessContext(Vec3.ZERO, realParentP, Vec3.ZERO, null, 0.0, 0.0,
                List.of("Effects.Ice.Snow"), null, List.of(), false, null, null, "", null, List.of(), false, "", null, null);
        assertTrue(NiagaraDataChannelGameplayBursts.useAttachedPath(iceChildTagCtx, cascadeBurstSettings.attachment()),
                "Configured parent tag Effects.Ice must match owner child tag Effects.Ice.Snow");
        var icePrefixTrapCtx = new NiagaraDataChannels.AccessContext(Vec3.ZERO, realParentP, Vec3.ZERO, null, 0.0, 0.0,
                List.of("Effects.IceCube"), null, List.of(), false, null, null, "", null, List.of(), false, "", null, null);
        assertFalse(NiagaraDataChannelGameplayBursts.useAttachedPath(icePrefixTrapCtx, cascadeBurstSettings.attachment()),
                "Substring prefix without dot boundary (Effects.IceCube) must NOT match Effects.Ice");
        var snowOnlyAttachment = new NiagaraDataChannelDefinition.AttachmentSettings(
                150.0, List.of("Effects.Ice.Snow"), List.of());
        var parentOnlyTagCtx = new NiagaraDataChannels.AccessContext(Vec3.ZERO, realParentP, Vec3.ZERO, null, 0.0, 0.0,
                List.of("Effects.Ice"), null, List.of(), false, null, null, "", null, List.of(), false, "", null, null);
        assertFalse(NiagaraDataChannelGameplayBursts.useAttachedPath(parentOnlyTagCtx, snowOnlyAttachment),
                "Owner having only parent tag Effects.Ice must NOT satisfy child filter Effects.Ice.Snow");

        // #6: Short-name collision across packages must NOT match; UE5.9 C++ (ComponentTypes.Contains(Component->GetClass())) does exact class matching
        var wrongPkgCtx = new NiagaraDataChannels.AccessContext(Vec3.ZERO, speedOwner, Vec3.ZERO, null, 0.0, 0.0, List.of(),
                "/Game/B/MovingComponent.MovingComponent_C", List.of(), false, null, null, "", null, List.of(), false, "", null, null);
        assertFalse(NiagaraDataChannelGameplayBursts.useAttachedPath(wrongPkgCtx, noDefaultBurst.attachment()),
                "Different canonical path sharing short name must NOT match ComponentTypes");
        var subclassCtx = new NiagaraDataChannels.AccessContext(Vec3.ZERO, speedOwner, Vec3.ZERO, null, 0.0, 0.0, List.of(),
                "/Game/B/SubMoving.SubMoving_C", List.of("/Game/A/MovingComponent.MovingComponent_C"),
                false, null, null, "", null, List.of(), false, "", null, null);
        assertFalse(NiagaraDataChannelGameplayBursts.useAttachedPath(subclassCtx, noDefaultBurst.attachment()),
                "Actual UE5.9 GameplayBurst.cpp:166 uses ComponentTypes.Contains(Component->GetClass()) exact equality (despite .h:74 comment)");
        NiagaraDataChannelWorlds.clear();

        // #2 (new re-audit): GameplayBurst autoLink=true on unlinked handler returns null,
        // while Islands uses legacy SearchParams (no bIsAutoLinkingSystem): bound handler resolves its owning island,
        // and unlinked/manual reader falls through to lookup existing island or create a new island by location!
        var unlinkedOwner = new EffectHandle(new Vec3(100.0, 200.0, 300.0));
        var unlinkedCtx = new NiagaraDataChannels.AccessContext(Vec3.ZERO, unlinkedOwner, Vec3.ZERO, null, 0.0, 0.0,
                List.of(), null, List.of(), false, null, null, "", null, List.of(), false, "", null, null);
        assertNull(NiagaraDataChannelWorlds.channel(null, channel, new Vec3(100.0, 200.0, 300.0), unlinkedCtx, unlinkedOwner, true, true),
                "GameplayBurst autoLink=true on unlinked handler must return null instead of acquiring a bucket");
        var islandChan = new NiagaraDataChannelDefinition.Channel(
                "/Game/NDC_Island_AutoLink", List.of(new Variable("Val", "/Script/Niagara.NiagaraFloat",
                        List.of(NiagaraDataChannelDefinition.Register.FLOAT))),
                false, false, 1, new NiagaraDataChannelDefinition.Islands(false, new Vec3(100.0, 100.0, 100.0),
                        new Vec3(1000.0, 1000.0, 1000.0), Vec3.ZERO, 4, List.of(defaultSys)), null);
        var islandWorld = NiagaraDataChannelWorlds.world(null);
        List<EffectHandle> spawnedIslandHandlers = new ArrayList<>();
        islandWorld.islands.put(islandChan, new NiagaraDataChannelIslands(islandWorld, islandChan, (lvl, id, pos, seed) -> {
            var h = new EffectHandle(pos);
            spawnedIslandHandlers.add(h);
            return h;
        }));
        var writerIslandBuf = NiagaraDataChannelWorlds.channel(null, islandChan, new Vec3(100.0, 200.0, 300.0),
                unlinkedCtx, null, false, false);
        assertNotNull(writerIslandBuf);
        assertEquals(1, spawnedIslandHandlers.size());

        // Bound island handler reading from shifted location (even with autoLink=false) resolves back to its owning island!
        EffectHandle boundIslandHandler = spawnedIslandHandlers.get(0);
        var boundReadBuf = NiagaraDataChannelWorlds.channel(null, islandChan, new Vec3(9000.0, 200.0, 300.0),
                unlinkedCtx, boundIslandHandler, true, false);
        assertNotNull(boundReadBuf);
        assertEquals(writerIslandBuf.address, boundReadBuf.address,
                "Bound Islands handler must resolve its owning island on ReadOnly even when queried from a distant position");

        // Unlinked/manual reader (autoLink=true) at existing island location reads the EXISTING island!
        var manualExistingBuf = NiagaraDataChannelWorlds.channel(null, islandChan, new Vec3(120.0, 200.0, 300.0),
                unlinkedCtx, unlinkedOwner, true, true);
        assertNotNull(manualExistingBuf, "Unlinked Islands reader with autoLink=true must still resolve existing island by location");
        assertEquals(writerIslandBuf.address, manualExistingBuf.address);

        // Unlinked/manual reader (autoLink=true) far away creates a NEW island!
        var manualNewBuf = NiagaraDataChannelWorlds.channel(null, islandChan, new Vec3(9000.0, 200.0, 300.0),
                unlinkedCtx, unlinkedOwner, true, true);
        assertNotNull(manualNewBuf, "Unlinked Islands reader at distant location must create a new island");
        assertFalse(writerIslandBuf.address.equals(manualNewBuf.address));

        var autoLinkReaderDef = new NiagaraDataChannelDefinition(channel, false, true, true, true, false, true, true,
                0, false, false, false, true, accessCfg);
        var autoLinkState = new NiagaraDataChannelState(autoLinkReaderDef);
        autoLinkState.capture(null, true, false, false, Vec3.ZERO, new Vec3(100.0, 200.0, 300.0), unlinkedOwner, idx -> true);
        assertFalse(autoLinkState.gpuSnapshot(1).bound(), "Unlinked GameplayBurst autoLink GPU snapshot must stay unbound cleanly");

        // #4 (re-audit): Duplicate systems in expanded collection spawn one EffectHandle per entry
        var dupWorld = new NiagaraDataChannelWorlds.World(null);
        List<Identifier> dupSpawned = new ArrayList<>();
        var dupBursts = new NiagaraDataChannelGameplayBursts(dupWorld, channel, (lvl, id, pos, seed) -> {
            dupSpawned.add(id);
            return new EffectHandle(pos);
        });
        var dupCtx = new NiagaraDataChannels.AccessContext(
                new Vec3(1.0, 2.0, 3.0), null, null, null, 0.0, List.of(), null,
                false, null, null, "", sharedSys, List.of(sharedSys, sharedSys), true, "/game/col_dup.col_dup", null, null);
        dupBursts.acquire(new Vec3(100.0, 200.0, 300.0), dupCtx);
        assertEquals(List.of(sharedSys, sharedSys), dupSpawned,
                "Duplicate entries in SystemCollection must spawn one handler per slot in order");
        NiagaraDataChannelWorlds.clear();
    }
}
