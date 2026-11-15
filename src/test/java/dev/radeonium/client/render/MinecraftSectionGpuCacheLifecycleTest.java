package dev.radeonium.client.render;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
