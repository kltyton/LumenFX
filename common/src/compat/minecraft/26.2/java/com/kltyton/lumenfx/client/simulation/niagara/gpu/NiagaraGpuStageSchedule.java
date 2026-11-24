package com.kltyton.lumenfx.client.simulation.niagara.gpu;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/** Builds the per-tick stage sequence from Niagara's saved stage and loop metadata. */
public final class NiagaraGpuStageSchedule {
    public interface Parameters {
        @Nullable Integer integer(String name);
        @Nullable Boolean bool(String name);
    }
    public enum ParticleAccess { READ_ONLY, PARTIAL, FULL }
    public record Invocation(int stageIndex, int iterations, int loopIndex, int loops,
                             int elementsX, int elementsY, int elementsZ, boolean first, ParticleAccess access,
                             String iterationSource, String iterationDataInterface, String dispatchType, String directElementType, boolean indirect,
                             int particleStateComponent, int particleStateMinimum, int particleStateMaximum) { }

    private record Stage(String name, String enabled, String behavior, String source, String dataInterface, String dispatch, String directElementType, boolean indirect,
                         String iterationsBinding, int iterations, int[] elements, String[] elementBindings,
                         ParticleAccess access, int stateComponent, int stateMinimum, int stateMaximum) { }
    private record Loop(String binding, int count, int start, int end) { }

    private final List<Stage> stages;
    private final List<Loop> loops;
    private final boolean validEditorLoops;

    public NiagaraGpuStageSchedule(JsonObject program, List<NiagaraDefinition.Attribute> attributes) {
        JsonArray source = program.getAsJsonArray("stages");
        if (source == null || source.isEmpty()) throw new IllegalArgumentException("Missing Niagara GPU stages");
        List<Stage> parsed = new ArrayList<>();
        for (var item : source) parsed.add(stage(item.getAsJsonObject(), attributes));
        stages = List.copyOf(parsed);
        JsonObject settings = program.getAsJsonObject("emitter_settings");
        if (settings == null) throw new IllegalArgumentException("Missing Niagara GPU emitter settings");
        if (settings.has("SimStageExecutionLoopEditorData")) {
            LoopBuild built = editorLoops(settings.getAsJsonArray("SimStageExecutionLoopEditorData"), stages);
            loops = built.loops();
            validEditorLoops = built.valid();
        } else {
            loops = savedLoops(settings.getAsJsonArray("SimStageExecutionLoops"), stages.size());
            validEditorLoops = true;
        }
    }

    public boolean validEditorLoops() { return validEditorLoops; }

    public void forEach(Parameters parameters, boolean reset, Consumer<Invocation> consumer) {
        boolean first = true;
        for (Loop loop : loops) {
            int count = clamp(resolveInteger(parameters, loop.binding(), loop.count()));
            for (int loopIndex = 0; loopIndex < count; loopIndex++) {
                for (int index = loop.start(); index <= loop.end(); index++) {
                    Stage stage = stages.get(index);
                    if (!shouldRun(stage.behavior(), reset)) continue;
                    Boolean enabled = isNone(stage.enabled()) ? null : parameters.bool(stage.enabled());
                    if (Boolean.FALSE.equals(enabled)) continue;
                    int iterations = clamp(resolveInteger(parameters, stage.iterationsBinding(), stage.iterations()));
                    if (iterations == 0) continue;
                    int x = 1, y = 1, z = 1;
                    if (stage.source().equals("DirectSet")) {
                        x = element(parameters, stage, 0);
                        if (!stage.dispatch().equals("OneD")) y = element(parameters, stage, 1);
                        if (stage.dispatch().equals("ThreeD") || stage.dispatch().equals("Custom")) z = element(parameters, stage, 2);
                        if (Math.min(x, Math.min(y, z)) <= 0) continue;
                    }
                    consumer.accept(new Invocation(index, iterations, loopIndex, count, x, y, z, first, stage.access(),
                            stage.source(), stage.dataInterface(), stage.dispatch(), stage.directElementType(), stage.indirect(),
                            stage.stateComponent(), stage.stateMinimum(), stage.stateMaximum()));
                    first = false;
                }
            }
        }
    }

    private static Stage stage(JsonObject value, List<NiagaraDefinition.Attribute> attributes) {
        String source = stageEnum(value, "IterationSourceType", "Particles", "DataInterface", "DirectSet");
        String dispatch = stageEnum(value, "GpuDispatchType", "OneD", "TwoD", "ThreeD", "Custom");
        boolean writes = flag(value, "bWritesParticles"), partial = flag(value, "bPartialParticleUpdate");
        ParticleAccess access = !writes ? ParticleAccess.READ_ONLY : partial ? ParticleAccess.PARTIAL : ParticleAccess.FULL;
        JsonObject element = value.getAsJsonObject("ElementCount");
        int[] elements = {number(element, "X", 0), number(element, "Y", 0), number(element, "Z", 0)};
        String[] bindings = {text(value, "ElementCountXBinding", "None"), text(value, "ElementCountYBinding", "None"),
                text(value, "ElementCountZBinding", "None")};
        int stateComponent = -1;
        if (flag(value, "bParticleIterationStateEnabled")) {
            String name = text(value, "ParticleIterationStateBinding", "None");
            for (var attribute : attributes)
                if (SourceNames.equal(attribute.name(), name) && attribute.type() == 1 && attribute.count() == 1
                        && SourceNames.equal(attribute.sourceType(), "/Script/Niagara.NiagaraInt32")) {
                    stateComponent = attribute.component();
                    break;
                }
        }
        var range = value.get("ParticleIterationStateRange");
        int minimum = 0, maximum = 0;
        if (range instanceof JsonObject point) {
            minimum = number(point, "X", 0);
            maximum = number(point, "Y", 0);
        } else if (range instanceof JsonArray pair && pair.size() == 2) {
            minimum = pair.get(0).getAsInt();
            maximum = pair.get(1).getAsInt();
        } else if (range != null && !range.isJsonNull()) {
            throw new IllegalArgumentException("Invalid Niagara particle iteration state range");
        }
        return new Stage(text(value, "SimulationStageName", "None"), text(value, "EnabledBinding", "None"),
                stageEnum(value, "ExecuteBehavior", "Always", "OnSimulationReset", "NotOnSimulationReset"),
                source, text(value, "IterationDataInterface", "None"), dispatch,
                stageEnum(value, "GpuDirectDispatchElementType", "NumThreads", "NumThreadsNoClipping", "NumGroups"),
                flag(value, "bGpuIndirectDispatch"),
                text(value, "NumIterationsBinding", "None"), number(value, "NumIterations", 1), elements, bindings,
                access, stateComponent, minimum, maximum);
    }

