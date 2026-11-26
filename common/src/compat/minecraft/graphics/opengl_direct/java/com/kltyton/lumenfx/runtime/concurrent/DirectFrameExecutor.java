package com.kltyton.lumenfx.runtime.concurrent;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import javax.annotation.Nullable;

/**
 * Java-17-compatible lock-free frame-synchronous executor shared by all OpenGL-direct targets
 * (1.20.1, 1.21.1, 26.1.2).
 *
 * <p>Persistent daemon worker slots wake via {@link LockSupport} with zero per-frame queue or future
 * allocations. The calling thread executes owner-bound work concurrently while workers steal worker-safe
 * items, then immediately joins work-stealing as partition 0 so the main thread never sits idle.</p>
 */
public final class DirectFrameExecutor implements AutoCloseable {
    public static final DirectFrameExecutor INSTANCE = new DirectFrameExecutor();

    private static final int SPIN_LIMIT = 256;

    @FunctionalInterface
    public interface IndexedTask {
        void run(int partition, int index);
    }

    private final int maximumWorkers = computeMaximumWorkers();
    private @Nullable WorkerPool pool;

    private DirectFrameExecutor() { }

    private static int computeMaximumWorkers() {
        int cpus = Runtime.getRuntime().availableProcessors();
        if (cpus <= 2) return 1;
        return Math.max(1, Math.min(6, cpus / 2));
    }

    public int maximumWorkers() {
        return maximumWorkers;
    }

    public int partitions(int items, int estimatedWork) {
        if (items <= 1 || maximumWorkers <= 1) return 1;
        if (items >= 4 || estimatedWork >= 64) return Math.min(maximumWorkers, items);
        return 1;
    }

    public void executeIndexed(int count, int partitions, @Nullable Runnable ownerWork, IndexedTask task) {
        if (partitions < 1 || partitions > maximumWorkers) {
            throw new IllegalArgumentException("Invalid VFX frame partition count: " + partitions);
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
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private volatile boolean shutdown;
        private volatile int itemCount;
        private volatile @Nullable IndexedTask indexedTask;
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
            waiter = Thread.currentThread();
            remainingWorkers.set(workerCount);
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
            Throwable error = failure.getAndSet(null);
            if (error != null) {
                throw new IllegalStateException("LumenFX direct frame batch failed", error);
            }
        }

        void executeWorker(int partition) {
            try {
                IndexedTask task = indexedTask;
                int limit = itemCount;
                if (task != null) {
                    for (int index; (index = nextIndex.getAndIncrement()) < limit; ) {
                        task.run(partition, index);
                    }
                }
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            } finally {
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

        void shutdown() {
            shutdown = true;
            for (WorkerSlot slot : slots) slot.stop();
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
                worker = new Thread(this, "LumenFX-direct-sim-" + slotIndex);
                worker.setDaemon(true);
                worker.start();
                thread = worker;
            }
            pendingPartition = partition;
            dispatchedEpoch = ++callerEpoch;
            LockSupport.unpark(worker);
        }

        void stop() {
            Thread worker = thread;
            if (worker != null) LockSupport.unpark(worker);
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
