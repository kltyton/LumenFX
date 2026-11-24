package com.kltyton.lumenfx.client.simulation.niagara.interfaces.geometry;

import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs.Transform;
import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.world.phys.Vec3;

/** An immutable geometry proxy publication for one scheduled GPU simulation step. */
public final class GeometryCollectionFrame {
    public enum Buffer {
        WORLD("WorldTransformBuffer"), PREVIOUS("PrevWorldTransformBuffer"),
        INVERSE("WorldInverseTransformBuffer"), PREVIOUS_INVERSE("PrevWorldInverseTransformBuffer"),
        BOUNDS("BoundsBuffer"), REST("ElementTransforms");
        private final String symbol;
        Buffer(String symbol) { this.symbol = symbol; }
        public String symbol() { return symbol; }
    }

    private final Transform root;
    private final Vec3 minimum, maximum;
    private final int count;
    private final Map<Buffer, float[]> words;

    GeometryCollectionFrame(Transform root, Vec3 origin, Vec3 extent, int count,
                            float[] world, float[] previous, float[] inverse, float[] previousInverse,
                            float[] bounds, float[] rest) {
        this.root = root;
        minimum = origin.subtract(extent);
        maximum = origin.add(extent);
        this.count = count;
        var buffers = new EnumMap<Buffer, float[]>(Buffer.class);
        buffers.put(Buffer.WORLD, world);
        buffers.put(Buffer.PREVIOUS, previous);
        buffers.put(Buffer.INVERSE, inverse);
        buffers.put(Buffer.PREVIOUS_INVERSE, previousInverse);
        buffers.put(Buffer.BOUNDS, bounds);
        buffers.put(Buffer.REST, rest);
        words = Map.copyOf(buffers);
    }

    public Transform root() { return root; }
    public Vec3 minimum() { return minimum; }
    public Vec3 maximum() { return maximum; }
    public int count() { return count; }

    public int bytes(Buffer buffer) {
        return Math.multiplyExact(words.get(buffer).length, Float.BYTES);
    }

    public void write(Buffer buffer, ByteBuffer target) {
        for (float word : words.get(buffer)) target.putFloat(word);
    }
}
