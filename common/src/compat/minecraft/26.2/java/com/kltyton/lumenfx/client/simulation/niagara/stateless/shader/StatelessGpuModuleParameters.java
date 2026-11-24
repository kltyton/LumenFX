package com.kltyton.lumenfx.client.simulation.niagara.stateless.shader;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessOwnerScale;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessEmitterFrame;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessRange;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.StatelessSpawnSchedule;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.math.NativeStatelessMath;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.modules.InitialMeshOrientationModule;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.modules.PhysicsModule;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.modules.PreparedShaderModule;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.modules.ShapeLocationModule;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** The default native shader's module fields are prepared from compiled data, without parsing configuration during a tick. */
public final class StatelessGpuModuleParameters implements StatelessShaderFields {
    @FunctionalInterface private interface Preparation { void apply(int[] bindings, float[] pose); }
    @FunctionalInterface private interface Factor { float value(float[] pose); }
    private static final Factor UNIT = pose -> 1f;
    private static final int[] ZERO3 = new int[3], ZERO4 = new int[4];
    private static final float[] ONES = { 1f, 1f, 1f }, AXIS_X = { 1f, 0f, 0f }, AXIS_Y = { 0f, 1f, 0f };
    private static final Set<String> REQUIRED = Set.of("InitializeParticle", "InitialMeshOrientation", "ShapeLocation",
            "SolveVelocitiesAndForces", "CameraOffset", "DynamicMaterialParameters", "MeshIndex", "MeshRotationRate",
            "ScaleColor", "ScaleRibbonWidth", "ScaleSpriteSize", "ScaleSpriteSizeBySpeed", "ScaleMeshSize", "ScaleMeshSizeBySpeed",
            "SpriteFacingAndAlignment", "SpriteRotationRate", "SubUVAnimation");
    // PARTICLE_ATTRIBUTES in NiagaraStatelessSimulationDefault.usf defines the packed shader order.
    private static final List<String> SHADER_OUTPUT_ORDER = List.of(
            "UniqueID", "MaterialRandom", "Position", "Color", "SpriteSize", "SpriteRotation", "Scale",
            "PreviousPosition", "PreviousSpriteSize", "PreviousSpriteRotation", "PreviousScale", "RibbonWidth",
            "PreviousRibbonWidth", "MeshOrientation", "PreviousMeshOrientation", "Velocity", "PreviousVelocity",
            "CameraOffset", "PreviousCameraOffset", "DynamicMaterialParameter", "DynamicMaterialParameter1",
            "DynamicMaterialParameter2", "DynamicMaterialParameter3", "MeshIndex", "SpriteFacing", "PreviousSpriteFacing",
            "SpriteAlignment", "PreviousSpriteAlignment", "SubImageIndex");
    private final Map<String, byte[]> fields = new HashMap<>();
    private final List<Preparation> preparations = new ArrayList<>();
    private final StatelessOwnerScale ownerScale;
    private final float[] rotations = new float[12], rotation = new float[4];
    private final int[] spawnActive = new int[8], spawnOffsets = new int[8], spawnIds = new int[8];
    private final float[] spawnTimes = new float[8], spawnRates = new float[8], spawnProbabilities = new float[8],
            spawnLifetimeScales = new float[8], spawnLifetimeBiases = new float[8];
    private boolean ready;

