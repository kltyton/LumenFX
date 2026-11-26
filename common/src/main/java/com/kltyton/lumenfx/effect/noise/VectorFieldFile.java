package com.kltyton.lumenfx.effect.noise;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Reads the .lfvf payload independent of the host resource system. */
public final class VectorFieldFile {
    private VectorFieldFile() { }

    public static VectorFieldVolume read(byte[] bytes) throws IOException {
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (data.remaining() < 48 || data.getInt() != 0x4656464c) {
            throw new IOException("Unsupported vector field header");
        }
        int version = data.getInt();
        if (version < 1 || version > 3) throw new IOException("Unsupported vector field version " + version);
        if (version == 3 && data.remaining() < 64) throw new IOException("Truncated vector field bounds");
        int x = data.getInt(), y = data.getInt(), z = data.getInt();
        double minX = version == 3 ? data.getDouble() : data.getFloat();
        double minY = version == 3 ? data.getDouble() : data.getFloat();
        double minZ = version == 3 ? data.getDouble() : data.getFloat();
        double maxX = version == 3 ? data.getDouble() : data.getFloat();
        double maxY = version == 3 ? data.getDouble() : data.getFloat();
        double maxZ = version == 3 ? data.getDouble() : data.getFloat();
        if (data.getInt() != 8 || x <= 0 || y <= 0 || z <= 0) {
            throw new IOException("Invalid vector field dimensions or storage");
        }
        try {
            int cells = Math.multiplyExact(Math.multiplyExact(x, y), z);
            if (data.remaining() != Math.multiplyExact(cells, 8)) {
                throw new IOException("Vector field payload length differs");
            }
            short[] values = new short[Math.multiplyExact(cells, 4)];
            data.asShortBuffer().get(values);
            return VectorFieldVolume.fromHalfRgba(x, y, z, minX, minY, minZ, maxX, maxY, maxZ, values, version >= 2);
        } catch (ArithmeticException | IllegalArgumentException invalid) {
            throw new IOException("Invalid vector field bounds or values", invalid);
        }
    }
}
