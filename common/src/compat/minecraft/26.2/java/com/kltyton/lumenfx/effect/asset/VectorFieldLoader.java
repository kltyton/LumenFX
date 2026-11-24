package com.kltyton.lumenfx.effect.asset;

import com.kltyton.lumenfx.effect.noise.VectorFieldVolume;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceProvider;

/** Vector fields are decoded during resource preparation, never during simulation or rendering. */
public final class VectorFieldLoader {
    private VectorFieldLoader() { }

    public static VectorFieldVolume load(ResourceProvider resources, Identifier id) {
        Identifier path = Identifier.fromNamespaceAndPath(id.getNamespace(), "lumenfx/vector_fields/" + id.getPath() + ".lfvf");
        try (var input = resources.getResourceOrThrow(path).open()) {
            ByteBuffer data = ByteBuffer.wrap(input.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
            if (data.remaining() < 48 || data.getInt() != 0x4656464c)
                throw new IOException("Unsupported vector field header: " + id);
            int version = data.getInt();
            if (version < 1 || version > 3) throw new IOException("Unsupported vector field version " + version + ": " + id);
            if (version == 3 && data.remaining() < 64) throw new IOException("Truncated vector field bounds: " + id);
            int x = data.getInt(), y = data.getInt(), z = data.getInt();
            double minX = version == 3 ? data.getDouble() : data.getFloat();
            double minY = version == 3 ? data.getDouble() : data.getFloat();
            double minZ = version == 3 ? data.getDouble() : data.getFloat();
            double maxX = version == 3 ? data.getDouble() : data.getFloat();
            double maxY = version == 3 ? data.getDouble() : data.getFloat();
            double maxZ = version == 3 ? data.getDouble() : data.getFloat();
            if (data.getInt() != 8 || x <= 0 || y <= 0 || z <= 0)
                throw new IOException("Invalid vector field dimensions or storage: " + id);
            int cells = Math.multiplyExact(Math.multiplyExact(x, y), z);
            if (data.remaining() != Math.multiplyExact(cells, 8)) throw new IOException("Vector field payload length differs: " + id);
            short[] values = new short[Math.multiplyExact(cells, 4)];
            for (int i = 0; i < values.length; i++) {
                values[i] = data.getShort();
                if (i % 4 != 3 && !Float.isFinite(Float.float16ToFloat(values[i])))
                    throw new IOException("Non-finite vector field value: " + id);
            }
            return VectorFieldVolume.fromHalfRgba(x, y, z, minX, minY, minZ, maxX, maxY, maxZ, values, version >= 2);
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot load vector field " + id, error);
        }
    }
}
