package dev.radeonium.client.render;

import org.junit.jupiter.api.Test;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftSectionGpuCacheLifecycleTest {
    @Test
    void emptySectionReplacementRemovalAndClearKeepOwnerAccountingEmpty() {
        AtomicInteger renderThreadChecks = new AtomicInteger();
        MinecraftGpuResourceOwner owner = new MinecraftGpuResourceOwner(renderThreadChecks::incrementAndGet);
        MinecraftSectionGpuCache cache =
                new MinecraftSectionGpuCache(owner, renderThreadChecks::incrementAndGet);
        SectionPos position = new SectionPos(2, -1, 4);

        cache.rebuild(position, List.of());
        assertEquals(1, cache.sectionCount());
        assertEquals(0, cache.gpuAllocationCount());
        assertEquals(0L, cache.uploadedBytes());

        // Replacing an entry must dispose the old entry without changing the
        // number of live sections or creating a phantom GPU allocation.
        cache.rebuild(position, List.of());
        assertEquals(1, cache.sectionCount());
        assertEquals(0, cache.gpuAllocationCount());

        cache.remove(position);
        assertEquals(0, cache.sectionCount());
        assertEquals(0, cache.materialCount());
        assertEquals(0L, cache.uploadedBytes());

        cache.clear();
        cache.clear();
        assertEquals(0, cache.sectionCount());
        assertEquals(0, owner.allocationCount());
        assertEquals(0L, owner.allocatedBytes());
        assertTrue(renderThreadChecks.get() > 0);
    }


    @Test
    void sectionCountRemainsBoundedByCacheCapacity() {
        MinecraftGpuResourceOwner owner = new MinecraftGpuResourceOwner(() -> {});
        MinecraftSectionGpuCache cache =
                new MinecraftSectionGpuCache(owner, () -> {});

        for (int index = 0; index < 70; index++) {
            cache.rebuild(new SectionPos(index, 0, 0), List.of());
        }

        assertEquals(64, cache.sectionCount());
        assertEquals(0, cache.gpuAllocationCount());
        cache.clear();
        assertEquals(0, cache.sectionCount());
    }



    @Test
    void cacheRejectsForeignGpuUploadsBeforeReplacingAnExistingEntry() {
        MinecraftGpuResourceOwner owner = new MinecraftGpuResourceOwner(() -> {});
        MinecraftSectionGpuCache cache =
                new MinecraftSectionGpuCache(owner, () -> {});
        SectionPos position = new SectionPos(3, 0, 0);
        MinecraftMeshGpuUpload owned =
                MinecraftMeshGpuUpload.forTesting(4, 24L, () -> {});
        owner.track(owned, null);
        cache.installPreparedEntry(position, entry(owned));

        MinecraftMeshGpuUpload foreign =
                MinecraftMeshGpuUpload.forTesting(4, 32L, () -> {});
        assertThrows(IllegalArgumentException.class,
                () -> cache.installPreparedEntry(position, entry(foreign)));

        assertFalse(owned.isClosed());
        assertFalse(foreign.isClosed());
        assertEquals(1, cache.sectionCount());
        assertEquals(1, owner.allocationCount());
        assertEquals(24L, owner.allocatedBytes());

        cache.clear();
        foreign.close();
    }

    @Test
    void replacementDisposesOldUploadAndRetainsOnlyTheNewOne() {
        MinecraftGpuResourceOwner owner = new MinecraftGpuResourceOwner(() -> {});
        MinecraftSectionGpuCache cache =
                new MinecraftSectionGpuCache(owner, () -> {});
        SectionPos position = new SectionPos(1, 2, 3);
        MinecraftMeshGpuUpload oldUpload =
                MinecraftMeshGpuUpload.forTesting(4, 28L, () -> {});
        MinecraftMeshGpuUpload newUpload =
                MinecraftMeshGpuUpload.forTesting(4, 56L, () -> {});
        owner.track(oldUpload, null);
        cache.installPreparedEntry(position, entry(oldUpload));
        owner.track(newUpload, null);

        cache.installPreparedEntry(position, entry(newUpload));

        assertTrue(oldUpload.isClosed());
        assertFalse(newUpload.isClosed());
        assertEquals(1, cache.sectionCount());
        assertEquals(1, owner.allocationCount());
        assertEquals(56L, owner.allocatedBytes());

        cache.remove(position);
        assertTrue(newUpload.isClosed());
        assertEquals(0, owner.allocationCount());
    }


    @Test
    void sharedUploadSurvivesRemovalOfOnlyOneSection() {
        MinecraftGpuResourceOwner owner = new MinecraftGpuResourceOwner(() -> {});
        MinecraftSectionGpuCache cache =
                new MinecraftSectionGpuCache(owner, () -> {});
        MinecraftMeshGpuUpload shared =
                MinecraftMeshGpuUpload.forTesting(4, 48L, () -> {});
        owner.track(shared, new MinecraftGpuResourceOwner.ByteArrayKey(new byte[] {2, 4}));
        assertTrue(owner.retain(shared));
        cache.installPreparedEntry(new SectionPos(8, 0, 0), entry(shared));
        cache.installPreparedEntry(new SectionPos(9, 0, 0), entry(shared));

        cache.remove(new SectionPos(8, 0, 0));

        assertFalse(shared.isClosed());
        assertEquals(1, owner.allocationCount());
        assertEquals(48L, owner.allocatedBytes());

        cache.remove(new SectionPos(9, 0, 0));
        assertTrue(shared.isClosed());
        assertEquals(0, owner.allocationCount());
        assertEquals(0L, owner.allocatedBytes());
    }

    @Test
    void failedReplacementRollsBackNewUploadAndKeepsOldEntryRetryable() {
        AtomicInteger oldCloseAttempts = new AtomicInteger();
        MinecraftGpuResourceOwner owner = new MinecraftGpuResourceOwner(() -> {});
        MinecraftSectionGpuCache cache =
                new MinecraftSectionGpuCache(owner, () -> {});
        SectionPos position = new SectionPos(4, 0, 0);
        MinecraftMeshGpuUpload oldUpload = MinecraftMeshGpuUpload.forTesting(4, 32L, () -> {
            if (oldCloseAttempts.incrementAndGet() == 1) {
                throw new IllegalStateException("old upload disposal fails once");
            }
        });
        owner.track(oldUpload, null);
        cache.installPreparedEntry(position, entry(oldUpload));

        MinecraftMeshGpuUpload rejectedReplacement =
                MinecraftMeshGpuUpload.forTesting(4, 48L, () -> {});
        owner.track(rejectedReplacement, null);
        assertThrows(IllegalStateException.class,
                () -> cache.installPreparedEntry(position, entry(rejectedReplacement)));

        assertEquals(1, cache.sectionCount());
        assertFalse(oldUpload.isClosed());
        assertTrue(rejectedReplacement.isClosed());
        assertEquals(1, owner.allocationCount());
        assertEquals(32L, owner.allocatedBytes());

        MinecraftMeshGpuUpload retry =
                MinecraftMeshGpuUpload.forTesting(4, 64L, () -> {});
        owner.track(retry, null);
        cache.installPreparedEntry(position, entry(retry));
        assertTrue(oldUpload.isClosed());
        assertFalse(retry.isClosed());
        cache.clear();
        assertEquals(0, owner.allocationCount());
    }

    @Test
    void failedRemoveAndClearKeepEntriesForDisposalRetry() {
        AtomicInteger closeAttempts = new AtomicInteger();
        MinecraftGpuResourceOwner owner = new MinecraftGpuResourceOwner(() -> {});
        MinecraftSectionGpuCache cache =
                new MinecraftSectionGpuCache(owner, () -> {});
        SectionPos position = new SectionPos(5, 0, 0);
        MinecraftMeshGpuUpload upload = MinecraftMeshGpuUpload.forTesting(4, 40L, () -> {
            if (closeAttempts.incrementAndGet() <= 2) {
                throw new IllegalStateException("first two disposal attempts fail");
            }
        });
        owner.track(upload, null);
        cache.installPreparedEntry(position, entry(upload));

        assertThrows(IllegalStateException.class, () -> cache.remove(position));
        assertEquals(1, cache.sectionCount());
        assertEquals(1, owner.allocationCount());
        assertFalse(upload.isClosed());

        assertThrows(IllegalStateException.class, cache::clear);
        assertEquals(1, cache.sectionCount());
        assertFalse(upload.isClosed());

        cache.clear();
        assertEquals(0, cache.sectionCount());
        assertEquals(0, owner.allocationCount());
        assertEquals(3, closeAttempts.get());
    }


    @Test
    void clearContinuesDisposalAndRetainsOnlyFailedEntries() {
        AtomicInteger firstAttempts = new AtomicInteger();
        MinecraftGpuResourceOwner owner = new MinecraftGpuResourceOwner(() -> {});
        MinecraftSectionGpuCache cache =
                new MinecraftSectionGpuCache(owner, () -> {});
        MinecraftMeshGpuUpload retryable = MinecraftMeshGpuUpload.forTesting(4, 24L, () -> {
            if (firstAttempts.incrementAndGet() == 1) {
                throw new IllegalStateException("first buffer disposal fails");
            }
        });
        MinecraftMeshGpuUpload independent =
                MinecraftMeshGpuUpload.forTesting(4, 32L, () -> {});
        owner.track(retryable, null);
        owner.track(independent, null);
        cache.installPreparedEntry(new SectionPos(0, 0, 0), entry(retryable));
        cache.installPreparedEntry(new SectionPos(1, 0, 0), entry(independent));

        assertThrows(IllegalStateException.class, cache::clear);

        assertEquals(1, cache.sectionCount());
        assertFalse(retryable.isClosed());
        assertTrue(independent.isClosed());
        assertEquals(1, owner.allocationCount());

        cache.clear();
        assertEquals(0, cache.sectionCount());
        assertEquals(0, owner.allocationCount());
    }

    @Test
    void evictionReleasesOldestGpuAllocation() {
        MinecraftGpuResourceOwner owner = new MinecraftGpuResourceOwner(() -> {});
        MinecraftSectionGpuCache cache =
                new MinecraftSectionGpuCache(owner, () -> {});
        MinecraftMeshGpuUpload oldest = null;

        for (int index = 0; index < 65; index++) {
            MinecraftMeshGpuUpload upload =
                    MinecraftMeshGpuUpload.forTesting(4, 28L, () -> {});
            if (index == 0) oldest = upload;
            owner.track(upload, null);
            cache.installPreparedEntry(new SectionPos(index, 0, 0), entry(upload));
        }

        assertTrue(oldest != null && oldest.isClosed());
        assertEquals(64, cache.sectionCount());
        assertEquals(64, owner.allocationCount());
        assertEquals(64L * 28L, owner.allocatedBytes());
        cache.clear();
        assertEquals(0, owner.allocationCount());
    }


    @Test
    void leastRecentlyUsedSectionIsEvictedAfterAnOlderSectionIsAccessed() {
        MinecraftGpuResourceOwner owner = new MinecraftGpuResourceOwner(() -> {});
        MinecraftSectionGpuCache cache =
                new MinecraftSectionGpuCache(owner, () -> {});
        SectionPos recentlyUsed = new SectionPos(0, 0, 0);
        MinecraftMeshGpuUpload first = null;

        for (int index = 0; index < 64; index++) {
            MinecraftMeshGpuUpload upload =
                    MinecraftMeshGpuUpload.forTesting(4, 16L, () -> {});
            if (index == 0) first = upload;
            owner.track(upload, null);
            cache.installPreparedEntry(new SectionPos(index, 0, 0), entry(upload));
        }

        assertTrue(cache.mesh(recentlyUsed) != null); // refresh the LRU position
        MinecraftMeshGpuUpload newest =
                MinecraftMeshGpuUpload.forTesting(4, 16L, () -> {});
        owner.track(newest, null);
        cache.installPreparedEntry(new SectionPos(64, 0, 0), entry(newest));

        assertFalse(first.isClosed());
        assertTrue(owner.allocationCount() == 64);
        assertEquals(64, cache.sectionCount());
        assertEquals(64L * 16L, cache.uploadedBytes());
        cache.clear();
        assertEquals(0, owner.allocationCount());
    }

    @Test
    void byteBudgetEvictsOldestSectionsUntilUniqueGpuBytesAreWithinLimit() {
        MinecraftGpuResourceOwner owner = new MinecraftGpuResourceOwner(() -> {});
        MinecraftSectionGpuCache cache =
                new MinecraftSectionGpuCache(owner, () -> {});
        long allocationBytes = 40L * 1024L * 1024L;
        MinecraftMeshGpuUpload oldest =
                MinecraftMeshGpuUpload.forTesting(4, allocationBytes, () -> {});
        MinecraftMeshGpuUpload newest =
                MinecraftMeshGpuUpload.forTesting(4, allocationBytes, () -> {});
        owner.track(oldest, null);
        cache.installPreparedEntry(new SectionPos(0, 0, 0), entry(oldest));
        owner.track(newest, null);
        cache.installPreparedEntry(new SectionPos(1, 0, 0), entry(newest));

        assertTrue(oldest.isClosed());
        assertFalse(newest.isClosed());
        assertEquals(1, cache.sectionCount());
        assertEquals(allocationBytes, cache.uploadedBytes());
        assertEquals(1, owner.allocationCount());
        cache.clear();
    }

    @Test
    void closeClearsCacheAndClosesGpuOwner() {
        MinecraftGpuResourceOwner owner = new MinecraftGpuResourceOwner(() -> {});
        MinecraftSectionGpuCache cache =
                new MinecraftSectionGpuCache(owner, () -> {});
        cache.rebuild(new SectionPos(0, 0, 0), List.of());

        cache.close();

        assertEquals(0, cache.sectionCount());
        assertEquals(0, cache.materialCount());
        assertTrue(owner.isClosed());
        assertEquals(0, owner.allocationCount());
    }

    private static MinecraftSectionGpuCache.Entry entry(MinecraftMeshGpuUpload upload) {
        EnumMap<ChunkSectionLayer, MinecraftMeshGpuUpload> uploads =
                new EnumMap<>(ChunkSectionLayer.class);
        uploads.put(ChunkSectionLayer.values()[0], upload);
        return new MinecraftSectionGpuCache.Entry(
                new MinecraftBlockModelMesh(List.of()),
                MinecraftFluidSectionMesher.SectionFluidMesh.empty(),
                null,
                Set.of(),
                uploads,
                new int[0]
        );
    }
}
