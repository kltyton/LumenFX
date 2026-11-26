package com.kltyton.lumenfx.runtime.simulation;

import com.kltyton.lumenfx.runtime.model.MaterialDataCurves;

/** Mutable pooled particle state shared by Unity, Niagara, and Photon emitters. */
public final class ParticleInstance {
    public static final int MAX_TRAIL_POINTS = 24;

    public double x, y, z;
    public double prevX, prevY, prevZ;
    public float vx, vy, vz;
    public float age;
    public float lifetime;
    public float startWidth, startHeight;
    public float width, height;
    public float rotationDeg;
    public float angularVelocityDeg;
    public float startR, startG, startB, startA;
    public float r, g, b, a;
    public float curveRandom;
    public float customX, customY, customZ, customW;

    void setMaterialData(MaterialDataCurves curves) {
        float age = normalizedAge();
        customX = curves.x().evaluate(age, curveRandom);
        customY = curves.y().evaluate(age, curveRandom);
        customZ = curves.z().evaluate(age, curveRandom);
        customW = curves.w().evaluate(age, curveRandom);
    }
    public int packedLight = 0x00F000F0;

    public final double[] trailX = new double[MAX_TRAIL_POINTS];
    public final double[] trailY = new double[MAX_TRAIL_POINTS];
    public final double[] trailZ = new double[MAX_TRAIL_POINTS];
    public int trailCount;
    public float trailSampleAccum;

    public void resetTrail(double wx, double wy, double wz) {
        trailX[0] = wx;
        trailY[0] = wy;
        trailZ[0] = wz;
        trailCount = 1;
        trailSampleAccum = 0f;
    }

    public void pushTrailPoint(double wx, double wy, double wz) {
        if (trailCount < MAX_TRAIL_POINTS) {
            trailX[trailCount] = wx;
            trailY[trailCount] = wy;
            trailZ[trailCount] = wz;
            trailCount++;
        } else {
            System.arraycopy(trailX, 1, trailX, 0, MAX_TRAIL_POINTS - 1);
            System.arraycopy(trailY, 1, trailY, 0, MAX_TRAIL_POINTS - 1);
            System.arraycopy(trailZ, 1, trailZ, 0, MAX_TRAIL_POINTS - 1);
            trailX[MAX_TRAIL_POINTS - 1] = wx;
            trailY[MAX_TRAIL_POINTS - 1] = wy;
            trailZ[MAX_TRAIL_POINTS - 1] = wz;
        }
    }

    public double lerpX(float pt) { return prevX + (x - prevX) * pt; }
    public double lerpY(float pt) { return prevY + (y - prevY) * pt; }
    public double lerpZ(float pt) { return prevZ + (z - prevZ) * pt; }

    public float normalizedAge() {
        return lifetime > 1e-5f ? Math.max(0f, Math.min(1f, age / lifetime)) : 1f;
    }
}
