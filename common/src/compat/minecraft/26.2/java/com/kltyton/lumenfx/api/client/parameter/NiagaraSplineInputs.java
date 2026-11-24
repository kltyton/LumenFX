package com.kltyton.lumenfx.api.client.parameter;

import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs.Transform;
import com.kltyton.lumenfx.effect.curve.NiagaraSplineCurves;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Host-published splines keyed by Niagara user parameter and source actor path. */
public record NiagaraSplineInputs(Map<String, Source> parameters, Map<String, Actor> actors, @Nullable Source attachmentRoot) {
    public static final NiagaraSplineInputs EMPTY = new NiagaraSplineInputs(Map.of(), Map.of(), null);

    public NiagaraSplineInputs {
        parameters = named(parameters);
        actors = named(actors);
    }

    /** An actor can resolve successfully while containing no spline component. */
    public record Actor(@Nullable Source firstSpline, @Nullable Source nativeSpline) {
        public Actor(@Nullable Source firstSpline) { this(firstSpline, null); }

        public @Nullable Source firstSpline(boolean useSplineCurves) {
            return useSplineCurves || nativeSpline == null ? firstSpline : nativeSpline;
        }
    }

    /** Transform uses absolute Unreal axes and centimeters. Replace curves or advance version when their data changes. */
    public record Source(String identity, long version, Transform transform, Vec3 defaultUp, NiagaraSplineCurves curves) {
        public Source {
            if (identity.isBlank()) throw new IllegalArgumentException("A Niagara spline source requires a stable identity");
            Objects.requireNonNull(transform);
            Objects.requireNonNull(defaultUp);
            Objects.requireNonNull(curves);
        }
    }

    private static <T> Map<String, T> named(Map<String, T> values) {
        var copy = new TreeMap<String, T>(SourceNames.ASCII_CASE_INSENSITIVE);
        copy.putAll(Map.copyOf(values));
        if (copy.size() != values.size()) throw new IllegalArgumentException("Duplicate Niagara spline source name");
        return Collections.unmodifiableMap(copy);
    }
}
