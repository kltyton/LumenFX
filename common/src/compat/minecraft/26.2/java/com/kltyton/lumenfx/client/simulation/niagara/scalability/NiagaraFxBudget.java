package com.kltyton.lumenfx.client.simulation.niagara.scalability;

import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Rolling FX budget input from actual simulation work and render command preparation. */
public final class NiagaraFxBudget {
    private static final String[] LANES = { "GameThread", "GameThreadConcurrent", "RenderThread" };
    private final long[] totals = new long[LANES.length];
    private final float[] adjusted = new float[LANES.length];
    private long[] @Nullable [] history;
    private int frameIndex;
    private long gameTime, workerTime, renderTime, gameWall, workerWall;
    private double previousTime;
    private float worstUsage;
    private boolean gameScopeOpen;

    public void beginFrame(NiagaraPlatformEnvironment platform, double time) {
        if (!enabled(platform)) {
            if (history != null) clear();
            return;
        }
        if (history == null) {
            int frames = Math.max(1, Objects.requireNonNull(platform.console("fx.Budget.HistoryFrames")).bits());
            history = new long[LANES.length][frames];
            previousTime = time;
        }
        float decay = setting(platform, "fx.Budget.AdjustedUsageDecayRate") * (float)Math.max(0, time - previousTime);
        float maximum = setting(platform, "fx.Budget.AdjustedUsageMax");
        worstUsage = 0;
        for (int index = 0; index < LANES.length; index++) {
            long sample = switch (index) {
                case 0 -> gameTime;
                case 1 -> gameTime + workerTime;
                default -> renderTime;
            };
            long[] lane = history[index];
            totals[index] += sample - lane[frameIndex];
            lane[frameIndex] = sample;
            float budget = setting(platform, "fx.Budget." + LANES[index]);
            float override = setting(platform, "fx.Budget.Debug." + LANES[index] + "TimeOverride");
            float measured = override >= 0 ? override : (float)(totals[index] * 1e-6 / lane.length);
            float usage = budget > 0 ? measured / budget : 0;
            adjusted[index] = Math.min(maximum, Math.max(usage, adjusted[index] - decay));
            worstUsage = Math.max(worstUsage, adjusted[index]);
        }
        frameIndex = (frameIndex + 1) % history[0].length;
        previousTime = time;
        gameTime = workerTime = renderTime = gameWall = workerWall = 0;
    }

    public float worstUsage() { return worstUsage; }
    public boolean trackingWorkers() { return gameScopeOpen; }

    public @Nullable Scope game() {
        if (history == null || gameScopeOpen) return null;
        gameScopeOpen = true;
        return new Scope(true, workerWall);
    }

    public @Nullable Scope render() { return history == null ? null : new Scope(false, gameWall); }

    public void workers(long elapsed, long actualWork) {
        workerWall += elapsed;
        workerTime += actualWork;
    }

    public void clear() {
        history = null;
        frameIndex = 0;
        gameTime = workerTime = renderTime = gameWall = workerWall = 0;
        worstUsage = 0;
        gameScopeOpen = false;
        Arrays.fill(totals, 0);
        Arrays.fill(adjusted, 0);
    }

    public static boolean enabled(NiagaraPlatformEnvironment platform) {
        return Objects.requireNonNull(platform.console("fx.Budget.Enabled")).bits() != 0
                && Objects.requireNonNull(platform.console("fx.Niagara.UseGlobalFXBudget")).bits() != 0;
    }

    private static float setting(NiagaraPlatformEnvironment platform, String name) {
        return Float.intBitsToFloat(Objects.requireNonNull(platform.console(name)).bits());
    }

    public final class Scope implements AutoCloseable {
        private final boolean game;
        private final long excludedTime;
        private final long start = System.nanoTime();

        private Scope(boolean game, long excludedTime) {
            this.game = game;
            this.excludedTime = excludedTime;
        }

        @Override public void close() {
            if (history == null) return;
            long elapsed = System.nanoTime() - start;
            if (game) {
                gameWall += elapsed;
                gameTime += Math.max(0, elapsed - (workerWall - excludedTime));
                gameScopeOpen = false;
            } else renderTime += Math.max(0, elapsed - (gameWall - excludedTime));
        }
    }
}
