package com.kltyton.lumenfx.effect.niagara.audio;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.kltyton.lumenfx.effect.curve.NiagaraCurve;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.function.Function;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Cooked source attenuation and concurrency settings used by Niagara voices. */
public final class NiagaraSoundAcoustics {
    public enum Shape { Sphere, Capsule, Box, Cone }
    public enum Distance { Linear, Logarithmic, Inverse, LogReverse, NaturalSound, Custom }
    public enum Falloff { Continues, Silent, Hold }
    public enum Rule { PreventNew, StopOldest, StopFarthestThenPreventNew, StopFarthestThenOldest,
        StopLowestPriority, StopQuietest, StopLowestPriorityThenPreventNew }
    public enum DuckMode { Default, Distance, Priority }
    public record Attenuation(boolean attenuate, boolean spatialize, Shape shape, Distance distance,
                              Falloff mode, float decibels, Vec3 extents, float coneOffset, float falloff,
                              float coneSphereRadius, float coneSphereFalloff, @Nullable NiagaraCurve customCurve) {
        public Attenuation {
            if (distance == Distance.Custom && customCurve == null)
                throw new IllegalArgumentException("A custom Niagara attenuation model requires a source curve");
        }
    }
    public record Concurrency(String path, int maximum, boolean perOwner, Rule rule, float retrigger,
                              float release, float duckScale, DuckMode duckMode, float duckAttack, float duckRelease, boolean recover) {
        public Concurrency {
            path = SourceNames.canonical(path);
            if (maximum < 1 || !Float.isFinite(duckScale) || duckScale < 0 || duckScale > 1)
                throw new IllegalArgumentException("Invalid Niagara sound concurrency settings");
        }
    }

    private NiagaraSoundAcoustics() { }

    public static Attenuation readAttenuation(JsonObject source, Function<JsonObject, NiagaraCurve> curves) {
        var axes = source.getAsJsonArray("extents");
        return new Attenuation(source.get("attenuate").getAsBoolean(), source.get("spatialize").getAsBoolean(),
                Shape.valueOf(source.get("shape").getAsString()), Distance.valueOf(source.get("model").getAsString()),
                Falloff.valueOf(source.get("falloff_mode").getAsString()), value(source, "decibels"),
                new Vec3(axes.get(0).getAsDouble(), axes.get(1).getAsDouble(), axes.get(2).getAsDouble()),
                value(source, "cone_offset"), value(source, "falloff"), value(source, "cone_sphere_radius"),
                value(source, "cone_sphere_falloff"), source.has("curve") ? curves.apply(source.getAsJsonObject("curve")) : null);
    }

    public static Concurrency readConcurrency(JsonObject source) {
        return new Concurrency(source.get("path").getAsString(), source.get("maximum").getAsInt(),
                source.get("per_owner").getAsBoolean(), Rule.valueOf(source.get("rule").getAsString()),
                value(source, "retrigger"), value(source, "release"), value(source, "duck_scale"),
                DuckMode.valueOf(source.get("duck_mode").getAsString()), value(source, "duck_attack"),
                value(source, "duck_release"), source.get("duck_recover").getAsBoolean());
    }
    public static List<Concurrency> readConcurrencySet(@Nullable JsonArray source) {
        if (source == null) return List.of();
        var settings = new ArrayList<Concurrency>();
        for (var value : source) settings.add(readConcurrency(value.getAsJsonObject()));
        return List.copyOf(settings);
    }

    private static float value(JsonObject source, String key) { return source.get(key).getAsFloat(); }
}
