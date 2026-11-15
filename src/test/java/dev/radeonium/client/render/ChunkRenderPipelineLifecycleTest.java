package dev.radeonium.client.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChunkRenderPipelineLifecycleTest {
    @Test
    void clearAndWorldRecreationCannotReuseAnOldBuildGeneration() {
        ChunkRenderPipeline pipeline = new ChunkRenderPipeline();
        SectionPos position = new SectionPos(2, 4, -3);
        SectionMesh mesh = new SectionMesh(new byte[0], 0);

        pipeline.addSection(position, 0);
        long oldGeneration = pipeline.beginMeshBuild(position);

        pipeline.clear();
        pipeline.addSection(position, 0);
        long newGeneration = pipeline.beginMeshBuild(position);

        assertNotEquals(oldGeneration, newGeneration);
        assertFalse(pipeline.installMesh(position, oldGeneration, mesh));
        assertTrue(pipeline.installMesh(position, newGeneration, mesh));
    }

    @Test
    void removingAndReaddingASectionInvalidatesItsPreviousBuild() {
        ChunkRenderPipeline pipeline = new ChunkRenderPipeline();
        SectionPos position = new SectionPos(-1, 0, 8);
        SectionMesh mesh = new SectionMesh(new byte[0], 0);

        pipeline.addSection(position, 0);
        long oldGeneration = pipeline.beginMeshBuild(position);
        pipeline.removeSection(position);
        pipeline.addSection(position, 0);

        assertFalse(pipeline.installMesh(position, oldGeneration, mesh));
        assertTrue(pipeline.installMesh(position, pipeline.beginMeshBuild(position), mesh));
    }

    @Test
    void dirtyingASectionInvalidatesAnInFlightBuildAndQueuesOneRebuild() {
        ChunkRenderPipeline pipeline = new ChunkRenderPipeline();
        SectionPos position = new SectionPos(1, 1, 1);
        SectionMesh mesh = new SectionMesh(new byte[0], 0);
        pipeline.addSection(position, 0);
        long generation = pipeline.beginMeshBuild(position);

        pipeline.markDirty(position);
        pipeline.markDirty(position);

        assertFalse(pipeline.installMesh(position, generation, mesh));
        assertArrayEquals(new SectionPos[]{position}, pipeline.drainDirtySections(8));
        assertEquals(0, pipeline.dirtySectionCount());
    }

    @Test
    void visibleSetChangesRemoveDepartedSectionsAndKeepCurrentSections() {
        ChunkRenderPipeline pipeline = new ChunkRenderPipeline();
        SectionPos first = new SectionPos(0, 0, 0);
        SectionPos second = new SectionPos(1, 0, 0);

        pipeline.syncVisibleSections(List.of(first, second));
        assertEquals(2, pipeline.visibleSectionCount());
        assertEquals(2, pipeline.sectionCount());

        pipeline.syncVisibleSections(List.of(second));

        assertEquals(1, pipeline.visibleSectionCount());
        assertEquals(1, pipeline.sectionCount());
        assertTrue(pipeline.visibilityGraph().sectionCount() >= 1);
        assertTrue(pipeline.drainDirtySections(8).length >= 1);
    }
}
