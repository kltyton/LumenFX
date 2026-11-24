package com.kltyton.lumenfx.client.simulation.niagara.interfaces.water;

import com.kltyton.lumenfx.api.client.water.NiagaraWaterBody;
import com.kltyton.lumenfx.api.client.water.NiagaraWaterBody.Flags;
import com.kltyton.lumenfx.api.client.water.NiagaraWaterBody.QueryError;
import com.kltyton.lumenfx.api.client.water.NiagaraWaterBody.Surface;
import com.kltyton.lumenfx.api.client.water.NiagaraWaterScene;
import com.kltyton.lumenfx.effect.niagara.water.WaterDefinition;
import com.mojang.logging.LogUtils;
import java.util.Objects;
import java.util.function.IntBinaryOperator;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public final class NiagaraWaterState {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Vec3 UP = new Vec3(0, 0, 1);
    private static final Flags CLOSEST = new Flags(true, false, false, false, true, false);
    private static final Flags DEPTH = new Flags(true, false, false, true, false, false);
    private static final Surface DEFAULT_SURFACE = new Surface(Vec3.ZERO, UP, Vec3.ZERO, UP, 0, Vec3.ZERO, 0, false);

    public enum Function { IsValid, GetWaterSurfaceInfo, GetWaterDataAtPoint, GetWaveParamLookupTableOffset }
    @FunctionalInterface public interface Output { void set(int word, int lane, int value); }

    private record Frame(@Nullable NiagaraWaterBody body, float depth, Vec3 origin, boolean queryDepth) { }

    private final WaterDefinition definition;
    private final @Nullable NiagaraAuthoredWaterBody authored;
    private boolean first = true;
    private @Nullable String sourceOverride;
    private @Nullable String closestComponent;
    private Frame frame = new Frame(null, 0, Vec3.ZERO, false);

    public NiagaraWaterState(WaterDefinition definition, @Nullable NiagaraAuthoredWaterBody authored) {
        this.definition = definition;
        this.authored = authored;
    }

    public void begin(NiagaraWaterScene scene, @Nullable String override, Vec3 owner, Vec3 origin, boolean queryDepth,
                      float seconds, float oceanFallback) {
        String source = override != null ? override : definition.source();
        boolean changed = first || !Objects.equals(sourceOverride, override);
        if (changed) {
            closestComponent = first && override == null && source.isEmpty() && definition.findClosest()
                    ? closest(scene, owner) : null;
            sourceOverride = override;
            first = false;
        }
        var component = closestComponent != null ? scene.component(closestComponent)
                : source.isEmpty() ? null : scene.source(source);
        NiagaraWaterBody body = component != null ? component.data()
                : authored != null && override == null ? authored.snapshot(seconds, oceanFallback, scene.terrain()) : null;
        float depth = frame.depth();
        if (definition.evaluateDepth() && (changed || frame.body() == null || definition.depthPerFrame()) && body != null) {
            var result = body.query(owner, DEPTH);
            if (result instanceof Surface surface) depth = surface.excluded() ? 0f : surface.depth();
            else log((QueryError)result, "sampling owner depth");
        }
        frame = new Frame(body, depth, origin, queryDepth);
    }

    private static @Nullable String closest(NiagaraWaterScene scene, Vec3 location) {
        String closest = null;
        double distance = Double.POSITIVE_INFINITY;
        for (var candidate : scene.bodies()) {
            if (!(candidate.data().query(location, CLOSEST) instanceof Surface sample) || sample.excluded()) continue;
            double nextDistance = sample.planeLocation().distanceToSqr(location);
            if (closest == null || nextDistance < distance) {
                closest = candidate.component();
                distance = nextDistance;
            }
        }
        return closest;
    }

    public void execute(Function function, int count, IntBinaryOperator input, Output output) {
        var state = frame;
        var body = state.body();
        if (function == Function.IsValid || function == Function.GetWaveParamLookupTableOffset) {
            int value = function == Function.IsValid ? (body == null ? 0 : -1) : (body == null ? 0 : body.index());
            for (int lane = 0; lane < count; lane++) output.set(0, lane, value);
            return;
        }
        int outLane = 0;
        for (int lane = 0; lane < count; lane++) {
            if (function == Function.GetWaterSurfaceInfo) {
                boolean execute = input.applyAsInt(0, lane) != 0 && body != null;
                boolean depth = input.applyAsInt(4, lane) != 0;
                boolean waves = input.applyAsInt(5, lane) != 0;
                boolean simpleWaves = input.applyAsInt(6, lane) != 0;
                var flags = new Flags(true, true, true, depth && state.queryDepth(), waves, simpleWaves);
                var result = execute ? body.query(position(input, 1, lane, state.origin()), flags) : DEFAULT_SURFACE;
                if (result instanceof QueryError error) {
                    log(error, "querying the water surface");
                    continue;
                }
                var surface = (Surface)result;
                Vec3 origin = execute ? state.origin() : Vec3.ZERO;
                vector(output, 0, outLane, surface.planeLocation().subtract(origin));
                vector(output, 3, outLane, surface.planeNormal());
                vector(output, 6, outLane, surface.surfaceLocation().subtract(origin));
                number(output, 9, outLane, !execute || !depth ? 0f : flags.depth() ? surface.depth() : state.depth());
                vector(output, 10, outLane, surface.velocity());
                output.set(13, outLane, surface.excluded() ? -1 : 0);
            } else {
                var flags = new Flags(true, true, true, state.queryDepth(), true, false);
                var result = body == null ? DEFAULT_SURFACE : body.query(position(input, 0, lane, state.origin()), flags);
                if (result instanceof QueryError error) {
                    log(error, "querying the water data point");
                    continue;
                }
                var surface = (Surface)result;
                boolean valid = body != null && !surface.excluded();
                number(output, 0, outLane, valid ? surface.waveHeight() : 0f);
                number(output, 1, outLane, valid ? (flags.depth() ? surface.depth() : state.depth()) : 0f);
                vector(output, 2, outLane, valid ? surface.velocity() : Vec3.ZERO);
                vector(output, 5, outLane, (valid ? surface.surfaceLocation() : Vec3.ZERO).subtract(state.origin()));
                vector(output, 8, outLane, valid ? surface.surfaceNormal() : UP);
            }
            // Both native functions skip output register advancement after query errors.
            outLane++;
        }
    }

    private static void log(QueryError error, String operation) {
        LOGGER.error("Niagara Water error while {}: {}", operation, error.reason());
    }

    private static Vec3 position(IntBinaryOperator input, int word, int lane, Vec3 origin) {
        return origin.add(Float.intBitsToFloat(input.applyAsInt(word, lane)),
                Float.intBitsToFloat(input.applyAsInt(word + 1, lane)), Float.intBitsToFloat(input.applyAsInt(word + 2, lane)));
    }

    private static void vector(Output output, int word, int lane, Vec3 value) {
        number(output, word, lane, (float)value.x);
        number(output, word + 1, lane, (float)value.y);
        number(output, word + 2, lane, (float)value.z);
    }

    private static void number(Output output, int word, int lane, float value) {
        output.set(word, lane, Float.floatToRawIntBits(value));
    }
}
