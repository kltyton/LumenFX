package com.kltyton.lumenfx.client.component.light;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.component.ComponentFactory;
import com.kltyton.lumenfx.api.client.component.ComponentValue;
import com.kltyton.lumenfx.api.client.component.ComponentValue.Kind;
import com.kltyton.lumenfx.client.component.SceneEffectComponent;
import com.kltyton.lumenfx.client.render.light.photometry.PointLightPhotometry;
import com.kltyton.lumenfx.client.render.math.SourceColorMath;
import com.kltyton.lumenfx.client.render.math.SourceColorSpace;
import com.kltyton.lumenfx.client.render.math.UnrealRendererMath;
import com.kltyton.lumenfx.client.resource.component.PointLightResources;
import com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices;
import com.kltyton.lumenfx.effect.render.light.scene.PointLightShaderParameters;
import com.kltyton.lumenfx.effect.render.light.scene.LightFunctionSettings;
import com.kltyton.lumenfx.effect.render.light.scene.PointLightData;
import com.kltyton.lumenfx.effect.render.light.scene.PointShadowLightSettings;
import com.kltyton.lumenfx.effect.render.light.scene.PointLightDistanceSettings;
import com.kltyton.lumenfx.effect.render.light.scene.ContactShadowSettings;
import com.kltyton.lumenfx.effect.render.light.scene.SpotLightCone;
import com.kltyton.lumenfx.effect.render.light.scene.RectLightShape;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.Consumer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.jspecify.annotations.Nullable;

