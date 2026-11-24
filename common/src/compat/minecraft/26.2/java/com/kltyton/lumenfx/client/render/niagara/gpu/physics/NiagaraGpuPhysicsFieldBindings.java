package com.kltyton.lumenfx.client.render.niagara.gpu.physics;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.IntegerTarget;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.ScalarTarget;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.VectorTarget;
import com.kltyton.lumenfx.api.client.physics.PhysicsFieldGpuData;
import com.kltyton.lumenfx.api.client.physics.PhysicsFieldGpuData.Buffer;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Supplies the original Niagara Chaos field resource layout to the compiled HLSL. */
public final class NiagaraGpuPhysicsFieldBindings {
    private static final byte[] TARGET_MAPPINGS = targetMappings();

    private final NiagaraGpuPrograms programs;
    private final Set<String> symbols;
    private @Nullable NiagaraPhysicsField field;

    public NiagaraGpuPhysicsFieldBindings(NiagaraGpuPrograms programs, JsonObject program) {
        this.programs = programs;
        Set<String> names = new HashSet<>();
        for (var item : program.getAsJsonArray("interfaces")) {
            var descriptor = item.getAsJsonObject();
            if (descriptor.has("physics_field") && descriptor.has("shader_symbol"))
                names.add(descriptor.get("shader_symbol").getAsString());
        }
        symbols = Set.copyOf(names);
    }

    public void snapshot(@Nullable NiagaraPhysicsField value) { field = value; }

    public @Nullable byte[] value(String qualified) {
        if (symbols.isEmpty()) return null;
        String name = qualified.substring(qualified.lastIndexOf('.') + 1);
        int split = name.lastIndexOf('_');
        if (split < 0 || !symbols.contains(name.substring(0, split))) return null;
        var world = field;
        var clipmap = world == null ? null : world.clipmap();
        var data = world == null ? null : world.gpu();
        return switch (name.substring(split + 1)) {
            case "TargetMappings" -> data == null ? new byte[TARGET_MAPPINGS.length] : TARGET_MAPPINGS;
            case "FieldTargets" -> targetAttributes(data);
            case "bClipmapAvailable" -> word(data != null && data.clipmapAvailable() ? 1 : 0);
            case "ClipmapCenter" -> vector(clipmap == null ? Vec3.ZERO : clipmap.center());
            case "ClipmapDistance" -> word(Float.floatToRawIntBits(clipmap == null ? 1 : clipmap.distance()));
            case "ClipmapResolution" -> word(clipmap == null ? 2 : clipmap.resolution());
            case "ClipmapExponent" -> word(data == null ? 1 : data.exponent());
            case "ClipmapCount" -> word(data == null ? 1 : data.levels());
            case "TargetCount" -> word(data == null ? 0 : data.attributes());
            case "TimeSeconds" -> word(Float.floatToRawIntBits(world == null ? 0 : world.worldSeconds()));
            default -> null;
        };
    }

    public void bind(CommandEncoder encoder, ShaderProgram shader, ComputeBindings.Builder resources) {
        if (symbols.isEmpty()) return;
        var world = field;
        var data = world == null ? null : world.gpu();
        for (var binding : shader.bindings()) if (binding instanceof ShaderBinding.StorageBuffer storage) {
            String name = storage.name();
            int split = name.lastIndexOf('_');
            if (split < 0 || !symbols.contains(name.substring(0, split))) continue;
            Buffer kind = switch (name.substring(split + 1)) {
                case "ClipmapBuffer" -> Buffer.CLIPMAP;
                case "NodesParams" -> Buffer.PARAMETERS;
                case "NodesOffsets" -> Buffer.NODES;
                case "TargetsOffsets" -> Buffer.TARGETS;
                default -> null;
            };
            if (kind == null) continue;
            if (world != null && data == null && !world.commands().isEmpty())
                throw new IllegalStateException("The active physics field has CPU commands but no Chaos GPU resource");
            GpuBuffer buffer = data == null ? null : kind == Buffer.CLIPMAP && data.generateClipmap()
                    ? programs.physicsFields().clipmap(encoder, programs, world, data)
                    : programs.physicsFields().buffer(data, kind, programs.runtime());
            resources.storage(name, (buffer == null ? programs.dummyBuffer() : buffer).slice(), storage.access());
        }
    }

    private static byte[] targetMappings() {
        var buffer = ByteBuffer.allocate(PhysicsFieldGpuData.TARGET_ROWS * 16).order(ByteOrder.LITTLE_ENDIAN);
        var vector = VectorTarget.values();
        var scalar = ScalarTarget.values();
        var integer = IntegerTarget.values();
        for (int row = 0; row < PhysicsFieldGpuData.TARGET_ROWS; row++)
            buffer.putInt(row < vector.length ? vector[row].physicsType() : 0)
                    .putInt(row < scalar.length ? scalar[row].physicsType() : 0)
                    .putInt(row < integer.length ? integer[row].physicsType() : 0).putInt(0);
        return buffer.array();
    }

    private static byte[] targetAttributes(@Nullable PhysicsFieldGpuData data) {
        var buffer = ByteBuffer.allocate(PhysicsFieldGpuData.TARGET_ROWS * 16).order(ByteOrder.LITTLE_ENDIAN);
        if (data != null) for (int word = 0; word < PhysicsFieldGpuData.TARGET_ROWS * 4; word++)
            buffer.putInt(data.targetAttribute(word));
        return buffer.array();
    }

    private static byte[] word(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    private static byte[] vector(Vec3 value) {
        return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .putFloat((float)value.x).putFloat((float)value.y).putFloat((float)value.z).array();
    }
}
