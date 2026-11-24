package com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.VfxEffects;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelWorlds.Buffer;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.channel.NiagaraDataChannelWorlds.World;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraPositionParameters;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition.Address;
import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition.Channel;
import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition.Islands;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** The active islands and handler systems of one spatial data channel. */
final class NiagaraDataChannelIslands {
    @FunctionalInterface
    interface Spawner { EffectHandle spawn(net.minecraft.client.multiplayer.@Nullable ClientLevel level,
                                           net.minecraft.resources.Identifier id, Vec3 position, long seed); }

    private final World world;
    private final Channel channel;
    private final Islands settings;
    private final Spawner spawner;
    private final ArrayDeque<Island> pool;
    private final ArrayList<Island> active = new ArrayList<>();

    NiagaraDataChannelIslands(World world, Channel channel) {
        this(world, channel, (lvl, id, pos, seed) -> VfxEffects.spawn(lvl, id, pos, seed));
    }

    NiagaraDataChannelIslands(World world, Channel channel, Spawner spawner) {
        this.world = world;
        this.channel = channel;
        this.spawner = spawner;
        settings = Objects.requireNonNull(channel.islands());
        pool = new ArrayDeque<>(settings.poolSize());
        for (int i = 0; i < settings.poolSize(); i++) pool.addLast(new Island());
    }

    Buffer acquire(Vec3 point) {
        for (var island : active) {
            if (settings.aligned() ? island.contains(point) : island.grow(point)) return island.data();
        }
        var island = pool.pollLast();
        if (island == null) island = new Island();
        try {
            island.acquire(point);
            active.add(island);
            return island.data();
        } catch (RuntimeException | Error failure) {
            island.release();
            pool.addLast(island);
            throw failure;
        }
    }

    void retireUnused() {
        for (int index = active.size() - 1; index >= 0; index--) {
            var island = active.get(index);
            if (island.inUse()) continue;
            island.release();
            var last = active.removeLast();
            if (last != island) active.set(index, last);
            pool.addLast(island);
        }
    }

    void close() {
        for (var island : active) island.release();
        active.clear();
        pool.clear();
    }

    private final class Island {
        private Vec3 center = Vec3.ZERO, extent = Vec3.ZERO;
        private final EffectHandle[] systems = new EffectHandle[settings.systems().size()];
        private @Nullable Buffer buffer;
        Buffer data() { return Objects.requireNonNull(buffer, "Inactive Niagara data channel island"); }

        void acquire(Vec3 point) {
            if (settings.aligned()) {
                var size = settings.maximum();
                center = new Vec3(aligned(point.x, size.x), aligned(point.y, size.y), aligned(point.z, size.z));
                extent = size.add(settings.padding());
            } else {
                center = point;
                extent = settings.initial();
            }
            var address = new Address(channel, ++world.islandSequence);
            Vec3 tile = new Vec3(NiagaraPositionParameters.origin(center.x), NiagaraPositionParameters.origin(center.y),
                    NiagaraPositionParameters.origin(center.z));
            buffer = new Buffer(address, tile, this::activateHandlers);
            world.channels.put(address, buffer);
            if (!settings.aligned()) grow(point);
            activateHandlers();
        }

        boolean contains(Vec3 point) {
            return Math.abs(point.x - center.x) <= extent.x && Math.abs(point.y - center.y) <= extent.y
                    && Math.abs(point.z - center.z) <= extent.z;
        }

        boolean grow(Vec3 point) {
            var padding = settings.padding();
            var max = maximum(center.add(extent), point.add(padding));
            var min = minimum(center.subtract(extent), point.subtract(padding));
            var limit = settings.maximum().add(padding);
            var lower = center.subtract(limit);
            var upper = center.add(limit);
            if (min.x < lower.x || min.y < lower.y || min.z < lower.z || max.x > upper.x || max.y > upper.y || max.z > upper.z)
                return false;
            var nextCenter = min.add(max).scale(.5);
            var nextExtent = max.subtract(min).scale(.5);
            if (nextCenter.equals(center) && nextExtent.equals(extent)) return true;
            center = nextCenter;
            extent = nextExtent;
            for (var handle : systems) if (handle != null && handle.isAlive()) updateHandler(handle);
            return true;
        }

        void activateHandlers() {
            for (int index = 0; index < systems.length; index++) {
                var handle = systems[index];
                if (handle != null && handle.isAlive()) continue;
                if (handle != null) world.handlerOwners.remove(handle);
                handle = spawner.spawn(world.level, settings.systems().get(index), hostPosition(center), ThreadLocalRandom.current().nextLong());
                systems[index] = handle;
                world.handlerOwners.put(handle, data().address);
                updateHandler(handle);
            }
        }

        private void updateHandler(EffectHandle handle) {
            handle.moveTo(hostPosition(center));
            handle.setNiagaraSystemFixedBounds(new NiagaraBounds(-extent.x, -extent.y, -extent.z, extent.x, extent.y, extent.z));
        }

        boolean inUse() {
            if (data().inUse()) return true;
            for (var handle : systems) if (handle != null && handle.isAlive()) return true;
            return false;
        }

        void release() {
            if (buffer != null) world.channels.remove(buffer.address);
            for (var handle : systems) if (handle != null) {
                world.handlerOwners.remove(handle);
                handle.invalidate();
            }
            Arrays.fill(systems, null);
            buffer = null;
        }
    }

    static Vec3 sourcePosition(Vec3 position) { return new Vec3(position.x * 100, position.z * 100, position.y * 100); }
    private static Vec3 hostPosition(Vec3 position) { return new Vec3(position.x / 100, position.z / 100, position.y / 100); }
    private static double aligned(double value, double extent) { return Math.floor(value / (2 * extent)) * (2 * extent) + extent; }
    private static Vec3 minimum(Vec3 a, Vec3 b) { return new Vec3(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.min(a.z, b.z)); }
    private static Vec3 maximum(Vec3 a, Vec3 b) { return new Vec3(Math.max(a.x, b.x), Math.max(a.y, b.y), Math.max(a.z, b.z)); }
}
