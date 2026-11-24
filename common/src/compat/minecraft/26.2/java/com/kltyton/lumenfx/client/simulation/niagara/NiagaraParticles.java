package com.kltyton.lumenfx.client.simulation.niagara;

import com.kltyton.lumenfx.effect.render.RibbonSettings;

import com.kltyton.lumenfx.client.render.niagara.gpu.mesh.dynamic.NiagaraDynamicMeshRenderSource.Resolver;
import com.kltyton.lumenfx.client.render.niagara.gpu.splat.NiagaraCpuSplatCollector;
import com.kltyton.lumenfx.client.render.volume.NiagaraVolumeInstance;
import com.kltyton.lumenfx.client.simulation.niagara.splat.NiagaraSplatParticles;
import com.kltyton.lumenfx.client.simulation.niagara.volume.NiagaraVolumeInstances;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.client.simulation.niagara.renderer.NiagaraRendererFields.Inputs;
import com.kltyton.lumenfx.effect.render.splat.SplatRendererSettings;
import com.kltyton.lumenfx.effect.EffectDefinition;
import com.kltyton.lumenfx.effect.EmitterDefinition;
import net.minecraft.world.phys.Vec3;

import com.kltyton.lumenfx.effect.niagara.NiagaraComponentDefaults;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraParticleReadTypes;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import org.jspecify.annotations.Nullable;
import com.kltyton.lumenfx.effect.render.SpriteCoverage;
import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.client.simulation.niagara.render.NaniteInstanceFloatBindings.FloatComponent;
import java.util.List;
import java.util.Map;

/** Current frame's typed particle output, consumed after its emitter worker has completed. */
public final class NiagaraParticles {
    private final com.kltyton.lumenfx.client.simulation.niagara.material.NiagaraMaterialInstances materialInstances;
    private final NiagaraDefinition.Emitter emitterDefinition;
    private final NiagaraDefinition.Renderer rendererDefinition;
    private @Nullable RibbonSettings ribbonSettings;
    void setRibbonSettings(RibbonSettings settings) { ribbonSettings = settings; }
    public RibbonSettings ribbonSettings(RibbonSettings defaults) {
        return ribbonSettings == null ? defaults : ribbonSettings;
    }
    private record FloatBinding(int component, int type) { }
    private final FloatBinding position, color, width, age, normalizedAge, lifetime, random, rotation, customSort;
    private final FloatBinding velocity, facing, alignment, cameraOffset, pivotOffset;
    private final FloatBinding ribbonUvDistance, ribbonU0, ribbonV0, ribbonU1, ribbonV1;
    private final FloatBinding ribbonFacing, ribbonTwist;
    private final FloatBinding meshScale, meshOrientation;
    private final FloatBinding previousPosition, previousMeshScale, previousMeshOrientation, previousVelocity, previousCameraOffset;
    private final FloatBinding previousSpriteSize, previousSpriteRotation, previousSpriteFacing, previousSpriteAlignment;
    private final FloatBinding previousRibbonWidth, previousRibbonFacing, previousRibbonTwist;
    private final com.kltyton.lumenfx.effect.render.ParticleMotionSettings motion;
    private EffectHandle.Pose previousOwnerPose;
    private @Nullable Resolver dynamicMeshes;
    private @Nullable NiagaraVolumeInstances volumeInstances;
    private boolean componentVelocity = true;
    private final int meshIndexAttribute;
    private final NiagaraDefinition.RendererAttributeBinding meshIndexBinding;
    private final float[] hostMeshScale = new float[3], hostMeshOrientation = new float[4];
    private final float[] hostPreviousPosition = new float[3], hostPreviousScale = new float[3], hostPreviousOrientation = new float[4];
    private final float[] hostPreviousVelocity = new float[3], hostPreviousOffset = new float[1];
    private final float[] hostPreviousSpriteSize = new float[2], hostPreviousSpriteRotation = new float[1];
    private final float[] hostPreviousSpriteFacing = new float[3], hostPreviousSpriteAlignment = new float[3];
    private boolean hasHostPreviousPosition;
    private boolean hasHostPreviousScale, hasHostPreviousOrientation, hasHostPreviousVelocity, hasHostPreviousOffset;
    private boolean hasHostPreviousSize, hasHostPreviousRotation, hasHostPreviousFacing, hasHostPreviousAlignment;
    private int hostMeshIndex;
    private boolean hasHostMeshIndex;
    private List<FloatComponent> naniteInstanceFloats = List.of();
    private final int uniqueId, subImage, subImageType;
    private final int visibilityAttribute, rendererVisibility;
    private final String visibilityBinding;
    private final String enabledBinding;
    private final boolean visibilityParticleBinding;
    private final int ribbonLink, ribbonLinkType, ribbonId, ribbonIdComponents;
    private final boolean ribbon;
    private final boolean mesh;
    private final boolean localSpace;
    private final boolean emitterSource;
    private final Map<String, NiagaraDefinition.RendererAttributeBinding> rendererBindings;
    private final float[] hostPosition = new float[3], hostColor = new float[4], hostSize = new float[2];
    private final float[] hostRotation = new float[1], hostNormalizedAge = new float[1], hostRandom = new float[1];
    private final float[] hostVelocity = new float[3], hostFacing = new float[3], hostAlignment = new float[3];
    private final float[] hostCameraOffset = new float[1], hostPivot = new float[2];
    private final String facingMode, alignmentMode;
    private final float pivotX, pivotY, facingBlendMin, facingBlendMax;
    private int resolvedFacing, resolvedAlignment;
    private boolean hasHostPosition;
    private final boolean sortOnlyWhenTranslucent;
    private final boolean distanceCulling;
    private final float minimumDistanceSquared, maximumDistanceSquared;
    private final SpriteCoverage coverage;
    private final float subImageColumns, subImageRows;
    private final boolean subImageBlend;
    private final String subImageBinding;
    private final boolean subImageParticleBinding;
    private final float subImageDefault;
    private final String uvScaleBinding;
    private final boolean uvScaleParticleBinding;
    private final int uvScale, uvScaleType;
    private final String dynamicBinding;
    private final boolean dynamicParticleBinding;
    private final int dynamicParameter, dynamicType;
    private final int dynamicChannelMask;
    private final FloatBinding[] extraDynamic = new FloatBinding[3];
    private final NiagaraDefinition.RendererAttributeBinding[] extraBindings = new NiagaraDefinition.RendererAttributeBinding[3];
    private final float[][] hostExtra = new float[3][4];
    private final boolean[] hasHostExtra = new boolean[3];
    private final float[] hostDynamic = new float[4];
    private boolean hasHostDynamic;
    private boolean hostVisible = true;
    private boolean naniteEnabled = true;
    private Map<Key, byte[]> naniteRendererValues = Map.of();
    private float hostSubImage;
    private float hostUvScaleX = 1, hostUvScaleY = 1;
    private VectorDataSet data;
    private EffectHandle.Input input;
    private VectorDataSet system;
    private Map<String, NiagaraDefinition.Variable> variables;
    private @Nullable NiagaraParameterStore rendererStore;
    private java.util.function.Supplier<com.kltyton.lumenfx.effect.niagara.NiagaraBounds> componentBounds;
    private double originX, originY, originZ;
    private double updateWorldSeconds = Double.NaN;
    private final com.kltyton.lumenfx.effect.render.light.LightRendererSettings lightSettings;
    private final com.kltyton.lumenfx.client.simulation.niagara.light.NiagaraLightParticles lights;
    private final com.kltyton.lumenfx.effect.render.decal.DecalRendererSettings decalSettings;
    private final com.kltyton.lumenfx.client.simulation.niagara.decal.NiagaraDecalParticles decals;
    private final @Nullable SplatRendererSettings splatSettings;
    private final @Nullable NiagaraSplatParticles splats;
    private long sourcePrimitive;

