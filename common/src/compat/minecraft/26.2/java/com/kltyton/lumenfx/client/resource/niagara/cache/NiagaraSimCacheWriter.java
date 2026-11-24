package com.kltyton.lumenfx.client.resource.niagara.cache;

import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheDebugData;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.ArrayFrame;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.DynamicMesh;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheVolumeFrame;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Buffer;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Layout;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Range;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Storage;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.TreeMap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Writes the little-endian cache interchange including capture identities for appending. */
public final class NiagaraSimCacheWriter {
    private final DataOutputStream data;
    private NiagaraSimCacheWriter(OutputStream output) { data = new DataOutputStream(new BufferedOutputStream(output)); }

    public static void write(NiagaraSimulationCache cache, OutputStream output) throws IOException {
        var writer = new NiagaraSimCacheWriter(output);
        writer.cache(cache);
        writer.data.flush();
    }

    private void cache(NiagaraSimulationCache cache) throws IOException {
        integer(0x4353464c); integer(6);
        text(cache.sourceSystem());
        floating(cache.start()); floating(cache.duration());
        integer((cache.interpolation() ? 1 : 0) | (cache.extrapolation() ? 2 : 0)
                | cache.captureMode().ordinal() << 2 | (cache.debugData() != null ? 16 : 0));
        integer(cache.emitterLayouts().size()); integer(cache.frames().size());
        var materials = new java.util.TreeSet<String>();
        for (var storage : cache.interfaces()) if (storage.frames() instanceof NiagaraCacheInterfaceData.DynamicMeshes meshes)
            for (var pair : meshes.values()) {
                if (pair.cpu() != null) materials.addAll(pair.cpu().materials());
                if (pair.gpu() != null) materials.addAll(pair.gpu().materials());
            }
        materials.remove("");
        integer(materials.size());
        for (String material : materials) text(material);
        layout(cache.systemLayout());
        for (var emitter : cache.emitterLayouts()) layout(emitter);
        for (var frame : cache.frames()) {
            floating(frame.age()); integer(frame.tick());
            var transform = frame.transform();
            vector(transform.position());
            decimal(transform.qx()); decimal(transform.qy()); decimal(transform.qz()); decimal(transform.qw());
            vector(transform.scale()); vector(transform.tile());
            bounds(frame.bounds());
            buffer(frame.system(), cache.systemLayout());
            for (int index = 0; index < frame.emitters().size(); index++) {
                var emitter = frame.emitters().get(index);
                bounds(emitter.bounds());
                integer(emitter.totalSpawned());
                buffer(emitter.particles(), cache.emitterLayouts().get(index));
            }
        }
        integer(cache.interfaces().size());
        for (var storage : cache.interfaces()) interfaceData(storage);
        if (cache.debugData() != null) debugData(cache.debugData());
    }

    private void debugData(NiagaraCacheDebugData debug) throws IOException {
        var state = debug.systemState();
        integer((state.runSpawn() ? 1 : 0) | (state.runUpdate() ? 2 : 0) | (state.ignoreState() ? 4 : 0)
                | (state.resampleDuration() ? 8 : 0) | (state.delayEnabled() ? 16 : 0)
                | (state.firstDelayOnly() ? 32 : 0) | (state.resampleDelay() ? 64 : 0));
        integer(state.inactiveResponse().ordinal()); integer(state.loopBehavior().ordinal()); integer(state.loopCount());
        floating(state.duration().min()); floating(state.duration().max()); floating(state.delay().min()); floating(state.delay().max());
        for (var frame : debug.frames()) {
            integer(frame.stores().size());
            for (var store : new TreeMap<>(frame.stores()).entrySet()) {
                text(store.getKey());
                integer(store.getValue().size());
                for (var parameter : store.getValue()) {
                    var key = parameter.key();
                    text(key.name()); text(key.sourceType());
                    integer(key.underlyingType()); integer(key.flags()); integer(key.storage().ordinal());
                    if (key.storage() == Storage.NUMERIC) {
                        byte[] bytes = parameter.numeric();
                        integer(bytes.length); data.write(bytes);
                    } else {
                        var reference = parameter.reference();
                        integer(reference == null ? 0 : 1);
                        if (reference != null) {
                            text(reference.identity()); text(reference.type());
                        }
                    }
                }
            }
        }
    }

    private void layout(Layout layout) throws IOException {
        text(layout.name());
        integer((layout.localSpace() ? 1 : 0) | (layout.gpu() ? 2 : 0));
        integer(layout.floatCount()); integer(layout.integerCount()); integer(layout.halfCount());
        integer(layout.velocityComponent()); integer(layout.fields().size());
        for (var field : layout.fields()) {
            text(field.name()); text(field.sourceType());
            range(field.floats()); range(field.integers()); range(field.halves());
            integer((field.rebase() ? 1 : 0) | (field.interpolate() ? 2 : 0));
        }
    }

