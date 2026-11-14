package dev.radeonium.client.render;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
        CompletableFuture<SectionMesh> result = new CompletableFuture<>();
        try {
            workers.execute(() -> {
                try {
                    result.complete(builder.build(snapshot));
                } catch (Throwable throwable) {
                    result.completeExceptionally(throwable);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
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

    @Override
    public void close() {
        workers.shutdown();
    }
}
