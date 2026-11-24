package com.kltyton.lumenfx.client.component.light;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.component.ComponentFactory;
import com.kltyton.lumenfx.api.client.component.ComponentValue;
import com.kltyton.lumenfx.api.client.component.ComponentValue.Kind;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.client.component.SceneEffectComponent;
import com.kltyton.lumenfx.client.render.light.photometry.PointLightPhotometry;
import com.kltyton.lumenfx.client.render.math.SourceColorMath;
import com.kltyton.lumenfx.client.render.math.SourceColorSpace;
import com.kltyton.lumenfx.client.render.math.UnrealRendererMath;
import com.kltyton.lumenfx.effect.render.scene.ScenePrimitiveIds;
import com.kltyton.lumenfx.client.resource.component.PointLightResources;
import com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices;
import com.kltyton.lumenfx.effect.EffectDefinition;
import com.kltyton.lumenfx.effect.render.light.scene.LightFunctionSettings;
import com.kltyton.lumenfx.effect.render.light.scene.SceneDirectionalLight;
import com.kltyton.lumenfx.effect.render.light.scene.SceneDirectionalLight.Atmosphere;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.jspecify.annotations.Nullable;

/** Keeps the render proxy in step with the component setters and pooled visibility transitions. */
public final class DirectionalLightEffectComponent extends SceneEffectComponent {
    private final long identity = ScenePrimitiveIds.next();
    private final Matrix4d lightToWorld = new Matrix4d();
    private final Matrix4d worldToLight = new Matrix4d();
    private final SourceColorSpace working;
    private final boolean movableData;
    private final boolean movable;
    private final @Nullable String lightFunctionMaterial;
    private final SceneDirectionalLight.@Nullable StaticShadow staticShadow;
    private @Nullable Proxy proxy;
    private boolean dirty = true;
    private boolean staticLightingAllowed;

    private record Initial(Template scene, Map<String, ComponentValue> properties,
                           SourceColorSpace working, boolean movableData, boolean movable, @Nullable String lightFunctionMaterial,
                           SceneDirectionalLight.@Nullable StaticShadow staticShadow) { }
    private record Proxy(Vec3 color, int forwardPriority, int lightingChannels, boolean affectsTranslucency,
                         float sourceRadius, float softSourceRadius, float diffuseScale, float specularScale,
                         @Nullable LightFunctionSettings lightFunction, SceneDirectionalLight.@Nullable Cascades cascades,
                         float volumetricScattering, boolean castVolumetricShadow,
                         SceneDirectionalLight.@Nullable StaticShadow staticShadow, SceneDirectionalLight.@Nullable Atmosphere atmosphere) { }

