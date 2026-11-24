package com.kltyton.lumenfx.api.client.water;

import com.kltyton.lumenfx.api.client.water.NiagaraWaterBodySnapshot.Terrain;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Loaded water bodies in world actor order, indexed by their source component and actor paths. */
public final class NiagaraWaterScene {
    public record Body(String component, String actor, NiagaraWaterBody data) {
        public Body {
            if (component == null || component.isBlank()) throw new IllegalArgumentException("Missing water component path");
            Objects.requireNonNull(actor);
            Objects.requireNonNull(data);
        }
    }

    public static final NiagaraWaterScene EMPTY = new NiagaraWaterScene(List.of());
    private final List<Body> bodies;
    private final Map<String, Body> components;
    private final Map<String, Body> actors;
    private final @Nullable Terrain terrain;

    public NiagaraWaterScene(List<Body> bodies) {
        this(bodies, null);
    }

    public NiagaraWaterScene(List<Body> bodies, @Nullable Terrain terrain) {
        this.terrain = terrain;
        this.bodies = List.copyOf(bodies);
        var components = new HashMap<String, Body>();
        var actors = new HashMap<String, Body>();
        for (var body : this.bodies) {
            if (components.putIfAbsent(SourceNames.canonical(body.component()), body) != null)
                throw new IllegalArgumentException("Duplicate water body component " + body.component());
            if (!body.actor().isEmpty()) actors.putIfAbsent(SourceNames.canonical(body.actor()), body);
        }
        this.components = Map.copyOf(components);
        this.actors = Map.copyOf(actors);
    }

    public List<Body> bodies() { return bodies; }
    public @Nullable Terrain terrain() { return terrain; }

    public @Nullable Body component(String path) { return components.get(SourceNames.canonical(path)); }

    public @Nullable Body source(String path) {
        var component = component(path);
        return component != null ? component : actors.get(SourceNames.canonical(path));
    }
}
