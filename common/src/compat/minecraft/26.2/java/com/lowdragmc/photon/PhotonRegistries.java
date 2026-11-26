/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon;

import com.kltyton.lumenfx.internal.photon.core.registry.AutoRegistry;
import com.kltyton.lumenfx.internal.photon.core.registry.LDLRegistry;
import com.kltyton.lumenfx.internal.photon.core.registry.annotation.LDLRegisterClient;
import com.lowdragmc.photon.client.fx.timeline.AnimatedPropertyType;
import com.lowdragmc.photon.client.fx.timeline.property.ConfigPropertyTypeDispatcher;
import com.lowdragmc.photon.client.fx.timeline.property.PositionPropertyType;
import com.lowdragmc.photon.client.fx.timeline.property.RotationPropertyType;
import com.lowdragmc.photon.client.fx.timeline.property.ScalePropertyType;
import com.lowdragmc.photon.client.fx.timeline.property.SpeedPropertyType;
import com.lowdragmc.photon.client.fx.instance.InstanceTags;
import com.lowdragmc.photon.client.gameobject.EmptyFXObject;
import com.lowdragmc.photon.client.gameobject.FXInstanceObject;
import com.lowdragmc.photon.client.gameobject.FXObjectType;
import com.lowdragmc.photon.client.gameobject.collider.ColliderObject;
import com.lowdragmc.photon.client.gameobject.emitter.aratrail.AraTrailEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.beam.BeamEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.BlockTextureSheetMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.IModelSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.number.NumberFunction;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.IShape;
import com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleEmitter;
import com.lowdragmc.photon.client.gameobject.emitter.trail.TrailEmitter;
import com.lowdragmc.photon.client.gameobject.forcefield.ForceFieldObject;

import java.util.function.Supplier;

public final class PhotonRegistries {

    // FX objects are type-driven: a registered FXObjectType supplies the creator + type-level apis.
    // each fx-object class declares its own @LDLRegisterClient static TYPE and returns it from
    // IFXObject#getFXObjectType().
    public static final LDLRegistry.String<FXObjectType> FX_OBJECTS;

    public static final AutoRegistry.LDLibRegisterClient<IMaterial, Supplier<IMaterial>> MATERIALS;

    public static final AutoRegistry.LDLibRegisterClient<NumberFunction, Supplier<NumberFunction>> NUMBER_FUNCTIONS;

    public static final AutoRegistry.LDLibRegisterClient<IShape, Supplier<IShape>> SHAPES;

    public static final AutoRegistry.LDLibRegisterClient<IModelSource, Supplier<IModelSource>> MODEL_SOURCES;

    public static final LDLRegistry.String<AnimatedPropertyType> ANIMATED_PROPERTIES;

    static {
        FX_OBJECTS = new LDLRegistry.String<>(Photon.id("fx_object"));
        FX_OBJECTS.registerOrOverride("empty", EmptyFXObject.TYPE);
        FX_OBJECTS.registerOrOverride(InstanceTags.INSTANCE_TYPE, FXInstanceObject.TYPE);
        FX_OBJECTS.registerOrOverride("particle_emitter", ParticleEmitter.TYPE);
        FX_OBJECTS.registerOrOverride("beam_emitter", BeamEmitter.TYPE);
        FX_OBJECTS.registerOrOverride("trail_emitter", TrailEmitter.TYPE);
        FX_OBJECTS.registerOrOverride("ara_trail_emitter", AraTrailEmitter.TYPE);
        FX_OBJECTS.registerOrOverride("collider", ColliderObject.TYPE);
        FX_OBJECTS.registerOrOverride("force_field", ForceFieldObject.TYPE);
        MATERIALS = AutoRegistry.LDLibRegisterClient
                .create(Photon.id("material"), IMaterial.class, AutoRegistry::noArgsCreator);
        NUMBER_FUNCTIONS = AutoRegistry.LDLibRegisterClient
                .create(Photon.id("number_function"), NumberFunction.class, AutoRegistry::noArgsCreator);
        SHAPES = AutoRegistry.LDLibRegisterClient
                .create(Photon.id("shape"), IShape.class, AutoRegistry::noArgsCreator);
        MODEL_SOURCES = AutoRegistry.LDLibRegisterClient
                .create(Photon.id("model_source"), IModelSource.class, AutoRegistry::noArgsCreator);
        ANIMATED_PROPERTIES = new LDLRegistry.String<>(Photon.id("animated_property"));
        ANIMATED_PROPERTIES.registerOrOverride("position", PositionPropertyType.INSTANCE);
        ANIMATED_PROPERTIES.registerOrOverride("rotation", RotationPropertyType.INSTANCE);
        ANIMATED_PROPERTIES.registerOrOverride("scale", ScalePropertyType.INSTANCE);
        ANIMATED_PROPERTIES.registerOrOverride("speed", SpeedPropertyType.INSTANCE);
        ANIMATED_PROPERTIES.registerOrOverride("config", ConfigPropertyTypeDispatcher.INSTANCE);
        // 26.1: @LDLRegisterClient lost `manual = true`, so the auto-scan registers these classes
        // itself; override the scanned holders with the singleton-backed ones.
        MATERIALS.registerOrOverride("missing", AutoRegistry.Holder.of(
                IMaterial.MissingMaterial.class.getAnnotation(LDLRegisterClient.class),
                IMaterial.MissingMaterial.class,
                () -> IMaterial.MISSING));
        MATERIALS.registerOrOverride("block_atlas", AutoRegistry.Holder.of(
                BlockTextureSheetMaterial.class.getAnnotation(LDLRegisterClient.class),
                BlockTextureSheetMaterial.class,
                () -> BlockTextureSheetMaterial.INSTANCE));
    }

    private PhotonRegistries() {
    }

}
