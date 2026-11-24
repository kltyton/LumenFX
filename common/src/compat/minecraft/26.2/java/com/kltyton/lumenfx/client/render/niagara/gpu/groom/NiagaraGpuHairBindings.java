package com.kltyton.lumenfx.client.render.niagara.gpu.groom;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuComputeStage;
import com.kltyton.lumenfx.client.render.niagara.gpu.groom.NiagaraGpuHairBuffers.Entry;
import com.kltyton.lumenfx.client.render.niagara.gpu.groom.NiagaraGpuHairBuffers.Resource;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.groom.HairStrandsFrame;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.mojang.blaze3d.systems.CommandEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public final class NiagaraGpuHairBindings {
    private static final List<String> UNIFORMS = List.of("WorldTransform", "WorldInverse", "WorldRotation",
            "BoneTransform", "BoneInverse", "BoneRotation", "NumStrands", "StrandSize", "InterpolationMode",
            "RestRootOffset", "DeformedRootOffset", "RestPositionOffset", "SampleCount", "LocalSimulation", "RBFLocalSpace",
            "BoneLinearVelocity", "BoneAngularVelocity", "BoneLinearAcceleration", "BoneAngularAcceleration",
            "ResetSimulation", "RestUpdate", "BoundingBoxOffsets");
    private static final String MAX_DELAY = "r.HairStrands.SimulationMaxDelay";
    private static final String REST_UPDATE = "r.HairStrands.SimulationRestUpdate";

    private record Interface(String compileName, String defaultId, String symbol) {
        boolean used(NiagaraGpuComputeStage stage) {
            for (String field : UNIFORMS) if (stage.hasParameter(symbol + "_" + field)) return true;
            for (Resource resource : Resource.values()) if (stage.hasParameter(symbol + "_" + resource.symbol)) return true;
            return false;
        }
    }
    private record Bound(Entry entry, HairStrandsFrame frame) { }
    private record Buffer(Interface source, Resource resource) { }

    private final Map<String, Interface> interfaces = new HashMap<>();
    private final Map<String, Buffer> resources = new HashMap<>();
    private final Map<Interface, Bound> active = new HashMap<>();
    private final Set<Bound> stage = new LinkedHashSet<>();
    private final Set<Bound> dispatched = new LinkedHashSet<>();
    private int delay;
    private boolean restUpdate;

    public NiagaraGpuHairBindings(JsonObject program) {
        for (var item : program.getAsJsonArray("interfaces")) {
            var binding = item.getAsJsonObject();
            if (!binding.has("hair_strands") || !binding.has("shader_symbol")) continue;
            String symbol = binding.get("shader_symbol").getAsString();
            var source = new Interface(SourceNames.canonical(binding.get("compile_name").getAsString()),
                    binding.get("interface_id").getAsString(), symbol);
            interfaces.put(symbol, source);
            for (Resource resource : Resource.values()) resources.put(symbol + "_" + resource.symbol, new Buffer(source, resource));
        }
    }

    public void beginTick(@Nullable NiagaraGpuHairBuffers buffers, Map<String, String> aliases,
                          Map<String, HairStrandsFrame> frames, NiagaraPlatformEnvironment environment) {
        clear();
        if (interfaces.isEmpty()) return;
        delay = value(environment, MAX_DELAY, 4);
        restUpdate = value(environment, REST_UPDATE, 0) != 0;
        var owner = Objects.requireNonNull(buffers);
        for (var source : interfaces.values()) {
            String id = aliases.getOrDefault(source.compileName(), source.defaultId());
            var frame = Objects.requireNonNull(frames.get(id), "Missing Niagara HairStrands step: " + id);
            active.put(source, new Bound(owner.prepare(id, frame), frame));
        }
    }

    public void beginStage(CommandEncoder encoder, NiagaraGpuComputeStage shader) {
        stage.clear();
        for (var item : active.entrySet()) {
            if (item.getKey().used(shader) && stage.add(item.getValue())) {
                var value = item.getValue();
                value.entry().beginStep(encoder, value.frame());
            }
        }
    }

    public void bind(CommandEncoder encoder, ShaderProgram shader, ComputeBindings.Builder target) {
        for (var bound : stage) {
            bound.entry().beginDispatch(delay);
            dispatched.add(bound);
        }
        for (var resource : shader.bindings()) {
            if (!(resource instanceof ShaderBinding.StorageBuffer storage)) continue;
            var descriptor = resources.get(storage.name());
            if (descriptor == null) continue;
            var bound = active.get(descriptor.source());
            var slice = bound.entry().buffer(encoder, descriptor.resource(), bound.frame());
            target.storage(storage.name(), slice, storage.access());
        }
    }

    public @Nullable byte[] value(String qualifiedName) {
        String name = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
        int split = name.lastIndexOf('_');
        if (split < 0) return null;
        var source = interfaces.get(name.substring(0, split));
        if (source == null) return null;
        var bound = active.get(source);
        return switch (name.substring(split + 1)) {
            case "ResetSimulation" -> flag(bound.entry().resetActive());
            case "RestUpdate" -> flag(bound.frame().group() != null
                    && (restUpdate || bound.entry().resetActive() && bound.frame().group().skinningTransfer()));
            case "BoundingBoxOffsets" -> bound.entry().boundsOffsets();
            default -> bound.frame().value(name.substring(split + 1));
        };
    }

    public void postSimulate(CommandEncoder encoder, boolean dataToRender) {
        if (!dataToRender) return;
        for (var bound : dispatched) bound.entry().publish(encoder, bound.frame());
    }

    public void clear() {
        active.clear();
        stage.clear();
        dispatched.clear();
    }

    private static int value(NiagaraPlatformEnvironment environment, String name, int fallback) {
        var value = environment.console(name);
        return value == null ? fallback : value.bits();
    }

    private static byte[] flag(boolean value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value ? 1 : 0).array();
    }
}
