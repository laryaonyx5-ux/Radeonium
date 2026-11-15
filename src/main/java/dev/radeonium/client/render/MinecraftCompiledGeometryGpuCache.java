package dev.radeonium.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.IndexType;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.SectionPos;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Render-thread-owned GPU mirror of Minecraft's actual compiled section meshes.
 * It preserves the original vertex/index byte streams and index type. It does
 * not invent a vertex layout or submit a terrain pass; the eventual pipeline
 * must use the matching vanilla VertexFormat and per-layer render state.
 */
public final class MinecraftCompiledGeometryGpuCache implements AutoCloseable {
    private static final int MAX_SECTIONS = 128;
    private static final long MAX_BYTES = 64L * 1024L * 1024L;

    private final LinkedHashMap<SectionPos, SectionUpload> sections =
            new LinkedHashMap<>(32, 0.75f, true);
    private long uploadedBytes;

    /** Must run on the render thread. Replaces the section only if its CPU snapshot is still current. */
    public synchronized boolean upload(MinecraftCompiledGeometryStore.Snapshot snapshot) {
        RenderSystem.assertOnRenderThread();
        if (!MinecraftCompiledGeometryStore.isCurrent(snapshot)) return false;

        GpuDevice device = RenderSystem.getDevice();
        EnumMap<ChunkSectionLayer, LayerUpload> layers =
                new EnumMap<>(ChunkSectionLayer.class);
        try {
            for (Map.Entry<ChunkSectionLayer, MinecraftCompiledGeometryStore.LayerData> entry
                    : snapshot.layers().entrySet()) {
                MinecraftCompiledGeometryStore.LayerData source = entry.getValue();
                LayerUpload layer = LayerUpload.create(device, entry.getKey(), source);
                if (layer != null) layers.put(entry.getKey(), layer);
            }

            if (!MinecraftCompiledGeometryStore.isCurrent(snapshot)) {
                closeLayers(layers);
                return false;
            }

            SectionUpload replacement = new SectionUpload(snapshot, layers);
            SectionUpload previous = sections.put(snapshot.position(), replacement);
            if (previous != null) {
                uploadedBytes -= previous.byteSize();
                previous.close();
            }
            uploadedBytes += replacement.byteSize();
            evictToBudget();
            return true;
        } catch (RuntimeException | Error failure) {
            closeLayers(layers);
            throw failure;
        }
    }

    private void evictToBudget() {
        var iterator = sections.entrySet().iterator();
        while ((sections.size() > MAX_SECTIONS || uploadedBytes > MAX_BYTES)
                && iterator.hasNext()) {
            SectionUpload removed = iterator.next().getValue();
            uploadedBytes -= removed.byteSize();
            removed.close();
            iterator.remove();
        }
    }

    private static void closeLayers(Map<ChunkSectionLayer, LayerUpload> layers) {
        for (LayerUpload layer : layers.values()) layer.close();
    }

    public synchronized void remove(SectionPos position) {
        RenderSystem.assertOnRenderThread();
        SectionUpload removed = sections.remove(position);
        if (removed != null) {
            uploadedBytes -= removed.byteSize();
            removed.close();
        }
    }

    public synchronized void clear() {
        RenderSystem.assertOnRenderThread();
        for (SectionUpload section : sections.values()) section.close();
        sections.clear();
        uploadedBytes = 0L;
    }

    /**
     * Returns Radeonium-owned buffers only while their source CPU snapshot is
     * still the newest compile for that section. Null means the caller must
     * keep using Minecraft's live buffer as a safe fallback.
     */
    public synchronized LayerBuffers layerBuffers(SectionPos position, ChunkSectionLayer layer) {
        SectionUpload section = sections.get(position);
        if (section == null || !MinecraftCompiledGeometryStore.isCurrent(section.snapshot)) return null;
        LayerUpload upload = section.layers.get(layer);
        if (upload == null || upload.isEmpty()) return null;
        return new LayerBuffers(
                upload.vertices, upload.indices, upload.indexType,
                upload.vertexCount, upload.indexCount, upload.vertexFormat, upload.topology
        );
    }

    public record LayerBuffers(
            GpuBuffer vertexBuffer,
            GpuBuffer indexBuffer,
            IndexType indexType,
            int vertexCount,
            int indexCount,
            String vertexFormat,
            String topology
    ) {}

    public synchronized int sectionCount() { return sections.size(); }
    public synchronized long uploadedBytes() { return uploadedBytes; }

