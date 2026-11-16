package dev.radeonium.client.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TerrainGpuUploadMetricsTest {
    @Test
    void aggregatesSuccessfulUploadsAndDuration() {
        TerrainGpuUploadMetrics metrics = new TerrainGpuUploadMetrics();
        metrics.record(1024, 100, true);
        metrics.record(2048, 300, true);

        var snapshot = metrics.snapshot();
        assertEquals(2, snapshot.attempts());
        assertEquals(2, snapshot.successes());
        assertEquals(0, snapshot.failures());
        assertEquals(0, snapshot.skipped());
        assertEquals(3072, snapshot.submittedBytes());
        assertEquals(400, snapshot.totalNanos());
        assertEquals(300, snapshot.maxUploadNanos());
        assertEquals(200, snapshot.averageUploadNanos());
    }

    @Test
    void countsFailuresAndTheirPayloadBytesWithoutAddingSuccessfulTime() {
        TerrainGpuUploadMetrics metrics = new TerrainGpuUploadMetrics();
        metrics.record(512, 90, false);

        var snapshot = metrics.snapshot();
        assertEquals(1, snapshot.attempts());
        assertEquals(0, snapshot.successes());
        assertEquals(1, snapshot.failures());
        assertEquals(0, snapshot.skipped());
        assertEquals(512, snapshot.submittedBytes());
        assertEquals(0, snapshot.totalNanos());
        assertEquals(0, snapshot.averageUploadNanos());
    }

    @Test
    void resetsAllCounters() {
        TerrainGpuUploadMetrics metrics = new TerrainGpuUploadMetrics();
        metrics.record(128, 50, true);
        metrics.reset();

        assertEquals(new TerrainGpuUploadMetrics.Snapshot(0, 0, 0, 0, 0, 0, 0), metrics.snapshot());
    }

    @Test
    void rejectsNegativeMeasurements() {
        TerrainGpuUploadMetrics metrics = new TerrainGpuUploadMetrics();
        assertThrows(IllegalArgumentException.class, () -> metrics.record(-1, 0, true));
        assertThrows(IllegalArgumentException.class, () -> metrics.record(0, -1, true));
    }
}
