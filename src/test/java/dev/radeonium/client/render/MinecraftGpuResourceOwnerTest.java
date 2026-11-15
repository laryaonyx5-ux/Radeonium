package dev.radeonium.client.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
