package com.kltyton.lumenfx.client.simulation.niagara.component;

import com.kltyton.lumenfx.api.client.component.NiagaraMovieSceneEvaluation;
import com.kltyton.lumenfx.effect.niagara.sequence.NiagaraMovieSceneCacheTrack;
import com.kltyton.lumenfx.effect.niagara.sequence.NiagaraMovieSceneCacheTrack.ReplayMode;
import com.kltyton.lumenfx.effect.niagara.sequence.NiagaraMovieSceneCacheTrack.Section;
import com.kltyton.lumenfx.effect.niagara.sequence.NiagaraMovieSceneCacheTrack.StretchMode;
import org.jspecify.annotations.Nullable;

public final class NiagaraMovieSceneCachePlayback {
    public record Selection(boolean anyValidCache, @Nullable Section section, ReplayMode replayMode) { }

    private NiagaraMovieSceneCachePlayback() { }

    public static Selection select(NiagaraMovieSceneCacheTrack track, NiagaraMovieSceneEvaluation context) {
        boolean anyValid = false;
        var replay = ReplayMode.DisplayCacheOnly;
        double frame = Math.floor(context.time());
        for (var section : track.sections()) {
            anyValid |= section.cache() != null;
            replay = section.replayMode();
            // The source template skips an overlapping section without a SimCache;
            // a later section can still supply the cache for the same frame.
            if (section.cache() != null && frame >= section.startFrame() && frame < section.endFrame())
                return new Selection(anyValid, section, replay);
        }
        return new Selection(anyValid, null, replay);
    }

    public static float age(Section section, NiagaraMovieSceneEvaluation context) {
        var cache = section.cache();
        if (cache == null) throw new IllegalArgumentException("MovieScene cache section has no valid frames");
        var rate = context.frameRate();
        boolean dilate = section.stretchMode() == StretchMode.TimeDilate;
        long sectionLength = (long)section.endFrame() - section.startFrame();
        float length = dilate ? (float)rate.seconds(sectionLength) : cache.duration();
        double lengthInFrames = (double)length * rate.numerator() / rate.denominator();
        long roundedLength = (long)Math.floor(lengthInFrames + 0.5) + 1;
        long offsets = (long)section.startOffset() + section.endOffset();
        boolean looping = sectionLength + offsets > roundedLength;
        double time = Math.clamp(context.time(), section.startFrame(), section.endFrame() - 1);
        float playRate = Math.abs(section.playRate()) <= 1.0e-8f ? 1f : section.playRate();
        float sequenceLength = length - (float)rate.seconds(offsets);
        float age = (float)rate.seconds((time - section.startFrame()) * playRate) + (float)rate.seconds(section.firstLoopOffset());
        if (sequenceLength > 0f && (looping || Math.abs(age - sequenceLength) > 1.0e-4f)) age %= sequenceLength;
        age += (float)rate.seconds(section.startOffset());
        if (section.reverse()) age = length - age;
        if (dilate) age = age / length * cache.duration();
        return age + cache.start();
    }
}
