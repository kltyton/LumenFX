package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.simulation.niagara.gpu.NiagaraGpuSpawnInfo;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Source Niagara dispatch constants, before reflected uniform-buffer packing. */
public final class NiagaraGpuDispatchParameters {
    private final Map<String, byte[]> values;

    public NiagaraGpuDispatchParameters(NiagaraGpuSpawnInfo spawn, int eventSpawns, int actualSpawns, int tick, boolean reset,
                                         int readStride, int writeStride, int readCountOffset, int writeCountOffset,
                                         int dispatchX, int dispatchY, int dispatchZ, int iterationCountOffset,
                                         int iterationInstanceCount, int iterations, int iterationIndex,
                                         int loops, int loopIndex, int stateComponent, int stateMinimum, int stateMaximum) {
        if (eventSpawns < 0 || actualSpawns < 0 || (long)actualSpawns > (long)spawn.count() + eventSpawns
                || readStride < 0 || writeStride < 0
                || dispatchX < 0 || dispatchY < 0 || dispatchZ < 0
                || iterations < 0 || iterations > 0xffff || iterationIndex < 0 || iterationIndex > 0xffff
                || loops < 0 || loops > 0xffff || loopIndex < 0 || loopIndex > 0xffff)
            throw new IllegalArgumentException("Invalid Niagara GPU dispatch parameters");
        Map<String, byte[]> fields = new LinkedHashMap<>();
        fields.put("SimStart", words(reset ? 1 : 0));
        fields.put("EmitterTickCounter", words(tick));
        fields.put("NumSpawnedInstances", words(actualSpawns));
        fields.put("EmitterSpawnInfoOffsets", words(spawn.offsets()));
        fields.put("EmitterSpawnInfoParams", words(spawn.parameterWords()));
        fields.put("ComponentBufferSizeRead", words(readStride));
        fields.put("ComponentBufferSizeWrite", words(writeStride));
        fields.put("ReadInstanceCountOffset", words(readCountOffset));
        fields.put("WriteInstanceCountOffset", words(writeCountOffset));
        fields.put("DispatchThreadIdBounds", words(dispatchX, dispatchY, dispatchZ));
        fields.put("DispatchThreadIdToLinear", words(1, dispatchX, Math.multiplyExact(dispatchX, dispatchY)));
        fields.put("IndirectDispatchArgsOffset", words(1));
        fields.put("SimulationStageIterationInfo", words(iterationCountOffset, iterationInstanceCount,
                iterations << 16 | iterationIndex, loops << 16 | loopIndex));
        fields.put("ParticleIterationStateInfo", words(stateComponent, stateMinimum, stateMaximum));
        values = Map.copyOf(fields);
    }

    public @Nullable byte[] value(String name) {
        byte[] value = values.get(name);
        return value == null ? null : value.clone();
    }

    private static byte[] words(int... values) {
        ByteBuffer result = ByteBuffer.allocate(Math.multiplyExact(values.length, Integer.BYTES)).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) result.putInt(value);
        return result.array();
    }
}
