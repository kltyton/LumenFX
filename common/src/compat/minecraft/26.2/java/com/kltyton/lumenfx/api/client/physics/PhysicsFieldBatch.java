package com.kltyton.lumenfx.api.client.physics;

import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.IntegerNode;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.ScalarNode;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.VectorNode;
import java.util.Arrays;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Evaluates one command over its lane set before advancing to the next graph branch. */
public final class PhysicsFieldBatch {
    private PhysicsFieldBatch() { }

    public static void evaluate(VectorNode node, Vec3[] positions, float age, Vec3[] output) {
        new Lanes(positions, age, null, positions.length, null).vector(node, output);
    }
    public static void evaluate(ScalarNode node, Vec3[] positions, float age, float[] output) {
        new Lanes(positions, age, null, positions.length, null).scalar(node, output);
    }
    public static void evaluate(IntegerNode node, Vec3[] positions, float age, int[] output) {
        new Lanes(positions, age, null, positions.length, null).integer(node, output);
    }

    interface VectorOperator extends VectorNode {
        void evaluateBatch(Lanes lanes, Vec3[] output);
        @Override default Vec3 evaluate(Vec3 position, float age, Vec3 previous) {
            Vec3[] result = {previous};
            PhysicsFieldBatch.evaluate(this, new Vec3[]{position}, age, result);
            return result[0];
        }
    }
    interface ScalarOperator extends ScalarNode {
        void evaluateBatch(Lanes lanes, float[] output);
        @Override default float evaluate(Vec3 position, float age, float previous) {
            float[] result = {previous};
            PhysicsFieldBatch.evaluate(this, new Vec3[]{position}, age, result);
            return result[0];
        }
    }
    interface IntegerOperator extends IntegerNode {
        void evaluateBatch(Lanes lanes, int[] output);
        @Override default int evaluate(Vec3 position, float age, int previous) {
            int[] result = {previous};
            PhysicsFieldBatch.evaluate(this, new Vec3[]{position}, age, result);
            return result[0];
        }
    }

    record Lanes(Vec3[] positions, float age, int @Nullable [] indices, int count, @Nullable Object enclosing) {
        int size() { return positions.length; }
        int index(int ordinal) { return indices == null ? ordinal : indices[ordinal]; }
        Lanes withResults(Object values) { return new Lanes(positions, age, indices, count, values); }

        Lanes masked(@Nullable ScalarNode mask, PhysicsFieldOperators.Culling operation) {
            if (mask == null) return this;
            float[] values = new float[size()];
            scalar(mask, values);
            int[] selected = new int[count];
            int length = 0;
            for (int ordinal = 0; ordinal < count; ordinal++) {
                int lane = index(ordinal);
                if (operation.includes(values[lane])) selected[length++] = lane;
            }
            return new Lanes(positions, age, selected, length, enclosing);
        }

        Vec3[] emptyVectors() {
            var values = new Vec3[size()];
            Arrays.fill(values, Vec3.ZERO);
            return values;
        }

        void vector(VectorNode node, Vec3[] output) {
            if (count == 0) return;
            if (node instanceof VectorOperator operator) operator.evaluateBatch(this, output);
            else for (int ordinal = 0; ordinal < count; ordinal++) {
                int lane = index(ordinal);
                output[lane] = enclosing instanceof Vec3[] scope
                        ? node.evaluateInScope(positions[lane], age, output[lane], scope[lane])
                        : node.evaluate(positions[lane], age, output[lane]);
            }
        }

        void scalar(ScalarNode node, float[] output) {
            if (count == 0) return;
            if (node instanceof ScalarOperator operator) operator.evaluateBatch(this, output);
            else for (int ordinal = 0; ordinal < count; ordinal++) {
                int lane = index(ordinal);
                output[lane] = enclosing instanceof float[] scope
                        ? node.evaluateInScope(positions[lane], age, output[lane], scope[lane])
                        : node.evaluate(positions[lane], age, output[lane]);
            }
        }

        void integer(IntegerNode node, int[] output) {
            if (count == 0) return;
            if (node instanceof IntegerOperator operator) operator.evaluateBatch(this, output);
            else for (int ordinal = 0; ordinal < count; ordinal++) {
                int lane = index(ordinal);
                output[lane] = node.evaluate(positions[lane], age, output[lane]);
            }
        }
    }
}
