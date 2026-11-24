package com.kltyton.lumenfx.client.simulation.niagara.interfaces.water;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.parameter.NiagaraSplineInputs.Source;
import com.kltyton.lumenfx.api.client.water.NiagaraGerstnerWaves;
import com.kltyton.lumenfx.api.client.water.NiagaraGerstnerWaves.Wave;
import com.kltyton.lumenfx.api.client.water.NiagaraWaterBodySnapshot;
import com.kltyton.lumenfx.api.client.water.NiagaraWaterBodySnapshot.Spline;
import com.kltyton.lumenfx.api.client.water.NiagaraWaterBodySnapshot.SplineSample;
import com.kltyton.lumenfx.api.client.water.NiagaraWaterBodySnapshot.Terrain;
import com.kltyton.lumenfx.api.client.water.NiagaraWaterBodySnapshot.Type;
import com.kltyton.lumenfx.client.resource.EffectJson;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.spline.NiagaraSplineSourceJson;
import com.kltyton.lumenfx.client.transform.SceneComponentTransform;
import com.kltyton.lumenfx.effect.curve.FloatCurve;
import java.util.ArrayList;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

public record NiagaraAuthoredWaterBody(Type type, float height, boolean hasMetadata, float waveMaskDepth,
                                       @Nullable Spline spline, @Nullable NiagaraGerstnerWaves waves) {
    public static @Nullable NiagaraAuthoredWaterBody read(@Nullable JsonElement entry) {
        if (entry == null || entry.isJsonNull()) return null;
        var data = entry.getAsJsonObject();
        var type = Type.valueOf(data.get("type").getAsString());
        var transform = SceneComponentTransform.authored(data.getAsJsonObject("transform"));
        var savedSpline = data.get("spline");
        var spline = savedSpline.isJsonNull() ? null : NiagaraSplineSourceJson.read(savedSpline.getAsJsonObject()).firstSpline();
        var flow = spline == null ? null : new AuthoredSpline(spline, curve(data.get("depth")), curve(data.get("speed")));
        return new NiagaraAuthoredWaterBody(type, (float)transform.matrix().m32(),
                data.get("has_spline_metadata").getAsBoolean(), data.get("wave_mask_depth").getAsFloat(), flow, waves(data.get("waves")));
    }

    public NiagaraWaterBodySnapshot snapshot(float seconds, float oceanFallback, @Nullable Terrain terrain) {
        // The source index and ocean height offset are assigned by the active water world, after loading.
        return new NiagaraWaterBodySnapshot(-1, type, height, 0, true, hasMetadata, seconds,
                waveMaskDepth, oceanFallback, spline, terrain, null, waves, position -> false);
    }

    private static FloatCurve curve(JsonElement value) {
        return value.isJsonNull() ? FloatCurve.constant(0) : EffectJson.curve(value);
    }

    private static @Nullable NiagaraGerstnerWaves waves(JsonElement value) {
        if (value.isJsonNull()) return null;
        var waves = new ArrayList<Wave>();
        for (var entry : value.getAsJsonArray()) {
            var wave = entry.getAsJsonObject();
            var direction = wave.getAsJsonArray("direction");
            var axis = new Vec3(direction.get(0).getAsDouble(), direction.get(1).getAsDouble(), direction.get(2).getAsDouble());
            float amplitude = wave.get("amplitude").getAsFloat();
            var defaults = Wave.fromParameters(wave.get("wavelength").getAsFloat(), amplitude, wave.get("steepness").getAsFloat(), axis);
            var vector = wave.get("vector");
            double x = vector.isJsonNull() ? defaults.vectorX() : vector.getAsJsonArray().get(0).getAsDouble();
            double y = vector.isJsonNull() ? defaults.vectorY() : vector.getAsJsonArray().get(1).getAsDouble();
            waves.add(new Wave(amplitude, axis, x, y, number(wave, "speed", defaults.speed()),
                    number(wave, "wka", defaults.wka()), number(wave, "q", defaults.q())));
        }
        return new NiagaraGerstnerWaves(waves);
    }

    private static float number(JsonObject object, String key, float fallback) {
        var value = object.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsFloat();
    }

    private static final class AuthoredSpline implements Spline {
        private static final Vec3 UP = new Vec3(0, 0, 1);
        private final Source source;
        private final Quaterniondc rotation;
        private final Quaterniondc inverse;
        private final Vec3 location;
        private final Vec3 scale;
        private final FloatCurve depth;
        private final FloatCurve speed;

        AuthoredSpline(Source source, FloatCurve depth, FloatCurve speed) {
            this.source = source;
            var transform = source.transform();
            rotation = new Quaterniond(transform.qx(), transform.qy(), transform.qz(), transform.qw());
            inverse = new Quaterniond(rotation).conjugate();
            location = new Vec3(transform.x(), transform.y(), transform.z());
            scale = new Vec3(transform.scaleX(), transform.scaleY(), transform.scaleZ());
            this.depth = depth;
            this.speed = speed;
        }

        @Override public SplineSample closest(Vec3 position) {
            var relative = rotate(inverse, position.subtract(location));
            var local = new Vec3(relative.x * reciprocal(scale.x), relative.y * reciprocal(scale.y), relative.z * reciprocal(scale.z));
            var curves = source.curves();
            float key = curves.nearestPositionTime(local);
            var point = rotate(rotation, curves.position(key).multiply(scale)).add(location);
            var tangent = unit(curves.tangent(key));
            var direction = unit(rotate(rotation, tangent.multiply(scale)));
            var up = unit(rotate(curves.rotation(key), source.defaultUp()));
            if (Math.abs(Math.abs(tangent.dot(up)) - 1) <= 1e-4) up = Math.abs(tangent.z) < .9999 ? UP : new Vec3(1, 0, 0);
            var upAxis = tangent.cross(unit(up.cross(tangent)));
            var worldUp = rotate(rotation, tangent.lengthSqr() == 0 ? UP : upAxis);
            return new SplineSample((float)point.z, worldUp, direction, (float)depth.sample(key), (float)speed.sample(key));
        }

        private static Vec3 rotate(Quaterniondc rotation, Vec3 value) {
            var vector = rotation.transform(new Vector3d(value.x, value.y, value.z));
            return new Vec3(vector.x, vector.y, vector.z);
        }

        private static double reciprocal(double scale) { return Math.abs(scale) <= 1e-8 ? 0 : 1 / scale; }

        private static Vec3 unit(Vec3 value) {
            double square = value.lengthSqr();
            if (square < 1e-8) return Vec3.ZERO;
            return square == 1 ? value : value.scale(1 / Math.sqrt(square));
        }
    }
}
