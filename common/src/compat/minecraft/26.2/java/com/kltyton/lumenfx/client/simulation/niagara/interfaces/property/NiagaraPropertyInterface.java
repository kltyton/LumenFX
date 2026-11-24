package com.kltyton.lumenfx.client.simulation.niagara.interfaces.property;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPropertyInterfaceInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPropertyInterfaceInputs.Property;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Per-system owner property data, rebuilt before simulation and retained by queued GPU steps. */
public final class NiagaraPropertyInterface {
    public static final String TYPE = "NiagaraDataInterfacePropertyInterface";
    public record Definition(String path, String sourcePackage) {
        public static Definition read(JsonObject source) {
            return new Definition(source.get("path").getAsString(), source.get("package").getAsString());
        }
    }

    public record Output(String name, String type, int width) {
        public Output {
            if (width < 1) throw new IllegalArgumentException("Niagara Property Interface output has no words: " + name);
        }

        public static List<Output> read(JsonArray source) {
            var fields = new ArrayList<Output>(source.size());
            for (var value : source) {
                var field = value.getAsJsonObject();
                fields.add(new Output(field.get("name").getAsString(), field.get("type").getAsString(), field.get("width").getAsInt()));
            }
            return List.copyOf(fields);
        }
    }

    private record TypedName(String name, String type) {
        TypedName {
            name = SourceNames.canonical(name);
            type = SourceNames.canonical(type);
        }
    }

    public static final class Frame {
        private static final int PADDING_WORDS = 4;
        private static final Frame EMPTY = new Frame(List.of());
        private final int[] words;
        private final Map<TypedName, Integer> typedOffsets = new HashMap<>();
        private final Map<String, Integer> nameOffsets = new HashMap<>();

        private Frame(List<Property> properties) {
            int size = PADDING_WORDS;
            for (var property : properties) size = Math.addExact(size, property.words().size());
            words = new int[size];
            int offset = 0;
            for (var property : properties) {
                typedOffsets.putIfAbsent(new TypedName(property.name(), property.type()), offset);
                nameOffsets.putIfAbsent(SourceNames.canonical(property.name()), offset);
                for (int value : property.words()) words[offset++] = value;
            }
        }

        public int[] bind(List<Output> outputs) {
            int[] offsets = new int[outputs.stream().mapToInt(Output::width).sum()];
            int index = 0;
            for (var output : outputs) {
                int offset = typedOffsets.getOrDefault(new TypedName(output.name(), output.type()), paddingOffset());
                for (int word = 0; word < output.width(); word++) offsets[index++] = offset + word;
            }
            return offsets;
        }

        public int readWord(int offset) {
            // The VM retains the initial read table even when the current owner has fewer fields.
            return offset < words.length ? words[offset] : 0;
        }

        public int byteOffset(String name) {
            return Math.multiplyExact(nameOffsets.getOrDefault(SourceNames.canonical(name), paddingOffset()), Integer.BYTES);
        }

        public int byteSize() { return Math.multiplyExact(words.length, Integer.BYTES); }
        public void write(IntBuffer target) { target.put(words); }
        private int paddingOffset() { return words.length - PADDING_WORDS; }
    }

    private final Definition definition;
    private List<Property> previous = List.of();
    private Frame frame = Frame.EMPTY;
    private @Nullable Frame initialFrame;

    public NiagaraPropertyInterface(Definition definition) { this.definition = definition; }

    public void capture(NiagaraPropertyInterfaceInputs owner) {
        var properties = owner.properties(definition.path());
        if (properties != previous) {
            frame = properties.isEmpty() ? Frame.EMPTY : new Frame(properties);
            previous = properties;
        }
        if (initialFrame == null) initialFrame = frame;
    }

    public Frame frame() { return frame; }
    public @Nullable Frame initialFrame() { return initialFrame; }
}
