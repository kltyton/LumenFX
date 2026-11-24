package com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh;

import com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.NiagaraSkeletalMeshReader.Frame;
import com.kltyton.lumenfx.effect.niagara.mesh.NiagaraSkeletalGpuLayout;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.AliasTable;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset.LocalTransform;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/** The source buffers and uniform values of one queued skeletal Niagara simulation step. */
public final class NiagaraSkeletalGpuSnapshot {
    public enum Buffer {
        INDEX("MeshIndexBuffer", 1, false), POSITION("MeshVertexBuffer", 1, false),
        TANGENT("MeshTangentBuffer", 4, false), UV("MeshTexCoordBuffer", 2, false), COLOR("MeshColorBuffer", 4, false),
        WEIGHT("MeshSkinWeightBuffer", 1, true), LOOKUP("MeshSkinWeightLookupBuffer", 1, true),
        SECTION_OFFSET("MeshTriangleMatricesOffsetBuffer", 1, false),
        CURRENT_SKIN("MeshCurrBonesBuffer", 4, true), PREVIOUS_SKIN("MeshPrevBonesBuffer", 4, true),
        CURRENT_BONES("MeshCurrSamplingBonesBuffer", 4, true), PREVIOUS_BONES("MeshPrevSamplingBonesBuffer", 4, true),
        TRIANGLE_ALIAS("MeshTriangleSamplerProbAliasBuffer", 1, false),
        REGION_ALIAS("MeshSamplingRegionsProbAliasBuffer", 1, false),
        REGION_TRIANGLE("MeshSampleRegionsTriangleIndices", 1, false), REGION_VERTEX("MeshSampleRegionsVertices", 1, false),
        BONE_FILTER("FilteredAndUnfilteredBones", 1, false), UV_MAPPING("UvMappingBuffer", 1, false),
        CONNECTIVITY("ConnectivityBuffer", 1, false),
        CURRENT_DEFORMED("DeformedCurrPositionBuffer", 1, true),
        PREVIOUS_DEFORMED("DeformedPrevPositionBuffer", 1, true), DEFORMED_TANGENT("DeformedTangentBuffer", 4, true);

        private final String member;
        private final int words;
        private final boolean dynamic;
        Buffer(String member, int words, boolean dynamic) { this.member = member; this.words = words; this.dynamic = dynamic; }
        public String member() { return member; }
        public boolean dynamic() { return dynamic; }
    }

    private record SamplingBones(@Nullable NiagaraSkeletalPose pose, List<LocalTransform> sockets) {
        static SamplingBones retain(@Nullable SamplingBones previous, @Nullable NiagaraSkeletalPose pose,
                                    List<LocalTransform> sockets) {
            if (previous != null && previous.pose() == pose && previous.sockets() == sockets) return previous;
            return new SamplingBones(pose, sockets);
        }
    }

    private final Frame frame;
    private final SamplingBones currentBones, previousBones;
    private final @Nullable StaticMeshGeometry geometry;
    private final int vertices, indices, uvCount, bones, sectionBones, maxInfluences, weightBytes, indexBytes;
    private final int totalInfluences, regionTriangles, regionVertices;
    private final boolean lodAvailable, unlimited, weightedWhole, weightedRegions;

