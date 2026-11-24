package com.kltyton.lumenfx.client.simulation.niagara.parameter.store;

import com.kltyton.lumenfx.client.simulation.niagara.interfaces.splat.NiagaraGaussianSplatReader;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.splat.NiagaraGaussianSplatWriter;


import com.kltyton.lumenfx.client.simulation.niagara.interfaces.distribution.NiagaraIntDistribution;
import com.kltyton.lumenfx.effect.niagara.water.WaterDefinition;
import com.kltyton.lumenfx.effect.niagara.geometry.GeometryCollectionDefinition;
import com.kltyton.lumenfx.effect.niagara.physics.ChaosDestructionDefinition;
import com.kltyton.lumenfx.effect.niagara.groom.HairStrandsDefinition;
import com.kltyton.lumenfx.api.client.geometry.NiagaraGeometryCollectionInputs.RestCollection;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.geometry.GeometryCollectionAssetJson;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.water.NiagaraAuthoredWaterBody;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimCacheReaderDefinition;
import com.kltyton.lumenfx.effect.niagara.memory.NiagaraMemoryBufferDefinition;
import com.kltyton.lumenfx.effect.niagara.mesh.NiagaraDynamicMeshDefinition;
import com.kltyton.lumenfx.effect.niagara.grid.NeighborGridDefinition;
import com.kltyton.lumenfx.effect.niagara.grid.RasterizationGridDefinition;
import com.kltyton.lumenfx.effect.niagara.grid.VelocityGridDefinition;
import com.kltyton.lumenfx.effect.niagara.grid.Grid3DCollectionDefinition;
import com.kltyton.lumenfx.effect.niagara.grid.Grid2DCollectionDefinition;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.material.NiagaraMaterialCollectionWriter;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.table.NiagaraDataTable;
import com.kltyton.lumenfx.client.simulation.niagara.texture.target.NiagaraIntRenderTarget2D;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.socket.NiagaraSocketReader;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.spline.NiagaraSpline;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.grid.NiagaraNeighborQuery;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.material.NiagaraDynamicMaterialWriter;
import com.kltyton.lumenfx.effect.niagara.texture.SparseVolumeInterfaceDefinition;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.memory.NiagaraMemoryBuffer.Operation;

import com.kltyton.lumenfx.client.simulation.niagara.interfaces.NiagaraObjectPropertyReader;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.property.NiagaraPropertyInterface;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.NiagaraActorComponent;
import com.kltyton.lumenfx.effect.niagara.collision.NiagaraAsyncGpuTraceDefinition;
import com.kltyton.lumenfx.effect.niagara.mesh.NiagaraStaticMeshDefinition;
import com.kltyton.lumenfx.effect.niagara.mesh.NiagaraSkeletalMeshDefinition;
import com.kltyton.lumenfx.effect.niagara.export.NiagaraParticleExportDefinition;
import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraAudioPlayerDefinition;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraAudioAnalysisDefinition;
import com.kltyton.lumenfx.client.resource.niagara.NiagaraJson;
import com.google.gson.JsonObject;
import com.kltyton.lumenfx.effect.niagara.VectorProgram.External;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.MutableInterfaceInstance;
import com.kltyton.lumenfx.effect.curve.NiagaraCurve;
import com.kltyton.lumenfx.effect.noise.NiagaraCurlNoise;
import com.kltyton.lumenfx.effect.noise.VectorFieldVolume;
import com.kltyton.lumenfx.client.simulation.niagara.field.NiagaraVectorFieldData;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraCurves;
import com.kltyton.lumenfx.client.resource.niagara.SpriteRendererInfoJson;
import com.kltyton.lumenfx.client.resource.niagara.MeshRendererInfoJson;
import com.kltyton.lumenfx.effect.render.sprite.SpriteRendererInformation;
import com.kltyton.lumenfx.effect.render.mesh.MeshRendererInformation;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** One source DI identity supplies callable functions while instance data stays with the owning system. */
public final class NiagaraDataInterfaceObject {
    private record Function(String name, int inputs, int outputs, JsonObject info) { }
    private final String identity;
    private @Nullable String sourceType;
    private @Nullable String authoredSourcePath;
    private boolean loadedSource;
    private final Map<Function, External> functions = new LinkedHashMap<>();
    private @Nullable MutableInterfaceInstance mutable;
    private record CurveDefinition(String type, List<NiagaraCurve> curves) { }
    private @Nullable CurveDefinition curve;
    private record CurlDefinition(JsonObject data, NiagaraCurlNoise noise, byte[] parameters) { }
    private volatile @Nullable CurlDefinition curl;
    private record FieldDefinition(JsonObject data, @Nullable VectorFieldVolume volume,
                                   boolean tileX, boolean tileY, boolean tileZ, NiagaraVectorFieldData state) { }
    private volatile @Nullable FieldDefinition field;
    private static final class GpuCurveDefinition {
        final String type;
        final JsonObject properties;
        PreparedNiagaraCurves.@Nullable Curve prepared;
        GpuCurveDefinition(String type, JsonObject properties) { this.type = type; this.properties = properties.deepCopy(); }
    }
    private volatile @Nullable GpuCurveDefinition gpuCurve;
    public record CameraDefinition(int controllerIndex, boolean requireCurrent) { }
    private volatile @Nullable CameraDefinition camera;
    private volatile NiagaraObjectPropertyReader.@Nullable Definition propertyReader;
    private volatile NiagaraPropertyInterface.@Nullable Definition propertyInterface;
    private volatile NiagaraActorComponent.@Nullable Definition actorComponent;
    private volatile @Nullable NiagaraAsyncGpuTraceDefinition asyncGpuTrace;
    private volatile @Nullable NiagaraStaticMeshDefinition staticMeshReader;
    private volatile @Nullable NiagaraSkeletalMeshDefinition skeletalMeshReader;
    private volatile @Nullable NiagaraParticleExportDefinition particleExport;
    private volatile @Nullable NiagaraDataChannelDefinition dataChannel;
    private volatile @Nullable NiagaraAudioPlayerDefinition audioPlayer;
    private volatile @Nullable NiagaraAudioAnalysisDefinition audioAnalysis;
    private volatile @Nullable NiagaraSimCacheReaderDefinition simCacheReader;
    private volatile @Nullable NiagaraMemoryBufferDefinition memoryBuffer;
    private volatile @Nullable NiagaraDynamicMeshDefinition dynamicMesh;
    private volatile @Nullable NeighborGridDefinition neighborGrid;
    private volatile @Nullable RasterizationGridDefinition rasterizationGrid;
    private volatile @Nullable VelocityGridDefinition velocityGrid;
    private volatile @Nullable Grid3DCollectionDefinition grid3DCollection;
    private volatile @Nullable Grid2DCollectionDefinition grid2DCollection;
    private volatile NiagaraDataTable.@Nullable Definition dataTable;
    private volatile NiagaraGaussianSplatReader.@Nullable Definition gaussianSplatReader;
    private volatile NiagaraGaussianSplatWriter.@Nullable Definition gaussianSplatWriter;
    private volatile NiagaraIntRenderTarget2D.@Nullable Definition intRenderTarget2D;
    private volatile NiagaraSocketReader.@Nullable Definition socketReader;
    private volatile NiagaraSpline.@Nullable Definition spline;
    private volatile @Nullable NiagaraIntDistribution intDistribution;
    private volatile @Nullable WaterDefinition water;
    private volatile @Nullable NiagaraAuthoredWaterBody authoredWater;
    private volatile NiagaraNeighborQuery.@Nullable Definition neighborQuery;
    private volatile int debugLineLimit;
    private volatile NiagaraDynamicMaterialWriter.@Nullable Definition dynamicMaterial;
    private volatile NiagaraMaterialCollectionWriter.@Nullable Definition materialCollectionWriter;
    private volatile @Nullable SparseVolumeInterfaceDefinition sparseVolume;
    private volatile @Nullable JsonObject volumeCache;
    private volatile @Nullable JsonObject physicsAsset;
    private volatile @Nullable JsonObject rigidMeshCollision;
    private volatile @Nullable GeometryCollectionDefinition geometryCollection;
    private volatile @Nullable ChaosDestructionDefinition chaosDestruction;
    private volatile @Nullable HairStrandsDefinition hairStrands;
    private volatile @Nullable RestCollection geometryCollectionAsset;
    private volatile @Nullable JsonObject sceneCapture;
    private volatile @Nullable JsonObject landscape;
    private volatile @Nullable JsonObject virtualTexture;
    private record RendererInformation(JsonObject data, @Nullable SpriteRendererInformation sprite,
                                       @Nullable MeshRendererInformation mesh) { }
    private volatile @Nullable RendererInformation rendererInfo;
    public record EmitterBinding(String type, String mode, String emitter) { }
    public record AsyncTraceSnapshot(Object proxy, NiagaraAsyncGpuTraceDefinition definition) { }
    private volatile @Nullable EmitterBinding emitter;
    private @Nullable JsonObject ribbonProperties;
    private @Nullable JsonObject textureDefinition, renderTargetDefinition, dimensionalTargetDefinition, platformDefinition;

