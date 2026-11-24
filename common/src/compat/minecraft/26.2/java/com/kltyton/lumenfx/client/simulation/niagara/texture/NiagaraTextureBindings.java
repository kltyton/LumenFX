package com.kltyton.lumenfx.client.simulation.niagara.texture;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.api.client.parameter.TextureParameter.Dimension;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStoreSources;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Source object bindings are resolved once on their owner thread and published to CPU and GPU consumers. */
public final class NiagaraTextureBindings {
    private static final class Capture {
        final TextureParameter source;
        final TextureParameter.Snapshot borrowed;
        final TextureParameter.Snapshot snapshot;
        final TextureParameter.@Nullable Retention retention;
        int references = 1;

        Capture(TextureParameter source, TextureParameter.Snapshot snapshot) {
            this.source = source;
            borrowed = snapshot;
            retention = snapshot.bound() ? source.retain(snapshot) : null;
            this.snapshot = retention == null ? snapshot : retention.snapshot(snapshot);
            if (retention != null && (this.snapshot.sampler() != snapshot.sampler() || this.snapshot.width() != snapshot.width()
                    || this.snapshot.height() != snapshot.height() || this.snapshot.layers() != snapshot.layers()
                    || this.snapshot.mipLevels() != snapshot.mipLevels() || this.snapshot.dimension() != snapshot.dimension()
                    || this.snapshot.volume() != snapshot.volume()
                    || (this.snapshot.arrayTexture() == null) != (snapshot.arrayTexture() == null)
                    || this.snapshot.arrayTexture() != null && this.snapshot.arrayTexture().texture() != snapshot.arrayTexture().texture()
                    || this.snapshot.view() != null && (snapshot.view() == null || this.snapshot.view().texture() != snapshot.view().texture()))) {
                retention.close();
                throw new IllegalArgumentException("Retained Niagara texture publication differs from the borrowed image");
            }
        }

        void retain() { references = Math.incrementExact(references); }

        void release() {
            if (--references == 0 && retention != null) retention.close();
        }
    }

    /** Owns the texture publications used by one queued simulation step. */
    public static final class Step implements AutoCloseable {
        private final Map<String, TextureParameter.Snapshot> snapshots;
        private final List<Capture> captures;

        private Step(Map<String, TextureParameter.Snapshot> snapshots, List<Capture> captures) {
            this.snapshots = Map.copyOf(snapshots);
            this.captures = captures;
        }

        public Map<String, TextureParameter.Snapshot> snapshots() { return snapshots; }
        public boolean retainedAcrossFrames() {
            for (var capture : captures)
                if (capture.snapshot.bound() && capture.retention == null) return false;
            return true;
        }

        @Override public void close() {
            RenderSystem.assertOnRenderThread();
            captures.forEach(Capture::release);
            captures.clear();
        }
    }

    public static final class State {
        private final JsonObject definition;
        private final String userParameter;
        private final TextureParameter defaultTexture;
        private final TextureParameter configuredTexture;
        private final Dimension dimension;
        private final String configuredSource, userSource;
        private final boolean hasUserSource;
        private volatile @Nullable Capture publication;
        private long capturedFrame;

        private State(JsonObject data) {
            definition = data.deepCopy();
            dimension = dimension(data);
            configuredSource = source(data, data.has("source_object") ? "source_object" : "source_texture");
            userSource = source(data, data.has("source_user_object") ? "source_user_object" : "source_user_texture");
            hasUserSource = data.has("source_user_object") || data.has("source_user_texture");
            userParameter = data.has("user_parameter") ? data.get("user_parameter").getAsString() : "";
            String field = data.has("user_texture") ? "user_texture" : "texture";
            defaultTexture = !data.has(field) || data.get(field).isJsonNull() ? TextureParameter.unbound()
                    : TextureParameter.resource(Identifier.parse(data.get(field).getAsString()));
            configuredTexture = !data.has("texture") || data.get("texture").isJsonNull() ? TextureParameter.unbound()
                    : TextureParameter.resource(Identifier.parse(data.get("texture").getAsString()));
        }

