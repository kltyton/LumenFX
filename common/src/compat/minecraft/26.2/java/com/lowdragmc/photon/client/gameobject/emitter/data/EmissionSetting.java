/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data;
import com.kltyton.lumenfx.internal.photon.core.syncdata.annotation.Persisted;
import com.kltyton.lumenfx.internal.photon.core.syncdata.IPersistedSerializable;
import com.kltyton.lumenfx.internal.photon.core.syncdata.annotation.ReadOnlyManaged;
import com.lowdragmc.photon.client.gameobject.RuntimeValue;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.Constant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunctionConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.RandomConstant;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.Curve;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.CurveConfig;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.curve.RandomCurve;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.nbt.IntTag;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-data emission config; the emission-count behaviour lives on the co-located {@link Runtime}
 * (its {@code emissionRate}/{@code distanceRate} are timeline-overridable slots).
 *
 * @author KilaBash
 * @date 2023/6/1
 */
@Setter
@Getter
public class EmissionSetting implements IPersistedSerializable {

    public enum Mode {
        Exacting,
        Random
    }

    @Persisted
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, defaultValue = 0.5f, curveConfig = @CurveConfig(bound = {0, 5}, xAxis = "duration", yAxis = "emission rate"))
    protected NumberFunction emissionRate = NumberFunction.constant(0.5f);

    @Persisted
    @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, defaultValue = 0.5f, curveConfig = @CurveConfig(bound = {0, 5}, xAxis = "duration", yAxis = "emission rate"))
    protected NumberFunction distanceRate = NumberFunction.constant(0);

    @Persisted
    protected Mode emissionMode = Mode.Exacting;

    @Persisted
    @ReadOnlyManaged(serializeMethod = "burstsSerialize", deserializeMethod = "burstsDeserialize")
    protected List<Burst> bursts = new ArrayList<>();

    public Runtime createRuntime() {
        return new Runtime(this);
    }

    /** Per-emitter runtime: timeline-overridable emission/distance rate slots + the emission-count logic. */
    public static class Runtime {
        private final EmissionSetting config;
        public final RuntimeValue<NumberFunction> emissionRate;
        public final RuntimeValue<NumberFunction> distanceRate;

        public Runtime(EmissionSetting config) {
            this.config = config;
            this.emissionRate = new RuntimeValue<>(() -> config.emissionRate);
            this.distanceRate = new RuntimeValue<>(() -> config.distanceRate);
        }

        public int getEmissionCount(ParticleEmitter particleEmitter, RandomSource randomSource) {
            return getEmissionCount(particleEmitter, randomSource, 1f);
        }

        /**
         * Emission for a simulation slice of length {@code dt} ticks starting at the emitter's current age.
         * Time-rate emission accumulates fractionally (so a scaled {@code dt} still emits whole particles);
         * bursts fire when an integer trigger age is crossed within the slice {@code [age, age+dt)}.
         */
        public int getEmissionCount(ParticleEmitter particleEmitter, RandomSource randomSource, float dt) {
            var ageStart = particleEmitter.getAgeF();
            var ageEnd = ageStart + dt;
            var t = particleEmitter.getT();
            var timeValue = emissionRate.get().get(randomSource, t).floatValue();
            var distanceValue = distanceRate.get().get(randomSource, t).floatValue();
            // time-based rate: accumulate rate*dt, emit whole particles, carry the remainder
            var acc = particleEmitter.getEmissionRateAccum() + timeValue * dt;
            if (config.emissionMode == Mode.Random) {
                // randomized rounding of the fractional part (keeps the same expected rate)
                var whole = (int) acc;
                var frac = acc - whole;
                acc -= whole;
                if (randomSource.nextFloat() < frac) { whole += 1; acc -= 1; }
                particleEmitter.setEmissionRateAccum(acc);
                var n = whole;
                return n + distanceAndBursts(particleEmitter, randomSource, t, distanceValue, ageStart, ageEnd);
            }
            var number = (int) acc;
            particleEmitter.setEmissionRateAccum(acc - number);
            return number + distanceAndBursts(particleEmitter, randomSource, t, distanceValue, ageStart, ageEnd);
        }

        private int distanceAndBursts(ParticleEmitter particleEmitter, RandomSource randomSource, float t,
                                      float distanceValue, float ageStart, float ageEnd) {
            var number = 0;
            if (distanceValue > 0) {
                var emitDistance = (int) (particleEmitter.getAccumulatedDistance() / distanceValue);
                number += emitDistance;
                particleEmitter.setAccumulatedDistance(particleEmitter.getAccumulatedDistance() - emitDistance * distanceValue);
            }

            var duration = particleEmitter.getLifetime();
            var looping = particleEmitter.isLooping() && duration > 0;
            for (var bust : config.bursts) {
                var count = bust.count.get(randomSource, t).intValue();
                if (looping) {
                    // looping emitters: ageF grows unbounded while the effect repeats every `duration`
                    // ticks, so evaluate bursts against the age wrapped into [0, duration) — bursts
                    // re-fire each loop, cycles caps triggers within one loop iteration.
                    var dt = Math.min(ageEnd - ageStart, duration); // dt > duration: clamp to one full loop
                    var loopedStart = ageStart % duration;
                    var loopedEnd = loopedStart + dt;
                    number += burstTriggers(bust, count, randomSource, loopedStart, Math.min(loopedEnd, duration));
                    if (loopedEnd > duration) { // slice crosses the loop boundary: second sub-window from 0
                        number += burstTriggers(bust, count, randomSource, 0, loopedEnd - duration);
                    }
                } else {
                    number += burstTriggers(bust, count, randomSource, ageStart, ageEnd);
                }
            }
            return number;
        }

        /**
         * Fire each burst trigger age ({@code time + k*interval}, {@code k} limited by cycles when > 0)
         * that lands in {@code [start, end)}.
         */
        private int burstTriggers(Burst bust, int count, RandomSource randomSource, float start, float end) {
            int number = 0;
            int repeats = bust.cycles > 0 ? bust.cycles : Integer.MAX_VALUE;
            int first = EmissionBurstTiming.firstRepeat(bust.time, bust.interval, repeats, start, true);
            for (int index = first; index < repeats; index++) {
                if (EmissionBurstTiming.time(bust.time, bust.interval, index) >= end) break;
                if (randomSource.nextFloat() < bust.probability) {
                    number += count;
                }
            }
            return number;
        }

        public void clear() {
            emissionRate.clear();
            distanceRate.clear();
        }
    }

    private IntTag burstsSerialize(List<Burst> bursts) {
        return IntTag.valueOf(bursts.size());
    }

    private List<Burst> burstsDeserialize(IntTag tag) {
        var groups = new ArrayList<Burst>();
        for (int i = 0; i < tag.intValue(); i++) {
            groups.add(new Burst());
        }
        return groups;
    }

    public static class Burst implements IPersistedSerializable{
        @Persisted
        public int time = 0;
        @Setter
        @Getter
        @Persisted
        @NumberFunctionConfig(types = {Constant.class, RandomConstant.class, Curve.class, RandomCurve.class}, min = 0, defaultValue = 50, curveConfig = @CurveConfig(bound = {0, 50}, xAxis = "duration", yAxis = "emit count"))
        protected NumberFunction count = NumberFunction.constant(50);
        @Persisted
        public int cycles = 1;
        @Persisted
        public int interval = 1;
        @Persisted
        public float probability = 1;
    }
}
