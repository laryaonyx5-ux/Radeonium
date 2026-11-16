package dev.radeonium.client.render;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Bounded worker pool for CPU section meshing.
 *
 * The bounded queue prevents a burst of block updates from growing memory
 * without limit. Rejected work is reported to the caller instead of silently
 * moving expensive meshing onto the render thread.
 */
public final class SectionMeshingExecutor implements AutoCloseable {
    private final ThreadPoolExecutor workers;
    private final SectionMeshBuilder builder = new SectionMeshBuilder();
    private final MinecraftSectionModelMesher minecraftMesher = new MinecraftSectionModelMesher();
    private final SectionMeshingMetrics metrics = new SectionMeshingMetrics();

    public SectionMeshingExecutor() {
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        int threadCount = Math.max(1, Math.min(4, processors - 1));
        this.workers = new ThreadPoolExecutor(
                threadCount,
                threadCount,
                30L,
                TimeUnit.SECONDS,
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
        if (snapshot == null) throw new IllegalArgumentException("snapshot cannot be null");
        return submit(() -> builder.build(snapshot));
    }

    /** CPU-mesh an immutable Minecraft capture without accessing world or renderer state. */
    public CompletableFuture<MinecraftSectionModelMesher.SectionModelMesh> buildMinecraftAsync(
            MinecraftSectionModelMesher.CapturedSectionInput input
    ) {
        if (input == null) throw new IllegalArgumentException("input cannot be null");
        return submit(() -> minecraftMesher.buildResolved(input.blocks())
                .withUnsupportedBlockEntityCount(input.unsupportedBlockEntityCount())
                .withFluids(input.fluids()));
    }

    private <T> CompletableFuture<T> submit(Supplier<T> build) {
        CompletableFuture<T> result = new CompletableFuture<>();
        long submittedAt = System.nanoTime();
        try {
            workers.execute(() -> {
                long startedAt = System.nanoTime();
                long queueWait = Math.max(0L, startedAt - submittedAt);
                try {
                    T value = build.get();
                    metrics.recordCompleted(queueWait, Math.max(0L, System.nanoTime() - startedAt));
                    result.complete(value);
                } catch (Throwable failure) {
                    metrics.recordFailed(queueWait, Math.max(0L, System.nanoTime() - startedAt));
                    result.completeExceptionally(failure);
                }
            });
            metrics.recordSubmitted();
        } catch (RejectedExecutionException rejected) {
            metrics.recordRejected();
            result.completeExceptionally(rejected);
        }
        return result;
    }

    public int queuedJobs() {
        return workers.getQueue().size();
    }

    public int activeWorkers() {
        return workers.getActiveCount();
    }

    public int workerCount() {
        return workers.getPoolSize();
    }

    public int maximumWorkerCount() {
        return workers.getMaximumPoolSize();
    }

    public SectionMeshingMetrics.Snapshot metricsSnapshot() {
        return metrics.snapshot();
    }

    @Override
    public void close() {
        workers.shutdown();
    }
}
