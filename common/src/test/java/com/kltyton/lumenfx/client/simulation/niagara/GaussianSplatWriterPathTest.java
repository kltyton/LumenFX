package com.kltyton.lumenfx.client.simulation.niagara;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.niagara.gpu.splat.NiagaraGpuGaussianSplatWriterBindings;
import com.kltyton.lumenfx.client.render.niagara.gpu.splat.NiagaraGpuGaussianSplatWriters;
import com.kltyton.lumenfx.client.resource.niagara.ScriptParameterStoreJson;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraGaussianSplatShaders;
import com.kltyton.lumenfx.client.simulation.niagara.gpu.NiagaraGpuParameterValues;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.splat.NiagaraGaussianSplatWriter;
import com.kltyton.lumenfx.effect.EffectDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.VectorProgram;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.parameter.store.ScriptParameterStoreDefinition;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.effect.render.SpriteCoverage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

final class GaussianSplatWriterPathTest {
    private static final String WRITER_CLASS = NiagaraGaussianSplatWriter.TYPE;
    private static final String WRITER_SOURCE_TYPE = "/Script/Niagara." + WRITER_CLASS;

    @Test
    void detectsGaussianSplatShaderRequirementForWriterOnlyEffectsWithoutReaderOrSplatRenderer() {
        EffectDefinition storeBased = niagaraEffect(
                new NiagaraDefinition.GpuSimulation(
                        Identifier.parse("lumenfx:writer_only_store"),
                        "writer_only_pipeline",
                        new JsonObject(),
                        writerExecutionStore(64),
                        List.of()),
                emptyProgram());
        assertTrue(PreparedNiagaraGaussianSplatShaders.isRequired(storeBased));

        JsonObject metadataWithInterfaces = new JsonObject();
        JsonArray interfaces = new JsonArray();
        JsonObject iface = new JsonObject();
        iface.addProperty("class", WRITER_CLASS);
        iface.addProperty("compile_name", "Emitter.SplatWriter");
        iface.addProperty("interface_id", "writer_0");
        interfaces.add(iface);
        metadataWithInterfaces.add("interfaces", interfaces);
        EffectDefinition camelCaseInterface = niagaraEffect(
                new NiagaraDefinition.GpuSimulation(
                        Identifier.parse("lumenfx:writer_only_iface"),
                        "writer_only_pipeline",
                        metadataWithInterfaces,
                        null,
                        List.of()),
                emptyProgram());
        assertTrue(PreparedNiagaraGaussianSplatShaders.isRequired(camelCaseInterface));

        JsonObject metadataWithParamInfo = new JsonObject();
        JsonObject paramMeta = new JsonObject();
        JsonArray paramInfos = new JsonArray();
        JsonObject paramInfo = new JsonObject();
        paramInfo.addProperty("DIClassName", WRITER_CLASS);
        paramInfos.add(paramInfo);
        paramMeta.add("DataInterfaceParamInfo", paramInfos);
        metadataWithParamInfo.add("parameter_metadata", paramMeta);
        EffectDefinition paramMetadataOnly = niagaraEffect(
                new NiagaraDefinition.GpuSimulation(
                        Identifier.parse("lumenfx:writer_only_meta"),
                        "writer_only_pipeline",
                        metadataWithParamInfo,
                        null,
                        List.of()),
                emptyProgram());
        assertTrue(PreparedNiagaraGaussianSplatShaders.isRequired(paramMetadataOnly));

        JsonObject writerExternalData = new JsonObject();
        writerExternalData.add("gaussian_splat_writer", writerConfigJson(64));
        VectorProgram cpuWriterProgram = new VectorProgram(
                0, 0, 1, 0, 0, 32, false, 0, List.of(), new byte[0], new int[0], List.of(), List.of(),
                List.of(new VectorProgram.External(
                        "GetNumSplats", 0, 1, List.of(), null, false, false, false,
                        "gaussian_splat_writer", "writer_0", 0, true, writerExternalData, "")),
                List.of());
        EffectDefinition cpuExternalOnly = niagaraEffect(
                new NiagaraDefinition.CpuSimulation(cpuWriterProgram, emptyProgram()),
                emptyProgram());
        assertTrue(PreparedNiagaraGaussianSplatShaders.isRequired(cpuExternalOnly));

        EffectDefinition plainCpu = niagaraEffect(
                new NiagaraDefinition.CpuSimulation(emptyProgram(), emptyProgram()),
                emptyProgram());
        assertFalse(PreparedNiagaraGaussianSplatShaders.isRequired(plainCpu));
    }

