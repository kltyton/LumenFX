package com.kltyton.lumenfx.api.client.physics;

import java.util.List;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * One world Physics Field publication. Positions use Unreal axes and centimeters. Values retain the source target units.
 * Evaluators capture immutable scene data and may run concurrently on simulation workers.
 */
public record NiagaraPhysicsField(float worldSeconds, @Nullable Clipmap clipmap, List<Command> commands,
                                  @Nullable PhysicsFieldGpuData gpu) {
    public NiagaraPhysicsField(float worldSeconds, @Nullable Clipmap clipmap, List<Command> commands) {
        this(worldSeconds, clipmap, commands, null);
    }

    public NiagaraPhysicsField {
        if (!Float.isFinite(worldSeconds)) throw new IllegalArgumentException("Non-finite physics field time");
        commands = List.copyOf(commands);
        if (gpu != null) gpu.requireResolution(Objects.requireNonNull(clipmap, "Chaos GPU field metadata").resolution());
    }

    public record Clipmap(Vec3 center, float distance, int resolution) {
        public Clipmap {
            Objects.requireNonNull(center);
            if (!Double.isFinite(center.x) || !Double.isFinite(center.y) || !Double.isFinite(center.z)
                    || !Float.isFinite(distance) || distance <= 0 || resolution < 2)
                throw new IllegalArgumentException("Invalid physics field clipmap");
        }
    }

    public enum VectorTarget {
        LINEAR_FORCE(2), LINEAR_VELOCITY(5), ANGULAR_VELOCITY(6), ANGULAR_TORQUE(7), POSITION_TARGET(13),
        INITIAL_LINEAR_VELOCITY(17), INITIAL_ANGULAR_VELOCITY(18), LINEAR_IMPULSE(19);
        private final int physicsType;
        VectorTarget(int physicsType) { this.physicsType = physicsType; }
        public int physicsType() { return physicsType; }
    }
    public enum ScalarTarget {
        EXTERNAL_CLUSTER_STRAIN(3), KILL(4), DISABLE_THRESHOLD(9), SLEEPING_THRESHOLD(10),
        INTERNAL_CLUSTER_STRAIN(8), DYNAMIC_CONSTRAINT(14);
        private final int physicsType;
        ScalarTarget(int physicsType) { this.physicsType = physicsType; }
        public int physicsType() { return physicsType; }
    }
    public enum IntegerTarget {
        DYNAMIC_STATE(1), ACTIVATE_DISABLED(16), COLLISION_GROUP(15), POSITION_ANIMATED(12), POSITION_STATIC(11);
        private final int physicsType;
        IntegerTarget(int physicsType) { this.physicsType = physicsType; }
        public int physicsType() { return physicsType; }
    }

    public sealed interface Command permits VectorCommand, ScalarCommand, IntegerCommand {
        float createdAt();
        int physicsType();
    }
    @FunctionalInterface public interface VectorNode {
        Vec3 evaluate(Vec3 worldPosition, float age, Vec3 previous);
        default Vec3 evaluateInScope(Vec3 worldPosition, float age, Vec3 previous, Vec3 enclosingResults) {
            return evaluate(worldPosition, age, previous);
        }
    }
    @FunctionalInterface public interface ScalarNode {
        float evaluate(Vec3 worldPosition, float age, float previous);
        default float evaluateInScope(Vec3 worldPosition, float age, float previous, float enclosingResults) {
            return evaluate(worldPosition, age, previous);
        }
    }
    @FunctionalInterface public interface IntegerNode {
        int evaluate(Vec3 worldPosition, float age, int previous);
    }
    public record VectorCommand(VectorTarget target, float createdAt, VectorNode root) implements Command {
        public VectorCommand { Objects.requireNonNull(target); Objects.requireNonNull(root); requireTime(createdAt); }
        @Override public int physicsType() { return target.physicsType(); }
    }
    public record ScalarCommand(ScalarTarget target, float createdAt, ScalarNode root) implements Command {
        public ScalarCommand { Objects.requireNonNull(target); Objects.requireNonNull(root); requireTime(createdAt); }
        @Override public int physicsType() { return target.physicsType(); }
    }
    public record IntegerCommand(IntegerTarget target, float createdAt, IntegerNode root) implements Command {
        public IntegerCommand { Objects.requireNonNull(target); Objects.requireNonNull(root); requireTime(createdAt); }
        @Override public int physicsType() { return target.physicsType(); }
    }

    private static void requireTime(float seconds) {
        if (!Float.isFinite(seconds)) throw new IllegalArgumentException("Non-finite physics field creation time");
    }
}
