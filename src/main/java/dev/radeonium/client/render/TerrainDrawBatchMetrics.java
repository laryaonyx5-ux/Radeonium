package dev.radeonium.client.render;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Render-thread frame metrics used to evaluate terrain draw batching.
 *
 * Buffers are compared by identity, not equals(): two GPU handles are
 * interchangeable only when they are the exact same bound resource.
 * This class measures the current submission stream; it does not reorder
 * translucent draws or merge GPU resources.
 */
public final class TerrainDrawBatchMetrics {
    public record LayerSnapshot(long draws, long uniqueVertexBuffers, long vertexBufferBinds) {
        public long potentialRunsSaved() {
            return Math.max(0L, draws - vertexBufferBinds);
        }
    }

    public record Snapshot(Map<ChunkSectionLayer, LayerSnapshot> layers) {
        public Snapshot {
            EnumMap<ChunkSectionLayer, LayerSnapshot> copy =
                    new EnumMap<>(ChunkSectionLayer.class);
            copy.putAll(layers);
            layers = Collections.unmodifiableMap(copy);
        }

        public long totalDraws() {
            return layers.values().stream().mapToLong(LayerSnapshot::draws).sum();
        }

        public long totalVertexBufferBinds() {
            return layers.values().stream().mapToLong(LayerSnapshot::vertexBufferBinds).sum();
        }
    }

    private final EnumMap<ChunkSectionLayer, MutableLayer> layers =
            new EnumMap<>(ChunkSectionLayer.class);

    public TerrainDrawBatchMetrics() {
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            layers.put(layer, new MutableLayer());
        }
    }

    /** Starts a new frame measurement without changing cumulative draw diagnostics. */
    public synchronized void beginFrame() {
        layers.values().forEach(MutableLayer::clear);
    }

    /**
     * Records one draw in submission order. A null buffer is counted as a draw
     * but not as a bind, because no reliable resource identity was supplied.
     */
    public synchronized void recordDraw(ChunkSectionLayer layer, Object vertexBuffer) {
        if (layer == null) throw new IllegalArgumentException("layer is required");
        MutableLayer stats = layers.get(layer);
        stats.draws++;
        if (vertexBuffer == null) return;
        stats.uniqueBuffers.add(vertexBuffer);
        if (stats.lastBuffer != vertexBuffer) {
            stats.vertexBufferBinds++;
            stats.lastBuffer = vertexBuffer;
        }
    }

    public synchronized Snapshot snapshot() {
        EnumMap<ChunkSectionLayer, LayerSnapshot> result =
                new EnumMap<>(ChunkSectionLayer.class);
        for (Map.Entry<ChunkSectionLayer, MutableLayer> entry : layers.entrySet()) {
            MutableLayer value = entry.getValue();
            result.put(entry.getKey(), new LayerSnapshot(
                    value.draws, value.uniqueBuffers.size(), value.vertexBufferBinds));
        }
        return new Snapshot(result);
    }

    private static final class MutableLayer {
        private final Set<Object> uniqueBuffers =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private long draws;
        private long vertexBufferBinds;
        private Object lastBuffer;

        private void clear() {
            draws = 0L;
            vertexBufferBinds = 0L;
            lastBuffer = null;
            uniqueBuffers.clear();
        }
    }
}