    @Test
    void writerOperatesWithExplicitCountAndNoInputSplatAsset() {
        var writer = new NiagaraGaussianSplatWriter(
                NiagaraGaussianSplatWriter.Definition.read(writerConfigJson(64)),
                Map.of(),
                NiagaraPlatformEnvironment.WINDOWS_DEFAULT,
                1L << 30);

        var initial = writer.publish();
        assertEquals(64, initial.config().count());
        assertEquals(0, initial.config().degree());

        int[] numSplats = new int[1];
        writer.execute(NiagaraGaussianSplatWriter.Function.GetNumSplats, 1, (word, lane) -> 0,
                (word, lane, value) -> numSplats[lane] = value);
        assertEquals(64, numSplats[0]);

        int[] shDegree = new int[1];
        writer.execute(NiagaraGaussianSplatWriter.Function.GetSHDegree, 1, (word, lane) -> 0,
                (word, lane, value) -> shDegree[lane] = value);
        assertEquals(0, shDegree[0]);

        writer.execute(NiagaraGaussianSplatWriter.Function.Clear, 1, (word, lane) -> -1, (word, lane, value) -> { });
        assertEquals(initial.clearEpoch() + 1, writer.publish().clearEpoch());
    }

    @Test
    void gpuParameterValuesAndWriterBindingsTrackWriterOnlyProgramWithoutReaderOrRenderer() {
        JsonObject programJson = writerGpuProgramJson(64, true);
        var values = new NiagaraGpuParameterValues(programJson);
        Map<String, String> selections = values.dataInterfaceSelections(null);
        assertEquals(1, selections.size());
        assertTrue(selections.containsValue("writer_0"));

        var writer = new NiagaraGaussianSplatWriter(
                NiagaraGaussianSplatWriter.Definition.read(writerConfigJson(64)),
                Map.of(),
                NiagaraPlatformEnvironment.WINDOWS_DEFAULT,
                1L << 30);
        var bindings = new NiagaraGpuGaussianSplatWriterBindings(programJson);
        bindings.beginTick(new NiagaraGpuGaussianSplatWriters(null), selections, Map.of(), Map.of("writer_0", writer.publish()));

        var iteration = bindings.iteration(SourceNames.canonical("Emitter.SplatWriter"));
        assertNotNull(iteration);
        assertNotNull(iteration.elements());
        assertEquals(64, iteration.elements().x());
        assertEquals(64, Objects.requireNonNull(Objects.requireNonNull(bindings.iteration("Emitter.SplatWriter")).elements()).x());

        byte[] numSplatsBytes = bindings.value("SplatWriter_0_NumSplats");
        assertNotNull(numSplatsBytes);
        assertEquals(64, ByteBuffer.wrap(numSplatsBytes).order(ByteOrder.LITTLE_ENDIAN).getInt());

        JsonObject camelCaseProgram = writerGpuProgramJson(64, false);
        var camelCaseSelections = new NiagaraGpuParameterValues(camelCaseProgram).dataInterfaceSelections(null);
        assertEquals(1, camelCaseSelections.size());
        assertTrue(camelCaseSelections.containsValue("writer_0"));
        var camelCaseBindings = new NiagaraGpuGaussianSplatWriterBindings(camelCaseProgram);
        camelCaseBindings.beginTick(new NiagaraGpuGaussianSplatWriters(null), camelCaseSelections, Map.of(), Map.of("writer_0", writer.publish()));
        assertEquals(64, Objects.requireNonNull(Objects.requireNonNull(camelCaseBindings.iteration("Emitter.SplatWriter")).elements()).x());
    }

