package com.kltyton.lumenfx.api.client.physics;

import com.kltyton.lumenfx.math.WindowsSourceRandom;

import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.IntegerNode;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.ScalarNode;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.VectorNode;
import com.kltyton.lumenfx.api.client.physics.PhysicsFieldGraph.PackedNode;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;

/** Chaos field terminals and their native shader argument order. */
public final class PhysicsFieldNodes {
    private static final float SMALL_NUMBER = 1.0e-8f;

    private PhysicsFieldNodes() { }

    public enum MaskCondition { ALWAYS, IF_NOT_INTERIOR, IF_NOT_EXTERIOR }
    public enum WaveFunction { COSINE, GAUSSIAN, FALLOFF, DECAY }
    public enum Falloff {
        NONE, LINEAR, INVERSE, SQUARED, LOGARITHMIC;

        float evaluate(float value) {
            return switch (this) {
                case NONE -> 1;
                case LINEAR -> value;
                case INVERSE -> 2 * (1 - 1 / (value + 1));
                case SQUARED -> value * value;
                case LOGARITHMIC -> (float)(Math.log(value + 1) / Math.log(2));
            };
        }
    }

    public record UniformInteger(int magnitude) implements IntegerNode, PackedNode {
        @Override public int evaluate(Vec3 worldPosition, float age, int previous) { return magnitude; }
        @Override public void pack(PhysicsFieldGraph graph, float creationTime) {
            graph.node(PhysicsFieldGraph.INTEGER, 1, magnitude);
        }
    }

    public record RadialIntegerMask(float radius, Vec3 center, int interior, int exterior, MaskCondition condition)
            implements IntegerNode, PackedNode {
        public RadialIntegerMask { Objects.requireNonNull(center); Objects.requireNonNull(condition); }

        @Override public int evaluate(Vec3 worldPosition, float age, int previous) {
            if (condition == MaskCondition.IF_NOT_INTERIOR && previous == interior
                    || condition == MaskCondition.IF_NOT_EXTERIOR && previous == exterior) return previous;
            return center.distanceToSqr(worldPosition) < radius * radius ? interior : exterior;
        }

        @Override public void pack(PhysicsFieldGraph graph, float creationTime) {
            graph.node(PhysicsFieldGraph.INTEGER, 2, radius, (float)center.x, (float)center.y, (float)center.z,
                    interior, exterior, condition.ordinal());
        }
    }

    public record UniformScalar(float magnitude) implements ScalarNode, PackedNode {
        @Override public float evaluate(Vec3 worldPosition, float age, float previous) { return magnitude; }
        @Override public void pack(PhysicsFieldGraph graph, float creationTime) {
            graph.node(PhysicsFieldGraph.SCALAR, 3, magnitude);
        }
    }

    public record RadialFalloff(float magnitude, float minimum, float maximum, float outside,
                                float radius, Vec3 center, Falloff falloff) implements ScalarNode, PackedNode {
        public RadialFalloff { Objects.requireNonNull(center); Objects.requireNonNull(falloff); }

        @Override public float evaluate(Vec3 worldPosition, float age, float previous) {
            if (radius <= 0) return previous;
            double distance = center.distanceTo(worldPosition);
            return distance < radius ? scale(magnitude, minimum, maximum, falloff, (float)(1 - distance / radius)) : outside;
        }

        @Override public void pack(PhysicsFieldGraph graph, float creationTime) {
            graph.node(PhysicsFieldGraph.SCALAR, 4, magnitude, minimum, maximum, outside, radius,
                    (float)center.x, (float)center.y, (float)center.z, falloff.ordinal());
        }
    }

