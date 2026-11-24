package com.kltyton.lumenfx.api.client;

import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/** Capture settings and the result of recording completed Niagara simulation frames. */
public final class NiagaraCacheCapture {
    public enum AttributeMode { ALL, RENDERING_ONLY, EXPLICIT }

    public record Attributes(AttributeMode mode, Set<String> names, Set<String> rebaseInclude, Set<String> rebaseExclude,
                             Set<String> interpolationInclude, Set<String> interpolationExclude) {
        public static final Attributes ALL = new Attributes(AttributeMode.ALL, Set.of());

        public Attributes(AttributeMode mode, Set<String> names) {
            this(mode, names, Set.of(), Set.of(), Set.of(), Set.of());
        }

        public Attributes {
            Objects.requireNonNull(mode);
            names = canonical(names);
            rebaseInclude = canonical(rebaseInclude);
            rebaseExclude = canonical(rebaseExclude);
            interpolationInclude = canonical(interpolationInclude);
            interpolationExclude = canonical(interpolationExclude);
            if (mode == AttributeMode.EXPLICIT && names.isEmpty())
                throw new IllegalArgumentException("Explicit Niagara cache capture requires attribute names");
        }

        private static Set<String> canonical(Set<String> names) {
            return names.stream().map(SourceNames::canonical).collect(Collectors.toUnmodifiableSet());
        }
    }

    public record Options(int frameLimit, int captureRate, boolean rebase, boolean interpolate,
                          boolean interpolateAllTypes, boolean extrapolate, boolean captureInterfaces, Attributes attributes,
                          boolean includeDebugData, int timeoutFrames) {
        public static final Options DEFAULT = new Options(16, 1, true, false, true, false, true);
        public Options(int frameLimit, int captureRate, boolean rebase, boolean interpolate,
                       boolean interpolateAllTypes, boolean extrapolate, boolean captureInterfaces) {
            this(frameLimit, captureRate, rebase, interpolate, interpolateAllTypes, extrapolate, captureInterfaces, Attributes.ALL);
        }
        public Options(int frameLimit, int captureRate, boolean rebase, boolean interpolate, boolean interpolateAllTypes,
                       boolean extrapolate, boolean captureInterfaces, Attributes attributes) {
            this(frameLimit, captureRate, rebase, interpolate, interpolateAllTypes, extrapolate, captureInterfaces, attributes, false);
        }
        public Options(int frameLimit, int captureRate, boolean rebase, boolean interpolate, boolean interpolateAllTypes,
                       boolean extrapolate, boolean captureInterfaces, Attributes attributes, boolean includeDebugData) {
            this(frameLimit, captureRate, rebase, interpolate, interpolateAllTypes, extrapolate, captureInterfaces, attributes, includeDebugData, 10);
        }
        public Options(int frameLimit, int captureRate, boolean rebase, boolean interpolate,
                       boolean interpolateAllTypes, boolean extrapolate) {
            this(frameLimit, captureRate, rebase, interpolate, interpolateAllTypes, extrapolate, true);
        }
        public Options {
            if (frameLimit < 0 || captureRate < 1 || timeoutFrames < 0)
                throw new IllegalArgumentException("Invalid Niagara capture frame count, rate or timeout");
            Objects.requireNonNull(attributes);
        }
    }

    private final Options options;
    private final @Nullable NiagaraSimulationCache append;
    private final boolean currentFrame;
    private final float advanceDelta;
    private final CompletableFuture<NiagaraSimulationCache> result = new CompletableFuture<>();
    private volatile boolean stop;

    NiagaraCacheCapture(Options options, @Nullable NiagaraSimulationCache append, boolean currentFrame, float advanceDelta) {
        this.options = options; this.append = append; this.currentFrame = currentFrame; this.advanceDelta = advanceDelta;
    }
    public Options options() { return options; }
    public @Nullable NiagaraSimulationCache appendSource() { return append; }
    public boolean currentFrame() { return currentFrame; }
    public float advanceDelta() { return advanceDelta; }
    public boolean capturesInterfaces(@Nullable String sourceSystem) {
        return append != null && sourceSystem != null && SourceNames.equal(append.sourceSystem(), sourceSystem)
                ? !append.interfaces().isEmpty() : options.captureInterfaces();
    }
    public CompletableFuture<NiagaraSimulationCache> result() { return result; }
    public void finish() { stop = true; }
    public boolean finishing() { return stop; }
}
