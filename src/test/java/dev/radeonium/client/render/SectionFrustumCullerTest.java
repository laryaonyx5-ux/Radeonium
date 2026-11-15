package dev.radeonium.client.render;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SectionFrustumCullerTest {
    private static SectionFrustumCuller boxFrustum() {
        return new SectionFrustumCuller(List.of(
                new SectionFrustumCuller.Plane(1, 0, 0, 16),
                new SectionFrustumCuller.Plane(-1, 0, 0, 32),
                new SectionFrustumCuller.Plane(0, 1, 0, 16),
                new SectionFrustumCuller.Plane(0, -1, 0, 32),
                new SectionFrustumCuller.Plane(0, 0, 1, 16),
                new SectionFrustumCuller.Plane(0, 0, -1, 32)));
    }

    @Test void acceptsInsideAndBoundarySections() {
        assertTrue(boxFrustum().intersects(new SectionPos(0, 0, 0)));
        assertTrue(boxFrustum().intersects(new SectionPos(1, 0, 0)));
    }

    @Test void rejectsSectionsOutsideAnyPlane() {
        var culler = boxFrustum();
        assertFalse(culler.intersects(new SectionPos(3, 0, 0)));
        assertFalse(culler.intersects(new SectionPos(0, -3, 0)));
        assertFalse(culler.intersects(new SectionPos(0, 0, 3)));
    }

    @Test void filtersListInPlace() {
        var sections = new java.util.ArrayList<>(List.of(
                new SectionPos(0, 0, 0), new SectionPos(4, 0, 0), new SectionPos(0, 0, 3)));
        assertEquals(1, boxFrustum().filterInPlace(sections));
        assertEquals(List.of(new SectionPos(0, 0, 0)), sections);
    }

    @Test void rejectsInvalidPlanesAndInputs() {
        assertThrows(IllegalArgumentException.class, () -> new SectionFrustumCuller(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new SectionFrustumCuller(List.of(
                        new SectionFrustumCuller.Plane(0, 0, 0, 1),
                        new SectionFrustumCuller.Plane(1, 0, 0, 1),
                        new SectionFrustumCuller.Plane(1, 0, 0, 1),
                        new SectionFrustumCuller.Plane(1, 0, 0, 1),
                        new SectionFrustumCuller.Plane(1, 0, 0, 1),
                        new SectionFrustumCuller.Plane(1, 0, 0, 1))));
        assertThrows(IllegalArgumentException.class, () -> boxFrustum().intersects(null));
        assertThrows(IllegalArgumentException.class, () -> boxFrustum().filterInPlace(null));
    }

    @Test void combinesPortalReachabilityAndFrustum() {
        ChunkRenderPipeline pipeline = new ChunkRenderPipeline();
        SectionPos camera = new SectionPos(0, 0, 0);
        SectionPos adjacent = new SectionPos(1, 0, 0);
        SectionPos disconnected = new SectionPos(4, 0, 0);
        pipeline.syncVisibleSections(List.of(camera, adjacent, disconnected));
        long cameraConnections = 1L << (SectionFace.NORTH.ordinal() * 6 + SectionFace.EAST.ordinal());
        long adjacentConnections = 1L << (SectionFace.WEST.ordinal() * 6 + SectionFace.NORTH.ordinal());
        pipeline.updateVisibilityConnections(camera, cameraConnections);
        pipeline.updateVisibilityConnections(adjacent, adjacentConnections);
        pipeline.updateVisibilityConnections(disconnected, 0L);
        assertEquals(Set.of(camera, adjacent), pipeline.collectVisibleSections(camera, boxFrustum()));
    }
}
