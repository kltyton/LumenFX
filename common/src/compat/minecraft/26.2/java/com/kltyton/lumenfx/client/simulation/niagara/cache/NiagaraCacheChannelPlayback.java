package com.kltyton.lumenfx.client.simulation.niagara.cache;

import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.api.client.NiagaraDataChannels.Row;
import com.kltyton.lumenfx.api.client.NiagaraDataChannels.Visibility;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelWorlds;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.ChannelColumn;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.ChannelFrame;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Transform;
import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition;
import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition.Variable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/** Replays the saved game-level columns through the live channel's world publication path. */
final class NiagaraCacheChannelPlayback {
    private static final String POSITION = "/script/niagara.niagaraposition";
    private record Column(Variable target, ChannelColumn data) { }

    private NiagaraCacheChannelPlayback() { }

    static void publish(@Nullable ClientLevel world, NiagaraDataChannelDefinition writer, ChannelFrame saved,
                        Transform recorded, Pose owner, Vec3 tile) {
        if (!writer.writer()) throw new IllegalArgumentException("Niagara cache channel writer is bound to a reader");
        var channel = Objects.requireNonNull(writer.channel(), "Niagara cache channel writer has no channel asset");
        if (saved.rows() == 0 || !saved.game() && !saved.cpu() && !saved.gpu()) return;
        Objects.requireNonNull(world, "Niagara cache channel writer requires a client world");
        var columns = new ArrayList<Column>();
        for (var column : saved.fields()) {
            for (var target : channel.variables()) {
                if (!target.name().equals(column.field().name()) || !target.type().equals(column.field().type())) continue;
                if (target.width() != column.width())
                    throw new IllegalArgumentException("Niagara cache channel field layout changed: " + target.name());
                columns.add(new Column(target, column));
                break;
            }
        }
        var rebase = saved.rebase() ? NiagaraCachePlayback.rebaseMatrix(recorded, owner, tile) : null;
        var rows = new ArrayList<Row>(saved.rows());
        for (int row = 0; row < saved.rows(); row++) {
            var fields = new LinkedHashMap<String, List<Number>>();
            for (var column : columns) {
                var value = column.data();
                var variable = column.target();
                if (rebase != null && variable.type().equals(POSITION)) {
                    var p = rebase.transformPosition(new Vector3d(value.component(row, 0), value.component(row, 1), value.component(row, 2)));
                    fields.put(variable.name(), List.of(p.x, p.y, p.z));
                } else {
                    var components = new ArrayList<Number>(value.width());
                    for (int i = 0; i < value.width(); i++) components.add(value.component(row, i));
                    fields.put(variable.name(), components);
                }
            }
            rows.add(new Row(fields));
        }
        NiagaraDataChannelWorlds.publishGame(world, channel, owner.position(), rows, new Visibility(saved.game(), saved.cpu(), saved.gpu()));
    }
}
