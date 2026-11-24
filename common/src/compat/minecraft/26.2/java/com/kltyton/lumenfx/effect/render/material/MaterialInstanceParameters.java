package com.kltyton.lumenfx.effect.render.material;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.api.client.parameter.DynamicMaterialValues;
import com.kltyton.lumenfx.api.client.parameter.DynamicMaterialValues.ParameterInfo;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Collections;
import java.util.TreeMap;
import java.util.Objects;
import java.util.function.ToIntFunction;
import org.jspecify.annotations.Nullable;
import net.minecraft.resources.Identifier;

/** A definition owns defaults; each renderer instance publishes its own complete parameter snapshot. */
public final class MaterialInstanceParameters {
    public enum Kind { SCALAR, VECTOR, DOUBLE_VECTOR, STATIC_SWITCH, TOON_PROFILE }
    public enum Association { GLOBAL, LAYER, BLEND }
    public enum Source {
        FLOAT(1, "/Script/Niagara.NiagaraFloat"), FLOAT2(2, "/Script/CoreUObject.Vector2f"),
        FLOAT3(3, "/Script/CoreUObject.Vector3f"), FLOAT4(4, "/Script/CoreUObject.Vector4f"),
        COLOR(4, "/Script/CoreUObject.LinearColor"), POSITION(3, "/Script/Niagara.NiagaraPosition"), CONSTANT(0, null);
        private final int components;
        private final @Nullable String sourceType;
        Source(int components, @Nullable String sourceType) { this.components = components; this.sourceType = sourceType; }
        public int components() { return components; }
        public @Nullable String sourceType() { return sourceType; }
    }
    public record Slot(String name, Kind kind, @Nullable String variable, Source source, @Nullable String dataInterface,
                       @Nullable String child, float[] defaults, Association association, int parameterIndex,
                       @Nullable String defaultProfile, boolean rendererConstant) {
        public Slot(String name, Kind kind, @Nullable String variable, Source source, @Nullable String dataInterface,
                    @Nullable String child, float[] defaults) {
            this(name, kind, variable, source, dataInterface, child, defaults, Association.GLOBAL, -1, null, false);
        }
        public Slot(String name, Kind kind, @Nullable String variable, Source source, @Nullable String dataInterface,
                    @Nullable String child, float[] defaults, Association association, int parameterIndex) {
            this(name, kind, variable, source, dataInterface, child, defaults, association, parameterIndex, null, false);
        }
        public Slot {
            Objects.requireNonNull(name); Objects.requireNonNull(kind); Objects.requireNonNull(source); Objects.requireNonNull(association);
            if (defaults.length != (kind == Kind.DOUBLE_VECTOR ? 8 : 4)
                    || source != Source.CONSTANT && (kind == Kind.SCALAR) != (source == Source.FLOAT)
                    || kind == Kind.DOUBLE_VECTOR && source != Source.POSITION && source != Source.CONSTANT
                    || (dataInterface == null) != (child == null))
                throw new IllegalArgumentException("Invalid material instance parameter layout");
            if (kind == Kind.STATIC_SWITCH) {
                if (source != Source.CONSTANT || variable != null || dataInterface != null || child != null
                        || defaultProfile != null || rendererConstant
                        || (defaults[0] != 0 && defaults[0] != 1) || defaults[1] != 0 || defaults[2] != 0 || defaults[3] != 0)
                    throw new IllegalArgumentException("Invalid material static-switch uniform");
            } else if (kind == Kind.TOON_PROFILE) {
                if (source != Source.CONSTANT || variable != null || dataInterface != null || child != null
                        || defaultProfile == null || defaultProfile.isBlank() || rendererConstant)
                    throw new IllegalArgumentException("Invalid material Toon profile uniform");
                defaultProfile = defaultProfile.replace(':', '.');
            } else {
                if (defaultProfile != null) throw new IllegalArgumentException("Only Toon profile parameters carry a default profile");
                if (rendererConstant && source != Source.CONSTANT)
                    throw new IllegalArgumentException("Renderer constant parameter cannot also bind a Niagara variable");
                if (source == Source.CONSTANT ? variable != null || dataInterface != null : variable == null)
                    throw new IllegalArgumentException("Invalid material parameter source");
            }
            if (association == Association.GLOBAL ? parameterIndex != -1 : parameterIndex < 0)
                throw new IllegalArgumentException("Invalid material parameter association index");
            defaults = defaults.clone();
        }
        @Override public float[] defaults() { return defaults.clone(); }
        public int floats() { return kind == Kind.DOUBLE_VECTOR ? 8 : 4; }
    }
    public record TextureCollection(String path, String name, String sourceType, boolean systemOverride,
                                    boolean basePresent, @Nullable Identifier baseDefault) {
        public TextureCollection {
            Objects.requireNonNull(path); Objects.requireNonNull(name); Objects.requireNonNull(sourceType);
            if (!basePresent && baseDefault != null) throw new IllegalArgumentException("Collection texture has no base object entry");
        }
    }
    public record TextureObjectInput(String variable, boolean defaultPresent, @Nullable Identifier defaultTexture) {
        public TextureObjectInput {
            Objects.requireNonNull(variable);
            if (!defaultPresent && defaultTexture != null) throw new IllegalArgumentException("Renderer object input has no default entry");
        }
    }
    public record TextureSlot(String name, String sampler, String variable, @Nullable String dataInterface,
                              @Nullable String child, Identifier baseDefault, @Nullable Identifier objectDefault, boolean staticExposure,
                              @Nullable String interfaceId, @Nullable JsonObject interfaceDefinition,
                              @Nullable TextureCollection collection, List<TextureObjectInput> objectInputs,
                              @Nullable String sourceType, int typeFlags) {
        public TextureSlot(String name, String sampler, String variable, @Nullable String dataInterface,
                           @Nullable String child, Identifier baseDefault, @Nullable Identifier objectDefault, boolean staticExposure,
                           @Nullable String interfaceId, @Nullable JsonObject interfaceDefinition,
                           @Nullable TextureCollection collection, List<TextureObjectInput> objectInputs) {
            this(name, sampler, variable, dataInterface, child, baseDefault, objectDefault, staticExposure,
                    interfaceId, interfaceDefinition, collection, objectInputs, null, 0);
        }
        public TextureSlot(String name, String sampler, String variable, @Nullable String dataInterface,
                           @Nullable String child, Identifier baseDefault, @Nullable Identifier objectDefault, boolean staticExposure,
                           @Nullable String interfaceId, @Nullable JsonObject interfaceDefinition,
                           @Nullable TextureCollection collection) {
            this(name, sampler, variable, dataInterface, child, baseDefault, objectDefault, staticExposure,
                    interfaceId, interfaceDefinition, collection, List.of());
        }
        public TextureSlot(String name, String sampler, String variable, @Nullable String dataInterface,
                           @Nullable String child, Identifier baseDefault, @Nullable Identifier objectDefault, boolean staticExposure,
                           @Nullable String interfaceId, @Nullable JsonObject interfaceDefinition) {
            this(name, sampler, variable, dataInterface, child, baseDefault, objectDefault, staticExposure,
                    interfaceId, interfaceDefinition, null);
        }
        public TextureSlot(String name, String sampler, String variable, @Nullable String dataInterface,
                           @Nullable String child, Identifier baseDefault, @Nullable Identifier objectDefault, boolean staticExposure,
                           @Nullable String interfaceId) {
            this(name, sampler, variable, dataInterface, child, baseDefault, objectDefault, staticExposure, interfaceId, null);
        }
        public TextureSlot(String name, String sampler, String variable, @Nullable String dataInterface,
                           @Nullable String child, Identifier baseDefault, @Nullable Identifier objectDefault, boolean staticExposure) {
            this(name, sampler, variable, dataInterface, child, baseDefault, objectDefault, staticExposure, null);
        }
        public TextureSlot(String name, String sampler, String variable, @Nullable String dataInterface,
                           @Nullable String child, Identifier baseDefault, @Nullable Identifier objectDefault) {
            this(name, sampler, variable, dataInterface, child, baseDefault, objectDefault, false);
        }
        public TextureSlot {
            Objects.requireNonNull(name); Objects.requireNonNull(sampler); Objects.requireNonNull(variable); Objects.requireNonNull(baseDefault);
            if (typeFlags < 0 || typeFlags > 255) throw new IllegalArgumentException("Invalid material object type flags");
            if ((dataInterface == null) != (child == null)) throw new IllegalArgumentException("Invalid material texture interface binding");
            if (collection != null && dataInterface != null) throw new IllegalArgumentException("Collection object texture cannot also be a DI exposure");
            objectInputs = List.copyOf(objectInputs);
            if (!objectInputs.isEmpty() && (dataInterface != null || collection != null))
                throw new IllegalArgumentException("Renderer object inputs cannot also be collection or DI textures");
            interfaceDefinition = interfaceDefinition == null ? null : interfaceDefinition.deepCopy();
        }
        @Override public @Nullable JsonObject interfaceDefinition() {
            return interfaceDefinition == null ? null : interfaceDefinition.deepCopy();
        }
    }
    @FunctionalInterface
    public interface TextureValues {
        TextureParameter.Snapshot resolve(TextureSlot binding);
        default @Nullable TextureParameter object(TextureSlot binding) { return null; }
    }
    public record Publication(float[] values, Map<String, TextureParameter> textureParameters, @Nullable TextureValues exposedTextures,
                              Map<String, VirtualTextureRead.StreamedValue> streamedTextures) {
        public Publication(float[] values, Map<String, TextureParameter> textureParameters, @Nullable TextureValues exposedTextures) {
            this(values, textureParameters, exposedTextures, Map.of());
        }
        public Publication {
            values = values.clone();
            streamedTextures = Map.copyOf(streamedTextures);
            if (textureParameters.isEmpty()) textureParameters = Map.of();
            else {
                var parameters = new TreeMap<String, TextureParameter>(SourceNames.ASCII_CASE_INSENSITIVE);
                textureParameters.forEach((name, value) -> {
                    if (parameters.putIfAbsent(name, Objects.requireNonNull(value)) != null)
                        throw new IllegalArgumentException("Texture parameters contain duplicate FName spellings: " + name);
                });
                textureParameters = Collections.unmodifiableMap(parameters);
            }
        }
        @Override public float[] values() { return values.clone(); }
    }
    private record Captured(Publication publication, Map<String, TextureParameter.Snapshot> textures) { }
    public static final MaterialInstanceParameters NONE = new MaterialInstanceParameters(List.of(), List.of(), false, null);
    private final List<Slot> layout;
    private final List<TextureSlot> textureLayout;
    private final Map<String, ParameterInfo> fontSamplers;
    private final Map<String, Identifier> parentFontPages;
    private final Map<String, ParameterInfo> dynamicTextureSamplers;
    private final Set<String> textureSamplers;
    private final float[] defaults;
    private final @Nullable ParameterInfo[] dynamicNumericSlots;
    private final boolean hasToonProfileSlots;
    private final boolean hasRendererBindings;
    private final boolean mutable;
    private final @Nullable MaterialDefinition programDefinition;
    private final @Nullable DynamicMaterialValues dynamics;
    private volatile Publication publication;
    private volatile @Nullable Captured captured;

