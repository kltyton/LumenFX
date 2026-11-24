package com.kltyton.lumenfx.client.component.mesh;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.component.ComponentFactory;
import com.kltyton.lumenfx.api.client.component.ComponentValue;
import com.kltyton.lumenfx.api.client.component.EffectComponent;
import com.kltyton.lumenfx.api.client.component.PrimitiveOwnerVisibility;
import com.kltyton.lumenfx.client.component.SceneEffectComponent;
import com.kltyton.lumenfx.client.transform.SceneComponentTransform;
import com.kltyton.lumenfx.effect.EffectDefinition;
import com.kltyton.lumenfx.effect.render.depth.CustomDepthStencil;
import com.kltyton.lumenfx.effect.render.light.PrimitiveLighting;
import com.kltyton.lumenfx.effect.render.mesh.MeshRendererSettings;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/** Pooled Niagara scene primitive with an independently authored mesh and material slots. */
public final class StaticMeshEffectComponent extends SceneEffectComponent implements EffectComponent.Primitive {
    private final MeshRendererSettings renderer;
    private final @Nullable String fallbackMaterial;
    private final CustomDepthStencil.WriteMask stencilMask;
    private final int lightingChannels;

    static ComponentFactory.Prepared prepare(JsonObject source, MeshRendererSettings renderer) {
        var transform = prepareTemplate(source);
        JsonObject frozen = source.deepCopy();
        return context -> new StaticMeshEffectComponent(context, transform, frozen, renderer);
    }

    private StaticMeshEffectComponent(ComponentFactory.Context context, Template transform, JsonObject source,
                                      MeshRendererSettings renderer) {
        super(context, transform);
        this.renderer = renderer;
        var fallback = source.get("lumenfx_static_mesh_fallback");
        fallbackMaterial = fallback == null || fallback.isJsonNull() ? null : fallback.getAsString();
        stencilMask = stencilMask(source);
        var channels = source.getAsJsonObject("LightingChannels");
        lightingChannels = channels == null ? 1 : (flag(channels, "bChannel0", true) ? 1 : 0)
                | (flag(channels, "bChannel1", false) ? 2 : 0) | (flag(channels, "bChannel2", false) ? 4 : 0);
        booleanProperty(source, "bOnlyOwnerSee", "SetOnlyOwnerSee", false);
        booleanProperty(source, "bOwnerNoSee", "SetOwnerNoSee", false);
        booleanProperty(source, "CastShadow", "SetCastShadow", true);
        booleanProperty(source, "bCastDynamicShadow", "SetCastDynamicShadow", true);
        booleanProperty(source, "bCastContactShadow", "SetCastContactShadow", true);
        booleanProperty(source, "bCastHiddenShadow", "SetCastHiddenShadow", false);
        booleanProperty(source, "bReceivesDecals", "SetReceivesDecals", true);
        booleanProperty(source, "bRenderCustomDepth", "SetRenderCustomDepth", false);
        integerProperty(source, "CustomDepthStencilValue", "SetCustomDepthStencilValue");
        integerProperty(source, "ForcedLodModel", "SetForcedLodModel");
        booleanProperty(source, "bOverrideMinLOD", "SetOverrideMinLOD", false);
        integerProperty(source, "MinLOD", "SetMinLOD");
    }

    private void booleanProperty(JsonObject source, String name, String setter, boolean fallback) {
        define(name, ComponentValue.bool(flag(source, name, fallback)), () -> changed(Change.PROPERTY));
        setter(setter, ComponentValue.Kind.BOOL, (value, defaults) -> apply(name, value));
    }

    private void integerProperty(JsonObject source, String name, String setter) {
        define(name, ComponentValue.integer(source.has(name) ? source.get(name).getAsInt() : 0), () -> changed(Change.PROPERTY));
        setter(setter, ComponentValue.Kind.INT, (value, defaults) -> apply(name, value));
    }

    private boolean flag(String name) { return value(name).number(0) != 0; }

