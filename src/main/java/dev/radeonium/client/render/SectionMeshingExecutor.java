package dev.radeonium.client.render;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Bounded worker pool for CPU section meshing.
 *
 * Workers only consume immutable captures. Stale jobs can be cancelled before
 * they start and are checked again before and after building. GPU work remains
 * owned by the render thread.
 */
public final class SectionMeshingExecutor implements AutoCloseable {
    private static final long SHUTDOWN_WAIT_SECONDS = 2L;

    private final ThreadPoolExecutor workers;
    private final SectionMeshBuilder builder = new SectionMeshBuilder();
    private final MinecraftSectionModelMesher minecraftMesher = new MinecraftSectionModelMesher();
    private final SectionMeshingMetrics metrics = new SectionMeshingMetrics();
    private final AtomicBoolean closed = new AtomicBoolean();

    public SectionMeshingExecutor() {
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        int threadCount = Math.max(1, Math.min(4, processors - 1));
        this.workers = new ThreadPoolExecutor(
                threadCount, threadCount, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(256),
                task -> {
                    Thread thread = new Thread(task, "Radeonium-Mesher");
                    thread.setDaemon(true);
                    thread.setPriority(Thread.NORM_PRIORITY - 1);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public CompletableFuture<SectionMesh> buildAsync(VoxelSectionSnapshot snapshot) {
        return buildAsync(snapshot, () -> true);
    }

    /** The predicate must only inspect thread-safe generation/epoch state. */
    public CompletableFuture<SectionMesh> buildAsync(
            VoxelSectionSnapshot snapshot, BooleanSupplier stillCurrent
    ) {
        if (snapshot == null) throw new IllegalArgumentException("snapshot cannot be null");
        if (stillCurrent == null) throw new IllegalArgumentException("stillCurrent cannot be null");
        return submit(() -> builder.build(snapshot), stillCurrent);
    }

    public CompletableFuture<MinecraftSectionModelMesher.SectionModelMesh> buildMinecraftAsync(
            MinecraftSectionModelMesher.CapturedSectionInput input
    ) {
        return buildMinecraftAsync(input, () -> true);
    }

    /** CPU-mesh an immutable capture; never reads live world or renderer state. */
    public CompletableFuture<MinecraftSectionModelMesher.SectionModelMesh> buildMinecraftAsync(
            MinecraftSectionModelMesher.CapturedSectionInput input, BooleanSupplier stillCurrent
    ) {
        if (input == null) throw new IllegalArgumentException("input cannot be null");
        if (stillCurrent == null) throw new IllegalArgumentException("stillCurrent cannot be null");
        return submit(() -> minecraftMesher.buildResolved(input.blocks()).withCaptureMetadata(input), stillCurrent);
    }

    private <T> CompletableFuture<T> submit(Supplier<T> build, BooleanSupplier stillCurrent) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (closed.get()) {
            metrics.recordRejected();
            result.completeExceptionally(new RejectedExecutionException("meshing executor is closed"));
            return result;
        }

        MeshingTask<T> task = new MeshingTask<>(build, stillCurrent, result);
        result.whenComplete((value, failure) -> {
            if (result.isCancelled()) task.cancelQueued();
        });
        try {
            workers.execute(task);
            metrics.recordSubmitted();
        } catch (RejectedExecutionException rejected) {
            metrics.recordRejected();
            result.completeExceptionally(rejected);
        }
        return result;
    }

    private final class MeshingTask<T> implements Runnable {
        private final Supplier<T> build;
        private final BooleanSupplier stillCurrent;
        private final CompletableFuture<T> result;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final long submittedAt = System.nanoTime();

        private MeshingTask(Supplier<T> build, BooleanSupplier stillCurrent, CompletableFuture<T> result) {
            this.build = build;
            this.stillCurrent = stillCurrent;
            this.result = result;
        }

        @Override
        public void run() {
            if (cancelled.get() || result.isCancelled()) return;
            long startedAt = System.nanoTime();
            long queueWait = Math.max(0L, startedAt - submittedAt);
            try {
                if (!stillCurrent.getAsBoolean()) {
                    cancelAsStale();
                    return;
                }
                T value = build.get();
                if (cancelled.get() || result.isCancelled() || !stillCurrent.getAsBoolean()) {
                    cancelAsStale();
                    return;
                }
                metrics.recordCompleted(queueWait, Math.max(0L, System.nanoTime() - startedAt));
                result.complete(value);
            } catch (CancellationException stale) {
                cancelAsStale();
            } catch (Throwable failure) {
                metrics.recordFailed(queueWait, Math.max(0L, System.nanoTime() - startedAt));
                result.completeExceptionally(failure);
            }
        }

        private void cancelAsStale() {
            if (cancelled.compareAndSet(false, true)) metrics.recordCancelled();
            result.completeExceptionally(new CancellationException("section snapshot is stale"));
        }

        private void cancelQueued() {
            if (cancelled.compareAndSet(false, true)) {
                if (workers.remove(this)) metrics.recordCancelled();
            }
        }

        private void cancelForShutdown() {
            if (cancelled.compareAndSet(false, true)) metrics.recordCancelled();
            result.completeExceptionally(new CancellationException("meshing executor is shutting down"));
        }
    }

    public int queuedJobs() { return workers.getQueue().size(); }
    public int activeWorkers() { return workers.getActiveCount(); }
    public int workerCount() { return workers.getPoolSize(); }
    public int maximumWorkerCount() { return workers.getMaximumPoolSize(); }
    public int remainingQueueCapacity() { return workers.getQueue().remainingCapacity(); }
    public boolean isClosed() { return closed.get(); }
    public SectionMeshingMetrics.Snapshot metricsSnapshot() { return metrics.snapshot(); }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        workers.shutdown();
        try {
            if (!workers.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                List<Runnable> abandoned = workers.shutdownNow();
                for (Runnable runnable : abandoned) {
                    if (runnable instanceof SectionMeshingExecutor.MeshingTask<?> task) {
                        task.cancelForShutdown();
                    }
                }
                workers.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            List<Runnable> abandoned = workers.shutdownNow();
            for (Runnable runnable : abandoned) {
                if (runnable instanceof SectionMeshingExecutor.MeshingTask<?> task) {
                    task.cancelForShutdown();
                }
            }
            Thread.currentThread().interrupt();
        }
    }
}