    NiagaraDataInterfaceObject(String identity) { this.identity = identity; }
    public String identity() { return identity; }
    public @Nullable String sourceClassName() {
        String type = sourceType;
        if (type == null) return null;
        int separator = Math.max(type.lastIndexOf('.'), Math.max(type.lastIndexOf('/'), type.lastIndexOf(':')));
        return type.substring(separator + 1);
    }
    void sourceType(JsonObject source) {
        sourceType = descriptorType(source);
        if ("NiagaraDataInterfaceRibbon".equals(sourceClassName())) {
            var value = source.get("properties");
            ribbonProperties = value instanceof JsonObject settings ? settings.deepCopy() : new JsonObject();
        }
        String initialization = source.has("initialization") ? source.get("initialization").getAsString() : "";
        authoredSourcePath = source.get("path") instanceof com.google.gson.JsonPrimitive path && path.isString()
                ? path.getAsString() : null;
        loadedSource = initialization.equals("loaded") || initialization.equals("reference");
    }
    public @Nullable String authoredSourcePath() { return authoredSourcePath; }
    public @Nullable String loadedSourcePath() { return loadedSource ? authoredSourcePath : null; }
    public JsonObject ribbonProperties() {
        return Objects.requireNonNull(ribbonProperties, "Niagara Ribbon Interface has no source settings");
    }
    boolean matchesSourceType(Key key) {
        return matchesSourceType(key, sourceType);
    }
    static boolean matchesPreparedType(Key key, JsonObject source) { return matchesSourceType(key, descriptorType(source)); }
    private static @Nullable String descriptorType(JsonObject source) {
        return source.has("class_path") && !source.get("class_path").isJsonNull()
                ? source.get("class_path").getAsString() : source.has("class") ? source.get("class").getAsString() : null;
    }
    private static boolean matchesSourceType(Key key, @Nullable String type) {
        return type != null && SourceNames.equal(type, type.indexOf('/') >= 0 ? key.sourceType() : key.storeTypeName());
    }

