package dev.radeonium.client.memory;

/**
 * Samples Java heap pressure at a low frequency and exposes a small,
 * allocation-free state snapshot for Radeonium's scheduling hot paths.
 *
 * This intentionally monitors JVM heap only. It does not claim to measure
 * dedicated GPU/VRAM usage or native graphics allocations.
 */
public final class MemoryPressureMonitor {
    public enum Pressure {
        NORMAL,
        ELEVATED,
        CRITICAL
    }

    private static final int SAMPLE_INTERVAL_TICKS = 20;
    private static final double ELEVATED_ENTER = 0.85;
    private static final double ELEVATED_EXIT = 0.78;
    private static final double CRITICAL_ENTER = 0.93;
    private static final double CRITICAL_EXIT = 0.88;

    private static volatile Pressure pressure = Pressure.NORMAL;
    private static int ticksUntilSample;

    private MemoryPressureMonitor() {
    }

    /**
     * Call from the client tick. Heap measurements are performed once per
     * second at 20 TPS rather than on every render-frame callback.
     *
     * @return true only when the pressure state changes
     */
    public static boolean tickAndSample() {
        if (++ticksUntilSample < SAMPLE_INTERVAL_TICKS) {
            return false;
        }
        ticksUntilSample = 0;

        Runtime runtime = Runtime.getRuntime();
        long maxHeap = runtime.maxMemory();
        if (maxHeap <= 0L) {
            return false;
        }

        long usedHeap = runtime.totalMemory() - runtime.freeMemory();
        double usage = Math.min(1.0, Math.max(0.0, (double) usedHeap / (double) maxHeap));
        Pressure previous = pressure;
        pressure = nextPressure(previous, usage);
        return previous != pressure;
    }

    static Pressure nextPressure(Pressure current, double usage) {
        return switch (current) {
            case NORMAL -> usage >= CRITICAL_ENTER ? Pressure.CRITICAL
                    : usage >= ELEVATED_ENTER ? Pressure.ELEVATED : Pressure.NORMAL;
            case ELEVATED -> usage >= CRITICAL_ENTER ? Pressure.CRITICAL
                    : usage <= ELEVATED_EXIT ? Pressure.NORMAL : Pressure.ELEVATED;
            case CRITICAL -> usage <= ELEVATED_EXIT ? Pressure.NORMAL
                    : usage <= CRITICAL_EXIT ? Pressure.ELEVATED : Pressure.CRITICAL;
        };
    }

    public static Pressure current() {
        return pressure;
    }

    public static boolean isElevated() {
        return pressure != Pressure.NORMAL;
    }

    public static boolean isCritical() {
        return pressure == Pressure.CRITICAL;
    }
}
