package com.kltyton.lumenfx.client.component.decal;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.component.ComponentFactory;
import com.kltyton.lumenfx.api.client.component.ComponentValue;
import com.kltyton.lumenfx.api.client.component.ComponentValue.Kind;
import com.kltyton.lumenfx.client.component.SceneEffectComponent;
import com.kltyton.lumenfx.effect.render.scene.ScenePrimitiveIds;
import com.kltyton.lumenfx.effect.EffectDefinition;
import com.kltyton.lumenfx.effect.render.decal.DecalFading;
import com.kltyton.lumenfx.effect.render.decal.ParticleDecal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/** Component proxy fading and owner destruction remain separate from per-particle Niagara fading. */
public final class DecalEffectComponent extends SceneEffectComponent {
    private record Initial(Template scene, Map<String, ComponentValue> properties, String material) { }
    private static final class Proxy {
        ComponentValue color;
        int sortOrder;
        float screenSize;
        Vec3 halfSize;
        DecalFading fading;
    }
    private final long identity = ScenePrimitiveIds.next();
    private final String material;
    private final DoubleSupplier fadeDurationScale;
    private final Quaterniond rotation = new Quaterniond();
    private final Vector3d scale = new Vector3d();
    private boolean dirty = true;
    private @Nullable Proxy proxy;

    public static ComponentFactory.Prepared prepare(JsonObject template, DoubleSupplier fadeDurationScale) {
        if (!template.has("lumenfx_decal_material") || template.get("lumenfx_decal_material").isJsonNull())
            throw new IllegalArgumentException("Decal component requires its compiled deferred-decal material");
        var properties = new LinkedHashMap<String, ComponentValue>();
        properties.put("SortOrder", ComponentValue.integer((int)number(template, "SortOrder", 0)));
        for (String name : new String[] {"FadeStartDelay", "FadeDuration", "FadeInStartDelay", "FadeInDuration", "FadeScreenSize"})
            properties.put(name, floating((float)number(template, name, name.equals("FadeScreenSize") ? .01 : 0)));
        properties.put("bDestroyOwnerAfterFade", ComponentValue.bool(!template.has("bDestroyOwnerAfterFade")
                || template.get("bDestroyOwnerAfterFade").getAsBoolean()));
        JsonObject size = template.getAsJsonObject("DecalSize"), color = template.getAsJsonObject("DecalColor");
        properties.put("DecalSize", ComponentValue.doubles(Kind.DOUBLE3,
                number(size, "X", 128), number(size, "Y", 256), number(size, "Z", 256), 0));
        properties.put("DecalColor", ComponentValue.floating(Kind.LINEAR_COLOR, (float)number(color, "R", 1),
                (float)number(color, "G", 1), (float)number(color, "B", 1), (float)number(color, "A", 1)));
        Initial initial = new Initial(prepareTemplate(template), Map.copyOf(properties), template.get("lumenfx_decal_material").getAsString());
        return context -> new DecalEffectComponent(context, initial, fadeDurationScale);
    }

    private DecalEffectComponent(ComponentFactory.Context context, Initial initial, DoubleSupplier fadeDurationScale) {
        super(context, initial.scene());
        this.material = initial.material();
        this.fadeDurationScale = Objects.requireNonNull(fadeDurationScale);
        initial.properties().forEach((name, value) -> define(name, value, () -> dirty = true));
        setter("SetFadeScreenSize", Kind.FLOAT, (value, defaults) -> applyChanged("FadeScreenSize", value));
        setter("SetSortOrder", Kind.INT, (value, defaults) -> applyChanged("SortOrder", value));
        setter("SetDecalColor", Kind.LINEAR_COLOR, (value, defaults) -> applyChanged("DecalColor", value));
        setter("SetFadeOut", Kind.FLOAT, (value, defaults) -> {
            float factor = (float)this.fadeDurationScale.getAsDouble();
            if (factor <= 1e-8f) factor = 0;
            property("FadeStartDelay").set(floating((float)value.number(0) * factor));
            property("FadeDuration").set(floating(argument(defaults, "Duration") * factor));
            property("bDestroyOwnerAfterFade").set(ComponentValue.bool(bool(defaults, "DestroyOwnerAfterFade", false)));
            setLifeSpan(scalar("FadeStartDelay") + scalar("FadeDuration"));
            if (proxy != null) proxy.fading = proxy.fading.fadeOut(time(), scalar("FadeStartDelay"), scalar("FadeDuration"));
            else dirty = true;
        });
        setter("SetFadeIn", Kind.FLOAT, (value, defaults) -> {
            property("FadeInStartDelay").set(value);
            property("FadeInDuration").set(floating(argument(defaults, "Duration")));
            if (proxy != null) proxy.fading = proxy.fading.fadeIn(time(), scalar("FadeInStartDelay"), scalar("FadeInDuration"));
            else dirty = true;
        });
        onChange(change -> {
            if (change == Change.VISIBILITY) dirty = true;
            else if (change == Change.TRANSFORM && proxy != null) proxy.halfSize = halfSize();
            else if (change == Change.DESTROYED) proxy = null;
        });
        setLifeSpan(scalar("FadeStartDelay") + scalar("FadeDuration"));
    }

    public @Nullable ParticleDecal frame(EffectDefinition effect) {
        if (!visible()) { proxy = null; return null; }
        if (dirty || proxy == null) {
            Proxy next = new Proxy();
            next.color = value("DecalColor"); next.sortOrder = (int)value("SortOrder").number(0);
            next.screenSize = scalar("FadeScreenSize"); next.halfSize = halfSize();
            next.fading = DecalFading.DEFAULT.fadeOut(time(), scalar("FadeStartDelay"), scalar("FadeDuration"))
                    .fadeIn(time(), scalar("FadeInStartDelay"), scalar("FadeInDuration"));
            proxy = next; dirty = false;
        }
        var definition = Objects.requireNonNull(effect.materials().get(material), "Unknown component decal material " + material);
        if (definition.decalSettings() == null) throw new IllegalArgumentException("Component decal material has the wrong domain: " + material);
        sourceRotation(rotation);
        ComponentValue color = proxy.color;
        return new ParticleDecal(definition, identity, "component_decal", 0, position(),
                -rotation.x, -rotation.z, -rotation.y, rotation.w, proxy.halfSize,
                (float)color.number(0), (float)color.number(1), (float)color.number(2), (float)color.number(3),
                1, proxy.sortOrder, proxy.screenSize, proxy.fading);
    }

    private Vec3 halfSize() {
        sourceScale(scale);
        ComponentValue size = value("DecalSize");
        return new Vec3(size.number(0) * scale.x, size.number(1) * scale.y, size.number(2) * scale.z);
    }
    private float time() { return (float)context.owner().time(); }
    private float scalar(String name) { return (float)value(name).number(0); }
    private static ComponentValue floating(float value) { return ComponentValue.floating(Kind.FLOAT, value, 0, 0, 0); }
    private static float argument(Map<String, String> defaults, String name) {
        String text = defaults.get(name);
        return text == null ? 0 : Float.parseFloat(text);
    }
    private void setLifeSpan(float seconds) { context.owner().setLifeSpan(this, seconds, this::expire); }
    private void expire() {
        boolean destroyOwner = value("bDestroyOwnerAfterFade").number(0) != 0
                && (scalar("FadeDuration") > 0 || scalar("FadeStartDelay") > 0);
        close();
        if (destroyOwner && context.owner().alive()) context.owner().destroy();
    }
    @Override public void close() {
        if (!alive()) return;
        if (context.owner().alive()) context.owner().setLifeSpan(this, 0, this::expire);
        super.close();
    }
}
