package com.kltyton.lumenfx.client.simulation.niagara.stateless;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.client.simulation.niagara.VectorDataSet;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraCollectionSnapshot;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.modules.CameraOffsetModule;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.modules.AccurateVelocityModule;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.modules.DynamicMaterialModule;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.modules.InitializeParticleModule;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.modules.LightAttributesModule;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.modules.PhysicsModule;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.modules.ScaleAttributeModule;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.modules.ScaleBySpeedModule;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.modules.SpriteRotationRateModule;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.modules.FacingAlignmentModule;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.modules.SubImageAnimationModule;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.modules.InitialMeshOrientationModule;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.modules.MeshRotationRateModule;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.modules.DecalAttributesModule;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.modules.PreparedModule;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.modules.OrbitModule;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.modules.ShapeLocationModule;
import com.kltyton.lumenfx.client.simulation.niagara.stateless.modules.MeshIndexModule;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** One analytic CPU instance owns its random stream, spawn history, parameter store and particle scratch data. */
public final class StatelessCpuProgram {
    private static final class Initialization {
        final StatelessRange ribbon, sprite, rotation, mesh;
        InitializeParticleModule.Parameters prepared;
        Initialization(JsonObject value) {
            ribbon = range(value.getAsJsonObject("ribbon_width")); sprite = range(value.getAsJsonObject("sprite_size"));
            rotation = range(value.getAsJsonObject("rotation")); mesh = range(value.getAsJsonObject("mesh_scale"));
        }
        void prepare(int[] parameters, float spriteFactor, float ribbonFactor) {
            float rotationBias = rotation.minimum(0, parameters), rotationScale = rotation.maximum(0, parameters) - rotationBias;
            float ribbonBias = ribbon.minimum(0, parameters) * ribbonFactor;
            float ribbonScale = (ribbon.maximum(0, parameters) - ribbon.minimum(0, parameters)) * ribbonFactor;
            prepared = new InitializeParticleModule.Parameters(ribbonScale, ribbonBias, sprite.scale(parameters, spriteFactor),
                    sprite.bias(parameters, spriteFactor), rotationScale, rotationBias, mesh.scale(parameters, 1f), mesh.bias(parameters, 1f));
        }
    }
    private final StatelessParameterBuffer parameters;
    private final StatelessEmitterState emitter;
    private final StatelessParticleSimulation simulation;
    private final VectorDataSet particles;
    private final boolean canEverExecute;
    private final List<Initialization> initializations = new ArrayList<>();
    private final List<PreparedModule> preparedModules = new ArrayList<>();
    private final JsonObject ownerScaleDefinition;
    private final float[] sourcePose = new float[10];
    private final float[] incomingPose = new float[10];
    private int[] frozenBindings = new int[0];
    private boolean frozenInitialized;
    private static final int[] POSE_FIELDS = { 480, 484, 488, 492, 496, 500, 504, 576, 580, 584 };
    private boolean poseInitialized;
    private final StatelessOwnerScale ownerScale;
    private final int[] shaderWords = new int[2];
    private float scaleX = Float.NaN, scaleY = Float.NaN, scaleZ = Float.NaN;

    public StatelessCpuProgram(NiagaraDefinition system, NiagaraDefinition.Emitter source, VectorDataSet particles, int seed) {
        this(system, source, particles, seed, NiagaraPlatforms.snapshot());
    }
    public StatelessCpuProgram(NiagaraDefinition system, NiagaraDefinition.Emitter source, VectorDataSet particles, int seed,
                               NiagaraPlatformEnvironment platform) {
        this(source, particles, new StatelessEmitterState(system, source, seed, platform));
    }
    public StatelessCpuProgram(NiagaraDefinition.Emitter source, VectorDataSet particles, StatelessEmitterState emitter) {
        this.particles = particles;
        this.emitter = emitter;
        parameters = emitter.parameters();
        JsonObject metadata = ((NiagaraDefinition.StatelessSimulation)source.simulation()).metadata();
        canEverExecute = emitter.canEverExecute() && (emitter.features() & 2) != 0;
        ownerScale = new StatelessOwnerScale(metadata, canEverExecute);
        ownerScaleDefinition = ownerScale.definition();
        List<StatelessParticleSimulation.Callback> callbacks = new ArrayList<>();
        if (canEverExecute) for (JsonElement item : metadata.getAsJsonArray("modules")) {
            JsonObject value = item.getAsJsonObject();
            StatelessParticleSimulation.Module module = module(value);
            if (module != null) callbacks.add(new StatelessParticleSimulation.Callback((int)value.get("seed").getAsLong(), module));
        }
        simulation = new StatelessParticleSimulation(particles, component(source, "UniqueID", 1), component(source, "MaterialRandom", 0),
                words(metadata.getAsJsonArray("static_words")), callbacks);
    }

