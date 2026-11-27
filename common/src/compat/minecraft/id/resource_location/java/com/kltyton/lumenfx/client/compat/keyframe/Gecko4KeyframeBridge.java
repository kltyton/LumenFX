package com.kltyton.lumenfx.client.compat.keyframe;

import com.kltyton.lumenfx.client.keyframe.KbrParticleProbe;
import com.kltyton.lumenfx.client.keyframe.KeyframeEffectManager;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pure soft-compatibility runtime bridge for GeckoLib 4.x (both 1.20.1 {@code .core.*} and 1.21.1 packages)
 * and KBR / KBR-Lite on Minecraft 1.20.1 / 1.21.1. Zero compile-time GeckoLib/KBR dependencies.
 *
 * <p>Standalone GeckoLib preserves its callback-only particle keyframe contract (no unsolicited auto-spawning);
 * developers triggering LumenFX via {@code LumenFxKeyframeBridge.trigger(...)} in callbacks automatically gain
 * 3D bone/locator tracking and controller-reset cleanup. When KBR / KBR-Lite is present, LumenFX intercepts
 * KBR's {@code GeckoLibHelper.processParticleEffect} directly at HEAD—completely independent of Mixin apply
 * order—routing LumenFX effects to LumenFX, KBR effects to KBR, and gracefully skipping unregistered IDs.</p>
 */
