package dev.radeonium.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns every Radeonium-created terrain vertex upload.
 *
 * <p>All allocation and release operations are render-thread-only. Identical
 * immutable section-layer payloads share one GPU allocation with reference
 * counting. The owner rejects foreign uploads and provides deterministic
 * release and shutdown paths. Minecraft-owned buffers are never accepted.</p>
 */
public final class MinecraftGpuResourceOwner implements AutoCloseable {
    private final Map<MinecraftMeshGpuUpload, Allocation> allocations = new IdentityHashMap<>();
    private final Map<ByteArrayKey, MinecraftMeshGpuUpload> reusableUploads = new HashMap<>();
    private long allocatedBytes;
    private boolean closed;

    public MinecraftMeshGpuUpload upload(
            MinecraftBlockModelMesh mesh,
            int[] materialIndices
    ) {
        return upload(RenderSystem.getDevice(), mesh, materialIndices, List.of());
    }

    public MinecraftMeshGpuUpload upload(
            GpuDevice device,
            MinecraftBlockModelMesh mesh,
            int[] materialIndices,
            List<MinecraftFluidSectionMesher.FluidQuad> fluids
    ) {
        RenderSystem.assertOnRenderThread();
        ensureOpen();
        if (device == null || mesh == null || materialIndices == null || fluids == null) {
            throw new IllegalArgumentException("device, mesh, material indices and fluids are required");
        }

        byte[] payload = MinecraftMeshGpuUpload.encodeLayer(mesh, fluids).vertexBytes();
        ByteArrayKey key = new ByteArrayKey(payload);
        MinecraftMeshGpuUpload reusable = reusableUploads.get(key);
        if (reusable != null && !reusable.isClosed()) {
            Allocation allocation = allocations.get(reusable);
            if (allocation != null) {
                allocation.retain();
                return reusable;
            }
        }

        MinecraftMeshGpuUpload created =
                MinecraftMeshGpuUpload.upload(device, mesh, materialIndices, fluids);
        return track(created, key);
    }

    public MinecraftMeshGpuUpload uploadQuadData(
            GpuDevice device,
            List<MinecraftMeshGpuUpload.QuadData> quads
    ) {
        RenderSystem.assertOnRenderThread();
        ensureOpen();
        return track(MinecraftMeshGpuUpload.uploadQuadData(device, quads), null);
    }

    private MinecraftMeshGpuUpload track(MinecraftMeshGpuUpload upload, ByteArrayKey key) {
        if (upload.isEmpty()) return upload;
        if (allocations.containsKey(upload)) {
            upload.close();
            throw new IllegalStateException("GPU upload is already owned");
        }
        long bytes = upload.byteSize();
        final long nextAllocatedBytes;
        try {
            nextAllocatedBytes = Math.addExact(allocatedBytes, bytes);
        } catch (ArithmeticException overflow) {
            // The GPU buffer already exists; dispose it if accounting cannot accept it.
            upload.close();
            throw overflow;
        }
        Allocation allocation = new Allocation(bytes, key);
        allocations.put(upload, allocation);
        if (key != null) reusableUploads.put(key, upload);
        allocatedBytes = nextAllocatedBytes;
        return upload;
    }

    /**
     * Releases one owner's reference. The GPU allocation is closed only after
     * the final section releases it. Returns false when already released.
     */
    public boolean release(MinecraftMeshGpuUpload upload) {
        RenderSystem.assertOnRenderThread();
        if (upload == null) return false;
        Allocation allocation = allocations.get(upload);
        if (allocation == null) {
            if (upload.isClosed()) return false;
            throw new IllegalArgumentException("GPU upload is not owned by this resource owner");
        }
        if (upload.isClosed()) {
            // A caller closed the exposed upload directly; stop accounting it as live
            // and prevent any later section from reusing the invalid allocation.
            allocations.remove(upload);
            if (allocation.key != null) reusableUploads.remove(allocation.key, upload);
            allocatedBytes -= allocation.bytes;
            return true;
        }
        if (allocation.referenceCount() > 1) {
            allocation.releaseReference();
            return true;
        }

        upload.close();
        allocation.releaseReference();
        allocations.remove(upload);
        if (allocation.key != null) reusableUploads.remove(allocation.key, upload);
        allocatedBytes -= allocation.bytes;
        return true;
    }

    /** Releases all currently owned allocations but keeps the owner reusable. */
    public void releaseAll() {
        RenderSystem.assertOnRenderThread();
        List<MinecraftMeshGpuUpload> pending = new ArrayList<>(allocations.keySet());
        RuntimeException failure = null;
        for (MinecraftMeshGpuUpload upload : pending) {
            try {
                Allocation allocation = allocations.get(upload);
                if (allocation == null) continue;
                upload.close();
                allocations.remove(upload);
                if (allocation.key != null) reusableUploads.remove(allocation.key, upload);
                allocatedBytes -= allocation.bytes;
            } catch (RuntimeException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
        }
        if (allocations.isEmpty()) reusableUploads.clear();
        if (failure != null) throw failure;
    }

    public int allocationCount() {
        return allocations.size();
    }

    public long allocatedBytes() {
        return allocatedBytes;
    }

    public boolean isClosed() {
        return closed;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("GPU resource owner is closed");
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        releaseAll();
        closed = allocations.isEmpty();
    }

    static final class Allocation {
        private final long bytes;
        private final ByteArrayKey key;
        private int references = 1;

        Allocation(long bytes, ByteArrayKey key) {
            if (bytes < 0) throw new IllegalArgumentException("bytes cannot be negative");
            this.bytes = bytes;
            this.key = key;
        }

        void retain() {
            if (references <= 0) throw new IllegalStateException("GPU allocation is already released");
            references = Math.addExact(references, 1);
        }

        boolean releaseReference() {
            if (references <= 0) throw new IllegalStateException("GPU allocation reference underflow");
            references--;
            return references == 0;
        }

        long bytes() {
            return bytes;
        }

        int referenceCount() {
            return references;
        }
    }

    static final class ByteArrayKey {
        private final byte[] bytes;
        private final int hash;

        ByteArrayKey(byte[] bytes) {
            this.bytes = bytes.clone();
            this.hash = Arrays.hashCode(this.bytes);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ByteArrayKey key && Arrays.equals(bytes, key.bytes);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