    NiagaraSkeletalGpuSnapshot(Frame frame, @Nullable NiagaraSkeletalGpuSnapshot previous) {
        this.frame = frame;
        currentBones = SamplingBones.retain(previous == null ? null : previous.currentBones, frame.current(), frame.sockets());
        previousBones = SamplingBones.retain(previous == null ? null : previous.previousBones, frame.previous(), frame.previousSockets());
        lodAvailable = frame.lod() != null;
        if (previous != null && previous.frame.sampling() == frame.sampling()
                && previous.frame.skinWeights() == frame.skinWeights()) {
            geometry = previous.geometry;
            vertices = previous.vertices;
            indices = previous.indices;
            uvCount = previous.uvCount;
            bones = previous.bones;
            sectionBones = previous.sectionBones;
            maxInfluences = previous.maxInfluences;
            weightBytes = previous.weightBytes;
            indexBytes = previous.indexBytes;
            totalInfluences = previous.totalInfluences;
            unlimited = previous.unlimited;
            regionTriangles = previous.regionTriangles;
            regionVertices = previous.regionVertices;
            weightedWhole = previous.weightedWhole;
            weightedRegions = previous.weightedRegions;
            return;
        }
        var lod = frame.lod();
        var buffer = frame.skinWeights();
        geometry = lod == null ? null : lod.geometry();
        vertices = geometry == null ? 0 : geometry.vertexCount();
        indices = geometry == null ? 0 : geometry.indexCount();
        uvCount = geometry == null ? 0 : geometry.textureCoordinateCount();
        bones = !lodAvailable || frame.mesh() == null ? 0 : frame.mesh().bones().size();
        indexBytes = buffer == null ? 1 : buffer.indexBytes();
        weightBytes = buffer == null ? 1 : buffer.weightBytes();
        unlimited = buffer != null && buffer.unlimited();
        int maximum = 0, sections = 0, regionTris = 0, regionVerts = 0;
        boolean regionsWeighted = !frame.regions().isEmpty();
        if (lod != null) {
            for (var section : lod.sections()) sections = Math.addExact(sections, section.boneMap().size());
        }
        if (buffer != null) {
            for (int vertex = 0; vertex < vertices; vertex++)
                maximum = Math.max(maximum, buffer.weights().count(vertex));
        }
        for (var region : frame.regions()) {
            regionTris = Math.addExact(regionTris, region.triangleOffsets().size());
            regionVerts = Math.addExact(regionVerts, region.vertices().size());
            regionsWeighted &= region.areaWeighted() && region.areaSampler().size() == region.triangleOffsets().size();
        }
        totalInfluences = buffer == null ? 0 : buffer.weights().influenceCount();
        if (unlimited && (maximum > 255 || (long)totalInfluences * (indexBytes + weightBytes) > 0x1000000L))
            throw new IllegalArgumentException("Skeletal skin weight stream exceeds Niagara's 24-bit offset and 8-bit count");
        sectionBones = sections;
        maxInfluences = maximum;
        regionTriangles = regionTris;
        regionVertices = regionVerts;
        weightedRegions = regionsWeighted;
        weightedWhole = lod != null && lod.supportsUniformlyDistributedSampling()
                && indices != 0 && lod.areaSampler().size() == indices / 3
                && frame.regions().stream().allMatch(region -> region.areaWeighted());
    }

    public @Nullable Object bufferIdentity(Buffer buffer) {
        return switch (buffer) {
            case WEIGHT, LOOKUP -> frame.skinWeights();
            case CURRENT_SKIN -> frame.current();
            case PREVIOUS_SKIN -> frame.previous();
            case CURRENT_BONES -> currentBones;
            case PREVIOUS_BONES -> previousBones;
            case CURRENT_DEFORMED, PREVIOUS_DEFORMED, DEFORMED_TANGENT -> frame.deformation();
            default -> frame.sampling();
        };
    }

    public long size(Buffer buffer, NiagaraSkeletalGpuLayout layout) {
        var deformation = frame.deformation();
        long elements = switch (buffer) {
            case INDEX -> indices;
            case POSITION -> vertices * 3L;
            case TANGENT -> vertices * 2L;
            case UV -> (long)vertices * uvCount;
            case COLOR -> vertices;
            case WEIGHT -> (long)totalInfluences * (indexBytes + weightBytes) / (unlimited ? 1 : Integer.BYTES);
            case LOOKUP -> unlimited ? vertices : 0;
            case SECTION_OFFSET -> vertices;
            case CURRENT_SKIN, PREVIOUS_SKIN -> (sectionBones + 1L) * 3;
            case CURRENT_BONES, PREVIOUS_BONES -> (bones + (lodAvailable ? frame.sockets().size() : 0L)) * 3;
            case TRIANGLE_ALIAS -> weightedWhole ? indices / 3 * (long)layout.aliasWords() : 0;
            case REGION_ALIAS -> weightedRegions ? (long)regionTriangles * layout.aliasWords() : 0;
            case REGION_TRIANGLE -> regionTriangles;
            case REGION_VERTEX -> regionVertices;
            case BONE_FILTER -> lodAvailable ? frame.filter().selected().size() + (long)frame.filter().remainder().size() : 0;
            case UV_MAPPING -> uvBytes() / Integer.BYTES;
            case CONNECTIVITY -> connectivityWords(layout);
            case CURRENT_DEFORMED, PREVIOUS_DEFORMED -> layout.allowDeformed() && deformation != null && deformation.hasPositions() ? vertices * 3L : 0;
            case DEFORMED_TANGENT -> layout.allowDeformed() && deformation != null && deformation.hasTangents() ? vertices * 2L : 0;
        };
        return Math.multiplyExact(Math.max(1L, elements), (long)buffer.words * Integer.BYTES);
    }

