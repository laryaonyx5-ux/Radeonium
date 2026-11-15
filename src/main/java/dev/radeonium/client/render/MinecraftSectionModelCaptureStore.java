package dev.radeonium.client.render;

import net.minecraft.core.SectionPos;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded CPU cache of Radeonium meshes built from actual Minecraft section
 * region inputs. This cache is an extraction milestone only; vanilla remains
 * the submitted terrain until parity and GPU upload ownership are complete.
 */
public final class MinecraftSectionModelCaptureStore {
    private static final int MAX_SECTIONS = 64;
    private static final int MAX_RETAINED_QUADS = 250_000;
    private static final int MAX_ACTIVE_CAPTURES = 256;

    private static final LinkedHashMap<SectionPos, Snapshot> SNAPSHOTS =
            new LinkedHashMap<>(32, 0.75f, true);
    private static int retainedQuads;
    private static long epoch;
    private static long nextGeneration;
    private static final LinkedHashMap<SectionPos, Long> CAPTURE_GENERATIONS =
            new LinkedHashMap<>(32, 0.75f, true);
    private static final ThreadLocal<LinkedHashMap<SectionPos, PendingCapture>> PENDING =
            ThreadLocal.withInitial(() -> new LinkedHashMap<>(16, 0.75f, true));
    private static final ThreadLocal<LinkedHashMap<SectionPos, CaptureToken>> ACTIVE_CAPTURES =
            ThreadLocal.withInitial(() -> new LinkedHashMap<>(16, 0.75f, true));

    private MinecraftSectionModelCaptureStore() {}

    /**
     * The duplicate CPU model pass is deliberately opt-in until visual parity
     * and its frame-time cost have been measured in-game.
     */
    public static boolean captureEnabled() {
        return Boolean.getBoolean("radeonium.captureModelMeshes");
    }

    /** Ticket for a mesh job that completes after the SectionCompiler invocation returns. */
    public record CaptureTicket(SectionPos position, long epoch, long generation) {}

    public static synchronized CaptureTicket beginAsyncCapture(SectionPos position) {
        if (position == null) return null;
        long generation = ++nextGeneration;
        CAPTURE_GENERATIONS.put(position, generation);
        while (CAPTURE_GENERATIONS.size() > MAX_ACTIVE_CAPTURES) {
            CAPTURE_GENERATIONS.remove(CAPTURE_GENERATIONS.keySet().iterator().next());
        }
        return new CaptureTicket(position, epoch, generation);
    }

    public static synchronized Snapshot publishAsync(
            CaptureTicket ticket, MinecraftSectionModelMesher.SectionModelMesh mesh
    ) {
        if (ticket == null || mesh == null || ticket.epoch() != epoch
                || !Objects.equals(CAPTURE_GENERATIONS.get(ticket.position()), ticket.generation())) return null;
        CAPTURE_GENERATIONS.remove(ticket.position());
        return put(ticket.position(), mesh);
    }

    public static synchronized void abortAsync(CaptureTicket ticket) {
        if (ticket != null && ticket.epoch() == epoch
                && Objects.equals(CAPTURE_GENERATIONS.get(ticket.position()), ticket.generation())) {
            CAPTURE_GENERATIONS.remove(ticket.position());
        }
    }

    /**
     * Starts a generation before reading models. A newer compile for the same
     * section invalidates older workers even if they finish later.
     */
    public static synchronized long beginCapture(SectionPos position) {
        if (position == null) return -1L;
        long generation = ++nextGeneration;
        CAPTURE_GENERATIONS.put(position, generation);
        while (CAPTURE_GENERATIONS.size() > MAX_ACTIVE_CAPTURES) {
            CAPTURE_GENERATIONS.remove(CAPTURE_GENERATIONS.keySet().iterator().next());
        }

        LinkedHashMap<SectionPos, CaptureToken> active = ACTIVE_CAPTURES.get();
        active.remove(position);
        active.put(position, new CaptureToken(epoch, generation));
        while (active.size() > MAX_SECTIONS) {
            active.remove(active.keySet().iterator().next());
        }
        return generation;
    }

    /** Stage on the compiler worker; publish only after vanilla compile succeeds. */
    public static synchronized void stage(
            SectionPos position, MinecraftSectionModelMesher.SectionModelMesh mesh
    ) {
        if (position == null || mesh == null) return;
        CaptureToken token = ACTIVE_CAPTURES.get().remove(position);
        if (token == null || token.epoch() != epoch
                || !Objects.equals(CAPTURE_GENERATIONS.get(position), token.generation())) {
            return;
        }

        LinkedHashMap<SectionPos, PendingCapture> pending = PENDING.get();
        pending.remove(position);
        pending.put(position, new PendingCapture(mesh, token.epoch(), token.generation()));
        while (pending.size() > MAX_SECTIONS) {
            pending.remove(pending.keySet().iterator().next());
        }
    }

    /** Commit only the newest staged mesh at SectionCompiler RETURN. */
    public static synchronized Snapshot complete(SectionPos position) {
        if (position == null) return null;
        PendingCapture pending = PENDING.get().remove(position);
        if (pending == null || pending.epoch() != epoch
                || !Objects.equals(CAPTURE_GENERATIONS.get(position), pending.generation())) {
            return null;
        }
        CAPTURE_GENERATIONS.remove(position);
        return put(position, pending.mesh());
    }

    public static synchronized void abort(SectionPos position) {
        if (position == null) return;
        CaptureToken active = ACTIVE_CAPTURES.get().remove(position);
        PendingCapture pending = PENDING.get().remove(position);
        long generation = active != null ? active.generation()
                : pending != null ? pending.generation() : -1L;
        if (generation >= 0 && Objects.equals(CAPTURE_GENERATIONS.get(position), generation)) {
            CAPTURE_GENERATIONS.remove(position);
        }
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
        if (position == null) return;
        CAPTURE_GENERATIONS.remove(position);
        Snapshot removed = SNAPSHOTS.remove(position);
        if (removed != null) retainedQuads -= removed.quadCount();
    }

    public static synchronized void clear() {
        epoch++;
        CAPTURE_GENERATIONS.clear();
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

    private record CaptureToken(long epoch, long generation) {}

    private record PendingCapture(
            MinecraftSectionModelMesher.SectionModelMesh mesh, long epoch, long generation
    ) {}

    public record Snapshot(
            SectionPos position,
            MinecraftSectionModelMesher.SectionModelMesh mesh,
            int quadCount,
            long capturedAtNanos
    ) {}
}
