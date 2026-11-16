package dev.radeonium.client.render;

/**
 * Thread-safe telemetry for CPU section meshing work. Durations are CPU-side
 * queue wait and worker wall time, not GPU timings.
 */
public final class SectionMeshingMetrics {
    public record Snapshot(
            long submitted,
            long completed,
            long failed,
            long rejected,
            long totalQueueWaitNanos,
            long totalBuildNanos,
            long maxBuildNanos
    ) {
        public long averageBuildNanos() {
            return completed == 0 ? 0L : totalBuildNanos / completed;
        }
    }

    private long submitted;
    private long completed;
    private long failed;
    private long rejected;
    private long totalQueueWaitNanos;
    private long totalBuildNanos;
    private long maxBuildNanos;

    public synchronized void recordSubmitted() {
        submitted = Math.addExact(submitted, 1L);
    }

    public synchronized void recordRejected() {
        rejected = Math.addExact(rejected, 1L);
    }

    public synchronized void recordCompleted(long queueWaitNanos, long buildNanos) {
        validateDurations(queueWaitNanos, buildNanos);
        completed = Math.addExact(completed, 1L);
        totalQueueWaitNanos = Math.addExact(totalQueueWaitNanos, queueWaitNanos);
        totalBuildNanos = Math.addExact(totalBuildNanos, buildNanos);
        maxBuildNanos = Math.max(maxBuildNanos, buildNanos);
    }

    public synchronized void recordFailed(long queueWaitNanos, long buildNanos) {
        validateDurations(queueWaitNanos, buildNanos);
        failed = Math.addExact(failed, 1L);
        totalQueueWaitNanos = Math.addExact(totalQueueWaitNanos, queueWaitNanos);
        totalBuildNanos = Math.addExact(totalBuildNanos, buildNanos);
        maxBuildNanos = Math.max(maxBuildNanos, buildNanos);
    }

    private static void validateDurations(long queueWaitNanos, long buildNanos) {
        if (queueWaitNanos < 0 || buildNanos < 0) {
            throw new IllegalArgumentException("meshing durations cannot be negative");
        }
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(submitted, completed, failed, rejected,
                totalQueueWaitNanos, totalBuildNanos, maxBuildNanos);
    }

    public synchronized void reset() {
        submitted = 0L;
        completed = 0L;
        failed = 0L;
        rejected = 0L;
        totalQueueWaitNanos = 0L;
        totalBuildNanos = 0L;
        maxBuildNanos = 0L;
    }
}
