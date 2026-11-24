package com.kltyton.lumenfx.api.client.physics;

import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.Command;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.IntegerCommand;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.ScalarCommand;
import com.kltyton.lumenfx.api.client.physics.NiagaraPhysicsField.VectorCommand;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Saved FieldSystemComponent command graph, ready for explicit dispatch at the host's world time. */
public record AuthoredPhysicsFieldComponent(boolean chaos, boolean world, List<SavedCommand> commands) {
    public AuthoredPhysicsFieldComponent { commands = List.copyOf(commands); }

    public record SavedCommand(Command source, @Nullable String metadataObject) {
        public SavedCommand { Objects.requireNonNull(source); }
    }
    public record Dispatch(List<Command> cpu, List<Command> gpu) {
        public Dispatch { cpu = List.copyOf(cpu); gpu = List.copyOf(gpu); }
    }

    public Dispatch dispatch(float timeSeconds) {
        var started = commands.stream().map(saved -> switch (saved.source()) {
            case IntegerCommand command -> (Command)new IntegerCommand(command.target(), timeSeconds, command.root());
            case ScalarCommand command -> new ScalarCommand(command.target(), timeSeconds, command.root());
            case VectorCommand command -> new VectorCommand(command.target(), timeSeconds, command.root());
        }).toList();
        return new Dispatch(chaos ? started : List.of(), world ? started : List.of());
    }
}
