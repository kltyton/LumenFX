package com.kltyton.lumenfx.api.client.groom;

import java.nio.ByteBuffer;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;

/** Native HairStrands packed guides. Each point stores the two source words, including its attributes. */
public final class NiagaraGroomGuides {
    private final int[] rest;
    private final int[] initialDeformed;
    private final int[] curves;
    private final int[] pointToCurve;
    private final Vec3 restOffset;
    private final Vec3 deformedOffset;

    public NiagaraGroomGuides(int[] rest, int[] initialDeformed, int[] curves, int[] pointToCurve,
                             Vec3 restOffset, Vec3 deformedOffset) {
        if (rest.length == 0 || (rest.length & 1) != 0 || rest.length != initialDeformed.length
                || curves.length == 0 || pointToCurve.length != Math.ceilDiv(rest.length / 2, 8))
            throw new IllegalArgumentException("Inconsistent packed Niagara groom guides");
        this.rest = rest.clone();
        this.initialDeformed = initialDeformed.clone();
        this.curves = curves.clone();
        this.pointToCurve = pointToCurve.clone();
        this.restOffset = Objects.requireNonNull(restOffset);
        this.deformedOffset = Objects.requireNonNull(deformedOffset);
    }

    public int points() { return rest.length / 2; }
    public int curves() { return curves.length; }
    public Vec3 restOffset() { return restOffset; }
    public Vec3 deformedOffset() { return deformedOffset; }

    public int pointBytes() { return Math.multiplyExact(rest.length, Integer.BYTES); }
    public int curveBytes() { return Math.multiplyExact(curves.length, Integer.BYTES); }
    public int pointToCurveBytes() { return Math.multiplyExact(pointToCurve.length, Integer.BYTES); }

    public void writeRest(ByteBuffer target) { write(rest, target); }
    public void writeInitialDeformed(ByteBuffer target) { write(initialDeformed, target); }
    public void writeCurves(ByteBuffer target) { write(curves, target); }
    public void writePointToCurve(ByteBuffer target) { write(pointToCurve, target); }

    private static void write(int[] values, ByteBuffer target) {
        for (int value : values) target.putInt(value);
    }
}
