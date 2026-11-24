package com.kltyton.lumenfx.client.simulation.niagara.stateless;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.client.resource.niagara.NiagaraCollectionJson;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraCollectionSnapshot;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraEngineParameters;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraPositionParameters;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.VectorProgram;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.parameter.NiagaraCollectionBinding;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Each emitter resolves spawn, update, instance and user stores in their native binding order. */
public final class StatelessParameterBuffer {
    private record Binding(String name, String type, int offset, int width, int spawnOffset, int updateOffset,
                           int @Nullable [] instanceDefault, double @Nullable [] positionDefault,
                           @Nullable NiagaraCollectionBinding collection) { }
    private record Mapping(int offset, Expression expression) { }
    private record Expression(String operation, int width, int offset, int @Nullable [] values,
                              @Nullable Expression a, @Nullable Expression b) {
        float evaluate(int component, int[] parameters) {
            return switch (operation) {
                case "constant" -> Float.intBitsToFloat(values[component]);
                case "binding" -> Float.intBitsToFloat(parameters[offset + component]);
                case "add" -> a.evaluate(component, parameters) + b.evaluate(component, parameters);
                case "subtract" -> a.evaluate(component, parameters) - b.evaluate(component, parameters);
                case "multiply" -> a.evaluate(component, parameters) * b.evaluate(component, parameters);
                case "divide" -> {
                    float left = a.evaluate(component, parameters), right = b.evaluate(component, parameters);
                    yield Math.abs(right) > 1e-8f ? left / right : 0f;
                }
                default -> throw new IllegalStateException("Unknown stateless expression " + operation);
            };
        }
    }
    private final List<Binding> bindings;
    private final List<Mapping> expressions;
    private final int[] current, destination;
    private final String emitter;
    private final float[] positionScratch = new float[3];

    public StatelessParameterBuffer(JsonObject definition, NiagaraDefinition system, String emitter) {
        this.emitter = emitter;
        int size = definition.get("words").getAsInt();
        if (size < 0) throw new IllegalArgumentException("Negative stateless parameter store size");
        current = new int[size]; destination = new int[size];
        bindings = definition.getAsJsonArray("bindings").asList().stream().map(item -> {
            JsonObject value = item.getAsJsonObject();
            String name = value.get("name").getAsString(), type = value.get("source_type").getAsString();
            int offset = value.get("offset").getAsInt(), width = value.get("components").getAsInt();
            span(offset, width, size);
            int[] defaults = defaultWords(value, "instance_default", width, name);
            double[] position = defaultPosition(value, "position_default", type, name);
            NiagaraCollectionBinding collection = NiagaraCollectionJson.read(value);
            if (collection != null && collection.bytes() != width * Integer.BYTES)
                throw new IllegalArgumentException("Invalid stateless collection width " + name);
            return new Binding(name, type, offset, width, scriptOffset(system.systemSpawn(), name, type, width),
                    scriptOffset(system.systemUpdate(), name, type, width), defaults, position, collection);
        }).toList();
        expressions = definition.getAsJsonArray("expressions").asList().stream().map(item -> {
            JsonObject value = item.getAsJsonObject();
            Expression expression = expression(value.getAsJsonObject("expression"), size);
            int offset = value.get("offset").getAsInt();
            span(offset, expression.width(), size);
            return new Mapping(offset, expression);
        }).toList();
    }

    public boolean update(byte[] spawn, byte[] update, byte[] runtime, EffectHandle.Input input, double originX, double originY, double originZ) {
        return update(spawn, update, runtime, input, originX, originY, originZ, NiagaraCollectionSnapshot.EMPTY);
    }

    public boolean update(byte[] spawn, byte[] update, byte[] runtime, EffectHandle.Input input, double originX, double originY,
                          double originZ, NiagaraCollectionSnapshot collections) {
        Arrays.fill(destination, 0);
        ByteBuffer spawnBytes = ByteBuffer.wrap(spawn).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer updateBytes = ByteBuffer.wrap(update).order(ByteOrder.LITTLE_ENDIAN);
        for (Binding binding : bindings) {
            if (binding.spawnOffset() >= 0) copy(spawnBytes, binding.spawnOffset(), destination, binding.offset(), binding.width());
            if (binding.updateOffset() >= 0) copy(updateBytes, binding.updateOffset(), destination, binding.offset(), binding.width());
            if (binding.instanceDefault() != null) System.arraycopy(binding.instanceDefault(), 0, destination, binding.offset(), binding.width());
            if (binding.positionDefault() != null) {
                position(binding.positionDefault(), binding.offset(), destination, originX, originY, originZ);
            }
            NiagaraEngineParameters.readWords(runtime, binding.name(), emitter, binding.type(), destination, binding.offset(), binding.width());
            EffectHandle.ParameterValue override = null;
            for (Map.Entry<String, EffectHandle.ParameterValue> parameter : input.parameters().entrySet())
                if (SourceNames.equal(binding.name(), parameter.getKey())) { override = parameter.getValue(); break; }
            if (override != null) apply(binding, override, originX, originY, originZ);
            NiagaraCollectionBinding collection = binding.collection();
            if (collection != null) {
                boolean component = collections.component().contains(collection.path());
                collection.copyDefault(destination, binding.offset(), component, originX, originY, originZ);
                EffectHandle.ParameterValue replacement = component ? collections.component().get(collection.path(), binding.name(), binding.type())
                        : collection.systemOverride() ? null : collections.world().get(collection.path(), binding.name(), binding.type());
                if (replacement != null) apply(binding, replacement, originX, originY, originZ);
            }
        }
        for (Mapping mapping : expressions)
            for (int component = 0; component < mapping.expression().width(); component++)
                destination[mapping.offset() + component] = Float.floatToRawIntBits(mapping.expression().evaluate(component, destination));
        boolean changed = !Arrays.equals(current, destination);
        System.arraycopy(destination, 0, current, 0, current.length);
        return changed;
    }

