package dev.radeonium.client.chunk;

/**
 * Limits how many chunk sections may be rebuilt synchronously during one
 * LevelRenderer.compileSections call.
 *
 * Synchronous rebuilds run on the render thread and can create frame-time
 * spikes when many nearby sections become dirty together. Excess work is
 * sent through Minecraft's existing asynchronous section task queue instead.
 */
public final class ChunkBuildBudget {
    /**
     * Keep one synchronous rebuild for urgent nearby/player changes, while
     * moving additional work to the existing worker queue.
     */
    public static final int MAX_SYNCHRONOUS_BUILDS_PER_FRAME = 1;

    private static int synchronousBuildsThisFrame;

    private ChunkBuildBudget() {
    }

    public static void beginFrame() {
        synchronousBuildsThisFrame = 0;
    }

    public static boolean reserveSynchronousBuild() {
        if (synchronousBuildsThisFrame >= MAX_SYNCHRONOUS_BUILDS_PER_FRAME) {
            return false;
        }

        synchronousBuildsThisFrame++;
        return true;
    }
}