        private TextureParameter parameter(Map<String, TextureParameter> parameters, @Nullable NiagaraParameterStore instance) {
            if (dimension != Dimension.D2) {
                if (!userParameter.isEmpty()) {
                    if (instance != null) {
                        var binding = instance.objectBinding(userParameter);
                        if (binding.valid()) return NiagaraParameterStoreSources.dimensionalTexture(binding.value(), parameters, dimension);
                    }
                    var host = parameters.get(userParameter);
                    if (host != null) return host;
                }
                boolean useSavedOverride = instance == null && hasUserSource;
                String source = useSavedOverride ? userSource : configuredSource;
                return parameters.getOrDefault(source, useSavedOverride ? defaultTexture : configuredTexture);
            }
            TextureParameter parameter = userParameter.isEmpty() ? defaultTexture : parameters.getOrDefault(userParameter, defaultTexture);
            if (instance != null && !userParameter.isEmpty()) {
                var binding = instance.objectBinding(userParameter);
                parameter = binding.valid() ? NiagaraParameterStoreSources.texture(binding.value())
                        : parameters.getOrDefault(userParameter, configuredTexture);
            }
            return parameter;
        }

        private static String source(JsonObject data, String key) {
            var field = data.get(key);
            return field == null || field.isJsonNull() ? "" : field.getAsString();
        }

        private static Dimension dimension(JsonObject data) {
            Dimension result = Dimension.D2;
            for (Dimension kind : List.of(Dimension.ARRAY, Dimension.CUBE, Dimension.VOLUME)) {
                var flag = data.get(kind.name().toLowerCase(java.util.Locale.ROOT));
                if (flag == null || !flag.getAsBoolean()) continue;
                if (result != Dimension.D2) throw new IllegalArgumentException("Niagara texture has conflicting dimensions");
                result = kind;
            }
            return result;
        }

        private void capture(Map<String, TextureParameter> parameters, @Nullable NiagaraParameterStore instance) {
            TextureParameter parameter = parameter(parameters, instance);
            var snapshot = Objects.requireNonNull(parameter.resolve(), "Texture parameter returned no snapshot");
            var previous = publication;
            if (dimension != Dimension.D2 && !matchesDimension(snapshot) && previous != null && previous.snapshot.bound()) {
                if (previous.retention != null) return;
                parameter = previous.source;
                snapshot = Objects.requireNonNull(parameter.resolve(), "Texture parameter returned no snapshot");
            }
            if (!matchesDimension(snapshot)) snapshot = TextureParameter.Snapshot.UNBOUND;
            if (previous != null && previous.source == parameter && previous.borrowed.equals(snapshot)
                    && (!snapshot.bound() || previous.retention != null)) return;
            publication = new Capture(parameter, snapshot);
            if (previous != null) previous.release();
        }

        private boolean matchesDimension(TextureParameter.Snapshot snapshot) {
            return snapshot.bound() && dimension == snapshot.dimension();
        }

        public TextureParameter.Snapshot snapshot() {
            return current().snapshot;
        }

        private Capture current() {
            return Objects.requireNonNull(publication, "Niagara texture has no current simulation snapshot");
        }

        private void reset() {
            var previous = publication;
            publication = null;
            if (previous != null) previous.release();
        }
    }

    private final Map<String, State> definitions = new ConcurrentHashMap<>();
    private Map<String, TextureParameter> parameters = Map.of();
    private Map<String, TextureParameter> sourceParameters = Map.of();
    private volatile Map<String, TextureParameter.Snapshot> snapshots = Map.of();
    private boolean frameReady;
    private long captureFrame;
    private @Nullable NiagaraParameterStore instance;
    public void parameterStore(@Nullable NiagaraParameterStore value) { instance = value; }

    public State register(String id, JsonObject data) {
        if (id == null || id.isEmpty() || data == null) throw new IllegalArgumentException("Invalid Niagara texture binding");
        State state = definitions.computeIfAbsent(id, ignored -> {
            var created = new State(data);
            if (frameReady) { RenderSystem.assertOnRenderThread(); created.capture(parameters, instance); }
            return created;
        });
        if (!state.definition.equals(data)) throw new IllegalArgumentException("Niagara texture identity has conflicting definitions: " + id);
        return state;
    }

