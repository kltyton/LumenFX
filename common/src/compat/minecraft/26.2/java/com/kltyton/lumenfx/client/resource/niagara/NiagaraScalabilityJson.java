package com.kltyton.lumenfx.client.resource.niagara;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraEmitterScalability;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformSet;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraSystemScalability;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraSystemScalability.Budget;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraSystemScalability.Count;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraSystemScalability.Distance;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraSystemScalability.Proxy;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraSystemScalability.Ramp;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraSystemScalability.Row;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraSystemScalability.Visibility;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Decodes platform predicates without resolving them against the resource-loading thread's environment. */
public final class NiagaraScalabilityJson {
    private NiagaraScalabilityJson() { }

    static @Nullable NiagaraSystemScalability system(@Nullable JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        JsonObject object = value.getAsJsonObject();
        JsonElement handler = object.get("significance_class");
        return new NiagaraSystemScalability(object.get("effect_type").getAsString(),
                NiagaraSystemScalability.Frequency.valueOf(object.get("update_frequency").getAsString()),
                NiagaraSystemScalability.Reaction.valueOf(object.get("cull_reaction").getAsString()),
                handler == null || handler.isJsonNull() ? null : handler.getAsString(),
                object.get("allow_local_player_culling").getAsBoolean(),
                object.has("script_reads_significance") && object.get("script_reads_significance").getAsBoolean(),
                rows(object.getAsJsonArray("effect_settings")), rows(object.getAsJsonArray("overrides")));
    }

    private static List<Row> rows(JsonArray array) {
        return array.asList().stream().map(value -> {
            JsonObject row = value.getAsJsonObject();
            JsonObject distance = row.getAsJsonObject("distance");
            JsonObject visibility = row.getAsJsonObject("visibility");
            JsonObject budget = row.getAsJsonObject("budget");
            JsonObject proxy = row.getAsJsonObject("proxy");
            return new Row(platform(row.getAsJsonObject("platforms")),
                    distance == null ? null : new Distance(distance.get("enabled").getAsBoolean(), distance.get("limit").getAsFloat()),
                    count(row.getAsJsonObject("effect_instances")), count(row.getAsJsonObject("system_instances")),
                    visibility == null ? null : new Visibility(visibility.get("not_rendered").getAsBoolean(),
                            visibility.get("max_time_without_render").getAsFloat(), visibility.get("outside_frustum").getAsBoolean(),
                            visibility.get("allow_frustum_precull").getAsBoolean(), visibility.get("max_time_outside_frustum").getAsFloat()),
                    budget == null ? null : new Budget(budget.get("cull").getAsBoolean(), budget.get("max_usage").getAsFloat(),
                            ramp(budget.get("distance_scale")), ramp(budget.get("effect_instances_scale")), ramp(budget.get("system_instances_scale"))),
                    proxy == null ? null : new Proxy(NiagaraSystemScalability.ProxyMode.valueOf(proxy.get("mode").getAsString()),
                            proxy.get("maximum").getAsInt()));
        }).toList();
    }

    private static @Nullable Count count(@Nullable JsonObject value) {
        return value == null ? null : new Count(value.get("enabled").getAsBoolean(), value.get("limit").getAsInt());
    }

    private static @Nullable Ramp ramp(JsonElement value) {
        if (value.isJsonNull()) return null;
        JsonArray array = value.getAsJsonArray();
        return new Ramp(array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat(), array.get(3).getAsFloat());
    }

    public static NiagaraEmitterScalability emitter(@Nullable JsonObject value) {
        if (value == null) return NiagaraEmitterScalability.DEFAULT;
        return new NiagaraEmitterScalability(platform(value.getAsJsonObject("platforms")),
                settings(value, "effect_settings"), settings(value, "overrides"));
    }
    public static NiagaraPlatformSet platform(@Nullable JsonObject value) {
        if (value == null) return NiagaraPlatformSet.ALL;
        var profiles = value.getAsJsonArray("profiles").asList().stream().map(item -> {
            JsonObject entry = item.getAsJsonObject();
            return new NiagaraPlatformSet.ProfileState(entry.get("name").getAsString(), mask(entry, "enabled_mask"), mask(entry, "set_mask"));
        }).toList();
        var conditions = value.getAsJsonArray("conditions").asList().stream().map(item -> {
            JsonObject entry = item.getAsJsonObject();
            return new NiagaraPlatformSet.Condition(entry.get("console").getAsString(), response(entry.get("pass")), response(entry.get("fail")),
                    entry.get("bool_value").getAsBoolean(), entry.get("min_int_enabled").getAsBoolean(), entry.get("min_int").getAsInt(),
                    entry.get("max_int_enabled").getAsBoolean(), entry.get("max_int").getAsInt(),
                    entry.get("min_float_enabled").getAsBoolean(), floating(entry, "min_float"),
                    entry.get("max_float_enabled").getAsBoolean(), floating(entry, "max_float"));
        }).toList();
        return new NiagaraPlatformSet(mask(value, "quality_mask"), profiles, conditions);
    }
    public static @Nullable List<NiagaraPlatformSet> refreshSets(@Nullable JsonArray values) {
        return values == null ? null : values.asList().stream().map(value -> platform(value.getAsJsonObject())).toList();
    }
    private static List<NiagaraEmitterScalability.Setting> settings(JsonObject value, String field) {
        return value.getAsJsonArray(field).asList().stream().map(item -> {
            JsonObject entry = item.getAsJsonObject();
            return new NiagaraEmitterScalability.Setting(platform(entry.getAsJsonObject("platforms")), entry.get("scale_enabled").getAsBoolean(),
                    floating(entry, "spawn_scale"), entry.get("override_spawn_scale").getAsBoolean());
        }).toList();
    }
    private static int mask(JsonObject value, String field) { return (int)value.get(field).getAsLong(); }
    private static float floating(JsonObject value, String field) { return Float.intBitsToFloat(mask(value, field)); }
    private static NiagaraPlatformSet.Response response(JsonElement value) { return switch (value.getAsString()) {
        case "None" -> NiagaraPlatformSet.Response.NONE; case "Enable" -> NiagaraPlatformSet.Response.ENABLE;
        case "Disable" -> NiagaraPlatformSet.Response.DISABLE; default -> throw new IllegalArgumentException("Invalid Niagara platform response " + value);
    }; }
}