    NiagaraParticles(NiagaraDefinition.Emitter source, NiagaraDefinition.Renderer renderer, boolean ribbon, boolean localSpace) {
        this(source, renderer, ribbon, localSpace, false);
    }

    NiagaraParticles(NiagaraDefinition.Emitter source, NiagaraDefinition.Renderer renderer, boolean ribbon, boolean localSpace, boolean mesh) {
        this(source, renderer, ribbon, localSpace, mesh, null);
    }

    NiagaraParticles(NiagaraDefinition.Emitter source, NiagaraDefinition.Renderer renderer, boolean ribbon, boolean localSpace, boolean mesh,
                     com.kltyton.lumenfx.effect.render.light.LightRendererSettings lightSettings) {
        this(source, renderer, ribbon, localSpace, mesh, lightSettings, null);
    }

    NiagaraParticles(NiagaraDefinition.Emitter source, NiagaraDefinition.Renderer renderer, boolean ribbon, boolean localSpace, boolean mesh,
                     com.kltyton.lumenfx.effect.render.light.LightRendererSettings lightSettings,
                     com.kltyton.lumenfx.effect.render.decal.DecalRendererSettings decalSettings) {
        this(source, renderer, ribbon, localSpace, mesh, lightSettings, decalSettings, null);
    }

    public NiagaraParticles(NiagaraDefinition.Emitter source, NiagaraDefinition.Renderer renderer, boolean ribbon, boolean localSpace, boolean mesh,
                     com.kltyton.lumenfx.effect.render.light.LightRendererSettings lightSettings,
                     com.kltyton.lumenfx.effect.render.decal.DecalRendererSettings decalSettings,
                     com.kltyton.lumenfx.effect.render.material.MaterialInstanceParameters.@Nullable TextureValues exposedTextures) {
        this(source, renderer, ribbon, localSpace, mesh, lightSettings, decalSettings, null, exposedTextures);
    }

