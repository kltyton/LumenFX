package com.kltyton.lumenfx.client.simulation.niagara.interfaces.spline;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSplineInputs;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSplineInputs.Actor;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSplineInputs.Source;
import com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices;
import com.kltyton.lumenfx.effect.curve.NiagaraSplineCurves;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.function.IntBinaryOperator;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3d;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/** Per-system Niagara spline state with separate CPU and render-proxy publications. */
public final class NiagaraSpline {
    public enum Mode { Default, AttachParentOnly, ParameterBindingOnly }
    public record Definition(Mode mode, String parameter, String actor, boolean useLut, int steps, @Nullable Actor savedActor) {
        public Definition(Mode mode, String parameter, String actor, boolean useLut, int steps) {
            this(mode, parameter, actor, useLut, steps, null);
        }
        public Definition {
            if (steps < 0) throw new IllegalArgumentException("Invalid Niagara spline LUT step count");
        }
        public static Definition read(JsonObject value) {
            var saved = value.get("saved_actor");
            return new Definition(Mode.valueOf(value.get("mode").getAsString()), value.get("parameter").getAsString(),
                    value.get("actor").getAsString(), value.get("use_lut").getAsBoolean(), value.get("lut_steps").getAsInt(),
                    saved == null || saved.isJsonNull() ? null : NiagaraSplineSourceJson.read(saved.getAsJsonObject()));
        }
    }
    public enum Function {
        SampleSplinePositionByUnitDistance, SampleSplinePositionByUnitDistanceWS,
        SampleSplineRotationByUnitDistance, SampleSplineRotationByUnitDistanceWS,
        SampleSplineUpVectorByUnitDistance, SampleSplineUpVectorByUnitDistanceWS,
        SampleSplineRightVectorByUnitDistance, SampleSplineRightVectorByUnitDistanceWS,
        SampleSplineDirectionByUnitDistance, SampleSplineDirectionByUnitDistanceWS,
        SampleSplineTangentByUnitDistance, SampleSplineTangentByUnitDistanceWS,
        FindClosestUnitDistanceFromPositionWS, GetSplineLocalToWorld, GetSplineLocalToWorldInverseTransposed, GetSplineLength
    }
    @FunctionalInterface public interface Output { void set(int word, int lane, int value); }
    private static final Vec3 UP = new Vec3(0, 0, 1), RIGHT = new Vec3(0, 1, 0), FORWARD = new Vec3(1, 0, 0);
    public record Transform(Matrix4dc world, Matrix4dc rotationMatrix, Matrix4dc inverse, Matrix4dc inverseTranspose,
                            Quaterniondc rotation, Vec3 up) { }
    public record GpuFrame(NiagaraSplineLut lut, Transform transform) { }
    private record CpuFrame(boolean valid, boolean useLut, NiagaraSplineCurves curves, NiagaraSplineLut lut, Transform transform) { }
    private static final Transform IDENTITY = new Transform(new Matrix4d(), new Matrix4d(), new Matrix4d(),
            new Matrix4d(), new Quaterniond(), UP);
    private final Definition definition;
    private boolean gpu;
    private @Nullable Source selected;
    private volatile CpuFrame cpu = new CpuFrame(false, false, NiagaraSplineCurves.EMPTY, NiagaraSplineLut.EMPTY, IDENTITY);
    private GpuFrame render = new GpuFrame(NiagaraSplineLut.EMPTY, IDENTITY);

    public NiagaraSpline(Definition definition) { this.definition = definition; }
    public void enableGpu() { gpu = true; }
    public String parameter() { return definition.parameter(); }
    public GpuFrame gpuFrame() { return render; }

    public void capture(NiagaraSplineInputs inputs, @Nullable String savedObject, @Nullable Actor savedParameter,
                        boolean disableCpuLut, double x, double y, double z) {
        capture(inputs, savedObject, savedParameter, disableCpuLut, true, x, y, z);
    }

    public void capture(NiagaraSplineInputs inputs, @Nullable String savedObject, @Nullable Actor savedParameter,
                        boolean disableCpuLut, boolean useSplineCurves, double x, double y, double z) {
        capture(inputs, savedObject, savedParameter, false, disableCpuLut, useSplineCurves, x, y, z);
    }

