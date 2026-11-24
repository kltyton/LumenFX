package com.kltyton.lumenfx.client.simulation.niagara.interfaces.memory;

import com.kltyton.lumenfx.effect.niagara.memory.NiagaraMemoryBufferDefinition;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.IntBinaryOperator;
import org.jspecify.annotations.Nullable;

/** One physical DI owns four-byte words and the four source guard words beyond its logical end. */
public final class NiagaraMemoryBuffer {
    private static final VarHandle WORDS = MethodHandles.arrayElementVarHandle(int[].class);
    private enum Kind { RESIZE, SIZE, LOAD, STORE, CLEAR, ATOMIC }
    public enum Operation {
        SetNumElements(Kind.RESIZE, 0), GetNumElements(Kind.SIZE, 0),
        ClearBufferInt(Kind.CLEAR, 0), ClearBufferFloat(Kind.CLEAR, 0),
        LoadInt(Kind.LOAD, 1), LoadInt2(Kind.LOAD, 2), LoadInt3(Kind.LOAD, 3), LoadInt4(Kind.LOAD, 4),
        LoadFloat(Kind.LOAD, 1), LoadFloat2(Kind.LOAD, 2), LoadFloat3(Kind.LOAD, 3), LoadFloat4(Kind.LOAD, 4),
        StoreInt(Kind.STORE, 1), StoreInt2(Kind.STORE, 2), StoreInt3(Kind.STORE, 3), StoreInt4(Kind.STORE, 4),
        StoreFloat(Kind.STORE, 1), StoreFloat2(Kind.STORE, 2), StoreFloat3(Kind.STORE, 3), StoreFloat4(Kind.STORE, 4),
        AtomicAdd(Kind.ATOMIC, 0), AtomicAnd(Kind.ATOMIC, 0), AtomicCompareAndExchange(Kind.ATOMIC, 0),
        AtomicMax(Kind.ATOMIC, 0), AtomicMin(Kind.ATOMIC, 0), AtomicOr(Kind.ATOMIC, 0), AtomicXor(Kind.ATOMIC, 0);

        private final Kind kind;
        private final int width;
        Operation(Kind kind, int width) { this.kind = kind; this.width = width; }
        public boolean usesWords() { return kind != Kind.RESIZE && kind != Kind.SIZE; }
    }

    @FunctionalInterface
    public interface Output { void set(int register, int lane, int value); }

    public record Snapshot(int elements, long revision, int[] words, @Nullable Integer pendingClear,
                           NiagaraMemoryBufferDefinition.SyncMode sync) {
        public Snapshot { words = words.clone(); }
        @Override public int[] words() { return words.clone(); }
    }

    private final NiagaraMemoryBufferDefinition definition;
    private final int maximum;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final AtomicLong revision = new AtomicLong(1);
    private volatile long cacheEpoch;
    private int elements;
    private volatile boolean usedCpu;
    private int[] words = new int[0];
    private @Nullable Integer gpuClear;
    private @Nullable Snapshot published;

    public NiagaraMemoryBuffer(NiagaraMemoryBufferDefinition definition, int maximum) {
        this.definition = definition;
        this.maximum = maximum;
        elements = Math.min(definition.defaultElements(), maximum);
    }
    public NiagaraMemoryBufferDefinition definition() { return definition; }
    public long revision() { return revision.get(); }
    public long cacheEpoch() { return cacheEpoch; }

    public void requireCpu() {
        if (usedCpu) return;
        var write = lock.writeLock();
        write.lock();
        try {
            if (usedCpu) return;
            words = new int[elements + 4];
            usedCpu = true;
            revision.incrementAndGet();
        } finally { write.unlock(); }
    }

    public void execute(Operation operation, int lanes, IntBinaryOperator input, Output output) {
        if (operation.usesWords()) requireCpu();
        var selected = switch (operation.kind) {
            case SIZE, LOAD, ATOMIC -> lock.readLock();
            case RESIZE, STORE, CLEAR -> lock.writeLock();
        };
        selected.lock();
        try {
            switch (operation.kind) {
                case RESIZE -> resize(lanes, input);
                case SIZE -> { for (int lane = 0; lane < lanes; lane++) output.set(0, lane, elements); }
                case LOAD -> load(operation.width, lanes, input, output);
                case STORE -> store(operation.width, lanes, input, output);
                case CLEAR -> clear(lanes, input);
                case ATOMIC -> atomics(operation, lanes, input, output);
            }
        } finally { selected.unlock(); }
    }

