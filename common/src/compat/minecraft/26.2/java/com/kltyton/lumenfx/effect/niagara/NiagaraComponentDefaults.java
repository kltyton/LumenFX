package com.kltyton.lumenfx.effect.niagara;

import com.kltyton.lumenfx.api.client.component.PrimitiveOwnerVisibility;
import com.kltyton.lumenfx.effect.niagara.NiagaraTiming.Warmup;
import com.kltyton.lumenfx.effect.niagara.NiagaraTickBehavior.ComponentTickGroup;
import com.kltyton.lumenfx.effect.niagara.component.NiagaraTranslucencySort;
import com.kltyton.lumenfx.effect.niagara.sequence.NiagaraSystemLifeCycleTrack;
import com.kltyton.lumenfx.effect.niagara.sequence.NiagaraMovieSceneCacheTrack;
import com.kltyton.lumenfx.effect.render.material.PrimitiveCustomData;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record NiagaraComponentDefaults(@Nullable String source, @Nullable Integer randomSeedOffset,
                                       @Nullable Warmup warmup, boolean renderingEnabled, boolean allowScalability,
                                       OcclusionMode occlusionMode, NiagaraTickBehavior tickBehavior, ComponentTickGroup tickGroup,
                                       boolean autoActivate, boolean autoDestroy, @Nullable NiagaraSystemLifeCycleTrack lifeCycleTrack,
                                       @Nullable NiagaraMovieSceneCacheTrack cacheTrack, float boundsScale,
                                       PrimitiveCustomData customPrimitiveData, NiagaraComponentAttachment autoAttachment,
                                       PrimitiveOwnerVisibility ownerVisibility, NiagaraTranslucencySort translucencySort,
                                       boolean renderInMainPass, boolean castHiddenShadow, boolean drawInGame) {
    public enum OcclusionMode { Default, AlwaysEnabled, AlwaysDisabled }

    // UNiagaraComponent publishes GetPreviewLODDistance() for every render update.
    // With editor preview disabled, that getter returns a valid zero override.
    public static final float RENDERER_LOD_DISTANCE_OVERRIDE = 0.0f;
    public static final NiagaraComponentDefaults DEFAULT = new NiagaraComponentDefaults(null, null, null, true, true,
            OcclusionMode.Default, NiagaraTickBehavior.UsePrereqs, ComponentTickGroup.TG_PrePhysics, true, false, null, null,
            1.0f, PrimitiveCustomData.EMPTY, NiagaraComponentAttachment.DISABLED);

    public NiagaraComponentDefaults {
        Objects.requireNonNull(customPrimitiveData);
        Objects.requireNonNull(autoAttachment);
        Objects.requireNonNull(ownerVisibility);
        Objects.requireNonNull(translucencySort);
    }

    public NiagaraComponentDefaults(@Nullable String source, @Nullable Integer randomSeedOffset, @Nullable Warmup warmup,
            boolean renderingEnabled, boolean allowScalability, OcclusionMode occlusionMode, NiagaraTickBehavior tickBehavior,
            ComponentTickGroup tickGroup, boolean autoActivate, boolean autoDestroy,
            @Nullable NiagaraSystemLifeCycleTrack lifeCycleTrack, @Nullable NiagaraMovieSceneCacheTrack cacheTrack,
            float boundsScale, PrimitiveCustomData customPrimitiveData, NiagaraComponentAttachment autoAttachment,
            PrimitiveOwnerVisibility ownerVisibility, NiagaraTranslucencySort translucencySort) {
        this(source, randomSeedOffset, warmup, renderingEnabled, allowScalability, occlusionMode, tickBehavior, tickGroup,
                autoActivate, autoDestroy, lifeCycleTrack, cacheTrack, boundsScale, customPrimitiveData, autoAttachment,
                ownerVisibility, translucencySort, true, false, true);
    }

    public NiagaraComponentDefaults(@Nullable String source, @Nullable Integer randomSeedOffset, @Nullable Warmup warmup,
            boolean renderingEnabled, boolean allowScalability, OcclusionMode occlusionMode, NiagaraTickBehavior tickBehavior,
            ComponentTickGroup tickGroup, boolean autoActivate, boolean autoDestroy,
            @Nullable NiagaraSystemLifeCycleTrack lifeCycleTrack, @Nullable NiagaraMovieSceneCacheTrack cacheTrack,
            float boundsScale, PrimitiveCustomData customPrimitiveData, NiagaraComponentAttachment autoAttachment,
            PrimitiveOwnerVisibility ownerVisibility) {
        this(source, randomSeedOffset, warmup, renderingEnabled, allowScalability, occlusionMode, tickBehavior, tickGroup,
                autoActivate, autoDestroy, lifeCycleTrack, cacheTrack, boundsScale, customPrimitiveData, autoAttachment,
                ownerVisibility, NiagaraTranslucencySort.DEFAULT);
    }

    public NiagaraComponentDefaults(@Nullable String source, @Nullable Integer randomSeedOffset, @Nullable Warmup warmup,
            boolean renderingEnabled, boolean allowScalability, OcclusionMode occlusionMode, NiagaraTickBehavior tickBehavior,
            ComponentTickGroup tickGroup, boolean autoActivate, boolean autoDestroy,
            @Nullable NiagaraSystemLifeCycleTrack lifeCycleTrack, @Nullable NiagaraMovieSceneCacheTrack cacheTrack,
            float boundsScale, PrimitiveCustomData customPrimitiveData, NiagaraComponentAttachment autoAttachment) {
        this(source, randomSeedOffset, warmup, renderingEnabled, allowScalability, occlusionMode, tickBehavior, tickGroup,
                autoActivate, autoDestroy, lifeCycleTrack, cacheTrack, boundsScale, customPrimitiveData, autoAttachment,
                PrimitiveOwnerVisibility.UNRESTRICTED);
    }

    public NiagaraComponentDefaults(@Nullable String source, @Nullable Integer randomSeedOffset, @Nullable Warmup warmup,
            boolean renderingEnabled, boolean allowScalability, OcclusionMode occlusionMode, NiagaraTickBehavior tickBehavior,
            ComponentTickGroup tickGroup, boolean autoActivate, boolean autoDestroy,
            @Nullable NiagaraSystemLifeCycleTrack lifeCycleTrack, @Nullable NiagaraMovieSceneCacheTrack cacheTrack,
            float boundsScale, PrimitiveCustomData customPrimitiveData) {
        this(source, randomSeedOffset, warmup, renderingEnabled, allowScalability, occlusionMode, tickBehavior, tickGroup,
                autoActivate, autoDestroy, lifeCycleTrack, cacheTrack, boundsScale, customPrimitiveData,
                NiagaraComponentAttachment.DISABLED);
    }

    public NiagaraComponentDefaults(@Nullable String source, @Nullable Integer randomSeedOffset, @Nullable Warmup warmup,
            boolean renderingEnabled, boolean allowScalability, OcclusionMode occlusionMode, NiagaraTickBehavior tickBehavior,
            ComponentTickGroup tickGroup, boolean autoActivate, boolean autoDestroy,
            @Nullable NiagaraSystemLifeCycleTrack lifeCycleTrack, @Nullable NiagaraMovieSceneCacheTrack cacheTrack, float boundsScale) {
        this(source, randomSeedOffset, warmup, renderingEnabled, allowScalability, occlusionMode, tickBehavior, tickGroup,
                autoActivate, autoDestroy, lifeCycleTrack, cacheTrack, boundsScale, PrimitiveCustomData.EMPTY);
    }

    public NiagaraComponentDefaults(@Nullable String source, @Nullable Integer randomSeedOffset, @Nullable Warmup warmup,
            boolean renderingEnabled, boolean allowScalability, OcclusionMode occlusionMode, NiagaraTickBehavior tickBehavior,
            ComponentTickGroup tickGroup, boolean autoActivate, boolean autoDestroy,
            @Nullable NiagaraSystemLifeCycleTrack lifeCycleTrack, @Nullable NiagaraMovieSceneCacheTrack cacheTrack) {
        this(source, randomSeedOffset, warmup, renderingEnabled, allowScalability, occlusionMode, tickBehavior, tickGroup,
                autoActivate, autoDestroy, lifeCycleTrack, cacheTrack, 1.0f);
    }

    public NiagaraComponentDefaults(@Nullable String source, @Nullable Integer randomSeedOffset, @Nullable Warmup warmup,
            boolean renderingEnabled, boolean allowScalability, OcclusionMode occlusionMode, NiagaraTickBehavior tickBehavior,
            ComponentTickGroup tickGroup) {
        this(source, randomSeedOffset, warmup, renderingEnabled, allowScalability, occlusionMode, tickBehavior, tickGroup, true, false, null, null);
    }
}
