package com.kltyton.lumenfx.client.render.niagara.gpu.view;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.view.NiagaraViewQueries;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Uniforms for source view queries that share one host publication across all aliases. */
public final class NiagaraGpuViewQueries {
    private enum Kind { MOUSE, MOVIE }
    private final Map<String, Kind> symbols;
    private NiagaraViewQueries frame = NiagaraViewQueries.EMPTY;

    public NiagaraGpuViewQueries(JsonObject program) {
        var definitions = new HashMap<String, Kind>();
        for (var item : program.getAsJsonArray("interfaces")) {
            var value = item.getAsJsonObject();
            if (!value.has("shader_symbol")) continue;
            var kind = switch (value.get("class").getAsString()) {
                case "NiagaraDataInterfaceMousePosition" -> Kind.MOUSE;
                case "NiagaraDataInterfaceMRQ" -> Kind.MOVIE;
                default -> null;
            };
            if (kind != null) definitions.put(value.get("shader_symbol").getAsString(), kind);
        }
        symbols = Map.copyOf(definitions);
    }

    public void capture(NiagaraViewQueries snapshot) { frame = snapshot; }

    public @Nullable byte[] value(String qualified) {
        String name = qualified.substring(qualified.lastIndexOf('.') + 1);
        int index = name.lastIndexOf('_');
        if (index < 0) return null;
        var kind = symbols.get(name.substring(0, index));
        if (kind == null) return null;
        String member = name.substring(index + 1);
        if (kind == Kind.MOUSE) {
            return member.equals("MousePosition") ? ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                    .putFloat(frame.mouseX()).putFloat(frame.mouseY()).putFloat(frame.viewportWidth()).putFloat(frame.viewportHeight()).array() : null;
        }
        var sample = frame.movie();
        return switch (member) {
            case "Active" -> word(sample.active() ? 1 : 0);
            case "TemporalSampleCount" -> word(sample.sampleCount());
            case "TemporalSampleIndex" -> word(sample.sampleIndex());
            case "SequenceFPS" -> word(Float.floatToRawIntBits((float)sample.sequenceFramesPerSecond()));
            default -> null;
        };
    }

    private static byte[] word(int value) { return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array(); }
}
