package com.kltyton.lumenfx.api.client.physics;

import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.IntegerNode;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.ScalarNode;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.VectorNode;
import com.kltyton.lumenfx.api.client.physics.PhysicsFieldBatch.IntegerOperator;
import com.kltyton.lumenfx.api.client.physics.PhysicsFieldBatch.Lanes;
import com.kltyton.lumenfx.api.client.physics.PhysicsFieldBatch.ScalarOperator;
import com.kltyton.lumenfx.api.client.physics.PhysicsFieldBatch.VectorOperator;
import com.kltyton.lumenfx.api.client.physics.PhysicsFieldGraph.PackedNode;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Typed composition and postfix packing for the native Chaos sum, conversion and culling nodes. */
public final class PhysicsFieldOperators {
    private PhysicsFieldOperators() { }

    public enum Operation {
        MULTIPLY, DIVIDE, ADD, SUBTRACT;

        float combine(float left, float right) {
            return switch (this) {
                case MULTIPLY -> right * left;
                case DIVIDE -> left / right;
                case ADD -> right + left;
                case SUBTRACT -> left - right;
            };
        }

        Vec3 combine(Vec3 left, Vec3 right) {
            return switch (this) {
                case MULTIPLY -> right.multiply(left);
                case DIVIDE -> new Vec3(left.x / right.x, left.y / right.y, left.z / right.z);
                case ADD -> right.add(left);
                case SUBTRACT -> left.subtract(right);
            };
        }
    }

    public enum Culling {
        INSIDE, OUTSIDE;

        boolean includes(float culling) {
            boolean nonzero = culling != 0;
            return this == OUTSIDE ? nonzero : !nonzero;
        }
    }

    public record SumScalar(float magnitude, Operation operation, @Nullable ScalarNode left, @Nullable ScalarNode right)
            implements ScalarOperator, PackedNode {
        public SumScalar { Objects.requireNonNull(operation); }

        @Override public void evaluateBatch(Lanes lanes, float[] output) {
            var scope = lanes.withResults(output);
            if (left != null && right != null) {
                float[] lhs = new float[lanes.size()], rhs = new float[lanes.size()];
                scope.scalar(left, lhs);
                scope.scalar(right, rhs);
                for (int ordinal = 0; ordinal < lanes.count(); ordinal++) {
                    int lane = lanes.index(ordinal);
                    output[lane] = operation.combine(lhs[lane], rhs[lane]);
                }
            } else if (left != null) scope.scalar(left, output);
            else if (right != null) scope.scalar(right, output);
            if (magnitude != 1) for (int ordinal = 0; ordinal < lanes.count(); ordinal++)
                output[lanes.index(ordinal)] *= magnitude;
        }

        @Override public void pack(PhysicsFieldGraph graph, float creationTime) {
            graph.child(right, creationTime);
            graph.child(left, creationTime);
            graph.node(PhysicsFieldGraph.SCALAR, PhysicsFieldGraph.SUM_SCALAR, magnitude,
                    present(right), present(left), operation.ordinal());
        }
    }

    public record SumVector(float magnitude, Operation operation, @Nullable ScalarNode scalar,
                            @Nullable VectorNode left, @Nullable VectorNode right) implements VectorOperator, PackedNode {
        public SumVector { Objects.requireNonNull(operation); }

        @Override public void evaluateBatch(Lanes lanes, Vec3[] output) {
            var scope = lanes.withResults(output);
            if (left != null && right != null) {
                Vec3[] lhs = lanes.emptyVectors(), rhs = lanes.emptyVectors();
                scope.vector(left, lhs);
                scope.vector(right, rhs);
                for (int ordinal = 0; ordinal < lanes.count(); ordinal++) {
                    int lane = lanes.index(ordinal);
                    output[lane] = operation.combine(lhs[lane], rhs[lane]);
                }
            } else if (left != null) scope.vector(left, output);
            else if (right != null) scope.vector(right, output);
            if (scalar != null) {
                float[] values = new float[lanes.size()];
                scope.scalar(scalar, values);
                for (int ordinal = 0; ordinal < lanes.count(); ordinal++) {
                    int lane = lanes.index(ordinal);
                    output[lane] = output[lane].scale(values[lane]);
                }
            }
            if (magnitude != 1) for (int ordinal = 0; ordinal < lanes.count(); ordinal++) {
                int lane = lanes.index(ordinal);
                output[lane] = output[lane].scale(magnitude);
            }
        }

        @Override public void pack(PhysicsFieldGraph graph, float creationTime) {
            graph.child(scalar, creationTime);
            graph.child(right, creationTime);
            graph.child(left, creationTime);
            graph.node(PhysicsFieldGraph.VECTOR, PhysicsFieldGraph.SUM_VECTOR, magnitude, present(scalar),
                    present(right), present(left), operation.ordinal());
        }
    }