    public void write(Buffer buffer, ByteBuffer output, NiagaraSkeletalGpuLayout layout) {
        var lod = frame.lod();
        if (geometry != null && lod != null) switch (buffer) {
            case INDEX -> geometry.writeIndices(output);
            case POSITION -> {
                for (int vertex = 0; vertex < vertices; vertex++)
                    for (int axis = 0; axis < 3; axis++) output.putFloat(geometry.position(vertex, axis));
            }
            case TANGENT -> {
                for (int vertex = 0; vertex < vertices; vertex++) {
                    for (int axis = 0; axis < 3; axis++) output.putFloat(geometry.tangent(vertex, axis));
                    output.putFloat(1f);
                    for (int axis = 0; axis < 3; axis++) output.putFloat(geometry.normal(vertex, axis));
                    output.putFloat(geometry.binormalSign(vertex));
                }
            }
            case UV -> {
                for (int vertex = 0; vertex < vertices; vertex++)
                    for (int channel = 0; channel < uvCount; channel++)
                        for (int axis = 0; axis < 2; axis++) output.putFloat(geometry.textureCoordinate(channel, vertex, axis));
            }
            case COLOR -> {
                for (int vertex = 0; vertex < vertices; vertex++)
                    for (int channel = 0; channel < 4; channel++) output.putFloat(geometry.color(vertex, channel));
            }
            case WEIGHT -> {
                var skin = frame.skinWeights();
                if (skin != null) {
                    var weights = skin.weights();
                    for (int vertex = 0; vertex < vertices; vertex++) {
                        for (int influence = 0; influence < weights.count(vertex); influence++)
                            writeInfluence(output, weights.weight(vertex, influence) == 0 ? 0
                                    : weights.localBone(vertex, influence), indexBytes);
                        for (int influence = 0; influence < weights.count(vertex); influence++) {
                            int weight = weights.weight(vertex, influence);
                            writeInfluence(output, weightBytes == 1 ? weight >>> 8 : weight, weightBytes);
                        }
                    }
                }
            }
            case LOOKUP -> {
                var skin = frame.skinWeights();
                if (skin != null && unlimited) {
                    int offset = 0;
                    for (int vertex = 0; vertex < vertices; vertex++) {
                        int count = skin.weights().count(vertex);
                        output.putInt(offset << 8 | count);
                        offset += count * (indexBytes + weightBytes);
                    }
                }
            }
            case SECTION_OFFSET -> {
                var offsets = new int[vertices];
                int offset = 0;
                for (var section : lod.sections()) {
                    for (int vertex = section.firstVertex(); vertex < section.firstVertex() + section.vertices(); vertex++)
                        offsets[vertex] = offset;
                    offset += section.boneMap().size();
                }
                for (int value : offsets) output.putInt(value);
            }
            case TRIANGLE_ALIAS -> {
                if (weightedWhole) writeAliases(output, lod.areaSampler(), 0, layout.probabilityBits());
            }
            case REGION_ALIAS -> {
                int offset = 0;
                if (weightedRegions) for (var region : frame.regions()) {
                    var sampler = region.areaSampler();
                    writeAliases(output, sampler, offset, layout.probabilityBits());
                    offset += sampler.size();
                }
            }
            case REGION_TRIANGLE, REGION_VERTEX -> {
                boolean triangles = buffer == Buffer.REGION_TRIANGLE;
                for (var region : frame.regions()) {
                    var elements = triangles ? region.triangleOffsets() : region.vertices();
                    for (int i = 0; i < elements.size(); i++) output.putInt(triangles ? elements.get(i) / 3 : elements.get(i));
                }
            }
            case UV_MAPPING -> {
                var tree = frame.sampling().uvTree();
                if (tree != null) tree.write(output);
            }
            case CONNECTIVITY -> {
                if (connectivityWords(layout) != 0) {
                    var data = frame.sampling().connectivity();
                    if (data != null) data.write(output, layout.halfAdjacency());
                }
            }
            default -> { }
        }
        switch (buffer) {
            case CURRENT_SKIN, PREVIOUS_SKIN -> writeSkin(output, buffer == Buffer.CURRENT_SKIN ? frame.current() : frame.previous());
            case CURRENT_BONES, PREVIOUS_BONES -> {
                var bones = buffer == Buffer.CURRENT_BONES ? currentBones : previousBones;
                writeBones(output, bones.pose(), bones.sockets());
            }
            case BONE_FILTER -> {
                if (lodAvailable) {
                    for (int index : frame.filter().selected()) output.putInt(index);
                    for (int index : frame.filter().remainder()) output.putInt(index);
                }
            }
            case CURRENT_DEFORMED, PREVIOUS_DEFORMED -> {
                var deformation = frame.deformation();
                if (deformation != null && deformation.hasPositions())
                    for (int i = 0; i < vertices * 3; i++) output.putFloat(deformation.position(i, buffer == Buffer.PREVIOUS_DEFORMED));
            }
            case DEFORMED_TANGENT -> {
                var deformation = frame.deformation();
                if (deformation != null && deformation.hasTangents())
                    for (int i = 0; i < vertices * 8; i++) output.putFloat(deformation.tangent(i));
            }
            default -> { }
        }
        while (output.remaining() >= Integer.BYTES) output.putInt(buffer == Buffer.COLOR ? Float.floatToRawIntBits(1f) : 0);
    }

