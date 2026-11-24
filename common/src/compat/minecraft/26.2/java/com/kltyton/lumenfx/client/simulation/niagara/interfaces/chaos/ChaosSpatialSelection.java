package com.kltyton.lumenfx.client.simulation.niagara.interfaces.chaos;

import com.kltyton.lumenfx.effect.niagara.physics.ChaosDestructionDefinition.SpatialHash;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;
import net.minecraft.world.phys.Vec3;

final class ChaosSpatialSelection {
    private Vec3 minimum;
    private Vec3 maximum;
    private final Vec3 size;
    private final int perCell;

    ChaosSpatialSelection(SpatialHash definition) {
        minimum = definition.minimum();
        maximum = definition.maximum();
        size = definition.cellSize();
        perCell = definition.perCell();
    }

    <E> List<E> select(List<E> events, Function<E, Vec3> location) {
        Vec3 extent = maximum.subtract(minimum);
        if (events.size() <= 1 || !valid(extent.x, size.x) || !valid(extent.y, size.y) || !valid(extent.z, size.z)) return events;

        int x = (int) Math.ceil(extent.x / size.x);
        int y = (int) Math.ceil(extent.y / size.y);
        int z = (int) Math.ceil(extent.z / size.z);
        Vec3 padding = new Vec3((float) ((x * size.x - extent.x) / 2),
                (float) ((y * size.y - extent.y) / 2), (float) ((z * size.z - extent.z) / 2));
        minimum = minimum.subtract(padding);
        maximum = maximum.add(padding);
        int xy = x * y;
        int cells = xy * z;
        var used = new LinkedHashMap<Integer, ArrayList<E>>();
        for (E event : events) {
            Vec3 point = location.apply(event);
            if (point.x < minimum.x || point.x > maximum.x || point.y < minimum.y || point.y > maximum.y
                    || point.z < minimum.z || point.z > maximum.z) continue;
            Vec3 relative = point.subtract(minimum);
            int index = (int) (relative.x * (1 / size.x)) + (int) (relative.y * (1 / size.y)) * x
                    + (int) (relative.z * (1 / size.z)) * xy;
            if (Integer.compareUnsigned(index, cells) < 0) {
                used.computeIfAbsent(index, ignored -> new ArrayList<>()).add(event);
            }
        }

        var result = new ArrayList<E>();
        for (var group : used.values()) {
            // TMultiMap inserts the most recent element at the head of the key's chain.
            int count = Math.min(perCell, group.size());
            for (int i = 0; i < count; i++) {
                result.add(group.get(group.size() - 1 - i));
            }
        }
        return result;
    }

    static <E> List<E> cap(List<E> events, int maximum) {
        if (events.size() <= maximum) return events;
        float increment = (float) events.size() / maximum;
        var sampled = new ArrayList<E>(maximum);
        for (int i = 0; i < maximum; i++) sampled.add(events.get((int) (i * increment)));
        return sampled;
    }

    private static boolean valid(double extent, double size) {
        return extent > 100 && extent < 1.0e8 && size >= 1;
    }
}
