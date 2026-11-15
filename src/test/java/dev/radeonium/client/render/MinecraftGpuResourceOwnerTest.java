package dev.radeonium.client.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MinecraftGpuResourceOwnerTest {
    @Test
    void startsWithNoOwnedGpuAllocations() {
        MinecraftGpuResourceOwner owner = new MinecraftGpuResourceOwner();

        assertEquals(0, owner.allocationCount());
        assertEquals(0L, owner.allocatedBytes());
        assertFalse(owner.isClosed());
    }
}