    public int[] words() { return current; }
    private static int scriptOffset(VectorProgram program, String name, String type, int width) {
        return program.parameters().stream().filter(parameter -> SourceNames.equal(parameter.name(), name)
                && parameter.sourceType().equals(type) && parameter.bytes() == width * Integer.BYTES)
            .mapToInt(parameter -> program.runtimeBytes() + parameter.offset()).findFirst().orElse(-1);
    }
    private static void copy(ByteBuffer source, int sourceOffset, int[] destination, int destinationOffset, int width) {
        for (int index = 0; index < width; index++) destination[destinationOffset + index] = source.getInt(sourceOffset + index * Integer.BYTES);
    }
    private static void position(double[] source, int offset, int[] destination, double originX, double originY, double originZ) {
        destination[offset] = Float.floatToRawIntBits((float)(source[0] - originX));
        destination[offset + 1] = Float.floatToRawIntBits((float)(source[1] - originY));
        destination[offset + 2] = Float.floatToRawIntBits((float)(source[2] - originZ));
    }
    private void apply(Binding binding, EffectHandle.ParameterValue value, double originX, double originY, double originZ) {
        if (binding.type().equals(NiagaraDefinition.POSITION_TYPE)) {
            if (value.type() != EffectHandle.ParameterType.POSITION) throw new IllegalArgumentException("Incompatible stateless position binding " + binding.name());
            NiagaraPositionParameters.read(value, originX, originY, originZ, positionScratch);
            for (int component = 0; component < 3; component++) destination[binding.offset() + component] = Float.floatToRawIntBits(positionScratch[component]);
            return;
        }
        int storage = switch (binding.type()) {
            case "/Script/Niagara.NiagaraInt32" -> 1;
            case "/Script/Niagara.NiagaraBool" -> 2;
            default -> 0;
        };
        if (value.type().storage() != storage || value.type().components() != binding.width())
            throw new IllegalArgumentException("Incompatible stateless binding " + binding.name());
        long[] values = value.words();
        for (int component = 0; component < binding.width(); component++)
            destination[binding.offset() + component] = storage == 2 ? values[component] == 0 ? 0 : -1 : (int)values[component];
    }

    private static int @Nullable [] defaultWords(JsonObject value, String field, int width, String name) {
        if (!value.has(field)) return null;
        int[] result = words(value.getAsJsonArray(field));
        if (result.length != width) throw new IllegalArgumentException("Invalid stateless " + field + " for " + name);
        return result;
    }
    private static double @Nullable [] defaultPosition(JsonObject value, String field, String type, String name) {
        if (!value.has(field)) return null;
        double[] result = value.getAsJsonArray(field).asList().stream().mapToDouble(JsonElement::getAsDouble).toArray();
        if (result.length != 3 || !type.equals(NiagaraDefinition.POSITION_TYPE))
            throw new IllegalArgumentException("Invalid stateless " + field + " for " + name);
        return result;
    }
    private static Expression expression(JsonObject value, int size) {
        String operation = value.get("op").getAsString();
        int width = value.get("width").getAsInt();
        if (width < 1 || width > 4) throw new IllegalArgumentException("Invalid stateless expression width");
        if (operation.equals("constant")) {
            int[] values = words(value.getAsJsonArray("values"));
            if (values.length != width) throw new IllegalArgumentException("Invalid stateless expression constant");
            return new Expression(operation, width, -1, values, null, null);
        }
        if (operation.equals("binding")) {
            int offset = value.get("offset").getAsInt();
            span(offset, width, size);
            return new Expression(operation, width, offset, null, null, null);
        }
        if (!List.of("add", "subtract", "multiply", "divide").contains(operation))
            throw new IllegalArgumentException("Unknown stateless expression operation " + operation);
        Expression a = expression(value.getAsJsonObject("a"), size), b = expression(value.getAsJsonObject("b"), size);
        if (a.width() != width || b.width() != width) throw new IllegalArgumentException("Stateless expression operand type mismatch");
        return new Expression(operation, width, -1, null, a, b);
    }
    private static void span(int offset, int width, int size) {
        if (width < 1 || offset < 0 || offset > size - width) throw new IllegalArgumentException("Stateless parameter lies outside its store");
    }
    private static int[] words(JsonArray values) { return values.asList().stream().mapToInt(value -> (int)value.getAsLong()).toArray(); }
}
