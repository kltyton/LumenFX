package com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel;

import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelWorlds.Row;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelWorlds.Buffer;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelWorlds.Lease;
import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition.Channel;
import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition.Address;
import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition.Register;
import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition.Variable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

/** Immutable CPU publication and spawn data for an enqueued channel GPU reader. */
public final class NiagaraDataChannelGpuSnapshot implements AutoCloseable {
    private record ColumnOffset(int floats, int integers) { }
    private final @Nullable Address address;
    private final @Nullable ClientLevel world;
    private final boolean currentFrame;
    private final List<Row> rows;
    private final int[] floats, integers, spawnData;
    private final int elementCountAtSpawn, emitters;
    private final Map<Variable, ColumnOffset> offsets;
    private final @Nullable Lease lease;

    NiagaraDataChannelGpuSnapshot(@Nullable Address address, @Nullable ClientLevel world, boolean currentFrame, List<Row> rows,
                                  Map<Integer, NiagaraDataChannelSpawns> spawnTables, int emitters) {
        this(address, world, currentFrame, rows, spawnTables, emitters, null);
    }
    NiagaraDataChannelGpuSnapshot(@Nullable Address address, @Nullable ClientLevel world, boolean currentFrame, List<Row> rows,
                                  Map<Integer, NiagaraDataChannelSpawns> spawnTables, int emitters, @Nullable Buffer retained) {
        this.address = address;
        this.world = world;
        this.currentFrame = currentFrame;
        this.rows = rows;
        this.emitters = emitters;
        var floatColumns = new ArrayList<Integer>();
        var intColumns = new ArrayList<Integer>();
        var layout = new HashMap<Variable, ColumnOffset>();
        int word = 0;
        if (address != null) for (var variable : address.channel().variables()) {
            int firstFloat = -1, firstInt = -1;
            for (Register register : variable.registers()) {
                switch (register) {
                    case FLOAT -> {
                        if (firstFloat < 0) firstFloat = floatColumns.size();
                        floatColumns.add(word);
                    }
                    case INT, BOOL -> {
                        if (firstInt < 0) firstInt = intColumns.size();
                        intColumns.add(word);
                    }
                    case HALF -> { }
                }
                word++;
            }
            layout.put(variable, new ColumnOffset(firstFloat, firstInt));
        }
        floats = floatColumns.stream().mapToInt(Integer::intValue).toArray();
        integers = intColumns.stream().mapToInt(Integer::intValue).toArray();
        offsets = Map.copyOf(layout);
        int words = emitters;
        int elementCount = 0;
        var tables = new int[emitters][];
        for (int emitter = 0; emitter < emitters; emitter++) {
            var table = spawnTables.get(emitter);
            if (table == null) continue;
            if (elementCount != 0 && elementCount != table.elements())
                throw new IllegalStateException("Niagara channel emitters have different source spawn row counts");
            elementCount = table.elements();
            tables[emitter] = table.gpuWords();
            words = Math.addExact(words, tables[emitter].length);
        }
        elementCountAtSpawn = elementCount;
        spawnData = new int[Math.max(1, words)];
        int offset = emitters;
        for (int emitter = 0; emitter < emitters; emitter++) if (tables[emitter] != null) {
            spawnData[emitter] = offset;
            System.arraycopy(tables[emitter], 0, spawnData, offset, tables[emitter].length);
            offset += tables[emitter].length;
        }
        lease = retained == null ? null : retained.lease();
    }

    public @Nullable Address address() { return address; }
    public @Nullable Channel schema() { return address == null ? null : address.channel(); }
    public @Nullable ClientLevel world() { return world; }
    public boolean currentFrame() { return currentFrame; }
    public boolean bound() { return address != null; }
    public int rows() { return rows.size(); }
    public Object rowIdentity() { return rows; }
    public int elementCountAtSpawn() { return elementCountAtSpawn; }
    public int emitterCount() { return emitters; }
    public long columnBytes(boolean floating) {
        return columnBytes(floating, rows());
    }
    public long columnBytes(boolean floating, int stride) {
        return Math.max(1L, (long)stride * (floating ? floats.length : integers.length)) * Integer.BYTES;
    }
    public int columnCount(boolean floating) { return floating ? floats.length : integers.length; }
    public long spawnBytes() { return (long)spawnData.length * Integer.BYTES; }
    public void writeColumns(boolean floating, ByteBuffer output) {
        writeColumns(floating, rows(), output);
    }
    public void writeColumns(boolean floating, int stride, ByteBuffer output) {
        for (int column : floating ? floats : integers) {
            for (Row row : rows) output.putInt(row.words()[column]);
            for (int i = rows.size(); i < stride; i++) output.putInt(0);
        }
        if (output.position() == 0) output.putInt(0);
    }
    public void writeSpawnData(ByteBuffer output) { for (int word : spawnData) output.putInt(word); }
    public int[] parameterOffsets(List<Variable> requested) {
        var values = new int[Math.max(1, Math.multiplyExact(requested.size(), 2))];
        for (int index = 0; index < requested.size(); index++) {
            var offset = offsets.get(requested.get(index));
            values[index * 2] = offset == null ? -1 : offset.floats();
            values[index * 2 + 1] = offset == null ? -1 : offset.integers();
        }
        return values;
    }

    public static NiagaraDataChannelGpuSnapshot empty(Channel schema) {
        return new NiagaraDataChannelGpuSnapshot(new Address(schema, 0), null, true, List.of(), Map.of(), 0);
    }
    @Override public void close() { if (lease != null) lease.close(); }
}
