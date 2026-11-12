package dev.radeonium.client.chunk;

import dev.radeonium.client.memory.MemoryPressureMonitor;

/**
 * Adapts synchronous chunk-section work and optional chunk maintenance to
 * a smoothed pacing signal. The complete Radeonium scheduling layer can be
 * toggled without changing Minecraft's own renderer behavior.
 */
public final class ChunkBuildBudget {
    private static final long FAST_ENTER_NANOS = 11_000_000L;
    private static final long FAST_EXIT_NANOS = 13_000_000L;
    private static final long SLOW_EXIT_NANOS = 18_000_000L;
    private static final long SLOW_ENTER_NANOS = 22_000_000L;
    private static final long TRANSLUCENCY_DEFER_FRAME_NANOS = 25_000_000L;
    private static final long MAX_PACING_SAMPLE_NANOS = 100_000_000L;
    private static final int MAX_SYNCHRONOUS_BUILDS = CpuWorkProfile.maxSynchronousChunkBuilds();
    private static final int MAX_DEFERRED_FRAMES = 3;

    private enum PacingState {
        FAST,
        NORMAL,
        SLOW
    }

    private static volatile boolean enabled = true;
    private static long lastFrameStartNanos;
    private static long smoothedFrameIntervalNanos;
    private static int synchronousBuildsThisFrame;
    private static int synchronousBudget = 1;
    private static int consecutiveFramesWithoutSync;
    private static PacingState pacingState = PacingState.NORMAL;

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
            lastFrameStartNanos = 0L;
            smoothedFrameIntervalNanos = 0L;
            synchronousBuildsThisFrame = 0;
            consecutiveFramesWithoutSync = 0;
            pacingState = PacingState.NORMAL;
        } else {
            // Start with a neutral estimate; do not reuse stale measurements.
            lastFrameStartNanos = 0L;
            smoothedFrameIntervalNanos = 0L;
            synchronousBudget = 1;
            synchronousBuildsThisFrame = 0;
            consecutiveFramesWithoutSync = 0;
            pacingState = PacingState.NORMAL;
        }
    }

    /**
     * Called at the start of LevelRenderer.compileSections. The interval
     * between calls is only a proxy for pacing, not a guaranteed render-frame
     * measurement. An exponential moving average and hysteresis prevent the
     * chunk budget from rapidly switching around a single threshold.
     */
    public static void beginFrame(long nowNanos) {
        if (!enabled) {
            lastFrameStartNanos = nowNanos;
            return;
        }

        if (lastFrameStartNanos != 0L) {
            long sample = Math.max(0L, nowNanos - lastFrameStartNanos);
            sample = Math.min(sample, MAX_PACING_SAMPLE_NANOS);

            if (smoothedFrameIntervalNanos == 0L) {
                smoothedFrameIntervalNanos = sample;
            } else {
                smoothedFrameIntervalNanos =
                        (smoothedFrameIntervalNanos * 3L + sample) / 4L;
            }

            updatePacingState(smoothedFrameIntervalNanos);

            if (synchronousBuildsThisFrame == 0 && synchronousBudget == 0) {
                consecutiveFramesWithoutSync++;
            } else {
                consecutiveFramesWithoutSync = 0;
            }

            synchronousBudget = switch (pacingState) {
                case FAST -> MAX_SYNCHRONOUS_BUILDS;
                case NORMAL -> 1;
                case SLOW -> 0;
            };

            // Never starve urgent terrain updates during sustained slow pacing.
            // Critical heap pressure still overrides this in reserveSynchronousBuild.
            if (synchronousBudget == 0
                    && consecutiveFramesWithoutSync >= MAX_DEFERRED_FRAMES) {
                synchronousBudget = 1;
            }
        } else {
            smoothedFrameIntervalNanos = 0L;
            synchronousBudget = 1;
        }

        lastFrameStartNanos = nowNanos;
        synchronousBuildsThisFrame = 0;
    }

    private static void updatePacingState(long intervalNanos) {
        switch (pacingState) {
            case FAST -> {
                if (intervalNanos >= FAST_EXIT_NANOS) {
                    pacingState = intervalNanos >= SLOW_ENTER_NANOS
                            ? PacingState.SLOW : PacingState.NORMAL;
                }
            }
            case SLOW -> {
                if (intervalNanos <= SLOW_EXIT_NANOS) {
                    pacingState = intervalNanos <= FAST_ENTER_NANOS
                            ? PacingState.FAST : PacingState.NORMAL;
                }
            }
            case NORMAL -> {
                if (intervalNanos <= FAST_ENTER_NANOS) {
                    pacingState = PacingState.FAST;
                } else if (intervalNanos >= SLOW_ENTER_NANOS) {
                    pacingState = PacingState.SLOW;
                }
            }
        }
    }

    public static boolean reserveSynchronousBuild() {
        if (!enabled) {
            return true;
        }
        if (MemoryPressureMonitor.isCritical()) {
            return false;
        }

        int effectiveBudget = MemoryPressureMonitor.isElevated()
                ? Math.min(synchronousBudget, 1)
                : synchronousBudget;
        if (synchronousBuildsThisFrame >= effectiveBudget) {
            return false;
        }

        synchronousBuildsThisFrame++;
        return true;
    }

    /**
     * Translucent resorting is maintenance work: if the smoothed pacing signal
     * is especially slow, leave Minecraft's round-robin cursor untouched and
     * let it resume the pending slice later.
     */
    public static boolean shouldDeferTranslucencyResort() {
        return enabled && (smoothedFrameIntervalNanos >= TRANSLUCENCY_DEFER_FRAME_NANOS
                || MemoryPressureMonitor.isCritical());
    }
}