    public record PlaneFalloff(float magnitude, float minimum, float maximum, float outside, float distance,
                                Vec3 position, Vec3 normal, Falloff falloff) implements ScalarNode, PackedNode {
        public PlaneFalloff { Objects.requireNonNull(position); Objects.requireNonNull(normal); Objects.requireNonNull(falloff); }

        @Override public float evaluate(Vec3 worldPosition, float age, float previous) {
            if (distance <= 0) return previous;
            double delta = normal.dot(worldPosition) - normal.dot(position);
            return delta < -SMALL_NUMBER && delta > -distance
                    ? scale(magnitude, minimum, maximum, falloff, (float)(1 + delta / distance)) : outside;
        }

        @Override public void pack(PhysicsFieldGraph graph, float creationTime) {
            graph.node(PhysicsFieldGraph.SCALAR, 5, magnitude, minimum, maximum, outside, distance,
                    (float)position.x, (float)position.y, (float)position.z,
                    (float)normal.x, (float)normal.y, (float)normal.z, falloff.ordinal());
        }
    }

    public record BoxFalloff(float magnitude, float minimum, float maximum, float outside,
                             PhysicsFieldTransform transform,
                             Falloff falloff) implements ScalarNode, PackedNode {
        public BoxFalloff {
            Objects.requireNonNull(transform);
            Objects.requireNonNull(falloff);
        }

        @Override public float evaluate(Vec3 worldPosition, float age, float previous) {
            var local = transform.inversePosition(worldPosition);
            double extent = Math.max(Math.abs(local.x), Math.max(Math.abs(local.y), Math.abs(local.z)));
            if (extent >= 50) return outside;
            float delta = (float)(extent - 50);
            return PhysicsFieldNodes.scale(magnitude, minimum, maximum, falloff, -delta / 50);
        }

        @Override public void pack(PhysicsFieldGraph graph, float creationTime) {
            var position = transform.position();
            var scale = transform.scale();
            graph.node(PhysicsFieldGraph.SCALAR, 6, magnitude, minimum, maximum, outside,
                    (float)transform.qx(), (float)transform.qy(), (float)transform.qz(), (float)transform.qw(),
                    (float)position.x, (float)position.y, (float)position.z,
                    (float)scale.x, (float)scale.y, (float)scale.z, falloff.ordinal());
        }
    }

    public record NoiseScalar(float minimum, float maximum, PhysicsFieldTransform transform) implements ScalarNode, PackedNode {
        public NoiseScalar { Objects.requireNonNull(transform); }

        @Override public float evaluate(Vec3 worldPosition, float age, float previous) {
            Vec3 local = transform.inversePosition(worldPosition);
            float noise = ChaosFieldPerlin.sample(lattice(local.x), lattice(local.y), lattice(local.z));
            return minimum + (maximum - minimum) * (0.5f * (noise + 1));
        }

        @Override public void pack(PhysicsFieldGraph graph, float creationTime) {
            var position = transform.position();
            var scale = transform.scale();
            graph.node(PhysicsFieldGraph.SCALAR, 7, minimum, maximum,
                    (float)transform.qx(), (float)transform.qy(), (float)transform.qz(), (float)transform.qw(),
                    (float)position.x, (float)position.y, (float)position.z, (float)scale.x, (float)scale.y, (float)scale.z);
        }

        private static float lattice(double coordinate) { return (float)((coordinate % 1 * 0.5 + 0.5) * 255); }
    }

    public record WaveScalar(float magnitude, Vec3 center, float wavelength, float period,
                             WaveFunction function, Falloff falloff) implements ScalarNode, PackedNode {
        public WaveScalar {
            Objects.requireNonNull(center);
            Objects.requireNonNull(function);
            Objects.requireNonNull(falloff);
        }

        @Override public float evaluate(Vec3 worldPosition, float age, float previous) {
            double elapsed = Math.max(age, 0);
            if (function == WaveFunction.DECAY) {
                double decay = elapsed / period;
                return magnitude * (float)Math.exp(-decay * decay);
            }
            float waveNumber = wavelength != 0 ? 2 * (float)Math.PI / wavelength : 0;
            double radius = wavelength * elapsed / period;
            double distance = (float)center.distanceTo(worldPosition);
            double fraction = 1 - distance / radius;
            double phase = -waveNumber * radius * fraction;
            return switch (function) {
                case COSINE -> magnitude * (float)Math.cos(phase);
                case GAUSSIAN -> magnitude * (float)Math.exp(-phase * phase);
                case FALLOFF -> distance < radius && radius > 0 ? waveFalloff(fraction) : 0;
                case DECAY -> throw new AssertionError(function);
            };
        }

        private float waveFalloff(double fraction) {
            return switch (falloff) {
                case NONE -> magnitude;
                case LINEAR -> magnitude * (float)fraction;
                case SQUARED -> magnitude * (float)(fraction * fraction);
                case INVERSE -> magnitude * 2 * (1 - 1 / (float)(fraction + 1));
                case LOGARITHMIC -> magnitude * falloff.evaluate((float)fraction);
            };
        }

        @Override public void pack(PhysicsFieldGraph graph, float creationTime) {
            graph.node(PhysicsFieldGraph.SCALAR, 15, magnitude, (float)center.x, (float)center.y, (float)center.z,
                    wavelength, period, creationTime, function.ordinal(), falloff.ordinal());
        }
    }

