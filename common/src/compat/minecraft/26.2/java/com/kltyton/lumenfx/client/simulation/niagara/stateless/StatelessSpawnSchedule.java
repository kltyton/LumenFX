package com.kltyton.lumenfx.client.simulation.niagara.stateless;

import com.kltyton.lumenfx.math.SourceFloatMath;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Runtime spawn ranges become the same narrowed windows used by CPU and compute particle evaluation. */
public final class StatelessSpawnSchedule {
    private static final long UINT_MASK = 0xffff_ffffL;
    public enum Type { BURST, RATE }
    public record Info(Type type, int uniqueOffset, float start, float end, float rate, float probability,
                       int amount, float lifetimeMinimum, float lifetimeMaximum) {
        public Info { Objects.requireNonNull(type); }
    }
    public record Plan(long active, List<StatelessParticleSimulation.SpawnWindow> windows) {
        public Plan {
            if (active < 0 || active > UINT_MASK || windows.size() > 8)
                throw new IllegalArgumentException("Invalid stateless spawn plan");
            windows = List.copyOf(windows);
        }
    }
    private record Active(long count, long offset, float time, float interval) { }
    private StatelessSpawnSchedule() { }

    public static Plan plan(List<Info> infos, float age) {
        var windows = new ArrayList<StatelessParticleSimulation.SpawnWindow>(8);
        long total = 0;
        for (var info : infos) {
            Active active = active(info, true, age);
            if (active.count() == 0) continue;
            if (windows.size() == 8) break;
            windows.add(new StatelessParticleSimulation.SpawnWindow(active.count(), (int)active.offset(), info.uniqueOffset(),
                    active.time(), active.interval(), info.probability(), info.lifetimeMaximum() - info.lifetimeMinimum(), info.lifetimeMinimum()));
            total = (total + active.count()) & UINT_MASK;
        }
        return new Plan(total, windows);
    }

    public static long count(List<Info> infos, boolean ageKnown, float age) {
        long total = 0;
        // The source only advances its eight-window index when shader parameters are requested.
        for (var info : infos) total = (total + active(info, ageKnown, age).count()) & UINT_MASK;
        return total;
    }

    private static Active active(Info info, boolean ageKnown, float age) {
        if (ageKnown && !(age >= info.start() && age < info.end() + info.lifetimeMaximum()))
            return new Active(0, 0, info.start(), 0);
        long amount = Integer.toUnsignedLong(info.amount()), offset = 0, count = amount;
        float time = info.start(), interval = 0f;
        if (info.type() == Type.RATE) {
            if (ageKnown) {
                offset = Integer.toUnsignedLong(SourceFloatMath.floorToInt(
                        SourceFloatMath.max(age - info.start() - info.lifetimeMaximum(), 0f) * info.rate()));
                offset = Math.min(offset, amount);
                count = Integer.toUnsignedLong(SourceFloatMath.floorToInt(
                        SourceFloatMath.max(age - info.start(), 0f) * info.rate()));
                count = (Math.min(count, amount) - offset) & UINT_MASK;
            }
            interval = 1f / info.rate();
            time += interval;
        }
        return new Active(count, offset, time, interval);
    }
}
