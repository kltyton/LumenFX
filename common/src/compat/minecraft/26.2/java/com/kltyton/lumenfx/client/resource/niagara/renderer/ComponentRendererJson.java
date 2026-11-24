package com.kltyton.lumenfx.client.resource.niagara.renderer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.kltyton.lumenfx.api.client.component.ComponentValue;
import com.kltyton.lumenfx.api.client.VfxComponents;
import com.kltyton.lumenfx.client.component.mesh.StaticMeshComponentFactory;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset;
import com.kltyton.lumenfx.effect.render.component.ComponentRendererSettings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.function.Function;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Component templates are decoded on resource preparation, before any simulation or scene work. */
public final class ComponentRendererJson {
    private ComponentRendererJson() { }

    public static @Nullable ComponentRendererSettings read(@Nullable JsonObject value, Function<Identifier, StaticMeshAsset> assets) {
        if (value == null) return null;
        var bindings = new ArrayList<ComponentRendererSettings.Binding>();
        for (var item : value.getAsJsonArray("bindings")) {
            var binding = item.getAsJsonObject();
            var defaults = new LinkedHashMap<String, String>();
            for (var entry : binding.getAsJsonObject("setter_defaults").entrySet()) defaults.put(entry.getKey(), entry.getValue().getAsString());
            bindings.add(new ComponentRendererSettings.Binding(binding.get("attribute").getAsString(),
                    binding.get("source_type").isJsonNull() ? null : kind(binding, "source_type"),
                    binding.get("property").getAsString(), binding.get("property_type").isJsonNull() ? null : kind(binding, "property_type"),
                    binding.get("metadata_setter").isJsonNull() ? null : binding.get("metadata_setter").getAsString(), defaults));
        }
        ComponentRendererSettings settings = new ComponentRendererSettings(value.get("source_class").getAsString(),
                value.has("template_key") ? value.get("template_key").getAsString() : "", value.getAsJsonObject("template"),
                value.get("count_limit").getAsInt(), value.get("assign_by_particle_id").getAsBoolean(),
                value.get("first_particle_frame_only").getAsBoolean(), value.get("activate_only_new").getAsBoolean(),
                value.get("enabled_attribute").getAsString(), value.get("visibility_attribute").getAsString(),
                value.get("visibility_tag").getAsInt(), bindings,
                !value.has("inherit_owner_visibility") || value.get("inherit_owner_visibility").getAsBoolean(), null);
        var factory = VfxComponents.factory(settings.sourceClass());
        return settings.withPreparedFactory(factory instanceof StaticMeshComponentFactory mesh
                ? mesh.prepare(settings.template(), assets) : factory.prepare(settings.template()));
    }

    private static ComponentValue.Kind kind(JsonObject value, String field) {
        try { return ComponentValue.Kind.valueOf(value.get(field).getAsString().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { throw new JsonParseException("Unknown component binding type: " + value.get(field), exception); }
    }
}