    public NiagaraParticles(NiagaraDefinition.Emitter source, NiagaraDefinition.Renderer renderer, boolean ribbon, boolean localSpace, boolean mesh,
                     com.kltyton.lumenfx.effect.render.light.LightRendererSettings lightSettings,
                     com.kltyton.lumenfx.effect.render.decal.DecalRendererSettings decalSettings,
                     @Nullable SplatRendererSettings splatSettings,
                     com.kltyton.lumenfx.effect.render.material.MaterialInstanceParameters.@Nullable TextureValues exposedTextures) {
        materialInstances = new com.kltyton.lumenfx.client.simulation.niagara.material.NiagaraMaterialInstances(null, exposedTextures);
        emitterDefinition = source;
        rendererDefinition = renderer;
        this.decalSettings = decalSettings;
        decals = decalSettings == null ? null : new com.kltyton.lumenfx.client.simulation.niagara.decal.NiagaraDecalParticles(source, renderer);
        this.lightSettings = lightSettings;
        lights = lightSettings == null ? null : new com.kltyton.lumenfx.client.simulation.niagara.light.NiagaraLightParticles(source, renderer);
        this.splatSettings = splatSettings;
        splats = splatSettings == null ? null : new NiagaraSplatParticles(source, renderer);
        List<NiagaraDefinition.Attribute> attributes = source.attributes();
        this.ribbon = ribbon;
        this.mesh = mesh;
        motion = renderer.motion();
        this.localSpace = localSpace;
        emitterSource = !ribbon && renderer.sourceMode().equals("Emitter");
        rendererBindings = renderer.attributeBindings();
        meshScale = boundParticle(attributes, "Scale", 3);
        meshOrientation = boundParticle(attributes, "MeshOrientation", 4);
        previousPosition = boundParticle(attributes, "PreviousPosition", 3);
        previousMeshScale = boundParticle(attributes, "PreviousScale", 3);
        previousMeshOrientation = boundParticle(attributes, "PreviousMeshOrientation", 4);
        previousVelocity = boundParticle(attributes, "PreviousVelocity", 3);
        previousCameraOffset = boundParticle(attributes, "PreviousCameraOffset", 1);
        previousSpriteSize = boundParticle(attributes, "PreviousSpriteSize", 2);
        previousSpriteRotation = boundParticle(attributes, "PreviousSpriteRotation", 1);
        previousSpriteFacing = boundParticle(attributes, "PreviousSpriteFacing", 3);
        previousSpriteAlignment = boundParticle(attributes, "PreviousSpriteAlignment", 3);
        previousRibbonWidth = boundParticle(attributes, "PreviousRibbonWidth", 1);
        previousRibbonFacing = boundParticle(attributes, "PreviousRibbonFacing", 3);
        previousRibbonTwist = boundParticle(attributes, "PreviousRibbonTwist", 1);
        meshIndexBinding = rendererBindings.get("MeshIndex");
        var meshIndex = emitterSource || meshIndexBinding != null && !meshIndexBinding.particle() ? null
                : NiagaraParticleReadTypes.renderer(attributes, meshIndexBinding == null ? "MeshIndex" : meshIndexBinding.name(), "Int");
        meshIndexAttribute = meshIndex == null ? -1 : meshIndex.component();
        facingMode = renderer.facingMode(); alignmentMode = renderer.alignment();
        pivotX = renderer.pivotX(); pivotY = renderer.pivotY();
        facingBlendMin = renderer.facingBlendMin(); facingBlendMax = renderer.facingBlendMax();
        sortOnlyWhenTranslucent = renderer.sortOnlyWhenTranslucent();
        distanceCulling = renderer.distanceCulling();
        minimumDistanceSquared = renderer.minCameraDistance() * renderer.minCameraDistance();
        maximumDistanceSquared = renderer.maxCameraDistance() * renderer.maxCameraDistance();
        visibilityBinding = renderer.visibilityBinding();
        enabledBinding = renderer.enabledBinding();
        visibilityParticleBinding = renderer.visibilityParticleBinding();
        rendererVisibility = renderer.rendererVisibility();
        NiagaraDefinition.Attribute visibility = ribbon || emitterSource || !visibilityParticleBinding ? null
                : NiagaraParticleReadTypes.renderer(attributes, visibilityBinding, "Int");
        visibilityAttribute = visibility == null ? -1 : visibility.component();
        coverage = renderer.coverage();
        subImageColumns = renderer.subImageColumns(); subImageRows = renderer.subImageRows();
        subImageBlend = renderer.subImageBlend();
        subImageBinding = renderer.subImageBinding();
        subImageParticleBinding = renderer.subImageParticleBinding();
        subImageDefault = renderer.subImageParticleBinding() ? 0 : renderer.subImageDefault();
        uvScaleBinding = renderer.uvScaleBinding();
        uvScaleParticleBinding = renderer.uvScaleParticleBinding();
        dynamicBinding = renderer.dynamicParameterBinding();
        dynamicParticleBinding = renderer.dynamicParameterParticleBinding();
        dynamicChannelMask = renderer.dynamicParameterChannelMask();
        for (int slot = 1; slot <= 3; slot++) {
            String semantic = "DynamicMaterial" + slot;
            var binding = rendererBindings.get(semantic);
            if (binding == null) binding = new NiagaraDefinition.RendererAttributeBinding("Particles.DynamicMaterialParameter" + slot, true);
            extraBindings[slot - 1] = binding;
            if (!emitterSource && binding.particle()) {
                var attribute = optionalAttribute(attributes, binding.name());
                if (attribute != null && attribute.count() == 4 && (attribute.type() == 0 || attribute.type() == 2))
                    extraDynamic[slot - 1] = new FloatBinding(attribute.component(), attribute.type());
            }
        }
        position = boundParticle(attributes, "Position", 3); color = boundParticle(attributes, "Color", 4);
        age = emitterSource ? null : floatingBinding(attributes, "Age", 1);
        var normalizedBinding = rendererBindings.get("NormalizedAge");
        var ribbonAge = ribbon && (normalizedBinding == null || normalizedBinding.particle())
                ? NiagaraParticleReadTypes.renderer(attributes,
                        normalizedBinding == null ? "NormalizedAge" : normalizedBinding.name(), "Float") : null;
        normalizedAge = ribbon ? ribbonAge == null ? null : new FloatBinding(ribbonAge.component(), ribbonAge.type())
                : boundParticle(attributes, "NormalizedAge", 1);
        lifetime = emitterSource ? null : floatingBinding(attributes, "Lifetime", 1);
        random = boundParticle(attributes, "MaterialRandom", 1);
        velocity = boundParticle(attributes, "Velocity", 3);
        facing = boundParticle(attributes, "SpriteFacing", 3);
        alignment = boundParticle(attributes, "SpriteAlignment", 3);
        cameraOffset = boundParticle(attributes, "CameraOffset", 1);
        pivotOffset = boundParticle(attributes, "PivotOffset", 2);
        NiagaraDefinition.Attribute unique = NiagaraParticleReadTypes.renderer(attributes, "UniqueID", "Int");
        uniqueId = unique == null || emitterSource ? -1 : unique.component();
        rotation = boundParticle(attributes, "SpriteRotation", 1);
        width = ribbon ? boundParticle(attributes, "RibbonWidth", 1) : boundParticle(attributes, "SpriteSize", 2);
        ribbonUvDistance = ribbon ? boundParticle(attributes, "RibbonUVDistance", 1) : null;
        ribbonU0 = ribbon ? boundParticle(attributes, "RibbonU0Override", 1) : null;
        ribbonV0 = ribbon ? boundParticle(attributes, "RibbonV0RangeOverride", 2) : null;
        ribbonU1 = ribbon ? boundParticle(attributes, "RibbonU1Override", 1) : null;
        ribbonV1 = ribbon ? boundParticle(attributes, "RibbonV1RangeOverride", 2) : null;
        ribbonFacing = ribbon ? boundParticle(attributes, "RibbonFacing", 3) : null;
        ribbonTwist = ribbon ? boundParticle(attributes, "RibbonTwist", 1) : null;
        NiagaraDefinition.Attribute sort = renderer.customSortParticleBinding() ? optionalAttribute(attributes, renderer.customSortBinding()) : null;
        customSort = !emitterSource && sort != null && (sort.type() == 0 || sort.type() == 2) && sort.count() == 1
                ? new FloatBinding(sort.component(), sort.type()) : null;
        NiagaraDefinition.Attribute link = ribbon ? NiagaraParticleReadTypes.renderer(attributes, renderer.ribbonLinkBinding(), "Float") : null;
        if (link != null) {
            ribbonLink = link.component(); ribbonLinkType = link.type();
        } else if (ribbon && renderer.ribbonUniqueFallback() && uniqueId >= 0) {
            ribbonLink = uniqueId; ribbonLinkType = 1;
        } else {
            ribbonLink = ribbon && normalizedAge != null ? normalizedAge.component() : -1;
            ribbonLinkType = ribbonLink < 0 ? -1 : normalizedAge.type();
        }
        NiagaraDefinition.Attribute id = ribbon ? NiagaraParticleReadTypes.ribbonId(attributes, renderer) : null;
        ribbonId = id == null ? -1 : id.component(); ribbonIdComponents = id == null ? 0 : id.count();
        NiagaraDefinition.Attribute subImageAttribute = ribbon || emitterSource || renderer.subImageBinding() == null || !renderer.subImageParticleBinding() ? null
                : optionalAttribute(attributes, renderer.subImageBinding());
        if (subImageAttribute != null && (subImageAttribute.count() != 1 || subImageAttribute.type() != 0 && subImageAttribute.type() != 2))
            subImageAttribute = null;
        subImage = subImageAttribute == null ? -1 : subImageAttribute.component();
        subImageType = subImageAttribute == null ? -1 : subImageAttribute.type();
        NiagaraDefinition.Attribute uvAttribute = ribbon || emitterSource || uvScaleBinding == null || !uvScaleParticleBinding ? null
                : optionalAttribute(attributes, uvScaleBinding);
        if (uvAttribute != null && (uvAttribute.count() != 2 || uvAttribute.type() != 0 && uvAttribute.type() != 2)) uvAttribute = null;
        uvScale = uvAttribute == null ? -1 : uvAttribute.component();
        uvScaleType = uvAttribute == null ? -1 : uvAttribute.type();
        NiagaraDefinition.Attribute dynamicAttribute = emitterSource || dynamicBinding == null || !dynamicParticleBinding ? null
                : optionalAttribute(attributes, dynamicBinding);
        if (dynamicAttribute != null && (dynamicAttribute.count() != 4 || dynamicAttribute.type() != 0 && dynamicAttribute.type() != 2)) dynamicAttribute = null;
        dynamicParameter = dynamicAttribute == null ? -1 : dynamicAttribute.component();
        dynamicType = dynamicAttribute == null ? -1 : dynamicAttribute.type();
    }

