package com.kltyton.lumenfx.client.compat.keyframe;

import com.kltyton.lumenfx.client.keyframe.KbrParticleProbe;
import com.kltyton.lumenfx.client.keyframe.KeyframeEffectManager;
import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pure soft-compatibility runtime bridge for GeckoLib 5.x and KBR / KBR-Lite on Minecraft 26.1.2 / 26.2 / 26.3.
 * Holds zero compile-time references to GeckoLib or KBR classes and caches reflective handles once on first use.
 *
 * <p>Standalone GeckoLib keeps its native callback-only particle keyframe semantics (no unsolicited auto-spawning);
 * developers calling {@code LumenFxKeyframeBridge.trigger(...)} in a GeckoLib callback automatically receive
 * 3D locator/bone pose tracking and controller-reset cleanup. When KBR / KBR-Lite is installed, LumenFX hooks
 * KBR's automatic particle dispatchers so LumenFX effects play smoothly without KBR throwing on unknown IDs.</p>
 */
public final class Gecko5KeyframeBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("LumenFX/Gecko5Keyframe");

    private record BakedMeta(@Nullable String locator, @Nullable String preEffectScript, @Nullable Boolean bindToActor) { }

    private static final Map<Object, BakedMeta> BAKED_META = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Entity> MANAGER_ENTITIES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Set<String>> PASS_ATTACHED_LOCATORS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Map<String, Quaternionf>> PASS_LOCATOR_ROTATIONS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<ArrayDeque<Object>> RENDER_PASS_STACK = new ThreadLocal<>();

    private static volatile boolean reflectionInitAttempted;
    private static volatile boolean reflectionReady;

    private static Method controllerGetNameMethod;
    private static volatile @Nullable Method keyframeHandlerHandleMethod;
    private static Method eventAnimatableMethod;
    private static Method eventRenderStateMethod;
    private static Method eventControllerMethod;
    private static Method eventKeyframeDataMethod;
    private static Method dataGetEffectMethod;
    private static Method dataGetLocatorNameMethod;
    private static Method actorParticleLocatorMethod;
    private static Method actorParticleScriptMethod;
    private static Method actorParticleBindMethod;
    private static Method renderStateGetDataMethod;
    private static Object ticketAnimatableManager;
    private static Object ticketPosition;
    private static Object ticketEntity;
    private static Method renderPassStateMethod;
    private static Method renderPassAddLocatorListenerMethod;
    private static Method renderPassAddBoneListenerMethod;
    private static Class<?> bonePositionListenerInterface;
    private static Method geoLocatorNameMethod;

    private Gecko5KeyframeBridge() { }

    public static void recordBakedParticleEffect(@Nullable Object source, @Nullable Object baked) {
        if (source == null || baked == null) return;
        if (!ensureReflection(source.getClass().getClassLoader())) return;
        try {
            String locator = actorParticleLocatorMethod != null ? (String) actorParticleLocatorMethod.invoke(source) : null;
            String script = actorParticleScriptMethod != null ? (String) actorParticleScriptMethod.invoke(source) : null;
            Boolean bind = actorParticleBindMethod != null ? (Boolean) actorParticleBindMethod.invoke(source) : null;
            BAKED_META.put(baked, new BakedMeta(locator, script, bind));
        } catch (Throwable error) {
            LOGGER.debug("Failed to record GeckoLib 5 baked particle metadata", error);
        }
    }

    public static void onControllerReset(@Nullable Object controller) {
        if (controller == null) return;
        KeyframeEffectManager.stopForControllerKey(controller);
    }

    /**
     * Invokes GeckoLib 5's {@code KeyframeEventHandler.handle(KeyFrameEvent)} inside an exception-safe
     * {@code try/finally} per-event dispatch scope, propagating any callback exception unchanged.
     */
    public static void dispatchWithScope(@Nullable Object owner, @Nullable Object handler, @Nullable Object event) {
        if (handler == null) return;
        ClassLoader loader = event != null ? event.getClass().getClassLoader() : handler.getClass().getClassLoader();
        Method handleMethod = resolveKeyframeHandlerHandle(loader, handler);
        if (handleMethod == null) {
            return;
        }
        Object token = KeyframeEffectManager.enterDispatchScope(owner);
        try {
            handleMethod.invoke(handler, event);
        } catch (InvocationTargetException wrapped) {
            rethrowUnchecked(wrapped.getCause() != null ? wrapped.getCause() : wrapped);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Unable to invoke GeckoLib 5 KeyframeEventHandler.handle", error);
        } finally {
            KeyframeEffectManager.exitDispatchScope(token);
        }
    }

    private static @Nullable Method resolveKeyframeHandlerHandle(ClassLoader loader, Object handler) {
        Method cached = keyframeHandlerHandleMethod;
        if (cached != null) return cached;
        synchronized (Gecko5KeyframeBridge.class) {
            cached = keyframeHandlerHandleMethod;
            if (cached != null) return cached;
            try {
                Class<?> handlerCls = Class.forName(
                        "com.geckolib.animation.AnimationController$KeyframeEventHandler", false, loader);
                Class<?> eventCls = Class.forName("com.geckolib.animation.state.KeyFrameEvent", false, loader);
                Method m = handlerCls.getMethod("handle", eventCls);
                keyframeHandlerHandleMethod = m;
                return m;
            } catch (Throwable error) {
                for (Class<?> iface : handler.getClass().getInterfaces()) {
                    for (Method m : iface.getMethods()) {
                        if ("handle".equals(m.getName()) && m.getParameterCount() == 1) {
                            return m;
                        }
                    }
                }
                LOGGER.warn("Unable to resolve GeckoLib 5 KeyframeEventHandler.handle: {}", error.toString());
                return null;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void rethrowUnchecked(Throwable throwable) throws E {
        throw (E) throwable;
    }

    public static void enterRenderPass(@Nullable Object renderPassInfo) {
        if (renderPassInfo == null) return;
        ArrayDeque<Object> stack = RENDER_PASS_STACK.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            RENDER_PASS_STACK.set(stack);
        }
        stack.push(renderPassInfo);
    }

    public static void exitRenderPass(@Nullable Object renderPassInfo) {
        ArrayDeque<Object> stack = RENDER_PASS_STACK.get();
        if (stack != null && stack.peek() == renderPassInfo) {
            stack.pop();
            if (stack.isEmpty()) RENDER_PASS_STACK.remove();
        }
        if (renderPassInfo != null) {
            PASS_ATTACHED_LOCATORS.remove(renderPassInfo);
            PASS_LOCATOR_ROTATIONS.remove(renderPassInfo);
        }
    }

    public static void attachLocatorListeners(@Nullable Object renderPassInfo) {
        if (renderPassInfo == null) return;
        if (!ensureReflection(renderPassInfo.getClass().getClassLoader())) return;
        try {
            Object renderState = renderPassStateMethod.invoke(renderPassInfo);
            if (renderState == null) return;
            Object manager = getRenderStateData(renderState, ticketAnimatableManager);
            Entity entity = resolveRenderStateEntity(manager, renderState);

            boolean trackManager = manager != null && KeyframeEffectManager.hasTrackedLocators(manager);
            boolean trackEntity = entity != null && entity != manager && KeyframeEffectManager.hasTrackedLocators(entity);
            if (!trackManager && !trackEntity) {
                return;
            }

            Set<String> tracked = new HashSet<>();
            if (trackManager) tracked.addAll(KeyframeEffectManager.trackedLocators(manager));
            if (trackEntity) tracked.addAll(KeyframeEffectManager.trackedLocators(entity));
            if (tracked.isEmpty()) return;

            Set<String> alreadyAttached = PASS_ATTACHED_LOCATORS.computeIfAbsent(
                    renderPassInfo, ignored -> Collections.synchronizedSet(new HashSet<>()));
            Vec3 fallbackBase = resolveBasePosition(entity, renderState);

            for (String locatorName : tracked) {
                if (!alreadyAttached.add(locatorName)) {
                    continue;
                }
                Object listener = Proxy.newProxyInstance(
                        bonePositionListenerInterface.getClassLoader(),
                        new Class<?>[] { bonePositionListenerInterface },
                        (p, method, args) -> {
                            if ("accept".equals(method.getName()) && args != null && args.length >= 3) {
                                Vec3 worldPos = args[0] instanceof Vec3 w ? w : null;
                                Vec3 modelPos = args[1] instanceof Vec3 m ? m : null;
                                Vec3 localPos = args[2] instanceof Vec3 l ? l : null;
                                Vec3 target = worldPos;
                                if (target == null && fallbackBase != null) {
                                    if (localPos != null) target = fallbackBase.add(localPos);
                                    else if (modelPos != null) target = fallbackBase.add(modelPos.scale(1.0 / 16.0));
                                }
                                if (target != null) {
                                    Quaternionf rot = lookupLocatorRotation(renderPassInfo, locatorName);
                                    Entity resolvedEntity = resolveRenderStateEntity(manager, renderState);
                                    Vec3 actorPos = resolvedEntity != null ? resolvedEntity.position() : fallbackBase;
                                    if (manager != null && KeyframeEffectManager.hasTrackedLocators(manager)) {
                                        KeyframeEffectManager.onLocatorSample(
                                                manager, resolvedEntity, locatorName,
                                                target.x, target.y, target.z,
                                                rot.x, rot.y, rot.z, rot.w,
                                                actorPos);
                                    }
                                    if (resolvedEntity != null && resolvedEntity != manager
                                            && KeyframeEffectManager.hasTrackedLocators(resolvedEntity)) {
                                        KeyframeEffectManager.onLocatorSample(
                                                resolvedEntity, resolvedEntity, locatorName,
                                                target.x, target.y, target.z,
                                                rot.x, rot.y, rot.z, rot.w,
                                                actorPos);
                                    }
                                }
                            }
                            return null;
                        });
                if (renderPassAddLocatorListenerMethod != null) {
                    renderPassAddLocatorListenerMethod.invoke(renderPassInfo, locatorName, listener);
                }
                if (renderPassAddBoneListenerMethod != null) {
                    renderPassAddBoneListenerMethod.invoke(renderPassInfo, locatorName, listener);
                }
            }
        } catch (Throwable error) {
            LOGGER.debug("Failed to attach GeckoLib 5 locator listeners", error);
        }
    }

    public static void captureLocatorPose(@Nullable Object geoLocator, @Nullable PoseStack poseStack, @Nullable Object renderPassInfo) {
        if (geoLocator == null || poseStack == null || renderPassInfo == null) return;
        if (!ensureReflection(geoLocator.getClass().getClassLoader())) return;
        try {
            String name = (String) geoLocatorNameMethod.invoke(geoLocator);
            if (name == null || name.isBlank()) return;
            Matrix4f pose = new Matrix4f(poseStack.last().pose());
            Quaternionf rot = pose.getNormalizedRotation(new Quaternionf());
            PASS_LOCATOR_ROTATIONS
                    .computeIfAbsent(renderPassInfo, ignored -> Collections.synchronizedMap(new HashMap<>()))
                    .put(name, rot);
        } catch (Throwable error) {
            LOGGER.debug("Failed to capture GeckoLib 5 locator rotation", error);
        }
    }

    /**
     * Guard invoked at the head of KBR / KBR-Lite's {@code GeckoLibHelper.processParticleEffect(KeyFrameEvent)}.
     *
     * @return {@code true} to cancel KBR's handler (either because LumenFX handled this effect, or because the
     *         keyframe effect is not registered in KBR's MolangParticleEngine and would otherwise throw).
     */
    public static boolean shouldCancelKbrGeckoLibHelper(@Nullable Object event) {
        if (event == null) return false;
        if (!ensureReflection(event.getClass().getClassLoader())) return false;
        try {
            Object data = eventKeyframeDataMethod.invoke(event);
            if (data == null) return false;

            String effect = (String) dataGetEffectMethod.invoke(data);
            if (effect == null || effect.isBlank()) return false;

            BakedMeta meta = BAKED_META.get(data);
            String script = meta != null ? meta.preEffectScript() : null;

            // 1. If LumenFX owns this effect ID, dispatch to LumenFX and cancel KBR so KBR never throws on it.
            if (KeyframeEffectManager.isManagedEffect(effect, script)) {
                handleKeyframeEvent(event);
                return true;
            }

            // 2. Otherwise, check whether KBR actually defines this particle before letting KBR parse/throw.
            Object animatable = eventAnimatableMethod.invoke(event);
            Object renderState = eventRenderStateMethod.invoke(event);
            Entity entity = resolveEntity(animatable);
            if (entity == null && renderState != null) {
                entity = resolveRenderStateEntity(getRenderStateData(renderState, ticketAnimatableManager), renderState);
            }
            Identifier parsedId = Identifier.tryParse(effect.trim());
            boolean registeredInKbr = KbrParticleProbe.isRegisteredInKbr(
                    event.getClass().getClassLoader(), entity, effect, parsedId);
            return !registeredInKbr;
        } catch (Throwable error) {
            LOGGER.debug("Failed to evaluate KBR GeckoLib 5 coexistence guard", error);
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

    private static boolean handleKeyframeEvent(Object event) {
        try {
            Object animatable = eventAnimatableMethod.invoke(event);
            Object renderState = eventRenderStateMethod.invoke(event);
            Object controller = eventControllerMethod.invoke(event);
            Object data = eventKeyframeDataMethod.invoke(event);
            if (data == null) return false;

            String effect = (String) dataGetEffectMethod.invoke(data);
            if (effect == null || effect.isBlank()) return false;

            BakedMeta meta = BAKED_META.get(data);
            String locator = null;
            if (dataGetLocatorNameMethod != null) {
                locator = (String) dataGetLocatorNameMethod.invoke(data);
            }
            if ((locator == null || locator.isBlank()) && meta != null) {
                locator = meta.locator();
            }
            String script = meta != null ? meta.preEffectScript() : null;
            Boolean bindToActor = meta != null ? meta.bindToActor() : null;

            Object manager = renderState != null ? getRenderStateData(renderState, ticketAnimatableManager) : null;
            Object host = manager != null ? manager : animatable;
            if (host == null) return false;

            Entity entity = resolveEntity(animatable);
            if (entity == null && renderState != null) {
                entity = resolveRenderStateEntity(manager, renderState);
            }
            if (entity != null && manager != null) {
                MANAGER_ENTITIES.put(manager, entity);
            }
            ClientLevel level = entity != null && entity.level() instanceof ClientLevel cl
                    ? cl
                    : Minecraft.getInstance().level;
            Vec3 fallbackPos = resolveBasePosition(entity, renderState);
            double fx = fallbackPos != null ? fallbackPos.x : 0.0;
            double fy = fallbackPos != null ? fallbackPos.y : 0.0;
            double fz = fallbackPos != null ? fallbackPos.z : 0.0;

            String controllerName = "";
            if (controller != null && controllerGetNameMethod != null) {
                Object n = controllerGetNameMethod.invoke(controller);
                if (n instanceof String s) controllerName = s;
            }

            Object dispatchToken = KeyframeEffectManager.currentDispatchToken();
            boolean handled = KeyframeEffectManager.handleKeyframe(
                    level, host, entity, controller, controllerName,
                    effect, locator, script, bindToActor,
                    fx, fy, fz, true, dispatchToken != null ? dispatchToken : event);

            if (handled && locator != null && !locator.isBlank()) {
                ArrayDeque<Object> stack = RENDER_PASS_STACK.get();
                if (stack != null && !stack.isEmpty()) {
                    attachLocatorListeners(stack.peek());
                }
            }
            return handled;
        } catch (Throwable error) {
            LOGGER.warn("Failed to evaluate GeckoLib 5 particle keyframe", error);
            return false;
        }
    }

    private static Quaternionf lookupLocatorRotation(Object renderPassInfo, String locatorName) {
        Map<String, Quaternionf> map = PASS_LOCATOR_ROTATIONS.get(renderPassInfo);
        Quaternionf q = map != null ? map.get(locatorName) : null;
        return q != null ? q : new Quaternionf();
    }

    private static @Nullable Object getRenderStateData(Object renderState, @Nullable Object ticket) {
        if (renderState == null || ticket == null || renderStateGetDataMethod == null) return null;
        try {
            return renderStateGetDataMethod.invoke(renderState, ticket);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static @Nullable Entity resolveRenderStateEntity(@Nullable Object manager, @Nullable Object renderState) {
        if (manager != null) {
            Entity cached = MANAGER_ENTITIES.get(manager);
            if (cached != null) return cached;
        }
        Object fromTicket = getRenderStateData(renderState, ticketEntity);
        if (fromTicket instanceof Entity e) {
            if (manager != null) MANAGER_ENTITIES.put(manager, e);
            return e;
        }
        return null;
    }

    private static @Nullable Entity resolveEntity(@Nullable Object animatable) {
        if (animatable instanceof Entity entity) {
            return entity;
        }
        if (animatable != null) {
            try {
                Method m = animatable.getClass().getMethod("getCurrentEntity");
                Object res = m.invoke(animatable);
                if (res instanceof Entity e) return e;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static @Nullable Vec3 resolveBasePosition(@Nullable Entity entity, @Nullable Object renderState) {
        if (entity != null) {
            return entity.position();
        }
        Object pos = getRenderStateData(renderState, ticketPosition);
        return pos instanceof Vec3 vec ? vec : null;
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

    private static boolean ensureReflection(ClassLoader loader) {
        if (reflectionReady) return true;
        if (reflectionInitAttempted) return false;
        synchronized (Gecko5KeyframeBridge.class) {
            if (reflectionReady) return true;
            if (reflectionInitAttempted) return false;
            reflectionInitAttempted = true;
            try {
                Class<?> controllerCls = Class.forName("com.geckolib.animation.AnimationController", false, loader);
                try {
                    controllerGetNameMethod = controllerCls.getMethod("getName");
                } catch (NoSuchMethodException ignored) { }

                Class<?> eventCls = Class.forName("com.geckolib.animation.state.KeyFrameEvent", false, loader);
                Class<?> handlerCls = Class.forName(
                        "com.geckolib.animation.AnimationController$KeyframeEventHandler", false, loader);
                keyframeHandlerHandleMethod = handlerCls.getMethod("handle", eventCls);
                eventAnimatableMethod = eventCls.getMethod("animatable");
                eventRenderStateMethod = eventCls.getMethod("renderState");
                eventControllerMethod = eventCls.getMethod("controller");
                eventKeyframeDataMethod = eventCls.getMethod("keyframeData");

                Class<?> dataCls = Class.forName("com.geckolib.cache.animation.keyframeevent.ParticleKeyframeData", false, loader);
                dataGetEffectMethod = dataCls.getMethod("getEffect");
                try {
                    dataGetLocatorNameMethod = dataCls.getMethod("getLocatorName");
                } catch (NoSuchMethodException ignored) { }

                try {
                    Class<?> actorEffectCls = Class.forName("com.geckolib.loading.definition.animation.ActorAnimationParticleEffect", false, loader);
                    actorParticleLocatorMethod = actorEffectCls.getMethod("locator");
                    actorParticleScriptMethod = actorEffectCls.getMethod("preEffectScript");
                    actorParticleBindMethod = actorEffectCls.getMethod("bindToActor");
                } catch (Throwable ignored) { }

                Class<?> renderStateCls = Class.forName("com.geckolib.renderer.base.GeoRenderState", false, loader);
                Class<?> dataTicketCls = Class.forName("com.geckolib.constant.dataticket.DataTicket", false, loader);
                renderStateGetDataMethod = renderStateCls.getMethod("getGeckolibData", dataTicketCls);

                Class<?> ticketsCls = Class.forName("com.geckolib.constant.DataTickets", false, loader);
                ticketAnimatableManager = ticketsCls.getField("ANIMATABLE_MANAGER").get(null);
                try {
                    ticketPosition = ticketsCls.getField("POSITION").get(null);
                } catch (NoSuchFieldException ignored) { }
                try {
                    ticketEntity = ticketsCls.getField("ENTITY").get(null);
                } catch (NoSuchFieldException ignored) { }

                Class<?> renderPassCls = Class.forName("com.geckolib.renderer.base.RenderPassInfo", false, loader);
                bonePositionListenerInterface = Class.forName("com.geckolib.renderer.base.RenderPassInfo$BonePositionListener", false, loader);
                renderPassStateMethod = renderPassCls.getMethod("renderState");
                renderPassAddLocatorListenerMethod = renderPassCls.getMethod("addLocatorPositionListener", String.class, bonePositionListenerInterface);
                try {
                    renderPassAddBoneListenerMethod = renderPassCls.getMethod("addBonePositionListener", String.class, bonePositionListenerInterface);
                } catch (NoSuchMethodException ignored) { }

                Class<?> geoLocatorCls = Class.forName("com.geckolib.cache.model.GeoLocator", false, loader);
                geoLocatorNameMethod = geoLocatorCls.getMethod("name");

                reflectionReady = true;
                return true;
            } catch (Throwable error) {
                LOGGER.warn("GeckoLib 5 reflection bridge unavailable: {}", error.toString());
                return false;
            }
        }
    }
}
