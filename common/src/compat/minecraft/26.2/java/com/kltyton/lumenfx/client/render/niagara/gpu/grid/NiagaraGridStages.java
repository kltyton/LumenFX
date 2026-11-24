package com.kltyton.lumenfx.client.render.niagara.gpu.grid;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class NiagaraGridStages {
    private NiagaraGridStages() { }

    static List<Set<String>> outputDestinations(JsonObject program) {
        return stageNames(program, "OutputDestinations");
    }

    static List<Set<String>> inputInterfaces(JsonObject program) {
        return stageNames(program, "InputDataInterfaces");
    }

    private static List<Set<String>> stageNames(JsonObject program, String member) {
        var stages = new ArrayList<Set<String>>();
        for (var item : program.getAsJsonArray("stages")) {
            var destinations = item.getAsJsonObject().getAsJsonArray(member);
            var output = new HashSet<String>();
            if (destinations != null) for (var destination : destinations)
                output.add(SourceNames.canonical(destination.getAsString()));
            stages.add(Set.copyOf(output));
        }
        return List.copyOf(stages);
    }
}
