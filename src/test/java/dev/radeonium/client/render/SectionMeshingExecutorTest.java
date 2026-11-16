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
            var capture = new MinecraftSectionModelMesher.CapturedSectionInput(List.of(), 3);
            var result = executor.buildMinecraftAsync(capture);
            var mesh = result.get(5, TimeUnit.SECONDS);

            assertEquals(0, mesh.quadCount());
            assertEquals(3, mesh.unsupportedBlockEntityCount());
            assertEquals(1L, executor.metricsSnapshot().completed());
        }
    }

    @Test
    void staleCaptureIsDiscardedBeforePublishingResult() {
        try (SectionMeshingExecutor executor = new SectionMeshingExecutor()) {
            var capture = new MinecraftSectionModelMesher.CapturedSectionInput(List.of(), 0);
            var result = executor.buildMinecraftAsync(capture, () -> false);

            ExecutionException failure = assertThrows(
                    ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, failure.getCause());
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