    private StatelessParticleSimulation.@Nullable Module module(JsonObject definition) {
        String kind = definition.get("class").getAsString();
        if (kind.equals("NiagaraStatelessModule_ApplyOwnerScaleToAttributes")) return null;
        if (List.of("NiagaraStatelessModule_AddVelocity", "NiagaraStatelessModule_AccelerationForce", "NiagaraStatelessModule_Drag",
                "NiagaraStatelessModule_GravityForce", "NiagaraStatelessModule_CurlNoiseForce").contains(kind)) return null;
        if (!kind.equals("NiagaraStatelessModule_InitializeParticle") && !kind.equals("NiagaraStatelessModule_CalculateAccurateVelocity")
                && !kind.equals("NiagaraStatelessModule_SolveVelocitiesAndForces")
                && !kind.equals("NiagaraStatelessModule_InitialMeshOrientation")
                && !definition.get("enabled").getAsBoolean()) return null;
        JsonObject value = definition.has("compiled") && !definition.get("compiled").isJsonNull() ? definition.getAsJsonObject("compiled") : null;
        switch (kind) {
            case "NiagaraStatelessModule_InitializeParticle" -> {
                if (value == null) throw new IllegalArgumentException("Missing stateless initializer data");
                Initialization initialization = new Initialization(value); initializations.add(initialization);
                int[] channels = words(value.getAsJsonArray("channels"));
                if (channels.length != 11) throw new IllegalArgumentException("Invalid stateless initializer channels");
                return new InitializeParticleModule(new InitializeParticleModule.Channels(channels[0], channels[1], channels[2], channels[3], channels[4], channels[5],
                        channels[6], channels[7], channels[8], channels[9], channels[10]), distribution(value.getAsJsonArray("position")), distribution(value.getAsJsonArray("color")),
                        value.get("uniform_sprite").getAsBoolean(), value.get("uniform_mesh").getAsBoolean(), context -> initialization.prepared);
            }
            case "NiagaraStatelessModule_ScaleColor", "NiagaraStatelessModule_ScaleMeshSize", "NiagaraStatelessModule_ScaleSpriteSize", "NiagaraStatelessModule_ScaleRibbonWidth" -> {
                if (value == null) return null;
                ScaleAttributeModule.Kind type = switch (kind) {
                    case "NiagaraStatelessModule_ScaleColor" -> ScaleAttributeModule.Kind.COLOR;
                    case "NiagaraStatelessModule_ScaleMeshSize" -> ScaleAttributeModule.Kind.MESH;
                    case "NiagaraStatelessModule_ScaleSpriteSize" -> ScaleAttributeModule.Kind.SPRITE;
                    default -> ScaleAttributeModule.Kind.RIBBON;
                };
                return new ScaleAttributeModule(type, value.get("current").getAsInt(), value.get("previous").getAsInt(), distribution(value.getAsJsonArray("distribution")),
                        type == ScaleAttributeModule.Kind.COLOR ? null : new ScaleAttributeModule.CurveScale(value.get("curve_scale_offset").getAsInt(), floats(value.getAsJsonArray("curve_scale_default"))));
            }
            case "NiagaraStatelessModule_CameraOffset" -> {
                return value == null ? null : new CameraOffsetModule(value.get("current").getAsInt(), value.get("previous").getAsInt(), distribution(value.getAsJsonArray("distribution")), 0);
            }
            case "NiagaraStatelessModule_DynamicMaterialParameters" -> {
                if (value == null) return null;
                List<DynamicMaterialModule.Parameter> entries = value.getAsJsonArray("entries").asList().stream().map(item -> {
                    JsonObject entry = item.getAsJsonObject();
                    return new DynamicMaterialModule.Parameter(entry.get("parameter").getAsInt(), entry.get("mask").getAsInt(),
                            entry.get("component").getAsInt(), entry.getAsJsonArray("distributions").asList().stream()
                                .map(encoded -> distribution(encoded.getAsJsonArray())).toList());
                }).toList();
                return new DynamicMaterialModule(entries);
            }
            case "NiagaraStatelessModule_LightAttributes" -> {
                if (value == null) return null;
                List<LightAttributesModule.Attribute> entries = value.getAsJsonArray("entries").asList().stream().map(item -> {
                    JsonObject entry = item.getAsJsonObject();
                    return new LightAttributesModule.Attribute(entry.get("component").getAsInt(), entry.get("radius").getAsBoolean(),
                            distribution(entry.getAsJsonArray("distribution")));
                }).toList();
                return new LightAttributesModule(entries, 1);
            }
            case "NiagaraStatelessModule_SpriteRotationRate" -> {
                if (value == null) return null;
                StatelessRange rate = range(value.getAsJsonObject("rate"));
                return new SpriteRotationRateModule(value.get("current").getAsInt(), value.get("previous").getAsInt(),
                        rate.minimum(0), rate.maximum(0), rate.parameterOffset(), distribution(value.getAsJsonArray("curve")));
            }
            case "NiagaraStatelessModule_ScaleMeshSizeBySpeed", "NiagaraStatelessModule_ScaleSpriteSizeBySpeed" -> {
                if (value == null || !value.get("callback").getAsBoolean()) return null;
                StatelessRange threshold = range(value.getAsJsonObject("threshold")), minimum = range(value.getAsJsonObject("minimum_scale")),
                        maximum = range(value.getAsJsonObject("maximum_scale"));
                return new ScaleBySpeedModule(value.get("width").getAsInt(), value.get("current").getAsInt(), value.get("previous").getAsInt(),
                        value.get("position").getAsInt(), value.get("previous_position").getAsInt(), value.get("velocity").getAsInt(),
                        new ScaleBySpeedModule.Value(threshold.minimumValues(), threshold.parameterOffset()), new ScaleBySpeedModule.Value(minimum.minimumValues(), minimum.parameterOffset()),
                        new ScaleBySpeedModule.Value(maximum.minimumValues(), maximum.parameterOffset()), distribution(value.getAsJsonArray("distribution")));
            }
            case "NiagaraStatelessModule_CalculateAccurateVelocity" -> {
                return value == null ? null : new AccurateVelocityModule(value.get("position").getAsInt(), value.get("previous_position").getAsInt(),
                        value.get("velocity").getAsInt(), value.get("previous_velocity").getAsInt());
            }
            case "NiagaraStatelessModule_SolveVelocitiesAndForces" -> {
                if (value == null || !value.get("callback").getAsBoolean()) return null;
                PhysicsModule module = new PhysicsModule(value, ownerScaleDefinition);
                preparedModules.add(module);
                return module;
            }
            case "NiagaraStatelessModule_SpriteFacingAndAlignment" -> {
                if (value == null) return null;
                List<FacingAlignmentModule.Attribute> attributes = value.getAsJsonArray("entries").asList().stream().map(item -> {
                    JsonObject entry = item.getAsJsonObject();
                    return new FacingAlignmentModule.Attribute(entry.get("current").getAsInt(), entry.get("previous").getAsInt(),
                            StatelessRange.decode(entry.getAsJsonObject("value")));
                }).toList();
                return new FacingAlignmentModule(attributes);
            }
            case "NiagaraStatelessModule_SubUVAnimation" -> { return value == null ? null : new SubImageAnimationModule(value); }
            case "NiagaraStatelessModule_InitialMeshOrientation" -> {
                if (value == null || !value.get("callback").getAsBoolean()) return null;
                InitialMeshOrientationModule module = new InitialMeshOrientationModule(value); preparedModules.add(module); return module;
            }
            case "NiagaraStatelessModule_MeshRotationRate" -> { return value == null ? null : new MeshRotationRateModule(value); }
            case "NiagaraStatelessModule_DecalAttributes" -> {
                if (value == null) return null;
                DecalAttributesModule module = new DecalAttributesModule(value, ownerScaleDefinition); preparedModules.add(module); return module;
            }
            case "NiagaraStatelessModule_RotateAroundPoint" -> {
                if (value == null) return null;
                OrbitModule module = new OrbitModule(value); preparedModules.add(module); return module;
            }
            case "NiagaraStatelessModule_ShapeLocation" -> {
                if (value == null || !value.get("callback").getAsBoolean()) return null;
                ShapeLocationModule module = new ShapeLocationModule(value); preparedModules.add(module); return module;
            }
            case "NiagaraStatelessModule_MeshIndex" -> { return value == null ? null : new MeshIndexModule(value); }
            default -> throw new IllegalArgumentException("Missing stateless CPU module implementation: " + kind);
        }
    }

