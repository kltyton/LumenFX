package com.kltyton.lumenfx.client.simulation.niagara.cache;

import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimCacheReaderDefinition;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Buffer;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Field;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Layout;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.parameter.UserParameterNames;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Reads the original cache dataset with the emitter and attribute selected by the authored interface. */
public final class NiagaraSimCacheReader {
    public enum Operation {
        GetNumFrames("", 0, 0), GetNumEmitters("", 0, 0), GetEmitterIndex("", 0, 0), GetNumInstances("", 0, 0),
        GetInstanceInt("/script/niagara.niagaraint32", 1, 1), GetInstanceFloat("/script/niagara.niagarafloat", 0, 1),
        GetInstanceVector2("/script/coreuobject.vector2f", 0, 2), GetInstanceVector3("/script/coreuobject.vector3f", 0, 3),
        GetInstanceVector4("/script/coreuobject.vector4f", 0, 4), GetInstanceColor("/script/coreuobject.linearcolor", 0, 4),
        GetInstanceQuat("/script/coreuobject.quat4f", 0, 4), GetInstancePosition("/script/niagara.niagaraposition", 0, 3),
        GetInstanceID("/script/niagara.niagaraid", 1, 2), GetInstanceIndexFromID("/script/niagara.niagaraid", 1, 2);

        private final String type;
        private final int register, width;
        Operation(String type, int register, int width) { this.type = type; this.register = register; this.width = width; }
        public String type() { return type; }
        public int register() { return register; }
        public int width() { return width; }
    }

    public record Bound(@Nullable NiagaraSimulationCache cache, int emitter) {
        public static final Bound EMPTY = new Bound(null, -1);
        public @Nullable Layout layout() {
            if (cache == null) return null;
            return emitter < 0 ? cache.systemLayout() : cache.emitterLayouts().get(emitter);
        }
        public @Nullable Buffer frame(int index) {
            if (cache == null || index < 0 || index >= cache.frames().size()) return null;
            var frame = cache.frames().get(index);
            return emitter < 0 ? frame.system() : frame.emitters().get(emitter).particles();
        }
        public @Nullable Field attribute(Operation operation, String name) {
            var layout = layout();
            if (layout == null) return null;
            String attribute = SourceNames.canonical(name);
            for (var field : layout.fields())
                if (field.name().equals(attribute) && field.sourceType().equals(operation.type())) return field;
            return null;
        }
        public int column(Operation operation, String name) {
            var field = attribute(operation, name);
            if (field == null) return -1;
            var range = operation.register() == 1 ? field.integers() : field.floats();
            if (range.count() != operation.width())
                throw new IllegalArgumentException("Niagara simulation cache reader field width changed: " + name);
            return range.start();
        }
    }

    public record Call(Bound source, Operation operation, int column) {
        public void read(int[] input, int[] output) {
            switch (operation) {
                case GetNumFrames -> output[0] = source.cache() == null ? 0 : source.cache().frames().size();
                case GetNumEmitters -> output[0] = source.cache() == null ? 0 : source.cache().emitterLayouts().size();
                case GetEmitterIndex -> output[0] = source.emitter();
                case GetNumInstances -> {
                    var frame = source.frame(input[0]);
                    var layout = source.layout();
                    output[0] = frame == null || layout == null || layout.fields().isEmpty() ? 0 : frame.count();
                }
                case GetInstanceIndexFromID -> {
                    var frame = source.frame(input[0]);
                    int row = -1;
                    if (frame != null && column >= 0) {
                        for (int i = 0; i < frame.count(); i++) {
                            if (frame.word(1, column, i) == input[1] && frame.word(1, column + 1, i) == input[2]) {
                                row = i;
                                break;
                            }
                        }
                    }
                    output[0] = row >= 0 ? -1 : 0;
                    output[1] = row;
                }
                default -> {
                    var frame = source.frame(input[0]);
                    int row = input[1];
                    if (frame != null && column >= 0 && row >= 0 && row < frame.count()) {
                        output[0] = -1;
                        for (int i = 0; i < operation.width(); i++) output[i + 1] = frame.word(operation.register(), column + i, row);
                    } else {
                        Arrays.fill(output, 0);
                        if (operation == Operation.GetInstanceColor) Arrays.fill(output, 1, output.length, Float.floatToRawIntBits(1));
                        if (operation == Operation.GetInstanceQuat) output[4] = Float.floatToRawIntBits(1);
                        if (operation == Operation.GetInstanceID) Arrays.fill(output, 1, output.length, -1);
                    }
                }
            }
        }
    }

    private final NiagaraSimCacheReaderDefinition definition;
    private Bound bound = Bound.EMPTY;

    public NiagaraSimCacheReader(NiagaraSimCacheReaderDefinition definition) { this.definition = definition; }
    public NiagaraSimCacheReaderDefinition definition() { return definition; }
    public Bound bound() { return bound; }

    public void capture(Map<String, NiagaraSimulationCache> overrides, Map<Identifier, NiagaraSimulationCache> assets,
                        @Nullable NiagaraParameterStore parameters) {
        String name = definition.parameter();
        NiagaraSimulationCache selected = overrides.get(UserParameterNames.display(name));
        if (selected == null && parameters != null && !name.isEmpty() && !name.equals("none")) {
            var object = parameters.objectBinding(name).value();
            if (object instanceof NiagaraSimulationCache cache) selected = cache;
        }
        if (selected == null && definition.asset() != null)
            selected = Objects.requireNonNull(assets.get(definition.asset()), "Missing Niagara SimCache Reader asset " + definition.asset());
        if (selected == bound.cache()) return;
        int emitter = -1;
        if (selected != null && !definition.emitter().isEmpty() && !definition.emitter().equals("none")) {
            for (int index = 0; index < selected.emitterLayouts().size(); index++) {
                if (!definition.emitter().equals(selected.emitterLayouts().get(index).name())) continue;
                emitter = index;
                break;
            }
        }
        bound = new Bound(selected, emitter);
    }

    public Call bind(String name, String attribute) {
        var operation = Operation.valueOf(name);
        return new Call(bound, operation, bound.column(operation, attribute));
    }
}
