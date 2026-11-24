package com.kltyton.lumenfx.client.simulation.niagara.material;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.parameter.DynamicMaterialValues;
import com.kltyton.lumenfx.api.client.parameter.DynamicMaterialValues.DoubleVector;
import com.kltyton.lumenfx.api.client.parameter.DynamicMaterialValues.ParameterInfo;
import com.kltyton.lumenfx.api.client.parameter.DynamicMaterialValues.Vector;
import com.kltyton.lumenfx.api.client.parameter.RendererParameter;
import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.client.resource.texture.EffectTextures;
import com.kltyton.lumenfx.client.simulation.niagara.VectorDataSet;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraRendererParameters;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraPositionParameters;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraCollectionSnapshot;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.render.material.MaterialInstanceParameters;
import com.kltyton.lumenfx.effect.render.material.VirtualTextureRead;
import com.kltyton.lumenfx.effect.render.material.VirtualTextureRead.StreamedValue;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Renderer-owned MID values read the completed system snapshot, without touching a world on workers. */
public final class NiagaraMaterialInstances {
    @FunctionalInterface
    public interface ExposedValues {
        /** Implementations consume an already captured interface snapshot and preserve unwritten components. */
        boolean read(MaterialInstanceParameters.Slot slot, float[] destination);
    }
    @FunctionalInterface
    public interface EngineValues {
        boolean read(String name, float[] destination);
        default boolean read(String name, MaterialInstanceParameters.Source source, float[] destination) { return read(name, destination); }
        default boolean readDefault(String name, MaterialInstanceParameters.Source source, float[] destination) { return false; }
        default @Nullable TextureParameter readTexture(MaterialInstanceParameters.TextureSlot binding) { return null; }
        default StreamedValue readStreamedTexture(VirtualTextureRead.StreamedBinding binding) { return StreamedValue.Default.BASE; }
        default NiagaraCollectionSnapshot collections() { return NiagaraCollectionSnapshot.EMPTY; }
        default @Nullable DynamicMaterialValues dynamicMaterial(MaterialDefinition source) { return null; }
        default boolean dynamicBindingsPublished() { return false; }
    }
    private static final class Instance {
        final MaterialDefinition material;
        final float[] values;
        final float[][] readers;
        final boolean collectionTextures;
        final @Nullable DynamicMaterialValues externalDynamic;
        final @Nullable DynamicMaterialValues ownedDynamic;
        long frame = -1;
        Instance(MaterialDefinition source, @Nullable DynamicMaterialValues externalDynamic,
                 @Nullable DynamicMaterialValues ownedDynamic) {
            this.externalDynamic = externalDynamic;
            this.ownedDynamic = ownedDynamic;
            DynamicMaterialValues effective = externalDynamic != null ? externalDynamic : ownedDynamic;
            material = source.instantiateParameters(effective); this.values = material.instanceParameters().defaults();
            readers = material.instanceParameters().layout().stream().map(slot -> new float[slot.source().components()]).toArray(float[][]::new);
            collectionTextures = material.instanceParameters().textureLayout().stream().anyMatch(slot -> slot.collection() != null);
        }
    }
    private final Map<MaterialDefinition, Instance> instances = new IdentityHashMap<>();
    private final @Nullable ExposedValues exposed;
    private final MaterialInstanceParameters.@Nullable TextureValues exposedTextures;
    private VectorDataSet system;
    private Map<String, NiagaraDefinition.Variable> variables;
    private EffectHandle.Input input;
    private double originX, originY, originZ;
    private long frame;
    private @Nullable EngineValues engineValues;
    private NiagaraCollectionSnapshot collections = NiagaraCollectionSnapshot.EMPTY;

    public NiagaraMaterialInstances() { this(null, null); }
    public NiagaraMaterialInstances(@Nullable ExposedValues exposed) { this(exposed, null); }
    public NiagaraMaterialInstances(@Nullable ExposedValues exposed, MaterialInstanceParameters.@Nullable TextureValues exposedTextures) {
        this.exposed = exposed; this.exposedTextures = exposedTextures;
    }

