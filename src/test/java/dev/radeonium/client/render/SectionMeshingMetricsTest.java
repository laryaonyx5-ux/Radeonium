package dev.radeonium.client.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SectionMeshingMetricsTest {
    @Test
    void aggregatesCompletedWorkAndDurations() {
        SectionMeshingMetrics metrics = new SectionMeshingMetrics();
        metrics.recordSubmitted();
        metrics.recordCompleted(10, 100);
        metrics.recordSubmitted();
        metrics.recordCompleted(20, 300);

        var snapshot = metrics.snapshot();
        assertEquals(2, snapshot.submitted());
        assertEquals(2, snapshot.completed());
        assertEquals(0, snapshot.failed());
        assertEquals(0, snapshot.rejected());
        assertEquals(30, snapshot.totalQueueWaitNanos());
        assertEquals(400, snapshot.totalBuildNanos());
        assertEquals(300, snapshot.maxBuildNanos());
        assertEquals(200, snapshot.averageBuildNanos());
    }

    @Test
    void tracksFailuresAndRejectedWorkSeparately() {
        SectionMeshingMetrics metrics = new SectionMeshingMetrics();
        metrics.recordRejected();
        metrics.recordSubmitted();
        metrics.recordFailed(7, 50);

        var snapshot = metrics.snapshot();
        assertEquals(1, snapshot.submitted());
        assertEquals(0, snapshot.completed());
        assertEquals(1, snapshot.failed());
        assertEquals(1, snapshot.rejected());
        assertEquals(7, snapshot.totalQueueWaitNanos());
        assertEquals(50, snapshot.totalBuildNanos());
    }

    @Test
    void resetsCountersAndDurations() {
        SectionMeshingMetrics metrics = new SectionMeshingMetrics();
        metrics.recordSubmitted();
        metrics.recordCompleted(1, 2);
        metrics.reset();

        assertEquals(new SectionMeshingMetrics.Snapshot(0, 0, 0, 0, 0, 0, 0), metrics.snapshot());
    }

    @Test
    void rejectsNegativeDurations() {
        SectionMeshingMetrics metrics = new SectionMeshingMetrics();
        assertThrows(IllegalArgumentException.class, () -> metrics.recordCompleted(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> metrics.recordFailed(0, -1));
    }
}
