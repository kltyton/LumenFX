package com.kltyton.lumenfx.effect.niagara.mesh;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Authored source selection, skinning and sampling configuration of a Skeletal Mesh Reader. */
public record NiagaraSkeletalMeshDefinition(SourceMode mode, Skinning skinning, String actor, String component,
                                            String componentClass, List<String> componentTags, String meshParameter,
                                            @Nullable Identifier asset, List<String> regions, int wholeMeshLod,
                                            List<String> bones, List<String> sockets, boolean excludeBone, String excludedBone,
                                            int uvSet, boolean requireCurrent, boolean readDeformed) {
    public enum SourceMode { DEFAULT, SOURCE, ATTACH_PARENT, DEFAULT_MESH_ONLY, CHILDREN_ONLY }
    public enum Skinning { NONE, SKIN_ON_THE_FLY, PRE_SKIN }

    public NiagaraSkeletalMeshDefinition {
        componentTags = List.copyOf(componentTags); regions = List.copyOf(regions);
        bones = List.copyOf(bones); sockets = List.copyOf(sockets);
    }

    public static NiagaraSkeletalMeshDefinition read(JsonObject value) {
        if (value.has("asset_source")) throw new IllegalArgumentException("Niagara skeletal mesh source has not been packaged");
        var mode = switch (text(value, "source_mode")) {
            case "Default" -> SourceMode.DEFAULT;
            case "Source" -> SourceMode.SOURCE;
            case "AttachParent" -> SourceMode.ATTACH_PARENT;
            case "DefaultMeshOnly" -> SourceMode.DEFAULT_MESH_ONLY;
            case "ChildrenOnly" -> SourceMode.CHILDREN_ONLY;
            default -> throw new IllegalArgumentException("Unknown Niagara skeletal source mode");
        };
        var skinning = switch (text(value, "skinning")) {
            case "None" -> Skinning.NONE;
            case "SkinOnTheFly" -> Skinning.SKIN_ON_THE_FLY;
            case "PreSkin" -> Skinning.PRE_SKIN;
            default -> throw new IllegalArgumentException("Unknown Niagara skeletal skinning mode");
        };
        return new NiagaraSkeletalMeshDefinition(mode, skinning, text(value, "source_actor"), text(value, "source_component"),
                text(value, "component_class"), names(value, "component_tags"), text(value, "mesh_parameter"),
                value.has("asset") ? Identifier.parse(text(value, "asset")) : null, names(value, "regions"),
                value.get("whole_mesh_lod").getAsInt(), names(value, "filtered_bones"), names(value, "filtered_sockets"),
                value.get("exclude_bone").getAsBoolean(), text(value, "excluded_bone"), value.get("uv_set").getAsInt(),
                value.get("require_current").getAsBoolean(), value.get("read_deformed").getAsBoolean());
    }

    private static String text(JsonObject value, String name) { return value.get(name).getAsString(); }
    private static List<String> names(JsonObject value, String name) {
        var result = new ArrayList<String>();
        for (var item : value.getAsJsonArray(name)) result.add(item.getAsString());
        return result;
    }
}