    public void beginFrame(VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input,
                           double originX, double originY, double originZ) {
        beginFrame(system, variables, input, originX, originY, originZ, null);
    }
    public void beginFrame(VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input,
                           double originX, double originY, double originZ, @Nullable EngineValues engineValues) {
        this.system = Objects.requireNonNull(system); this.variables = Objects.requireNonNull(variables); this.input = Objects.requireNonNull(input);
        this.originX = originX; this.originY = originY; this.originZ = originZ;
        this.engineValues = engineValues;
        collections = engineValues == null ? NiagaraCollectionSnapshot.EMPTY : Objects.requireNonNull(engineValues.collections());
        frame++;
        instances.entrySet().removeIf(entry -> entry.getValue().frame < frame - 1);
    }

    public MaterialDefinition material(MaterialDefinition source, NiagaraDefinition.@Nullable SurfaceMaterialBinding binding) {
        MaterialDefinition authored = source;
        if (binding != null && frame != 0) {
            var parameter = RendererParameter.named(input.rendererParameters(), binding.parameter());
            var selected = RendererParameter.material(parameter, binding.variant());
            if (selected != null) source = selected;
        }
        return material(source, authored);
    }

    public MaterialDefinition material(MaterialDefinition source) {
        return material(source, source);
    }

    public MaterialDefinition material(MaterialDefinition source, MaterialDefinition authored) {
        boolean bound = source.instanceParameters().hasBindings();
        boolean authoredBound = authored != source && authored.instanceParameters().hasRendererBindings();
        boolean streamedBindings = engineValues != null && source.virtualTextureReads().stream().anyMatch(read -> read.rendererBinding() != null);
        var externalDynamic = source.instanceParameters().dynamicValues();
        if (externalDynamic == null && engineValues != null) externalDynamic = engineValues.dynamicMaterial(source);
        if (!bound && !authoredBound && externalDynamic == null && !streamedBindings) return source;
        if (frame == 0) throw new IllegalStateException("Material instance requires the completed system render snapshot");
        Instance instance = instances.get(source);
        boolean needsOwnedDynamic = externalDynamic == null && authoredBound;
        if (instance == null || instance.externalDynamic != externalDynamic
                || (instance.ownedDynamic != null) != needsOwnedDynamic) {
            if (instance != null) instances.remove(instance.material);
            instance = new Instance(source, externalDynamic, needsOwnedDynamic ? new DynamicMaterialValues() : null);
            instances.put(source, instance); instances.put(instance.material, instance);
        }
        if (instance.frame != frame) {
            var slots = instance.material.instanceParameters().layout();
            int offset = 0;
            for (int index = 0; index < slots.size(); index++) {
                var slot = slots.get(index);
                if (slot.source() == MaterialInstanceParameters.Source.CONSTANT) { offset += slot.floats(); continue; }
                float[] value = instance.readers[index];
                Arrays.fill(value, slot.source() == MaterialInstanceParameters.Source.POSITION ? 0 : 1);
                if (slot.dataInterface() != null) {
                    if (exposed == null) throw new IllegalStateException("Material binding needs its exposed interface snapshot: " + slot.variable() + "." + slot.child());
                    exposed.read(slot, value);
                } else read(slot, system, variables, input, originX, originY, originZ, engineValues, value);
                if (slot.kind() == MaterialInstanceParameters.Kind.DOUBLE_VECTOR) {
                    for (int component = 0; component < 4; component++) {
                        double world = component == 3 ? 1 : value[component]
                                + (component == 0 ? originX : component == 1 ? originY : originZ);
                        float high = (float)world;
                        instance.values[offset + component] = high;
                        instance.values[offset + 4 + component] = (float)(world - high);
                    }
                    offset += slot.floats();
                    continue;
                }
                Arrays.fill(instance.values, offset, offset + 4, slot.kind() == MaterialInstanceParameters.Kind.SCALAR ? 0 : 1);
                for (int component = 0; component < value.length; component++) instance.values[offset + component] = value[component];
                if (slot.source() == MaterialInstanceParameters.Source.POSITION) {
                    instance.values[offset] = (float)(originX + value[0]);
                    instance.values[offset + 1] = (float)(originY + value[1]);
                    instance.values[offset + 2] = (float)(originZ + value[2]);
                }
                offset += slot.floats();
            }
            if (instance.ownedDynamic != null) {
                applyDynamicBindings(authored, instance.ownedDynamic, system, variables, input,
                        originX, originY, originZ, engineValues, exposed);
            } else if (instance.externalDynamic != null && (engineValues == null || !engineValues.dynamicBindingsPublished())
                    && authored.instanceParameters().hasRendererBindings()) {
                applyDynamicBindings(authored, instance.externalDynamic, system, variables, input,
                        originX, originY, originZ, engineValues, exposed);
            }
            instance.material.instanceParameters().publish(instance.values,
                    instance.material.instanceParameters().textureLayout().isEmpty() ? Map.of() : input.textureParameters(), textureValues(instance),
                    streamedBindings ? streamedValues(instance.material) : Map.of());
            instance.frame = frame;
        }
        return instance.material;
    }

