package com.kltyton.lumenfx.effect.niagara.audio;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Saved Sound Mix timing, class adjusters and EQ. */
public record NiagaraSoundMix(String path, float delay, float fadeIn, float duration, float fadeOut,
                              List<Adjustment> adjustments, float eqPriority, List<Band> eq) {
    public record Adjustment(String soundClass, float volume, float pitch, float lowPass, float centerVolume, boolean children) { }
    public record Band(float frequency, float gain, float bandwidth) { }
    public record Passive(NiagaraSoundMix mix, float minimum, float maximum) {
        public boolean applies(float volume) { return volume >= minimum && volume <= maximum; }
    }

    public NiagaraSoundMix {
        adjustments = List.copyOf(adjustments);
        eq = List.copyOf(eq);
    }

    static @Nullable NiagaraSoundMix read(@Nullable JsonObject source) {
        if (source == null) return null;
        var adjustments = new ArrayList<Adjustment>();
        for (var element : source.getAsJsonArray("adjustments")) {
            var entry = element.getAsJsonObject();
            adjustments.add(new Adjustment(entry.get("class").getAsString(), scalar(entry, "volume"), scalar(entry, "pitch"),
                    scalar(entry, "low_pass"), scalar(entry, "center_volume"), entry.get("children").getAsBoolean()));
        }
        var bands = new ArrayList<Band>();
        if (source.has("eq")) {
            for (var element : source.getAsJsonArray("eq")) {
                var entry = element.getAsJsonObject();
                bands.add(new Band(scalar(entry, "frequency"), scalar(entry, "gain"), scalar(entry, "bandwidth")));
            }
        }
        return new NiagaraSoundMix(source.get("path").getAsString(), scalar(source, "delay"), scalar(source, "fade_in"),
                scalar(source, "duration"), scalar(source, "fade_out"), adjustments, scalar(source, "eq_priority"), bands);
    }

    static List<Passive> passiveMixes(JsonObject source) {
        if (!source.has("passive_mixes")) return List.of();
        var passive = new ArrayList<Passive>();
        for (var element : source.getAsJsonArray("passive_mixes")) {
            var entry = element.getAsJsonObject();
            var mix = read(entry.getAsJsonObject("mix"));
            if (mix == null) throw new IllegalArgumentException("Sound Class passive modifier has no Sound Mix");
            passive.add(new Passive(mix, scalar(entry, "minimum"), scalar(entry, "maximum")));
        }
        return List.copyOf(passive);
    }

    private static float scalar(JsonObject source, String name) { return source.get(name).getAsFloat(); }
}
