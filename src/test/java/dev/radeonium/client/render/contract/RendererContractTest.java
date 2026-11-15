package dev.radeonium.client.render.contract;

import dev.radeonium.client.render.SectionPos;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RendererContractTest {
    @Test
    void meshPayloadDefensivelyCopiesBothBuffers() {
        byte[] vertices = {1, 2, 3, 4};
        byte[] indices = {0, 0};
        var payload = new RendererContract.MeshPayload(
                new RendererContract.SectionBuildTicket(new SectionPos(0, 0, 0), 1, 2),
                RendererContract.TerrainLayer.SOLID, "test/position", vertices, 4, 1, indices, 2, 1
        );

        vertices[0] = 99;
        indices[0] = 99;
        byte[] exposed = payload.vertexBytes();
        exposed[1] = 99;

        assertArrayEquals(new byte[]{1, 2, 3, 4}, payload.vertexBytes());
        assertArrayEquals(new byte[]{0, 0}, payload.indexBytes());
        assertEquals(6L, payload.byteSize());
    }

    @Test
    void rejectsMismatchedMeshByteCounts() {
        var ticket = new RendererContract.SectionBuildTicket(new SectionPos(1, 2, 3), 0, 0);
        assertThrows(IllegalArgumentException.class, () -> new RendererContract.MeshPayload(
                ticket, RendererContract.TerrainLayer.CUTOUT, "test", new byte[3], 4, 1,
                new byte[0], 0, 0
        ));
        assertThrows(IllegalArgumentException.class, () -> new RendererContract.MeshPayload(
                ticket, RendererContract.TerrainLayer.CUTOUT, "test", new byte[4], 4, 1,
                new byte[1], 2, 1
        ));
    }

    @Test
    void renderThreadGuardBindsOnceAndRejectsOtherThreads() throws InterruptedException {
        var guard = new RendererThreadGuard();
        assertThrows(IllegalStateException.class, guard::checkRenderThread);
        guard.bindCurrentThread();
        assertTrue(guard.isRenderThread());
        assertDoesNotThrow(guard::checkRenderThread);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                guard.checkRenderThread();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        worker.start();
        worker.join();

        assertInstanceOf(IllegalStateException.class, failure.get());
        assertThrows(IllegalStateException.class, guard::bindCurrentThread);
    }

    @Test
    void sectionBuildTicketRejectsNegativeEpochOrGeneration() {
        assertThrows(IllegalArgumentException.class, () ->
                new RendererContract.SectionBuildTicket(new SectionPos(0, 0, 0), -1, 0));
        assertThrows(IllegalArgumentException.class, () ->
                new RendererContract.SectionBuildTicket(new SectionPos(0, 0, 0), 0, -1));
    }
}
