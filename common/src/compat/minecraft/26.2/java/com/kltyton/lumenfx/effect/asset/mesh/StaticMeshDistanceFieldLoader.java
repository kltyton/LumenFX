package com.kltyton.lumenfx.effect.asset.mesh;

import com.kltyton.lumenfx.effect.render.mesh.StaticMeshDistanceField;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshDistanceField.Mip;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.world.phys.Vec3;

/** Loads the native mesh builder's sparse distance-field exchange. */
final class StaticMeshDistanceFieldLoader {
    private StaticMeshDistanceFieldLoader() { }

    static StaticMeshDistanceField load(ResourceProvider resources, Identifier path) throws IOException {
        try (var input = new DataInputStream(new BufferedInputStream(resources.getResourceOrThrow(path).open()))) {
            if (word(input) != 0x4644464c || word(input) != 1)
                throw new IOException("Invalid native static mesh distance field header: " + path);
            int flags = word(input), count = word(input);
            if ((flags & ~1) != 0 || count != StaticMeshDistanceField.MIP_COUNT)
                throw new IOException("Invalid native static mesh distance field flags or mip count: " + path);
            var minimum = vector(input);
            var maximum = vector(input);
            var mips = new ArrayList<Mip>(count);
            for (int index = 0; index < count; index++) {
                int x = word(input), y = word(input), z = word(input), bricks = word(input);
                var scale = vector(input);
                var add = vector(input);
                float distanceScale = scalar(input), distanceBias = scalar(input);
                int size = word(input);
                if (size != Mip.payloadSize(x, y, z, bricks))
                    throw new IOException("Native static mesh distance field mip size differs: " + path);
                byte[] payload = input.readNBytes(size);
                if (payload.length != size) throw new IOException("Truncated native static mesh distance field mip: " + path);
                mips.add(new Mip(x, y, z, bricks, scale, add, distanceScale, distanceBias, payload));
            }
            if (input.read() != -1) throw new IOException("Unexpected native static mesh distance field tail: " + path);
            return new StaticMeshDistanceField(minimum, maximum, (flags & 1) != 0, mips);
        }
    }

    private static int word(DataInputStream input) throws IOException { return Integer.reverseBytes(input.readInt()); }
    private static float scalar(DataInputStream input) throws IOException { return Float.intBitsToFloat(word(input)); }
    private static Vec3 vector(DataInputStream input) throws IOException { return new Vec3(scalar(input), scalar(input), scalar(input)); }
}
