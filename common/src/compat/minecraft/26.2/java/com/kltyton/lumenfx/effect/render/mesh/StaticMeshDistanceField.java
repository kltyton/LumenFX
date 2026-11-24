package com.kltyton.lumenfx.effect.render.mesh;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;

/** Native sparse R8 brick data in source-local mesh space. */
public record StaticMeshDistanceField(Vec3 minimum, Vec3 maximum, boolean mostlyTwoSided, List<Mip> mips) {
    public static final int BRICK_SIZE = 8;
    public static final int BRICK_BYTES = BRICK_SIZE * BRICK_SIZE * BRICK_SIZE;
    public static final int MIP_COUNT = 3;

    public StaticMeshDistanceField {
        mips = List.copyOf(mips);
        if (!finite(minimum) || !finite(maximum) || minimum.x > maximum.x || minimum.y > maximum.y || minimum.z > maximum.z
                || maximum.equals(minimum) || mips.size() != MIP_COUNT)
            throw new IllegalArgumentException("Invalid native static mesh distance field bounds or mip count");
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    public static final class Mip {
        private final int x, y, z, brickCount;
        private final Vec3 uvScale, uvAdd;
        private final float distanceScale, distanceBias;
        private final byte[] payload;
        private final int tableBytes;

        public Mip(int x, int y, int z, int brickCount, Vec3 uvScale, Vec3 uvAdd, float distanceScale,
                   float distanceBias, byte[] payload) {
            if (payload.length != payloadSize(x, y, z, brickCount) || !finite(uvScale) || !finite(uvAdd)
                    || !Float.isFinite(distanceScale) || distanceScale <= 0 || !Float.isFinite(distanceBias))
                throw new IllegalArgumentException("Invalid native static mesh distance field mip");
            this.x = x; this.y = y; this.z = z; this.brickCount = brickCount;
            this.uvScale = uvScale; this.uvAdd = uvAdd;
            this.distanceScale = distanceScale; this.distanceBias = distanceBias;
            this.payload = payload.clone();
            tableBytes = x * y * z * Integer.BYTES;
            for (int index = 0; index < tableBytes / Integer.BYTES; index++) {
                int value = brickIndex(index);
                if (value != -1 && (value < 0 || value >= brickCount))
                    throw new IllegalArgumentException("Invalid native static mesh distance field brick index");
            }
        }

        public static int payloadSize(int x, int y, int z, int bricks) {
            if (x <= 0 || y <= 0 || z <= 0 || x >= 1024 || y >= 1024 || z >= 1024 || bricks < 0)
                throw new IllegalArgumentException("Invalid native static mesh distance field dimensions");
            int table = Math.multiplyExact(x * y * z, Integer.BYTES);
            return Math.addExact(table, Math.multiplyExact(bricks, BRICK_BYTES));
        }

        public int x() { return x; }
        public int y() { return y; }
        public int z() { return z; }
        public int brickCount() { return brickCount; }
        public Vec3 uvScale() { return uvScale; }
        public Vec3 uvAdd() { return uvAdd; }
        public float distanceScale() { return distanceScale; }
        public float distanceBias() { return distanceBias; }
        public int brickIndex(int index) {
            int offset = Objects.checkIndex(index, tableBytes / Integer.BYTES) * Integer.BYTES;
            return Byte.toUnsignedInt(payload[offset]) | Byte.toUnsignedInt(payload[offset + 1]) << 8
                    | Byte.toUnsignedInt(payload[offset + 2]) << 16 | Byte.toUnsignedInt(payload[offset + 3]) << 24;
        }
        public ByteBuffer bricks() {
            return ByteBuffer.wrap(payload, tableBytes, payload.length - tableBytes).slice().asReadOnlyBuffer();
        }
    }
}
