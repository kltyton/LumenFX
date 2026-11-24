package com.kltyton.lumenfx.client.simulation.niagara.scalability;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.client.resource.niagara.NiagaraScalabilityJson;
import com.kltyton.lumenfx.effect.niagara.VectorProgram;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformSet;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Keeps resolved PlatformSet proxy values across script bindings and system reinitialization. */
public final class NiagaraPlatformSetProxies {
    private enum Initialization { LOADED, COPY, DUPLICATE }
    private record Definition(NiagaraPlatformSet platforms, Initialization initialization,
                              boolean sourceClassDefault, boolean sourceArchetype) {
        boolean classDefault() { return initialization == Initialization.LOADED && sourceClassDefault; }
        boolean postLoaded() { return switch (initialization) {
            case LOADED -> !sourceClassDefault;
            case COPY -> false;
            case DUPLICATE -> !sourceArchetype;
        }; }
    }
    private static final class Proxy {
        final Definition definition;
        final byte[] parameters = new byte[Integer.BYTES];
        Proxy(Definition definition, NiagaraPlatformEnvironment environment) {
            this.definition = definition;
            if (definition.postLoaded()) refresh(environment);
        }
        void refresh(NiagaraPlatformEnvironment environment) {
            ByteBuffer.wrap(parameters).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(definition.platforms().active(environment) ? 1 : 0);
        }
    }
    private final Map<String, Proxy> proxies = new LinkedHashMap<>();
    private NiagaraPlatforms.Publication publication;

    public NiagaraPlatformSetProxies(NiagaraPlatforms.Publication publication) {
        this.publication = Objects.requireNonNull(publication);
    }
    public void register(VectorProgram program) {
        for (var function : program.external()) if (function.interfaceType().equals("platform_set"))
            register(function.interfaceId(), Objects.requireNonNull(function.interfaceData()));
    }
    public void registerGpu(JsonObject program) {
        for (var element : program.getAsJsonArray("interfaces")) {
            JsonObject binding = element.getAsJsonObject();
            if (binding.get("class").getAsString().equals("NiagaraDataInterfacePlatformSet"))
                register(binding.get("interface_id").getAsString(),
                        Objects.requireNonNull(binding.getAsJsonObject("platform_set")));
        }
    }
    public void register(String identity, JsonObject source) {
        if (identity.isEmpty()) throw new IllegalArgumentException("PlatformSet has no source object identity");
        String mode = source.has("initialization") ? source.get("initialization").getAsString() : "loaded";
        Initialization initialization = switch (mode) {
            case "loaded" -> Initialization.LOADED;
            case "copy" -> Initialization.COPY;
            case "duplicate" -> Initialization.DUPLICATE;
            default -> throw new IllegalArgumentException("Invalid PlatformSet initialization " + mode);
        };
        Definition definition = new Definition(NiagaraScalabilityJson.platform(source.getAsJsonObject("platforms")),
                initialization, source.get("class_default").getAsBoolean(),
                source.has("archetype") && source.get("archetype").getAsBoolean());
        Proxy previous = proxies.get(identity);
        if (previous == null) proxies.put(identity, new Proxy(definition, publication.environment()));
        else if (!previous.definition.equals(definition))
            throw new IllegalArgumentException("Conflicting PlatformSet object " + identity);
    }
    public void synchronize(NiagaraPlatforms.Publication next) {
        Objects.requireNonNull(next);
        if (publication.sequence() != next.sequence())
            for (Proxy proxy : proxies.values()) if (!proxy.definition.classDefault()) proxy.refresh(next.environment());
        publication = next;
    }
    public boolean consoleChanged(NiagaraPlatformEnvironment previous, NiagaraPlatformEnvironment next) {
        for (Proxy proxy : proxies.values()) if (proxy.definition.platforms().consoleChanged(previous, next)) return true;
        return false;
    }
    public byte[] parameters(String identity) {
        Proxy proxy = proxies.get(identity);
        if (proxy == null) throw new IllegalArgumentException("Unknown PlatformSet object " + identity);
        return proxy.parameters.clone();
    }
    public Map<String, Integer> captureValues() {
        if (proxies.isEmpty()) return Map.of();
        var values = new LinkedHashMap<String, Integer>();
        proxies.forEach((identity, proxy) ->
                values.put(identity, ByteBuffer.wrap(proxy.parameters).order(ByteOrder.LITTLE_ENDIAN).getInt()));
        return Map.copyOf(values);
    }
    public void retainObjects(Set<String> identities) { proxies.keySet().removeIf(identity -> !identities.contains(identity)); }
}
