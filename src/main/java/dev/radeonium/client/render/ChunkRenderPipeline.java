package dev.radeonium.client.render;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Renderer-owned CPU state for section lifetime, dirty scheduling,
 * visibility topology, and region grouping.
 *
 * This is the first internal pipeline layer. It does not submit GPU commands
 * yet; backend upload and draw ownership require the target RenderPearl APIs
 * and the exact vanilla integration seams to be mapped first.
 */
public final class ChunkRenderPipeline {
    private final Map<SectionPos, SectionState> sections = new HashMap<>();
    private final Map<SectionPos.RegionPos, RenderRegion> regions = new HashMap<>();
    private final ArrayDeque<SectionPos> dirtyQueue = new ArrayDeque<>();
    private final Set<SectionPos> queuedDirty = new HashSet<>();
    private final SectionVisibilityGraph visibility = new SectionVisibilityGraph();
    private final Set<SectionPos> visibleSections = new HashSet<>();

    /**
     * Reconciles the currently visible Minecraft sections with Radeonium's
     * CPU-side lifetime tracker. Visibility here mirrors vanilla's frustum /
     * occlusion result; it is not a second occlusion decision.
     */
    public synchronized void syncVisibleSections(Iterable<SectionPos> current) {
        Set<SectionPos> next = new HashSet<>();
        for (SectionPos position : current) next.add(position);

        for (SectionPos previous : new HashSet<>(visibleSections)) {
            if (!next.contains(previous)) removeSection(previous);
        }

        for (SectionPos position : next) {
            if (visibleSections.add(position)) {
                sections.putIfAbsent(position, new SectionState());
                visibility.put(position, 0);
                markDirtyInternal(position);
            }
        }
    }

    public synchronized int visibleSectionCount() {
        return visibleSections.size();
    }

    public synchronized void addSection(SectionPos position, int openFaceMask) {
        SectionState previous = sections.putIfAbsent(position, new SectionState());
        visibility.put(position, openFaceMask);
        if (previous == null) markDirtyInternal(position);
    }

    public synchronized void removeSection(SectionPos position) {
        sections.remove(position);
        visibleSections.remove(position);
        visibility.remove(position);
        queuedDirty.remove(position);
        dirtyQueue.removeIf(position::equals);

        RenderRegion region = regions.get(position.region());
        if (region != null) {
            region.remove(position.regionIndex());
            if (region.isEmpty()) regions.remove(position.region());
        }
    }

    public synchronized void updateOpenFaces(SectionPos position, int openFaceMask) {
        if (sections.containsKey(position)) visibility.put(position, openFaceMask);
    }

    public synchronized void markDirty(SectionPos position) {
        if (sections.containsKey(position)) markDirtyInternal(position);
    }

    private void markDirtyInternal(SectionPos position) {
        SectionState state = sections.get(position);
        if (state != null) {
            state.generation++;
            state.dirty = true;
            if (queuedDirty.add(position)) dirtyQueue.addLast(position);
        }
    }

    /** Removes at most maxSections dirty entries for the meshing workers. */
    public synchronized SectionPos[] drainDirtySections(int maxSections) {
        if (maxSections <= 0 || dirtyQueue.isEmpty()) return new SectionPos[0];

        int count = Math.min(maxSections, dirtyQueue.size());
        SectionPos[] result = new SectionPos[count];
        for (int i = 0; i < count; i++) {
            SectionPos position = dirtyQueue.removeFirst();
            queuedDirty.remove(position);
            result[i] = position;
        }
        return result;
    }

    /**
     * Returns the current generation token for a section build.
     *
     * A caller must capture this value before starting asynchronous meshing and
     * pass it to the generation-aware installMesh overload when the result is
     * ready. Any invalidation or removal makes the old token unusable.
     */
    public synchronized long beginMeshBuild(SectionPos position) {
        SectionState state = sections.get(position);
        return state == null ? -1L : state.generation;
    }

    /**
     * Installs a completed CPU mesh only if no newer invalidation has occurred
     * since the caller captured its generation token.
     */
    public synchronized boolean installMesh(
            SectionPos position, long expectedGeneration, SectionMesh mesh
    ) {
        SectionState state = sections.get(position);
        if (state == null || expectedGeneration < 0L
                || state.generation != expectedGeneration) {
            return false;
        }

        RenderRegion region = regions.computeIfAbsent(position.region(), RenderRegion::new);
        region.put(position.regionIndex(), mesh);
        state.dirty = false;
        state.hasMesh = mesh != null && !mesh.isEmpty();
        return true;
    }

    /**
     * Legacy convenience for synchronous callers. Asynchronous callers must
     * use the token-aware overload to prevent stale mesh installation.
     */
    public synchronized boolean installMesh(SectionPos position, SectionMesh mesh) {
        long generation = beginMeshBuild(position);
        return installMesh(position, generation, mesh);
    }

    public SectionVisibilityGraph visibilityGraph() { return visibility; }

    public synchronized RenderRegion region(SectionPos.RegionPos position) {
        return regions.get(position);
    }

    public synchronized int sectionCount() { return sections.size(); }
    public synchronized int dirtySectionCount() { return dirtyQueue.size(); }

    public synchronized long residentCpuMeshBytes() {
        long total = 0L;
        for (RenderRegion region : regions.values()) total += region.totalMeshBytes();
        return total;
    }

    private static final class SectionState {
        private boolean dirty = true;
        private boolean hasMesh;
        private long generation = 1L;
    }
}
