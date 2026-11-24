package com.kltyton.lumenfx.client.simulation.niagara.stateless.modules;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessParticleSimulation;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessRange;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.math.NativeStatelessMath;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.shader.StatelessShaderFields;

/** Native scalar integration consumes the source solver's captured force state and this emitter's SoA buffers. */
public final class PhysicsModule implements PreparedShaderModule {
    private final StatelessRange mass, drag, linear, linearScale, wind, acceleration, gravity,
            coneRotation, coneDirection, coneVelocity, coneScale, pointVelocity, pointScale, noiseOffset;
    private final boolean coneUsesRotator;
    private final float[] input = new float[69], prepared = new float[60];
    private final int[] controls = new int[9], channels;
    private final int[][] required = new int[6][], components = new int[12][];

    public PhysicsModule(JsonObject definition, JsonObject ownerScale) {
        JsonObject source = definition.getAsJsonObject("physics");
        channels = definition.getAsJsonArray("channels").asList().stream().mapToInt(value -> value.getAsInt()).toArray();
        if (channels.length != 4) throw new IllegalArgumentException("Invalid stateless physics channels");
        mass = range(source, "mass", 1); drag = range(source, "drag", 1);
        linear = range(source, "linear_velocity", 3); linearScale = range(source, "linear_scale", 1);
        wind = range(source, "wind", 3); acceleration = range(source, "acceleration", 3); gravity = range(source, "gravity", 3);
        coneRotation = range(source, "cone_rotator", 3); coneDirection = range(source, "cone_direction", 3);
        coneVelocity = range(source, "cone_velocity", 1); coneScale = range(source, "cone_scale", 1);
        pointVelocity = range(source, "point_velocity", 1); pointScale = range(source, "point_scale", 1);
        noiseOffset = range(source, "noise_offset", 3);
        controls[0] = space(source, "linear_space"); controls[1] = space(source, "wind_space"); controls[2] = space(source, "acceleration_space");
        controls[3] = space(source, "cone_space"); controls[4] = space(source, "point_space");
        coneUsesRotator = source.get("cone_use_rotator").getAsBoolean(); controls[5] = coneUsesRotator ? 1 : 0;
        controls[6] = enabled(ownerScale, "bScaleForces"); controls[7] = enabled(ownerScale, "bScaleForcesInverseScaling");
        controls[8] = enabled(ownerScale, "bScaleInitialVelocity");
        input[28] = source.get("cone_enabled").getAsBoolean() ? 1f : 0f;
        input[33] = source.get("cone_inner_angle").getAsFloat(); input[34] = source.get("cone_outer_angle").getAsFloat();
        input[37] = source.get("cone_falloff").getAsFloat(); input[38] = source.get("point_enabled").getAsBoolean() ? 1f : 0f;
        for (int component = 0; component < 3; component++) input[39 + component] = source.getAsJsonArray("point_origin").get(component).getAsFloat();
        input[44] = source.get("noise_enabled").getAsBoolean() ? 1f : 0f;
        input[45] = source.get("noise_strength").getAsFloat(); input[46] = source.get("noise_frequency").getAsFloat();
        vector(ownerScale, "ScaleForcesScaleAmount", input, 63); vector(ownerScale, "ScaleInitialVelocityScaleAmount", input, 66);
        if (input[44] != 0f) NativeStatelessMath.initializeNoise();
    }

