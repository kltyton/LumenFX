package com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel;

import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelWorlds.Row;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.ChannelColumn;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.ChannelFrame;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.Key;
import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.phys.Vec3;

/** Collects one writer's CPU publication and independent GPU write copies for SimCache. */
public final class NiagaraDataChannelCacheCapture {
    private final List<Row> cpu = new ArrayList<>();
    private final List<CompletableFuture<List<Row>>> gpu = new ArrayList<>();

    void cpu(List<Row> rows) { cpu.addAll(rows); }

    public CompletableFuture<List<int[]>> gpu(Vec3 tile) {
        var submitted = new CompletableFuture<List<int[]>>();
        gpu.add(submitted.thenApply(rows -> rows.stream().map(row -> new Row(row, tile)).toList()));
        return submitted;
    }

    void discard() { cpu.clear(); gpu.clear(); }

    CompletableFuture<ChannelFrame> take(NiagaraDataChannelDefinition definition, boolean rebase) {
        var game = new ArrayList<>(cpu);
        var pending = List.copyOf(gpu);
        discard();
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            for (var result : pending) game.addAll(result.join());
            return encode(definition, game, rebase);
        });
    }

    static ChannelFrame encode(NiagaraDataChannelDefinition definition, List<Row> rows, boolean rebase) {
        var schema = Objects.requireNonNull(definition.channel(), "Cached Niagara data channel writer has no schema");
        var fields = new ArrayList<ChannelColumn>();
        int offset = 0;
        for (var variable : schema.variables()) {
            int width = variable.width();
            var values = new double[Math.multiplyExact(rows.size(), width)];
            boolean position = variable.type().equals("/script/niagara.niagaraposition");
            for (int index = 0; index < rows.size(); index++) {
                var row = rows.get(index);
                for (int component = 0; component < width; component++) {
                    int word = row.words()[offset + component];
                    double value = switch (variable.registers().get(component)) {
                        case FLOAT -> Float.intBitsToFloat(word);
                        case INT -> word;
                        case BOOL -> word == 0 ? 0 : -1;
                        case HALF -> Float.float16ToFloat((short)word);
                    };
                    if (position) value += switch (component) {
                        case 0 -> row.tile().x;
                        case 1 -> row.tile().y;
                        case 2 -> row.tile().z;
                        default -> throw new IllegalArgumentException("Niagara cache Position has more than three components");
                    };
                    values[index * width + component] = value;
                }
            }
            fields.add(new ChannelColumn(new Key(variable.name(), variable.type()), width, values));
            offset += width;
        }
        return new ChannelFrame(rows.size(), definition.publishGame(), definition.publishCpu(), definition.publishGpu(), rebase, fields);
    }
}
