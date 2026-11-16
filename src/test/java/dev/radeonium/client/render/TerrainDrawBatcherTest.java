package dev.radeonium.client.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TerrainDrawBatcherTest {
    private record Draw(String name, Object buffer) {}

    @Test
    void groupsSameBufferByIdentityAndPreservesWithinGroupOrder() {
        Object a = new Object();
        Object b = new Object();
        Draw a1 = new Draw("a1", a);
        Draw b1 = new Draw("b1", b);
        Draw a2 = new Draw("a2", a);
        Draw b2 = new Draw("b2", b);

        List<Draw> grouped = TerrainDrawBatcher.groupByVertexBuffer(
                List.of(a1, b1, a2, b2), Draw::buffer);

        assertEquals(List.of(a1, a2, b1, b2), grouped);
    }

    @Test
    void distinctButEqualBuffersAreNotMerged() {
        Object a = new String("same");
        Object b = new String("same");
        Draw first = new Draw("first", a);
        Draw second = new Draw("second", b);

        assertEquals(List.of(first, second),
                TerrainDrawBatcher.groupByVertexBuffer(List.of(first, second), Draw::buffer));
    }

    @Test
    void keepsNullBufferDrawsWithoutInventingAnIdentity() {
        Object a = new Object();
        Draw first = new Draw("first", null);
        Draw second = new Draw("second", a);
        Draw third = new Draw("third", null);

        assertEquals(List.of(second, first, third),
                TerrainDrawBatcher.groupByVertexBuffer(List.of(first, second, third), Draw::buffer));
    }

    @Test
    void rejectsMissingArgumentsAndNullEntries() {
        assertThrows(IllegalArgumentException.class,
                () -> TerrainDrawBatcher.groupByVertexBuffer(null, Draw::buffer));
        assertThrows(IllegalArgumentException.class,
                () -> TerrainDrawBatcher.groupByVertexBuffer(List.of(), null));
        assertThrows(IllegalArgumentException.class,
                () -> TerrainDrawBatcher.groupByVertexBuffer(java.util.Arrays.asList((Draw) null), Draw::buffer));
    }
}
