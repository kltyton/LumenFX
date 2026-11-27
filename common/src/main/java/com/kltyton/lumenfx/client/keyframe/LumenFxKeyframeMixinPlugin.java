package com.kltyton.lumenfx.client.keyframe;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

/**
 * Pure bytecode-probing MixinConfigPlugin that enables each optional animation-mod particle keyframe mixin
 * (GeckoLib 4/5, KBR/KBR-Lite, PAL, PALMore) ONLY when its exact target class and matching API generation
 * exist on the classpath, without ever classloading absent optional mods.
 */
public final class LumenFxKeyframeMixinPlugin implements IMixinConfigPlugin {
    private static final String GECKO4_1201_MARKER = "software.bernie.geckolib.core.animatable.GeoAnimatable";
    private static final String GECKO4_1211_MARKER = "software.bernie.geckolib.animatable.GeoAnimatable";
    private static final String GECKO5_MARKER = "com.geckolib.animatable.GeoAnimatable";
    private static final String PALMORE_1201_PLAYER = "com.kltyton.playeranimationlibrarymorerotation.client.PalMoreKeyframePlayer";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (targetClassName == null || targetClassName.isEmpty()) {
            return false;
        }
        if (!classExists(targetClassName)) {
            return false;
        }
        if (mixinClassName != null) {
            int dot = mixinClassName.lastIndexOf('.');
            String simple = dot >= 0 ? mixinClassName.substring(dot + 1) : mixinClassName;
            if ("Kbr1201GeckoLibHelperMixin".equals(simple)) {
                return classExists(GECKO4_1201_MARKER);
            }
            if ("Kbr1211GeckoLibHelperMixin".equals(simple)) {
                return classExists(GECKO4_1211_MARKER);
            }
            if ("KbrGeckoLibHelperMixin".equals(simple)) {
                return classExists(GECKO5_MARKER);
            }
            if ("PalMore1201PoseSampleMixin".equals(simple)) {
                return classExists(PALMORE_1201_PLAYER);
            }
        }
        return true;
    }

    private static boolean classExists(String className) {
        try {
            return MixinService.getService().getBytecodeProvider().getClassNode(className, false) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