    public void bind(VectorDataSet data, double originX, double originY, double originZ,
              VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input) {
        bind(data, originX, originY, originZ, system, variables, input, null);
    }

    public void bind(VectorDataSet data, double originX, double originY, double originZ,
              VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input,
              @Nullable NiagaraParameterStore rendererStore) {
        this.data = data; this.input = input; this.originX = originX; this.originY = originY; this.originZ = originZ;
        this.system = system; this.variables = variables; this.rendererStore = rendererStore;
        java.util.Arrays.fill(hostPosition, 0);
        if (!localSpace) {
            var owner = input.pose().position();
            hostPosition[0] = (float)(owner.x * 100 - originX);
            hostPosition[1] = (float)(owner.z * 100 - originY);
            hostPosition[2] = (float)(owner.y * 100 - originZ);
        }
        java.util.Arrays.fill(hostColor, 1);
        java.util.Arrays.fill(hostSize, 50);
        hostRotation[0] = hostNormalizedAge[0] = hostRandom[0] = 0;
        java.util.Arrays.fill(hostVelocity, 0);
        java.util.Arrays.fill(hostFacing, 0); hostFacing[0] = 1;
        java.util.Arrays.fill(hostAlignment, 0); hostAlignment[0] = 1;
        hostCameraOffset[0] = 0; hostPivot[0] = pivotX; hostPivot[1] = pivotY;
        hasHostPosition = host("Position", system, variables, input, hostPosition);
        host("Color", system, variables, input, hostColor);
        host("SpriteSize", system, variables, input, hostSize);
        host("SpriteRotation", system, variables, input, hostRotation);
        host("NormalizedAge", system, variables, input, hostNormalizedAge);
        host("MaterialRandom", system, variables, input, hostRandom);
        host("Velocity", system, variables, input, hostVelocity);
        boolean hasHostFacing = host("SpriteFacing", system, variables, input, hostFacing);
        boolean hasHostAlignment = host("SpriteAlignment", system, variables, input, hostAlignment);
        host("CameraOffset", system, variables, input, hostCameraOffset);
        host("PivotOffset", system, variables, input, hostPivot);
        java.util.Arrays.fill(hostMeshScale, 1);
        java.util.Arrays.fill(hostMeshOrientation, 0); hostMeshOrientation[3] = 1;
        host("Scale", system, variables, input, hostMeshScale);
        host("MeshOrientation", system, variables, input, hostMeshOrientation);
        System.arraycopy(hostPosition, 0, hostPreviousPosition, 0, 3);
        System.arraycopy(hostMeshScale, 0, hostPreviousScale, 0, 3);
        System.arraycopy(hostMeshOrientation, 0, hostPreviousOrientation, 0, 4);
        System.arraycopy(hostVelocity, 0, hostPreviousVelocity, 0, 3);
        hostPreviousOffset[0] = hostCameraOffset[0];
        hasHostPreviousPosition = previousHost("PreviousPosition", system, variables, input, hostPreviousPosition);
        hasHostPreviousScale = previousHost("PreviousScale", system, variables, input, hostPreviousScale);
        hasHostPreviousOrientation = previousHost("PreviousMeshOrientation", system, variables, input, hostPreviousOrientation);
        hasHostPreviousVelocity = previousHost("PreviousVelocity", system, variables, input, hostPreviousVelocity);
        hasHostPreviousOffset = previousHost("PreviousCameraOffset", system, variables, input, hostPreviousOffset);
        System.arraycopy(hostSize, 0, hostPreviousSpriteSize, 0, 2);
        hostPreviousSpriteRotation[0] = hostRotation[0];
        System.arraycopy(hostFacing, 0, hostPreviousSpriteFacing, 0, 3);
        System.arraycopy(hostAlignment, 0, hostPreviousSpriteAlignment, 0, 3);
        hasHostPreviousSize = previousHost("PreviousSpriteSize", system, variables, input, hostPreviousSpriteSize);
        hasHostPreviousRotation = previousHost("PreviousSpriteRotation", system, variables, input, hostPreviousSpriteRotation);
        hasHostPreviousFacing = previousHost("PreviousSpriteFacing", system, variables, input, hostPreviousSpriteFacing);
        hasHostPreviousAlignment = previousHost("PreviousSpriteAlignment", system, variables, input, hostPreviousSpriteAlignment);
        hostMeshIndex = 0;
        hasHostMeshIndex = false;
        if (meshIndexBinding != null && !meshIndexBinding.particle()) {
            Integer bound = NiagaraRendererParameters.integer(meshIndexBinding.name(), false, system, variables, input,
                    rendererStore, emitterDefinition.rendererBindings());
            if (bound != null) {
                hostMeshIndex = bound;
                hasHostMeshIndex = true;
            }
        }
        boolean canFace = facing != null || hasHostFacing;
        boolean canAlign = alignment != null || hasHostAlignment;
        resolvedFacing = mesh ? 0 : switch (facingMode) {
            case "FaceCamera" -> 0;
            case "FaceCameraPlane" -> 1;
            case "CustomFacingVector" -> canFace ? 2 : 0;
            case "FaceCameraPosition" -> 3;
            case "FaceCameraDistanceBlend" -> 4;
            case "Automatic" -> (emitterSource ? hasHostFacing : facing != null) ? 2 : 0;
            default -> throw new IllegalArgumentException("Unknown Niagara sprite facing mode");
        };
        resolvedAlignment = mesh ? 0 : switch (alignmentMode) {
            case "Unaligned" -> 0;
            case "VelocityAligned" -> 1;
            case "CustomAlignment" -> canAlign ? 2 : 0;
            case "Automatic" -> (emitterSource ? hasHostAlignment : alignment != null) ? 2 : 0;
            default -> throw new IllegalArgumentException("Unknown Niagara sprite alignment");
        };
        for (int i = 0; i < extraBindings.length; i++) {
            java.util.Arrays.fill(hostExtra[i], 0);
            var binding = extraBindings[i];
            hasHostExtra[i] = !binding.particle()
                    && NiagaraRendererParameters.floating(binding.name(), system, variables, input, rendererStore, hostExtra[i]);
        }
        Integer enabled = NiagaraRendererParameters.integer(enabledBinding, true, system, variables, input, rendererStore, emitterDefinition.rendererBindings());
        naniteEnabled = enabled == null || enabled != 0;
        hostVisible = naniteEnabled;
        if (!visibilityParticleBinding && visibilityBinding != null) {
            Integer visibility = NiagaraRendererParameters.integer(visibilityBinding, false, system, variables, input,
                    rendererStore, emitterDefinition.rendererBindings());
            hostVisible &= visibility == null || visibility == rendererVisibility;
        }
        hostSubImage = subImageDefault;
        if (!subImageParticleBinding && subImageBinding != null) {
            float[] resolvedSubImage = { subImageDefault };
            NiagaraRendererParameters.floating(subImageBinding, system, variables, input, rendererStore, resolvedSubImage);
            hostSubImage = resolvedSubImage[0];
        }
        hostUvScaleX = 1; hostUvScaleY = 1;
        if (!uvScaleParticleBinding && uvScaleBinding != null) {
            float[] resolvedUvScale = { 1, 1 };
            NiagaraRendererParameters.floating(uvScaleBinding, system, variables, input, rendererStore, resolvedUvScale);
            hostUvScaleX = resolvedUvScale[0];
            hostUvScaleY = resolvedUvScale[1];
        }
        java.util.Arrays.fill(hostDynamic, 0);
        hasHostDynamic = !dynamicParticleBinding && dynamicBinding != null
                && NiagaraRendererParameters.floating(dynamicBinding, system, variables, input, rendererStore, hostDynamic);
    }
    public int count() { return emitterSource ? 1 : data.count(); }
    void sourcePrimitive(long value) { sourcePrimitive = value; }
    void dynamicMeshes(Resolver value) { dynamicMeshes = value; }
    public @Nullable Resolver dynamicMeshes() { return dynamicMeshes; }
    void prepareMaterialFrame(double worldOriginX, double worldOriginY, double worldOriginZ,
                              com.kltyton.lumenfx.client.simulation.niagara.material.NiagaraMaterialInstances.EngineValues engine) {
        materialInstances.beginFrame(system, variables, input, worldOriginX, worldOriginY, worldOriginZ, engine);
    }
    public com.kltyton.lumenfx.effect.MaterialDefinition material(com.kltyton.lumenfx.effect.MaterialDefinition source) {
        return materialInstances.material(source, rendererDefinition.surfaceMaterialBinding());
    }
    public NiagaraCpuRenderData renderData() {
        return new NiagaraCpuRenderData(sourcePrimitive, emitterDefinition, rendererDefinition, data, system, variables,
                originX, originY, originZ, rendererStore);
    }
    public void appendVolumes(EffectDefinition effect, EmitterDefinition definition, java.util.List<NiagaraVolumeInstance> output) {
        if (volumeInstances == null) volumeInstances = new NiagaraVolumeInstances(emitterDefinition, rendererDefinition);
        volumeInstances.append(output, effect, definition, new Inputs(system, variables, input, rendererStore), data, componentBounds(),
                new Vec3(originX, originY, originZ), this::material);
    }
    public void appendLights(java.util.List<com.kltyton.lumenfx.effect.render.light.ParticleLight> output) {
        if (lights == null || lightSettings == null) throw new IllegalStateException("Renderer has no light data accessors");
        lights.append(lightSettings, data, system, variables, input, rendererStore, sourcePrimitive, originX, originY, originZ,
                lightSettings.defaultInverseExposureBlend(), output);
    }
    public @Nullable NiagaraBounds lightBounds(VectorDataSet particles) {
        return lights == null ? null : lights.bounds(lightSettings, particles);
    }
    public void updateWorldSeconds(double updateWorldSeconds) { this.updateWorldSeconds = updateWorldSeconds; }