    private static EffectDefinition niagaraEffect(NiagaraDefinition.Simulation simulation, VectorProgram systemProgram) {
        var emitter = new NiagaraDefinition.Emitter(
                "emitter", "Emitter", 0, false, simulation,
                List.of(), List.of(), new SpriteCoverage(0, 0f, false),
                1, 1, false, "", true, 0, List.of(), false, 16);
        var systemLayout = List.of(
                new NiagaraDefinition.Variable("System.ExecutionState", 0, 0, 0, 1, 0, 0, NiagaraDefinition.EXECUTION_STATE_TYPE),
                new NiagaraDefinition.Variable("Emitter.ExecutionState", 0, 0, 1, 1, 0, 0, NiagaraDefinition.EXECUTION_STATE_TYPE));
        var niagara = new NiagaraDefinition(
                systemProgram, systemProgram, systemLayout,
                List.of(), List.of(), List.of(), List.of(), 0, false, List.of(emitter));
        return new EffectDefinition(Map.of(), List.of(), niagara);
    }

    private static VectorProgram emptyProgram() {
        return new VectorProgram(
                0, 0, 1, 0, 0, 32, false, 0,
                List.of(), new byte[0], new int[0], List.of(), List.of(), List.of(), List.of());
    }

    private static JsonObject writerConfigJson(int count) {
        JsonObject writer = new JsonObject();
        writer.addProperty("count", count);
        writer.addProperty("sh_degree", 0);
        writer.addProperty("splat_scale", 1.0f);
        writer.addProperty("opacity_scale", 1.0f);
        writer.addProperty("invert_expand_gamut", true);
        writer.addProperty("local_space", false);
        return writer;
    }

    private static JsonObject writerKeyJson() {
        JsonObject key = new JsonObject();
        key.addProperty("name", "Emitter.SplatWriter");
        key.addProperty("source_type", WRITER_SOURCE_TYPE);
        key.addProperty("underlying", 1);
        key.addProperty("flags", 0);
        key.addProperty("storage", "interface");
        return key;
    }

    private static JsonObject writerExecutionStoreJson(int count) {
        JsonObject store = new JsonObject();
        JsonArray variables = new JsonArray();
        JsonObject variable = writerKeyJson();
        variable.addProperty("bytes", 0);
        variable.addProperty("object_index", 0);
        variables.add(variable);
        store.add("variables", variables);
        store.add("references", new JsonArray());
        store.addProperty("data", "");
        store.addProperty("current_bytes", 0);
        store.addProperty("interpolated", false);

        JsonArray interfaceValues = new JsonArray();
        JsonObject writerValue = new JsonObject();
        writerValue.addProperty("id", "writer_0");
        writerValue.add("gaussian_splat_writer", writerConfigJson(count));
        interfaceValues.add(writerValue);
        store.add("interface_values", interfaceValues);
        store.add("object_values", new JsonArray());
        return store;
    }

    private static ScriptParameterStoreDefinition writerExecutionStore(int count) {
        return ScriptParameterStoreJson.read(writerExecutionStoreJson(count));
    }

    private static JsonObject writerGpuProgramJson(int count, boolean includeWriterData) {
        JsonObject program = new JsonObject();
        program.add("execution_store", writerExecutionStoreJson(count));

        JsonArray interfaces = new JsonArray();
        JsonObject iface = new JsonObject();
        iface.addProperty("class", WRITER_CLASS);
        iface.addProperty("compile_name", "Emitter.SplatWriter");
        iface.addProperty("parameter_name", "Emitter.SplatWriter");
        iface.addProperty("interface_id", "writer_0");
        iface.addProperty("shader_symbol", "SplatWriter_0");
        iface.add("interface_key", writerKeyJson());
        if (includeWriterData) {
            iface.add("gaussian_splat_writer", writerConfigJson(count));
        }
        interfaces.add(iface);
        program.add("interfaces", interfaces);
        program.add("parameter_bindings", new JsonArray());
        return program;
    }
}
