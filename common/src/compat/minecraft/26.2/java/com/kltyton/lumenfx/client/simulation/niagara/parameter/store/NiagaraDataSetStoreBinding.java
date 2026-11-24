package com.kltyton.lumenfx.client.simulation.niagara.parameter.store;

import com.kltyton.lumenfx.client.simulation.niagara.VectorDataSet;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import java.util.ArrayList;
import java.util.List;

/** Reflected component byte offsets preserve struct padding and the source's half-to-float store conversion. */
public final class NiagaraDataSetStoreBinding {
    private record Component(int type, int component, int offset) { }
    private final NiagaraParameterStore store;
    private final List<Component> components;

    public NiagaraDataSetStoreBinding(NiagaraParameterStore store, List<NiagaraDefinition.Variable> layout) {
        this.store = store;
        var components = new ArrayList<Component>();
        for (var variable : layout) {
            var offsets = variable.offsets();
            if (offsets == null) continue;
            int offset = store.dataSetNumericOffset(variable.name());
            if (offset < 0) continue;
            add(components, offsets.floats(), 0, variable.floatStart(), offset, 1);
            add(components, offsets.integers(), 1, variable.intStart(), offset, 1);
            add(components, offsets.halves(), 2, variable.halfStart(), offset, 2);
        }
        this.components = List.copyOf(components);
    }

    private static void add(List<Component> result, List<Integer> offsets, int type, int start, int base, int scale) {
        for (int index = 0; index < offsets.size(); index++)
            result.add(new Component(type, Math.addExact(start, index), Math.addExact(base, Math.multiplyExact(offsets.get(index), scale))));
    }

    public void toStore(VectorDataSet data, int row) {
        for (var component : components) {
            int bits = data.component(component.type(), component.component())[row];
            if (component.type() == 2) bits = Float.floatToRawIntBits(Float.float16ToFloat((short)bits));
            store.numericWord(component.offset(), bits);
        }
        store.markNumericDirty();
    }

    public void toDataSet(VectorDataSet data, int row) {
        for (var component : components) {
            int bits = store.numericWord(component.offset());
            if (component.type() == 2) {
                float value = Float.intBitsToFloat(bits);
                bits = Float.isNaN(value) ? (bits >>> 16 & 0x8000) | 0x7e00 : Short.toUnsignedInt(Float.floatToFloat16(value));
            }
            data.component(component.type(), component.component())[row] = bits;
        }
    }
}
