package com.kltyton.lumenfx.client.render.niagara.gpu.cache;

import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Buffer;
import java.nio.ByteBuffer;

/** Encodes the frame/emitter headers and three typed streams consumed by the native cache reader shader. */
final class NiagaraGpuCacheData {
    private static final int HEADER_WORDS = 4;
    private NiagaraGpuCacheData() { }

    static long bytes(NiagaraSimulationCache source) {
        long words = header(source);
        for (var frame : source.frames()) {
            for (int emitter = 0; emitter < frame.emitters().size(); emitter++) {
                var layout = source.emitterLayouts().get(emitter);
                long rows = frame.emitters().get(emitter).particles().count();
                long full = rows * ((long)layout.floatCount() + layout.integerCount());
                long half = Math.ceilDiv(rows * layout.halfCount(), 2);
                words = Math.addExact(words, Math.addExact(full, half));
            }
        }
        return Math.multiplyExact(words, Integer.BYTES);
    }

    static void write(ByteBuffer destination, NiagaraSimulationCache source) {
        int header = 0;
        destination.position(Math.toIntExact(header(source) * Integer.BYTES));
        for (var frame : source.frames()) {
            for (int emitter = 0; emitter < frame.emitters().size(); emitter++) {
                var layout = source.emitterLayouts().get(emitter);
                var particles = frame.emitters().get(emitter).particles();
                destination.putInt(header, particles.count());
                destination.putInt(header + 4, destination.position() / Integer.BYTES);
                words(destination, particles, 1, layout.integerCount());
                destination.putInt(header + 8, destination.position() / Integer.BYTES);
                words(destination, particles, 0, layout.floatCount());
                destination.putInt(header + 12, destination.position() / Integer.BYTES);
                for (int column = 0; column < layout.halfCount(); column++)
                    for (int row = 0; row < particles.count(); row++)
                        destination.putShort((short)particles.word(2, column, row));
                if ((destination.position() & 3) != 0) destination.putShort((short)0);
                header += HEADER_WORDS * Integer.BYTES;
            }
        }
        destination.flip();
    }

    private static long header(NiagaraSimulationCache source) {
        return Math.multiplyExact((long)source.frames().size() * source.emitterLayouts().size(), HEADER_WORDS);
    }
    private static void words(ByteBuffer destination, Buffer particles, int type, int columns) {
        for (int column = 0; column < columns; column++)
            for (int row = 0; row < particles.count(); row++) destination.putInt(particles.word(type, column, row));
    }
}
