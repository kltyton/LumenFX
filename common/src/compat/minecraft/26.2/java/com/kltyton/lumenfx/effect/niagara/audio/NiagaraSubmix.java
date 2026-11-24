package com.kltyton.lumenfx.effect.niagara.audio;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Saved audio submix routing and its authored processing inputs. */
public record NiagaraSubmix(String path, String type, boolean dynamic, @Nullable NiagaraSubmix parent,
                           float outputDb, float wetDb, float dryDb, boolean modulated, List<Effect> effects) {
    public record Effect(String path, String type) { }

    public NiagaraSubmix {
        effects = List.copyOf(effects);
    }

    boolean hasConstantGain() {
        return type.equals("/Script/Engine.SoundSubmix") && !modulated && effects.isEmpty();
    }

    float constantGain() {
        float dry = linear(dryDb);
        // The dry buffer is added separately. Wet gain applies only when an effect chain runs.
        return linear(outputDb) * (Math.abs(dry) <= 1.e-8f ? 1 : 1 + dry);
    }

    static @Nullable NiagaraSubmix read(@Nullable JsonObject source) {
        if (source == null) return null;
        var effects = new ArrayList<Effect>();
        if (source.has("effects")) {
            for (var element : source.getAsJsonArray("effects")) {
                var effect = element.getAsJsonObject();
                effects.add(new Effect(effect.get("path").getAsString(), effect.get("class").getAsString()));
            }
        }
        return new NiagaraSubmix(source.get("path").getAsString(), source.get("class").getAsString(), flag(source, "dynamic"),
                read(source.getAsJsonObject("parent")), db(source, "output_db", 0), db(source, "wet_db", 0),
                db(source, "dry_db", -160), flag(source, "modulated"), effects);
    }

    private static float linear(float db) { return (float)Math.pow(10, Math.clamp(db, -160, 0) / 20); }
    private static float db(JsonObject source, String key, float fallback) { return source.has(key) ? source.get(key).getAsFloat() : fallback; }
    private static boolean flag(JsonObject source, String key) { return source.has(key) && source.get(key).getAsBoolean(); }
}
