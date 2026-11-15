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
 * Vertex format matches Minecraft's DefaultVertexFormat.BLOCK (28-byte stride):
 * float3 position, packed RGBA color, float2 atlas UV and packed block/sky light.
 * Quads are retained as four vertices and use Minecraft's sequential QUADS index buffer.
 */
public final class MinecraftMeshGpuUpload implements AutoCloseable {
    public static final int POSITION_OFFSET_BYTES = 0;
    public static final int COLOR_OFFSET_BYTES = 12;
    public static final int UV_OFFSET_BYTES = 16;
    public static final int LIGHT_OFFSET_BYTES = 24;
    public static final int VERTEX_STRIDE_BYTES = 28;

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
        int vertexCount = Math.multiplyExact(totalQuads, 4);
        if (vertexCount == 0) return new MinecraftMeshGpuUpload(null, 0);

        int byteCount = Math.multiplyExact(vertexCount, VERTEX_STRIDE_BYTES);
        ByteBuffer data = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder());
        for (int i = 0; i < mesh.quadCount(); i++) {
            mesh.quads().get(i).writeBlockVertices(data);
        }
        for (MinecraftFluidSectionMesher.FluidQuad quad : fluidQuads) {
            writeFluidBlockVertices(data, quad);
        }

        data.flip();
        GpuBuffer buffer = device.createBuffer(
                () -> "Radeonium Minecraft block and fluid section vertices",
                GpuBuffer.USAGE_VERTEX,
                data
        );
        return new MinecraftMeshGpuUpload(buffer, vertexCount);
    }


    private static void putRgbaBytes(ByteBuffer target, int argb) {
        target.put((byte) (argb >>> 16));
        target.put((byte) (argb >>> 8));
        target.put((byte) argb);
        target.put((byte) (argb >>> 24));
    }

    private static void writeFluidBlockVertices(
            ByteBuffer target, MinecraftFluidSectionMesher.FluidQuad quad
    ) {
        float[] positions = quad.positions();
        float[] uv = quad.uv();
        int[] colors = quad.colors();
        int[] light = quad.lightCoords();
        for (int vertex = 0; vertex < 4; vertex++) {
            int positionOffset = vertex * 3;
            int uvOffset = vertex * 2;
            target.putFloat(positions[positionOffset]);
            target.putFloat(positions[positionOffset + 1]);
            target.putFloat(positions[positionOffset + 2]);
            putRgbaBytes(target, colors[vertex]);
            target.putFloat(uv[uvOffset]);
            target.putFloat(uv[uvOffset + 1]);
            target.putShort((short) (light[vertex] & 0xffff));
            target.putShort((short) ((light[vertex] >>> 16) & 0xffff));
        }
    }



    /** Encodes one layer without touching GPU state, for exact vanilla parity checks. */
    public static EncodedLayer encodeLayer(
            MinecraftBlockModelMesh mesh,
            List<MinecraftFluidSectionMesher.FluidQuad> fluidQuads
    ) {
        if (mesh == null || fluidQuads == null) {
            throw new IllegalArgumentException("mesh and fluidQuads are required");
        }
        int totalQuads = Math.addExact(mesh.quadCount(), fluidQuads.size());
        int vertexCount = Math.multiplyExact(totalQuads, 4);
        ByteBuffer data = ByteBuffer.allocate(
                Math.multiplyExact(vertexCount, VERTEX_STRIDE_BYTES)
        ).order(ByteOrder.nativeOrder());
        for (MinecraftBlockModelMesh.Quad quad : mesh.quads()) quad.writeBlockVertices(data);
        for (MinecraftFluidSectionMesher.FluidQuad quad : fluidQuads) writeFluidBlockVertices(data, quad);
        data.flip();
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        return new EncodedLayer(bytes, vertexCount);
    }

    public record EncodedLayer(byte[] vertexBytes, int vertexCount) {
        public EncodedLayer {
            if (vertexBytes == null || vertexCount < 0
                    || vertexBytes.length != (long) vertexCount * VERTEX_STRIDE_BYTES) {
                throw new IllegalArgumentException("encoded layer bytes do not match its vertex count");
            }
            vertexBytes = vertexBytes.clone();
        }
        @Override public byte[] vertexBytes() { return vertexBytes.clone(); }
    }

    /** Uploads a pre-sorted list of model/fluid quads using native BLOCK vertices. */
    public static MinecraftMeshGpuUpload uploadQuadData(
            GpuDevice device, List<QuadData> quads
    ) {
        RenderSystem.assertOnRenderThread();
        if (device == null || quads == null) throw new IllegalArgumentException("device and quads are required");
        int vertexCount = Math.multiplyExact(quads.size(), 4);
        if (vertexCount == 0) return new MinecraftMeshGpuUpload(null, 0);
        ByteBuffer data = ByteBuffer.allocateDirect(
                Math.multiplyExact(vertexCount, VERTEX_STRIDE_BYTES)
        ).order(ByteOrder.nativeOrder());
        for (QuadData quad : quads) writeQuadData(data, quad);
        data.flip();
        GpuBuffer buffer = device.createBuffer(
                () -> "Radeonium sorted translucent section vertices",
                GpuBuffer.USAGE_VERTEX,
                data
        );
        return new MinecraftMeshGpuUpload(buffer, vertexCount);
    }

    private static void writeQuadData(ByteBuffer target, QuadData quad) {
        float[] positions = quad.positions();
        float[] uv = quad.uv();
        int[] colors = quad.colors();
        int[] light = quad.lightCoords();
        for (int vertex = 0; vertex < 4; vertex++) {
            int p = vertex * 3;
            int t = vertex * 2;
            target.putFloat(positions[p]);
            target.putFloat(positions[p + 1]);
            target.putFloat(positions[p + 2]);
            putRgbaBytes(target, colors[vertex]);
            target.putFloat(uv[t]);
            target.putFloat(uv[t + 1]);
            target.putShort((short) (light[vertex] & 0xffff));
            target.putShort((short) ((light[vertex] >>> 16) & 0xffff));
        }
    }

    public static QuadData fromModelQuad(MinecraftBlockModelMesh.Quad quad) {
        if (quad == null) throw new IllegalArgumentException("quad cannot be null");
        long[] packedUv = quad.copyPackedUv();
        float[] uv = new float[8];
        int[] colors = quad.copyVertexColors();
        int tint = quad.tintColor();
        for (int vertex = 0; vertex < 4; vertex++) {
            uv[vertex * 2] = Float.intBitsToFloat((int) packedUv[vertex]);
            uv[vertex * 2 + 1] = Float.intBitsToFloat((int) (packedUv[vertex] >>> 32));
            colors[vertex] = net.minecraft.util.ARGB.multiply(colors[vertex], tint);
        }
        return new QuadData(quad.copyPositions(), uv, colors, quad.copyVertexLightCoords());
    }

    public static QuadData fromFluidQuad(MinecraftFluidSectionMesher.FluidQuad quad) {
        if (quad == null) throw new IllegalArgumentException("quad cannot be null");
        return new QuadData(quad.positions(), quad.uv(), quad.colors(), quad.lightCoords());
    }



    public record QuadData(float[] positions, float[] uv, int[] colors, int[] lightCoords) {
        public QuadData {
            if (positions == null || positions.length != 12 || uv == null || uv.length != 8
                    || colors == null || colors.length != 4 || lightCoords == null || lightCoords.length != 4) {
                throw new IllegalArgumentException("quad data requires four complete vertices");
            }
            positions = positions.clone();
            uv = uv.clone();
            colors = colors.clone();
            lightCoords = lightCoords.clone();
        }
        @Override public float[] positions() { return positions.clone(); }
        @Override public float[] uv() { return uv.clone(); }
        @Override public int[] colors() { return colors.clone(); }
        @Override public int[] lightCoords() { return lightCoords.clone(); }
    }

    public GpuBuffer vertexBuffer() {
        if (vertexBuffer == null || vertexBuffer.isClosed()) {
            throw new IllegalStateException("GPU vertex buffer is not available");
        }
        return vertexBuffer;
    }

    public boolean isEmpty() {
        return vertexCount == 0;
    }

    /** True when this upload has no live Radeonium-owned GPU buffer. */
    public boolean isClosed() {
        return vertexBuffer == null || vertexBuffer.isClosed();
    }

    public int vertexCount() {
        return vertexCount;
    }

    /** Minecraft's sequential QUADS index count for this four-vertex-per-quad buffer. */
    public int indexCount() {
        return Math.multiplyExact(vertexCount / 4, 6);
    }

    public String vertexFormat() {
        return com.mojang.blaze3d.vertex.DefaultVertexFormat.BLOCK.toString();
    }

    public String topology() {
        return com.mojang.renderpearl.api.pipeline.PrimitiveTopology.QUADS.toString();
    }

    public long byteSize() {
        return vertexBuffer == null ? 0L : vertexBuffer.size();
    }

    /**
     * Binds this upload to an already-open RenderPearl pass and submits its
     * four-vertex QUADS geometry. The caller owns the pass, pipeline, uniforms,
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