    public State get(String id) {
        return java.util.Objects.requireNonNull(definitions.get(id), "Niagara texture was not registered before simulation: " + id);
    }

    public void beginFrame(Map<String, TextureParameter> parameters) {
        beginCapture(parameters);
        publishCapture();
    }

    public void beginCapture(Map<String, TextureParameter> parameters) {
        captureFrame++;
        frameReady = false;
        if (parameters != sourceParameters) {
            Map<String, TextureParameter> normalized = new TreeMap<>(SourceNames.ASCII_CASE_INSENSITIVE);
            parameters.forEach((name, texture) -> {
                if (normalized.putIfAbsent(name, texture) != null)
                    throw new IllegalArgumentException("Texture parameters contain duplicate FName spellings: " + name);
            });
            this.parameters = Collections.unmodifiableMap(normalized);
            sourceParameters = parameters;
        }
    }

    public void capture(String identity) {
        RenderSystem.assertOnRenderThread();
        capture(get(identity));
    }

    private void capture(State state) {
        state.capture(parameters, instance);
        state.capturedFrame = captureFrame;
    }

    public void publishCapture() {
        if (definitions.isEmpty()) { snapshots = Map.of(); frameReady = true; return; }
        RenderSystem.assertOnRenderThread();
        Map<String, TextureParameter.Snapshot> captured = new java.util.LinkedHashMap<>();
        definitions.forEach((id, state) -> {
            if (state.capturedFrame != captureFrame) capture(state);
            captured.put(id, state.snapshot());
        });
        snapshots = Map.copyOf(captured);
        frameReady = true;
    }

    public Map<String, TextureParameter.Snapshot> snapshots() { return snapshots; }

    public boolean retainsAcrossFrames(Set<String> identities, Map<String, TextureParameter> incoming) {
        Map<String, TextureParameter> normalizedIncoming = null;
        for (String id : identities) {
            var state = get(id);
            var previous = state.publication;
            if (state.dimension != Dimension.D2 && previous != null && previous.snapshot.bound() && previous.retention == null) return false;
            if (!state.parameter(parameters, instance).retainsAcrossFrames()) return false;
            if (state.dimension != Dimension.D2 && !incoming.isEmpty()) {
                if (normalizedIncoming == null) {
                    normalizedIncoming = new TreeMap<>(SourceNames.ASCII_CASE_INSENSITIVE);
                    normalizedIncoming.putAll(incoming);
                }
                if (!state.parameter(normalizedIncoming, instance).retainsAcrossFrames()) return false;
            }
            String name = state.userParameter;
            String alias = name.length() > 5 && SourceNames.equal(name.substring(0, 5), "User.") ? name.substring(5) : name;
            for (var entry : incoming.entrySet()) {
                if ((!name.isEmpty() && (SourceNames.equal(entry.getKey(), name) || SourceNames.equal(entry.getKey(), alias))
                        || state.dimension != Dimension.D2 && (SourceNames.equal(entry.getKey(), state.configuredSource)
                        || SourceNames.equal(entry.getKey(), state.userSource)))
                        && !entry.getValue().retainsAcrossFrames()) return false;
            }
        }
        return true;
    }

    public Step retainStep(Set<String> identities) {
        RenderSystem.assertOnRenderThread();
        var snapshots = new LinkedHashMap<String, TextureParameter.Snapshot>();
        var retained = new ArrayList<Capture>(identities.size());
        try {
            for (String id : identities) {
                var capture = get(id).current();
                snapshots.put(id, capture.snapshot);
                capture.retain();
                retained.add(capture);
            }
            return new Step(snapshots, retained);
        } catch (RuntimeException | Error failure) {
            retained.forEach(Capture::release);
            throw failure;
        }
    }

    public void reset() {
        parameters = Map.of(); sourceParameters = Map.of(); snapshots = Map.of(); frameReady = false;
        definitions.values().forEach(State::reset);
    }

    public void clear() { reset(); definitions.clear(); }
}