    private record LoopBuild(List<Loop> loops, boolean valid) { }
    private static LoopBuild editorLoops(JsonArray source, List<Stage> stages) {
        int[] membership = new int[stages.size()];
        Arrays.fill(membership, -1);
        boolean valid = true;
        for (int loop = 0; loop < source.size(); loop++) {
            JsonObject value = source.get(loop).getAsJsonObject();
            String startName = text(value, "StageNameStart", "None"), endName = text(value, "StageNameEnd", "None");
            if (!bool(value, "bEnabled", true) || isNone(startName) || isNone(endName)) continue;
            int start = find(stages, startName), end = find(stages, endName);
            if (start < 0 || end < start) { valid = false; break; }
            for (int i = start; i <= end; i++) {
                if (membership[i] != -1) valid = false;
                membership[i] = loop;
            }
        }
        if (!valid) Arrays.fill(membership, -1);
        List<Loop> loops = new ArrayList<>();
        int currentLoop = -1, start = 0;
        for (int i = 1; i < stages.size(); i++) {
            if (membership[i] == currentLoop) continue;
            loops.add(loop(source, currentLoop, start, i - 1));
            currentLoop = membership[i];
            start = i;
        }
        loops.add(loop(source, currentLoop, start, stages.size() - 1));
        return new LoopBuild(List.copyOf(loops), valid);
    }

    private static Loop loop(JsonArray source, int index, int start, int end) {
        if (index < 0) return new Loop("None", 1, start, end);
        JsonObject data = source.get(index).getAsJsonObject();
        return new Loop(text(data, "NumLoopsBindingName", "None"), number(data, "NumLoops", 1), start, end);
    }

    private static List<Loop> savedLoops(JsonArray source, int stageCount) {
        if (source == null) throw new IllegalArgumentException("Missing cooked Niagara GPU stage loops");
        List<Loop> loops = new ArrayList<>();
        for (var item : source) {
            JsonObject data = item.getAsJsonObject();
            int start = number(data, "StartStageIndex", 0), end = number(data, "EndStageIndex", 0);
            if (start < 0 || end < start || end >= stageCount) throw new IllegalArgumentException("Invalid Niagara GPU stage loop");
            loops.add(new Loop(text(data, "NumLoopsBinding", "None"), number(data, "NumLoops", 1), start, end));
        }
        return List.copyOf(loops);
    }

    private static int find(List<Stage> stages, String name) {
        for (int i = 0; i < stages.size(); i++) if (SourceNames.equal(stages.get(i).name(), name)) return i;
        return -1;
    }
    private static boolean shouldRun(String behavior, boolean reset) {
        return switch (behavior) {
            case "Always" -> true;
            case "OnSimulationReset" -> reset;
            case "NotOnSimulationReset" -> !reset;
            default -> throw new IllegalArgumentException("Unknown Niagara GPU stage behavior " + behavior);
        };
    }
    private static int element(Parameters parameters, Stage stage, int index) {
        return isNone(stage.elementBindings()[index]) ? stage.elements()[index]
                : resolveInteger(parameters, stage.elementBindings()[index], 0);
    }
    private static int resolveInteger(Parameters parameters, String name, int fallback) {
        Integer value = isNone(name) ? null : parameters.integer(name);
        return value == null ? fallback : value;
    }
    private static int clamp(int value) { return Math.clamp(value, 0, 0xffff); }
    private static boolean isNone(String value) { return value == null || value.isEmpty() || SourceNames.equal(value, "None"); }
    private static String suffix(String value) { int index = value.lastIndexOf("::"); return index < 0 ? value : value.substring(index + 2); }
    private static String stageEnum(JsonObject stage, String field, String... names) {
        String value = suffix(text(stage, field, names[0]));
        for (int i = 0; i < names.length; i++) {
            if (value.equals(names[i]) || value.equals(Integer.toString(i))) return names[i];
        }
        throw new IllegalArgumentException("Unknown Niagara GPU stage " + field + ": " + value);
    }
    private static String text(JsonObject value, String name, String fallback) {
        return value != null && value.has(name) && !value.get(name).isJsonNull() ? value.get(name).getAsString() : fallback;
    }
    private static int number(JsonObject value, String name, int fallback) {
        return value != null && value.has(name) && !value.get(name).isJsonNull() ? value.get(name).getAsInt() : fallback;
    }
    private static boolean flag(JsonObject value, String name) { return bool(value, name, false); }
    private static boolean bool(JsonObject value, String name, boolean fallback) {
        return value != null && value.has(name) && !value.get(name).isJsonNull() ? value.get(name).getAsBoolean() : fallback;
    }
}