    private void buffer(Buffer source, Layout layout) throws IOException {
        var ids = source.idTable();
        var captureIds = source.captureIds();
        integer(source.count()); integer(source.acquireTag()); integer(ids.length); integer(source.nextRowCount());
        integer(captureIds.length);
        words(source, 0, layout.floatCount());
        words(source, 1, layout.integerCount());
        for (int component = 0; component < layout.halfCount(); component++)
            for (int row = 0; row < source.count(); row++)
                data.writeShort(Short.reverseBytes((short)source.word(2, component, row)));
        for (int id : ids) integer(id);
        for (int row = 0; row < source.nextRowCount(); row++) integer(source.nextRow(row));
        for (int id : captureIds) integer(id);
    }

    private void interfaceData(NiagaraCacheInterfaceData storage) throws IOException {
        text(storage.key().name()); text(storage.key().type());
        switch (storage.frames()) {
            case NiagaraCacheInterfaceData.Arrays arrays -> {
                integer(1);
                for (int index = 0; index < arrays.size(); index++) {
                    array(arrays.cpu().get(index)); array(arrays.gpu().get(index));
                }
            }
            case NiagaraCacheInterfaceData.Counters counters -> {
                integer(2);
                for (int index = 0; index < counters.size(); index++) {
                    integer(counters.cpu(index)); integer(counters.gpu(index));
                }
            }
            case NiagaraCacheInterfaceData.Targets2D textures -> {
                integer(3);
                for (var frame : textures.values()) {
                    integer(frame.format());
                    if (frame.format() != -1) {
                        integer(frame.width()); integer(frame.height());
                        byte[] pixels = frame.pixels();
                        integer(pixels.length);
                        data.write(pixels);
                    }
                }
            }
            case NiagaraCacheInterfaceData.Channels channels -> {
                integer(4);
                for (var frame : channels.values()) {
                    integer(frame.rows()); integer(frame.fields().size());
                    integer((frame.game() ? 1 : 0) | (frame.cpu() ? 2 : 0) | (frame.gpu() ? 4 : 0) | (frame.rebase() ? 8 : 0));
                    for (var field : frame.fields()) {
                        text(field.field().name()); text(field.field().type()); integer(field.width());
                        for (int row = 0; row < frame.rows(); row++)
                            for (int component = 0; component < field.width(); component++)
                                decimal(field.component(row, component));
                    }
                }
            }
            case NiagaraCacheInterfaceData.DynamicMeshes meshes -> {
                integer(5);
                for (var pair : meshes.values()) { dynamicMesh(pair.cpu()); dynamicMesh(pair.gpu()); }
            }
            case NiagaraCacheInterfaceData.VolumeTargets volumes -> {
                integer(6);
                for (var frame : volumes.values()) volume(frame);
            }
        }
    }

    private void dynamicMesh(@Nullable DynamicMesh value) throws IOException {
        integer(value == null ? 0 : 1);
        if (value == null) return;
        var frame = value.frame();
        integer(frame.triangles()); integer(frame.vertices()); integer(frame.uvChannels());
        integer(frame.tangentOffset()); integer(frame.uvOffset()); integer(frame.colorOffset()); integer(frame.vertexWords());
        bounds(frame.bounds());
        integer(frame.sections().size());
        for (int index = 0; index < frame.sections().size(); index++) {
            var section = frame.sections().get(index);
            integer(section.triangleOffset()); integer(section.maxTriangles()); integer(section.allocatedTriangles());
            text(value.materials().get(index));
        }
        var indices = frame.indices();
        var vertices = frame.attributes();
        integer(indices.length); integer(vertices.length);
        for (int word : indices) integer(word);
        for (int word : vertices) integer(word);
    }

    private void volume(NiagaraCacheVolumeFrame frame) throws IOException {
        integer(frame.format());
        if (frame.format() == -1) return;
        integer(frame.width()); integer(frame.height()); integer(frame.depth());
        integer(frame.writePixels() ? 1 : 0);
        for (float component : frame.fallback()) floating(component);
        integer(frame.tileCount()); integer(frame.pages().size());
        for (var page : frame.pages()) { integer(page.x()); integer(page.y()); integer(page.z()); integer(page.tile()); }
        byte[] pixels = frame.pixels();
        integer(pixels.length);
        data.write(pixels);
    }

    private void array(ArrayFrame frame) throws IOException {
        if (!frame.present()) { integer(-1); return; }
        var words = frame.words();
        integer(words.length);
        for (int word : words) integer(word);
    }
    private void words(Buffer source, int type, int columns) throws IOException {
        for (int component = 0; component < columns; component++)
            for (int row = 0; row < source.count(); row++) integer(source.word(type, component, row));
    }
    private void range(Range value) throws IOException { integer(value.start()); integer(value.count()); }
    private void text(String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        integer(bytes.length); data.write(bytes);
    }
    private void bounds(@Nullable NiagaraBounds value) throws IOException {
        integer(value == null ? 0 : 1);
        if (value == null) return;
        decimal(value.minX()); decimal(value.minY()); decimal(value.minZ());
        decimal(value.maxX()); decimal(value.maxY()); decimal(value.maxZ());
    }
    private void vector(Vec3 value) throws IOException { decimal(value.x); decimal(value.y); decimal(value.z); }
    private void floating(float value) throws IOException { integer(Float.floatToRawIntBits(value)); }
    private void decimal(double value) throws IOException { data.writeLong(Long.reverseBytes(Double.doubleToRawLongBits(value))); }
    private void integer(int value) throws IOException { data.writeInt(Integer.reverseBytes(value)); }
}
