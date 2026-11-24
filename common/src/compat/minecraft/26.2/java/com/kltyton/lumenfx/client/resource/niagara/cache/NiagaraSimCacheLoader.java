package com.kltyton.lumenfx.client.resource.niagara.cache;

import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.ArrayFrame;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.ChannelColumn;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.ChannelFrame;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.DynamicMesh;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.DynamicMeshPair;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheVolumeFrame;
import com.kltyton.lumenfx.effect.niagara.mesh.NiagaraDynamicMeshFrame;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.Target2D;

import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.niagara.NiagaraSystemState;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheDebugData;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Buffer;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.CaptureMode;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.EmitterFrame;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Field;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Frame;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Layout;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Range;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Transform;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Storage;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Reads the native cache export without changing raw register values or particle correspondence. */
public final class NiagaraSimCacheLoader {
    private NiagaraSimCacheLoader() { }

    public static NiagaraSimulationCache load(ResourceManager resources, Identifier id) throws IOException {
        var path = Identifier.fromNamespaceAndPath(id.getNamespace(), "lumenfx/sim_caches/" + id.getPath() + ".lfsc");
        try (var resource = resources.getResourceOrThrow(path).open()) {
            var data = ByteBuffer.wrap(resource.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
            int magic = data.getInt(), version = data.getInt();
            if (magic != 0x4353464c || version < 1 || version > 6) throw new IOException("Invalid Niagara simulation cache header " + id);
            String sourceSystem = text(data);
            float start = data.getFloat(), duration = data.getFloat();
            int flags = data.getInt(), emitters = count(data, 28), frames = count(data, 84);
            int flagMask = version >= 6 ? 31 : 3;
            if ((flags & ~flagMask) != 0) throw new IOException("Unknown Niagara simulation cache flags " + id);
            CaptureMode mode = version >= 6 ? CaptureMode.values()[flags >>> 2 & 3] : CaptureMode.UNKNOWN;
            if (version >= 3) {
                int materials = count(data, Integer.BYTES);
                for (int index = 0; index < materials; index++) text(data);
            }
            Layout system = layout(data);
            var layouts = new ArrayList<Layout>(emitters);
            for (int index = 0; index < emitters; index++) layouts.add(layout(data));
            var values = new ArrayList<Frame>(frames);
            for (int index = 0; index < frames; index++) {
                float age = data.getFloat();
                int tick = data.getInt();
                var transform = new Transform(vector(data), data.getDouble(), data.getDouble(), data.getDouble(), data.getDouble(), vector(data), vector(data));
                var bounds = bounds(data);
                var systems = buffer(data, system, version);
                var particles = new ArrayList<EmitterFrame>(emitters);
                for (var layout : layouts) particles.add(new EmitterFrame(bounds(data), data.getInt(), buffer(data, layout, version)));
                values.add(new Frame(age, tick, transform, bounds, systems, particles));
            }
            var interfaces = new ArrayList<NiagaraCacheInterfaceData>();
            if (version >= 2) {
                int size = count(data, 12);
                for (int index = 0; index < size; index++) interfaces.add(interfaceData(data, frames));
            }
            var debug = version == 5 || (flags & 16) != 0 ? debugData(data, frames) : null;
            if (data.hasRemaining()) throw new IOException("Unexpected Niagara simulation cache tail " + id);
            return new NiagaraSimulationCache(sourceSystem, start, duration, (flags & 1) != 0, (flags & 2) != 0,
                    system, layouts, values, interfaces, debug, mode);
        } catch (BufferUnderflowException | ArithmeticException | IllegalArgumentException error) {
            throw new IOException("Invalid Niagara simulation cache " + id, error);
        }
    }

    private static NiagaraCacheDebugData debugData(ByteBuffer data, int frames) throws IOException {
        int flags = data.getInt();
        if ((flags & ~127) != 0) throw new IOException("Unknown Niagara cache system state flags");
        var inactive = enumValue(data, NiagaraSystemState.InactiveResponse.values());
        var loop = enumValue(data, NiagaraSystemState.LoopBehavior.values());
        int loopCount = data.getInt();
        var state = new NiagaraSystemState((flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0,
                (flags & 8) != 0, (flags & 16) != 0, (flags & 32) != 0, (flags & 64) != 0, inactive, loop, loopCount,
                new NiagaraSystemState.Range(data.getFloat(), data.getFloat()),
                new NiagaraSystemState.Range(data.getFloat(), data.getFloat()));
        var snapshots = new ArrayList<NiagaraCacheDebugData.Frame>(frames);
        for (int frame = 0; frame < frames; frame++) {
            int size = count(data, 2 * Integer.BYTES);
            var stores = new LinkedHashMap<String, List<NiagaraCacheDebugData.Parameter>>();
            for (int store = 0; store < size; store++) {
                String name = text(data);
                int parameters = count(data, 6 * Integer.BYTES);
                var values = new ArrayList<NiagaraCacheDebugData.Parameter>(parameters);
                for (int i = 0; i < parameters; i++) {
                    var key = new Key(text(data), text(data), data.getInt(), data.getInt(), enumValue(data, Storage.values()));
                    if (key.storage() == Storage.NUMERIC) {
                        byte[] bytes = new byte[count(data, 1)];
                        data.get(bytes);
                        values.add(new NiagaraCacheDebugData.Parameter(key, bytes, null));
                    } else {
                        int present = data.getInt();
                        if (present != 0 && present != 1) throw new IOException("Invalid Niagara debug object reference");
                        var reference = present == 0 ? null : new NiagaraCacheDebugData.Reference(text(data), text(data));
                        values.add(new NiagaraCacheDebugData.Parameter(key, new byte[0], reference));
                    }
                }
                if (stores.putIfAbsent(name, values) != null) throw new IOException("Duplicate Niagara cache debug store " + name);
            }
            snapshots.add(new NiagaraCacheDebugData.Frame(stores));
        }
        return new NiagaraCacheDebugData(state, snapshots);
    }

    private static <T extends Enum<T>> T enumValue(ByteBuffer data, T[] options) throws IOException {
        int value = data.getInt();
        if (value < 0 || value >= options.length) throw new IOException("Invalid Niagara cache enum value " + value);
        return options[value];
    }

    private static NiagaraCacheInterfaceData interfaceData(ByteBuffer data, int frames) throws IOException {
        var key = new NiagaraCacheInterfaceData.Key(text(data), text(data));
        int type = data.getInt();
        int minimumBytes = type == 3 || type == 6 ? Integer.BYTES : 2 * Integer.BYTES;
        if (frames > data.remaining() / minimumBytes) throw new IOException("Truncated Niagara data-interface cache");
        NiagaraCacheInterfaceData.Frames storage;
        switch (type) {
            case 1 -> {
                var cpu = new ArrayList<ArrayFrame>(frames);
                var gpu = new ArrayList<ArrayFrame>(frames);
                for (int i = 0; i < frames; i++) {
                    cpu.add(arrayFrame(data));
                    gpu.add(arrayFrame(data));
                }
                storage = new NiagaraCacheInterfaceData.Arrays(cpu, gpu);
            }
            case 2 -> {
                int[] cpu = new int[frames], gpu = new int[frames];
                for (int i = 0; i < frames; i++) {
                    cpu[i] = data.getInt();
                    gpu[i] = data.getInt();
                }
                storage = new NiagaraCacheInterfaceData.Counters(cpu, gpu);
            }
            case 3 -> {
                var textures = new ArrayList<Target2D>(frames);
                for (int i = 0; i < frames; i++) {
                    int format = data.getInt();
                    if (format == -1) textures.add(new Target2D(-1, 0, 0, new byte[0]));
                    else {
                        int width = data.getInt(), height = data.getInt();
                        byte[] pixels = new byte[count(data, Byte.BYTES)];
                        data.get(pixels);
                        textures.add(new Target2D(format, width, height, pixels));
                    }
                }
                storage = new NiagaraCacheInterfaceData.Targets2D(textures);
            }
            case 4 -> {
                var channel = new ArrayList<ChannelFrame>(frames);
                for (int i = 0; i < frames; i++) channel.add(channelFrame(data));
                storage = new NiagaraCacheInterfaceData.Channels(channel);
            }
            case 5 -> {
                var meshes = new ArrayList<DynamicMeshPair>(frames);
                for (int i = 0; i < frames; i++) meshes.add(new DynamicMeshPair(dynamicMesh(data), dynamicMesh(data)));
                storage = new NiagaraCacheInterfaceData.DynamicMeshes(meshes);
            }
            case 6 -> {
                var volumes = new ArrayList<NiagaraCacheVolumeFrame>(frames);
                for (int i = 0; i < frames; i++) volumes.add(volumeFrame(data));
                storage = new NiagaraCacheInterfaceData.VolumeTargets(volumes);
            }
            default -> throw new IOException("Unknown Niagara cache interface payload for " + key.name());
        }
        return new NiagaraCacheInterfaceData(key, storage);
    }

    private static ArrayFrame arrayFrame(ByteBuffer data) throws IOException {
        int words = data.getInt();
        return words == -1 ? new ArrayFrame(false, new int[0]) : new ArrayFrame(true, words(data, words));
    }

    private static ChannelFrame channelFrame(ByteBuffer data) throws IOException {
        int rows = data.getInt(), size = count(data, 12), flags = data.getInt();
        if (rows < 0 || (flags & ~15) != 0) throw new IOException("Invalid Niagara cache channel frame");
        var fields = new ArrayList<ChannelColumn>(size);
        for (int index = 0; index < size; index++) {
            var key = new NiagaraCacheInterfaceData.Key(text(data), text(data));
            int width = data.getInt();
            if (width < 1 || (long)rows * width > data.remaining() / Double.BYTES)
                throw new IOException("Invalid Niagara cache channel column " + key.name());
            var values = new double[rows * width];
            for (int value = 0; value < values.length; value++) values[value] = data.getDouble();
            fields.add(new ChannelColumn(key, width, values));
        }
        return new ChannelFrame(rows, (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, (flags & 8) != 0, fields);
    }

    private static @Nullable DynamicMesh dynamicMesh(ByteBuffer data) throws IOException {
        int present = data.getInt();
        if (present == 0) return null;
        if (present != 1) throw new IOException("Invalid Niagara dynamic mesh cache flag");
        int triangles = data.getInt(), vertices = data.getInt(), uvs = data.getInt();
        int tangent = data.getInt(), uv = data.getInt(), color = data.getInt(), vertexWords = data.getInt();
        var box = bounds(data);
        int count = count(data, 16);
        var sections = new ArrayList<NiagaraDynamicMeshFrame.Section>(count);
        var materials = new ArrayList<String>(count);
        var indicesByMaterial = new java.util.LinkedHashMap<String, Integer>();
        for (int index = 0; index < count; index++) {
            int first = data.getInt(), maximum = data.getInt(), allocated = data.getInt();
            String material = text(data);
            int slot = indicesByMaterial.computeIfAbsent(com.kltyton.lumenfx.effect.niagara.parameter.SourceNames.canonical(material),
                    ignored -> indicesByMaterial.size());
            sections.add(new NiagaraDynamicMeshFrame.Section(first, maximum, allocated, slot));
            materials.add(material);
        }
        int indices = count(data, Integer.BYTES), attributes = count(data, Integer.BYTES);
        return new DynamicMesh(new NiagaraDynamicMeshFrame(0, triangles, vertices, uvs, tangent, uv, color, vertexWords,
                box == null ? NiagaraBounds.DEFAULT : box, sections, words(data, indices), words(data, attributes)), materials);
    }

    private static NiagaraCacheVolumeFrame volumeFrame(ByteBuffer data) throws IOException {
        int format = data.getInt();
        if (format == -1) return NiagaraCacheVolumeFrame.ABSENT;
        int width = data.getInt(), height = data.getInt(), depth = data.getInt();
        int flags = data.getInt();
        if ((flags & ~1) != 0) throw new IOException("Invalid Niagara volume cache flags");
        float[] fallback = {data.getFloat(), data.getFloat(), data.getFloat(), data.getFloat()};
        int tiles = data.getInt(), size = count(data, 16);
        var pages = new ArrayList<NiagaraCacheVolumeFrame.Page>(size);
        for (int index = 0; index < size; index++)
            pages.add(new NiagaraCacheVolumeFrame.Page(data.getInt(), data.getInt(), data.getInt(), data.getInt()));
        byte[] pixels = new byte[count(data, Byte.BYTES)];
        data.get(pixels);
        return new NiagaraCacheVolumeFrame(format, width, height, depth, flags == 1, fallback, tiles, pages, pixels);
    }

    private static Layout layout(ByteBuffer data) throws IOException {
        String name = text(data);
        int flags = data.getInt(), floats = data.getInt(), integers = data.getInt(), halves = data.getInt(), velocity = data.getInt();
        if ((flags & ~3) != 0) throw new IOException("Invalid Niagara cache layout flags");
        int size = count(data, 32);
        var fields = new ArrayList<Field>(size);
        for (int i = 0; i < size; i++) {
            String field = text(data), type = text(data);
            var f = range(data); var n = range(data); var h = range(data);
            int flagsField = data.getInt();
            if ((flagsField & ~3) != 0) throw new IOException("Invalid Niagara cache variable flags");
            fields.add(new Field(field, type, f, n, h, (flagsField & 1) != 0, (flagsField & 2) != 0));
        }
        return new Layout(name, (flags & 1) != 0, (flags & 2) != 0, floats, integers, halves, velocity, fields);
    }

    private static Buffer buffer(ByteBuffer data, Layout layout, int version) throws IOException {
        int count = data.getInt(), tag = data.getInt(), ids = count(data, Integer.BYTES), next = count(data, Integer.BYTES);
        int captureIds = version >= 4 ? count(data, Integer.BYTES) : 0;
        if (count < 0 || next != 0 && next != count || captureIds != 0 && captureIds != count)
            throw new IOException("Invalid Niagara simulation cache buffer dimensions");
        int[] floats = words(data, Math.multiplyExact(count, layout.floatCount()));
        int[] integers = words(data, Math.multiplyExact(count, layout.integerCount()));
        int size = Math.multiplyExact(count, layout.halfCount());
        if (size > data.remaining() / Short.BYTES) throw new IOException("Truncated Niagara cache half data");
        var halves = new short[size];
        for (int i = 0; i < size; i++) halves[i] = data.getShort();
        return new Buffer(count, tag, floats, integers, halves, words(data, ids), words(data, next), words(data, captureIds));
    }

    private static Range range(ByteBuffer data) { return new Range(data.getInt(), data.getInt()); }
    private static int[] words(ByteBuffer data, int count) throws IOException {
        if (count < 0 || count > data.remaining() / Integer.BYTES) throw new IOException("Truncated Niagara cache data");
        var values = new int[count];
        for (int i = 0; i < count; i++) values[i] = data.getInt();
        return values;
    }
    private static @Nullable NiagaraBounds bounds(ByteBuffer data) throws IOException {
        int flags = data.getInt();
        if (flags == 0) return null;
        if (flags != 1) throw new IOException("Invalid Niagara simulation cache bounds flag");
        return new NiagaraBounds(data.getDouble(), data.getDouble(), data.getDouble(),
                data.getDouble(), data.getDouble(), data.getDouble());
    }
    private static String text(ByteBuffer data) throws IOException {
        var bytes = new byte[count(data, 1)];
        data.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
    private static Vec3 vector(ByteBuffer data) { return new Vec3(data.getDouble(), data.getDouble(), data.getDouble()); }
    private static int count(ByteBuffer data, int bytes) throws IOException {
        int count = data.getInt();
        if (count < 0 || count > data.remaining() / bytes) throw new IOException("Invalid Niagara cache array size");
        return count;
    }
}
