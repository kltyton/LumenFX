package com.kltyton.lumenfx.client.render.niagara;

import com.kltyton.lumenfx.client.simulation.niagara.NiagaraParticles;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import net.minecraft.world.phys.Vec3;

/** Worker-owned CPU view of the same component packet consumed by the vertex factories. */
public final class NiagaraComponentGeometry {
    private final ByteBuffer packet = ByteBuffer.allocate(NiagaraComponentUniforms.BYTES).order(ByteOrder.nativeOrder());
    private final FloatBuffer words = packet.asFloatBuffer();
    private final float[] previousRibbonInverse = new float[3];
    private Vec3 camera;

    public void prepare(NiagaraParticles particles, Vec3 camera) {
        var source = particles.renderData();
        packet.clear();
        NiagaraComponentUniforms.write(packet, particles.ownerPose(), particles.previousOwnerPose(),
                source.originX(), source.originY(), source.originZ(), camera);
        for (int axis = 0; axis < 3; axis++) {
            float x = words.get(32 + axis), y = words.get(36 + axis), z = words.get(40 + axis);
            float square = (x * x + y * y) + z * z;
            previousRibbonInverse[axis] = (float)(1 / Math.sqrt(Math.max(.0001f, square)));
        }
        this.camera = camera;
    }

    public void worldPosition(double[] target, int offset, float x, float y, float z, boolean local, boolean previous) {
        target[offset] = camera.x + translated(x, y, z, 0, local, previous) * .01f;
        target[offset + 1] = camera.y + translated(x, y, z, 2, local, previous) * .01f;
        target[offset + 2] = camera.z + translated(x, y, z, 1, local, previous) * .01f;
    }

    public void position(float[] target, int offset, float x, float y, float z, boolean local, boolean previous) {
        target[offset] = translated(x, y, z, 0, local, previous) * .01f;
        target[offset + 1] = translated(x, y, z, 2, local, previous) * .01f;
        target[offset + 2] = translated(x, y, z, 1, local, previous) * .01f;
    }

    public void direction(float[] target, int offset, float x, float y, float z, boolean local, boolean previous) {
        direction(target, offset, x, y, z, local, previous, false, true);
    }

    public void sourcePosition(float[] target, int offset, float x, float y, float z, boolean local, boolean previous) {
        for (int axis = 0; axis < 3; axis++) target[offset + axis] = translated(x, y, z, axis, local, previous);
    }

    public void sourcePositionOffset(float[] target, int offset, float x, float y, float z, float vx, float vy, float vz,
                                      boolean local, boolean previous) {
        target[offset] = translated(x, y, z, 0, local, previous, vx, true);
        target[offset + 1] = translated(x, y, z, 1, local, previous, vy, true);
        target[offset + 2] = translated(x, y, z, 2, local, previous, vz, true);
    }

    /** Tile and offset are kept separate until world-space velocity and cross-section offsets are applied. */
    public void sourceWorldPosition(float[] target, float x, float y, float z, boolean local, boolean previous) {
        for (int axis = 0; axis < 3; axis++) {
            if (local) {
                int matrix = (previous ? 32 : 0) + axis;
                target[axis] = words.get(48 + axis) * 0.000000476837158203125f;
                target[3 + axis] = ((x * words.get(matrix) + y * words.get(matrix + 4)) + z * words.get(matrix + 8)) + words.get(matrix + 12);
            } else {
                target[axis] = words.get(56 + axis);
                target[3 + axis] = axis == 0 ? x : axis == 1 ? y : z;
            }
        }
    }

    public void sourceTranslatedPosition(float[] target, float[] world) {
        for (int axis = 0; axis < 3; axis++) target[axis] = translateWorld(world[axis], world[3 + axis], axis);
    }

    public void sourceTranslatedPositionOffset(float[] target, float[] world, float x, float y, float z) {
        target[0] = translateWorld(world[0], world[3] + x, 0);
        target[1] = translateWorld(world[1], world[4] + y, 1);
        target[2] = translateWorld(world[2], world[5] + z, 2);
    }

    private float translateWorld(float tile, float offset, int axis) {
        float relativeTile = tile + words.get(60 + axis);
        float relativeOffset = offset + words.get(64 + axis);
        return relativeTile * 2097152.0f + relativeOffset;
    }

    public void sourceRibbonDirection(float[] target, int offset, float x, float y, float z, boolean local, boolean previous) {
        direction(target, offset, x, y, z, local, previous, true, false);
    }

    private void direction(float[] target, int offset, float x, float y, float z, boolean local, boolean previous,
                           boolean ribbon, boolean minecraft) {
        float dx = x, dy = y, dz = z;
        if (local) {
            x *= ribbon && previous ? previousRibbonInverse[0] : words.get(52);
            y *= ribbon && previous ? previousRibbonInverse[1] : words.get(53);
            z *= ribbon && previous ? previousRibbonInverse[2] : words.get(54);
            int matrix = previous ? 32 : 0;
            dx = (x * words.get(matrix) + y * words.get(matrix + 4)) + z * words.get(matrix + 8);
            dy = (x * words.get(matrix + 1) + y * words.get(matrix + 5)) + z * words.get(matrix + 9);
            dz = (x * words.get(matrix + 2) + y * words.get(matrix + 6)) + z * words.get(matrix + 10);
        }
        target[offset] = dx; target[offset + 1] = minecraft ? dz : dy; target[offset + 2] = minecraft ? dy : dz;
    }

    private float translated(float x, float y, float z, int axis, boolean local, boolean previous) {
        return translated(x, y, z, axis, local, previous, 0, false);
    }

    private float translated(float x, float y, float z, int axis, boolean local, boolean previous, float vertexOffset,
                              boolean addVertexOffset) {
        float tile, offset;
        if (local) {
            int matrix = (previous ? 32 : 0) + axis;
            offset = ((x * words.get(matrix) + y * words.get(matrix + 4)) + z * words.get(matrix + 8)) + words.get(matrix + 12);
            tile = words.get(48 + axis) * 0.000000476837158203125f;
        } else {
            offset = axis == 0 ? x : axis == 1 ? y : z;
            tile = words.get(56 + axis);
        }
        if (addVertexOffset) offset += vertexOffset;
        float relativeTile = tile + words.get(60 + axis);
        float relativeOffset = offset + words.get(64 + axis);
        float tileOffset = relativeTile * 2097152.0f;
        return tileOffset + relativeOffset;
    }
}
