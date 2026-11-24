package com.kltyton.lumenfx.api.client.parameter;

import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Properties supplied by the Niagara scene component's owner, keyed by the full authored interface path. */
public record NiagaraPropertyInterfaceInputs(Map<String, List<Property>> interfaces) {
    public static final NiagaraPropertyInterfaceInputs EMPTY = new NiagaraPropertyInterfaceInputs(Map.of());

    public NiagaraPropertyInterfaceInputs {
        var paths = new LinkedHashMap<String, List<Property>>();
        interfaces.forEach((path, properties) -> {
            if (paths.putIfAbsent(SourceNames.canonical(path), List.copyOf(properties)) != null)
                throw new IllegalArgumentException("Duplicate Niagara property interface path " + path);
        });
        interfaces = Map.copyOf(paths);
    }

    public List<Property> properties(String path) {
        return interfaces.getOrDefault(SourceNames.canonical(path), List.of());
    }

    /** A converted property in source PropertyLink order with its unmangled name and Niagara type path. */
    public record Property(String name, String type, List<Integer> words) {
        public Property {
            Objects.requireNonNull(name);
            Objects.requireNonNull(type);
            words = List.copyOf(words);
            if (name.isEmpty() || type.isEmpty() || words.isEmpty())
                throw new IllegalArgumentException("Niagara interface property requires a name, type and value");
        }

        public static Property integer(String name, int value) {
            return new Property(name, "/Script/Niagara.NiagaraInt32", List.of(value));
        }

        public static Property scalar(String name, double value) {
            return floating(name, "/Script/Niagara.NiagaraFloat", value);
        }

        public static Property vector2(String name, double x, double y) {
            return floating(name, "/Script/CoreUObject.Vector2f", x, y);
        }

        public static Property vector3(String name, double x, double y, double z) {
            return floating(name, "/Script/CoreUObject.Vector3f", x, y, z);
        }

        public static Property vector4(String name, double x, double y, double z, double w) {
            return floating(name, "/Script/CoreUObject.Vector4f", x, y, z, w);
        }

        public static Property quaternion(String name, double x, double y, double z, double w) {
            return floating(name, "/Script/CoreUObject.Quat4f", x, y, z, w);
        }

        public static Property color(String name, float r, float g, float b, float a) {
            return new Property(name, "/Script/CoreUObject.LinearColor", List.of(
                    Float.floatToRawIntBits(r), Float.floatToRawIntBits(g), Float.floatToRawIntBits(b), Float.floatToRawIntBits(a)));
        }

        private static Property floating(String name, String type, double... components) {
            var words = new ArrayList<Integer>(components.length);
            for (double component : components) words.add(Float.floatToRawIntBits((float)component));
            return new Property(name, type, words);
        }
    }
}
