package com.kltyton.lumenfx.client.resource.shader;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.resource.niagara.SpriteRendererInfoJson;
import com.kltyton.lumenfx.effect.render.sprite.SpriteRendererInformation;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Default shader parameters from Niagara sprite-renderer information interfaces. */
public final class PreparedNiagaraSpriteInfo {
    private final Map<String, byte[]> values;

    public PreparedNiagaraSpriteInfo(JsonObject program) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (var item : program.getAsJsonArray("interfaces")) {
            JsonObject binding = item.getAsJsonObject();
            if (!"NiagaraDataInterfaceSpriteRendererInfo".equals(binding.get("class").getAsString())
                    || !binding.has("shader_symbol") || !binding.has("sprite_info")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            result.putAll(parameters(symbol, SpriteRendererInfoJson.read(binding.getAsJsonObject("sprite_info"))));
        }
        values = Map.copyOf(result);
    }

    public @Nullable byte[] value(String name) {
        byte[] value = values.get(name);
        return value == null ? null : value.clone();
    }
    public static Map<String, byte[]> parameters(String symbol, SpriteRendererInformation source) {
        var result = new LinkedHashMap<String, byte[]>();
        result.put(symbol + "_bIsValid", words(source.valid() ? 1 : 0));
        result.put(symbol + "_SourceMode", words(source.sourceMode()));
        result.put(symbol + "_Alignment", words(source.alignment()));
        result.put(symbol + "_FacingMode", words(source.facingMode()));
        result.put(symbol + "_bSubImageBlend", words(source.subImageBlend() ? 1 : 0));
        result.put(symbol + "_SubImageSize", words(Float.floatToRawIntBits(source.columns()), Float.floatToRawIntBits(source.rows())));
        return Map.copyOf(result);
    }

    private static byte[] words(int... words) {
        ByteBuffer data = ByteBuffer.allocate(Math.multiplyExact(words.length, Integer.BYTES)).order(ByteOrder.LITTLE_ENDIAN);
        for (int word : words) data.putInt(word);
        return data.array();
    }
}
