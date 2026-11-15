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

    private MinecraftSectionModelCaptureStore() {}

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
        SNAPSHOTS.clear();
        retainedQuads = 0;
    }

    public static synchronized int sectionCount() {
        return SNAPSHOTS.size();
    }

    public static synchronized int retainedQuadCount() {
        return retainedQuads;
    }

    public record Snapshot(
            SectionPos position,
            MinecraftSectionModelMesher.SectionModelMesh mesh,
            int quadCount,
            long capturedAtNanos
    ) {}
}
