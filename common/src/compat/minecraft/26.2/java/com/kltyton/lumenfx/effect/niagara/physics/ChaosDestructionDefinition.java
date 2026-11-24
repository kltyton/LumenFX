package com.kltyton.lumenfx.effect.niagara.physics;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.world.phys.Vec3;

public record ChaosDestructionDefinition(Source source, List<String> solvers, int frequency, int maximumEntries,
                                         boolean spawn, Range multiplier, float chance, Filters filters, Order order,
                                         boolean externalBreaking, SpatialHash spatialHash, Motion motion, float maxLatency) {
    public static final String TYPE = "NiagaraDataInterfaceChaosDestruction";

    public enum Source { COLLISION, BREAKING, TRAILING }
    public enum Order { NONE, RANDOM, HEAVIEST, LIGHTEST }
    public enum Velocity { RANDOM, STREAMERS, COLLISION_CONE }
    public enum AxisMode { NONE, MIN, MAX, MINMAX }

    public record Range(double min, double max) {
        public boolean rejects(double value, boolean squared) {
            if (min == 0 || max == 0) return false;
            double low = squared ? (float) (min * min) : min;
            double high = squared ? (float) (max * max) : max;
            return (min > 0 && value < low) || (max > 0 && value > high);
        }
    }

    public record Axis(AxisMode mode, Range range) {
        public boolean rejects(double value, boolean inclusive) {
            return switch (mode) {
                case NONE -> false;
                case MIN -> inclusive ? value <= range.min : value > range.min;
                case MAX -> inclusive ? value >= range.max : value < range.max;
                case MINMAX -> inclusive == (value <= range.min || value >= range.max);
            };
        }
    }

    public record Filters(Range impulse, Range speed, Range mass, Range extentMin, Range extentMax,
                          Range volume, Range time, int surface, Axis x, Axis y, Axis z, boolean inclusive,
                          float trailSpeed, boolean filterMaterials, Set<String> materials) {
        public boolean acceptsMaterial(String name) {
            return name != null && materials.contains(name.toLowerCase(Locale.ROOT));
        }

        public boolean rejectsLocation(Vec3 location) {
            return x.rejects(location.x, inclusive) || y.rejects(location.y, inclusive) || z.rejects(location.z, inclusive);
        }
    }

    public record SpatialHash(boolean enabled, Vec3 minimum, Vec3 maximum, Vec3 cellSize, int perCell) { }

    public record Motion(Range position, float inheritedVelocity, Velocity model, Range randomVelocity,
                         float spreadDegrees, Vec3 offsetMin, Vec3 offsetMax, Range finalVelocity) { }

    public static ChaosDestructionDefinition read(JsonObject data) {
        return new ChaosDestructionDefinition(Source.valueOf(data.get("source").getAsString()), strings(data, "solvers"),
                data.get("frequency").getAsInt(), data.get("maximum_entries").getAsInt(), data.get("spawn").getAsBoolean(),
                range(data, "multiplier"), data.get("chance").getAsFloat(),
                new Filters(range(data, "impulse"), range(data, "speed"), range(data, "mass"),
                        range(data, "extent_min"), range(data, "extent_max"), range(data, "volume"), range(data, "time"),
                        data.get("surface").getAsInt(), axis(data, "x"), axis(data, "y"), axis(data, "z"),
                        data.get("inclusive").getAsBoolean(), data.get("trail_speed").getAsFloat(),
                        data.get("filter_materials").getAsBoolean(), Set.copyOf(strings(data, "materials"))),
                Order.valueOf(data.get("order").getAsString()), data.get("external_breaking").getAsBoolean(),
                new SpatialHash(data.get("spatial").getAsBoolean(), vector(data, "spatial_min"),
                        vector(data, "spatial_max"), vector(data, "cell_size"), data.get("per_cell").getAsInt()),
                new Motion(range(data, "position"), data.get("inherited").getAsFloat(),
                        Velocity.valueOf(data.get("velocity_model").getAsString()), range(data, "random_velocity"),
                        data.get("spread").getAsFloat(), vector(data, "offset_min"), vector(data, "offset_max"),
                        range(data, "final_velocity")), data.get("max_latency").getAsFloat());
    }

    private static List<String> strings(JsonObject data, String name) {
        var result = new ArrayList<String>();
        for (var value : data.getAsJsonArray(name)) result.add(value.getAsString());
        return List.copyOf(result);
    }

    private static Axis axis(JsonObject data, String name) {
        return new Axis(AxisMode.valueOf(data.get(name + "_mode").getAsString()), range(data, name + "_range"));
    }

    private static Range range(JsonObject data, String name) {
        var array = data.getAsJsonArray(name);
        return new Range(array.get(0).getAsDouble(), array.get(1).getAsDouble());
    }

    private static Vec3 vector(JsonObject data, String name) {
        var array = data.getAsJsonArray(name);
        return new Vec3(array.get(0).getAsDouble(), array.get(1).getAsDouble(), array.get(2).getAsDouble());
    }
}
