package com.kltyton.lumenfx.client.compat.sodium;

import com.kltyton.lumenfx.client.compat.kbr.KbrCompat;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Gates Sodium compatibility mixins so standalone launches never touch absent mod classes. */
public final class SodiumMixinConfigPlugin implements IMixinConfigPlugin {
    private static final String KBR_BILLBOARD_MIXIN = ".KbrBillboardSodiumCompatMixin";

    @Override
    public void onLoad(final String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) {
        if (!SodiumCompat.isSodiumPresent()) {
            return false;
        }
        if (mixinClassName.endsWith(KBR_BILLBOARD_MIXIN)) {
            return KbrCompat.isPresent();
        }
        return true;
    }

    @Override
    public void acceptTargets(final Set<String> myTargets, final Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(final String targetClassName, final ClassNode targetClass, final String mixinClassName,
                         final IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(final String targetClassName, final ClassNode targetClass, final String mixinClassName,
                          final IMixinInfo mixinInfo) {
    }
}
