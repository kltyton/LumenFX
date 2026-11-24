package com.kltyton.lumenfx.client.resource.niagara.groom;

import com.kltyton.lumenfx.api.client.groom.NiagaraGroomAsset;
import com.kltyton.lumenfx.api.client.groom.NiagaraGroomGuides;
import com.kltyton.lumenfx.api.client.groom.NiagaraGroomPhysics;
import com.kltyton.lumenfx.api.client.groom.NiagaraGroomPhysics.Collision;
import com.kltyton.lumenfx.api.client.groom.NiagaraGroomPhysics.Forces;
import com.kltyton.lumenfx.api.client.groom.NiagaraGroomPhysics.Scales;
import com.kltyton.lumenfx.api.client.groom.NiagaraGroomPhysics.Spring;
import com.kltyton.lumenfx.api.client.groom.NiagaraGroomPhysics.Strand;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.phys.Vec3;

public final class NiagaraGroomAssetLoader {
    private static final int MAGIC = 0x5247464c;
    private static final int VERSION = 1;
    private static final int GROUP_HEADER_BYTES = 672;

    private NiagaraGroomAssetLoader() { }

    public static NiagaraGroomAsset load(ResourceManager resources, Identifier key) {
        Identifier file = Identifier.fromNamespaceAndPath(key.getNamespace(), "lumenfx/grooms/" + key.getPath() + ".lfgr");
        try (var stream = resources.getResourceOrThrow(file).open()) {
            var data = ByteBuffer.wrap(stream.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
            if (data.remaining() < 12 || data.getInt() != MAGIC || data.getInt() != VERSION)
                throw new IllegalArgumentException("Invalid native groom header");
            int count = data.getInt();
            if (count <= 0 || count > data.remaining() / GROUP_HEADER_BYTES)
                throw new IllegalArgumentException("Invalid native groom group count");
            var groups = new ArrayList<NiagaraGroomAsset.Group>(count);
            for (int i = 0; i < count; i++) groups.add(group(data));
            if (data.hasRemaining()) throw new IllegalArgumentException("Unexpected trailing groom data");
            return new NiagaraGroomAsset(groups);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read Niagara Groom " + file, failure);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Cannot decode Niagara Groom " + file, failure);
        }
    }

    private static NiagaraGroomAsset.Group group(ByteBuffer data) {
        if (data.remaining() < GROUP_HEADER_BYTES) throw new IllegalArgumentException("Truncated groom group header");
        int points = data.getInt();
        int curves = data.getInt();
        if (points < 0 || curves < 0 || curves > points || (points == 0) != (curves == 0))
            throw new IllegalArgumentException("Invalid groom guide topology");
        Vec3 offset = vector(data);
        int size = data.getInt();
        int subSteps = data.getInt();
        int iterations = data.getInt();
        int flags = data.getInt();
        if ((flags & ~127) != 0) throw new IllegalArgumentException("Unknown groom physics flags");
        float gravityPreloading = data.getFloat();
        var forces = new Forces(vector(data), data.getFloat(), vector(data));
        var bend = new Spring(flag(flags, 2), flag(flags, 4), data.getFloat(), data.getFloat());
        var stretch = new Spring(flag(flags, 8), flag(flags, 16), data.getFloat(), data.getFloat());
        var collision = new Collision(flag(flags, 32), flag(flags, 64), data.getFloat(), data.getFloat(),
                data.getFloat(), data.getInt(), data.getInt(), data.getInt(), data.getFloat());
        var strand = new Strand(data.getFloat(), data.getFloat(), data.getFloat());
        var scales = new float[4 * Scales.NODE_CAPACITY];
        for (int i = 0; i < scales.length; i++) scales[i] = data.getFloat();
        var physics = new NiagaraGroomPhysics(size, subSteps, iterations, gravityPreloading, forces, bend, stretch,
                collision, strand, new Scales(scales));
        if (points == 0) return new NiagaraGroomAsset.Group(flag(flags, 1), physics, null);

        int curveChunks = Math.ceilDiv(points, 8);
        long bytes = points * 8L + curves * 4L + curveChunks * 4L;
        if (bytes > data.remaining()) throw new IllegalArgumentException("Truncated packed groom guide buffers");
        int[] rest = words(data, points * 2);
        var guides = new NiagaraGroomGuides(rest, rest, words(data, curves), words(data, curveChunks), offset, offset);
        return new NiagaraGroomAsset.Group(flag(flags, 1), physics, guides);
    }

    private static boolean flag(int flags, int bit) { return (flags & bit) != 0; }

    private static Vec3 vector(ByteBuffer data) { return new Vec3(data.getDouble(), data.getDouble(), data.getDouble()); }

    private static int[] words(ByteBuffer data, int count) {
        var words = new int[count];
        for (int i = 0; i < words.length; i++) words[i] = data.getInt();
        return words;
    }
}