    public @Nullable byte[] value(String field, NiagaraSkeletalGpuLayout layout) {
        var mesh = frame.mesh();
        var lod = frame.lod();
        var deformation = frame.deformation();
        return switch (field) {
            case "MeshNumSamplingRegionTriangles" -> integer(regionTriangles);
            case "MeshNumSamplingRegionVertices" -> integer(regionVertices);
            case "MeshTriangleCount" -> integer(indices / 3);
            case "MeshVertexCount" -> integer(vertices);
            case "MeshNumTexCoord" -> integer(uvCount);
            case "MeshNumWeights" -> integer(maxInfluences);
            case "MeshBoneWeightStride" -> integer(unlimited ? 0 : maxInfluences * (indexBytes + weightBytes) / Integer.BYTES);
            case "MeshBoneIndexSizeBytes" -> integer(lodAvailable ? indexBytes : 0);
            case "MeshBoneWeightSizeBytes" -> integer(lodAvailable ? weightBytes : 0);
            case "NumBones", "FilteredSocketBoneOffset" -> integer(bones);
            case "NumFilteredBones" -> integer(lodAvailable ? frame.filter().selected().size() : 0);
            case "NumUnfilteredBones" -> integer(lodAvailable ? frame.filter().remainder().size() : 0);
            case "RandomMaxBone" -> integer(lodAvailable ? bones - (frame.filter().excluded() >= 0 ? 2 : 1) : 0);
            case "ExcludeBoneIndex" -> integer(lodAvailable ? frame.filter().excluded() : 0);
            case "NumFilteredSockets" -> integer(lodAvailable ? frame.sockets().size() : 0);
            case "UvMappingBufferLength" -> integer(uvBytes());
            case "UvMappingSet" -> integer(uvChannel());
            case "ConnectivityBufferLength" -> integer(connectivityWords(layout));
            case "ConnectivityMaxAdjacentPerVertex" -> integer(connectivityWords(layout) == 0 ? 0 : NiagaraSkeletalConnectivity.MAX_ADJACENT);
            case "InstanceTransform" -> matrix(frame.world());
            case "InstancePrevTransform" -> matrix(frame.previousWorld());
            case "InstanceRotation" -> rotation(frame.rotation());
            case "InstancePrevRotation" -> rotation(frame.previousRotation());
            case "InstanceInvDeltaTime" -> floats(frame.inverseDelta());
            case "PreSkinnedLocalBoundsCenter" -> vector(!lodAvailable || mesh == null ? Vec3.ZERO : mesh.bounds().origin());
            case "PreSkinnedLocalBoundsExtents" -> vector(!lodAvailable || mesh == null ? Vec3.ZERO : mesh.bounds().extent().scale(2));
            case "EnabledFeatures" -> integer((weightedWhole ? 1 : 0) | (weightedRegions ? 2 : 0)
                    | (unlimited ? 4 : 0) | (lod != null && lod.hasColors() ? 8 : 0)
                    | (layout.allowDeformed() && deformation != null && deformation.hasPositions() ? 16 : 0)
                    | (layout.allowDeformed() && deformation != null && deformation.hasTangents() ? 32 : 0));
            default -> null;
        };
    }

