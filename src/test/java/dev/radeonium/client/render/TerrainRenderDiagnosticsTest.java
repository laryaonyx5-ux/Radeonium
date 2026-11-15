package dev.radeonium.client.render;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

class TerrainRenderDiagnosticsTest {
    @Test
    void countsRadeoniumAndVanillaDrawSourcesPerLayer() {
        TerrainRenderDiagnostics diagnostics = new TerrainRenderDiagnostics();

        diagnostics.recordDraw(ChunkSectionLayer.SOLID, TerrainRenderDiagnostics.Source.RADEONIUM_MODEL);
        diagnostics.recordDraw(ChunkSectionLayer.SOLID,
                TerrainRenderDiagnostics.Source.RADEONIUM_COMPILED_GEOMETRY);
        diagnostics.recordDraw(ChunkSectionLayer.CUTOUT, TerrainRenderDiagnostics.Source.VANILLA_FALLBACK);
        diagnostics.recordDraw(ChunkSectionLayer.TRANSLUCENT, TerrainRenderDiagnostics.Source.VANILLA_FALLBACK);
        diagnostics.recordSectionWithoutMesh();

        TerrainRenderDiagnostics.Snapshot snapshot = diagnostics.snapshot();
        assertEquals(1, snapshot.layers().get(ChunkSectionLayer.SOLID).radeoniumModelDraws());
        assertEquals(1, snapshot.layers().get(ChunkSectionLayer.SOLID).radeoniumCompiledGeometryDraws());
        assertEquals(0, snapshot.layers().get(ChunkSectionLayer.SOLID).vanillaFallbackDraws());
        assertEquals(1, snapshot.layers().get(ChunkSectionLayer.CUTOUT).vanillaFallbackDraws());
        assertEquals(1, snapshot.layers().get(ChunkSectionLayer.TRANSLUCENT).vanillaFallbackDraws());
        assertEquals(2, snapshot.totalRadeoniumDraws());
        assertEquals(2, snapshot.totalVanillaFallbackDraws());
        assertEquals(4, snapshot.totalDraws());
        assertEquals(1, snapshot.sectionsWithoutMesh());
    }

    @Test
    void snapshotsAreImmutableAndIndependentOfFutureUpdates() {
        TerrainRenderDiagnostics diagnostics = new TerrainRenderDiagnostics();
        diagnostics.recordDraw(ChunkSectionLayer.SOLID, TerrainRenderDiagnostics.Source.RADEONIUM_MODEL);

        TerrainRenderDiagnostics.Snapshot before = diagnostics.snapshot();
        diagnostics.recordDraw(ChunkSectionLayer.SOLID, TerrainRenderDiagnostics.Source.VANILLA_FALLBACK);

        assertEquals(1, before.totalDraws());
        assertEquals(2, diagnostics.snapshot().totalDraws());
        assertThrows(UnsupportedOperationException.class,
                () -> before.layers().put(ChunkSectionLayer.CUTOUT,
                        new TerrainRenderDiagnostics.LayerSnapshot(0, 0, 0)));
    }

    @Test
    void resetClearsAllDrawAndMissingMeshCounters() {
        TerrainRenderDiagnostics diagnostics = new TerrainRenderDiagnostics();
        diagnostics.recordDraw(ChunkSectionLayer.CUTOUT, TerrainRenderDiagnostics.Source.VANILLA_FALLBACK);
        diagnostics.recordSectionWithoutMesh();

        diagnostics.reset();

        assertEquals(0, diagnostics.snapshot().totalDraws());
        assertEquals(0, diagnostics.snapshot().sectionsWithoutMesh());
    }

    @Test
    void rejectsMissingDrawClassification() {
        TerrainRenderDiagnostics diagnostics = new TerrainRenderDiagnostics();
        assertThrows(IllegalArgumentException.class,
                () -> diagnostics.recordDraw(null, TerrainRenderDiagnostics.Source.VANILLA_FALLBACK));
        assertThrows(IllegalArgumentException.class,
                () -> diagnostics.recordDraw(ChunkSectionLayer.SOLID, null));
    }

    @Test
    void concurrentDrawRecordsAreNotLost() throws InterruptedException {
        TerrainRenderDiagnostics diagnostics = new TerrainRenderDiagnostics();
        int workers = 4;
        int incrementsPerWorker = 2_000;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(workers);

        for (int worker = 0; worker < workers; worker++) {
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (int i = 0; i < incrementsPerWorker; i++) {
                        diagnostics.recordDraw(ChunkSectionLayer.SOLID,
                                TerrainRenderDiagnostics.Source.RADEONIUM_MODEL);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            });
            thread.start();
        }

        ready.await();
        start.countDown();
        finished.await();

        assertEquals((long) workers * incrementsPerWorker,
                diagnostics.snapshot().layers().get(ChunkSectionLayer.SOLID).radeoniumModelDraws());
    }
}
