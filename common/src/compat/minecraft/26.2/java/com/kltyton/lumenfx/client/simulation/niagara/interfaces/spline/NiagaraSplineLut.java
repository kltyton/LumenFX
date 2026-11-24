package com.kltyton.lumenfx.client.simulation.niagara.interfaces.spline;

import com.kltyton.lumenfx.effect.curve.NiagaraSplineCurves;
import java.nio.ByteBuffer;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;

/** Uniform-distance spline samples shared by the CPU LUT path and GPU source buffers. */
public final class NiagaraSplineLut {
    public enum Channel { POSITIONS, SCALES, ROTATIONS }
    static final NiagaraSplineLut EMPTY = new NiagaraSplineLut(NiagaraSplineCurves.EMPTY, 0);
    private final Vec3[] positions, scales;
    private final Quaterniond[] rotations;
    private final float length, step, inverseStep;

    NiagaraSplineLut(NiagaraSplineCurves source, int steps) {
        positions = new Vec3[steps];
        scales = new Vec3[steps];
        rotations = new Quaterniond[steps];
        length = source.length();
        int last = maxIndex();
        step = last > 0 ? (1f / last) * length : 0;
        inverseStep = step > 0 ? 1 / step : 0;
        for (int index = 0; index < steps; index++) {
            float key = source.timeAtDistance(index * step);
            positions[index] = source.position(key);
            scales[index] = source.scale(key);
            rotations[index] = source.rotation(key);
        }
    }

    public int maxIndex() { return positions.length - 1; }
    public float length() { return length; }
    public float step() { return step; }
    public float inverseStep() { return inverseStep; }
    public int bytes() { return Math.multiplyExact(Math.max(1, positions.length), 4 * Float.BYTES); }

    public void write(Channel channel, ByteBuffer output) {
        if (maxIndex() < 0) {
            float value = channel == Channel.SCALES ? 1 : 0;
            output.putFloat(value).putFloat(value).putFloat(value).putFloat(1);
            return;
        }
        for (int index = 0; index <= maxIndex(); index++) {
            if (channel == Channel.ROTATIONS) {
                var rotation = rotations[index];
                output.putFloat((float)rotation.x).putFloat((float)rotation.y).putFloat((float)rotation.z).putFloat((float)rotation.w);
            } else {
                var value = channel == Channel.POSITIONS ? positions[index] : scales[index];
                output.putFloat((float)value.x).putFloat((float)value.y).putFloat((float)value.z).putFloat(1);
            }
        }
    }

    private record Interval(int previous, int next, float alpha) { }
    private Interval interval(float distance) {
        float key = distance * inverseStep;
        int previous = Math.clamp((int)Math.floor(key), 0, maxIndex());
        int next = Math.clamp((int)Math.ceil(key), 0, maxIndex());
        return new Interval(previous, next, key - (int)key);
    }

    Vec3 position(float distance) {
        if (maxIndex() < 0) return Vec3.ZERO;
        var key = interval(distance);
        return key.previous() == key.next() ? positions[key.previous()]
                : positions[key.previous()].lerp(positions[key.next()], key.alpha());
    }

    Quaterniond rotation(float distance) {
        if (maxIndex() < 0) return new Quaterniond();
        var key = interval(distance);
        var rotation = new Quaterniond(rotations[key.previous()]);
        if (key.previous() != key.next()) rotation.slerp(rotations[key.next()], key.alpha()).normalize();
        return rotation;
    }

    Vec3 tangent(float distance) {
        if (maxIndex() < 1) return Vec3.ZERO;
        var key = interval(distance);
        int previous = key.previous(), next = key.next();
        if (next == previous) {
            if (next < maxIndex()) next++;
            else previous--;
        }
        return positions[next].subtract(positions[previous]);
    }

    float nearest(Vec3 position) {
        int best = 0;
        float minimum = Float.MAX_VALUE;
        for (int index = 0; index < positions.length; index++) {
            float distance = (float)positions[index].distanceToSqr(position);
            if (distance < minimum) {
                minimum = distance;
                best = index;
            }
        }
        return best > 0 && maxIndex() > 0 ? (float)best / maxIndex() : 0;
    }
}
