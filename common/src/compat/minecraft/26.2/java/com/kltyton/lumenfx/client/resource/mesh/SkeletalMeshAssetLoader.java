package com.kltyton.lumenfx.client.resource.mesh;

import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.AliasTable;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.Bone;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.Indices;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.LocalTransform;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.Lod;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.Region;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.Section;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.SkinWeightBuffer;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.SkinWeights;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.Socket;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset.Bounds;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry;
import com.google.gson.JsonParseException;
import it.unimi.dsi.fastutil.chars.CharArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Reads the native skeletal render exchange during effect resource preparation. */
public final class SkeletalMeshAssetLoader {
    private SkeletalMeshAssetLoader() { }

    public static SkeletalMeshAsset load(ResourceManager resources, Identifier id) {
        return load(resources, id, null);
    }

    public static SkeletalMeshAsset load(ResourceManager resources, Identifier id, @Nullable SkeletalMeshStreaming streaming) {
        Identifier path = Identifier.fromNamespaceAndPath(id.getNamespace(), "lumenfx/skinned_meshes/" + id.getPath() + ".lfsk");
        try (var input = resources.getResourceOrThrow(path).open()) {
            var data = ByteBuffer.wrap(input.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
            int magic = data.getInt(), version = data.getInt();
            if (magic != 0x4b53464c || version < 2 || version > 6) throw new JsonParseException("Invalid skeletal mesh header " + id);
            int lodCount = count(data, 28), boneCount = count(data, 88), socketCount = count(data, 48);
            int regionCount = count(data, 32), inlineLods = data.getInt(), rawBones = data.getInt();
            if (rawBones < 1 || rawBones > boneCount || inlineLods < 0 || inlineLods > lodCount)
                throw new JsonParseException("Invalid skeletal bone or inline LOD count " + id);
            int firstInline = lodCount - inlineLods;
            var bounds = new Bounds(new Vec3(data.getDouble(), data.getDouble(), data.getDouble()),
                    new Vec3(data.getDouble(), data.getDouble(), data.getDouble()), (float)data.getDouble());
            var bones = new ArrayList<Bone>(boneCount);
            for (int index = 0; index < boneCount; index++) {
                int parent = data.getInt();
                bones.add(new Bone(text(data), parent, pose(data), pose(data), index < rawBones ? floats(data, 16) : null));
            }
            var sockets = new ArrayList<Socket>(socketCount);
            for (int index = 0; index < socketCount; index++) sockets.add(new Socket(text(data), text(data), pose(data)));
            var lods = new ArrayList<Lod>(lodCount);
            var inline = new java.util.LinkedHashMap<Integer, Lod>();
            var payloads = new java.util.LinkedHashMap<Integer, SkeletalMeshStreaming.Payload>();
            for (int index = 0; index < lodCount; index++) {
                if (version < 5) {
                    var value = lod(data, version).withIndex(index);
                    lods.add(value);
                    inline.put(index, value);
                    continue;
                }
                int streamed = data.getInt();
                if (streamed == 0) {
                    if (index < firstInline) throw new JsonParseException("Expected streamed skeletal LOD " + index + " for " + id);
                    var value = lod(data, version).withIndex(index);
                    lods.add(value);
                    inline.put(index, value);
                } else if (streamed == 1) {
                    if (index >= firstInline) throw new JsonParseException("Expected inline skeletal LOD " + index + " for " + id);
                    int vertices = data.getInt(), flags = data.getInt();
                    if (vertices < 1 || (flags & ~(version >= 6 ? 31 : 15)) != 0 || (flags & 6) == 4)
                        throw new JsonParseException("Invalid streamed skeletal LOD metadata " + index + " for " + id);
                    Identifier lodPath = Identifier.fromNamespaceAndPath(id.getNamespace(),
                            "lumenfx/skinned_meshes/" + id.getPath() + ".lod" + index + ".lfsl");
                    Resource resource = resources.getResource(lodPath)
                            .orElseThrow(() -> new JsonParseException("Missing streamed skeletal LOD " + lodPath));
                    if (streaming == null) {
                        try (var lodInput = resource.open()) {
                            var lodData = ByteBuffer.wrap(lodInput.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
                            var value = lod(lodData, version).withIndex(index);
                            if (lodData.hasRemaining() || value.vertexCount() != vertices || value.cpuAccess() != ((flags & 1) != 0)
                                    || value.hasColors() != ((flags & 2) != 0) || value.cpuColors() != ((flags & 4) != 0)
                                    || value.unlimitedInfluences() != ((flags & 8) != 0)
                                    || version >= 6 && value.supportsUniformlyDistributedSampling() != ((flags & 16) != 0))
                                throw new JsonParseException("Streamed skeletal LOD metadata mismatch " + lodPath);
                            lods.add(value);
                            inline.put(index, value);
                        }
                    } else {
                        lods.add(Lod.unloaded(vertices, (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0,
                                version >= 6 && (flags & 16) != 0).withIndex(index));
                        payloads.put(index, new SkeletalMeshStreaming.Payload(lodPath, resource, vertices, flags, version));
                    }
                } else throw new JsonParseException("Unknown skeletal LOD storage kind " + streamed + " for " + id);
            }
            var regions = new ArrayList<Region>(regionCount);
            for (int index = 0; index < regionCount; index++) {
                String name = text(data);
                int lod = data.getInt(), flags = data.getInt();
                if ((flags & ~1) != 0) throw new JsonParseException("Invalid skeletal region flags " + name);
                regions.add(new Region(name, lod, (flags & 1) != 0, indices(data), indices(data), indices(data), sampler(data)));
            }
            if (data.hasRemaining()) throw new JsonParseException("Unexpected skeletal mesh payload tail " + id);
            var resident = streaming != null && !payloads.isEmpty()
                    ? streaming.register(id, lodCount, firstInline, inline, payloads) : null;
            return new SkeletalMeshAsset(bounds, bones, sockets, lods, regions, inlineLods, resident);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot load native skeletal mesh " + id, failure);
        } catch (BufferUnderflowException | ArithmeticException | IllegalArgumentException failure) {
            throw new JsonParseException("Invalid native skeletal mesh " + id, failure);
        }
    }

    static Lod lod(ByteBuffer data, int version) {
        int vertices = count(data, 14 * Float.BYTES), indexCount = count(data, Integer.BYTES), channels = data.getInt();
        int sectionCount = count(data, 24), flags = data.getInt(), indexBytes = data.getInt(), weightBytes = data.getInt();
        int validFlags = switch (version) { case 2 -> 1; case 3 -> 7; case 6 -> 31; default -> 15; };
        if (indexCount % 3 != 0 || channels < 0 || channels > 8 || (flags & ~validFlags) != 0
                || (flags & 6) == 4)
            throw new JsonParseException("Invalid native skeletal render channel counts");
        int[] vertexIds = new int[vertices];
        for (int index = 0; index < vertices; index++) vertexIds[index] = index;
        int vectors = Math.multiplyExact(vertices, 3);
        float[] positions = floats(data, vectors), normals = floats(data, vectors), tangents = floats(data, vectors);
        float[] signs = floats(data, vertices), colors = floats(data, Math.multiplyExact(vertices, 4));
        var uv = new ArrayList<float[]>(channels);
        for (int channel = 0; channel < channels; channel++) uv.add(floats(data, Math.multiplyExact(vertices, 2)));
        int[] indices = words(data, indexCount);
        var geometry = new StaticMeshGeometry(vertexIds, positions, normals, tangents, signs, colors, uv, indices,
                List.of(new StaticMeshGeometry.Section(0, "None", 0, indexCount)), StaticMeshGeometry.VertexIdKind.RENDER_VERTEX);
        var sections = new ArrayList<Section>(sectionCount);
        for (int index = 0; index < sectionCount; index++)
            sections.add(new Section(data.getInt(), data.getInt(), data.getInt(), data.getInt(), data.getInt(), indices(data)));
        var starts = new int[Math.addExact(vertices, 1)];
        var bones = new IntArrayList();
        var weights = new CharArrayList();
        for (int vertex = 0; vertex < vertices; vertex++) {
            starts[vertex] = bones.size();
            int influences = count(data, Integer.BYTES + Short.BYTES);
            for (int influence = 0; influence < influences; influence++) {
                bones.add(data.getInt());
                weights.add((char)Short.toUnsignedInt(data.getShort()));
            }
        }
        starts[vertices] = bones.size();
        var areaSampler = sampler(data);
        var skin = new SkinWeightBuffer(new SkinWeights(starts, bones.toIntArray(), weights.toCharArray()),
                indexBytes, weightBytes, version < 4 || (flags & 8) != 0);
        return new Lod(geometry, sections, skin,
                (flags & 1) != 0, version == 2 || (flags & 2) != 0, version == 2 || (flags & 4) != 0,
                areaSampler, version >= 6 ? (flags & 16) != 0 : areaSampler.size() > 0);
    }

    private static AliasTable sampler(ByteBuffer data) {
        int count = count(data, 8);
        float total = data.getFloat();
        var probability = new float[count];
        var alias = new int[count];
        for (int index = 0; index < count; index++) {
            probability[index] = data.getFloat();
            alias[index] = data.getInt();
        }
        return new AliasTable(probability, alias, total);
    }
    private static Indices indices(ByteBuffer data) { return new Indices(words(data, count(data, Integer.BYTES))); }
    private static int[] words(ByteBuffer data, int count) {
        if (count > data.remaining() / Integer.BYTES) throw new JsonParseException("Truncated native skeletal index data");
        var values = new int[count];
        for (int index = 0; index < count; index++) values[index] = data.getInt();
        return values;
    }
    private static float[] floats(ByteBuffer data, int count) {
        if (count > data.remaining() / Float.BYTES) throw new JsonParseException("Truncated native skeletal vertex data");
        var values = new float[count];
        for (int index = 0; index < count; index++) values[index] = data.getFloat();
        return values;
    }
    private static int count(ByteBuffer data, int minimumBytes) {
        int count = data.getInt();
        if (count < 0 || count > data.remaining() / minimumBytes) throw new JsonParseException("Invalid native skeletal mesh count");
        return count;
    }
    private static String text(ByteBuffer data) {
        byte[] bytes = new byte[count(data, 1)];
        data.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
    private static Vec3 vector(ByteBuffer data) { return new Vec3(data.getFloat(), data.getFloat(), data.getFloat()); }
    private static LocalTransform pose(ByteBuffer data) {
        return new LocalTransform(vector(data), data.getFloat(), data.getFloat(), data.getFloat(), data.getFloat(), vector(data));
    }
}
