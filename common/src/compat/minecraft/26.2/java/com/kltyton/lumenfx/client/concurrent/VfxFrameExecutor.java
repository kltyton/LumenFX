package com.kltyton.lumenfx.client.concurrent;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Low-overhead frame-synchronous executor for VFX simulation and packing.
 *
 * <p>At most one batch runs at a time per caller. Persistent worker slots wake via {@link LockSupport}
 * with zero per-frame queue/future allocation, and the calling thread participates directly as partition 0
 * instead of sitting idle behind {@code Future.get()}.</p>
 */
public final class VfxFrameExecutor implements AutoCloseable {
    public static final VfxFrameExecutor INSTANCE = new VfxFrameExecutor();

    private static final int SPIN_LIMIT = 256;

    @FunctionalInterface
    public interface RangeTask {
        void run(int partition, int from, int to);
    }

    @FunctionalInterface
    public interface IndexedTask {
        void run(int partition, int index);
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
            messages = new ArrayBlockingQueue<>(Math.max(1, partitions));
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

    private final int maximumWorkers = computeMaximumWorkers();
    private @Nullable WorkerPool pool;

    private VfxFrameExecutor() { }

    private static int computeMaximumWorkers() {
        int cpus = Runtime.getRuntime().availableProcessors();
        if (cpus <= 2) return 1;
        return Math.max(1, Math.min(6, cpus / 2));
    }

    public int maximumWorkers() { return maximumWorkers; }

    public int partitions(int effects) {
        return effects < 8 ? 1 : Math.min(maximumWorkers, effects);
    }

    /**
     * Workload-aware partition count: parallelizes smaller emitter counts when aggregate particle work is
     * substantial, while keeping trivial batches single-threaded to avoid thread wakeup overhead.
     */
    public int partitions(int effects, int estimatedWork) {
        if (effects <= 1 || maximumWorkers <= 1) return 1;
        if (effects >= 8 || estimatedWork >= 64) return Math.min(maximumWorkers, effects);
        return 1;
    }

    /** Advance disjoint contiguous ranges and join before the caller publishes the results. */
    public void executeRanges(int from, int to, int partitions, RangeTask task) {
        executeRanges(from, to, partitions, null, task);
    }

    public void executeRanges(int from, int to, int partitions, @Nullable OwnerRequests ownerRequests, RangeTask task) {
        if (partitions == 1) {
            task.run(0, from, to);
            return;
        }
        if (partitions < 1 || partitions > maximumWorkers) {
            throw new IllegalArgumentException("Invalid VFX frame partition count");
        }
        int count = to - from;
        if (count <= 0) {
            for (int partition = 0; partition < partitions; partition++) {
                task.run(partition, from, from);
            }
            return;
        }
        WorkerPool activePool = ensurePool();
        if (ownerRequests != null) {
            // Owner thread must remain responsive to ownerRequests.read() calls from every worker partition.
            activePool.runRangesWithOwnerService(from, count, partitions, ownerRequests, task);
            return;
        }
        // Owner thread executes partition 0 directly while workers execute partitions 1..partitions-1.
        activePool.runRangesCallerAssists(from, count, partitions, task);
    }

    /**
     * Dynamically load-balances {@code count} indexed work items across {@code partitions} executors
     * (partition 0 = calling thread, partitions 1..partitions-1 = worker threads).
     *
     * <p>If {@code ownerWork} is provided, the calling thread runs it concurrently while workers immediately
     * begin stealing from the indexed items, then joins the remaining indexed items once {@code ownerWork}
     * finishes.</p>
     */
    public void executeIndexed(int count, int partitions, @Nullable Runnable ownerWork, IndexedTask task) {
        if (partitions < 1 || partitions > maximumWorkers) {
            throw new IllegalArgumentException("Invalid VFX frame partition count");
        }
        if (count <= 0) {
            if (ownerWork != null) ownerWork.run();
            return;
        }
        if (partitions == 1 || (count == 1 && ownerWork == null)) {
            if (ownerWork != null) ownerWork.run();
            for (int i = 0; i < count; i++) task.run(0, i);
            return;
        }
        int workerCount = Math.min(partitions - 1, count);
        if (workerCount <= 0) {
            if (ownerWork != null) ownerWork.run();
            for (int i = 0; i < count; i++) task.run(0, i);
            return;
        }
        ensurePool().runIndexed(count, workerCount, ownerWork, task);
    }

    private WorkerPool ensurePool() {
        WorkerPool current = pool;
        if (current == null) {
            current = new WorkerPool(maximumWorkers);
            pool = current;
        }
        return current;
    }

    @Override
    public void close() {
        WorkerPool current = pool;
        if (current != null) {
            pool = null;
            current.shutdown();
        }
    }

    private static final class WorkerPool {
        private final WorkerSlot[] slots;
        private final AtomicInteger nextIndex = new AtomicInteger();
        private final AtomicInteger remainingWorkers = new AtomicInteger();
        private final AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();

        private volatile boolean shutdown;
        private volatile int mode; // 1 = indexed, 2 = range caller-assists, 3 = range with owner service
        private volatile int itemCount;
        private volatile int rangeFrom;
        private volatile int rangeCount;
        private volatile int rangePartitions;
        private volatile @Nullable IndexedTask indexedTask;
        private volatile @Nullable RangeTask rangeTask;
        private volatile @Nullable OwnerRequests ownerRequests;
        private volatile @Nullable Thread waiter;