    public record UniformVector(float magnitude, Vec3 direction) implements VectorNode, PackedNode {
        public UniformVector { Objects.requireNonNull(direction); }
        @Override public Vec3 evaluate(Vec3 worldPosition, float age, Vec3 previous) { return direction.scale(magnitude); }
        @Override public void pack(PhysicsFieldGraph graph, float creationTime) {
            graph.node(PhysicsFieldGraph.VECTOR, 8, magnitude, (float)direction.x, (float)direction.y, (float)direction.z);
        }
    }

    public record RadialVector(float magnitude, Vec3 center) implements VectorNode, PackedNode {
        public RadialVector { Objects.requireNonNull(center); }
        @Override public Vec3 evaluate(Vec3 worldPosition, float age, Vec3 previous) {
            return safeNormal(worldPosition.subtract(center)).scale(magnitude);
        }
        @Override public void pack(PhysicsFieldGraph graph, float creationTime) {
            graph.node(PhysicsFieldGraph.VECTOR, 9, magnitude, (float)center.x, (float)center.y, (float)center.z);
        }
    }

    public record RandomVector(float magnitude) implements VectorNode, PackedNode {
        @Override public Vec3 evaluate(Vec3 worldPosition, float age, Vec3 previous) {
            var random = WindowsSourceRandom.current();
            Vec3 direction = new Vec3(random.signedUnit(), random.signedUnit(), random.signedUnit());
            double squared = direction.lengthSqr();
            if (squared > SMALL_NUMBER) direction = direction.scale(1 / Math.sqrt(squared));
            return direction.scale(magnitude);
        }

        @Override public void pack(PhysicsFieldGraph graph, float creationTime) {
            graph.node(PhysicsFieldGraph.VECTOR, 10, magnitude);
        }
    }

    public record RotatedRadialVector(float magnitude, Vec3 center, float degrees, Vec3 axis) implements VectorNode, PackedNode {
        public RotatedRadialVector { Objects.requireNonNull(center); Objects.requireNonNull(axis); }

        @Override public Vec3 evaluate(Vec3 worldPosition, float age, Vec3 previous) {
            double halfAngle = (degrees * ((float)Math.PI / 180)) * 0.5;
            Vec3 rotation = safeNormal(axis).scale(Math.sin(halfAngle));
            Vec3 direction = safeNormal(worldPosition.subtract(center));
            Vec3 cross = rotation.cross(direction).scale(2);
            return direction.add(cross.scale(Math.cos(halfAngle))).add(rotation.cross(cross)).scale(magnitude);
        }

        @Override public void pack(PhysicsFieldGraph graph, float creationTime) {
            graph.node(PhysicsFieldGraph.VECTOR, 17, magnitude, (float)center.x, (float)center.y, (float)center.z,
                    degrees, (float)axis.x, (float)axis.y, (float)axis.z);
        }
    }

    private static Vec3 safeNormal(Vec3 vector) {
        double squared = vector.lengthSqr();
        return squared < SMALL_NUMBER ? Vec3.ZERO : squared == 1 ? vector : vector.scale(1 / Math.sqrt(squared));
    }

    private static float scale(float magnitude, float minimum, float maximum, Falloff falloff, float value) {
        return magnitude * (minimum + (maximum - minimum) * falloff.evaluate(value));
    }
}