    private void resize(int lanes, IntBinaryOperator input) {
        boolean changed = false;
        for (int lane = 0; lane < lanes; lane++) {
            int next = Math.clamp(input.applyAsInt(0, lane), 0, maximum);
            changed |= next != elements;
            elements = next;
        }
        if (usedCpu && words.length != elements + 4) words = Arrays.copyOf(words, elements + 4);
        if (changed) revision.incrementAndGet();
    }

    private void load(int width, int lanes, IntBinaryOperator input, Output output) {
        for (int lane = 0; lane < lanes; lane++) {
            int offset = input.applyAsInt(0, lane);
            int clamped = Math.clamp(offset, 0, elements);
            output.set(0, lane, offset >= 0 && offset <= elements - width ? -1 : 0);
            for (int component = 0; component < width; component++)
                output.set(component + 1, lane, (int)WORDS.getVolatile(words, clamped + component));
        }
    }

    private void store(int width, int lanes, IntBinaryOperator input, Output output) {
        revision.incrementAndGet();
        for (int lane = 0; lane < lanes; lane++) {
            int offset = input.applyAsInt(1, lane);
            boolean valid = input.applyAsInt(0, lane) != 0 && offset >= 0 && offset <= elements - width;
            if (valid) for (int component = 0; component < width; component++)
                words[offset + component] = input.applyAsInt(component + 2, lane);
            output.set(0, lane, valid ? -1 : 0);
        }
    }

    private void clear(int lanes, IntBinaryOperator input) {
        revision.incrementAndGet();
        for (int lane = lanes - 1; lane >= 0; lane--) {
            if (input.applyAsInt(0, lane) == 0) continue;
            int value = input.applyAsInt(1, lane);
            Arrays.fill(words, value);
            gpuClear = value;
            break;
        }
    }

    private void atomics(Operation operation, int lanes, IntBinaryOperator input, Output output) {
        revision.incrementAndGet();
        for (int lane = 0; lane < lanes; lane++) {
            int old = 0, current = 0;
            if (input.applyAsInt(0, lane) != 0) {
                int offset = Math.clamp(input.applyAsInt(1, lane), 0, elements);
                int value = input.applyAsInt(2, lane);
                int compare = operation == Operation.AtomicCompareAndExchange ? input.applyAsInt(3, lane) : 0;
                int observed = (int)WORDS.getVolatile(words, offset);
                do {
                    old = observed;
                    current = switch (operation) {
                        case AtomicAdd -> old + value;
                        case AtomicAnd -> old & value;
                        case AtomicOr -> old | value;
                        case AtomicXor -> old ^ value;
                        case AtomicMin -> Math.min(old, value);
                        case AtomicMax -> Math.max(old, value);
                        case AtomicCompareAndExchange -> old == compare ? value : old;
                        default -> throw new IllegalArgumentException("Not a Niagara memory buffer atomic operation: " + operation);
                    };
                    observed = (int)WORDS.compareAndExchange(words, offset, old, current);
                } while (observed != old);
            }
            output.set(0, lane, old);
            output.set(1, lane, current);
        }
    }

    public Snapshot snapshot() {
        var write = lock.writeLock();
        write.lock();
        try {
            long version = revision.get();
            if (published != null && published.revision() == version) return published;
            var value = new Snapshot(elements, version, definition.sync().toGpu() ? words : new int[0], gpuClear, definition.sync());
            gpuClear = null;
            published = value;
            return value;
        } finally { write.unlock(); }
    }

    public void applyGpu(int[] value, long expectedCacheEpoch) {
        var write = lock.writeLock();
        write.lock();
        try { if (expectedCacheEpoch == cacheEpoch && value.length == words.length) words = value; }
        finally { write.unlock(); }
    }

    public int[] cachedCpuWords() {
        var read = lock.readLock();
        read.lock();
        try {
            var result = new int[words.length];
            for (int i = 0; i < result.length; i++) result[i] = (int)WORDS.getVolatile(words, i);
            return result;
        } finally { read.unlock(); }
    }

    public void readCache(int[] values) {
        var write = lock.writeLock();
        write.lock();
        try {
            if (!usedCpu || values.length == 0) return;
            if (values.length > maximum) throw new IllegalArgumentException("Cached Niagara memory buffer exceeds the device limit");
            elements = values.length;
            words = Arrays.copyOf(values, elements + 4);
            cacheEpoch++;
            revision.incrementAndGet();
        } finally { write.unlock(); }
    }
}
