package dev.radeonium.client.render;

import net.minecraft.core.SectionPos;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded CPU cache of Radeonium meshes built from actual Minecraft section
 * region inputs. This cache is an extraction milestone only; vanilla remains
 * the submitted terrain until parity and GPU upload ownership are complete.
 */
public final class MinecraftSectionModelCaptureStore {
    private static final int MAX_SECTIONS = 64;
    private static final int MAX_RETAINED_QUADS = 250_000;

    private static final LinkedHashMap<SectionPos, Snapshot> SNAPSHOTS =
            new LinkedHashMap<>(32, 0.75f, true);
    private static int retainedQuads;
    private static volatile long epoch;
    private static final ThreadLocal<LinkedHashMap<SectionPos, PendingCapture>> PENDING =
            ThreadLocal.withInitial(() -> new LinkedHashMap<>(16, 0.75f, true));
    private static final ThreadLocal<LinkedHashMap<SectionPos, Long>> ACTIVE_CAPTURES =
            ThreadLocal.withInitial(() -> new LinkedHashMap<>(16, 0.75f, true));

    private MinecraftSectionModelCaptureStore() {}

    /**
     * The duplicate CPU model pass is deliberately opt-in until visual parity
     * and its frame-time cost have been measured in-game.
     */
    public static boolean captureEnabled() {
        return Boolean.getBoolean("radeonium.captureModelMeshes");
    }

    /** Capture the reload epoch before reading models from the compile region. */
    public static long beginCapture(SectionPos position) {
        if (position == null) return -1L;
        long captureEpoch = epoch;
        LinkedHashMap<SectionPos, Long> active = ACTIVE_CAPTURES.get();
        active.remove(position);
        active.put(position, captureEpoch);
        while (active.size() > MAX_SECTIONS) {
            active.remove(active.keySet().iterator().next());
        }
        return captureEpoch;
    }

    /** Stage on the compiler worker; publish only after vanilla compile succeeds. */
    public static void stage(
            SectionPos position, MinecraftSectionModelMesher.SectionModelMesh mesh
    ) {
        if (position == null || mesh == null) return;
        Long captureEpoch = ACTIVE_CAPTURES.get().remove(position);
        if (captureEpoch == null || captureEpoch != epoch) return;
        LinkedHashMap<SectionPos, PendingCapture> pending = PENDING.get();
        pending.remove(position);
        pending.put(position, new PendingCapture(mesh, captureEpoch));
        while (pending.size() > MAX_SECTIONS) {
            pending.remove(pending.keySet().iterator().next());
        }
    }

    /** Commit the worker's staged mesh at SectionCompiler RETURN. */
    public static synchronized Snapshot complete(SectionPos position) {
        if (position == null) return null;
        PendingCapture pending = PENDING.get().remove(position);
        if (pending == null || pending.epoch() != epoch) return null;
        return put(position, pending.mesh());
    }

    public static void abort(SectionPos position) {
        if (position == null) return;
        ACTIVE_CAPTURES.get().remove(position);
        PENDING.get().remove(position);
    }

    public static synchronized Snapshot put(
            SectionPos position, MinecraftSectionModelMesher.SectionModelMesh mesh
    ) {
        if (position == null || mesh == null) return null;
        int quadCount = mesh.quadCount();
        Snapshot previous = SNAPSHOTS.remove(position);
        if (previous != null) retainedQuads -= previous.quadCount();
        if (quadCount > MAX_RETAINED_QUADS) return null;

        while (!SNAPSHOTS.isEmpty()
                && (SNAPSHOTS.size() >= MAX_SECTIONS
                || retainedQuads + quadCount > MAX_RETAINED_QUADS)) {
            Map.Entry<SectionPos, Snapshot> oldest =
                    SNAPSHOTS.entrySet().iterator().next();
            retainedQuads -= oldest.getValue().quadCount();
            SNAPSHOTS.remove(oldest.getKey());
        }

        Snapshot snapshot = new Snapshot(position, mesh, quadCount, System.nanoTime());
        SNAPSHOTS.put(position, snapshot);
        retainedQuads += quadCount;
        return snapshot;
    }

    public static synchronized Snapshot get(SectionPos position) {
        return SNAPSHOTS.get(position);
    }

    public static synchronized void remove(SectionPos position) {
        Snapshot removed = SNAPSHOTS.remove(position);
        if (removed != null) retainedQuads -= removed.quadCount();
    }

    public static synchronized void clear() {
        epoch++;
        SNAPSHOTS.clear();
        retainedQuads = 0;
        PENDING.remove();
        ACTIVE_CAPTURES.remove();
    }

    public static synchronized int sectionCount() {
        return SNAPSHOTS.size();
    }

    public static synchronized int retainedQuadCount() {
        return retainedQuads;
    }

    private record PendingCapture(
            MinecraftSectionModelMesher.SectionModelMesh mesh, long epoch
    ) {}

    public record Snapshot(
            SectionPos position,
            MinecraftSectionModelMesher.SectionModelMesh mesh,
            int quadCount,
            long capturedAtNanos
    ) {}
}