    public void tick(float delta, int parentState, byte[] spawn, byte[] update, byte[] runtime, EffectHandle.Input input,
                     double originX, double originY, double originZ) {
        tick(delta, parentState, spawn, update, runtime, input, originX, originY, originZ, NiagaraCollectionSnapshot.EMPTY);
    }
    public void tick(float delta, int parentState, byte[] spawn, byte[] update, byte[] runtime, EffectHandle.Input input,
                     double originX, double originY, double originZ, NiagaraCollectionSnapshot collections) {
        tick(delta, parentState, spawn, update, runtime, input, originX, originY, originZ, collections, true);
    }
    public void tick(float delta, int parentState, byte[] spawn, byte[] update, byte[] runtime, EffectHandle.Input input,
                     double originX, double originY, double originZ, NiagaraCollectionSnapshot collections,
                     boolean attachmentBirthsAllowed) {
        if (!canEverExecute) return;
        boolean dirty = emitter.updateParameters(spawn, update, runtime, input, originX, originY, originZ, collections);
        dirty |= frozenInitialized;
        frozenInitialized = false;
        ByteBuffer engine = ByteBuffer.wrap(runtime).order(ByteOrder.LITTLE_ENDIAN);
        for (int index = 0; index < incomingPose.length; index++) incomingPose[index] = engine.getFloat(POSE_FIELDS[index]);
        prepareModules(parameters.words(), incomingPose, dirty);
        emitter.tick(delta, parentState, engine.getFloat(32), engine.getFloat(36), attachmentBirthsAllowed);
        simulation.simulate(emitter.seed(), emitter.age(), delta, emitter.plan(), parameters.words(), shaderWords);
    }
    public void spawningAllowed(boolean allowed) { emitter.spawningAllowed(allowed); }
    public void complete(int executionState) { emitter.complete(executionState); }
    private void prepareModules(int[] bindings, float[] pose, boolean dirty) {
        boolean transformChanged = !poseInitialized;
        for (int index = 0; index < sourcePose.length; index++) {
            float value = pose[index];
            transformChanged |= Float.floatToRawIntBits(sourcePose[index]) != Float.floatToRawIntBits(value);
            sourcePose[index] = value;
        }
        poseInitialized = true;
        float x = sourcePose[7], y = sourcePose[8], z = sourcePose[9];
        if (dirty || scaleX != x || scaleY != y || scaleZ != z) {
            scaleX = x; scaleY = y; scaleZ = z;
            for (Initialization initialization : initializations) initialization.prepare(bindings, ownerScale.sprite(x, y, z), ownerScale.ribbon(x, y, z));
            shaderWords[0] = Float.floatToRawIntBits(ownerScale.camera(x, y, z));
            shaderWords[1] = Float.floatToRawIntBits(ownerScale.light(x, y, z));
        }
        if (dirty || transformChanged) for (PreparedModule module : preparedModules) module.prepare(bindings, sourcePose);
    }

