package com.kltyton.lumenfx.api.client.physics;

import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.Clipmap;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.Command;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.IntegerCommand;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.ScalarCommand;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.VectorCommand;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.Arrays;
import java.util.List;

/** Packs the native postfix Chaos node stream from the same graph used by the CPU. */
public final class PhysicsFieldGraph {
    static final int INTEGER = 2, SCALAR = 3, VECTOR = 4;
    static final int SUM_SCALAR = 11, SUM_VECTOR = 12, CONVERSION = 13, CULLING = 14;
    private static final int ADD = 2;

    interface PackedNode {
        void pack(PhysicsFieldGraph graph, float creationTime);
    }

    private final FloatArrayList parameters = new FloatArrayList();
    private final IntArrayList offsets = new IntArrayList();

    private PhysicsFieldGraph() { }

    /** Publishes analytic GPU field sampling with a disabled clipmap. */
    public static NiagaraPhysicsField analytic(float time, Clipmap geometry, List<Command> commands) {
        return publication(time, geometry, new AuthoredPhysicsFieldComponent.Dispatch(commands, commands), 2, 4, false);
    }

    /** Publishes a graph whose clipmap is evaluated on the device with the fixed Unreal field builder. */
    public static NiagaraPhysicsField clipmapped(float time, Clipmap geometry, List<Command> commands, int exponent, int levels) {
        return publication(time, geometry, new AuthoredPhysicsFieldComponent.Dispatch(commands, commands), exponent, levels, true);
    }

    /** Compiles separately dispatched CPU Chaos and GPU world command lists into a single world publication. */
    public static NiagaraPhysicsField publication(float time, Clipmap geometry, AuthoredPhysicsFieldComponent.Dispatch commands,
                                                   int exponent, int levels, boolean clipmapped) {
        var graph = new PhysicsFieldGraph();
        int[] targets = new int[PhysicsFieldGpuData.PHYSICS_TYPES + 1];
        int[] targetAttributes = new int[PhysicsFieldGpuData.TARGET_ROWS * 4];
        for (int row = 0; row < targetAttributes.length; row += 4) Arrays.fill(targetAttributes, row, row + 3, -1);
        int attributes = 0;
        for (int target = 0; target < PhysicsFieldGpuData.PHYSICS_TYPES; target++) {
            int count = 0;
            for (Command command : commands.gpu()) if (command.physicsType() == target) {
                if (count == 0 && clipmapped) attributes += assignAttribute(command, attributes, targetAttributes);
                graph.append(command, count++);
            }
            targets[target + 1] = graph.offsets.size();
        }
        var data = new PhysicsFieldGpuData(exponent, levels, attributes, targetAttributes, graph.parameters.toFloatArray(),
                graph.offsets.toIntArray(), targets, new float[0], clipmapped && attributes > 0);
        return new NiagaraPhysicsField(time, geometry, commands.cpu(), data);
    }

    private static int assignAttribute(Command command, int firstAttribute, int[] offsets) {
        return switch (command) {
            case VectorCommand vector -> {
                offsets[vector.target().ordinal() * 4] = firstAttribute;
                yield 3;
            }
            case ScalarCommand scalar -> {
                offsets[scalar.target().ordinal() * 4 + 1] = firstAttribute;
                yield 1;
            }
            case IntegerCommand integer -> {
                offsets[integer.target().ordinal() * 4 + 2] = firstAttribute;
                yield 1;
            }
        };
    }

    void node(int type, int operation, float... arguments) {
        offsets.add(parameters.size());
        parameters.add(type);
        parameters.add(operation);
        for (float argument : arguments) parameters.add(argument);
    }

    void child(Object root, float creationTime) {
        if (root == null) return;
        if (!(root instanceof PackedNode node))
            throw new IllegalArgumentException("Physics field node does not provide its Chaos GPU layout");
        node.pack(this, creationTime);
    }

    private void append(Command command, int previous) {
        var root = switch (command) {
            case VectorCommand vector -> vector.root();
            case ScalarCommand scalar -> scalar.root();
            case IntegerCommand integer -> integer.root();
        };
        boolean integer = command instanceof IntegerCommand;
        if (integer && previous == 1) node(SCALAR, CONVERSION, 1);
        child(root, command.createdAt());
        if (previous == 0) return;
        if (command instanceof VectorCommand) node(VECTOR, SUM_VECTOR, 1, 0, 1, 1, ADD);
        else {
            if (integer) node(SCALAR, CONVERSION, 1);
            node(SCALAR, SUM_SCALAR, 1, 1, 1, ADD);
        }
    }
}