/** Point and spot components share lightweight color updates and deferred scene-proxy recreation. */
public final class PointLightEffectComponent extends SceneEffectComponent {
    private final long identity = com.kltyton.lumenfx.effect.render.scene.ScenePrimitiveIds.next();
    public record Frame(PointLightShaderParameters shader, float radius, float volumetricScattering, float indirectLighting,
                        boolean castDynamicShadow, boolean castStaticShadow, boolean allowMegaLights, LightFunctionSettings lightFunction,
                        @Nullable Matrix4dc sourceWorldToLight, PointShadowLightSettings shadowSettings,
                        PointLightDistanceSettings distanceSettings, int lightingChannels, ContactShadowSettings contactShadows,
                        boolean castVolumetricShadow, boolean transmitsThroughSurfaces) implements PointLightData {
        public Frame {
            if (sourceWorldToLight != null) sourceWorldToLight = new Matrix4d(sourceWorldToLight);
        }
        @Override public @Nullable Matrix4dc sourceWorldToLight() {
            return sourceWorldToLight == null ? null : new Matrix4d(sourceWorldToLight);
        }
        @Override public Matrix4d worldToLight(Matrix4d destination) {
            return destination.set(java.util.Objects.requireNonNull(sourceWorldToLight, "Point light projection needs its frozen source transform"));
        }
        public Frame(PointLightShaderParameters shader, float radius, float volumetricScattering, float indirectLighting,
                     boolean castDynamicShadow, boolean castStaticShadow, boolean allowMegaLights, LightFunctionSettings lightFunction,
                     @Nullable Matrix4dc sourceWorldToLight, PointShadowLightSettings shadowSettings, PointLightDistanceSettings distanceSettings,
                     int lightingChannels, ContactShadowSettings contactShadows, boolean castVolumetricShadow) {
            this(shader, radius, volumetricScattering, indirectLighting, castDynamicShadow, castStaticShadow, allowMegaLights,
                    lightFunction, sourceWorldToLight, shadowSettings, distanceSettings, lightingChannels, contactShadows, castVolumetricShadow, false);
        }
        public Frame(PointLightShaderParameters shader, float radius, float volumetricScattering, float indirectLighting,
                     boolean castDynamicShadow, boolean castStaticShadow, boolean allowMegaLights, LightFunctionSettings lightFunction,
                     @Nullable Matrix4dc sourceWorldToLight, PointShadowLightSettings shadowSettings, PointLightDistanceSettings distanceSettings,
                     int lightingChannels, ContactShadowSettings contactShadows) {
            this(shader, radius, volumetricScattering, indirectLighting, castDynamicShadow, castStaticShadow, allowMegaLights,
                    lightFunction, sourceWorldToLight, shadowSettings, distanceSettings, lightingChannels, contactShadows, false);
        }
        public Frame(PointLightShaderParameters shader, float radius, float volumetricScattering, float indirectLighting,
                     boolean castDynamicShadow, boolean castStaticShadow, boolean allowMegaLights, LightFunctionSettings lightFunction,
                     @Nullable Matrix4dc sourceWorldToLight, PointShadowLightSettings shadowSettings,
                     PointLightDistanceSettings distanceSettings, int lightingChannels) {
            this(shader, radius, volumetricScattering, indirectLighting, castDynamicShadow, castStaticShadow, allowMegaLights,
                    lightFunction, sourceWorldToLight, shadowSettings, distanceSettings, lightingChannels, ContactShadowSettings.DEFAULT);
        }
        public Frame(PointLightShaderParameters shader, float radius, float volumetricScattering, float indirectLighting,
                     boolean castDynamicShadow, boolean castStaticShadow, boolean allowMegaLights, LightFunctionSettings lightFunction,
                     @Nullable Matrix4dc sourceWorldToLight, PointShadowLightSettings shadowSettings, PointLightDistanceSettings distanceSettings) {
            this(shader, radius, volumetricScattering, indirectLighting, castDynamicShadow, castStaticShadow, allowMegaLights,
                    lightFunction, sourceWorldToLight, shadowSettings, distanceSettings, 1);
        }
        public Frame(PointLightShaderParameters shader, float radius, float volumetricScattering, float indirectLighting,
                     boolean castDynamicShadow, boolean castStaticShadow, boolean allowMegaLights, LightFunctionSettings lightFunction,
                     @Nullable Matrix4dc sourceWorldToLight, PointShadowLightSettings shadowSettings) {
            this(shader, radius, volumetricScattering, indirectLighting, castDynamicShadow, castStaticShadow, allowMegaLights,
                    lightFunction, sourceWorldToLight, shadowSettings, PointLightDistanceSettings.DEFAULT);
        }
        public Frame(PointLightShaderParameters shader, float radius, float volumetricScattering, float indirectLighting,
                     boolean castDynamicShadow, boolean castStaticShadow, boolean allowMegaLights, LightFunctionSettings lightFunction,
                     @Nullable Matrix4dc sourceWorldToLight) {
            this(shader, radius, volumetricScattering, indirectLighting, castDynamicShadow, castStaticShadow, allowMegaLights,
                    lightFunction, sourceWorldToLight, PointShadowLightSettings.DEFAULT);
        }
        public Frame(PointLightShaderParameters shader, float radius, float volumetricScattering, float indirectLighting,
                     boolean castDynamicShadow, boolean castStaticShadow, boolean allowMegaLights, LightFunctionSettings lightFunction) {
            this(shader, radius, volumetricScattering, indirectLighting, castDynamicShadow, castStaticShadow, allowMegaLights, lightFunction, null);
        }
        public Frame(PointLightShaderParameters shader, float radius, float volumetricScattering, float indirectLighting,
                     boolean castDynamicShadow, boolean castStaticShadow, boolean allowMegaLights) {
            this(shader, radius, volumetricScattering, indirectLighting, castDynamicShadow, castStaticShadow, allowMegaLights, LightFunctionSettings.DEFAULT);
        }
    }
    private enum Mobility { STATIC, STATIONARY, MOVABLE }
    enum Type { POINT, SPOT, RECT }
    private final SourceColorSpace working;
    private final PointLightPhotometry.@Nullable IesBrightness ies;
    private final PointLightPhotometry.Unit unit;
    private final Mobility mobility;
    private final Type type;
    private final boolean legacyRectEv;
    private final @Nullable PointLightResources resources;
    private final Matrix4d lightToWorld = new Matrix4d(), worldToLight = new Matrix4d();
    private final List<Consumer<PointLightEffectComponent>> colorListeners = new ArrayList<>();
    private boolean renderStateDirty = true;
    private @Nullable Proxy proxy;

