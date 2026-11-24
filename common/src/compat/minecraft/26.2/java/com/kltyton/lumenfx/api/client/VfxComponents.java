package com.kltyton.lumenfx.api.client;

import com.kltyton.lumenfx.api.client.component.ComponentFactory;
import com.kltyton.lumenfx.client.component.audio.AudioComponentFactory;
import com.kltyton.lumenfx.client.component.light.DirectionalLightComponentFactory;
import com.kltyton.lumenfx.client.component.light.PointLightComponentFactory;
import com.kltyton.lumenfx.client.component.mesh.StaticMeshComponentFactory;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Host scene-component implementations are registered by their source class path. */
public final class VfxComponents {
    private static final Map<String, ComponentFactory> FACTORIES = new ConcurrentHashMap<>(Map.of(
            "/script/engine.scenecomponent", new com.kltyton.lumenfx.client.component.SceneComponentFactory(),
            "/script/engine.pointlightcomponent", new PointLightComponentFactory(),
            "/script/engine.spotlightcomponent", PointLightComponentFactory.spot(),
            "/script/engine.rectlightcomponent", PointLightComponentFactory.rect(),
            "/script/engine.directionallightcomponent", new DirectionalLightComponentFactory(),
            "/script/engine.decalcomponent", new com.kltyton.lumenfx.client.component.decal.DecalComponentFactory(),
            "/script/engine.audiocomponent", new AudioComponentFactory(),
            "/script/engine.staticmeshcomponent", new StaticMeshComponentFactory()));
    private VfxComponents() { }

    public static void register(String sourceClass, ComponentFactory factory) {
        String key = Objects.requireNonNull(sourceClass, "Component source class").toLowerCase(Locale.ROOT);
        if (key.isBlank()) throw new IllegalArgumentException("Component source class is empty");
        if (FACTORIES.putIfAbsent(key, Objects.requireNonNull(factory, "Component factory")) != null)
            throw new IllegalStateException("Component factory is already registered: " + sourceClass);
    }

    public static ComponentFactory factory(String sourceClass) {
        ComponentFactory factory = FACTORIES.get(sourceClass.toLowerCase(Locale.ROOT));
        if (factory == null) throw new IllegalStateException("No host scene-component implementation for " + sourceClass);
        return factory;
    }
}