    public void appendDecals(com.kltyton.lumenfx.effect.MaterialDefinition material,
                             java.util.List<com.kltyton.lumenfx.effect.render.decal.ParticleDecal> output) {
        if (decals == null || decalSettings == null) throw new IllegalStateException("Renderer has no decal data accessors");
        decals.append(decalSettings, material, data,
                new com.kltyton.lumenfx.client.simulation.niagara.renderer.NiagaraRendererFields.Inputs(system, variables, input, rendererStore),
                sourcePrimitive, new Vec3(originX, originY, originZ), updateWorldSeconds, output);
    }
    public void appendDecals(com.kltyton.lumenfx.effect.EffectDefinition effect,
                             com.kltyton.lumenfx.effect.MaterialDefinition material,
                             java.util.List<com.kltyton.lumenfx.effect.render.decal.ParticleDecal> output) {
        if (decalSettings == null) throw new IllegalStateException("Renderer has no decal settings");
        appendDecals(material(com.kltyton.lumenfx.client.render.decal.DecalMaterialSelection.resolve(decalSettings, effect, material, input)), output);
    }
    public @Nullable NiagaraBounds decalBounds(VectorDataSet particles) { return decals == null ? null : decals.bounds(particles); }
    public @Nullable NiagaraBounds splatBounds(VectorDataSet particles) { return splats == null ? null : splats.bounds(particles); }
    public NiagaraCpuSplatCollector.@Nullable Source splatSource() {
        if (splats == null || splatSettings == null) return null;
        return new NiagaraCpuSplatCollector.Source(
                this, emitterDefinition, rendererDefinition, splatSettings,
                splats.emitterSource() ? null : data, system, variables, input, rendererStore,
                originX, originY, originZ, splats.emitterSource() ? null : splats.bounds(data));
    }
    public boolean emitterSource() { return emitterSource; }
    public net.minecraft.world.phys.Vec3 meshSimulationOrigin() {
        return new net.minecraft.world.phys.Vec3(originX, originY, originZ);
    }
    public float meshDefaultPosition(boolean previous, int axis) {
        if (previous && hasHostPreviousPosition) return hostPreviousPosition[axis];
        if (hasHostPosition) return hostPosition[axis];
        if (localSpace) return 0;
        var owner = input.pose().position();
        return switch (axis) {
            case 0 -> (float)(owner.x * 100 - originX);
            case 1 -> (float)(owner.z * 100 - originY);
            case 2 -> (float)(owner.y * 100 - originZ);
            default -> throw new IndexOutOfBoundsException(axis);
        };
    }
    public boolean hostVisible() { return hostVisible; }
    public boolean naniteEnabled() { return naniteEnabled; }
    void naniteRendererValues(Map<Key, byte[]> values) { naniteRendererValues = values; }
    public Map<Key, byte[]> naniteRendererValues() { return naniteRendererValues; }
    public boolean visible(int row) { return visibilityAttribute < 0 || data.integer(visibilityAttribute, row) == rendererVisibility; }
    public float simulationPosition(int row, int axis) { return floating(position, row, axis, hostPosition[axis]); }
    public float previousSimulationPosition(int row, int axis) {
        return previousPosition == null && !hasHostPreviousPosition ? simulationPosition(row, axis)
                : floating(previousPosition, row, axis, hostPreviousPosition[axis]);
    }
    public double x(int row) { return (floating(position, row, 0, hostPosition[0]) + (localSpace ? 0 : originX)) * .01; }
    public double y(int row) { return (floating(position, row, 2, hostPosition[2]) + (localSpace ? 0 : originZ)) * .01; }
    public double z(int row) { return (floating(position, row, 1, hostPosition[1]) + (localSpace ? 0 : originY)) * .01; }
    public float width(int row) { return width == null && ribbon ? .01f : floating(width, row, 0, hostSize[0]) * .01f; }
    public float height(int row) { return width == null && ribbon ? .01f : floating(width, row, ribbon ? 0 : 1, hostSize[1]) * .01f; }
    public float color(int row, int channel) { return floating(color, row, channel, hostColor[channel]); }
    public float age(int row) { return floating(age, row, 0, 0); }
    public float normalizedAge(int row) { return floating(normalizedAge, row, 0, hostNormalizedAge[0]); }
    public boolean hasRibbonU(int channel) { return (channel == 0 ? ribbonU0 : ribbonU1) != null; }
    public boolean hasRibbonV(int channel) { return (channel == 0 ? ribbonV0 : ribbonV1) != null; }
    public boolean hasRibbonUvDistance() { return ribbonUvDistance != null; }
    public boolean hasRibbonFacing() { return ribbonFacing != null; }
    public boolean hasRibbonTwistAndWidth() { return ribbonTwist != null && width != null; }
    public boolean fullRibbonIds() { return ribbonIdComponents == 2; }
    public float ribbonWidthSource(int row) { return floating(width, row, 0, 1); }
    public float previousRibbonWidthSource(int row) { return previousRibbonWidth == null ? ribbonWidthSource(row)
            : floating(previousRibbonWidth, row, 0, 1); }
    public boolean hasRibbonTwist(boolean previous) { return (previous ? previousRibbonTwist : ribbonTwist) != null; }
    public float ribbonUvDistanceSource(int row) { return floating(ribbonUvDistance, row, 0, 0); }
    public float ribbonNormalizedAge(int row) { return floating(normalizedAge, row, 0, 0); }
    public float ribbonFacing(int row, int axis) { return floating(ribbonFacing, row, axis, 0); }
    public float ribbonTwist(int row) { return floating(ribbonTwist, row, 0, 0); }
    public float previousRibbonWidth(int row) { return previousRibbonWidth == null ? width(row) : floating(previousRibbonWidth, row, 0, 1) * .01f; }
    public float previousRibbonFacing(int row, int axis) { return previousRibbonFacing == null ? ribbonFacing(row, axis) : floating(previousRibbonFacing, row, axis, 0); }
    public float previousRibbonTwist(int row) { return previousRibbonTwist == null ? ribbonTwist(row) : floating(previousRibbonTwist, row, 0, 0); }
    public boolean hasPreviousRibbonFacing() { return previousRibbonFacing != null || hasRibbonFacing(); }
    public float ribbonUvDistance(int row) { return floating(ribbonUvDistance, row, 0, 0) * .01f; }
    public float ribbonU(int row, int channel) { return floating(channel == 0 ? ribbonU0 : ribbonU1, row, 0, 0); }
    public float ribbonV(int row, int channel, int component) {
        return floating(channel == 0 ? ribbonV0 : ribbonV1, row, component, component == 0 ? 0 : 1);
    }
    public float lifetime(int row) { return floating(lifetime, row, 0, 0); }
    public float random(int row) { return floating(random, row, 0, hostRandom[0]); }
    public float velocity(int row, int axis) { return floating(velocity, row, axis, hostVelocity[axis]); }
    public float meshScale(int row, int axis) { return floating(meshScale, row, axis, hostMeshScale[axis]); }
    public float meshOrientation(int row, int axis) { return floating(meshOrientation, row, axis, hostMeshOrientation[axis]); }
    public com.kltyton.lumenfx.effect.render.ParticleMotionSettings motion() { return motion; }
    void previousOwnerPose(EffectHandle.Pose pose) { previousOwnerPose = pose; }
    void componentVelocity(boolean enabled) { componentVelocity = enabled; }
    public boolean componentVelocity() { return componentVelocity; }
    public EffectHandle.Pose ownerPose() { return input.pose(); }
    public EffectHandle.Pose previousOwnerPose() { return previousOwnerPose == null ? input.pose() : previousOwnerPose; }
    public double previousPosition(int row, int axis) {
        double current = axis == 0 ? x(row) * 100 : axis == 1 ? z(row) * 100 : y(row) * 100;
        if (previousPosition == null && !hasHostPreviousPosition) {
            return current;
        }
        return floating(previousPosition, row, axis, hostPreviousPosition[axis]) + (localSpace ? 0 : axis == 0 ? originX : axis == 1 ? originY : originZ);
    }
    public float previousScale(int row, int axis) { return previousMeshScale == null && !hasHostPreviousScale ? meshScale(row, axis)
            : floating(previousMeshScale, row, axis, hostPreviousScale[axis]); }
    public float previousOrientation(int row, int axis) { return previousMeshOrientation == null && !hasHostPreviousOrientation ? meshOrientation(row, axis)
            : floating(previousMeshOrientation, row, axis, hostPreviousOrientation[axis]); }
    public float previousVelocity(int row, int axis) { return previousVelocity == null && !hasHostPreviousVelocity ? velocity(row, axis)
            : floating(previousVelocity, row, axis, hostPreviousVelocity[axis]); }
    public float previousCameraOffset(int row) { return previousCameraOffset == null && !hasHostPreviousOffset ? cameraOffset(row)
            : floating(previousCameraOffset, row, 0, hostPreviousOffset[0]); }
    public float previousSpriteSize(int row, int axis) { return previousSpriteSize == null && !hasHostPreviousSize
            ? (axis == 0 ? width(row) : height(row)) : floating(previousSpriteSize, row, axis, hostPreviousSpriteSize[axis]) * .01f; }
    public float previousSpriteRotation(int row) { return previousSpriteRotation == null && !hasHostPreviousRotation ? rotation(row)
            : floating(previousSpriteRotation, row, 0, hostPreviousSpriteRotation[0]) / 180f * (float)Math.PI; }
    public float previousSpriteFacing(int row, int axis) { return previousSpriteFacing == null && !hasHostPreviousFacing ? facing(row, axis)
            : floating(previousSpriteFacing, row, axis, hostPreviousSpriteFacing[axis]); }
    public float previousSpriteAlignment(int row, int axis) { return previousSpriteAlignment == null && !hasHostPreviousAlignment ? alignment(row, axis)
            : floating(previousSpriteAlignment, row, axis, hostPreviousSpriteAlignment[axis]); }
    public boolean nanitePreciseMotion() { return motion.precise(); }
    public @Nullable Integer hostMeshIndex() { return hasHostMeshIndex ? hostMeshIndex : null; }
    public boolean selectsMeshParticle(int row, int slot) {
        return visible(row) && (meshIndexAttribute < 0 || data.integer(meshIndexAttribute, row) == slot);
    }
    public float nanitePosition(int row, int axis, boolean previous, boolean precise, float fallback) {
        if (!previous) return floating(position, row, axis, fallback);
        var attribute = precise ? previousPosition : position;
        return floating(attribute, row, axis, fallback);
    }
    public float naniteScale(int row, int axis, boolean previous, boolean precise, float fallback) {
        if (!previous) return floating(meshScale, row, axis, fallback);
        return floating(precise ? previousMeshScale : meshScale, row, axis, fallback);
    }
    public float naniteOrientation(int row, int axis, boolean previous, boolean precise, float fallback) {
        if (!previous) return floating(meshOrientation, row, axis, fallback);
        return floating(precise ? previousMeshOrientation : meshOrientation, row, axis, fallback);
    }
    void naniteInstanceFloats(List<FloatComponent> values) { naniteInstanceFloats = values; }
    public int naniteInstanceFloatCount() { return naniteInstanceFloats.size(); }
    public float naniteInstanceFloat(int row, int component) {
        var binding = naniteInstanceFloats.get(component);
        return row < data.count() ? binding.value(data, row) : binding.hostValue();
    }
    public int meshInteger(com.kltyton.lumenfx.effect.render.mesh.MeshRendererSettings.IntegerBinding binding) {
        Integer value = NiagaraRendererParameters.integer(binding.name(), false, system, variables, input, rendererStore);
        return value == null ? binding.value() : value;
    }
    void componentBounds(java.util.function.Supplier<com.kltyton.lumenfx.effect.niagara.NiagaraBounds> supplier) {
        componentBounds = supplier;
    }
    public com.kltyton.lumenfx.effect.niagara.NiagaraBounds componentBounds() {
        if (componentBounds == null) throw new IllegalStateException("Mesh renderer has no system bounds source");
        return componentBounds.get();
    }
    public float ribbonVelocity(int row, int axis) { return floating(velocity, row, axis, 0); }
    public float facing(int row, int axis) { return floating(facing, row, axis, hostFacing[axis]); }
    public float alignment(int row, int axis) { return floating(alignment, row, axis, hostAlignment[axis]); }
    public float cameraOffset(int row) { return floating(cameraOffset, row, 0, hostCameraOffset[0]); }
    public float pivot(int row, int axis) { return floating(pivotOffset, row, axis, hostPivot[axis]); }
    public int facingMode() { return resolvedFacing; }
    public int alignmentMode() { return resolvedAlignment; }
    public float facingBlendMin() { return facingBlendMin; }
    public float facingBlendMax() { return facingBlendMax; }
    public int uniqueId(int row) { return uniqueId < 0 ? 0 : data.integer(uniqueId, row); }
    public boolean canRenderRibbon() { return position != null && ribbonLinkType >= 0; }
    public boolean sortOnlyWhenTranslucent() { return sortOnlyWhenTranslucent; }
    public boolean emitterDistanceVisible() {
        if (!emitterSource || !distanceCulling) return true;
        float distance = NiagaraComponentDefaults.RENDERER_LOD_DISTANCE_OVERRIDE;
        float distanceSquared = distance * distance;
        return distanceSquared >= minimumDistanceSquared && distanceSquared <= maximumDistanceSquared;
    }
    public boolean supportsSort(EmitterDefinition.SortMode mode) {
        if (emitterSource) return false;
        return switch (mode) {
            case NONE -> false;
            case DISTANCE, VIEW_DEPTH -> position != null;
            case CUSTOM_ASCENDING, CUSTOM_DESCENDING -> customSort != null;
        };
    }
    public int customSortKey(int row, boolean ascending) {
        if (customSort == null) throw new IllegalStateException("Niagara custom sorting has no attribute");
        if (customSort.type() == 2) {
            int bits = data.component(2, customSort.component())[row] & 0xffff;
            int key = (bits & 0x8000) == 0 ? bits | 0x8000 : bits ^ 0xffff;
            return ascending ? key : key ^ 0xffff;
        }
        float value = floating(customSort, row, 0, 0);
        int bits = Float.floatToRawIntBits(value);
        int key = value >= 0 ? bits | Integer.MIN_VALUE : ~bits;
        return ascending ? key : ~key;
    }
    public boolean ribbonLinkIsFloat() { return ribbonLinkType == 0 || ribbonLinkType == 2; }
    public double ribbonLink(int row) { return ribbonLinkType == 0 ? data.floating(ribbonLink, row)
            : ribbonLinkType == 2 ? data.half(ribbonLink, row) : data.integer(ribbonLink, row); }
    public long ribbonId(int row) {
        if (ribbonId < 0) return 0;
        int index = data.integer(ribbonId, row), tag = ribbonIdComponents == 2 ? data.integer(ribbonId + 1, row) : 0;
        return ((long)index << 32) | Integer.toUnsignedLong(tag ^ Integer.MIN_VALUE);
    }
    public float subImage(int row) {
        if (!subImageParticleBinding) return hostSubImage;
        if (emitterSource) return 0;
        return switch (subImageType) {
            case 0 -> data.floating(subImage, row);
            case 2 -> data.half(subImage, row);
            default -> subImageDefault;
        };
    }
    public SpriteCoverage coverage() { return coverage; }
    public float subImageColumns() { return subImageColumns; }
    public float subImageRows() { return subImageRows; }
    public boolean subImageBlend() { return subImageBlend; }
    public float uvScale(int row, int component) {
        if (!uvScaleParticleBinding) return component == 0 ? hostUvScaleX : hostUvScaleY;
        return switch (uvScaleType) {
            case 0 -> data.floating(uvScale + component, row);
            case 2 -> data.half(uvScale + component, row);
            default -> 1;
        };
    }
    public float dynamicParameter(int row, int component, float defaultValue) {
        if (!dynamicParticleBinding) return hasHostDynamic ? hostDynamic[component] : defaultValue;
        return switch (dynamicType) {
            case 0 -> data.floating(dynamicParameter + component, row);
            case 2 -> data.half(dynamicParameter + component, row);
            default -> defaultValue;
        };
    }
    public int dynamicParameterMask() {
        int mask = dynamicParticleBinding ? dynamicType >= 0 ? dynamicChannelMask : 0 : hasHostDynamic ? 15 : 0;
        for (int i = 0; i < extraBindings.length; i++) {
            int channels = extraBindings[i].particle() ? extraDynamic[i] == null ? 0 : extraBindings[i].channelMask()
                    : hasHostExtra[i] ? 15 : 0;
            mask |= channels << ((i + 1) * 4);
        }
        return mask;
    }

