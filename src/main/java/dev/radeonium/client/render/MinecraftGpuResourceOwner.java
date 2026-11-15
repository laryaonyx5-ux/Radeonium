package dev.radeonium.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns every Radeonium-created terrain vertex upload.
 *
 * <p>All allocation and release operations are render-thread-only. The owner
 * tracks uploads by identity, prevents foreign/double ownership from silently
 * corrupting accounting, and provides one deterministic shutdown path. It
 * never accepts or closes Minecraft-owned buffers.</p>
 */
public final class MinecraftGpuResourceOwner implements AutoCloseable {
    private final Map<MinecraftMeshGpuUpload, Long> allocations = new IdentityHashMap<>();
    private long allocatedBytes;
    private boolean closed;

    public MinecraftMeshGpuUpload upload(
            MinecraftBlockModelMesh mesh,
            int[] materialIndices
    ) {
        RenderSystem.assertOnRenderThread();
        ensureOpen();
        return track(MinecraftMeshGpuUpload.upload(mesh, materialIndices));
    }

    public MinecraftMeshGpuUpload upload(
            GpuDevice device,
            MinecraftBlockModelMesh mesh,
            int[] materialIndices,
            List<MinecraftFluidSectionMesher.FluidQuad> fluids
    ) {
        RenderSystem.assertOnRenderThread();
        ensureOpen();
        return track(MinecraftMeshGpuUpload.upload(device, mesh, materialIndices, fluids));
    }

    public MinecraftMeshGpuUpload uploadQuadData(
            GpuDevice device,
            List<MinecraftMeshGpuUpload.QuadData> quads
    ) {
        RenderSystem.assertOnRenderThread();
        ensureOpen();
        return track(MinecraftMeshGpuUpload.uploadQuadData(device, quads));
    }

    private MinecraftMeshGpuUpload track(MinecraftMeshGpuUpload upload) {
        long bytes = upload.byteSize();
        if (allocations.put(upload, bytes) != null) {
            upload.close();
            throw new IllegalStateException("GPU upload is already owned");
        }
        allocatedBytes = Math.addExact(allocatedBytes, bytes);
        return upload;
    }

    /**
     * Releases an upload created by this owner. Returns false when it has
     * already been released; foreign uploads are rejected.
     */
    public boolean release(MinecraftMeshGpuUpload upload) {
        RenderSystem.assertOnRenderThread();
        if (upload == null) return false;
        Long bytes = allocations.remove(upload);
        if (bytes == null) {
            if (upload.isClosed()) return false;
            throw new IllegalArgumentException("GPU upload is not owned by this resource owner");
        }
        allocatedBytes -= bytes;
        upload.close();
        return true;
    }

    /** Releases all currently owned uploads but keeps the owner reusable. */
    public void releaseAll() {
        RenderSystem.assertOnRenderThread();
        List<MinecraftMeshGpuUpload> pending = new ArrayList<>(allocations.keySet());
        RuntimeException failure = null;
        for (MinecraftMeshGpuUpload upload : pending) {
            try {
                release(upload);
            } catch (RuntimeException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
        }
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
        closed = true;
    }
}
