package dev.radeonium.client.render;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TerrainDrawBatchMetricsTest {
    @Test
    void countsContiguousBufferRunsAndUniqueHandlesByIdentity() {
        TerrainDrawBatchMetrics metrics = new TerrainDrawBatchMetrics();
        Object bufferA = new Object();
        Object bufferB = new Object();

        metrics.recordDraw(ChunkSectionLayer.SOLID, bufferA);
        metrics.recordDraw(ChunkSectionLayer.SOLID, bufferA);
        metrics.recordDraw(ChunkSectionLayer.SOLID, bufferB);
        metrics.recordDraw(ChunkSectionLayer.SOLID, bufferA);

        TerrainDrawBatchMetrics.LayerSnapshot solid =
                metrics.snapshot().layers().get(ChunkSectionLayer.SOLID);
        assertEquals(4, solid.draws());
        assertEquals(2, solid.uniqueVertexBuffers());
        assertEquals(3, solid.vertexBufferBinds());
        assertEquals(1, solid.potentialRunsSaved());
    }

    @Test
    void tracksLayersIndependentlyAndDoesNotTreatEqualObjectsAsSameHandle() {
        TerrainDrawBatchMetrics metrics = new TerrainDrawBatchMetrics();
        Object first = new String("same");
        Object second = new String("same");

        metrics.recordDraw(ChunkSectionLayer.SOLID, first);
        metrics.recordDraw(ChunkSectionLayer.SOLID, second);
        metrics.recordDraw(ChunkSectionLayer.CUTOUT, first);

        var snapshot = metrics.snapshot();
        assertEquals(2, snapshot.layers().get(ChunkSectionLayer.SOLID).uniqueVertexBuffers());
        assertEquals(1, snapshot.layers().get(ChunkSectionLayer.CUTOUT).draws());
        assertEquals(2, snapshot.totalDraws());
    }

    @Test
    void startsEachFrameWithEmptyMetrics() {
        TerrainDrawBatchMetrics metrics = new TerrainDrawBatchMetrics();
        metrics.recordDraw(ChunkSectionLayer.SOLID, new Object());

        metrics.beginFrame();

        var snapshot = metrics.snapshot();
        assertEquals(0, snapshot.totalDraws());
        assertEquals(0, snapshot.totalVertexBufferBinds());
    }

    @Test
    void countsDrawWithoutBufferButDoesNotInventBind() {
        TerrainDrawBatchMetrics metrics = new TerrainDrawBatchMetrics();
        metrics.recordDraw(ChunkSectionLayer.TRANSLUCENT, null);

        var translucent = metrics.snapshot().layers().get(ChunkSectionLayer.TRANSLUCENT);
        assertEquals(1, translucent.draws());
        assertEquals(0, translucent.uniqueVertexBuffers());
        assertEquals(0, translucent.vertexBufferBinds());
    }

    @Test
    void rejectsMissingLayer() {
        TerrainDrawBatchMetrics metrics = new TerrainDrawBatchMetrics();
        assertThrows(IllegalArgumentException.class, () -> metrics.recordDraw(null, new Object()));
    }
}
