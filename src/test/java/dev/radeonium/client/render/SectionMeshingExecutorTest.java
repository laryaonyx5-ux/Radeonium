package dev.radeonium.client.render;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

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
        }
    }

    @Test
    void rejectsNullMinecraftCaptureImmediately() {
        try (SectionMeshingExecutor executor = new SectionMeshingExecutor()) {
            assertThrows(IllegalArgumentException.class, () -> executor.buildMinecraftAsync(null));
        }
    }
}
