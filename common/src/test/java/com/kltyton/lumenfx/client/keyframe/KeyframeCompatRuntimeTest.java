package com.kltyton.lumenfx.client.keyframe;

import com.kltyton.lumenfx.api.client.keyframe.KeyframeEffectParams;
import com.kltyton.lumenfx.api.client.keyframe.LumenFxKeyframeBridge;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyframeCompatRuntimeTest {

    @AfterEach
    void cleanup() {
        KeyframeEffectManager.clearAll();
        KeyframeEffectManager.setAutoInterceptEnabled(true);
    }

    @Test
    void managedEffectCheckAndManualTriggerDedupRespectCoexistenceBoundaries() {
        List<FakeHandle> spawned = new ArrayList<>();
        Set<String> loaded = Set.of("demo:fire_slash", "lumenfx:ember");
        KeyframeEffectManager.installAdapter(new FakeAdapter(loaded, spawned));

        // Loaded LumenFX effects and explicit prefixes are managed; vanilla / KBR-only IDs are not.
        assertTrue(KeyframeEffectManager.isManagedEffect("demo:fire_slash", null));
        assertTrue(KeyframeEffectManager.isManagedEffect("lumenfx:demo/fire_slash", null));
        assertTrue(KeyframeEffectManager.isManagedEffect("lumenfx:missing/forced", null));
        assertFalse(KeyframeEffectManager.isManagedEffect("minecraft:crit", null));
        assertFalse(KeyframeEffectManager.isManagedEffect("kbr_demo:snowstorm_burst", null));

        // Disabling auto-intercept stops automatic KBR interception while keeping manual trigger active.
        KeyframeEffectManager.setAutoInterceptEnabled(false);
        assertFalse(KeyframeEffectManager.isManagedEffect("demo:fire_slash", null));
        KeyframeEffectManager.setAutoInterceptEnabled(true);

        Object host = new Object();
        Object controller = new Object();

        // Manual trigger in custom callback followed immediately by KBR auto-hook must NOT double-spawn one-shot effect.
        boolean manual = LumenFxKeyframeBridge.trigger(
                null, host, null, controller, "main",
                "demo:fire_slash", null, "follow=false", false,
                1.0, 2.0, 3.0);
        assertTrue(manual);
        assertEquals(1, spawned.size());

        boolean immediatelyFollowingAutoHook = KeyframeEffectManager.handleKeyframe(
                null, host, null, controller, "main",
                "demo:fire_slash", null, "follow=false", false,
                1.0, 2.0, 3.0, true);
        assertTrue(immediatelyFollowingAutoHook);
        assertEquals(1, spawned.size(), "Auto-hook right after manual callback trigger must not duplicate spawn");

        // A different effect on the same controller is not deduplicated.
        boolean secondEffect = KeyframeEffectManager.handleKeyframe(
                null, host, null, controller, "main",
                "ember", null, "follow=false", false,
                1.0, 2.0, 3.0, true);
        assertTrue(secondEffect);
        assertEquals(2, spawned.size());
    }

    @Test
    void kbrDedupIsolatesHostsSurvivesSlowCallbacksHandlesReverseOrderLoopsStopsAndFilters() throws Exception {
        List<FakeHandle> spawned = new ArrayList<>();
        Set<String> loaded = Set.of("demo:slash", "demo:spark");
        KeyframeEffectManager.installAdapter(new FakeAdapter(loaded, spawned));

        // 1. Two different hosts sharing the exact same controller must BOTH spawn (host B must never be swallowed by host A).
        Object hostA = new Object();
        Object hostB = new Object();
        Object sharedController = new Object();
        assertTrue(LumenFxKeyframeBridge.trigger(
                null, hostA, null, sharedController, "main",
                "demo:slash", null, "follow=false", false,
                1.0, 0.0, 0.0));
        assertTrue(KeyframeEffectManager.handleKeyframe(
                null, hostB, null, sharedController, "main",
                "demo:slash", null, "follow=false", false,
                16.0, 0.0, 0.0, true));
        assertEquals(2, spawned.size());
        assertEquals(1.0, spawned.get(0).lastX, 1e-6);
        assertEquals(16.0, spawned.get(1).lastX, 1e-6);

        // 2. Slow callback (>5ms delay) between manual and auto on the same host must STILL deduplicate.
        spawned.clear();
        KeyframeEffectManager.clearAll();
        Object slowHost = new Object();
        Object slowCtrl = new Object();
        assertTrue(LumenFxKeyframeBridge.trigger(
                null, slowHost, null, slowCtrl, "main",
                "demo:slash", null, "follow=false", false,
                1.0, 0.0, 0.0));
        Thread.sleep(15L);
        assertTrue(KeyframeEffectManager.handleKeyframe(
                null, slowHost, null, slowCtrl, "main",
                "demo:slash", null, "follow=false", false,
                1.0, 0.0, 0.0, true));
        assertEquals(1, spawned.size(), "Slow callback (>5ms) must not break manual/auto dedup");

        // 3. Reversed callback order (auto BEFORE manual) on same host (and fresh controller) must deduplicate.
        spawned.clear();
        KeyframeEffectManager.clearAll();
        Object revHost = new Object();
        Object revCtrl = new Object();
        assertTrue(KeyframeEffectManager.handleKeyframe(
                null, revHost, null, revCtrl, "main",
                "demo:slash", null, "follow=false", false,
                1.0, 0.0, 0.0, true));
        assertTrue(LumenFxKeyframeBridge.trigger(
                null, revHost, null, revCtrl, "main",
                "demo:slash", null, "follow=false", false,
                1.0, 0.0, 0.0));
        assertEquals(1, spawned.size(), "Auto-then-manual callback order must not double-spawn");

        // 4. Second animation loop fires again cleanly because the first manual/auto pair was consumed.
        assertTrue(LumenFxKeyframeBridge.trigger(
                null, revHost, null, revCtrl, "main",
                "demo:slash", null, "follow=false", false,
                2.0, 0.0, 0.0));
        assertTrue(KeyframeEffectManager.handleKeyframe(
                null, revHost, null, revCtrl, "main",
                "demo:slash", null, "follow=false", false,
                2.0, 0.0, 0.0, true));
        assertEquals(2, spawned.size(), "Second loop must spawn once again");

        // 5. Two different keys at the same timestamp on the same host both spawn independently.
        assertTrue(LumenFxKeyframeBridge.trigger(
                null, revHost, null, revCtrl, "main",
                "demo:spark", null, "follow=false", false,
                3.0, 0.0, 0.0));
        assertEquals(3, spawned.size());

        // 6. Stop request handled manually keeps auto counterpart claimed (returns true) without falling through to KBR.
        Object stopHost = new Object();
        Object stopCtrl = new Object();
        assertTrue(LumenFxKeyframeBridge.trigger(
                null, stopHost, null, stopCtrl, "main",
                "demo:slash", null, "follow=true", true,
                4.0, 0.0, 0.0));
        assertTrue(LumenFxKeyframeBridge.trigger(
                null, stopHost, null, stopCtrl, "main",
                "demo:slash", null, "stop=true", false,
                4.0, 0.0, 0.0));
        assertTrue(KeyframeEffectManager.handleKeyframe(
                null, stopHost, null, stopCtrl, "main",
                "demo:slash", null, "stop=true", false,
                4.0, 0.0, 0.0, true), "Auto counterpart of a handled stop keyframe must return true");

        // 7. Filter-cancelled trigger must NOT record a dedup pair; non-explicit missing effect returns false.
        LumenFxKeyframeBridge.Filter blockSlash = req -> !"demo:slash".equals(req.resolvedEffectId());
        LumenFxKeyframeBridge.addFilter(blockSlash);
        try {
            Object filterHost = new Object();
            Object filterCtrl = new Object();
            assertFalse(LumenFxKeyframeBridge.trigger(
                    null, filterHost, null, filterCtrl, "main",
                    "demo:slash", null, "follow=false", false,
                    5.0, 0.0, 0.0));
            assertFalse(KeyframeEffectManager.handleKeyframe(
                    null, filterHost, null, filterCtrl, "main",
                    "demo:slash", null, "follow=false", false,
                    5.0, 0.0, 0.0, true));
        } finally {
            LumenFxKeyframeBridge.removeFilter(blockSlash);
        }
        assertFalse(KeyframeEffectManager.handleKeyframe(
                null, revHost, null, revCtrl, "main",
                "unknown:nonexistent", null, null, false,
                0.0, 0.0, 0.0, true));

        // 8. Standalone public manual trigger outside any dispatch scope MUST NOT swallow a separate tokenized KBR keyframe
        // in the same tick on the same host/controller/effect.
        spawned.clear();
        KeyframeEffectManager.clearAll();
        Object sameTickHost = new Object();
        Object sameTickCtrl = new Object();
        Object autoKeyframeData = new Object();
        assertTrue(LumenFxKeyframeBridge.trigger(
                null, sameTickHost, null, sameTickCtrl, "main",
                "demo:slash", null, "follow=false; color=#ff0000", false,
                1.0, 0.0, 0.0));
        assertTrue(KeyframeEffectManager.handleKeyframe(
                null, sameTickHost, null, sameTickCtrl, "main",
                "demo:slash", null, "follow=false; color=#00ff00", false,
                16.0, 0.0, 0.0, true, autoKeyframeData));
        assertEquals(2, spawned.size(), "Standalone public trigger must not swallow a separate tokenized auto keyframe");
        assertEquals(1.0, spawned.get(0).lastX, 1e-6);
        assertEquals(16.0, spawned.get(1).lastX, 1e-6);

        // 9. Inside a GeckoLib dispatch scope, public trigger() inherits the scope token and dedups against KBR auto-hook
        // in EITHER order, while a second keyframe in the same tick gets a fresh scope and spawns independently.
        spawned.clear();
        KeyframeEffectManager.clearAll();
        Object scopeOwner = new Object();
        Object scope1 = KeyframeEffectManager.enterDispatchScope(scopeOwner);
        try {
            assertTrue(LumenFxKeyframeBridge.trigger(
                    null, sameTickHost, null, sameTickCtrl, "main",
                    "demo:slash", null, "follow=false", false,
                    1.0, 0.0, 0.0));
            assertTrue(KeyframeEffectManager.handleKeyframe(
                    null, sameTickHost, null, sameTickCtrl, "main",
                    "demo:slash", null, "follow=false", false,
                    1.0, 0.0, 0.0, true, KeyframeEffectManager.currentDispatchToken()));
        } finally {
            KeyframeEffectManager.exitDispatchScope(scope1);
        }
        Object scope2 = KeyframeEffectManager.enterDispatchScope(scopeOwner);
        try {
            assertTrue(KeyframeEffectManager.handleKeyframe(
                    null, sameTickHost, null, sameTickCtrl, "main",
                    "demo:slash", null, "follow=false", false,
                    16.0, 0.0, 0.0, true, KeyframeEffectManager.currentDispatchToken()));
            assertTrue(LumenFxKeyframeBridge.trigger(
                    null, sameTickHost, null, sameTickCtrl, "main",
                    "demo:slash", null, "follow=false", false,
                    16.0, 0.0, 0.0));
        } finally {
            KeyframeEffectManager.exitDispatchScope(scope2);
        }
        assertEquals(2, spawned.size(), "Two distinct dispatched keyframes in same tick must each spawn once");
        assertEquals(1.0, spawned.get(0).lastX, 1e-6);
        assertEquals(16.0, spawned.get(1).lastX, 1e-6);

        // 10. Exception thrown inside a dispatch scope MUST unwind scope & prune pending pair so subsequent independent
        // keyframe dispatches on the same host/controller/effect are never swallowed.
        spawned.clear();
        KeyframeEffectManager.clearAll();
        Object exScope = KeyframeEffectManager.enterDispatchScope(scopeOwner);
        boolean caught = false;
        try {
            assertTrue(LumenFxKeyframeBridge.trigger(
                    null, sameTickHost, null, sameTickCtrl, "main",
                    "demo:slash", null, "follow=false", false,
                    1.0, 0.0, 0.0));
            throw new IllegalStateException("simulated callback failure");
        } catch (IllegalStateException expected) {
            caught = true;
        } finally {
            KeyframeEffectManager.exitDispatchScope(exScope);
        }
        assertTrue(caught);
        assertNull(KeyframeEffectManager.currentDispatchToken(), "Dispatch scope must be cleared after exception");
        assertTrue(KeyframeEffectManager.handleKeyframe(
                null, sameTickHost, null, sameTickCtrl, "main",
                "demo:slash", null, "follow=false", false,
                16.0, 0.0, 0.0, true, new Object()));
        assertEquals(2, spawned.size(), "Post-exception independent keyframe must not be swallowed");

        // 11. Nested (re-entrant) dispatch scopes on the SAME owner must stack LIFO cleanly without clobbering outer scope.
        spawned.clear();
        KeyframeEffectManager.clearAll();
        Object outer = KeyframeEffectManager.enterDispatchScope(scopeOwner);
        try {
            assertTrue(LumenFxKeyframeBridge.trigger(
                    null, sameTickHost, null, sameTickCtrl, "main",
                    "demo:slash", null, "follow=false", false,
                    1.0, 0.0, 0.0));
            Object inner = KeyframeEffectManager.enterDispatchScope(scopeOwner);
            try {
                assertTrue(KeyframeEffectManager.currentDispatchToken() == inner);
                assertTrue(LumenFxKeyframeBridge.trigger(
                        null, sameTickHost, null, sameTickCtrl, "main",
                        "demo:slash", null, "follow=false", false,
                        8.0, 0.0, 0.0));
                assertTrue(KeyframeEffectManager.handleKeyframe(
                        null, sameTickHost, null, sameTickCtrl, "main",
                        "demo:slash", null, "follow=false", false,
                        8.0, 0.0, 0.0, true, KeyframeEffectManager.currentDispatchToken()));
            } finally {
                KeyframeEffectManager.exitDispatchScope(inner);
            }
            assertTrue(KeyframeEffectManager.currentDispatchToken() == outer, "Outer scope must be restored after inner exit");
            assertTrue(KeyframeEffectManager.handleKeyframe(
                    null, sameTickHost, null, sameTickCtrl, "main",
                    "demo:slash", null, "follow=false", false,
                    1.0, 0.0, 0.0, true, KeyframeEffectManager.currentDispatchToken()));
        } finally {
            KeyframeEffectManager.exitDispatchScope(outer);
        }
        assertNull(KeyframeEffectManager.currentDispatchToken());
        assertEquals(2, spawned.size(), "Nested re-entrant dispatch must spawn once for outer and once for inner");
    }

    @Test
    void locatorSourcePriorityPreventsLowerPriorityFallbackFromOverwritingRenderedModelSample() {
        List<FakeHandle> spawned = new ArrayList<>();
        KeyframeEffectManager.installAdapter(new FakeAdapter(Set.of("demo:slash"), spawned));

        Object geckoHost = new Object();
        Object sceneKey = new Object();

        // GeckoLib renders and publishes authoritative GeoLocator pose at X = 12.5.
        KeyframeEffectManager.onLocatorSample(
                geckoHost, null, "right_hand",
                12.5, 65.0, 3.0, 0, 0, 0, 1, null,
                KeyframeEffectManager.LocatorSource.RENDERED_MODEL);

        assertTrue(LumenFxKeyframeBridge.trigger(
                null, geckoHost, null, sceneKey, "animcue",
                "demo:slash", "right_hand", "follow=true", true,
                0.0, 0.0, 0.0));
        assertEquals(1, spawned.size());
        assertEquals(12.5, spawned.get(0).lastX, 1e-6);

        // Lower-priority ANIMATION_BONE and FALLBACK samples must NOT overwrite the authoritative RENDERED_MODEL sample.
        KeyframeEffectManager.onLocatorSample(
                geckoHost, null, "right_hand",
                8.125, 64.0, 2.0, 0, 0, 0, 1, null,
                KeyframeEffectManager.LocatorSource.ANIMATION_BONE);
        KeyframeEffectManager.onLocatorSample(
                geckoHost, null, "right_hand",
                7.0, 64.0, 1.0, 0, 0, 0, 1, null,
                KeyframeEffectManager.LocatorSource.FALLBACK);
        assertEquals(12.5, spawned.get(0).lastX, 1e-6, "Lower-priority sample must not clobber RENDERED_MODEL pose");

        // Subsequent authoritative RENDERED_MODEL sample DOES update the pose cleanly.
        KeyframeEffectManager.onLocatorSample(
                geckoHost, null, "right_hand",
                14.25, 65.5, 3.5, 0, 0, 0, 1, null,
                KeyframeEffectManager.LocatorSource.RENDERED_MODEL);
        assertEquals(14.25, spawned.get(0).lastX, 1e-6);

        // Non-GeckoLib host without RENDERED_MODEL sample receives ANIMATION_BONE and FALLBACK normally.
        Object normalHost = new Object();
        assertTrue(LumenFxKeyframeBridge.trigger(
                null, normalHost, null, sceneKey, "animcue",
                "demo:slash", "right_hand", "follow=true", true,
                0.0, 0.0, 0.0));
        KeyframeEffectManager.onLocatorSample(
                normalHost, null, "right_hand",
                18.125, 64.75, 0.125, 0, 0, 0, 1, null,
                KeyframeEffectManager.LocatorSource.ANIMATION_BONE);
        assertEquals(2, spawned.size());
        assertEquals(18.125, spawned.get(1).lastX, 1e-6);
        assertEquals(14.25, spawned.get(0).lastX, 1e-6, "Same locator name across two hosts must stay isolated");
    }

    @Test
    void controllerKeyCleanupAndDeferredLocatorSpawnWorkWithoutHardModDependencies() {
        List<FakeHandle> spawned = new ArrayList<>();
        KeyframeEffectManager.installAdapter(new FakeAdapter(Set.of("demo:slash"), spawned));

        Object playerA = new Object();
        Object playerB = new Object();
        Object ctrlA = new Object();
        Object ctrlB = new Object();

        // Trigger with a locator: stays pending until first bone/locator pose sample arrives.
        assertTrue(LumenFxKeyframeBridge.trigger(
                null, playerA, null, ctrlA, "pal",
                "demo:slash", "right_item", "follow=true; rotate=true", true,
                0.0, 0.0, 0.0));
        assertTrue(LumenFxKeyframeBridge.trigger(
                null, playerB, null, ctrlB, "pal",
                "demo:slash", "right_item", "follow=true; rotate=true", true,
                0.0, 0.0, 0.0));
        assertEquals(0, spawned.size());
        assertTrue(KeyframeEffectManager.hasTrackedLocators(playerA));
        assertTrue(KeyframeEffectManager.hasTrackedLocators(playerB));

        // Publishing sampled poses spawns each handle at its exact sampled coordinate.
        KeyframeEffectManager.onLocatorSample(playerA, null, "right_item", 10.5, 65.2, -3.25, 0, 0, 0, 1, null);
        KeyframeEffectManager.onLocatorSample(playerB, null, "right_item", 20.5, 70.0, 4.0, 0, 0, 0, 1, null);
        assertEquals(2, spawned.size());
        assertEquals(10.5, spawned.get(0).lastX, 1e-6);
        assertEquals(65.2, spawned.get(0).lastY, 1e-6);

        // Stopping ctrlA closes playerA's effect while leaving playerB's effect alive.
        KeyframeEffectManager.stopForControllerKey(ctrlA);
        assertTrue(spawned.get(0).closed);
        assertFalse(spawned.get(1).closed);
        assertFalse(KeyframeEffectManager.hasTrackedLocators(playerA));
        assertTrue(KeyframeEffectManager.hasTrackedLocators(playerB));
    }

    @Test
    void playerBonePoseSamplerComputesExactVanillaAndBentWorldTransforms() {
        PlayerBonePoseSampler.BoneLookup neutral = name -> PlayerBonePoseSampler.BonePose.IDENTITY;

        // Player standing at (10, 64, 20), facing South (bodyYaw = 0 deg -> model +X maps to world -X, model -Z maps to world +Z).
        PlayerBonePoseSampler.WorldPose rightArm = PlayerBonePoseSampler.sample(10, 64, 20, 0.0F, 1.0F, "right_arm", neutral);
        assertNotNull(rightArm);
        assertEquals(10.0 - 5.0 / 16.0, rightArm.x(), 1e-4);
        assertEquals(64.0 + 22.0 / 16.0, rightArm.y(), 1e-4);
        assertEquals(20.0, rightArm.z(), 1e-4);

        PlayerBonePoseSampler.WorldPose rightItem = PlayerBonePoseSampler.sample(10, 64, 20, 0.0F, 1.0F, "Right_Hand", neutral);
        assertNotNull(rightItem);
        assertEquals(10.0 - 6.0 / 16.0, rightItem.x(), 1e-4);
        assertEquals(64.0 + 12.0 / 16.0, rightItem.y(), 1e-4);
        assertEquals(20.0 + 2.0 / 16.0, rightItem.z(), 1e-4);

        // Verify PAL body.posX = +16px shifts body, held items, and torso_bend by +1.0 world X at bodyYaw = 0
        // (matching both PAL 1.1.6 PlayerRendererMixin and PAL 1.2.9 LivingEntityRendererMixin).
        PlayerBonePoseSampler.BonePose shiftedBody = new PlayerBonePoseSampler.BonePose(
                16.0F, 0, 0, 0, 0, 0, 1, 1, 1,
                0, 0, 0, 0, 0, 0, 1, 1, 1, false);
        PlayerBonePoseSampler.BoneLookup withShiftedBody = name ->
                "body".equals(name) ? shiftedBody : PlayerBonePoseSampler.BonePose.IDENTITY;
        for (String loc : new String[] { "body", "right_item", "left_item", "torso_bend", "head" }) {
            PlayerBonePoseSampler.WorldPose base = PlayerBonePoseSampler.sample(0, 0, 0, 0.0F, 1.0F, loc, neutral);
            PlayerBonePoseSampler.WorldPose moved = PlayerBonePoseSampler.sample(0, 0, 0, 0.0F, 1.0F, loc, withShiftedBody);
            assertNotNull(base);
            assertNotNull(moved);
            assertEquals(1.0, moved.x() - base.x(), 1e-4, "body.posX=+16px must yield +1.0 world X for " + loc);
            assertEquals(0.0, moved.y() - base.y(), 1e-4, "body.posX=+16px must keep Y unchanged for " + loc);
            assertEquals(0.0, moved.z() - base.z(), 1e-4, "body.posX=+16px must keep Z unchanged for " + loc);
        }

        // Verify body.rotX = +90 deg leans head forward (+Z at yaw=0) around waist pivot (0, 12px, 0).
        float halfPi = (float) (Math.PI * 0.5);
        PlayerBonePoseSampler.BonePose pitchedBody = new PlayerBonePoseSampler.BonePose(
                0, 0, 0, halfPi, 0, 0, 1, 1, 1,
                0, 0, 0, 0, 0, 0, 1, 1, 1, false);
        PlayerBonePoseSampler.WorldPose pitchedHead = PlayerBonePoseSampler.sample(
                0, 0, 0, 0.0F, 1.0F, "head",
                name -> "body".equals(name) ? pitchedBody : PlayerBonePoseSampler.BonePose.IDENTITY);
        assertNotNull(pitchedHead);
        assertEquals(0.0, pitchedHead.x(), 1e-4);
        assertEquals(12.0 / 16.0, pitchedHead.y(), 1e-4);
        assertEquals(12.0 / 16.0, pitchedHead.z(), 1e-4);

        // Verify non-uniform body.scale placement matches PAL 1.2 (after translate/rotate) vs PAL 1.1 (before translate).
        PlayerBonePoseSampler.BonePose scaledShiftedBody = new PlayerBonePoseSampler.BonePose(
                16.0F, 0, 0, 0, 0, 0, 2.0F, 0.5F, 1.0F,
                0, 0, 0, 0, 0, 0, 1, 1, 1, false);
        PlayerBonePoseSampler.BoneLookup withScaledBody = name ->
                "body".equals(name) ? scaledShiftedBody : PlayerBonePoseSampler.BonePose.IDENTITY;
        PlayerBonePoseSampler.WorldPose headPal12 = PlayerBonePoseSampler.sample(
                0, 0, 0, 0.0F, 1.0F, "head", withScaledBody, PlayerBonePoseSampler.BodyTransformOrder.PAL_1_2);
        PlayerBonePoseSampler.WorldPose headPal11 = PlayerBonePoseSampler.sample(
                0, 0, 0, 0.0F, 1.0F, "head", withScaledBody, PlayerBonePoseSampler.BodyTransformOrder.PAL_1_1);
        assertNotNull(headPal12);
        assertNotNull(headPal11);
        assertEquals(1.0, headPal12.x(), 1e-4);
        assertEquals(18.0 / 16.0, headPal12.y(), 1e-4);
        assertEquals(2.0, headPal11.x(), 1e-4);
        assertEquals(12.0 / 16.0, headPal11.y(), 1e-4);

        // Native X-bend (-90 deg in ModelPart space) bends forearm forward (+Z world) and raises hand to Y = 20px.
        PlayerBonePoseSampler.BonePose bentArm = new PlayerBonePoseSampler.BonePose(
                0, 0, 0, 0, 0, 0, 1, 1, 1,
                -halfPi, 0, 0,
                0, 0, 0, 1, 1, 1, false);
        PlayerBonePoseSampler.WorldPose bentItem = PlayerBonePoseSampler.sample(
                0, 0, 0, 0.0F, 1.0F, "right_item",
                name -> "right_arm".equals(name) ? bentArm : PlayerBonePoseSampler.BonePose.IDENTITY);
        assertNotNull(bentItem);
        assertEquals(-6.0 / 16.0, bentItem.x(), 1e-4);
        assertEquals(20.0 / 16.0, bentItem.y(), 1e-4);
        assertEquals(6.0 / 16.0, bentItem.z(), 1e-4);

        // PALMore 3-axis bend + bend.position (+2, +4, -1) + bend.scale on right_arm_bend matches ModelPart translate(+x, -y, +z).
        PlayerBonePoseSampler.BonePose palMoreArm = new PlayerBonePoseSampler.BonePose(
                0, 0, 0, 0, 0, 0, 1, 1, 1,
                0.3F, 0.4F, -0.2F,
                2.0F, 4.0F, -1.0F,
                1.0F, 1.5F, 1.0F, true);
        PlayerBonePoseSampler.WorldPose elbowBend = PlayerBonePoseSampler.sample(
                0, 0, 0, 0.0F, 1.0F, "Right_Arm_Lower",
                name -> "right_arm".equals(name) ? palMoreArm : PlayerBonePoseSampler.BonePose.IDENTITY);
        assertNotNull(elbowBend);
        // Elbow ModelPart (-5, 6, 0) + bendPos (+2, -4, -1) = (-3, 2, -1) -> world (-3/16, +22/16, +1/16)
        assertEquals(-3.0 / 16.0, elbowBend.x(), 1e-4);
        assertEquals(22.0 / 16.0, elbowBend.y(), 1e-4);
        assertEquals(1.0 / 16.0, elbowBend.z(), 1e-4);
        Quaternionf q = new Quaternionf(elbowBend.qx(), elbowBend.qy(), elbowBend.qz(), elbowBend.qw());
        Vector3f forward = q.transform(new Vector3f(0, 0, -1));
        assertTrue(Float.isFinite(forward.length()) && Math.abs(forward.length() - 1.0F) < 1e-4F);

        // PALMore compound held-item bend: bend=(0.55, 0.32, -0.41), bendPos=(2,-3,1), bendScale=(1.2,0.8,1.4)
        // Modern PAL 1.1/1.2 with sidecar-only bone (nativeBend=0): PalMoreBendableCuboids applies -bendX undo before bendPos.
        PlayerBonePoseSampler.BonePose compoundSidecarArm = new PlayerBonePoseSampler.BonePose(
                0, 0, 0, 0, 0, 0, 1, 1, 1,
                0.55F, 0.32F, -0.41F,
                2.0F, -3.0F, 1.0F,
                1.2F, 0.8F, 1.4F, true);
        PlayerBonePoseSampler.BoneLookup rightCompound = name ->
                "right_arm".equals(name) ? compoundSidecarArm : PlayerBonePoseSampler.BonePose.IDENTITY;
        PlayerBonePoseSampler.BoneLookup leftCompound = name ->
                "left_arm".equals(name) ? compoundSidecarArm : PlayerBonePoseSampler.BonePose.IDENTITY;

        for (PlayerBonePoseSampler.BodyTransformOrder modernOrder : new PlayerBonePoseSampler.BodyTransformOrder[] {
                PlayerBonePoseSampler.BodyTransformOrder.PAL_1_2, PlayerBonePoseSampler.BodyTransformOrder.PAL_1_1 }) {
            PlayerBonePoseSampler.WorldPose rightMod = PlayerBonePoseSampler.sample(
                    0, 0, 0, 0.0F, 1.0F, "right_item", rightCompound, modernOrder);
            PlayerBonePoseSampler.WorldPose leftMod = PlayerBonePoseSampler.sample(
                    0, 0, 0, 0.0F, 1.0F, "left_item", leftCompound, modernOrder);
            assertNotNull(rightMod);
            assertNotNull(leftMod);
            assertEquals(-0.037655, rightMod.x(), 1e-4);
            assertEquals(0.606654, rightMod.y(), 1e-4);
            assertEquals(0.072779, rightMod.z(), 1e-4);
            assertEquals(0.717930, leftMod.x(), 1e-4);
            assertEquals(0.667685, leftMod.y(), 1e-4);
            assertEquals(0.099933, leftMod.z(), 1e-4);
        }

        // Modern PAL 1.2 with native bend == sidecar bendX (0.55): bendable-cuboids Rx(+0.55) cancels PalMore Rx(-0.55).
        PlayerBonePoseSampler.BonePose nativePlusSidecarModern = new PlayerBonePoseSampler.BonePose(
                0, 0, 0, 0, 0, 0, 1, 1, 1,
                0.55F, 0.32F, -0.41F,
                2.0F, -3.0F, 1.0F,
                1.2F, 0.8F, 1.4F, true,
                0.0F, 0.55F, true);
        PlayerBonePoseSampler.WorldPose rightCancelled = PlayerBonePoseSampler.sample(
                0, 0, 0, 0.0F, 1.0F, "right_item",
                name -> "right_arm".equals(name) ? nativePlusSidecarModern : PlayerBonePoseSampler.BonePose.IDENTITY,
                PlayerBonePoseSampler.BodyTransformOrder.PAL_1_2);
        assertNotNull(rightCancelled);
        assertEquals(-0.037655, rightCancelled.x(), 1e-4);
        assertEquals(0.645056, rightCancelled.y(), 1e-4);
        assertEquals(-0.208887, rightCancelled.z(), 1e-4);

        // Legacy 1.20.1: native-only 2-param playerAnimator bend (axis=pi/2, bend=0.4) rotates around axisVec=(0,0,-1).
        PlayerBonePoseSampler.BonePose legacyNativeOnly = new PlayerBonePoseSampler.BonePose(
                0, 0, 0, 0, 0, 0, 1, 1, 1,
                0.4F, 0.0F, 0.0F,
                0, 0, 0, 1, 1, 1, false,
                halfPi, 0.4F, false);
        PlayerBonePoseSampler.WorldPose legacyNativeRight = PlayerBonePoseSampler.sample(
                0, 0, 0, 0.0F, 1.0F, "right_item",
                name -> "right_arm".equals(name) ? legacyNativeOnly : PlayerBonePoseSampler.BonePose.IDENTITY,
                PlayerBonePoseSampler.BodyTransformOrder.LEGACY_PALMORE_1_20_1);
        assertNotNull(legacyNativeRight);
        // Right hand relative to elbow (-5,6,0) is (-1,+6,-2)px; rotateAxis(+0.4, (0,0,-1)) == Rz(-0.4) in ModelPart
        double mx = -1.0 / 16.0, my = 6.0 / 16.0;
        double cos = Math.cos(-0.4), sin = Math.sin(-0.4);
        assertEquals(-5.0 / 16.0 + (mx * cos - my * sin), legacyNativeRight.x(), 1e-4);
        assertEquals(18.0 / 16.0 - (mx * sin + my * cos), legacyNativeRight.y(), 1e-4);
        assertEquals(2.0 / 16.0, legacyNativeRight.z(), 1e-4);

        // Legacy 1.20.1 with BOTH native (axis=pi/2, bend=0.4) AND active PALMore sidecar:
        // HeldItemMixin (+0.4) and PalMoreBendyCuboids (-0.4) cancel before applying sidecar bend.
        PlayerBonePoseSampler.BonePose legacyNativeAndSidecar = new PlayerBonePoseSampler.BonePose(
                0, 0, 0, 0, 0, 0, 1, 1, 1,
                0.55F, 0.32F, -0.41F,
                2.0F, -3.0F, 1.0F,
                1.2F, 0.8F, 1.4F, true,
                halfPi, 0.4F, true);
        PlayerBonePoseSampler.WorldPose legacyCombinedRight = PlayerBonePoseSampler.sample(
                0, 0, 0, 0.0F, 1.0F, "right_item",
                name -> "right_arm".equals(name) ? legacyNativeAndSidecar : PlayerBonePoseSampler.BonePose.IDENTITY,
                PlayerBonePoseSampler.BodyTransformOrder.LEGACY_PALMORE_1_20_1);
        assertNotNull(legacyCombinedRight);
        assertEquals(-0.037655, legacyCombinedRight.x(), 1e-4);
        assertEquals(0.645056, legacyCombinedRight.y(), 1e-4);
        assertEquals(-0.208887, legacyCombinedRight.z(), 1e-4);

        // Custom PAL pivot bone resolves cleanly; completely unknown locator returns null.
        PlayerBonePoseSampler.BoneLookup withCustom = new PlayerBonePoseSampler.BoneLookup() {
            @Override
            public PlayerBonePoseSampler.BonePose bone(String canonicalBoneName) {
                return "sword_tip".equals(canonicalBoneName) ? PlayerBonePoseSampler.BonePose.IDENTITY : null;
            }

            @Override
            public float[] customPivotPx(String locatorName) {
                return "sword_tip".equals(locatorName) ? new float[] { 8.0F, 16.0F, -4.0F } : null;
            }
        };
        PlayerBonePoseSampler.WorldPose custom = PlayerBonePoseSampler.sample(0, 0, 0, 0.0F, 1.0F, "sword_tip", withCustom);
        assertNotNull(custom);
        assertEquals(-8.0 / 16.0, custom.x(), 1e-4);
        assertEquals(16.0 / 16.0, custom.y(), 1e-4);
        assertEquals(4.0 / 16.0, custom.z(), 1e-4);
        assertNull(PlayerBonePoseSampler.sample(0, 0, 0, 0.0F, 1.0F, "nonexistent_locator", withCustom));
    }

    @Test
    void kbrProbeFailsOpenForGeckoGuardAndStrictReturnsFalseWhenAbsent() {
        // When KBR is not installed on classpath, GeckoLib guard fails open while strict AnimCue probe returns false.
        assertTrue(KbrParticleProbe.isRegisteredInKbr(getClass().getClassLoader(), null, "demo:anything", null));
        assertFalse(KbrParticleProbe.isRegisteredInKbr(getClass().getClassLoader(), null, "   ", null));
        assertFalse(KbrParticleProbe.isDefinedInMolangEngine(getClass().getClassLoader(), "demo:anything"));
        assertFalse(KbrParticleProbe.isDefinedInMolangEngine(getClass().getClassLoader(), null));
    }

    @Test
    void animCueBridgeSafelyNoOpsWithoutClientLevelAndCleansUpSceneOnFinish() {
        List<FakeHandle> spawned = new ArrayList<>();
        KeyframeEffectManager.installAdapter(new FakeAdapter(Set.of("demo:animcue_hit"), spawned));

        // Non-ClientLevel (null / server) or unknown effect returns false cleanly without throwing.
        assertFalse(AnimCueKeyframeBridge.onSpawnParticleFallback(
                null, null, new net.minecraft.world.phys.Vec3(1, 2, 3), "demo:animcue_hit", null));
        assertFalse(AnimCueKeyframeBridge.onSpawnParticleFallback(
                null, null, new net.minecraft.world.phys.Vec3(1, 2, 3), "minecraft:crit", null));

        // Per-scene finish cleanup closes all effects bound to that scene controller key while leaving other scenes alive.
        Object sceneA = new Object();
        Object sceneB = new Object();
        Object actorA = new Object();
        Object actorB = new Object();
        assertTrue(LumenFxKeyframeBridge.trigger(
                null, actorA, null, sceneA, "animcue",
                "demo:animcue_hit", null, "follow=true", true, 1, 2, 3));
        assertTrue(LumenFxKeyframeBridge.trigger(
                null, actorB, null, sceneB, "animcue",
                "demo:animcue_hit", null, "follow=true", true, 4, 5, 6));
        assertEquals(2, spawned.size());

        AnimCueKeyframeBridge.onClientSceneFinish(sceneA);
        assertTrue(spawned.get(0).closed);
        assertFalse(spawned.get(1).closed);
    }

    private static final class FakeAdapter implements KeyframeEffectPlaybackAdapter {
        private final Set<String> loaded;
        private final List<FakeHandle> spawned;
        private final AtomicInteger counter = new AtomicInteger();

        FakeAdapter(Set<String> loaded, List<FakeHandle> spawned) {
            this.loaded = loaded;
            this.spawned = spawned;
        }

        @Override
        public boolean hasEffect(String canonicalEffectId) {
            return loaded.contains(canonicalEffectId);
        }

        @Override
        public boolean requiresLevel() {
            return false;
        }

        @Override
        public BoundHandle spawn(ClientLevel level, String canonicalEffectId,
                                 double x, double y, double z, long seed,
                                 KeyframeEffectParams params, boolean trackRenderSamples) {
            FakeHandle handle = new FakeHandle(counter.incrementAndGet(), x, y, z);
            spawned.add(handle);
            return handle;
        }

        @Override
        public float entityScale(Entity entity) {
            return 1.0F;
        }
    }

    private static final class FakeHandle implements KeyframeEffectPlaybackAdapter.BoundHandle {
        final int id;
        double lastX;
        double lastY;
        double lastZ;
        boolean closed;

        FakeHandle(int id, double x, double y, double z) {
            this.id = id;
            this.lastX = x;
            this.lastY = y;
            this.lastZ = z;
        }

        @Override
        public Object rawHandle() {
            return this;
        }

        @Override
        public boolean isAlive() {
            return !closed;
        }

        @Override
        public void updatePose(double x, double y, double z, float qx, float qy, float qz, float qw,
                               float scale, double[] actorPosition, double[] actorVelocityCmPerSec) {
            this.lastX = x;
            this.lastY = y;
            this.lastZ = z;
        }

        @Override
        public void setBeamEnd(double x, double y, double z) { }

        @Override
        public void close() {
            this.closed = true;
        }
    }
}
