package com.kltyton.lumenfx.client.simulation.niagara.cache;

import com.kltyton.lumenfx.client.simulation.niagara.NiagaraInterfaces;
import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelState;
import com.kltyton.lumenfx.effect.niagara.memory.NiagaraMemoryBufferDefinition.SyncMode;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraInterfaces.ArraySnapshot;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraInterfaces.CounterSnapshot;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraInterfaces.MutableGpuInputs;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.NiagaraDataInterfaceInstances.Usage;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraDataInterfaceObject;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.ArrayFrame;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.Key;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.Target2D;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Sample;
import com.kltyton.lumenfx.effect.niagara.mesh.NiagaraDynamicMeshFrame;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheVolumeFrame;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Restores the CPU and GPU copies of each live interface independently. */
public final class NiagaraCacheInterfacePlayback {
    public record MemorySnapshot(ArrayFrame words, long revision, long liveRevision, SyncMode sync) { }
    private static final AtomicLong NEXT_CACHE_REVISION = new AtomicLong(-1);
    private record Binding(String identity, boolean usedGpu) { }
    private final List<NiagaraCacheInterfaceData> saved;
    private final Map<Key, Binding> bindings = new HashMap<>();
    private MutableGpuInputs gpu = new MutableGpuInputs(Map.of(), Map.of());
    private Map<String, Target2D> targets = Map.of();
    private Map<String, NiagaraCacheVolumeFrame> volumes = Map.of();
    private Map<String, MemorySnapshot> memory = Map.of();
    private Map<String, NiagaraDynamicMeshFrame> meshes = Map.of();
    private int lastFrame = -1;
    private int lastTargetFrame = -1;
    private Map<String, Long> arrayRevisions = Map.of();
    private Map<String, Long> counterRevisions = Map.of();

    public NiagaraCacheInterfacePlayback(List<NiagaraCacheInterfaceData> saved) {
        this.saved = List.copyOf(saved);
    }

    public void clearBindings() {
        bindings.clear();
        lastFrame = -1;
        lastTargetFrame = -1;
        gpu = new MutableGpuInputs(Map.of(), Map.of());
        targets = Map.of();
        volumes = Map.of();
        memory = Map.of();
        meshes = Map.of();
        arrayRevisions = Map.of();
        counterRevisions = Map.of();
    }

    public void bind(NiagaraDataInterfaceObject object, Usage usage) {
        var mutable = object.mutableDefinition();
        String className = mutable == null ? object.sourceClassName() : mutable.type();
        if (className == null) return;
        String type = "/Script/Niagara." + className;
        var binding = new Binding(object.identity(), usage.gpu());
        for (String name : usage.parameterNames()) {
            if (!name.isEmpty()) bindings.putIfAbsent(new Key(name, type), binding);
        }
        if (mutable != null && !mutable.parameterName().isEmpty())
            bindings.putIfAbsent(new Key(mutable.parameterName(), type), binding);
    }

