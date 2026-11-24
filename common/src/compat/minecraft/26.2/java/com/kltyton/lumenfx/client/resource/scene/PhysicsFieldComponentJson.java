package com.kltyton.lumenfx.client.resource.scene;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.kltyton.lumenfx.api.client.physics.AuthoredPhysicsFieldComponent;
import com.kltyton.lumenfx.api.client.physics.AuthoredPhysicsFieldComponent.SavedCommand;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.Command;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.IntegerCommand;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.IntegerNode;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.IntegerTarget;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.ScalarCommand;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.ScalarNode;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.ScalarTarget;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.VectorCommand;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.VectorNode;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.VectorTarget;
import com.kltyton.lumenfx.api.client.physics.PhysicsFieldNodes;
import com.kltyton.lumenfx.api.client.physics.PhysicsFieldNodes.Falloff;
import com.kltyton.lumenfx.api.client.physics.PhysicsFieldNodes.MaskCondition;
import com.kltyton.lumenfx.api.client.physics.PhysicsFieldNodes.WaveFunction;
import com.kltyton.lumenfx.api.client.physics.PhysicsFieldOperators;
import com.kltyton.lumenfx.api.client.physics.PhysicsFieldOperators.Culling;
import com.kltyton.lumenfx.api.client.physics.PhysicsFieldOperators.Operation;
import com.kltyton.lumenfx.api.client.physics.PhysicsFieldTransform;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class PhysicsFieldComponentJson {
    private enum Results { REFERENCE }

    private PhysicsFieldComponentJson() { }

    public static Map<String, AuthoredPhysicsFieldComponent> read(@Nullable JsonObject root) {
        if (root == null) return Map.of();
        var components = new LinkedHashMap<String, AuthoredPhysicsFieldComponent>();
        for (var entry : root.entrySet()) {
            var source = entry.getValue().getAsJsonObject();
            var commands = new ArrayList<SavedCommand>();
            for (var item : source.getAsJsonArray("commands")) {
                var saved = item.getAsJsonObject();
                var field = saved.getAsJsonObject("node");
                var metadata = saved.get("metadata");
                commands.add(new SavedCommand(command(saved.get("target").getAsInt(), node(field)),
                        metadata == null || metadata.isJsonNull() ? null : metadata.getAsString()));
            }
            var component = new AuthoredPhysicsFieldComponent(source.get("chaos").getAsBoolean(), source.get("world").getAsBoolean(), commands);
            if (components.putIfAbsent(SourceNames.canonical(entry.getKey()), component) != null)
                throw new JsonParseException("Duplicate Chaos FieldSystemComponent " + entry.getKey());
        }
        return Map.copyOf(components);
    }

    private static Command command(int target, Object root) {
        if (root instanceof IntegerNode node) {
            for (var key : IntegerTarget.values()) if (key.physicsType() == target) return new IntegerCommand(key, 0, node);
        } else if (root instanceof ScalarNode node) {
            for (var key : ScalarTarget.values()) if (key.physicsType() == target) return new ScalarCommand(key, 0, node);
        } else if (root instanceof VectorNode node) {
            for (var key : VectorTarget.values()) if (key.physicsType() == target) return new VectorCommand(key, 0, node);
        }
        throw new JsonParseException("Chaos field node result type does not match target " + target);
    }

    private static Object node(JsonObject value) {
        JsonObject data = value.getAsJsonObject("properties");
        String type = value.get("type").getAsString();
        float magnitude = number(data, "Magnitude", 1), minimum = number(data, "MinRange", 0);
        float maximum = number(data, "MaxRange", 1), outside = number(data, "Default", 0);
        return switch (type) {
            case "UniformInteger" -> new PhysicsFieldNodes.UniformInteger(integer(data, "Magnitude", 0));
            case "RadialIntMask" -> new PhysicsFieldNodes.RadialIntegerMask(number(data, "Radius", 0), position(data),
                    integer(data, "InteriorValue", 1), integer(data, "ExteriorValue", 0), mask(data));
            case "UniformScalar" -> new PhysicsFieldNodes.UniformScalar(magnitude);
            case "RadialFalloff" -> new PhysicsFieldNodes.RadialFalloff(magnitude, minimum, maximum, outside,
                    number(data, "Radius", 0), position(data), falloff(data));
            case "PlaneFalloff" -> new PhysicsFieldNodes.PlaneFalloff(magnitude, minimum, maximum, outside,
                    number(data, "Distance", 0), position(data), vector(data, "Normal", 0, 0, 1), falloff(data));
            case "BoxFalloff" -> new PhysicsFieldNodes.BoxFalloff(magnitude, minimum, maximum, outside, transform(data), falloff(data));
            case "NoiseField" -> new PhysicsFieldNodes.NoiseScalar(minimum, maximum, transform(data));
            case "WaveScalar" -> new PhysicsFieldNodes.WaveScalar(magnitude, position(data), number(data, "Wavelength", 10000),
                    number(data, "Period", 1), enumValue(data, "Function", "FIELD_WAVE_", WaveFunction.COSINE), falloff(data));
            case "UniformVector" -> new PhysicsFieldNodes.UniformVector(magnitude, vector(data, "Direction", 0, 0, 0));
            case "RadialVector" -> new PhysicsFieldNodes.RadialVector(magnitude, position(data));
            case "RandomVector" -> new PhysicsFieldNodes.RandomVector(magnitude);
            case "RotatedRadialVector" -> new PhysicsFieldNodes.RotatedRadialVector(magnitude, position(data),
                    number(data, "Angle", 0), vector(data, "Axis", 0, 0, 1));
            case "OperatorField" -> operator(data, magnitude);
            case "ToIntegerField" -> new PhysicsFieldOperators.ScalarToInteger(require(data, "FloatField", ScalarNode.class));
            case "ToFloatField" -> new PhysicsFieldOperators.IntegerToScalar(require(data, "IntField", IntegerNode.class));
            case "CullingField" -> culling(data);
            case "ReturnResultsTerminal" -> Results.REFERENCE;
            default -> throw new JsonParseException("Unsupported Chaos FieldSystem node " + type);
        };
    }

    private static Object operator(JsonObject data, float magnitude) {
        var operation = enumValue(data, "Operation", "FIELD_", Operation.MULTIPLY);
        var left = child(data, "LeftField");
        var right = child(data, "RightField");
        if (left instanceof IntegerNode || right instanceof IntegerNode || left == null && right == null
                || left == Results.REFERENCE && right == null || right == Results.REFERENCE && left == null)
            throw new JsonParseException("Invalid Chaos OperatorField inputs");
        if (left instanceof VectorNode || right instanceof VectorNode)
            return new PhysicsFieldOperators.SumVector(magnitude, operation,
                    left instanceof ScalarNode leftScalar ? leftScalar : right instanceof ScalarNode rightScalar ? rightScalar : null,
                    vectorInput(left), vectorInput(right));
        return new PhysicsFieldOperators.SumScalar(magnitude, operation, scalarInput(left), scalarInput(right));
    }

    private static @Nullable VectorNode vectorInput(@Nullable Object value) {
        if (value == Results.REFERENCE) return new PhysicsFieldOperators.ReturnVectorResults();
        return value instanceof VectorNode vector ? vector : null;
    }

    private static @Nullable ScalarNode scalarInput(@Nullable Object value) {
        if (value == Results.REFERENCE) return new PhysicsFieldOperators.ReturnScalarResults();
        return (ScalarNode)value;
    }

    private static Object culling(JsonObject data) {
        var operation = enumValue(data, "Operation", "FIELD_CULLING_", Culling.INSIDE);
        var mask = require(data, "Culling", ScalarNode.class);
        return switch (child(data, "Field")) {
            case ScalarNode input -> new PhysicsFieldOperators.CullScalar(operation, mask, input);
            case VectorNode input -> new PhysicsFieldOperators.CullVector(operation, mask, input);
            case IntegerNode input -> new PhysicsFieldOperators.CullInteger(operation, mask, input);
            case null, default -> throw new JsonParseException("Chaos CullingField requires a typed input");
        };
    }

    private static @Nullable Object child(JsonObject data, String name) {
        return data.get(name) instanceof JsonObject source ? node(source) : null;
    }

    private static <T> T require(JsonObject data, String name, Class<T> type) {
        var value = child(data, name);
        if (!type.isInstance(value)) throw new JsonParseException("Invalid Chaos node input " + name);
        return type.cast(value);
    }

    private static Falloff falloff(JsonObject data) { return enumValue(data, "Falloff", "FIELD_FALLOFF_", Falloff.LINEAR); }

    private static MaskCondition mask(JsonObject data) {
        return enumValue(data, "SetMaskCondition", "FIELD_SET_", MaskCondition.ALWAYS);
    }

    private static <E extends Enum<E>> E enumValue(JsonObject data, String name, String prefix, E defaultValue) {
        var property = data.get(name);
        if (property == null || property.isJsonNull()) return defaultValue;
        E[] values = defaultValue.getDeclaringClass().getEnumConstants();
        if (property.getAsJsonPrimitive().isNumber()) {
            int index = property.getAsInt();
            if (index >= 0 && index < values.length) return values[index];
        } else {
            String raw = property.getAsString().toUpperCase(Locale.ROOT);
            String label = raw.substring(raw.lastIndexOf(':') + 1);
            if (label.startsWith(prefix)) label = label.substring(prefix.length());
            label = label.replace("IFF_NOT_", "IF_NOT_");
            if (label.equals("SUBSTRACT")) label = "SUBTRACT";
            for (E value : values) if (value.name().equals(label)) return value;
        }
        throw new JsonParseException("Unknown Chaos " + name + " value " + property);
    }

    private static PhysicsFieldTransform transform(JsonObject data) {
        if (!(data.get("Transform") instanceof JsonObject transform)) return PhysicsFieldTransform.IDENTITY;
        var rotation = transform.getAsJsonObject("Rotation");
        return new PhysicsFieldTransform(vector(transform, "Translation", 0, 0, 0),
                component(rotation, "X", 0), component(rotation, "Y", 0),
                component(rotation, "Z", 0), component(rotation, "W", 1), vector(transform, "Scale3D", 1, 1, 1));
    }

    private static Vec3 position(JsonObject data) { return vector(data, "Position", 0, 0, 0); }
    private static Vec3 vector(JsonObject data, String name, double x, double y, double z) {
        var vector = data.getAsJsonObject(name);
        return new Vec3(component(vector, "X", x), component(vector, "Y", y), component(vector, "Z", z));
    }

    private static double component(@Nullable JsonObject data, String name, double fallback) {
        return data == null || !data.has(name) ? fallback : data.get(name).getAsDouble();
    }

    private static int integer(JsonObject data, String name, int fallback) { return data.has(name) ? data.get(name).getAsInt() : fallback; }
    private static float number(JsonObject data, String name, float fallback) { return data.has(name) ? data.get(name).getAsFloat() : fallback; }
}
