package com.kltyton.lumenfx.client.render.niagara.gpu.channel;

import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelGpuSnapshot;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelWorlds;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelWorlds.Lease;
import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition.Channel;
import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition.Address;
import com.mojang.logging.LogUtils;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

/** Shares GPU channel columns across systems and rotates them at the render frame boundary. */
public final class NiagaraGpuDataChannelWorlds {
    private static final class Publication implements AutoCloseable {
        @Nullable NiagaraGpuDataChannelColumns current, previous;
        @Nullable NiagaraDataChannelGpuSnapshot cpu;
        @Nullable Lease lease;
        int allocation, pendingAllocation;
        boolean warnedAboutSameStageAccess;

        void begin(NiagaraGpuPrograms programs, Channel schema, NiagaraDataChannelWorlds.GpuInput incoming, boolean retain) {
            if (retain && !incoming.cpuUpdated() && allocation == 0 && pendingAllocation == 0) return;
            var source = retain && cpu != null && !incoming.cpuUpdated() ? cpu : incoming.snapshot();
            int nextAllocation = pendingAllocation;
            int capacity = Math.addExact(source.rows(), nextAllocation);
            var replacement = capacity == 0 ? null : new NiagaraGpuDataChannelColumns(programs, source, capacity, schema.path());
            if (previous != null) previous.close();
            previous = schema.keepPrevious() ? current : null;
            if (!schema.keepPrevious() && current != null) current.close();
            current = replacement;
            cpu = retain ? source : null;
            allocation = nextAllocation;
            pendingAllocation = 0;
        }

        @Override public void close() {
            try { if (previous != null) previous.close(); }
            finally {
                try { if (current != null) current.close(); }
                finally { if (lease != null) lease.close(); }
            }
            previous = current = null;
            cpu = null;
            lease = null;
        }
    }

    private static final class World {
        final Map<Address, Publication> publications = new HashMap<>();
    }

    private static final Map<ClientLevel, World> WORLDS = new IdentityHashMap<>();
    private NiagaraGpuDataChannelWorlds() { }

    public static void beginFrame(ClientLevel level, NiagaraGpuPrograms programs, boolean retain, boolean autoUpload) {
        var inputs = NiagaraDataChannelWorlds.takeGpuInputs(level, autoUpload);
        if (inputs.isEmpty() && !WORLDS.containsKey(level)) return;
        var world = WORLDS.computeIfAbsent(level, ignored -> new World());
        for (var entry : inputs.entrySet()) {
            var address = entry.getKey();
            var publication = world.publications.computeIfAbsent(address, ignored -> new Publication());
            publication.begin(programs, address.channel(), entry.getValue(), retain);
            if (publication.current != null || publication.previous != null) {
                if (publication.lease == null) publication.lease = NiagaraDataChannelWorlds.retain(level, address);
            } else if (publication.lease != null) {
                publication.lease.close();
                publication.lease = null;
            }
        }
        var iterator = world.publications.entrySet().iterator();
        while (iterator.hasNext()) {
            var publication = iterator.next();
            if (inputs.containsKey(publication.getKey())) continue;
            publication.getValue().close();
            iterator.remove();
        }
    }

    private static @Nullable Publication publication(@Nullable ClientLevel level, @Nullable Address address) {
        if (level == null || address == null) return null;
        var world = WORLDS.get(level);
        return world == null ? null : world.publications.get(address);
    }

    /** Owns the access direction of each current-frame publication until the group barrier. */
    public static final class StageAccess {
        private final Map<Publication, Boolean> currentAccess = new IdentityHashMap<>();

        @Nullable NiagaraGpuDataChannelColumns acquire(@Nullable ClientLevel level, @Nullable Address address,
                                                       boolean current, boolean write) {
            var publication = publication(level, address);
            if (publication == null) return null;
            if (!current) return publication.previous;
            if (publication.current == null) return null;
            var activeWriter = currentAccess.putIfAbsent(publication, write);
            if (activeWriter != null && activeWriter != write) {
                if (!publication.warnedAboutSameStageAccess) {
                    publication.warnedAboutSameStageAccess = true;
                    if (address != null) LogUtils.getLogger().warn(
                            "Niagara data channel {} cannot {} its current frame in this GPU group",
                            address.channel().path(), write ? "write" : "read");
                }
                return null;
            }
            return publication.current;
        }
    }

    static boolean hasFrame(@Nullable ClientLevel level, @Nullable Address address) {
        return publication(level, address) != null;
    }

    static void reserve(ClientLevel level, Address address, int count) {
        var world = WORLDS.computeIfAbsent(level, ignored -> new World());
        var publication = world.publications.computeIfAbsent(address, ignored -> new Publication());
        publication.pendingAllocation = Math.addExact(publication.pendingAllocation, count);
    }

    public static void clear(ClientLevel level) {
        var world = WORLDS.remove(level);
        if (world != null) for (var channel : world.publications.values()) channel.close();
    }

    public static void clear() {
        for (var world : WORLDS.values()) for (var channel : world.publications.values()) channel.close();
        WORLDS.clear();
    }
}