    public void read(NiagaraInterfaces interfaces, Sample sample) {
        int frame = sample.index();
        int targetFrame = sample.fraction() >= .5f && sample.first() != sample.second() ? frame + 1 : frame;
        if (targetFrame != lastTargetFrame) {
            var images = new HashMap<String, Target2D>();
            var volumeImages = new HashMap<String, NiagaraCacheVolumeFrame>();
            for (var storage : saved) {
                var binding = bindings.get(storage.key());
                if (binding == null) continue;
                if (storage.frames() instanceof NiagaraCacheInterfaceData.Targets2D values) {
                    var target = values.values().get(targetFrame);
                    if (target.format() < 0) throw new IllegalArgumentException("Niagara cache has no image for " + storage.key().name() + " at frame " + targetFrame);
                    images.put(binding.identity(), target);
                } else if (storage.frames() instanceof NiagaraCacheInterfaceData.VolumeTargets values) {
                    var target = values.values().get(targetFrame);
                    if (target.format() >= 0) volumeImages.put(binding.identity(), target);
                }
            }
            targets = Map.copyOf(images);
            volumes = Map.copyOf(volumeImages);
            lastTargetFrame = targetFrame;
        }
        if (frame == lastFrame) return;
        long nextRevision = NEXT_CACHE_REVISION.getAndDecrement();
        var arrays = new HashMap<String, ArraySnapshot>();
        var counters = new HashMap<String, CounterSnapshot>();
        var liveArrays = new HashMap<String, Long>();
        var liveCounters = new HashMap<String, Long>();
        var buffers = new HashMap<String, MemorySnapshot>();
        var dynamic = new HashMap<String, NiagaraDynamicMeshFrame>();
        for (var storage : saved) {
            var binding = bindings.get(storage.key());
            if (binding == null) continue;
            String id = binding.identity();
            switch (storage.frames()) {
                case NiagaraCacheInterfaceData.Arrays values -> {
                    var cpu = values.cpu().get(frame);
                    var render = values.gpu().get(frame);
                    if (storage.key().type().equals("/script/niagara.niagaradatainterfacememorybuffer")) {
                        var state = interfaces.memoryBuffer(id);
                        if (cpu.present()) state.readCache(cpu.words());
                        if (binding.usedGpu() && render.present()) buffers.put(id,
                                new MemorySnapshot(render, nextRevision, state.revision(), state.definition().sync()));
                    } else {
                        var state = interfaces.array(id);
                        if (cpu.present()) state.readCache(cpu.words());
                        if (binding.usedGpu() && render.present()) {
                            arrays.put(id, state.cacheGpuSnapshot(render.words(), nextRevision));
                            liveArrays.put(id, state.version());
                        }
                    }
                }
                case NiagaraCacheInterfaceData.Counters values -> {
                    var state = interfaces.counter(id);
                    state.readCache(values.cpu(frame));
                    if (binding.usedGpu()) {
                        counters.put(id, new CounterSnapshot(values.gpu(frame), nextRevision));
                        liveCounters.put(id, state.revision());
                    }
                }
                case NiagaraCacheInterfaceData.Targets2D ignored -> { }
                case NiagaraCacheInterfaceData.VolumeTargets ignored -> { }
                case NiagaraCacheInterfaceData.Channels ignored -> { }
                case NiagaraCacheInterfaceData.DynamicMeshes values -> {
                    var pair = values.values().get(frame);
                    var render = pair.gpu() == null ? pair.cpu() : pair.gpu();
                    var state = interfaces.dynamicMesh(id).readCache(render, pair.gpu() != null);
                    if (binding.usedGpu()) dynamic.put(id, state);
                }
            }
        }
        gpu = new MutableGpuInputs(arrays, counters);
        memory = Map.copyOf(buffers);
        meshes = Map.copyOf(dynamic);
        arrayRevisions = Map.copyOf(liveArrays);
        counterRevisions = Map.copyOf(liveCounters);
        lastFrame = frame;
    }

    public MutableGpuInputs gpu() { return gpu; }
    public long arrayLiveRevision(String id) { return arrayRevisions.get(id); }
    public long counterLiveRevision(String id) { return counterRevisions.get(id); }
    public Map<String, Target2D> targets() { return targets; }
    public Map<String, NiagaraCacheVolumeFrame> volumes() { return volumes; }
    public Map<String, MemorySnapshot> memory() { return memory; }
    public Map<String, NiagaraDynamicMeshFrame> meshes() { return meshes; }

    public void publishChannels(Function<String, NiagaraDataChannelState> channels, @Nullable ClientLevel world, Sample sample,
                                Pose owner, Vec3 tile) {
        for (var storage : saved) {
            var binding = bindings.get(storage.key());
            if (binding == null || !(storage.frames() instanceof NiagaraCacheInterfaceData.Channels data)) continue;
            NiagaraCacheChannelPlayback.publish(world, channels.apply(binding.identity()).definition(), data.values().get(sample.index()),
                    sample.first().transform(), owner, tile);
        }
    }
}
