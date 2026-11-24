package com.kltyton.lumenfx.client.render.niagara.gpu.spline;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.spline.NiagaraSpline.GpuFrame;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.spline.NiagaraSplineLut.Channel;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.joml.Matrix4dc;
import org.jspecify.annotations.Nullable;

public final class NiagaraGpuSplineBindings {
    private record Source(String compileName, String id) { }
    private record Active(String id, GpuFrame frame) { }
    private final Map<String, Source> symbols;
    private final Map<String, Active> active = new HashMap<>();
    private @Nullable NiagaraGpuSplineBuffers buffers;

    public NiagaraGpuSplineBindings(JsonObject program) {
        var definitions = new HashMap<String, Source>();
        for (var item : program.getAsJsonArray("interfaces")) {
            var binding = item.getAsJsonObject();
            if (binding.has("spline") && binding.has("shader_symbol"))
                definitions.put(binding.get("shader_symbol").getAsString(),
                        new Source(SourceNames.canonical(binding.get("compile_name").getAsString()), binding.get("interface_id").getAsString()));
        }
        symbols = Map.copyOf(definitions);
    }

    public void beginTick(@Nullable NiagaraGpuSplineBuffers buffers, Map<String, String> selections, Map<String, GpuFrame> snapshots) {
        if (symbols.isEmpty()) return;
        this.buffers = Objects.requireNonNull(buffers);
        symbols.forEach((symbol, source) -> {
            String id = selections.getOrDefault(source.compileName(), source.id());
            active.put(symbol, new Active(id, Objects.requireNonNull(snapshots.get(id), "Missing Niagara spline frame: " + id)));
        });
    }

    public void bind(ShaderProgram shader, ComputeBindings.Builder resources) {
        if (active.isEmpty()) return;
        var registry = Objects.requireNonNull(buffers);
        for (var binding : shader.bindings()) if (binding instanceof ShaderBinding.StorageBuffer storage) {
            String name = storage.name();
            int index = name.lastIndexOf('_');
            if (index < 0) continue;
            var state = active.get(name.substring(0, index));
            if (state == null) continue;
            var channel = switch (name.substring(index + 1)) {
                case "SplinePositionsLUT" -> Channel.POSITIONS;
                case "SplineScalesLUT" -> Channel.SCALES;
                case "SplineRotationsLUT" -> Channel.ROTATIONS;
                default -> null;
            };
            if (channel != null) resources.storage(name, registry.buffer(state.id(), state.frame().lut(), channel), storage.access());
        }
    }

    public @Nullable byte[] value(String qualified) {
        String name = qualified.substring(qualified.lastIndexOf('.') + 1);
        int index = name.lastIndexOf('_');
        if (index < 0) return null;
        var state = active.get(name.substring(0, index));
        if (state == null) return null;
        var transform = state.frame().transform();
        var lut = state.frame().lut();
        return switch (name.substring(index + 1)) {
            case "SplineTransform" -> matrix(transform.world());
            case "SplineTransformRotationMat" -> matrix(transform.rotationMatrix());
            case "SplineTransformInverse" -> matrix(transform.inverse());
            case "SplineTransformInverseTranspose" -> matrix(transform.inverseTranspose());
            case "SplineTransformRotation" -> floats(transform.rotation().x(), transform.rotation().y(), transform.rotation().z(), transform.rotation().w());
            case "DefaultUpVector" -> floats(transform.up().x, transform.up().y, transform.up().z);
            case "SplineLength" -> floats(lut.length());
            case "SplineDistanceStep" -> floats(lut.step());
            case "InvSplineDistanceStep" -> floats(lut.inverseStep());
            case "MaxIndex" -> ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(lut.maxIndex()).array();
            default -> null;
        };
    }

    private static byte[] matrix(Matrix4dc value) {
        var bytes = ByteBuffer.allocate(16 * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int column = 0; column < 4; column++)
            for (int row = 0; row < 4; row++) bytes.putFloat((float)value.get(column, row));
        return bytes.array();
    }

    private static byte[] floats(double... values) {
        var bytes = ByteBuffer.allocate(values.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (double value : values) bytes.putFloat((float)value);
        return bytes.array();
    }

    public void clearSnapshots() { active.clear(); buffers = null; }
}
