package com.kltyton.lumenfx.effect.simulation;

import java.util.Comparator;
import java.util.TreeSet;

/** A Unity ParticleSystem shares its live-particle or ring-buffer capacity among active sub-emission sources. */
public final class UnityEmitterCapacity {
    record RingSlot(ParticleEmitterSimulation owner, ParticleState particle, double hostBorn, long order) { }

    private static final Comparator<RingSlot> RING_ORDER = Comparator.comparingDouble(RingSlot::hostBorn)
            .thenComparingLong(RingSlot::order);

    private final int maximum;
    private int live;
    private final TreeSet<RingSlot> pauseRing = new TreeSet<>(RING_ORDER);
    private final TreeSet<RingSlot> loopingRing = new TreeSet<>(RING_ORDER);
    private long nextOrder;

    public UnityEmitterCapacity(int maximum) { this.maximum = maximum; }

    int maximum() { return maximum; }
    boolean hasRoom() { return live < maximum; }
    void occupy() { live++; }
    void release(int count) { live -= count; }

    long nextOrder() { return nextOrder++; }

    void addPauseSlot(RingSlot slot, double replacementTime) {
        pauseRing.add(slot);
        if (pauseRing.size() > maximum) {
            RingSlot oldest = pauseRing.pollFirst();
            if (oldest != null) {
                RingSlot replacer = oldest == slot ? pauseRing.first() : slot;
                double evictHost = Math.max(oldest.hostBorn(), replacer != null ? replacer.hostBorn() : replacementTime);
                oldest.owner().evictSharedRingSlot(oldest.particle(), evictHost);
            }
        }
    }

    void addLoopingSlot(RingSlot slot, double releaseTime) {
        loopingRing.add(slot);
        if (loopingRing.size() > maximum) {
            RingSlot oldest = loopingRing.pollFirst();
            if (oldest != null) {
                RingSlot replacer = oldest == slot ? loopingRing.first() : slot;
                double releaseHost = Math.max(oldest.hostBorn(), replacer != null ? replacer.hostBorn() : releaseTime);
                oldest.owner().releaseSharedLoopingSlot(oldest.particle(), releaseHost);
            }
        }
    }

    void removeLoopingParticle(ParticleState particle) {
        loopingRing.removeIf(slot -> slot.particle() == particle);
    }

    void removePauseParticle(ParticleState particle) {
        pauseRing.removeIf(slot -> slot.particle() == particle);
    }

    void drainOwner(ParticleEmitterSimulation owner, double time) {
        loopingRing.removeIf(slot -> {
            if (slot.owner() != owner) return false;
            slot.particle().releaseFromLoop(time);
            return true;
        });
        pauseRing.removeIf(slot -> slot.owner() == owner);
    }
}
