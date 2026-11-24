package com.kltyton.lumenfx.api.client.water;

import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Source water surface, flow, wave and depth rules over immutable geometry providers. */
public record NiagaraWaterBodySnapshot(int index, Type type, float componentHeight, double oceanHeightOffset,
                                       boolean registered, boolean hasSplineMetadata, float worldSeconds,
                                       float targetWaveMaskDepth, float oceanFallbackDepth,
                                       @Nullable Spline spline, @Nullable Terrain terrain, @Nullable ShallowWater shallowWater,
                                       @Nullable NiagaraWaterWaves waves, Predicate<Vec3> exclusion) implements NiagaraWaterBody {
    public enum Type {
        RIVER, LAKE, OCEAN, TRANSITION;
        boolean flat() { return this == LAKE || this == OCEAN; }
        boolean wavesSupported() { return this != RIVER; }
    }

    @FunctionalInterface public interface Spline { SplineSample closest(Vec3 position); }
    @FunctionalInterface public interface Terrain { @Nullable Float height(Vec3 position); }
    @FunctionalInterface public interface ShallowWater { ShallowSample sample(Vec3 position); }

    public record SplineSample(float height, Vec3 up, Vec3 direction, float depth, float speed) {
        public SplineSample { Objects.requireNonNull(up); Objects.requireNonNull(direction); }
    }
    public record ShallowSample(float height, Vec3 normal, float depth, Vec3 velocity) {
        public ShallowSample { Objects.requireNonNull(normal); Objects.requireNonNull(velocity); }
    }

    private static final Vec3 UP = new Vec3(0, 0, 1);
    private static final SplineSample NO_SPLINE = new SplineSample(0, UP, Vec3.ZERO, 0, 0);
    private static final QueryError NOT_REGISTERED = new QueryError("WaterBodyNotRegistered");
    private static final QueryError NO_METADATA = new QueryError("NullWaterSplineMetadata");

    public NiagaraWaterBodySnapshot {
        Objects.requireNonNull(type);
        Objects.requireNonNull(exclusion);
    }

    @Override public QueryResult query(Vec3 position, Flags flags) {
        if (!registered) return NOT_REGISTERED;
        if (!hasSplineMetadata) return NO_METADATA;
        boolean excluded = exclusion.test(position);
        boolean sampleWaves = flags.waves() && waves != null && type.wavesSupported();
        boolean computeDepth = flags.depth() || sampleWaves;
        boolean computeLocation = flags.location() || sampleWaves;
        var shallow = shallowWater == null ? null : shallowWater.sample(position);
        var terrainHeight = computeDepth && shallow == null && type.flat() && terrain != null ? terrain.height(position) : null;
        boolean splinePlane = !type.flat() && spline != null;
        boolean splineDepth = computeDepth && shallow == null && type != Type.OCEAN && (terrainHeight == null
                || (excluded ? position.z : componentHeight) < terrainHeight);
        boolean splineGeometry = splinePlane && (flags.normal() || computeLocation && !excluded);
        boolean splineFlow = flags.velocity() && !excluded;
        var curve = spline != null && (shallow == null && (splineGeometry || splineFlow) || splineDepth)
                ? spline.closest(position) : NO_SPLINE;

        Vec3 plane = Vec3.ZERO;
        if (computeLocation) {
            float height = !splinePlane ? componentHeight : shallow == null ? curve.height() : shallow.height();
            double offset = type == Type.OCEAN ? oceanHeightOffset : 0;
            plane = excluded ? position : new Vec3(position.x, position.y, height + offset);
        }
        var planeNormal = flags.normal() && splinePlane ? (shallow == null ? curve.up() : shallow.normal()) : UP;
        float depth = 0f;
        boolean oceanUnderTerrain = false;
        if (computeDepth) {
            if (shallow != null) depth = shallow.depth();
            else if (type.flat()) {
                float terrainDepth = terrainHeight == null ? -1f : (float)(plane.z - terrainHeight);
                if (terrainDepth >= 0) depth = terrainDepth;
                else {
                    oceanUnderTerrain = type == Type.OCEAN && terrainHeight != null;
                    depth = type == Type.OCEAN ? oceanFallbackDepth : curve.depth();
                }
            } else depth = curve.depth();
            depth = Math.max(depth, 0f);
        }

        Vec3 normal = planeNormal;
        float waveHeight = 0;
        if (sampleWaves) {
            if (excluded) normal = UP;
            else if (!oceanUnderTerrain) {
                float attenuation = waves.attenuation(plane, depth, targetWaveMaskDepth);
                if (attenuation > 0) {
                    var sample = waves.sample(plane, depth, worldSeconds, flags.simpleWaves());
                    waveHeight = sample.height() * attenuation;
                    if (!flags.simpleWaves()) {
                        var attenuatedNormal = planeNormal.lerp(sample.normal(), attenuation);
                        if (attenuatedNormal.lengthSqr() != 0) normal = attenuatedNormal;
                    }
                }
            }
        }
        var velocity = flags.velocity() && !excluded ? (shallow == null ? curve.direction().scale(curve.speed()) : shallow.velocity())
                : Vec3.ZERO;
        return new Surface(plane, planeNormal, plane.add(0, 0, waveHeight), normal, depth + waveHeight, velocity, waveHeight, excluded);
    }
}