    public static ComponentFactory.Prepared prepare(JsonObject template) {
        Map<String, ComponentValue> properties = new LinkedHashMap<>();
        for (var field : Map.ofEntries(Map.entry("Intensity", 10f), Map.entry("Temperature", 6500f),
                Map.entry("LightSourceAngle", Atmosphere.DEFAULT_DISK_DIAMETER), Map.entry("LightSourceSoftAngle", 0f),
                Map.entry("SpecularScale", 1f), Map.entry("DiffuseScale", 1f),
                Map.entry("LightFunctionFadeDistance", 100000f), Map.entry("DisabledBrightness", .5f),
                Map.entry("DynamicShadowDistanceMovableLight", 40000f), Map.entry("DynamicShadowDistanceStationaryLight", 0f),
                Map.entry("CascadeDistributionExponent", 3f), Map.entry("CascadeTransitionFraction", .1f),
                Map.entry("FarShadowDistance", 300000f), Map.entry("ShadowDistanceFadeoutFraction", .1f),
                Map.entry("ShadowCascadeBiasDistribution", 1f), Map.entry("ShadowBias", .5f), Map.entry("ShadowSlopeBias", .5f),
                Map.entry("VolumetricScatteringIntensity", 1f)).entrySet()) {
            properties.put(field.getKey(), scalar((float) number(template, field.getKey(), field.getValue())));
        }
        for (var field : Map.of("bUseTemperature", false, "bAffectsWorld", true,
                "bAffectTranslucentLighting", true, "CastShadows", true, "CastDynamicShadows", true,
                "CastStaticShadows", true, "bCastVolumetricShadow", true, "bAtmosphereSunLight", true,
                "bPerPixelAtmosphereTransmittance", false).entrySet()) {
            properties.put(field.getKey(), flag(template, field.getKey(), field.getValue()));
        }
        JsonObject color = template.getAsJsonObject("LightColor");
        properties.put("LightColor", ComponentValue.color((int) number(color, "R", 255),
                (int) number(color, "G", 255), (int) number(color, "B", 255), (int) number(color, "A", 255)));
        JsonObject diskColor = template.getAsJsonObject("AtmosphereSunDiskColorScale");
        properties.put("AtmosphereSunDiskColorScale", ComponentValue.floating(Kind.LINEAR_COLOR,
                (float) number(diskColor, "R", 1), (float) number(diskColor, "G", 1),
                (float) number(diskColor, "B", 1), (float) number(diskColor, "A", 1)));
        JsonObject channels = template.getAsJsonObject("LightingChannels");
        for (int channel = 0; channel < 3; channel++) {
            String name = "bChannel" + channel;
            properties.put("LightingChannels." + name, flag(channels, name, channel == 0));
        }
        properties.put("ForwardShadingPriority", ComponentValue.integer((int) number(template, "ForwardShadingPriority", 0)));
        properties.put("AtmosphereSunLightIndex", ComponentValue.integer((int) number(template, "AtmosphereSunLightIndex", 0)));
        properties.put("DynamicShadowCascades", ComponentValue.integer((int) number(template, "DynamicShadowCascades", 4)));
        properties.put("FarShadowCascadeCount", ComponentValue.integer((int) number(template, "FarShadowCascadeCount", 0)));
        JsonObject scale = template.getAsJsonObject("LightFunctionScale");
        properties.put("LightFunctionScale", ComponentValue.doubles(Kind.DOUBLE3, number(scale, "X", 1024),
                number(scale, "Y", 1024), number(scale, "Z", 1024), 0));
        String mobility = template.has("Mobility") ? template.get("Mobility").getAsString() : "Movable";
        int separator = mobility.lastIndexOf("::");
        String mobilityName = separator < 0 ? mobility : mobility.substring(separator + 2);
        boolean movableData = switch (mobilityName) {
            case "Static" -> false;
            case "Stationary", "Movable" -> true;
            default -> throw new IllegalArgumentException("Unknown source component mobility " + mobility);
        };
        var resources = PointLightResources.read(template);
        Initial initial = new Initial(prepareTemplate(template), Map.copyOf(properties),
                resources.workingColorSpace(), movableData, mobilityName.equals("Movable"), resources.lightFunctionMaterial(),
                mobilityName.equals("Movable") ? null : staticShadow(template));
        return context -> new DirectionalLightEffectComponent(context, initial);
    }