    private int connectivityWords(NiagaraSkeletalGpuLayout layout) {
        if (!frame.sampling().accessible() || layout.halfAdjacency() && indices / 3 >= 0xfffe) return 0;
        return Math.multiplyExact(vertices, NiagaraSkeletalConnectivity.MAX_ADJACENT / (layout.halfAdjacency() ? 2 : 1));
    }

    private static void writeAliases(ByteBuffer output, AliasTable table, int offset, int bits) {
        for (int index = 0; index < table.size(); index++) {
            float probability = table.probability(index);
            int alias = offset + table.alias(index);
            if (bits == 0) {
                output.putFloat(probability).putInt(alias);
            } else {
                int mask = (1 << bits) - 1;
                output.putInt(alias >>> (Integer.SIZE - bits) == 0 ? (alias << bits) | (int)(probability * mask) : 0);
            }
        }
    }

    private int uvBytes() {
        var tree = frame.sampling().uvTree();
        return tree == null ? 0 : tree.byteSize();
    }
    private int uvChannel() {
        var tree = frame.sampling().uvTree();
        return tree == null ? 0 : tree.channel();
    }
    private void writeInfluence(ByteBuffer output, int value, int bytes) {
        // The native variable stream is an R8_UINT SRV expanded to one uint per byte in the storage buffer.
        if (unlimited) {
            output.putInt(value & 255);
            if (bytes == 2) output.putInt(value >>> 8 & 255);
        } else if (bytes == 1) {
            output.put((byte)value);
        } else {
            output.putShort((short)value);
        }
    }
    private void writeSkin(ByteBuffer output, @Nullable NiagaraSkeletalPose pose) {
        var lod = frame.lod();
        if (lod != null && pose != null) for (var section : lod.sections())
            for (int i = 0; i < section.boneMap().size(); i++) skinMatrix(output, pose.gpuSkinMatrix(section.boneMap().get(i)));
        skinMatrix(output, new Matrix4f());
    }
    private static void skinMatrix(ByteBuffer output, Matrix4fc m) {
        output.putFloat(m.m00()).putFloat(m.m10()).putFloat(m.m20()).putFloat(m.m30());
        output.putFloat(m.m01()).putFloat(m.m11()).putFloat(m.m21()).putFloat(m.m31());
        output.putFloat(m.m02()).putFloat(m.m12()).putFloat(m.m22()).putFloat(m.m32());
    }
    private void writeBones(ByteBuffer output, @Nullable NiagaraSkeletalPose pose, List<LocalTransform> sockets) {
        var mesh = frame.mesh();
        if (pose != null && mesh != null) for (int bone = 0; bone < bones; bone++)
            samplingTransform(output, pose.bone(bone), mesh.bones().get(bone).parent());
        if (lodAvailable) for (var socket : sockets) samplingTransform(output, socket, -1);
    }
    private static void samplingTransform(ByteBuffer output, LocalTransform transform, int parent) {
        var p = transform.location();
        var s = transform.scale();
        output.putFloat((float)p.x).putFloat((float)p.y).putFloat((float)p.z).putFloat(0);
        output.putFloat(transform.qx()).putFloat(transform.qy()).putFloat(transform.qz()).putFloat(transform.qw());
        output.putFloat((float)s.x).putFloat((float)s.y).putFloat((float)s.z).putInt(parent);
    }
    private static byte[] integer(int value) { return bytes(1).putInt(value).array(); }
    private static byte[] vector(Vec3 value) { return floats((float)value.x, (float)value.y, (float)value.z); }
    private static byte[] rotation(Quaternionf value) { return floats(value.x, value.y, value.z, value.w); }
    private static byte[] matrix(Matrix4f value) { return floats(new Matrix4f(value).transpose().get(new float[16])); }
    private static byte[] floats(float... values) {
        var buffer = bytes(values.length);
        for (float value : values) buffer.putFloat(value);
        return buffer.array();
    }
    private static ByteBuffer bytes(int words) { return ByteBuffer.allocate(words * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN); }
}
