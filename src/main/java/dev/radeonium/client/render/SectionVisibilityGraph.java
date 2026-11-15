package dev.radeonium.client.render;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Section portal graph populated from Minecraft's compiled VisibilitySet.
 * Connectivity is directional by entry/exit face pair, not a guessed
 * six-bit "open face" approximation.
 */
public final class SectionVisibilityGraph {
    private record Visit(SectionPos section, SectionFace entryFace) {}

    private final Map<SectionPos, Long> connections = new HashMap<>();

    private static int bit(SectionFace entry, SectionFace exit) {
        return entry.ordinal() * 6 + exit.ordinal();
    }

    /** Stores the exact 6x6 face-connectivity matrix for a compiled section. */
    public synchronized void putConnections(SectionPos position, long connectionMask) {
        if (position == null) throw new IllegalArgumentException("position cannot be null");
        connections.put(position, connectionMask & ((1L << 36) - 1L));
    }

    public synchronized boolean containsAll(Collection<SectionPos> sections) {
        return sections != null && connections.keySet().containsAll(sections);
    }

    public synchronized void remove(SectionPos position) {
        connections.remove(position);
    }

    public synchronized void clear() {
        connections.clear();
    }

    public synchronized int sectionCount() {
        return connections.size();
    }

    /**
     * Traverses exact portal connectivity. A camera starts inside its section,
     * so its first exit may use any face; subsequent exits must be connected
     * to the face through which the traversal entered the section.
     */
    public synchronized List<SectionPos> collectVisible(SectionPos cameraSection) {
        if (!connections.containsKey(cameraSection)) return List.of();

        ArrayDeque<Visit> pending = new ArrayDeque<>();
        Set<Visit> visited = new HashSet<>();
        LinkedHashSet<SectionPos> visible = new LinkedHashSet<>();
        Visit start = new Visit(cameraSection, null);
        pending.add(start);
        visited.add(start);

        while (!pending.isEmpty()) {
            Visit visit = pending.removeFirst();
            SectionPos current = visit.section();
            visible.add(current);
            long mask = connections.getOrDefault(current, 0L);

            for (SectionFace exit : SectionFace.values()) {
                if (visit.entryFace() != null
                        && (mask & (1L << bit(visit.entryFace(), exit))) == 0L) {
                    continue;
                }

                SectionPos neighbor = current.offset(exit);
                if (!connections.containsKey(neighbor)) continue;
                Visit next = new Visit(neighbor, exit.opposite());
                if (visited.add(next)) pending.addLast(next);
            }
        }

        return List.copyOf(visible);
    }
}
