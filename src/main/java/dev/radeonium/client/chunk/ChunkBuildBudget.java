package dev.radeonium.client.chunk;

/**
 * Adapts synchronous chunk-section work and optional chunk maintenance to
 * recent frame pacing. The complete Radeonium scheduling layer can be toggled
 * without changing Minecraft's own renderer behavior.
 */
public final class ChunkBuildBudget {
    private static final long FAST_FRAME_NANOS = 12_000_000L;
    private static final long SLOW_FRAME_NANOS = 20_000_000L;
    private static final long TRANSLUCENCY_DEFER_FRAME_NANOS = 25_000_000L;
    private static final int MAX_SYNCHRONOUS_BUILDS = CpuWorkProfile.maxSynchronousChunkBuilds();
    private static final int MAX_DEFERRED_FRAMES = 3;

    private static volatile boolean enabled = true;
    private static long lastFrameStartNanos;
    private static long lastFrameIntervalNanos;
    private static int synchronousBuildsThisFrame;
    private static int synchronousBudget = 1;
    private static int consecutiveFramesWithoutSync;

    private ChunkBuildBudget() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        if (!value) {
            // Restore vanilla's synchronous behavior immediately while off.
            synchronousBudget = Integer.MAX_VALUE;
            lastFrameIntervalNanos = 0L;
            synchronousBuildsThisFrame = 0;
            consecutiveFramesWithoutSync = 0;
        }
    }

    /**
     * Called at the start of LevelRenderer.compileSections. The interval
     * between calls is used as a lightweight frame-time signal.
     */
    public static void beginFrame(long nowNanos) {
        if (!enabled) {
            lastFrameStartNanos = nowNanos;
            return;
        }

        if (lastFrameStartNanos != 0L) {
            long frameInterval = Math.max(0L, nowNanos - lastFrameStartNanos);
            lastFrameIntervalNanos = frameInterval;
            if (synchronousBuildsThisFrame == 0 && synchronousBudget == 0) {
                consecutiveFramesWithoutSync++;
            } else {
                consecutiveFramesWithoutSync = 0;
            }

            if (frameInterval >= SLOW_FRAME_NANOS) {
                synchronousBudget = 0;
            } else if (frameInterval <= FAST_FRAME_NANOS) {
                synchronousBudget = MAX_SYNCHRONOUS_BUILDS;
            } else {
                synchronousBudget = 1;
            }
        } else {
            lastFrameIntervalNanos = 0L;
            synchronousBudget = 1;
        }

        // Never starve urgent terrain updates during sustained slow frames.
        if (synchronousBudget == 0
                && consecutiveFramesWithoutSync >= MAX_DEFERRED_FRAMES) {
            synchronousBudget = 1;
        }

        lastFrameStartNanos = nowNanos;
        synchronousBuildsThisFrame = 0;
    }

    public static boolean reserveSynchronousBuild() {
        if (!enabled) {
            return true;
        }
        if (synchronousBuildsThisFrame >= synchronousBudget) {
            return false;
        }

        synchronousBuildsThisFrame++;
        return true;
    }

    /**
     * Translucent resorting is maintenance work: if the previous frame was
     * especially slow, leave Minecraft's round-robin cursor untouched and
     * let it resume the pending slice on a later frame.
     */
    public static boolean shouldDeferTranslucencyResort() {
        return enabled && lastFrameIntervalNanos >= TRANSLUCENCY_DEFER_FRAME_NANOS;
    }
}