    private static final class Proxy {
        ComponentValue energy;
        float radius, falloff, sourceRadius, softRadius, sourceLength, specular, diffuse, exposure, scattering, indirect;
        boolean inverseSquared, translucent, castDynamic, castStatic, castVolumetric, megaLights, transmission;
        LightFunctionSettings lightFunction;
        PointShadowLightSettings shadow;
        PointLightDistanceSettings distance;
        int lightingChannels;
        ContactShadowSettings contact;
        @Nullable SpotLightCone cone;
        @Nullable RectLightShape rect;
    }

    private record Initial(Template scene, Map<String, ComponentValue> properties, PointLightPhotometry.Unit unit,
                           Mobility mobility, SourceColorSpace working, PointLightPhotometry.@Nullable IesBrightness ies,
                           @Nullable PointLightResources resources, Type type, boolean legacyRectEv) { }

    public static ComponentFactory.Prepared prepare(JsonObject template) {
        return prepare(template, Type.POINT);
    }

    static ComponentFactory.Prepared prepare(JsonObject template, Type type) {
        PointLightResources resources = PointLightResources.read(template);
        Initial initial = initial(template, resources.workingColorSpace(), resources.ies() == null ? null : resources.ies().brightness(), resources, type);
        return context -> new PointLightEffectComponent(context, initial);
    }