    private DirectionalLightEffectComponent(ComponentFactory.Context context, Initial initial) {
        super(context, initial.scene());
        working = initial.working();
        movableData = initial.movableData();
        movable = initial.movable();
        lightFunctionMaterial = initial.lightFunctionMaterial();
        staticShadow = initial.staticShadow();
        initial.properties().forEach((name, value) -> define(name, value, this::invalidate));
        for (String field : new String[] {"Intensity", "Temperature", "LightSourceAngle", "LightSourceSoftAngle"})
            bindSetter("Set" + field, field, Kind.FLOAT);
        bindSetter("SetShadowSlopeBias", "ShadowSlopeBias", Kind.FLOAT);
        scaleSetter("SpecularScale");
        scaleSetter("DiffuseScale");
        bindSetter("SetUseTemperature", "bUseTemperature", Kind.BOOL);
        bindSetter("SetAffectTranslucentLighting", "bAffectTranslucentLighting", Kind.BOOL);
        bindSetter("SetCastShadows", "CastShadows", Kind.BOOL);
        bindSetter("SetCastVolumetricShadow", "bCastVolumetricShadow", Kind.BOOL);
        bindSetter("SetAtmosphereSunLight", "bAtmosphereSunLight", Kind.BOOL);
        setter("SetAtmosphereSunLightIndex", Kind.INT, (value, defaults) -> {
            if (movableData) apply("AtmosphereSunLightIndex", ComponentValue.integer(Math.max(0, (int) value.number(0))));
        });
        bindSetter("SetAtmosphereSunDiskColorScale", "AtmosphereSunDiskColorScale", Kind.LINEAR_COLOR);
        bindSetter("SetVolumetricScatteringIntensity", "VolumetricScatteringIntensity", Kind.FLOAT);
        bindSetter("SetShadowBias", "ShadowBias", Kind.FLOAT);
        bindSetter("SetLightFColor", "LightColor", Kind.BYTE_COLOR);
        bindSetter("SetLightFunctionScale", "LightFunctionScale", Kind.DOUBLE3);
        bindSetter("SetLightFunctionFadeDistance", "LightFunctionFadeDistance", Kind.FLOAT);
        bindSetter("SetLightFunctionDisabledBrightness", "DisabledBrightness", Kind.FLOAT);
        for (String field : new String[] {"DynamicShadowDistanceMovableLight", "DynamicShadowDistanceStationaryLight",
                "CascadeDistributionExponent", "CascadeTransitionFraction", "ShadowDistanceFadeoutFraction"})
            setter("Set" + field, Kind.FLOAT, (value, defaults) -> apply(field, value));
        setter("SetDynamicShadowCascades", Kind.INT, (value, defaults) -> apply("DynamicShadowCascades", value));
        setter("SetLightColor", Kind.LINEAR_COLOR, (value, defaults) -> {
            if (!movableData) return;
            boolean srgb = bool(defaults, "bSRGB", false);
            apply("LightColor", ComponentValue.color(colorChannel(value, 0, srgb), colorChannel(value, 1, srgb),
                    colorChannel(value, 2, srgb), colorChannel(value, 3, false)));
        });
        setter("SetForwardShadingPriority", Kind.INT, (value, defaults) -> {
            if (movableData) apply("ForwardShadingPriority", ComponentValue.integer(Math.max(0, (int) value.number(0))));
        });
        setter("SetLightingChannels", Kind.BOOL, (first, defaults) -> {
            apply("LightingChannels.bChannel0", first);
            apply("LightingChannels.bChannel1", ComponentValue.bool(bool(defaults, "bChannel1", false)));
            apply("LightingChannels.bChannel2", ComponentValue.bool(bool(defaults, "bChannel2", false)));
        });
        onChange(change -> { if (change == Change.VISIBILITY) invalidate(); });
    }

    public @Nullable SceneDirectionalLight frame(EffectDefinition effect) {
        requireAlive();
        boolean allowStatic = NiagaraPlatforms.snapshot().staticLightingAllowed();
        if (dirty || staticLightingAllowed != allowStatic) rebuild(allowStatic);
        var current = proxy;
        if (current == null) return null;
        sourceTransformNoScale(lightToWorld);
        Vec3 direction = new Vec3(-lightToWorld.m00(), -lightToWorld.m01(), -lightToWorld.m02()).normalize();
        SceneDirectionalLight.Function function = null;
        if (lightFunctionMaterial != null && current.lightFunction() != null) {
            NiagaraComponentMatrices.inverse(lightToWorld, worldToLight);
            function = new SceneDirectionalLight.Function(effect, lightFunctionMaterial, current.lightFunction(), worldToLight);
        }
        return new SceneDirectionalLight(direction, current.color(), current.forwardPriority(), current.lightingChannels(),
                current.affectsTranslucency(), current.sourceRadius(), current.softSourceRadius(),
                current.diffuseScale(), current.specularScale(), function, current.cascades(),
                current.volumetricScattering(), current.castVolumetricShadow(), working.luminance(current.color()),
                current.staticShadow(), identity, current.atmosphere());
    }

