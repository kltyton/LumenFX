package com.kltyton.lumenfx.client.render.niagara.gpu.scene;

import com.kltyton.lumenfx.client.render.niagara.gpu.scene.NiagaraVoxelDistanceFieldAtlas.Atlas;
import java.util.function.BooleanSupplier;

/** Builds the coarse R8 clipmaps using the source propagation sequence. */
final class NiagaraDistanceFieldMip {
    private static final int PROPAGATION_STEPS = 5;
    private static final float STEP = .5f / NiagaraVoxelDistanceFieldAtlas.INFLUENCE_VOXELS;

    private NiagaraDistanceFieldMip() { }

    static byte[] compose(Atlas atlas, BooleanSupplier cancelled) {
        int side = atlas.layout().mipResolution();
        int count = Math.multiplyExact(Math.multiplyExact(side, side), side);
        int clips = atlas.layout().clips().size();
        var output = new byte[Math.multiplyExact(count, clips)];
        var current = new float[count];
        var next = new float[count];
        float inverseSide = 1f / side;
        float scale = 1f / atlas.layout().mipFactor(), bias = .5f - .5f * scale;
        for (int clip = 0; clip < clips; clip++) {
            for (int z = 0; z < side; z++) {
                NiagaraVoxelDistanceFieldAtlas.checkCancellation(cancelled);
                for (int y = 0; y < side; y++) for (int x = 0; x < side; x++) {
                    float value = NiagaraVoxelDistanceFieldAtlas.samplePage(atlas, clip, (x + .5f) * inverseSide,
                            (y + .5f) * inverseSide, (z + .5f) * inverseSide);
                    current[index(side, x, y, z)] = value < 1 ? value * scale + bias : 1;
                }
            }
            for (int iteration = 0; iteration < PROPAGATION_STEPS; iteration++) {
                propagate(current, next, side, cancelled);
                var swap = current;
                current = next;
                next = swap;
            }
            for (int i = 0; i < count; i++) output[clip * count + i] = (byte)Math.round(current[i] * 255);
        }
        return output;
    }

    private static void propagate(float[] from, float[] into, int side, BooleanSupplier cancelled) {
        for (int z = 0; z < side; z++) {
            NiagaraVoxelDistanceFieldAtlas.checkCancellation(cancelled);
            int z0 = Math.max(0, z - 1), z1 = Math.min(side - 1, z + 1);
            for (int y = 0; y < side; y++) {
                int y0 = Math.max(0, y - 1), y1 = Math.min(side - 1, y + 1);
                for (int x = 0; x < side; x++) {
                    int x0 = Math.max(0, x - 1), x1 = Math.min(side - 1, x + 1);
                    int at = index(side, x, y, z);
                    float vx = Math.min(from[index(side, x0, y, z)], from[index(side, x1, y, z)]);
                    float vy = Math.min(from[index(side, x, y0, z)], from[index(side, x, y1, z)]);
                    float vz = Math.min(from[index(side, x, y, z0)], from[index(side, x, y, z1)]);
                    float low = Math.min(vx, Math.min(vy, vz)), high = Math.max(vx, Math.max(vy, vz));
                    float middle = vx != low && vx != high ? vx : vy != low && vy != high ? vy : vz;
                    float center = Math.min(from[at], low + STEP);
                    center = eikonal(center, 2, low + middle, low * low + middle * middle);
                    center = eikonal(center, 3, low + middle + high, low * low + middle * middle + high * high);
                    into[at] = Math.round(Math.clamp(center, 0f, 1f) * 255) * (1f / 255);
                }
            }
        }
    }

    private static float eikonal(float center, int axes, float sum, float squares) {
        float root = sum * sum - axes * (squares - STEP * STEP);
        float distance = (sum + (float)Math.sqrt(root)) / axes;
        return distance < center ? distance : center;
    }

    private static int index(int side, int x, int y, int z) { return (z * side + y) * side + x; }
}
