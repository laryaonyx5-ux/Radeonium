package dev.radeonium.client.render;

/**
 * Hard per-tick limits for render-thread geometry uploads.
 *
 * <p>The first queued item is always allowed so a single large section cannot
 * starve forever. Further items must fit both the byte and elapsed-time
 * budgets. Limits are deliberately conservative and can be tuned from live
 * profiling without changing cache ownership.</p>
 */
public final class TerrainGpuUploadBudget {
    public static final int MAX_UPLOADS_PER_TICK = 4;
    public static final long MAX_BYTES_PER_TICK = 8L * 1024L * 1024L;
    public static final long MAX_NANOS_PER_TICK = 2_000_000L;

    private TerrainGpuUploadBudget() {}

    public static boolean canUploadNext(
            int uploadsThisTick,
            long bytesThisTick,
            long elapsedNanos,
            long nextPayloadBytes
    ) {
        if (uploadsThisTick < 0 || bytesThisTick < 0 || elapsedNanos < 0 || nextPayloadBytes < 0) {
            throw new IllegalArgumentException("upload budget measurements cannot be negative");
        }
        if (uploadsThisTick == 0) return true;
        return uploadsThisTick < MAX_UPLOADS_PER_TICK
                && elapsedNanos < MAX_NANOS_PER_TICK
                && nextPayloadBytes <= MAX_BYTES_PER_TICK - Math.min(bytesThisTick, MAX_BYTES_PER_TICK);
    }
}
