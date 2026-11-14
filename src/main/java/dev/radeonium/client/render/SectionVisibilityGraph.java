package dev.radeonium.client.render;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Section-level portal traversal graph.
 *
 * A connection is traversable only when the source section exposes the
 * outgoing face and the neighboring section exposes the opposite face.
 * Face masks are supplied by the meshing/occlusion stage; this class does
 * not guess visibility from chunk coordinates.
 */
public final class SectionVisibilityGraph {
    private final Map<SectionPos, Integer> openFaces = new HashMap<>();

    public synchronized void put(SectionPos position, int openFaceMask) {
        openFaces.put(position, openFaceMask & 0x3f);
    }

    public synchronized void remove(SectionPos position) {
        openFaces.remove(position);
    }

    public synchronized void clear() {
        openFaces.clear();
    }

    public synchronized int sectionCount() {
        return openFaces.size();
    }

    /**
     * Returns sections reachable from the camera section through mutually
     * open section faces. The result is a snapshot and can be consumed
     * without holding this graph's monitor.
     */
    public synchronized List<SectionPos> collectVisible(SectionPos cameraSection) {
        if (!openFaces.containsKey(cameraSection)) {
            return List.of();
        }

        ArrayDeque<SectionPos> pending = new ArrayDeque<>();
        Set<SectionPos> visited = new HashSet<>();
        List<SectionPos> visible = new ArrayList<>();

        pending.add(cameraSection);
        visited.add(cameraSection);

        while (!pending.isEmpty()) {
            SectionPos current = pending.removeFirst();
            visible.add(current);
            int currentMask = openFaces.getOrDefault(current, 0);

            for (SectionFace face : SectionFace.values()) {
                if ((currentMask & face.bit()) == 0) {
                    continue;
                }

                SectionPos neighbor = current.offset(face);
                Integer neighborMask = openFaces.get(neighbor);
                if (neighborMask == null
                        || (neighborMask & face.opposite().bit()) == 0
                        || !visited.add(neighbor)) {
                    continue;
                }

                pending.addLast(neighbor);
            }
        }

        return List.copyOf(visible);
    }
}
