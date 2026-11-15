package dev.radeonium.client.render;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.blaze3d.vertex.MeshData;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;

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
    private static final int MAX_ACTIVE_CAPTURES = 256;
    private static final long MAX_BYTES = 64L * 1024L * 1024L;

    private static final LinkedHashMap<SectionPos, Snapshot> SNAPSHOTS =
            new LinkedHashMap<>(32, 0.75f, true);
    private static long retainedBytes;
    private static long nextGeneration;
    private static final Map<SectionPos, Long> CAPTURE_GENERATIONS = new HashMap<>();
    private static final ThreadLocal<Map<SectionPos, Long>> ACTIVE_CAPTURES =
            ThreadLocal.withInitial(HashMap::new);

    private MinecraftCompiledGeometryStore() {}

    /** Called at SectionCompiler entry on the worker thread. */
    public static synchronized void beginCapture(SectionPos position) {
        if (position == null) return;
        long generation = ++nextGeneration;
        CAPTURE_GENERATIONS.put(position, generation);
        Map<SectionPos, Long> active = ACTIVE_CAPTURES.get();
        active.put(position, generation);

        // Failed/aborted compiler invocations never reach the RETURN injection.
        // Bound both registries so those abandoned tokens cannot grow forever.
        while (CAPTURE_GENERATIONS.size() > MAX_ACTIVE_CAPTURES) {
            SectionPos oldest = CAPTURE_GENERATIONS.keySet().iterator().next();
            CAPTURE_GENERATIONS.remove(oldest);
        }
        while (active.size() > MAX_ACTIVE_CAPTURES) {
            SectionPos oldest = active.keySet().iterator().next();
            Long abandonedGeneration = active.remove(oldest);
            if (Objects.equals(CAPTURE_GENERATIONS.get(oldest), abandonedGeneration)) {
                CAPTURE_GENERATIONS.remove(oldest);
            }
        }
    }

    /**
     * Completes the current worker-thread capture only if no newer compile for
     * this section has started in the meantime.
     */
    public static synchronized Snapshot captureCurrent(
            SectionPos position, SectionCompiler.Results results
    ) {
        if (position == null || results == null) return null;
        Long generation = ACTIVE_CAPTURES.get().remove(position);
        if (generation == null
                || !Objects.equals(CAPTURE_GENERATIONS.get(position), generation)) {
            return null;
        }
        Snapshot snapshot = capture(position, results, generation);
        CAPTURE_GENERATIONS.remove(position);
        return snapshot;
    }

    private static synchronized Snapshot capture(
            SectionPos position, SectionCompiler.Results results, long generation
    ) {
        if (position == null || results == null
                || !Objects.equals(CAPTURE_GENERATIONS.get(position), generation)) return null;

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
            if (draw.vertexCount() < 0 || draw.indexCount() < 0
                    || draw.vertexCount() == 0
                    || (draw.indexCount() > 0 && indexBuffer == null)) {
                continue;
            }

            layers.put(entry.getKey(), new LayerData(
                    vertices,
                    indices,
                    draw.format().toString(),
                    draw.primitiveTopology().toString(),
                    draw.indexType(),
                    draw.vertexCount(),
                    draw.indexCount()
            ));
        }

        Snapshot replacement = new Snapshot(position, layers, System.nanoTime(), generation);
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
        CAPTURE_GENERATIONS.put(position, ++nextGeneration);
        Snapshot removed = SNAPSHOTS.remove(position);
        if (removed != null) retainedBytes -= removed.byteSize();
    }

    public static synchronized void clear() {
        SNAPSHOTS.clear();
        CAPTURE_GENERATIONS.clear();
        ACTIVE_CAPTURES.remove();
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
            long capturedAtNanos,
            long generation
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
            IndexType indexType,
            int vertexCount,
            int indexCount
    ) {
        public LayerData {
            if (vertexBytes == null || indexBytes == null
                    || vertexFormat == null || topology == null
                    || vertexCount < 0 || indexCount < 0) {
                throw new IllegalArgumentException("Compiled layer metadata is invalid");
            }
            if (vertexCount == 0 && (vertexBytes.length != 0 || indexCount != 0)) {
                throw new IllegalArgumentException("An empty compiled layer cannot contain geometry");
            }
            if (vertexCount > 0 && vertexBytes.length == 0) {
                throw new IllegalArgumentException("A non-empty compiled layer needs vertex bytes");
            }
            if (indexCount > 0 && (indexType == null
                    || indexBytes.length < (long) indexCount * indexType.bytes)) {
                throw new IllegalArgumentException("Compiled index bytes do not match index metadata");
            }
            if (indexCount == 0 && indexBytes.length != 0) {
                throw new IllegalArgumentException("Index bytes require a positive index count");
            }
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
