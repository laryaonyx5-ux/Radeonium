package dev.radeonium.client.render;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SectionVisibilityGraphTest {
    @Test
    void traversalUsesTheActualEntryToExitFacePair() {
        SectionVisibilityGraph graph = new SectionVisibilityGraph();
        SectionPos camera = new SectionPos(0, 0, 0);
        SectionPos middle = new SectionPos(1, 0, 0);
        SectionPos beyond = new SectionPos(2, 0, 0);

        // The camera can start toward any face. The middle section is entered
        // from WEST, but its only connection is NORTH-to-SOUTH, so EAST is blocked.
        graph.putConnections(camera, 0L);
        graph.putConnections(middle,
                1L << (SectionFace.NORTH.ordinal() * 6 + SectionFace.SOUTH.ordinal()));
        graph.putConnections(beyond, (1L << 36) - 1L);

        assertEquals(Set.of(camera, middle), Set.copyOf(graph.collectVisible(camera)));
    }

    @Test
    void cameraSectionMayExitWithoutAnEntryFace() {
        SectionVisibilityGraph graph = new SectionVisibilityGraph();
        SectionPos camera = new SectionPos(0, 0, 0);
        SectionPos east = new SectionPos(1, 0, 0);
        graph.putConnections(camera, 0L);
        graph.putConnections(east, 0L);

        assertEquals(Set.of(camera, east), Set.copyOf(graph.collectVisible(camera)));
    }

    @Test
    void unknownSectionsDoNotPretendToHaveVisibilityData() {
        SectionVisibilityGraph graph = new SectionVisibilityGraph();
        SectionPos camera = new SectionPos(0, 0, 0);
        SectionPos neighbor = new SectionPos(1, 0, 0);
        graph.putConnections(camera, (1L << 36) - 1L);

        assertFalse(graph.containsAll(Set.of(camera, neighbor)));
        assertTrue(graph.collectVisible(camera).contains(camera));
        assertFalse(graph.collectVisible(camera).contains(neighbor));
    }
}