    /** Matches UE 5.9 FNiagaraRenderer::ProcessMaterialParameterBindings write order onto a live MID. */
    public static void applyDynamicBindings(MaterialDefinition authored, DynamicMaterialValues target,
                                            VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables,
                                            EffectHandle.Input input, double originX, double originY, double originZ,
                                            @Nullable EngineValues engineValues, @Nullable ExposedValues exposed) {
        var params = authored.instanceParameters();
        var slots = params.layout();
        // 1. AttributeBindings (Scalar, Vector, Position -> DoubleVector + Vector)
        for (int index = 0; index < slots.size(); index++) {
            var slot = slots.get(index);
            if (slot.source() == MaterialInstanceParameters.Source.CONSTANT) continue;
            var info = params.slotInfo(index);
            if (info == null) continue;
            float[] value = new float[slot.source().components()];
            Arrays.fill(value, slot.source() == MaterialInstanceParameters.Source.POSITION ? 0 : 1);
            if (slot.dataInterface() != null) {
                if (exposed == null) throw new IllegalStateException("Material binding needs its exposed interface snapshot: " + slot.variable() + "." + slot.child());
                exposed.read(slot, value);
            } else read(slot, system, variables, input, originX, originY, originZ, engineValues, value);
            if (slot.kind() == MaterialInstanceParameters.Kind.SCALAR) {
                target.setScalar(info, value[0]);
            } else if (slot.kind() == MaterialInstanceParameters.Kind.DOUBLE_VECTOR
                    || slot.source() == MaterialInstanceParameters.Source.POSITION) {
                double wx = originX + value[0], wy = originY + value[1], wz = originZ + value[2];
                target.setDoubleVector(info, new DoubleVector(wx, wy, wz, 1.0));
                target.setVector(info, new Vector((float)wx, (float)wy, (float)wz, 1.0f));
            } else if (slot.kind() == MaterialInstanceParameters.Kind.VECTOR) {
                float x = value.length > 0 ? value[0] : 1f;
                float y = value.length > 1 ? value[1] : 1f;
                float z = value.length > 2 ? value[2] : 1f;
                float w = value.length > 3 ? value[3] : 1f;
                target.setVector(info, new Vector(x, y, z, w));
            }
        }
        // 2. Texture AttributeBindings (bound UTexture/UObject -> SetTextureParameterValue, unbound -> reset to parent default)
        for (var binding : params.textureLayout()) {
            if (binding.dataInterface() != null || binding.collection() != null || binding.name().isBlank()) continue;
            TextureParameter resolved = resolveDynamicTexture(binding, input, engineValues);
            ParameterInfo info = new ParameterInfo(binding.name());
            if (resolved != null) target.setTexture(info, resolved);
            else target.resetTexture(info);
        }
        // 3. Renderer Constant ScalarParameters & VectorParameters (applied after AttributeBindings)
        for (int index = 0; index < slots.size(); index++) {
            var slot = slots.get(index);
            if (!slot.rendererConstant()) continue;
            var info = params.slotInfo(index);
            if (info == null) continue;
            float[] d = slot.defaults();
            if (slot.kind() == MaterialInstanceParameters.Kind.SCALAR) target.setScalar(info, d[0]);
            else if (slot.kind() == MaterialInstanceParameters.Kind.VECTOR) target.setVector(info, new Vector(d[0], d[1], d[2], d[3]));
        }
    }

    private static @Nullable TextureParameter resolveDynamicTexture(MaterialInstanceParameters.TextureSlot binding,
                                                                    EffectHandle.Input input,
                                                                    @Nullable EngineValues engineValues) {
        if (engineValues != null) {
            TextureParameter bound = engineValues.readTexture(binding);
            if (bound != null) return bound;
        }
        TextureParameter resolved = findTextureParameter(input.textureParameters(), binding.variable());
        for (var objectInput : binding.objectInputs()) {
            TextureParameter override = findTextureParameter(input.textureParameters(), objectInput.variable());
            if (override != null) resolved = override;
            else if (objectInput.defaultPresent() && objectInput.defaultTexture() == null) resolved = null;
        }
        return resolved;
    }

