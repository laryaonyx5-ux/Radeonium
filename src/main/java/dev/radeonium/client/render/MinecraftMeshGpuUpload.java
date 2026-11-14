package dev.radeonium.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.device.GpuDevice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Uploads extracted Minecraft block-model geometry into a RenderPearl vertex
 * buffer. Call upload on Minecraft's render thread: GPU resources are owned by
 * the active RenderPearl device and must not be created by meshing workers.
 *
 * Vertex format (20-byte stride): float3 model position, then the low and high
 * 32-bit words of Minecraft's packed UV value. Material/shader binding and
 * terrain draw submission are intentionally separate later stages.
 */
public final class MinecraftMeshGpuUpload implements AutoCloseable {
    public static final int VERTEX_STRIDE_BYTES = 20;

    private final GpuBuffer vertexBuffer;
    private final int vertexCount;

    private MinecraftMeshGpuUpload(GpuBuffer vertexBuffer, int vertexCount) {
        this.vertexBuffer = vertexBuffer;
        this.vertexCount = vertexCount;
    }

    /**
     * Uploads through the currently active Minecraft RenderPearl device.
     * Must be called on the render thread.
     */
    public static MinecraftMeshGpuUpload upload(MinecraftBlockModelMesh mesh) {
        return upload(RenderSystem.getDevice(), mesh);
    }

    /**
     * Device-explicit overload for controlled integration and testing.
     * Must be called on the render thread associated with {@code device}.
     */
    public static MinecraftMeshGpuUpload upload(
            GpuDevice device,
            MinecraftBlockModelMesh mesh
    ) {
        if (device == null) throw new IllegalArgumentException("device cannot be null");
        if (mesh == null) throw new IllegalArgumentException("mesh cannot be null");

        int vertexCount = Math.multiplyExact(mesh.quadCount(), 6);
        if (vertexCount == 0) {
            return new MinecraftMeshGpuUpload(null, 0);
        }

        int byteCount = Math.multiplyExact(vertexCount, VERTEX_STRIDE_BYTES);
        ByteBuffer data = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder());

        for (MinecraftBlockModelMesh.Quad quad : mesh.quads()) {
            quad.writeTriangleVertices(data);
        }

        data.flip();
        GpuBuffer buffer = device.createBuffer(
                () -> "Radeonium Minecraft block-model vertices",
                GpuBuffer.USAGE_VERTEX,
                data
        );
        return new MinecraftMeshGpuUpload(buffer, vertexCount);
    }

    public boolean isEmpty() {
        return vertexCount == 0;
    }

    public int vertexCount() {
        return vertexCount;
    }

    public long byteSize() {
        return vertexBuffer == null ? 0L : vertexBuffer.size();
    }

    /**
     * Binds this upload to an already-open RenderPearl pass and submits its
     * triangle-list vertices. The caller owns the pass, pipeline, uniforms,
     * render target and draw ordering. This method never opens or submits a pass.
     */
    public void drawInto(RenderPass pass) {
        if (pass == null) throw new IllegalArgumentException("pass cannot be null");
        if (isEmpty()) return;
        pass.setVertexBuffer(0, vertexBufferSlice());
        pass.draw(vertexCount, 1, 0, 0);
    }

    /**
     * Returns the complete vertex-buffer slice for a future RenderPearl pass.
     * The returned slice is valid only while this upload remains open.
     */
    public GpuBufferSlice vertexBufferSlice() {
        if (vertexBuffer == null) {
            throw new IllegalStateException("An empty mesh has no GPU vertex buffer");
        }
        if (vertexBuffer.isClosed()) {
            throw new IllegalStateException("GPU vertex buffer is already closed");
        }
        return vertexBuffer.slice(0L, vertexBuffer.size());
    }

    @Override
    public void close() {
        if (vertexBuffer != null && !vertexBuffer.isClosed()) {
            vertexBuffer.close();
        }
    }
}
