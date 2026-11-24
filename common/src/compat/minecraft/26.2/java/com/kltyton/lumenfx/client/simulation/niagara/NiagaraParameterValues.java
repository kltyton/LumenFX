package com.kltyton.lumenfx.client.simulation.niagara;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.effect.niagara.VectorProgram;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraCollectionSnapshot;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraPositionParameters;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

/** Writes instance parameter values into a script's typed parameter buffer. */
public final class NiagaraParameterValues {
    private NiagaraParameterValues() { }

    static void restore(VectorProgram program, byte[] target, byte[] defaults) {
        System.arraycopy(defaults, program.runtimeBytes(), target, program.runtimeBytes(), program.parameterBytes());
    }

    public static void write(VectorProgram program, byte[] target, Map<String, EffectHandle.ParameterValue> values,
                      double originX, double originY, double originZ) {
        ByteBuffer data = ByteBuffer.wrap(target).order(ByteOrder.LITTLE_ENDIAN);
        for (var parameter : program.parameters()) {
            var value = values.get(parameter.name());
            if (value == null) for (var entry : values.entrySet())
                if (SourceNames.equal(entry.getKey(), parameter.name())) { value = entry.getValue(); break; }
            if (value == null) continue;
            writeValue(parameter, data, program.runtimeBytes() + parameter.offset(), value, originX, originY, originZ);
        }
    }

    public static void writeCollections(VectorProgram program, byte[] target, NiagaraCollectionSnapshot collections,
                                 double originX, double originY, double originZ) {
        ByteBuffer data = null;
        for (var parameter : program.parameters()) {
            var collection = parameter.collection();
            if (collection == null) continue;
            int offset = program.runtimeBytes() + parameter.offset();
            boolean component = collections.component().contains(collection.path());
            collection.copyDefault(target, offset, component, originX, originY, originZ);
            EffectHandle.ParameterValue value = component ? collections.component().get(collection.path(), parameter.name(), parameter.sourceType())
                    : collection.systemOverride() ? null : collections.world().get(collection.path(), parameter.name(), parameter.sourceType());
            if (value == null) continue;
            if (data == null) data = ByteBuffer.wrap(target).order(ByteOrder.LITTLE_ENDIAN);
            writeValue(parameter, data, offset, value, originX, originY, originZ);
        }
    }

    static void writeSystemSimulation(VectorProgram program, byte[] target, NiagaraPlatformEnvironment platform, int running) {
        ByteBuffer data = null;
        for (var parameter : program.parameters()) {
            if (parameter.bytes() != Integer.BYTES) continue;
            int value;
            if (SourceNames.equal(parameter.sourceType(), "/Script/Niagara.NiagaraInt32")
                    && SourceNames.equal(parameter.name(), "Engine.NumSystemInstances")) value = running;
            else if (SourceNames.equal(parameter.sourceType(), "/Script/Niagara.NiagaraFloat")
                    && SourceNames.equal(parameter.name(), "Engine.GlobalSpawnCountScale"))
                value = Float.floatToRawIntBits(platform.globalSpawnCountScale());
            else if (SourceNames.equal(parameter.sourceType(), "/Script/Niagara.NiagaraFloat")
                    && SourceNames.equal(parameter.name(), "Engine.GlobalSystemCountScale"))
                value = Float.floatToRawIntBits(platform.globalSystemCountScale());
            else continue;
            if (data == null) data = ByteBuffer.wrap(target).order(ByteOrder.LITTLE_ENDIAN);
            data.putInt(program.runtimeBytes() + parameter.offset(), value);
        }
    }

    private static void writeValue(VectorProgram.Parameter parameter, ByteBuffer data, int offset, EffectHandle.ParameterValue value,
                                   double originX, double originY, double originZ) {
        writeValue(parameter.name(), parameter.sourceType(), parameter.bytes(), data, offset, value, originX, originY, originZ);
    }

    public static void writeValue(String name, String sourceType, int bytes, ByteBuffer data, int offset,
                                  EffectHandle.ParameterValue value, double originX, double originY, double originZ) {
        int components = value.type().components(), storage = value.type().storage();
        int width = storage == 3 ? Short.BYTES : Integer.BYTES;
        if (bytes != components * width || !matches(sourceType, storage, components))
            throw new IllegalArgumentException("Niagara parameter type does not match " + name);
        long[] words = value.words();
        if (storage == 4) {
            NiagaraPositionParameters.write(value, data, offset, originX, originY, originZ);
            return;
        }
        for (int i = 0; i < components; i++) {
            if (storage == 3) data.putShort(offset + i * width, (short)words[i]);
            else data.putInt(offset + i * width, storage == 2 ? words[i] == 0 ? 0 : -1 : (int)words[i]);
        }
    }

    public static boolean matches(String type, int storage, int components) {
        return switch (storage) {
            case 0 -> switch (components) {
                case 1 -> type.endsWith(".NiagaraFloat");
                case 2 -> type.endsWith(".Vector2f");
                case 3 -> type.endsWith(".Vector3f");
                case 4 -> type.endsWith(".Vector4f") || type.endsWith(".LinearColor") || type.endsWith(".Quat4f");
                default -> false;
            };
            case 1 -> components == 1 && type.endsWith(".NiagaraInt32");
            case 2 -> components == 1 && type.endsWith(".NiagaraBool");
            case 3 -> switch (components) {
                case 1 -> type.endsWith(".NiagaraHalf");
                case 2 -> type.endsWith(".NiagaraHalfVector2");
                case 3 -> type.endsWith(".NiagaraHalfVector3");
                case 4 -> type.endsWith(".NiagaraHalfVector4");
                default -> false;
            };
            case 4 -> components == 3 && type.endsWith(".NiagaraPosition");
            case 5 -> components == 2 && SourceNames.equal(type, "/Script/Niagara.NiagaraSharedDataID_StaticMesh");
            default -> false;
        };
    }
}
