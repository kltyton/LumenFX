package com.kltyton.lumenfx.client.keyframe;

import com.kltyton.lumenfx.api.client.keyframe.LumenFxKeyframeBridge;
import com.kltyton.lumenfx.client.compat.keyframe.KeyframeResourceLookup;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import javax.annotation.Nullable;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pure soft-compatibility runtime bridge for AnimCue ({@code interactive_animation}, 0.1.2+).
 * Holds zero compile-time references to AnimCue classes and caches reflective handles once on first use.
 *
 * <p>Covers both client-local scenes (via {@code KbrParticleCompat.spawn} fallback after vanilla/KBR priority
 * and {@code SceneEffectDispatcher.dispatch} context) and server-synced remote scenes ({@code ClientScene} with
 * {@code remote != null}, allowing dedicated servers without LumenFX to drive client LumenFX particle keyframes),
 * plus per-participant 3D bone/bend locator tracking (isolating identical locator names across participants) and
 * automatic lifecycle cleanup on {@code ClientScene.finish}.</p>
 */
public final class AnimCueKeyframeBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("LumenFX/AnimCueKeyframe");

    private record DispatchContext(Object dispatcher, @Nullable Object handle, @Nullable Object definition) { }

    private static final class SceneState {
        int remoteParticleIndex;
        final Map<String, Object> previewRoleHosts = new HashMap<>();

        Object previewHost(String roleKey) {
            return previewRoleHosts.computeIfAbsent(roleKey, ignored -> new Object());
        }
    }

    private static final ThreadLocal<ArrayDeque<DispatchContext>> DISPATCH_STACK = new ThreadLocal<>();
    private static final Map<Object, SceneState> SCENE_STATES = Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile boolean reflectionInitAttempted;
    private static volatile boolean reflectionReady;

    // SceneEffectDispatcher & SceneEffectRegistry
    private static @Nullable Field dispatcherParticleIndexField;
    private static @Nullable Method effectRegistryParticleMethod;

    // SceneDefinition & ParticleKeyframe
    private static @Nullable Method defParticlesMethod;
    private static @Nullable Method kfSecondsMethod;
    private static @Nullable Method kfRoleMethod;
    private static @Nullable Method kfEffectMethod;
    private static @Nullable Method kfLocatorMethod;
    private static @Nullable Method kfScriptMethod;

    // ClientScene & Role & ScenePacket.Start / Frame
    private static @Nullable Field sceneLevelField;
    private static @Nullable Field sceneDefinitionField;
    private static @Nullable Field sceneRemoteField;
    private static @Nullable Field sceneRolesField;
    private static @Nullable Field scenePrimaryBonesField;
    private static @Nullable Field sceneCurrentField;
    private static @Nullable Method sceneClosedMethod;
    private static @Nullable Method startSubjectIdMethod;
    private static @Nullable Method startNativePrimaryMethod;
    private static @Nullable Method startCustomParticleMethod;
    private static @Nullable Method frameSecondsMethod;
    private static @Nullable Method frameApproachingMethod;
    private static @Nullable Method framePosesMethod;
    private static @Nullable Field roleIndexField;
    private static @Nullable Method roleEntityMethod;

    // ClientActor & BoneTrack & BoneTrack.Pose & AnimationCurve
    private static @Nullable Method actorRoleMethod;
    private static @Nullable Method actorBoundMethod;
    private static @Nullable Method actorPoseMethod;
    private static @Nullable Method actorSecondsMethod;
    private static @Nullable Method actorBonesMethod;
    private static @Nullable Method posePositionMethod;
    private static @Nullable Method poseRotationMethod;
    private static @Nullable Method poseScaleMethod;
    private static @Nullable Method trackSampleMethod;
    private static @Nullable Method trackBendMethod;
    private static @Nullable Method trackHasPositionMethod;
    private static @Nullable Method trackHasRotationMethod;
    private static @Nullable Method trackHasScaleMethod;
    private static @Nullable Method trackHasBendMethod;
    private static @Nullable Method curveSampleMethod;

    // Optional GeckoLib marker interfaces (to avoid overwriting GeckoLib GeoLocator samples on GeoAnimatable entities)
    private static volatile boolean geckoMarkersChecked;
    private static @Nullable Class<?> gecko5Animatable;
    private static @Nullable Class<?> gecko41211Animatable;
    private static @Nullable Class<?> gecko41201Animatable;

    private AnimCueKeyframeBridge() { }

    /** Pushes active {@code SceneEffectDispatcher.dispatch} context so downstream spawn calls receive handle/role/locator. */
    public static void enterDispatcher(@Nullable Object dispatcher, @Nullable Object handle, @Nullable Object definition) {
        if (dispatcher == null) return;
        ArrayDeque<DispatchContext> stack = DISPATCH_STACK.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            DISPATCH_STACK.set(stack);
        }
        stack.push(new DispatchContext(dispatcher, handle, definition));
    }

    /** Pops active {@code SceneEffectDispatcher.dispatch} context. */
    public static void exitDispatcher(@Nullable Object dispatcher) {
        ArrayDeque<DispatchContext> stack = DISPATCH_STACK.get();
        if (stack != null && !stack.isEmpty() && stack.peek().dispatcher() == dispatcher) {
            stack.pop();
            if (stack.isEmpty()) {
                DISPATCH_STACK.remove();
            }
        }
    }

    /**
     * Fallback invoked at {@code RETURN} of AnimCue's {@code KbrParticleCompat.spawn} when neither vanilla
     * {@code ParticleType} nor KBR handled the particle.
     *
     * @return {@code true} if LumenFX claimed and handled this particle on the client; {@code false} otherwise.
     */
    public static boolean onSpawnParticleFallback(@Nullable Level level, @Nullable Entity sourceEntity,
                                                  @Nullable Vec3 position, @Nullable String effect,
                                                  @Nullable String script) {
        if (!(level instanceof ClientLevel clientLevel) || position == null || effect == null || effect.isBlank()) {
            return false;
        }
        if (!KeyframeEffectManager.isManagedEffect(effect, script)) {
            return false;
        }

        Object controllerKey = null;
        String role = null;
        String locator = null;

        ArrayDeque<DispatchContext> stack = DISPATCH_STACK.get();
        if (stack != null && !stack.isEmpty()) {
            DispatchContext ctx = stack.peek();
            controllerKey = ctx.handle();
            if (ensureReflection(ctx.dispatcher().getClass().getClassLoader())) {
                Object activeKf = resolveCurrentDispatcherKeyframe(ctx);
                if (activeKf != null) {
                    role = invokeString(kfRoleMethod, activeKf);
                    locator = invokeString(kfLocatorMethod, activeKf);
                }
            }
        }

        Object host = resolveHost(sourceEntity, controllerKey, role);
        String normLocator = (locator != null && !locator.isBlank()) ? locator.trim() : null;
        // If no entity/scene can supply locator samples, spawn directly at the provided position instead of pending forever
        if (normLocator != null && sourceEntity == null && controllerKey == null) {
            normLocator = null;
        }

        boolean handled = KeyframeEffectManager.handleKeyframe(
                clientLevel, host, sourceEntity, controllerKey, "animcue",
                effect, normLocator, script, null,
                position.x, position.y, position.z, true);

        if (handled && normLocator != null) {
            publishImmediateFallbackIfNoActorSamples(controllerKey, role, host, sourceEntity, position, normLocator);
        }
        return handled;
    }

    /**
     * When {@code role == null} and {@code primaryBones} is empty (or {@code controllerKey == null}),
     * {@code ClientScene.actor(...)} returns {@code null} for the primary subject; for non-GeckoLib entities,
     * immediately sample {@link PlayerBonePoseSampler} (or actor anchor) so the effect does not hang pending.
     */
    private static void publishImmediateFallbackIfNoActorSamples(@Nullable Object clientScene,
                                                                 @Nullable String role,
                                                                 Object host,
                                                                 @Nullable Entity entity,
                                                                 Vec3 fallbackPos,
                                                                 String locatorName) {
        if (isGeckoAnimatable(entity)) {
            return;
        }
        boolean needsImmediate = (clientScene == null);
        if (!needsImmediate && role == null && scenePrimaryBonesField != null) {
            try {
                Object primaryBones = scenePrimaryBonesField.get(clientScene);
                if (primaryBones instanceof Map<?, ?> map && map.isEmpty()) {
                    needsImmediate = true;
                }
            } catch (Throwable ignored) { }
        }
        if (!needsImmediate) {
            return;
        }
        float yaw = entity != null ? entity.getYRot() : 0.0F;
        float scale = resolveEntityScale(entity);
        PlayerBonePoseSampler.WorldPose pose = PlayerBonePoseSampler.sample(
                fallbackPos.x, fallbackPos.y, fallbackPos.z, yaw, scale, locatorName,
                name -> PlayerBonePoseSampler.BonePose.IDENTITY);
        Vec3 actorPos = entity != null ? entity.position() : fallbackPos;
        if (pose != null) {
            KeyframeEffectManager.onLocatorSample(
                    host, entity, locatorName,
                    pose.x(), pose.y(), pose.z(),
                    pose.qx(), pose.qy(), pose.qz(), pose.qw(),
                    actorPos,
                    KeyframeEffectManager.LocatorSource.ANIMATION_BONE);
        } else {
            Quaternionf rot = new Quaternionf().rotateY((float) Math.toRadians(180.0F - yaw));
            KeyframeEffectManager.onLocatorSample(
                    host, entity, locatorName,
                    fallbackPos.x, fallbackPos.y, fallbackPos.z,
                    rot.x, rot.y, rot.z, rot.w,
                    actorPos,
                    KeyframeEffectManager.LocatorSource.FALLBACK);
        }
    }

    /**
     * Advances LumenFX-only particle keyframes for server-synced {@code ClientScene} instances ({@code remote != null})
     * so dedicated servers without LumenFX still drive client LumenFX keyframes in sync with the scene while
     * strictly preserving AnimCue's {@code Vanilla ParticleType -> KBR -> LumenFX} routing priority.
     */
    public static void onClientSceneTick(@Nullable Object clientScene) {
        if (clientScene == null) return;
        if (!ensureReflection(clientScene.getClass().getClassLoader())) return;
        try {
            if (Boolean.TRUE.equals(sceneClosedMethod.invoke(clientScene))) return;
            Object remote = sceneRemoteField.get(clientScene);
            if (remote == null) return; // Local scenes already dispatch through SceneEffectDispatcher -> KbrParticleCompat
            if (startCustomParticleMethod != null
                    && Boolean.TRUE.equals(startCustomParticleMethod.invoke(remote))) {
                return; // Forward-compatible hook: server owns particle dispatch via custom PlaybackOptions.particle()
            }
            Object current = sceneCurrentField.get(clientScene);
            if (current == null || Boolean.TRUE.equals(frameApproachingMethod.invoke(current))) return;

            Object levelObj = sceneLevelField.get(clientScene);
            if (!(levelObj instanceof ClientLevel clientLevel)) return;
            Object definition = sceneDefinitionField.get(clientScene);
            if (definition == null) return;

            List<?> particles = (List<?>) defParticlesMethod.invoke(definition);
            if (particles == null || particles.isEmpty()) return;

            double seconds = ((Number) frameSecondsMethod.invoke(current)).doubleValue();
            boolean nativePrimary = Boolean.TRUE.equals(startNativePrimaryMethod.invoke(remote));
            SceneState state = SCENE_STATES.computeIfAbsent(clientScene, ignored -> new SceneState());
            ClassLoader sceneLoader = clientScene.getClass().getClassLoader();

            while (state.remoteParticleIndex < particles.size()) {
                Object kf = particles.get(state.remoteParticleIndex);
                double kfSec = ((Number) kfSecondsMethod.invoke(kf)).doubleValue();
                if (kfSec > seconds) break;
                state.remoteParticleIndex++;

                String role = invokeString(kfRoleMethod, kf);
                if (nativePrimary && role == null) continue;

                String effect = invokeString(kfEffectMethod, kf);
                String script = invokeString(kfScriptMethod, kf);
                LumenFxKeyframeBridge.ParsedEffectId parsed = LumenFxKeyframeBridge.parseEffectId(effect);
                if (parsed == null || !KeyframeEffectManager.isManagedEffect(effect, script)) continue;
                if (!parsed.explicitLumenFx() && isHandledByVanillaOrKbr(sceneLoader, effect)) {
                    continue;
                }

                String locator = invokeString(kfLocatorMethod, kf);
                Entity entity = resolveRemoteEntity(clientScene, clientLevel, remote, role);
                Vec3 pos = resolveRemotePosition(clientScene, current, entity, role);
                if (pos == null) continue;

                Object host = resolveHost(entity, clientScene, role);
                String normLocator = (locator != null && !locator.isBlank()) ? locator.trim() : null;
                boolean handled = KeyframeEffectManager.handleKeyframe(
                        clientLevel, host, entity, clientScene, "animcue",
                        effect, normLocator, script, null,
                        pos.x, pos.y, pos.z, true, kf);
                if (handled && normLocator != null) {
                    publishImmediateFallbackIfNoActorSamples(clientScene, role, host, entity, pos, normLocator);
                }
            }
        } catch (Throwable error) {
            LOGGER.debug("Failed to advance remote AnimCue LumenFX particle keyframes", error);
        }
    }

    /**
     * Checks whether {@code rawEffect} is owned by a vanilla/modded {@code ParticleType} or a registered client KBR
     * particle, matching AnimCue's {@code SceneEffects.spawnParticle -> KbrParticleCompat.spawn} priority.
     */
    static boolean isHandledByVanillaOrKbr(@Nullable ClassLoader loader, @Nullable String rawEffect) {
        if (rawEffect == null || rawEffect.isBlank()) return false;
        String trimmed = rawEffect.trim();
        if (effectRegistryParticleMethod != null) {
            try {
                if (effectRegistryParticleMethod.invoke(null, trimmed) != null) {
                    return true;
                }
            } catch (Throwable ignored) { }
        }
        if (KeyframeResourceLookup.hasVanillaParticleType(trimmed)) {
            return true;
        }
        if (trimmed.indexOf(':') > 0) {
            Object parsedId = KeyframeResourceLookup.tryParseId(trimmed);
            if (parsedId != null && KbrParticleProbe.isDefinedInMolangEngine(loader, parsedId)) {
                return true;
            }
        }
        return false;
    }

    /** Cleans up all LumenFX effects bound to an AnimCue {@code ClientScene} when it finishes or is cancelled. */
    public static void onClientSceneFinish(@Nullable Object clientScene) {
        if (clientScene == null) return;
        KeyframeEffectManager.stopForControllerKey(clientScene);
        SceneState removed = SCENE_STATES.remove(clientScene);
        if (removed != null) {
            for (Object previewHost : removed.previewRoleHosts.values()) {
                KeyframeEffectManager.stopForHost(previewHost);
            }
        }
    }

    /** Samples 3D locator/bone world poses for a single AnimCue {@code ClientActor}. */
    public static void onClientActorSampled(@Nullable Object clientScene, @Nullable Object clientActor) {
        if (clientScene == null || clientActor == null) return;
        if (!ensureReflection(clientScene.getClass().getClassLoader())) return;
        try {
            String role = invokeString(actorRoleMethod, clientActor);
            Object boundObj = actorBoundMethod.invoke(clientActor);
            Entity entity = boundObj instanceof Entity e ? e : null;
            // GeckoLib entities own their rendered model skeleton & GeoLocators via Gecko4/Gecko5 bridges;
            // never synthesize humanoid player-bone fallback coordinates over a GeoAnimatable entity.
            if (isGeckoAnimatable(entity)) {
                return;
            }
            Object host = resolveHost(entity, clientScene, role);
            if (!KeyframeEffectManager.hasTrackedLocators(host)) {
                return;
            }
            Set<String> tracked = KeyframeEffectManager.trackedLocators(host);
            if (tracked.isEmpty()) return;

            Object poseObj = actorPoseMethod.invoke(clientActor);
            if (poseObj == null) return;
            Vec3 anchorPos = (Vec3) posePositionMethod.invoke(poseObj);
            Vec3 anchorRotDeg = (Vec3) poseRotationMethod.invoke(poseObj);
            Vec3 anchorScale = (Vec3) poseScaleMethod.invoke(poseObj);
            if (anchorPos == null || anchorRotDeg == null) return;

            double seconds = ((Number) actorSecondsMethod.invoke(clientActor)).doubleValue();
            Map<?, ?> bones = (Map<?, ?>) actorBonesMethod.invoke(clientActor);

            float entityScale = resolveEntityScale(entity);
            if (anchorScale != null && Double.isFinite(anchorScale.y) && anchorScale.y > 0.0) {
                entityScale *= (float) anchorScale.y;
            }
            float yawDeg = (float) anchorRotDeg.y;
            Vec3 actorWorldPos = entity != null ? entity.position() : anchorPos;

            PlayerBonePoseSampler.BoneLookup lookup = buildActorBoneLookup(bones, seconds);

            for (String locatorName : tracked) {
                PlayerBonePoseSampler.WorldPose sampled = PlayerBonePoseSampler.sample(
                        anchorPos.x, anchorPos.y, anchorPos.z,
                        yawDeg, entityScale, locatorName, lookup);
                if (sampled != null) {
                    KeyframeEffectManager.onLocatorSample(
                            host, entity, locatorName,
                            sampled.x(), sampled.y(), sampled.z(),
                            sampled.qx(), sampled.qy(), sampled.qz(), sampled.qw(),
                            actorWorldPos,
                            KeyframeEffectManager.LocatorSource.ANIMATION_BONE);
                } else {
                    // Non-GeckoLib entity/preview with an unmapped locator name: fall back to actor anchor pose
                    Quaternionf rot = new Quaternionf().rotateY((float) Math.toRadians(180.0F - yawDeg));
                    KeyframeEffectManager.onLocatorSample(
                            host, entity, locatorName,
                            anchorPos.x, anchorPos.y, anchorPos.z,
                            rot.x, rot.y, rot.z, rot.w,
                            actorWorldPos,
                            KeyframeEffectManager.LocatorSource.FALLBACK);
                }
            }
        } catch (Throwable error) {
            LOGGER.debug("Failed to sample AnimCue actor locator pose", error);
        }
    }

    /** Samples 3D locator/bone poses for all extra/preview {@code ClientActor}s collected by {@code ClientScene.collectExtras}. */
    public static void onClientExtrasCollected(@Nullable Object clientScene, @Nullable List<?> actors) {
        if (clientScene == null || actors == null || actors.isEmpty()) return;
        for (Object actor : actors) {
            onClientActorSampled(clientScene, actor);
        }
    }

    private static PlayerBonePoseSampler.BoneLookup buildActorBoneLookup(@Nullable Map<?, ?> bones, double seconds) {
        return canonicalBoneName -> {
            if (bones == null || bones.isEmpty()) return null;
            Object track = resolveAnimCueBoneTrack(bones, canonicalBoneName);
            if (track == null) return null;
            return readAnimCueBonePose(track, seconds);
        };
    }

    /**
     * Matches AnimCue's {@code PalScenePose} bone lookup semantics: only treat {@code body} as whole-avatar root
     * when the animation distinguishes {@code torso}; otherwise map {@code body} onto {@code torso}.
     */
    private static @Nullable Object resolveAnimCueBoneTrack(Map<?, ?> bones, String canonicalName) {
        Object track = switch (canonicalName) {
            case "body" -> bones.containsKey("torso") ? bones.get("body") : null;
            case "torso" -> bones.containsKey("torso") ? bones.get("torso") : bones.get("body");
            default -> bones.get(canonicalName);
        };
        if (track == null) {
            String legacy = PlayerBonePoseSampler.toLegacyBoneName(canonicalName);
            if (!legacy.equals(canonicalName)) {
                track = bones.get(legacy);
            }
        }
        return track;
    }

    private static PlayerBonePoseSampler.BonePose readAnimCueBonePose(Object track, double seconds) {
        try {
            Object pose = trackSampleMethod.invoke(track, seconds);
            Vec3 pos = Boolean.TRUE.equals(trackHasPositionMethod.invoke(track))
                    ? (Vec3) posePositionMethod.invoke(pose) : Vec3.ZERO;
            Vec3 rotDeg = Boolean.TRUE.equals(trackHasRotationMethod.invoke(track))
                    ? (Vec3) poseRotationMethod.invoke(pose) : Vec3.ZERO;
            Vec3 scale = Boolean.TRUE.equals(trackHasScaleMethod.invoke(track))
                    ? (Vec3) poseScaleMethod.invoke(pose) : new Vec3(1.0, 1.0, 1.0);

            float bendX = 0.0F, bendY = 0.0F, bendZ = 0.0F;
            if (Boolean.TRUE.equals(trackHasBendMethod.invoke(track))) {
                Object bendCurve = trackBendMethod.invoke(track);
                Vec3 bendDeg = (Vec3) curveSampleMethod.invoke(bendCurve, seconds);
                if (bendDeg != null) {
                    bendX = (float) Math.toRadians(bendDeg.x);
                    bendY = (float) Math.toRadians(bendDeg.y);
                    bendZ = (float) Math.toRadians(bendDeg.z);
                }
            }

            return new PlayerBonePoseSampler.BonePose(
                    (float) pos.x, (float) pos.y, (float) pos.z,
                    (float) Math.toRadians(rotDeg.x), (float) Math.toRadians(rotDeg.y), (float) Math.toRadians(rotDeg.z),
                    (float) scale.x, (float) scale.y, (float) scale.z,
                    bendX, bendY, bendZ,
                    0.0F, 0.0F, 0.0F,
                    1.0F, 1.0F, 1.0F,
                    false);
        } catch (Throwable ignored) {
            return PlayerBonePoseSampler.BonePose.IDENTITY;
        }
    }

    private static @Nullable Object resolveCurrentDispatcherKeyframe(DispatchContext ctx) {
        if (ctx.definition() == null || dispatcherParticleIndexField == null || defParticlesMethod == null) {
            return null;
        }
        try {
            int idx = dispatcherParticleIndexField.getInt(ctx.dispatcher()) - 1;
            List<?> particles = (List<?>) defParticlesMethod.invoke(ctx.definition());
            if (particles != null && idx >= 0 && idx < particles.size()) {
                return particles.get(idx);
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static Object resolveHost(@Nullable Entity entity, @Nullable Object sceneHandle, @Nullable String role) {
        if (entity != null) return entity;
        if (sceneHandle != null) {
            SceneState state = SCENE_STATES.computeIfAbsent(sceneHandle, ignored -> new SceneState());
            return state.previewHost(role != null ? role : "primary");
        }
        return new Object();
    }

    private static @Nullable Entity resolveRemoteEntity(Object clientScene, ClientLevel level,
                                                        Object remoteStart, @Nullable String role) {
        try {
            if (role == null) {
                int subjectId = ((Number) startSubjectIdMethod.invoke(remoteStart)).intValue();
                return subjectId < 0 ? null : level.getEntity(subjectId);
            }
            Map<?, ?> roles = (Map<?, ?>) sceneRolesField.get(clientScene);
            Object roleObj = roles != null ? roles.get(role) : null;
            if (roleObj == null) return null;
            ensureRoleReflection(roleObj.getClass());
            Object entityObj = roleEntityMethod != null ? roleEntityMethod.invoke(roleObj) : null;
            return entityObj instanceof Entity e ? e : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static @Nullable Vec3 resolveRemotePosition(Object clientScene, Object currentFrame,
                                                        @Nullable Entity entity, @Nullable String role) {
        try {
            if (role != null) {
                Map<?, ?> roles = (Map<?, ?>) sceneRolesField.get(clientScene);
                Object roleObj = roles != null ? roles.get(role) : null;
                if (roleObj != null) {
                    ensureRoleReflection(roleObj.getClass());
                    int index = roleIndexField != null ? roleIndexField.getInt(roleObj) : -1;
                    List<?> poses = (List<?>) framePosesMethod.invoke(currentFrame);
                    if (poses != null && index >= 0 && index < poses.size()) {
                        Vec3 pos = (Vec3) posePositionMethod.invoke(poses.get(index));
                        if (pos != null) return pos;
                    }
                }
            }
        } catch (Throwable ignored) { }
        return entity != null ? entity.position() : null;
    }

    private static boolean isGeckoAnimatable(@Nullable Entity entity) {
        if (entity == null) return false;
        if (!geckoMarkersChecked) {
            geckoMarkersChecked = true;
            ClassLoader loader = entity.getClass().getClassLoader();
            gecko5Animatable = tryLoadClass("com.geckolib.animatable.GeoAnimatable", loader);
            gecko41211Animatable = tryLoadClass("software.bernie.geckolib.animatable.GeoAnimatable", loader);
            gecko41201Animatable = tryLoadClass("software.bernie.geckolib.core.animatable.GeoAnimatable", loader);
        }
        return (gecko5Animatable != null && gecko5Animatable.isInstance(entity))
                || (gecko41211Animatable != null && gecko41211Animatable.isInstance(entity))
                || (gecko41201Animatable != null && gecko41201Animatable.isInstance(entity));
    }

    private static @Nullable Class<?> tryLoadClass(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static float resolveEntityScale(@Nullable Entity entity) {
        KeyframeEffectPlaybackAdapter adapter = KeyframeEffectManager.adapter();
        if (adapter != null) {
            float s = adapter.entityScale(entity);
            if (Float.isFinite(s) && s > 0.0F) return s;
        }
        return 1.0F;
    }

    private static @Nullable String invokeString(@Nullable Method method, Object target) {
        if (method == null) return null;
        try {
            Object v = method.invoke(target);
            return v instanceof String s ? s : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void ensureRoleReflection(Class<?> roleCls) {
        if (roleIndexField != null && roleEntityMethod != null) return;
        try {
            Field f = roleCls.getDeclaredField("index");
            f.setAccessible(true);
            roleIndexField = f;
            Method m = roleCls.getDeclaredMethod("entity");
            m.setAccessible(true);
            roleEntityMethod = m;
        } catch (Throwable ignored) { }
    }

    private static boolean ensureReflection(ClassLoader loader) {
        if (reflectionReady) return true;
        if (reflectionInitAttempted) return false;
        synchronized (AnimCueKeyframeBridge.class) {
            if (reflectionReady) return true;
            if (reflectionInitAttempted) return false;
            reflectionInitAttempted = true;
            try {
                Class<?> dispatcherCls = Class.forName(
                        "com.kltyton.interactiveanimation.scene.playback.SceneEffectDispatcher", false, loader);
                dispatcherParticleIndexField = dispatcherCls.getDeclaredField("particleIndex");
                dispatcherParticleIndexField.setAccessible(true);

                try {
                    Class<?> registryCls = Class.forName(
                            "com.kltyton.interactiveanimation.scene.playback.SceneEffectRegistry", false, loader);
                    effectRegistryParticleMethod = registryCls.getMethod("particle", String.class);
                } catch (Throwable ignored) { }

                Class<?> defCls = Class.forName(
                        "com.kltyton.interactiveanimation.scene.asset.SceneDefinition", false, loader);
                defParticlesMethod = defCls.getMethod("particles");

                Class<?> kfCls = Class.forName(
                        "com.kltyton.interactiveanimation.scene.asset.SceneDefinition$ParticleKeyframe", false, loader);
                kfSecondsMethod = kfCls.getMethod("seconds");
                kfRoleMethod = kfCls.getMethod("role");
                kfEffectMethod = kfCls.getMethod("effect");
                kfLocatorMethod = kfCls.getMethod("locator");
                kfScriptMethod = kfCls.getMethod("script");

                Class<?> sceneCls = Class.forName(
                        "com.kltyton.interactiveanimation.client.playback.ClientScene", false, loader);
                sceneLevelField = sceneCls.getDeclaredField("level");
                sceneLevelField.setAccessible(true);
                sceneDefinitionField = sceneCls.getDeclaredField("definition");
                sceneDefinitionField.setAccessible(true);
                sceneRemoteField = sceneCls.getDeclaredField("remote");
                sceneRemoteField.setAccessible(true);
                sceneRolesField = sceneCls.getDeclaredField("roles");
                sceneRolesField.setAccessible(true);
                try {
                    scenePrimaryBonesField = sceneCls.getDeclaredField("primaryBones");
                    scenePrimaryBonesField.setAccessible(true);
                } catch (NoSuchFieldException ignored) { }
                sceneCurrentField = sceneCls.getDeclaredField("current");
                sceneCurrentField.setAccessible(true);
                sceneClosedMethod = sceneCls.getDeclaredMethod("closed");
                sceneClosedMethod.setAccessible(true);

                Class<?> startCls = Class.forName(
                        "com.kltyton.interactiveanimation.scene.network.ScenePacket$Start", false, loader);
                startSubjectIdMethod = startCls.getMethod("subjectId");
                startNativePrimaryMethod = startCls.getMethod("nativePrimary");
                try {
                    startCustomParticleMethod = startCls.getMethod("customParticle");
                } catch (NoSuchMethodException ignored) { }

                Class<?> frameCls = Class.forName(
                        "com.kltyton.interactiveanimation.scene.network.ScenePacket$Frame", false, loader);
                frameSecondsMethod = frameCls.getMethod("seconds");
                frameApproachingMethod = frameCls.getMethod("approaching");
                framePosesMethod = frameCls.getMethod("poses");

                Class<?> actorCls = Class.forName(
                        "com.kltyton.interactiveanimation.client.playback.ClientActor", false, loader);
                actorRoleMethod = actorCls.getMethod("role");
                actorBoundMethod = actorCls.getMethod("bound");
                actorPoseMethod = actorCls.getMethod("pose");
                actorSecondsMethod = actorCls.getMethod("seconds");
                actorBonesMethod = actorCls.getMethod("bones");

                Class<?> poseCls = Class.forName(
                        "com.kltyton.interactiveanimation.scene.asset.BoneTrack$Pose", false, loader);
                posePositionMethod = poseCls.getMethod("position");
                poseRotationMethod = poseCls.getMethod("rotation");
                poseScaleMethod = poseCls.getMethod("scale");

                Class<?> trackCls = Class.forName(
                        "com.kltyton.interactiveanimation.scene.asset.BoneTrack", false, loader);
                trackSampleMethod = trackCls.getMethod("sample", double.class);
                trackBendMethod = trackCls.getMethod("bend");
                trackHasPositionMethod = trackCls.getMethod("hasPosition");
                trackHasRotationMethod = trackCls.getMethod("hasRotation");
                trackHasScaleMethod = trackCls.getMethod("hasScale");
                trackHasBendMethod = trackCls.getMethod("hasBend");

                Class<?> curveCls = Class.forName(
                        "com.kltyton.interactiveanimation.scene.asset.AnimationCurve", false, loader);
                curveSampleMethod = curveCls.getMethod("sample", double.class);

                reflectionReady = true;
                return true;
            } catch (Throwable error) {
                LOGGER.warn("AnimCue reflection bridge unavailable: {}", error.toString());
                return false;
            }
        }
    }
}