    private static Initial initial(JsonObject template, SourceColorSpace working, PointLightPhotometry.@Nullable IesBrightness ies,
                                   @Nullable PointLightResources resources, Type type) {
        java.util.Objects.requireNonNull(working);
        if (template.has("IESTexture") && !template.get("IESTexture").isJsonNull()
                && !template.get("IESTexture").getAsString().equals("None") && ies == null)
            throw new IllegalArgumentException("Point light template requires its prepared IES brightness data");
        var unit = PointLightPhotometry.Unit.source(string(template, "IntensityUnits", "Unitless"));
        String sourceMobility = string(template, "Mobility", "Movable");
        int separator = sourceMobility.lastIndexOf("::");
        String mobilityName = separator < 0 ? sourceMobility : sourceMobility.substring(separator + 2);
        Mobility mobility = switch (mobilityName) {
            case "Static" -> Mobility.STATIC; case "Stationary" -> Mobility.STATIONARY; case "Movable" -> Mobility.MOVABLE;
            default -> throw new IllegalArgumentException("Unknown source component mobility " + sourceMobility);
        };
        Map<String, ComponentValue> properties = new LinkedHashMap<>();
        for (var entry : Map.ofEntries(Map.entry("Intensity", 5000f), Map.entry("AttenuationRadius", 1000f),
                Map.entry("LightFalloffExponent", 8f), Map.entry("SourceRadius", 0f), Map.entry("SoftSourceRadius", 0f),
                Map.entry("SourceLength", 0f), Map.entry("InverseExposureBlend", 0f), Map.entry("SpecularScale", 1f),
                Map.entry("DiffuseScale", 1f), Map.entry("Temperature", 6500f), Map.entry("IESBrightnessScale", 1f),
                Map.entry("VolumetricScatteringIntensity", 1f), Map.entry("IndirectLightingIntensity", 1f),
                Map.entry("LightFunctionFadeDistance", 100000f), Map.entry("DisabledBrightness", .5f),
                Map.entry("ShadowResolutionScale", 1f), Map.entry("ShadowBias", .5f), Map.entry("ShadowSlopeBias", .5f),
                Map.entry("ShadowSharpen", 0f), Map.entry("MaxDrawDistance", 0f), Map.entry("MaxDistanceFadeRange", 0f),
                Map.entry("ContactShadowLength", 0f), Map.entry("ContactShadowCastingIntensity", 1f),
                Map.entry("ContactShadowNonCastingIntensity", 0f)).entrySet())
            properties.put(entry.getKey(), ComponentValue.floating(Kind.FLOAT, (float)number(template, entry.getKey(), entry.getValue()), 0, 0, 0));
        if (type == Type.SPOT) {
            properties.put("InnerConeAngle", ComponentValue.floating(Kind.FLOAT, (float)number(template, "InnerConeAngle", 0), 0, 0, 0));
            properties.put("OuterConeAngle", ComponentValue.floating(Kind.FLOAT, (float)number(template, "OuterConeAngle", 44), 0, 0, 0));
        }
        if (type == Type.RECT) {
            for (var field : Map.of("SourceWidth", 64f, "SourceHeight", 64f, "BarnDoorAngle", 88f,
                    "BarnDoorLength", 20f, "LightFunctionConeAngle", 0f).entrySet())
                properties.put(field.getKey(), ComponentValue.floating(Kind.FLOAT, (float)number(template, field.getKey(), field.getValue()), 0, 0, 0));
            JsonObject offset = template.getAsJsonObject("SourceTextureOffset");
            JsonObject scale = template.getAsJsonObject("SourceTextureScale");
            properties.put("SourceTextureOffset", ComponentValue.floating(Kind.FLOAT2, (float)number(offset, "X", 0), (float)number(offset, "Y", 0), 0, 0));
            properties.put("SourceTextureScale", ComponentValue.floating(Kind.FLOAT2, (float)number(scale, "X", 1), (float)number(scale, "Y", 1), 0, 0));
        }
        for (var entry : Map.ofEntries(Map.entry("bUseInverseSquaredFalloff", true), Map.entry("bUseTemperature", false),
                Map.entry("bUseIESBrightness", false), Map.entry("bAffectsWorld", true), Map.entry("bAffectTranslucentLighting", true),
                Map.entry("CastShadows", true), Map.entry("CastDynamicShadows", true), Map.entry("CastStaticShadows", true),
                Map.entry("bCastVolumetricShadow", false), Map.entry("bTransmission", false),
                Map.entry("bAllowMegaLights", true), Map.entry("bForceCachedShadowsForMovablePrimitives", false),
                Map.entry("ContactShadowLengthInWS", false)).entrySet())
            properties.put(entry.getKey(), ComponentValue.bool(template.has(entry.getKey()) ? template.get(entry.getKey()).getAsBoolean() : entry.getValue()));
        JsonObject color = template.getAsJsonObject("LightColor");
        properties.put("LightColor", ComponentValue.color((int)number(color, "R", 255), (int)number(color, "G", 255),
                (int)number(color, "B", 255), (int)number(color, "A", 255)));
        JsonObject scale = template.getAsJsonObject("LightFunctionScale");
        properties.put("LightFunctionScale", ComponentValue.doubles(Kind.DOUBLE3, number(scale, "X", 1024),
                number(scale, "Y", 1024), number(scale, "Z", 1024), 0));
        JsonObject channels = template.getAsJsonObject("LightingChannels");
        for (int channel = 0; channel < 3; channel++) {
            String name = "bChannel" + channel;
            properties.put("LightingChannels." + name, ComponentValue.bool(channels != null && channels.has(name)
                    ? channels.get(name).getAsBoolean() : channel == 0));
        }
        return new Initial(prepareTemplate(template), Map.copyOf(properties), unit, mobility, working, ies, resources, type,
                type == Type.RECT && template.has("bLightRequiresBrokenEVMath") && template.get("bLightRequiresBrokenEVMath").getAsBoolean());
    }

    /** Resources are prepared with the owning effect; construction performs no resource I/O. */
    public PointLightEffectComponent(ComponentFactory.Context context, JsonObject template, PointLightResources resources) {
        this(context, template, resources.workingColorSpace(), resources.ies() == null ? null : resources.ies().brightness(), resources);
    }

    public PointLightEffectComponent(ComponentFactory.Context context, JsonObject template, SourceColorSpace working,
                                     PointLightPhotometry.@Nullable IesBrightness ies) {
        this(context, template, working, ies, null);
    }

    private PointLightEffectComponent(ComponentFactory.Context context, JsonObject template, SourceColorSpace working,
                                      PointLightPhotometry.@Nullable IesBrightness ies, @Nullable PointLightResources resources) {
        this(context, initial(template, working, ies, resources, Type.POINT));
    }