    private MaterialInstanceParameters(List<Slot> layout, List<TextureSlot> textures, boolean mutable, @Nullable MaterialDefinition programDefinition) {
        this(layout, textures, Map.of(), Map.of(), Map.of(), mutable, programDefinition, null);
    }

    private MaterialInstanceParameters(List<Slot> layout, List<TextureSlot> textures, Map<String, ParameterInfo> fonts,
                                       Map<String, Identifier> parentFontPages, Map<String, ParameterInfo> dynamicTextures, boolean mutable,
                                       @Nullable MaterialDefinition programDefinition, @Nullable DynamicMaterialValues dynamics) {
        this.layout = List.copyOf(layout); this.mutable = mutable;
        textureLayout = List.copyOf(textures);
        fontSamplers = Map.copyOf(fonts);
        this.parentFontPages = Map.copyOf(parentFontPages);
        dynamicTextureSamplers = Map.copyOf(dynamicTextures);
        if (!fontSamplers.keySet().containsAll(this.parentFontPages.keySet()))
            throw new IllegalArgumentException("Inherited font pages require a material font sampler");
        var samplers = new HashSet<String>();
        for (var texture : textures) if (!samplers.add(texture.sampler())) throw new IllegalArgumentException("Duplicate material texture sampler " + texture.sampler());
        samplers.addAll(fontSamplers.keySet());
        for (String sampler : dynamicTextureSamplers.keySet()) {
            if (samplers.contains(sampler)) throw new IllegalArgumentException("Duplicate material texture sampler " + sampler);
            if (dynamics != null) samplers.add(sampler);
        }
        textureSamplers = Set.copyOf(samplers);
        this.programDefinition = programDefinition;
        this.dynamics = dynamics;
        int count = 0;
        for (var slot : layout) count = Math.addExact(count, slot.floats());
        defaults = new float[count];
        dynamicNumericSlots = new ParameterInfo[layout.size()];
        boolean toonSlots = false;
        boolean rendererBound = !textures.isEmpty();
        int offset = 0;
        for (int index = 0; index < layout.size(); index++) {
            var slot = layout.get(index);
            System.arraycopy(slot.defaults, 0, defaults, offset, slot.floats());
            offset += slot.floats();
            if (slot.kind() == Kind.TOON_PROFILE) toonSlots = true;
            if (slot.source() != Source.CONSTANT || slot.rendererConstant()) rendererBound = true;
            if (!slot.name().isBlank() && slot.kind() != Kind.STATIC_SWITCH)
                dynamicNumericSlots[index] = new ParameterInfo(slot.name(), slot.association(), slot.parameterIndex());
        }
        hasToonProfileSlots = toonSlots;
        hasRendererBindings = rendererBound;
        publication = new Publication(defaults, Map.of(), null);
    }
    public static MaterialInstanceParameters definition(List<Slot> layout) {
        return definition(layout, List.of());
    }
    public static MaterialInstanceParameters definition(List<Slot> layout, List<TextureSlot> textures) {
        return definition(layout, textures, Map.of());
    }
    public static MaterialInstanceParameters definition(List<Slot> layout, List<TextureSlot> textures, Map<String, ParameterInfo> fonts) {
        return definition(layout, textures, fonts, Map.of());
    }
    public static MaterialInstanceParameters definition(List<Slot> layout, List<TextureSlot> textures,
                                                        Map<String, ParameterInfo> fonts, Map<String, Identifier> parentFontPages) {
        return definition(layout, textures, fonts, parentFontPages, Map.of());
    }
    public static MaterialInstanceParameters definition(List<Slot> layout, List<TextureSlot> textures,
                                                        Map<String, ParameterInfo> fonts, Map<String, Identifier> parentFontPages,
                                                        Map<String, ParameterInfo> dynamicTextures) {
        return layout.isEmpty() && textures.isEmpty() && fonts.isEmpty() && parentFontPages.isEmpty() && dynamicTextures.isEmpty() ? NONE
                : new MaterialInstanceParameters(layout, textures, fonts, parentFontPages, dynamicTextures, false, null, null);
    }
    public MaterialInstanceParameters instantiate() {
        return instantiate(programDefinition);
    }
    public MaterialInstanceParameters instantiate(@Nullable MaterialDefinition program) {
        return instantiate(program, dynamics);
    }
    public MaterialInstanceParameters instantiate(@Nullable MaterialDefinition program, @Nullable DynamicMaterialValues values) {
        boolean rendererVirtualTexture = program != null && program.virtualTextureReads().stream().anyMatch(read -> read.rendererBinding() != null);
        return !hasBindings() && values == null && !rendererVirtualTexture ? NONE
                : new MaterialInstanceParameters(layout, textureLayout, fontSamplers, parentFontPages, dynamicTextureSamplers, true, program, values);
    }
    public @Nullable DynamicMaterialValues dynamicValues() { return dynamics; }
    public @Nullable MaterialDefinition programDefinition() { return programDefinition; }
    public List<Slot> layout() { return layout; }
    public List<TextureSlot> textureLayout() { return textureLayout; }
    public Map<String, ParameterInfo> fontSamplers() { return fontSamplers; }
    public Map<String, Identifier> parentFontPages() { return parentFontPages; }
    public Map<String, ParameterInfo> dynamicTextureSamplers() { return dynamicTextureSamplers; }
    public boolean hasBindings() { return !layout.isEmpty() || !textureLayout.isEmpty() || !fontSamplers.isEmpty(); }
    public boolean hasRendererBindings() { return hasRendererBindings; }
    public @Nullable ParameterInfo slotInfo(int index) { return dynamicNumericSlots[index]; }
    public Publication publication() { return publication; }
    public int byteSize() { return Math.multiplyExact(defaults.length, Float.BYTES); }
    public float[] defaults() { return defaults.clone(); }
    public void publish(float[] parameters) {
        Publication previous = publication;
        publish(parameters, previous.textureParameters(), previous.exposedTextures());
    }
    public void publish(float[] parameters, Map<String, TextureParameter> textures, @Nullable TextureValues exposedTextures) {
        publish(parameters, textures, exposedTextures, publication.streamedTextures());
    }
    public void publish(float[] parameters, Map<String, TextureParameter> textures, @Nullable TextureValues exposedTextures,
                        Map<String, VirtualTextureRead.StreamedValue> streamedTextures) {
        if (!mutable) throw new IllegalStateException("Material definition defaults cannot be modified");
        if (parameters.length != defaults.length) throw new IllegalArgumentException("Material parameter snapshot does not match its layout");
        publication = new Publication(parameters, textures, exposedTextures, streamedTextures);
    }
    public void capturedTextures(Publication state, Map<String, TextureParameter.Snapshot> textures) {
        if (state != publication) throw new IllegalStateException("Material parameters changed during texture capture");
        if (!textures.keySet().equals(textureSamplers)) throw new IllegalArgumentException("Captured textures do not match the material sampler layout");
        captured = new Captured(state, Map.copyOf(textures));
    }
    public TextureParameter.@Nullable Snapshot texture(String sampler) {
        if (!textureSamplers.contains(sampler)) return null;
        Captured ready = captured;
        if (ready == null || ready.publication() != publication) throw new IllegalStateException("Material texture was not captured for its current parameter publication");
        var value = ready.textures().get(sampler);
        return dynamicTextureSamplers.containsKey(sampler) && !value.bound() ? null : value;
    }
    public void write(ByteBuffer destination) {
        write(publication, destination);
    }
    public void write(Publication state, ByteBuffer destination) {
        write(state, dynamics == null ? null : dynamics.snapshot(), destination);
    }
    public void write(Publication state, DynamicMaterialValues.@Nullable Snapshot edits, ByteBuffer destination) {
        write(state, edits, destination, null);
    }
    public void write(Publication state, DynamicMaterialValues.@Nullable Snapshot edits, ByteBuffer destination,
                      @Nullable ToIntFunction<String> toonProfileIndex) {
        if (!hasToonProfileSlots && (edits == null || !edits.hasNumericValues())) {
            for (float value : state.values) destination.putFloat(value);
            return;
        }
        int offset = 0;
        for (int slotIndex = 0; slotIndex < layout.size(); slotIndex++) {
            var slot = layout.get(slotIndex);
            var info = dynamicNumericSlots[slotIndex];
            if (slot.kind() == Kind.TOON_PROFILE) {
                String selected = edits != null && info != null ? edits.toonProfile(info) : null;
                if (selected == null) selected = Objects.requireNonNull(slot.defaultProfile());
                float resolved = toonProfileIndex == null ? state.values[offset] : toonProfileIndex.applyAsInt(selected);
                destination.putFloat(resolved).putFloat(0).putFloat(0).putFloat(0);
                offset += slot.floats();
                continue;
            }
            Float scalar = edits != null && info != null && slot.kind() == Kind.SCALAR ? edits.scalar(info) : null;
            var vector = edits != null && info != null && slot.kind() == Kind.VECTOR ? edits.vector(info) : null;
            var precise = edits != null && info != null && slot.kind() == Kind.DOUBLE_VECTOR ? edits.doubleVector(info) : null;
            if (scalar != null) {
                destination.putFloat(scalar);
                for (int index = 1; index < slot.floats(); index++) destination.putFloat(state.values[offset + index]);
            } else if (vector != null) {
                destination.putFloat(vector.x()).putFloat(vector.y()).putFloat(vector.z()).putFloat(vector.w());
            } else if (precise != null) {
                float x = (float)precise.x(), y = (float)precise.y(), z = (float)precise.z(), w = (float)precise.w();
                destination.putFloat(x).putFloat(y).putFloat(z).putFloat(w);
                destination.putFloat((float)(precise.x() - x)).putFloat((float)(precise.y() - y))
                        .putFloat((float)(precise.z() - z)).putFloat((float)(precise.w() - w));
            } else for (int index = 0; index < slot.floats(); index++) destination.putFloat(state.values[offset + index]);
            offset += slot.floats();
        }
    }
}