public final class Gecko4KeyframeBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("LumenFX/Gecko4Keyframe");

    private static final ThreadLocal<Object> CURRENT_RENDER_ANIMATABLE = new ThreadLocal<>();

    private static final class ReflectionSet {
        final @Nullable Method getNameMethod;
        final Method particleHandlerHandle;
        final Method dataGetEffect;
        final @Nullable Method dataGetLocator;
        final @Nullable Method dataScript;

        ReflectionSet(ClassLoader loader, boolean legacy1201) throws ReflectiveOperationException {
            String controllerPkg = legacy1201
                    ? "software.bernie.geckolib.core.animation.AnimationController"
                    : "software.bernie.geckolib.animation.AnimationController";
            String eventPkg = legacy1201
                    ? "software.bernie.geckolib.core.keyframe.event.ParticleKeyframeEvent"
                    : "software.bernie.geckolib.animation.keyframe.event.ParticleKeyframeEvent";
            String dataPkg = legacy1201
                    ? "software.bernie.geckolib.core.keyframe.event.data.ParticleKeyframeData"
                    : "software.bernie.geckolib.animation.keyframe.event.data.ParticleKeyframeData";

            Class<?> controllerCls = Class.forName(controllerPkg, false, loader);
            Method nameM = null;
            try {
                nameM = controllerCls.getMethod("getName");
            } catch (NoSuchMethodException ignored) { }
            this.getNameMethod = nameM;

            Class<?> handlerCls = Class.forName(controllerPkg + "$ParticleKeyframeHandler", false, loader);
            Class<?> eventCls = Class.forName(eventPkg, false, loader);
            this.particleHandlerHandle = handlerCls.getMethod("handle", eventCls);

            Class<?> dataCls = Class.forName(dataPkg, false, loader);
            this.dataGetEffect = dataCls.getMethod("getEffect");

            Method locM = null;
            try { locM = dataCls.getMethod("getLocator"); } catch (NoSuchMethodException ignored) { }
            this.dataGetLocator = locM;

            Method scriptM = null;
            try { scriptM = dataCls.getMethod("script"); } catch (NoSuchMethodException ignored) {
                try { scriptM = dataCls.getMethod("getScript"); } catch (NoSuchMethodException ignored2) { }
            }
            this.dataScript = scriptM;
        }
    }

    private static volatile @Nullable ReflectionSet reflection1201;
    private static volatile boolean reflection1201Failed;
    private static volatile @Nullable ReflectionSet reflection1211;
    private static volatile boolean reflection1211Failed;
    private static volatile @Nullable Method particleHandlerHandle1201;
    private static volatile @Nullable Method particleHandlerHandle1211;

    private static volatile @Nullable Method boneGetNameMethod;
    private static volatile @Nullable Method kbrGetLocatorsMethod;
    private static volatile boolean kbrGetLocatorsChecked;
    private static volatile @Nullable Method locatorOffsetMethod;
    private static volatile @Nullable Method locatorRotationMethod;
    private static volatile @Nullable Method kbrKeyframeBindToActorMethod;
    private static volatile boolean kbrKeyframeBindChecked;

    private Gecko4KeyframeBridge() { }

    public static void onControllerReset(@Nullable Object controller) {
        if (controller == null) return;
        KeyframeEffectManager.stopForControllerKey(controller);
    }

    /**
     * Invokes GeckoLib 4's {@code ParticleKeyframeHandler.handle(ParticleKeyframeEvent)} inside an exception-safe
     * {@code try/finally} per-event dispatch scope, propagating any callback exception unchanged.
     */
    public static void dispatchWithScope(@Nullable Object owner, @Nullable Object handler,
                                         @Nullable Object event, boolean legacy1201) {
        if (handler == null) return;
        ClassLoader loader = event != null ? event.getClass().getClassLoader() : handler.getClass().getClassLoader();
        Method handleMethod = resolveParticleHandlerHandle(loader, handler, legacy1201);
        if (handleMethod == null) {
            return;
        }
        Object token = KeyframeEffectManager.enterDispatchScope(owner);
        try {
            handleMethod.invoke(handler, event);
        } catch (InvocationTargetException wrapped) {
            rethrowUnchecked(wrapped.getCause() != null ? wrapped.getCause() : wrapped);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Unable to invoke GeckoLib 4 ParticleKeyframeHandler.handle", error);
        } finally {
            KeyframeEffectManager.exitDispatchScope(token);
        }
    }

    private static @Nullable Method resolveParticleHandlerHandle(ClassLoader loader, Object handler, boolean legacy1201) {
        Method cached = legacy1201 ? particleHandlerHandle1201 : particleHandlerHandle1211;
        if (cached != null) return cached;
        synchronized (Gecko4KeyframeBridge.class) {
            cached = legacy1201 ? particleHandlerHandle1201 : particleHandlerHandle1211;
            if (cached != null) return cached;
            try {
                String handlerName = legacy1201
                        ? "software.bernie.geckolib.core.animation.AnimationController$ParticleKeyframeHandler"
                        : "software.bernie.geckolib.animation.AnimationController$ParticleKeyframeHandler";
                String eventName = legacy1201
                        ? "software.bernie.geckolib.core.keyframe.event.ParticleKeyframeEvent"
                        : "software.bernie.geckolib.animation.keyframe.event.ParticleKeyframeEvent";
                Method m = Class.forName(handlerName, false, loader)
                        .getMethod("handle", Class.forName(eventName, false, loader));
                if (legacy1201) particleHandlerHandle1201 = m;
                else particleHandlerHandle1211 = m;
                return m;
            } catch (Throwable error) {
                for (Class<?> iface : handler.getClass().getInterfaces()) {
                    for (Method m : iface.getMethods()) {
                        if ("handle".equals(m.getName()) && m.getParameterCount() == 1) {
                            return m;
                        }
                    }
                }
                LOGGER.warn("Unable to resolve GeckoLib 4 ParticleKeyframeHandler.handle: {}", error.toString());
                return null;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void rethrowUnchecked(Throwable throwable) throws E {
        throw (E) throwable;
    }

    public static void setActiveRenderAnimatable(@Nullable Object animatable) {
        if (animatable == null) {
            CURRENT_RENDER_ANIMATABLE.remove();
        } else {
            CURRENT_RENDER_ANIMATABLE.set(animatable);
        }
    }

    public static boolean shouldTrackBone(@Nullable Object bone) {
        if (bone == null) return false;
        Object animatable = CURRENT_RENDER_ANIMATABLE.get();
        if (animatable == null) return false;
        Entity entity = resolveEntity(animatable);
        boolean trackAnim = KeyframeEffectManager.hasTrackedLocators(animatable);
        boolean trackEntity = entity != null && entity != animatable && KeyframeEffectManager.hasTrackedLocators(entity);
        if (!trackAnim && !trackEntity) {
            return false;
        }
        Set<String> tracked = collectTrackedLocators(animatable, entity, trackAnim, trackEntity);
        if (tracked.isEmpty()) return false;

        String boneName = readBoneName(bone);
        if (boneName != null && tracked.contains(boneName)) {
            return true;
        }
        Map<?, ?> locators = readKbrBoneLocators(bone);
        if (locators != null && !locators.isEmpty()) {
            for (String name : tracked) {
                if (locators.containsKey(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static void onBoneWorldSpaceMatrix(@Nullable Object bone, @Nullable Matrix4f matrix) {
        if (bone == null || matrix == null) return;
        Object animatable = CURRENT_RENDER_ANIMATABLE.get();
        if (animatable == null) return;
        Entity entity = resolveEntity(animatable);
        boolean trackAnim = KeyframeEffectManager.hasTrackedLocators(animatable);
        boolean trackEntity = entity != null && entity != animatable && KeyframeEffectManager.hasTrackedLocators(entity);
        if (!trackAnim && !trackEntity) {
            return;
        }
        Set<String> tracked = collectTrackedLocators(animatable, entity, trackAnim, trackEntity);
        if (tracked.isEmpty()) return;

        Vec3 actorPos = entity != null ? entity.position() : null;
        Quaternionf boneRot = new Matrix4f(matrix).getNormalizedRotation(new Quaternionf());

        String boneName = readBoneName(bone);
        if (boneName != null && tracked.contains(boneName)) {
            publishSampleToHosts(animatable, entity, trackAnim, trackEntity, boneName,
                    matrix.m30(), matrix.m31(), matrix.m32(),
                    boneRot.x, boneRot.y, boneRot.z, boneRot.w, actorPos);
        }

        Map<?, ?> kbrLocators = readKbrBoneLocators(bone);
        if (kbrLocators != null && !kbrLocators.isEmpty()) {
            for (String trackedName : tracked) {
                Object locatorValue = kbrLocators.get(trackedName);
                if (locatorValue == null) continue;
                publishKbrSubLocator(animatable, entity, trackAnim, trackEntity, actorPos, matrix, boneRot, trackedName, locatorValue);
            }
        }
    }

    /**
     * Guard invoked at the head of KBR / KBR-Lite's {@code GeckoLibHelper.processParticleEffect(...)} on 1.20.1 / 1.21.1.
     * Completely independent of Mixin application order because LumenFX does not double-wrap {@code particleKeyframeHandler}.
     *
     * @return {@code true} to cancel KBR's handler (either because LumenFX handled this effect, or because the
     *         keyframe effect is not registered in KBR's MolangParticleEngine and would otherwise throw).
     */
    public static boolean shouldCancelKbrGeckoLibHelper(@Nullable Object animatable,
                                                        @Nullable Object controller,
                                                        @Nullable Object keyframeData,
                                                        boolean legacy1201) {
        if (keyframeData == null) return false;
        ReflectionSet ref = resolveReflection(keyframeData.getClass().getClassLoader(), legacy1201);
        if (ref == null) return false;
        try {
            String effect = (String) ref.dataGetEffect.invoke(keyframeData);
            if (effect == null || effect.isBlank()) return false;
            String script = ref.dataScript != null ? (String) ref.dataScript.invoke(keyframeData) : null;

            // 1. If LumenFX owns this effect ID, dispatch to LumenFX and cancel KBR so KBR never throws on it.
            if (KeyframeEffectManager.isManagedEffect(effect, script)) {
                handleKbrGeckoLibKeyframe(ref, animatable, controller, keyframeData, effect, script);
                return true;
            }

            // 2. Otherwise, check whether KBR actually defines this particle before letting KBR parse/throw.
            Entity entity = resolveEntity(animatable);
            ResourceLocation parsedId = ResourceLocation.tryParse(effect.trim());
            boolean registeredInKbr = KbrParticleProbe.isRegisteredInKbr(
                    keyframeData.getClass().getClassLoader(), entity, effect, parsedId);
            return !registeredInKbr;
        } catch (Throwable error) {
            LOGGER.debug("Failed to evaluate KBR GeckoLib 4 coexistence guard", error);
            return false;
        }
    }

    public static void onKbrAnimationParticleFallback(@Nullable Object actorObj, @Nullable Object positionObj,
                                                      @Nullable String particle, @Nullable Object expressionObj,
                                                      boolean bound) {
        if (!(actorObj instanceof Entity actor) || !(positionObj instanceof Vec3 pos)) return;
        if (particle == null || particle.isBlank()) return;
        if (!(actor.level() instanceof ClientLevel level)) return;
        String script = extractExpressionSource(expressionObj);
        KeyframeEffectManager.handleKeyframe(
                level, actor, actor, null, "kbr_animation",
                particle, null, script, bound,
                pos.x, pos.y, pos.z, true);
    }

    private static void handleKbrGeckoLibKeyframe(ReflectionSet ref,
                                                  @Nullable Object animatable,
                                                  @Nullable Object controller,
                                                  Object data,
                                                  String effect,
                                                  @Nullable String script) {
        if (animatable == null) return;
        try {
            String locator = ref.dataGetLocator != null ? (String) ref.dataGetLocator.invoke(data) : null;
            Boolean bindToActor = readKbrBindToActor(data);

            Entity entity = resolveEntity(animatable);
            ClientLevel level = entity != null && entity.level() instanceof ClientLevel cl
                    ? cl
                    : Minecraft.getInstance().level;
            double fx = entity != null ? entity.getX() : 0.0;
            double fy = entity != null ? entity.getY() : 0.0;
            double fz = entity != null ? entity.getZ() : 0.0;

            String controllerName = "";
            if (controller != null && ref.getNameMethod != null) {
                Object n = ref.getNameMethod.invoke(controller);
                if (n instanceof String s) controllerName = s;
            }

            Object dispatchToken = KeyframeEffectManager.currentDispatchToken();
            KeyframeEffectManager.handleKeyframe(
                    level, animatable, entity, controller, controllerName,
                    effect, locator, script, bindToActor,
                    fx, fy, fz, true, dispatchToken != null ? dispatchToken : data);
        } catch (Throwable error) {
            LOGGER.warn("Failed to evaluate KBR/GeckoLib 4 particle keyframe", error);
        }
    }

    private static Set<String> collectTrackedLocators(Object animatable, @Nullable Entity entity,
                                                      boolean trackAnim, boolean trackEntity) {
        if (trackAnim && !trackEntity) {
            return KeyframeEffectManager.trackedLocators(animatable);
        }
        if (!trackAnim && trackEntity) {
            return KeyframeEffectManager.trackedLocators(entity);
        }
        Set<String> merged = new HashSet<>(KeyframeEffectManager.trackedLocators(animatable));
        merged.addAll(KeyframeEffectManager.trackedLocators(entity));
        return merged;
    }

    private static void publishSampleToHosts(Object animatable, @Nullable Entity entity,
                                             boolean trackAnim, boolean trackEntity,
                                             String locatorName,
                                             double x, double y, double z,
                                             float qx, float qy, float qz, float qw,
                                             @Nullable Vec3 actorPos) {
        if (trackAnim) {
            KeyframeEffectManager.onLocatorSample(animatable, entity, locatorName, x, y, z, qx, qy, qz, qw, actorPos);
        }
        if (trackEntity && entity != null) {
            KeyframeEffectManager.onLocatorSample(entity, entity, locatorName, x, y, z, qx, qy, qz, qw, actorPos);
        }
    }

    private static void publishKbrSubLocator(Object animatable, @Nullable Entity entity,
                                             boolean trackAnim, boolean trackEntity,
                                             @Nullable Vec3 actorPos,
                                             Matrix4f boneWorldMatrix, Quaternionf boneRot,
                                             String locatorName, Object locatorValue) {
        try {
            if (locatorOffsetMethod == null) {
                locatorOffsetMethod = locatorValue.getClass().getMethod("offset");
                try {
                    locatorRotationMethod = locatorValue.getClass().getMethod("rotation");
                } catch (NoSuchMethodException ignored) { }
            }
            double[] offset = (double[]) locatorOffsetMethod.invoke(locatorValue);
            Vector3f worldPoint = new Vector3f(
                    offset != null && offset.length >= 3 ? (float) (offset[0] / 16.0) : 0.0F,
                    offset != null && offset.length >= 3 ? (float) (offset[1] / 16.0) : 0.0F,
                    offset != null && offset.length >= 3 ? (float) (offset[2] / 16.0) : 0.0F);
            boneWorldMatrix.transformPosition(worldPoint);

            Quaternionf finalRot = new Quaternionf(boneRot);
            if (locatorRotationMethod != null) {
                double[] rotDeg = (double[]) locatorRotationMethod.invoke(locatorValue);
                if (rotDeg != null && rotDeg.length >= 3) {
                    finalRot.rotateZYX(
                            (float) Math.toRadians(rotDeg[2]),
                            (float) Math.toRadians(rotDeg[1]),
                            (float) Math.toRadians(rotDeg[0]));
                }
            }
            publishSampleToHosts(animatable, entity, trackAnim, trackEntity, locatorName,
                    worldPoint.x, worldPoint.y, worldPoint.z,
                    finalRot.x, finalRot.y, finalRot.z, finalRot.w, actorPos);
        } catch (Throwable ignored) { }
    }

    private static @Nullable String readBoneName(Object bone) {
        try {
            Method m = boneGetNameMethod;
            if (m == null) {
                m = bone.getClass().getMethod("getName");
                boneGetNameMethod = m;
            }
            Object res = m.invoke(bone);
            return res instanceof String s ? s : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static @Nullable Map<?, ?> readKbrBoneLocators(Object bone) {
        if (!kbrGetLocatorsChecked) {
            kbrGetLocatorsChecked = true;
            try {
                kbrGetLocatorsMethod = bone.getClass().getMethod("kltyton_bedrock_runtime$getLocators");
            } catch (NoSuchMethodException ignored) { }
        }
        Method m = kbrGetLocatorsMethod;
        if (m == null) return null;
        try {
            Object res = m.invoke(bone);
            return res instanceof Map<?, ?> map ? map : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static @Nullable Boolean readKbrBindToActor(Object keyframeData) {
        if (!kbrKeyframeBindChecked) {
            kbrKeyframeBindChecked = true;
            try {
                kbrKeyframeBindToActorMethod = keyframeData.getClass().getMethod("kltyton_bedrock_runtime$getBindToActor");
            } catch (NoSuchMethodException ignored) { }
        }
        Method m = kbrKeyframeBindToActorMethod;
        if (m == null) return null;
        try {
            Object res = m.invoke(keyframeData);
            return res instanceof Boolean b ? b : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static @Nullable Entity resolveEntity(@Nullable Object animatable) {
        if (animatable instanceof Entity entity) return entity;
        if (animatable != null) {
            try {
                Method m = animatable.getClass().getMethod("getCurrentEntity");
                Object res = m.invoke(animatable);
                if (res instanceof Entity e) return e;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static @Nullable String extractExpressionSource(@Nullable Object expressionObj) {
        if (expressionObj == null) return null;
        try {
            for (String fieldName : new String[] { "raw", "source", "expression", "script" }) {
                try {
                    Field f = expressionObj.getClass().getDeclaredField(fieldName);
                    f.setAccessible(true);
                    Object v = f.get(expressionObj);
                    if (v instanceof String s && !s.isBlank()) return s;
                } catch (NoSuchFieldException ignored) { }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static @Nullable ReflectionSet resolveReflection(ClassLoader loader, boolean legacy1201) {
        if (legacy1201) {
            if (reflection1201 != null) return reflection1201;
            if (reflection1201Failed) return null;
            synchronized (Gecko4KeyframeBridge.class) {
                if (reflection1201 != null) return reflection1201;
                if (reflection1201Failed) return null;
                try {
                    reflection1201 = new ReflectionSet(loader, true);
                    return reflection1201;
                } catch (Throwable error) {
                    reflection1201Failed = true;
                    LOGGER.warn("GeckoLib 4 (1.20.1) reflection bridge unavailable: {}", error.toString());
                    return null;
                }
            }
        } else {
            if (reflection1211 != null) return reflection1211;
            if (reflection1211Failed) return null;
            synchronized (Gecko4KeyframeBridge.class) {
                if (reflection1211 != null) return reflection1211;
                if (reflection1211Failed) return null;
                try {
                    reflection1211 = new ReflectionSet(loader, false);
                    return reflection1211;
                } catch (Throwable error) {
                    reflection1211Failed = true;
                    LOGGER.warn("GeckoLib 4 (1.21.1) reflection bridge unavailable: {}", error.toString());
                    return null;
                }
            }
        }
    }
}
