package dev.radeonium.client.render;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import com.mojang.blaze3d.vertex.MeshData;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded CPU-side mirror of the actual geometry emitted by Minecraft's
 * SectionCompiler. MeshData owns its backing memory, so every buffer is copied
 * before the compiler result is released or handed to vanilla's upload path.
 *
 * This is an extraction milestone, not a renderer: it deliberately does not
 * replace vanilla submission or upload these bytes to the GPU yet.
 */
public final class MinecraftCompiledGeometryStore {
    private static final int MAX_SECTIONS = 128;
    private static final long MAX_BYTES = 64L * 1024L * 1024L;

    private static final LinkedHashMap<SectionPos, Snapshot> SNAPSHOTS =
            new LinkedHashMap<>(32, 0.75f, true);
    private static long retainedBytes;

    private MinecraftCompiledGeometryStore() {}

    public static synchronized Snapshot capture(SectionPos position, SectionCompiler.Results results) {
        if (position == null || results == null) return null;

        EnumMap<ChunkSectionLayer, LayerData> layers =
                new EnumMap<>(ChunkSectionLayer.class);
        for (Map.Entry<ChunkSectionLayer, MeshData> entry : results.renderedLayers.entrySet()) {
            MeshData mesh = entry.getValue();
            if (mesh == null) continue;

            MeshData.DrawState draw = mesh.drawState();
            byte[] vertices = copy(mesh.vertexBuffer());
            ByteBuffer indexBuffer = mesh.indexBuffer();
            byte[] indices = indexBuffer == null ? new byte[0] : copy(indexBuffer);
            if (vertices.length == 0) continue;

            layers.put(entry.getKey(), new LayerData(
                    vertices,
                    indices,
                    draw.format().toString(),
                    draw.primitiveTopology().toString(),
                    draw.indexType().toString(),
                    draw.vertexCount(),
                    draw.indexCount()
            ));
        }

        Snapshot replacement = new Snapshot(position, layers, System.nanoTime());
        Snapshot previous = SNAPSHOTS.put(position, replacement);
        if (previous != null) retainedBytes -= previous.byteSize();
        retainedBytes += replacement.byteSize();
        evictToBudget();
        return SNAPSHOTS.get(position);
    }

    private static byte[] copy(ByteBuffer source) {
        if (source == null) return new byte[0];
        ByteBuffer view = source.duplicate();
        byte[] result = new byte[view.remaining()];
        view.get(result);
        return result;
    }

    private static void evictToBudget() {
        var iterator = SNAPSHOTS.entrySet().iterator();
        while ((SNAPSHOTS.size() > MAX_SECTIONS || retainedBytes > MAX_BYTES)
                && iterator.hasNext()) {
            Snapshot removed = iterator.next().getValue();
            retainedBytes -= removed.byteSize();
            iterator.remove();
        }
    }

    public static synchronized Snapshot get(SectionPos position) {
        return SNAPSHOTS.get(position);
    }

    /** Prevents delayed render-thread uploads from installing stale worker results. */
    public static synchronized boolean isCurrent(Snapshot snapshot) {
        return snapshot != null && SNAPSHOTS.get(snapshot.position()) == snapshot;
    }

    public static synchronized void remove(SectionPos position) {
        Snapshot removed = SNAPSHOTS.remove(position);
        if (removed != null) retainedBytes -= removed.byteSize();
    }

    public static synchronized void clear() {
        SNAPSHOTS.clear();
        retainedBytes = 0L;
    }

    public static synchronized int sectionCount() {
        return SNAPSHOTS.size();
    }

    public static synchronized long retainedBytes() {
        return retainedBytes;
    }

    public record Snapshot(
            SectionPos position,
            Map<ChunkSectionLayer, LayerData> layers,
            long capturedAtNanos
    ) {
        public Snapshot {
            EnumMap<ChunkSectionLayer, LayerData> copy =
                    new EnumMap<>(ChunkSectionLayer.class);
            copy.putAll(layers);
            layers = Collections.unmodifiableMap(copy);
        }

        public long byteSize() {
            long total = 0L;
            for (LayerData layer : layers.values()) total += layer.byteSize();
            return total;
        }
    }

    public record LayerData(
            byte[] vertexBytes,
            byte[] indexBytes,
            String vertexFormat,
            String topology,
            String indexType,
            int vertexCount,
            int indexCount
    ) {
        public LayerData {
            vertexBytes = vertexBytes.clone();
            indexBytes = indexBytes.clone();
        }

        @Override public byte[] vertexBytes() { return vertexBytes.clone(); }
        @Override public byte[] indexBytes() { return indexBytes.clone(); }

        public long byteSize() {
            return (long) vertexBytes.length + indexBytes.length;
        }
    }
}
