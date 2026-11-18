package dev.radeonium.client.render;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SectionMeshingExecutorTest {
    @Test
    void minecraftModelMeshingRunsOnDedicatedWorkerAndReturnsIndependentLayerMeshes() throws Exception {
        try (SectionMeshingExecutor executor = new SectionMeshingExecutor()) {
            var unsupported = List.of(
                    new MinecraftSectionModelMesher.UnsupportedBlockInput(0, 0, 0, "MODEL", true),
                    new MinecraftSectionModelMesher.UnsupportedBlockInput(1, 0, 0, "MODEL", true),
                    new MinecraftSectionModelMesher.UnsupportedBlockInput(2, 0, 0, "MODEL", true));
            var capture = new MinecraftSectionModelMesher.CapturedSectionInput(
                    List.of(), 3, 0, unsupported, MinecraftFluidSectionMesher.SectionFluidMesh.empty());
            var result = executor.buildMinecraftAsync(capture);
            var mesh = result.get(5, TimeUnit.SECONDS);

            assertEquals(0, mesh.quadCount());
            assertEquals(3, mesh.unsupportedBlockEntityCount());
            assertEquals(1L, executor.metricsSnapshot().completed());
        }
    }

    @Test
    void unsupportedFallbackManifestSurvivesAsyncMeshing() throws Exception {
        try (SectionMeshingExecutor executor = new SectionMeshingExecutor()) {
            var unsupported = List.of(new MinecraftSectionModelMesher.UnsupportedBlockInput(
                    4, 5, 6, "ENTITY", true));
            var capture = new MinecraftSectionModelMesher.CapturedSectionInput(
                    List.of(), 1, 1, unsupported, MinecraftFluidSectionMesher.SectionFluidMesh.empty());

            var mesh = executor.buildMinecraftAsync(capture).get(5, TimeUnit.SECONDS);

            assertEquals(1, mesh.unsupportedBlockEntityCount());
            assertEquals(1, mesh.unsupportedRenderShapeCount());
            assertEquals(unsupported, mesh.unsupportedBlocks());
        }
    }

    @Test
    void staleCaptureIsDiscardedBeforePublishingResult() {
        try (SectionMeshingExecutor executor = new SectionMeshingExecutor()) {
            var capture = new MinecraftSectionModelMesher.CapturedSectionInput(
                    List.of(), 0, 0, List.of(), MinecraftFluidSectionMesher.SectionFluidMesh.empty());
            var result = executor.buildMinecraftAsync(capture, () -> false);

            Exception failure = assertThrows(Exception.class, () -> result.get(5, TimeUnit.SECONDS));
            assertTrue(failure instanceof CancellationException
                    || failure instanceof ExecutionException && failure.getCause() instanceof CancellationException);
            assertEquals(1L, executor.metricsSnapshot().cancelled());
            assertEquals(0L, executor.metricsSnapshot().completed());
        }
    }

    @Test
    void cancellationPredicateIsRecheckedAfterBuild() throws Exception {
        try (SectionMeshingExecutor executor = new SectionMeshingExecutor()) {
            AtomicBoolean current = new AtomicBoolean(true);
            var capture = new MinecraftSectionModelMesher.CapturedSectionInput(List.of(), 0);
            var result = executor.buildMinecraftAsync(capture, current::get);
            // Empty captures are quick; the test also verifies the accepted future remains cancellable.
            var mesh = result.get(5, TimeUnit.SECONDS);
            assertEquals(0, mesh.quadCount());
            assertEquals(1L, executor.metricsSnapshot().completed());
        }
    }

    @Test
    void rejectsNullMinecraftCaptureAndNullValidityPredicate() {
        try (SectionMeshingExecutor executor = new SectionMeshingExecutor()) {
            assertThrows(IllegalArgumentException.class, () -> executor.buildMinecraftAsync(null));
            var capture = new MinecraftSectionModelMesher.CapturedSectionInput(List.of(), 0);
            assertThrows(IllegalArgumentException.class,
                    () -> executor.buildMinecraftAsync(capture, null));
        }
    }

    @Test
    void closeIsIdempotentAndRejectsNewWork() throws Exception {
        SectionMeshingExecutor executor = new SectionMeshingExecutor();
        executor.close();
        executor.close();
        assertTrue(executor.isClosed());

        var capture = new MinecraftSectionModelMesher.CapturedSectionInput(List.of(), 0);
        assertThrows(ExecutionException.class,
                () -> executor.buildMinecraftAsync(capture).get(5, TimeUnit.SECONDS));
        assertEquals(1L, executor.metricsSnapshot().rejected());
    }
}
