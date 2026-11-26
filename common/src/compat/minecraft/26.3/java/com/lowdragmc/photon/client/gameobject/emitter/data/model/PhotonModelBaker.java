/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import com.kltyton.lumenfx.client.platform.LumenClientPlatform;
import com.lowdragmc.photon.Photon;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.dispatch.BlockModelRotation;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelDebugName;
import net.minecraft.client.resources.model.ResolvedModel;
import net.minecraft.client.resources.model.SimpleModelWrapper;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.resources.model.sprite.MaterialBaker;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.client.resources.model.sprite.TextureSlots;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3fc;

/** Bakes a Minecraft JSON model into geometry against the live block atlas on MC 26.3. */
final class PhotonModelBaker {
    private static final FileToIdConverter MODEL_LISTER = FileToIdConverter.json("models");
    private static final int MAX_PARENT_DEPTH = 64;
    private static final UnbakedModel EMPTY_MODEL = new UnbakedModel() { };

    private PhotonModelBaker() { }

    @Nullable
    static QuadCollection bake(Identifier modelId) {
        try {
            var baker = new Baker();
            var model = baker.getModel(modelId);
            return model.bakeTopGeometry(model.getTopTextureSlots(), baker, BlockModelRotation.IDENTITY);
        } catch (AtlasNotReadyException e) {
            return null;
        } catch (Exception e) {
            Photon.LOGGER.warn("Failed to bake json model {}", modelId, e);
            return QuadCollection.EMPTY;
        }
    }

    private static final class AtlasNotReadyException extends RuntimeException {
        AtlasNotReadyException(Throwable cause) {
            super(null, cause, false, false);
        }
    }

    private static final class Baker implements ModelBaker, ModelBaker.Interner {
        private final Map<Identifier, ResolvedModel> resolved = new HashMap<>();
        private final Set<Identifier> resolving = new HashSet<>();
        private final Map<SharedOperationKey<Object>, Object> operations = new HashMap<>();
        private final LiveAtlasMaterials materials = new LiveAtlasMaterials();
        @Nullable
        private BlockStateModelPart missingPart;

        @Override
        public ResolvedModel getModel(Identifier location) {
            var model = resolve(location, 0);
            return model == null ? new Resolved(EMPTY_MODEL, location.toString()) : model;
        }

        @Nullable
        private ResolvedModel resolve(Identifier location, int depth) {
            var cached = resolved.get(location);
            if (cached != null) return cached;
            if (depth > MAX_PARENT_DEPTH || !resolving.add(location)) {
                Photon.LOGGER.warn("Cyclic or too-deep json model parent chain at {}, stopping", location);
                return null;
            }
            try {
                var unbaked = read(location);
                var model = new Resolved(unbaked, location.toString());
                var parentId = unbaked.parent();
                model.parent = parentId == null ? null : resolve(parentId, depth + 1);
                resolved.put(location, model);
                return model;
            } finally {
                resolving.remove(location);
            }
        }

        private static UnbakedModel read(Identifier location) {
            var manager = Minecraft.getInstance().getResourceManager();
            var file = MODEL_LISTER.idToFile(location);
            var resource = manager.getResource(file).orElse(null);
            if (resource == null) {
                Photon.LOGGER.warn("Missing json model: {}", location);
                return EMPTY_MODEL;
            }
            try (var reader = resource.openAsReader()) {
                return LumenClientPlatform.parseUnbakedModel(reader);
            } catch (Exception e) {
                Photon.LOGGER.warn("Failed to parse json model {}", location, e);
                return EMPTY_MODEL;
            }
        }

        @Override
        public BlockStateModelPart missingBlockModelPart() {
            if (missingPart == null) {
                missingPart = new SimpleModelWrapper(QuadCollection.EMPTY, true, materials.missing);
            }
            return missingPart;
        }

        @Override
        public MaterialBaker materials() {
            return materials;
        }

        @Override
        public Interner interner() {
            return this;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T compute(SharedOperationKey<T> key) {
            var existing = operations.get(key);
            if (existing != null) return (T) existing;
            var value = key.compute(this);
            operations.put((SharedOperationKey<Object>) key, value);
            return value;
        }

        @Override
        public Vector3fc vector(Vector3fc vector) {
            return vector;
        }

        @Override
        public BakedQuad.MaterialInfo materialInfo(BakedQuad.MaterialInfo material) {
            return material;
        }
    }

    private static final class LiveAtlasMaterials extends MaterialBaker {
        private final Material.Baked missing;
        private final Map<Material, Material.Baked> cache = new HashMap<>();

        LiveAtlasMaterials() {
            this(sprite(MissingTextureAtlasSprite.getLocation()));
        }

        private LiveAtlasMaterials(TextureAtlasSprite missingSprite) {
            super(livePreparations(missingSprite), livePreparations(missingSprite));
            this.missing = new Material.Baked(missingSprite, false);
        }

        private static SpriteLoader.Preparations livePreparations(TextureAtlasSprite missingSprite) {
            return new SpriteLoader.Preparations(16, 16, 0, missingSprite, Map.of(), CompletableFuture.completedFuture(null));
        }

        @Override
        public Material.Baked get(Material material, ModelDebugName name) {
            return cache.computeIfAbsent(material, m -> new Material.Baked(sprite(m.sprite()), m.forceTranslucent()));
        }

        @Override
        public Material.Baked resolveSlot(TextureSlots slots, @Nullable String id, ModelDebugName name) {
            return id == null ? missing : super.resolveSlot(slots, id, name);
        }

        private static TextureAtlasSprite sprite(Identifier texture) {
            try {
                return Minecraft.getInstance().getAtlasManager().get(new SpriteId(Sheets.BLOCKS_MAPPER.sheet(), texture));
            } catch (Exception e) {
                throw new AtlasNotReadyException(e);
            }
        }
    }

    private static final class Resolved implements ResolvedModel {
        private final UnbakedModel wrapped;
        private final String debugName;
        @Nullable
        private ResolvedModel parent;

        Resolved(UnbakedModel wrapped, String debugName) {
            this.wrapped = wrapped;
            this.debugName = debugName;
        }

        @Override public UnbakedModel wrapped() { return wrapped; }
        @Nullable @Override public ResolvedModel parent() { return parent; }
        @Override public String debugName() { return debugName; }
    }
}