    public StatelessGpuModuleParameters(JsonObject metadata) {
        if (!metadata.get("default_shader").getAsBoolean()) throw new IllegalArgumentException("Emitter does not use the native default analytic shader");
        ownerScale = new StatelessOwnerScale(metadata, true);
        Set<String> present = new HashSet<>();
        for (var item : metadata.getAsJsonArray("modules")) {
            JsonObject module = item.getAsJsonObject();
            String name = module.get("class").getAsString();
            if (!name.startsWith("NiagaraStatelessModule_")) throw new IllegalArgumentException("Invalid analytic shader module " + name);
            String kind = name.substring("NiagaraStatelessModule_".length());
            if (!present.add(kind)) throw new IllegalArgumentException("Duplicate analytic shader module " + kind);
            JsonObject value = module.has("compiled") && !module.get("compiled").isJsonNull() ? module.getAsJsonObject("compiled") : null;
            switch (kind) {
                case "InitializeParticle" -> initializer(required(value, kind));
                case "InitialMeshOrientation" -> nativeModule(new InitialMeshOrientationModule(required(value, kind)));
                case "SolveVelocitiesAndForces" -> nativeModule(new PhysicsModule(required(value, kind), ownerScale.definition()));
                case "ShapeLocation" -> shape(value);
                case "CameraOffset" -> {
                    packed("CameraOffset_Distribution", value, "distribution");
                    preparations.add((bindings, pose) -> floating("CameraOffset_Scale", ownerScale.camera(pose[7], pose[8], pose[9])));
                }
                case "DynamicMaterialParameters" -> dynamic(value);
                case "MeshIndex" -> {
                    integer("MeshIndex_Index", value == null ? 0 : (int)value.get("index").getAsLong());
                    integer("MeshIndex_TableOffset", value == null ? 0 : value.get("table_offset").getAsInt());
                    integer("MeshIndex_TableNumElements", value == null ? 0 : value.get("table_last").getAsInt());
                }
                case "MeshRotationRate" -> rotation("MeshRotationRate", "ModuleEnabled", value, "rotation", 3);
                case "SpriteRotationRate" -> rotation("SpriteRotationRate", "Enabled", value, "rate", 1);
                case "ScaleColor" -> packed("ScaleColor_Distribution", value, "distribution");
                case "ScaleMeshSize" -> scale("ScaleMeshSize", value, 3, true);
                case "ScaleRibbonWidth" -> scale("ScaleRibbonWidth", value, 1, false);
                case "ScaleSpriteSize" -> scale("ScaleSpriteSize", value, 2, false);
                case "ScaleMeshSizeBySpeed" -> speed("ScaleMeshSizeBySpeed", value, 3);
                case "ScaleSpriteSizeBySpeed" -> speed("ScaleSpriteSizeBySpeed", value, 2);
                case "SpriteFacingAndAlignment" -> facing(value, module.getAsJsonObject("properties"));
                case "SubUVAnimation" -> subImages(value);
                case "AddVelocity", "AccelerationForce", "CurlNoiseForce", "Drag", "GravityForce",
                     "ApplyOwnerScaleToAttributes", "DecalAttributes", "LightAttributes" -> {
                    // These source template slots execute Null; their shared build data is consumed by the solver and scale preparation.
                }
                default -> throw new IllegalArgumentException("Module is outside the native default analytic shader: " + kind);
            }
        }
        if (!present.containsAll(REQUIRED)) throw new IllegalArgumentException("Incomplete native default analytic shader module data");
        outputComponents(metadata);
    }

    private void outputComponents(JsonObject metadata) {
        JsonArray outputs = metadata.getAsJsonArray("shader_outputs"), offsets = metadata.getAsJsonArray("shader_output_offsets");
        if (outputs.size() != offsets.size()) throw new IllegalArgumentException("Invalid analytic shader output component map");
        int[] components = new int[(SHADER_OUTPUT_ORDER.size() + 3) / 4 * 4];
        Arrays.fill(components, -1);
        for (int index = 0; index < outputs.size(); index++) {
            String name = outputs.get(index).getAsJsonObject().get("name").getAsString();
            if (name.startsWith("Particles.")) name = name.substring("Particles.".length());
            String shaderName = name.replace("Previous.", "Previous");
            int slot = SHADER_OUTPUT_ORDER.indexOf(shaderName);
            if (slot < 0) throw new IllegalArgumentException("Unknown default analytic shader output " + name);
            int component = offsets.get(index).getAsInt();
            components[slot] = component;
        }
        for (int slot = 0; slot < SHADER_OUTPUT_ORDER.size(); slot++)
            integer("Permutation_" + SHADER_OUTPUT_ORDER.get(slot) + "Component", components[slot]);
        words("ParticleOutputComponents", components, 0, components.length);
    }

