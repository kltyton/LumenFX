package com.kltyton.lumenfx.client.concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** At most one complete numeric frame batch is in flight; the queue cannot accumulate frames. */
public final class VfxFrameExecutor implements AutoCloseable {
    public static final VfxFrameExecutor INSTANCE = new VfxFrameExecutor();

    @FunctionalInterface
    public interface RangeTask {
        void run(int partition, int from, int to);
    }

    /**
     * While joining one batch, its owner can service inputs that only it can read.
     * Each partition has at most one outstanding request or completion.
     */
    public static final class OwnerRequests {
        private static final Runnable COMPLETED = () -> { };
        private final Thread owner = Thread.currentThread();
        private final ArrayBlockingQueue<Runnable> messages;

        public OwnerRequests(int partitions) {
            messages = new ArrayBlockingQueue<>(partitions);
        }

        public <T> T read(Supplier<T> reader) {
            if (Thread.currentThread() == owner) return reader.get();
            var task = new FutureTask<>(reader::get);
            messages.add(task);
            boolean interrupted = false;
            try {
                for (;;) {
                    try {
                        return task.get();
                    } catch (InterruptedException error) {
                        interrupted = true;
                    } catch (ExecutionException error) {
                        throw new IllegalStateException("VFX owner input could not be read", error.getCause());
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        private void completePartition() {
            messages.add(COMPLETED);
        }

        private void service(int partitions) {
            boolean interrupted = false;
            try {
                int completed = 0;
                while (completed < partitions) {
                    try {
                        Runnable message = messages.take();
                        if (message == COMPLETED) completed++;
                        else message.run();
                    } catch (InterruptedException error) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private final int maximumWorkers = Math.max(1, Math.min(6, Runtime.getRuntime().availableProcessors() / 2 - 1));
    private ThreadPoolExecutor executor;

    private VfxFrameExecutor() { }

    public int maximumWorkers() { return maximumWorkers; }
    public int partitions(int effects) { return effects < 8 ? 1 : Math.min(maximumWorkers, effects); }

    /** Advance disjoint contiguous ranges and join before the caller publishes the results. */
    public void executeRanges(int from, int to, int partitions, RangeTask task) {
        executeRanges(from, to, partitions, null, task);
    }

    public void executeRanges(int from, int to, int partitions, @Nullable OwnerRequests ownerRequests, RangeTask task) {
        if (partitions == 1) { task.run(0, from, to); return; }
        if (partitions < 1 || partitions > maximumWorkers) throw new IllegalArgumentException("Invalid VFX frame partition count");
        if (executor == null) {
            executor = new ThreadPoolExecutor(maximumWorkers, maximumWorkers, 30, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(maximumWorkers),
                    Thread.ofPlatform().daemon().name("LumenFX-simulation-", 0).factory(),
                    new ThreadPoolExecutor.AbortPolicy());
            executor.allowCoreThreadTimeOut(true);
        }
        List<Future<?>> futures = new ArrayList<>(partitions);
        int count = to - from;
        for (int index = 0; index < partitions; index++) {
            int partition = index;
            int start = from + (int) ((long) count * partition / partitions);
            int end = from + (int) ((long) count * (partition + 1) / partitions);
            futures.add(executor.submit(() -> {
                try {
                    task.run(partition, start, end);
                } finally {
                    if (ownerRequests != null) ownerRequests.completePartition();
                }
            }));
        }
        if (ownerRequests != null) ownerRequests.service(partitions);
        Throwable failure = null;
        boolean interrupted = false;
        for (Future<?> future : futures) {
            boolean completed = false;
            while (!completed) {
                try { future.get(); completed = true; }
                catch (InterruptedException error) { interrupted = true; if (failure == null) failure = error; }
                catch (ExecutionException error) { if (failure == null) failure = error.getCause(); completed = true; }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (failure != null) throw new IllegalStateException("VFX numeric frame batch failed", failure);
    }

    @Override public void close() {
        if (executor != null) { executor.shutdown(); executor = null; }
    }
}
