package dev.radeonium.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.device.GpuDevice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

/**
 * Uploads extracted Minecraft block-model geometry into a RenderPearl vertex
 * buffer. Call upload on Minecraft's render thread: GPU resources are owned by
 * the active RenderPearl device and must not be created by meshing workers.
 *
 * Vertex format (40-byte stride): float3 model position, packed UV (two words),
 * captured ARGB tint, packed block/sky light, per-vertex AO color and lightmap.
 * Material/shader binding and
 * terrain draw submission are intentionally separate later stages.
 */
public final class MinecraftMeshGpuUpload implements AutoCloseable {
    public static final int POSITION_OFFSET_BYTES = 0;
    public static final int UV_LOW_OFFSET_BYTES = 12;
    public static final int UV_HIGH_OFFSET_BYTES = 16;
    public static final int TINT_OFFSET_BYTES = 20;
    public static final int BLOCK_LIGHT_OFFSET_BYTES = 24;
    public static final int AO_COLOR_OFFSET_BYTES = 28;
    public static final int VERTEX_LIGHT_OFFSET_BYTES = 32;
    public static final int MATERIAL_INDEX_OFFSET_BYTES = 36;
    public static final int VERTEX_STRIDE_BYTES = 40;

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
        if (mesh == null) throw new IllegalArgumentException("mesh cannot be null");
        int[] defaultMaterials = new int[mesh.quadCount()];
        Arrays.fill(defaultMaterials, -1);
        return upload(device, mesh, defaultMaterials);
    }


    /** Uploads with the active RenderPearl device and per-quad material indices. */
    public static MinecraftMeshGpuUpload upload(
            MinecraftBlockModelMesh mesh, int[] materialIndices
    ) {
        return upload(RenderSystem.getDevice(), mesh, materialIndices);
    }

    /** Uploads one material-table index per quad into every emitted vertex. */
    public static MinecraftMeshGpuUpload upload(
            GpuDevice device, MinecraftBlockModelMesh mesh, int[] materialIndices
    ) {
        return upload(device, mesh, materialIndices, List.of());
    }

    /**
     * Uploads block-model and Minecraft-FluidRenderer geometry into one
     * layer buffer. Fluid vertices already contain vanilla's UV, tint and
     * light results, so they use the reserved material index -1.
     */
    public static MinecraftMeshGpuUpload upload(
            GpuDevice device,
            MinecraftBlockModelMesh mesh,
            int[] materialIndices,
            List<MinecraftFluidSectionMesher.FluidQuad> fluidQuads
    ) {
        RenderSystem.assertOnRenderThread();
        if (device == null) throw new IllegalArgumentException("device cannot be null");
        if (mesh == null || materialIndices == null || materialIndices.length != mesh.quadCount()
                || fluidQuads == null) {
            throw new IllegalArgumentException("mesh, material indices and fluid quads are required");
        }

        int totalQuads = Math.addExact(mesh.quadCount(), fluidQuads.size());
        int vertexCount = Math.multiplyExact(totalQuads, 6);
        if (vertexCount == 0) return new MinecraftMeshGpuUpload(null, 0);

        int byteCount = Math.multiplyExact(vertexCount, VERTEX_STRIDE_BYTES);
        ByteBuffer data = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder());
        for (int i = 0; i < mesh.quadCount(); i++) {
            mesh.quads().get(i).writeTriangleVertices(data, materialIndices[i]);
        }
        for (MinecraftFluidSectionMesher.FluidQuad quad : fluidQuads) {
            writeFluidTriangleVertices(data, quad);
        }

        data.flip();
        GpuBuffer buffer = device.createBuffer(
                () -> "Radeonium Minecraft block and fluid section vertices",
                GpuBuffer.USAGE_VERTEX,
                data
        );
        return new MinecraftMeshGpuUpload(buffer, vertexCount);
    }

    private static void writeFluidTriangleVertices(
            ByteBuffer target, MinecraftFluidSectionMesher.FluidQuad quad
    ) {
        float[] positions = quad.positions();
        float[] uv = quad.uv();
        int[] colors = quad.colors();
        int[] light = quad.lightCoords();
        int[] order = {0, 1, 2, 0, 2, 3};
        for (int vertex : order) {
            int positionOffset = vertex * 3;
            int uvOffset = vertex * 2;
            target.putFloat(positions[positionOffset]);
            target.putFloat(positions[positionOffset + 1]);
            target.putFloat(positions[positionOffset + 2]);
            target.putInt(Float.floatToRawIntBits(uv[uvOffset]));
            target.putInt(Float.floatToRawIntBits(uv[uvOffset + 1]));
            target.putInt(-1);
            target.putInt(light[vertex]);
            target.putInt(colors[vertex]);
            target.putInt(light[vertex]);
            target.putInt(-1);
        }
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
        RenderSystem.assertOnRenderThread();
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
        RenderSystem.assertOnRenderThread();
        if (vertexBuffer != null && !vertexBuffer.isClosed()) {
            vertexBuffer.close();
        }
    }
}