    private static boolean flag(JsonObject source, String name, boolean fallback) {
        if (!source.has(name)) return fallback;
        var flag = source.get(name).getAsJsonPrimitive();
        return flag.isNumber() ? flag.getAsInt() != 0 : flag.getAsBoolean();
    }

    @Override public void setOwnerVisibility(PrimitiveOwnerVisibility visibility) {
        apply("bOnlyOwnerSee", ComponentValue.bool(visibility.onlyOwnerSee()));
        apply("bOwnerNoSee", ComponentValue.bool(visibility.ownerNoSee()));
    }

    public @Nullable StaticMeshComponentDraw capture(EffectDefinition effect, @Nullable UUID viewActor) {
        if (!alive() || fallbackMaterial == null) return null;
        var entry = renderer.entries().getFirst();
        if (entry.asset() == null) return null;
        var visibility = new PrimitiveOwnerVisibility(flag("bOnlyOwnerSee"), flag("bOwnerNoSee"));
        boolean draw = visible() && visibility.visibleTo(context.owner().viewActors(), viewActor);
        boolean cast = flag("CastShadow") && flag("bCastDynamicShadow") && (draw || active() && flag("bCastHiddenShadow"));
        if (!draw && !cast) return null;
        var rotation = sourceRotation(new Quaterniond());
        var scale = sourceScale(new Vector3d());
        var current = pose(position(), rotation, scale);
        var previous = new SceneComponentTransform();
        previous.source(previousSourceTransform());
        var old = previousSourceTransform();
        var oldPosition = new Vec3(old.m30() * .01, old.m32() * .01, old.m31() * .01);
        return new StaticMeshComponentDraw(effect, fallbackMaterial, renderer, entry, current,
                pose(oldPosition, previous.rotation(rotation), previous.scale(scale)), context.input().actorPosition(),
                new PrimitiveLighting(lightingChannels, cast, flag("CastShadow") && flag("bCastContactShadow")),
                new CustomDepthStencil(flag("bRenderCustomDepth"),
                        Math.clamp((int)value("CustomDepthStencilValue").number(0), 0, 255), stencilMask),
                flag("bReceivesDecals"), draw, Math.max(0, (int)value("ForcedLodModel").number(0)),
                flag("bOverrideMinLOD") ? Math.max(0, (int)value("MinLOD").number(0)) : -1);
    }

    private static EffectHandle.Pose pose(Vec3 location, Quaterniond rotation, Vector3d scale) {
        return new EffectHandle.Pose(location, (float)-rotation.x, (float)-rotation.z, (float)-rotation.y, (float)rotation.w,
                (float)scale.x, (float)scale.z, (float)scale.y);
    }

    private static CustomDepthStencil.WriteMask stencilMask(JsonObject source) {
        var field = source.get("CustomDepthStencilWriteMask");
        if (field == null || field.isJsonNull()) return CustomDepthStencil.WriteMask.DEFAULT;
        String name = field.getAsString();
        String value = name.substring(name.lastIndexOf(':') + 1);
        return switch (value) {
            case "0", "ERSM_Default" -> CustomDepthStencil.WriteMask.DEFAULT;
            case "1", "ERSM_255" -> CustomDepthStencil.WriteMask.ALL;
            case "2", "ERSM_1" -> CustomDepthStencil.WriteMask.BIT_1;
            case "3", "ERSM_2" -> CustomDepthStencil.WriteMask.BIT_2;
            case "4", "ERSM_4" -> CustomDepthStencil.WriteMask.BIT_4;
            case "5", "ERSM_8" -> CustomDepthStencil.WriteMask.BIT_8;
            case "6", "ERSM_16" -> CustomDepthStencil.WriteMask.BIT_16;
            case "7", "ERSM_32" -> CustomDepthStencil.WriteMask.BIT_32;
            case "8", "ERSM_64" -> CustomDepthStencil.WriteMask.BIT_64;
            case "9", "ERSM_128" -> CustomDepthStencil.WriteMask.BIT_128;
            default -> throw new IllegalArgumentException("Unknown component CustomDepthStencilWriteMask " + name);
        };
    }
}