    public float dynamicParameter(int row, int slot, int component) {
        if (slot < 1 || slot > 3 || component < 0 || component > 3)
            throw new IllegalArgumentException("Invalid Niagara dynamic material component");
        int index = slot - 1;
        if (!extraBindings[index].particle()) return hasHostExtra[index] ? hostExtra[index][component] : 0;
        return floating(extraDynamic[index], row, component, 0);
    }
    public float rotation(int row) {
        return (floating(rotation, row, 0, hostRotation[0]) / 180.0f) * (float)Math.PI;
    }

    private FloatBinding boundParticle(List<NiagaraDefinition.Attribute> attributes, String semantic, int count) {
        if (emitterSource) return null;
        var binding = rendererBindings.get(semantic);
        if (binding != null && !binding.particle()) return null;
        var value = optionalAttribute(attributes, binding == null ? semantic : binding.name());
        if (value == null || value.count() != count || value.type() != 0 && value.type() != 2) return null;
        return new FloatBinding(value.component(), value.type());
    }

    private boolean previousHost(String semantic, VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables,
                                 EffectHandle.Input input, float[] target) {
        boolean resolved = host(semantic, system, variables, input, target);
        var binding = rendererBindings.get(semantic);
        return resolved || binding != null && !binding.particle();
    }

