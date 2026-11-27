package com.kltyton.lumenfx.client.keyframe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pure soft-compatibility runtime bridge for Player Animation Library (PAL 1.1.x on 1.21.1, PAL 1.2.x on 26.x)
 * and {@code PlayerAnimationLibraryMoreRotation} (PALMore, including 1.20.1 {@code PalMoreKeyframePlayer}).
 *
 * <p>Holds zero compile-time references to PAL, {@code playerAnimator}, {@code bendable-cuboids}, or PALMore.
 * Intentionally preserves PAL / PALMore's native callback-only particle keyframe model (no automatic spawning);
 * when a mod triggers LumenFX inside a PAL / PALMore particle callback via {@code LumenFxKeyframeBridge.trigger(...)},
 * this bridge automatically samples player bones (including native X-bend and PALMore 3-axis bend/position/scale)
 * every render frame and cleans up effects when the controller resets or stops.</p>
 */
public final class PalKeyframeBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("LumenFX/PalKeyframe");

    // ---- Modern PAL (com.zigythebird.playeranimcore.animation.AnimationController) ----
    private static volatile boolean palInitAttempted;
    private static volatile boolean palReady;
    private static @Nullable Field palBonesField;
    private static @Nullable Field palActiveBonesField;
    private static @Nullable Field palPivotBonesField;
    private static @Nullable Method palGetBonePositionMethod;
    private static @Nullable Method palDataPartialTickMethod;
    private static @Nullable Method palGetAvatarMethod;
    private static @Nullable Method palGetPlayerMethod;
    private static @Nullable Field palBonePosVecField;
    private static @Nullable Field palBoneRotVecField;
    private static @Nullable Field palBoneScaleVecField;
    private static @Nullable Field palBonePosXField;
    private static @Nullable Field palBonePosYField;
    private static @Nullable Field palBonePosZField;
    private static @Nullable Field palBoneRotXField;
    private static @Nullable Field palBoneRotYField;
    private static @Nullable Field palBoneRotZField;
    private static @Nullable Field palBoneScaleXField;
    private static @Nullable Field palBoneScaleYField;
    private static @Nullable Field palBoneScaleZField;
    private static @Nullable Field palBoneBendField;
    private static @Nullable Method palVec3fXMethod;
    private static @Nullable Method palVec3fYMethod;
    private static @Nullable Method palVec3fZMethod;

    // Optional PALMore PalMoreBendHolder methods (resolved lazily if PALMore is present)
    private static volatile boolean palMoreBendChecked;
    private static @Nullable Class<?> palMoreBendHolderClass;
    private static @Nullable Method pmBendX;
    private static @Nullable Method pmBendY;
    private static @Nullable Method pmBendZ;
    private static @Nullable Method pmHasBendVector;
    private static @Nullable Method pmBendPosX;
    private static @Nullable Method pmBendPosY;
    private static @Nullable Method pmBendPosZ;
    private static @Nullable Method pmBendScaleX;
    private static @Nullable Method pmBendScaleY;
    private static @Nullable Method pmBendScaleZ;
    private static @Nullable Method pmHasBendTransform;

    // ---- Legacy 1.20.1 PALMore (PalMoreKeyframePlayer + playerAnimator) ----
    private static volatile boolean legacyInitAttempted;
    private static volatile boolean legacyReady;
    private static @Nullable Field legacyPlayerField;
    private static @Nullable Field legacyControllerField;
    private static @Nullable Field legacyAnimationIdField;
    private static @Nullable Method legacyIsActiveMethod;
    private static @Nullable Method legacyGetCurrentTickMethod;
    private static @Nullable Method legacyGetDataMethod;
    private static @Nullable Method legacyGet3DTransformMethod;
    private static @Nullable Object legacyTransformPosition;
    private static @Nullable Object legacyTransformRotation;
    private static @Nullable Object legacyTransformScale;
    private static @Nullable Object legacyTransformBend;
    private static @Nullable Class<?> legacyVec3fClass;
    private static @Nullable Constructor<?> legacyVec3fCtor;
    private static @Nullable Method legacyVec3fGetX;
    private static @Nullable Method legacyVec3fGetY;
    private static @Nullable Method legacyVec3fGetZ;
    private static @Nullable Method legacyGetExtrasMethod;
    private static @Nullable Method legacySampleBendMethod;
    private static @Nullable Method legacySampleScaleMethod;
    private static @Nullable Method legacyBendHasActive;
    private static @Nullable Method legacyBendX;
    private static @Nullable Method legacyBendY;
    private static @Nullable Method legacyBendZ;
    private static @Nullable Method legacyBendPosX;
    private static @Nullable Method legacyBendPosY;
    private static @Nullable Method legacyBendPosZ;
    private static @Nullable Method legacyBendScaleX;
    private static @Nullable Method legacyBendScaleY;
    private static @Nullable Method legacyBendScaleZ;
    private static @Nullable Method legacyBendHasTransform;

    private PalKeyframeBridge() { }

    // =========================================================================
    // Modern PAL (1.21.1 & 26.1.2 / 26.2 / 26.3)
    // =========================================================================

    /** Cleans up any LumenFX keyframe effects bound to a PAL {@code AnimationController} on reset/stop. */
    public static void onPalControllerReset(@Nullable Object controller) {
        if (controller == null) return;
        KeyframeEffectManager.stopForControllerKey(controller);
    }

    /**
     * Samples tracked locator/bone world poses (including native X-bend and PALMore 3-axis bend/position/scale)
     * at the end of {@code AnimationController.process(AnimationData)} when the animated player has active
     * LumenFX locator bindings.
     */
    public static void onPalControllerProcessed(@Nullable Object controller, @Nullable Object animationData) {
        if (controller == null) return;
        if (!ensurePalReflection(controller.getClass())) return;

        Entity entity = resolvePalEntity(controller);
        boolean trackEntity = entity != null && KeyframeEffectManager.hasTrackedLocators(entity);
        boolean trackController = KeyframeEffectManager.hasTrackedLocators(controller);
        if (!trackEntity && !trackController) {
            return;
        }

        try {
            float partialTick = readPartialTick(animationData);
            double ex = entity != null ? Mth.lerp(partialTick, entity.xo, entity.getX()) : 0.0;
            double ey = entity != null ? Mth.lerp(partialTick, entity.yo, entity.getY()) : 0.0;
            double ez = entity != null ? Mth.lerp(partialTick, entity.zo, entity.getZ()) : 0.0;
            float bodyYaw = resolveBodyYaw(entity, partialTick);
            float entityScale = resolveEntityScale(entity);
            Vec3 actorPos = entity != null ? new Vec3(ex, ey, ez) : null;

            Map<?, ?> activeBones = palActiveBonesField != null ? (Map<?, ?>) palActiveBonesField.get(controller) : null;
            Map<?, ?> allBones = palBonesField != null ? (Map<?, ?>) palBonesField.get(controller) : null;
            Map<?, ?> pivotBones = palPivotBonesField != null ? (Map<?, ?>) palPivotBonesField.get(controller) : null;

            PlayerBonePoseSampler.BoneLookup lookup = new PlayerBonePoseSampler.BoneLookup() {
                @Override
                public @Nullable PlayerBonePoseSampler.BonePose bone(String canonicalBoneName) {
                    Object raw = lookupPalBone(activeBones, allBones, pivotBones, canonicalBoneName);
                    return raw != null ? readPalBonePose(raw) : null;
                }

                @Override
                public @Nullable float[] customPivotPx(String locatorName) {
                    return readPalBonePivotPx(controller, locatorName);
                }
            };

            PlayerBonePoseSampler.BodyTransformOrder bodyOrder = palBonePosVecField != null
                    ? PlayerBonePoseSampler.BodyTransformOrder.PAL_1_2
                    : PlayerBonePoseSampler.BodyTransformOrder.PAL_1_1;

            if (trackEntity && entity != null) {
                publishSamplesForHost(entity, entity, ex, ey, ez, bodyYaw, entityScale, actorPos, lookup, bodyOrder);
            }
            if (trackController) {
                publishSamplesForHost(controller, entity, ex, ey, ez, bodyYaw, entityScale, actorPos, lookup, bodyOrder);
            }
        } catch (Throwable error) {
            LOGGER.debug("Failed to sample PAL player bone poses", error);
        }
    }

    // =========================================================================
    // Legacy 1.20.1 PALMore (PalMoreKeyframePlayer + playerAnimator)
    // =========================================================================

    /** Cleans up LumenFX effects when a 1.20.1 {@code PalMoreKeyframePlayer} stops or finishes. */
    public static void onLegacyPalMoreTick(@Nullable Object keyframePlayer) {
        if (keyframePlayer == null) return;
        if (!ensureLegacyReflection(keyframePlayer.getClass())) return;
        try {
            boolean active = legacyIsActiveMethod != null && Boolean.TRUE.equals(legacyIsActiveMethod.invoke(keyframePlayer));
            if (!active) {
                KeyframeEffectManager.stopForControllerKey(keyframePlayer);
                Object playerObj = legacyPlayerField != null ? legacyPlayerField.get(keyframePlayer) : null;
                if (playerObj != null) {
                    Object ctrl = legacyControllerField != null ? legacyControllerField.get(keyframePlayer) : null;
                    Object animId = legacyAnimationIdField != null ? legacyAnimationIdField.get(keyframePlayer) : null;
                    if (ctrl != null) KeyframeEffectManager.stopForController(playerObj, ctrl);
                    if (animId != null) KeyframeEffectManager.stopForController(playerObj, animId);
                }
                return;
            }
            // Fallback tick sampling in case setupAnim wasn't invoked (e.g., off-screen or first tick)
            sampleLegacyPalMorePoses(keyframePlayer, 0.0F);
        } catch (Throwable error) {
            LOGGER.debug("Failed to process 1.20.1 PalMoreKeyframePlayer tick", error);
        }
    }

    /** Per-frame smooth bone + bend world-pose sampling for 1.20.1 {@code PalMoreKeyframePlayer}. */
    public static void onLegacyPalMoreSetupAnim(@Nullable Object keyframePlayer, float tickDelta) {
        if (keyframePlayer == null) return;
        if (!"com.kltyton.playeranimationlibrarymorerotation.client.PalMoreKeyframePlayer"
                .equals(keyframePlayer.getClass().getName())) {
            return;
        }
        if (!ensureLegacyReflection(keyframePlayer.getClass())) return;
        sampleLegacyPalMorePoses(keyframePlayer, tickDelta);
    }

    private static void sampleLegacyPalMorePoses(Object keyframePlayer, float tickDelta) {
        try {
            if (legacyPlayerField == null) return;
            Object playerObj = legacyPlayerField.get(keyframePlayer);
            Entity entity = playerObj instanceof Entity e ? e : null;
            boolean trackEntity = entity != null && KeyframeEffectManager.hasTrackedLocators(entity);
            boolean trackController = KeyframeEffectManager.hasTrackedLocators(keyframePlayer);
            if (!trackEntity && !trackController) {
                return;
            }

            float clampedDelta = Float.isFinite(tickDelta) ? Math.max(0.0F, Math.min(1.0F, tickDelta)) : 0.0F;
            double ex = entity != null ? Mth.lerp(clampedDelta, entity.xo, entity.getX()) : 0.0;
            double ey = entity != null ? Mth.lerp(clampedDelta, entity.yo, entity.getY()) : 0.0;
            double ez = entity != null ? Mth.lerp(clampedDelta, entity.zo, entity.getZ()) : 0.0;
            float bodyYaw = resolveBodyYaw(entity, clampedDelta);
            float entityScale = resolveEntityScale(entity);
            Vec3 actorPos = entity != null ? new Vec3(ex, ey, ez) : null;

            float currentTick = legacyGetCurrentTickMethod != null
                    ? ((Number) legacyGetCurrentTickMethod.invoke(keyframePlayer)).floatValue() + clampedDelta
                    : 0.0F;
            Object extras = resolveLegacyExtras(keyframePlayer);

            PlayerBonePoseSampler.BoneLookup lookup = canonicalBoneName ->
                    readLegacyBonePose(keyframePlayer, extras, canonicalBoneName, clampedDelta, currentTick);

            if (trackEntity && entity != null) {
                publishSamplesForHost(entity, entity, ex, ey, ez, bodyYaw, entityScale, actorPos, lookup,
                        PlayerBonePoseSampler.BodyTransformOrder.LEGACY_PALMORE_1_20_1);
            }
            if (trackController) {
                publishSamplesForHost(keyframePlayer, entity, ex, ey, ez, bodyYaw, entityScale, actorPos, lookup,
                        PlayerBonePoseSampler.BodyTransformOrder.LEGACY_PALMORE_1_20_1);
            }
        } catch (Throwable error) {
            LOGGER.debug("Failed to sample 1.20.1 PalMore player bone poses", error);
        }
    }

    // =========================================================================
    // Shared helpers
    // =========================================================================

    private static void publishSamplesForHost(Object host, @Nullable Entity entity,
                                              double ex, double ey, double ez,
                                              float bodyYaw, float entityScale,
                                              @Nullable Vec3 actorPos,
                                              PlayerBonePoseSampler.BoneLookup lookup,
                                              PlayerBonePoseSampler.BodyTransformOrder bodyOrder) {
        Set<String> tracked = KeyframeEffectManager.trackedLocators(host);
        if (tracked.isEmpty()) return;
        for (String locatorName : tracked) {
            PlayerBonePoseSampler.WorldPose pose = PlayerBonePoseSampler.sample(
                    ex, ey, ez, bodyYaw, entityScale, locatorName, lookup, bodyOrder);
            if (pose != null) {
                KeyframeEffectManager.onLocatorSample(
                        host, entity, locatorName,
                        pose.x(), pose.y(), pose.z(),
                        pose.qx(), pose.qy(), pose.qz(), pose.qw(),
                        actorPos,
                        KeyframeEffectManager.LocatorSource.ANIMATION_BONE);
            }
        }
    }

    private static @Nullable Object lookupPalBone(@Nullable Map<?, ?> activeBones,
                                                  @Nullable Map<?, ?> allBones,
                                                  @Nullable Map<?, ?> pivotBones,
                                                  String canonicalName) {
        Object bone = getFromMap(activeBones, canonicalName);
        if (bone == null) bone = getFromMap(allBones, canonicalName);
        if (bone == null) bone = getFromMap(pivotBones, canonicalName);
        if (bone == null) {
            String legacyName = PlayerBonePoseSampler.toLegacyBoneName(canonicalName);
            if (!legacyName.equals(canonicalName)) {
                bone = getFromMap(activeBones, legacyName);
                if (bone == null) bone = getFromMap(allBones, legacyName);
                if (bone == null) bone = getFromMap(pivotBones, legacyName);
            }
        }
        return bone;
    }

    private static @Nullable Object getFromMap(@Nullable Map<?, ?> map, String key) {
        return map != null ? map.get(key) : null;
    }

    private static PlayerBonePoseSampler.BonePose readPalBonePose(Object bone) {
        try {
            float posX = 0.0F, posY = 0.0F, posZ = 0.0F;
            float rotX = 0.0F, rotY = 0.0F, rotZ = 0.0F;
            float scaleX = 1.0F, scaleY = 1.0F, scaleZ = 1.0F;

            if (palBonePosVecField != null) {
                Object pv = palBonePosVecField.get(bone);
                if (pv instanceof Vector3f v) { posX = v.x; posY = v.y; posZ = v.z; }
                Object rv = palBoneRotVecField.get(bone);
                if (rv instanceof Vector3f v) { rotX = v.x; rotY = v.y; rotZ = v.z; }
                Object sv = palBoneScaleVecField.get(bone);
                if (sv instanceof Vector3f v) { scaleX = v.x; scaleY = v.y; scaleZ = v.z; }
            } else if (palBonePosXField != null) {
                posX = palBonePosXField.getFloat(bone);
                posY = palBonePosYField.getFloat(bone);
                posZ = palBonePosZField.getFloat(bone);
                rotX = palBoneRotXField.getFloat(bone);
                rotY = palBoneRotYField.getFloat(bone);
                rotZ = palBoneRotZField.getFloat(bone);
                scaleX = palBoneScaleXField.getFloat(bone);
                scaleY = palBoneScaleYField.getFloat(bone);
                scaleZ = palBoneScaleZField.getFloat(bone);
            }

            float nativeBendX = palBoneBendField != null ? palBoneBendField.getFloat(bone) : 0.0F;
            float bendX = nativeBendX;
            float bendY = 0.0F, bendZ = 0.0F;
            float bendPosX = 0.0F, bendPosY = 0.0F, bendPosZ = 0.0F;
            float bendScaleX = 1.0F, bendScaleY = 1.0F, bendScaleZ = 1.0F;
            boolean hasBendTransform = false;
            boolean hasExtendedBend = false;

            ensurePalMoreBendChecked(bone.getClass().getClassLoader());
            if (palMoreBendHolderClass != null && palMoreBendHolderClass.isInstance(bone)) {
                boolean hasVec = pmHasBendVector == null || Boolean.TRUE.equals(pmHasBendVector.invoke(bone));
                if (hasVec) {
                    bendX = ((Number) pmBendX.invoke(bone)).floatValue();
                    bendY = ((Number) pmBendY.invoke(bone)).floatValue();
                    bendZ = ((Number) pmBendZ.invoke(bone)).floatValue();
                }
                if (pmHasBendTransform != null && Boolean.TRUE.equals(pmHasBendTransform.invoke(bone))) {
                    bendPosX = ((Number) pmBendPosX.invoke(bone)).floatValue();
                    bendPosY = ((Number) pmBendPosY.invoke(bone)).floatValue();
                    bendPosZ = ((Number) pmBendPosZ.invoke(bone)).floatValue();
                    bendScaleX = ((Number) pmBendScaleX.invoke(bone)).floatValue();
                    bendScaleY = ((Number) pmBendScaleY.invoke(bone)).floatValue();
                    bendScaleZ = ((Number) pmBendScaleZ.invoke(bone)).floatValue();
                    hasBendTransform = true;
                }
                hasExtendedBend = Math.abs(bendY) > 1.0E-5F || Math.abs(bendZ) > 1.0E-5F || hasBendTransform;
            }

            return new PlayerBonePoseSampler.BonePose(
                    posX, posY, posZ, rotX, rotY, rotZ, scaleX, scaleY, scaleZ,
                    bendX, bendY, bendZ, bendPosX, bendPosY, bendPosZ,
                    bendScaleX, bendScaleY, bendScaleZ, hasBendTransform,
                    0.0F, nativeBendX, hasExtendedBend);
        } catch (Throwable error) {
            return PlayerBonePoseSampler.BonePose.IDENTITY;
        }
    }

    private static @Nullable float[] readPalBonePivotPx(Object controller, String locatorName) {
        if (palGetBonePositionMethod == null) return null;
        try {
            Object vec = palGetBonePositionMethod.invoke(controller, locatorName);
            if (vec == null) return null;
            if (palVec3fXMethod == null) {
                palVec3fXMethod = vec.getClass().getMethod("x");
                palVec3fYMethod = vec.getClass().getMethod("y");
                palVec3fZMethod = vec.getClass().getMethod("z");
            }
            float x = ((Number) palVec3fXMethod.invoke(vec)).floatValue();
            float y = ((Number) palVec3fYMethod.invoke(vec)).floatValue();
            float z = ((Number) palVec3fZMethod.invoke(vec)).floatValue();
            if (x == 0.0F && y == 0.0F && z == 0.0F) return null;
            return new float[] { x, y, z };
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static @Nullable Object resolveLegacyExtras(Object keyframePlayer) {
        if (legacyGetDataMethod == null || legacyGetExtrasMethod == null) return null;
        try {
            Object animData = legacyGetDataMethod.invoke(keyframePlayer);
            return animData != null ? legacyGetExtrasMethod.invoke(null, animData) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static @Nullable PlayerBonePoseSampler.BonePose readLegacyBonePose(
            Object keyframePlayer, @Nullable Object extras,
            String canonicalName, float tickDelta, float currentTick) {
        if (legacyGet3DTransformMethod == null || legacyVec3fCtor == null) {
            return null;
        }
        String boneName = PlayerBonePoseSampler.toLegacyBoneName(canonicalName);
        try {
            Object zeroVec = legacyVec3fCtor.newInstance(0.0F, 0.0F, 0.0F);
            Object oneVec = legacyVec3fCtor.newInstance(1.0F, 1.0F, 1.0F);

            Object pos = legacyGet3DTransformMethod.invoke(keyframePlayer, boneName, legacyTransformPosition, tickDelta, zeroVec);
            Object rot = legacyGet3DTransformMethod.invoke(keyframePlayer, boneName, legacyTransformRotation, tickDelta, zeroVec);
            Object scl = legacyTransformScale != null
                    ? legacyGet3DTransformMethod.invoke(keyframePlayer, boneName, legacyTransformScale, tickDelta, oneVec)
                    : oneVec;

            float posX = readLegacyVec(pos, legacyVec3fGetX, 0.0F);
            float posY = readLegacyVec(pos, legacyVec3fGetY, 0.0F);
            float posZ = readLegacyVec(pos, legacyVec3fGetZ, 0.0F);
            float rotX = readLegacyVec(rot, legacyVec3fGetX, 0.0F);
            float rotY = readLegacyVec(rot, legacyVec3fGetY, 0.0F);
            float rotZ = readLegacyVec(rot, legacyVec3fGetZ, 0.0F);
            float scaleX = readLegacyVec(scl, legacyVec3fGetX, 1.0F);
            float scaleY = readLegacyVec(scl, legacyVec3fGetY, 1.0F);
            float scaleZ = readLegacyVec(scl, legacyVec3fGetZ, 1.0F);

            float nativeBendAxis = 0.0F;
            float nativeBend = 0.0F;
            float bendX = 0.0F, bendY = 0.0F, bendZ = 0.0F;
            float bendPosX = 0.0F, bendPosY = 0.0F, bendPosZ = 0.0F;
            float bendScaleX = 1.0F, bendScaleY = 1.0F, bendScaleZ = 1.0F;
            boolean hasBendTransform = false;
            boolean hasExtendedBend = false;

            if (legacyTransformBend != null) {
                Object nativeBendVec = legacyGet3DTransformMethod.invoke(keyframePlayer, boneName, legacyTransformBend, tickDelta, zeroVec);
                // playerAnimator stores (axis, bend) in Vec3f(x=axis, y=bend)
                nativeBendAxis = readLegacyVec(nativeBendVec, legacyVec3fGetX, 0.0F);
                nativeBend = readLegacyVec(nativeBendVec, legacyVec3fGetY, 0.0F);
                bendX = nativeBend;
            }

            if (extras != null) {
                if (legacySampleScaleMethod != null) {
                    Object extraScale = legacySampleScaleMethod.invoke(extras, boneName, currentTick);
                    if (extraScale instanceof Vector3f sv) {
                        scaleX = sv.x; scaleY = sv.y; scaleZ = sv.z;
                    }
                }
                if (legacySampleBendMethod != null) {
                    Object bendState = legacySampleBendMethod.invoke(extras, boneName, currentTick);
                    if (bendState != null && Boolean.TRUE.equals(legacyBendHasActive.invoke(bendState))) {
                        bendX = ((Number) legacyBendX.invoke(bendState)).floatValue();
                        bendY = ((Number) legacyBendY.invoke(bendState)).floatValue();
                        bendZ = ((Number) legacyBendZ.invoke(bendState)).floatValue();
                        hasExtendedBend = true;
                        if (Boolean.TRUE.equals(legacyBendHasTransform.invoke(bendState))) {
                            bendPosX = ((Number) legacyBendPosX.invoke(bendState)).floatValue();
                            bendPosY = ((Number) legacyBendPosY.invoke(bendState)).floatValue();
                            bendPosZ = ((Number) legacyBendPosZ.invoke(bendState)).floatValue();
                            bendScaleX = ((Number) legacyBendScaleX.invoke(bendState)).floatValue();
                            bendScaleY = ((Number) legacyBendScaleY.invoke(bendState)).floatValue();
                            bendScaleZ = ((Number) legacyBendScaleZ.invoke(bendState)).floatValue();
                            hasBendTransform = true;
                        }
                    }
                }
            }

            return new PlayerBonePoseSampler.BonePose(
                    posX, posY, posZ, rotX, rotY, rotZ, scaleX, scaleY, scaleZ,
                    bendX, bendY, bendZ, bendPosX, bendPosY, bendPosZ,
                    bendScaleX, bendScaleY, bendScaleZ, hasBendTransform,
                    nativeBendAxis, nativeBend, hasExtendedBend);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static float readLegacyVec(@Nullable Object vec, @Nullable Method getter, float fallback) {
        if (vec == null || getter == null) return fallback;
        try {
            Object value = getter.invoke(vec);
            return value instanceof Number number ? number.floatValue() : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static @Nullable Entity resolvePalEntity(Object controller) {
        try {
            if (palGetAvatarMethod != null && palGetAvatarMethod.getDeclaringClass().isInstance(controller)) {
                Object res = palGetAvatarMethod.invoke(controller);
                if (res instanceof Entity e) return e;
            }
            if (palGetPlayerMethod != null && palGetPlayerMethod.getDeclaringClass().isInstance(controller)) {
                Object res = palGetPlayerMethod.invoke(controller);
                if (res instanceof Entity e) return e;
            }
            // Fallback for custom AnimationController subclasses
            for (String mName : new String[] { "getAvatar", "getPlayer" }) {
                try {
                    Method m = controller.getClass().getMethod(mName);
                    Object res = m.invoke(controller);
                    if (res instanceof Entity e) {
                        if ("getAvatar".equals(mName) && palGetAvatarMethod == null) palGetAvatarMethod = m;
                        if ("getPlayer".equals(mName) && palGetPlayerMethod == null) palGetPlayerMethod = m;
                        return e;
                    }
                } catch (NoSuchMethodException ignored) { }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static float readPartialTick(@Nullable Object animationData) {
        if (animationData == null || palDataPartialTickMethod == null) return 0.0F;
        try {
            float v = ((Number) palDataPartialTickMethod.invoke(animationData)).floatValue();
            return Float.isFinite(v) ? Math.max(0.0F, Math.min(1.0F, v)) : 0.0F;
        } catch (Throwable ignored) {
            return 0.0F;
        }
    }

    private static float resolveBodyYaw(@Nullable Entity entity, float partialTick) {
        if (entity instanceof LivingEntity living) {
            return Mth.rotLerp(partialTick, living.yBodyRotO, living.yBodyRot);
        }
        return entity != null ? entity.getViewYRot(partialTick) : 0.0F;
    }

    private static float resolveEntityScale(@Nullable Entity entity) {
        KeyframeEffectPlaybackAdapter adapter = KeyframeEffectManager.adapter();
        if (adapter != null) {
            float s = adapter.entityScale(entity);
            if (Float.isFinite(s) && s > 0.0F) return s;
        }
        return 1.0F;
    }

    private static boolean ensurePalReflection(Class<?> controllerCls) {
        if (palReady) return true;
        if (palInitAttempted) return false;
        synchronized (PalKeyframeBridge.class) {
            if (palReady) return true;
            if (palInitAttempted) return false;
            palInitAttempted = true;
            ClassLoader loader = controllerCls.getClassLoader();
            try {
                Class<?> baseController = Class.forName(
                        "com.zigythebird.playeranimcore.animation.AnimationController", false, loader);
                palBonesField = baseController.getDeclaredField("bones");
                palBonesField.setAccessible(true);
                palActiveBonesField = baseController.getDeclaredField("activeBones");
                palActiveBonesField.setAccessible(true);
                try {
                    palPivotBonesField = baseController.getDeclaredField("pivotBones");
                    palPivotBonesField.setAccessible(true);
                } catch (NoSuchFieldException ignored) { }
                try {
                    palGetBonePositionMethod = baseController.getMethod("getBonePosition", String.class);
                } catch (NoSuchMethodException ignored) { }

                Class<?> animDataCls = Class.forName(
                        "com.zigythebird.playeranimcore.animation.AnimationData", false, loader);
                try {
                    palDataPartialTickMethod = animDataCls.getMethod("getPartialTick");
                } catch (NoSuchMethodException ignored) { }

                Class<?> boneCls = Class.forName(
                        "com.zigythebird.playeranimcore.bones.PlayerAnimBone", false, loader);
                try {
                    // PAL 1.2.x (26.x): org.joml.Vector3f position, rotation, scale
                    palBonePosVecField = boneCls.getField("position");
                    palBoneRotVecField = boneCls.getField("rotation");
                    palBoneScaleVecField = boneCls.getField("scale");
                } catch (NoSuchFieldException ignored) {
                    // PAL 1.1.x (1.21.1): float positionX/Y/Z, rotX/Y/Z, scaleX/Y/Z
                    palBonePosXField = boneCls.getField("positionX");
                    palBonePosYField = boneCls.getField("positionY");
                    palBonePosZField = boneCls.getField("positionZ");
                    palBoneRotXField = boneCls.getField("rotX");
                    palBoneRotYField = boneCls.getField("rotY");
                    palBoneRotZField = boneCls.getField("rotZ");
                    palBoneScaleXField = boneCls.getField("scaleX");
                    palBoneScaleYField = boneCls.getField("scaleY");
                    palBoneScaleZField = boneCls.getField("scaleZ");
                }
                try {
                    palBoneBendField = boneCls.getField("bend");
                } catch (NoSuchFieldException ignored) { }

                try {
                    Class<?> playerCtrlCls = Class.forName(
                            "com.zigythebird.playeranim.animation.PlayerAnimationController", false, loader);
                    try {
                        palGetAvatarMethod = playerCtrlCls.getMethod("getAvatar");
                    } catch (NoSuchMethodException ignored) { }
                    try {
                        palGetPlayerMethod = playerCtrlCls.getMethod("getPlayer");
                    } catch (NoSuchMethodException ignored) { }
                } catch (ClassNotFoundException ignored) { }

                palReady = true;
                return true;
            } catch (Throwable error) {
                LOGGER.warn("PAL reflection bridge unavailable: {}", error.toString());
                return false;
            }
        }
    }

    private static void ensurePalMoreBendChecked(ClassLoader loader) {
        if (palMoreBendChecked) return;
        synchronized (PalKeyframeBridge.class) {
            if (palMoreBendChecked) return;
            palMoreBendChecked = true;
            try {
                Class<?> holder = Class.forName(
                        "com.kltyton.playeranimationlibrarymorerotation.compat.PalMoreBendHolder", false, loader);
                pmBendX = holder.getMethod("palMore$getBendX");
                pmBendY = holder.getMethod("palMore$getBendY");
                pmBendZ = holder.getMethod("palMore$getBendZ");
                try {
                    pmHasBendVector = holder.getMethod("palMore$hasBendVectorOverride");
                } catch (NoSuchMethodException ignored) { }
                pmBendPosX = holder.getMethod("palMore$getBendPositionX");
                pmBendPosY = holder.getMethod("palMore$getBendPositionY");
                pmBendPosZ = holder.getMethod("palMore$getBendPositionZ");
                pmBendScaleX = holder.getMethod("palMore$getBendScaleX");
                pmBendScaleY = holder.getMethod("palMore$getBendScaleY");
                pmBendScaleZ = holder.getMethod("palMore$getBendScaleZ");
                pmHasBendTransform = holder.getMethod("palMore$hasBendTransformOverride");
                palMoreBendHolderClass = holder;
            } catch (Throwable ignored) {
                palMoreBendHolderClass = null;
            }
        }
    }

    private static boolean ensureLegacyReflection(Class<?> playerCls) {
        if (legacyReady) return true;
        if (legacyInitAttempted) return false;
        synchronized (PalKeyframeBridge.class) {
            if (legacyReady) return true;
            if (legacyInitAttempted) return false;
            legacyInitAttempted = true;
            ClassLoader loader = playerCls.getClassLoader();
            try {
                legacyPlayerField = playerCls.getDeclaredField("player");
                legacyPlayerField.setAccessible(true);
                try {
                    legacyControllerField = playerCls.getDeclaredField("controller");
                    legacyControllerField.setAccessible(true);
                } catch (NoSuchFieldException ignored) { }
                try {
                    legacyAnimationIdField = playerCls.getDeclaredField("animationId");
                    legacyAnimationIdField.setAccessible(true);
                } catch (NoSuchFieldException ignored) { }
                legacyIsActiveMethod = playerCls.getMethod("isActive");
                legacyGetCurrentTickMethod = playerCls.getMethod("getCurrentTick");
                legacyGetDataMethod = playerCls.getMethod("getData");

                Class<?> transformEnum = Class.forName("dev.kosmx.playerAnim.api.TransformType", false, loader);
                legacyVec3fClass = Class.forName("dev.kosmx.playerAnim.core.util.Vec3f", false, loader);
                legacyVec3fCtor = legacyVec3fClass.getConstructor(float.class, float.class, float.class);
                legacyVec3fGetX = legacyVec3fClass.getMethod("getX");
                legacyVec3fGetY = legacyVec3fClass.getMethod("getY");
                legacyVec3fGetZ = legacyVec3fClass.getMethod("getZ");
                legacyGet3DTransformMethod = playerCls.getMethod(
                        "get3DTransform", String.class, transformEnum, float.class, legacyVec3fClass);

                for (Object constant : transformEnum.getEnumConstants()) {
                    String name = ((Enum<?>) constant).name();
                    if ("POSITION".equals(name)) legacyTransformPosition = constant;
                    else if ("ROTATION".equals(name)) legacyTransformRotation = constant;
                    else if ("SCALE".equals(name)) legacyTransformScale = constant;
                    else if ("BEND".equals(name)) legacyTransformBend = constant;
                }

                try {
                    Class<?> resCls = Class.forName(
                            "com.kltyton.playeranimationlibrarymorerotation.client.compat.PalMoreBendResources", false, loader);
                    Class<?> keyframeAnimCls = Class.forName(
                            "dev.kosmx.playerAnim.core.data.KeyframeAnimation", false, loader);
                    legacyGetExtrasMethod = resCls.getMethod("getExtras", keyframeAnimCls);

                    Class<?> extrasCls = Class.forName(
                            "com.kltyton.playeranimationlibrarymorerotation.client.compat.PalMoreAnimationExtras", false, loader);
                    legacySampleBendMethod = extrasCls.getMethod("sampleBend", String.class, float.class);
                    legacySampleScaleMethod = extrasCls.getMethod("sampleBoneScale", String.class, float.class);

                    Class<?> bendStateCls = Class.forName(
                            "com.kltyton.playeranimationlibrarymorerotation.compat.PalMoreBendState", false, loader);
                    legacyBendHasActive = bendStateCls.getMethod("hasActiveBend");
                    legacyBendX = bendStateCls.getMethod("bendX");
                    legacyBendY = bendStateCls.getMethod("bendY");
                    legacyBendZ = bendStateCls.getMethod("bendZ");
                    legacyBendPosX = bendStateCls.getMethod("positionX");
                    legacyBendPosY = bendStateCls.getMethod("positionY");
                    legacyBendPosZ = bendStateCls.getMethod("positionZ");
                    legacyBendScaleX = bendStateCls.getMethod("scaleX");
                    legacyBendScaleY = bendStateCls.getMethod("scaleY");
                    legacyBendScaleZ = bendStateCls.getMethod("scaleZ");
                    legacyBendHasTransform = bendStateCls.getMethod("hasNonTrivialTransform");
                } catch (Throwable ignored) { }

                legacyReady = true;
                return true;
            } catch (Throwable error) {
                LOGGER.warn("Legacy 1.20.1 PALMore reflection bridge unavailable: {}", error.toString());
                return false;
            }
        }
    }
}
