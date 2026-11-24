package com.kltyton.lumenfx.client.simulation.niagara.parameter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import org.jspecify.annotations.Nullable;

/** Shared source owner/global/emitter constant layout for VM, GPU and renderer-bound values. */
public final class NiagaraEngineParameters {
    private record Span(int offset, int bytes) { }
    private static final Map<String, Span> FIELDS = Map.ofEntries(
            field("Engine_WorldDeltaTime", 0, 4), field("Engine_DeltaTime", 4, 4),
            field("Engine_InverseDeltaTime", 8, 4), field("Engine_Time", 12, 4),
            field("Engine_RealTime", 16, 4), field("Engine_QualityLevel", 20, 4),
            field("Engine_PaddingInt32_0", 24, 4), field("Engine_PaddingInt32_1", 28, 4),
            field("Engine_Owner_TimeSinceRendered", 32, 4), field("Engine_Owner_LODDistance", 36, 4),
            field("Engine_Owner_LODDistanceFraction", 40, 4), field("Engine_System_Age", 44, 4),
            field("Engine_Owner_ExecutionState", 48, 4), field("Engine_System_TickCount", 52, 4),
            field("Engine_System_NumEmitters", 56, 4), field("Engine_System_NumEmittersAlive", 60, 4),
            field("Engine_System_SignificanceIndex", 64, 4), field("Engine_System_RandomSeed", 68, 4),
            field("Engine_System_CurrentTimeStep", 72, 4), field("Engine_System_NumTimeSteps", 76, 4),
            field("Engine_System_TimeStepFraction", 80, 4), field("Engine_System_NumParticles", 84, 4),
            field("Engine_System_PaddingInt32_0", 88, 4), field("Engine_System_PaddingInt32_1", 92, 4),
            field("Engine_Owner_SystemLocalToWorld", 96, 64), field("Engine_Owner_SystemWorldToLocal", 160, 64),
            field("Engine_Owner_SystemLocalToWorldTransposed", 224, 64), field("Engine_Owner_SystemWorldToLocalTransposed", 288, 64),
            field("Engine_Owner_SystemLocalToWorldNoScale", 352, 64), field("Engine_Owner_SystemWorldToLocalNoScale", 416, 64),
            field("Engine_Owner_Rotation", 480, 16), field("Engine_Owner_Position", 496, 12),
            field("Engine_Owner_PaddingInt32_0", 508, 4), field("Engine_Owner_Velocity", 512, 12),
            field("Engine_Owner_PaddingInt32_1", 524, 4), field("Engine_Owner_SystemXAxis", 528, 12),
            field("Engine_Owner_PaddingInt32_2", 540, 4), field("Engine_Owner_SystemYAxis", 544, 12),
            field("Engine_Owner_PaddingInt32_3", 556, 4), field("Engine_Owner_SystemZAxis", 560, 12),
            field("Engine_Owner_PaddingInt32_4", 572, 4), field("Engine_Owner_Scale", 576, 12),
            field("Engine_Owner_PaddingInt32_5", 588, 4), field("Engine_Owner_LWCTile", 592, 16),
            field("Engine_Emitter_NumParticles", 608, 4), field("Engine_Emitter_TotalSpawnedParticles", 612, 4),
            field("Engine_Emitter_SpawnCountScale", 616, 4), field("Emitter_Age", 620, 4),
            field("Emitter_RandomSeed", 624, 4), field("Engine_Emitter_InstanceSeed", 628, 4),
            field("Engine_Emitter_PaddingInt32_0", 632, 4), field("Engine_Emitter_PaddingInt32_1", 636, 4));
    private static final Map<String, Span> NATIVE_FIELDS = nativeFields();
    private static final Set<Integer> INTEGER_OFFSETS = Set.of(20, 24, 28, 48, 52, 56, 60, 64, 68, 72, 76,
            84, 88, 92, 508, 524, 540, 556, 572, 588, 608, 612, 624, 628, 632, 636);
    private final byte[] current, previous;

    public NiagaraEngineParameters(byte[] current, byte[] previous) {
        requireLayout(current); requireLayout(previous);
        this.current = current.clone(); this.previous = previous.clone();
    }
    public @Nullable byte[] value(String name) {
        boolean old = name.startsWith("PREV_");
        Span span = FIELDS.get(old ? name.substring(5) : name);
        if (span == null) return null;
        return Arrays.copyOfRange(old ? previous : current, span.offset(), span.offset() + span.bytes());
    }
    public static boolean readFloats(byte[] snapshot, String variable, String emitter, float[] destination) {
        return readFloats(snapshot, variable, emitter, null, destination);
    }
    public static boolean readFloats(byte[] snapshot, String variable, String emitter, @Nullable String requiredType, float[] destination) {
        requireLayout(snapshot);
        String name = variable;
        if (name.length() > emitter.length() && name.charAt(emitter.length()) == '.'
                && SourceNames.equal(name.substring(0, emitter.length()), emitter)) name = "Emitter" + name.substring(emitter.length());
        Span span = NATIVE_FIELDS.get(name);
        if (span == null || span.bytes() != destination.length * Float.BYTES || INTEGER_OFFSETS.contains(span.offset())
                || span.offset() == 480 || requiredType != null && !SourceNames.equal(requiredType, sourceType(span.offset()))) return false;
        ByteBuffer data = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < destination.length; i++) destination[i] = data.getFloat(span.offset() + i * Float.BYTES);
        return true;
    }
    public static boolean readWords(byte[] snapshot, String variable, String emitter, String sourceType,
                                    int[] destination, int offset, int count) {
        requireLayout(snapshot);
        String name = variable;
        if (name.length() > emitter.length() && name.charAt(emitter.length()) == '.'
                && SourceNames.equal(name.substring(0, emitter.length()), emitter)) name = "Emitter" + name.substring(emitter.length());
        Span span = NATIVE_FIELDS.get(name);
        if (span == null || span.bytes() != count * Integer.BYTES || !sourceType.equals(sourceType(span.offset()))) return false;
        ByteBuffer data = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN);
        for (int index = 0; index < count; index++) destination[offset + index] = data.getInt(span.offset() + index * Integer.BYTES);
        return true;
    }
    private static void requireLayout(byte[] snapshot) {
        if (snapshot.length != 640) throw new IllegalArgumentException("Niagara engine constant snapshot must contain 640 bytes");
    }
    private static Map<String, Span> nativeFields() {
        var fields = new TreeMap<String, Span>(SourceNames.ASCII_CASE_INSENSITIVE);
        FIELDS.forEach((name, span) -> fields.put(name.replace('_', '.'), span));
        return Collections.unmodifiableMap(fields);
    }
    private static String sourceType(int offset) {
        if (offset == 48) return "/Script/Niagara.ENiagaraExecutionState";
        if (INTEGER_OFFSETS.contains(offset)) return "/Script/Niagara.NiagaraInt32";
        return switch (offset) {
            case 96, 160, 224, 288, 352, 416 -> "/Script/Niagara.NiagaraMatrix";
            case 480 -> "/Script/CoreUObject.Quat4f";
            case 496 -> "/Script/Niagara.NiagaraPosition";
            case 512, 528, 544, 560, 576 -> "/Script/CoreUObject.Vector3f";
            case 592 -> "/Script/CoreUObject.Vector4f";
            default -> "/Script/Niagara.NiagaraFloat";
        };
    }
    private static Map.Entry<String, Span> field(String name, int offset, int bytes) { return Map.entry(name, new Span(offset, bytes)); }
}
