package com.kltyton.lumenfx.client.render.scene.material;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.client.transform.NiagaraComponentBounds;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.niagara.component.NiagaraTranslucencySort;
import com.kltyton.lumenfx.effect.render.light.PrimitiveLighting;
import com.kltyton.lumenfx.effect.render.depth.CustomDepthStencil;
import com.kltyton.lumenfx.effect.render.material.PrimitiveCustomData;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Component poses frozen with one render frame and its committed predecessor. */
public record MaterialPrimitiveFrame(EffectHandle.Pose current, EffectHandle.Pose previous, PrimitiveLighting lighting,
                                     CustomDepthStencil customDepthStencil, boolean firstPerson,
                                     @Nullable Supplier<@Nullable NiagaraBounds> componentBounds, Vec3 actorPosition,
                                     PrimitiveCustomData customPrimitiveData, NiagaraTranslucencySort translucencySort,
                                     boolean renderInMainPass) {
    public MaterialPrimitiveFrame(EffectHandle.Pose current, EffectHandle.Pose previous, PrimitiveLighting lighting,
                                  CustomDepthStencil customDepthStencil, boolean firstPerson,
                                  @Nullable Supplier<@Nullable NiagaraBounds> componentBounds, Vec3 actorPosition,
                                  PrimitiveCustomData customPrimitiveData) {
        this(current, previous, lighting, customDepthStencil, firstPerson, componentBounds, actorPosition, customPrimitiveData,
                NiagaraTranslucencySort.DEFAULT, true);
    }

    public MaterialPrimitiveFrame(EffectHandle.Pose current, EffectHandle.Pose previous, PrimitiveLighting lighting,
                                  CustomDepthStencil customDepthStencil, boolean firstPerson,
                                  @Nullable Supplier<@Nullable NiagaraBounds> componentBounds) {
        this(current, previous, lighting, customDepthStencil, firstPerson, componentBounds, current.position(), PrimitiveCustomData.EMPTY);
    }

    public MaterialPrimitiveFrame(EffectHandle.Pose current, EffectHandle.Pose previous, PrimitiveLighting lighting,
                                  CustomDepthStencil customDepthStencil, boolean firstPerson) {
        this(current, previous, lighting, customDepthStencil, firstPerson, null);
    }

    public MaterialPrimitiveFrame(EffectHandle.Pose current, EffectHandle.Pose previous, PrimitiveLighting lighting,
                                  CustomDepthStencil customDepthStencil) {
        this(current, previous, lighting, customDepthStencil, false);
    }

    public MaterialPrimitiveFrame(EffectHandle.Pose current, EffectHandle.Pose previous, PrimitiveLighting lighting) {
        this(current, previous, lighting, CustomDepthStencil.DISABLED);
    }

    public MaterialPrimitiveFrame(EffectHandle.Pose current, EffectHandle.Pose previous) {
        this(current, previous, PrimitiveLighting.DEFAULT);
    }

    public MaterialPrimitiveFrame {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(lighting, "lighting");
        Objects.requireNonNull(customDepthStencil, "customDepthStencil");
        Objects.requireNonNull(actorPosition, "actorPosition");
        Objects.requireNonNull(customPrimitiveData, "customPrimitiveData");
        Objects.requireNonNull(translucencySort, "translucencySort");
    }

    public @Nullable Vec3 sourceBoundsOrigin() {
        var bounds = sourceLocalBounds();
        return bounds == null ? null : NiagaraComponentBounds.center(current, bounds);
    }

    public @Nullable NiagaraBounds sourceLocalBounds() {
        return componentBounds == null ? null : componentBounds.get();
    }

    public static @Nullable MaterialPrimitiveFrame forMaterial(MaterialDefinition material, EffectHandle.Pose current,
                                                               EffectHandle.Pose previous) {
        return forMaterial(material, current, previous, PrimitiveLighting.DEFAULT, CustomDepthStencil.DISABLED,
                false, null, current.position(), PrimitiveCustomData.EMPTY, NiagaraTranslucencySort.DEFAULT, true);
    }

    public static @Nullable MaterialPrimitiveFrame forMaterial(MaterialDefinition material, EffectHandle.Input input,
                                                               EffectHandle.Pose previous) {
        return forMaterial(material, input, previous, null);
    }

    public static @Nullable MaterialPrimitiveFrame forMaterial(MaterialDefinition material, EffectHandle.Input input,
                                                               EffectHandle.Pose previous,
                                                               @Nullable Supplier<@Nullable NiagaraBounds> bounds) {
        return forMaterial(material, input.pose(), previous, input.primitiveLighting(), input.customDepthStencil(),
                input.firstPerson(), bounds, input.actorPosition(), input.customPrimitiveData(), input.niagaraTranslucencySort(),
                input.renderInMainPass());
    }

    private static @Nullable MaterialPrimitiveFrame forMaterial(MaterialDefinition material, EffectHandle.Pose current,
                                                                EffectHandle.Pose previous, PrimitiveLighting lighting,
                                                                CustomDepthStencil customDepthStencil, boolean firstPerson,
                                                                @Nullable Supplier<@Nullable NiagaraBounds> bounds,
                                                                Vec3 actorPosition, PrimitiveCustomData customPrimitiveData,
                                                                NiagaraTranslucencySort translucencySort, boolean renderInMainPass) {
        var vertex = material.vertexMaterial();
        boolean opaqueLighting = material.blend() == MaterialDefinition.Blend.OPAQUE
                && (material.defaultLitSurface() || lighting.castsContactShadow());
        boolean surfaceEnvironment = material.translucencyVolume().usesSurfaceEnvironment();
        boolean primitiveMatrices = vertex != null && vertex.primitiveMatrices();
        boolean customTranslucencySort = material.blend().translucent() && !translucencySort.equals(NiagaraTranslucencySort.DEFAULT);
        return opaqueLighting || surfaceEnvironment || lighting.castsDynamicShadow()
                || customDepthStencil.enabled() || primitiveMatrices || customTranslucencySort || !renderInMainPass
                ? new MaterialPrimitiveFrame(current, previous, lighting, customDepthStencil, firstPerson,
                        surfaceEnvironment || primitiveMatrices ? bounds : null, actorPosition, customPrimitiveData, translucencySort,
                        renderInMainPass) : null;
    }

    public static boolean drawsInMainPass(@Nullable MaterialPrimitiveFrame primitive) {
        return primitive == null || primitive.renderInMainPass();
    }
}