    public void prepare(int[] bindings, float[] pose) {
        if (pose.length != 10) throw new IllegalArgumentException("Invalid analytic shader source pose");
        ready = false;
        for (Preparation preparation : preparations) preparation.apply(bindings, pose);
        floats("Common_LocalToWorldScale", pose, 7, 3);
        for (int space = 0; space < 3; space++) {
            NativeStatelessMath.toSimulationRotation(space, pose, rotation);
            System.arraycopy(rotation, 0, rotations, space * 4, 4);
        }
        floats("Common_ToSimulationRotations", rotations, 0, rotations.length);
        ready = true;
    }
    public void common(StatelessEmitterFrame frame, StatelessSpawnSchedule.Plan plan, int outputStride, int countOffset) {
        if (!ready) throw new IllegalStateException("Analytic shader module parameters have not been prepared");
        integer("Common_RandomSeed", frame.seed());
        floating("Common_SimulationTime", frame.age());
        floating("Common_SimulationDeltaTime", frame.delta());
        floating("Common_SimulationInvDeltaTime", frame.delta() > 0f ? 1f / frame.delta() : 0f);
        integer("Common_OutputBufferStride", outputStride);
        integer("Common_GPUCountBufferOffset", countOffset);
        Arrays.fill(spawnActive, 0); Arrays.fill(spawnOffsets, 0); Arrays.fill(spawnIds, 0);
        Arrays.fill(spawnTimes, 0f); Arrays.fill(spawnRates, 0f); Arrays.fill(spawnProbabilities, 0f);
        Arrays.fill(spawnLifetimeScales, 0f); Arrays.fill(spawnLifetimeBiases, 0f);
        for (int index = 0; index < plan.windows().size(); index++) {
            var window = plan.windows().get(index);
            spawnActive[index] = (int)window.active(); spawnOffsets[index] = window.particleOffset(); spawnIds[index] = window.uniqueOffset();
            spawnTimes[index] = window.time(); spawnRates[index] = window.interval(); spawnProbabilities[index] = window.probability();
            spawnLifetimeScales[index] = window.lifetimeScale(); spawnLifetimeBiases[index] = window.lifetimeBias();
        }
        words("SpawnInfo_NumActive", spawnActive, 0, 8);
        words("SpawnInfo_ParticleOffset", spawnOffsets, 0, 8);
        words("SpawnInfo_UniqueOffset", spawnIds, 0, 8);
        floats("SpawnInfo_Time", spawnTimes, 0, 8);
        floats("SpawnInfo_Rate", spawnRates, 0, 8);
        floats("SpawnInfo_Probability", spawnProbabilities, 0, 8);
        floats("SpawnInfo_LifetimeScale", spawnLifetimeScales, 0, 8);
        floats("SpawnInfo_LifetimeBias", spawnLifetimeBiases, 0, 8);
    }
    public byte @Nullable [] value(String qualifiedName) {
        if (!ready) throw new IllegalStateException("Analytic shader module parameters have not been prepared");
        byte[] value = fields.get(qualifiedName);
        if (value != null) return value;
        value = fields.get(qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1));
        if (value != null) fields.put(qualifiedName, value);
        return value;
    }

    private void initializer(JsonObject value) {
        int flags = (value.get("uniform_sprite").getAsBoolean() ? 1 : 0) | (value.get("uniform_mesh").getAsBoolean() ? 2 : 0);
        integer("InitializeParticle_ModuleFlags", flags);
        packed("InitializeParticle_InitialPosition", value, "position");
        packed("InitializeParticle_InitialColor", value, "color");
        pair("InitializeParticle_SpriteSize", range(value, "sprite_size"), ownerScale.spriteEnabled()
                ? pose -> ownerScale.sprite(pose[7], pose[8], pose[9]) : UNIT);
        pair("InitializeParticle_SpriteRotation", range(value, "rotation"), UNIT);
        pair("InitializeParticle_MeshScale", range(value, "mesh_scale"), UNIT);
        pair("InitializeParticle_RibbonWidth", range(value, "ribbon_width"), ownerScale.ribbonEnabled()
                ? pose -> ownerScale.ribbon(pose[7], pose[8], pose[9]) : UNIT);
    }
    private void nativeModule(PreparedShaderModule module) {
        preparations.add((bindings, pose) -> { module.prepare(bindings, pose); module.writeShaderParameters(this); });
    }
    private void shape(@Nullable JsonObject value) {
        packed("ShapeLocation_TransformOffset", value, "offset");
        if (value != null) nativeModule(new ShapeLocationModule(value));
        else {
            words("ShapeLocation_Mode", ZERO4, 0, 4);
            words("ShapeLocation_Parameters0", ZERO4, 0, 4);
            words("ShapeLocation_Parameters1", ZERO4, 0, 4);
            words("ShapeLocation_Parameters2", ZERO4, 0, 4);
            words("ShapeLocation_TransformOffsetScale", ZERO3, 0, 3);
        }
    }
    private void dynamic(@Nullable JsonObject value) {
        int mask = 0;
        for (int parameter = 0; parameter < 4; parameter++) for (int channel = 0; channel < 4; channel++)
            words("DynamicMaterialParameters_Parameter" + parameter + "XYZW".charAt(channel), ZERO3, 0, 3);
        if (value != null) for (var item : value.getAsJsonArray("entries")) {
            JsonObject entry = item.getAsJsonObject();
            int parameter = entry.get("parameter").getAsInt();
            mask |= entry.get("mask").getAsInt() << (parameter * 4);
            for (int channel = 0; channel < 4; channel++) {
                int[] distribution = ints(entry.getAsJsonArray("distributions").get(channel).getAsJsonArray());
                words("DynamicMaterialParameters_Parameter" + parameter + "XYZW".charAt(channel), distribution, 0, 3);
            }
        }
        integer("DynamicMaterialParameters_ChannelMask", mask);
    }
    private void rotation(String prefix, String enabled, @Nullable JsonObject value, String range, int width) {
        integer(prefix + "_" + enabled, value == null ? 0 : 1);
        packed(prefix + "_RateScaleParameters", value, "curve");
        if (value == null) {
            words(prefix + "_Scale", ZERO3, 0, width);
            words(prefix + "_Bias", ZERO3, 0, width);
        } else pair(prefix + "_", range(value, range), UNIT);
    }
    private void scale(String prefix, @Nullable JsonObject value, int width, boolean shaderBinding) {
        packed(prefix + "_Distribution", value, "distribution");
        int offset = value == null ? -1 : value.get("curve_scale_offset").getAsInt();
        int[] defaults = value == null ? new int[width] : ints(value.getAsJsonArray("curve_scale_default"));
        if (value == null) for (int index = 0; index < defaults.length; index++) defaults[index] = Float.floatToRawIntBits(1f);
        String field = prefix + "_CurveScale";
        if (shaderBinding) {
            words(field, defaults, 0, width);
            integer(prefix + "_CurveScaleOffset", offset);
        } else preparations.add((bindings, pose) -> words(field, offset < 0 ? defaults : bindings, Math.max(offset, 0), width));
    }
    private void speed(String prefix, @Nullable JsonObject value, int width) {
        packed(prefix + "_ScaleDistribution", value, "distribution");
        if (value == null) {
            floats(prefix + "_ScaleFactorBias", ONES, 0, width);
            words(prefix + "_ScaleFactorScale", ZERO3, 0, width);
            floating(prefix + "_VelocityNorm", 0f);
            return;
        }
        StatelessRange minimum = range(value, "minimum_scale"), maximum = range(value, "maximum_scale"), threshold = range(value, "threshold");
        String bias = prefix + "_ScaleFactorBias", scale = prefix + "_ScaleFactorScale", velocity = prefix + "_VelocityNorm";
        preparations.add((bindings, pose) -> {
            byte[] biasWords = field(bias, width), scaleWords = field(scale, width);
            for (int index = 0; index < width; index++) {
                float low = minimum.minimum(index, bindings), high = maximum.minimum(index, bindings);
                word(biasWords, index, Float.floatToRawIntBits(low));
                word(scaleWords, index, Float.floatToRawIntBits(high - low));
            }
            float speed = threshold.minimum(0, bindings);
            floating(velocity, speed > 0f ? 1f / (speed * speed) : 0f);
        });
    }
    private void facing(@Nullable JsonObject value, JsonObject properties) {
        floats("SpriteFacingAndAlignment_SpriteFacing", AXIS_X, 0, 3);
        floats("SpriteFacingAndAlignment_SpriteAlignment", AXIS_Y, 0, 3);
        if (value == null) return;
        JsonArray entries = value.getAsJsonArray("entries");
        int index = 0;
        if (!properties.has("bSpriteFacingEnabled") || properties.get("bSpriteFacingEnabled").getAsBoolean())
            vector("SpriteFacingAndAlignment_SpriteFacing", range(entries.get(index++).getAsJsonObject(), "value"));
        if (properties.has("bSpriteAlignmentEnabled") && properties.get("bSpriteAlignmentEnabled").getAsBoolean())
            vector("SpriteFacingAndAlignment_SpriteAlignment", range(entries.get(index).getAsJsonObject(), "value"));
    }
    private void subImages(@Nullable JsonObject value) {
        String prefix = "SubUVAnimation_";
        if (value == null || value.get("frames").getAsInt() <= 1) {
            integer(prefix + "Mode", 0);
            for (String field : new String[] { "NumFrames", "InitialFrameScale", "InitialFrameBias", "InitialFrameRateChange", "AnimFrameStart", "AnimFrameRange", "RateScale" })
                floating(prefix + field, 0f);
            return;
        }
        float frames = value.get("frames").getAsFloat();
        integer(prefix + "Mode", value.get("mode").getAsInt());
        floating(prefix + "NumFrames", frames);
        floating(prefix + "InitialFrameRateChange", value.get("seed_rate").getAsFloat());
        floating(prefix + "AnimFrameStart", value.get("frame_start").getAsInt() / frames);
        floating(prefix + "AnimFrameRange", value.get("frame_range").getAsInt() / frames);
        floating(prefix + "RateScale", value.get("rate_scale").getAsFloat());
        JsonObject initial = value.getAsJsonObject("initial");
        int offset = initial.get("parameter_offset").getAsInt(), low = initial.get("minimum").getAsInt(), high = initial.get("maximum").getAsInt();
        preparations.add((bindings, pose) -> {
            int minimum = offset < 0 ? low : bindings[offset], maximum = offset < 0 ? high : bindings[offset];
            floating("SubUVAnimation_InitialFrameScale", (maximum - minimum) / frames);
            floating("SubUVAnimation_InitialFrameBias", minimum / frames);
        });
    }
    private void pair(String prefix, StatelessRange range, Factor factor) {
        String scale = prefix + "Scale", bias = prefix + "Bias";
        preparations.add((bindings, pose) -> {
            byte[] scaleWords = field(scale, range.width()), biasWords = field(bias, range.width());
            float multiplier = factor.value(pose);
            for (int index = 0; index < range.width(); index++) {
                float low = range.minimum(index, bindings), high = range.maximum(index, bindings);
                word(scaleWords, index, Float.floatToRawIntBits(factor == UNIT ? high - low : (high - low) * multiplier));
                word(biasWords, index, Float.floatToRawIntBits(factor == UNIT ? low : low * multiplier));
            }
        });
    }
    private void vector(String name, StatelessRange range) {
        preparations.add((bindings, pose) -> {
            byte[] words = field(name, range.width());
            for (int index = 0; index < range.width(); index++) word(words, index, Float.floatToRawIntBits(range.minimum(index, bindings)));
        });
    }
    private void packed(String name, @Nullable JsonObject value, String field) {
        int[] words = value == null ? ZERO3 : ints(value.getAsJsonArray(field));
        if (words.length != 3) throw new IllegalArgumentException("Invalid analytic packed distribution " + name);
        words(name, words, 0, 3);
    }
    private static StatelessRange range(JsonObject value, String field) { return StatelessRange.decode(value.getAsJsonObject(field)); }
    private static JsonObject required(@Nullable JsonObject value, String module) {
        if (value == null) throw new IllegalArgumentException("Missing analytic module build data " + module);
        return value;
    }
    private static int[] ints(JsonArray values) { return values.asList().stream().mapToInt(value -> (int)value.getAsLong()).toArray(); }

    @Override public void integer(String name, int value) { word(field(name, 1), 0, value); }
    @Override public void floating(String name, float value) { integer(name, Float.floatToRawIntBits(value)); }
    @Override public void floats(String name, float[] values, int offset, int count) {
        byte[] data = field(name, count);
        for (int index = 0; index < count; index++) word(data, index, Float.floatToRawIntBits(values[offset + index]));
    }
    @Override public void words(String name, int[] values, int offset, int count) {
        byte[] data = field(name, count);
        for (int index = 0; index < count; index++) word(data, index, values[offset + index]);
    }
    private byte[] field(String name, int count) {
        byte[] data = fields.get(name);
        int bytes = Math.multiplyExact(count, Integer.BYTES);
        if (data == null) { data = new byte[bytes]; fields.put(name, data); }
        else if (data.length != bytes) throw new IllegalArgumentException("Analytic shader field width changed: " + name);
        return data;
    }
    private static void word(byte[] data, int index, int value) {
        int offset = index * Integer.BYTES;
        data[offset] = (byte)value; data[offset + 1] = (byte)(value >>> 8);
        data[offset + 2] = (byte)(value >>> 16); data[offset + 3] = (byte)(value >>> 24);
    }
}