    public void capture(NiagaraSplineInputs inputs, @Nullable String boundObjectPath, @Nullable Actor savedParameter,
                        boolean objectParameterOverridden, boolean disableCpuLut, boolean useSplineCurves,
                        double x, double y, double z) {
        Source next = select(inputs, boundObjectPath, savedParameter, objectParameterOverridden, useSplineCurves);
        var previous = selected;
        selected = next;
        var old = cpu;
        boolean useLut = definition.useLut() && !disableCpuLut;
        if (next == null) {
            cpu = new CpuFrame(false, useLut, NiagaraSplineCurves.EMPTY, NiagaraSplineLut.EMPTY, IDENTITY);
            if (gpu) render = new GpuFrame(NiagaraSplineLut.EMPTY, IDENTITY);
            return;
        }
        boolean changed = previous == null || !previous.identity().equals(next.identity())
                || previous.version() != next.version() || previous.curves() != next.curves();
        var lut = old.lut();
        var transform = transform(next, x, y, z);
        if ((gpu || definition.useLut()) && (changed || lut.maxIndex() < 0)) {
            lut = new NiagaraSplineLut(next.curves(), definition.useLut() ? definition.steps() : 256);
        }
        if (gpu) render = new GpuFrame(lut, transform);
        cpu = new CpuFrame(true, useLut, next.curves(), lut, transform);
    }

    private @Nullable Source select(NiagaraSplineInputs inputs, @Nullable String boundObjectPath, @Nullable Actor savedParameter,
                                    boolean objectParameterOverridden, boolean useSplineCurves) {
        Mode mode = definition.mode();
        String parameter = definition.parameter();
        if (mode != Mode.AttachParentOnly && !parameter.isBlank() && !SourceNames.equal(parameter, "None")) {
            // An explicit UObject assignment, including null, replaces a source published by parameter name.
            if (!objectParameterOverridden) {
                var source = inputs.parameters().get(parameter);
                if (source != null) return source;
            }
            Actor actor = boundObjectPath == null ? null : inputs.actors().get(boundObjectPath);
            if (actor == null) actor = savedParameter;
            if (actor != null) {
                var source = actor.firstSpline(useSplineCurves);
                if (source != null) return source;
            }
        }
        if (mode == Mode.Default) {
            var actor = inputs.actors().get(definition.actor());
            if (actor == null) actor = definition.savedActor();
            if (actor != null) return actor.firstSpline(useSplineCurves);
        }
        return mode == Mode.ParameterBindingOnly ? null : inputs.attachmentRoot();
    }

    private static Transform transform(Source source, double x, double y, double z) {
        var component = source.transform();
        var rotation = new Quaterniond(component.qx(), component.qy(), component.qz(), component.qw());
        var world = NiagaraComponentMatrices.srt(rotation.x, rotation.y, rotation.z, rotation.w,
                component.scaleX(), component.scaleY(), component.scaleZ(),
                component.x() - x, component.y() - y, component.z() - z, new Matrix4d());
        var rotationMatrix = new Matrix4d(world).m30(0).m31(0).m32(0);
        for (int column = 0; column < 3; column++) {
            double size = 0;
            for (int row = 0; row < 3; row++) {
                double value = rotationMatrix.get(column, row);
                size += value * value;
            }
            if (size > 1e-8) {
                double scale = 1 / Math.sqrt(size);
                for (int row = 0; row < 3; row++) rotationMatrix.set(column, row, rotationMatrix.get(column, row) * scale);
            }
        }
        var inverse = NiagaraComponentMatrices.inverse(world, new Matrix4d());
        return new Transform(world, rotationMatrix, inverse, new Matrix4d(inverse).transpose(), rotation, source.defaultUp());
    }

    public void execute(Function function, int count, IntBinaryOperator input, Output output) {
        var frame = cpu;
        boolean world = switch (function) {
            case SampleSplinePositionByUnitDistanceWS, SampleSplineRotationByUnitDistanceWS, SampleSplineUpVectorByUnitDistanceWS,
                    SampleSplineRightVectorByUnitDistanceWS, SampleSplineDirectionByUnitDistanceWS, SampleSplineTangentByUnitDistanceWS -> true;
            default -> false;
        };
        for (int lane = 0; lane < count; lane++) {
            switch (function) {
                case GetSplineLocalToWorld -> write(frame.transform().world(), lane, output);
                case GetSplineLocalToWorldInverseTransposed -> write(frame.transform().inverseTranspose(), lane, output);
                case GetSplineLength -> output.set(0, lane, bits(frame.valid()
                        ? (definition.useLut() ? frame.lut().length() : frame.curves().length()) : 0));
                case FindClosestUnitDistanceFromPositionWS -> output.set(0, lane, bits(nearest(frame, input, lane)));
                default -> sample(function, frame, world, Float.intBitsToFloat(input.applyAsInt(0, lane)), lane, output);
            }
        }
    }

    private static float nearest(CpuFrame frame, IntBinaryOperator input, int lane) {
        if (!frame.valid()) return 0;
        var pos = new Vec3(Float.intBitsToFloat(input.applyAsInt(0, lane)), Float.intBitsToFloat(input.applyAsInt(1, lane)),
                Float.intBitsToFloat(input.applyAsInt(2, lane)));
        var point = position(frame.transform().inverse(), pos);
        if (frame.useLut()) return frame.lut().nearest(point);
        float finalTime = frame.curves().finalKeyTime();
        return finalTime > 0 ? frame.curves().nearestPositionTime(point) / finalTime : 0;
    }