    private PointLightEffectComponent(ComponentFactory.Context context, Initial initial) {
        super(context, initial.scene());
        working = initial.working(); ies = initial.ies(); resources = initial.resources();
        unit = initial.unit(); mobility = initial.mobility(); type = initial.type(); legacyRectEv = initial.legacyRectEv();
        if (type == Type.SPOT) {
            property(initial, "InnerConeAngle", this::recreate);
            property(initial, "OuterConeAngle", this::recreate);
            scalarSetter("SetInnerConeAngle", "InnerConeAngle", false, false);
            scalarSetter("SetOuterConeAngle", "OuterConeAngle", false, false);
        }
        if (type == Type.RECT) {
            for (String field : new String[] {"SourceWidth", "SourceHeight", "BarnDoorAngle", "BarnDoorLength",
                    "LightFunctionConeAngle", "SourceTextureOffset", "SourceTextureScale"}) property(initial, field, this::recreate);
            scalarSetter("SetSourceWidth", "SourceWidth", false, false);
            scalarSetter("SetSourceHeight", "SourceHeight", false, false);
            rectBarnSetter("BarnDoorAngle", 0f, 88f);
            rectBarnSetter("BarnDoorLength", .1f, Float.POSITIVE_INFINITY);
        }
        property(initial, "Intensity", this::updateIntensity);
        property(initial, "AttenuationRadius", this::updateRadius);
        property(initial, "LightFalloffExponent", this::recreate);
        property(initial, "SourceRadius", this::recreate);
        property(initial, "SoftSourceRadius", this::recreate);
        property(initial, "SourceLength", this::recreate);
        property(initial, "InverseExposureBlend", this::recreate);
        property(initial, "SpecularScale", this::recreate);
        property(initial, "DiffuseScale", this::recreate);
        property(initial, "Temperature", this::updateColorAndNotify);
        property(initial, "IESBrightnessScale", this::recreate);
        property(initial, "VolumetricScatteringIntensity", this::updateColorAndNotify);
        property(initial, "IndirectLightingIntensity", this::updateColorAndNotify);
        property(initial, "LightFunctionScale", this::recreate);
        property(initial, "LightFunctionFadeDistance", this::recreate);
        property(initial, "DisabledBrightness", this::recreate);
        for (String field : new String[] {"ShadowResolutionScale", "ShadowBias", "ShadowSlopeBias", "ShadowSharpen"})
            property(initial, field, this::recreate);
        property(initial, "MaxDrawDistance", this::recreate);
        property(initial, "MaxDistanceFadeRange", this::recreate);
        for (String field : new String[] {"ContactShadowLength", "ContactShadowCastingIntensity", "ContactShadowNonCastingIntensity", "ContactShadowLengthInWS"})
            property(initial, field, this::recreate);
        for (int channel = 0; channel < 3; channel++) property(initial, "LightingChannels.bChannel" + channel, this::recreate);
        for (String field : new String[] {"bUseInverseSquaredFalloff", "bUseTemperature", "bUseIESBrightness", "bAffectsWorld",
                "bAffectTranslucentLighting", "CastShadows", "CastDynamicShadows", "CastStaticShadows", "bCastVolumetricShadow", "bAllowMegaLights",
                "bForceCachedShadowsForMovablePrimitives"})
            property(initial, field, this::recreate);
        property(initial, "LightColor", this::updateColor);
        property(initial, "bTransmission", this::updateTransmission);
        for (String field : new String[] {"Intensity", "LightFalloffExponent", "SourceRadius", "SoftSourceRadius", "SourceLength",
                "InverseExposureBlend", "Temperature", "IESBrightnessScale", "VolumetricScatteringIntensity", "IndirectLightingIntensity", "LightFunctionFadeDistance"})
            scalarSetter("Set" + field, field, false, false);
        scalarSetter("SetAttenuationRadius", "AttenuationRadius", true, false);
        scalarSetter("SetSpecularScale", "SpecularScale", false, true);
        scalarSetter("SetDiffuseScale", "DiffuseScale", false, true);
        scalarSetter("SetLightFunctionDisabledBrightness", "DisabledBrightness", false, false);
        scalarSetter("SetShadowBias", "ShadowBias", false, false);
        scalarSetter("SetShadowSlopeBias", "ShadowSlopeBias", false, false);
        scalarSetter("SetMaxDistanceFadeRange", "MaxDistanceFadeRange", false, false);
        setter("SetMaxDrawDistance", Kind.FLOAT, (requested, defaults) -> {
            float next = UnrealRendererMath.max((float)requested.number(0), 0f);
            if (dynamic(false) && floating("MaxDrawDistance") != next)
                applyChanged("MaxDrawDistance", ComponentValue.floating(Kind.FLOAT, next, 0, 0, 0));
        });
        setter("SetLightFunctionScale", Kind.DOUBLE3, (requested, defaults) -> {
            ComponentValue current = value("LightFunctionScale");
            if (dynamic(false) && (current.number(0) != requested.number(0) || current.number(1) != requested.number(1)
                    || current.number(2) != requested.number(2))) applyChanged("LightFunctionScale", requested);
        });
        booleanSetter("SetUseInverseSquaredFalloff", "bUseInverseSquaredFalloff");
        booleanSetter("SetUseTemperature", "bUseTemperature");
        booleanSetter("SetUseIESBrightness", "bUseIESBrightness");
        booleanSetter("SetAffectTranslucentLighting", "bAffectTranslucentLighting");
        booleanSetter("SetCastShadows", "CastShadows");
        booleanSetter("SetCastVolumetricShadow", "bCastVolumetricShadow");
        booleanSetter("SetTransmission", "bTransmission");
        booleanSetter("SetForceCachedShadowsForMovablePrimitives", "bForceCachedShadowsForMovablePrimitives");
        setter("SetLightingChannels", Kind.BOOL, (requested, defaults) -> {
            boolean first = requested.number(0) != 0, second = bool(defaults, "bChannel1", false), third = bool(defaults, "bChannel2", false);
            if (flag("LightingChannels.bChannel0") == first && flag("LightingChannels.bChannel1") == second
                    && flag("LightingChannels.bChannel2") == third) return;
            java.util.Objects.requireNonNull(property("LightingChannels.bChannel0")).set(ComponentValue.bool(first));
            java.util.Objects.requireNonNull(property("LightingChannels.bChannel1")).set(ComponentValue.bool(second));
            java.util.Objects.requireNonNull(property("LightingChannels.bChannel2")).set(ComponentValue.bool(third));
            recreate();
        });
        setter("SetLightFColor", Kind.BYTE_COLOR, (value, defaults) -> { if (dynamic(false)) apply("LightColor", value); });
        setter("SetLightColor", Kind.LINEAR_COLOR, (value, defaults) -> {
            boolean srgb = bool(defaults, "bSRGB", false);
            float r = (float)value.number(0), g = (float)value.number(1), b = (float)value.number(2), a = (float)value.number(3);
            ComponentValue bytes = ComponentValue.color(srgb ? SourceColorMath.srgb8(r) : SourceColorMath.unorm8(r),
                    srgb ? SourceColorMath.srgb8(g) : SourceColorMath.unorm8(g), srgb ? SourceColorMath.srgb8(b) : SourceColorMath.unorm8(b),
                    SourceColorMath.unorm8(a));
            if (dynamic(false)) apply("LightColor", bytes);
        });
        onChange(change -> { if (change == Change.VISIBILITY) recreate(); });
    }

