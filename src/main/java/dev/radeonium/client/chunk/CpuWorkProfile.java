package dev.radeonium.client.chunk;

/**
 * CPU profile used to size Radeonium's render-thread chunk-work ceiling.
 *
 * The ceiling is only reachable while frame pacing is healthy. Slow and
 * normal pacing states continue to reduce the number of synchronous builds.
 */
public final class CpuWorkProfile {
    private static final int AVAILABLE_LOGICAL_PROCESSORS =
            Math.max(1, Runtime.getRuntime().availableProcessors());

    private CpuWorkProfile() {
    }

    public static int availableLogicalProcessors() {
        return AVAILABLE_LOGICAL_PROCESSORS;
    }

    /**
     * Larger CPUs get a slightly wider fast-frame budget. This is deliberately
     * a ceiling, not a target: ChunkBuildBudget only uses it while frames are
     * consistently fast, and otherwise falls back to one or zero.
     */
    public static int maxSynchronousChunkBuilds() {
        if (AVAILABLE_LOGICAL_PROCESSORS >= 12) {
            return 3;
        }
        if (AVAILABLE_LOGICAL_PROCESSORS >= 6) {
            return 2;
        }
        return 1;
    }
}