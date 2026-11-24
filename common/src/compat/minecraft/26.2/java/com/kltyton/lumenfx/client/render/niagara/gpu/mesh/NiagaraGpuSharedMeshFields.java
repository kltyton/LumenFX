package com.kltyton.lumenfx.client.render.niagara.gpu.mesh;

import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRigidMeshInputs.DistanceField;
import com.kltyton.lumenfx.client.render.compute.ComputeUsage;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.NiagaraStaticMeshWorlds;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.NiagaraStaticMeshWorlds.Field;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.mesh.NiagaraStaticMeshWorlds.Snapshot;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraPositionParameters;
import com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshDistanceField;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshDistanceField.Mip;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/** Shared scene-object slots and sparse mip atlas used by Niagara mesh distance queries. */
public final class NiagaraGpuSharedMeshFields implements AutoCloseable {
    private static final int OBJECT_BYTES = 10 * 4 * Integer.BYTES;
    private static final int MIP_BYTES = 4 * 4 * Integer.BYTES;
    private final NiagaraGpuPrograms programs;
    private @Nullable ClientLevel world;
    private Snapshot current = Snapshot.EMPTY;
    private List<StaticMeshDistanceField> fields = List.of();
    private @Nullable NiagaraGpuMeshDistanceField atlas;
    private @Nullable GpuBuffer objects, mips;

    public NiagaraGpuSharedMeshFields(NiagaraGpuPrograms programs) { this.programs = programs; }

    public Snapshot scene() { return current; }

    public int sceneIndex(DistanceField reference) {
        for (Field field : current.fields())
            if (field.index() == reference.index() && field.acquireTag() == reference.acquireTag()) return field.index();
        return -1;
    }

    public void capture(ClientLevel level) {
        if (world != level) {
            close();
            world = level;
        }
        Snapshot next = NiagaraStaticMeshWorlds.capture(level);
        if (current.equals(next)) return;
        var offsets = new IdentityHashMap<StaticMeshDistanceField, Integer>();
        List<StaticMeshDistanceField> assets = new ArrayList<>();
        List<Mip> levels = new ArrayList<>();
        for (Field slot : next.fields()) if (!offsets.containsKey(slot.field())) {
            offsets.put(slot.field(), levels.size());
            assets.add(slot.field());
            levels.addAll(slot.field().mips());
        }
        boolean rebuildAtlas = fields.size() != assets.size();
        for (int i = 0; !rebuildAtlas && i < fields.size(); i++) rebuildAtlas = fields.get(i) != assets.get(i);

        NiagaraGpuMeshDistanceField nextAtlas = null;
        GpuBuffer nextObjects = null, nextMips = null;
        try {
            if (rebuildAtlas && !levels.isEmpty()) {
                int size = bufferSize(levels.size(), MIP_BYTES);
                nextAtlas = new NiagaraGpuMeshDistanceField("Niagara.SharedMesh", levels);
                var data = MemoryUtil.memAlloc(size).order(ByteOrder.LITTLE_ENDIAN);
                try {
                    nextAtlas.writeSharedMips(data);
                    nextMips = upload("Niagara.SharedMesh.Mips", data.flip());
                } finally { MemoryUtil.memFree(data); }
            }
            if (!next.fields().isEmpty()) {
                var data = MemoryUtil.memCalloc(bufferSize(next.slots(), OBJECT_BYTES)).order(ByteOrder.LITTLE_ENDIAN);
                try {
                    for (Field field : next.fields()) writeObject(data, field, offsets.get(field.field()));
                    nextObjects = upload("Niagara.SharedMesh.Objects", data.clear());
                } finally { MemoryUtil.memFree(data); }
            }
        } catch (RuntimeException | Error failure) {
            if (nextObjects != null) nextObjects.close();
            if (nextMips != null) nextMips.close();
            if (nextAtlas != null) nextAtlas.close();
            throw failure;
        }
        if (objects != null) objects.close();
        objects = nextObjects;
        if (rebuildAtlas) {
            if (mips != null) mips.close();
            if (atlas != null) atlas.close();
            mips = nextMips;
            atlas = nextAtlas;
            fields = List.copyOf(assets);
        }
        current = next;
    }

    private int bufferSize(int entries, int stride) {
        long size = Math.multiplyExact((long)entries, stride);
        if (size > programs.runtime().maxStorageBufferBytes())
            throw new IllegalArgumentException("Shared Niagara static mesh data exceeds the device storage limit");
        return Math.toIntExact(size);
    }