    public void onColorAndBrightness(Consumer<PointLightEffectComponent> listener) {
        requireAlive(); colorListeners.add(java.util.Objects.requireNonNull(listener));
    }

    public @Nullable Identifier iesTexture() { return resources == null || resources.ies() == null ? null : resources.ies().texture(); }
    public long lightIdentity() { return identity; }
    public @Nullable String sourceLightFunction() { return resources == null ? null : resources.sourceLightFunction(); }
    public @Nullable String lightFunctionMaterial() { return resources == null ? null : resources.lightFunctionMaterial(); }

    public @Nullable Frame frame(int iesAtlasIndex, int lightFunctionAtlasIndex) {
        requireAlive();
        if (renderStateDirty) rebuild();
        Proxy current = proxy;
        if (current == null) return null;
        sourceTransformNoScale(lightToWorld);
        NiagaraComponentMatrices.inverse(lightToWorld, worldToLight);
        Vec3 color = new Vec3(current.energy.number(0), current.energy.number(1), current.energy.number(2));
        if (current.rect != null) color = color.scale(1f / current.rect.areaNormalization());
        var shader = PointLightShaderParameters.prepare(lightToWorld, worldToLight, 1f / Math.max(.00001f, current.radius), color,
                current.inverseSquared, current.falloff, current.specular, current.diffuse, current.sourceRadius, current.softRadius,
                current.sourceLength, current.exposure, iesAtlasIndex, lightFunctionAtlasIndex, current.translucent, current.cone, current.rect);
        return new Frame(shader, current.radius, current.scattering, current.indirect, current.castDynamic, current.castStatic,
                current.megaLights, current.lightFunction, worldToLight, current.shadow, current.distance, current.lightingChannels,
                current.contact, current.castVolumetric, current.transmission);
    }