    private static @Nullable TextureParameter findTextureParameter(Map<String, TextureParameter> parameters, String name) {
        var exact = parameters.get(name);
        if (exact != null) return exact;
        for (var entry : parameters.entrySet())
            if (SourceNames.equal(entry.getKey(), name)) return entry.getValue();
        return null;
    }

    private Map<String, StreamedValue> streamedValues(MaterialDefinition material) {
        var values = new HashMap<String, StreamedValue>();
        var engine = Objects.requireNonNull(engineValues);
        for (var read : material.virtualTextureReads()) {
            var binding = read.rendererBinding();
            if (binding != null) values.put(read.name(), engine.readStreamedTexture(binding));
        }
        return values;
    }

    private MaterialInstanceParameters.@Nullable TextureValues textureValues(Instance instance) {
        if (!instance.collectionTextures && engineValues == null) return exposedTextures;
        NiagaraCollectionSnapshot snapshot = collections;
        var exposed = exposedTextures;
        var objects = new IdentityHashMap<MaterialInstanceParameters.TextureSlot, TextureParameter>();
        var engine = engineValues;
        if (engine != null) for (var binding : instance.material.instanceParameters().textureLayout()) {
            TextureParameter value = engine.readTexture(binding);
            if (value != null) objects.put(binding, value);
        }
        return new MaterialInstanceParameters.TextureValues() {
            @Override public TextureParameter.Snapshot resolve(MaterialInstanceParameters.TextureSlot binding) {
                if (binding.collection() != null) return collectionTexture(binding, snapshot);
                if (exposed != null) return exposed.resolve(binding);
                if (binding.staticExposure()) return binding.objectDefault() == null
                        ? TextureParameter.Snapshot.UNBOUND : EffectTextures.snapshot(binding.objectDefault());
                throw new IllegalStateException("Material texture binding needs its exposed interface snapshot: " + binding.variable());
            }
            @Override public @Nullable TextureParameter object(MaterialInstanceParameters.TextureSlot binding) { return objects.get(binding); }
        };
    }

    private static TextureParameter.Snapshot collectionTexture(MaterialInstanceParameters.TextureSlot slot, NiagaraCollectionSnapshot snapshot) {
        var binding = Objects.requireNonNull(slot.collection());
        boolean component = snapshot.component().contains(binding.path());
        @Nullable TextureParameter value = component
                ? snapshot.component().getTexture(binding.path(), binding.name(), binding.sourceType())
                : binding.systemOverride() ? null : snapshot.world().getTexture(binding.path(), binding.name(), binding.sourceType());
        if (value != null) return Objects.requireNonNull(value.resolve(), "Collection texture returned no snapshot");
        @Nullable Identifier fallback = component ? binding.basePresent() ? binding.baseDefault() : null : slot.objectDefault();
        return fallback == null ? TextureParameter.Snapshot.UNBOUND : EffectTextures.snapshot(fallback);
    }

    private static void read(MaterialInstanceParameters.Slot slot, VectorDataSet system,
                             Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input,
                             double originX, double originY, double originZ,
                             @Nullable EngineValues engineValues, float[] output) {
        String name = Objects.requireNonNull(slot.variable(), "Material variable reader cannot read a fixed uniform");
        var variable = NiagaraRendererParameters.variable(variables, name);
        if (variable != null && variable.floatCount() == output.length && variable.intCount() == 0 && variable.halfCount() == 0) {
            String type = slot.source().sourceType();
            if (type != null && SourceNames.equal(variable.sourceType(), type)) {
                for (int i = 0; i < output.length; i++) output[i] = system.floating(variable.floatStart() + i, 0);
                return;
            }
        }
        var override = NiagaraRendererParameters.parameter(input, name);
        if (override != null && slot.source() == MaterialInstanceParameters.Source.POSITION
                && override.type() == EffectHandle.ParameterType.POSITION) {
            NiagaraPositionParameters.read(override, originX, originY, originZ, output);
            return;
        }
        if (override != null && slot.source() != MaterialInstanceParameters.Source.POSITION
                && override.type().storage() == 0 && override.type().components() == output.length) {
            long[] words = override.words();
            for (int i = 0; i < output.length; i++) output[i] = Float.intBitsToFloat((int)words[i]);
            return;
        }
        if (engineValues != null && !engineValues.read(name, slot.source(), output)) engineValues.readDefault(name, slot.source(), output);
    }
}
