package dev.radeonium.client.chunk;

import dev.radeonium.client.memory.MemoryPressureMonitor;

/**
 * Adaptive budget for render-thread chunk section compilation.
 *
 * Synchronous builds are only allowed when recent frame pacing is healthy.
 * Their measured cost then adapts the per-frame count ceiling, while all
 * deferred builds use Minecraft's own asynchronous task pipeline.
 */
public final class ChunkBuildBudget {
    private static final long FAST_ENTER_NANOS = 11_000_000L;
    private static final long FAST_EXIT_NANOS = 13_000_000L;
    private static final long SLOW_EXIT_NANOS = 18_000_000L;
    private static final long SLOW_ENTER_NANOS = 22_000_000L;
    private static final long TRANSLUCENCY_DEFER_FRAME_NANOS = 25_000_000L;
    private static final long MAX_PACING_SAMPLE_NANOS = 100_000_000L;

    // If a synchronous rebuild is expensive, spend less render-thread time on
    // further rebuilds. These are adaptation thresholds, not hard timeouts.
    private static final long CHEAP_BUILD_NANOS = 750_000L;
    private static final long MODERATE_BUILD_NANOS = 2_000_000L;
    private static final int MAX_DEFERRED_FRAMES = 3;
    private static final int MAX_SYNCHRONOUS_BUILDS = CpuWorkProfile.maxSynchronousChunkBuilds();

    private enum PacingState {
        FAST,
        NORMAL,
        SLOW
    }

    private static volatile boolean enabled = true;
    private static long lastFrameStartNanos;
    private static long smoothedFrameIntervalNanos;
    private static long smoothedSynchronousBuildNanos;
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
        lastFrameStartNanos = 0L;
        smoothedFrameIntervalNanos = 0L;
        smoothedSynchronousBuildNanos = 0L;
        synchronousBuildsThisFrame = 0;
        consecutiveFramesWithoutSync = 0;
        pacingState = PacingState.NORMAL;
        synchronousBudget = value ? 1 : Integer.MAX_VALUE;
    }

    /**
     * Called at the start of the section-compilation phase. This interval is
     * a pacing proxy rather than a guaranteed measurement of every rendered
     * frame; smoothing and hysteresis keep it from reacting to one spike.
     */
    public static void beginFrame(long nowNanos) {
        if (!enabled) {
            lastFrameStartNanos = nowNanos;
            return;
        }

        if (lastFrameStartNanos != 0L) {
            long sample = Math.max(0L, nowNanos - lastFrameStartNanos);
            sample = Math.min(sample, MAX_PACING_SAMPLE_NANOS);
            smoothedFrameIntervalNanos = smooth(smoothedFrameIntervalNanos, sample);
            updatePacingState(smoothedFrameIntervalNanos);

            if (synchronousBuildsThisFrame == 0 && synchronousBudget == 0) {
                consecutiveFramesWithoutSync++;
            } else {
                consecutiveFramesWithoutSync = 0;
            }

            synchronousBudget = calculateBudget();
            // Avoid indefinitely postponing urgent terrain changes.
            if (synchronousBudget == 0
                    && consecutiveFramesWithoutSync >= MAX_DEFERRED_FRAMES
                    && !MemoryPressureMonitor.isCritical()) {
                synchronousBudget = 1;
            }
        } else {
            smoothedFrameIntervalNanos = 0L;
            synchronousBudget = 1;
        }

        lastFrameStartNanos = nowNanos;
        synchronousBuildsThisFrame = 0;
    }

    private static long smooth(long previous, long sample) {
        return previous == 0L ? sample : (previous * 3L + sample) / 4L;
    }

    private static int calculateBudget() {
        if (MemoryPressureMonitor.isCritical()) {
            return 0;
        }
        if (pacingState == PacingState.SLOW) {
            return 0;
        }
        if (pacingState == PacingState.NORMAL) {
            return 1;
        }

        int budget = MAX_SYNCHRONOUS_BUILDS;
        if (MemoryPressureMonitor.isElevated()) {
            budget = Math.min(budget, 1);
        }

        long buildCost = smoothedSynchronousBuildNanos;
        if (buildCost >= MODERATE_BUILD_NANOS) {
            budget = Math.min(budget, 1);
        } else if (buildCost >= CHEAP_BUILD_NANOS) {
            budget = Math.min(budget, 2);
        }
        return budget;
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
     * Feeds the real duration of a synchronous section build back into the
     * scheduler. No allocation or logging occurs on this hot path.
     */
    public static void recordSynchronousBuild(long elapsedNanos) {
        if (elapsedNanos <= 0L) {
            return;
        }
        smoothedSynchronousBuildNanos = smooth(
                smoothedSynchronousBuildNanos,
                Math.min(elapsedNanos, MAX_PACING_SAMPLE_NANOS)
        );
    }

    /**
     * Translucent resorting is maintenance work. During sustained slow pacing
     * its round-robin cursor is left untouched so Minecraft can resume later.
     */
    public static boolean shouldDeferTranslucencyResort() {
        return enabled && (smoothedFrameIntervalNanos >= TRANSLUCENCY_DEFER_FRAME_NANOS
                || MemoryPressureMonitor.isCritical());
    }
}