    private void rebuild() {
        if (proxy != null) com.kltyton.lumenfx.client.VfxEngine.INSTANCE.invalidatePointLight(identity);
        renderStateDirty = false; proxy = null;
        if (!visible() || !flag("bAffectsWorld") || !validIntensity()) return;
        Proxy next = new Proxy();
        next.cone = sourceCone();
        if (type == Type.RECT) {
            ComponentValue scale = value("SourceTextureScale"), offset = value("SourceTextureOffset");
            next.rect = RectLightShape.fromSource(floating("SourceWidth"), floating("SourceHeight"), floating("BarnDoorAngle"),
                    floating("BarnDoorLength"), floating("LightFunctionConeAngle"),
                    (float)scale.number(0), (float)scale.number(1), (float)offset.number(0), (float)offset.number(1),
                    resources == null ? null : resources.rectSourceTexture());
        }
        next.energy = energy(next.cone); next.radius = floating("AttenuationRadius"); next.falloff = floating("LightFalloffExponent");
        next.sourceRadius = floating("SourceRadius"); next.softRadius = floating("SoftSourceRadius"); next.sourceLength = floating("SourceLength");
        next.specular = floating("SpecularScale"); next.diffuse = floating("DiffuseScale"); next.exposure = floating("InverseExposureBlend");
        next.scattering = UnrealRendererMath.max(floating("VolumetricScatteringIntensity"), 0f); next.indirect = floating("IndirectLightingIntensity");
        next.inverseSquared = type == Type.RECT || flag("bUseInverseSquaredFalloff");
        next.translucent = flag("bAffectTranslucentLighting");
        next.castDynamic = flag("CastShadows") && flag("CastDynamicShadows");
        next.castStatic = flag("CastShadows") && flag("CastStaticShadows"); next.megaLights = flag("bAllowMegaLights");
        next.castVolumetric = flag("bCastVolumetricShadow");
        next.transmission = sourceTransmission(next.castDynamic);
        ComponentValue scale = value("LightFunctionScale");
        next.lightFunction = new LightFunctionSettings(new Vec3(scale.number(0), scale.number(1), scale.number(2)),
                floating("LightFunctionFadeDistance"), floating("DisabledBrightness"));
        next.shadow = new PointShadowLightSettings(floating("ShadowResolutionScale"), floating("ShadowBias"),
                floating("ShadowSlopeBias"), floating("ShadowSharpen"), mobility != Mobility.MOVABLE,
                flag("bForceCachedShadowsForMovablePrimitives"));
        next.distance = new PointLightDistanceSettings(floating("MaxDrawDistance"), floating("MaxDistanceFadeRange"));
        next.lightingChannels = (flag("LightingChannels.bChannel0") ? 1 : 0) | (flag("LightingChannels.bChannel1") ? 2 : 0)
                | (flag("LightingChannels.bChannel2") ? 4 : 0);
        next.contact = new ContactShadowSettings(floating("ContactShadowLength"), flag("ContactShadowLengthInWS"),
                floating("ContactShadowCastingIntensity"), floating("ContactShadowNonCastingIntensity"));
        proxy = next;
    }

    private @Nullable SpotLightCone sourceCone() {
        return type == Type.SPOT ? SpotLightCone.fromDegrees(floating("InnerConeAngle"), floating("OuterConeAngle")) : null;
    }