    public record ReturnScalarResults() implements ScalarNode, PackedNode {
        @Override public float evaluate(Vec3 position, float age, float previous) {
            throw new IllegalStateException("Chaos ReturnResultsTerminal needs an enclosing scalar operator");
        }
        @Override public float evaluateInScope(Vec3 position, float age, float previous, float enclosingResults) { return enclosingResults; }
        @Override public void pack(PhysicsFieldGraph graph, float creationTime) { graph.node(PhysicsFieldGraph.SCALAR, 16); }
    }

    public record ReturnVectorResults() implements VectorNode, PackedNode {
        @Override public Vec3 evaluate(Vec3 position, float age, Vec3 previous) {
            throw new IllegalStateException("Chaos ReturnResultsTerminal needs an enclosing vector operator");
        }
        @Override public Vec3 evaluateInScope(Vec3 position, float age, Vec3 previous, Vec3 enclosingResults) { return enclosingResults; }
        @Override public void pack(PhysicsFieldGraph graph, float creationTime) { graph.node(PhysicsFieldGraph.VECTOR, 16); }
    }

    public record IntegerToScalar(IntegerNode input) implements ScalarOperator, PackedNode {
        public IntegerToScalar { Objects.requireNonNull(input); }
        @Override public void evaluateBatch(Lanes lanes, float[] output) {
            int[] values = new int[lanes.size()];
            lanes.integer(input, values);
            for (int ordinal = 0; ordinal < lanes.count(); ordinal++) {
                int lane = lanes.index(ordinal);
                output[lane] = values[lane];
            }
        }
        @Override public void pack(PhysicsFieldGraph graph, float creationTime) {
            graph.child(input, creationTime);
            graph.node(PhysicsFieldGraph.SCALAR, PhysicsFieldGraph.CONVERSION, 1);
        }
    }

    public record ScalarToInteger(ScalarNode input) implements IntegerOperator, PackedNode {
        public ScalarToInteger { Objects.requireNonNull(input); }
        @Override public void evaluateBatch(Lanes lanes, int[] output) {
            float[] values = new float[lanes.size()];
            lanes.scalar(input, values);
            for (int ordinal = 0; ordinal < lanes.count(); ordinal++) {
                int lane = lanes.index(ordinal);
                output[lane] = (int)values[lane];
            }
        }
        @Override public void pack(PhysicsFieldGraph graph, float creationTime) {
            graph.child(input, creationTime);
            graph.node(PhysicsFieldGraph.INTEGER, PhysicsFieldGraph.CONVERSION, 1);
        }
    }

    public record CullScalar(Culling operation, @Nullable ScalarNode culling, @Nullable ScalarNode input)
            implements ScalarOperator, PackedNode {
        public CullScalar { Objects.requireNonNull(operation); }
        @Override public void evaluateBatch(Lanes lanes, float[] output) {
            var selected = lanes.masked(culling, operation);
            if (input != null) selected.scalar(input, output);
        }
        @Override public void pack(PhysicsFieldGraph graph, float creationTime) {
            packCulling(graph, PhysicsFieldGraph.SCALAR, operation, culling, input, creationTime);
        }
    }

    public record CullVector(Culling operation, @Nullable ScalarNode culling, @Nullable VectorNode input)
            implements VectorOperator, PackedNode {
        public CullVector { Objects.requireNonNull(operation); }
        @Override public void evaluateBatch(Lanes lanes, Vec3[] output) {
            var selected = lanes.masked(culling, operation);
            if (input != null) selected.vector(input, output);
        }
        @Override public void pack(PhysicsFieldGraph graph, float creationTime) {
            packCulling(graph, PhysicsFieldGraph.VECTOR, operation, culling, input, creationTime);
        }
    }

    public record CullInteger(Culling operation, @Nullable ScalarNode culling, @Nullable IntegerNode input)
            implements IntegerOperator, PackedNode {
        public CullInteger { Objects.requireNonNull(operation); }
        @Override public void evaluateBatch(Lanes lanes, int[] output) {
            var selected = lanes.masked(culling, operation);
            if (input != null) selected.integer(input, output);
        }
        @Override public void pack(PhysicsFieldGraph graph, float creationTime) {
            packCulling(graph, PhysicsFieldGraph.INTEGER, operation, culling, input, creationTime);
        }
    }

    private static void packCulling(PhysicsFieldGraph graph, int type, Culling operation,
                                    @Nullable ScalarNode culling, @Nullable Object input, float creationTime) {
        graph.child(culling, creationTime);
        graph.child(input, creationTime);
        graph.node(type, PhysicsFieldGraph.CULLING, present(culling), present(input), operation.ordinal());
    }

    private static int present(@Nullable Object value) { return value == null ? 0 : 1; }
}
