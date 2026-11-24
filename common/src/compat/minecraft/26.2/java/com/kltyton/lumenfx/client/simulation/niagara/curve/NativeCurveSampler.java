package com.kltyton.lumenfx.client.simulation.niagara.curve;

import com.kltyton.lumenfx.client.simulation.niagara.NativeVectorMath;
import com.kltyton.lumenfx.effect.curve.NiagaraCurve;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.VectorProgram;

/** Rich curves use the scalar Windows math path; LUTs retain their separate float sampling path. */
public final class NativeCurveSampler {
    private final float[] keys;
    private final int[] modes;
    private final int pre, post;

    public static void prepare(NiagaraDefinition system) {
        boolean needed = required(system.systemSpawn()) || required(system.systemUpdate());
        for (var emitter : system.emitters()) if (emitter.simulation() instanceof NiagaraDefinition.CpuSimulation cpu) {
            needed |= required(cpu.spawn()) || required(cpu.update());
            for (var event : emitter.eventHandlers()) if (event.program() != null) needed |= required(event.program());
        }
        if (needed) NativeVectorMath.initialize();
    }

    private static boolean required(VectorProgram program) {
        for (var instruction : program.instructions())
            if (instruction.opcode() == VectorProgram.Opcode.EXTERNAL_FUNC_CALL
                    && program.external().get(instruction.function()).curves().stream().anyMatch(curve -> curve.lookup() == null)) return true;
        return false;
    }

    public NativeCurveSampler(NiagaraCurve curve) {
        if (curve.lookup() != null) throw new IllegalArgumentException("A rich-curve sampler cannot replace a curve LUT");
        keys = new float[Math.multiplyExact(curve.keys().size(), 6)];
        modes = new int[curve.keys().size()];
        for (int index = 0; index < modes.length; index++) {
            var key = curve.keys().get(index);
            int offset = index * 6;
            keys[offset] = key.time(); keys[offset + 1] = key.value();
            keys[offset + 2] = key.arrive(); keys[offset + 3] = key.leave();
            keys[offset + 4] = key.arriveWeight(); keys[offset + 5] = key.leaveWeight();
            int interpolation = switch (key.interpolation()) { case CONSTANT -> 0; case LINEAR -> 1; case CUBIC -> 2; };
            modes[index] = interpolation | key.weight().ordinal() << 2;
        }
        pre = curve.pre().ordinal(); post = curve.post().ordinal();
    }

    public void sample(int count, int[] input, int[] output) {
        if (count < 0 || count > input.length || count > output.length) throw new IllegalArgumentException("Invalid rich-curve batch extent");
        if (count == 0) return;
        NativeVectorMath.initialize();
        evaluateNative(count, input, output, keys, modes, pre, post);
    }

    private static native void evaluateNative(int count, int[] input, int[] output, float[] keys, int[] modes, int pre, int post);
}