    private boolean host(String semantic, VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables,
                         EffectHandle.Input input, float[] target) {
        var binding = rendererBindings.get(semantic);
        return binding != null && !binding.particle()
                && NiagaraRendererParameters.floating(binding.name(), system, variables, input, rendererStore, target);
    }

    private float floating(FloatBinding binding, int row, int component, float fallback) {
        if (binding == null) return fallback;
        return binding.type() == 0 ? data.floating(binding.component() + component, row)
                : data.half(binding.component() + component, row);
    }

    private static FloatBinding floatingBinding(List<NiagaraDefinition.Attribute> attributes, String name, int count) {
        NiagaraDefinition.Attribute value = optionalAttribute(attributes, name);
        if (value == null) return null;
        if ((value.type() != 0 && value.type() != 2) || value.count() != count)
            throw new IllegalArgumentException("Incompatible Niagara floating attribute " + name);
        return new FloatBinding(value.component(), value.type());
    }

    private static NiagaraDefinition.Attribute optionalAttribute(List<NiagaraDefinition.Attribute> attributes, String name) {
        if (name == null) return null;
        String bare = unqualifiedParticle(name);
        for (var value : attributes) {
            if (com.kltyton.lumenfx.effect.niagara.parameter.SourceNames.equal(unqualifiedParticle(value.name()), bare)) {
                return value;
            }
        }
        return null;
    }

    private static String unqualifiedParticle(String name) {
        return name.regionMatches(true, 0, "Particles.", 0, "Particles.".length())
                ? name.substring("Particles.".length()) : name;
    }

    private static float systemValue(VectorDataSet system, NiagaraDefinition.Variable variable, float fallback) {
        if (variable == null) return fallback;
        if (variable.floatCount() == 1) return system.floating(variable.floatStart(), 0);
        if (variable.halfCount() == 1) return system.half(variable.halfStart(), 0);
        throw new IllegalArgumentException("Niagara SubImageIndex host binding is not a scalar float: " + variable.name());
    }
}
