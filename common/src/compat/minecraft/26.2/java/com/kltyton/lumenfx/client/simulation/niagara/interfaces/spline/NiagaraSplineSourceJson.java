package com.kltyton.lumenfx.client.simulation.niagara.interfaces.spline;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSplineInputs.Actor;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSplineInputs.Source;
import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs.Transform;
import com.kltyton.lumenfx.client.transform.SceneComponentTransform;
import com.kltyton.lumenfx.effect.curve.NiagaraSplineCurves;
import com.kltyton.lumenfx.effect.curve.NiagaraSplineCurves.DistanceKey;
import com.kltyton.lumenfx.effect.curve.NiagaraSplineCurves.Interpolation;
import com.kltyton.lumenfx.effect.curve.NiagaraSplineCurves.Quaternion;
import com.kltyton.lumenfx.effect.curve.NiagaraSplineCurves.RotationKey;
import com.kltyton.lumenfx.effect.curve.NiagaraSplineCurves.VectorKey;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Converts the saved FSplineCurves and its scene transform into the same source used by live bindings. */
public final class NiagaraSplineSourceJson {
    private NiagaraSplineSourceJson() { }

    public static Actor read(JsonObject actor) {
        var entry = actor.get("component");
        if (entry.isJsonNull()) return new Actor(null);
        var data = entry.getAsJsonObject();
        var component = SceneComponentTransform.authored(data.getAsJsonObject("transform"));
        var matrix = component.matrix();
        var rotation = component.rotation(new Quaterniond());
        var scale = component.scale(new Vector3d());
        var transform = new Transform(matrix.m30(), matrix.m31(), matrix.m32(), rotation.x, rotation.y, rotation.z, rotation.w,
                scale.x, scale.y, scale.z);
        String identity = data.get("identity").getAsString();
        var up = vector(data.getAsJsonArray("up"));
        var legacy = new Source(identity, 0, transform, up, curves(data, new Vec3(scale.x, scale.y, scale.z)));
        var nativeData = data.getAsJsonObject("native_spline");
        var tangent = nativeData != null && nativeData.get("available").getAsBoolean()
                ? new Source(identity, 1, transform, up, curves(nativeData, new Vec3(1, 1, 1))) : null;
        return new Actor(legacy, tangent);
    }

    private static NiagaraSplineCurves curves(JsonObject data, Vec3 scale) {
        var rotations = new ArrayList<RotationKey>();
        for (var item : data.getAsJsonArray("rotations")) {
            var point = item.getAsJsonObject();
            rotations.add(new RotationKey(point.get("time").getAsFloat(), quaternion(point.getAsJsonArray("value")),
                    quaternion(point.getAsJsonArray("arrive")), quaternion(point.getAsJsonArray("leave")),
                    Interpolation.valueOf(point.get("mode").getAsString())));
        }
        var distance = new ArrayList<DistanceKey>();
        for (var item : data.getAsJsonArray("distance")) {
            var point = item.getAsJsonArray();
            distance.add(new DistanceKey(point.get(0).getAsFloat(), point.get(1).getAsFloat()));
        }
        var curves = new NiagaraSplineCurves(vectors(data.getAsJsonArray("positions")), vectors(data.getAsJsonArray("scales")),
                rotations, distance, data.get("loop").getAsFloat());
        if (distance.isEmpty())
            curves = curves.withComponentDistanceTable(data.get("reparam_steps").getAsInt(), scale);
        return curves;
    }

    private static List<VectorKey> vectors(JsonArray points) {
        var result = new ArrayList<VectorKey>(points.size());
        for (var item : points) {
            var point = item.getAsJsonObject();
            result.add(new VectorKey(point.get("time").getAsFloat(), vector(point.getAsJsonArray("value")),
                    vector(point.getAsJsonArray("arrive")), vector(point.getAsJsonArray("leave")),
                    Interpolation.valueOf(point.get("mode").getAsString())));
        }
        return result;
    }

    private static Vec3 vector(JsonArray data) {
        return new Vec3(data.get(0).getAsDouble(), data.get(1).getAsDouble(), data.get(2).getAsDouble());
    }

    private static Quaternion quaternion(JsonArray data) {
        return new Quaternion(data.get(0).getAsDouble(), data.get(1).getAsDouble(), data.get(2).getAsDouble(), data.get(3).getAsDouble());
    }
}
