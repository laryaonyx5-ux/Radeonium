package dev.radeonium.client.render;

/**
 * Render-thread GPU upload telemetry. Measures attempted payload bytes and
 * wall-clock upload time; it does not claim to measure GPU execution time.
 */
public final class TerrainGpuUploadMetrics {
    public record Snapshot(
            long attempts,
            long successes,
            long failures,
            long skipped,
            long submittedBytes,
            long totalNanos,
            long maxUploadNanos
    ) {
        public long averageUploadNanos() {
            return successes == 0 ? 0L : totalNanos / successes;
        }
    }

    private long attempts;
    private long successes;
    private long failures;
    private long skipped;
    private long submittedBytes;
    private long totalNanos;
    private long maxUploadNanos;

    public synchronized void record(long payloadBytes, long elapsedNanos, boolean success) {
        if (payloadBytes < 0 || elapsedNanos < 0) {
            throw new IllegalArgumentException("upload bytes and duration cannot be negative");
        }
        attempts = Math.addExact(attempts, 1L);
        submittedBytes = Math.addExact(submittedBytes, payloadBytes);
        if (success) {
            successes = Math.addExact(successes, 1L);
            totalNanos = Math.addExact(totalNanos, elapsedNanos);
            maxUploadNanos = Math.max(maxUploadNanos, elapsedNanos);
        } else {
            failures = Math.addExact(failures, 1L);
        }
    }

    public synchronized void recordSkipped(long payloadBytes, long elapsedNanos) {
        if (payloadBytes < 0 || elapsedNanos < 0) {
            throw new IllegalArgumentException("upload bytes and duration cannot be negative");
        }
        attempts = Math.addExact(attempts, 1L);
        skipped = Math.addExact(skipped, 1L);
        submittedBytes = Math.addExact(submittedBytes, payloadBytes);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(attempts, successes, failures, skipped, submittedBytes, totalNanos, maxUploadNanos);
    }

    public synchronized void reset() {
        attempts = 0L;
        successes = 0L;
        failures = 0L;
        skipped = 0L;
        submittedBytes = 0L;
        totalNanos = 0L;
        maxUploadNanos = 0L;
    }
}
