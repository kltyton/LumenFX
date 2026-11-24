package com.kltyton.lumenfx.client.simulation.niagara;

/** Math for UNiagaraFunctionLibrary VM calls that have no data interface instance. */
final class NiagaraFastPathMath {
    private NiagaraFastPathMath() { }

    /** Reads an Unreal row-major matrix and writes XYZW into the four following register slots. */
    static void matrixToQuaternion(float[] registers) {
        float trace = registers[0] + registers[5] + registers[10];
        if (trace > 0) {
            float inverse = inverseSqrt(trace + 1);
            float halfInverse = .5f * inverse;
            registers[16] = (registers[6] - registers[9]) * halfInverse;
            registers[17] = (registers[8] - registers[2]) * halfInverse;
            registers[18] = (registers[1] - registers[4]) * halfInverse;
            registers[19] = .5f * (1 / inverse);
            return;
        }
        int axis = registers[5] > registers[0] ? 1 : 0;
        if (registers[10] > registers[axis * 5]) axis = 2;
        int next = (axis + 1) % 3;
        int last = (axis + 2) % 3;
        float inverse = inverseSqrt(registers[axis * 5] - registers[next * 5] - registers[last * 5] + 1);
        float halfInverse = .5f * inverse;
        registers[16 + axis] = .5f * (1 / inverse);
        registers[16 + next] = (registers[axis * 4 + next] + registers[next * 4 + axis]) * halfInverse;
        registers[16 + last] = (registers[axis * 4 + last] + registers[last * 4 + axis]) * halfInverse;
        registers[19] = (registers[next * 4 + last] - registers[last * 4 + next]) * halfInverse;
    }

    private static float inverseSqrt(float value) {
        return 1 / (float)Math.sqrt(value);
    }
}
