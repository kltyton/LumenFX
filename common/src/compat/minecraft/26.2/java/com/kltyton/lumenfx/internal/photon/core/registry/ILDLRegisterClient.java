package com.kltyton.lumenfx.internal.photon.core.registry;

import com.kltyton.lumenfx.internal.photon.core.registry.annotation.LDLRegisterClient;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

public interface ILDLRegisterClient<T extends ILDLRegisterClient<T, V>, V> {
    default boolean isLDLRegister() {
        return getRegisterUIClient() != null;
    }

    @Nullable
    default LDLRegisterClient getRegisterUIClient() {
        return getClass().getAnnotation(LDLRegisterClient.class);
    }

    default String name() {
        var ann = getRegisterUIClient();
        return ann != null ? ann.name() : "";
    }

    default String group() {
        var ann = getRegisterUIClient();
        return ann != null ? ann.group() : "";
    }

    default Identifier registryName() {
        var ann = getRegisterUIClient();
        return ann != null ? Identifier.parse(ann.registry()) : Identifier.fromNamespaceAndPath("photon", "unknown");
    }

    @SuppressWarnings("unchecked")
    default AutoRegistry.LDLibRegisterClient<T, V> getRegistry() {
        return (AutoRegistry.LDLibRegisterClient<T, V>) LDLRegistry.REGISTERED.get(registryName());
    }

    @Nullable
    default AutoRegistry.Holder<LDLRegisterClient, T, V> getRegistryHolder() {
        var reg = getRegistry();
        return reg == null ? null : reg.get(name());
    }

    default Optional<AutoRegistry.Holder<LDLRegisterClient, T, V>> getRegistryHolderOptional() {
        return Optional.ofNullable(getRegistryHolder());
    }

    default String getTranslateKey() {
        return registryName().getNamespace() + "." + registryName().getPath() + "." + name();
    }

    default Component getChatComponent() {
        return Component.translatable(getTranslateKey());
    }
}