    /** Evaluates a frozen analytic request without advancing loops, age, random-stream state or parameter bindings. */
    public void evaluateFrozen(int emitterSeed, float age, float delta, StatelessSpawnSchedule.Plan plan,
                               int[] bindings, float[] pose) {
        if (!canEverExecute) return;
        if (pose.length != sourcePose.length || bindings.length != parameters.words().length)
            throw new IllegalArgumentException("Frozen Niagara analytic input layout does not match its emitter");
        boolean dirty = !frozenInitialized || !Arrays.equals(frozenBindings, bindings);
        if (dirty) {
            frozenBindings = bindings.clone();
            frozenInitialized = true;
        }
        prepareModules(bindings, pose, dirty);
        simulation.simulate(emitterSeed, age, delta, plan, bindings, shaderWords);
    }
    public boolean shouldTick() { return canEverExecute && emitter.shouldTick(); }
    public boolean complete() { return !canEverExecute || emitter.complete(); }
    public int countEstimate() { return canEverExecute ? (int)emitter.countEstimate() : 0; }
    public int executionState() { return canEverExecute ? emitter.executionState() : 4; }
    public float age() { return emitter.age(); }
    public int seed() { return emitter.seed(); }
    public boolean canEverExecute() { return canEverExecute; }
    public void reset(int nextSeed) {
        reset(true, nextSeed);
    }

    public void reset(boolean killExisting, int nextSeed) {
        if (!canEverExecute) return;
        emitter.reset(killExisting, nextSeed);
        if (killExisting) particles.clear();
        frozenInitialized = false;
        scaleX = scaleY = scaleZ = Float.NaN;
        poseInitialized = false;
    }

    private static StatelessDistribution distribution(JsonArray source) {
        int[] values = words(source);
        if (values.length != 3) throw new IllegalArgumentException("Invalid stateless packed distribution");
        return new StatelessDistribution(values[0], values[1], values[2]);
    }
    private static StatelessRange range(JsonObject value) { return StatelessRange.decode(value); }
    private static int component(NiagaraDefinition.Emitter source, String name, int type) {
        return source.attributes().stream().filter(attribute -> attribute.type() == type && (SourceNames.equal(attribute.name(), name) || SourceNames.equal(attribute.name(), "Particles." + name)))
                .mapToInt(NiagaraDefinition.Attribute::component).findFirst().orElse(-1);
    }
    private static int[] words(JsonArray values) { return values.asList().stream().mapToInt(value -> (int)value.getAsLong()).toArray(); }
    private static float[] floats(JsonArray values) {
        float[] result = new float[values.size()];
        for (int index = 0; index < result.length; index++) result[index] = Float.intBitsToFloat((int)values.get(index).getAsLong());
        return result;
    }
}
