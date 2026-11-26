/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.gameobject.emitter.data.model;

import com.kltyton.lumenfx.internal.photon.core.syncdata.annotation.PersistedSetter;
import com.kltyton.lumenfx.internal.photon.core.syncdata.annotation.Persisted;
import com.kltyton.lumenfx.internal.photon.core.LDLib2;
import com.kltyton.lumenfx.internal.photon.core.registry.annotation.LDLRegisterClient;
import com.lowdragmc.photon.client.gameobject.particle.TileParticle;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.resources.Identifier;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Objects;

/**
 * Geometry from a Minecraft JSON model, baked by {@link PhotonModelBaker} straight off the resource
 * manager — any model id works, at any time, with no registration and no resource-pack reload.
 * UVs are block-atlas coordinates (a texture missing from the atlas comes back as the missing
 * sprite; see {@link PhotonModelBaker}). Positions are shifted into centered space and per-face
 * shade factors are baked per quad (the {@code shade} toggle is applied at consumption time).
 */
@LDLRegisterClient(name = "json_model", registry = "photon:model_source")
public class JsonModelSource implements IModelSource {
    @Getter
    @Persisted
    private Identifier modelLocation = Identifier.withDefaultNamespace("block/stone");

    public JsonModelSource() {
    }

    public JsonModelSource(Identifier modelLocation) {
        this.modelLocation = modelLocation;
    }

    @PersistedSetter(field = "modelLocation")
    public void setModelLocation(Identifier modelLocation) {
        invalidate(); // drop the old key's entry before it changes
        this.modelLocation = modelLocation;
    }

    @Override
    public PhotonMesh getMesh() {
        return PhotonMeshCache.INSTANCE.get(new PhotonMeshCache.JsonKey(modelLocation), k -> bake());
    }

    @Override
    public void invalidate() {
        PhotonMeshCache.INSTANCE.invalidate(new PhotonMeshCache.JsonKey(modelLocation));
    }

    @Override
    public boolean hasAtlasUV() {
        return true;
    }

    @Override
    public IModelSource copy() {
        return new JsonModelSource(modelLocation);
    }

    @Nullable
    private PhotonMesh bake() {
        // don't touch the resource manager mid-reload (null = retry later, not cached)
        if (Minecraft.getInstance().gui.overlay() instanceof LoadingOverlay) {
            return null;
        }
        var quads = PhotonModelBaker.bake(modelLocation);
        if (quads == null) {
            return null; // atlas not ready yet
        }
        var collected = new ArrayList<Pair<BakedQuad, Float>>();
        for (var side : TileParticle.MODEL_SIDES) {
            var brightness = side == null ? 1f : switch (side) {
                case DOWN, UP -> 0.9F;
                case NORTH, SOUTH -> 0.8F;
                case WEST, EAST -> 0.6F;
            };
            for (var quad : quads.getQuads(side)) {
                collected.add(Pair.of(quad, brightness));
            }
        }
        return PhotonMesh.fromBakedQuads(collected);
    }

    /** {@code assets/<ns>/models/<path>.json} → {@code <ns>:<path>} (was LDLib2 IModelRenderer.getModelFromFile). */
    @Nullable
    private static Identifier getModelFromFile(File filePath) {
        String fullPath = filePath.getPath().replace('\\', '/');
        int assetsIndex = fullPath.indexOf("assets/");
        if (assetsIndex == -1) return null;
        String relativePath = fullPath.substring(assetsIndex + "assets/".length());
        int slashIndex = relativePath.indexOf('/');
        if (slashIndex == -1) return null;
        String modId = relativePath.substring(0, slashIndex);
        String subPath = relativePath.substring(slashIndex + 1);
        int modelsIndex = subPath.indexOf("models/");
        if (modelsIndex == -1) return null;
        String modelPath = subPath.substring(modelsIndex + "models/".length());
        if (!modelPath.endsWith(".json")) return null;
        String location = modId + ":" + modelPath.substring(0, modelPath.length() - 5);
        return LDLib2.isValidResourceLocation(location) ? Identifier.parse(location) : null;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        return Objects.equals(modelLocation, ((JsonModelSource) o).modelLocation);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(modelLocation);
    }
}
