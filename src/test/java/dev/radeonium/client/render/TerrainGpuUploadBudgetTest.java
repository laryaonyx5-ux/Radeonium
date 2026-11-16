package dev.radeonium.client.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TerrainGpuUploadBudgetTest {
    @Test
    void alwaysAllowsFirstUploadSoOversizedSectionsCannotStarve() {
        assertTrue(TerrainGpuUploadBudget.canUploadNext(
                0, 0, 0, TerrainGpuUploadBudget.MAX_BYTES_PER_TICK + 1));
    }

    @Test
    void enforcesUploadCountByteAndTimeBudgetsAfterFirstUpload() {
        assertTrue(TerrainGpuUploadBudget.canUploadNext(1, 1024, 1, 1024));
        assertFalse(TerrainGpuUploadBudget.canUploadNext(
                TerrainGpuUploadBudget.MAX_UPLOADS_PER_TICK, 0, 0, 1));
        assertFalse(TerrainGpuUploadBudget.canUploadNext(
                1, TerrainGpuUploadBudget.MAX_BYTES_PER_TICK, 0, 1));
        assertFalse(TerrainGpuUploadBudget.canUploadNext(
                1, 0, TerrainGpuUploadBudget.MAX_NANOS_PER_TICK, 1));
    }

    @Test
    void rejectsInvalidMeasurements() {
        assertThrows(IllegalArgumentException.class,
                () -> TerrainGpuUploadBudget.canUploadNext(-1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> TerrainGpuUploadBudget.canUploadNext(0, -1, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> TerrainGpuUploadBudget.canUploadNext(0, 0, -1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> TerrainGpuUploadBudget.canUploadNext(0, 0, 0, -1));
    }
}
