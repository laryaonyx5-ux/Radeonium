package dev.radeonium.client.chunk;

/**
 * Adapts synchronous chunk-section work to the recent frame interval.
 *
 * Synchronous section compilation runs on the render thread. When frames are
 * already slow, Radeonium defers more work to Minecraft's existing async
 * queue; when there is headroom, it permits a small amount of extra urgent
 * work. A periodic synchronous slot prevents updates from being deferred
 * forever during sustained low frame rates.
 */
public final class ChunkBuildBudget {
    private static final long FAST_FRAME_NANOS = 12_000_000L;
    private static final long SLOW_FRAME_NANOS = 20_000_000L;
    private static final int MAX_SYNCHRONOUS_BUILDS = 2;
    private static final int MAX_DEFERRED_FRAMES = 3;

    private static long lastFrameStartNanos;
    private static int synchronousBuildsThisFrame;
    private static int synchronousBudget = 1;
    private static int consecutiveFramesWithoutSync;

    private ChunkBuildBudget() {
    }

    /**
     * Called once at the start of LevelRenderer.compileSections.
     * The interval between calls is used as a lightweight frame-time signal.
     */
    public static void beginFrame(long nowNanos) {
        if (lastFrameStartNanos != 0L) {
            long frameInterval = Math.max(0L, nowNanos - lastFrameStartNanos);

            if (frameInterval >= SLOW_FRAME_NANOS) {
                synchronousBudget = 0;
            } else if (frameInterval <= FAST_FRAME_NANOS) {
                synchronousBudget = MAX_SYNCHRONOUS_BUILDS;
            } else {
                synchronousBudget = 1;
            }
        } else {
            synchronousBudget = 1;
        }

        // Never starve urgent terrain updates if the machine remains overloaded.
        if (synchronousBudget == 0
                && consecutiveFramesWithoutSync >= MAX_DEFERRED_FRAMES) {
            synchronousBudget = 1;
        }

        lastFrameStartNanos = nowNanos;
        synchronousBuildsThisFrame = 0;
    }

    public static boolean reserveSynchronousBuild() {
        if (synchronousBuildsThisFrame >= synchronousBudget) {
            consecutiveFramesWithoutSync++;
            return false;
        }

        synchronousBuildsThisFrame++;
        consecutiveFramesWithoutSync = 0;
        return true;
    }
}
