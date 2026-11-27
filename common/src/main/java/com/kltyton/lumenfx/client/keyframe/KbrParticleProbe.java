package com.kltyton.lumenfx.client.keyframe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.annotation.Nullable;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Soft-compatibility reflective probe for KBR / KBR-Lite ({@code kltyton_bedrock_runtime}) particle registration.
 * Used to determine whether KBR's {@code MolangParticleEngine} or {@code BedrockParticleBindings} actually defines
 * a keyframe's particle ID before KBR attempts to parse the ID/Molang script and throws on unknown IDs.
 *
 * <p>Holds zero compile-time references to KBR classes and caches reflective handles once per classloader.</p>
 */
public final class KbrParticleProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("LumenFX/KbrProbe");

    private static final class ReflectionHandles {
        final @Nullable Field engineInstanceField;
        final @Nullable Method resolveParticleIdMethod;
        final @Nullable Method bindingsResolveEntityMethod;
        final @Nullable Method renderItemCurrentItemMethod;

        ReflectionHandles(ClassLoader loader) {
            Field instanceField = null;
            Method resolveMethod = null;
            try {
                Class<?> engineCls = Class.forName(
                        "com.kltyton.bedrockruntime.client.particle.runtime.MolangParticleEngine", false, loader);
                instanceField = engineCls.getField("INSTANCE");
                for (Method m : engineCls.getMethods()) {
                    if ("resolveParticleId".equals(m.getName()) && m.getParameterCount() == 1) {
                        resolveMethod = m;
                        break;
                    }
                }
            } catch (Throwable ignored) { }

            Method entityBindingMethod = null;
            try {
                Class<?> bindingsCls = Class.forName(
                        "com.kltyton.bedrockruntime.client.particle.bedrock.BedrockParticleBindings", false, loader);
                entityBindingMethod = bindingsCls.getMethod("resolve", Entity.class, String.class);
            } catch (Throwable ignored) { }

            Method currentItemMethod = null;
            try {
                Class<?> itemCtxCls = Class.forName(
                        "com.kltyton.bedrockruntime.client.particle.definition.molang.RenderItemContext", false, loader);
                currentItemMethod = itemCtxCls.getMethod("currentItem");
            } catch (Throwable ignored) { }

            this.engineInstanceField = instanceField;
            this.resolveParticleIdMethod = resolveMethod;
            this.bindingsResolveEntityMethod = entityBindingMethod;
            this.renderItemCurrentItemMethod = currentItemMethod;
        }
    }

    private static volatile @Nullable ReflectionHandles cached;
    private static volatile @Nullable ClassLoader cachedLoader;

    private KbrParticleProbe() { }

    /**
     * Checks whether KBR's {@code MolangParticleEngine} (or entity/item {@code BedrockParticleBindings})
     * recognizes {@code rawEffect} / {@code parsedMinecraftId}.
     *
     * <p>Returns {@code true} if KBR can resolve this particle (or if KBR's engine cannot be probed cleanly,
     * failing open so native KBR particles are never suppressed); returns {@code false} only when KBR's
     * {@code MolangParticleEngine} is confirmed present and does NOT contain this particle ID.</p>
     */
    public static boolean isRegisteredInKbr(@Nullable ClassLoader kbrClassLoader,
                                            @Nullable Entity entity,
                                            @Nullable String rawEffect,
                                            @Nullable Object parsedMinecraftId) {
        if (rawEffect == null || rawEffect.isBlank()) {
            return false;
        }
        ClassLoader loader = kbrClassLoader != null ? kbrClassLoader : KbrParticleProbe.class.getClassLoader();
        ReflectionHandles handles = resolveHandles(loader);
        if (handles.engineInstanceField == null || handles.resolveParticleIdMethod == null) {
            return true;
        }

        try {
            Object engine = handles.engineInstanceField.get(null);
            if (engine == null) {
                return true;
            }

            // 1. Direct lookup in KBR's MolangParticleEngine (handles both Identifier on 26.x and ResourceLocation on 1.20.1/1.21.1)
            if (parsedMinecraftId != null) {
                Object resolved = handles.resolveParticleIdMethod.invoke(engine, parsedMinecraftId);
                if (resolved != null) {
                    return true;
                }
            }

            // 2. Entity-level BedrockParticleBindings remapping (short alias -> KBR particle ID)
            if (entity != null && handles.bindingsResolveEntityMethod != null) {
                Object mapped = handles.bindingsResolveEntityMethod.invoke(null, entity, rawEffect);
                if (mapped != null) {
                    Object resolved = handles.resolveParticleIdMethod.invoke(engine, mapped);
                    if (resolved != null) {
                        return true;
                    }
                }
            }

            // 3. If a Bedrock item is actively rendering, fail open so item-level BedrockParticleBindings still work
            if (handles.renderItemCurrentItemMethod != null) {
                Object currentItem = handles.renderItemCurrentItemMethod.invoke(null);
                if (currentItem != null) {
                    return true;
                }
            }

            return false;
        } catch (Throwable error) {
            LOGGER.debug("Failed to probe KBR MolangParticleEngine for '{}'", rawEffect, error);
            return true;
        }
    }

    /**
     * Strict variant for routing decisions where KBR is optional (e.g., AnimCue remote particle keyframes).
     * Returns {@code true} ONLY when KBR's {@code MolangParticleEngine} is loaded and resolves
     * {@code parsedMinecraftId} to a registered KBR particle; returns {@code false} when KBR is absent or does not
     * define this particle.
     */
    public static boolean isDefinedInMolangEngine(@Nullable ClassLoader kbrClassLoader,
                                                  @Nullable Object parsedMinecraftId) {
        if (parsedMinecraftId == null) {
            return false;
        }
        ClassLoader loader = kbrClassLoader != null ? kbrClassLoader : KbrParticleProbe.class.getClassLoader();
        ReflectionHandles handles = resolveHandles(loader);
        if (handles.engineInstanceField == null || handles.resolveParticleIdMethod == null) {
            return false;
        }
        try {
            Object engine = handles.engineInstanceField.get(null);
            if (engine == null) {
                return false;
            }
            return handles.resolveParticleIdMethod.invoke(engine, parsedMinecraftId) != null;
        } catch (Throwable error) {
            LOGGER.debug("Failed strict KBR MolangParticleEngine lookup for '{}'", parsedMinecraftId, error);
            return false;
        }
    }

    private static ReflectionHandles resolveHandles(ClassLoader loader) {
        ReflectionHandles existing = cached;
        if (existing != null && cachedLoader == loader) {
            return existing;
        }
        synchronized (KbrParticleProbe.class) {
            if (cached != null && cachedLoader == loader) {
                return cached;
            }
            ReflectionHandles created = new ReflectionHandles(loader);
            cached = created;
            cachedLoader = loader;
            return created;
        }
    }
}