    private static GpuBuffer upload(String name, ByteBuffer data) {
        return RenderSystem.getDevice().createBuffer(() -> name, ComputeUsage.storageBuffer(GpuBuffer.USAGE_COPY_DST), data);
    }

    private static void writeObject(ByteBuffer data, Field source, int mipOffset) {
        var transform = source.transform();
        var matrix = NiagaraComponentMatrices.srt(transform.qx(), transform.qy(), transform.qz(), transform.qw(),
                transform.scaleX(), transform.scaleY(), transform.scaleZ(), transform.x(), transform.y(), transform.z(), new Matrix4d());
        var field = source.field();
        var half = field.maximum().subtract(field.minimum()).scale(.5);
        double radius = Math.max(half.x, Math.max(half.y, half.z));
        var center = field.minimum().add(field.maximum()).scale(.5);
        var origin = matrix.transformPosition(new Vector3d(center.x, center.y, center.z));
        matrix.setTranslation(0, 0, 0).scale(radius);
        double determinant = matrix.determinant();
        if (!Double.isFinite(determinant) || determinant == 0) return;
        var inverse = new Matrix4d(matrix).invert();
        double x = NiagaraPositionParameters.tile(origin.x);
        double y = NiagaraPositionParameters.tile(origin.y);
        double z = NiagaraPositionParameters.tile(origin.z);
        double tileSize = NiagaraPositionParameters.TILE_SIZE;
        data.position(source.index() * OBJECT_BYTES);
        data.putInt(source.acquireTag()).putInt(1).putInt(mipOffset).putInt(0);
        vector(data, origin.x - x * tileSize, origin.y - y * tileSize, origin.z - z * tileSize, 0);
        vector(data, x, y, z, 0);
        vector(data, inverse.m00(), inverse.m10(), inverse.m20(), half.x / radius);
        vector(data, inverse.m01(), inverse.m11(), inverse.m21(), half.y / radius);
        vector(data, inverse.m02(), inverse.m12(), inverse.m22(), half.z / radius);
        double sx = Math.abs(transform.scaleX()) * radius;
        double sy = Math.abs(transform.scaleY()) * radius;
        double sz = Math.abs(transform.scaleZ()) * radius;
        vector(data, sx, sy, sz, Math.min(sx, Math.min(sy, sz)));
        vector(data, matrix.m00(), matrix.m10(), matrix.m20(), 0);
        vector(data, matrix.m01(), matrix.m11(), matrix.m21(), 0);
        vector(data, matrix.m02(), matrix.m12(), matrix.m22(), 0);
    }

    private static void vector(ByteBuffer data, double x, double y, double z, double w) {
        data.putFloat((float)x).putFloat((float)y).putFloat((float)z).putFloat((float)w);
    }

    public @Nullable byte[] value(String name) {
        return name.substring(name.lastIndexOf('.') + 1).equals("LfxSharedMeshSlotCount")
                ? NiagaraGpuMeshDistanceField.packed(1).putInt(objects == null ? 0 : current.slots()).array() : null;
    }

    public void bind(ShaderProgram shader, ComputeBindings.Builder resources) {
        for (var binding : shader.bindings()) {
            if (binding instanceof ShaderBinding.StorageBuffer buffer) {
                GpuBuffer data = switch (buffer.name()) {
                    case "LfxSharedMeshObjects" -> objects == null ? programs.dummyBuffer() : objects;
                    case "LfxSharedMeshMips" -> mips == null ? programs.dummyBuffer() : mips;
                    default -> null;
                };
                if (data != null) resources.storage(buffer.name(), data.slice(), buffer.access());
            } else if (binding instanceof ShaderBinding.SampledTexture texture && texture.volume()) {
                String name = texture.name();
                if (name.equals("LfxSharedMeshTable") || name.startsWith("LfxSharedMeshTable_"))
                    resources.volume(name, atlas == null ? programs.blackIntegerVolumeTexture() : atlas.table(), 0);
                else if (name.equals("LfxSharedMeshAtlas") || name.startsWith("LfxSharedMeshAtlas_"))
                    resources.volume(name, atlas == null ? programs.blackVolumeTexture() : atlas.atlas(), 0);
            }
        }
    }

    public void clear(ClientLevel level) { if (world == level) close(); }

    @Override public void close() {
        if (objects != null) { objects.close(); objects = null; }
        if (mips != null) { mips.close(); mips = null; }
        if (atlas != null) { atlas.close(); atlas = null; }
        current = Snapshot.EMPTY;
        fields = List.of();
        world = null;
    }
}
