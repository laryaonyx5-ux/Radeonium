package dev.radeonium.client.render;

import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

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
