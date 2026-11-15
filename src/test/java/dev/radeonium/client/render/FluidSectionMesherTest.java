package dev.radeonium.client.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FluidSectionMesherTest {
    @Test
    void emitsFiveFacesForAnIsolatedFluidCell() {
        short[] ids = new short[4096]; byte[] heights = new byte[4096];
        int[] tints = new int[4096], lights = new int[4096];
        int index = 1 + 16 * (1 + 16 * 1);
        ids[index] = 2; heights[index] = 8; tints[index] = 0xAABBCC; lights[index] = 0x00F000F0;
        var mesh = new FluidSectionMesher().build(new FluidSectionSnapshot(ids, heights, tints, lights));
        assertEquals(5, mesh.quadCount());
        assertEquals(5, mesh.quadCount((short) 2));
        assertEquals(0, mesh.quadCount((short) 3));
    }

    @Test
    void removesSharedSidesBetweenSameFluidCells() {
        short[] ids = new short[4096]; byte[] heights = new byte[4096];
        int[] tints = new int[4096], lights = new int[4096];
        int a = 1 + 16 * (1 + 16 * 1), b = 2 + 16 * (1 + 16 * 1);
        ids[a] = ids[b] = 1; heights[a] = heights[b] = 8;
        var mesh = new FluidSectionMesher().build(new FluidSectionSnapshot(ids, heights, tints, lights));
        assertEquals(8, mesh.quadCount());
    }

    @Test
    void emitsOnlyTheExposedPartWhenNeighborFluidIsLower() {
        short[] ids = new short[4096]; byte[] heights = new byte[4096];
        int[] tints = new int[4096], lights = new int[4096];
        int a = 1 + 16 * (1 + 16 * 1), b = 2 + 16 * (1 + 16 * 1);
        ids[a] = ids[b] = 1; heights[a] = 8; heights[b] = 4;
        var mesh = new FluidSectionMesher().build(new FluidSectionSnapshot(ids, heights, tints, lights));
        assertEquals(9, mesh.quadCount());
    }

    @Test
    void interpolatesTopSurfaceAcrossUnequalSameFluidNeighbors() {
        short[] ids = new short[4096]; byte[] heights = new byte[4096];
        int[] tints = new int[4096], lights = new int[4096];
        int low = 1 + 16 * (1 + 16 * 1);
        int high = 2 + 16 * (1 + 16 * 1);
        ids[low] = ids[high] = 4;
        heights[low] = 4;
        heights[high] = 8;

        var mesh = new FluidSectionMesher().build(new FluidSectionSnapshot(ids, heights, tints, lights));
        var top = mesh.quads().stream().filter(q -> q.normalY() == 1.0f).findFirst().orElseThrow();
        float[] positions = top.positions();
        assertNotEquals(positions[1], positions[4],
                "the top surface should slope between unequal neighboring fluid heights");
    }

    @Test
    void fluidAboveAnyCellSharingCornerForcesFullCornerHeight() {
        short[] ids = new short[4096]; byte[] heights = new byte[4096];
        int[] tints = new int[4096], lights = new int[4096];
        int lower = 1 + 16 * (1 + 16 * 1);
        int neighbor = 2 + 16 * (1 + 16 * 1);
        int aboveNeighbor = 2 + 16 * (1 + 16 * 2);
        ids[lower] = ids[neighbor] = ids[aboveNeighbor] = 7;
        heights[lower] = heights[neighbor] = 4;
        heights[aboveNeighbor] = 8;

        var mesh = new FluidSectionMesher().build(new FluidSectionSnapshot(ids, heights, tints, lights));
        var lowerTop = mesh.quads().stream()
                .filter(q -> q.normalY() == 1.0f)
                .filter(q -> q.positions()[0] == 1.0f && q.positions()[2] == 1.0f)
                .findFirst().orElseThrow();
        assertEquals(2.0f, lowerTop.positions()[4],
                "a same-fluid block above any cell sharing the corner makes it full height");
    }

    @Test
    void copiesCaptureArraysAndRejectsInvalidHeights() {
        short[] ids = new short[4096]; byte[] heights = new byte[4096];
        int[] tints = new int[4096], lights = new int[4096];
        ids[0] = 1; heights[0] = 4;
        var snapshot = new FluidSectionSnapshot(ids, heights, tints, lights);
        ids[0] = 0; heights[0] = 0;
        assertEquals(1, snapshot.fluidId(0,0,0));
        assertEquals(4, snapshot.heightEighths(0,0,0));
        heights[0] = 9;
        assertThrows(IllegalArgumentException.class,
                () -> new FluidSectionSnapshot(new short[4096], heights, tints, lights));
    }
}
