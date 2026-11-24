package com.kltyton.lumenfx.client.simulation.niagara.interfaces.geometry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.kltyton.lumenfx.api.client.geometry.NiagaraGeometryCollectionInputs.Piece;
import com.kltyton.lumenfx.api.client.geometry.NiagaraGeometryCollectionInputs.RestCollection;
import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs.Transform;
import java.util.ArrayList;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class GeometryCollectionAssetJson {
    private GeometryCollectionAssetJson() { }

    public static @Nullable RestCollection read(@Nullable JsonElement source) {
        if (source == null || source.isJsonNull()) return null;
        var data = source.getAsJsonObject();
        var rest = new ArrayList<Transform>();
        for (var item : data.getAsJsonArray("rest")) {
            var pose = item.getAsJsonArray();
            if (pose.size() != 10) throw new IllegalArgumentException("Invalid geometry collection rest transform");
            rest.add(new Transform(pose.get(0).getAsDouble(), pose.get(1).getAsDouble(), pose.get(2).getAsDouble(),
                    pose.get(3).getAsDouble(), pose.get(4).getAsDouble(), pose.get(5).getAsDouble(), pose.get(6).getAsDouble(),
                    pose.get(7).getAsDouble(), pose.get(8).getAsDouble(), pose.get(9).getAsDouble()));
        }
        var pieces = new ArrayList<Piece>();
        for (var item : data.getAsJsonArray("pieces")) {
            var piece = item.getAsJsonObject();
            pieces.add(new Piece(piece.get("transform").getAsInt(), piece.get("leaf").getAsBoolean(),
                    vector(piece.getAsJsonArray("minimum")), vector(piece.getAsJsonArray("maximum"))));
        }
        return new RestCollection(pieces, rest);
    }

    private static Vec3 vector(JsonArray value) {
        if (value.size() != 3) throw new IllegalArgumentException("Invalid geometry collection bounds");
        return new Vec3(value.get(0).getAsDouble(), value.get(1).getAsDouble(), value.get(2).getAsDouble());
    }
}
