package dev.radeonium.client.render;

import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MinecraftSectionModelCaptureStoreTest {
    @AfterEach
    void clearStore() {
        MinecraftSectionModelCaptureStore.clear();
    }

    @Test
    void storesAndRetrievesCapturedSectionMesh() {
        SectionPos position = SectionPos.of(2, 3, 4);
        var mesh = new MinecraftSectionModelMesher().buildResolved(List.of());

        var snapshot = MinecraftSectionModelCaptureStore.put(position, mesh);

        assertNotNull(snapshot);
        assertSame(snapshot, MinecraftSectionModelCaptureStore.get(position));
        assertEquals(1, MinecraftSectionModelCaptureStore.sectionCount());
        assertEquals(0, MinecraftSectionModelCaptureStore.retainedQuadCount());
    }

    @Test
    void boundsStoreAndEvictsLeastRecentlyUsedSection() {
        var mesher = new MinecraftSectionModelMesher();
        var mesh = mesher.buildResolved(List.of());
        SectionPos first = SectionPos.of(0, 0, 0);

        MinecraftSectionModelCaptureStore.put(first, mesh);
        for (int i = 1; i <= 64; i++) {
            MinecraftSectionModelCaptureStore.put(SectionPos.of(i, 0, 0), mesh);
        }

        assertEquals(64, MinecraftSectionModelCaptureStore.sectionCount());
        assertNull(MinecraftSectionModelCaptureStore.get(first));
        assertNotNull(MinecraftSectionModelCaptureStore.get(SectionPos.of(64, 0, 0)));
    }

    @Test
    void stagedMeshIsPublishedOnlyAfterSuccessfulCompileCompletion() {
        SectionPos position = SectionPos.of(8, 2, 1);
        var mesh = new MinecraftSectionModelMesher().buildResolved(List.of());

        MinecraftSectionModelCaptureStore.beginCapture(position);
        MinecraftSectionModelCaptureStore.stage(position, mesh);
        assertNull(MinecraftSectionModelCaptureStore.get(position));

        var snapshot = MinecraftSectionModelCaptureStore.complete(position);
        assertNotNull(snapshot);
        assertSame(snapshot, MinecraftSectionModelCaptureStore.get(position));
    }

    @Test
    void clearInvalidatesUnfinishedWorkerCaptures() {
        SectionPos position = SectionPos.of(9, 2, 1);
        var mesh = new MinecraftSectionModelMesher().buildResolved(List.of());

        MinecraftSectionModelCaptureStore.beginCapture(position);
        MinecraftSectionModelCaptureStore.stage(position, mesh);
        MinecraftSectionModelCaptureStore.clear();

        assertNull(MinecraftSectionModelCaptureStore.complete(position));
        assertNull(MinecraftSectionModelCaptureStore.get(position));
    }

    @Test
    void worldClearInvalidatesCaptureStagedOnAnotherWorkerThread() throws Exception {
        SectionPos position = SectionPos.of(10, 2, 1);
        var mesh = new MinecraftSectionModelMesher().buildResolved(List.of());
        CountDownLatch staged = new CountDownLatch(1);
        CountDownLatch continueWorker = new CountDownLatch(1);
        AtomicReference<MinecraftSectionModelCaptureStore.Snapshot> completed =
                new AtomicReference<>();

        Thread worker = new Thread(() -> {
            MinecraftSectionModelCaptureStore.beginCapture(position);
            MinecraftSectionModelCaptureStore.stage(position, mesh);
            staged.countDown();
            try {
                continueWorker.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
            completed.set(MinecraftSectionModelCaptureStore.complete(position));
        }, "Radeonium-Capture-Store-Test");
        worker.start();
        staged.await();

        MinecraftSectionModelCaptureStore.clear();
        continueWorker.countDown();
        worker.join();

        assertNull(completed.get());
        assertNull(MinecraftSectionModelCaptureStore.get(position));
    }

    @Test
    void reloadDuringModelCapturePreventsStaleMeshFromBeingStaged() throws Exception {
        SectionPos position = SectionPos.of(11, 2, 1);
        var mesh = new MinecraftSectionModelMesher().buildResolved(List.of());
        CountDownLatch captureStarted = new CountDownLatch(1);
        CountDownLatch continueWorker = new CountDownLatch(1);
        AtomicReference<MinecraftSectionModelCaptureStore.Snapshot> completed =
                new AtomicReference<>();

        Thread worker = new Thread(() -> {
            MinecraftSectionModelCaptureStore.beginCapture(position);
            captureStarted.countDown();
            try {
                continueWorker.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
            MinecraftSectionModelCaptureStore.stage(position, mesh);
            completed.set(MinecraftSectionModelCaptureStore.complete(position));
        }, "Radeonium-Reload-Race-Test");
        worker.start();
        captureStarted.await();

        MinecraftSectionModelCaptureStore.clear();
        continueWorker.countDown();
        worker.join();

        assertNull(completed.get());
        assertNull(MinecraftSectionModelCaptureStore.get(position));
    }

    @Test
    void removeAndClearReleaseCapturedSections() {
        SectionPos position = SectionPos.of(5, 6, 7);
        var mesh = new MinecraftSectionModelMesher().buildResolved(List.of());
        MinecraftSectionModelCaptureStore.put(position, mesh);
        MinecraftSectionModelCaptureStore.remove(position);
        assertNull(MinecraftSectionModelCaptureStore.get(position));

        MinecraftSectionModelCaptureStore.put(position, mesh);
        MinecraftSectionModelCaptureStore.clear();
        assertEquals(0, MinecraftSectionModelCaptureStore.sectionCount());
        assertEquals(0, MinecraftSectionModelCaptureStore.retainedQuadCount());
    }
}
