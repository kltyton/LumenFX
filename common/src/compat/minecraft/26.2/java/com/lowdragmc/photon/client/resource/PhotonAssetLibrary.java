package com.lowdragmc.photon.client.resource;

import com.lowdragmc.lowdraglib2.editor.resource.BuiltinPath;
import com.lowdragmc.lowdraglib2.editor.resource.FilePath;
import com.lowdragmc.lowdraglib2.editor.resource.IResourcePath;
import com.lowdragmc.photon.Photon;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.BlockTextureSheetMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.CustomShaderMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.IMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.material.TextureMaterial;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.IModelSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.JsonModelSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.model.ObjModelSource;
import com.lowdragmc.photon.client.gameobject.emitter.data.shape.MeshData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jetbrains.annotations.Nullable;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Reads authored resource references from enabled packs and publishes a complete generation at reload. */
public final class PhotonAssetLibrary extends SimplePreparableReloadListener<Map<Identifier, PhotonAssetLibrary.Asset>> {
    public static final PhotonAssetLibrary INSTANCE = new PhotonAssetLibrary();
    public static final String SHADER_GRAPH = "shader_graph";
    public static final String SHADER_FUNCTION = "shader_function";
    private static final String MESH = "mesh";
    private static final String MATERIAL = "material";
    private static final long MAX_NBT_BYTES = 64L * 1024 * 1024;

    private volatile Generation current = new Generation(Map.of());

    private PhotonAssetLibrary() {}

    @Override
    protected Map<Identifier, Asset> prepare(ResourceManager resources, ProfilerFiller profiler) {
        var assets = new HashMap<Identifier, Asset>();
        load(resources, assets, "resources", false);
        for (var type : List.of(MESH, MATERIAL, SHADER_GRAPH, SHADER_FUNCTION)) {
            load(resources, assets, type, true);
        }
        return Map.copyOf(assets);
    }

    private static void load(ResourceManager resources, Map<Identifier, Asset> assets, String directory,
                             boolean photonExport) {
        resources.listResources(directory, id -> (!photonExport || id.getNamespace().equals("photon_fx"))
                        && assetType(id.getPath()) != null)
                .forEach((id, resource) -> {
                    try (var stream = new DataInputStream(resource.open())) {
                        var tag = NbtIo.read(stream, NbtAccounter.create(MAX_NBT_BYTES));
                        var type = assetType(id.getPath());
                        if (!type.equals(tag.getStringOr("type", "")) || !(tag.get("data") instanceof CompoundTag data)) {
                            throw new IOException("Expected a " + type + " resource with compound data");
                        }
                        assets.put(id, new Asset(type, data));
                    } catch (IOException error) {
                        throw new UncheckedIOException("Reading Photon resource " + id + " from " + resource.sourcePackId(), error);
                    }
                });
    }

    @Override
    protected void apply(Map<Identifier, Asset> assets, ResourceManager resources, ProfilerFiller profiler) {
        current = new Generation(assets);
    }

    public static Object generation() {
        return INSTANCE.current;
    }

    @Nullable
    public static CompoundTag findGraph(IResourcePath path, String type) {
        var asset = INSTANCE.current.find(path, type);
        return asset == null ? null : asset.data();
    }

    public static IModelSource mesh(IResourcePath path) {
        var generation = INSTANCE.current;
        return generation.meshes.computeIfAbsent(path, key -> {
            if (key instanceof BuiltinPath builtin) {
                return switch (builtin.name()) {
                    case "block" -> new JsonModelSource();
                    case "cube", "sphere", "plane", "quad", "cylinder", "capsule" ->
                            new ObjModelSource(Photon.id("models/" + builtin.name() + ".obj"));
                    default -> throw missing(MESH, key);
                };
            }
            var asset = generation.find(key, MESH);
            if (asset == null) throw missing(MESH, key);
            return new MeshData(asset.data()).getSource();
        });
    }

    public static IMaterial material(IResourcePath path) {
        var generation = INSTANCE.current;
        return generation.materials.computeIfAbsent(path, key -> {
            if (key instanceof BuiltinPath builtin) {
                return switch (builtin.name()) {
                    case "missing" -> IMaterial.MISSING;
                    case "block_atlas" -> BlockTextureSheetMaterial.INSTANCE;
                    case "circle" -> new CustomShaderMaterial(Photon.id("circle"));
                    case "kila_tail", "laser", "smoke", "thaumcraft", "ring" ->
                            new TextureMaterial(Photon.id("textures/particle/" + builtin.name() + ".png"));
                    default -> throw missing(MATERIAL, key);
                };
            }
            var asset = generation.find(key, MATERIAL);
            if (asset == null) throw missing(MATERIAL, key);
            return IMaterial.CODEC.parse(NbtOps.INSTANCE, asset.data())
                    .getOrThrow(message -> new IllegalArgumentException("Invalid Photon material " + key + ": " + message));
        });
    }

    private static IllegalArgumentException missing(String type, IResourcePath path) {
        return new IllegalArgumentException("Missing Photon " + type + " resource: " + path.getResourceName());
    }

    @Nullable
    private static String assetType(String path) {
        if (path.endsWith(".mesh.nbt")) return MESH;
        if (path.endsWith(".material.nbt")) return MATERIAL;
        if (path.endsWith(".shader_graph.nbt")) return SHADER_GRAPH;
        if (path.endsWith(".shader_function.nbt")) return SHADER_FUNCTION;
        return null;
    }

    record Asset(String type, CompoundTag data) {}

    private static final class Generation {
        private final Map<Identifier, Asset> assets;
        private final Map<IResourcePath, IModelSource> meshes = new ConcurrentHashMap<>();
        private final Map<IResourcePath, IMaterial> materials = new ConcurrentHashMap<>();

        private Generation(Map<Identifier, Asset> assets) {
            this.assets = assets;
        }

        @Nullable
        private Asset find(IResourcePath path, String type) {
            if (path instanceof FilePath file && file.location != null) {
                var asset = assets.get(file.location);
                if (asset != null && asset.type().equals(type)) return asset;
            }
            return null;
        }
    }
}
