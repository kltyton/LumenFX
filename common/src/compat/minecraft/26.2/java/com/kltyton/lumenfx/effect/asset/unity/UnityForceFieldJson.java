package com.kltyton.lumenfx.effect.asset.unity;

import com.kltyton.lumenfx.effect.asset.EffectJsonFields;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.kltyton.lumenfx.effect.noise.VectorFieldVolume;
import com.kltyton.lumenfx.effect.unity.UnityExternalForces;
import com.lowdragmc.photon.client.gameobject.forcefield.ForceFieldConfig.Shape;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Locale;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

final class UnityForceFieldJson {
    private UnityForceFieldJson() { }

    static @Nullable UnityExternalForces read(@Nullable JsonObject value) {
        if (value == null) return null;
        var fields = new ArrayList<UnityExternalForces.Field>();
        for (var element : value.getAsJsonArray("fields")) {
            var field = element.getAsJsonObject();
            String name = field.get("shape").getAsString().toLowerCase(Locale.ROOT);
            Shape shape = Shape.valueOf(Character.toUpperCase(name.charAt(0)) + name.substring(1));
            float end = field.get("end").getAsFloat();
            float length = field.get("length").getAsFloat();
            fields.add(new UnityExternalForces.Field(shape, vector(field, "center"), vector(field, "x_axis"),
                    vector(field, "y_axis"), vector(field, "z_axis"), field.get("start").getAsFloat(), end, length,
                    EffectJsonFields.components(field, "direction", 3), EffectJsonFields.scalar(field, "gravity", 0),
                    field.get("gravity_focus").getAsFloat(), EffectJsonFields.scalar(field, "rotation_speed", 0),
                    EffectJsonFields.scalar(field, "rotation_attraction", 0), field.get("rotation_randomness_x").getAsFloat(),
                    field.get("rotation_randomness_z").getAsFloat(), EffectJsonFields.scalar(field, "drag", 0),
                    field.get("drag_by_size").getAsBoolean(), field.get("drag_by_speed").getAsBoolean(),
                    volume(field.getAsJsonObject("vector_field"), shape, end, length),
                    EffectJsonFields.scalar(field, "vector_speed", 0), EffectJsonFields.scalar(field, "vector_attraction", 0),
                    EffectJsonFields.jsonString(field, "source_id"), !field.has("enabled") || field.get("enabled").getAsBoolean()));
        }
        var windZones = new ArrayList<UnityExternalForces.WindZone>();
        var winds = EffectJsonFields.jsonArray(value, "wind_zones");
        if (winds != null) {
            for (var element : winds) {
                var wind = element.getAsJsonObject();
                var mode = UnityExternalForces.WindZone.Mode.valueOf(
                        EffectJsonFields.string(wind, "mode", "directional").toUpperCase(Locale.ROOT));
                windZones.add(new UnityExternalForces.WindZone(mode, vector(wind, "center"),
                        EffectJsonFields.vector(wind, "direction", new Vec3(0, 0, -1)),
                        wind.has("radius") ? wind.get("radius").getAsFloat() : 0f,
                        wind.has("main") ? wind.get("main").getAsFloat() : 0f,
                        wind.has("turbulence") ? wind.get("turbulence").getAsFloat() : 0f,
                        wind.has("pulse_magnitude") ? wind.get("pulse_magnitude").getAsFloat() : 0f,
                        wind.has("pulse_frequency") ? wind.get("pulse_frequency").getAsFloat() : 0f,
                        EffectJsonFields.jsonString(wind, "source_id"),
                        !wind.has("enabled") || wind.get("enabled").getAsBoolean()));
            }
        }
        return new UnityExternalForces(EffectJsonFields.scalar(value, "multiplier", 1), fields, windZones);
    }

    private static Vec3 vector(JsonObject field, String name) {
        return EffectJsonFields.vector(field, name, Vec3.ZERO);
    }

    private static @Nullable VectorFieldVolume volume(@Nullable JsonObject input, Shape shape, float radius, float length) {
        if (input == null) return null;
        int width = input.get("width").getAsInt(), height = input.get("height").getAsInt(), depth = input.get("depth").getAsInt();
        if (width < 1 || height < 1 || depth < 1) throw new JsonParseException("Invalid Unity vector Texture3D dimensions");
        int count = Math.multiplyExact(Math.multiplyExact(width, height), Math.multiplyExact(depth, 3));
        var bytes = Base64.getDecoder().decode(input.get("rgb32le").getAsString());
        if (bytes.length != Math.multiplyExact(count, Float.BYTES))
            throw new JsonParseException("Unity vector Texture3D size disagrees with its texels");
        float[] vectors = new float[count];
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            vectors[i] = data.getFloat();
            if (!Float.isFinite(vectors[i])) throw new JsonParseException("Non-finite Unity vector Texture3D texel");
        }
        double extent = radius > 0 ? radius : 1;
        double vertical = shape == Shape.Cylinder && length > 0 ? length * .5 : extent;
        return new VectorFieldVolume(width, height, depth, -extent, shape == Shape.Hemisphere ? 0 : -vertical,
                -extent, extent, vertical, extent, vectors);
    }
}
