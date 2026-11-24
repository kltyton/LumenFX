package com.kltyton.lumenfx.client.simulation.niagara.interfaces;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment.ConsoleType;

/** One compiled console-variable lookup and its source representation. */
public record NiagaraConsoleVariable(String name, ConsoleType type) {
    public record Initialization(NiagaraPlatformEnvironment environment) { }

    /** The name and type are resolved at binding. Only the value is sampled by later ticks. */
    public static final class Bound {
        private final NiagaraConsoleVariable request;
        private final boolean valid;
        private int word;

        private Bound(NiagaraConsoleVariable request, boolean valid) {
            this.request = request;
            this.valid = valid;
        }

        public void sample(NiagaraPlatformEnvironment environment) {
            if (!valid) return;
            var value = environment.console(request.name());
            if (value == null || value.type() != request.type()) return;
            word = request.type() == ConsoleType.BOOL && value.bits() != 0 ? 1 : value.bits();
        }

        public boolean valid() { return valid; }
        public int word() { return word; }
        public int vmWord() { return request.type() == ConsoleType.BOOL && word != 0 ? -1 : word; }
    }

    public static NiagaraConsoleVariable read(String function, JsonObject binding) {
        ConsoleType type = switch (function) {
            case "GetConsoleVariableFloat" -> ConsoleType.FLOAT;
            case "GetConsoleVariableInt" -> ConsoleType.INT;
            case "GetConsoleVariableBool" -> ConsoleType.BOOL;
            default -> throw new IllegalArgumentException("Unknown Niagara console variable function: " + function);
        };
        return new NiagaraConsoleVariable(binding.get("console_name").getAsString(), type);
    }

    public Bound bind(NiagaraPlatformEnvironment environment) {
        var value = name.isEmpty() || SourceNames.equal(name, "None") ? null : environment.console(name);
        return new Bound(this, value != null && value.type() == type);
    }
}
