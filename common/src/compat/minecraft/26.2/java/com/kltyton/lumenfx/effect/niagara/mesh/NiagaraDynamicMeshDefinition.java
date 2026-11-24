package com.kltyton.lumenfx.effect.niagara.mesh;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import java.util.ArrayList;
import java.util.List;

/** Authored capacity, section order, and vertex layout of a Niagara Dynamic Mesh. */
public record NiagaraDynamicMeshDefinition(int vertices, int texcoords, boolean colors, boolean tangents, boolean clearTrianglesPerFrame,
                                           NiagaraBounds bounds, List<Section> sections, List<Material> materials) {
    public record Section(int triangles, int materialIndex) {
        public Section { if (triangles < 0) throw new IllegalArgumentException("Negative Niagara mesh triangle capacity"); }
    }
    public record Material(String source, String userParameter) { }

    public NiagaraDynamicMeshDefinition {
        if (vertices < 0 || texcoords < 0) throw new IllegalArgumentException("Invalid Niagara dynamic mesh vertex layout");
        sections = List.copyOf(sections);
        materials = List.copyOf(materials);
    }

    public static NiagaraDynamicMeshDefinition read(JsonObject source) {
        var sections = new ArrayList<Section>();
        for (var element : source.getAsJsonArray("sections")) {
            var section = element.getAsJsonObject();
            sections.add(new Section(section.get("triangles").getAsInt(), section.get("material_index").getAsInt()));
        }
        var materials = new ArrayList<Material>();
        for (var element : source.getAsJsonArray("materials")) {
            var material = element.getAsJsonObject();
            materials.add(new Material(material.get("material_source").getAsString(), material.get("user_parameter").getAsString()));
        }
        var box = source.get("bounds");
        NiagaraBounds bounds = NiagaraBounds.DEFAULT;
        if (box != null && !box.isJsonNull()) {
            var data = box.getAsJsonObject();
            var min = data.getAsJsonArray("min");
            var max = data.getAsJsonArray("max");
            bounds = new NiagaraBounds(min.get(0).getAsDouble(), min.get(1).getAsDouble(), min.get(2).getAsDouble(),
                    max.get(0).getAsDouble(), max.get(1).getAsDouble(), max.get(2).getAsDouble());
        }
        return new NiagaraDynamicMeshDefinition(source.get("vertices").getAsInt(), source.get("texcoords").getAsInt(),
                source.get("has_colors").getAsBoolean(), source.get("has_tangent_basis").getAsBoolean(),
                source.get("clear_triangles_per_frame").getAsBoolean(), bounds, sections, materials);
    }
}