    private static void sample(Function function, CpuFrame frame, boolean world, float u, int lane, Output output) {
        var transform = frame.transform();
        var curves = frame.curves();
        var lut = frame.lut();
        boolean lookup = frame.useLut();
        float distance = u * (lookup ? lut.length() : curves.length());
        float key = lookup ? distance : curves.timeAtDistance(distance);
        switch (function) {
            case SampleSplinePositionByUnitDistance, SampleSplinePositionByUnitDistanceWS -> {
                var local = frame.valid() ? (lookup ? lut.position(key) : curves.position(key)) : Vec3.ZERO;
                write(world ? position(transform.world(), local) : local, lane, output);
            }
            case SampleSplineRotationByUnitDistance, SampleSplineRotationByUnitDistanceWS -> {
                var rotation = frame.valid() ? orientation(frame, key) : new Quaterniond();
                if (frame.valid() && world) rotation = new Quaterniond(transform.rotation()).mul(rotation);
                output.set(0, lane, bits(rotation.x)); output.set(1, lane, bits(rotation.y));
                output.set(2, lane, bits(rotation.z)); output.set(3, lane, bits(rotation.w));
            }
            case SampleSplineUpVectorByUnitDistance, SampleSplineUpVectorByUnitDistanceWS,
                    SampleSplineRightVectorByUnitDistance, SampleSplineRightVectorByUnitDistanceWS -> {
                boolean up = function == Function.SampleSplineUpVectorByUnitDistance || function == Function.SampleSplineUpVectorByUnitDistanceWS;
                Vec3 local = frame.valid()
                    ? vec(orientation(frame, key).transform(new Vector3d(up ? UP.x : RIGHT.x, up ? UP.y : RIGHT.y, up ? UP.z : RIGHT.z)))
                    : (up ? UP : new Vec3(-1.0, 0.0, 0.0));
                write(world ? worldUnit(transform.world(), local) : local, lane, output);
            }
            case SampleSplineDirectionByUnitDistance, SampleSplineDirectionByUnitDistanceWS,
                    SampleSplineTangentByUnitDistance, SampleSplineTangentByUnitDistanceWS -> {
                boolean direction = function == Function.SampleSplineDirectionByUnitDistance || function == Function.SampleSplineDirectionByUnitDistanceWS;
                var local = frame.valid() ? (lookup ? lut.tangent(key) : curves.tangent(key)) : direction ? RIGHT : Vec3.ZERO;
                if (direction && frame.valid()) local = unit(local);
                write(world && (frame.valid() || direction) ? worldUnit(transform.world(), local) : local, lane, output);
            }
            default -> throw new IllegalArgumentException("Invalid spline sample " + function);
        }
    }

    private static Quaterniond orientation(CpuFrame frame, float key) {
        var local = frame.useLut() ? frame.lut().position(key) : frame.curves().position(key);
        var x = unit(local);
        var up = frame.transform().up();
        var authored = frame.useLut() ? frame.lut().rotation(key) : frame.curves().rotation(key);
        var normal = unit(vec(authored.transform(new Vector3d(up.x, up.y, up.z))));
        if (Math.abs(Math.abs(x.dot(normal)) - 1) < .0001) normal = Math.abs(x.z) < .9999 ? UP : FORWARD;
        var y = unit(normal.cross(x));
        if (x.lengthSqr() == 0 || y.lengthSqr() == 0) return new Quaterniond();
        var z = x.cross(y);
        return new Quaterniond().setFromNormalized(new Matrix3d(x.x, x.y, x.z, y.x, y.y, y.z, z.x, z.y, z.z));
    }

    private static Vec3 position(Matrix4dc matrix, Vec3 v) { return vec(matrix.transformPosition(new Vector3d(v.x, v.y, v.z))); }
    private static Vec3 worldUnit(Matrix4dc matrix, Vec3 v) {
        var transformed = matrix.transformDirection(new Vector3d(v.x, v.y, v.z));
        return unit(vec(transformed));
    }
    private static Vec3 vec(Vector3d v) { return new Vec3(v.x, v.y, v.z); }
    private static Vec3 unit(Vec3 v) { double length = v.lengthSqr(); return length < 1e-8 ? Vec3.ZERO : v.scale(1 / Math.sqrt(length)); }
    private static int bits(double value) { return Float.floatToRawIntBits((float)value); }
    private static void write(Vec3 v, int lane, Output output) {
        output.set(0, lane, bits(v.x)); output.set(1, lane, bits(v.y)); output.set(2, lane, bits(v.z));
    }
    private static void write(Matrix4dc matrix, int lane, Output output) {
        for (int column = 0; column < 4; column++)
            for (int row = 0; row < 4; row++) output.set(column * 4 + row, lane, bits(matrix.get(column, row)));
    }
}