    void register(External function) {
        functions.putIfAbsent(signature(function), function);
        if (function.interfaceType().equals("curl_noise")) configureCurl(Objects.requireNonNull(function.interfaceData()));
        if (function.interfaceType().equals("camera")) camera = new CameraDefinition(function.controllerIndex(), function.requireCurrent());
        if (function.interfaceType().equals("texture_2d") || function.interfaceType().equals("texture_array")
                || function.interfaceType().equals("texture_cube") || function.interfaceType().equals("texture_volume"))
            configureTexture(Objects.requireNonNull(function.interfaceData()));
        if (function.interfaceType().equals("render_target_2d")) configureRenderTarget(Objects.requireNonNull(function.interfaceData()));
        if (function.interfaceType().equals("render_target_array") || function.interfaceType().equals("render_target_volume")
                || function.interfaceType().equals("render_target_cube"))
            configureDimensionalTarget(Objects.requireNonNull(function.interfaceData()));
        if (function.interfaceType().equals("platform_set")) configurePlatform(Objects.requireNonNull(function.interfaceData()));
        if (function.interfaceType().equals("volume_cache"))
            configureVolumeCache(Objects.requireNonNull(function.interfaceData()).getAsJsonObject("volume_cache"));
        if (function.interfaceType().equals("rigid_mesh_collision"))
            configureRigidMeshCollision(Objects.requireNonNull(function.interfaceData()));
        if (function.interfaceType().equals("geometry_collection") && geometryCollection == null)
            configureGeometryCollection(Objects.requireNonNull(function.interfaceData()));
        if (function.interfaceType().equals("gaussian_splat_reader") && gaussianSplatReader == null)
            configureGaussianSplatReader(Objects.requireNonNull(function.interfaceData()).getAsJsonObject("gaussian_splat_reader"));
        if (function.interfaceType().equals("gaussian_splat_writer") && gaussianSplatWriter == null)
            configureGaussianSplatWriter(Objects.requireNonNull(function.interfaceData()).getAsJsonObject("gaussian_splat_writer"));
        if (function.interfaceType().equals("property_interface") && propertyInterface == null)
            configurePropertyInterface(Objects.requireNonNull(function.interfaceData()));
        if (function.interfaceType().equals("chaos_destruction") && chaosDestruction == null)
            configureChaosDestruction(Objects.requireNonNull(function.interfaceData()));
        if (function.interfaceType().equals("hair_strands") && hairStrands == null)
            configureHairStrands(Objects.requireNonNull(function.interfaceData()));
        if (function.interfaceType().equals("scene_capture_2d"))
            configureSceneCapture(Objects.requireNonNull(function.interfaceData()));
        if (function.interfaceType().equals("landscape"))
            configureLandscape(Objects.requireNonNull(function.interfaceData()));
        if (function.interfaceType().equals("virtual_texture_sample"))
            configureVirtualTexture(Objects.requireNonNull(function.interfaceData()));
        if (function.interfaceType().equals("particle_read") || function.interfaceType().equals("emitter_properties"))
            configureEmitter(function.interfaceType().equals("particle_read")
                    ? "NiagaraDataInterfaceParticleRead" : "NiagaraDataInterfaceEmitterProperties",
                    Objects.requireNonNull(function.interfaceData()));
    }
    void configure(MutableInterfaceInstance definition) { mutable = definition; }
    public @Nullable MutableInterfaceInstance mutableDefinition() { return mutable; }
    void configureMutableDefaults(String type, JsonObject data) {
        if (mutable == null) mutable = new MutableInterfaceInstance(identity, type, "", false, false, data);
    }
    void configureTexture(JsonObject data) { textureDefinition = data.deepCopy(); }
    void configureRenderTarget(JsonObject data) { renderTargetDefinition = data.deepCopy(); }
    void configureDimensionalTarget(JsonObject data) { dimensionalTargetDefinition = data.deepCopy(); }
    void configurePlatform(JsonObject data) { platformDefinition = data.deepCopy(); }
    public @Nullable JsonObject textureDefinition() { return textureDefinition == null ? null : textureDefinition.deepCopy(); }
    public @Nullable JsonObject renderTargetDefinition() { return renderTargetDefinition == null ? null : renderTargetDefinition.deepCopy(); }
    public @Nullable JsonObject dimensionalTargetDefinition() { return dimensionalTargetDefinition == null ? null : dimensionalTargetDefinition.deepCopy(); }
    public @Nullable JsonObject platformDefinition() { return platformDefinition == null ? null : platformDefinition.deepCopy(); }
    void configureCamera(JsonObject data) {
        camera = new CameraDefinition(data.get("controller_index").getAsInt(), data.get("require_current").getAsBoolean());
    }
    public @Nullable CameraDefinition camera() { return camera; }
    void configureSprite(JsonObject data) { rendererInfo = new RendererInformation(data.deepCopy(), SpriteRendererInfoJson.read(data), null); }
    void configureMesh(JsonObject data) { rendererInfo = new RendererInformation(data.deepCopy(), null, MeshRendererInfoJson.read(data)); }
    public @Nullable SpriteRendererInformation spriteInformation() { var value = rendererInfo; return value == null ? null : value.sprite(); }
    public @Nullable MeshRendererInformation meshInformation() { var value = rendererInfo; return value == null ? null : value.mesh(); }
    void configureEmitter(String type, JsonObject data) {
        if (!type.equals("NiagaraDataInterfaceParticleRead") && !type.equals("NiagaraDataInterfaceEmitterProperties"))
            throw new IllegalArgumentException("Unknown emitter binding interface: " + type);
        String mode = data.get("mode").getAsString();
        if (!mode.equals("Self") && !mode.equals("Other")) throw new IllegalArgumentException("Invalid emitter binding mode: " + mode);
        emitter = new EmitterBinding(type, mode, data.get("emitter").getAsString());
    }
    public @Nullable EmitterBinding emitterBinding() { return emitter; }
    void configureCurves(String type, List<NiagaraCurve> curves) {
        int channels = curveChannels(type);
        if (channels == 0 || curves.size() != channels)
            throw new IllegalArgumentException("Curve data interface has invalid source channels: " + identity);
        curve = new CurveDefinition(type, List.copyOf(curves));
    }
    private static int curveChannels(String type) {
        return switch (type) {
            case "NiagaraDataInterfaceCurve" -> 1;
            case "NiagaraDataInterfaceVector2DCurve" -> 2;
            case "NiagaraDataInterfaceVectorCurve" -> 3;
            case "NiagaraDataInterfaceColorCurve", "NiagaraDataInterfaceVector4Curve" -> 4;
            default -> 0;
        };
    }
    void configureGpuCurve(String type, JsonObject properties) {
        if (curveChannels(type) == 0) throw new IllegalArgumentException("Unknown GPU curve class: " + type);
        gpuCurve = new GpuCurveDefinition(type, properties);
    }
    public PreparedNiagaraCurves.@Nullable Curve gpuCurve() {
        RenderSystem.assertOnRenderThread();
        var value = gpuCurve;
        if (value == null) return null;
        var prepared = value.prepared;
        if (prepared == null) {
            prepared = PreparedNiagaraCurves.curve(value.properties, curveChannels(value.type), identity);
            value.prepared = prepared;
        }
        return prepared;
    }
    void configureCurl(JsonObject data) {
        int seed = data.get(data.has("offset_seed") ? "offset_seed" : "seed").getAsInt();
        var noise = new NiagaraCurlNoise(seed);
        byte[] parameters = ByteBuffer.allocate(3 * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(noise.offsetX()).putFloat(noise.offsetY()).putFloat(noise.offsetZ()).array();
        curl = new CurlDefinition(data.deepCopy(), noise, parameters);
    }
    public NiagaraCurlNoise curlNoise() {
        return Objects.requireNonNull(curl, "Curl object has no initialized offset: " + identity).noise();
    }
    public byte[] curlParameters() {
        return Objects.requireNonNull(curl, "Curl proxy has no initialized offset: " + identity).parameters().clone();
    }
    void configureVectorField(JsonObject data, @Nullable VectorFieldVolume volume) {
        var tile = data.getAsJsonArray("tile");
        boolean x = tile.get(0).getAsBoolean(), y = tile.get(1).getAsBoolean(), z = tile.get(2).getAsBoolean();
        var state = new NiagaraVectorFieldData(volume, x, y, z, data.getAsJsonObject("interface_data"), true);
        field = new FieldDefinition(data.deepCopy(), volume, x, y, z, state);
    }
    public @Nullable NiagaraVectorFieldData vectorField() {
        var value = field;
        return value == null ? null : value.state();
    }
    public record VectorFieldBinding(@Nullable VectorFieldVolume volume, int tilingAxes, String kind) { }
    public @Nullable VectorFieldBinding vectorFieldBinding() {
        var value = field;
        return value == null ? null : new VectorFieldBinding(value.volume(),
                (value.tileX() ? 1 : 0) | (value.tileY() ? 2 : 0) | (value.tileZ() ? 4 : 0),
                value.data().get("field_kind").getAsString());
    }

    public @Nullable External bind(External declaration) {
        if (declaration.interfaceType().equals("virtual_texture_sample")) {
            var data = virtualTexture;
            return data == null || !matchesType(declaration, "NiagaraDataInterfaceVirtualTextureSample") ? null
                    : configured(declaration, declaration.curves(), data, declaration.interfaceParameter());
        }
        if (declaration.interfaceType().equals("landscape")) {
            var data = landscape;
            return data == null || !matchesType(declaration, "NiagaraDataInterfaceLandscape") ? null
                    : configured(declaration, declaration.curves(), data, declaration.interfaceParameter());
        }
        if (declaration.interfaceType().equals("scene_capture_2d")) {
            var data = sceneCapture;
            return data == null || !matchesType(declaration, "NiagaraDataInterfaceSceneCapture2D") ? null
                    : configured(declaration, declaration.curves(), data, declaration.interfaceParameter());
        }
        if (declaration.interfaceType().equals("hair_strands"))
            return hairStrands == null || !matchesType(declaration, HairStrandsDefinition.TYPE) ? null
                    : configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        if (declaration.interfaceType().equals("chaos_destruction"))
            return chaosDestruction == null || !matchesType(declaration, ChaosDestructionDefinition.TYPE) ? null
                    : configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        if (declaration.interfaceType().equals("geometry_collection"))
            return geometryCollection == null || !matchesType(declaration, GeometryCollectionDefinition.TYPE) ? null
                    : configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        if (declaration.interfaceType().equals("rigid_mesh_collision")) {
            var data = rigidMeshCollision;
            return data == null || !matchesType(declaration, "NiagaraDataInterfaceRigidMeshCollisionQuery") ? null
                    : configured(declaration, declaration.curves(), data, declaration.interfaceParameter());
        }
        if (declaration.interfaceType().equals("volume_cache")) {
            var cache = volumeCache;
            if (cache == null || !matchesType(declaration, "NiagaraDataInterfaceVolumeCache")) return null;
            var data = new JsonObject();
            data.add("volume_cache", cache.deepCopy());
            return configured(declaration, declaration.curves(), data, declaration.interfaceParameter());
        }
        if (declaration.interfaceType().equals("debug_draw")) {
            if (!matchesType(declaration, "NiagaraDataInterfaceDebugDraw")) return null;
            JsonObject data = Objects.requireNonNull(declaration.interfaceData()).deepCopy();
            data.addProperty("line_limit", debugLineLimit);
            return configured(declaration, declaration.curves(), data, declaration.interfaceParameter());
        }
        if (declaration.interfaceType().equals("sparse_volume_texture"))
            return sparseVolume == null || !matchesType(declaration, "NiagaraDataInterfaceSparseVolumeTexture") ? null
                    : configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        if (declaration.interfaceType().equals("dynamic_mesh"))
            return dynamicMesh == null || !matchesType(declaration, "NiagaraDataInterfaceDynamicMesh") ? null
                    : configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        if (declaration.interfaceType().equals("memory_buffer"))
            return memoryBuffer == null || !matchesType(declaration, "NiagaraDataInterfaceMemoryBuffer") ? null
                    : configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        if (declaration.interfaceType().equals("neighbor_grid"))
            return neighborGrid == null || !matchesType(declaration, "NiagaraDataInterfaceNeighborGrid3D") ? null
                    : configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        if (declaration.interfaceType().equals("rasterization_grid"))
            return rasterizationGrid == null || !matchesType(declaration, "NiagaraDataInterfaceRasterizationGrid3D") ? null
                    : configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        if (declaration.interfaceType().equals("velocity_grid"))
            return velocityGrid == null || !matchesType(declaration, velocityGrid.interfaceClass()) ? null
                    : configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        if (declaration.interfaceType().equals("grid2d_collection"))
            return grid2DCollection == null || !matchesType(declaration, grid2DCollection.reader()
                    ? "NiagaraDataInterfaceGrid2DCollectionReader" : "NiagaraDataInterfaceGrid2DCollection") ? null
                    : configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        if (declaration.interfaceType().equals("data_table"))
            return dataTable == null || !matchesType(declaration, "NiagaraDataInterfaceDataTable") ? null
                    : configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        if (declaration.interfaceType().equals("gaussian_splat_reader"))
            return gaussianSplatReader == null || !matchesType(declaration, NiagaraGaussianSplatReader.TYPE) ? null
                    : configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        if (declaration.interfaceType().equals("gaussian_splat_writer"))
            return gaussianSplatWriter == null || !matchesType(declaration, NiagaraGaussianSplatWriter.TYPE) ? null
                    : configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        if (declaration.interfaceType().equals("int_render_target_2d"))
            return intRenderTarget2D == null || !matchesType(declaration, "NiagaraDataInterfaceIntRenderTarget2D") ? null
                    : configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        if (declaration.interfaceType().equals("socket_reader"))
            return socketReader == null || !matchesType(declaration, "NiagaraDataInterfaceSocketReader") ? null
                    : configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        if (declaration.interfaceType().equals("spline"))
            return spline == null || !matchesType(declaration, "NiagaraDataInterfaceSpline") ? null
                    : configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        if (declaration.interfaceType().equals("water"))
            return water == null || !matchesType(declaration, WaterDefinition.TYPE) ? null
                    : configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        if (declaration.interfaceType().equals("int_distribution"))
            return intDistribution == null || !matchesType(declaration, "NiagaraDataInterfaceArrayDistributionInt") ? null
                    : configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        if (declaration.interfaceType().equals("physics_field"))
            return matchesType(declaration, "NiagaraDataInterfacePhysicsField")
                    ? configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter()) : null;
        if (declaration.interfaceType().equals("neighbor_query"))
            return neighborQuery == null || !matchesType(declaration, "NiagaraDataInterfaceNeighborQuery") ? null
                    : configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        if (declaration.interfaceType().equals("material_instance_dynamic"))
            return dynamicMaterial == null || !matchesType(declaration, "NiagaraDataInterfaceMaterialInstanceDynamic") ? null
                    : configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        if (declaration.interfaceType().equals("mouse_position"))
            return matchesType(declaration, "NiagaraDataInterfaceMousePosition")
                    ? configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter()) : null;
        if (declaration.interfaceType().equals("movie_render_queue"))
            return matchesType(declaration, "NiagaraDataInterfaceMRQ")
                    ? configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter()) : null;
        if (declaration.interfaceType().equals("grid3d_collection"))
            return grid3DCollection == null || !matchesType(declaration, grid3DCollection.reader()
                    ? "NiagaraDataInterfaceGrid3DCollectionReader" : "NiagaraDataInterfaceGrid3DCollection") ? null
                    : configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        if (declaration.interfaceType().equals("material_collection_writer"))
            return materialCollectionWriter == null || !matchesType(declaration, "NiagaraDataInterfaceMaterialParameterCollection") ? null
                    : configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        if (declaration.interfaceType().equals("sim_cache_reader"))
            return simCacheReader == null || !matchesType(declaration, "NiagaraDataInterfaceSimCacheReader") ? null
                    : configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        if (declaration.interfaceType().equals("audio_analysis")) {
            var settings = audioAnalysis;
            String type = settings != null && settings.spectrum() ? "NiagaraDataInterfaceAudioSpectrum" : "NiagaraDataInterfaceAudioOscilloscope";
            return settings == null || !matchesType(declaration, type) ? null
                    : configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        }
        if (declaration.interfaceType().equals("audio_player"))
            return audioPlayer == null || !matchesType(declaration, "NiagaraDataInterfaceAudioPlayer") ? null
                    : configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        if (declaration.interfaceType().equals("data_channel")) {
            var channel = dataChannel;
            String type = channel != null && channel.writer() ? "NiagaraDataInterfaceDataChannelWrite" : "NiagaraDataInterfaceDataChannelRead";
            return channel == null || !matchesType(declaration, type) ? null
                    : configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        }
        if (declaration.interfaceType().equals("particle_export"))
            return particleExport == null || !matchesType(declaration, "NiagaraDataInterfaceExport") ? null
                    : configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        if (declaration.interfaceType().equals("skeletal_mesh"))
            return skeletalMeshReader == null || !matchesType(declaration, "NiagaraDataInterfaceSkeletalMesh") ? null
                    : configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        if (declaration.interfaceType().equals("static_mesh"))
            return staticMeshReader == null || !matchesType(declaration, "NiagaraDataInterfaceStaticMesh") ? null
                    : configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        if (declaration.interfaceType().equals("collision_query") && "NiagaraDataInterfaceCollisionQuery".equals(sourceClassName())
                && matchesType(declaration, "NiagaraDataInterfaceCollisionQuery"))
            return configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        if (declaration.interfaceType().equals("console_variable") && "NiagaraDataInterfaceConsoleVariable".equals(sourceClassName())
                && matchesType(declaration, "NiagaraDataInterfaceConsoleVariable"))
            return configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        if (declaration.interfaceType().equals("ribbon_shape") && ribbonProperties != null
                && matchesType(declaration, "NiagaraDataInterfaceRibbon"))
            return configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        if (declaration.interfaceType().equals("object_property_reader"))
            return propertyReader == null || !matchesType(declaration, "NiagaraDataInterfaceUObjectPropertyReader") ? null
                    : configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        if (declaration.interfaceType().equals("property_interface"))
            return propertyInterface == null || !matchesType(declaration, NiagaraPropertyInterface.TYPE) ? null
                    : configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        if (declaration.interfaceType().equals("actor_component"))
            return actorComponent == null || !matchesType(declaration, "NiagaraDataInterfaceActorComponent") ? null
                    : configured(declaration, declaration.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        var target = emitter;
        if (target != null && matchesType(declaration, target.type())
                && (target.type().equals("NiagaraDataInterfaceParticleRead") && declaration.interfaceType().equals("particle_read")
                || target.type().equals("NiagaraDataInterfaceEmitterProperties") && declaration.interfaceType().equals("emitter_properties"))) {
            JsonObject data = Objects.requireNonNull(declaration.interfaceData()).deepCopy();
            data.addProperty("mode", target.mode());
            data.addProperty("emitter", target.emitter());
            return configured(declaration, declaration.curves(), data, declaration.interfaceParameter());
        }
        var renderer = rendererInfo;
        if (renderer != null && (renderer.sprite() != null && matchesType(declaration, "NiagaraDataInterfaceSpriteRendererInfo")
                    && declaration.interfaceType().equals("sprite_renderer_info")
                || renderer.mesh() != null && matchesType(declaration, "NiagaraDataInterfaceMeshRendererInfo")
                    && declaration.interfaceType().equals("mesh_renderer_info")))
            return configured(declaration, declaration.curves(), renderer.data(), declaration.interfaceParameter());
        var view = camera;
        if (view != null && declaration.interfaceType().equals("camera") && matchesType(declaration, "NiagaraDataInterfaceCamera"))
            return new External(declaration.name(), declaration.inputs(), declaration.outputs(), declaration.curves(), declaration.field(),
                    declaration.tileX(), declaration.tileY(), declaration.tileZ(), declaration.interfaceType(), identity,
                    view.controllerIndex(), view.requireCurrent(), declaration.interfaceData(), declaration.interfaceParameter(),
                    declaration.interfaceKey(), declaration.bindingInfo(), declaration.instanceKey());
        if (SourceNames.equal(identity, declaration.interfaceId())) return declaration;
        if (textureDefinition != null && declaration.interfaceType().equals("texture_2d")
                && matchesType(declaration, "NiagaraDataInterfaceTexture"))
            return configured(declaration, declaration.curves(), textureDefinition, declaration.interfaceParameter());
        if (textureDefinition != null && declaration.interfaceType().equals("texture_array")
                && matchesType(declaration, "NiagaraDataInterface2DArrayTexture"))
            return configured(declaration, declaration.curves(), textureDefinition, declaration.interfaceParameter());
        if (textureDefinition != null && declaration.interfaceType().equals("texture_cube")
                && matchesType(declaration, "NiagaraDataInterfaceCubeTexture"))
            return configured(declaration, declaration.curves(), textureDefinition, declaration.interfaceParameter());
        if (textureDefinition != null && declaration.interfaceType().equals("texture_volume")
                && matchesType(declaration, "NiagaraDataInterfaceVolumeTexture"))
            return configured(declaration, declaration.curves(), textureDefinition, declaration.interfaceParameter());
        if (renderTargetDefinition != null && declaration.interfaceType().equals("render_target_2d")
                && matchesType(declaration, "NiagaraDataInterfaceRenderTarget2D"))
            return configured(declaration, declaration.curves(), renderTargetDefinition, declaration.interfaceParameter());
        if (dimensionalTargetDefinition != null && (declaration.interfaceType().equals("render_target_array")
                && matchesType(declaration, "NiagaraDataInterfaceRenderTarget2DArray")
                || declaration.interfaceType().equals("render_target_volume") && matchesType(declaration, "NiagaraDataInterfaceRenderTargetVolume")
                || declaration.interfaceType().equals("render_target_cube") && matchesType(declaration, "NiagaraDataInterfaceRenderTargetCube")))
            return configured(declaration, declaration.curves(), dimensionalTargetDefinition, declaration.interfaceParameter());
        if (platformDefinition != null && declaration.interfaceType().equals("platform_set")
                && matchesType(declaration, "NiagaraDataInterfacePlatformSet"))
            return configured(declaration, declaration.curves(), platformDefinition, declaration.interfaceParameter());
        External function = functions.get(signature(declaration));
        var definition = mutable;
        if (function == null && definition != null && declaration.interfaceKey() != null) {
            boolean array = definition.type().startsWith("NiagaraDataInterfaceArray") && declaration.interfaceType().equals("array");
            boolean counter = definition.type().equals("NiagaraDataInterfaceSimpleCounter") && declaration.interfaceType().equals("simple_counter");
            if (matchesType(declaration, definition.type()) && (array || counter))
                function = configured(declaration, declaration.curves(), definition.data(), definition.parameterName());
        }
        var curves = curve;
        if (function == null && curves != null && matchesType(declaration, curves.type()) && declaration.interfaceType().isEmpty()
                && declaration.field() == null && declaration.inputs() == 1 && declaration.outputs() == curves.curves().size())
            function = configured(declaration, curves.curves(), declaration.interfaceData(), declaration.interfaceParameter());
        var noise = curl;
        if (function == null && noise != null && matchesType(declaration, "NiagaraDataInterfaceCurlNoise")
                && declaration.interfaceType().equals("curl_noise") && declaration.inputs() == 3 && declaration.outputs() == 3)
            function = configured(declaration, declaration.curves(), noise.data(), declaration.interfaceParameter());
        var vector = field;
        if (function == null && vector != null && matchesType(declaration, "NiagaraDataInterfaceVectorField")
                && declaration.interfaceType().equals("vector_field"))
            function = new External(declaration.name(), declaration.inputs(), declaration.outputs(), declaration.curves(),
                    vector.volume(), vector.tileX(), vector.tileY(), vector.tileZ(), declaration.interfaceType(), identity,
                    declaration.controllerIndex(), declaration.requireCurrent(), vector.data().getAsJsonObject("interface_data"),
                    declaration.interfaceParameter(), declaration.interfaceKey(), declaration.bindingInfo(), declaration.instanceKey());
        return function;
    }

    void configurePropertyReader(JsonObject data) { propertyReader = NiagaraObjectPropertyReader.Definition.read(data); }
    public NiagaraObjectPropertyReader.@Nullable Definition propertyReader() { return propertyReader; }
    void configurePropertyInterface(JsonObject data) { propertyInterface = NiagaraPropertyInterface.Definition.read(data); }
    public NiagaraPropertyInterface.@Nullable Definition propertyInterface() { return propertyInterface; }
    void configureActorComponent(JsonObject data) { actorComponent = NiagaraActorComponent.Definition.read(data); }
    public NiagaraActorComponent.@Nullable Definition actorComponent() { return actorComponent; }
    void configureAsyncGpuTrace(JsonObject data) { asyncGpuTrace = NiagaraAsyncGpuTraceDefinition.read(data); }
    public @Nullable NiagaraAsyncGpuTraceDefinition asyncGpuTrace() { return asyncGpuTrace; }
    void configureStaticMeshReader(JsonObject data) { staticMeshReader = NiagaraStaticMeshDefinition.read(data); }
    public @Nullable NiagaraStaticMeshDefinition staticMeshReader() { return staticMeshReader; }
    void configureSkeletalMeshReader(JsonObject data) { skeletalMeshReader = NiagaraSkeletalMeshDefinition.read(data); }
    public @Nullable NiagaraSkeletalMeshDefinition skeletalMeshReader() { return skeletalMeshReader; }
    void configureParticleExport(JsonObject data) { particleExport = NiagaraParticleExportDefinition.read(data); }
    public @Nullable NiagaraParticleExportDefinition particleExport() { return particleExport; }
    void configureDataChannel(JsonObject data) { dataChannel = NiagaraDataChannelDefinition.read(data); }
    public @Nullable NiagaraDataChannelDefinition dataChannel() { return dataChannel; }
    void configureAudioPlayer(JsonObject data) { audioPlayer = NiagaraAudioPlayerDefinition.read(data, NiagaraJson::niagaraCurve); }
    public @Nullable NiagaraAudioPlayerDefinition audioPlayer() { return audioPlayer; }
    void configureAudioAnalysis(JsonObject data) { audioAnalysis = NiagaraAudioAnalysisDefinition.read(data); }
    public @Nullable NiagaraAudioAnalysisDefinition audioAnalysis() { return audioAnalysis; }
    void configureSimCacheReader(JsonObject data) { simCacheReader = NiagaraSimCacheReaderDefinition.read(data); }
    public @Nullable NiagaraSimCacheReaderDefinition simCacheReader() { return simCacheReader; }
    void configureMemoryBuffer(JsonObject data) { memoryBuffer = NiagaraMemoryBufferDefinition.read(data); }
    public @Nullable NiagaraMemoryBufferDefinition memoryBuffer() { return memoryBuffer; }
    void configureDynamicMesh(JsonObject data) { dynamicMesh = NiagaraDynamicMeshDefinition.read(data); }
    public @Nullable NiagaraDynamicMeshDefinition dynamicMesh() { return dynamicMesh; }
    void configureNeighborGrid(JsonObject data) { neighborGrid = NeighborGridDefinition.read(data); }
    public @Nullable NeighborGridDefinition neighborGrid() { return neighborGrid; }
    void configureRasterizationGrid(JsonObject data) { rasterizationGrid = RasterizationGridDefinition.read(data); }
    public @Nullable RasterizationGridDefinition rasterizationGrid() { return rasterizationGrid; }
    void configureVelocityGrid(JsonObject data) { velocityGrid = VelocityGridDefinition.read(data); }
    public @Nullable VelocityGridDefinition velocityGrid() { return velocityGrid; }
    void configureGrid3DCollection(JsonObject data) { grid3DCollection = Grid3DCollectionDefinition.read(data); }
    public @Nullable Grid3DCollectionDefinition grid3DCollection() { return grid3DCollection; }
    void configureGrid2DCollection(JsonObject data) { grid2DCollection = Grid2DCollectionDefinition.read(data); }
    public @Nullable Grid2DCollectionDefinition grid2DCollection() { return grid2DCollection; }
    void configureDataTable(JsonObject data) { dataTable = NiagaraDataTable.Definition.read(data); }
    public NiagaraDataTable.@Nullable Definition dataTable() { return dataTable; }
    void configureGaussianSplatReader(JsonObject data) { gaussianSplatReader = NiagaraGaussianSplatReader.Definition.read(data); }
    public NiagaraGaussianSplatReader.@Nullable Definition gaussianSplatReader() { return gaussianSplatReader; }
    void configureGaussianSplatWriter(JsonObject data) { gaussianSplatWriter = NiagaraGaussianSplatWriter.Definition.read(data); }
    public NiagaraGaussianSplatWriter.@Nullable Definition gaussianSplatWriter() { return gaussianSplatWriter; }
    void configureIntRenderTarget2D(JsonObject data) { intRenderTarget2D = NiagaraIntRenderTarget2D.Definition.read(data); }
    public NiagaraIntRenderTarget2D.@Nullable Definition intRenderTarget2D() { return intRenderTarget2D; }
    void configureSocketReader(JsonObject data) { socketReader = NiagaraSocketReader.Definition.read(data); }
    public NiagaraSocketReader.@Nullable Definition socketReader() { return socketReader; }
    void configureSpline(JsonObject data) { spline = NiagaraSpline.Definition.read(data); }
    public NiagaraSpline.@Nullable Definition spline() { return spline; }
    void configureIntDistribution(JsonObject data) { intDistribution = NiagaraIntDistribution.read(data); }
    public @Nullable NiagaraIntDistribution intDistribution() { return intDistribution; }
    void configureWater(JsonObject data) {
        authoredWater = NiagaraAuthoredWaterBody.read(data.get("saved_body"));
        water = WaterDefinition.read(data);
    }
    public @Nullable WaterDefinition water() { return water; }
    public @Nullable NiagaraAuthoredWaterBody authoredWater() { return authoredWater; }
    void configureNeighborQuery(JsonObject data) { neighborQuery = NiagaraNeighborQuery.Definition.read(data); }
    void configureDebugDraw(JsonObject data) { debugLineLimit = data.get("line_limit").getAsInt(); }
    void configureVolumeCache(JsonObject data) { volumeCache = data.deepCopy(); }
    public @Nullable JsonObject volumeCache() {
        var cache = volumeCache;
        return cache == null ? null : cache.deepCopy();
    }
    void configurePhysicsAsset(JsonObject data) { physicsAsset = data.deepCopy(); }
    void configureRigidMeshCollision(JsonObject data) { rigidMeshCollision = data.deepCopy(); }
    void configureChaosDestruction(JsonObject data) { chaosDestruction = ChaosDestructionDefinition.read(data); }
    public @Nullable ChaosDestructionDefinition chaosDestruction() { return chaosDestruction; }

    void configureHairStrands(JsonObject data) { hairStrands = HairStrandsDefinition.read(data); }
    public @Nullable HairStrandsDefinition hairStrands() { return hairStrands; }

    void configureGeometryCollection(JsonObject data) {
        geometryCollectionAsset = GeometryCollectionAssetJson.read(data.get("saved_collection"));
        geometryCollection = GeometryCollectionDefinition.read(data);
    }
    public @Nullable GeometryCollectionDefinition geometryCollection() { return geometryCollection; }
    public @Nullable RestCollection geometryCollectionAsset() { return geometryCollectionAsset; }
    void configureSceneCapture(JsonObject data) { sceneCapture = data.deepCopy(); }
    void configureLandscape(JsonObject data) { landscape = data.deepCopy(); }
    void configureVirtualTexture(JsonObject data) { virtualTexture = data.deepCopy(); }
    public @Nullable JsonObject virtualTexture() {
        var data = virtualTexture;
        return data == null ? null : data.deepCopy();
    }
    public @Nullable JsonObject landscape() {
        var data = landscape;
        return data == null ? null : data.deepCopy();
    }
    public @Nullable JsonObject sceneCapture() {
        var data = sceneCapture;
        return data == null ? null : data.deepCopy();
    }
    public @Nullable JsonObject rigidMeshCollision() {
        var data = rigidMeshCollision;
        return data == null ? null : data.deepCopy();
    }
    public @Nullable JsonObject physicsAsset() {
        var asset = physicsAsset;
        return asset == null ? null : asset.deepCopy();
    }
    public NiagaraNeighborQuery.@Nullable Definition neighborQuery() { return neighborQuery; }
    void configureDynamicMaterial(JsonObject data) { dynamicMaterial = NiagaraDynamicMaterialWriter.Definition.read(data); }
    public NiagaraDynamicMaterialWriter.@Nullable Definition dynamicMaterial() { return dynamicMaterial; }
    void configureMaterialCollectionWriter(JsonObject data) { materialCollectionWriter = NiagaraMaterialCollectionWriter.Definition.read(data); }
    public NiagaraMaterialCollectionWriter.@Nullable Definition materialCollectionWriter() { return materialCollectionWriter; }
    void configureSparseVolume(JsonObject data) { sparseVolume = SparseVolumeInterfaceDefinition.read(data); }
    public @Nullable SparseVolumeInterfaceDefinition sparseVolume() { return sparseVolume; }
    public boolean usesCpuMemoryBuffer() {
        return functions.values().stream().anyMatch(function -> function.interfaceType().equals("memory_buffer")
                && Operation.valueOf(function.name()).usesWords());
    }

    private External configured(External declaration, List<NiagaraCurve> curves, @Nullable JsonObject data, String parameter) {
        return new External(declaration.name(), declaration.inputs(), declaration.outputs(), curves,
                declaration.field(), declaration.tileX(), declaration.tileY(), declaration.tileZ(), declaration.interfaceType(),
                identity, declaration.controllerIndex(), declaration.requireCurrent(), data, parameter,
                declaration.interfaceKey(), declaration.bindingInfo(), declaration.instanceKey());
    }
    private static boolean matchesType(External declaration, String type) {
        if (declaration.interfaceKey() == null) return false;
        String expected = declaration.interfaceKey().sourceType();
        return SourceNames.equal(expected.substring(expected.lastIndexOf('.') + 1), type);
    }

    private static Function signature(External declaration) {
        JsonObject info = declaration.bindingInfo();
        if (info == null) info = new JsonObject();
        info.remove("OwnerName");
        info.remove("Name");
        return new Function(SourceNames.canonical(declaration.name()), declaration.inputs(), declaration.outputs(), info);
    }
}
