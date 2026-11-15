package dev.radeonium.client.render;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void payloadKeysRejectNullInput() {
        assertThrows(IllegalArgumentException.class,
                () -> new MinecraftGpuResourceOwner.ByteArrayKey(null));
    }

    @Test
    void ownerTracksAndReleasesOnlyItsOwnUpload() {
        AtomicInteger closeCalls = new AtomicInteger();
        MinecraftGpuResourceOwner owner = new MinecraftGpuResourceOwner(() -> {});
        MinecraftMeshGpuUpload upload =
                MinecraftMeshGpuUpload.forTesting(4, 112L, closeCalls::incrementAndGet);

        owner.track(upload, null);
        assertEquals(1, owner.allocationCount());
        assertEquals(112L, owner.allocatedBytes());

        assertTrue(owner.release(upload));
        assertEquals(1, closeCalls.get());
        assertEquals(0, owner.allocationCount());
        assertEquals(0L, owner.allocatedBytes());
        assertFalse(owner.release(upload));
        assertEquals(1, closeCalls.get());
    }



    @Test
    void sharedUploadClosesOnlyAfterEveryOwnerReferenceIsReleased() {
        AtomicInteger closeCalls = new AtomicInteger();
        MinecraftGpuResourceOwner owner = new MinecraftGpuResourceOwner(() -> {});
        MinecraftMeshGpuUpload upload =
                MinecraftMeshGpuUpload.forTesting(4, 80L, closeCalls::incrementAndGet);
        owner.track(upload, new MinecraftGpuResourceOwner.ByteArrayKey(new byte[] {7, 8}));

        assertTrue(owner.retain(upload));
        assertEquals(1, owner.allocationCount());
        assertEquals(80L, owner.allocatedBytes());

        assertTrue(owner.release(upload));
        assertEquals(0, closeCalls.get());
        assertEquals(1, owner.allocationCount());
        assertEquals(80L, owner.allocatedBytes());

        assertTrue(owner.release(upload));
        assertEquals(1, closeCalls.get());
        assertEquals(0, owner.allocationCount());
        assertEquals(0L, owner.allocatedBytes());
    }


    @Test
    void externallyClosedSharedUploadIsRemovedFromAccounting() {
        MinecraftGpuResourceOwner owner = new MinecraftGpuResourceOwner(() -> {});
        MinecraftMeshGpuUpload upload =
                MinecraftMeshGpuUpload.forTesting(4, 96L, () -> {});
        owner.track(upload, new MinecraftGpuResourceOwner.ByteArrayKey(new byte[] {3}));
        assertTrue(owner.retain(upload));

        upload.close();
        assertTrue(owner.release(upload));
        assertEquals(0, owner.allocationCount());
        assertEquals(0L, owner.allocatedBytes());
        assertFalse(owner.retain(upload));
        assertFalse(owner.release(upload));
    }

    @Test
    void duplicateTrackingDoesNotCloseAnAlreadyOwnedUpload() {
        AtomicInteger closeCalls = new AtomicInteger();
        MinecraftGpuResourceOwner owner = new MinecraftGpuResourceOwner(() -> {});
        MinecraftMeshGpuUpload upload =
                MinecraftMeshGpuUpload.forTesting(4, 80L, closeCalls::incrementAndGet);
        owner.track(upload, null);

        assertThrows(IllegalStateException.class, () -> owner.track(upload, null));
        assertEquals(0, closeCalls.get());
        assertFalse(upload.isClosed());
        assertEquals(1, owner.allocationCount());
        assertEquals(80L, owner.allocatedBytes());
    }

    @Test
    void trackingRejectsClosedUploadsWithoutCountingThem() {
        MinecraftGpuResourceOwner owner = new MinecraftGpuResourceOwner(() -> {});
        MinecraftMeshGpuUpload upload = MinecraftMeshGpuUpload.forTesting(4, 48L, () -> {});
        upload.close();

        assertThrows(IllegalArgumentException.class, () -> owner.track(upload, null));
        assertEquals(0, owner.allocationCount());
        assertEquals(0L, owner.allocatedBytes());
    }

    @Test
    void accountingOverflowDisposesOnlyTheUntrackedUpload() {
        AtomicInteger firstClose = new AtomicInteger();
        AtomicInteger overflowClose = new AtomicInteger();
        MinecraftGpuResourceOwner owner = new MinecraftGpuResourceOwner(() -> {});
        MinecraftMeshGpuUpload first =
                MinecraftMeshGpuUpload.forTesting(4, Long.MAX_VALUE, firstClose::incrementAndGet);
        MinecraftMeshGpuUpload overflow =
                MinecraftMeshGpuUpload.forTesting(4, 1L, overflowClose::incrementAndGet);

        owner.track(first, null);
        assertThrows(ArithmeticException.class, () -> owner.track(overflow, null));

        assertEquals(1, owner.allocationCount());
        assertEquals(Long.MAX_VALUE, owner.allocatedBytes());
        assertEquals(0, firstClose.get());
        assertEquals(1, overflowClose.get());
        assertFalse(first.isClosed());
        assertTrue(overflow.isClosed());
    }

    @Test
    void failedDisposalRemainsTrackedAndCanBeRetried() {
        AtomicInteger closeAttempts = new AtomicInteger();
        MinecraftGpuResourceOwner owner = new MinecraftGpuResourceOwner(() -> {});
        MinecraftMeshGpuUpload upload = MinecraftMeshGpuUpload.forTesting(4, 224L, () -> {
            if (closeAttempts.incrementAndGet() == 1) {
                throw new IllegalStateException("simulated disposal failure");
            }
        });
        owner.track(upload, null);

        assertThrows(IllegalStateException.class, owner::releaseAll);
        assertEquals(1, owner.allocationCount());
        assertEquals(224L, owner.allocatedBytes());
        assertFalse(upload.isClosed());

        owner.releaseAll();
        assertEquals(2, closeAttempts.get());
        assertEquals(0, owner.allocationCount());
        assertEquals(0L, owner.allocatedBytes());
        assertTrue(upload.isClosed());
    }

    @Test
    void closeCanBeRetriedAfterDisposalFailure() {
        AtomicInteger closeAttempts = new AtomicInteger();
        MinecraftGpuResourceOwner owner = new MinecraftGpuResourceOwner(() -> {});
        MinecraftMeshGpuUpload upload = MinecraftMeshGpuUpload.forTesting(4, 64L, () -> {
            if (closeAttempts.incrementAndGet() == 1) {
                throw new IllegalStateException("simulated disposal failure");
            }
        });
        owner.track(upload, null);

        assertThrows(IllegalStateException.class, owner::close);
        assertFalse(owner.isClosed());
        owner.close();
        assertTrue(owner.isClosed());
        assertEquals(0, owner.allocationCount());
    }

    @Test
    void allocationsRejectNegativeByteAccounting() {
        assertThrows(IllegalArgumentException.class,
                () -> new MinecraftGpuResourceOwner.Allocation(-1L, null));
        MinecraftGpuResourceOwner.Allocation empty =
                new MinecraftGpuResourceOwner.Allocation(0L, null);
        assertEquals(0L, empty.bytes());
        assertEquals(1, empty.referenceCount());
    }

    @Test
    void sharedAllocationsReleaseOnlyAfterTheFinalReference() {
        MinecraftGpuResourceOwner.Allocation allocation =
                new MinecraftGpuResourceOwner.Allocation(128L, null);

        allocation.retain();
        assertEquals(2, allocation.referenceCount());
        assertFalse(allocation.releaseReference());
        assertEquals(1, allocation.referenceCount());
        assertTrue(allocation.releaseReference());
        assertEquals(0, allocation.referenceCount());
        assertThrows(IllegalStateException.class, allocation::retain);
        assertThrows(IllegalStateException.class, allocation::releaseReference);
    }

}