    private ComponentValue energy(@Nullable SpotLightCone cone) {
        float intensity = floating("Intensity"), radius = floating("SourceRadius"), length = floating("SourceLength");
        float iesScale = floating("IESBrightnessScale");
        boolean inverseSquared = flag("bUseInverseSquaredFalloff"), useIes = flag("bUseIESBrightness");
        float brightness = switch (type) {
            case POINT -> PointLightPhotometry.brightness(intensity, unit, inverseSquared, radius, length, ies, useIes, iesScale);
            case SPOT -> PointLightPhotometry.brightness(intensity, unit, inverseSquared, radius, length, ies, useIes, iesScale,
                    java.util.Objects.requireNonNull(cone).cosineOuter());
            case RECT -> PointLightPhotometry.rectBrightness(intensity, unit, floating("SourceWidth"), floating("SourceHeight"),
                    legacyRectEv, ies, useIes, iesScale);
        };
        return PointLightPhotometry.colored(value("LightColor"), brightness, flag("bUseTemperature"), floating("Temperature"), working);
    }
    private boolean validIntensity() { return floating("Intensity") > 0 || unit == PointLightPhotometry.Unit.EV; }
    private void recreate() { renderStateDirty = true; }
    private void updateTransmission() {
        Proxy current = proxy;
        if (current != null) current.transmission = sourceTransmission(current.castDynamic);
    }
    private boolean sourceTransmission(boolean castsDynamicShadow) {
        return flag("bTransmission") && castsDynamicShadow && mobility == Mobility.MOVABLE;
    }
    private void updateIntensity() {
        boolean added = proxy != null;
        if (added != validIntensity()) recreate(); else if (added) updateColor();
        notifyColor();
    }
    private void updateColor() {
        if (proxy == null) return;
        proxy.energy = energy(sourceCone()); proxy.indirect = floating("IndirectLightingIntensity");
        proxy.scattering = floating("VolumetricScatteringIntensity");
    }
    private void updateColorAndNotify() { updateColor(); notifyColor(); }
    private void notifyColor() { for (var listener : colorListeners) listener.accept(this); }
    private void updateRadius() {
        if (flag("CastShadows")) recreate();
        else if (proxy != null) proxy.radius = floating("AttenuationRadius");
    }
    private boolean dynamic(boolean movableOnly) { return mobility != Mobility.STATIC && (!movableOnly || mobility != Mobility.STATIONARY); }
    private void scalarSetter(String method, String field, boolean movableOnly, boolean clamp) {
        setter(method, Kind.FLOAT, (value, defaults) -> {
            float number = (float)value.number(0);
            if (clamp) number = number < 0 ? 0 : number < 1 ? number : 1;
            if (dynamic(movableOnly) && floating(field) != number)
                applyChanged(field, ComponentValue.floating(Kind.FLOAT, number, 0, 0, 0));
        });
    }
    private void rectBarnSetter(String field, float min, float max) {
        setter("Set" + field, Kind.FLOAT, (value, defaults) -> {
            float authored = (float)value.number(0);
            if (dynamic(false) && floating(field) != authored)
                applyChanged(field, ComponentValue.floating(Kind.FLOAT, UnrealRendererMath.clamp(authored, min, max), 0, 0, 0));
        });
    }
    private void booleanSetter(String method, String field) {
        setter(method, Kind.BOOL, (value, defaults) -> {
            boolean requested = value.number(0) != 0;
            if (dynamic(false) && flag(field) != requested) applyChanged(field, ComponentValue.bool(requested));
        });
    }
    private void property(Initial initial, String field, Runnable callback) { define(field, initial.properties().get(field), callback); }
    private float floating(String field) { return (float)value(field).number(0); }
    private boolean flag(String field) { return value(field).number(0) != 0; }
    private static String string(JsonObject template, String field, String fallback) { return template.has(field) ? template.get(field).getAsString() : fallback; }
    @Override public void close() {
        super.close();
        if (proxy != null) com.kltyton.lumenfx.client.VfxEngine.INSTANCE.invalidatePointLight(identity);
        proxy = null; colorListeners.clear();
    }
}
