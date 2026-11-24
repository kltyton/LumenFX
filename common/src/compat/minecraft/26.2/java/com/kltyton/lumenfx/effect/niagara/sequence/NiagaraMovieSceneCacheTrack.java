package com.kltyton.lumenfx.effect.niagara.sequence;

import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Saved SimCache sections retain the source track's evaluation order. */
public record NiagaraMovieSceneCacheTrack(String source, List<Section> sections) {
    public enum ReplayMode { SimWithoutCache, DisplayCacheOnly }
    public enum StretchMode { Repeat, TimeDilate }

    public record Section(int startFrame, int endFrame, @Nullable NiagaraSimulationCache cache, float playRate,
                          int startOffset, int endOffset, int firstLoopOffset, boolean reverse,
                          ReplayMode replayMode, StretchMode stretchMode) {
        public Section {
            if (endFrame < startFrame || !Float.isFinite(playRate))
                throw new IllegalArgumentException("Invalid MovieScene Niagara cache section");
            Objects.requireNonNull(replayMode, "replayMode");
            Objects.requireNonNull(stretchMode, "stretchMode");
        }
    }

    public NiagaraMovieSceneCacheTrack {
        Objects.requireNonNull(source, "source");
        sections = List.copyOf(sections);
    }
}