    private void rebuild(boolean allowStatic) {
        dirty = false;
        staticLightingAllowed = allowStatic;
        proxy = null;
        float intensity = floating("Intensity");
        if (!visible() || value("bAffectsWorld").number(0) == 0 || !(intensity > 0)) return;
        boolean hasStaticShadowing = !movable && (allowStatic || !movableData);
        boolean precomputedValid = hasStaticShadowing && allowStatic && staticShadow != null;
        if (!movableData && precomputedValid) return;
        var energy = PointLightPhotometry.colored(value("LightColor"), intensity, value("bUseTemperature").number(0) != 0,
                floating("Temperature"), working);
        int channels = 0;
        for (int channel = 0; channel < 3; channel++)
            if (value("LightingChannels.bChannel" + channel).number(0) != 0) channels |= 1 << channel;
        var scale = value("LightFunctionScale");
        var function = lightFunctionMaterial == null ? null : new LightFunctionSettings(
                new Vec3(scale.number(0), scale.number(1), scale.number(2)), floating("LightFunctionFadeDistance"), floating("DisabledBrightness"));
        boolean castsShadow = value("CastShadows").number(0) != 0;
        boolean castsStaticShadow = castsShadow && value("CastStaticShadows").number(0) != 0;
        boolean unbuiltPreview = hasStaticShadowing && !precomputedValid;
        boolean castsCascades = castsShadow && movableData && value("CastDynamicShadows").number(0) != 0
                || castsStaticShadow && unbuiltPreview;
        var cascades = castsCascades
                ? new SceneDirectionalLight.Cascades(floating(movable || !allowStatic ? "DynamicShadowDistanceMovableLight" : "DynamicShadowDistanceStationaryLight"),
                        (int) value("DynamicShadowCascades").number(0), floating("FarShadowDistance"), (int) value("FarShadowCascadeCount").number(0),
                        floating("CascadeDistributionExponent"), floating("CascadeTransitionFraction"), floating("ShadowDistanceFadeoutFraction"),
                        floating("ShadowCascadeBiasDistribution"), floating("ShadowBias"), floating("ShadowSlopeBias"), unbuiltPreview)
                : null;
        var diskColor = value("AtmosphereSunDiskColorScale");
        var atmosphere = value("bAtmosphereSunLight").number(0) != 0 ? new Atmosphere(
                (int) value("AtmosphereSunLightIndex").number(0), value("bPerPixelAtmosphereTransmittance").number(0) != 0,
                Atmosphere.cosHalfApex(floating("LightSourceAngle")),
                new Vec3(diskColor.number(0), diskColor.number(1), diskColor.number(2))) : null;
        proxy = new Proxy(new Vec3(energy.number(0), energy.number(1), energy.number(2)),
                (int) value("ForwardShadingPriority").number(0), channels, value("bAffectTranslucentLighting").number(0) != 0,
                radius("LightSourceAngle"), radius("LightSourceSoftAngle"), clampedScale(floating("DiffuseScale")),
                clampedScale(floating("SpecularScale")), function, cascades,
                Math.max(0, floating("VolumetricScatteringIntensity")), value("bCastVolumetricShadow").number(0) != 0,
                castsStaticShadow && precomputedValid ? staticShadow : null, atmosphere);
    }

    private void bindSetter(String method, String field, Kind kind) {
        setter(method, kind, (value, defaults) -> { if (movableData) apply(field, value); });
    }
    private void scaleSetter(String field) {
        setter("Set" + field, Kind.FLOAT, (value, defaults) -> {
            if (movableData) apply(field, scalar(clampedScale((float) value.number(0))));
        });
    }
    private void invalidate() { dirty = true; }
    private float floating(String field) { return (float) value(field).number(0); }
    private float radius(String field) { return (float) Math.sin(.5f * Math.toRadians(floating(field))); }
    private static float clampedScale(float value) { return UnrealRendererMath.clamp(value, 0, 1); }
    private static ComponentValue scalar(float value) { return ComponentValue.floating(Kind.FLOAT, value, 0, 0, 0); }
    private static ComponentValue flag(@Nullable JsonObject template, String field, boolean fallback) {
        return ComponentValue.bool(template != null && template.has(field) ? template.get(field).getAsBoolean() : fallback);
    }
    private static int colorChannel(ComponentValue color, int index, boolean srgb) {
        float value = (float) color.number(index);
        return srgb ? SourceColorMath.srgb8(value) : SourceColorMath.unorm8(value);
    }
    private static SceneDirectionalLight.@Nullable StaticShadow staticShadow(JsonObject template) {
        if (!template.has("lumenfx_static_shadow")) return null;
        var shadow = template.getAsJsonObject("lumenfx_static_shadow");
        var values = shadow.getAsJsonArray("world_to_light");
        if (values.size() != 16) throw new IllegalArgumentException("Static directional shadow needs a 4x4 source transform");
        var matrix = new double[16];
        for (int index = 0; index < matrix.length; index++) matrix[index] = values.get(index).getAsDouble();
        return new SceneDirectionalLight.StaticShadow(Identifier.parse(shadow.get("texture").getAsString()), new Matrix4d().set(matrix));
    }
}
