package com.kltyton.lumenfx.effect.niagara.mesh;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Storage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Saved source, filters and per-instance sampling options of a Static Mesh Reader. */
public record NiagaraStaticMeshDefinition(SourceMode mode, String sourceActor, String meshParameter, String lodParameter,
                                          @Nullable Key lodKey, @Nullable Identifier defaultMesh, int lod, int instance,
                                          boolean captureTransforms, boolean physicsVelocity, boolean streamingLods,
                                          List<Integer> allowedSlots, List<String> filteredSockets) {
    public enum SourceMode { DEFAULT, SOURCE, ATTACH_PARENT, DEFAULT_MESH_ONLY, MESH_PARAMETER_BINDING }

    public NiagaraStaticMeshDefinition {
        Objects.requireNonNull(mode);
        Objects.requireNonNull(sourceActor);
        Objects.requireNonNull(meshParameter);
        Objects.requireNonNull(lodParameter);
        allowedSlots = List.copyOf(allowedSlots);
        filteredSockets = List.copyOf(filteredSockets);
    }

    public static NiagaraStaticMeshDefinition read(JsonObject value) {
        if (value.has("asset_source")) throw new IllegalArgumentException("Niagara static mesh source has not been packaged");
        var mode = switch (value.get("source_mode").getAsString()) {
            case "Default" -> SourceMode.DEFAULT;
            case "Source" -> SourceMode.SOURCE;
            case "AttachParent" -> SourceMode.ATTACH_PARENT;
            case "DefaultMeshOnly" -> SourceMode.DEFAULT_MESH_ONLY;
            case "MeshParameterBinding" -> SourceMode.MESH_PARAMETER_BINDING;
            default -> throw new IllegalArgumentException("Unknown Niagara static mesh source mode");
        };
        var allowed = new ArrayList<Integer>();
        for (var slot : value.getAsJsonArray("allowed_slots")) allowed.add(slot.getAsInt());
        var sockets = new ArrayList<String>();
        for (var socket : value.getAsJsonArray("filtered_sockets")) sockets.add(socket.getAsString());
        var lod = value.getAsJsonObject("lod_key");
        var lodKey = lod == null ? null : new Key(lod.get("name").getAsString(), lod.get("source_type").getAsString(),
                lod.get("underlying").getAsInt(), lod.get("flags").getAsInt(), Storage.NUMERIC);
        return new NiagaraStaticMeshDefinition(mode, value.get("source_actor").getAsString(),
                value.get("mesh_parameter").getAsString(), value.get("lod_parameter").getAsString(), lodKey,
                value.has("asset") ? Identifier.parse(value.get("asset").getAsString()) : null,
                value.get("lod").getAsInt(), value.get("instance").getAsInt(), value.get("capture_transforms").getAsBoolean(),
                value.get("physics_velocity").getAsBoolean(), value.get("sample_streaming_lods").getAsBoolean(), allowed, sockets);
    }
}