        WorkerPool(int capacity) {
            this.slots = new WorkerSlot[capacity];
            for (int i = 0; i < capacity; i++) {
                this.slots[i] = new WorkerSlot(this, i);
            }
        }

        void runIndexed(int count, int workerCount, @Nullable Runnable ownerWork, IndexedTask task) {
            nextIndex.set(0);
            failure.set(null);
            itemCount = count;
            indexedTask = task;
            rangeTask = null;
            ownerRequests = null;
            mode = 1;
            waiter = Thread.currentThread();
            remainingWorkers.set(workerCount);
            // Worker slot w executes with partition = w + 1 (caller owns partition 0).
            for (int w = 0; w < workerCount; w++) {
                slots[w].wake(w + 1);
            }
            try {
                if (ownerWork != null) {
                    ownerWork.run();
                }
                for (int index; (index = nextIndex.getAndIncrement()) < count; ) {
                    task.run(0, index);
                }
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            } finally {
                awaitWorkers();
                indexedTask = null;
                waiter = null;
            }
            rethrowIfFailed();
        }

        void runRangesCallerAssists(int from, int count, int partitions, RangeTask task) {
            int workerCount = partitions - 1;
            failure.set(null);
            rangeFrom = from;
            rangeCount = count;
            rangePartitions = partitions;
            rangeTask = task;
            indexedTask = null;
            ownerRequests = null;
            mode = 2;
            waiter = Thread.currentThread();
            remainingWorkers.set(workerCount);
            // Workers take partitions 1..partitions-1 while caller runs partition 0 immediately.
            for (int w = 0; w < workerCount; w++) {
                slots[w].wake(w + 1);
            }
            try {
                int end0 = from + (int) ((long) count / partitions);
                task.run(0, from, end0);
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            } finally {
                awaitWorkers();
                rangeTask = null;
                waiter = null;
            }
            rethrowIfFailed();
        }

        void runRangesWithOwnerService(int from, int count, int partitions, OwnerRequests requests, RangeTask task) {
            failure.set(null);
            rangeFrom = from;
            rangeCount = count;
            rangePartitions = partitions;
            rangeTask = task;
            indexedTask = null;
            ownerRequests = requests;
            mode = 3;
            waiter = Thread.currentThread();
            remainingWorkers.set(partitions);
            for (int p = 0; p < partitions; p++) {
                slots[p].wake(p);
            }
            try {
                requests.service(partitions);
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            } finally {
                awaitWorkers();
                rangeTask = null;
                ownerRequests = null;
                waiter = null;
            }
            rethrowIfFailed();
        }

        private void executeWorker(int partition) {
            try {
                int currentMode = mode;
                if (currentMode == 1) {
                    IndexedTask task = indexedTask;
                    int limit = itemCount;
                    if (task != null) {
                        for (int index; (index = nextIndex.getAndIncrement()) < limit; ) {
                            task.run(partition, index);
                        }
                    }
                } else {
                    RangeTask task = rangeTask;
                    if (task != null) {
                        int from = rangeFrom;
                        int count = rangeCount;
                        int partitions = rangePartitions;
                        int start = from + (int) ((long) count * partition / partitions);
                        int end = from + (int) ((long) count * (partition + 1) / partitions);
                        task.run(partition, start, end);
                    }
                }
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            } finally {
                if (mode == 3) {
                    OwnerRequests req = ownerRequests;
                    if (req != null) req.completePartition();
                }
                if (remainingWorkers.decrementAndGet() == 0) {
                    Thread target = waiter;
                    if (target != null) LockSupport.unpark(target);
                }
            }
        }

        private void awaitWorkers() {
            int spins = 0;
            boolean interrupted = false;
            while (remainingWorkers.get() > 0) {
                if (spins < SPIN_LIMIT) {
                    spins++;
                    Thread.onSpinWait();
                    continue;
                }
                LockSupport.park(this);
                if (Thread.interrupted()) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }

        private void rethrowIfFailed() {
            Throwable error = failure.getAndSet(null);
            if (error != null) {
                throw new IllegalStateException("VFX numeric frame batch failed", error);
            }
        }

        void shutdown() {
            shutdown = true;
            for (WorkerSlot slot : slots) {
                slot.stop();
            }
        }
    }

    private static final class WorkerSlot implements Runnable {
        private final WorkerPool pool;
        private final int slotIndex;
        private @Nullable Thread thread;
        private volatile int pendingPartition = -1;
        private volatile int dispatchedEpoch;
        private int callerEpoch;
        private int handledEpoch;

        WorkerSlot(WorkerPool pool, int slotIndex) {
            this.pool = pool;
            this.slotIndex = slotIndex;
        }

        void wake(int partition) {
            Thread worker = thread;
            if (worker == null) {
                worker = Thread.ofPlatform().daemon().name("LumenFX-simulation-" + slotIndex).start(this);
                thread = worker;
            }
            pendingPartition = partition;
            dispatchedEpoch = ++callerEpoch;
            LockSupport.unpark(worker);
        }

        void stop() {
            Thread worker = thread;
            if (worker != null) {
                LockSupport.unpark(worker);
            }
        }

        @Override
        public void run() {
            while (!pool.shutdown) {
                int epoch = dispatchedEpoch;
                if (epoch == handledEpoch) {
                    LockSupport.park(this);
                    continue;
                }
                int partition = pendingPartition;
                handledEpoch = epoch;
                if (partition >= 0) {
                    pool.executeWorker(partition);
                }
            }
        }
    }
}
