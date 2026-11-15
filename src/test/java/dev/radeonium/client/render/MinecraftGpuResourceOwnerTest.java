package dev.radeonium.client.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class MinecraftGpuResourceOwnerTest {
    @Test
    void startsWithNoOwnedGpuAllocations() {
        MinecraftGpuResourceOwner owner = new MinecraftGpuResourceOwner();

        assertEquals(0, owner.allocationCount());
        assertEquals(0L, owner.allocatedBytes());
        assertFalse(owner.isClosed());
    }

    @Test
    void payloadKeysUseContentEqualityAndDefensiveCopies() {
        byte[] source = new byte[] {1, 2, 3, 4};
        MinecraftGpuResourceOwner.ByteArrayKey first = new MinecraftGpuResourceOwner.ByteArrayKey(source);
        source[0] = 9;
        MinecraftGpuResourceOwner.ByteArrayKey same = new MinecraftGpuResourceOwner.ByteArrayKey(new byte[] {1, 2, 3, 4});
        MinecraftGpuResourceOwner.ByteArrayKey different = new MinecraftGpuResourceOwner.ByteArrayKey(new byte[] {1, 2, 3, 5});

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, different);
    }
    @Test
    void sharedAllocationsReleaseOnlyAfterTheFinalReference() {
        MinecraftGpuResourceOwner.Allocation allocation =
                new MinecraftGpuResourceOwner.Allocation(128L, null);

        allocation.retain();
        assertEquals(2, allocation.referenceCount());
        assertFalse(allocation.releaseReference());
        assertEquals(1, allocation.referenceCount());
        org.junit.jupiter.api.Assertions.assertTrue(allocation.releaseReference());
        assertEquals(0, allocation.referenceCount());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, allocation::retain);
    }

}
