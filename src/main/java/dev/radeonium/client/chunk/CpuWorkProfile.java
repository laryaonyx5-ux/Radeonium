package dev.radeonium.client.chunk;

/**
 * Conservative CPU profile used to cap render-thread chunk work.
 *
 * availableProcessors() reports the logical processors available to this JVM
 * (and respects container/OS CPU limits). The result is captured once so the
 * chunk scheduling hot path does not repeatedly query the runtime.
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
     * Keep synchronous render-thread work conservative on small CPUs.
     * The frame-pacing budget can still reduce this value to zero or one.
     */
    public static int maxSynchronousChunkBuilds() {
        return AVAILABLE_LOGICAL_PROCESSORS <= 4 ? 1 : 2;
    }
}
