package dev.radeonium.client.render;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RendererSectionTrackerTest {
    @Test
    void dirtySectionInvalidatesItselfAndAllSixNeighbors() {
        var affected = RendererSectionTracker.affectedSectionsForDirty(4, -2, 9);

        assertEquals(7, affected.size());
        assertEquals(7, new HashSet<>(affected).size());
        assertTrue(affected.contains(new SectionPos(4, -2, 9)));
        assertTrue(affected.contains(new SectionPos(4, -3, 9)));
        assertTrue(affected.contains(new SectionPos(4, -1, 9)));
        assertTrue(affected.contains(new SectionPos(4, -2, 8)));
        assertTrue(affected.contains(new SectionPos(4, -2, 10)));
        assertTrue(affected.contains(new SectionPos(3, -2, 9)));
        assertTrue(affected.contains(new SectionPos(5, -2, 9)));
    }
}
