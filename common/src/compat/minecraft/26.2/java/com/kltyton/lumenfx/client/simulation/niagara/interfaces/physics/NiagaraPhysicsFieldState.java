package com.kltyton.lumenfx.client.simulation.niagara.interfaces.physics;

import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.Clipmap;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.IntegerCommand;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.ScalarCommand;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.VectorCommand;
import com.kltyton.lumenfx.api.client.physics.PhysicsFieldBatch;
import java.util.Arrays;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** The CPU DI reads the commands published at the previous post-simulate boundary. */
public final class NiagaraPhysicsFieldState {
    public enum Operation {
        SamplePhysicsVectorField, SamplePhysicsScalarField, SamplePhysicsIntegerField,
        EvalPhysicsVectorField, EvalPhysicsScalarField, EvalPhysicsIntegerField,
        GetPhysicsFieldResolution, GetPhysicsFieldBounds
    }
    @FunctionalInterface public interface Inputs { int word(int register, int lane); }
    @FunctionalInterface public interface Outputs { void word(int register, int lane, int value); }

    private boolean initialized, hasFieldResource;
    private @Nullable Clipmap clipmap;
    private float time;
    private List<NiagaraPhysicsField.Command> commands = List.of();
    private double originX, originY, originZ;

    public void begin(@Nullable NiagaraPhysicsField world, double originX, double originY, double originZ) {
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        if (!initialized) {
            initialized = true;
            hasFieldResource = world != null && world.clipmap() != null;
            if (world != null) time = world.worldSeconds();
        }
        clipmap = hasFieldResource && world != null ? world.clipmap() : null;
    }

    public void postSimulate(@Nullable NiagaraPhysicsField world) {
        if (world == null) {
            commands = List.of();
            return;
        }
        time = world.worldSeconds();
        commands = world.commands();
    }

    public @Nullable NiagaraPhysicsField renderField(@Nullable NiagaraPhysicsField world) {
        return hasFieldResource ? world : null;
    }

    public void clear() {
        initialized = false;
        hasFieldResource = false;
        clipmap = null;
        time = 0;
        originX = originY = originZ = 0;
        commands = List.of();
    }

    public void execute(Operation operation, int count, Inputs inputs, Outputs outputs) {
        if (count == 0) return;
        switch (operation) {
            case GetPhysicsFieldResolution -> {
                int resolution = clipmap == null ? 1 : clipmap.resolution();
                for (int lane = 0; lane < count; lane++) vector(outputs, 0, lane, resolution, resolution, resolution);
            }
            case GetPhysicsFieldBounds -> {
                Vec3 center = clipmap == null ? Vec3.ZERO : clipmap.center();
                double distance = clipmap == null ? 0 : clipmap.distance();
                for (int lane = 0; lane < count; lane++) {
                    vector(outputs, 0, lane, center.x - distance, center.y - distance, center.z - distance);
                    vector(outputs, 3, lane, center.x + distance, center.y + distance, center.z + distance);
                }
            }
            case SamplePhysicsVectorField, EvalPhysicsVectorField -> sampleVector(count, inputs, outputs);
            case SamplePhysicsScalarField, EvalPhysicsScalarField -> sampleScalar(count, inputs, outputs);
            case SamplePhysicsIntegerField, EvalPhysicsIntegerField -> sampleInteger(count, inputs, outputs);
        }
    }

    private Vec3[] positions(Inputs input, int count) {
        var positions = new Vec3[count];
        for (int lane = 0; lane < count; lane++)
            positions[lane] = new Vec3(originX + Float.intBitsToFloat(input.word(0, lane)),
                    originY + Float.intBitsToFloat(input.word(1, lane)), originZ + Float.intBitsToFloat(input.word(2, lane)));
        return positions;
    }

    private void sampleVector(int count, Inputs input, Outputs output) {
        // Fixed UE uses the final target register for every lane and applies each command to the entire batch.
        int target = input.word(3, count - 1);
        Vec3[] positions = positions(input, count), previous = new Vec3[count], sum = new Vec3[count];
        Arrays.fill(previous, Vec3.ZERO);
        Arrays.fill(sum, Vec3.ZERO);
        for (var command : commands) if (command instanceof VectorCommand vector && vector.target().ordinal() == target) {
            PhysicsFieldBatch.evaluate(vector.root(), positions, time - vector.createdAt(), previous);
            for (int lane = 0; lane < count; lane++) sum[lane] = sum[lane].add(previous[lane]);
        }
        for (int lane = 0; lane < count; lane++) vector(output, 0, lane, sum[lane].x, sum[lane].y, sum[lane].z);
    }

    private void sampleScalar(int count, Inputs input, Outputs output) {
        int target = input.word(3, count - 1);
        Vec3[] positions = positions(input, count);
        float[] previous = new float[count], sum = new float[count];
        for (var command : commands) if (command instanceof ScalarCommand scalar && scalar.target().ordinal() == target) {
            PhysicsFieldBatch.evaluate(scalar.root(), positions, time - scalar.createdAt(), previous);
            for (int lane = 0; lane < count; lane++) sum[lane] += previous[lane];
        }
        for (int lane = 0; lane < count; lane++) output.word(0, lane, Float.floatToRawIntBits(sum[lane]));
    }

    private void sampleInteger(int count, Inputs input, Outputs output) {
        int target = input.word(3, count - 1);
        Vec3[] positions = positions(input, count);
        int[] previous = new int[count], sum = new int[count];
        for (var command : commands) if (command instanceof IntegerCommand integer && integer.target().ordinal() == target) {
            PhysicsFieldBatch.evaluate(integer.root(), positions, time - integer.createdAt(), previous);
            for (int lane = 0; lane < count; lane++) sum[lane] += previous[lane];
        }
        for (int lane = 0; lane < count; lane++) output.word(0, lane, sum[lane]);
    }

    private static void vector(Outputs output, int start, int lane, double x, double y, double z) {
        output.word(start, lane, Float.floatToRawIntBits((float)x));
        output.word(start + 1, lane, Float.floatToRawIntBits((float)y));
        output.word(start + 2, lane, Float.floatToRawIntBits((float)z));
    }
}