    @Override public void prepare(int[] parameters, float[] sourcePose) {
        pair(mass, parameters, 0, 1); pair(drag, parameters, 2, 3);
        pair(linear, parameters, 4, 7); pair(wind, parameters, 10, 13);
        pair(acceleration, parameters, 16, 19); pair(gravity, parameters, 22, 25);
        StatelessRange orientation = coneUsesRotator ? coneRotation : coneDirection;
        for (int component = 0; component < 3; component++) input[29 + component] = orientation.minimum(component, parameters);
        input[32] = 1f;
        pair(coneVelocity, parameters, 35, 36); pair(pointVelocity, parameters, 42, 43); pair(noiseOffset, parameters, 48, 51);
        input[60] = linearScale.minimum(0, parameters); input[61] = coneScale.minimum(0, parameters); input[62] = pointScale.minimum(0, parameters);
        NativeStatelessMath.preparePhysics(controls, input, sourcePose, prepared);
    }
    private void pair(StatelessRange range, int[] parameters, int bias, int scale) {
        for (int component = 0; component < range.width(); component++) {
            float minimum = range.minimum(component, parameters);
            input[bias + component] = minimum; input[scale + component] = range.maximum(component, parameters) - minimum;
        }
    }
    @Override public void writeShaderParameters(StatelessShaderFields fields) {
        final String prefix = "SolveVelocitiesAndForces_";
        fields.floats(prefix + "OwnerAccelerationScale", prepared, 57, 3);
        fields.floats(prefix + "OwnerVelocityScale", prepared, 54, 3);
        fields.floating(prefix + "MassScale", prepared[1]); fields.floating(prefix + "MassBias", prepared[0]);
        fields.floating(prefix + "DragScale", prepared[3]); fields.floating(prefix + "DragBias", prepared[2]);
        fields.floats(prefix + "VelocityScale", prepared, 7, 3); fields.floats(prefix + "VelocityBias", prepared, 4, 3);
        fields.floats(prefix + "WindScale", prepared, 13, 3); fields.floats(prefix + "WindBias", prepared, 10, 3);
        fields.floats(prefix + "AccelerationScale", prepared, 19, 3); fields.floats(prefix + "AccelerationBias", prepared, 16, 3);
        fields.floats(prefix + "GravityScale", prepared, 25, 3); fields.floats(prefix + "GravityBias", prepared, 22, 3);
        fields.integer(prefix + "ConeVelocityEnabled", (int)prepared[28]);
        fields.floats(prefix + "ConeQuat", prepared, 29, 4);
        fields.floating(prefix + "ConeVelocityScale", prepared[36]); fields.floating(prefix + "ConeVelocityBias", prepared[35]);
        fields.floating(prefix + "ConeAngleScale", prepared[34]); fields.floating(prefix + "ConeAngleBias", prepared[33]);
        fields.floating(prefix + "ConeVelocityFalloff", prepared[37]);
        fields.integer(prefix + "PointVelocityEnabled", (int)prepared[38]);
        fields.floating(prefix + "PointVelocityScale", prepared[43]); fields.floating(prefix + "PointVelocityBias", prepared[42]);
        fields.floats(prefix + "PointOrigin", prepared, 39, 3);
        fields.integer(prefix + "NoiseEnabled", (int)prepared[44]);
        fields.floating(prefix + "NoiseStrength", prepared[45]); fields.floating(prefix + "NoiseFrequency", prepared[46]);
        fields.floats(prefix + "NoiseFieldOffsetScale", prepared, 51, 3); fields.floats(prefix + "NoiseFieldOffsetBias", prepared, 48, 3);
        fields.floating(prefix + "NoiseLUTNormalize", prepared[47]);
        fields.integer(prefix + "NoiseLUTRowMask", 31); fields.integer(prefix + "NoiseLUTRowWidth", 64);
    }
    @Override public void simulate(StatelessParticleSimulation context) {
        required[0] = context.requiredWords(StatelessParticleSimulation.Required.UNIQUE_ID);
        required[1] = context.requiredWords(StatelessParticleSimulation.Required.LIFETIME);
        required[2] = context.requiredWords(StatelessParticleSimulation.Required.AGE);
        required[3] = context.requiredWords(StatelessParticleSimulation.Required.PREVIOUS_AGE);
        required[4] = context.requiredWords(StatelessParticleSimulation.Required.NORMALIZED_AGE);
        required[5] = context.requiredWords(StatelessParticleSimulation.Required.PREVIOUS_NORMALIZED_AGE);
        for (int component = 0; component < 3; component++) {
            components[component] = context.particleFloatWords(channels[0] < 0 ? -1 : channels[0] + component);
            components[3 + component] = context.particleFloatWords(channels[2] < 0 ? -1 : channels[2] + component);
            components[6 + component] = context.particleFloatWords(channels[1] < 0 ? -1 : channels[1] + component);
            components[9 + component] = context.particleFloatWords(channels[3] < 0 ? -1 : channels[3] + component);
        }
        NativeStatelessMath.solvePhysics(context.count(), context.emitterSeed(), context.moduleSeed(), context.deltaTime(), required, components, prepared);
    }
    private static StatelessRange range(JsonObject source, String name, int width) {
        StatelessRange result = StatelessRange.decode(source.getAsJsonObject(name));
        if (result.width() != width) throw new IllegalArgumentException("Invalid stateless physics range " + name);
        return result;
    }
    private static int enabled(JsonObject source, String name) { return source.has(name) && source.get(name).getAsBoolean() ? 1 : 0; }
    private static int space(JsonObject source, String name) {
        int value = source.get(name).getAsInt();
        if (value < 0 || value > 2) throw new IllegalArgumentException("Invalid stateless physics coordinate space");
        return value;
    }
    private static void vector(JsonObject source, String name, float[] output, int offset) {
        JsonObject vector = source.has(name) ? source.getAsJsonObject(name) : new JsonObject();
        String[] axes = { "X", "Y", "Z" };
        for (int axis = 0; axis < 3; axis++) output[offset + axis] = vector.has(axes[axis]) ? vector.get(axes[axis]).getAsFloat() : 1f;
    }
}
