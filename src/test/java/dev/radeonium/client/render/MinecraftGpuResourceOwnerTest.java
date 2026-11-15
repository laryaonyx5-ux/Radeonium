package dev.radeonium.client.render;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftGpuResourceOwnerTest {
    @Test
    void startsWithNoOwnedGpuAllocations() {
        MinecraftGpuResourceOwner owner = new MinecraftGpuResourceOwner(() -> {});

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
    void ownerRejectsLiveUploadsCreatedElsewhere() {
        MinecraftGpuResourceOwner owner = new MinecraftGpuResourceOwner(() -> {});
        MinecraftMeshGpuUpload foreign =
                MinecraftMeshGpuUpload.forTesting(4, 32L, () -> {});

        assertThrows(IllegalArgumentException.class, () -> owner.release(foreign));
        assertFalse(foreign.isClosed());
        assertEquals(0, owner.allocationCount());
        assertEquals(0L, owner.allocatedBytes());
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
    void identicalPayloadKeysReuseAndRetainTheExistingAllocation() {
        AtomicInteger closeCalls = new AtomicInteger();
        MinecraftGpuResourceOwner owner = new MinecraftGpuResourceOwner(() -> {});
        MinecraftMeshGpuUpload original =
                MinecraftMeshGpuUpload.forTesting(4, 80L, closeCalls::incrementAndGet);
        owner.track(original, new MinecraftGpuResourceOwner.ByteArrayKey(new byte[] {4, 5, 6}));

        MinecraftMeshGpuUpload reused =
                owner.findReusable(new MinecraftGpuResourceOwner.ByteArrayKey(new byte[] {4, 5, 6}));
        assertTrue(reused == original);
        assertEquals(1, owner.allocationCount());
        assertEquals(80L, owner.allocatedBytes());

        assertTrue(owner.release(reused));
        assertEquals(0, closeCalls.get());
        assertTrue(owner.release(original));
        assertEquals(1, closeCalls.get());
        assertEquals(0, owner.allocationCount());
    }


    @Test
    void getOrCreateInvokesUploadFactoryOnlyForAContentCacheMiss() {
        AtomicInteger factoryCalls = new AtomicInteger();
        AtomicInteger closeCalls = new AtomicInteger();
        MinecraftGpuResourceOwner owner = new MinecraftGpuResourceOwner(() -> {});
        MinecraftGpuResourceOwner.ByteArrayKey firstKey =
                new MinecraftGpuResourceOwner.ByteArrayKey(new byte[] {9, 8, 7});
        MinecraftGpuResourceOwner.ByteArrayKey equalKey =
                new MinecraftGpuResourceOwner.ByteArrayKey(new byte[] {9, 8, 7});

        MinecraftMeshGpuUpload first = owner.getOrCreate(firstKey, () -> {
            factoryCalls.incrementAndGet();
            return MinecraftMeshGpuUpload.forTesting(4, 72L, closeCalls::incrementAndGet);
        });
        MinecraftMeshGpuUpload second = owner.getOrCreate(equalKey, () -> {
            factoryCalls.incrementAndGet();
            return MinecraftMeshGpuUpload.forTesting(4, 72L, closeCalls::incrementAndGet);
        });

        assertTrue(first == second);
        assertEquals(1, factoryCalls.get());
        assertEquals(1, owner.allocationCount());
        assertEquals(72L, owner.allocatedBytes());
        owner.release(first);
        assertEquals(0, closeCalls.get());
        owner.release(second);
        assertEquals(1, closeCalls.get());
    }

    @Test
    void differentPayloadKeysDoNotReuseAnAllocation() {
        MinecraftGpuResourceOwner owner = new MinecraftGpuResourceOwner(() -> {});
        MinecraftMeshGpuUpload original =
                MinecraftMeshGpuUpload.forTesting(4, 80L, () -> {});
        owner.track(original, new MinecraftGpuResourceOwner.ByteArrayKey(new byte[] {1, 2}));

        assertNull(owner.findReusable(
                new MinecraftGpuResourceOwner.ByteArrayKey(new byte[] {1, 3})));
        assertEquals(1, owner.allocationCount());
        owner.release(original);
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
    void accountingOverflowKeepsDisposalFailureAsSuppressed() {
        MinecraftGpuResourceOwner owner = new MinecraftGpuResourceOwner(() -> {});
        owner.track(MinecraftMeshGpuUpload.forTesting(4, Long.MAX_VALUE, () -> {}), null);
        MinecraftMeshGpuUpload overflow = MinecraftMeshGpuUpload.forTesting(4, 1L, () -> {
            throw new IllegalStateException("simulated cleanup failure");
        });

        ArithmeticException failure =
                assertThrows(ArithmeticException.class, () -> owner.track(overflow, null));

        assertEquals(1, failure.getSuppressed().length);
        assertTrue(failure.getSuppressed()[0] instanceof IllegalStateException);
        assertEquals(1, owner.allocationCount());
        assertEquals(Long.MAX_VALUE, owner.allocatedBytes());
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
    void bulkReleaseContinuesAfterOneDisposalFailureAndRemainsReusable() {
        AtomicInteger failedAttempts = new AtomicInteger();
        AtomicInteger successfulClose = new AtomicInteger();
        MinecraftGpuResourceOwner owner = new MinecraftGpuResourceOwner(() -> {});
        MinecraftMeshGpuUpload retryable = MinecraftMeshGpuUpload.forTesting(4, 40L, () -> {
            if (failedAttempts.incrementAndGet() == 1) {
                throw new IllegalStateException("first disposal attempt fails");
            }
        });
        MinecraftMeshGpuUpload independent =
                MinecraftMeshGpuUpload.forTesting(4, 56L, successfulClose::incrementAndGet);
        owner.track(retryable, null);
        owner.track(independent, null);

        assertThrows(IllegalStateException.class, owner::releaseAll);
        assertFalse(retryable.isClosed());
        assertTrue(independent.isClosed());
        assertEquals(1, owner.allocationCount());
        assertEquals(40L, owner.allocatedBytes());

        owner.releaseAll();
        assertEquals(0, owner.allocationCount());
        assertEquals(0L, owner.allocatedBytes());
        assertEquals(2, failedAttempts.get());

        // releaseAll does not close the owner; new allocations remain valid.
        MinecraftMeshGpuUpload later =
                MinecraftMeshGpuUpload.forTesting(4, 24L, () -> {});
        owner.track(later, null);
        assertEquals(1, owner.allocationCount());
        owner.release(later);
        assertEquals(0, owner.allocationCount());
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
    void closedOwnerRejectsNewAllocations() {
        MinecraftGpuResourceOwner owner = new MinecraftGpuResourceOwner(() -> {});
        owner.close();
        MinecraftMeshGpuUpload upload =
                MinecraftMeshGpuUpload.forTesting(4, 16L, () -> {});

        assertThrows(IllegalStateException.class, () -> owner.track(upload, null));
        assertThrows(IllegalStateException.class, () -> owner.getOrCreate(
                new MinecraftGpuResourceOwner.ByteArrayKey(new byte[] {1}), () -> upload));
        assertFalse(upload.isClosed());
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