    /** Binds and submits one section's original indexed geometry into a caller-owned pass. */
    public synchronized boolean drawLayer(
            RenderPass pass, SectionPos position, ChunkSectionLayer layer
    ) {
        if (pass == null || position == null || layer == null) {
            throw new IllegalArgumentException("pass, position and layer are required");
        }
        SectionUpload section = sections.get(position);
        if (section == null) return false;
        LayerUpload upload = section.layers.get(layer);
        if (upload == null || upload.isEmpty() || (upload.indexCount > 0 && upload.indices == null)) return false;
        upload.drawInto(pass);
        return true;
    }

    @Override
    public void close() { clear(); }

    private record SectionUpload(
            MinecraftCompiledGeometryStore.Snapshot snapshot,
            EnumMap<ChunkSectionLayer, LayerUpload> layers
    ) {
        private SectionUpload {
            layers = new EnumMap<>(layers);
        }
        private long byteSize() {
            long total = 0L;
            for (LayerUpload layer : layers.values()) total += layer.byteSize();
            return total;
        }
        private void close() { closeLayers(layers); }
    }

    private static final class LayerUpload implements AutoCloseable {
        private final GpuBuffer vertices;
        private final GpuBuffer indices;
        private final IndexType indexType;
        private final int vertexCount;
        private final int indexCount;
        private final String vertexFormat;
        private final String topology;

        private LayerUpload(
                GpuBuffer vertices, GpuBuffer indices, IndexType indexType,
                int vertexCount, int indexCount, String vertexFormat, String topology
        ) {
            this.vertices = vertices;
            this.indices = indices;
            this.indexType = indexType;
            this.vertexCount = vertexCount;
            this.indexCount = indexCount;
            this.vertexFormat = vertexFormat;
            this.topology = topology;
        }

        private static LayerUpload create(
                GpuDevice device,
                ChunkSectionLayer layer,
                MinecraftCompiledGeometryStore.LayerData source
        ) {
            byte[] vertexBytes = source.vertexBytes();
            if (vertexBytes.length == 0 || source.vertexCount() <= 0) return null;

            GpuBuffer vertexBuffer = null;
            GpuBuffer indexBuffer = null;
            try {
                vertexBuffer = device.createBuffer(
                        () -> "Radeonium compiled section " + layer + " vertices",
                        GpuBuffer.USAGE_VERTEX,
                        directBuffer(vertexBytes)
                );

                byte[] indexBytes = source.indexBytes();
                IndexType type = source.indexType();
                if (indexBytes.length > 0 && source.indexCount() > 0) {
                    indexBuffer = device.createBuffer(
                            () -> "Radeonium compiled section " + layer + " indices",
                            GpuBuffer.USAGE_INDEX,
                            directBuffer(indexBytes)
                    );
                }

                return new LayerUpload(
                        vertexBuffer, indexBuffer, type, source.vertexCount(),
                        source.indexCount(), source.vertexFormat(), source.topology()
                );
            } catch (RuntimeException | Error failure) {
                if (indexBuffer != null && !indexBuffer.isClosed()) indexBuffer.close();
                if (vertexBuffer != null && !vertexBuffer.isClosed()) vertexBuffer.close();
                throw failure;
            }
        }

        private static ByteBuffer directBuffer(byte[] bytes) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
            buffer.put(bytes).flip();
            return buffer;
        }

        private boolean isEmpty() {
            return vertices == null || vertices.isClosed()
                    || (indices != null && indices.isClosed());
        }

        private long byteSize() {
            long total = vertices == null || vertices.isClosed() ? 0L : vertices.size();
            if (indices != null && !indices.isClosed()) total += indices.size();
            return total;
        }

        @SuppressWarnings("unused")
        private String vertexFormat() { return vertexFormat; }
        @SuppressWarnings("unused")
        private String topology() { return topology; }

        private void drawInto(RenderPass pass) {
            if (isEmpty()) return;
            pass.setVertexBuffer(0, vertices.slice(0L, vertices.size()));
            if (indexCount > 0) {
                pass.setIndexBuffer(indices, indexType);
                pass.drawIndexed(indexCount, 1, 0, 0, 0);
            } else {
                pass.draw(vertexCount, 1, 0, 0);
            }
        }

        @Override
        public void close() {
            if (indices != null && !indices.isClosed()) indices.close();
            if (vertices != null && !vertices.isClosed()) vertices.close();
        }
    }
}
